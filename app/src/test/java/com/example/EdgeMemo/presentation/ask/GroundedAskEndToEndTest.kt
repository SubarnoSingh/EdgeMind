package com.example.EdgeMemo.presentation.ask

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.model.SyncDecision
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.presentation.components.EdgeUiTags
import com.example.EdgeMemo.presentation.shell.EdgeMindShell
import com.example.EdgeMemo.testing.TestNativeLoader
import com.example.EdgeMemo.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI Phase 2 end-to-end grounding test. Records are seeded through the REAL
 * production `CreateMemoryUseCase` (Qdrant-backed), the question runs the REAL
 * retrieval → sufficiency → extractive-answer pipeline, and citations are
 * traced into the detail screen. No fake answer, source, score, or service is
 * involved anywhere.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GroundedAskEndToEndTest {

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

    private fun waitForIdleTag(tag: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForAnyTag(tags: List<String>, timeoutMs: Long = 25_000) {
        val ok = compose.waitUntilOrNull(timeoutMs) {
            tags.any { t -> compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        }
        if (!ok) {
            val composerState = compose.onAllNodesWithTag(AskUiTags.SUBMIT)
                .fetchSemanticsNodes().firstOrNull()?.let { "submit-present" } ?: "submit-missing"
            throw AssertionError(
                "none of $tags rendered within ${timeoutMs}ms; composer state: $composerState",
            )
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilOrNull(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                waitForIdle()
            } catch (_: Throwable) {
                // keep polling
            }
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun waitForIdleText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) {
            compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }


    private fun toAskTab() {
        waitForIdleTag(EdgeUiTags.DASHBOARD)
        compose.onNodeWithTag("edge-tab-ask").performClick()
        waitForIdleTag(AskUiTags.SCREEN)
    }

    @Test
    fun questionIsAnsweredFromRealSeededRecordsWithTraceableCitations() {
        val container = newContainer()
        runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal observation",
                    content = "Seal weep observed on pump P-101 after 3800 hours; cavitation suspected upstream.",
                    type = MemoryType.OBSERVATION,
                    subjectKey = "p101/seal",
                    userSyncChoice = SyncDecision.SYNC,
                ),
            )
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 restart procedure",
                    content = "Verify minimum submergence and the flush line before restarting pump P-101 to prevent seal failures.",
                    type = MemoryType.PROCEDURE,
                    subjectKey = "p101/procedure",
                ),
            )
        }
        showShell(container)
        toAskTab()

        // Idle guidance is present before any submission (scrollable page).
        compose.onNodeWithText("Try asking").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag(AskUiTags.COMPOSER_INPUT)
            .performTextInput("why does pump P-101 keep failing its mechanical seal")
        compose.onNodeWithTag(AskUiTags.SUBMIT).performClick()

        // Real pipeline: grounded answer + evidence section + honest counts.
        waitForIdleTag(AskUiTags.ANSWER)
        compose.onNodeWithTag(AskUiTags.ANSWER).performScrollTo().assertIsDisplayed()
        waitForIdleTag(AskUiTags.EVIDENCE_SECTION)
        // Provenance badges are child nodes of the tagged row: assert the real
        // label nodes directly (merged-tree text lives on the children).
        compose.onNodeWithText("From your records").assertExists()
        compose.onNodeWithText("Based on", substring = true).assertExists()

        // First citation opens full traceability detail.
        compose.onNodeWithTag("${AskUiTags.EVIDENCE_CARD_PREFIX}1").performScrollTo().performClick()
        waitForIdleTag(CitationDetailTags.SCREEN)
        compose.onNodeWithTag(CitationDetailTags.SCREEN).assertIsDisplayed()
        // Retrieved content must be text from one of the REAL seeded records.
        compose.onNodeWithTag(CitationDetailTags.CONTENT)
            .assert(hasText("P-101", substring = true))
        // Location/record sections render real identifiers, no storage
        // internals (sections below the fold are asserted by existence —
        // scrolling is exercised by the tap flow above).
        compose.onNodeWithText("Why it matched").assertIsDisplayed()
        compose.onNodeWithText("What it says").assertExists()
        compose.onNodeWithText("Details").assertExists()

        // Deterministic back returns to the retained answer + evidence
        // (composition was swapped by navigation, so scrolled content
        // re-lays-out from the top; assert existence then scroll for taps).
        // Back is the shell's single back control.
        compose.onNodeWithTag("edge-shell-back").performClick()
        waitForIdleTag(AskUiTags.SCREEN)
        compose.onNodeWithTag(AskUiTags.ANSWER).assertExists()
        compose.onNodeWithTag(AskUiTags.EVIDENCE_SECTION).assertExists()

        // New question resets to idle (session state, no persistence).
        compose.onNodeWithTag(AskUiTags.NEW_QUESTION).performScrollTo().performClick()
        waitForIdleText("Try asking")
        compose.onNodeWithTag(AskUiTags.ANSWER).assertDoesNotExist()
    }

    @Test
    fun unsupportedQuestionShowsHonestInsufficientEvidenceNotAnError() {
        val container = newContainer()
        runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "Bearing temps",
                    content = "Line B bearing temperatures logged weekly.",
                    type = MemoryType.OBSERVATION,
                ),
            )
        }
        showShell(container)
        toAskTab()

        compose.onNodeWithTag(AskUiTags.COMPOSER_INPUT)
            .performTextInput("how many helicopters fit on the roof of the reactor?")
        compose.onNodeWithTag(AskUiTags.SUBMIT).performClick()

        // One of the three terminal Ask states must render (never stuck on loading).
        val resultTags = listOf(AskUiTags.INSUFFICIENT, AskUiTags.ERROR, AskUiTags.ANSWER)
        waitForAnyTag(resultTags)
        compose.onNodeWithTag(AskUiTags.INSUFFICIENT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not enough in your records to answer this.").assertExists()
        // Insufficient is NOT an error state:
        compose.onNodeWithTag(AskUiTags.ERROR).assertDoesNotExist()
        // and no answer card or fabricated citations are rendered.
        compose.onNodeWithTag(AskUiTags.ANSWER).assertDoesNotExist()
        // Offline is reported honestly (Robolectric has no network here).
        compose.onNodeWithTag(EdgeUiTags.CONNECTION_BADGE).assertExists()
        // Local knowledge remains fully usable: a new question works.
        compose.onNodeWithTag(AskUiTags.NEW_QUESTION).performScrollTo().performClick()
        waitForIdleText("Try asking")
    }

    @Test
    fun suggestionPromptsFillTheComposerWithoutExecutingAnything() {
        showShell(newContainer())
        toAskTab()

        val firstSuggestion = compose.onAllNodesWithTag("edge-ask-suggestion").onFirst()
        firstSuggestion.performScrollTo()
        firstSuggestion.performClick()
        // Composer now holds the suggestion text, but nothing was submitted.
        val suggestionText = "Why is this machine failing repeatedly?"
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText(suggestionText), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(AskUiTags.ANSWER).assertDoesNotExist()
        compose.onNodeWithTag(AskUiTags.LOADING).assertDoesNotExist()
        compose.onNodeWithTag(AskUiTags.EVIDENCE_SECTION).assertDoesNotExist()
    }

    @Test
    fun machineDetailOpensTheSharedAskScreenWithAssetContext() {
        val container = newContainer()
        runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "P-101 seal kit",
                    content = "Replacement seal kits for P-101 are stored in cage B4.",
                    type = MemoryType.NOTE,
                    subjectKey = "p101/stores",
                ),
            )
        }
        showShell(container)
        waitForIdleTag(EdgeUiTags.DASHBOARD)
        compose.onNodeWithTag("edge-tab-machines").performClick()
        waitForIdleTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101")
        compose.onNodeWithTag("${EdgeUiTags.MACHINE_CARD_PREFIX}p101").performClick()
        waitForIdleText("Ask about this asset")
        compose.onNodeWithTag("edge-ask-about-asset").performScrollTo().performClick()

        // SAME Ask surface (no second screen), carrying the asset context chip.
        waitForIdleTag(AskUiTags.SCREEN)
        compose.onNodeWithTag(AskUiTags.ASSET_CHIP).assertIsDisplayed()
        compose.onNodeWithText("Asking about P101").assertIsDisplayed()

        // The executed query visibly includes the asset reference.
        // The composer is pinned below the scrolling result area.
        compose.onNodeWithTag(AskUiTags.COMPOSER_INPUT)
            .performTextInput("why do the seals keep weeping")
        compose.onNodeWithTag(AskUiTags.SUBMIT).performClick()
        waitForIdleTag(AskUiTags.PROVENANCE)
        compose.onNodeWithText("Searched as “why do the seals keep weeping p101”", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
