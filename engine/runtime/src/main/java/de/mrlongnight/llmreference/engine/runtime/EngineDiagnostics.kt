package de.mrlongnight.llmreference.engine.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Process-aware diagnostics writer shared by the app and isolated inference service.
 *
 * One compact, single-line .log file is written per process. Records are queued on a bounded
 * background writer; the UI and inference threads never wait for disk I/O. Logs are capped and
 * rotated to keep a device report small enough to inspect and attach to a support chat.
 */
object EngineDiagnostics {
    private const val DIRECTORY_NAME = "diagnostics"
    private const val MAX_FILE_BYTES = 128L * 1024L
    private const val MAX_QUEUED_RECORDS = 256
    private const val EXPORT_FLUSH_TIMEOUT_SECONDS = 10L
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS'Z'"

    private val droppedRecords = AtomicLong(0L)
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_RECORDS),
        ThreadFactory { runnable ->
            Thread(runnable, "LocalLLM-Diagnostics").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Volatile private var logDirectory: File? = null
    @Volatile private var processName: String = "unknown"

    fun initialize(context: Context, process: String) {
        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            android.util.Log.e("EngineDiagnostics", "Could not create diagnostics directory")
            return
        }

        processName = process
        logDirectory = directory
        // Remove older unbounded JSONL files for this process when adopting the capped format.
        listOf(
            File(directory, "$process.jsonl"),
            File(directory, "$process.jsonl.1")
        ).forEach { legacy ->
            if (legacy.exists() && !legacy.delete()) {
                android.util.Log.w("EngineDiagnostics", "Could not remove oversized legacy diagnostics file")
            }
        }
        // Enforce the new hard cap immediately when upgrading from an older .log format.
        listOf(
            File(directory, "$process.log"),
            File(directory, "$process.log.1")
        ).forEach { existing ->
            if (existing.exists() && existing.length() > MAX_FILE_BYTES && !existing.delete()) {
                android.util.Log.e("EngineDiagnostics", "Could not remove oversized diagnostics log")
            }
        }

        info("diagnostics", "logger_ready", mapOf(
            "sdk" to Build.VERSION.SDK_INT,
            "device" to "${Build.MANUFACTURER} ${Build.DEVICE}",
            "abis" to Build.SUPPORTED_ABIS.joinToString(","),
        ) + memorySnapshot(context))
    }

    /** Process heap and system-wide memory pressure at selected diagnostic boundaries. */
    fun memorySnapshot(context: Context): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) activityManager.getMemoryInfo(memory)

        return buildMap {
            put("heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            put("heap_committed_bytes", runtime.totalMemory())
            put("heap_max_bytes", runtime.maxMemory())
            put("native_heap_bytes", Debug.getNativeHeapAllocatedSize())
            if (activityManager != null) {
                put("system_available_bytes", memory.availMem)
                put("system_total_bytes", memory.totalMem)
                put("system_threshold_bytes", memory.threshold)
                put("low_memory", memory.lowMemory)
            }
        }
    }

    fun info(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("INFO", component, event, attributes)

    fun warn(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("WARN", component, event, attributes)

    fun error(component: String, event: String, attributes: Map<String, Any?> = emptyMap()) =
        write("ERROR", component, event, attributes)

    private fun write(level: String, component: String, event: String, attributes: Map<String, Any?>) {
        val directory = logDirectory ?: return
        val process = processName
        val safeAttributes = DiagnosticAttributePolicy.sanitize(attributes).toMutableMap()
        val droppedBeforeRecord = droppedRecords.getAndSet(0L)
        if (droppedBeforeRecord > 0L) safeAttributes["dropped"] = droppedBeforeRecord

        val line = try {
            DiagnosticLogFormatter.format(
                timestamp = timestamp(),
                level = level,
                process = process,
                component = component,
                event = event,
                attributes = safeAttributes
            ) + "\n"
        } catch (failure: RuntimeException) {
            recordDropped(droppedBeforeRecord + 1L)
            android.util.Log.e("EngineDiagnostics", "Could not format diagnostics record", failure)
            return
        }
        val bytes = line.toByteArray(Charsets.UTF_8)

        try {
            writer.execute { appendRecord(directory, process, bytes, level, component, event) }
        } catch (_: RejectedExecutionException) {
            recordDropped(droppedBeforeRecord + 1L)
        }

        // INFO events already live in the compact file; duplicating every one in Logcat adds noise.
        if (level != "INFO") {
            android.util.Log.println(
                if (level == "ERROR") android.util.Log.ERROR else android.util.Log.WARN,
                "EngineDiagnostics",
                "$component/$event"
            )
        }
    }

    private fun appendRecord(
        directory: File,
        process: String,
        bytes: ByteArray,
        level: String,
        component: String,
        event: String
    ) {
        val target = File(directory, "$process.log")
        try {
            rotateIfNeeded(target, bytes.size.toLong())
            FileOutputStream(target, true).use { stream -> stream.write(bytes) }
        } catch (failure: IOException) {
            recordDropped(1L)
            android.util.Log.e(
                "EngineDiagnostics",
                "Could not append $level $component/$event diagnostics record",
                failure
            )
        } catch (failure: RuntimeException) {
            recordDropped(1L)
            android.util.Log.e("EngineDiagnostics", "Diagnostics writer failed", failure)
        }
    }

    private fun recordDropped(count: Long) {
        val total = droppedRecords.addAndGet(count)
        // Avoid turning a sustained writer overload into a second high-volume Logcat stream.
        if (total == count || total % 100L == 0L) {
            android.util.Log.w("EngineDiagnostics", "Dropped diagnostics records: $total")
        }
    }

    private fun rotateIfNeeded(target: File, incomingBytes: Long) {
        if (!target.exists() || target.length() + incomingBytes <= MAX_FILE_BYTES) return
        val previous = File(target.parentFile, target.name + ".1")
        if (previous.exists() && !previous.delete()) {
            throw IOException("Could not remove previous diagnostics rotation.")
        }
        if (!target.renameTo(previous)) {
            // Fail closed: never keep appending if rotation failed, or the size cap is meaningless.
            throw IOException("Could not rotate diagnostics file.")
        }
    }

    /** Waits until all records accepted before this barrier have reached disk. */
    internal fun flushPendingWrites() {
        try {
            writer.submit {}.get(EXPORT_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while flushing diagnostics.", failure)
        } catch (failure: ExecutionException) {
            throw IOException("Could not flush diagnostics.", failure.cause ?: failure)
        } catch (failure: TimeoutException) {
            throw IOException("Timed out while flushing diagnostics.", failure)
        } catch (failure: RejectedExecutionException) {
            throw IOException("Diagnostics writer is not accepting work.", failure)
        }
    }

    private fun timestamp(): String = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    /**
     * Exports only current and previous compact logs. The isolated inference queue is flushed over
     * IPC before this method is called; the app process is flushed here before reading both files.
     */
    @Throws(IOException::class)
    fun exportZip(context: Context, destination: Uri): Int {
        flushPendingWrites()

        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        val files = directory.listFiles()
            ?.filter {
                it.isFile &&
                    (it.name.endsWith(".log") || it.name.endsWith(".log.1")) &&
                    it.length() <= MAX_FILE_BYTES
            }
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
                Local LLM Reference diagnostics
                Generated UTC: ${timestamp()}
                Device: ${Build.MANUFACTURER} ${Build.DEVICE}
                Included files: $exportedFiles

                Format: UTF-8 plain text, one compact event per line:
                UTC timestamp | level (I/W/E) | process | component/event | key=value fields

                Size policy: one active file up to 128 KiB plus one previous 128 KiB segment
                per process. The app and inference process therefore contribute at most about
                512 KiB total before ZIP overhead.
                Disk writes use a bounded background queue of 256 records.
                Queue saturation and disk errors are counted as dropped records.

                Privacy: prompt/output content, content URIs, absolute paths and credentials are
                redacted. Unknown non-scalar attribute values are represented as [REDACTED].
            """.trimIndent()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return exportedFiles
    }
}
