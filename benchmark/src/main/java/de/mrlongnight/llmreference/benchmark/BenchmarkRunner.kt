package de.mrlongnight.llmreference.benchmark

import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.LocalInferenceEngine

data class BenchmarkPlan(
    val prompt: String,
    val maxOutputTokens: Int = 64,
    val warmupRuns: Int = 1,
    val measuredRuns: Int = 3,
    val timeoutMillis: Long = 180_000L,
) {
    init {
        require(prompt.isNotBlank()) { "Benchmark prompt must not be empty." }
        require(maxOutputTokens in 1..8192) { "maxOutputTokens must be between 1 and 8192." }
        require(warmupRuns in 0..5) { "warmupRuns must be between 0 and 5." }
        require(measuredRuns in 1..20) { "measuredRuns must be between 1 and 20." }
        require(timeoutMillis in 1_000L..600_000L) { "timeoutMillis must be between 1 second and 10 minutes." }
    }
}

/** A sample contains timings and token counts only; prompt and generated text are never retained. */
data class BenchmarkSample(
    val iteration: Int,
    val promptTokens: Int,
    val generatedTokens: Int,
    val nativeElapsedMillis: Long,
    val wallElapsedMillis: Long,
    val nativeTokensPerSecond: Double,
    val wallTokensPerSecond: Double,
)

data class BenchmarkReport(
    val modelDisplayName: String,
    val backendLabel: String,
    val warmupRuns: Int,
    val measuredRuns: Int,
    val samples: List<BenchmarkSample>,
    val averageNativeTokensPerSecond: Double,
    val medianNativeTokensPerSecond: Double,
    val averageWallTokensPerSecond: Double,
    val medianWallTokensPerSecond: Double,
    val averageWallElapsedMillis: Double,
)

class BenchmarkRunner {
    /**
     * Runs a warmup phase followed by measured iterations.
     *
     * The model must already be loaded. Native timing excludes IPC overhead; wall timing includes
     * the complete suspend call. Neither prompt text nor generated output is copied into the
     * returned report or logged by this runner.
     */
    suspend fun run(
        engine: LocalInferenceEngine,
        modelDisplayName: String,
        backendLabel: String,
        plan: BenchmarkPlan,
    ): BenchmarkReport {
        require(modelDisplayName.isNotBlank()) { "Model display name must not be empty." }
        require(backendLabel.isNotBlank()) { "Backend label must not be empty." }

        val config = GenerationConfig(
            maxOutputTokens = plan.maxOutputTokens,
            timeoutMillis = plan.timeoutMillis,
        )

        repeat(plan.warmupRuns) {
            engine.generate(plan.prompt, config)
        }

        val samples = ArrayList<BenchmarkSample>(plan.measuredRuns)
        repeat(plan.measuredRuns) { index ->
            val startedAt = System.nanoTime()
            val result = engine.generate(plan.prompt, config)
            val wallElapsedMillis = ((System.nanoTime() - startedAt) / 1_000_000L)
                .coerceAtLeast(1L)
            val nativeElapsedMillis = result.elapsedMillis.coerceAtLeast(1L)
            val generatedTokens = result.generatedTokens.coerceAtLeast(0)

            samples += BenchmarkSample(
                iteration = index + 1,
                promptTokens = result.promptTokens.coerceAtLeast(0),
                generatedTokens = generatedTokens,
                nativeElapsedMillis = result.elapsedMillis.coerceAtLeast(0L),
                wallElapsedMillis = wallElapsedMillis,
                nativeTokensPerSecond = generatedTokens * 1_000.0 / nativeElapsedMillis,
                wallTokensPerSecond = generatedTokens * 1_000.0 / wallElapsedMillis,
            )
        }

        val nativeRates = samples.map { it.nativeTokensPerSecond }
        val wallRates = samples.map { it.wallTokensPerSecond }

        return BenchmarkReport(
            modelDisplayName = modelDisplayName,
            backendLabel = backendLabel,
            warmupRuns = plan.warmupRuns,
            measuredRuns = plan.measuredRuns,
            samples = samples.toList(),
            averageNativeTokensPerSecond = nativeRates.average(),
            medianNativeTokensPerSecond = nativeRates.median(),
            averageWallTokensPerSecond = wallRates.average(),
            medianWallTokensPerSecond = wallRates.median(),
            averageWallElapsedMillis = samples.map { it.wallElapsedMillis.toDouble() }.average(),
        )
    }

    private fun List<Double>.median(): Double {
        require(isNotEmpty()) { "Cannot calculate a median from an empty sample list." }
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
