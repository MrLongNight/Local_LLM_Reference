package de.mrlongnight.llmreference.engine.runtime

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import de.mrlongnight.llmreference.engine.nativebridge.NativeLlama

/**
 * Runs all native model work in a dedicated Android process and a single worker thread.
 * The app process never calls JNI directly.
 */
class InferenceService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var serviceMessenger: Messenger

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("LocalLLM-Inference").apply { start() }
        serviceMessenger = Messenger(object : Handler(workerThread.looper) {
            override fun handleMessage(message: Message) {
                handleRequest(message)
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

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
        workerThread.quitSafely()
        super.onDestroy()
    }
}
