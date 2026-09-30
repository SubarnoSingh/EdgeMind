package com.example.EdgeMemo.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncOperationTransitionsTest {
    @Test
    fun architectureDefinedTransitionsAreLegal() {
        val legal = listOf(
            OutboxOperationState.PENDING to OutboxOperationState.IN_FLIGHT,
            OutboxOperationState.IN_FLIGHT to OutboxOperationState.ACKED,
            OutboxOperationState.IN_FLIGHT to OutboxOperationState.FAILED,
            OutboxOperationState.IN_FLIGHT to OutboxOperationState.DEAD,
            OutboxOperationState.FAILED to OutboxOperationState.PENDING,
        )

        legal.forEach { (from, to) ->
            assertTrue("$from -> $to", SyncOperationTransitions.isLegal(from, to))
            SyncOperationTransitions.validate(from, to)
        }
    }

    @Test
    fun architectureDefinedIllegalTransitionsAreRejected() {
        val illegal = listOf(
            OutboxOperationState.ACKED to OutboxOperationState.PENDING,
            OutboxOperationState.DEAD to OutboxOperationState.PENDING,
            OutboxOperationState.ACKED to OutboxOperationState.IN_FLIGHT,
            OutboxOperationState.DEAD to OutboxOperationState.IN_FLIGHT,
            OutboxOperationState.PENDING to OutboxOperationState.ACKED,
            OutboxOperationState.PENDING to OutboxOperationState.FAILED,
            OutboxOperationState.PENDING to OutboxOperationState.DEAD,
            OutboxOperationState.FAILED to OutboxOperationState.ACKED,
            OutboxOperationState.FAILED to OutboxOperationState.DEAD,
        )

        illegal.forEach { (from, to) ->
            assertFalse("$from -> $to", SyncOperationTransitions.isLegal(from, to))
            assertIllegalArgument { SyncOperationTransitions.validate(from, to) }
        }
    }

    @Test
    fun terminalStatesAreImmutable() {
        OutboxOperationState.values()
            .filter { it != OutboxOperationState.ACKED }
            .forEach { state ->
                assertFalse(SyncOperationTransitions.isLegal(OutboxOperationState.ACKED, state))
            }
        OutboxOperationState.values()
            .filter { it != OutboxOperationState.DEAD }
            .forEach { state ->
                assertFalse(SyncOperationTransitions.isLegal(OutboxOperationState.DEAD, state))
            }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
