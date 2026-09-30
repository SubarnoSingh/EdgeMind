package com.example.EdgeMemo.presentation.ask

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.di.AppContainer
import com.example.EdgeMemo.testing.TestNativeLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct pipeline check (no Compose): an unrelated question against seeded
 * but irrelevant local knowledge must return the honest INSUFFICIENT_EVIDENCE
 * status through the real escalating RAG graph, offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AskPipelineInsufficientDiagnosticTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun loadNativeLibrary() {
            TestNativeLoader.ensureLoaded()
        }
    }

    @Test
    fun irrelevantQuestionYieldsInsufficientEvidenceNotAnAnswer() {
        val container = AppContainer(ApplicationProvider.getApplicationContext<Application>())
        runBlocking {
            container.createMemory(
                CreateMemoryInput(
                    title = "Bearing temps",
                    content = "Line B bearing temperatures logged weekly.",
                    type = MemoryType.OBSERVATION,
                ),
            )
            val response = container.askQuestion(
                RagRequest(question = "how many helicopters fit on the roof of the reactor?"),
            )
            assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
            // The frozen DefaultRagService keeps the REAL weak evidence as
            // sources on insufficient results (honest, not fabricated): verify
            // each cited id is a real stored record, never invent assertions.
            response.sources.forEach { src ->
                assertTrue(
                    "citation must reference a real record",
                    container.memoryRepository.get(src.memoryId) != null,
                )
            }
        }
    }
}
