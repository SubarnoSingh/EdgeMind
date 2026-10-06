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

    /**
     * Metadata marker identifying a machine/asset DEFINITION record. Creating
     * a machine persists a real record (subject namespace = the machine id) so
     * the asset exists and shows its name BEFORE any activity is captured; the
     * definition record itself is deliberately excluded from timelines/counts
     * so a new machine starts with an empty activity view. It is still a real
     * Qdrant-backed record — never a UI-only fake.
     */
    const val MACHINE_METADATA_KEY = "assetKind"
    const val MACHINE_METADATA_VALUE = "machine"

    /** Subject-key segment used for the machine definition record. */
    const val MACHINE_DEFINITION_SEGMENT = "machine"

    fun isMachineDefinition(memory: Memory): Boolean =
        memory.metadata[MACHINE_METADATA_KEY] == MACHINE_METADATA_VALUE

    /** Namespace token of a machine definition record = the machine id token. */
    fun machineDefinitionSubjectKey(idToken: String): String =
        "$idToken/$MACHINE_DEFINITION_SEGMENT"

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

    /**
     * The ONE canonical machine/asset-id rule: case-folded, separators
     * (hyphen, space, slash, punctuation) removed, so `P-102`, `P102`,
     * `p 102` and `P.102` all map to the same token `p102`. Used both to create
     * a machine and to compare it against existing namespaces, which is what
     * lets the UI reject duplicates instead of showing `P102` and `P-102` as
     * two machines. Returns null when nothing alphanumeric remains.
     *
     * NOTE: this is only the machine-ID canonical form; [namespaceOf] still
     * reads the stored subject-key segment verbatim (lowercased) so existing
     * records — including the `p-101` demo dataset — are never rewritten.
     */
    fun canonicalAssetToken(raw: String): String? =
        raw.trim().lowercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotEmpty() }

    /** Group real records into assets; records without a subject key are
     *  deliberately NOT shown as assets (no identifier to derive).
     *  A machine that has only been DEFINED (its definition record carries the
     *  [MACHINE_METADATA_KEY] marker) still appears here — with an empty
     *  activity count — because the definition record is a real stored record.
     *  [conflictsByNamespace] comes from the real Qdrant conflict store. */
    fun deriveAssets(
        memories: List<Memory>,
        conflictsByNamespace: Map<String, Long> = emptyMap(),
    ): List<Asset> =
        memories.filter { !it.subjectKey.isNullOrBlank() && !it.tombstone }
            .groupBy { namespaceOf(it.subjectKey!!) }
            .mapNotNull { (namespace, records) ->
                val activity = records.filterNot { isMachineDefinition(it) }
                val definition = records.filter { isMachineDefinition(it) }
                    .sortedWith(activityOrder)
                    .firstOrNull()
                // An asset only exists if it has activity records OR a real
                // machine-definition record backing it.
                if (activity.isEmpty() && definition == null) return@mapNotNull null
                val conflicts = conflictsByNamespace[namespace] ?: 0L
                val newest = activity.sortedWith(activityOrder).firstOrNull()
                Asset(
                    namespace = namespace,
                    recordCount = activity.size,
                    // Recency includes the definition timestamp so a freshly
                    // created machine is ordered correctly on the list.
                    lastActivityAt = records.maxOf { it.updatedAt },
                    representativeTitle = newest?.title?.takeIf { it.isNotBlank() }
                        ?: definition?.title?.takeIf { it.isNotBlank() }
                        ?: namespace,
                    maintenanceCount = activity.count {
                        it.type == MemoryType.REPAIR ||
                            it.type == MemoryType.OBSERVATION ||
                            it.type == MemoryType.PROCEDURE ||
                            it.type == MemoryType.EVENT
                    },
                    pendingSyncCount = activity.count {
                        it.syncState == com.example.EdgeMemo.core.model.MemorySyncState.PENDING
                    },
                    unresolvedConflictCount = conflicts,
                    types = activity.map { it.type }.distinct().sortedBy { it.name },
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

    /**
     * Activity records that belong to one asset namespace in [activityOrder].
     * The machine-DEFINITION record is excluded: a freshly created machine has
     * an empty timeline until real observations/maintenance/events/procedures
     * are captured against it.
     */
    fun recordsFor(memories: List<Memory>, namespace: String): List<Memory> =
        memories.filter {
            !it.tombstone &&
                it.subjectKey != null &&
                namespaceOf(it.subjectKey!!) == namespace &&
                !isMachineDefinition(it)
        }
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
