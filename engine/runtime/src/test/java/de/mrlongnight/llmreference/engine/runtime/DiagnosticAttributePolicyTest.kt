package de.mrlongnight.llmreference.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticAttributePolicyTest {
    @Test
    fun redactsPrivateContentAndLocationsButKeepsUsefulMetrics() {
        val safe = DiagnosticAttributePolicy.sanitize(
            mapOf(
                "prompt" to "my private prompt",
                "generated_text" to "private completion",
                "source_uri" to "content://provider/document/123",
                "cache_path" to "/data/user/0/example/cache/model.gguf",
                "authorization" to "Bearer secret",
                "prompt_characters" to 72,
                "prompt_tokens" to 18,
                "generated_tokens" to 12,
                "elapsed_ms" to 345L
            )
        )

        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["prompt"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["generated_text"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["source_uri"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["cache_path"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["authorization"])
        assertEquals(72, safe["prompt_characters"])
        assertEquals(18, safe["prompt_tokens"])
        assertEquals(12, safe["generated_tokens"])
        assertEquals(345L, safe["elapsed_ms"])
    }

    @Test
    fun redactsUriLikeAndAbsolutePathValuesEvenUnderUnexpectedKeys() {
        val safe = DiagnosticAttributePolicy.sanitize(
            mapOf(
                "provider_label" to "content://com.example.documents/document/1",
                "diagnostic_detail" to "/sdcard/private/model.gguf",
                "windows_location" to "C:\\Users\\private\\model.gguf",
                "ordinary_status" to "inference started"
            )
        )

        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["provider_label"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["diagnostic_detail"])
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["windows_location"])
        assertEquals("inference started", safe["ordinary_status"])
    }

    @Test
    fun truncatesLongStringsAndRejectsUnknownObjectValues() {
        val safe = DiagnosticAttributePolicy.sanitize(
            mapOf(
                "label" to "x".repeat(400),
                "unsupported" to Any()
            )
        )

        assertTrue((safe["label"] as String).length < 270)
        assertEquals(DiagnosticAttributePolicy.REDACTED, safe["unsupported"])
    }
}
