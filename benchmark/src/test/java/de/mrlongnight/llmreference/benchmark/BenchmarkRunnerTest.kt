package de.mrlongnight.llmreference.benchmark

import de.mrlongnight.llmreference.engine.api.GenerationConfig
import de.mrlongnight.llmreference.engine.api.GenerationResult
import de.mrlongnight.llmreference.engine.api.LocalInferenceEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BenchmarkRunnerTest {
    @Test
    fun warmupsAreExcludedAndPrefillDecodeRatesAreSeparated() = runBlocking {
        val engine = FakeInferenceEngine(
            results = listOf(
                result(generatedTokens = 1), // warmup
                result(generatedTokens = 2),
                result(generatedTokens = 4),
                result(generatedTokens = 6),
            )
        )

        val report = BenchmarkRunner().run(
            engine = engine,
            modelDisplayName = "fixture.gguf",
            backendLabel = "CPU",
            plan = BenchmarkPlan(
                prompt = "fixed diagnostic prompt",
                maxOutputTokens = 8,
                warmupRuns = 1,
                measuredRuns = 3,
            ),
        )

        assertEquals(4, engine.calls)
        assertEquals(3, report.samples.size)
        assertEquals(2, report.samples.first().generatedTokens)
        assertEquals(10.0, report.averagePromptTokensPerSecond, 0.001)
        assertEquals(8.0, report.averageGenerationTokensPerSecond, 0.001)
        assertEquals(8.0, report.medianGenerationTokensPerSecond, 0.001)
    }

    private fun result(generatedTokens: Int) = GenerationResult(
        text = "not retained in report",
        promptTokens = 5,
        generatedTokens = generatedTokens,
        promptElapsedMillis = 500,
        generationElapsedMillis = 500,
        elapsedMillis = 1_000,
    )

    private class FakeInferenceEngine(
        private val results: List<GenerationResult>,
    ) : LocalInferenceEngine {
        var calls: Int = 0
            private set

        override suspend fun loadModel(model: de.mrlongnight.llmreference.engine.api.ModelDescriptor) = Unit

        override suspend fun generate(prompt: String, config: GenerationConfig): GenerationResult {
            return results[calls++]
        }

        override suspend fun unload() = Unit
    }
}
