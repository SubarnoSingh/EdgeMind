package com.example.EdgeMemo.core.record

/**
 * Query model for the record store.
 * Supports exact retrieval, filtered scroll, semantic search, and hybrid search.
 */
sealed interface RecordQuery {

    /** Retrieve records by exact point IDs. */
    data class ByIds(
        val ids: List<RecordId>,
    ) : RecordQuery

    /** Filtered scroll with pagination. */
    data class Scroll(
        val filter: RecordFilter? = null,
        val limit: Int = 50,
        val offsetId: String? = null,  // point ID to resume from
        val recordTypes: Set<RecordType>? = null,
    ) : RecordQuery {
        constructor(
            filter: RecordFilter? = null,
            limit: Int = 50,
            offsetId: String? = null,
            vararg recordTypes: RecordType,
        ) : this(filter, limit, offsetId, if (recordTypes.isEmpty()) null else recordTypes.toSet())
    }

    /** Semantic vector search with optional payload filter. */
    data class Search(
        val vector: FloatArray,
        val limit: Int = 10,
        val filter: RecordFilter? = null,
        val scoreThreshold: Double? = null,
        val recordTypes: Set<RecordType>? = null,
    ) : RecordQuery

    /** Count records matching a filter. */
    data class Count(
        val filter: RecordFilter? = null,
        val exact: Boolean = true,
        val recordTypes: Set<RecordType>? = null,
    ) : RecordQuery

    companion object {
        /** Scroll all records of given types (active, non-tombstone). */
        fun allActive(
            recordTypes: Set<RecordType> = RecordType.KNOWLEDGE_TYPES,
            limit: Int = 50,
            offsetId: String? = null,
        ): Scroll {
            val filter = RecordFilter.and(
                RecordFilter.activeOnly(),
                RecordFilter.or(*recordTypes.map { RecordFilter.recordType(it) }.toTypedArray())
            )
            return Scroll(filter = filter, limit = limit, offsetId = offsetId)
        }

        /** Search within specific record types. */
        fun searchInTypes(
            vector: FloatArray,
            recordTypes: Set<RecordType> = RecordType.KNOWLEDGE_TYPES,
            limit: Int = 10,
            additionalFilter: RecordFilter? = null,
            scoreThreshold: Double? = null,
        ): Search {
            val typeFilter = RecordFilter.or(*recordTypes.map { RecordFilter.recordType(it) }.toTypedArray())
            val activeFilter = RecordFilter.activeOnly()
            val filter = if (additionalFilter != null) {
                RecordFilter.and(activeFilter, typeFilter, additionalFilter)
            } else {
                RecordFilter.and(activeFilter, typeFilter)
            }
            return Search(vector = vector, limit = limit, filter = filter, scoreThreshold = scoreThreshold)
        }
    }
}

/** Result page for scroll queries. */
data class RecordPage(
    val records: List<Record>,
    val nextOffsetId: String?,
)