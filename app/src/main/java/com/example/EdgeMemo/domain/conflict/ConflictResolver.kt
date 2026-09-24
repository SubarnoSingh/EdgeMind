package com.example.EdgeMemo.domain.conflict

/**
 * Deterministic conflict resolution. No model/AI decides correctness; the only
 * built-in rules are the identity/version rules the classifier already applied.
 * Every resolution preserves evidence (the conflict row and, where applicable,
 * the superseded/tombstoned local record remain).
 *
 * MERGE is intentionally NOT supported: no deterministic generic merge of two
 * contradictory human/procedural records exists, so fabricating one would
 * silently destroy evidence. Callers must not offer a Merge action.
 */
interface ConflictResolver {

    /** The local version stays authoritative; incoming evidence remains traced. */
    suspend fun keepLocal(conflictId: String, note: String? = null): Conflict

    /** The cloud version becomes the active local knowledge; the old local
     * record is preserved as history (tombstoned where it is a distinct row). */
    suspend fun keepCloud(conflictId: String, note: String? = null): Conflict

    /** Mark as reviewed without changing either side; both remain active. */
    suspend fun dismiss(conflictId: String, note: String? = null): Conflict
}