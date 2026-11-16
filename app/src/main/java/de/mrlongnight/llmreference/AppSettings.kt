package de.mrlongnight.llmreference

import android.content.Context
import de.mrlongnight.llmreference.engine.api.InferenceBackend

data class InferenceAppSettings(
    val contextTokens: Int = 2048,
    val threads: Int = 0,
    val maxOutputTokens: Int = 64,
    val timeoutMillis: Long = 180_000L,
    val temperature: Float = 0f,
    val topP: Float = 1f,
    val topK: Int = 0,
    val seed: Int = 42,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val gpuLayers: Int = -1,
    val quickWarmups: Int = 1,
    val quickMeasurements: Int = 3,
    val fullWarmups: Int = 2,
    val fullMeasurements: Int = 8,
)

class InferenceSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("inference_settings", Context.MODE_PRIVATE)

    fun load() = InferenceAppSettings(
        contextTokens = prefs.getInt("context_tokens", 2048).coerceIn(256, 131072),
        threads = prefs.getInt("threads", 0).coerceIn(0, 256),
        maxOutputTokens = prefs.getInt("max_output_tokens", 64).coerceIn(1, 8192),
        timeoutMillis = prefs.getLong("timeout_ms", 180_000L).coerceIn(1_000L, 600_000L),
        temperature = prefs.getFloat("temperature", 0f).coerceIn(0f, 2f),
        topP = prefs.getFloat("top_p", 1f).coerceIn(0.01f, 1f),
        topK = prefs.getInt("top_k", 0).coerceIn(0, 1024),
        seed = prefs.getInt("seed", 42).coerceAtLeast(0),
        backend = runCatching { InferenceBackend.valueOf(prefs.getString("backend", "CPU") ?: "CPU") }
            .getOrDefault(InferenceBackend.CPU),
        gpuLayers = prefs.getInt("gpu_layers", -1).coerceIn(-1, 256),
        quickWarmups = prefs.getInt("quick_warmups", 1).coerceIn(0, 10),
        quickMeasurements = prefs.getInt("quick_measurements", 3).coerceIn(1, 20),
        fullWarmups = prefs.getInt("full_warmups", 2).coerceIn(0, 10),
        fullMeasurements = prefs.getInt("full_measurements", 8).coerceIn(1, 50),
    )

    fun save(value: InferenceAppSettings) {
        prefs.edit()
            .putInt("context_tokens", value.contextTokens)
            .putInt("threads", value.threads)
            .putInt("max_output_tokens", value.maxOutputTokens)
            .putLong("timeout_ms", value.timeoutMillis)
            .putFloat("temperature", value.temperature)
            .putFloat("top_p", value.topP)
            .putInt("top_k", value.topK)
            .putInt("seed", value.seed)
            .putString("backend", value.backend.name)
            .putInt("gpu_layers", value.gpuLayers)
            .putInt("quick_warmups", value.quickWarmups)
            .putInt("quick_measurements", value.quickMeasurements)
            .putInt("full_warmups", value.fullWarmups)
            .putInt("full_measurements", value.fullMeasurements)
            .apply()
    }
}
