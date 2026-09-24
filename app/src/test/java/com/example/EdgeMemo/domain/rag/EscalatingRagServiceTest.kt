package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagError
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.domain.cloud.CloudAnswer
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escalation is a strict decorator: only a local INSUFFICIENT_EVIDENCE response
 * plus a confirmed online state may trigger a cloud request, and the cloud
 * answer is always attributed and never auto-stored.
 */
class EscalatingRagServiceTest {

    @Test
    fun localSufficientAnswerNeverTouchesCloud() = runBlocking {
        val cloud = CountingCloud { error("not called") }
        val rag = service(
            inner = stub { question -> RagResponse(question, "local answer [1]", AnswerStatus.ANSWERED, emptyList(), emptyList()) },
            cloud = cloud,
            online = true,
        )

        val response = rag.answer(RagRequest("question"))

        assertEquals(AnswerStatus.ANSWERED, response.status)
        assertEquals("local answer [1]", response.answer)
        assertNull(response.escalation)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun localErrorNeverTouchesCloud() = runBlocking {
        val cloud = CountingCloud { error("not called") }
        val rag = service(
            inner = stub { question ->
                RagResponse(question, "failed", AnswerStatus.ERROR, emptyList(), emptyList(), error = RagError.RetrievalFailed("boom"))
            },
            cloud = cloud,
            online = true,
        )

        val response = rag.answer(RagRequest("question"))

        assertEquals(AnswerStatus.ERROR, response.status)
        assertNull(response.escalation)
        assertEquals(0, cloud.calls)
    }

    @Test
    fun insufficientOfflineReturnsHonestLimitationWithoutCloudRequest() = runBlocking {
        val cloud = CountingCloud { error("not called") }
        val rag = service(
            inner = stub { question -> insufficient(question) },
            cloud = cloud,
            online = false,
        )
        val stages = mutableListOf<RagStage>()

        val response = rag.answer(RagRequest("question"), onStage = stages::add)

        assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(CloudEscalation.Offline, response.escalation)
        assertTrue(response.answer.isNotBlank())
        assertEquals(0, cloud.calls)
        assertFalse("no ESCALATING stage when offline", RagStage.ESCALATING in stages)
    }

    @Test
    fun insufficientOnlineAnswersWithAttributedCloudAnswer() = runBlocking {
        val cloud = CountingCloud { q -> CloudAnswer(q, "The latest approved procedure revision is P-101 revision 4.", "central-engineering") }
        val rag = service(inner = stub { question -> insufficient(question) }, cloud = cloud, online = true)
        val stages = mutableListOf<RagStage>()

        val response = rag.answer(RagRequest("What is the latest approved procedure revision?"), onStage = stages::add)

        assertEquals(AnswerStatus.ANSWERED, response.status)
        assertEquals("The latest approved procedure revision is P-101 revision 4.", response.answer)
        val escalated = response.escalation
        assertTrue(escalated is CloudEscalation.Answered)
        assertEquals("central-engineering", (escalated as CloudEscalation.Answered).authority)
        assertEquals("only the question reaches the cloud", 1, cloud.calls)
        assertTrue(cloud.questions.single().contains("approved procedure revision"))
        assertTrue(RagStage.ESCALATING in stages)
        assertTrue("cloud answers are never merged into local evidence", response.evidence.isEmpty())
        assertTrue(response.sources.isEmpty())
    }

    @Test
    fun insufficientOnlineButCloudUnavailableIsGraceful() = runBlocking {
        val cloud = CountingCloud { throw EdgeError.CloudUnavailable("no backend") }
        val rag = service(inner = stub { question -> insufficient(question) }, cloud = cloud, online = true)

        val response = rag.answer(RagRequest("question"))

        assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(CloudEscalation.Unavailable, response.escalation)
        assertTrue(response.answer.isNotBlank())
        assertEquals(1, cloud.calls)
    }

    @Test
    fun insufficientOnlineButGenericRemoteErrorIsGraceful() = runBlocking {
        val cloud = CountingCloud { throw IllegalStateException("transport exploded") }
        val rag = service(inner = stub { question -> insufficient(question) }, cloud = cloud, online = true)

        val response = rag.answer(RagRequest("question"))

        assertEquals(CloudEscalation.Unavailable, response.escalation)
        assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
    }

    @Test
    fun blankCloudAnswerIsTreatedAsUnavailableNeverAsAnAnswer() = runBlocking {
        val cloud = CountingCloud { q -> CloudAnswer(q, "   ", "central-engineering") }
        val rag = service(inner = stub { question -> insufficient(question) }, cloud = cloud, online = true)

        val response = rag.answer(RagRequest("question"))

        assertEquals("blank cloud text must not surface as an answer", AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(CloudEscalation.Unavailable, response.escalation)
    }

    @Test
    fun connectivityProbeFailureIsTreatedAsOffline() = runBlocking {
        val cloud = CountingCloud { error("not called") }
        val rag = service(
            inner = stub { question -> insufficient(question) },
            cloud = cloud,
            online = { throw IllegalStateException("probe failed") },
        )

        val response = rag.answer(RagRequest("question"))

        assertEquals(AnswerStatus.INSUFFICIENT_EVIDENCE, response.status)
        assertEquals(CloudEscalation.Offline, response.escalation)
        assertEquals(0, cloud.calls)
    }

    private fun insufficient(question: String) = RagResponse(
        question = question,
        answer = "Local memory does not contain enough evidence to answer this question.",
        status = AnswerStatus.INSUFFICIENT_EVIDENCE,
        sources = emptyList(),
        evidence = emptyList(),
    )

    private class CountingCloud(
        val behavior: (String) -> CloudAnswer,
    ) : CloudAnswerDataSource {
        var calls = 0
        val questions = mutableListOf<String>()

        override suspend fun ask(question: String): CloudAnswer {
            calls++
            questions += question
            return behavior(question)
        }
    }

    private fun stub(behavior: (String) -> RagResponse) = object : RagService {
        override suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit): RagResponse =
            behavior(request.question)
    }

    private fun service(
        inner: RagService,
        cloud: CloudAnswerDataSource,
        online: Boolean,
    ) = EscalatingRagService(inner, cloud, FixedConnectivity(online))

    private fun service(
        inner: RagService,
        cloud: CloudAnswerDataSource,
        online: () -> Boolean,
    ) = EscalatingRagService(inner, cloud, object : ConnectivityMonitor {
        override suspend fun isOnline(): Boolean = online()
    })
}

private class FixedConnectivity(private val online: Boolean) : ConnectivityMonitor {
    override suspend fun isOnline(): Boolean = online
}