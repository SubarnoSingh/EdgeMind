package com.example.EdgeMemo.presentation.machines

import com.example.EdgeMemo.core.model.Memory
import com.example.EdgeMemo.core.model.MemoryType

/**
 * Phase 1 — machine/asset foundation derived from REAL domain data.
 *
 * The active production domain has no first-class machine entity (verified
 * in the Phase 13.1 audit): nothing exposes or writes `RecordType.MACHINE`
 * records yet. Rather than fabricate machines or add a data path (both
 * forbidden by the UI-phase rules), an asset here is a genuine derivation:
 * the namespace of `Memory.subjectKey` — the domain's real identity field
 * for evolving knowledge (e.g. `p101/torque` ⇒ asset namespace `p101`).
 *
 * This means the list is EMPTY until real records reference assets, and it
 * becomes populated automatically once seeded/field data or the future
 * machine domain API writes records with subject keys. No mocks, ever.
 */
object AssetModel {

    data class Asset(
        val namespace: String,
        val recordCount: Int,
        val lastActivityAt: Long,
        val representativeTitle: String,
        val maintenanceCount: Int,
        val pendingSyncCount: Int,
        val unresolvedConflictCount: Long,
        val types: List<MemoryType>,
    )

    /** Namespace token of a subject key: leading segment before '/'. */
    fun namespaceOf(subjectKey: String): String =
        subjectKey.substringBefore('/').trim().lowercase().ifEmpty { subjectKey.trim().lowercase() }

    /** Group real records into assets; records without a subject key are
     *  deliberately NOT shown as assets (no identifier to derive).
     *  [conflictsByNamespace] comes from the real Qdrant conflict store. */
    fun deriveAssets(
        memories: List<Memory>,
        conflictsByNamespace: Map<String, Long> = emptyMap(),
    ): List<Asset> =
        memories.filter { !it.subjectKey.isNullOrBlank() && !it.tombstone }
            .groupBy { namespaceOf(it.subjectKey!!) }
            .map { (namespace, records) ->
                val conflicts = conflictsByNamespace[namespace] ?: 0L
                val newest = records.sortedWith(activityOrder).firstOrNull()
                Asset(
                    namespace = namespace,
                    recordCount = records.size,
                    lastActivityAt = records.maxOf { it.updatedAt },
                    representativeTitle = newest?.title?.takeIf { it.isNotBlank() } ?: namespace,
                    maintenanceCount = records.count {
                        it.type == MemoryType.REPAIR ||
                            it.type == MemoryType.OBSERVATION ||
                            it.type == MemoryType.PROCEDURE ||
                            it.type == MemoryType.EVENT
                    },
                    pendingSyncCount = records.count {
                        it.syncState == com.example.EdgeMemo.core.model.MemorySyncState.PENDING
                    },
                    unresolvedConflictCount = conflicts,
                    types = records.map { it.type }.distinct().sortedBy { it.name },
                )
            }
            .sortedWith(
                compareByDescending<Asset> { it.lastActivityAt }
                    .thenBy { it.namespace },
            )

    /**
     * UI Phase 5 activity order: newest stored timestamp first; equal or
     * missing timestamps are tied by memory id ascending. The timestamp is
     * never synthesized (0 remains "missing" in presentation).
     */
    val activityOrder: Comparator<Memory> =
        compareByDescending<Memory> { it.updatedAt }
            .thenBy { it.memoryId }

    /** Records that belong to one asset namespace in [activityOrder]. */
    fun recordsFor(memories: List<Memory>, namespace: String): List<Memory> =
        memories.filter { !it.tombstone && it.subjectKey != null && namespaceOf(it.subjectKey!!) == namespace }
            .sortedWith(activityOrder)

    /**
     * Presentation categories mapped from the EXISTING authoritative
     * `MemoryType` taxonomy (no second taxonomy invented):
     *  REPAIR → maintenance · OBSERVATION → observations · EVENT → incidents
     *  PROCEDURE → procedures · DOCUMENT → documents
     *  NOTE / CLOUD_KNOWLEDGE → other knowledge.
     */
    enum class AssetRecordCategory(val label: String) {
        MAINTENANCE("Maintenance"),
        OBSERVATIONS("Observations"),
        INCIDENTS("Incidents"),
        PROCEDURES("Procedures"),
        DOCUMENTS("Documents"),
        OTHER("Other"),
    }

    fun categoryOf(type: MemoryType): AssetRecordCategory = when (type) {
        MemoryType.REPAIR -> AssetRecordCategory.MAINTENANCE
        MemoryType.OBSERVATION -> AssetRecordCategory.OBSERVATIONS
        MemoryType.EVENT -> AssetRecordCategory.INCIDENTS
        MemoryType.PROCEDURE -> AssetRecordCategory.PROCEDURES
        MemoryType.DOCUMENT -> AssetRecordCategory.DOCUMENTS
        else -> AssetRecordCategory.OTHER
    }

    /** Presentation-only category selection over an already-loaded record set. */
    fun recordsForCategory(
        records: List<Memory>,
        category: AssetRecordCategory,
    ): List<Memory> = records
        .filter { categoryOf(it.type) == category }
        .sortedWith(activityOrder)

    /** Category counts of an asset's real records, in stable section order. */
    fun categoryCounts(records: List<Memory>): Map<AssetRecordCategory, Int> =
        records.groupingBy { categoryOf(it.type) }
            .eachCount()
            .let { counts ->
                AssetRecordCategory.entries
                    .filter { counts.containsKey(it) }
                    .associateWith { counts.getValue(it) }
            }
}
