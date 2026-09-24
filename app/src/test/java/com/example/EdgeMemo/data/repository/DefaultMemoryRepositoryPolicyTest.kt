package com.example.EdgeMemo.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.core.policy.SyncPayloadFactory
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.native.qdrant.LocalVectorStore
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.testing.TestNativeLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5 integration tests: real Room + qdrant-edge + real policy engine.
 * Proves decisions are persisted, reloadable, deterministic, explainable, and
 * that LOCAL_ONLY / SYNC_REDACTED never produce a syncable payload with the
 * original private text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DefaultMemoryRepositoryPolicyTest {

    private lateinit var context: Context
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        TestNativeLoader.ensureLoaded()
        context = ApplicationProvider.getApplicationContext()
        sandbox = File(context.cacheDir, "phase5-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    @Test
    fun procedureIsSyncEligibleWithStoredExplanation() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    "Procedure revision 4",
                    "Pump maintenance procedure revision 4",
                    type = MemoryType.PROCEDURE,
                ),
            )
            assertEquals(SyncDecision.SYNC, created.syncDecision)
            assertEquals("procedure memory is team-shareable.", created.policyReason)
            assertNotNull(SyncPayloadFactory.build(created))
        } finally {
            stack.close()
        }
    }

    @Test
    fun gateCodeIsLocalOnlyButStillRetrievableOffline() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput("Site access", "Gate code for Site 7 is 4412"),
            )
            assertEquals(SyncDecision.LOCAL_ONLY, created.syncDecision)
            assertNull("LOCAL_ONLY must have no syncable payload", SyncPayloadFactory.build(created))
            assertEquals(1L, stack.repository.count())

            val hits = stack.repository.search("gate code site seven", 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(created.memoryId, hits.first().memory.memoryId)
            assertEquals("local memory must keep its original text", "Gate code for Site 7 is 4412", hits.first().memory.content)
        } finally {
            stack.close()
        }
    }

    @Test
    fun repairKeepsOriginalLocallyAndRedactsIdentifiers() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput("P-101 repair", "Replaced P-101 seal; root cause cavitation", type = MemoryType.REPAIR),
            )
            assertEquals(SyncDecision.SYNC_REDACTED, created.syncDecision)
            assertEquals("original content must remain local", "Replaced P-101 seal; root cause cavitation", created.content)
            assertFalse("private identifier must not survive in the stored redaction", created.redactedContent.orEmpty().contains("P-101"))

            val payload = SyncPayloadFactory.build(created)
            assertNotNull(payload)
            assertFalse("sync payload must not expose the private identifier", payload!!.content.contains("P-101"))

            val hits = stack.repository.search("P-101 seal cavitation", 5)
            assertTrue(hits.isNotEmpty())
            assertEquals(created.memoryId, hits.first().memory.memoryId)
        } finally {
            stack.close()
        }
    }

    @Test
    fun policyStateSurvivesRoomAndShardRestart() = runBlocking {
        val dbName = "policy-restart-${UUID.randomUUID()}"
        val qdrantDir = File(sandbox, "qdrant-${UUID.randomUUID()}")

        val first = newStack(dbName, qdrantDir)
        val created = first.repository.create(
            CreateMemoryInput("P-101 repair", "P-101 seal replaced by Technician John", type = MemoryType.REPAIR),
        )
        first.close()

        val second = newStack(dbName, qdrantDir)
        try {
            val reloaded = second.repository.list().single()
            assertEquals(SyncDecision.SYNC_REDACTED, reloaded.syncDecision)
            assertEquals(created.policyReason, reloaded.policyReason)
            assertEquals(created.redactedTitle, reloaded.redactedTitle)
            assertEquals(created.redactedContent, reloaded.redactedContent)
            assertEquals(created, reloaded)
        } finally {
            second.close()
        }
    }

    @Test
    fun updateReevaluatesPolicyAndHardRulesWin() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput("Procedure", "revision 4 of the pump procedure", type = MemoryType.PROCEDURE),
            )
            assertEquals(SyncDecision.SYNC, created.syncDecision)

            val updated = stack.repository.update(
                created.copy(content = "Gate code for Site 7 is 4412"),
            )
            assertEquals("access information must force LOCAL_ONLY even after an update", SyncDecision.LOCAL_ONLY, updated.syncDecision)
            assertNull(SyncPayloadFactory.build(updated))
        } finally {
            stack.close()
        }
    }

    @Test
    fun userChoiceSyncIsPreservedOnCreateAndUpdate() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(
                CreateMemoryInput(
                    "Idea",
                    "Replace belt drive with a gear drive",
                    type = MemoryType.NOTE,
                    userSyncChoice = SyncDecision.SYNC,
                ),
            )
            assertEquals(SyncDecision.SYNC, created.syncDecision)
            assertTrue(created.policyReason.orEmpty().startsWith("User explicitly requested SYNC"))

            val updated = stack.repository.update(created.copy(content = "Replace belt drive with a gear drive (rev 2)"))
            assertEquals(SyncDecision.SYNC, updated.syncDecision)

            val forcedLocal = stack.repository.update(updated.copy(content = "Gate code for Site 4 is 7731"))
            assertEquals("access content beats retained user choice", SyncDecision.LOCAL_ONLY, forcedLocal.syncDecision)
        } finally {
            stack.close()
        }
    }

    @Test
    fun defaultNoteIsLocalOnlyAndSearchable() = runBlocking {
        val stack = newStack()
        try {
            val created = stack.repository.create(CreateMemoryInput("Note", "Checked the market this morning"))
            assertEquals(SyncDecision.LOCAL_ONLY, created.syncDecision)
            assertNull(SyncPayloadFactory.build(created))
            assertTrue(stack.repository.search("market", 5).isNotEmpty())
        } finally {
            stack.close()
        }
    }

    private class Stack(
        val database: EdgeMindDatabase,
        val store: LocalVectorStore,
        val repository: DefaultMemoryRepository,
    ) {
        fun close() {
            runBlocking { store.close() }
            database.close()
        }
    }

    private fun newStack(
        dbName: String = "phase5-${UUID.randomUUID()}",
        qdrantDir: File = File(sandbox, "qdrant-${UUID.randomUUID()}"),
        embedding: EmbeddingService = FeatureHashingEmbeddingService(),
        store: LocalVectorStore = QdrantEdgeVectorStore(qdrantDir),
    ): Stack {
        val database = Room.databaseBuilder(context, EdgeMindDatabase::class.java, dbName).build()
        val repository = DefaultMemoryRepository(database.memoryDao(), store, embedding)
        return Stack(database, store, repository)
    }
}