package de.mrlongnight.llmreference.engine.nativebridge

/**
 * Narrow JNI surface. Native objects are owned and used only by the isolated runtime process.
 * This is an implementation bridge, not part of the stable engine API module.
 */
object NativeLlama {
    init {
        System.loadLibrary("llm_reference")
    }

    external fun nativeInitialize(): Boolean
    external fun nativeLoadModelFromFd(fd: Int, contextTokens: Int, threads: Int)
    external fun nativeGenerate(prompt: String, maxOutputTokens: Int): String?
    /** Safe to invoke from the client dispatch thread while generation runs on the native worker. */
    external fun nativeAbortGeneration()
    external fun nativeUnloadModel()
    external fun nativeShutdown()
    external fun nativeSystemInfo(): String
    external fun nativeLastPromptTokens(): Int
    external fun nativeLastGeneratedTokens(): Int
    external fun nativeLastElapsedMillis(): Long
}
