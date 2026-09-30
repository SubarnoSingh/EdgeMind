# EdgeMind — EdgeMemo

**Offline-first AI work-memory and intelligence platform for Android edge devices.**

The device has useful persistent memory: it retrieves and reasons over that memory
**offline**, controls what information leaves the device through an explicit policy
engine, and becomes richer when connectivity returns.

This repository (`EdgeMemo`) is the Android application (`EdgeMind`, `com.example.EdgeMemo`,
v1.0) plus its Rust native Qdrant Edge library and an optional Node.js cloud backend.

Authoritative product documents:

- `docs/EdgeMind_CONTEXT.md`
- `docs/EdgeMind_COMPLETE_PROJECT_SPEC.md`

Current implementation state: `WORKING.md`. Phase records: `docs/PHASE_*.md`, `docs/UI_PHASE_*.md`.

---

## 1. The Core Lifecycle

```text
Knowledge enters edge
        ↓
Local memory (Qdrant Edge shard + deterministic embeddings)
        ↓
Offline retrieval (dense + keyword, RRF fusion, dedup, supersession)
        ↓
AI reasoning (extractive local RAG, optional cloud escalation)
        ↓
New knowledge captured
        ↓
Policy decision (LOCAL_ONLY / SYNC / SYNC_REDACTED)
        ↓
Durable offline outbox (Qdrant-native operation store)
        ↓
Connectivity returns → WorkManager sync
        ↓
Qdrant Server (cloud/shared knowledge)
        ↓
cloud → edge ingest → conflict detection/resolution
        ↓
Richer local memory
```

Nothing is faked: indexing, embedding, vector insertion, progress, sync status and
retrieval are all real application state.

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| UI | Kotlin, Jetpack Compose (Compose BOM 2026.02.01), Material 3 |
| State | ViewModel + StateFlow + coroutines |
| Storage (vectors, records, sync state) | **Qdrant Edge** via Rust FFI — single application shard |
| Native | Rust (`rust/edgememo_qdrant`, `qdrant-edge = 0.8.0`) behind a narrow JNI bridge |
| Embeddings | Deterministic on-device feature-hashing (512-d, unigrams + bigrams + char n-grams, L2-normalized) behind `EmbeddingService` |
| Answers | Extractive local RAG behind `LLMService`/`RagService`; optional cloud escalation behind interfaces |
| Background sync | WorkManager (`QdrantSyncWorker`, retries/backoff/idempotency) |
| Room | **Legacy only** — pre-cutover data importer; the active stack is Qdrant-native |
| Cloud backend (optional) | Node.js + TypeScript + Express + Qdrant Server (`@qdrant/js-client-rest`) |
| Build | AGP 9.4.1, Kotlin 2.2.10, Gradle 9.6, cargo (NDK cross-compilation wired into Gradle) |

---

## 3. Architecture

```text
Compose UI (presentation/)
    ↓
ViewModel / StateFlow
    ↓
Use Cases (domain/)
    ↓
Repositories / Services (data/)
    ↓
Qdrant Edge shard / Embeddings / Sync engine
    ↓
Rust native library (libedgememo_qdrant.so)
```

### Package map (`app/src/main/java/com/example/EdgeMemo/`)

```text
core/            Pure models & primitives
  model/           Memory, CreateMemoryInput, MemoryType, SyncDecision, …
  record/          LocalRecordStore interface, Record/RecordId/RecordType,
                   filters, JSON value codec (Qdrant payload mapping)
  sync/            Qdrant-native sync engine: canonical content hash, change
                   detection, operation store/types/transitions, sync protocol
  retrieval/       RetrievalService/QueryNormalizer/ReciprocalRankFusion
  rag/             RAG models
  policy/          Policy domain models
  document/        Document models
  common/          EdgeError sealed hierarchy (EmbeddingError, LocalStorageError,
                   QdrantError, InvalidDocument, CloudUnavailable, …)
  connectivity/    Online state

ai/
  embedding/       EmbeddingService + FeatureHashingEmbeddingService (offline, deterministic)
  llm/             LLMService + ExtractiveLLMService (local answer generation)

native/qdrant/     JNI boundary (kept narrow)
  NativeBridge           — external funs: create/open/upsert(+payload/batch)/search
                           (+filter)/scroll/retrieve/delete/count(±filter)/optimize/
                           flush/close/createPayloadIndex
  QdrantEdgeVectorStore  — LocalVectorStore implementation
  QdrantNativeException

data/
  local/record/    QdrantEdgeRecordStore — active records live in the Qdrant shard
  local/sync/      operation store persistence
  local/room/      LEGACY database + migration/ importer (pre-cutover rows only)
  repository/      QdrantRecordMemoryRepository (active MemoryRepository), mapper
  retrieval/       QdrantRecordRetrievalService (production pipeline, see §6)
  policy/          DefaultPolicyEngine + DefaultRedactionService
  document/        PDF / Markdown / TXT extractors, ContentResolver reader
  sync/            QdrantSyncWorker (WorkManager), QdrantSyncRuntime,
                   HttpQdrantSyncRemote, status reader, cloud-knowledge ingestor
  cloud/           Cloud answer cache, shared-knowledge sources
  conflict/        QdrantConflictStore

domain/
  memory/          CreateMemoryUseCase, ListMemories, GetMemory, Search, Delete
  policy/          PolicyEngine + RedactionService interfaces
  rag/             AskQuestionUseCase, DefaultRagService (local),
                   EscalatingRagService (local-first + cloud fallback)
  document/        DocumentIngestionService, IngestDocumentUseCase
  conflict/        conflict use cases & models (PULL_CONFLICT, authority, …)
  sync/            SyncStatusReader

di/                AppContainer — manual DI wiring the whole graph
presentation/      See §8
```

Dependency rule: the Rust/Qdrant implementation never leaks past
`native/qdrant/` + `data/local/record/`; everything above speaks the Kotlin
`LocalRecordStore` / `MemoryRepository` / `RetrievalService` boundaries.

---

## 4. Memory Model

Every memory (`core/model/Memory.kt`) carries:

`memoryId, title, content, chunkId, source, type, tags, createdAt, updatedAt,
origin (LOCAL/CLOUD/SYNCED), syncDecision, syncState (LOCAL/PENDING/SYNCED/FAILED),
sensitivity, importance, version, contentHash, subjectKey, supersedes, tombstone,
metadata, policyReason, redactedTitle/redactedContent, authority`

Memory types: `DOCUMENT · NOTE · OBSERVATION · PROCEDURE · REPAIR · EVENT · CLOUD_KNOWLEDGE`

`subjectKey` (e.g. `p-101/seal`) is what groups records into **assets** in the
Machines workspace. Evolving memory is handled via `version`, `contentHash`,
`supersedes`, `tombstone` and `subjectKey` — contradictory knowledge is never
silently overwritten; it surfaces in the conflict system.

---

## 5. Ingestion (real write path)

```text
Input
 ↓ validate/normalize
 ↓ chunk
 ↓ embed locally (FeatureHashingEmbeddingService)
 ↓ policy evaluation (DefaultPolicyEngine, at persistence time)
 ↓ duplicate/change detection (canonical content hash + version)
 ↓ Qdrant Edge upsert (record + vector + payload in the application shard)
 ↓ syncable → durable outbox operation (UPSERT:<uuid>:<version>)
 ↓ activity event
```

The same path serves notes, the Create Record form, the asset composer, and
document ingestion (PDF / Markdown / TXT via SAF `ContentResolver`). Batch writes
use `nativeUpsertBatchWithPayload`.

---

## 6. Retrieval & RAG (offline)

Production pipeline (`QdrantRecordRetrievalService`):

```text
Question
 ↓ QueryNormalizer
 ↓ local embedding
 ↓ dense vector search (same shard, active application record types)
 ↓ keyword/exact scan (bounded, indexed prefilter — keeps identifiers like
   SKF-6205, E-4417, P-101 retrievable)
 ↓ Reciprocal Rank Fusion (RRF, k=60 default)
 ↓ deduplication (content hash + chunk identity)
 ↓ tombstone/superseded exclusion (exhaustive indexed _supersedes scan)
 ↓ ranking → top-K evidence (denseScore / keywordScore / matchedTerms / rank)
 ↓ grounded answer with [n] citations (ExtractiveLLMService)
```

If evidence is insufficient the UI says so honestly (`NOT ENOUGH EVIDENCE`) —
answers are never fabricated, citations are the real records used.

`EscalatingRagService` adds optional cloud escalation **only** when the local
path reports insufficient evidence **and** the device is online; the cloud sees
the question text only, never local evidence, and cloud answers are attributed
with provenance before they can become local `CLOUD_KNOWLEDGE`.

---

## 7. Policy, Privacy and Sync

### Policy engine (`DefaultPolicyEngine`) — ordered rules, explainable

1. **Hard LOCAL_ONLY** — credentials/access content, `RESTRICTED` sensitivity,
   local-only tags (e.g. gate codes). Never enters the outbox.
2. Explicit user choice (`SYNC` / `SYNC_REDACTED`) from the capture UI.
3. `SENSITIVE` → `SYNC_REDACTED` (original stays local; safe representation leaves).
4. Team-shareable types (`PROCEDURE`, `DOCUMENT`) → `SYNC`.
5. `REPAIR` records → `SYNC_REDACTED` (may embed private identifiers).
6. Technical identifiers in content → `SYNC_REDACTED`.
7. Safe default → `LOCAL_ONLY`.

Every decision carries a human-readable `policyReason`, surfaced in the UI.

### Durable offline outbox

- Qdrant-native operation store (`core/sync/`): `operationId, memoryId,
  operationType, payload, createdAt, attempts, state, lastError`.
- States: `PENDING → IN_FLIGHT → ACKED | FAILED | DEAD`; idempotent operation
  IDs (`UPSERT:<uuid>:<version>`, `TOMBSTONE:…`) and canonical content hashes
  make retries safe and change detection deterministic.
- `QdrantSyncWorker` (WorkManager) drains the queue when connectivity returns,
  with retries/backoff. `LOCAL_ONLY` content provably never reaches it.

### Cloud backend (`backend/`, optional)

Node/TypeScript/Express service on **Qdrant Server**:

```text
GET  /health
POST /answers                      cloud answer (question text only)
GET  /knowledge  ·  POST /knowledge/ingest  ·  GET /knowledge/search
PUT  /sync/operations/:operationId idempotent operation upsert (ACK + conflict info)
```

Collections: `device_memory`, `cloud_knowledge`. Pull of shared knowledge
flows back through the native ingestor and the conflict detector
(`PULL_CONFLICT`, authority comparison, unresolved conflicts remain visible).

---

## 8. UI (Compose, dark charcoal / muted periwinkle engineering-tool aesthetic)

Primary tabs (`presentation/shell/EdgeMindShell.kt`, bottom nav):

| Tab | Route | What it shows |
|---|---|---|
| **Dashboard** | `Dashboard` | Real metrics (records, assets, maintenance, conflicts, outbox counts), recent activity, offline notice |
| **Machines** | `Machines` → `MachineDetail(namespace)` | Assets derived from `subjectKey`s; per-asset timeline (maintenance/observations/incidents/procedures/documents), conflicts, asset composer |
| **Ask** | `Ask` | Grounded console: QUESTION → RETRIEVAL → EVIDENCE (numbered source cards) → ANSWER with `[n]` citations |
| **Sync** | `Sync` | Real durable outbox/sync state only — queued work never shown as synchronized |
| **Settings** | `Settings` | Theme (dark/light, persisted), profile name (local-only), memory & sync info |

Pushed routes: `Records` (browser + capture + import), `RecordDetail`,
`Conflicts`/`ConflictDetail` (evidence + resolution), `CitationDetail`,
`CreateRecord` (full capture form: subject, title, detail, tags, type,
sync choice — saves through `CreateMemoryUseCase`).

Statuses (record counts, sync, conflicts, latency-free chips) reflect real
application state only.

---

## 9. Repository Layout

```text
app/                 Android application (Kotlin/Compose)
  src/main/          production code (see §3)
  src/test/          JVM unit + integration tests (real Qdrant native lib loaded
                     via TestNativeLoader; ~575 tests)
  src/androidTest/   instrumentation tests
rust/edgememo_qdrant Rust crate wrapping qdrant-edge 0.8.0 behind JNI
backend/             Optional cloud: Express + Qdrant Server + tests (node --test)
docs/                Context, full spec, per-phase architecture/verification records
UI_demo/             UI mockups
gradle/              Version catalog; Gradle 9.6 wrapper
AGENTS.md            Agent/engineering rules for this repo
WORKING.md           Current verified implementation state
```

---

## 10. Building & Running

### Prerequisites

- **JDK 17+** (AGP 9.4.1) — point `JAVA_HOME` at an installed, working JDK
  (check `ls /usr/lib/jvm` and verify `$JAVA_HOME/bin/java -version`).
- Android SDK with NDK (`local.properties` → `sdk.dir`).
- Rust toolchain with cargo (`~/.cargo/bin/cargo`), Android targets configured —
  Gradle invokes `cargo build --release` per ABI (arm64-v8a / x86_64 via NDK clang)
  and packages `libedgememo_qdrant.so` into `jniLibs`.

### Android app

```bash
./gradlew :app:test            # unit + integration tests
./gradlew lint                 # Android lint
./gradlew assembleDebug        # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug    # or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is large (~100 MB debug) because each debug build bundles the Rust
native library for every ABI.

### Cloud backend (optional — the app is fully functional without it)

```bash
cd backend
npm install
npm run verify:config          # env checks (Qdrant URL, API key, collections)
npm run dev                    # tsx watch src/index.ts
npm test                       # node --test suite
```

The device-side backend URL is set via `CLOUD_BACKEND_URL` in `app/build.gradle.kts`
/ build config; when blank, remote cloud features stay disabled and everything
local keeps working.

---

## 11. Testing Strategy

- **Unit:** chunking, policy decisions, redaction, canonical hashing, sync
  state transitions, conflict resolution, RRF fusion, mappings, embeddings
  determinism.
- **Integration:** memory → embedding → Qdrant Edge (real native library on
  host), policy → outbox, outbox → cloud.
- **Persistence:** data survives process/app restart (verified on device:
  records written before `force-stop` are retrievable after cold start).
- **Offline:** the local memory path (browse, search, capture, ingest, RAG)
  works with no network.
- **Privacy:** `LOCAL_ONLY → no outbox → no transmission` is asserted.
- **On-device smoke:** Dashboard → Add Record → Create Record form → save →
  asset timeline → grounded Ask with citations.

---

## 12. Error Handling & Security Posture

- Specific errors surface as specific states (`EdgeError` subclasses:
  `InvalidInput`, `EmbeddingError`, `LocalStorageError`, `QdrantError`,
  `MemoryNotFound`, `InvalidDocument`, `CloudUnavailable`, …) — never a generic
  "something went wrong".
- No raw sensitive memories in logs, no hardcoded secrets, no cloud dependency
  hidden in the core path, `LOCAL_ONLY` content never leaves the device,
  cloud responses are not trusted blindly (conflict + provenance checks).
- Heavy work (embeddings, document extraction, Qdrant/JNI calls, sync) runs on
  coroutines/dispatchers off the UI thread.

---

## 13. Current Status & Honest Limitations

Verified working (see `WORKING.md` and `docs/PHASE_13_*`):

- Qdrant Edge persistence spike (insert → close → restart → search)
- Qdrant-native application record store + metadata + search
- On-device deterministic embeddings, offline hybrid retrieval with RRF
- Grounded local RAG with citations and insufficient-evidence handling
- Policy engine (LOCAL_ONLY / SYNC / SYNC_REDACTED) with redaction
- Durable Qdrant-native outbox + WorkManager sync pipeline + idempotent ACKs
- Conflict store & resolution UI, activity/timeline UI, application shell UI

Honest limitations:

- Embeddings are a lexical feature-hashing baseline, not a neural model
  (swap behind `EmbeddingService` later).
- Local answers are extractive (no on-device neural LLM yet).
- Room exists only as the legacy pre-cutover importer; fresh installs never
  touch it.
- End-to-end encryption is not implemented (not a current-phase requirement).
- Cloud backend is optional infrastructure; the edge product is complete without it.

---

## 14. Development Order (project phases)

```text
Phase 0  Repo inspection          Phase 6  Offline outbox
Phase 1  Qdrant Edge spike        Phase 7  Qdrant Server sync
Phase 2  Local memory             Phase 8  Cloud → edge knowledge
Phase 3  Documents               Phase 9  Conflict detection/resolution
Phase 4  Local RAG                Phase 10 Persistence verification
Phase 5  Policy engine           Phase 11+ UI polish, activity timeline
```

Priority rule when time is limited: real Qdrant Edge > persistent local memory >
on-device embeddings > offline semantic retrieval > grounded local answers >
policy > durable outbox > cloud sync > conflicts > UI > stretch features.

---

*When uncertain: follow `docs/EdgeMind_CONTEXT.md` and
`docs/EdgeMind_COMPLETE_PROJECT_SPEC.md`, inspect the real repository, preserve
working behavior, make the smallest verifiable change — and never fake the core
edge system.*
