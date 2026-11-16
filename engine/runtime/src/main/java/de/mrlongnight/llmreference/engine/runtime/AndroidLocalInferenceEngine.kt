package de.mrlongnight.llmreference.engine.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.GenerationResult
import de.mrlongnight.llmreference.engine.api.LocalInferenceEngine
import de.mrlongnight.llmreference.engine.api.ModelDescriptor
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InferenceEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * App-process client for the isolated inference service. All requests are serialized so a model
 * cannot be replaced while another generation is using its context.
 */
class AndroidLocalInferenceEngine(context: Context) : LocalInferenceEngine {
    private val appContext = context.applicationContext
    private val operationMutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CancellableContinuation<Bundle>>()
    @Volatile private var remoteMessenger: Messenger? = null
    @Volatile private var activeConnection: ServiceConnection? = null
    @Volatile private var isBound = false

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { reply ->
        val continuation = pending.remove(reply.arg1)
        if (continuation != null && continuation.isActive) {
            if (reply.arg2 == InferenceIpc.STATUS_OK) {
                continuation.resume(reply.data ?: Bundle())
            } else {
                val detail = reply.data?.getString(InferenceIpc.KEY_ERROR) ?: "Inference service failed."
                continuation.resumeWithException(InferenceEngineException(detail))
            }
        }
        true
    })

    override suspend fun loadModel(model: ModelDescriptor) {
        require(model.contextTokens >= 32) { "Context size must be at least 32 tokens." }
        require(model.threads >= 0) { "Thread count cannot be negative." }
        val descriptor = openModelDescriptor(model.sourceUri)
        descriptor.use {
            val payload = Bundle().apply {
                putParcelable(InferenceIpc.KEY_DESCRIPTOR, it)
                putInt(InferenceIpc.KEY_CONTEXT_TOKENS, model.contextTokens)
                putInt(InferenceIpc.KEY_THREADS, model.threads)
            }
            request(InferenceIpc.LOAD_MODEL, payload)
        }
    }

    override suspend fun generate(prompt: String, config: GenerationConfig): GenerationResult {
        require(prompt.isNotBlank()) { "Prompt must not be empty." }
        require(config.maxOutputTokens in 1..8192) { "Output token limit must be between 1 and 8192." }
        require(config.timeoutMillis in 1_000L..600_000L) { "Generation timeout must be between 1 second and 10 minutes." }
        val payload = Bundle().apply {
            putString(InferenceIpc.KEY_PROMPT, prompt)
            putInt(InferenceIpc.KEY_MAX_OUTPUT_TOKENS, config.maxOutputTokens)
        }
        val result = try {
            withTimeout(config.timeoutMillis) {
                request(InferenceIpc.GENERATE, payload)
            }
        } catch (error: TimeoutCancellationException) {
            throw InferenceEngineException(
                "Generation timed out after ${config.timeoutMillis} ms; native work was asked to abort.",
                error
            )
        }
        return GenerationResult(
            text = result.getString(InferenceIpc.KEY_TEXT).orEmpty(),
            promptTokens = result.getInt(InferenceIpc.KEY_PROMPT_TOKENS),
            generatedTokens = result.getInt(InferenceIpc.KEY_GENERATED_TOKENS),
            promptElapsedMillis = result.getLong(InferenceIpc.KEY_PROMPT_ELAPSED_MILLIS),
            generationElapsedMillis = result.getLong(InferenceIpc.KEY_GENERATION_ELAPSED_MILLIS),
            elapsedMillis = result.getLong(InferenceIpc.KEY_ELAPSED_MILLIS)
        )
    }

    override suspend fun unload() {
        try {
            request(InferenceIpc.UNLOAD_MODEL)
        } finally {
            disconnect()
        }
    }

    suspend fun systemInfo(): String =
        request(InferenceIpc.SYSTEM_INFO).getString(InferenceIpc.KEY_SYSTEM_INFO).orEmpty()

    private suspend fun request(command: Int, payload: Bundle = Bundle()): Bundle =
        operationMutex.withLock {
            val remote = ensureBound()
            val requestId = InferenceRequestIds.next()
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                pending[requestId] = continuation
                // Serialize the initial send with cancellation. Otherwise a timeout can send
                // CANCEL before GENERATE reaches the service, so the service misses the cancel.
                val sendLock = Any()
                var requestSent = false
                var cancelled = false
                continuation.invokeOnCancellation {
                    pending.remove(requestId)
                    synchronized(sendLock) {
                        cancelled = true
                        if (command == InferenceIpc.GENERATE && requestSent) {
                            try {
                                remote.send(Message.obtain(null, InferenceIpc.CANCEL_GENERATION).apply {
                                    arg1 = requestId
                                })
                            } catch (_: RemoteException) {
                                // The isolated process may already have exited.
                            }
                        }
                    }
                }
                val message = Message.obtain(null, command).apply {
                    arg1 = requestId
                    data = payload
                    replyTo = replyMessenger
                }
                synchronized(sendLock) {
                    if (!cancelled && continuation.isActive) {
                        try {
                            remote.send(message)
                            requestSent = true
                        } catch (error: RemoteException) {
                            pending.remove(requestId)
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    InferenceEngineException("Inference process is unavailable.", error)
                                )
                            }
                            disconnect()
                        }
                    } else {
                        message.recycle()
                    }
                }
            }
        }

    private suspend fun ensureBound(): Messenger {
        remoteMessenger?.let { return it }
        return try {
            withTimeout(15_000) {
                withContext(Dispatchers.Main.immediate) {
                    remoteMessenger?.let { return@withContext it }
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val connection = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                val messenger = service?.let(::Messenger)
                                if (messenger == null) {
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            InferenceEngineException("Inference service returned no binder.")
                                        )
                                    }
                                    disconnect(this)
                                } else if (continuation.isActive) {
                                    remoteMessenger = messenger
                                    continuation.resume(messenger)
                                } else {
                                    disconnect(this)
                                }
                            }

                            override fun onServiceDisconnected(name: ComponentName?) {
                                failPending("Inference process terminated.")
                                disconnect(this)
                            }

                            override fun onBindingDied(name: ComponentName?) {
                                failPending("Inference service binding died.")
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        InferenceEngineException("Inference service binding died.")
                                    )
                                }
                                disconnect(this)
                            }

                            override fun onNullBinding(name: ComponentName?) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        InferenceEngineException("Inference service returned a null binding.")
                                    )
                                }
                                disconnect(this)
                            }
                        }
                        activeConnection = connection
                        val bound = appContext.bindService(
                            Intent(appContext, InferenceService::class.java),
                            connection,
                            Context.BIND_AUTO_CREATE
                        )
                        isBound = bound
                        if (!bound) {
                            activeConnection = null
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    InferenceEngineException("Could not bind to the inference service.")
                                )
                            }
                        }
                        continuation.invokeOnCancellation {
                            if (bound) {
                                Handler(Looper.getMainLooper()).post { disconnect(connection) }
                            }
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw InferenceEngineException("Timed out while connecting to the inference process.", error)
        }
    }

    private fun openModelDescriptor(sourceUri: String): ParcelFileDescriptor {
        require(sourceUri.isNotBlank()) { "Model location must not be empty." }
        val uri = Uri.parse(sourceUri)
        return when (uri.scheme) {
            "content" -> appContext.contentResolver.openFileDescriptor(uri, "r")
                ?: throw InferenceEngineException("The selected model provider returned no file descriptor.")
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path) { "File URI has no path." }),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            null -> ParcelFileDescriptor.open(File(sourceUri), ParcelFileDescriptor.MODE_READ_ONLY)
            else -> throw InferenceEngineException("Unsupported model URI scheme: ${uri.scheme}")
        }
    }

    private fun failPending(message: String) {
        val error = InferenceEngineException(message)
        pending.entries.toList().forEach { (id, continuation) ->
            if (pending.remove(id, continuation) && continuation.isActive) {
                continuation.resumeWithException(error)
            }
        }
    }

    private fun disconnect(expected: ServiceConnection? = activeConnection) {
        val connection = expected ?: return
        if (activeConnection !== connection) return
        remoteMessenger = null
        activeConnection = null
        if (isBound) {
            isBound = false
            try {
                appContext.unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Already unbound by Android after a binding-death callback.
            }
        }
    }
}
