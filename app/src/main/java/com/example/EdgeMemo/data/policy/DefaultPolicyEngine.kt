package com.example.EdgeMemo.data.policy

import com.example.EdgeMemo.core.policy.PolicyDecision
import com.example.EdgeMemo.core.policy.PolicyInput
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.domain.policy.PolicyEngine
import com.example.EdgeMemo.domain.policy.RedactionService

/**
 * Deterministic, ordered policy rule engine.
 *
 * Rule priority (first match wins):
 *
 *  1. HARD LOCAL_ONLY — access/credential content, `RESTRICTED` sensitivity,
 *     `personal` scope, or a `local-only`-style tag. Never overridable: the
 *     privacy invariant is architectural, not a user preference.
 *  2. Explicit user choice (`SYNC` / `SYNC_REDACTED`).
 *  3. `SENSITIVE` content → SYNC_REDACTED (private details stay local).
 *  4. Team-shareable types (PROCEDURE, DOCUMENT) → SYNC.
 *  5. Repair records → SYNC_REDACTED (may embed private identifiers).
 *  6. Technical identifiers in content → SYNC_REDACTED.
 *  7. Safe default → LOCAL_ONLY (no team-shareable signal).
 *
 * Every decision carries a deterministic, non-fabricated explanation built
 * from the rules that matched.
 */
class DefaultPolicyEngine(
    private val redaction: RedactionService,
) : PolicyEngine {

    override fun evaluate(input: PolicyInput): PolicyDecision {
        val rule = RULES.firstOrNull { it.isActive(input) } ?: DEFAULT_LOCAL_ONLY_RULE

        val decision = rule.decision(input)
        val redacted = if (decision == SyncDecision.SYNC_REDACTED) {
            redaction.redact(input.title, input.content)
        } else {
            null
        }

        return PolicyDecision(
            syncDecision = decision,
            reason = rule.reason(input),
            sensitivity = input.sensitivity,
            redacted = redacted,
        )
    }

    private class Rule(
        private val matches: (PolicyInput) -> Boolean,
        val decision: (PolicyInput) -> SyncDecision,
        val reason: (PolicyInput) -> String,
    ) {
        fun isActive(input: PolicyInput): Boolean = matches(input)
    }

    private fun constantRule(
        matches: (PolicyInput) -> Boolean,
        decision: SyncDecision,
        reason: (PolicyInput) -> String,
    ): Rule = Rule(matches, { decision }, reason)

    private val RULES: List<Rule> = listOf(
        constantRule(
            matches = { it.containsAccessInformation() },
            decision = SyncDecision.LOCAL_ONLY,
            reason = { ACCESS_REASON },
        ),
        constantRule(
            matches = { it.sensitivity == MemorySensitivity.RESTRICTED || it.scope.equals("personal", ignoreCase = true) },
            decision = SyncDecision.LOCAL_ONLY,
            reason = {
                if (it.sensitivity == MemorySensitivity.RESTRICTED) {
                    "Sensitivity is RESTRICTED — memory stays on device."
                } else {
                    "Scope is personal — memory stays on device."
                }
            },
        ),
        constantRule(
            matches = { it.tags.any { tag -> tag.lowercase() in LOCAL_ONLY_TAGS } },
            decision = SyncDecision.LOCAL_ONLY,
            reason = {
                val tag = it.tags.firstOrNull { t -> t.lowercase() in LOCAL_ONLY_TAGS }
                "Tagged $tag — memory stays on device."
            },
        ),
        Rule(
            matches = { it.userSyncChoice != null },
            decision = { input -> input.userSyncChoice ?: SyncDecision.LOCAL_ONLY },
            reason = { "User explicitly requested ${it.userSyncChoice}. Synchronization honors that choice." },
        ),
        constantRule(
            matches = { it.sensitivity == MemorySensitivity.SENSITIVE },
            decision = SyncDecision.SYNC_REDACTED,
            reason = { "Sensitivity is SENSITIVE — only a redacted copy may synchronize." },
        ),
        constantRule(
            matches = { it.type == MemoryType.PROCEDURE || it.type == MemoryType.DOCUMENT },
            decision = SyncDecision.SYNC,
            reason = { "${it.type.name.lowercase()} memory is team-shareable." },
        ),
        constantRule(
            matches = { it.type == MemoryType.REPAIR },
            decision = SyncDecision.SYNC_REDACTED,
            reason = { "Repair records may embed private identifiers — only a redacted copy may synchronize." },
        ),
        constantRule(
            matches = { it.containsIdentifier() },
            decision = SyncDecision.SYNC_REDACTED,
            reason = { "Contains technical identifiers — only a redacted copy may synchronize." },
        ),
    )

    private val DEFAULT_LOCAL_ONLY_RULE = Rule(
        matches = { true },
        decision = { SyncDecision.LOCAL_ONLY },
        reason = { "No team-shareable signal detected — defaults to local only." },
    )

    private fun PolicyInput.containsAccessInformation(): Boolean =
        text.lowercase().contains(ACCESS_TERM_REGEX)

    private fun PolicyInput.containsIdentifier(): Boolean =
        QueryNormalizer.tokens(QueryNormalizer.normalize(text)).any(QueryNormalizer::isIdentifier)

    private companion object {
        const val ACCESS_REASON =
            "Detected access or credential information — memory stays on device."

        val ACCESS_TERM_REGEX = Regex(
            "\\b(gate ?code|access code|passcode|password|pin( code)?|security code|credentials?)\\b",
        )

        val LOCAL_ONLY_TAGS = setOf("local-only", "private", "personal")
    }
}