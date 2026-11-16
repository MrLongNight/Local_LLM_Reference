package de.mrlongnight.llmreference.engine.api

/** Platform-neutral description of a model selected by the client. */
data class ModelDescriptor(
    val displayName: String,
    val sourceUri: String,
    val sizeBytes: Long? = null,
)

/** Inference settings intentionally contain no native or Android framework types. */
data class GenerationConfig(
    val contextTokens: Int = 2048,
    val maxOutputTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
)

/** A single generated result, without exposing backend-specific objects. */
data class GenerationResult(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val elapsedMillis: Long,
)

interface LocalInferenceEngine {
    suspend fun loadModel(model: ModelDescriptor)
    suspend fun generate(prompt: String, config: GenerationConfig): GenerationResult
    suspend fun unload()
}
