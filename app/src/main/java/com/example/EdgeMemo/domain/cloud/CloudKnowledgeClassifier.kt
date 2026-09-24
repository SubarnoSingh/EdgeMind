package com.example.EdgeMemo.domain.cloud

import com.example.EdgeMemo.core.model.Memory

/**
 * Deterministic classification of incoming cloud knowledge against the current
 * active local record for the same identity (memoryId) or subject (subjectKey).
 *
 * Priority-ordered rules (documented in WORKING.md Phase 7):
 *
 * 1. `LOCAL_ONLY` local records may never be silently overwritten — divergence
 *    is a CONFLICT, matching the Phase 5 privacy invariant.
 * 2. Incoming tombstone: applied when the local record is cloud/synced origin
 *    or carries the same content; divergent LOCally-authored content is a
 *    CONFLICT; nothing to delete is a no-op.
 * 3. Incoming supersedes: applied when the superseded target is cloud/synced or
 *    content-identical, otherwise CONFLICT.
 * 4. Same memoryId + same contentHash → DUPLICATE.
 * 5. Same memoryId + newer version + cloud/synced origin → UPDATE.
 * 6. Same subjectKey, distinct record: identical content → DUPLICATE,
 *    different content → CONFLICT (e.g. 45 Nm vs 52 Nm).
 * 7. No local record → NEW.
 */
enum class KnowledgeClassification {
    NEW,
    DUPLICATE,
    UPDATE,
    SUPERSEDES,
    CONFLICT,
    TOMBSTONE,
    NO_OP,
}

data class ClassificationResult(
    val classification: KnowledgeClassification,
    /** The active local record compared against (null for a brand-new item). */
    val local: Memory? = null,
    /** Reason, shown verbatim in the Conflicts UI / tests. */
    val reason: String = "",
)

interface CloudKnowledgeClassifier {
    fun classify(item: CloudKnowledgeItem, local: Memory?): ClassificationResult
}