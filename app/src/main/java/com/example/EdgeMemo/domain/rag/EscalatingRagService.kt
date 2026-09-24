package com.example.EdgeMemo.domain.rag

import com.example.EdgeMemo.core.common.EdgeError
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import com.example.EdgeMemo.core.rag.AnswerStatus
import com.example.EdgeMemo.core.rag.CloudEscalation
import com.example.EdgeMemo.core.rag.RagRequest
import com.example.EdgeMemo.core.rag.RagResponse
import com.example.EdgeMemo.core.rag.RagStage
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import kotlinx.coroutines.CancellationException

/**
 * Optional edge → cloud escalation, layered over an existing local-first
 * [RagService] (Phase 4 pipeline unchanged).
 *
 * Flow (spec §23, §39):
 *
 * local response
 *   ├─ ANSWERED / ERROR  → returned unchanged, NO cloud request
 *   └─ INSUFFICIENT_EVIDENCE
 *        ├─ offline  → limitation returned, escalation=Offline (honest)
 *        └─ online   → ask cloud (question text ONLY, never local evidence)
 *             ├─ answered  → escalation=Answered(answer+authority); NOT
 *             │              stored, NOT merged into local evidence
 *             └─ unavailable/error → limitation returned, escalation=Unavailable
 *
 * The cloud answer is attributed with its provenance and may only become local
 * memory through an explicit user action elsewhere (CloudAnswerCache). This
 * service never persists anything.
 */
class EscalatingRagService(
    private val inner: RagService,
    private val cloudAnswer: CloudAnswerDataSource,
    private val connectivity: ConnectivityMonitor,
) : RagService {

    override suspend fun answer(request: RagRequest, onStage: (RagStage) -> Unit): RagResponse {
        val response = inner.answer(request, onStage)
        if (response.status != AnswerStatus.INSUFFICIENT_EVIDENCE) {
            return response
        }

        val online = try {
            connectivity.isOnline()
        } catch (_: Exception) {
            false
        }
        if (!online) {
            return response.copy(escalation = CloudEscalation.Offline)
        }

        onStage(RagStage.ESCALATING)
        return try {
            val cloud = cloudAnswer.ask(request.question)
            if (cloud.answer.isBlank()) {
                // A blank "answer" is not an answer — surface the limitation
                // honestly instead of displaying/persisting empty cloud text.
                response.copy(escalation = CloudEscalation.Unavailable)
            } else {
                response.copy(
                    status = AnswerStatus.ANSWERED,
                    answer = cloud.answer,
                    sources = emptyList(),
                    evidence = emptyList(),
                    escalation = CloudEscalation.Answered(
                        question = request.question,
                        answer = cloud.answer,
                        authority = cloud.authority,
                    ),
                )
            }
        } catch (e: EdgeError) {
            response.copy(escalation = CloudEscalation.Unavailable)
        } catch (e: Exception) {
            response.copy(escalation = CloudEscalation.Unavailable)
        }
    }
}