package com.example.EdgeMemo.core.connectivity

/**
 * Answers "can this device reach the internet right now?" for a synchronous
 * Ask-path decision. Deliberately separate from WorkManager's network
 * constraint, which gates background sync and cannot be queried from the Ask
 * flow. Implementations must report real state only.
 */
interface ConnectivityMonitor {
    suspend fun isOnline(): Boolean
}