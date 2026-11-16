package de.mrlongnight.llmreference.engine.runtime

import java.util.Locale

/**
 * Central allow-by-default scalar policy with explicit sensitive-key and sensitive-value guards.
 * Diagnostic callers should still avoid passing user content in the first place.
 */
internal object DiagnosticAttributePolicy {
    const val REDACTED = "[REDACTED]"
    private const val MAX_STRING_LENGTH = 256

    private val sensitiveKeys = setOf(
        "prompt",
        "prompt_text",
        "completion",
        "completion_text",
        "generated_text",
        "output_text",
        "text",
        "uri",
        "path",
        "file_path",
        "absolute_path",
        "source_uri",
        "model_uri",
        "document_uri",
        "password",
        "secret",
        "credential",
        "credentials",
        "authorization",
        "api_key",
        "access_token",
        "refresh_token"
    )

    fun sanitize(attributes: Map<String, Any?>): Map<String, Any> {
        val result = LinkedHashMap<String, Any>(attributes.size)
        attributes.forEach { (rawKey, value) ->
            val key = rawKey.trim()
                .take(64)
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            if (key.isBlank() || value == null) return@forEach

            val normalizedKey = key.lowercase(Locale.ROOT).replace('-', '_')
            val safeValue = when {
                isSensitiveKey(normalizedKey) -> REDACTED
                value is String -> sanitizeString(value)
                value is Boolean -> value
                value is Byte || value is Short || value is Int || value is Long -> value
                value is Float && value.isFinite() -> value
                value is Double && value.isFinite() -> value
                value is Enum<*> -> value.name
                else -> REDACTED
            }
            result[key] = safeValue
        }
        return result
    }

    private fun isSensitiveKey(key: String): Boolean =
        key in sensitiveKeys ||
            key.endsWith("_uri") ||
            key.endsWith("_path") ||
            key.contains("password") ||
            key.contains("secret") ||
            key.contains("credential") ||
            key.endsWith("_api_key") ||
            key.endsWith("_access_token") ||
            key.endsWith("_refresh_token")

    private fun sanitizeString(value: String): String {
        val trimmed = value.trim()
        val looksLikeUri = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(trimmed)
        val looksLikeAbsolutePath = trimmed.startsWith("/") ||
            Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(trimmed)
        if (looksLikeUri || looksLikeAbsolutePath) return REDACTED
        return if (trimmed.length > MAX_STRING_LENGTH) {
            trimmed.take(MAX_STRING_LENGTH) + "…"
        } else {
            trimmed
        }
    }
}
