package de.mrlongnight.llmreference

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import de.mrlongnight.llmreference.benchmark.BenchmarkPlan
import de.mrlongnight.llmreference.benchmark.BenchmarkRunner
import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.ModelDescriptor
import de.mrlongnight.llmreference.engine.runtime.AndroidLocalInferenceEngine
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private lateinit var statusView: TextView
    private lateinit var modelView: TextView
    private lateinit var promptInput: EditText
    private lateinit var outputView: TextView
    private lateinit var selectModelButton: Button
    private lateinit var generateButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var cancelButton: Button

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onModelSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildReferenceUi()
    }

    private fun buildReferenceUi() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        content.addView(TextView(this).apply {
            text = "Local LLM Reference"
            textSize = 24f
        }, matchWidth())
        content.addView(TextView(this).apply {
            text = "GGUF completion test · CPU reference · isolated inference process"
            textSize = 13f
        }, matchWidth())

        selectModelButton = Button(this).apply {
            text = "GGUF-Modell auswählen"
            setOnClickListener {
                modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
            }
        }
        content.addView(selectModelButton, matchWidth())

        modelView = TextView(this).apply {
            text = "Kein Modell geladen"
            textSize = 14f
        }
        content.addView(modelView, matchWidth())

        content.addView(TextView(this).apply {
            text = "Prompt (direkte Completion, ohne Chat-Template)"
            textSize = 14f
        }, matchWidth())

        promptInput = EditText(this).apply {
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText("Explain in one short paragraph what a local language model does.")
        }
        content.addView(promptInput, matchWidth())

        generateButton = Button(this).apply {
            text = "Generieren (64 Tokens)"
            isEnabled = false
            setOnClickListener { generate() }
        }
        content.addView(generateButton, matchWidth())

        benchmarkButton = Button(this).apply {
            text = "CPU-Benchmark (1 Warmup + 3 Läufe)"
            isEnabled = false
            setOnClickListener { runBenchmark() }
        }
        content.addView(benchmarkButton, matchWidth())

        cancelButton = Button(this).apply {
            text = "Lauf abbrechen"
            isEnabled = false
            setOnClickListener {
                statusView.text = "Abbruch angefordert …"
                isEnabled = false
                activeOperationJob?.cancel()
            }
        }
        content.addView(cancelButton, matchWidth())

        statusView = TextView(this).apply {
            text = "Bereit"
            textSize = 14f
        }
        content.addView(statusView, matchWidth())

        content.addView(TextView(this).apply {
            text = "Ausgabe / Benchmark-Ergebnis"
            textSize = 16f
        }, matchWidth())

        outputView = TextView(this).apply {
            text = "Noch keine Ausgabe"
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(outputView, matchWidth())

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun onModelSelected(uri: Uri) {
        val metadata = queryModelMetadata(uri)
        if (!metadata.first.endsWith(".gguf", ignoreCase = true)) {
            statusView.text = "Bitte eine GGUF-Datei auswählen."
            return
        }

        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The opened descriptor is passed immediately; a provider may not grant persistence.
        }

        val descriptor = ModelDescriptor(
            displayName = metadata.first,
            sourceUri = uri.toString(),
            sizeBytes = metadata.second,
            contextTokens = 2048,
            threads = 0,
        )
        loadModel(descriptor)
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
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) sizeBytes = it.getLong(sizeIndex)
            }
        }
        return displayName to sizeBytes
    }

    private fun loadModel(model: ModelDescriptor) {
        activeOperationJob?.cancel()
        setBusy(true, cancelable = false)
        statusView.text = "Lade ${model.displayName} im isolierten Prozess …"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    engine().loadModel(model)
                }
                selectedModel = model
                modelView.text = model.sizeBytes?.let {
                    "${model.displayName} · ${formatBytes(it)} · Kontext ${model.contextTokens}"
                } ?: "${model.displayName} · Kontext ${model.contextTokens}"
                statusView.text = "Modell geladen · CPU-Referenz"
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
        val prompt = promptInput.text.toString()
        if (prompt.isBlank()) {
            statusView.text = "Prompt darf nicht leer sein."
            return
        }

        setBusy(true, cancelable = true)
        statusView.text = "Generierung läuft · ${model.displayName}"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine().generate(
                        prompt = prompt,
                        config = GenerationConfig(maxOutputTokens = 64, timeoutMillis = 180_000L),
                    )
                }
                outputView.text = result.text.ifBlank { "(Keine Textausgabe)" }
                val generationRate = if (result.generationElapsedMillis > 0L) {
                    result.generatedTokens * 1_000.0 / result.generationElapsedMillis
                } else {
                    0.0
                }
                statusView.text = String.format(
                    Locale.ROOT,
                    "Fertig · Prompt %d Tokens / %d ms · Ausgabe %d Tokens / %d ms · Gesamt %d ms · %.2f Gen tok/s",
                    result.promptTokens,
                    result.promptElapsedMillis,
                    result.generatedTokens,
                    result.generationElapsedMillis,
                    result.elapsedMillis,
                    generationRate,
                )
            } catch (cancelled: CancellationException) {
                statusView.text = "Generierung abgebrochen · native Abbruchanforderung gesendet"
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

    private fun runBenchmark() {
        val model = selectedModel ?: run {
            statusView.text = "Zuerst ein GGUF-Modell laden."
            return
        }
        val prompt = promptInput.text.toString()
        if (prompt.isBlank()) {
            statusView.text = "Prompt darf nicht leer sein."
            return
        }

        setBusy(true, cancelable = true)
        statusView.text = "CPU-Benchmark läuft · 1 Warmup + 3 Messungen"
        outputView.text = ""

        activeOperationJob = activityScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    BenchmarkRunner().run(
                        engine = engine(),
                        modelDisplayName = model.displayName,
                        backendLabel = "CPU",
                        plan = BenchmarkPlan(
                            prompt = prompt,
                            maxOutputTokens = 64,
                            warmupRuns = 1,
                            measuredRuns = 3,
                            timeoutMillis = 180_000L,
                        ),
                    )
                }
                val details = report.samples.joinToString(separator = "\n") { sample ->
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
                    "%s\n\nMittelwert Prefill: %.2f tok/s\nMedian Prefill: %.2f tok/s\nMittelwert Generierung: %.2f tok/s\nMedian Generierung: %.2f tok/s\nMittelwert Gesamt: %.2f tok/s\nMedian Gesamt: %.2f tok/s\n\n%s",
                    report.modelDisplayName,
                    report.averagePromptTokensPerSecond,
                    report.medianPromptTokensPerSecond,
                    report.averageGenerationTokensPerSecond,
                    report.medianGenerationTokensPerSecond,
                    report.averageWallTokensPerSecond,
                    report.medianWallTokensPerSecond,
                    details,
                )
                statusView.text = "CPU-Benchmark abgeschlossen · 3 Messungen"
            } catch (cancelled: CancellationException) {
                statusView.text = "CPU-Benchmark abgebrochen · native Abbruchanforderung gesendet"
                throw cancelled
            } catch (error: Exception) {
                statusView.text = "CPU-Benchmark fehlgeschlagen: ${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (activeOperationJob === coroutineContext[Job]) {
                    activeOperationJob = null
                    setBusy(false)
                }
            }
        }
    }

    private fun engine(): AndroidLocalInferenceEngine =
        inferenceEngine ?: AndroidLocalInferenceEngine(applicationContext).also {
            inferenceEngine = it
        }

    private fun setBusy(busy: Boolean, cancelable: Boolean = false) {
        selectModelButton.isEnabled = !busy
        generateButton.isEnabled = !busy && selectedModel != null
        benchmarkButton.isEnabled = !busy && selectedModel != null
        cancelButton.isEnabled = busy && cancelable
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
