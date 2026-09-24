package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.data.local.room.CloudCursorDao
import com.example.EdgeMemo.data.local.room.CloudCursorEntity
import com.example.EdgeMemo.data.local.room.ConflictDao
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeClassifier
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeIngestor
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeWriter
import com.example.EdgeMemo.domain.cloud.CloudPullResult
import com.example.EdgeMemo.domain.cloud.KnowledgeClassification

/**
 * One incremental cloud → edge pass. The checkpoint is durable (Room
 * `cloud_pull_cursor`); items are classified deterministically; accepted
 * knowledge is written through [CloudKnowledgeWriter]; contradictions create a
 * persistent conflict row instead of silently overwriting the device.
 */
class DefaultCloudKnowledgeIngestor(
    private val remote: CloudKnowledgeRemoteDataSource,
    private val classifier: CloudKnowledgeClassifier,
    private val writer: CloudKnowledgeWriter,
    private val memoryDao: MemoryDao,
    private val conflictDao: ConflictDao,
    private val cursorDao: CloudCursorDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : CloudKnowledgeIngestor {

    private val conflictRecorder = CloudConflictRecorder(conflictDao, clock)

    override suspend fun pullAndApply(): CloudPullResult {
        var applied = 0
        var duplicates = 0
        var conflicts = 0
        var tombstoned = 0

        val previousCursor = cursorDao.get()?.cursor
        val batch = remote.pullKnowledge(previousCursor)

        for (item in batch.items) {
            val local = resolveLocal(item)
            val result = classifier.classify(item, local)
            when (result.classification) {
                KnowledgeClassification.NEW -> {
                    writer.applyNew(item)
                    applied++
                }
                KnowledgeClassification.UPDATE -> {
                    writer.applyUpdate(item, requireNotNull(local) { "UPDATE requires local" })
                    applied++
                }
                KnowledgeClassification.SUPERSEDES -> {
                    writer.applySupersede(item)
                    applied++
                }
                KnowledgeClassification.TOMBSTONE -> {
                    writer.applyTombstone(item, requireNotNull(local) { "TOMBSTONE requires local" })
                    tombstoned++
                }
                KnowledgeClassification.DUPLICATE -> duplicates++
                KnowledgeClassification.CONFLICT -> {
                    conflictRecorder.record(
                        item,
                        requireNotNull(local) { "CONFLICT requires local" },
                        result.reason,
                    )
                    conflicts++
                }
                KnowledgeClassification.NO_OP -> {
                    // tombstone/supersede without a matching local record: nothing to do
                }
            }
        }

        val next = batch.nextCursor
        if (next != null || batch.items.isNotEmpty() || previousCursor != null) {
            cursorDao.save(CloudCursorEntity("checkpoint", next, clock()))
        }

        return CloudPullResult(
            applied = applied,
            duplicates = duplicates,
            conflicts = conflicts,
            tombstoned = tombstoned,
            noChange = applied == 0 && conflicts == 0 && tombstoned == 0,
            cursor = next,
        )
    }

    private suspend fun resolveLocal(item: CloudKnowledgeItem): Memory? {
        memoryDao.getById(item.memoryId)
            ?.takeIf { !it.tombstone }
            ?.let { return it.toDomain() }
        val subject = item.subjectKey ?: return null
        return memoryDao.findBySubjectKey(subject)?.takeIf { !it.tombstone }?.toDomain()
    }
}