package com.example.EdgeMemo.data.cloud

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.data.local.room.ConflictDao
import com.example.EdgeMemo.data.local.room.ConflictEntity
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import java.security.MessageDigest

/**
 * The single Phase 7 conflict-persistence path, shared by the cloud-knowledge
 * pull ([DefaultCloudKnowledgeIngestor]) and the explicit cloud-answer cache
 * ([DefaultCloudAnswerCache]) so both produce identical, idempotent conflict
 * records that the existing [com.example.EdgeMemo.domain.conflict.ConflictResolver]
 * can resolve. Both sides' evidence is retained in the row; nothing here ever
 * touches the sync outbox.
 */
internal class CloudConflictRecorder(
    private val conflictDao: ConflictDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun record(
        item: CloudKnowledgeItem,
        local: Memory,
        reason: String,
    ): ConflictEntity {
        val subject = item.subjectKey ?: local.subjectKey ?: ""
        val entity = ConflictEntity(
            conflictId = conflictId(subject, local, item),
            subjectKey = subject,
            localMemoryId = local.memoryId,
            incomingMemoryId = item.memoryId,
            localTitle = local.title,
            incomingTitle = item.title,
            localContent = local.content,
            incomingContent = item.content,
            localVersion = local.version,
            incomingVersion = item.version,
            localContentHash = local.contentHash,
            incomingContentHash = item.contentHash,
            localOrigin = local.origin.name,
            incomingOrigin = item.origin,
            localAuthority = local.authority,
            incomingAuthority = item.authority,
            detectedAt = clock(),
            reason = reason,
            state = "UNRESOLVED",
        )
        conflictDao.insertOrIgnore(entity)
        return entity
    }

    companion object {
        /**
         * Deterministic conflict id keyed on the two records' identities and
         * hashes, so re-recording the same contradictory pair never duplicates
         * the conflict row (`insertOrIgnore`).
         */
        fun conflictId(subject: String, local: Memory, item: CloudKnowledgeItem): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(
                buildString {
                    append("conflict|")
                    append(subject)
                    append("|")
                    append(local.memoryId)
                    append("|")
                    append(local.contentHash)
                    append("|")
                    append(item.memoryId)
                    append("|")
                    append(item.contentHash)
                }.toByteArray(Charsets.UTF_8),
            )
            return "conflict-${digest.joinToString("") { "%02x".format(it) }}"
        }
    }
}
