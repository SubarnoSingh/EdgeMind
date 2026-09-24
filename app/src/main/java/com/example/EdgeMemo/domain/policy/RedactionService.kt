package com.example.EdgeMemo.domain.policy

import com.example.EdgeMemo.core.policy.RedactedRepresentation

/**
 * Produces the safe, sync-ready representation of private content. Must be
 * deterministic: the same input always produces the same redacted output, so
 * redaction is testable and reproducible.
 *
 * The original text is never modified by this interface; the redacted copy is
 * stored separately and is the only representation allowed to sync.
 */
interface RedactionService {
    fun redact(title: String, content: String): RedactedRepresentation
}
