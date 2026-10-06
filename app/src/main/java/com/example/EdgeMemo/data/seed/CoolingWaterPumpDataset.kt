package com.example.EdgeMemo.data.seed

import android.content.Context
import android.net.Uri
import com.example.EdgeMemo.core.model.CreateMemoryInput
import com.example.EdgeMemo.core.model.MemoryType
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import java.io.File

/**
 * The second pitch demo machine — **P-103 Cooling Water Pump** — seeded through
 * the SAME production write seam as [CubicalDataset]: `MemoryRepository.createAll`
 * (embed → Qdrant Edge upsert → policy → change detection). Every record lives in
 * the isolated `p-103` subject namespace, so it never crosses into `p-101`.
 *
 * The domain has no MAINTENANCE/INCIDENT type: MAINTENANCE → REPAIR, INCIDENT →
 * EVENT (mirrors [CubicalDataset]); the dataset's own type stays in metadata/tags.
 *
 * Idempotency: a versioned preference flag skips an already-seeded install, and
 * before writing the inputs are filtered against records already present, so a
 * partial/interrupted first run resumes by seeding ONLY the missing records.
 * Existing user records are never touched and the database is never reset.
 */
object CoolingWaterPumpDataset {

    /** Canonical-ish namespace token for the machine (kept hyphenated, matching P-101). */
    const val NAMESPACE = "p-103"
    const val SOURCE = "cooling water pump demo"

    /** Bundled fictional demo PDF, ingested through the real document pipeline. */
    const val PDF_ASSET = "demo/cooling-water-pump-p-103.pdf"
    const val PDF_NAME = "cooling-water-pump-p-103.pdf"
    const val PDF_INCIDENT_REFERENCE = "DEMO-EVT-103-01"

    private const val PREFS = "edgemind_seed"
    private const val RECORDS_SEEDED_KEY = "cooling_water_pump_v1"
    private const val PDF_SEEDED_KEY = "cooling_water_pump_pdf_v1"

    private data class Entry(
        val datasetType: String,
        val title: String,
        val segment: String,
        val date: String,
        val readableDate: String,
        val content: String,
    )

    // 8 records, one per subject segment (like P-101), covering every suggested
    // Ask question with dated, corroborating evidence and an honest root-cause line.
    private val entries = listOf(
        Entry(
            "OBSERVATION", "Elevated Vibration Reported", "vibration", "2026-07-05", "July 5, 2026",
            "Operator observation: elevated vibration was reported on cooling water pump P-103, most " +
                "noticeable on the outboard bearing end during startup and at normal flow. Suction pressure " +
                "was recorded at approximately 2.4 bar. No visible external damage was found in these " +
                "observations. Further inspection of bearing condition, coupling alignment and possible " +
                "cavitation was recommended.",
        ),
        Entry(
            "MAINTENANCE", "Coupling Alignment Inspected", "coupling", "2026-07-12", "July 12, 2026",
            "Coupling alignment on pump P-103 was inspected during a scheduled maintenance window. The " +
                "alignment was found slightly outside tolerance and was adjusted; soft foot was checked and " +
                "hold-down bolts re-torqued. Vibration decreased after the adjustment but was not fully " +
                "eliminated. The underlying cause remained unconfirmed.",
        ),
        Entry(
            "INCIDENT", "Vibration Alert Logged", "incident", "2026-07-20", "July 20, 2026",
            "A vibration incident was recorded on cooling water pump P-103: an automated vibration alert, " +
                "reference " + PDF_INCIDENT_REFERENCE + ", was logged during a night shift. The incident " +
                "coincided with recurring elevated vibration at the outboard bearing. Initial investigation of " +
                "the incident noted a history of coupling alignment adjustment and unstable suction " +
                "conditions. The cause was not conclusively established and follow-up was recommended.",
        ),
        Entry(
            "OBSERVATION", "Recurring Vibration After Alignment", "recurring-vibration", "2026-08-03", "August 3, 2026",
            "Further observations recorded that vibration returned on P-103 within days of the coupling " +
                "alignment, becoming more noticeable when the pump ran at a higher flow condition. Suction " +
                "pressure remained approximately 2.4 bar across these observations. Cavitation was identified " +
                "as a possible contributing condition, but the available evidence is insufficient to confirm " +
                "cavitation as the root cause of the repeated vibration.",
        ),
        Entry(
            "MAINTENANCE", "Bearing Condition Checked", "bearing", "2026-08-11", "August 11, 2026",
            "During a maintenance visit the outboard bearing on pump P-103 was inspected and found to show " +
                "early wear. This maintenance task corrected lubrication and re-verified coupling alignment " +
                "within tolerance. Continued monitoring of vibration was recommended. The earlier repeated " +
                "vibration was not fully explained by the bearing wear alone.",
        ),
        Entry(
            "PROCEDURE", "P-103 Vibration Follow-up Procedure", "procedure", "2026-08-15", "August 15, 2026",
            "Follow-up maintenance procedure (checklist) for cooling water pump P-103: re-measure vibration " +
                "on both bearing ends after two operating days; verify coupling alignment and soft foot before " +
                "re-tightening hold-downs; record suction and discharge pressure across the observation " +
                "window; log any recurring vibration event with a new incident reference. Root cause must " +
                "not be confirmed from vibration readings alone.",
        ),
        Entry(
            "MAINTENANCE", "Repeated Vibration Investigation", "investigation", "2026-08-24", "August 24, 2026",
            "A maintenance investigation was performed after repeated vibration on P-103. The maintenance " +
                "team reviewed the coupling alignment, the bearing condition check, the vibration " +
                "observations and the vibration incident " + PDF_INCIDENT_REFERENCE + ". Evidence indicates " +
                "recurring vibration occurred alongside unstable suction conditions and a history of alignment " +
                "adjustment. Verifying suction-side conditions and the operating range was recommended before " +
                "further component replacement. Root cause remains unconfirmed.",
        ),
        Entry(
            "PROCEDURE", "P-103 Cooling Water Pump Suction Check", "suction-procedure", "2026-08-28", "August 28, 2026",
            "Maintenance procedure for suspected suction-related vibration on P-103: verify suction pressure " +
                "and suction-side restrictions and confirm the pump is within its intended flow range. Inspect " +
                "the suction line, valves and upstream conditions, and compare vibration and noise across " +
                "operating conditions. Cavitation should not be confirmed solely from rattling or vibration; " +
                "operating and inspection evidence should be reviewed together.",
        ),
    )

    val inputs: List<CreateMemoryInput> = entries.map { e ->
        CreateMemoryInput(
            title = e.title,
            content = "Recorded on ${e.readableDate}. ${e.content}",
            type = when (e.datasetType) {
                "MAINTENANCE" -> MemoryType.REPAIR
                "INCIDENT" -> MemoryType.EVENT
                else -> MemoryType.valueOf(e.datasetType)
            },
            tags = listOf("p-103", "demo", e.datasetType.lowercase(), e.segment),
            source = SOURCE,
            subjectKey = "$NAMESPACE/${e.segment}",
            metadata = mapOf("datasetType" to e.datasetType, "recordedDate" to e.date),
        )
    }

    /** Seeds the P-103 records; skips fully-seeded installs and seeds only missing records. */
    suspend fun seedIfNeeded(context: Context, repository: MemoryRepository) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(RECORDS_SEEDED_KEY, false)) return
        val present = repository.list()
            .map { (it.subjectKey ?: "").trim().lowercase() to it.title.trim().lowercase() }
            .toHashSet()
        val missing = inputs.filter {
            ((it.subjectKey ?: "").trim().lowercase() to it.title.trim().lowercase()) !in present
        }
        if (missing.isNotEmpty()) repository.createAll(missing)
        prefs.edit().putBoolean(RECORDS_SEEDED_KEY, true).apply()
    }

    /**
     * Ingests the bundled fictional demo PDF through the EXISTING document
     * pipeline (extract → chunk → embed → Qdrant Edge) and associates its chunks
     * with the P-103 namespace. This writes real DOCUMENT records — it never
     * fabricates a record that only pretends a PDF was ingested. Guarded by its
     * own flag and an existence check so it is idempotent and cannot duplicate.
     */
    suspend fun seedPdfIfNeeded(
        context: Context,
        repository: MemoryRepository,
        reader: ContentResolverDocumentReader,
        ingest: IngestDocumentUseCase,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PDF_SEEDED_KEY, false)) return
        val alreadyIngested = repository.list().any {
            it.type == MemoryType.DOCUMENT &&
                (it.subjectKey ?: "").startsWith("$NAMESPACE/") &&
                it.metadata[DocumentIngestionService.META_DOCUMENT_ID] != null
        }
        if (!alreadyIngested) {
            val file = copyAssetToCache(context)
            if (file != null) {
                val source = reader.resolve(Uri.fromFile(file))
                ingest(source, NAMESPACE)
            }
        }
        prefs.edit().putBoolean(PDF_SEEDED_KEY, true).apply()
    }

    /** Copies the bundled PDF asset into the app cache once; returns null on failure. */
    private fun copyAssetToCache(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, "seed-docs").apply { mkdirs() }
        val target = File(dir, PDF_NAME)
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(PDF_ASSET).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        target
    }.getOrNull()
}
