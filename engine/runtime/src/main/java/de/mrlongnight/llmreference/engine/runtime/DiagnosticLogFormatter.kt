package de.mrlongnight.llmreference.engine.runtime

/**
 * Compact, single-line log grammar intended to be readable in a plain text editor and grep-friendly.
 *
 * Example:
 * 2026-11-16 07:18:58.123Z | I | inference | generation/native_completed | tokens=64 duration_ms=812
 */
internal object DiagnosticLogFormatter {
    const val MAX_LINE_CHARACTERS = 1_400

    fun format(
        timestamp: String,
        level: String,
        process: String,
        component: String,
        event: String,
        attributes: Map<String, Any>
    ): String {
        val levelCode = when (level) {
            "ERROR" -> "E"
            "WARN" -> "W"
            else -> "I"
        }
        val safeProcess = field(process, 16)
        val safeComponent = field(component, 24)
        val safeEvent = field(event, 40)
        val header = "$timestamp | $levelCode | $safeProcess | $safeComponent/$safeEvent"
        if (attributes.isEmpty()) return header

        val details = attributes.toSortedMap().entries.joinToString(" ") { (key, item) ->
            "${field(key, 48)}=${value(item)}"
        }
        val fullLine = "$header | $details"
        return if (fullLine.length <= MAX_LINE_CHARACTERS) {
            fullLine
        } else {
            fullLine.take(MAX_LINE_CHARACTERS - 1) + "…"
        }
    }

    private fun field(value: String, maxLength: Int): String =
        value.trim()
            .replace(Regex("[\\r\\n\\t|]+"), "_")
            .take(maxLength)
            .ifBlank { "unknown" }

    private fun value(value: Any): String = when (value) {
        is String -> {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
            "\"$escaped\""
        }
        is Enum<*> -> value.name
        else -> value.toString()
    }
}
