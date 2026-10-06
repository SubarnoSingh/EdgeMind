package com.example.EdgeMemo.presentation.conflicts

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.data.local.sync.CloudApplyOutcome
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeItem
import com.example.EdgeMemo.domain.conflict.ConflictResolutionState
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.machines.AssetUiTags
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import com.example.EdgeMemo.testing.TestNativeLoader
import com.example.EdgeMemo.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI Phase 4 REAL end-to-end verification. The conflict is produced by the
 * PRODUCTION path — a locally authored Qdrant record meeting a divergent
 * cloud item through [DefaultQdrantSyncEngine.applyCloudItem], which records
 * durable evidence with the real 12B.9 recorder. Resolution runs the real
 * 12B.10 resolver; all state assertions are read back from the durable
 * store. Nothing about the conflict or resolution is mocked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConflictResolutionEndToEndTest {

    companion object {
        private const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    @get:Rule
    val compose = createComposeRule()

    private fun newContainer(): AppContainer =
        AppContainer(ApplicationProvider.getApplicationContext<Application>())

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

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

    /** Seed a local record and a divergent cloud item → REAL conflict point. */
    private fun seedRealConflict(container: AppContainer): Pair<String, String> {
        val created = runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal observation",
                    content = "Seal weep observed on P-101 after 3800 operating hours.",
                    type = MemoryType.OBSERVATION,
                    subjectKey = "p101/seal",
                    userSyncChoice = SyncDecision.SYNC,
                ),
            )
        }
        val conflictRecord = runBlocking {
            val outcome = container.qdrantSyncEngine.applyCloudItem(
                CloudKnowledgeItem(
                    memoryId = created.memoryId,
                    subjectKey = "p101/seal",
                    title = "P-101 OEM seal advisory",
                    content = "Replace seal faces every 2000 operating hours.",
                    contentHash = HASH_A,
                    version = 1,
                    updatedAt = 1_700_000_002_000L,
                    origin = "CLOUD",
                    authority = "OEM-MANUAL",
                    tombstone = false,
                    metadata = emptyMap(),
                ),
            )
            (outcome as CloudApplyOutcome.Conflict).conflict
        }
        return created.memoryId to conflictRecord.id.uuid
    }

    @Test
    fun keepLocalThroughTheRealWorkflowSettlesTheDurableConflict() {
        val container = newContainer()
        val (memoryId, conflictId) = seedRealConflict(container)
        assertEquals(1L, runBlocking { container.countUnresolvedConflicts() })

        showShell(container)
        waitForTag(EdgeUiTags.DASHBOARD)

        // Dashboard: the REAL unresolved-conflict metric navigates to the workspace.
        waitForText("Conflicts")
        compose.onNodeWithTag(EdgeUiTags.OPEN_CONFLICTS).performScrollTo().performClick()

        // Workspace lists the conflict with its real id and evidence summary.
        waitForTag("${ConflictUiTags.ROW_PREFIX}$conflictId")
        compose.onNodeWithTag("${ConflictUiTags.ROW_PREFIX}$conflictId")
            .assertIsDisplayed()
        compose.onNodeWithText("1 record has two versions. Pick which one to keep.").assertIsDisplayed()
        compose.onNodeWithTag(ConflictUiTags.ROW_PREFIX + conflictId).performClick()

        // Detail: both sides show the genuinely stored content.
        waitForTag(ConflictUiTags.DETAIL)
        waitForText("P-101 seal observation")
        compose.onNodeWithTag(ConflictUiTags.LOCAL_PANEL).assertExists()
        compose.onNodeWithTag(ConflictUiTags.CLOUD_PANEL).assertExists()
        compose.onNodeWithText("P-101 OEM seal advisory").assertExists()
        compose.onNodeWithText("OEM-MANUAL").assertExists()
        compose.onNodeWithText("Unresolved").assertExists()

        // Confirmation step first, then CANCEL — nothing may be written.
        compose.onNodeWithTag(ConflictUiTags.KEEP_LOCAL).performScrollTo().performClick()
        compose.onNodeWithText("Keep this device's version?").assertIsDisplayed()
        compose.onNodeWithTag(ConflictUiTags.CANCEL).performScrollTo().performClick()
        assertEquals(
            ConflictResolutionState.UNRESOLVED,
            runBlocking { container.listConflicts().first().state },
        )

        // Confirm Keep Local: the real resolver settles it durably.
        compose.onNodeWithTag(ConflictUiTags.KEEP_LOCAL).performScrollTo().performClick()
        compose.onNodeWithTag(ConflictUiTags.CONFIRM).performScrollTo().performClick()
        waitForTag(ConflictUiTags.OUTCOME)
        compose.onNodeWithText("Kept this device's version.")
            .assertIsDisplayed()
        compose.onNodeWithText("No record content changed", substring = true)
            .performScrollTo().assertIsDisplayed()

        // Durable truth: conflict RESOLVED_LOCAL, none unresolved, record intact.
        val after = runBlocking { container.listConflicts().first() }
        assertEquals(ConflictResolutionState.RESOLVED_LOCAL, after.state)
        assertEquals(0L, runBlocking { container.countUnresolvedConflicts() })
        runBlocking {
            assertTrue("keep-local must not alter the record", container.memoryRepository.get(memoryId) != null)
            assertEquals(memoryId, after.localMemoryId)
        }

        // Back to the workspace: the settled conflict left the unresolved list.
        compose.onNodeWithTag(ConflictUiTags.BACK).performClick()
        waitForTag(ConflictUiTags.LIST)
        waitForText("No unresolved conflicts")
    }

    @Test
    fun keepCloudFromTheAssetScreenQueuesHonestPendingSyncOnce() {
        val container = newContainer()
        val (memoryId, conflictId) = seedRealConflict(container)

        showShell(container)
        waitForTag(EdgeUiTags.DASHBOARD)

        // Asset route: machines tab → p101 card → conflict row → detail.
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForTag("${AssetUiTags.CONFLICT_PREFIX}$conflictId")
        compose.onNodeWithTag("${AssetUiTags.CONFLICT_PREFIX}$conflictId")
            .performScrollTo()
            .performClick()

        waitForTag(ConflictUiTags.DETAIL)
        compose.onNodeWithTag(ConflictUiTags.KEEP_CLOUD).performScrollTo().performClick()
        compose.onNodeWithText("Use the cloud version?").assertIsDisplayed()
        compose.onNodeWithTag(ConflictUiTags.CONFIRM).performScrollTo().performClick()
        waitForTag(ConflictUiTags.OUTCOME)

        // The resolver's deterministic follow-up: version max+1, queued sync.
        waitForText("is queued to sync")
        val resolved = runBlocking { container.listConflicts().first() }
        assertEquals(ConflictResolutionState.RESOLVED_CLOUD, resolved.state)
        val record = runBlocking { container.memoryRepository.get(memoryId) }!!
        assertEquals(2, record.version) // max(local 1, incoming 1) + 1
        assertEquals("Replace seal faces every 2000 operating hours.", record.content)

        // Exactly one v2 follow-up operation exists (no duplicate mutations).
        val ops = runBlocking {
            container.qdrantSyncOperations.listByStates(
                setOf(
                    com.example.EdgeMemo.core.sync.OutboxOperationState.PENDING,
                    com.example.EdgeMemo.core.sync.OutboxOperationState.FAILED,
                    com.example.EdgeMemo.core.sync.OutboxOperationState.IN_FLIGHT,
                ),
                limit = 50,
                offsetId = null,
            ).operations
        }
        assertEquals(1, ops.count { it.version == 2 })
        assertTrue(ops.all { it.operationId.value.startsWith("UPSERT:") })
        // The durable record's own sync state backs the "queued" claim. Read
        // before navigating away: a runBlocking read on the main thread while
        // the asset screen refreshes can deadlock under Robolectric.
        assertEquals(
            com.example.EdgeMemo.core.model.MemorySyncState.PENDING,
            record.syncState,
        )

        // Back on the asset workspace: after refresh the conflict section is
        // gone and counts reflect the durable state.
        compose.onNodeWithTag(ConflictUiTags.BACK).performClick()
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("${AssetUiTags.CONFLICT_PREFIX}$conflictId")
                .fetchSemanticsNodes().isEmpty()
        }
    }
}
