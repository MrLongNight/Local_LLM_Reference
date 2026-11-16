package de.mrlongnight.llmreference.benchmark

import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.InferenceBackend
import de.mrlongnight.llmreference.engine.api.LocalInferenceEngine

/** Reproducibility metadata: model bytes, prompt identity, runtime settings and target device. */
data class BenchmarkMetadata(
    val modelFileName: String = "unspecified.gguf",
    val modelSha256: String = "",
    val modelSizeBytes: Long? = null,
    val contextTokens: Int = 2048,
    val threads: Int = 0,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val gpuLayers: Int = 0,
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val topK: Int = 0,
    val seed: Int = 42,
    val maxOutputTokens: Int = 64,
    val timeoutMillis: Long = 180_000L,
    val promptSha256: String = "0".repeat(64),
    val promptCharacters: Int = 0,
    val deviceModel: String = "unknown",
    val androidApi: Int = 0,
    val supportedAbis: String = "",
    val nativeSystemInfo: String = "",
    val modelLoadElapsedMillis: Long? = null,
)

data class BenchmarkPlan(
    val prompt: String,
    val maxOutputTokens: Int = 64,
    val warmupRuns: Int = 1,
    val measuredRuns: Int = 3,
    val timeoutMillis: Long = 180_000L,
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val topK: Int = 0,
    val seed: Int = 42,
    val metadata: BenchmarkMetadata = BenchmarkMetadata(),
) {
    init {
        require(prompt.isNotBlank())
        require(maxOutputTokens in 1..8192)
        require(warmupRuns in 0..10)
        require(measuredRuns in 1..50)
        require(timeoutMillis in 1_000L..600_000L)
        require(temperature.isFinite() && temperature in 0f..2f)
        require(topP.isFinite() && topP > 0f && topP <= 1f)
        require(topK in 0..1024)
        require(seed >= 0)
        require(metadata.promptSha256.matches(Regex("[0-9a-f]{64}")))
        require(metadata.modelSha256.isEmpty() || metadata.modelSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class BenchmarkSample(
    val iteration: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptElapsedMillis: Long,
    val generationElapsedMillis: Long,
    val nativeElapsedMillis: Long,
    val wallElapsedMillis: Long,
    val promptTokensPerSecond: Double,
    val generationTokensPerSecond: Double,
    val wallTokensPerSecond: Double,
)

data class BenchmarkReport(
    val modelDisplayName: String,
    val backendLabel: String,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val metadata: BenchmarkMetadata,
    val samples: List<BenchmarkSample>,
    val averagePromptTokensPerSecond: Double,
    val medianPromptTokensPerSecond: Double,
    val averageGenerationTokensPerSecond: Double,
    val medianGenerationTokensPerSecond: Double,
    val averageWallTokensPerSecond: Double,
    val medianWallTokensPerSecond: Double,
    val averagePromptElapsedMillis: Double,
    val averageGenerationElapsedMillis: Double,
    val averageNativeElapsedMillis: Double,
    val averageWallElapsedMillis: Double,
) {
    /** Metadata and timings only: no prompt/output text, content URI or absolute path. */
    fun toCsv(): String = buildString {
        appendLine("section,key,value")
        fun meta(key: String, value: Any?) {
            val safe = value?.toString().orEmpty().replace(",", ";").replace("\n", " ")
            append("meta,").append(key).append(',').append(safe).appendLine()
        }
        meta("model_file_name", metadata.modelFileName)
        meta("model_sha256", metadata.modelSha256)
        meta("model_size_bytes", metadata.modelSizeBytes)
        meta("context_tokens", metadata.contextTokens)
        meta("threads", metadata.threads)
        meta("backend", metadata.backend.name)
        meta("gpu_layers", metadata.gpuLayers)
        meta("temperature", metadata.temperature)
        meta("top_p", metadata.topP)
        meta("top_k", metadata.topK)
        meta("seed", metadata.seed)
        meta("max_output_tokens", metadata.maxOutputTokens)
        meta("timeout_ms", metadata.timeoutMillis)
        meta("prompt_sha256", metadata.promptSha256)
        meta("prompt_characters", metadata.promptCharacters)
        meta("device_model", metadata.deviceModel)
        meta("android_api", metadata.androidApi)
        meta("supported_abis", metadata.supportedAbis)
        meta("native_system_info", metadata.nativeSystemInfo)
        meta("model_load_elapsed_ms", metadata.modelLoadElapsedMillis)
        meta("warmup_runs", warmupRuns)
        meta("measured_runs", measuredRuns)
        meta("mean_generation_tok_s", averageGenerationTokensPerSecond)
        meta("median_generation_tok_s", medianGenerationTokensPerSecond)
        meta("mean_prefill_tok_s", averagePromptTokensPerSecond)
        meta("median_prefill_tok_s", medianPromptTokensPerSecond)
        meta("mean_wall_tok_s", averageWallTokensPerSecond)
        meta("median_wall_tok_s", medianWallTokensPerSecond)
        meta("mean_model_prefill_ms", averagePromptElapsedMillis)
        meta("mean_generation_ms", averageGenerationElapsedMillis)
        meta("mean_native_total_ms", averageNativeElapsedMillis)
        meta("mean_wall_total_ms", averageWallElapsedMillis)
        appendLine()
        appendLine("sample,iteration,prompt_tokens,generated_tokens,prefill_ms,generation_ms,native_total_ms,wall_ms,prefill_tok_s,generation_tok_s,wall_tok_s")
        samples.forEach { s ->
            append("sample,").append(s.iteration).append(',')
                .append(s.promptTokens).append(',')
                .append(s.generatedTokens).append(',')
                .append(s.promptElapsedMillis).append(',')
                .append(s.generationElapsedMillis).append(',')
                .append(s.nativeElapsedMillis).append(',')
                .append(s.wallElapsedMillis).append(',')
                .append(s.promptTokensPerSecond).append(',')
                .append(s.generationTokensPerSecond).append(',')
                .append(s.wallTokensPerSecond).appendLine()
        }
    }
}

class BenchmarkRunner {
    /** Prefill excludes tokenization; decode excludes prefill; wall time covers IPC and runtime. */
    suspend fun run(
        engine: LocalInferenceEngine,
        modelDisplayName: String,
        backendLabel: String,
        plan: BenchmarkPlan,
    ): BenchmarkReport {
        require(modelDisplayName.isNotBlank())
        require(backendLabel.isNotBlank())
        val config = GenerationConfig(
            maxOutputTokens = plan.maxOutputTokens,
            timeoutMillis = plan.timeoutMillis,
            temperature = plan.temperature,
            topP = plan.topP,
            topK = plan.topK,
            seed = plan.seed,
        )
        repeat(plan.warmupRuns) { engine.generate(plan.prompt, config) }
        val samples = ArrayList<BenchmarkSample>(plan.measuredRuns)
        repeat(plan.measuredRuns) { index ->
            val startedAt = System.nanoTime()
            val result = engine.generate(plan.prompt, config)
            val wallMillis = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
            val prefillMillis = result.promptElapsedMillis.coerceAtLeast(1L)
            val decodeMillis = result.generationElapsedMillis.coerceAtLeast(1L)
            val promptTokens = result.promptTokens.coerceAtLeast(0)
            val generatedTokens = result.generatedTokens.coerceAtLeast(0)
            samples += BenchmarkSample(
                iteration = index + 1,
                promptTokens = promptTokens,
                generatedTokens = generatedTokens,
                promptElapsedMillis = result.promptElapsedMillis.coerceAtLeast(0L),
                generationElapsedMillis = result.generationElapsedMillis.coerceAtLeast(0L),
                nativeElapsedMillis = result.elapsedMillis.coerceAtLeast(0L),
                wallElapsedMillis = wallMillis,
                promptTokensPerSecond = promptTokens * 1_000.0 / prefillMillis,
                generationTokensPerSecond = generatedTokens * 1_000.0 / decodeMillis,
                wallTokensPerSecond = generatedTokens * 1_000.0 / wallMillis,
            )
        }
        val prefill = samples.map { it.promptTokensPerSecond }
        val decode = samples.map { it.generationTokensPerSecond }
        val wall = samples.map { it.wallTokensPerSecond }
        return BenchmarkReport(
            modelDisplayName, backendLabel, plan.warmupRuns, plan.measuredRuns,
            plan.metadata, samples.toList(),
            prefill.average(), prefill.median(), decode.average(), decode.median(),
            wall.average(), wall.median(),
            samples.map { it.promptElapsedMillis.toDouble() }.average(),
            samples.map { it.generationElapsedMillis.toDouble() }.average(),
            samples.map { it.nativeElapsedMillis.toDouble() }.average(),
            samples.map { it.wallElapsedMillis.toDouble() }.average(),
        )
    }

    private fun List<Double>.median(): Double {
        require(isNotEmpty())
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
}
