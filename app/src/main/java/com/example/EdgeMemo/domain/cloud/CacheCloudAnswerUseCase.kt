package com.example.EdgeMemo.domain.cloud

/** Wraps the explicit cloud-answer localization path for the UI. */
class CacheCloudAnswerUseCase(
    private val cache: CloudAnswerCache,
) {
    suspend operator fun invoke(
        question: String,
        answer: String,
        authority: String?,
    ): CacheCloudAnswerResult = cache.save(question, answer, authority)
}