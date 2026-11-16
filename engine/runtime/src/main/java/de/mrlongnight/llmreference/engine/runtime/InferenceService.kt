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
import de.mrlongnight.llmreference.engine.nativebridge.NativeLlama
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Binder messages are dispatched on the main thread; native work is serialized on a dedicated
 * executor in the isolated :llm_inference process. This lets CANCEL_GENERATION reach the native
 * abort callback while llama.cpp is busy on the worker thread.
 */
class InferenceService : Service() {
    private val activeRequestId = AtomicInteger(0)
    private val pendingRequestIds = ConcurrentHashMap.newKeySet<Int>()
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<Int>()
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LocalLLM-Inference")
    }

    private lateinit var serviceMessenger: Messenger

    override fun onCreate() {
        super.onCreate()
        serviceMessenger = Messenger(Handler(Looper.getMainLooper()) { incoming ->
            if (incoming.what == InferenceIpc.CANCEL_GENERATION) {
                cancelGeneration(incoming.arg1)
            } else {
                // Messenger recycles its incoming Message after handleMessage returns.
                val request = Message.obtain(incoming)
                pendingRequestIds.add(request.arg1)
                inferenceExecutor.execute {
                    try {
                        if (cancelledRequestIds.remove(request.arg1)) {
                            return@execute
                        }
                        activeRequestId.set(request.arg1)
                        handleRequest(request)
                    } finally {
                        activeRequestId.compareAndSet(request.arg1, 0)
                        pendingRequestIds.remove(request.arg1)
                        cancelledRequestIds.remove(request.arg1)
                        request.recycle()
                    }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    private fun cancelGeneration(requestId: Int) {
        if (requestId <= 0 || !pendingRequestIds.contains(requestId)) return
        if (activeRequestId.get() == requestId) {
            // JNI abort is lock-free and safe to call concurrently with nativeGenerate().
            NativeLlama.nativeAbortGeneration()
        } else {
            // The request is queued but has not entered the native worker yet.
            cancelledRequestIds.add(requestId)
        }
    }

    private fun handleRequest(message: Message) {
        val response = Bundle()
        val status = try {
            check(NativeLlama.nativeInitialize()) { "Native backend initialization failed." }
            when (message.what) {
                InferenceIpc.LOAD_MODEL -> loadModel(message.data)
                InferenceIpc.GENERATE -> generate(message.data, response)
                InferenceIpc.UNLOAD_MODEL -> NativeLlama.nativeUnloadModel()
                InferenceIpc.SYSTEM_INFO -> response.putString(
                    InferenceIpc.KEY_SYSTEM_INFO,
                    NativeLlama.nativeSystemInfo()
                )
                else -> error("Unsupported inference command: ${message.what}")
            }
            InferenceIpc.STATUS_OK
        } catch (error: Exception) {
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
            // The client may have cancelled or its process may have gone away.
        } finally {
            reply.recycle()
        }
    }

    private fun loadModel(data: Bundle) {
        @Suppress("DEPRECATION")
        val descriptor = if (android.os.Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(InferenceIpc.KEY_DESCRIPTOR, ParcelFileDescriptor::class.java)
        } else {
            data.getParcelable(InferenceIpc.KEY_DESCRIPTOR)
        } ?: error("Missing model file descriptor.")

        descriptor.use {
            NativeLlama.nativeLoadModelFromFd(
                it.fd,
                data.getInt(InferenceIpc.KEY_CONTEXT_TOKENS, 2048),
                data.getInt(InferenceIpc.KEY_THREADS, 0)
            )
        }
    }

    private fun generate(data: Bundle, response: Bundle) {
        val prompt = data.getString(InferenceIpc.KEY_PROMPT)
            ?: error("Missing prompt.")
        val maxOutputTokens = data.getInt(InferenceIpc.KEY_MAX_OUTPUT_TOKENS, 256)
        val text = NativeLlama.nativeGenerate(prompt, maxOutputTokens)
            ?: error("Native generation returned no result.")
        response.putString(InferenceIpc.KEY_TEXT, text)
        response.putInt(InferenceIpc.KEY_PROMPT_TOKENS, NativeLlama.nativeLastPromptTokens())
        response.putInt(InferenceIpc.KEY_GENERATED_TOKENS, NativeLlama.nativeLastGeneratedTokens())
        response.putLong(InferenceIpc.KEY_ELAPSED_MILLIS, NativeLlama.nativeLastElapsedMillis())
    }

    override fun onDestroy() {
        // Queue native cleanup behind any in-flight operation; never free a context concurrently.
        inferenceExecutor.execute { NativeLlama.nativeShutdown() }
        inferenceExecutor.shutdown()
        super.onDestroy()
    }
}
