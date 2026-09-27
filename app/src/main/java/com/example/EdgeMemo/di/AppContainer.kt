package com.example.EdgeMemo.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import com.example.EdgeMemo.ai.embedding.EmbeddingService
import com.example.EdgeMemo.ai.embedding.FeatureHashingEmbeddingService
import com.example.EdgeMemo.ai.llm.ExtractiveLLMService
import com.example.EdgeMemo.ai.llm.LLMService
import com.example.EdgeMemo.BuildConfig
import com.example.EdgeMemo.core.connectivity.ConnectivityMonitor
import com.example.EdgeMemo.core.document.DocumentChunker
import com.example.EdgeMemo.data.cloud.DefaultCloudAnswerCache
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeIngestor
import com.example.EdgeMemo.data.cloud.DefaultCloudKnowledgeWriter
import com.example.EdgeMemo.data.cloud.DefaultKnowledgeClassifier
import com.example.EdgeMemo.data.cloud.HttpCloudAnswerDataSource
import com.example.EdgeMemo.data.cloud.HttpCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.data.cloud.UnimplementedCloudAnswerDataSource
import com.example.EdgeMemo.data.cloud.UnimplementedCloudKnowledgeRemoteDataSource
import com.example.EdgeMemo.data.conflict.DefaultConflictResolver
import com.example.EdgeMemo.data.conflict.RoomConflictRepository
import com.example.EdgeMemo.data.connectivity.AndroidConnectivityMonitor
import com.example.EdgeMemo.data.connectivity.ConnectivityStatusFlow
import com.example.EdgeMemo.data.document.ContentResolverDocumentReader
import com.example.EdgeMemo.data.document.DocumentExtractorRegistry
import com.example.EdgeMemo.data.document.MarkdownDocumentExtractor
import com.example.EdgeMemo.data.document.PdfDocumentExtractor
import com.example.EdgeMemo.data.document.TxtDocumentExtractor
import com.example.EdgeMemo.data.local.room.EdgeMindDatabase
import com.example.EdgeMemo.data.repository.DefaultMemoryRepository
import com.example.EdgeMemo.data.retrieval.DefaultRetrievalService
import com.example.EdgeMemo.data.retrieval.KeywordRetriever
import com.example.EdgeMemo.data.policy.DefaultPolicyEngine
import com.example.EdgeMemo.data.policy.DefaultRedactionService
import com.example.EdgeMemo.data.sync.DefaultSyncEngine
import com.example.EdgeMemo.data.sync.HttpSyncRemoteDataSource
import com.example.EdgeMemo.data.sync.RoomSyncOutboxWriter
import com.example.EdgeMemo.data.sync.SyncScheduler
import com.example.EdgeMemo.data.sync.UnimplementedSyncRemoteDataSource
import com.example.EdgeMemo.domain.cloud.CloudAnswerDataSource
import com.example.EdgeMemo.domain.cloud.CloudKnowledgeIngestor
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
import com.example.EdgeMemo.domain.sync.SyncEngine
import com.example.EdgeMemo.domain.sync.SyncOutboxWriter
import com.example.EdgeMemo.domain.sync.SyncRemoteDataSource
import com.example.EdgeMemo.native.qdrant.QdrantEdgeVectorStore
import com.example.EdgeMemo.presentation.ask.AskViewModel
import com.example.EdgeMemo.presentation.memory.MemoryViewModel
import java.io.File

class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: EdgeMindDatabase = Room.databaseBuilder(
        appContext,
        EdgeMindDatabase::class.java,
        "edge-memory.db",
    )
        .addMigrations(
            EdgeMindDatabase.MIGRATION_1_2,
            EdgeMindDatabase.MIGRATION_2_3,
            EdgeMindDatabase.MIGRATION_3_4,
        )
        .build()

    private val vectorStore = QdrantEdgeVectorStore(File(appContext.filesDir, "local_qdrant"))

    val embeddingService: EmbeddingService = FeatureHashingEmbeddingService()

    private val redactionService = DefaultRedactionService()

    val policyEngine: PolicyEngine = DefaultPolicyEngine(redactionService)

    private val outboxDao = database.syncOutboxDao()

    /**
     * Real cloud boundary (real-cloud integration): the app talks only to the
     * EdgeMind backend, never directly to Qdrant Cloud; every cloud credential
     * lives server-side. A blank `CLOUD_BACKEND_URL` keeps the honest
     * Unimplemented fallbacks (SOURCE_UNAVAILABLE / CloudUnavailable).
     */
    private val cloudBackendUrl = BuildConfig.CLOUD_BACKEND_URL

    private val syncRemote: SyncRemoteDataSource = if (cloudBackendUrl.isBlank()) {
        UnimplementedSyncRemoteDataSource()
    } else {
        HttpSyncRemoteDataSource(cloudBackendUrl)
    }

    val syncOutboxWriter: SyncOutboxWriter = RoomSyncOutboxWriter(database, database.memoryDao(), outboxDao)

    val syncEngine: SyncEngine = DefaultSyncEngine(
        outboxDao = outboxDao,
        memoryDao = database.memoryDao(),
        remote = syncRemote,
        database = database,
    )

    private val syncScheduler = SyncScheduler(appContext)

    private val cloudKnowledgeRemote: CloudKnowledgeRemoteDataSource = if (cloudBackendUrl.isBlank()) {
        UnimplementedCloudKnowledgeRemoteDataSource()
    } else {
        HttpCloudKnowledgeRemoteDataSource(cloudBackendUrl)
    }

    private val conflictRepository = RoomConflictRepository(database.conflictDao())

    private val knowledgeClassifier = DefaultKnowledgeClassifier()

    private val cloudKnowledgeWriter = DefaultCloudKnowledgeWriter(
        dao = database.memoryDao(),
        database = database,
        vectorStore = vectorStore,
        embeddingService = embeddingService,
    )

    private val cloudKnowledgeIngestor: CloudKnowledgeIngestor = DefaultCloudKnowledgeIngestor(
        remote = cloudKnowledgeRemote,
        classifier = knowledgeClassifier,
        writer = cloudKnowledgeWriter,
        memoryDao = database.memoryDao(),
        conflictDao = database.conflictDao(),
        cursorDao = database.cloudCursorDao(),
    )

    val pullCloudKnowledge = PullCloudKnowledgeUseCase(cloudKnowledgeIngestor)
    val listConflicts = ListConflictsUseCase(conflictRepository)
    val countUnresolvedConflicts = CountUnresolvedConflictsUseCase(conflictRepository)
    val resolveConflict = ResolveConflictUseCase(
        DefaultConflictResolver(
            conflictDao = database.conflictDao(),
            memoryDao = database.memoryDao(),
            database = database,
            embeddingService = embeddingService,
            vectorStore = vectorStore,
        ),
    )

    val memoryRepository: MemoryRepository = DefaultMemoryRepository(
        dao = database.memoryDao(),
        vectorStore = vectorStore,
        embeddingService = embeddingService,
        policyEngine = policyEngine,
        outboxWriter = syncOutboxWriter,
    )

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

    private val keywordRetriever = KeywordRetriever(database.memoryDao())

    private val retrievalService = DefaultRetrievalService(
        embeddingService = embeddingService,
        vectorStore = vectorStore,
        dao = database.memoryDao(),
        keywordRetriever = keywordRetriever,
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

    private val cloudAnswerCache = DefaultCloudAnswerCache(
        classifier = knowledgeClassifier,
        writer = cloudKnowledgeWriter,
        memoryDao = database.memoryDao(),
        conflictDao = database.conflictDao(),
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
                syncOutboxWriter = syncOutboxWriter,
                onMemoriesChanged = syncScheduler::requestSync,
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

    /**
     * Process-start durable sync: request connectivity-gated work so any
     * operations persisted by a previous process are drained when a network
     * becomes available.
     */
    fun onAppForeground() {
        syncScheduler.requestSync()
    }
}