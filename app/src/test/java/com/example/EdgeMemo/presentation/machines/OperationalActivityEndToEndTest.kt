package com.example.EdgeMemo.presentation.machines

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemorySyncState
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.sync.CloudApplyOutcome
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.presentation.ask.AskUiTags
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.conflicts.ConflictUiTags
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import com.example.EdgeMemo.testing.TestNativeLoader
import com.example.EdgeMemo.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI Phase 5 real-stack scenario. Every record is created by the production
 * CreateMemoryUseCase and persists in the one Qdrant application shard; the
 * conflict is produced by the real cloud-apply classifier/recorder and is
 * resolved by the existing Phase 4 workflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OperationalActivityEndToEndTest {

    companion object {
        private const val CLOUD_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fieldTechnicianWorkflowUsesRealRecordsAskConflictAndOfflinePersistence() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        val seeded = seedRealActivity(container)
        val observation = seeded.first { it.type == MemoryType.OBSERVATION }
        val conflictId = seedRealConflict(container, observation)

        showShell(container)
        openAssetWorkspace()

        // Real operational summary and category counts from five stored rows.
        waitForText("5 records")
        listOf("Maintenance (1)", "Observations (1)", "Incidents (1)", "Procedures (1)", "Documents (1)")
            .forEach { label -> compose.onNodeWithText(label).assertExists() }
        compose.onNodeWithTag(AssetUiTags.MAINTENANCE).assertExists()
        compose.onNodeWithTag(AssetUiTags.OBSERVATIONS).assertExists()
        compose.onNodeWithTag(AssetUiTags.INCIDENTS).assertExists()
        compose.onNodeWithTag(AssetUiTags.PROCEDURES).assertExists()
        compose.onNodeWithTag(AssetUiTags.DOCUMENTS).assertExists()

        // The timeline's visual order matches the deterministic production
        // projection (updatedAt descending, memoryId ascending for ties).
        val expected = runBlocking {
            AssetModel.recordsFor(container.listMemories(), "p101")
        }
        assertTimelineOrder(expected)

        // Open one genuine maintenance record in the existing detail surface.
        val repair = seeded.first { it.type == MemoryType.REPAIR }
        compose.onNodeWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${repair.memoryId}")
            .performScrollTo()
            .performClick()
        waitForTag(RecordDetailTags.SCREEN)
        waitForText("Installed the approved mechanical seal")
        compose.onNodeWithText("p101/seal").assertExists()
        compose.onNodeWithTag(RecordDetailTags.BACK).performClick()
        waitForTag(AssetUiTags.SCREEN)
        waitForTag("${AssetUiTags.FILTER_PREFIX}incidents")

        // Presentation-only category filter affects the already-loaded
        // timeline, not the repository or focused reference sections.
        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}incidents")
            .performScrollTo()
            .performClick()
        val event = seeded.first { it.type == MemoryType.EVENT }
        waitForTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${event.memoryId}")
        compose.onNodeWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${repair.memoryId}")
            .assertDoesNotExist()
        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}all").performClick()
        waitForTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${repair.memoryId}")

        // Add an observation while offline-capable, through the existing
        // production creation pipeline, with an explicit local-only choice.
        compose.onNodeWithTag(AssetUiTags.ADD_OBSERVATION).performScrollTo().performClick()
        waitForTag(AssetUiTags.COMPOSER)
        compose.onNodeWithTag(AssetUiTags.COMPOSER_TITLE)
            .performTextInput("P-101 post-repair field observation")
        compose.onNodeWithTag(AssetUiTags.COMPOSER_CONTENT)
            .performTextInput("No visible leakage after the controlled restart inspection.")
        compose.onNodeWithTag("edge-asset-sync-local").performScrollTo().performClick()
        compose.onNodeWithTag(AssetUiTags.COMPOSER_SUBMIT).performScrollTo().performClick()

        val created = waitForStored(container, "P-101 post-repair field observation")
        assertEquals(MemoryType.OBSERVATION, created.type)
        assertEquals("p101/observation", created.subjectKey)
        assertEquals(SyncDecision.LOCAL_ONLY, created.syncDecision)
        assertEquals(MemorySyncState.LOCAL, created.syncState)
        waitForTag(AssetUiTags.CREATED_SYNC)
        compose.onNodeWithTag(AssetUiTags.CREATED_SYNC).assertExists()
        waitForTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${created.memoryId}")

        // The one existing Ask route receives the real namespace; no second
        // RAG implementation or fake scoped search exists.
        compose.onNodeWithTag("edge-ask-about-asset").performScrollTo().performClick()
        waitForTag(AskUiTags.SCREEN)
        compose.onNodeWithTag(AskUiTags.ASSET_CHIP).assertIsDisplayed()
        compose.onNodeWithText("ASSET CONTEXT · P101").assertIsDisplayed()

        // Return to the asset, use the per-record conflict indicator, then
        // resolve through the authoritative Phase 4 detail workflow.
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForTag("${AssetUiTags.RECORD_CONFLICT_PREFIX}${observation.memoryId}")
        compose.onNodeWithTag("${AssetUiTags.RECORD_CONFLICT_PREFIX}${observation.memoryId}")
            .performScrollTo()
            .performClick()
        waitForTag(ConflictUiTags.DETAIL)
        compose.onNodeWithTag(ConflictUiTags.KEEP_LOCAL).performScrollTo().performClick()
        compose.onNodeWithTag(ConflictUiTags.CONFIRM).performScrollTo().performClick()
        waitForTag(ConflictUiTags.OUTCOME)
        assertEquals(
            ConflictResolutionState.RESOLVED_LOCAL,
            runBlocking { container.getConflict(conflictId) }!!.state,
        )

        // Returning re-reads durable conflict state; the activity row no
        // longer claims conflict while the real local record remains intact.
        compose.onNodeWithTag(ConflictUiTags.BACK).performClick()
        waitForTag(AssetUiTags.SCREEN)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("${AssetUiTags.RECORD_CONFLICT_PREFIX}${observation.memoryId}")
                .fetchSemanticsNodes().isEmpty()
        }
        assertNotNull(runBlocking { container.memoryRepository.get(observation.memoryId) })
        assertEquals(0L, runBlocking { container.countUnresolvedConflicts() })

        // Offline/local architecture guard: browsing, detail and local write
        // all succeeded without a backend; only the one application shard exists.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(app.getDatabasePath("edge-memory.db").exists())
        assertFalse(File(app.filesDir, "local_qdrant").exists())
        assertTrue(File(app.filesDir, "qdrant_sync_store").exists())
    }

    private fun seedRealActivity(container: AppContainer): List<Memory> = runBlocking {
        listOf(
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal-face observation",
                    content = "Fine radial marks were observed on the inboard seal face.",
                    type = MemoryType.OBSERVATION,
                    subjectKey = "p101/seal",
                    userSyncChoice = SyncDecision.SYNC,
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal replacement",
                    content = "Installed the approved mechanical seal and verified flush flow.",
                    type = MemoryType.REPAIR,
                    subjectKey = "p101/seal",
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 motor protection trip",
                    content = "Motor protection tripped during the controlled restart.",
                    type = MemoryType.EVENT,
                    subjectKey = "p101/event",
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 controlled restart procedure",
                    content = "Verify suction level and flush flow before the controlled restart.",
                    type = MemoryType.PROCEDURE,
                    subjectKey = "p101/procedure",
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 mechanical seal manual",
                    content = "OEM manual evidence for seal inspection and approved installation.",
                    type = MemoryType.DOCUMENT,
                    subjectKey = "p101/document",
                ),
            ),
        )
    }

    private fun seedRealConflict(container: AppContainer, local: Memory): String = runBlocking {
        val result = container.qdrantSyncEngine.applyCloudItem(
            CloudKnowledgeItem(
                memoryId = local.memoryId,
                subjectKey = local.subjectKey!!,
                title = "P-101 OEM seal-face advisory",
                content = "Replace the seal faces before any controlled restart.",
                contentHash = CLOUD_HASH,
                version = local.version,
                updatedAt = local.updatedAt + 1_000L,
                origin = "CLOUD",
                authority = "OEM-MANUAL",
                tombstone = false,
                metadata = emptyMap(),
            ),
        ) as CloudApplyOutcome.Conflict
        result.conflict.id.uuid
    }

    private fun showShell(container: AppContainer) {
        compose.setContent {
            val owner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore = ViewModelStore()
                }
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                MyApplicationTheme(darkTheme = true) {
                    EdgeMindShell(
                        container = container,
                        darkTheme = true,
                        onToggleTheme = {},
                        profileName = "",
                        onProfileNameChange = {},
                    )
                }
            }
        }
    }

    private fun openAssetWorkspace() {
        waitForTag(EdgeUiTags.DASHBOARD)
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForTag(AssetUiTags.SCREEN)
        waitForText("5 records")
    }

    private fun waitForStored(container: AppContainer, title: String): Memory {
        var stored: Memory? = null
        compose.waitUntil(20_000) {
            stored = runBlocking { container.listMemories().firstOrNull { it.title == title } }
            stored != null
        }
        return checkNotNull(stored)
    }

    private fun assertTimelineOrder(expected: List<Memory>) {
        val bounds: List<Rect> = expected.map { record ->
            compose.onNodeWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${record.memoryId}")
                .fetchSemanticsNode().boundsInRoot
        }
        bounds.zipWithNext().forEachIndexed { index, pair ->
            assertTrue(
                "timeline item ${expected[index].memoryId} must precede ${expected[index + 1].memoryId}",
                pair.first.top <= pair.second.top,
            )
        }
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
}
