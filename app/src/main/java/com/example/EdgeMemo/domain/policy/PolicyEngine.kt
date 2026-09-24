package com.example.EdgeMemo.domain.policy

import com.example.EdgeMemo.core.policy.PolicyDecision
import com.example.EdgeMemo.core.policy.PolicyInput

/**
 * Answers: what should happen to this memory?
 *
 * Pure and deterministic — evaluating the same [PolicyInput] twice always
 * yields the same decision and the same [PolicyDecision.reason]. The engine
 * never performs synchronization; it only decides and explains.
 */
interface PolicyEngine {
    fun evaluate(input: PolicyInput): PolicyDecision
}
