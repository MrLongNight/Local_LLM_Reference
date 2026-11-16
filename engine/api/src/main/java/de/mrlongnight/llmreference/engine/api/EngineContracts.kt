package de.mrlongnight.llmreference.engine.api

/** Platform-neutral description of the original model selected by the client. */
data class ModelDescriptor(
    val displayName: String,
    /** Opaque client-owned location; the runtime resolves it to a readable descriptor. */
    val sourceUri: String,
    val sizeBytes: Long? = null,
    val contextTokens: Int = 2048,
    /** 0 lets the native backend choose its default. */
    val threads: Int = 0,
)

/** Controls a single generation request, including its client-side deadline. */
data class GenerationConfig(
    val maxOutputTokens: Int = 256,
    val timeoutMillis: Long = 180_000L,
)

data class GenerationResult(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val elapsedMillis: Long,
)

interface LocalInferenceEngine {
    suspend fun loadModel(model: ModelDescriptor)
    suspend fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): GenerationResult
    suspend fun unload()
}
