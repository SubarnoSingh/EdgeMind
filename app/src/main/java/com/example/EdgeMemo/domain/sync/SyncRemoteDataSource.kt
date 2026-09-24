package com.example.EdgeMemo.domain.sync

import com.example.EdgeMemo.core.sync.SyncOperation
import com.example.EdgeMemo.core.sync.SyncPushResult

/**
 * The only way operations leave the device. Implementations receive a
 * [SyncOperation] whose payload was produced exclusively by
 * `SyncPayloadFactory` — nobody rebuilds policy here, and private original
 * content cannot reach this interface.
 */
interface SyncRemoteDataSource {
    /**
     * Push one operation. Implementations MUST NOT "succeed" without a real
     * acknowledgement from a remote authority.
     */
    suspend fun push(operation: SyncOperation): SyncPushResult
}