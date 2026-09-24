package com.example.EdgeMemo.core.document

/**
 * Deterministic text normalization shared by every document format. No
 * randomness, locale dependence or external state: the same input always
 * yields the same output.
 */
object TextNormalizer {

    private val controlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")
    private val horizontalSpace = Regex("[\\t\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]+")
    private val trailingSpace = Regex(" +")
    private val manyBlankLines = Regex("\\n{3,}")

    fun normalizeLineBreaks(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /** Collapses whitespace and control characters while preserving paragraph breaks. */
    fun normalize(text: String): String {
        var result = normalizeLineBreaks(text)
        result = result.replace("\uFEFF", "")
        result = controlChars.replace(result, "")
        result = horizontalSpace.replace(result, " ")
        result = result.lines().joinToString("\n") { line ->
            line.trim().replace(trailingSpace, " ")
        }
        result = manyBlankLines.replace(result, "\n\n")
        return result.trim()
    }

    /** Normalizes to a single line (used for titles and inline metadata). */
    fun normalizeInline(text: String): String =
        normalize(text).replace('\n', ' ').trim()
}
