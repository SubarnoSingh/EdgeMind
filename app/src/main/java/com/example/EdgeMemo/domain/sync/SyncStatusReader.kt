package com.example.EdgeMemo.domain.sync

import com.example.EdgeMemo.core.sync.SyncSummary

/**
 * Read-only sync-status boundary for the UI (Phase 13.2 cutover).
 *
 * The application previously reached sync counts through [SyncOutboxWriter],
 * a WRITE interface bound to the Room outbox. After the data-layer cutover the
 * authoritative operation state lives in the Qdrant-native operation store,
 * and the UI must read from it without dragging write methods across the
 * boundary. Every implementation reports REAL durable state only — counts are
 * never fabricated.
 */
interface SyncStatusReader {
    /** Operation-store-derived counts for the UI (pending/syncing/synced/failed). */
    suspend fun outboxCounts(): SyncSummary
}
