package com.example.EdgeMemo.core.policy

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadFactoryTest {

    private fun memory(
        decision: SyncDecision,
        title: String = "Repair",
        content: String = "Replaced P-101 seal",
        redactedTitle: String? = null,
        redactedContent: String? = null,
        origin: MemoryOrigin = MemoryOrigin.LOCAL,
    ) = Memory(
        memoryId = "mem-1",
        title = title,
        content = content,
        chunkId = null,
        source = "USER_ENTRY",
        type = MemoryType.REPAIR,
        tags = emptyList(),
        createdAt = 0L,
        updatedAt = 0L,
        origin = origin,
        syncDecision = decision,
        syncState = MemorySyncState.LOCAL,
        sensitivity = MemorySensitivity.STANDARD,
        importance = 0,
        version = 1,
        contentHash = "hash",
        subjectKey = null,
        supersedes = null,
        tombstone = false,
        metadata = emptyMap(),
        policyReason = "reason",
        redactedTitle = redactedTitle,
        redactedContent = redactedContent,
    )

    @Test
    fun localOnlyHasNoSyncablePayload() {
        assertNull(SyncPayloadFactory.build(memory(SyncDecision.LOCAL_ONLY)))
    }

    @Test
    fun cloudOriginKnowledgeIsNeverPushedBack() {
        // Phase 8 security review hardening: the no-push-back invariant is
        // encoded in the privacy boundary itself, not only in the call graph.
        assertNull(
            SyncPayloadFactory.build(
                memory(SyncDecision.SYNC, title = "Cloud answer: Q", content = "Cloud says 52 Nm.", origin = MemoryOrigin.CLOUD),
            ),
        )
        assertNull(
            SyncPayloadFactory.build(
                memory(SyncDecision.SYNC_REDACTED, origin = MemoryOrigin.CLOUD),
            ),
        )
    }

    @Test
    fun syncHonorsOriginalContent() {
        val payload = SyncPayloadFactory.build(memory(SyncDecision.SYNC, title = "Procedure", content = "Pump maintenance"))
        assertEquals("mem-1", payload!!.memoryId)
        assertEquals(SyncPayloadFactory.UPSERT, payload.operationType)
        assertEquals("Procedure", payload.title)
        assertEquals("Pump maintenance", payload.content)
    }

    @Test
    fun syncRedactedUsesOnlyTheRedactedRepresentation() {
        val payload = SyncPayloadFactory.build(
            memory(
                SyncDecision.SYNC_REDACTED,
                title = "P-101 repair",
                content = "Replaced P-101 seal at Site 7",
                redactedTitle = "repair",
                redactedContent = "Replaced [redacted] seal at Site 7",
            ),
        )
        assertEquals("repair", payload!!.title)
        assertEquals("Replaced [redacted] seal at Site 7", payload.content)
        // private text must never leak through the payload
        assertFalse(payload.content.contains("P-101"))
        assertFalse(payload.title.contains("P-101"))
        assertTrue(payload.content.contains("Site 7"))
    }

    @Test
    fun syncRedactedNeverFallsBackToOriginalWhenRedactionIsMissing() {
        val payload = SyncPayloadFactory.build(memory(SyncDecision.SYNC_REDACTED))
        assertNull("missing redaction must block sync, not leak the private original", payload)
    }

    @Test
    fun syncRedactedNeverFallsBackWhenOnlyContentIsRedacted() {
        val partial = memory(
            SyncDecision.SYNC_REDACTED,
            redactedTitle = null,
            redactedContent = "Replaced [redacted] seal",
        )
        assertNull(SyncPayloadFactory.build(partial))
    }
}