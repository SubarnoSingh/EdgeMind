package com.example.EdgeMemo.core.sync

/**
 * Pure validator for the durable operation state machine.
 *
 * A terminal operation is never recycled. A later record version creates a
 * different [SyncOperationId] instead of mutating a terminal operation.
 */
object SyncOperationTransitions {
    private val legalTransitions = setOf(
        OutboxOperationState.PENDING to OutboxOperationState.IN_FLIGHT,
        OutboxOperationState.IN_FLIGHT to OutboxOperationState.ACKED,
        OutboxOperationState.IN_FLIGHT to OutboxOperationState.FAILED,
        OutboxOperationState.IN_FLIGHT to OutboxOperationState.DEAD,
        OutboxOperationState.FAILED to OutboxOperationState.PENDING,
    )

    fun isLegal(
        from: OutboxOperationState,
        to: OutboxOperationState,
    ): Boolean = from to to in legalTransitions

    fun validate(
        from: OutboxOperationState,
        to: OutboxOperationState,
    ) {
        require(isLegal(from, to)) {
            "Illegal sync operation transition: $from -> $to"
        }
    }
}

fun isLegalSyncOperationTransition(
    from: OutboxOperationState,
    to: OutboxOperationState,
): Boolean = SyncOperationTransitions.isLegal(from, to)

fun validateSyncOperationTransition(
    from: OutboxOperationState,
    to: OutboxOperationState,
) = SyncOperationTransitions.validate(from, to)
