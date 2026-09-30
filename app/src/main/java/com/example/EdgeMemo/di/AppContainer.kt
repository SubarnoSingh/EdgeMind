package com.example.EdgeMemo.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.ai.llm.LLMService
import com.example.EdgeMemo.BuildConfig
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.data.cloud.DefaultKnowledgeClassifier
import com.example.EdgeMemo.data.cloud.QdrantCloudAnswerCache
import com.example.EdgeMemo.data.conflict.QdrantConflictStore
import com.example.EdgeMemo.data.local.migration.RoomRecordImporter
import com.example.EdgeMemo.data.sync.QdrantNativeCloudKnowledgeIngestor
import com.example.EdgeMemo.data.cloud.HttpCloudAnswerDataSource
import com.example.EdgeMemo.data.cloud.HttpCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.data.cloud.UnimplementedCloudAnswerDataSource
import com.example.EdgeMemo.data.cloud.UnimplementedCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.data.connectivity.AndroidConnectivityMonitor
import com.example.EdgeMemo.data.connectivity.ConnectivityStatusFlow
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.data.document.MarkdownDocumentExtractor
import com.example.EdgeMemo.data.document.PdfDocumentExtractor
import com.example.EdgeMemo.data.document.TxtDocumentExtractor
import com.example.EdgeMemo.data.repository.QdrantRecordMemoryRepository
import com.example.EdgeMemo.core.retrieval.RetrievalService
import com.example.EdgeMemo.data.retrieval.QdrantRecordRetrievalService
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.sync.SyncScheduler
import com.example.EdgeMemo.domain.cloud.CloudAnswerCache
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.domain.cloud.PullCloudKnowledgeUseCase
import com.example.EdgeMemo.domain.cloud.CacheCloudAnswerUseCase
import com.example.EdgeMemo.domain.conflict.CountUnresolvedConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ListConflictsUseCase
import com.example.EdgeMemo.domain.conflict.ResolveConflictUseCase
import com.example.EdgeMemo.domain.document.DocumentIngestionService
import com.example.EdgeMemo.domain.document.IngestDocumentUseCase
import com.example.EdgeMemo.domain.memory.CreateMemoryUseCase
import com.example.EdgeMemo.domain.memory.DeleteMemoryUseCase
import com.example.EdgeMemo.domain.memory.GetMemoryCountUseCase
import com.example.EdgeMemo.domain.memory.ListMemoriesUseCase
import com.example.EdgeMemo.domain.memory.MemoryRepository
import com.example.EdgeMemo.domain.memory.SearchMemoriesUseCase
import com.example.EdgeMemo.domain.policy.PolicyEngine
import com.example.EdgeMemo.domain.rag.AskQuestionUseCase
import com.example.EdgeMemo.domain.rag.DefaultRagService
import com.example.EdgeMemo.domain.rag.EscalatingRagService
import com.example.EdgeMemo.domain.rag.RagService
import com.example.EdgeMemo.domain.retrieval.RetrieveMemoriesUseCase
import com.example.EdgeMemo.domain.sync.SyncStatusReader
import com.example.EdgeMemo.core.sync.QdrantSyncRemote
import com.example.EdgeMemo.data.local.sync.DefaultQdrantSyncEngine
import com.example.EdgeMemo.data.local.sync.QdrantChangeDetector
import com.example.EdgeMemo.data.local.sync.QdrantConflictResolver
import com.example.EdgeMemo.data.local.sync.QdrantSyncOperationStore
import com.example.EdgeMemo.data.remote.CloudHttpClient
import com.example.EdgeMemo.data.sync.HttpQdrantSyncRemote
import com.example.EdgeMemo.data.sync.QdrantSyncRuntime
import com.example.EdgeMemo.data.sync.QdrantSyncStatusReader
import com.example.EdgeMemo.data.sync.UnimplementedQdrantSyncRemote
import com.example.EdgeMemo.presentation.ask.AskViewModel
import com.example.EdgeMemo.presentation.memory.MemoryViewModel
import java.io.File

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Phase 13.4 — the production container NO LONGER constructs Room.
     * The Qdrant Edge application shard below is the sole local source of
     * truth for records, retrieval, sync state, cursor and conflicts.
     * Room schema/classes remain in the codebase purely as rollback
     * infrastructure, opened ONLY by [RoomRecordImporter] and only when a
     * legacy `edge-memory.db` file physically exists on the device.
     */
    val embeddingService: EmbeddingService = FeatureHashingEmbeddingService()

    private val redactionService = DefaultRedactionService()

    val policyEngine: PolicyEngine = DefaultPolicyEngine(redactionService)

    /**
     * Real cloud boundary (real-cloud integration): the app talks only to the
     * EdgeMind backend, never directly to Qdrant Cloud; every cloud credential
     * lives server-side. A blank `CLOUD_BACKEND_URL` keeps the honest
     * Unimplemented fallbacks (SOURCE_UNAVAILABLE / CloudUnavailable).
     */
    private val cloudBackendUrl = BuildConfig.CLOUD_BACKEND_URL

    private val cloudKnowledgeRemote: CloudKnowledgeRemoteDataSource = if (cloudBackendUrl.isBlank()) {
        UnimplementedCloudKnowledgeRemoteDataSource()
    } else {
        HttpCloudKnowledgeRemoteDataSource(cloudBackendUrl)
    }

    private val syncScheduler = SyncScheduler(appContext)

    /**
     * Phase 13.2/13.4 — the SINGLE active application shard. Authoring
     * (memory repository, document ingestion), retrieval/RAG, the change
     * detector, the sync engine, cloud pull, the cursor, conflicts and the
     * Qdrant sync worker ALL share THIS one [QdrantEdgeRecordStore]
     * instance — one native handle, one owner per process (12A §20).
     * There is no second Qdrant shard in the active graph: `local_qdrant`
     * construction was retired with Phase 13.4.
     */
    val qdrantRecordStore: com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore =
        com.example.EdgeMemo.data.local.record.QdrantEdgeRecordStore(
            File(appContext.filesDir, "qdrant_sync_store"),
        )

    /** Shared Qdrant-native operation store (the only sync outbox). */
    val qdrantSyncOperations: QdrantSyncOperationStore = QdrantSyncOperationStore(qdrantRecordStore)

    /**
     * The one Qdrant-native sync engine instance. Cloud pull (13.4) and the
     * answer cache share it with the worker — one §12 apply pipeline total.
     * Freshly pulled cloud records receive the same deterministic embedding
     * the repository writes, keeping dense retrieval parity (12B vectors are
     * excluded from content identity, so sync semantics are untouched).
     */
    val qdrantSyncEngine: DefaultQdrantSyncEngine by lazy {
        val remote: QdrantSyncRemote = if (cloudBackendUrl.isBlank()) {
            UnimplementedQdrantSyncRemote()
        } else {
            HttpQdrantSyncRemote(CloudHttpClient(cloudBackendUrl))
        }
        DefaultQdrantSyncEngine(
            recordStore = qdrantRecordStore,
            operationStore = qdrantSyncOperations,
            detector = qdrantChangeDetector,
            remote = remote,
            cloudKnowledge = cloudKnowledgeRemote,
            cloudEmbedding = { item ->
                runCatching {
                    embeddingService.embed(
                        buildString {
                            append(item.title)
                            if (item.title.isNotEmpty() && item.content.isNotEmpty()) append("\n")
                            append(item.content)
                        },
                    )
                }.getOrNull()
            },
        )
    }

    private val qdrantChangeDetector: QdrantChangeDetector by lazy {
        QdrantChangeDetector(qdrantRecordStore, qdrantSyncOperations)
    }

    val qdrantSyncRuntime: QdrantSyncRuntime by lazy {
        QdrantSyncRuntime(
            recordStore = qdrantRecordStore,
            engine = qdrantSyncEngine,
            conflicts = QdrantConflictResolver(qdrantRecordStore, qdrantChangeDetector),
            dimension = embeddingService.dimension,
            operations = qdrantSyncOperations,
        )
    }

    /** UI sync status reads the SAME Qdrant operation store the engine mutates. */
    val syncStatusReader: SyncStatusReader = QdrantSyncStatusReader(
        recordStore = qdrantRecordStore,
        operations = qdrantSyncOperations,
        dimension = embeddingService.dimension,
    )

    /** Startup/resume enqueue of the Qdrant-native pipeline (KEEP: never
     *  supersedes an already scheduled or executing run). */
    fun scheduleQdrantSync() = syncScheduler.requestQdrantSync(androidx.work.ExistingWorkPolicy.KEEP)

    /** Explicit trigger (user action / write follow-up) — REPLACE an idle queued run. */
    fun requestQdrantSyncNow() = syncScheduler.requestQdrantSync()

    /**
     * Phase 13.4 — one-time, crash-safe import of pre-cutover Room rows.
     * Only invoked from application startup; the Room builder runs inside
     * the importer and ONLY when a legacy database file actually exists and
     * the completion marker has not been written. Never deletes anything.
     */
    val legacyRecordImporter = RoomRecordImporter(
        appContext = appContext,
        recordStore = qdrantRecordStore,
        engine = qdrantSyncRuntime.engine,
        embeddingService = embeddingService,
    )

    var legacyImportReport: RoomRecordImporter.Report? = null
        private set

    /** Honest diagnostics: an import failure leaves data in Room and is
     *  retried on the next start (the marker is written only on success). */
    var legacyImportError: Throwable? = null
        private set

    fun recordLegacyImportFailure(error: Throwable) {
        legacyImportError = error
    }

    suspend fun migrateLegacyRoomDataIfNeeded(): RoomRecordImporter.Report {
        val report = legacyRecordImporter.importIfNeeded()
        legacyImportReport = report
        if (report.producedSyncWork) requestQdrantSyncNow()
        return report
    }

    // ------------------------------------------------------------------
    // Phase 13.4 — cloud pull, conflicts and answer cache on the shard.
    // ------------------------------------------------------------------

    private val knowledgeClassifier = DefaultKnowledgeClassifier()

    /**
     * Pull runs through the frozen 12B.9 engine pipeline (sys_cursor points,
     * validation, conflict recording, echo immunity) via a thin
     * CloudKnowledgeIngestor adapter — the use case and UI unchanged.
     */
    val pullCloudKnowledge = PullCloudKnowledgeUseCase(
        QdrantNativeCloudKnowledgeIngestor(qdrantSyncEngine),
    )

    /** One Qdrant-native conflict store serving repository + resolver. */
    private val qdrantConflictStore by lazy {
        QdrantConflictStore(qdrantRecordStore, qdrantSyncRuntime.conflicts)
    }

    val listConflicts = ListConflictsUseCase(qdrantConflictStore)
    val countUnresolvedConflicts = CountUnresolvedConflictsUseCase(qdrantConflictStore)
    val resolveConflict = ResolveConflictUseCase(qdrantConflictStore)
    val getConflict = com.example.EdgeMemo.domain.conflict.GetConflictUseCase(qdrantConflictStore)

    /**
     * Phase 13.2 — the ACTIVE memory repository is Qdrant-native. Authoring,
     * listing, search, deletion and document ingestion all persist to the
     * single application shard and feed the frozen Phase-12 change detection
     * (deterministic `UPSERT:<uuid>:<version>` / `TOMBSTONE:...` identities).
     * The legacy `DefaultMemoryRepository` (Room + vectors-only store) is no
     * longer wired into the graph; its class remains for tests/rollback.
     */
    val memoryRepository: MemoryRepository = QdrantRecordMemoryRepository(
        recordStore = qdrantRecordStore,
        syncEngine = qdrantSyncRuntime.engine,
        embeddingService = embeddingService,
        policyEngine = policyEngine,
    )

    private val getMemory = com.example.EdgeMemo.domain.memory.GetMemoryUseCase(memoryRepository)
    val createMemory = CreateMemoryUseCase(memoryRepository)
    val listMemories = ListMemoriesUseCase(memoryRepository)
    val searchMemories = SearchMemoriesUseCase(memoryRepository)
    val deleteMemory = DeleteMemoryUseCase(memoryRepository)
    val getMemoryCount = GetMemoryCountUseCase(memoryRepository)

    private val documentReader = ContentResolverDocumentReader(appContext)

    private val documentExtractors = DocumentExtractorRegistry(
        listOf(
            PdfDocumentExtractor(appContext),
            MarkdownDocumentExtractor(),
            TxtDocumentExtractor(),
        ),
    )

    private val documentChunker = DocumentChunker()

    private val documentIngestionService = DocumentIngestionService(
        reader = documentReader,
        registry = documentExtractors,
        chunker = documentChunker,
        repository = memoryRepository,
    )

    val ingestDocument = IngestDocumentUseCase(documentIngestionService)

    /**
     * Phase 13.3 — the ACTIVE retrieval path is Qdrant-native: dense search
     * and keyword/exact evidence both run over the SAME application shard the
     * repository writes to (one source, no Room, no `local_qdrant`, no
     * dual-source merge). RRF fusion, identifier weighting, superseded/
     * tombstone exclusion and dedup semantics are preserved by
     * [QdrantRecordRetrievalService]. The legacy Room/vector
     * `DefaultRetrievalService`/`KeywordRetriever` classes remain intact for
     * tests and rollback but are no longer wired anywhere.
     */
    private val retrievalService: RetrievalService = QdrantRecordRetrievalService(
        embeddingService = embeddingService,
        recordStore = qdrantRecordStore,
    )

    val retrieveMemories = RetrieveMemoriesUseCase(retrievalService)

    private val llmService: LLMService = ExtractiveLLMService()

    /**
     * Phase 8 optional escalation: local-first RAG stays the core; escalation
     * only triggers when the local pipeline reports insufficient evidence AND
     * the device is online. The cloud answer remote is an honest no-op until a
     * real backend exists (see WORKING.md).
     */
    private val connectivityMonitor: ConnectivityMonitor = AndroidConnectivityMonitor(appContext)

    /** App-scoped reactive connectivity state for UI status (header chip). */
    val connectivityStatus = ConnectivityStatusFlow(appContext)

    private val cloudAnswer: CloudAnswerDataSource = if (cloudBackendUrl.isBlank()) {
        UnimplementedCloudAnswerDataSource()
    } else {
        HttpCloudAnswerDataSource(cloudBackendUrl)
    }

    private val ragService: RagService = EscalatingRagService(
        inner = DefaultRagService(
            retrievalService = retrievalService,
            llmService = llmService,
        ),
        cloudAnswer = cloudAnswer,
        connectivity = connectivityMonitor,
    )

    val askQuestion = AskQuestionUseCase(ragService)

    /**
     * Phase 13.4 — saving a cloud answer writes the SAME application shard
     * through the SAME frozen §12 single-item pipeline as cloud pull. The
     * Room-backed writer/outbox/conflict paths are gone; the legacy class
     * remains only for rollback tests.
     */
    private val cloudAnswerCache: CloudAnswerCache = QdrantCloudAnswerCache(
        recordStore = qdrantRecordStore,
        engine = qdrantSyncEngine,
        classifier = knowledgeClassifier,
    )

    val cacheCloudAnswer = CacheCloudAnswerUseCase(cloudAnswerCache)

    val memoryViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            MemoryViewModel(
                createMemory = createMemory,
                listMemories = listMemories,
                searchMemories = searchMemories,
                deleteMemory = deleteMemory,
                documentReader = documentReader,
                ingestDocument = ingestDocument,
                policyEngine = policyEngine,
                syncStatusReader = syncStatusReader,
                // Phase 13.2: mutations drain through the Qdrant-native
                // pipeline. The legacy Room-outbox worker is NOT triggered by
                // application writes anymore (frozen rollback path only).
                onMemoriesChanged = this@AppContainer::requestQdrantSyncNow,
                pullCloudKnowledge = pullCloudKnowledge,
                listConflicts = listConflicts,
                countUnresolvedConflicts = countUnresolvedConflicts,
                resolveConflict = resolveConflict,
            )
        }
    }

    val askViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            AskViewModel(
                askQuestion = askQuestion,
                cacheCloudAnswer = cacheCloudAnswer,
            )
        }
    }

    // ── Industrial UI shell (UI Phase 1) ─────────────────────────────────
    // The shell owns ONE navigator; child ViewModels receive that same
    // instance through the factories below so navigation is a single
    // deterministic state machine. All reads go through the existing
    // Qdrant-native domain use cases — no Room, no data-layer access.

    val shellViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.shell.ShellViewModel(
                navigator = com.example.EdgeMemo.presentation.shell.EdgeNavigator(),
                isOnline = connectivityStatus.isOnline,
                syncStatusReader = syncStatusReader,
                countUnresolvedConflicts = countUnresolvedConflicts,
            )
        }
    }

    fun dashboardViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.dashboard.DashboardViewModel(
                listMemories = listMemories,
                syncStatusReader = syncStatusReader,
                countUnresolvedConflicts = countUnresolvedConflicts,
                navigator = navigator,
            )
        }
    }

    fun machinesViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.machines.MachinesViewModel(
                listMemories = listMemories,
                listConflicts = listConflicts,
                navigator = navigator,
            )
        }
    }

    fun machineDetailViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
        subjectKey: String,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.machines.MachineDetailViewModel(
                namespace = subjectKey,
                listMemories = listMemories,
                listConflicts = listConflicts,
                createMemory = createMemory,
                navigator = navigator,
            )
        }
    }

    fun recordDetailViewModelFactory(
        memoryId: String,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.machines.RecordDetailViewModel(
                memoryId = memoryId,
                getMemory = getMemory,
            )
        }
    }

    fun createRecordViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.record.CreateRecordViewModel(
                createMemory = createMemory,
                navigator = navigator,
            )
        }
    }

    fun conflictListViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.conflicts.ConflictListViewModel(
                listConflicts = listConflicts,
                countUnresolvedConflicts = countUnresolvedConflicts,
                navigator = navigator,
            )
        }
    }

    fun conflictDetailViewModelFactory(
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
        conflictId: String,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            com.example.EdgeMemo.presentation.conflicts.ConflictDetailViewModel(
                conflictId = conflictId,
                getConflict = getConflict,
                resolveConflict = resolveConflict,
                getMemory = getMemory,
                navigator = navigator,
            )
        }
    }

    fun syncViewModelFactory(
        onSyncNow: () -> Unit,
        navigator: com.example.EdgeMemo.presentation.shell.EdgeNavigator,
    ): ViewModelProvider.Factory =
        viewModelFactory {
            initializer {
                com.example.EdgeMemo.presentation.sync.SyncViewModel(
                    syncStatusReader = syncStatusReader,
                    listConflicts = listConflicts,
                    listMemories = listMemories,
                    onSyncNow = onSyncNow,
                    navigator = navigator,
                )
            }
        }

    /**
     * Process-start durable sync: Phase 13.2 cutover — the ACTIVE path is the
     * Qdrant-native pipeline over the application shard. The legacy Room
     * outbox is frozen: pre-cutover rows stay durably parked (rollback
     * path), they are no longer drained from the app-foreground trigger so
     * the two sync systems cannot run side-by-side.
     */
    fun onAppForeground() {
        syncScheduler.requestQdrantSync(androidx.work.ExistingWorkPolicy.KEEP)
    }
}