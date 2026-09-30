package com.example.EdgeMemo.domain.conflict

/**
 * Read ONE conflict by its deterministic id through the active
 * [ConflictRepository]. UI Phase 4's detail/resolution flow uses this for
 * the pre-action freshness check (is it still unresolved?) and to re-read
 * the authoritative outcome after a resolution — never to mutate anything.
 */
class GetConflictUseCase(
    private val repository: ConflictRepository,
) {
    suspend operator fun invoke(conflictId: String): Conflict? = repository.get(conflictId)
}
