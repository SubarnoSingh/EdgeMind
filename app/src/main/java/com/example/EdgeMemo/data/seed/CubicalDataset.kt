package com.example.EdgeMemo.data.seed

import android.content.Context
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.domain.memory.MemoryRepository

/**
 * The 8 P-101 records from `cubical dataset.pdf`, loaded once per install
 * through the production batch write seam (MemoryRepository.createAll →
 * embedding → one Qdrant Edge upsert → policy → change detection).
 *
 * The domain has no MAINTENANCE/INCIDENT type: MAINTENANCE maps to REPAIR,
 * INCIDENT to EVENT; the dataset's own type is kept in metadata + tags.
 */
object CubicalDataset {

    const val SOURCE = "cubical dataset.pdf"
    private const val PREFS = "edgemind_seed"
    private const val SEEDED_KEY = "cubical_dataset_v1"

    private data class Entry(
        val datasetType: String,
        val title: String,
        val subjectKey: String,
        val date: String,
        val readableDate: String,
        val content: String,
    )

    private val entries = listOf(
        Entry(
            "MAINTENANCE", "Mechanical Seal Leakage", "p-101/seal", "2026-07-14", "July 14, 2026",
            "During routine inspection of centrifugal pump P-101, a small amount of process fluid was " +
                "observed around the mechanical seal housing. The pump remained operational and no abnormal " +
                "vibration was recorded during this inspection. Seal leakage was classified as minor and the " +
                "area was cleaned for continued monitoring. Technician recommended checking seal condition and " +
                "shaft alignment during the next maintenance window.",
        ),
        Entry(
            "OBSERVATION", "Increased Pump Vibration", "p-101/vibration", "2026-07-21", "July 21, 2026",
            "Operator reported intermittent rattling from pump P-101 during operation at normal process load. " +
                "Vibration appeared higher than the previous inspection, particularly during startup. Suction " +
                "pressure was recorded at approximately 2.1 bar. No visible external damage was found. Further " +
                "inspection of suction conditions, bearings, coupling alignment, and possible cavitation was " +
                "recommended.",
        ),
        Entry(
            "MAINTENANCE", "Mechanical Seal Replacement", "p-101/seal", "2026-07-28", "July 28, 2026",
            "The mechanical seal on pump P-101 was replaced following continued leakage. The replacement was " +
                "completed during a scheduled maintenance window. Shaft condition and coupling alignment were " +
                "checked before reassembly. The pump was returned to service after a leak check. No immediate " +
                "leakage was observed after startup.",
        ),
        Entry(
            "INCIDENT", "Repeated Mechanical Seal Failure", "p-101/incident", "2026-08-02", "August 2, 2026",
            "Pump P-101 experienced renewed mechanical seal leakage approximately one week after the previous " +
                "seal replacement. The recurrence was recorded as INC-1042. Initial investigation identified " +
                "unstable suction conditions during part of the operating period. The cause was not conclusively " +
                "established. Recommended follow-up includes checking suction restrictions, operating conditions, " +
                "shaft alignment, bearing condition, and seal installation.",
        ),
        Entry(
            "PROCEDURE", "P-101 Mechanical Seal Inspection Procedure", "p-101/procedure", "2026-08-05", "August 5, 2026",
            "Before replacing the mechanical seal on P-101, technicians should inspect the seal housing for " +
                "leakage patterns and contamination. Check shaft condition, shaft runout, coupling alignment, " +
                "bearing condition, seal faces, elastomers, and installation seating. Verify that process " +
                "conditions are within the pump operating range. Inspect suction pressure and suction-side " +
                "restrictions before concluding that the seal itself is the primary cause of failure.",
        ),
        Entry(
            "OBSERVATION", "Possible Cavitation During Operation", "p-101/cavitation", "2026-08-18", "August 18, 2026",
            "During inspection of pump P-101, intermittent rattling and elevated vibration were again observed. " +
                "The symptoms were more noticeable when the pump was operating near a higher flow condition. " +
                "Suction pressure remained approximately 2.1 bar during the observation. Cavitation was " +
                "identified as a possible contributing condition, but the available evidence is insufficient to " +
                "confirm cavitation as the root cause.",
        ),
        Entry(
            "MAINTENANCE", "Seal Failure Investigation", "p-101/investigation", "2026-08-28", "August 28, 2026",
            "A follow-up investigation was performed after repeated mechanical seal leakage on P-101. The " +
                "maintenance team reviewed the previous seal replacement, operating observations, vibration " +
                "reports, and suction pressure readings. Evidence indicates repeated seal failures occurred " +
                "alongside intermittent vibration and unstable suction conditions. Shaft alignment and bearing " +
                "condition were inspected. The investigation recommends verifying suction-side conditions and " +
                "operating range before another seal replacement. Root cause remains unconfirmed.",
        ),
        Entry(
            "PROCEDURE", "P-101 Cavitation Investigation Procedure", "p-101/cavitation-procedure", "2026-08-30", "August 30, 2026",
            "For suspected cavitation on P-101, technicians should first verify suction pressure and " +
                "suction-side restrictions. Check whether the pump is operating within its intended flow range. " +
                "Inspect the suction line, valves, filters, and upstream conditions. Compare vibration and noise " +
                "observations across operating conditions. Inspect impeller condition if operational checks " +
                "indicate persistent cavitation. Cavitation should not be confirmed solely from rattling or " +
                "vibration; operating and inspection evidence should be reviewed together.",
        ),
    )

    val inputs: List<CreateMemoryInput> = entries.map { e ->
        CreateMemoryInput(
            title = e.title,
            // The record date lives in the content so retrieval/answers can cite it.
            content = "Recorded on ${e.readableDate}. ${e.content}",
            type = when (e.datasetType) {
                "MAINTENANCE" -> MemoryType.REPAIR
                "INCIDENT" -> MemoryType.EVENT
                else -> MemoryType.valueOf(e.datasetType)
            },
            tags = listOf("p-101", e.datasetType.lowercase(), e.subjectKey.substringAfter('/')),
            source = SOURCE,
            subjectKey = e.subjectKey,
            metadata = mapOf("datasetType" to e.datasetType, "recordedDate" to e.date),
        )
    }

    /** Seeds once per install; the flag is written only after every record persisted. */
    suspend fun seedIfNeeded(context: Context, repository: MemoryRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEDED_KEY, false)) return
        repository.createAll(inputs)
        prefs.edit().putBoolean(SEEDED_KEY, true).apply()
    }
}
