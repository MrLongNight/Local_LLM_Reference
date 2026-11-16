package de.mrlongnight.llmreference.engine.runtime

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Debug
import org.json.JSONObject
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
 * Process-aware structured diagnostics shared by the app and isolated inference service.
 *
 * Each process writes its own JSON Lines file. Disk work is serialized on a bounded background
 * writer so logging from the UI thread never performs file I/O or fsync. Sensitive attributes are
 * redacted centrally before serialization; callers must still prefer metadata over user content.
 */
object EngineDiagnostics {
    private const val DIRECTORY_NAME = "diagnostics"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    private const val MAX_QUEUED_RECORDS = 4_096
    private const val EXPORT_FLUSH_TIMEOUT_SECONDS = 10L

    private val lock = Any()
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
        synchronized(lock) {
            logDirectory = directory
            processName = process
        }
        info("diagnostics", "logger_initialized", mapOf(
            "sdk_int" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "device" to Build.DEVICE,
            "os_release" to Build.VERSION.RELEASE,
            "supported_abis" to Build.SUPPORTED_ABIS.joinToString(",")
        ) + memorySnapshot(context))
    }

    /**
     * Captures process-local managed/native heap usage and system-wide memory pressure.
     * Values are diagnostic measurements only; they are not a promise of allocatable memory.
     */
    fun memorySnapshot(context: Context): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        val memory = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) activityManager.getMemoryInfo(memory)

        return buildMap {
            put("runtime_heap_used_bytes", runtime.totalMemory() - runtime.freeMemory())
            put("runtime_heap_committed_bytes", runtime.totalMemory())
            put("runtime_heap_max_bytes", runtime.maxMemory())
            put("native_heap_allocated_bytes", Debug.getNativeHeapAllocatedSize())
            if (activityManager != null) {
                put("system_memory_available_bytes", memory.availMem)
                put("system_memory_total_bytes", memory.totalMem)
                put("system_memory_threshold_bytes", memory.threshold)
                put("system_low_memory", memory.lowMemory)
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
        if (droppedBeforeRecord > 0L) {
            safeAttributes["dropped_records_before_this_event"] = droppedBeforeRecord
        }

        val callerThread = Thread.currentThread().name
        val record = try {
            JSONObject().apply {
                put("timestamp_utc", timestamp())
                put("level", level)
                put("process", process)
                put("thread", callerThread)
                put("component", component)
                put("event", event)
                put("attributes", JSONObject().apply {
                    safeAttributes.forEach { (key, value) -> put(key, value) }
                })
            }.toString() + "\n"
        } catch (failure: RuntimeException) {
            android.util.Log.e("EngineDiagnostics", "Could not encode diagnostics record", failure)
            droppedRecords.incrementAndGet()
            return
        }

        val bytes = record.toByteArray(Charsets.UTF_8)
        try {
            writer.execute { appendRecord(directory, process, bytes, level, component, event) }
        } catch (_: RejectedExecutionException) {
            droppedRecords.incrementAndGet()
            android.util.Log.w("EngineDiagnostics", "Diagnostics queue full; record dropped")
        }

        android.util.Log.println(
            when (level) {
                "ERROR" -> android.util.Log.ERROR
                "WARN" -> android.util.Log.WARN
                else -> android.util.Log.INFO
            },
            "EngineDiagnostics",
            "$component/$event"
        )
    }

    private fun appendRecord(
        directory: File,
        process: String,
        bytes: ByteArray,
        level: String,
        component: String,
        event: String
    ) {
        val target = File(directory, "$process.jsonl")
        try {
            rotateIfNeeded(target, bytes.size.toLong())
            FileOutputStream(target, true).use { stream ->
                stream.write(bytes)
            }
        } catch (failure: IOException) {
            droppedRecords.incrementAndGet()
            android.util.Log.e(
                "EngineDiagnostics",
                "Could not append $level $component/$event diagnostics record",
                failure
            )
        } catch (failure: RuntimeException) {
            droppedRecords.incrementAndGet()
            android.util.Log.e("EngineDiagnostics", "Diagnostics writer failed", failure)
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

    private fun flushPendingWrites() {
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
     * Writes the app and inference process logs into one ZIP archive at the user-selected URI.
     * The app-process queue is flushed before the snapshot. An in-flight event from the isolated
     * process may be included in the next export, but all already-written service events are read.
     */
    @Throws(IOException::class)
    fun exportZip(context: Context, destination: Uri): Int {
        info("diagnostics", "export_requested", mapOf(
            "dropped_records_since_previous_event" to droppedRecords.get()
        ))
        flushPendingWrites()

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
                Each process writes its own log file. The active file is rotated at approximately
                2 MiB and one previous segment is retained for export.
                Disk writes use a bounded background queue. If it fills, dropped records are
                counted and reported in the next successfully queued event.
                Privacy: prompt/output text, content URIs, absolute paths, credentials and unknown
                non-scalar attribute values are redacted before serialization.
            """.trimIndent()
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write(readme.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return exportedFiles
    }
}
