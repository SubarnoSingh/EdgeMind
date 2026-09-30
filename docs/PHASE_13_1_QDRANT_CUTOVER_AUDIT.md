# Phase 13.1 — Qdrant-Native Application Cutover Audit

**Status:** AUDIT ONLY. No production code, tests, schema, UI, RAG, sync
protocol, or Rust/JNI changed.
**Method:** direct inspection of the actual working checkout (HEAD = Phase 10
commit `2a02f15`; all Phase 12B work is uncommitted in the tree). Every claim
below cites real `file:line` evidence. No previous completion report was taken
on faith.

---

## 1. Executive Summary

EdgeMind today runs **two parallel, non-connected systems**:

1. **The ACTIVE production app path** (what `MainActivity`/ViewModels actually
   use) is **Room-first**. All user knowledge (notes, documents, chunks,
   procedures, observations, cloud-pulled records) is persisted as metadata in
   **Room `MemoryDao`** and as **vectors-only** in a **Phase-2 `QdrantEdgeVectorStore`**
   (shard `local_qdrant`, `NativeBridge.nativeUpsert` — no payload).
   Local→cloud sync on this path uses the **legacy Room outbox**
   (`RoomSyncOutboxWriter` → `DefaultSyncEngine` → `HttpSyncRemoteDataSource`
   → legacy `SyncWorker`).

2. **The Qdrant-native 12B architecture** (`QdrantEdgeRecordStore` in shard
   `qdrant_sync_store`, `QdrantChangeDetector`, `QdrantSyncOperationStore`,
   `DefaultQdrantSyncEngine`, reconciler, conflict recorder/resolver,
   `HttpQdrantSyncRemote`, `QdrantSyncWorker`) is **fully implemented and
   heavily unit-tested, but orphaned from the app's real data.** Its write
   entry point `enqueueIfChanged(record)` has **zero production callers**
   (verified: only declared in `core/sync/QdrantSyncEngine.kt:19` and
   overridden in `data/local/sync/DefaultQdrantSyncEngine.kt:73` — never
   invoked by ingestion, memory CRUD, or the ViewModel path). The only
   production consumer, `QdrantSyncWorker` (`AppContainer.kt:145`), runs
   reconcile/pull/resolve/push against a shard that the app never writes local
   records to.

**The core blocker for Phase 13:** the application never reads or writes the
Qdrant-native record store for its primary memory/retrieval/RAG/ingestion
flows. Making EdgeMind Qdrant-native is therefore a **cutover of the active
runtime call graph** from `[Room MemoryDao + Phase-2 vector-only store]` to
`[QdrantEdgeRecordStore + 12B sync stack]` — not new feature development.

Additional structural divergence that must be resolved during cutover:
- **Two `RecordType`/`MemoryType` models** (`core/record/RecordType.kt`: 16
  types incl. MACHINE/MAINTENANCE/FAILURE/PART; `core/model/Memory.kt`:
  7 `MemoryType`) and **two record shapes** (typed `title`/`content` vs
  generic `payload: Map<String,JsonValue>`).
- **Two sync designs with incompatible identities**: legacy `UPSERT-<memoryId>`
  (`RoomSyncOutboxWriter.kt:109`, single refreshed row) vs 12B
  `UPSERT:<uuid>:<version>` (versioned immutable points).
- **Two conflict stores** (Room `ConflictEntity`/`ConflictDao` vs Qdrant
  `RecordType.CONFLICT` points), **two cursor stores** (Room
  `CloudCursorDao` vs Qdrant `RecordType.SYS_CURSOR` point), **two policy
  payload authorities** (legacy `SyncPayloadFactory` vs 12B detector
  redaction), and **two content-hash schemes** (repo `SHA256("title\ncontent")`
  at `DefaultMemoryRepository.kt:330` vs `CanonicalContentHash`).
- **Two local Qdrant shards** (`local_qdrant` vectors-only +
  `qdrant_sync_store` records) that must collapse into one.

The Rust/JNI layer already supports everything required (payload-only,
vector+payload, batch, filtered scroll, filtered search, filtered count,
payload indexing — `NativeBridge.kt:16-23`), so **no Rust change is required**
to enable the cutover.

---

## 2. Current Runtime Architecture (verified call graph)

```text
MainActivity
  ├─ onCreate → container.scheduleQdrantSync()  (EdgeMindApplication.kt:29)
  └─ onAppForeground → container.onAppForeground() → syncScheduler.requestSync()
        (MainActivity.kt:52 → AppContainer.kt:332-334 → legacy SyncWorker path)

MEMORY (create/list/search/delete/pull/conflicts/resolve)
  MemoryViewModel (presentation/memory/MemoryViewModel.kt)
    → CreateMemoryUseCase / ListMemoriesUseCase / SearchMemoriesUseCase /
      DeleteMemoryUseCase / IngestDocumentUseCase
        → DefaultMemoryRepository (data/repository/DefaultMemoryRepository.kt)
            ├─ vectorStore = QdrantEdgeVectorStore(local_qdrant)  ← vectors ONLY
            └─ outboxWriter = RoomSyncOutboxWriter  → Room memories + sync_outbox
              (fallback dao = MemoryDao when outboxWriter null)
    → PullCloudKnowledgeUseCase → DefaultCloudKnowledgeIngestor
            → DefaultCloudKnowledgeWriter → Room + local_qdrant
            → CloudConflictRecorder → Room conflicts ; cursor → Room cloud_pull_cursor
    → ListConflicts / CountUnresolved / ResolveConflict
            → RoomConflictRepository / DefaultConflictResolver → Room + local_qdrant

ASK (RAG)
  AskViewModel → AskQuestionUseCase → EscalatingRagService
    → DefaultRagService → DefaultRetrievalService
         ├─ dense : QdrantEdgeVectorStore(local_qdrant)
         ├─ keyword: KeywordRetriever → Room MemoryDao.searchByKeyword
         ├─ RRF fusion (core/retrieval/ReciprocalRankFusion.kt)
         └─ metadata resolve → Room MemoryDao.getByIds ; tombstone/supersede → Room
    → ExtractiveLLMService ; cloud escalation → HttpCloudAnswerDataSource
    → CacheCloudAnswerUseCase → DefaultCloudAnswerCache → Room writer + conflicts

BACKGROUND SYNC — TWO INDEPENDENT WORKERS
  A) legacy SyncWorker ("edgememo-sync")  → container.syncEngine = DefaultSyncEngine
        → Room sync_outbox → HttpSyncRemoteDataSource → cloud PUT /sync/operations
  B) QdrantSyncWorker ("edgemind-qdrant-sync") → container.qdrantSyncRuntime
        → DefaultQdrantSyncEngine over QdrantEdgeRecordStore(qdrant_sync_store)
        → reconcile | pullAndApply | resolveByAuthority | pushPending
        (data/sync/QdrantSyncWorker.kt:56-73)
```

The 12B engine in (B) is the target end-state, but it is fed only by cloud
pull — never by the app's local authoring path.

---

## 3. Target Runtime Architecture

```text
Compose UI → ViewModel → UseCase
   → QdrantRecordMemoryRepository (implements existing domain MemoryRepository)
   → QdrantRecordRetrievalService (implements existing RetrievalService)
   → QdrantEdgeRecordStore (SINGLE shard: vectors + payload + envelope)
   → Rust JNI → Qdrant Edge  (SOLE local source of truth)

local write → QdrantChangeDetector.enqueueIfChanged → QdrantSyncOperationStore
   → DefaultQdrantSyncEngine { reconcile | push | pull | conflict } → QdrantSyncWorker → Cloud
```

Room retained temporarily as frozen rollback only; the Phase-2 vectors-only
`local_qdrant` store and the legacy Room outbox/conflict/cursor subsystems
are retired once the record store carries the same responsibilities.

---

## 4. Complete Room Inventory (AUDIT AREA 1)

Schema owner: `data/local/room/EdgeMindDatabase.kt` — `@Database v4,
exportSchema=false`, `@TypeConverters(MemoryTypeConverters)`.

| # | Item | File | Active in prod? | Callers | Owns data | Qdrant replacement | Exists? | Risk |
|---|------|------|-----------------|---------|-----------|--------------------|---------|------|
| 1 | `EdgeMindDatabase` | room/EdgeMindDatabase.kt:20 | YES | AppContainer.kt:100-110 (built eagerly) | whole local DB | QdrantEdgeRecordStore single shard | YES (parallel) | HIGH |
| 2 | `MemoryEntity` | room/MemoryEntity.kt:17 | YES | MemoryDao | ALL user knowledge metadata | `Record` (RecordType.* + envelope) | YES (shape diff) | CRITICAL |
| 3 | `MemoryDao` | room/MemoryDao.kt | YES | DefaultMemoryRepository, DefaultRetrievalService, KeywordRetriever, DefaultCloudKnowledgeWriter, DefaultCloudKnowledgeIngestor, DefaultConflictResolver, DefaultCloudAnswerCache | memories CRUD/list/keyword/supersede/count | `LocalRecordStore.upsert/get/query/scroll/search/count` | YES | CRITICAL |
| 4 | `MemoryMappers` | room/MemoryMappers.kt | YES | repo/ingest/writer/resolver | Memory↔MemoryEntity | new Memory↔Record mapper | **NO** | HIGH |
| 5 | `MemoryTypeConverters` | room/MemoryTypeConverters.kt | YES (via DB) | Room list/map cols | tags/metadata serialization | JsonValue `_tags`/`_metadata` envelope | YES | LOW |
| 6 | `SyncOutboxEntity` | room/SyncOutboxEntity.kt | YES | SyncOutboxDao | legacy outbox ops | Qdrant `RecordType.OUTBOX_OP` points | YES (parallel) | HIGH |
| 7 | `SyncOutboxDao` | room/SyncOutboxDao.kt | YES | RoomSyncOutboxWriter, DefaultSyncEngine | outbox rows/state | QdrantSyncOperationStore | YES | HIGH |
| 8 | `ConflictEntity` | room/ConflictEntity.kt | YES | ConflictDao | conflict evidence | Qdrant `RecordType.CONFLICT` points | YES (parallel) | MEDIUM |
| 9 | `ConflictDao` | room/ConflictDao.kt | YES | CloudConflictRecorder, RoomConflictRepository, DefaultConflictResolver | conflicts table | QdrantConflictRecorder/Resolver | YES | MEDIUM |
| 10 | `CloudCursorEntity` | room/CloudCursorEntity.kt | YES | CloudCursorDao | pull checkpoint | Qdrant `SYS_CURSOR` point (DefaultQdrantSyncEngine.readCursor) | YES (parallel) | MEDIUM |
| 11 | `CloudCursorDao` | room/CloudCursorDao.kt | YES | DefaultCloudKnowledgeIngestor | cloud_pull_cursor | SYS_CURSOR record | YES | MEDIUM |
| 12 | Migrations 1→2→3→4 | room/EdgeMindDatabase.kt:34-120 | registered | AppContainer.addMigrations | schema evolution | N/A (Room only) | rollback-only | LOW |
| 13 | `RoomConflictRepository` | conflict/RoomConflictRepository.kt | YES | listConflicts/countUnresolved (AppContainer.kt:180,201-202) | conflict read model | QdrantConflict* over RecordStore | PARTIAL (no ConflictRepository impl yet) | MEDIUM |
| 14 | `DefaultConflictResolver` | conflict/DefaultConflictResolver.kt | YES | resolveConflict (AppContainer.kt:203-211) | keep-local/cloud/dismiss on Room+vector | QdrantConflictResolver | YES (parallel impl) | MEDIUM |
| 15 | `DefaultMemoryRepository` | repository/DefaultMemoryRepository.kt | YES | all memory use cases + ingestion | orchestrates Room+vector+outbox | QdrantRecordMemoryRepository | **NO** | CRITICAL |
| 16 | `DefaultRetrievalService` | retrieval/DefaultRetrievalService.kt | YES | retrieval use case + RAG | dense+keyword+RRF over Room+vector | Qdrant record-backed retrieval | **NO** | CRITICAL |
| 17 | `KeywordRetriever` | retrieval/KeywordRetriever.kt | YES | DefaultRetrievalService | keyword/exact via Room LIKE | RecordStore filtered scroll/query | PARTIAL (capability exists, no port yet) | HIGH |
| 18 | `DefaultCloudKnowledgeWriter` | cloud/DefaultCloudKnowledgeWriter.kt | YES | cloud ingestor + answer cache | apply cloud→Room+vector | DefaultQdrantSyncEngine.pullAndApply | YES (parallel) | HIGH |
| 19 | `DefaultCloudKnowledgeIngestor` | cloud/DefaultCloudKnowledgeIngestor.kt | YES | pullCloudKnowledge | cloud pull + Room cursor + conflicts | engine.pullAndApply | YES (parallel) | HIGH |
| 20 | `DefaultCloudAnswerCache` | cloud/DefaultCloudAnswerCache.kt | YES | cacheCloudAnswer (Ask) | save answer→Room + conflicts | record-store writer | **NO** | MEDIUM |
| 21 | `CloudConflictRecorder` | cloud/CloudConflictRecorder.kt | YES | ingestor + answer cache | Room conflict rows | QdrantConflictRecorder | YES (parallel) | MEDIUM |
| 22 | `RoomSyncOutboxWriter` | sync/RoomSyncOutboxWriter.kt | YES | memory repo create/update/delete + VM | Room outbox enqueue | QdrantChangeDetector.enqueueIfChanged | YES (parallel) | CRITICAL |
| 23 | `DefaultSyncEngine` (legacy) | sync/DefaultSyncEngine.kt | YES | legacy SyncWorker | Room outbox drain | DefaultQdrantSyncEngine | YES (parallel) | HIGH |
| 24 | `HttpSyncRemoteDataSource` (legacy) | sync/HttpSyncRemoteDataSource.kt | YES | legacy DefaultSyncEngine | legacy push contract | HttpQdrantSyncRemote | YES | HIGH |
| 25 | `SyncWorker` (legacy) | sync/SyncWorker.kt | YES ("edgememo-sync") | requestSync/onAppForeground/onMemoriesChanged | drains Room outbox | QdrantSyncWorker | YES | HIGH |
| 26 | `Room.databaseBuilder` | AppContainer.kt:100 | YES | eager at construction | DB handle | (record store) | YES | HIGH |

Room is therefore the **active system of record for all metadata, the outbox,
conflicts, and the pull cursor** on the production path.

Room-backed **test infra representing production behavior** (must migrate with
their classes, not be deleted to force green): `MemoryMappersTest`,
`EdgeMindDatabaseMigrationTest`, `DefaultMemoryRepositoryTest`,
`DefaultMemoryRepositoryPolicyTest`, `DefaultRetrievalServiceTest`,
`OutboxPrivacyTest`, `DefaultSyncEngineTest`, `SyncWorkerTest`,
`CloudKnowledgeIngestorTest`, `DefaultCloudAnswerCacheTest`,
`DefaultConflictResolverTest`.

---

## 5. Complete Qdrant-Native Inventory (AUDIT AREA 3)

| Component | File | Provides | Production callers TODAY | Should be called by | Missing |
|-----------|------|----------|--------------------------|---------------------|---------|
| `LocalRecordStore` (iface) | core/record/LocalRecordStore.kt | CRUD+query+search+scroll+count+softDelete+flush+indexes | Record store impl + engine | memory repo, ingestion, retrieval | — |
| `QdrantEdgeRecordStore` | data/local/record/QdrantEdgeRecordStore.kt | full payload+vector, mixed collection, indexes, offset-safe scroll, flush | AppContainer (lazy), engine | ALL local persistence | app write path never uses it |
| `Record` / envelope | core/record/Record.kt | generic `_`-prefixed envelope incl §7.3 watermark | engine/detector/store | needs Memory↔Record mapper | no Memory↔Record mapper |
| `RecordType` | core/record/RecordType.kt | 16 types incl domain MACHINE/MAINTENANCE/FAILURE/PART/TECHNICIAN | engine (MEMORY/PROCEDURE/CONFLICT/OUTBOX_OP/SYS_CURSOR in tests) | ingestion/CRUD mapping | unused domain types; MemoryType(7)≠RecordType(16) |
| `RecordQuery`/`RecordFilter`/`FilterCompiler`/`JsonValue`/`RecordId` | core/record/* | filters, search/scroll/count, JSON values | engine + record store + tests | retrieval + list flows | keyword retrieval not ported |
| `QdrantChangeDetector` | data/local/sync/QdrantChangeDetector.kt | §7.3 detect→op, policy gates, echo guard, redaction | engine.enqueueIfChanged (which has **0** prod callers) | every local memory write | **orphaned** |
| `SyncOperationStore`/`QdrantSyncOperationStore` | core/sync + data/local/sync | PENDING→…→DEAD, leases, deterministic ids | engine | fed by detector | not fed |
| `QdrantSyncEngine`/`DefaultQdrantSyncEngine` | core/sync + data/local/sync | push/pull/reconcile/resolve orchestration | **only** QdrantSyncWorker | also fed by app writes | local writes bypass it |
| `QdrantSyncReconciler` | data/local/sync/QdrantSyncReconciler.kt | R1–R5 crash recovery | engine.reconcile() | (unchanged) | — |
| `QdrantConflictRecorder`/`QdrantConflictResolver` | data/local/sync | durable conflict points + authority resolution | engine + worker | replace Room conflict subsystem | not wired to UI use cases |
| `QdrantSyncRuntime` | data/sync/QdrantSyncRuntime.kt | single store+engine+resolver+dim | AppContainer.kt:145 | (correct) | — |
| `QdrantSyncWorker` | data/sync/QdrantSyncWorker.kt | WorkManager exec of 12B engine | WorkManager ("edgemind-qdrant-sync") | becomes the ONLY sync worker | legacy worker still active |
| `UnimplementedQdrantSyncRemote` | data/sync/UnimplementedQdrantSyncRemote.kt | honest SOURCE_UNAVAILABLE push | AppContainer (blank URL) | (correct) | — |
| `HttpQdrantSyncRemote` | data/sync/HttpQdrantSyncRemote.kt | real push over §23.1 | AppContainer (URL set) | (correct) | — |
| Rust/JNI layer | native/qdrant/NativeBridge.kt + rust | vector+payload, filtered search/scroll/count, indexes | QdrantEdgeRecordStore + QdrantEdgeVectorStore | (already sufficient) | none required |

Phase-2 `QdrantEdgeVectorStore` (native/qdrant/QdrantEdgeVectorStore.kt, shard
`local_qdrant`, **vectors only, no payload**) is the store the app actually
uses today for embeddings. It must be **merged into / replaced by**
`QdrantEdgeRecordStore` so one shard holds vector+payload.

**Orphan summary:** the entire right-hand column of the target architecture
exists and is tested, but is fed only by cloud pull. `enqueueIfChanged` = 0
prod callers; `LocalRecordStore` = 0 prod writers other than the empty shard.

---

## 6. Runtime Data-Flow Audit (AUDIT AREA 2)

Legend: Room=Y participates, V2=Phase-2 vector store, QR=Qdrant record store,
CD=change detector, OPS=op store, ENG=12B engine, Prod=production-reachable.

| Flow | Origin | Persisted in | Read from | Room | V2 | QR | CD | OPS | ENG | Prod-reachable |
|------|--------|--------------|-----------|------|----|----|----|-----|-----|----------------|
| A. Machine CRUD | (no dedicated UI) | — | — | — | — | — | — | — | — | **NO — feature not present as a distinct path; would be Memory(type)** |
| B. Maintenance CRUD | (no dedicated UI) | — | — | — | — | — | — | — | — | **NO — same as A; lives under generic Memory** |
| C. Failure/incident CRUD | (no dedicated UI) | — | — | — | — | — | — | — | — | **NO — same as A** |
| D. Observation CRUD | MemoryViewModel create(type) | Room memories + V2 | Room | **Y** | **Y** | N | N | N | N | YES |
| E. Procedure CRUD | MemoryViewModel create(type) | Room + V2 | Room | **Y** | **Y** | N | N | N | N | YES |
| A–E note | the app collapses machine/maintenance/failure/observation/procedure into one **generic `Memory`** (`MemoryType` 7 values). The rich `RecordType` domain types (MACHINE, MAINTENANCE_RECORD, FAILURE, PART, TECHNICIAN, LOCATION) exist only in the 12B model and are **unused by production**. No separate tables per domain type exist today. | | | | | | | | | |
| F. Document ingestion | DocumentIngestionService | Room (createAll) + V2 | Room | **Y** | **Y** | N | N | N | N | YES |
| G. Chunk persistence | per-chunk Memory(DOCUMENT) | Room + V2 | Room | **Y** | **Y** | N | N | N | N | YES |
| H. Search (semantic) | SearchMemoriesUseCase | n/a | V2 dense + Room resolve | **Y** | **Y** | N | N | N | N | YES |
| I. Hybrid retrieval | DefaultRetrievalService | n/a | V2 + Room keyword + Room resolve + Room superseded | **Y** | **Y** | N | N | N | N | YES |
| J. RAG answer | AskViewModel→DefaultRagService | n/a | via I | **Y** | **Y** | N | N | N | N | YES |
| K. Tombstone/delete | DeleteMemoryUseCase | Room deleteById + V2 delete + outbox cancel | Room | **Y** | **Y** | N | N | N | N | YES (note: Room deleteById is HARD delete; tombstone column used by cloud/legacy) |
| L. Sync state | writes | Room syncState + Room outbox | Room | **Y** | — | N | **N** | N | N | YES (legacy path only) |
| M. Conflict handling | pull/answer divergence | Room conflicts | Room | **Y** | — | N | — | — | N | YES (legacy path only) |
| N. Cloud pull | pullCloud | Room + V2 + Room cursor | Room | **Y** | **Y** | N | — | — | **N** | YES (legacy ingestor, NOT engine.pullAndApply) |
| O. Cloud push | background | Room outbox → legacy remote | Room | **Y** | — | N | — | N | N | YES (legacy SyncWorker only) |

**Only flow touching the Qdrant-native record store/CD/OPS/ENG in production is
the 12B `QdrantSyncWorker` cloud-pull into `qdrant_sync_store`.** Everything
the user does is Room + Phase-2 vectors. The two sync paths do not share
records, cursors, conflicts, or operations — they are independent and
concurrently active against the same cloud backend via different
`/sync` request shapes.

---

## 7. Ingestion Audit (AUDIT AREA 4)

Current: `DocumentExtractor(TXT/MD/PDF)` → `TextNormalizer` →
`DocumentChunker` → `MemoryRepository.createAll` → **`DefaultMemoryRepository`**
→ (`QdrantEdgeVectorStore` embed+upsert) + (`RoomSyncOutboxWriter` →
`MemoryDao.insert` + `SyncOutboxDao`). Room participates at metadata persist
and outbox enqueue; Qdrant native record store does NOT.

Target (no implementation here):
```text
extractor → chunker → embedding
   → build Record(recordType=DOCUMENT_CHUNK|MEMORY, vector=embed, payload=title/content/metadata,
                   syncDecision=policy, contentHash=CanonicalContentHash)
   → QdrantEdgeRecordStore.upsertBatch   (vector+payload in ONE point)
   → QdrantChangeDetector.enqueueIfChanged(record)   [local→cloud feeds op store]
```
Interaction requirements for Phase 13.2:
- **Policy**: ingestion must evaluate `PolicyEngine` and set
  `Record.syncDecision` + `redacted*` + `policyReason`, then rely on the
  detector (not `SyncPayloadFactory`) to gate LOCAL_ONLY / SYNC_REDACTED. The
  detector's policy semantics (WORKING 12B.7) already mirror
  `SyncPayloadFactory`; keep behavior identical.
- **canonical content hash**: switch from repo `SHA256("title\ncontent")` to
  `CanonicalContentHash` (12B) which excludes sync-metadata. This is a
  deliberate behavior change to unify with sync.
- **change detection**: every ingest/create/update calls `enqueueIfChanged`.
- **tombstones**: document re-ingest/delete must use `softDelete` (already
  fixed in 12B.13 to mark synced tombstones PENDING) so R4 propagates.
- **provenance**: map `source`/`chunkId`/page/section/documentId metadata into
  `Record.metadata`/envelope so citations survive.

---

## 8. RAG Audit (AUDIT AREA 5)

`DefaultRagService` (domain/rag/DefaultRagService.kt) is **storage-agnostic**:
it depends only on `RetrievalService` + `LLMService`. It does not read Room
directly. The Room dependency sits entirely behind `DefaultRetrievalService`
and `KeywordRetriever`.

Therefore **the RAG cutover boundary is exactly `RetrievalService`**. To make
RAG read from Qdrant without redesigning it, provide a Qdrant-backed
`RetrievalService` that:
- dense: `QdrantEdgeRecordStore.search(RecordQuery.Search(vector,filter,types))`
- keyword/exact: `RecordStore.query/scroll` with `RecordFilter`
  (identifier weighting preserved by scoring logic, not Room `LIKE`)
- RRF: reuse `ReciprocalRankFusion` unchanged
- resolve metadata: evidence comes back **inside the same Record** (no Room
  `getByIds` join) — this is strictly better than the current split
- tombstone/superseded filtering: via envelope filters `_tombstone`,
  `_supersedes` (capability exists in `RecordFilter`/`FilterCompiler`)
- preserve sufficiency checks, citations, offline behavior, cloud escalation
  (`EscalatingRagService` untouched).

**Nothing in `DefaultRagService`/`LLMService`/citation/sufficiency must change**
— only what feeds it. This satisfies "do not redesign RAG."

---

## 9. UI / ViewModel Dependency Audit (AUDIT AREA 6 — trace only)

| Screen / feature | ViewModel | Use cases | Ultimately reads/writes |
|------------------|-----------|-----------|-------------------------|
| Memory list, search, capture, delete | MemoryViewModel | list/search/create/delete/getCount | Room MemoryDao + V2 |
| Document ingestion | MemoryViewModel | ingestDocument | Room + V2 |
| Cloud pull pill | MemoryViewModel | pullCloudKnowledge | Room ingestor (legacy) |
| Conflicts pill / resolve | MemoryViewModel | list/count/resolveConflicts | Room conflicts (legacy) |
| Sync status chips | MemoryViewModel.reload → `syncOutboxWriter.outboxCounts()` (MemoryViewModel.kt:253) | **Room** outbox counts | Room |
| Policy preview | MemoryViewModel → `policyEngine.evaluate` | in-memory, no storage | none (portable) |
| Ask / RAG | AskViewModel | askQuestion, cacheCloudAnswer | Room via retrieval + answer cache |
| Settings | SettingsScreen + ThemeState/ProfileState | `getSharedPreferences("edgemind_ui")` | SharedPreferences (UI-only, NOT memory data) |

The **UI-facing boundaries that must remain stable** for the future Industrial
UI are the **domain use cases** (`CreateMemoryUseCase`, `ListMemoriesUseCase`,
`SearchMemoriesUseCase`, `DeleteMemoryUseCase`, `IngestDocumentUseCase`,
`AskQuestionUseCase`, `PullCloudKnowledgeUseCase`, `ListConflictsUseCase`,
`ResolveConflictUseCase`, `CountUnresolvedConflictsUseCase`,
`GetMemoryCountUseCase`) and the domain interfaces (`MemoryRepository`,
`RetrievalService`, `ConflictRepository`, `ConflictResolver`). They are
storage-agnostic today; the cutover should swap their **implementations**, not
their **contracts**, so UI is unaffected. The one UI-coupled exception is the
sync-status chip, which reads `SyncOutboxWriter.outboxCounts()` (Room). After
cutover this must read the Qdrant engine's summary instead — a boundary to
define in 13.4 (do not change now).

SharedPreferences (theme/profile only, MainActivity.kt:99-111) is a
**legitimate non-memory store** and is NOT in scope to migrate.

---

## 10. AppContainer / DI Audit (AUDIT AREA 7)

`di/AppContainer.kt` (manual DI, no Hilt):
- **Eagerly builds** `Room EdgeMindDatabase` (:100) and `QdrantEdgeVectorStore
  (local_qdrant)` (:98).
- Builds the **legacy** memory/retrieval/sync/cloud graph on Room + V2
  (:124 syncEngine, :213 memoryRepository, :250 retrievalService, :184 writer,
  :191 ingestor, :203 conflict resolver, :289 answer cache).
- Builds the **12B** `qdrantSyncRuntime` **lazily** (:145) → separate
  `QdrantEdgeRecordStore(qdrant_sync_store)` (:142).
- Two independent sync schedulers/workers: legacy `SyncWorker` via
  `requestSync()` (:333, and `onMemoriesChanged` :309) and `QdrantSyncWorker`
  via `scheduleQdrantSync()` (:169).

Findings:
- **Duplicate stores:** `local_qdrant` (vectors) + `qdrant_sync_store`
  (records) are two shards; the record store can hold vectors too, so the
  Phase-2 store is redundant once the record store is wired.
- **Legacy Room paths still production-resolving** for every real user flow.
- **Orphaned Qdrant-native components:** the entire 12B stack except
  `QdrantSyncWorker` has no app-graph caller.
- **Singleton discipline:** `AppContainer` is a single instance owned by
  `EdgeMindApplication`; `qdrantSyncRuntime` is `by lazy` → one shard handle
  per process (correct). No container-level singleton violation, but two
  `NativeBridge` shards are opened under one container (acceptable during
  coexistence, must collapse to one at cutover).

---

## 11. Qdrant Lifecycle / Shard Audit (AUDIT AREA 8)

- `QdrantEdgeVectorStore` (native/qdrant/QdrantEdgeVectorStore.kt): `Mutex`-
  serialized, `ensureReady` (create-or-open), explicit `nativeFlush` after
  upsert/delete (:83,:101). Never `close()`d on the app path (long-lived).
- `QdrantEdgeRecordStore` (data/local/record/QdrantEdgeRecordStore.kt): same
  mutex model; `ensureReady(dimension)`+`ensureIndexes()` invoked by the
  worker bootstrap (QdrantSyncWorker.kt); flush after softDelete/upsert;
  single handle.
- **Ownership:** exactly one `QdrantSyncRuntime` (and its store) per process,
  created lazily in AppContainer, reused by `QdrantSyncWorker`. The legacy
  `SyncWorker` never touches these shards (Room only). **Single-writer per
  shard holds today.**
- **Risk — dual shard, dual sync to one cloud:** after Phase 12B.13 both
  `SyncWorker` (Room outbox) and `QdrantSyncWorker` (record store) can push to
  the same backend concurrently with **different identity schemes**. Today only
  the Room one has local data, so no true double-push of the same record — but
  once Phase 13 feeds BOTH, the same memory could be pushed twice under
  `UPSERT-<id>` and `UPSERT:<uuid>:<version>`. **This is the key lifecycle
  hazard for 13.4: the legacy Room outbox and legacy SyncWorker must be
  disabled in the same cutover step that enables detector-fed Qdrant sync —
  never co-activated for the same records.**
- WorkManager: on-demand `Configuration.Provider` (EdgeMindApplication.kt) +
  default initializer removed in manifest; survives process death/reboot. Two
  unique names keep the two workers from interleaving.
- No production code calls `close()` on either shard; process kill relies on
  flush + reopen (VERIFIED in 12B persistence tests). Not changed here.

---

## 12. Room → Qdrant Migration Matrix

| Area | Current implementation | Active? | Qdrant replacement | Exists? | Migration work | Risk |
|------|------------------------|---------|--------------------|---------|----------------|------|
| Notes / OBSERVATION / PROCEDURE / REPAIR / EVENT / CLOUD_KNOWLEDGE CRUD | `DefaultMemoryRepository` → `MemoryDao` + `local_qdrant` | YES | `QdrantRecordMemoryRepository` → `QdrantEdgeRecordStore` | NO | build repo impl + Memory↔Record mapper | CRITICAL |
| Documents (TXT/MD/PDF) ingestion | `DocumentIngestionService`→`createAll`→Room+V2 | YES | chunk→Record(vector+payload)→RecordStore + detector | PARTIAL (store yes, glue no) | re-point createAll, keep chunker/extractors | CRITICAL |
| Document chunk persistence | Room rows (type DOCUMENT) | YES | Record `DOCUMENT_CHUNK`/`MEMORY` points | YES (type exists) | map chunk metadata to envelope | HIGH |
| Embeddings/indexing | `local_qdrant` vectors-only | YES | RecordStore vector points (one shard) | YES | retire Phase-2 store; single-shard writes | HIGH |
| Dense retrieval | `DefaultRetrievalService.denseSearch`→V2 | YES | `RecordStore.search(Search(vector,filter))` | YES | port, keep scoring | HIGH |
| Keyword/exact retrieval | `KeywordRetriever`→Room `searchByKeyword` | YES | `RecordStore.query/scroll` + `FilterCompiler` | PARTIAL (no keyword scorer yet) | port identifier weighting w/o Room | HIGH |
| Hybrid fusion / RRF | `ReciprocalRankFusion` | YES | unchanged | N/A (storage-free) | none | LOW |
| Tombstone/superseded filter | Room `tombstone` + `supersededIds()` | YES | envelope `_tombstone`/`supersedes` filters | YES | port filters | MEDIUM |
| RAG answer/citations | `DefaultRagService` (+ `EscalatingRagService`) | YES | unchanged (reads new RetrievalService) | YES | none (only its input changes) | MEDIUM |
| Local→cloud sync | `RoomSyncOutboxWriter`+`DefaultSyncEngine`+legacy `SyncWorker` | YES | `QdrantChangeDetector`+`QdrantSyncOperationStore`+`DefaultQdrantSyncEngine`+`QdrantSyncWorker` | YES | feed detector on every write; disable legacy outbox | CRITICAL |
| Cloud→local pull | `DefaultCloudKnowledgeIngestor`+`DefaultCloudKnowledgeWriter`→Room | YES | `DefaultQdrantSyncEngine.pullAndApply`→RecordStore | YES | rewire PullCloudKnowledgeUseCase to engine; retire Room writer | HIGH |
| Sync cursor | Room `CloudCursorDao` | YES | Record `SYS_CURSOR` point | YES | use engine's cursor store | MEDIUM |
| Conflicts | Room `ConflictDao`+`DefaultConflictResolver`+`CloudConflictRecorder` | YES | Qdrant `CONFLICT` points + `QdrantConflictResolver/Recorder` | YES | unify to one conflict subsystem; port ConflictRepository | MEDIUM |
| Sync status (UI) | `RoomSyncOutboxWriter.outboxCounts()` | YES | Qdrant engine summary | NO | add read API | MEDIUM |
| Cloud answer cache (Ask save) | `DefaultCloudAnswerCache`→Room+V2 | YES | RecordStore write path | NO | record-backed writer | MEDIUM |
| Policy decisions | `DefaultPolicyEngine` (in-memory) + `SyncPayloadFactory` | YES | `PolicyEngine` (reuse) + detector redaction gate | YES | drop legacy payload factory | LOW |
| Migrations/converters | Room 1-2-3-4 + converters | YES | N/A (rollback only) | N/A | keep frozen, no forward need | LOW |

---

## 13. Blocking Gaps

1. **[CRITICAL] No production writer to `QdrantEdgeRecordStore`.** App writes
   go to Room + Phase-2 vector store. `enqueueIfChanged` has 0 prod callers →
   detector/operation store are dead in the running app.
2. **[CRITICAL] Retrieval/RAG read Room + `local_qdrant`,** not the record
   store; no `MemoryRepository`/`RetrievalService` impl over `LocalRecordStore`.
3. **[CRITICAL] Two concurrent sync subsystems to one cloud** with
   incompatible identities; legacy Room outbox is the one with data.
4. **[HIGH] No `Memory ↔ Record` mapper**; `MemoryType`(7) vs `RecordType`(16)
   and typed fields vs `payload` map are unmapped across the boundary.
5. **[HIGH] Content-hash scheme divergence** (repo SHA256 of title+content vs
   `CanonicalContentHash`) — first sync after cutover would re-classify every
   record unless reconciled deliberately.
6. **[HIGH] Two shards** (`local_qdrant`, `qdrant_sync_store`) must collapse to
   one vector+payload shard.
7. **[MEDIUM] Duplicate conflict / cursor / policy-payload / cloud-writer
   subsystems** must be consolidated (pick the 12B implementations, retire
   legacy), and the `ConflictRepository`/sync-status read paths must be
   re-pointed for the UI.
8. **[MEDIUM] No data-migration plan** for existing Room rows into the record
   store (or an explicit clean-slate policy).

---

## 14. Risk Classification

- **CRITICAL** (can corrupt/silently drop user memory or double-sync): #1–#3,
  and the ingestion/CRUD/sync-retirement actions.
- **HIGH** (behavioral divergence, retrieval regressions): #4–#6, retrieval &
  pull cutover, Phase-2 store retirement.
- **MEDIUM** (consolidation, UI read paths): #7, conflict/cursor unification,
  sync-status API, cloud answer cache.
- **LOW**: converters, RRF, policy engine reuse, SharedPreferences (out of
  scope).

---

## 15. Required Phase 13.2 Work (foundation + local write cutover)

- Define a single target shard and a `Memory ↔ Record` mapper (title/content/
  tags/source/type/importance/authority/policy/watermark → payload/envelope).
- Implement `QdrantRecordMemoryRepository : MemoryRepository` over
  `LocalRecordStore` (create/createAll/update/delete(soft)/get/list/search/
  count), embedding into the same Record point.
- Re-point ingestion and memory CRUD to it; call
  `QdrantChangeDetector.enqueueIfChanged` on every local write so the record
  store + op store are actually fed.
- Unify content hashing on `CanonicalContentHash`; document the one-time
  re-sync implication.
- Keep Room as an inert/frozen fallback (feature-flagged), NOT dual-write.
- Parity tests (see §18) before switching the AppContainer default.

## 16. Required Phase 13.3 Work (retrieval + RAG cutover)

- Implement a Qdrant-backed `RetrievalService` (dense via `RecordStore.search`
  with filters; keyword/exact via `RecordStore.query/scroll` +
  `FilterCompiler`, preserving identifier weighting; reuse `ReciprocalRankFusion`;
  tombstone/supersede via envelope filters; metadata resolved from the same
  Record — drop the Room `getByIds` join).
- Wire `DefaultRetrievalService`→ new impl in AppContainer; leave
  `DefaultRagService`/`LLMService`/`EscalatingRagService` behavior unchanged.
- Migrate `KeywordRetriever` responsibilities; retire Room `searchByKeyword`.
- Retrieval/RAG parity + offline tests.

## 17. Required Phase 13.4 Work (sync cutover + Room retirement)

- Re-point `PullCloudKnowledgeUseCase` to `DefaultQdrantSyncEngine.pullAndApply`;
  retire legacy `DefaultCloudKnowledgeIngestor`/`DefaultCloudKnowledgeWriter`.
- Disable the legacy Room outbox + legacy `SyncWorker` in the SAME step that
  enables detector-fed `QdrantSyncWorker` push (never co-activate for the same
  records — see §11).
- Consolidate conflicts to the Qdrant subsystem; provide a `ConflictRepository`
  impl over `RecordStore`; re-point UI conflict + resolve + sync-status reads
  to Qdrant-backed sources.
- Collapse to one shard; remove the Phase-2 `local_qdrant` store from the
  active graph; retain Room + migrations as frozen rollback (do not delete).
- Full end-to-end + crash-recovery + policy-privacy + idempotency cutover tests
  before declaring Room retired from the active path.

## 18. Tests Required Before Room Retirement (identify only — not written here)

1. Record-store CRUD parity: create/update/soft-delete/get/list/count +
   reopen persistence + process-death survival.
2. Ingestion → RecordStore (vector+payload) → reopen → dense+keyword+hybrid
   retrieval → grounded RAG with citations, offline.
3. Local→cloud end-to-end through the **wired detector** (writes feed op
   store), deterministic identity, idempotent retries, LOCAL_ONLY never
   leaves, SYNC_REDACTED sends only redaction.
4. Cloud→local pull feeds records the **same retrieval/RAG path** reads.
5. Tombstone/delete propagation via the real app delete path (softDelete →
   R4 → cloud) — currently only proven in the 12B worker test, not via UI repo.
6. Conflict record/resolve end-to-end over Qdrant conflict points feeding UI.
7. Sync-status read API reflects real Qdrant operation states (no Room).
8. No-double-push guard: legacy disabled while native enabled.
9. Migration/clean-slate policy test for pre-existing Room rows.
10. Full-suite parity: all §4 legacy Room tests replaced by record-backed
    equivalents (do not merely delete them).

## 19. Explicit "DO NOT CHANGE YET" List

- Do NOT delete `EdgeMindDatabase`, Room entities/DAOs, migrations, converters.
- Do NOT change the Rust/JNI surface (already sufficient).
- Do NOT modify UI composables, navigation, or ViewModel contracts.
- Do NOT change RAG sufficiency/citation/escalation behavior.
- Do NOT change the sync protocol (`SyncProtocolCodec`, §23.1, backend).
- Do NOT co-activate legacy Room outbox and detector-fed Qdrant push for the
  same records (double-push hazard, §11).
- Do NOT dual-write Room and Qdrant as a way to avoid migration.
- Do NOT alter SharedPreferences (theme/profile) — not memory data.
- Do NOT introduce another local database or a second source of truth.
- Do NOT weaken/delete existing tests to force green.

## 20. Final Readiness Verdict

**READY FOR PHASE 13.2**, with mandatory attention to the §13 blockers.
The Qdrant-native persistence, sync engine, reconciliation, conflict, worker,
and JNI layers already exist and are individually verified; the remaining work
is a **runtime call-graph cutover** (wire ingestion/CRUD/retrieval/RAG/sync to
`QdrantEdgeRecordStore` + the 12B sync stack, then retire Room from the active
path). No new subsystem and no Rust change is required. The single
highest-priority correctness constraint is §11/§19: the legacy Room outbox and
the detector-fed Qdrant pipeline must never push the same records concurrently.
