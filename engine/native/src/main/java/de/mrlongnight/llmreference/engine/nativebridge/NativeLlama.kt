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
    external fun nativeLoadModelFromFd(fd: Int, contextTokens: Int, threads: Int): String?
    external fun nativeGenerate(prompt: String, maxOutputTokens: Int): String?
    external fun nativeUnloadModel()
    external fun nativeSystemInfo(): String
    external fun nativeLastPromptTokens(): Int
    external fun nativeLastGeneratedTokens(): Int
    external fun nativeLastElapsedMillis(): Long
}
