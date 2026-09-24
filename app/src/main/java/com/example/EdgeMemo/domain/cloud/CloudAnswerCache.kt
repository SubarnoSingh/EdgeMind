package com.example.EdgeMemo.domain.cloud

import com.example.EdgeMemo.core.model.Memory

/**
 * Outcome of an explicit "save cloud answer to memory" action.
 *
 * - [Saved] — the answer was persisted as CLOUD-origin local knowledge.
 * - [AlreadyPresent] — identical knowledge for this question already exists
 *   locally (DUPLICATE under Phase 7 semantics); nothing was written.
 * - [ConflictPrevented] — an existing local memory for the same subject
 *   diverges; the answer was NOT written (Phase 7 conflict semantics, no silent
 *   overwrite). The divergence is persisted as a ConflictEntity through the
 *   same conflict system as cloud pulls, so the user can resolve it in the
 *   Conflicts UI with the existing ConflictResolver.
 */
sealed interface CacheCloudAnswerResult {
    data class Saved(val memory: Memory) : CacheCloudAnswerResult

    data object AlreadyPresent : CacheCloudAnswerResult

    data class ConflictPrevented(val reason: String) : CacheCloudAnswerResult
}

/**
 * Explicit localization of a cloud answer into local memory. This is the ONLY
 * path by which a cloud answer may enter EdgeMind storage, and it is invoked
 * exclusively by an explicit user action.
 */
interface CloudAnswerCache {
    suspend fun save(question: String, answer: String, authority: String?): CacheCloudAnswerResult
}