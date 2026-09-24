package com.example.EdgeMemo.domain.conflict

import com.example.EdgeMemo.core.common.EdgeError

class ListConflictsUseCase(
    private val repository: ConflictRepository,
) {
    suspend operator fun invoke(): List<Conflict> = repository.list()
}

class CountUnresolvedConflictsUseCase(
    private val repository: ConflictRepository,
) {
    suspend operator fun invoke(): Long = repository.countUnresolved()
}

enum class ConflictResolutionAction { KEEP_LOCAL, KEEP_CLOUD, DISMISS }

/**
 * The only allowed resolutions (mirrors [ConflictResolver]): the local record,
 * the cloud record, or reviewed-and-kept-both. No MERGE exists.
 */
class ResolveConflictUseCase(
    private val resolver: ConflictResolver,
) {
    suspend operator fun invoke(conflictId: String, action: ConflictResolutionAction, note: String? = null): Conflict =
        when (action) {
            ConflictResolutionAction.KEEP_LOCAL -> resolver.keepLocal(conflictId, note)
            ConflictResolutionAction.KEEP_CLOUD -> resolver.keepCloud(conflictId, note)
            ConflictResolutionAction.DISMISS -> resolver.dismiss(conflictId, note)
        }
}