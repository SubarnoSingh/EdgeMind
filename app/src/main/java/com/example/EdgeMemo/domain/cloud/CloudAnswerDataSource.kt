package com.example.EdgeMemo.domain.cloud

/**
 * A cloud answer to a question the edge could not answer from local memory.
 * Provenance is carried with the answer ([authority]) because cloud answers are
 * attributed, never silently merged into local evidence.
 */
data class CloudAnswer(
    val question: String,
    val answer: String,
    val authority: String?,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Edge → cloud question resolution used by optional escalation. The ONLY
 * context ever sent to the cloud is the question text itself — never local
 * memory content, evidence, or metadata. Implementations must not "succeed"
 * without a real answer from a remote authority.
 */
interface CloudAnswerDataSource {
    suspend fun ask(question: String): CloudAnswer
}