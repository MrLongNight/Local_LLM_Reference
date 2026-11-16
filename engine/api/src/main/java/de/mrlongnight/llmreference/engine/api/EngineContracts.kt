package de.mrlongnight.llmreference.engine.api

enum class InferenceBackend { CPU, VULKAN }

data class ModelDescriptor(
    val displayName: String,
    val sourceUri: String,
    val sizeBytes: Long? = null,
    val contextTokens: Int = 2048,
    val threads: Int = 0,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val gpuLayers: Int = 0,
    val sha256: String? = null,
) {
    init {
        require(displayName.isNotBlank())
        require(sourceUri.isNotBlank())
        require(contextTokens in 32..131072)
        require(threads in 0..256)
        require(gpuLayers == -1 || gpuLayers in 0..256)
        require(backend != InferenceBackend.VULKAN || gpuLayers != 0)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class ChatMessage(val role: String, val content: String) {
    init {
        require(role in setOf("system", "user", "assistant"))
        require(content.isNotBlank())
    }
}

data class GenerationConfig(
    val maxOutputTokens: Int = 256,
    val timeoutMillis: Long = 180_000L,
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val topK: Int = 0,
    val seed: Int = 42,
    /** Non-empty messages enable the model's GGUF default chat template; raw completion stays unchanged otherwise. */
    val chatMessages: List<ChatMessage> = emptyList(),
) {
    init {
        require(maxOutputTokens in 1..8192)
        require(timeoutMillis in 1_000L..600_000L)
        require(temperature.isFinite() && temperature in 0f..2f)
        require(topP.isFinite() && topP > 0f && topP <= 1f)
        require(topK in 0..1024)
        require(seed >= 0)
    }
}

data class GenerationResult(
    val text: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptElapsedMillis: Long,
    val generationElapsedMillis: Long,
    val elapsedMillis: Long,
)

interface LocalInferenceEngine {
    suspend fun loadModel(model: ModelDescriptor)
    suspend fun generate(prompt: String, config: GenerationConfig = GenerationConfig()): GenerationResult
    suspend fun unload()
}
