package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.KnowledgeClassification
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure, deterministic classification rules — no IO, no randomness. */
class DefaultKnowledgeClassifierTest {

    private val classifier = DefaultKnowledgeClassifier()

    @Test
    fun newCloudKnowledgeIsNew() {
        val result = classifier.classify(item(), null)
        assertEquals(KnowledgeClassification.NEW, result.classification)
        assertEquals(null, result.local)
    }

    @Test
    fun sameIdentityAndContentIsDuplicate() {
        val local = local(memoryId = "cloud-1")
        val result = classifier.classify(item(memoryId = "cloud-1", contentHash = local.contentHash), local)
        assertEquals(KnowledgeClassification.DUPLICATE, result.classification)
    }

    @Test
    fun sameIdentityNewerVersionOnCloudRecordIsUpdate() {
        val local = local(memoryId = "cloud-1", origin = MemoryOrigin.CLOUD, version = 2, contentHash = "old-hash")
        val result = classifier.classify(item(memoryId = "cloud-1", contentHash = "new-hash", version = 3), local)
        assertEquals(KnowledgeClassification.UPDATE, result.classification)
    }

    @Test
    fun sameIdentityLocalOriginDivergenceIsConflict() {
        val local = local(memoryId = "cloud-1", origin = MemoryOrigin.LOCAL, version = 5, contentHash = "old-hash")
        val result = classifier.classify(item(memoryId = "cloud-1", contentHash = "new-hash", version = 6), local)
        assertEquals(KnowledgeClassification.CONFLICT, result.classification)
    }

    @Test
    fun sameSubjectDifferentRecordsIsConflict() {
        val local = local(memoryId = "local-1", subjectKey = "TORQUE", contentHash = "h-45nm")
        val result = classifier.classify(item(memoryId = "cloud-1", subjectKey = "TORQUE", contentHash = "h-52nm"), local)
        assertEquals(KnowledgeClassification.CONFLICT, result.classification)
    }

    @Test
    fun sameSubjectIdenticalContentIsDuplicate() {
        val local = local(memoryId = "local-1", subjectKey = "TORQUE", contentHash = "h-same")
        val result = classifier.classify(item(memoryId = "cloud-1", subjectKey = "TORQUE", contentHash = "h-same"), local)
        assertEquals(KnowledgeClassification.DUPLICATE, result.classification)
    }

    @Test
    fun targetedSupersedeOnCloudRecordIsSupersedes() {
        val local = local(memoryId = "proc-1", origin = MemoryOrigin.CLOUD)
        val result = classifier.classify(item(memoryId = "proc-2", supersedes = "proc-1"), local)
        assertEquals(KnowledgeClassification.SUPERSEDES, result.classification)
    }

    @Test
    fun untargetedSupersedeIsNew() {
        val local = local(memoryId = "unrelated", origin = MemoryOrigin.CLOUD)
        val result = classifier.classify(item(memoryId = "proc-2", supersedes = "proc-99"), local)
        assertEquals(KnowledgeClassification.NEW, result.classification)
    }

    @Test
    fun cloudTombstoneOnCloudRecordIsTombstone() {
        val local = local(memoryId = "cloud-1", origin = MemoryOrigin.CLOUD)
        val result = classifier.classify(item(memoryId = "cloud-1", tombstone = true), local)
        assertEquals(KnowledgeClassification.TOMBSTONE, result.classification)
    }

    @Test
    fun cloudTombstoneOnEditedLocalIsConflict() {
        val local = local(memoryId = "cloud-1", origin = MemoryOrigin.LOCAL, contentHash = "edited")
        val result = classifier.classify(item(memoryId = "cloud-1", tombstone = true, contentHash = "cloud-old"), local)
        assertEquals(KnowledgeClassification.CONFLICT, result.classification)
    }

    @Test
    fun tombstoneForUnknownRecordIsNoOp() {
        val result = classifier.classify(item(memoryId = "never-seen", tombstone = true), null)
        assertEquals(KnowledgeClassification.NO_OP, result.classification)
    }

    @Test
    fun localOnlyIsNeverOverwritten() {
        val local = local(memoryId = "gate-1", origin = MemoryOrigin.LOCAL, syncDecision = SyncDecision.LOCAL_ONLY, contentHash = "h-private")
        // even a newer cloud version of the same identity cannot overwrite it
        val result = classifier.classify(item(memoryId = "gate-1", contentHash = "h-cloud", version = 9), local)
        assertEquals(KnowledgeClassification.CONFLICT, result.classification)
    }

    @Test
    fun identicalLocalOnlyIsDuplicateNotWrite() {
        val local = local(memoryId = "gate-1", origin = MemoryOrigin.LOCAL, syncDecision = SyncDecision.LOCAL_ONLY, contentHash = "h-same")
        val result = classifier.classify(item(memoryId = "gate-1", contentHash = "h-same"), local)
        assertEquals(KnowledgeClassification.DUPLICATE, result.classification)
    }

    @Test
    fun classificationIsDeterministic() {
        val local = local(memoryId = "cloud-1", origin = MemoryOrigin.CLOUD, version = 2, contentHash = "old-hash")
        val incoming = item(memoryId = "cloud-1", contentHash = "new-hash", version = 3)
        val a = classifier.classify(incoming, local)
        val b = classifier.classify(incoming, local)
        assertEquals(a.classification, b.classification)
        assertEquals(a.reason, b.reason)
    }

    private fun local(
        memoryId: String,
        subjectKey: String? = null,
        contentHash: String = "hash-local",
        origin: MemoryOrigin = MemoryOrigin.CLOUD,
        syncDecision: SyncDecision = SyncDecision.SYNC,
        version: Int = 2,
    ) = Memory(
        memoryId = memoryId,
        title = "Local title",
        content = "local content",
        chunkId = null,
        source = "test",
        type = MemoryType.NOTE,
        tags = emptyList(),
        createdAt = 1L,
        updatedAt = 1L,
        origin = origin,
        syncDecision = syncDecision,
        syncState = MemorySyncState.SYNCED,
        sensitivity = MemorySensitivity.STANDARD,
        importance = 0,
        version = version,
        contentHash = contentHash,
        subjectKey = subjectKey,
        supersedes = null,
        tombstone = false,
        metadata = emptyMap(),
    )

    private fun item(
        memoryId: String = "cloud-1",
        subjectKey: String? = null,
        contentHash: String = "hash-cloud",
        version: Int = 3,
        supersedes: String? = null,
        tombstone: Boolean = false,
        origin: String = "CLOUD",
        authority: String? = "central-engineering",
    ) = CloudKnowledgeItem(
        memoryId = memoryId,
        subjectKey = subjectKey,
        title = "Cloud title",
        content = "cloud content",
        contentHash = contentHash,
        version = version,
        updatedAt = 100L,
        origin = origin,
        authority = authority,
        supersedes = supersedes,
        tombstone = tombstone,
    )
}