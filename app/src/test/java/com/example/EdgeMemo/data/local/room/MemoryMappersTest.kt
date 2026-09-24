package com.example.EdgeMemo.data.local.room

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryOrigin
import com.example.EdgeMemo.core.model.MemorySensitivity
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.room.MemoryMappers.toDomain
import com.example.EdgeMemo.data.local.room.MemoryMappers.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMappersTest {

    @Test
    fun `entity round trip preserves all memory fields`() {
        val memory = sampleMemory()
        val roundTripped = memory.toEntity().toDomain()
        assertEquals(memory, roundTripped)
    }

    @Test
    fun `entity round trip preserves optional and empty fields`() {
        val memory = sampleMemory().copy(
            chunkId = "chunk-4",
            title = "",
            content = "",
            tags = emptyList(),
            metadata = emptyMap(),
            subjectKey = null,
            supersedes = null,
            tombstone = false,
        )
        assertEquals(memory, memory.toEntity().toDomain())
    }

    @Test
    fun `converters round trip lists and maps with separator characters`() {
        val tags = listOf("p101", "seal failure")
        assertEquals(tags, MemoryTypeConverters.stringToList(MemoryTypeConverters.listToString(tags)))
        assertTrue(MemoryTypeConverters.stringToList("").isEmpty())

        val metadata = mapOf("page" to "17", "section" to "Seal Inspection")
        assertEquals(
            metadata,
            MemoryTypeConverters.stringToMap(MemoryTypeConverters.mapToString(metadata)),
        )
        assertTrue(MemoryTypeConverters.stringToMap("").isEmpty())
    }

    private fun sampleMemory() = Memory(
        memoryId = "11111111-2222-3333-4444-555555555555",
        title = "P-101 seal failure",
        content = "Seal failed. Root cause cavitation.",
        chunkId = null,
        source = "USER_ENTRY",
        type = MemoryType.REPAIR,
        tags = listOf("p101", "seal"),
        createdAt = 1_700_000_000_000,
        updatedAt = 1_700_000_600_000,
        origin = MemoryOrigin.LOCAL,
        syncDecision = SyncDecision.LOCAL_ONLY,
        syncState = MemorySyncState.LOCAL,
        sensitivity = MemorySensitivity.STANDARD,
        importance = 3,
        version = 4,
        contentHash = "abc123",
        subjectKey = "P-101",
        supersedes = "old-memory-id",
        tombstone = true,
        metadata = mapOf("page" to "17"),
    )
}