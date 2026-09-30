package com.example.EdgeMemo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One-off hackathon demo preload. Drives the EXACT production write seam
 * (AppContainer.createMemory → CreateMemoryUseCase → QdrantRecordMemoryRepository
 * → Qdrant Edge shard → policy/change-detection/embeddings) with the same
 * arguments the Create Record form would pass. No fakes, no second database.
 */
@RunWith(AndroidJUnit4::class)
class P101DemoSeedTest {

    private data class Seed(
        val type: MemoryType,
        val title: String,
        val content: String,
        val tags: List<String>,
    )

    private val seeds = listOf(
        Seed(
            MemoryType.EVENT,
            "P-101 Mechanical Seal Leakage",
            "Mechanical seal leakage was observed during inspection. " +
                "The pump was removed from service for maintenance.",
            listOf("seal", "leak", "incident"),
        ),
        Seed(
            MemoryType.REPAIR,
            "P-101 Mechanical Seal Replacement",
            "Mechanical seal was replaced and the pump was returned to service " +
                "after inspection.",
            listOf("seal", "repair"),
        ),
        Seed(
            MemoryType.EVENT,
            "P-101 Repeated Seal Failure",
            "Repeated mechanical seal leakage was detected during follow-up inspection.",
            listOf("seal", "leak", "incident"),
        ),
        Seed(
            MemoryType.PROCEDURE,
            "P-101 Seal Failure Investigation",
            "Before replacing the mechanical seal again, inspect suction conditions, " +
                "vibration, seal condition, alignment, and signs of cavitation.",
            listOf("seal", "procedure", "vibration", "cavitation"),
        ),
        Seed(
            MemoryType.NOTE,
            "P-101 Maintenance History",
            "Maintenance history shows repeated seal-related issues requiring " +
                "investigation of underlying operating conditions.",
            listOf("history", "seal"),
        ),
    )

    @Test
    fun seedP101DemoRecordsThroughProductionUseCase() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val container = (app as EdgeMindApplication).container
        val createMemory: CreateMemoryUseCase = container.createMemory
        seeds.forEach { seed ->
            val created = runBlocking {
                createMemory(
                    CreateMemoryInput(
                        title = seed.title,
                        content = seed.content,
                        type = seed.type,
                        tags = seed.tags,
                        subjectKey = "p-101/${seed.type.name.lowercase()}",
                        userSyncChoice = SyncDecision.LOCAL_ONLY,
                    ),
                )
            }
            assertNotNull(created.memoryId)
        }
    }
}
