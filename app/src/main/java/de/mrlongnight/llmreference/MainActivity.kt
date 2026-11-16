package de.mrlongnight.llmreference

import android.content.Intent
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import de.mrlongnight.llmreference.benchmark.BenchmarkMetadata
import de.mrlongnight.llmreference.benchmark.BenchmarkPlan
import de.mrlongnight.llmreference.benchmark.BenchmarkRunner
import de.mrlongnight.llmreference.engine.api.ChatMessage
import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.ModelDescriptor
import de.mrlongnight.llmreference.engine.api.InferenceBackend
import de.mrlongnight.llmreference.engine.runtime.AndroidLocalInferenceEngine
import de.mrlongnight.llmreference.engine.runtime.EngineDiagnostics
import de.mrlongnight.llmreference.engine.runtime.InferenceEngineException
import java.util.Locale
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Intentionally small reference client. Prompting is raw completion, not a chat-template wrapper.
 * It exercises original file access, isolated model loading, generation, cancellation and CPU
 * benchmark execution on a physical device.
 */
class MainActivity : ComponentActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inferenceEngine: AndroidLocalInferenceEngine? = null
    private var selectedModel: ModelDescriptor? = null
    private var activeOperationJob: Job? = null
    private lateinit var settingsStore: InferenceSettingsStore
    private lateinit var modelLibraryStore: ModelLibraryStore
    private lateinit var inferenceSettings: InferenceAppSettings
    private var modelLoadElapsedMillis: Long? = null
    private var lastBenchmarkCsv: String? = null

    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var promptInput: EditText
    private lateinit var systemPromptInput: EditText
    private lateinit var chatModeButton: Button
    private lateinit var clearChatButton: Button
    private val chatHistory = mutableListOf<ChatMessage>()
    private var chatModeEnabled = false
    private lateinit var outputView: TextView
    private lateinit var selectModelButton: Button
    private lateinit var generateButton: Button
    private lateinit var cancelButton: Button
    private lateinit var utilityButton: Button
    private lateinit var rootScrollView: ScrollView
    private lateinit var rootContent: LinearLayout
    private lateinit var pageTitleView: TextView
    private lateinit var pageSubtitleView: TextView
    private lateinit var modelCard: LinearLayout
    private lateinit var promptCard: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var outputCard: LinearLayout
    private val surfaceCards = mutableListOf<LinearLayout>()
    private var darkModeEnabled = true

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onModelSelected(uri)
    }

    private val diagnosticsExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private val benchmarkExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) exportBenchmark(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkModeEnabled = getSharedPreferences("appearance", MODE_PRIVATE)
            .getBoolean("dark_mode", true)
        settingsStore = InferenceSettingsStore(applicationContext)
        modelLibraryStore = ModelLibraryStore(applicationContext)
        inferenceSettings = settingsStore.load()
        EngineDiagnostics.initialize(applicationContext, "app")
        buildReferenceUi()
        restoreLastModel()
    }

    private fun buildReferenceUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }

        pageTitleView = TextView(this).apply {
            text = "Lokale Modelle testen"
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        content.addView(pageTitleView, matchWidth())

        pageSubtitleView = TextView(this).apply {
            text = "GGUF-Datei laden und testen."
            textSize = 14f
        }
        content.addView(pageSubtitleView, matchWidth(bottom = 20))

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        utilityButton = Button(this).apply {
            text = "Weitere Aktionen"
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { showUtilityMenu(this) }
        }
        utilityRow.addView(utilityButton, matchWidth())
        content.addView(utilityRow, matchWidth(bottom = 20))

        modelCard = sectionCard("Modell")
        modelView = TextView(this).apply {
            text = "Kein Modell."
            textSize = 14f
            setPadding(0, dp(4), 0, dp(12))
        }
        modelCard.addView(modelView, matchWidth())
        selectModelButton = Button(this).apply {
            text = "GGUF-Modell auswählen"
            isAllCaps = false
            minHeight = dp(52)
            setOnClickListener {
                modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
            }
        }
        modelCard.addView(selectModelButton, matchWidth())
        val libraryButton = Button(this).apply {
            text = "Modellbibliothek öffnen"
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { showModelLibrary() }
        }
        modelCard.addView(libraryButton, matchWidth(top = 8))
        content.addView(modelCard, matchWidth(bottom = 16))

        promptCard = sectionCard("Prompt")
        promptCard.addView(TextView(this).apply {
            text = "Prompt / Chat-Eingabe"
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }, matchWidth())
        chatModeButton = Button(this).apply {
            text = "Chat-Template: aus"
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener {
                chatModeEnabled = !chatModeEnabled
                if (!chatModeEnabled) chatHistory.clear()
                text = if (chatModeEnabled) "Chat-Template: an" else "Chat-Template: aus"
                systemPromptInput.visibility = if (chatModeEnabled) View.VISIBLE else View.GONE
                clearChatButton.visibility = if (chatModeEnabled) View.VISIBLE else View.GONE
                promptCard.findViewWithTag<TextView>("prompt-mode-label")?.text =
                    if (chatModeEnabled) "Modellabhängige GGUF-Chatvorlage · Verlauf bleibt bis zum Leeren im Arbeitsspeicher."
                    else "Direkte Vervollständigung · reproduzierbarer Raw-Prompt."
            }
        }
        promptCard.addView(chatModeButton, matchWidth(bottom = 6))
        val modeLabel = TextView(this).apply {
            tag = "prompt-mode-label"
            text = "Direkte Vervollständigung · reproduzierbarer Raw-Prompt."
            textSize = 12f
        }
        promptCard.addView(modeLabel, matchWidth(bottom = 8))
        systemPromptInput = EditText(this).apply {
            hint = "Optionaler System-Prompt"
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            textSize = 14f
            visibility = View.GONE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        promptCard.addView(systemPromptInput, matchWidth(bottom = 8))
        clearChatButton = Button(this).apply {
            text = "Chatverlauf leeren"
            isAllCaps = false
            minHeight = dp(44)
            visibility = View.GONE
            setOnClickListener {
                chatHistory.clear()
                outputView.text = "Chatverlauf geleert."
                statusView.text = "Chat ist bereit."
            }
        }
        promptCard.addView(clearChatButton, matchWidth(bottom = 8))
        promptInput = EditText(this).apply {
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            textSize = 16f
            contentDescription = "Prompt eingeben"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText("Explain in one short paragraph what a local language model does.")
        }
        promptCard.addView(promptInput, matchWidth(bottom = 12))

        generateButton = Button(this).apply {
            text = "Generieren"
            isAllCaps = false
            minHeight = dp(52)
            isEnabled = false
            setOnClickListener { generate() }
        }
        promptCard.addView(generateButton, matchWidth())

        cancelButton = Button(this).apply {
            text = "Lauf abbrechen"
            isAllCaps = false
            minHeight = dp(48)
            visibility = View.GONE
            setOnClickListener {
                EngineDiagnostics.warn("ui", "cancel_requested")
                statusView.text = "Abbruch angefordert …"
                isEnabled = false
                activeOperationJob?.cancel()
            }
        }
        promptCard.addView(cancelButton, matchWidth(top = 8))
        content.addView(promptCard, matchWidth(bottom = 16))

        statusCard = sectionCard("Status")
        statusView = TextView(this).apply {
            text = "Bereit."
            textSize = 14f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(2), 0, 0)
            minHeight = dp(28)
        }
        statusCard.addView(statusView, matchWidth())
        content.addView(statusCard, matchWidth(bottom = 16))

        outputCard = sectionCard("Ausgabe")
        outputView = TextView(this).apply {
            text = "Noch leer."
            textSize = 15f
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            setPadding(0, dp(2), 0, 0)
        }
        outputCard.addView(outputView, matchWidth())
        content.addView(outputCard, matchWidth())

        rootContent = content
        rootScrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            addView(FrameLayout(this@MainActivity).apply {
                addView(
                    content,
                    FrameLayout.LayoutParams(
                        dp((resources.configuration.screenWidthDp - 16).coerceIn(1, 760)),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    ),
                )
            })
        }
        setContentView(rootScrollView)
        rootScrollView.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val safeInsets = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                view.setPadding(0, safeInsets.top, 0, safeInsets.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        rootScrollView.requestApplyInsets()
        applyTheme()
    }

    private fun sectionCard(title: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(18))
            background = roundedSurface(Color.BLACK, Color.DKGRAY)
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(0, 0, 0, dp(10))
        }, matchWidth())
        surfaceCards += card
        return card
    }

    private fun roundedSurface(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun matchWidth(
        top: Int = 0,
        bottom: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            if (top != 0 || bottom != 0) {
                topMargin = dp(top)
                bottomMargin = dp(bottom)
            }
        }

    private fun weightedWidth(
        weight: Float,
        end: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            if (end != 0) marginEnd = dp(end)
        }

    private data class ThemePalette(
        val background: Int,
        val surface: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
        val inputTint: Int,
        val systemBar: Int,
        val cardStroke: Int,
        val primaryTextOnAccent: Int,
    )

    private fun showUtilityMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Abbruch-/Timeout-Selbsttest").apply {
                isEnabled = selectedModel != null && activeOperationJob == null
                setOnMenuItemClickListener { runReliabilitySelfTest(); true }
            }
            menu.add("Quick-Benchmark").apply {
                isEnabled = selectedModel != null && activeOperationJob == null
                setOnMenuItemClickListener { runBenchmark(full = false); true }
            }
            menu.add("Full-Benchmark").apply {
                isEnabled = selectedModel != null && activeOperationJob == null
                setOnMenuItemClickListener { runBenchmark(full = true); true }
            }
            menu.add("CPU/Vulkan-Vergleich").apply {
                isEnabled = selectedModel != null && activeOperationJob == null
                setOnMenuItemClickListener { runBackendComparison(); true }
            }
            menu.add("Benchmark-CSV exportieren").apply {
                isEnabled = !lastBenchmarkCsv.isNullOrBlank() && activeOperationJob == null
                setOnMenuItemClickListener {
                    benchmarkExporter.launch("local-llm-benchmark-${System.currentTimeMillis()}.csv")
                    true
                }
            }
            menu.add("Einstellungen").apply {
                setOnMenuItemClickListener { showInferenceSettings(); true }
            }
            menu.add("Modellbibliothek").apply {
                setOnMenuItemClickListener { showModelLibrary(); true }
            }
            menu.add(if (darkModeEnabled) "Helles Design" else "OLED-Dunkelmodus").apply {
                setOnMenuItemClickListener { toggleDarkMode(); true }
            }
            menu.add("Diagnoseprotokoll exportieren (ZIP)").apply {
                setOnMenuItemClickListener {
                    diagnosticsExporter.launch("local-llm-diagnostics-${System.currentTimeMillis()}.zip")
                    true
                }
            }
            show()
        }
    }

    private fun toggleDarkMode() {
        darkModeEnabled = !darkModeEnabled
        getSharedPreferences("appearance", MODE_PRIVATE).edit()
            .putBoolean("dark_mode", darkModeEnabled)
            .apply()
        EngineDiagnostics.info("ui", "appearance_changed", mapOf("dark_mode" to darkModeEnabled))
        applyTheme()
    }

    private fun applyTheme() {
        val palette = if (darkModeEnabled) {
            ThemePalette(
                background = Color.BLACK,
                surface = Color.rgb(16, 20, 27),
                text = Color.rgb(235, 239, 245),
                secondaryText = Color.rgb(163, 174, 188),
                accent = Color.rgb(158, 198, 255),
                inputTint = Color.rgb(158, 198, 255),
                systemBar = Color.BLACK,
                cardStroke = Color.rgb(43, 51, 63),
                primaryTextOnAccent = Color.rgb(8, 14, 22),
            )
        } else {
            ThemePalette(
                background = Color.rgb(244, 247, 251),
                surface = Color.WHITE,
                text = Color.rgb(24, 34, 48),
                secondaryText = Color.rgb(77, 91, 108),
                accent = Color.rgb(20, 86, 165),
                inputTint = Color.rgb(20, 86, 165),
                systemBar = Color.rgb(235, 240, 246),
                cardStroke = Color.rgb(218, 225, 234),
                primaryTextOnAccent = Color.WHITE,
            )
        }

        rootScrollView.setBackgroundColor(palette.background)
        rootContent.setBackgroundColor(palette.background)
        window.decorView.setBackgroundColor(palette.background)
        surfaceCards.forEach { it.background = roundedSurface(palette.surface, palette.cardStroke) }

        fun tint(view: View) {
            when (view) {
                generateButton -> {
                    generateButton.setTextColor(palette.primaryTextOnAccent)
                    generateButton.backgroundTintList = ColorStateList.valueOf(palette.accent)
                }
                is Button -> {
                    view.setTextColor(palette.accent)
                    view.backgroundTintList = ColorStateList.valueOf(palette.surface)
                }
                is EditText -> {
                    view.setTextColor(palette.text)
                    view.setHintTextColor(palette.secondaryText)
                    view.backgroundTintList = ColorStateList.valueOf(palette.inputTint)
                }
                is TextView -> {
                    view.setTextColor(
                        when (view) {
                            pageSubtitleView, statusView, modelView -> palette.secondaryText
                            else -> palette.text
                        }
                    )
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) tint(view.getChildAt(index))
            }
        }
        tint(rootContent)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        var systemUi = 0
        if (!darkModeEnabled) {
            systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                systemUi = systemUi or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = systemUi
    }

    private fun exportDiagnostics(uri: Uri) {
        setBusy(true, cancelable = false)
        statusView.text = "Diagnoseprotokoll wird exportiert …"
        activeOperationJob = activityScope.launch {
            try {
                val diagnosticsEngine = engine()
                val count = withContext(Dispatchers.IO) {
                    diagnosticsEngine.flushRemoteDiagnostics()
                    EngineDiagnostics.exportZip(applicationContext, uri)
                }
                statusView.text = "Diagnoseprotokoll exportiert · $count Logdateien"
            } catch (error: Exception) {
                EngineDiagnostics.error("ui", "diagnostics_export_failed", mapOf(
                    "error_class" to error.javaClass.simpleName
                ))
                statusView.text = "Log-Export fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private fun onModelSelected(uri: Uri) {
        val metadata = queryModelMetadata(uri)
        if (!metadata.first.endsWith(".gguf", ignoreCase = true)) {
            statusView.text = "Bitte eine GGUF-Datei auswählen."
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers grant only temporary access; the file is still used immediately.
        }
        loadModel(ModelDescriptor(
            displayName = metadata.first,
            sourceUri = uri.toString(),
            sizeBytes = metadata.second,
            contextTokens = inferenceSettings.contextTokens,
            threads = inferenceSettings.threads,
            backend = inferenceSettings.backend,
            gpuLayers = if (inferenceSettings.backend == InferenceBackend.VULKAN) inferenceSettings.gpuLayers else 0,
        ))
    }

    private fun queryModelMetadata(uri: Uri): Pair<String, Long?> {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "selected-model.gguf"
        var sizeBytes: Long? = null
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = it.getString(nameIndex) ?: displayName
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) {
                    sizeBytes = it.getLong(sizeIndex).takeIf { size -> size >= 0L }
                }
            }
        }
        return displayName to sizeBytes
    }

    private fun loadModel(model: ModelDescriptor) {
        activeOperationJob?.cancel()
        setBusy(true, cancelable = false)
        statusView.text = "Prüfe Modell und lade ${model.displayName} …"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val fingerprint = sha256ForUri(model.sourceUri)
                    val resolved = model.copy(sha256 = fingerprint)
                    val started = SystemClock.elapsedRealtime()
                    engine().loadModel(resolved)
                    resolved to (SystemClock.elapsedRealtime() - started)
                }
                selectedModel = prepared.first
                modelLoadElapsedMillis = prepared.second
                modelLibraryStore.add(ModelLibraryEntry(
                    displayName = prepared.first.displayName,
                    sourceUri = prepared.first.sourceUri,
                    sizeBytes = prepared.first.sizeBytes,
                    sha256 = prepared.first.sha256,
                ))
                modelView.text = buildString {
                    append(prepared.first.displayName)
                    prepared.first.sizeBytes?.let { append(" · ").append(formatBytes(it)) }
                    append(" · Kontext ").append(prepared.first.contextTokens)
                    append(" · ").append(prepared.first.backend.name)
                    append(" · SHA256 ").append(prepared.first.sha256?.take(12) ?: "n/a")
                }
                statusView.text = "Modell geladen · ${prepared.first.backend.name} · ${prepared.second} ms"
                EngineDiagnostics.info("model", "reference_model_ready", mapOf(
                    "model_name" to prepared.first.displayName,
                    "model_size_bytes" to (prepared.first.sizeBytes ?: -1L),
                    "model_load_elapsed_ms" to prepared.second,
                    "context_tokens" to prepared.first.contextTokens,
                    "threads" to prepared.first.threads,
                    "backend" to prepared.first.backend.name,
                    "gpu_layers" to prepared.first.gpuLayers,
                    "model_sha256_prefix" to (prepared.first.sha256?.take(16) ?: "unknown"),
                ))
            } catch (cancelled: CancellationException) {
                statusView.text = "Modellladen abgebrochen."
                throw cancelled
            } catch (error: Exception) {
                selectedModel = null
                statusView.text = "Modellladen fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private fun generate() {
        val model = selectedModel ?: run {
            statusView.text = "Zuerst ein GGUF-Modell laden."
            return
        }
        val userPrompt = promptInput.text.toString()
        if (userPrompt.isBlank()) {
            statusView.text = "Prompt darf nicht leer sein."
            return
        }
        val messages = if (chatModeEnabled) {
            buildList {
                systemPromptInput.text.toString().trim().takeIf { it.isNotEmpty() }
                    ?.let { add(ChatMessage("system", it)) }
                addAll(chatHistory)
                add(ChatMessage("user", userPrompt))
            }
        } else emptyList()

        setBusy(true, cancelable = true)
        statusView.text = "Generierung läuft · ${model.displayName}"
        outputView.text = ""
        activeOperationJob = activityScope.launch {
            try {
                val settings = inferenceSettings
                val result = withContext(Dispatchers.IO) {
                    engine().generate(
                        prompt = if (chatModeEnabled) "" else userPrompt,
                        config = GenerationConfig(
                            maxOutputTokens = settings.maxOutputTokens,
                            timeoutMillis = settings.timeoutMillis,
                            temperature = settings.temperature,
                            topP = settings.topP,
                            topK = settings.topK,
                            seed = settings.seed,
                            chatMessages = messages,
                        ),
                    )
                }
                if (chatModeEnabled && result.text.isNotBlank()) {
                    chatHistory += ChatMessage("user", userPrompt)
                    chatHistory += ChatMessage("assistant", result.text)
                    outputView.text = chatHistory.joinToString("\\n\\n") { message ->
                        val speaker = if (message.role == "assistant") "Modell" else "Du"
                        "$speaker: ${message.content}"
                    }
                } else {
                    outputView.text = result.text.ifBlank { "(Keine Textausgabe)" }
                }
                val rate = if (result.generationElapsedMillis > 0L) {
                    result.generatedTokens * 1_000.0 / result.generationElapsedMillis
                } else 0.0
                statusView.text = String.format(
                    Locale.ROOT,
                    "Fertig · Prefill %d Tokens / %d ms · Ausgabe %d Tokens / %d ms · Gesamt %d ms · %.2f Gen tok/s",
                    result.promptTokens, result.promptElapsedMillis,
                    result.generatedTokens, result.generationElapsedMillis,
                    result.elapsedMillis, rate,
                )
            } catch (cancelled: CancellationException) {
                statusView.text = "Generierung abgebrochen."
                throw cancelled
            } catch (error: Exception) {
                statusView.text = "Generierung fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private fun runBenchmark(full: Boolean) {
        val model = selectedModel ?: run {
            statusView.text = "Zuerst ein GGUF-Modell laden."
            return
        }
        val prompt = promptInput.text.toString()
        if (prompt.isBlank()) {
            statusView.text = "Prompt darf nicht leer sein."
            return
        }
        val settings = inferenceSettings
        val warmups = if (full) settings.fullWarmups else settings.quickWarmups
        val measured = if (full) settings.fullMeasurements else settings.quickMeasurements
        setBusy(true, cancelable = true)
        statusView.text = "${if (full) "Full" else "Quick"}-Benchmark · ${warmups} Warmup + ${measured} Messungen"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            try {
                val runner = BenchmarkRunner()
                val metadata = buildBenchmarkMetadata(model, prompt, settings.backend, modelLoadElapsedMillis)
                val report = withContext(Dispatchers.IO) {
                    runner.run(
                        engine = engine(),
                        modelDisplayName = model.displayName,
                        backendLabel = settings.backend.name,
                        plan = BenchmarkPlan(
                            prompt = prompt,
                            maxOutputTokens = settings.maxOutputTokens,
                            warmupRuns = warmups,
                            measuredRuns = measured,
                            timeoutMillis = settings.timeoutMillis,
                            temperature = settings.temperature,
                            topP = settings.topP,
                            topK = settings.topK,
                            seed = settings.seed,
                            metadata = metadata.copy(
                                maxOutputTokens = settings.maxOutputTokens,
                                timeoutMillis = settings.timeoutMillis,
                                temperature = settings.temperature,
                                topP = settings.topP,
                                topK = settings.topK,
                                seed = settings.seed,
                            ),
                        ),
                    )
                }
                lastBenchmarkCsv = report.toCsv()
                displayBenchmarkReport(report)
                statusView.text = "${if (full) "Full" else "Quick"}-Benchmark abgeschlossen · ${report.measuredRuns} Messungen"
            } catch (cancelled: CancellationException) {
                statusView.text = "Benchmark abgebrochen."
                throw cancelled
            } catch (error: Exception) {
                EngineDiagnostics.error("benchmark", "failed", mapOf("error_class" to error.javaClass.simpleName))
                statusView.text = "Benchmark fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private fun runBackendComparison() {
        val original = selectedModel ?: run {
            statusView.text = "Zuerst ein GGUF-Modell laden."
            return
        }
        if (promptInput.text.isNullOrBlank()) {
            statusView.text = "Prompt darf nicht leer sein."
            return
        }
        val prompt = promptInput.text.toString()
        val settings = inferenceSettings
        setBusy(true, cancelable = true)
        statusView.text = "CPU/Vulkan-Vergleich startet …"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            val csvParts = mutableListOf<String>()
            try {
                val runner = BenchmarkRunner()
                val backends = listOf(InferenceBackend.CPU, InferenceBackend.VULKAN)
                for (backend in backends) {
                    val descriptor = original.copy(
                        backend = backend,
                        gpuLayers = if (backend == InferenceBackend.VULKAN) settings.gpuLayers else 0,
                    )
                    val loadStarted = SystemClock.elapsedRealtime()
                    withContext(Dispatchers.IO) { engine().loadModel(descriptor) }
                    val loadMillis = SystemClock.elapsedRealtime() - loadStarted
                    val metadata = buildBenchmarkMetadata(descriptor, prompt, backend, loadMillis)
                    val report = withContext(Dispatchers.IO) {
                        runner.run(
                            engine(), descriptor.displayName, backend.name,
                            BenchmarkPlan(
                                prompt = prompt,
                                maxOutputTokens = settings.maxOutputTokens,
                                warmupRuns = settings.quickWarmups,
                                measuredRuns = settings.quickMeasurements,
                                timeoutMillis = settings.timeoutMillis,
                                temperature = settings.temperature,
                                topP = settings.topP,
                                topK = settings.topK,
                                seed = settings.seed,
                                metadata = metadata,
                            ),
                        )
                    }
                    csvParts += "# ${backend.name}\n" + report.toCsv()
                    EngineDiagnostics.info("benchmark", "backend_comparison_completed", mapOf(
                        "backend" to backend.name,
                        "model_sha256_prefix" to (descriptor.sha256?.take(16) ?: "unknown"),
                        "median_generation_tok_s" to report.medianGenerationTokensPerSecond,
                        "model_load_elapsed_ms" to loadMillis,
                    ))
                }
                lastBenchmarkCsv = csvParts.joinToString("\n")
                outputView.text = "CPU- und Vulkan-Lauf abgeschlossen. Beide CSV-Berichte enthalten identische Prompt-/Modellfingerprints und die jeweiligen Backendparameter."
                statusView.text = "CPU/Vulkan-Vergleich abgeschlossen"
            } catch (cancelled: CancellationException) {
                statusView.text = "CPU/Vulkan-Vergleich abgebrochen."
                throw cancelled
            } catch (error: Exception) {
                csvParts += "# COMPARISON_ERROR\n" + (error.message ?: error.javaClass.simpleName)
                lastBenchmarkCsv = csvParts.joinToString("\n")
                outputView.text = "Vergleich abgebrochen: ${error.message ?: error.javaClass.simpleName}"
                statusView.text = "CPU/Vulkan-Vergleich unvollständig"
            } finally {
                runCatching {
                    withContext(Dispatchers.IO) {
                        engine().loadModel(original)
                    }
                }
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun buildBenchmarkMetadata(
        model: ModelDescriptor,
        prompt: String,
        backend: InferenceBackend,
        loadMillis: Long?,
    ): BenchmarkMetadata {
        val nativeInfo = withContext(Dispatchers.IO) { engine().systemInfo() }
        return BenchmarkMetadata(
            modelFileName = model.displayName,
            modelSha256 = model.sha256.orEmpty(),
            modelSizeBytes = model.sizeBytes,
            contextTokens = model.contextTokens,
            threads = model.threads,
            backend = backend,
            gpuLayers = model.gpuLayers,
            temperature = inferenceSettings.temperature,
            topP = inferenceSettings.topP,
            topK = inferenceSettings.topK,
            seed = inferenceSettings.seed,
            maxOutputTokens = inferenceSettings.maxOutputTokens,
            timeoutMillis = inferenceSettings.timeoutMillis,
            promptSha256 = sha256Text(prompt),
            promptCharacters = prompt.length,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidApi = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.joinToString(","),
            nativeSystemInfo = nativeInfo,
            modelLoadElapsedMillis = loadMillis,
        )
    }

    private fun displayBenchmarkReport(report: de.mrlongnight.llmreference.benchmark.BenchmarkReport) {
        val samples = report.samples.joinToString("\n") { sample ->
            String.format(
                Locale.ROOT,
                "Lauf %d: Prefill %.2f tok/s · Gen %.2f tok/s · Gesamt %.2f tok/s · Prefill %d ms · Gen %d ms · Gesamt %d ms",
                sample.iteration,
                sample.promptTokensPerSecond,
                sample.generationTokensPerSecond,
                sample.wallTokensPerSecond,
                sample.promptElapsedMillis,
                sample.generationElapsedMillis,
                sample.wallElapsedMillis,
            )
        }
        outputView.text = String.format(
            Locale.ROOT,
            "%s · %s\nModell-SHA256: %s\nPrompt-SHA256: %s\nKontext %d · Threads %d · Output %d · Temp %.2f · Top-p %.2f · Top-k %d · Seed %d\nLadezeit: %s ms\n\nPrefill Median %.2f tok/s\nGenerierung Median %.2f tok/s\nGesamt Median %.2f tok/s\n\n%s",
            report.modelDisplayName,
            report.backendLabel,
            report.metadata.modelSha256.take(16),
            report.metadata.promptSha256.take(16),
            report.metadata.contextTokens,
            report.metadata.threads,
            report.metadata.maxOutputTokens,
            report.metadata.temperature,
            report.metadata.topP,
            report.metadata.topK,
            report.metadata.seed,
            report.metadata.modelLoadElapsedMillis?.toString() ?: "n/a",
            report.medianPromptTokensPerSecond,
            report.medianGenerationTokensPerSecond,
            report.medianWallTokensPerSecond,
            samples,
        )
    }

    private fun exportBenchmark(uri: Uri) {
        val csv = lastBenchmarkCsv ?: return
        activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(csv)
                    } ?: error("Benchmark-Ziel kann nicht geschrieben werden.")
                }
                statusView.text = "Benchmark-CSV exportiert."
            } catch (error: Exception) {
                statusView.text = "Benchmark-Export fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    private fun restoreLastModel() {
        val last = modelLibraryStore.last() ?: return
        statusView.text = "Letztes Modell wird wiederhergestellt …"
        loadModel(ModelDescriptor(
            displayName = last.displayName,
            sourceUri = last.sourceUri,
            sizeBytes = last.sizeBytes,
            contextTokens = inferenceSettings.contextTokens,
            threads = inferenceSettings.threads,
            backend = inferenceSettings.backend,
            gpuLayers = if (inferenceSettings.backend == InferenceBackend.VULKAN) inferenceSettings.gpuLayers else 0,
            sha256 = last.sha256,
        ))
    }

    private fun sha256ForUri(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = contentResolver.openInputStream(Uri.parse(source))
            ?: error("GGUF-Datei kann für den Modellfingerprint nicht gelesen werden.")
        input.use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun showModelLibrary() {
        val entries = modelLibraryStore.entries()
        if (entries.isEmpty()) {
            Toast.makeText(this, "Noch keine Modelle gespeichert.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { entry ->
            entry.displayName + (entry.sizeBytes?.let { " · ${formatBytes(it)}" } ?: "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Modellbibliothek")
            .setItems(labels) { _, index ->
                val item = entries[index]
                loadModel(ModelDescriptor(
                    displayName = item.displayName,
                    sourceUri = item.sourceUri,
                    sizeBytes = item.sizeBytes,
                    contextTokens = inferenceSettings.contextTokens,
                    threads = inferenceSettings.threads,
                    backend = inferenceSettings.backend,
                    gpuLayers = if (inferenceSettings.backend == InferenceBackend.VULKAN) inferenceSettings.gpuLayers else 0,
                    sha256 = item.sha256,
                ))
            }
            .setNeutralButton("Eintrag entfernen") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Modell entfernen")
                    .setItems(labels) { _, index ->
                        val entry = entries[index]
                        modelLibraryStore.remove(entry.sourceUri)
                        runCatching {
                            contentResolver.releasePersistableUriPermission(
                                Uri.parse(entry.sourceUri),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        }
                        Toast.makeText(this, "${entry.displayName} entfernt.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun showInferenceSettings() {
        val current = inferenceSettings
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val scroller = ScrollView(this).apply { addView(form) }
        fun field(label: String, value: String, type: Int = InputType.TYPE_CLASS_NUMBER): EditText {
            form.addView(TextView(this).apply { text = label; textSize = 13f; setPadding(0, dp(8), 0, dp(3)) })
            return EditText(this).apply {
                setSingleLine(true)
                inputType = type
                setText(value)
                textSize = 16f
                form.addView(this, matchWidth(bottom = 4))
            }
        }
        val context = field("Kontext-Tokens", current.contextTokens.toString())
        val threads = field("CPU-Threads (0 = llama.cpp Standard)", current.threads.toString())
        val output = field("Maximale Ausgabe-Tokens", current.maxOutputTokens.toString())
        val timeout = field("Timeout in Millisekunden", current.timeoutMillis.toString())
        val temperature = field("Temperatur (0 = Greedy)", current.temperature.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val topP = field("Top-p", current.topP.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val topK = field("Top-k (0 = deaktiviert)", current.topK.toString())
        val seed = field("Sampling-Seed", current.seed.toString())
        val backend = field("Backend: CPU oder VULKAN", current.backend.name, InputType.TYPE_CLASS_TEXT)
        val gpuLayers = field("Vulkan GPU-Layer (-1 = alle)", current.gpuLayers.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
        val quickWarmups = field("Quick Warmups", current.quickWarmups.toString())
        val quickRuns = field("Quick Messläufe", current.quickMeasurements.toString())
        val fullWarmups = field("Full Warmups", current.fullWarmups.toString())
        val fullRuns = field("Full Messläufe", current.fullMeasurements.toString())

        AlertDialog.Builder(this)
            .setTitle("Inference- und Benchmark-Einstellungen")
            .setView(scroller)
            .setPositiveButton("Speichern") { _, _ ->
                try {
                    val parsedBackend = InferenceBackend.valueOf(backend.text.toString().trim().uppercase(Locale.ROOT))
                    val updated = InferenceAppSettings(
                        contextTokens = context.text.toString().toInt().also { require(it in 256..131072) },
                        threads = threads.text.toString().toInt().also { require(it in 0..256) },
                        maxOutputTokens = output.text.toString().toInt().also { require(it in 1..8192 && it <= context.text.toString().toInt() - 32) },
                        timeoutMillis = timeout.text.toString().toLong().also { require(it in 1_000L..600_000L) },
                        temperature = temperature.text.toString().toFloat().also { require(it.isFinite() && it in 0f..2f) },
                        topP = topP.text.toString().toFloat().also { require(it.isFinite() && it > 0f && it <= 1f) },
                        topK = topK.text.toString().toInt().also { require(it in 0..1024) },
                        seed = seed.text.toString().toInt().also { require(it >= 0) },
                        backend = parsedBackend,
                        gpuLayers = gpuLayers.text.toString().toInt().also { require(it == -1 || it in 0..256) },
                        quickWarmups = quickWarmups.text.toString().toInt().also { require(it in 0..10) },
                        quickMeasurements = quickRuns.text.toString().toInt().also { require(it in 1..20) },
                        fullWarmups = fullWarmups.text.toString().toInt().also { require(it in 0..10) },
                        fullMeasurements = fullRuns.text.toString().toInt().also { require(it in 1..50) },
                    )
                    settingsStore.save(updated)
                    inferenceSettings = updated
                    statusView.text = "Einstellungen gespeichert. Ein geladenes Modell muss für Kontext/Backend neu geladen werden."
                    selectedModel?.let { loaded ->
                        loadModel(loaded.copy(
                            contextTokens = updated.contextTokens,
                            threads = updated.threads,
                            backend = updated.backend,
                            gpuLayers = if (updated.backend == InferenceBackend.VULKAN) updated.gpuLayers else 0,
                        ))
                    }
                } catch (error: Exception) {
                    Toast.makeText(this, "Ungültige Einstellung: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    /**
     * Exercises the real Binder/JNI cancellation path against the currently loaded GGUF.
     * This is an on-device diagnostic, not a substitute for a model-independent unit test.
     */
    private fun runReliabilitySelfTest() {
        val model = selectedModel ?: run {
            statusView.text = "Zuerst ein GGUF-Modell laden."
            return
        }
        if (model.contextTokens < 256) {
            statusView.text = "Selbsttest benötigt mindestens 256 Kontext-Tokens."
            return
        }

        setBusy(true, cancelable = true)
        statusView.text = "Abbruch-/Timeout-Selbsttest läuft …"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            val results = mutableListOf<String>()
            try {
                val inference = engine()
                val prefillUnits = (model.contextTokens * 0.70).toInt().coerceIn(128, model.contextTokens - 64)
                val longPrompt = buildString(prefillUnits * 2) {
                    repeat(prefillUnits) { append("a ") }
                }

                results += cancellationProbe(
                    inference = inference,
                    label = "Langprompt-Abbruch",
                    prompt = longPrompt,
                    maxOutputTokens = 1,
                    delayMillis = 750L,
                )
                results += recoveryProbe(inference, "Recovery nach Langprompt-Abbruch")

                results += cancellationProbe(
                    inference = inference,
                    label = "Lange-Generierung-Abbruch",
                    prompt = "Write a very long essay about local language models. Do not stop early.",
                    maxOutputTokens = (model.contextTokens / 2).coerceIn(32, 1024),
                    delayMillis = 3_000L,
                )
                results += recoveryProbe(inference, "Recovery nach Decode-Abbruch")

                val timeoutFailure = try {
                    withContext(Dispatchers.IO) {
                        inference.generate(
                            prompt = "Explain local language model inference in detail.",
                            config = GenerationConfig(
                                maxOutputTokens = (model.contextTokens / 2).coerceIn(32, 1024),
                                timeoutMillis = 1_000L,
                            ),
                        )
                    }
                    null
                } catch (error: InferenceEngineException) {
                    error
                }
                check(timeoutFailure?.message?.contains("timed out", ignoreCase = true) == true) {
                    "Der 1-Sekunden-Timeout wurde nicht ausgelöst."
                }
                results += "Timeout: 1-Sekunden-Deadline ausgelöst"
                EngineDiagnostics.info("reliability_test", "timeout_confirmed", mapOf(
                    "timeout_ms" to 1_000
                ))
                results += recoveryProbe(inference, "Recovery nach Timeout")

                outputView.text = results.joinToString(separator = "\n")
                statusView.text = "Selbsttest abgeschlossen · Abbruch, Timeout und Recovery erfolgreich"
                EngineDiagnostics.info("reliability_test", "completed", mapOf(
                    "checks" to results.size
                ))
            } catch (cancelled: CancellationException) {
                statusView.text = "Abbruch-/Timeout-Selbsttest abgebrochen."
                EngineDiagnostics.warn("reliability_test", "cancelled")
                throw cancelled
            } catch (error: Exception) {
                results += "FEHLER: ${error.message ?: error.javaClass.simpleName}"
                outputView.text = results.joinToString(separator = "\n")
                statusView.text = "Selbsttest fehlgeschlagen · Diagnoseprotokoll exportieren"
                EngineDiagnostics.error("reliability_test", "failed", mapOf(
                    "error_class" to error.javaClass.simpleName
                ))
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private suspend fun cancellationProbe(
        inference: AndroidLocalInferenceEngine,
        label: String,
        prompt: String,
        maxOutputTokens: Int,
        delayMillis: Long,
    ): String = coroutineScope {
        val probe = async(Dispatchers.IO) {
            inference.generate(
                prompt = prompt,
                config = GenerationConfig(
                    maxOutputTokens = maxOutputTokens,
                    timeoutMillis = 600_000L,
                ),
            )
        }

        delay(delayMillis)
        if (probe.isCompleted) {
            val outcome = runCatching { probe.await() }
            throw IllegalStateException(
                "$label: Anfrage endete vor dem geplanten Abbruch" +
                    outcome.exceptionOrNull()?.let { " (${it.javaClass.simpleName})" }.orEmpty()
            )
        }

        probe.cancel(CancellationException("Reliability self-test: $label"))
        withTimeout(15_000L) { probe.join() }
        check(probe.isCancelled) { "$label: Coroutine wurde nicht abgebrochen." }
        EngineDiagnostics.info("reliability_test", "cancellation_confirmed", mapOf(
            "probe" to label,
            "delay_ms" to delayMillis
        ))
        "$label: Abbruchsignal bestätigt"
    }

    private suspend fun recoveryProbe(
        inference: AndroidLocalInferenceEngine,
        label: String,
    ): String {
        val result = withTimeout(60_000L) {
            withContext(Dispatchers.IO) {
                inference.generate(
                    prompt = "Answer briefly: what does a local language model do?",
                    config = GenerationConfig(
                        maxOutputTokens = 8,
                        timeoutMillis = 45_000L,
                    ),
                )
            }
        }
        EngineDiagnostics.info("reliability_test", "recovery_confirmed", mapOf(
            "probe" to label,
            "generated_tokens" to result.generatedTokens,
            "elapsed_ms" to result.elapsedMillis
        ))
        return "$label: Folgegenerierung erfolgreich (${result.generatedTokens} Tokens)"
    }

    private fun engine(): AndroidLocalInferenceEngine =
        inferenceEngine ?: AndroidLocalInferenceEngine(applicationContext).also {
            inferenceEngine = it
        }

    private fun setBusy(busy: Boolean, cancelable: Boolean = false) {
        utilityButton.isEnabled = !busy
        selectModelButton.isEnabled = !busy
        generateButton.isEnabled = !busy && selectedModel != null
        cancelButton.isEnabled = busy && cancelable
        cancelButton.visibility = if (busy && cancelable) View.VISIBLE else View.GONE
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) String.format(Locale.ROOT, "%.2f GiB", gib)
        else String.format(Locale.ROOT, "%.0f MiB", bytes / (1024.0 * 1024.0))
    }

    override fun onDestroy() {
        activeOperationJob?.cancel()
        activityScope.cancel()
        val engineToClose = inferenceEngine
        if (engineToClose != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { engineToClose.unload() }
            }
        }
        super.onDestroy()
    }
}
