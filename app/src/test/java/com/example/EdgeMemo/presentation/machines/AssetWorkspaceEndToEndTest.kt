package com.example.EdgeMemo.presentation.machines

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.presentation.ask.AskUiTags
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import com.example.EdgeMemo.testing.TestNativeLoader
import com.example.EdgeMemo.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI Phase 3 end-to-end: the asset workspace is built ENTIRELY from records
 * seeded through the production Qdrant-backed `CreateMemoryUseCase`. Timeline
 * order, categories, record detail, filtering, the add-record flow, Ask
 * integration and the no-Room architecture guard are all exercised on real
 * data over the real pipeline, offline (Robolectric has no network).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AssetWorkspaceEndToEndTest {

    companion object {
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
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun dumpTexts(): String {
        val out = StringBuilder()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            val texts = runCatching {
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            }.getOrNull()
            texts?.forEach { t -> out.append(t.text).append(" | ") }
            node.children.forEach { walk(it) }
        }
        walk(compose.onNodeWithTag(AssetUiTags.COMPOSER).fetchSemanticsNode())
        return out.toString()
    }

    private fun seed(container: AppContainer): List<Memory> = runBlocking {
        listOf(
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal weep observation",
                    content = "Seal weep observed on P-101 after 3800 operating hours.",
                    type = MemoryType.OBSERVATION,
                    subjectKey = "p101/seal",
                    userSyncChoice = SyncDecision.SYNC,
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal replacement",
                    content = "Mechanical seal replaced on P-101; old faces showed heat checking.",
                    type = MemoryType.REPAIR,
                    subjectKey = "p101/seal",
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 restart procedure",
                    content = "Verify submergence and the flush line before restarting P-101.",
                    type = MemoryType.PROCEDURE,
                    subjectKey = "p101/procedure",
                ),
            ),
            container.createMemory(
                CreateMemoryInput(
                    title = "Unrelated canteen rota",
                    content = "Kitchen cleaning schedule for week 35.",
                    type = MemoryType.NOTE,
                ),
            ),
        )
    }

    private fun openWorkspace(container: AppContainer) {
        showShell(container)
        waitForTag(EdgeUiTags.DASHBOARD)
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForTag(AssetUiTags.SCREEN)
        // The workspace reads the real shard on IO — wait for the Ready state
        // (the overview strip is the first content-bearing node).
        waitForText("records")
    }

    @Test
    fun assetWorkspaceRendersSeededRecordsCategoriesAndDetail() {
        val container = newContainer()
        val seeded = seed(container)
        openWorkspace(container)

        // Real identifier + real derived counts (nothing invented).
        compose.onNodeWithText("P101").assertIsDisplayed()
        waitForText("3 records")
        // Category filter chips from the real records: Maintenance, Observations, Procedures.
        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}maintenance").assertExists()
        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}observations").assertExists()
        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}procedures").assertExists()
        // The unrelated note must NOT leak into the asset workspace.
        compose.onNodeWithText("Unrelated canteen rota").assertDoesNotExist()

        // Timeline entries carry the REAL seeded titles.
        compose.onNodeWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[0].memoryId}")
            .assertExists()
        compose.onNodeWithText("P-101 seal replacement").performScrollTo().assertIsDisplayed()

        // Timeline entry opens the real record detail.
        compose.onNodeWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[1].memoryId}")
            .performScrollTo()
            .performClick()
        waitForTag(RecordDetailTags.SCREEN)
        // The record-detail VM re-reads on IO — wait for actual content.
        waitForText("heat checking")
        compose.onNodeWithTag(RecordDetailTags.CONTENT).assertExists()
        compose.onNodeWithText("heat checking", substring = true).assertIsDisplayed()
        compose.onNodeWithText("p101/seal").performScrollTo().assertExists() // real subject shown
        compose.onAllNodesWithText("v1").fetchSemanticsNodes().let {
            assertTrue("version fact rendered", it.isNotEmpty())
        }

        // Deterministic back (the shell header owns the one back control).
        compose.onNodeWithTag("edge-shell-back").performClick()
        waitForText("P-101 seal replacement")
        compose.onNodeWithText("P-101 seal replacement").assertExists()
    }


    @Test
    fun categoryFilterNarrowsTheLoadedTimelineClientSide() {
        val container = newContainer()
        val seeded = seed(container)
        openWorkspace(container)

        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}maintenance")
            .performScrollTo()
            .performClick()
        // Timeline ENTRIES are filtered (the header representative title is a
        // separate surface and may name any record); assert on timeline tags.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[2].memoryId}")
                .fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[0].memoryId}")
                .fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[1].memoryId}")
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("${AssetUiTags.FILTER_PREFIX}all").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("${AssetUiTags.TIMELINE_ITEM_PREFIX}${seeded[0].memoryId}")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun addRecordWritesThroughTheProductionPathAndRefreshesTheWorkspace() {
        val container = newContainer()
        seed(container)
        openWorkspace(container)

        compose.onNodeWithTag("edge-asset-add").performClick()
        waitForTag(AssetUiTags.COMPOSER)
        compose.onNodeWithTag(AssetUiTags.COMPOSER_TITLE)
            .performTextInput("P-101 dry-run event")
        compose.onNodeWithTag(AssetUiTags.COMPOSER_CONTENT)
            .performTextInput("Short dry run occurred during test; inspect seal faces.")
        // Submit sits below the fold in the test viewport — scroll it in
        // first (a dropped off-screen tap would silently do nothing).
        compose.onNodeWithTag(AssetUiTags.COMPOSER_SUBMIT)
            .performScrollTo()
            .performClick()

        // Independent of the banner: did the production path persist it?
        var persisted = false
        try {
            compose.waitUntil(15_000) {
                persisted = runBlocking {
                    container.memoryRepository.list().any { it.title == "P-101 dry-run event" }
                }
                persisted
            }
        } catch (_: Throwable) {
            throw AssertionError("record never reached the shard; $persisted; texts: ${dumpTexts()}", null as Throwable?)
        }
        try {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithTag(AssetUiTags.CREATED_MESSAGE).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            throw AssertionError("no created banner; screen texts: ${dumpTexts()}", e)
        }

        // The record truly persisted: readable through the production repository.
        val stored = runBlocking { container.memoryRepository.get(
            container.listMemories().first { it.title == "P-101 dry-run event" }.memoryId,
        ) }
        assertTrue(stored != null)
        assertTrue(stored!!.subjectKey!!.startsWith("p101/"))
    }

    @Test
    fun askAboutAssetStillOpensTheSameAskRouteWithContext() {
        val container = newContainer()
        seed(container)
        openWorkspace(container)

        compose.onNodeWithTag("edge-ask-about-asset").performScrollTo().performClick()
        waitForTag(AskUiTags.SCREEN)
        compose.onNodeWithTag(AskUiTags.ASSET_CHIP).assertIsDisplayed()
        assertTrue(
            "Ask carries the P101 asset context",
            compose.onAllNodes(hasText("P101", substring = true, ignoreCase = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun workspaceStaysFullyUsableOfflineWithArchitectureGuard() {
        val container = newContainer()
        seed(container)
        openWorkspace(container)

        // Robolectric provides no working INTERNET network: the workspace and
        // its full record list still render from the local shard.
        compose.onNodeWithTag(AssetUiTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(AssetUiTags.TIMELINE).performScrollTo().assertIsDisplayed()

        // No invented machine telemetry is displayed anywhere.
        for (fabricated in listOf("Health", "Uptime", "Vibration", "Risk score")) {
            compose.onNodeWithText(fabricated, substring = true).assertDoesNotExist()
        }

        // Architecture guard: no Room DB, no local_qdrant, one app shard.
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(app.getDatabasePath("edge-memory.db").exists())
        assertFalse(File(app.filesDir, "local_qdrant").exists())
        assertTrue(File(app.filesDir, "qdrant_sync_store").exists())
    }
}
