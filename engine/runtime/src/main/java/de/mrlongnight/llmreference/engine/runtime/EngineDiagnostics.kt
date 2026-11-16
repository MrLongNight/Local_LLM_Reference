package de.mrlongnight.llmreference.engine.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Process-aware structured diagnostics shared by the app and isolated inference service.
 *
 * Each process writes its own JSON Lines file. Prompts, generated text, document/model URIs,
 * absolute paths and credentials are deliberately excluded from log attributes.
 */
object EngineDiagnostics {
    private const val DIRECTORY_NAME = "diagnostics"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    private val lock = Any()
    @Volatile private var logDirectory: File? = null
    @Volatile private var processName: String = "unknown"

    fun initialize(context: Context, process: String) {
        synchronized(lock) {
            val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
            if (!directory.exists() && !directory.mkdirs()) {
                android.util.Log.e("EngineDiagnostics", "Could not create diagnostics directory")
                return
            }
            logDirectory = directory
            processName = process
            writeLocked("INFO", "diagnostics", "logger_initialized", mapOf(
                "sdk_int" to Build.VERSION.SDK_INT,
                "manufacturer" to Build.MANUFACTURER,
                "device" to Build.DEVICE
            ))
        }
    }

    fun info(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("INFO", component, event, attributes)

    fun warn(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("WARN", component, event, attributes)

    fun error(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("ERROR", component, event, attributes)

    private fun write(level: String, component: String, event: String, attributes: Map<String, Any?>) {
        synchronized(lock) { writeLocked(level, component, event, attributes) }
    }

    private fun writeLocked(
        level: String,
        component: String,
        event: String,
        attributes: Map<String, Any?>
    ) {
        val directory = logDirectory ?: return
        val target = File(directory, "$" + "processName.jsonl")
        try {
            val record = JSONObject().apply {
                put("timestamp_utc", timestamp())
                put("level", level)
                put("process", processName)
                put("thread", Thread.currentThread().name)
                put("component", component)
                put("event", event)
                put("attributes", JSONObject().apply {
                    attributes.forEach { (key, value) ->
                        if (value != null) put(key, value)
                    }
                })
            }.toString() + "\n"
            val bytes = record.toByteArray(Charsets.UTF_8)
            rotateIfNeeded(target, bytes.size.toLong())
            FileOutputStream(target, true).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            android.util.Log.println(
                when (level) {
                    "ERROR" -> android.util.Log.ERROR
                    "WARN" -> android.util.Log.WARN
                    else -> android.util.Log.INFO
                },
                "EngineDiagnostics",
                "$" + "component/$" + "event"
            )
        } catch (failure: IOException) {
            android.util.Log.e("EngineDiagnostics", "Could not append diagnostics record", failure)
        } catch (failure: RuntimeException) {
            android.util.Log.e("EngineDiagnostics", "Could not encode diagnostics record", failure)
        }
    }

    private fun rotateIfNeeded(target: File, incomingBytes: Long) {
        if (!target.exists() || target.length() + incomingBytes <= MAX_FILE_BYTES) return
        val previous = File(target.parentFile, target.name + ".1")
        if (previous.exists() && !previous.delete()) {
            android.util.Log.w("EngineDiagnostics", "Could not remove previous diagnostics rotation")
        }
        if (!target.renameTo(previous)) {
            android.util.Log.w("EngineDiagnostics", "Could not rotate diagnostics file")
        }
    }

    private fun timestamp(): String = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    /**
     * Writes the app and inference process logs into one ZIP archive at the user-selected URI.
     * The export includes only the diagnostic directory, never model files or prompt/output data.
     */
    @Throws(IOException::class)
    fun exportZip(context: Context, destination: Uri): Int {
        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        val files = directory.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jsonl") || it.name.endsWith(".jsonl.1")) }
            ?.sortedBy { it.name }
            .orEmpty()
        val output = context.contentResolver.openOutputStream(destination, "wt")
            ?: throw IOException("The selected destination cannot be opened.")

        var exportedFiles = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            files.forEach { logFile ->
                zip.putNextEntry(ZipEntry("logs/" + logFile.name))
                logFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                exportedFiles++
            }
            val readme = """
                Local LLM Reference diagnostics export
                Generated UTC: ${timestamp()}
                Android SDK: ${Build.VERSION.SDK_INT}
                Device: ${Build.MANUFACTURER} ${Build.DEVICE}
                Included log files: $exportedFiles

                Format: UTF-8 JSON Lines (one structured event per line).
                Privacy: prompts, generated text, model/document URIs, absolute paths and credentials
                are intentionally excluded from diagnostic attributes.
                Log files are process-separated and rotated at approximately 2 MiB per active file.
            """.trimIndent()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return exportedFiles
    }
}
