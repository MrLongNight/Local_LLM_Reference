package de.mrlongnight.llmreference.engine.nativebridge

/** JNI bridge; all native objects are owned by the isolated inference process. */
object NativeLlama {
    init { System.loadLibrary("llm_reference") }
    external fun nativeInitialize(): Boolean
    external fun nativeLoadModelFromFd(fd: Int, contextTokens: Int, threads: Int, gpuLayers: Int, useVulkan: Boolean)
    external fun nativeBeginGeneration()
    external fun nativeGenerate(prompt: String, maxOutputTokens: Int, temperature: Float, topP: Float, topK: Int, seed: Int): String?
    external fun nativeAbortGeneration()
    external fun nativeUnloadModel()
    external fun nativeShutdown()
    external fun nativeSystemInfo(): String
    external fun nativeLastPromptTokens(): Int
    external fun nativeLastGeneratedTokens(): Int
    external fun nativeLastPromptElapsedMillis(): Long
    external fun nativeLastGenerationElapsedMillis(): Long
    external fun nativeLastElapsedMillis(): Long
}
