package de.mrlongnight.llmreference.engine.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticLogFormatterTest {
    @Test
    fun rendersStableHumanReadableColumnsAndSortedFields() {
        val line = DiagnosticLogFormatter.format(
            timestamp = "2026-11-16 08:00:00.123Z",
            level = "WARN",
            process = "inference",
            component = "generation",
            event = "native_interrupted",
            attributes = mapOf(
                "tokens" to 17,
                "elapsed_ms" to 912L,
                "reason" to "deadline expired"
            )
        )

        assertTrue(line.startsWith("2026-11-16 08:00:00.123Z | W | inference | generation/native_interrupted"))
        assertTrue(line.endsWith("elapsed_ms=912 reason=\"deadline expired\" tokens=17"))
    }

    @Test
    fun escapesLineBreaksAndCapsOversizedRecords() {
        val escaped = DiagnosticLogFormatter.format(
            timestamp = "t",
            level = "INFO",
            process = "app",
            component = "test",
            event = "escaped",
            attributes = mapOf("message" to "first line\nsecond line")
        )
        assertTrue(!escaped.contains('\n'))
        assertTrue(escaped.contains("first line\\nsecond line"))

        val capped = DiagnosticLogFormatter.format(
            timestamp = "t",
            level = "INFO",
            process = "app",
            component = "test",
            event = "large",
            attributes = mapOf("detail" to "x".repeat(3_000))
        )
        assertEquals(DiagnosticLogFormatter.MAX_LINE_CHARACTERS, capped.length)
        assertTrue(capped.endsWith("…"))
    }

    @Test
    fun usesCompactSeverityCodes() {
        val line = DiagnosticLogFormatter.format(
            timestamp = "t",
            level = "ERROR",
            process = "app",
            component = "ipc",
            event = "send_failed",
            attributes = emptyMap()
        )
        assertEquals("t | E | app | ipc/send_failed", line)
    }
}
