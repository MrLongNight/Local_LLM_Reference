package de.mrlongnight.llmreference.engine.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import de.mrlongnight.llmreference.engine.nativebridge.NativeLlama
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Binder messages are dispatched on the main thread; native work is serialized on a dedicated
 * executor in the isolated :llm_inference process. This lets CANCEL_GENERATION reach the native
 * abort callback while llama.cpp is busy on the worker thread.
 *
 * The service is the sole owner of native model state. Requests are accepted and queued while
 * holding requestStateLock so service teardown cannot race executor submission.
 */
class InferenceService : Service() {
    private val activeRequestId = AtomicInteger(0)
    private val requestStateLock = Any()
    private val pendingRequestIds = ConcurrentHashMap.newKeySet<Int>()
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<Int>()
    private val modelSessionOwner = ModelSessionOwner()
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalLLM-Inference")
    }

    @Volatile private var stopping = false
    private lateinit var serviceMessenger: Messenger

    override fun onCreate() {
        super.onCreate()
        EngineDiagnostics.initialize(this, "inference")
        serviceMessenger = Messenger(Handler(Looper.getMainLooper()) { incoming ->
            if (incoming.what == InferenceIpc.CANCEL_GENERATION) {
                cancelGeneration(incoming.arg1)
            } else {
                // Messenger recycles its incoming Message after handleMessage returns.
                val request = Message.obtain(incoming)
                val accepted = synchronized(requestStateLock) {
                    if (stopping) {
                        false
                    } else {
                        pendingRequestIds.add(request.arg1)
                        try {
                            inferenceExecutor.execute {
                                try {
                                    if (stopping) {
                                        EngineDiagnostics.warn("inference_service", "request_discarded_during_shutdown", mapOf(
                                            "request_id" to request.arg1,
                                            "command" to commandName(request.what)
                                        ))
                                        return@execute
                                    }
                                    if (cancelledRequestIds.remove(request.arg1)) {
                                        EngineDiagnostics.info("inference_service", "queued_request_cancelled", mapOf(
                                            "request_id" to request.arg1
                                        ))
                                        return@execute
                                    }
                                    handleRequest(request)
                                } finally {
                                    synchronized(requestStateLock) {
                                        activeRequestId.compareAndSet(request.arg1, 0)
                                        pendingRequestIds.remove(request.arg1)
                                        cancelledRequestIds.remove(request.arg1)
                                    }
                                    request.recycle()
                                }
                            }
                            true
                        } catch (rejected: RejectedExecutionException) {
                            pendingRequestIds.remove(request.arg1)
                            cancelledRequestIds.remove(request.arg1)
                            EngineDiagnostics.error("inference_service", "request_rejected", mapOf(
                                "request_id" to request.arg1,
                                "command" to commandName(request.what)
                            ))
                            false
                        }
                    }
                }
                if (!accepted) {
                    replyUnavailable(request)
                    request.recycle()
                } else {
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    private fun commandName(command: Int): String = when (command) {
        InferenceIpc.LOAD_MODEL -> "load_model"
        InferenceIpc.GENERATE -> "generate"
        InferenceIpc.UNLOAD_MODEL -> "unload_model"
        InferenceIpc.SYSTEM_INFO -> "system_info"
        InferenceIpc.FLUSH_DIAGNOSTICS -> "flush_diagnostics"
        else -> "unknown"
    }

    private fun replyUnavailable(request: Message) {
        val reply = Message.obtain(null, InferenceIpc.RESULT).apply {
            arg1 = request.arg1
            arg2 = InferenceIpc.STATUS_ERROR
            data = Bundle().apply {
                putString(InferenceIpc.KEY_ERROR, "Inference service is shutting down.")
            }
        }
        try {
            request.replyTo?.send(reply)
        } catch (_: Exception) {
            EngineDiagnostics.warn("inference_service", "rejection_reply_failed", mapOf(
                "request_id" to request.arg1
            ))
        } finally {
            reply.recycle()
        }
    }

    private fun cancelGeneration(requestId: Int) {
        if (requestId <= 0) return
        synchronized(requestStateLock) {
            if (!pendingRequestIds.contains(requestId)) {
                EngineDiagnostics.warn("inference_service", "cancel_for_unknown_request", mapOf(
                    "request_id" to requestId
                ))
                return
            }
            if (activeRequestId.get() == requestId) {
                // JNI abort is lock-free and safe to call concurrently with nativeGenerate().
                NativeLlama.nativeAbortGeneration()
                EngineDiagnostics.info("inference_service", "active_generation_abort_requested", mapOf(
                    "request_id" to requestId
                ))
            } else {
                // The request is queued but has not entered the native worker yet.
                cancelledRequestIds.add(requestId)
                EngineDiagnostics.info("inference_service", "queued_generation_cancel_requested", mapOf(
                    "request_id" to requestId
                ))
            }
        }
    }

    private fun handleRequest(message: Message) {
        val command = commandName(message.what)
        val response = Bundle()
        val status = try {
            if (message.what != InferenceIpc.FLUSH_DIAGNOSTICS) {
                check(NativeLlama.nativeInitialize()) { "Native backend initialization failed." }
            }
            val clientId = message.data.getString(InferenceIpc.KEY_CLIENT_ID).orEmpty()
            check(clientId.isNotBlank()) { "Missing engine client identity." }
            when (message.what) {
                InferenceIpc.LOAD_MODEL -> {
                    // One native model slot exists in this process. Clear the old lease before
                    // replacement so a failed load cannot leave another client using stale state.
                    modelSessionOwner.resetForLoad()
                    loadModel(message.data)
                    modelSessionOwner.claim(clientId)
                }
                InferenceIpc.GENERATE -> {
                    modelSessionOwner.requireOwner(clientId)
                    generate(message.arg1, message.data, response)
                }
                InferenceIpc.UNLOAD_MODEL -> {
                    modelSessionOwner.requireOwner(clientId)
                    NativeLlama.nativeUnloadModel()
                    modelSessionOwner.release(clientId)
                }
                InferenceIpc.SYSTEM_INFO -> response.putString(
                    InferenceIpc.KEY_SYSTEM_INFO,
                    NativeLlama.nativeSystemInfo()
                )
                InferenceIpc.FLUSH_DIAGNOSTICS -> Unit
                else -> error("Unsupported inference command: ${message.what}")
            }
            // Flush on the inference worker only after the completion event was queued.
            if (message.what == InferenceIpc.FLUSH_DIAGNOSTICS) {
                EngineDiagnostics.flushPendingWrites()
            }
            InferenceIpc.STATUS_OK
        } catch (error: Exception) {
            EngineDiagnostics.error("inference_service", "request_failed", mapOf(
                "request_id" to message.arg1,
                "command" to command,
                "error_class" to error.javaClass.simpleName
            ))
            response.putString(
                InferenceIpc.KEY_ERROR,
                error.message ?: error::class.java.simpleName
            )
            InferenceIpc.STATUS_ERROR
        }

        val reply = Message.obtain(null, InferenceIpc.RESULT).apply {
            arg1 = message.arg1
            arg2 = status
            data = response
        }
        try {
            message.replyTo?.send(reply)
        } catch (_: Exception) {
            EngineDiagnostics.warn("inference_service", "result_delivery_failed", mapOf(
                "request_id" to message.arg1
            ))
        } finally {
            reply.recycle()
        }
    }

    private fun loadModel(data: Bundle) {
        val startedAt = SystemClock.elapsedRealtime()
        val contextTokens = data.getInt(InferenceIpc.KEY_CONTEXT_TOKENS, 2048)
        val threads = data.getInt(InferenceIpc.KEY_THREADS, 0)
        EngineDiagnostics.info("model", "native_load_started", mapOf(
            "context_tokens" to contextTokens,
            "threads_requested" to threads
        ) + EngineDiagnostics.memorySnapshot(this))
        @Suppress("DEPRECATION")
        val descriptor = if (android.os.Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(InferenceIpc.KEY_DESCRIPTOR, ParcelFileDescriptor::class.java)
        } else {
            data.getParcelable(InferenceIpc.KEY_DESCRIPTOR)
        } ?: error("Missing model file descriptor.")

        descriptor.use {
            NativeLlama.nativeLoadModelFromFd(it.fd, contextTokens, threads)
        }
        EngineDiagnostics.info("model", "native_load_completed", mapOf(
            "elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt),
            "context_tokens" to contextTokens,
            "threads_requested" to threads
        ) + EngineDiagnostics.memorySnapshot(this))
    }

    private fun generate(requestId: Int, data: Bundle, response: Bundle) {
        synchronized(requestStateLock) {
            check(!stopping) { "Inference service is shutting down." }
            if (cancelledRequestIds.remove(requestId)) {
                error("Generation cancelled before native execution.")
            }
            NativeLlama.nativeBeginGeneration()
            // Publish the active ID atomically with the queued-cancellation check. The
            // cancellation handler and service teardown use the same lock, so neither can
            // miss the transition into native generation.
            activeRequestId.set(requestId)
        }

        val startedAt = SystemClock.elapsedRealtime()
        val prompt = data.getString(InferenceIpc.KEY_PROMPT)
            ?: error("Missing prompt.")
        val maxOutputTokens = data.getInt(InferenceIpc.KEY_MAX_OUTPUT_TOKENS, 256)
        EngineDiagnostics.info("generation", "native_generation_started", mapOf(
            "request_id" to requestId,
            "prompt_characters" to prompt.length,
            "max_output_tokens" to maxOutputTokens
        ) + EngineDiagnostics.memorySnapshot(this))

        try {
            val text = NativeLlama.nativeGenerate(prompt, maxOutputTokens)
                ?: error("Native generation returned no result.")
            val promptTokens = NativeLlama.nativeLastPromptTokens()
            val generatedTokens = NativeLlama.nativeLastGeneratedTokens()
            val promptElapsed = NativeLlama.nativeLastPromptElapsedMillis()
            val generationElapsed = NativeLlama.nativeLastGenerationElapsedMillis()
            val nativeElapsed = NativeLlama.nativeLastElapsedMillis()
            response.putString(InferenceIpc.KEY_TEXT, text)
            response.putInt(InferenceIpc.KEY_PROMPT_TOKENS, promptTokens)
            response.putInt(InferenceIpc.KEY_GENERATED_TOKENS, generatedTokens)
            response.putLong(InferenceIpc.KEY_PROMPT_ELAPSED_MILLIS, promptElapsed)
            response.putLong(InferenceIpc.KEY_GENERATION_ELAPSED_MILLIS, generationElapsed)
            response.putLong(InferenceIpc.KEY_ELAPSED_MILLIS, nativeElapsed)
            EngineDiagnostics.info("generation", "native_generation_completed", mapOf(
                "request_id" to requestId,
                "prompt_tokens" to promptTokens,
                "generated_tokens" to generatedTokens,
                "prompt_elapsed_ms" to promptElapsed,
                "generation_elapsed_ms" to generationElapsed,
                "native_elapsed_ms" to nativeElapsed,
                "service_elapsed_ms" to (SystemClock.elapsedRealtime() - startedAt)
            ) + EngineDiagnostics.memorySnapshot(this))
        } catch (error: Exception) {
            EngineDiagnostics.warn("generation", "native_generation_interrupted", mapOf(
                "request_id" to requestId,
                "error_class" to error.javaClass.simpleName
            ))
            throw error
        } finally {
            synchronized(requestStateLock) {
                activeRequestId.compareAndSet(requestId, 0)
            }
        }
    }

    override fun onDestroy() {
        // Stop accepting work under the same lock used for queue admission and generation start.
        // Abort active native work, then serialize native cleanup behind the worker queue.
        synchronized(requestStateLock) {
            stopping = true
            if (activeRequestId.get() != 0) {
                NativeLlama.nativeAbortGeneration()
                EngineDiagnostics.warn("inference_service", "shutdown_aborted_active_generation", mapOf(
                    "request_id" to activeRequestId.get()
                ))
            }
        }
        inferenceExecutor.execute {
            NativeLlama.nativeShutdown()
        }
        inferenceExecutor.shutdown()
        super.onDestroy()
    }
}
