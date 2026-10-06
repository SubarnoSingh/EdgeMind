package com.example.EdgeMemo.presentation.shell

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.presentation.components.EdgeUiTags
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
 * UI Phase 1 — shell composition tests on Robolectric with the REAL
 * AppContainer wiring: real Qdrant-native repository, real sync-status
 * reader, real connectivity flow. Nothing here stubs a production
 * component; the only test-only seam is the ViewModelStoreOwner provider.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class EdgeMindShellComposeTest {

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

    @Test
    fun appShellLaunchesWithIndustrialIdentity() {
        showShell(newContainer())
        waitForDashboard()
        compose.onNodeWithTag(EdgeUiTags.DASHBOARD).assertIsDisplayed()
        compose.onNodeWithTag(EdgeUiTags.BOTTOM_NAV).assertIsDisplayed()
        compose.onNodeWithTag(EdgeUiTags.CONNECTION_BADGE).assertIsDisplayed()
        compose.onNodeWithTag(EdgeUiTags.SYNC_BADGE).assertIsDisplayed()
        // All five destinations present with text labels (never color-only).
        listOf("edge-tab-dashboard", "edge-tab-machines", "edge-tab-ask", "edge-tab-sync", "edge-tab-settings")
            .forEach { tag -> compose.onNodeWithTag(tag).assertIsDisplayed() }
    }

    @Test
    fun dashboardLoadsAndShowsHonestEmptyStatesOnAFreshDevice() {
        showShell(newContainer())
        waitForDashboard()
        // Fresh shard: metrics rendered from real zero counts, never faked.
        compose.onNodeWithText("Records").assertIsDisplayed()
        compose.onNodeWithText("To sync").assertIsDisplayed()
        compose.onNodeWithText("Conflicts").assertIsDisplayed()
        waitForNode("No records yet")
        // The recent-activity empty state sits below the fold on a phone-sized
        // test viewport: existence + semantics matter, pixel visibility does not.
        compose.onNodeWithText("No records yet").assertExists()
    }

    @Test
    fun navigationToMachinesShowsTheHonestEmptyFoundation() {
        showShell(newContainer())
        waitForDashboard()
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForNode("No machines yet")
        compose.onNodeWithTag(EdgeUiTags.MACHINES).assertIsDisplayed()
        compose.onNodeWithText("No machines yet").assertIsDisplayed()
    }

    @Test
    fun machineDetailNavigationWorksWhenRealRecordsExist() {
        val container = newContainer()
        // Seed through the PRODUCTION Qdrant-native repository (no demo data,
        // no fixtures — the same write path the app uses).
        runBlocking {
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
        showShell(container)
        waitForDashboard()

        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
            .assertIsDisplayed()
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForTag(EdgeUiTags.MACHINE_DETAIL)

        compose.onNodeWithTag(EdgeUiTags.MACHINE_DETAIL).assertIsDisplayed()
        compose.onNodeWithText("P101").assertIsDisplayed()
        waitForNode("P-101 seal observation")
        // Real record text shows in BOTH the header (representative title) and
        // its timeline entry — assert it is rendered, not that it is unique.
        assertTrue(
            "real seeded record must be rendered in the workspace",
            compose.onAllNodesWithText("P-101 seal observation")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        // Honesty: the domain has no health/risk model, so none is shown.
        assertTrue(compose.onAllNodesWithText("Health", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Risk score", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun offlineIndicatorsAreTextualNotColorOnly() {
        showShell(newContainer())
        waitForDashboard()
        // Robolectric's default connectivity is environment-dependent; whatever
        // it is, the badge carries its TEXT label (never color alone).
        compose.onNodeWithTag(EdgeUiTags.CONNECTION_BADGE).assertExists()
        // The badge carries a text label AND its contentDescription mirrors it —
        // status is never color-only.
        val badgeHasText =
            runCatching {
                compose.onAllNodesWithContentDescription("Online", ignoreCase = true)
                    .fetchSemanticsNodes()
            }.getOrThrow().isNotEmpty() ||
            runCatching {
                compose.onAllNodesWithContentDescription("Offline", ignoreCase = true)
                    .fetchSemanticsNodes()
            }.getOrThrow().isNotEmpty()
        assertTrue("connection badge must render a text label", badgeHasText)
    }

    @Test
    fun uiGraphNeverInitializesRoomOrLegacyShards() {
        val container = newContainer()
        showShell(container)
        waitForDashboard()
        runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "Graph check", content = "verify no Room",
                    type = MemoryType.NOTE,
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForTag(EdgeUiTags.MACHINES)
        compose.onNodeWithTag("edge-tab-sync").performClick()
        waitForTag("edge-sync-screen")

        val app = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(
            "Room must never be created by the UI graph",
            app.getDatabasePath("edge-memory.db").exists(),
        )
        val legacyVectorShard = File(app.filesDir, "local_qdrant")
        assertFalse("local_qdrant must stay retired", legacyVectorShard.exists())
        assertTrue(
            "the only Qdrant shard is the application shard",
            File(app.filesDir, "qdrant_sync_store").exists(),
        )
    }

    /** Wait until the dashboard route is composed and its VM reads settled. */
    private fun waitForDashboard() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(EdgeUiTags.DASHBOARD)
            .fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("Records")
                .fetchSemanticsNodes().isNotEmpty()
    }

    /** Wait until a given node exists (polls across looper + IO work). */
    private fun waitForNode(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
}
