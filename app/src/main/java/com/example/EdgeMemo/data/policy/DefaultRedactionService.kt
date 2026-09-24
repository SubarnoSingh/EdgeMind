package com.example.EdgeMemo.data.policy

import com.example.EdgeMemo.core.policy.RedactedRepresentation
import com.example.EdgeMemo.domain.policy.RedactionService

/**
 * Deterministic, conservative redaction for `SYNC_REDACTED` payloads.
 *
 * Removes the private tokens that this product can identify reliably without
 * network access or NER, and records exactly what was removed:
 *
 *  - person-name candidates following a known role word (`Technician John`)
 *  - e-mail addresses
 *  - standalone digit runs of length 4+ (codes, accounts)
 *  - technical identifiers containing both letters and digits (`P-101`,
 *    `SKF-6205`, `E-4417`)
 *
 * Everything else — the useful prose — is preserved and synchronized. The
 * original input is never modified; a separate redacted copy is returned. The
 * same input always produces the same output (no randomness).
 */
class DefaultRedactionService : RedactionService {

    override fun redact(title: String, content: String): RedactedRepresentation {
        val removed = LinkedHashSet<String>()
        val redactedTitle = RedactContext(title, removed)
        val redactedContent = RedactContext(content, removed)
        return RedactedRepresentation(
            title = redactedTitle,
            content = redactedContent,
            removedTokens = removed.toList(),
        )
    }

    private fun RedactContext(text: String, removed: MutableSet<String>): String {
        var result = ROLE_NAME_REGEX.replace(text) { match ->
            val role = match.groupValues[1]
            removed.add(match.groupValues[2])
            "$role [redacted]"
        }
        result = EMAIL_REGEX.replace(result) { match ->
            removed.add(match.value)
            "[redacted]"
        }
        result = IDENTIFIER_REGEX.replace(result) { match ->
            if (isTechnicalIdentifier(match.value)) {
                removed.add(match.value)
                "[redacted]"
            } else {
                match.value
            }
        }
        result = DIGIT_RUN_REGEX.replace(result) { match ->
            removed.add(match.value)
            "[redacted]"
        }
        return result
    }

    private fun isTechnicalIdentifier(token: String): Boolean =
        token.length >= 3 && token.any { it.isLetter() } && token.any { it.isDigit() }

    private companion object {
        val ROLE_NAME_REGEX = Regex(
            "(?i)\\b(mr|mrs|ms|dr|sir|madam|technician|engineer|operator|manager|supervisor|" +
                "foreman|specialist|inspector|contractor|employee|worker|apprentice|mechanic)" +
                "\\s+([A-Z][a-z]{2,})\\b",
        )
        val EMAIL_REGEX = Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
        val DIGIT_RUN_REGEX = Regex("\\b\\d{4,}\\b")
        val IDENTIFIER_REGEX = Regex("\\b[a-zA-Z0-9]+(?:[-_/][a-zA-Z0-9]+)*\\b")
    }
}