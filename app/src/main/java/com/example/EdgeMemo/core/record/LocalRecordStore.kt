package com.example.EdgeMemo.core.record

import com.example.EdgeMemo.core.common.EdgeError
import kotlinx.coroutines.flow.Flow

/**
 * Production interface for the Qdrant-native generic record store.
 * This is the application-facing boundary for record operations.
 * It abstracts over the underlying Qdrant Edge implementation.
 */
interface LocalRecordStore {

    /** Initialize the shard at the given vector dimension. Throws if already exists. */
    suspend fun initialize(dimension: Int)

    /** Reopen an existing shard. */
    suspend fun open()

    /** Create if fresh, otherwise reopen. Idempotent entry point for app restarts. */
    suspend fun ensureReady(dimension: Int)

    /** Ensure required payload indexes exist. Idempotent. */
    suspend fun ensureIndexes()

    /** Release the native shard. */
    suspend fun close()

    /** Check if the store is initialized/opened. */
    val isOpen: Boolean

    // ─── Write operations ───

    /**
     * Upsert a single record (vector + payload).
     * The record's version is used as optimistic version metadata.
     * Returns the updated record with any server-assigned fields.
     */
    suspend fun upsert(record: Record): Record

    /**
     * Upsert multiple records in a batch.
     * NOTE: This is NOT a transaction. Partial failure may leave some records persisted.
     * Callers must handle idempotency via deterministic RecordIds.
     */
    suspend fun upsertBatch(records: List<Record>): List<Record>

    /**
     * Delete a record by ID (physical delete).
     * Returns true if the record existed and was deleted.
     */
    suspend fun delete(id: RecordId): Boolean

    /**
     * Soft-delete a record (sets tombstone = true).
     * The record remains retrievable but is excluded from normal queries.
     */
    suspend fun softDelete(id: RecordId): Record

    // ─── Read operations ───

    /** Retrieve a single record by ID. Returns null if not found or tombstoned. */
    suspend fun get(id: RecordId): Record?

    /** Retrieve multiple records by IDs. Missing IDs are omitted from result. */
    suspend fun getMany(ids: List<RecordId>): List<Record>

    /** Check if a record exists (not tombstoned). */
    suspend fun exists(id: RecordId): Boolean

    // ─── Query operations ───

    /** Execute a query and return matching records. */
    suspend fun query(query: RecordQuery): QueryResult

    /** Scroll records with filter and pagination. */
    suspend fun scroll(query: RecordQuery.Scroll): RecordPage

    /** Semantic search with optional filter. */
    suspend fun search(query: RecordQuery.Search): List<ScoredRecord>

    /** Count records matching a filter. */
    suspend fun count(query: RecordQuery.Count): Long

    /** Force synchronous flush of WAL + segments to disk. */
    suspend fun flush()

    /** Optimize the collection (merge segments, rebuild indexes). */
    suspend fun optimize()
}

/** Record with search score. */
data class ScoredRecord(
    val record: Record,
    val score: Double,
)

/** Unified query result. */
sealed class QueryResult {
    data class Records(val records: List<Record>) : QueryResult()
    data class ScoredRecords(val records: List<ScoredRecord>) : QueryResult()
    data class Page(val page: RecordPage) : QueryResult()
    data class Count(val count: Long) : QueryResult()
}