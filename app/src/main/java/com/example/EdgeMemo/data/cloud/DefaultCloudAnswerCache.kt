package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.retrieval.QueryNormalizer
import com.example.EdgeMemo.data.local.room.ConflictDao
import com.example.EdgeMemo.data.local.room.MemoryDao
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerResult
import com.example.EdgeMemo.domain.cloud.CloudAnswerCache
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeClassifier
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeWriter
import com.example.EdgeMemo.domain.cloud.KnowledgeClassification
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit cloud-answer localization. A cloud answer may only enter local
 * memory through an explicit user action calling [save].
 *
 * Phase 7 semantics are reused verbatim: the answer becomes a
 * [com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem] and the existing
 * [CloudKnowledgeClassifier] decides against the current local record (by
 * memoryId, then subjectKey). Only NEW knowledge is stored (as CLOUD-origin,
 * fully synced-marked, never enqueued in the sync outbox). DUPLICATE is a
 * no-op; anything implying an existing divergent record (CONFLICT, UPDATE,
 * SUPERSEDES, TOMBSTONE, NO_OP) refuses to overwrite and returns
 * [CacheCloudAnswerResult.ConflictPrevented] — and a divergent CONFLICT is
 * additionally persisted through the same Phase 7
 * [CloudConflictRecorder] used by the cloud-knowledge pull, so it appears in
 * the Conflicts UI and is resolvable by the existing
 * [com.example.EdgeMemo.domain.conflict.ConflictResolver].
 */
class DefaultCloudAnswerCache(
    private val classifier: CloudKnowledgeClassifier,
    private val writer: CloudKnowledgeWriter,
    private val memoryDao: MemoryDao,
    private val conflictDao: ConflictDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudAnswerCache {

    private val conflictRecorder = CloudConflictRecorder(conflictDao, clock)

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

        val local = memoryDao.findBySubjectKey(subjectKey)
            ?.takeIf { !it.tombstone }
            ?.toDomain()

        val result = classifier.classify(item, local)
        when (result.classification) {
            KnowledgeClassification.NEW -> {
                try {
                    val memory = writer.applyNew(item)
                    CacheCloudAnswerResult.Saved(memory)
                } catch (e: EdgeError) {
                    throw e
                } catch (e: Exception) {
                    throw EdgeError.LocalStorageError("failed to persist cloud answer: ${e.message}", e)
                }
            }
            KnowledgeClassification.DUPLICATE -> CacheCloudAnswerResult.AlreadyPresent
            else -> {
                val existing = local
                    ?: throw EdgeError.LocalStorageError(
                        "divergent cloud answer has no local record to preserve",
                    )
                val recorded = conflictRecorder.record(item, existing, result.reason)
                CacheCloudAnswerResult.ConflictPrevented(
                    "not saved: existing local knowledge for this question differs" +
                        " (${existing.memoryId.take(8)}) — conflict " +
                        "${recorded.conflictId.removePrefix("conflict-").take(8)} recorded for review in Conflicts",
                )
            }
        }
    }

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

    companion object {
        val SUBJECT_PREFIX = "answer:"
        const val CLOUD_ANSWER_TITLE_PREFIX = "Cloud answer: "
        const val SOURCE_METADATA_KEY = "source"
        const val QUESTION_METADATA_KEY = "question"

        /** Cloud responses are validated: no blank answers and no unbounded payloads (spec §53). */
        const val MAX_ANSWER_CHARS = 8_000
    }
}