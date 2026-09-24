package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.cloud.ClassificationResult
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeClassifier
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.KnowledgeClassification

/**
 * Deterministic, order-stable classifier. See the rules documented on
 * [CloudKnowledgeClassifier]. Pure — no IO, no randomness.
 */
class DefaultKnowledgeClassifier : CloudKnowledgeClassifier {

    override fun classify(item: CloudKnowledgeItem, local: Memory?): ClassificationResult {
        if (local == null) return classifyAgainstNothing(item)

        // Phase 5 invariant: LOCAL_ONLY device knowledge is never overwritten
        // by cloud content, even for the same memoryId.
        if (local.syncDecision == SyncDecision.LOCAL_ONLY) {
            return if (local.contentHash == item.contentHash) {
                ClassificationResult(KnowledgeClassification.DUPLICATE, local, "identical local-only record")
            } else {
                ClassificationResult(
                    KnowledgeClassification.CONFLICT,
                    local,
                    "incoming cloud knowledge differs from LOCAL_ONLY device memory (${local.memoryId} / ${item.memoryId})",
                )
            }
        }

        if (item.tombstone) return classifyTombstone(item, local)

        if (item.supersedes != null) {
            val targeted = item.supersedes == local.memoryId
            if (!targeted) {
                // A superseding item for a record we do not hold is simply new.
                return ClassificationResult(KnowledgeClassification.NEW, null, "no local record for supersede target")
            }
            val cloudSide = local.origin == MemoryOrigin.CLOUD || local.origin == MemoryOrigin.SYNCED
            val sameContent = local.contentHash == item.contentHash
            return if (cloudSide || sameContent) {
                ClassificationResult(
                    KnowledgeClassification.SUPERSEDES,
                    local,
                    "cloud supersedes ${item.supersedes} (version ${item.version})",
                )
            } else {
                ClassificationResult(
                    KnowledgeClassification.CONFLICT,
                    local,
                    "cloud supersede conflicts with locally-edited record ${local.memoryId}",
                )
            }
        }

        if (local.memoryId == item.memoryId) {
            if (local.contentHash == item.contentHash) {
                return ClassificationResult(KnowledgeClassification.DUPLICATE, local, "same memory identity and content")
            }
            val cloudSide = local.origin == MemoryOrigin.CLOUD || local.origin == MemoryOrigin.SYNCED
            if (cloudSide && item.version > local.version) {
                return ClassificationResult(
                    KnowledgeClassification.UPDATE,
                    local,
                    "cloud version ${item.version} supersedes local version ${local.version} of ${local.memoryId}",
                )
            }
            return ClassificationResult(
                KnowledgeClassification.CONFLICT,
                local,
                "same memory identity diverged (local v${local.version} vs cloud v${item.version})",
            )
        }

        // Different record matching on subjectKey only.
        if (local.subjectKey != null && local.subjectKey == item.subjectKey) {
            if (local.contentHash == item.contentHash) {
                return ClassificationResult(KnowledgeClassification.DUPLICATE, local, "same subject and content")
            }
            return ClassificationResult(
                KnowledgeClassification.CONFLICT,
                local,
                "contradictory knowledge for subject ${item.subjectKey} (${local.memoryId} vs ${item.memoryId})",
            )
        }

        return classifyAgainstNothing(item)
    }

    private fun classifyAgainstNothing(item: CloudKnowledgeItem): ClassificationResult {
        if (item.tombstone) {
            return ClassificationResult(KnowledgeClassification.NO_OP, null, "tombstone for unknown record")
        }
        if (item.supersedes != null) {
            return ClassificationResult(KnowledgeClassification.NEW, null, "superseding record arrives as new")
        }
        return ClassificationResult(KnowledgeClassification.NEW, null, "new cloud knowledge")
    }

    private fun classifyTombstone(item: CloudKnowledgeItem, local: Memory): ClassificationResult {
        val cloudSide = local.origin == MemoryOrigin.CLOUD || local.origin == MemoryOrigin.SYNCED
        val sameContent = local.contentHash == item.contentHash
        if (cloudSide || sameContent) {
            return ClassificationResult(
                KnowledgeClassification.TOMBSTONE,
                local,
                "cloud tombstone for ${item.memoryId} (v${item.version})",
            )
        }
        return ClassificationResult(
            KnowledgeClassification.CONFLICT,
            local,
            "cloud tombstone conflicts with locally-edited record ${local.memoryId}",
        )
    }
}