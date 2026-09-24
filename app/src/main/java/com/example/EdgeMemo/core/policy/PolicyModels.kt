package com.example.EdgeMemo.core.policy

import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision

/**
 * Everything the policy engine is allowed to consider. Inputs are explicit so
 * decisions stay deterministic and explainable: same input → same decision.
 */
data class PolicyInput(
    val title: String,
    val content: String,
    val type: MemoryType,
    val tags: List<String>,
    val sensitivity: MemorySensitivity,
    val importance: Int,
    val scope: String?,
    val userSyncChoice: SyncDecision?,
    val metadata: Map<String, String>,
) {
    /** Combined text used by content heuristics. */
    val text: String
        get() = if (title.isBlank()) content else "$title\n$content"
}

/**
 * The result of a policy evaluation. [reason] is a human-readable explanation
 * of *why* this decision was taken; it is persisted with the memory so the app
 * can always explain current state without guessing.
 */
data class PolicyDecision(
    val syncDecision: SyncDecision,
    val reason: String,
    val sensitivity: MemorySensitivity,
    val redacted: RedactedRepresentation?,
)

/** A safe, sync-ready representation of private content. */
data class RedactedRepresentation(
    val title: String,
    val content: String,
    val removedTokens: List<String>,
)

/**
 * The only representation of a memory that may ever leave the device. Built
 * exclusively through [SyncPayloadFactory], which is the architectural privacy
 * boundary: `LOCAL_ONLY` memories have no payload and therefore cannot enter
 * an outbox or be uploaded.
 */
data class SyncPayload(
    val memoryId: String,
    val operationType: String,
    val title: String,
    val content: String,
)

object SyncPayloadFactory {

    const val UPSERT = "UPSERT"

    /**
     * Returns the syncable representation, or `null` when the memory must never
     * leave the device.
     *
     * - CLOUD-origin knowledge → always `null`: it arrived from the cloud and
     *   is never pushed back (Phase 7/8 invariant, encoded here so it does not
     *   depend on the call graph alone).
     * - `LOCAL_ONLY` → always `null` (no outbox entry, no cloud request).
     * - `SYNC` → the original title/content (explicitly allowed to sync as-is).
     * - `SYNC_REDACTED` → only the stored redacted representation. If it is
     *   missing, `null` is returned instead of falling back to the original
     *   content, so private text can never leak through a "missing payload".
     */
    fun build(memory: com.example.EdgeMemo.core.model.Memory): SyncPayload? =
        when {
            memory.origin == com.example.EdgeMemo.core.model.MemoryOrigin.CLOUD -> null
            memory.syncDecision == SyncDecision.LOCAL_ONLY -> null
            memory.syncDecision == SyncDecision.SYNC -> SyncPayload(
                memoryId = memory.memoryId,
                operationType = UPSERT,
                title = memory.title,
                content = memory.content,
            )
            else -> { // SYNC_REDACTED
                val title = memory.redactedTitle
                val content = memory.redactedContent
                if (title == null || content == null) {
                    null
                } else {
                    SyncPayload(
                        memoryId = memory.memoryId,
                        operationType = UPSERT,
                        title = title,
                        content = content,
                    )
                }
            }
        }
}
