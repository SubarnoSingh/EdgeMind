package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.record.LocalRecordStore
import com.example.EdgeMemo.core.record.Record
import com.example.EdgeMemo.core.record.RecordFilter
import com.example.EdgeMemo.core.record.RecordQuery
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.data.local.sync.CloudApplyOutcome
import com.example.EdgeMemo.data.local.sync.ConflictEvidence
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantConflictRecorder
import com.example.EdgeMemo.data.repository.MemoryRecordMapper
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudAnswerCache
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeClassifier
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.KnowledgeClassification
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 13.4 — Qdrant-native cloud-answer localization. The explicit user
 * action ("save this cloud answer") writes into the SAME application shard
 * as every other memory, through the SAME frozen §12 single-item pipeline as
 * cloud pull ([DefaultQdrantSyncEngine.applyCloudItem]) — validation,
 * version semantics, conflict evidence and echo immunity are not
 * reimplemented here.
 *
 * Phase 7/8 decision semantics are preserved verbatim: the answer becomes a
 * [CloudKnowledgeItem] (same subject-key derivation, title prefix, validation
 * limits, content-hash convention as the legacy cache) and the existing
 * [CloudKnowledgeClassifier] decides against the current local record for
 * that subject. Only NEW knowledge is stored (CLOUD origin, sync-marked,
 * never echoed back to the cloud); DUPLICATE is a no-op; any divergence
 * REFUSES to overwrite and — for a genuine CONFLICT — records durable
 * two-sided evidence through the same deterministic
 * [QdrantConflictRecorder] point ids the pull path uses, so it appears in
 * the (now Qdrant-backed) Conflicts list and is resolvable.
 */
class QdrantCloudAnswerCache(
    private val recordStore: LocalRecordStore,
    private val engine: DefaultQdrantSyncEngine,
    private val classifier: CloudKnowledgeClassifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudAnswerCache {

    private val conflictRecorder = QdrantConflictRecorder(recordStore) { clock() }

    override suspend fun save(
        question: String,
        answer: String,
        authority: String?,
    ): CacheCloudAnswerResult = withContext(dispatcher) {
        val trimmedQuestion = question.trim()
        if (trimmedQuestion.isEmpty()) {
            throw EdgeError.InvalidInput("cloud answer question is empty")
        }
        val content = answer.trim()
        if (content.isEmpty()) {
            throw EdgeError.InvalidInput("cloud answer content is empty")
        }
        if (content.length > MAX_ANSWER_CHARS) {
            throw EdgeError.InvalidInput("cloud answer exceeds $MAX_ANSWER_CHARS characters")
        }

        val normalizedQuestion = QueryNormalizer.normalize(trimmedQuestion)
        val subjectKey = subjectKey(normalizedQuestion)
        val title = CLOUD_ANSWER_TITLE_PREFIX + trimmedQuestion.take(60)
        val item = CloudKnowledgeItem(
            memoryId = idGenerator(),
            subjectKey = subjectKey,
            title = title,
            content = content,
            contentHash = contentHash(title, content),
            version = 1,
            updatedAt = clock(),
            origin = "CLOUD",
            authority = authority,
            supersedes = null,
            tombstone = false,
            metadata = mapOf(SOURCE_METADATA_KEY to "CLOUD_ANSWER", QUESTION_METADATA_KEY to trimmedQuestion),
        )

        val local = findLocalBySubject(subjectKey)
        val result = classifier.classify(item, local?.let { MemoryRecordMapper.toMemory(it) })
        when (result.classification) {
            KnowledgeClassification.NEW -> when (val applied = engine.applyCloudItem(item)) {
                is CloudApplyOutcome.Applied ->
                    CacheCloudAnswerResult.Saved(MemoryRecordMapper.toMemory(applied.record))
                CloudApplyOutcome.Duplicate -> CacheCloudAnswerResult.AlreadyPresent
                is CloudApplyOutcome.Conflict -> conflictPrevented(
                    "not saved: existing local knowledge for this question differs" +
                        " — conflict ${applied.conflict.id.uuid.take(8)} recorded for review in Conflicts",
                )
                CloudApplyOutcome.Stale -> conflictPrevented(
                    "not saved: existing local knowledge for this question is newer",
                )
                CloudApplyOutcome.RejectedInvalid -> throw EdgeError.LocalStorageError(
                    "cloud answer item failed local validation",
                )
            }
            KnowledgeClassification.DUPLICATE -> CacheCloudAnswerResult.AlreadyPresent
            else -> {
                val existing = local
                    ?: throw EdgeError.LocalStorageError(
                        "divergent cloud answer has no local record to preserve",
                    )
                val message = if (result.classification == KnowledgeClassification.CONFLICT) {
                    val conflict = conflictRecorder.record(
                        subject = subjectKey,
                        local = existing,
                        incoming = ConflictEvidence(
                            recordId = item.memoryId,
                            version = item.version,
                            contentHash = item.contentHash,
                            origin = item.origin,
                            authority = item.authority,
                            title = item.title,
                            content = item.content,
                            tombstone = item.tombstone,
                        ),
                        reason = result.reason,
                    )
                    "not saved: existing local knowledge for this question differs" +
                        " (${existing.id.uuid.take(8)}) — conflict " +
                        "${conflict.id.uuid.take(8)} recorded for review in Conflicts"
                } else {
                    "not saved: existing local knowledge for this question differs" +
                        " (${existing.id.uuid.take(8)})"
                }
                conflictPrevented(message)
            }
        }
    }

    private suspend fun findLocalBySubject(subjectKey: String): Record? =
        recordStore.scroll(
            RecordQuery.Scroll(
                filter = RecordFilter.and(
                    RecordFilter.activeOnly(),
                    RecordFilter.subjectKey(subjectKey),
                ),
                limit = 2,
                recordTypes = MemoryRecordMapper.APP_MEMORY_RECORD_TYPES,
            ),
        ).records.firstOrNull()

    private fun conflictPrevented(reason: String) =
        CacheCloudAnswerResult.ConflictPrevented(reason)

    private fun subjectKey(normalizedQuestion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedQuestion.toByteArray(Charsets.UTF_8))
        return SUBJECT_PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    private fun contentHash(title: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$title\n$content".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val SUBJECT_PREFIX = DefaultCloudAnswerCache.SUBJECT_PREFIX
        const val CLOUD_ANSWER_TITLE_PREFIX = DefaultCloudAnswerCache.CLOUD_ANSWER_TITLE_PREFIX
        const val SOURCE_METADATA_KEY = DefaultCloudAnswerCache.SOURCE_METADATA_KEY
        const val QUESTION_METADATA_KEY = DefaultCloudAnswerCache.QUESTION_METADATA_KEY
        const val MAX_ANSWER_CHARS = DefaultCloudAnswerCache.MAX_ANSWER_CHARS
    }
}
