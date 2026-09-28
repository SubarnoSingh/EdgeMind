# PHASE 9 — Qdrant-Only Local Storage Architecture

> EdgeMind — Offline Industrial Intelligence
> Architecture / design document. **No production code was changed to produce this document.**

**Status:** DRAFT — architecture proposal awaiting review and Phase 10 spike verification.

**Baseline commit:** `69377a9` (`fix: finalize EdgeMind dark UI surface hierarchy`)
**Stable fallback tag:** `hackathon-prototype` → `f457023` (must never be modified)
**Inspection date:** 2026-09-28

---

## How to read this document

Every claim in this document is marked with one of four labels:

| Label | Meaning |
|---|---|
| **VERIFIED** | Observed directly in the repository, in the built artifacts, or in the vendored `qdrant-edge 0.8.0` crate source at `~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/qdrant-edge-0.8.0/` |
| **TARGET DESIGN** | What we propose to build. Not implemented. |
| **ASSUMPTION** | A reasonable engineering expectation that has not been verified and must be verified by a spike before being depended upon |
| **OPEN QUESTION** | A decision that needs a human or a spike result before it is final |

If a requirement cannot be implemented safely with Qdrant Edge, this document says so explicitly.
Qdrant is **not** a relational database. This document never pretends otherwise.

---

# 0. Executive summary

The problem-statement clarification requires: **Qdrant Edge is the local storage layer. No
SQL/SQLite/Postgres-style database may remain as the application's primary database.** The current
implementation (Phases 1–8) uses Room as the primary metadata/state store and Qdrant Edge only for
dense vectors (with an **empty `{}` payload** on every point).

The repository inspection establishes that qdrant-edge 0.8.0 — the exact version pinned in
`rust/edgememo_qdrant/Cargo.toml` — **already exposes the primitives needed** to serve as a
record store: arbitrary JSON payloads on points, runtime payload-index creation
(`FieldIndexOperation::CreateIndex`), payload filters on query/scroll/count, paginated scroll with
payload ordering, retrieve-by-id, faceting, multiple named vectors, sparse/BM25 vectors, and
recommend queries. These exist in the crate but **none of them are wired through our JNI boundary
today** (the boundary only exposes create/open/upsert-empty-payload/search/delete/count/optimize/flush).

This document therefore proposes a **phased, flag-gated migration** to a Qdrant-only local record
store ("Qdrant-native record layer") that:

1. keeps the existing Room-backed system working and untouched as the rollback baseline;
2. proves payload persistence across Android process restarts **before** any Room table is removed
   (the same discipline that was used to fix vector persistence);
3. migrates each Room table (memories → knowledge records; sync_outbox → outbox records; conflicts →
   conflict records; cloud_pull_cursor → system record) one at a time, each behind a verified cutover;
4. is honest about what Qdrant does not provide: **no cross-record transactions, no compare-and-set,
   no joins, no relational constraints** — the design compensates with idempotent operation IDs,
   single-writer discipline inside the process, commit-marker patterns, and filter-based referential
   checks.

**Key verified facts that drive the whole design** (details in the appendix):

1. `ExtendedPointId` accepts **only `u64` or UUID** — arbitrary string IDs are rejected
   (VERIFIED, `segment/types.rs`). Our existing memory IDs are `UUID.randomUUID().toString()`, so
   they are already valid point IDs.
2. Payload storage is **mmap-backed and disk-persistent** in the config path
   (VERIFIED, `segment_constructor_base/payload_storage.rs` routes both `Mmap` and `InRamMmap`
   through `PayloadStorageImpl::open_or_create`; the non-persistent `InMemoryPayloadStorage` is
   selected only by test code, not by `EdgeConfig`). The current shard config uses
   `on_disk_payload(false)` = `InRamMmap` = "store on disk, populate on load". Payload persistence
   across Android process restart has **never been exercised** because the JNI boundary writes
   `json!({})` — it must be proven by a spike (same fix pattern as the `nativeFlush` vector fix).
3. The current `EdgeShard` config holds **one named dense vector** (`"semantic"`, Cosine, 512-dim,
   WAL segments 4 MiB). Named vectors are config-level; `CreateVectorName`/`DeleteVectorName` exist
   at runtime.

---

# 1. Current architecture

## 1.1 Current architecture diagram (VERIFIED)

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ Android application (com.example.EdgeMemo)                                    │
│                                                                               │
│  Compose UI  ─ AskScreen / MemoryScreen / SettingsScreen                      │
│      │                                                                        │
│      ▼                                                                        │
│  ViewModels (StateFlow)  ─ AskViewModel / MemoryViewModel                     │
│      │                                                                        │
│      ▼                                                                        │
│  Use cases  ─ Create/List/Search/Delete/Count Memory, IngestDocument,         │
│               AskQuestion, RetrieveMemories, PullCloudKnowledge,              │
│               CacheCloudAnswer, List/Count/Resolve Conflicts                  │
│      │                                                                        │
│      ▼                                                                        │
│  Services & repositories                                                      │
│   ├─ DefaultMemoryRepository ── policy → embed → Qdrant vector → Room row     │
│   │        (vector first, Room second, best-effort vector rollback)           │
│   ├─ DefaultRetrievalService ─ dense(Qdrant) + keyword(Room LIKE) → RRF       │
│   ├─ DefaultRagService / EscalatingRagService ─ grounded/extractive answer    │
│   ├─ DefaultPolicyEngine / DefaultRedactionService ─ LOCAL_ONLY/SYNC/REDACTED │
│   ├─ RoomSyncOutboxWriter ─ memory row + outbox row in ONE Room transaction   │
│   ├─ DefaultSyncEngine ─ guarded claim → push → ACK/FAILED/DEAD               │
│   ├─ DefaultCloudKnowledgeIngestor/Writer ─ pull, classify, apply (Room+vec)  │
│   ├─ DefaultConflictResolver / RoomConflictRepository ─ keepLocal/Cloud/dismiss│
│   └─ DefaultCloudAnswerCache ─ explicit save of cloud answers                 │
│      │                                                                        │
│      ▼                                                                        │
│  Storage                                                                    │
│   ├─ Room (edge-memory.db, v4):                                               │
│   │     memories / sync_outbox / conflicts / cloud_pull_cursor                │
│   ├─ Qdrant Edge (filesDir/local_qdrant):                                     │
│   │     1 named dense vector "semantic" (Cosine, 512-dim)                     │
│   │     payloads written as json!({})  ← EMPTY, never read back               │
│   │     flush() after every upsert batch and delete (Android restart fix)     │
│   └─ SharedPreferences ("edgemind_ui") — profile name only (UI preference)    │
│      │                                                                        │
│  Native boundary                                                              │
│   └─ LocalVectorStore → QdrantEdgeVectorStore → NativeBridge (JNI) →          │
│      rust/edgememo_qdrant (store.rs/jni.rs/error.rs) → qdrant-edge 0.8.0      │
│      (EdgeShard: create/open/upsert/delete/search/count/optimize/flush)       │
│      │                                                                        │
│  Background                                                                    │
│   └─ WorkManager: SyncWorker (unique "edgememo-sync", REPLACE, CONNECTED,     │
│      exponential backoff 10s)                                                 │
└──────────────────────────────────┬───────────────────────────────────────────┘
                                   │ HTTPS (only when CLOUD_BACKEND_URL set)
                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ EdgeMind backend (Node/Express + TS) — owns all cloud credentials             │
│   PUT /sync/operations/:id (idempotent) → Qdrant Cloud `device_memory`        │
│   GET /knowledge (cursor pagination) ← Qdrant Cloud `cloud_knowledge`         │
│   POST /answers → cloud LLM                                                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

The Kotlin → Rust boundary today (`native/qdrant/`):

```text
LocalVectorStore (interface)
  ├─ initialize(dimension) / open() / ensureReady(dimension) / close()
  ├─ upsert(List<VectorPoint>)   ← payload is hard-coded json!({}) in store.rs
  ├─ search(vector, limit)       ← dense only, no filter, no payload return
  ├─ delete(id) / count() / optimize()
QdrantEdgeVectorStore  (Mutex-serialized; flush after every write batch)
NativeBridge           (9 external fns; no payload, no filter, no scroll)
rust store.rs          (build_config: 1 vector "semantic", on_disk_payload(false))
rust jni.rs            (create/open/upsert/delete/search/count/optimize/flush/close)
```

## 1.2 Every place Room/SQLite is used today (VERIFIED)

The Room database (`edge-memory.db`, version 4) contains four tables. The following table is the
complete inventory.

| Current Room Entity | Purpose | Current Consumers | Migration Destination | Migration Difficulty | Notes |
|---|---|---|---|---|---|
| `MemoryEntity` (`memories`) | Metadata for every memory: title, content, type, tags, timestamps, origin, sync decision/state, sensitivity, importance, version, contentHash, subjectKey, supersedes, tombstone, metadata map, policy reason, redacted title/content, authority | `DefaultMemoryRepository` (create/createAll/update/delete/get/list/search), `DefaultRetrievalService` (resolve hits, superseded ids), `KeywordRetriever` (`LOWER LIKE` keyword search), `DefaultSyncEngine` (payload enrichment at push time), `DefaultCloudKnowledgeWriter` (upsert/tombstone), `DefaultKnowledgeClassifier` (`findBySubjectKey`), `DefaultConflictResolver`, `DefaultCloudKnowledgeIngestor`, `DefaultCloudAnswerCache`, `MemoryViewModel` (via use cases) | One Qdrant point per memory in collection `records`; payload = full memory fields; dense vector = embedding | **Medium-High** | Most widely consumed table (~11 consumers). Depends on: list ordering (scroll `order_by updated_at`), keyword search (Text index or BM25 sparse vector), subject lookup (keyword index on `subject_key`), superseded lookup (indexed `supersedes`). Pagination and exact-count semantics must be re-verified against qdrant-edge. |
| `SyncOutboxEntity` (`sync_outbox`) | Durable outbox: operationId (PK = idempotency key `UPSERT-<memoryId>`), memoryId, payload title/content, createdAt, attempts, state (PENDING/IN_FLIGHT/ACKED/FAILED/DEAD), lastError | `RoomSyncOutboxWriter` (insert/refresh/cancel), `DefaultSyncEngine` (recoverStaleInFlight, selectRetryable, guarded `claim`, markAcknowledged, markState, counts), `SyncWorker`, `MemoryViewModel` (summary) | Qdrant points with `record_type=outbox_op`, payload-only (no vector), `operation_id` as indexed keyword payload field | **High** | The hard part: today the memory row + outbox row are written in **one SQLite transaction**, and `claim` is an atomic compare-and-set (`UPDATE … WHERE state IN (PENDING,FAILED)`). Qdrant has **no transactions and no CAS**. See §10 for the redesign (single-writer + InsertOnly + server idempotency). |
| `ConflictEntity` (`conflicts`) | Conflict records with both sides' evidence (title/content/version/hash/origin/authority), state, resolution | `RoomConflictRepository`, `DefaultConflictResolver`, `DefaultCloudKnowledgeIngestor`, `DefaultCloudAnswerCache`, `MemoryViewModel` | Qdrant points with `record_type=conflict`, payload-only, deterministic UUID-ish `conflict_id` (SHA-256 derived) as indexed keyword field | **Low-Medium** | Self-contained rows, no transactional coupling to memories except during resolution (resolver writes memory row + conflict row together today). |
| `CloudCursorEntity` (`cloud_pull_cursor`) | Single-row incremental cloud-pull checkpoint | `DefaultCloudKnowledgeIngestor` | One Qdrant point with `record_type=sys_cursor`, payload-only | **Low** | Trivially migrated; the interesting question is how app state in general lives in Qdrant (see §2.6). |

Additional non-Room local state that exists today (VERIFIED): `SharedPreferences("edgemind_ui")`
holds only the user's profile name (UI preference). It is out of scope for this migration unless
"eventually local application state" in the problem statement is interpreted to include it
(OPEN QUESTION in §2.6).

## 1.3 What actually works today (VERIFIED)

From `WORKING.md` (kept current by prior phases) and the test suite:

- **Phase 1:** Android → JNI → Rust → qdrant-edge 0.8.0 → `EdgeShard` create/upsert/search/close/
  reopen/search, including across a separate host process. Persistence on physical Android devices
  required the `nativeFlush` fix (Android kills run neither graceful `Drop` nor WAL replay in the
  qdrant-edge version used); `QdrantEdgeVectorStore` flushes after every write batch.
- **Phases 2–8:** persistent local memory (Room + Qdrant), offline ingestion (PDF/Markdown/TXT),
  deterministic 512-dim hashing embeddings, hybrid retrieval (dense + Room `LIKE` + RRF), grounded
  extractive RAG with citations, policy engine with `LOCAL_ONLY`/`SYNC`/`SYNC_REDACTED`, durable
  Room outbox + WorkManager sync with idempotent `operationId`, cloud pull with cursor, conflict
  detection/resolution, cloud answer escalation + explicit save-to-memory, real Node backend with
  Qdrant Cloud push (idempotent by operationId) — real cloud connectivity itself is
  **NOT VERIFIED** (no live credentials), per WORKING.md.
- **Test surface:** 244 Android host/Robolectric tests, 0 failures; 29 backend tests, 0 failures;
  `:app:assembleDebug` + `:app:lintDebug` green. Android on-device runtime re-verification is
  documented as limited by no attached device.

## 1.4 What the Qdrant boundary cannot do today (VERIFIED — the gap)

`WORKING.md` explicitly lists as NOT IMPLEMENTED: "Qdrant payload handling in the native boundary
(currently empty `{}` payload; retrieval maps point ids back to Room metadata instead)". Concretely,
the JNI surface has **no** payload write, payload read, filter, scroll, retrieve, payload-index
creation, or recommend query. All of the above are available in the underlying crate but unused.

---

# 2. Target architecture

## 2.1 Target layering (TARGET DESIGN)

```text
Compose UI (ASK / MEMORY / CAPTURE / SYNC / CONFLICTS / SETTINGS)
   ↓
ViewModel / StateFlow
   ↓
Use cases (unchanged contracts where possible)
   ↓
Domain services
   ├─ IndustrialKnowledgeRepository      (machines, maintenance, failures, procedures,
   │                                      parts, inspections, documents, chunks)
   ├─ QueryPlanner / QueryEngine         (semantic / structured / hybrid / similar)
   ├─ PolicyEngine                       (unchanged)
   ├─ LocalChatService (RAG)             (grounded answers + citations + explanation)
   ├─ SyncEngine / SyncOutboxWriter      (Qdrant-backed outbox)
   └─ ConflictResolver                   (Qdrant-backed conflict records)
   ↓
QdrantEdgeRecordStore  (Kotlin, the single Qdrant boundary)
   ↓
NativeBridge (JNI — narrow, payload + filter aware)
   ↓
Rust edgememo_qdrant (store.rs extended with payload/filter/scroll/index ops)
   ↓
qdrant-edge 0.8.0 → EdgeShard
   ↓
Persistent local storage (filesDir/local_qdrant)
```

The current `LocalVectorStore` interface remains valid for the vector-only subset; the new
`LocalRecordStore`/`QdrantEdgeRecordStore` (§14) supersedes it as the application-facing boundary
while delegating vector operations through the same shard.

## 2.2 Where things live in the target (TARGET DESIGN)

| Data | Location | How |
|---|---|---|
| Vectors | One named dense vector `"semantic"` per knowledge record point (Cosine, dim = embedding service dimension, currently 512) | `PointStruct` upsert with vector + payload together |
| Payloads (all record data) | Qdrant point payloads (mmap-backed, disk-persistent — see §0 fact 2) | Full typed payload per record |
| Payload indexes | Per-shard payload index schema (`PAYLOAD_INDEX_CONFIG_FILE`) + runtime `FieldIndexOperation::CreateIndex` | Created once at shard init; survives restart |
| Document chunks | Knowledge records with `record_type=document_chunk`; `document_id` payload ref; one vector per chunk | §11 |
| Machine records | `record_type=machine` payloads (+ optional vector from a short embedded descriptor) | §5 |
| Failure records | `record_type=failure` payloads; vector over title+content+root cause | §5 |
| Maintenance records | `record_type=maintenance_record` payloads; vector over summary+work done | §5 |
| Sync state | `sync_state` payload field on each knowledge record **plus** `record_type=outbox_op` records | §10 |
| Conflicts | `record_type=conflict` payload-only records (both sides' evidence) | §10.6 |
| Tombstones | `tombstone=true` payload field (soft delete) or point deletion for history-free types; tombstone records excluded via filter | §9, §10.5 |
| App/system state (cursor, later settings) | `record_type=sys_*` payload-only records in the same shard | §2.6 |

**Cross-record consistency caveat (explicit):** Qdrant provides **no multi-record transactions**.
Memory-record + outbox-record are two separate points with no atomic unit. The design compensates
with: single writer inside the process, deterministic idempotency keys, commit-marker ordering
(outbox written *after* the knowledge record), and recovery scans. §12 covers failure windows
honestly.

## 2.3 The native boundary grows deliberately (TARGET DESIGN)

The Rust/JNI surface must gain, in order of need:

```text
Phase 10 spike:  upsertWithPayload(id, vector, payloadJson)
                 scroll(filterJson?, limit, offsetId?, withPayload, orderBy?)
                 retrieve(ids)
Phase 12/13:     count(filterJson?, exact)        (exact=false acceptable for UI counts)
                 createPayloadIndex(field, schema) / listIndexes()
Phase 14:        query(vector?, filterJson?, scoreThreshold?, limit, withPayload)
                 recommend(positiveIds, negativeIds, filterJson?, limit)
Phase 15+:       facet(field, filterJson?)         (facets: type, severity, status, machine_type)
                 sparse/BM25 ops (replace Room LIKE keyword search; optional)
```

Every new native function returns only JSON or primitive results — never Rust structures leaking
into Kotlin. The existing `NativeBridge` external-function style (JNI `register_native_methods`)
is preserved. All Kotlin callers keep using a `Mutex`-serialized store, and the "flush after every
write batch" invariant is preserved for **payloads as well as vectors**.

## 2.4 Record sharding: one collection (see §3 for the full evaluation)

Single collection **`records`**, containing knowledge records and system records, distinguished by
an indexed `record_type` keyword field. Knowledge queries always filter `record_type` (or rely on
vectors, which system records do not carry).

## 2.5 Domain model sits on top of a generic record layer (TARGET DESIGN)

The industrial domain model (§5) is expressed as **payload schemas**, not as new storage kinds.
The generic record model (§4) is the only thing the store understands. Type safety is enforced in
Kotlin codecs (payload → typed DTO), not in the store.

## 2.6 Application state in Qdrant (TARGET DESIGN + OPEN QUESTION)

The problem statement says "eventually local application state" should live in Qdrant. Proposed:

- One point per state entry: `record_type=sys_cursor` (cloud pull cursor), later `sys_settings`,
  `sys_activity` (activity events), etc. Payload-only points (no vector).
- Point IDs: deterministic UUIDs derived from the state key (e.g. UUID v5 of `"cloud_pull_cursor"`)
  so upserts are naturally idempotent.

**OPEN QUESTION:** whether payload-only points (empty named-vector map) are accepted by qdrant-edge
0.8.0 upserts and whether they survive restart. The backend already uses payload-only points in
Qdrant *Server*; qdrant-edge accepts `Vectors::new_named([])` at the type level (VERIFIED —
`NamedVectors::default()` exists), but behavior must be proven by the Phase 10 spike. Fallback if
unsupported: system records get the `"semantic"` vector of their small payload text (harmless),
and retrieval filters exclude `record_type` starting with `sys_`.

**OPEN QUESTION:** `SharedPreferences("edgemind_ui")` (profile name). Recommendation: leave it as a
UI preference for now; revisit only if the product requires all state in Qdrant.

---

# 3. Qdrant collection strategy

## 3.1 The options

- **Option A — one primary collection** (`records`): all record types in one `EdgeShard`; payload
  field `record_type` discriminates; `record_type` is a payload index.
- **Option B — multiple collections**: e.g. `machines`, `failures`, `maintenance`, `procedures`,
  `parts`, `inspections`, `documents`, `chunks`, `outbox`, `conflicts`, `sys` — each its own
  `EdgeShard` (own directory, own `edge_config.json`, own WAL, own indexes).

In qdrant-edge 0.8.0, "collection" == one `EdgeShard` == one directory with one vector config.
Multiple collections therefore mean **multiple `EdgeShard` instances in one process**, each opened,
flushed, and locked independently.

## 3.2 Evaluation criteria (VERIFIED where noted, otherwise engineering judgment)

| Criterion | Option A (one collection) | Option B (multiple collections) |
|---|---|---|
| Qdrant Edge limitations | Uses exactly one shard — matches the crate's orientation (single-shard `EdgeShard` API). | Possible but each shard is a separate handle: separate config files, separate WALs, N open/close/flush paths in Kotlin. |
| Vector configuration | One named dense vector config serves all record types; embedding dimension defined once. | Same vector config must be repeated per collection; adding/changing a vector means touching every shard. |
| Payload filtering | `record_type` keyword index; all filters are ordinary payload filters. | Filters are per-collection; a query spanning types needs N calls + Kotlin-side merge. |
| Cross-entity retrieval | **Native.** "Why does P-101 keep leaking?" returns documents + failures + maintenance + procedures in one query/prefetch/fusion. | **Broken.** qdrant-edge has no cross-shard query or fusion. Hybrid answers over mixed types would require Kotlin-side fan-out + re-ranking, losing native RRF and score comparability. |
| Performance | One HNSW over all knowledge records; searches scoped by `record_type` filter when needed; per-type counts via `count(filter)` or facet. | Smaller indexes per type; but fan-out queries multiply JNI crossings and lose native fusion. |
| Schema evolution | Payloads are schemaless; a new entity adds new `record_type` values + fields with zero migration. Indexes added via `CreateIndex` at runtime. | Adding a new entity = creating a new shard at runtime (create-on-boot logic, new vector config); no cross-collection queries with existing entities. |
| Deletion | Delete point by id; tombstone pattern identical. | Same per collection. |
| Migration | One collection to migrate, verify, and roll back. | N collections to migrate and keep consistent. |
| Demo simplicity | One directory, one flush, one count, one backup unit. | Multiple directories; more failure surface for the "restart and search" invariant. |

## 3.3 Recommendation: **Option A — one primary collection**

**Reasoning:**

1. The single decisive requirement is **cross-entity retrieval**: the flagship queries (§8) need
   machines, failures, maintenance records, procedures, documents and chunks in *one* result set
   with comparable scores. Only a single collection gives native prefetch/fusion over them.
2. qdrant-edge 0.8.0 is single-shard-oriented; Option B buys isolation we do not need at this
   scale and pays with N shard lifecycles on a mobile device (where the persistence fix taught us
   every write path must flush correctly).
3. `record_type` as an indexed keyword field gives us Option B's "type isolation" on demand
   (filter), without its cost.
4. Schema evolution is trivially additive in one schemaless collection.

**Reservation (honest):** one collection mixes system records (outbox/conflicts/cursor) with
knowledge records. Mitigations: system records carry **no vector** (if payload-only points verify
out, or an excluded `record_type` prefix otherwise), all knowledge searches filter by
`record_type`, and facet/count UIs filter `record_type != sys_*` (or count only knowledge types
explicitly). This reservation is acceptable; if the spike shows payload-only points are problematic,
we keep the single collection but add a `searchable: true` keyword-indexed field to knowledge
records and require it in every knowledge query filter.

**Rejected alternative:** a second shard only for `sys_*` state was considered and rejected as
premature — it would re-introduce a second persistence path for a handful of tiny records.

---

# 4. Record model (generic Qdrant-backed record)

**TARGET DESIGN — do not implement in Phase 9.**

## 4.1 Kotlin abstraction

```kotlin
/** A UUID point id (qdrant-edge accepts only u64 or UUID point ids). */
@JvmInline
value class RecordId(val uuid: String) // validated as UUID at construction

enum class RecordType { MACHINE, MAINTENANCE_RECORD, FAILURE, PROCEDURE, PART,
                        INSPECTION, DOCUMENT, DOCUMENT_CHUNK, TECHNICIAN, LOCATION,
                        MEMORY,            // legacy generic memory (migration target)
                        OUTBOX_OP, CONFLICT, SYS_CURSOR, SYS_SETTING, SYS_ACTIVITY }

/** The single storage-level record. The store understands only this. */
data class Record(
    val id: RecordId,                          // point id (UUID)
    val recordType: RecordType,                // → payload "record_type"
    val entityId: String?,                     // external reference id (e.g. "M-042", "P-101")
    val vector: FloatArray?,                   // null ⇒ payload-only point
    val payload: Map<String, JsonValue>,       // typed payload incl. envelope fields
    val version: Int,                          // optimistic version counter
    val createdAt: Long,                       // epoch millis (also payload "created_at")
    val updatedAt: Long,
    val source: String,                        // provenance: USER_ENTRY / CLOUD / import uri
    val syncState: SyncState,                  // LOCAL / PENDING / SYNCED / FAILED
    val tombstone: Boolean,
    val deletedAt: Long? = null,
    val schemaVersion: Int,                    // payload schema version for migrations
)

enum class SyncState { LOCAL, PENDING, SYNCED, FAILED }

/** Write intent. */
data class UpsertRecord(
    val id: RecordId,
    val recordType: RecordType,
    val vector: FloatArray?,
    val payload: Map<String, JsonValue>,
)

/** Scroll page (stable order). */
data class RecordPage(
    val records: List<Record>,
    val nextOffset: String?,    // point-id offset for the next page
)

/** Filter fragment, compiled to a Qdrant filter JSON by the store. */
sealed interface FilterClause
data class Match(val field: String, val value: String) : FilterClause        // keyword/term match
data class In(val field: String, val values: List<String>) : FilterClause
data class Range(val field: String, val gt: Double? = null, val gte: Double? = null,
                 val lt: Double? = null, val lte: Double? = null) : FilterClause // numeric/date
data class DateRange(val field: String, val after: Long? = null, val before: Long? = null) : FilterClause
data class GeoBox(val field: String, val topLeft: GeoPoint, val bottomRight: GeoPoint) : FilterClause
data class Exists(val field: String) : FilterClause
data class Not(val clause: FilterClause) : FilterClause
data class And(val clauses: List<FilterClause>) : FilterClause
data class Or(val clauses: List<FilterClause>) : FilterClause
```

## 4.2 Envelope payload fields (every record)

```text
record_type       keyword   REQUIRED   discriminates record kinds
schema_version    integer   REQUIRED   payload schema version (starts at 1)
entity_id         keyword?  optional   external reference (M-042, P-101, SKF-6205)
version           integer   REQUIRED   monotonic local version
created_at        datetime  REQUIRED   epoch millis
updated_at        datetime  REQUIRED
source            keyword   REQUIRED   USER_ENTRY | IMPORT | CLOUD | CURATED
sync_decision     keyword   REQUIRED   LOCAL_ONLY | SYNC | SYNC_REDACTED
sync_state        keyword   REQUIRED   LOCAL | PENDING | SYNCED | FAILED
subject_key       keyword?  optional   evolving-memory subject identity
content_hash      keyword?  optional   sha256(title\ncontent) style hash
supersedes        keyword?  optional   record id this record supersedes
tombstone         bool      REQUIRED   soft-delete marker
deleted_at        datetime? optional
origin            keyword   optional   LOCAL | CLOUD | SYNCED
authority         keyword?  optional   cloud authority provenance
policy_reason     text?     optional   explainable policy decision
redacted_title    text?     optional   SYNC_REDACTED representation
redacted_content  text?     optional
tags              keyword[] optional
metadata          object    optional   free-form string map (document chunk keys, scope, …)
```

Indexing of envelope fields is covered in §6. Sync fields exist on **all** record types because the
outbox redesign treats every syncable entity uniformly (§10).

## 4.3 Identity and IDs

- Point ID = `RecordId` (UUID). Valid today (VERIFIED — existing memory IDs are UUIDs).
- `entity_id` is the *human* identity (`M-042`) — indexed, filterable, **not** the point ID. This is
  the Qdrant-native answer to "entity ID": searchable reference in payload, unique-ish by
  convention + a `findFirst` check in the repository (no DB unique constraint — honest limitation).

---

# 5. Industrial domain model (payload schemas)

**TARGET DESIGN.** For every entity: required/optional fields, type, indexed?, embedded?, and
reference IDs. "Embedded" means the field contributes text to the dense embedding of the record
(`embedding_text = title + " " + embedded fields …`).

Common conventions: `*_at` = epoch millis; `*_id` = string reference (UUID or external code);
enums stored as keyword strings.

## 5.1 Machine

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| machine_id (entity_id) | keyword | ✓ | ✓ | ✓ | "M-042" |
| name | text | ✓ | | ✓ | |
| machine_type | keyword | ✓ | ✓ | ✓ | pump / compressor / turbine / motor / conveyor / … |
| manufacturer | text | | | ✓ | |
| model | text | | | ✓ | |
| serial_number | keyword | | ✓ | ✓ | |
| plant | keyword | ✓ | ✓ | ✓ | |
| line | keyword | | ✓ | ✓ | |
| zone | keyword | | ✓ | ✓ | |
| location_id | keyword | | ✓ | | → Location |
| install_date | datetime | | | | |
| status | keyword | ✓ | ✓ | | operational / degraded / out_of_service / retired |
| criticality | keyword | | ✓ | | low/medium/high |
| specs | object | | | | free-form |
| tags | keyword[] | | ✓ | | |

## 5.2 Failure

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| failure_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| machine_id | keyword | ✓ | ✓ | ✓ | → Machine (by entity_id) |
| failure_type | keyword | ✓ | ✓ | ✓ | leak / overheating / vibration / electrical / wear / cavitation / … |
| severity | keyword | ✓ | ✓ | ✓ | low / medium / high / critical |
| status | keyword | ✓ | ✓ | | open / investigating / resolved |
| detected_at | datetime | ✓ | | | |
| resolved_at | datetime | | | | |
| symptom | text | ✓ | | ✓ | |
| root_cause | text | | | ✓ | |
| temperature_c | float | | ✓ | | range queries |
| pressure_bar | float | | ✓ | | |
| line | keyword | | ✓ | | |
| plant | keyword | | ✓ | | |
| technician_id | keyword | | ✓ | | → Technician (reporter) |
| resolved_by_technician_id | keyword | | ✓ | | |
| related_failure_ids | keyword[] | | | | similar-incident links |
| procedure_id | keyword | | ✓ | | remediation procedure reference |

## 5.3 MaintenanceRecord

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| record_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| machine_id | keyword | ✓ | ✓ | ✓ | |
| failure_id | keyword | | ✓ | | optional link |
| work_type | keyword | ✓ | ✓ | ✓ | preventive / corrective / predictive / inspection |
| performed_at | datetime | ✓ | | | |
| technician_id | keyword | ✓ | ✓ | | |
| duration_min | integer | | | | |
| parts_used | object[] | | | | [{part_id, qty}] |
| actions | text | ✓ | | ✓ | work performed |
| findings | text | | | ✓ | |
| next_due_at | datetime | | | | |
| cost | float | | | | |

## 5.4 Procedure

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| procedure_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| title | text | ✓ | | ✓ | |
| revision | integer | ✓ | | ✓ | revision 4 → v4 |
| machine_type | keyword | | ✓ | ✓ | applicable equipment |
| machine_id | keyword | | ✓ | | optional specific machine |
| steps | text | ✓ | | ✓ | embedded step text |
| torque_specs | object | | | | e.g. {"bolt": "45 Nm"} — conflict-relevant values |
| safety_notes | text | | | ✓ | |
| supersedes | keyword | | | | older procedure id |
| valid_from | datetime | | | | |

## 5.5 Part

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| part_id (entity_id) | keyword | ✓ | ✓ | ✓ | "SKF-6205" |
| name | text | ✓ | | ✓ | |
| part_number | keyword | ✓ | ✓ | ✓ | |
| supplier | text | | | | |
| spec | text | | | ✓ | |
| stock_quantity | integer | | | | |
| reorder_level | integer | | | | |
| used_in_machine_ids | keyword[] | | ✓ | | reverse ref |

## 5.6 Inspection

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| inspection_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| machine_id | keyword | ✓ | ✓ | ✓ | |
| inspection_type | keyword | ✓ | ✓ | ✓ | routine / post-repair / safety |
| performed_at | datetime | ✓ | | | |
| technician_id | keyword | ✓ | ✓ | | |
| findings | text | | | ✓ | |
| pass | bool | ✓ | ✓ | | |
| next_due_at | datetime | | | | |

## 5.7 Document

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| document_id (entity_id) | keyword | ✓ | ✓ | ✓ | header record; one per imported doc |
| title | text | ✓ | | ✓ | |
| format | keyword | ✓ | | ✓ | pdf / markdown / txt |
| source_uri | keyword | | | | SAF uri |
| source_name | keyword | | | | |
| chunk_count | integer | ✓ | | | commit marker semantics (§11) |
| ingested_at | datetime | ✓ | | | |
| tags | keyword[] | | ✓ | | |

## 5.8 DocumentChunk

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| chunk_id (entity_id) | keyword | ✓ | ✓ | ✓ | "<documentId>#<index>" |
| document_id | keyword | ✓ | ✓ | | → Document |
| document_title | text | | | ✓ | denormalized for citations |
| chunk_index | integer | ✓ | ✓ | | |
| chunk_count | integer | ✓ | | | |
| text | text | ✓ | | ✓ | chunk content |
| page | integer | | ✓ | | |
| section | text | | | ✓ | |
| source_name | keyword | | | | |

## 5.9 Technician

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| technician_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| name | text | ✓ | | | **not embedded** by default — names are personal data and are redaction-sensitive (SYNC_REDACTED strips role names today) |
| badge_id | keyword | | ✓ | | |
| specialization | keyword | | ✓ | | |

## 5.10 Location

| Field | Type | Req | Indexed | Embedded | Notes |
|---|---|---|---|---|---|
| location_id (entity_id) | keyword | ✓ | ✓ | ✓ | |
| name | text | ✓ | | ✓ | |
| plant | keyword | ✓ | ✓ | | |
| line | keyword | | ✓ | | |
| zone | keyword | | ✓ | | |
| geo | geo | | ✓ | | {lat, lon} — Qdrant GeoPoint payload type |
| address | text | | | | |

## 5.11 Embedding text composition (TARGET DESIGN)

Each record type composes its embedding text from marked fields only, e.g.:

```text
machine            = "name machine_type manufacturer model serial_number machine_id plant line zone"
failure            = "symptom root_cause failure_type severity machine_id machine_type plant line"
maintenance_record = "work_type actions findings machine_id failure_type"
procedure          = "title revision steps safety_notes torque_specs machine_type"
document_chunk     = "document_title section text"
```

Technician names and location addresses are deliberately **not** embedded (retrieval quality and
redaction-consistency). Note: the embedding text of a record is **not** a privacy mechanism —
vectors never sync, and privacy is enforced by `SyncPayloadFactory` at payload level (unchanged).

---

# 6. Payload index strategy

## 6.1 Principles (TARGET DESIGN)

- Index what is **filtered in hot queries** or **sorted**. Every index costs write amplification
  and disk; do not index fields that are only displayed.
- Index types available in qdrant-edge 0.8.0 (VERIFIED, `PayloadSchemaType`): `Keyword`, `Integer`,
  `Float`, `Geo`, `Text` (full-text), `Bool`, `Datetime`, `Uuid`.
- Indexes are created once at shard initialization via `FieldIndexOperation::CreateIndex` and
  persist in the shard's payload-index schema (VERIFIED — `PAYLOAD_INDEX_CONFIG_FILE` is loaded on
  shard open).
- `order_by` in scroll sorts by a payload field; a field used for sorting should be indexed.

## 6.2 Initial index set

| Field | Index type | Why | Expected query | Frequently filtered? |
|---|---|---|---|---|
| `record_type` | keyword | discriminates all queries; mandatory filter on knowledge searches | `record_type IN [failure, maintenance_record, …]` | **Yes — every query** |
| `entity_id` | keyword | exact identifier lookup (`M-042`, `P-101`, `SKF-6205`) | `entity_id = "M-042"` | Yes (identifier questions) |
| `machine_id` | keyword | machine-scoped failures/maintenance/inspections | `machine_id = "M-042"` | Yes |
| `machine_type` | keyword | type-scoped retrieval | `machine_type = "pump"` | Yes |
| `plant` | keyword | site scoping + privacy/tenant boundaries | `plant = "Site A"` | Yes |
| `line` | keyword | line scoping | `line = "Line A"` | Medium |
| `zone` | keyword | zone scoping | `zone = "Z3"` | Medium |
| `failure_type` | keyword | failure-class retrieval + facets | `failure_type = "overheating"` | Yes |
| `severity` | keyword | severity filtering + facets | `severity = "high"` | Yes |
| `status` | keyword | unresolved/open filtering + facets | `status = "open"` | Yes |
| `technician_id` | keyword | technician history | `technician_id = "T-7"` | Medium |
| `priority` | integer | priority ranges/sorting | `priority >= 3` | Medium |
| `temperature_c` | float | numeric range on sensor values | `60 <= temperature_c <= 120` | Medium (failure analysis) |
| `created_at` | datetime | recency filters + list ordering | `created_at >= <7d>` | Yes (lists) |
| `updated_at` | datetime | **list ordering (`order_by`)** + staleness filters | `order_by updated_at desc` | **Yes — every list** |
| `geo` (on locations) | geo | geo queries ("nearest pump to…") | geo bounding box | Low (demo) |
| `document_id` | keyword | chunk→document resolution, chunk listing | `document_id = "…"` | Yes (ingestion/resume) |
| `procedure_id` | keyword | procedure refs on failures | `procedure_id = "PR-14"` | Medium |
| `part_id` | keyword | part cross-references | `part_id = "SKF-6205"` | Medium |
| `tombstone` | bool | exclude tombstones at query time | `tombstone = false` | **Yes — every knowledge query** |
| `sync_state` | keyword | sync dashboard counts | `sync_state = "PENDING"` | Yes (sync worker) |
| `operation_id` (outbox ops) | keyword | idempotency lookup | `operation_id = "UPSERT-…"` | Yes (sync worker) |

~22 fields. This is slightly larger than the "start minimal" instinct, but every listed field maps
to a concrete query in §7/§8/§9/§10. If write amplification becomes measurable (§16), trim `zone`,
`priority`, `temperature_c`, `geo` first — they are the least frequently filtered.

**Explicitly NOT indexed** (no known hot filter): `title`, `content`, `actions`, `findings`,
`symptom`, `root_cause`, `steps`, `chunk text` as *keyword* indexes. Keyword/full-text retrieval
over these comes from either a `Text` index or the BM25 sparse vector (§7), **not** from keyword
indexes (Qdrant keyword indexes are exact-match/value indexes, not substring search).

## 6.3 Keyword/full-text search replacement for the Room `LIKE` path (TARGET DESIGN)

Today `KeywordRetriever` runs `LOWER(title) LIKE '%term%'` per term against Room (VERIFIED). The
Qdrant-native replacements, in preference order:

1. **`Text` payload index + `MatchText` filters** — Qdrant's full-text index does tokenization,
   supports `should`-term matching. Exact behavior in qdrant-edge 0.8.0 (tokenizer availability,
   prefix/partial matching) must be verified in the Phase 13 spike. Identifier matching (`SKF-6205`)
   needs the tokenizer to preserve hyphens — Qdrant word tokenizers generally do.
2. **BM25 sparse vector** — qdrant-edge ships `EdgeBm25` (`embed_query`/`embed_document`,
   VERIFIED) and sparse named vectors (`EdgeSparseVectorParams`). Store a `"bm25"` sparse vector on
   knowledge points and run native prefetch fusion (RRF) between dense `"semantic"` and sparse
   `"bm25"` — this replaces the Kotlin-side `ReciprocalRankFusion` with native fusion for the same
   semantics and is the correct long-term path.
3. **Fallback (short term):** keep `KeywordRetriever` semantics by scrolling records and matching
   in Kotlin. **Rejected** for production (O(N) scans per term), acceptable only as a migration
   bridge — and only if it is explicitly marked as such.

---

# 7. Query model

## 7.1 QueryPlan abstraction (TARGET DESIGN — do not implement yet)

```kotlin
sealed interface QueryPlan {

    /** Pure dense semantic search. */
    data class Semantic(
        val text: String,                       // question/query text
        val recordTypes: Set<RecordType> = KNOWLEDGE_TYPES,
        val filters: List<FilterClause> = emptyList(),   // hard scoping (plant, tombstone=false…)
        val limit: Int = 8,
        val minScore: Double? = null,           // → score_threshold
        val withPayload: Boolean = true,
    ) : QueryPlan

    /** Pure structured/filter query — no vector involved. */
    data class Structured(
        val filters: List<FilterClause>,
        val recordTypes: Set<RecordType>? = null,
        val orderBy: String? = "updated_at",    // scroll order_by
        val descending: Boolean = true,
        val limit: Int = 50,
        val offset: String? = null,             // point-id pagination
    ) : QueryPlan

    /** Hybrid: dense + sparse(BM25) native fusion with the same filters. */
    data class Hybrid(
        val text: String,
        val recordTypes: Set<RecordType> = KNOWLEDGE_TYPES,
        val filters: List<FilterClause> = emptyList(),
        val limit: Int = 8,
        val minScore: Double? = null,
    ) : QueryPlan

    /** Similar-record: seed vector from one or more existing records. */
    data class Similar(
        val seedRecordIds: List<RecordId>,      // e.g. one overheating failure record
        val recordTypes: Set<RecordType>,
        val filters: List<FilterClause> = emptyList(),
        val excludeSeedIds: Boolean = true,
        val limit: Int = 8,
        val minScore: Double? = null,
    ) : QueryPlan

    /** Native recommend (positive/negative examples). */
    data class Recommend(
        val positiveIds: List<RecordId>,
        val negativeIds: List<RecordId> = emptyList(),
        val recordTypes: Set<RecordType>,
        val filters: List<FilterClause> = emptyList(),
        val limit: Int = 8,
    ) : QueryPlan

    /** Facet aggregation for a UI filter rail. */
    data class Facet(
        val field: String,                      // e.g. failure_type, severity, machine_type
        val filters: List<FilterClause> = emptyList(),
    ) : QueryPlan
}

val KNOWLEDGE_TYPES = setOf(MACHINE, MAINTENANCE_RECORD, FAILURE, PROCEDURE, PART,
                            INSPECTION, DOCUMENT, DOCUMENT_CHUNK, MEMORY)
```

Every executed plan returns both results and the **plan itself** (with resolved filters), which is
the basis of explainability (§8.5).

## 7.2 Concrete examples

**Example 1 — pure semantic with scoping (chat):**

```kotlin
QueryPlan.Semantic(
    text = "Why does M-042 keep leaking?",
    recordTypes = setOf(FAILURE, MAINTENANCE_RECORD, PROCEDURE, DOCUMENT_CHUNK),
    filters = listOf(
        Or(listOf(
            Match("machine_id", "M-042"),
            Match("entity_id", "M-042"),
        )),                             // machine scoping (or loosened by planner if empty)
        Match("tombstone", "false"),
    ),
    limit = 12,
)
```

**Example 2 — pure structured (browse):**

```kotlin
QueryPlan.Structured(
    filters = listOf(
        Match("record_type", "failure"),
        Match("severity", "high"),
        Match("status", "open"),
        Match("line", "Line A"),
        Match("machine_type", "pump"),
        Match("tombstone", "false"),
    ),
    orderBy = "updated_at",
    descending = true,
    limit = 50,
)
```

**Example 3 — hybrid with exact identifier:**

```kotlin
QueryPlan.Hybrid(
    text = "SKF-6205 bearing failures on Line A",
    filters = listOf(Match("part_id", "SKF-6205") /* or */ Match("line", "Line A")),
    recordTypes = KNOWLEDGE_TYPES,
)
```

**Example 4 — range query:**

```kotlin
QueryPlan.Structured(
    filters = listOf(
        Match("record_type", "failure"),
        Range("temperature_c", gte = 80.0),
        DateRange("detected_at", after = sevenDaysAgo),
        Match("tombstone", "false"),
    ),
)
```

**Example 5 — geo query:**

```kotlin
QueryPlan.Structured(
    filters = listOf(
        Match("record_type", "location"),
        GeoBox("geo", topLeft = GeoPoint(52.1, 4.9), bottomRight = GeoPoint(52.0, 5.1)),
    ),
)
```

**Example 6 — similar-record query (similar incidents):**

```kotlin
QueryPlan.Similar(
    seedRecordIds = listOf(RecordId("…")),     // the overheating incident record
    recordTypes = setOf(FAILURE),
    filters = listOf(Match("plant", "Site A"), Match("tombstone", "false")),
    excludeSeedIds = true,
    limit = 8,
)
```

**Example 7 — recommendation query:**

```kotlin
QueryPlan.Recommend(
    positiveIds = idsOfResolvedFailuresLikeThis,   // records the technician confirmed as "like this"
    negativeIds = idsOfDifferentFailureClasses,
    recordTypes = setOf(FAILURE, MAINTENANCE_RECORD, PROCEDURE),
    limit = 8,
)
```

---

# 8. Local chat architecture

## 8.1 Pipeline (TARGET DESIGN)

```text
User question
    ↓
QueryUnderstanding (QueryPlanner)
    ├─ intent classification (deterministic rules, no LLM required):
    │    structured → Structured plan
    │    similarity → Similar plan
    │    otherwise  → Hybrid plan (dense + sparse native fusion)
    ├─ entity extraction from tokens: machine ids (M-042, P-101), part ids (SKF-…),
    │    severity words (high/critical), status words (open/unresolved),
    │    line/zone words ("Line A"), failure-type vocabulary
    └─ scope resolution: if machine id found → add machine_id filter;
         if no scope and query looks scoped → keep filters minimal
    ↓
QueryPlan (Section 7)
    ↓
QueryEngine → QdrantEdgeRecordStore
    ├─ query(): hybrid native prefetch fusion (dense "semantic" + sparse "bm25")
    │           with filter, score_threshold, with_payload
    └─ structured plans → scroll(filter, order_by, page)
    ↓
Retrieved records (with payloads, scores, rank)
    ↓
ContextBuilder
    ├─ dedupe (document_id#chunk_index, content_hash)
    ├─ drop tombstone / superseded (already filtered, re-checked)
    ├─ cap context budget (chars)
    └─ keep citations metadata (page/section/source/record id)
    ↓
LocalGenerator (LLMService — extractive today, local neural later)
    ↓
GroundedAnswer + citations + explanation
```

The existing pieces that **survive unchanged**: `QueryNormalizer`, `ReciprocalRankFusion` (as
fallback / until native RRF is verified), sufficiency gating (`DEFAULT_MIN_DENSE_SCORE` semantics),
`ExtractiveLLMService`, `SourceReference` citation model, escalation policy (insufficiency + online
quorum → question-only cloud ask).

## 8.2 How structured questions differ from semantic questions (TARGET DESIGN)

| Question | Planner result |
|---|---|
| "What caused M-042's failures?" | Hybrid: `text` = question, `filters` = `machine_id = M-042` + `record_type IN [failure, maintenance_record]`. The filter narrows the candidate space **before** vector scoring — this is the classic Qdrant filtered-search pattern and is what makes "M-042" deterministic even when the dense embedding of "M-042" is weak. |
| "Show unresolved high-severity hydraulic pump failures on Line A." | **Structured** (no vector): `failure_type = hydraulic*`(via match/full-text on failure_type), `severity = high`, `status = open`, `line = Line A`, `machine_type = pump`, `order_by updated_at desc`. Retrieval is exact — the answer IS the list, with counts from `count(filter)`. |
| "Find failures similar to this overheating incident on Line A." | **Similar**: seed = the overheating failure record's vector; `recordTypes = [failure]`; `filter = line = Line A`; `excludeSeedIds = true`. Implemented as native recommend/nearest on the seed vector (or `QueryEnum::RecommendBestScore` when positive+negative sets exist). |

Rule of thumb encoded in the planner: **if the query's answer is enumerable from structured
attributes (status/severity/line/type), use Structured; if it needs understanding of free text,
use Hybrid with any entity filters attached; if it references an existing record as an example,
use Similar/Recommend.**

## 8.3 Why filtering-before-scoring matters here (VERIFIED + TARGET DESIGN)

Dense-only retrieval historically struggles with identifiers like `M-042` (the current system
compensates with the Room keyword path weighted 2.0 for identifiers — VERIFIED in
`KeywordRetriever`). The Qdrant-native design keeps that guarantee by attaching `entity_id` /
`machine_id` filters to the search. This *strengthens* the current behavior rather than weakening it.

## 8.4 Grounded answers and citations

Unchanged contract: citations must map to real stored records (now payloads + optional chunk
fields). `SourceReference` gains `recordId`/`recordType`. No fabricated sources; the sufficiency
gate and "insufficient evidence" messaging carry over.

## 8.5 Explainability of retrieval (TARGET DESIGN)

Because every plan is a first-class value, the chat answer can include a "why these results"
panel:

```text
plan: HYBRID  query="…"  filters=[machine_id=M-042, tombstone=false]
dense vector: "semantic" 512-dim  native fusion: RRF(k=60)
result 1..k: recordId, recordType, score, matched filter fields, citation metadata
```

Facets (`failure_type`, `severity`, `machine_type` counts over the same filter) give the UI a real,
query-scoped filter rail — every number computed by `facet()` over the shard, never fabricated.

---

# 9. Memory model without Room

The current generic `Memory` feature (notes, observations, procedures, repairs, events,
cloud knowledge) becomes `record_type=MEMORY` (and in the industrial model, typed records per §5).
All operations are direct Qdrant operations on collection `records`.

| Operation | Current (VERIFIED) | Target (TARGET DESIGN) |
|---|---|---|
| create | policy → embed → Qdrant vector → Room insert (rollback vector on Room failure) | policy → embed → **one upsert with vector + full payload** → flush. Failure = no row at all (single-point write; no cross-store rollback needed — this *removes* the current two-phase risk) |
| read (get) | `MemoryDao.getById` | `retrieve([id])` → payload → codec → typed record |
| update | Room `@Update` after re-embed | upsert same point id with new vector + payload, `version+1`, `updated_at`, fresh `content_hash` (single point replace) |
| delete | Room delete + outbox cancel + vector delete | tombstone upsert (`tombstone=true, deleted_at`) and vector removal **or** point delete; tombstone retained where history matters (evolving memory), hard-delete for user notes. Outbox withdraws pending ops (§10.4) |
| search | dense Qdrant + Room `LIKE` → RRF → resolve ids → filter tombstones/superseded | native Hybrid query (`semantic` dense + `bm25` sparse, RRF fusion), filters `tombstone=false` + `record_type IN …`; superseded exclusion via `supersedes` presence check (§9.1) |
| list | `MemoryDao.listAll()` ordered by `updatedAt DESC` | `scroll(filter tombstone=false & record_type IN …, order_by updated_at, desc, page)` |
| filter | not supported (only keyword `LIKE`) | structured `Structured` plans (§7) with indexed fields |
| count | `MemoryDao.count()` | `count(filter tombstone=false …)`; `exact=false` acceptable for UI badges, `exact=true` for sync/policy invariants |

## 9.1 Superseded and tombstoned exclusion (TARGET DESIGN)

- Tombstoned records: excluded by a mandatory `tombstone = false` filter (indexed bool).
- Superseded records: current Room logic queries `SELECT DISTINCT supersedes` and excludes those
  ids (VERIFIED). Qdrant-native equivalent: index `supersedes` (keyword). Candidates exclude any
  record whose id appears as someone's `supersedes` — implemented as: retrieve superseded set
  (scroll of records where `supersedes` exists, small), then post-filter — or store a
  `superseded_by` field **on the superseded record itself** at supersede time (recommended: makes
  the filter a simple `superseded_by IS NULL`-style check via `Exists`-negation). The
  write-time denormalization is preferred because it keeps every knowledge query a single filter.

## 9.2 Uniqueness and duplicate detection (honest limitations)

Qdrant has **no unique constraints** (beyond the point id). Duplicate content detection stays a
**read-then-write** check in the repository (`content_hash` filter → if hit, dedupe), which is
racy in theory but safe in practice because the app is a single process with a Mutex-serialized
store. This is the same pattern the cloud classifier uses (VERIFIED). Stated explicitly: Qdrant
does not give us ACID uniqueness; we give ourselves process-local serialization.

---

# 10. Sync / outbox redesign

## 10.1 Current design recap (VERIFIED)

Room outbox with `withTransaction` atomicity between memory row and outbox row, guarded
`claim()` as an atomic CAS (`UPDATE … WHERE state IN (PENDING,FAILED)`), `recoverStaleInFlight()`,
`MAX_ATTEMPTS=5` → DEAD, deterministic `operationId = "UPSERT-<memoryId>"`, server-side idempotency
already implemented in the backend (`PUT /sync/operations/:id` — replay is a no-op).

## 10.2 Qdrant-native outbox (TARGET DESIGN)

Each outbox operation is a point:

```text
record_type      = OUTBOX_OP                     (no vector)
entity_id        = "UPSERT-<recordId>"           ← idempotency key (indexed keyword)
payload:
  operation_id   "UPSERT-<recordId>"             (indexed keyword; == entity_id)
  entity_record_id    the knowledge record id (or entity_id for typed records)
  operation_type "UPSERT"                        (later: DELETE/TOMBSTONE)
  payload_title / payload_content                ← ONLY SyncPayloadFactory-sanctioned bytes
  sync_decision  LOCAL_ONLY never appears here (never enqueued)
  created_at, attempts, state, last_error(kind), version
```

Point id: UUID (random), because the idempotency key must be a **payload field** (point ids are
UUIDs/u64s only) — the point id itself does not carry `UPSERT-…`.

State machine (unchanged names): `PENDING → IN_FLIGHT → ACKED | FAILED | DEAD`.

## 10.3 Operations without transactions (the honest redesign)

| Current Room guarantee | Qdrant reality | Replacement |
|---|---|---|
| memory row + outbox row in one transaction | two independent point upserts | Write order: knowledge record first, then outbox record, then flush. If crash between them: knowledge exists without outbox → a **reconciliation scan** (`sync_state=PENDING` records without an outbox op) re-enqueues at worker start. If crash after outbox but before flush: both roll back together (single flush) — acceptable. |
| atomic `claim()` (CAS) | no CAS primitive | Single-worker discipline: WorkManager runs one sync job at a time (`REPLACE` policy, unique name — VERIFIED current scheduler). Within the process, `QdrantEdgeRecordStore` is Mutex-serialized, and claim is a read-check-write inside that mutex. Cross-process races (two app processes — not possible for a normal Android app; WorkManager already guarantees single execution per unique work) are out of scope and documented. |
| `recoverStaleInFlight()` | scroll `state=IN_FLIGHT` → reset | Same query via scroll+filter at worker start. |
| `selectRetryable()` | scroll `state IN (PENDING,FAILED)` order_by `created_at` | Same. |
| ACK + memory sync_state together | two points | ACK first (state=ACKED), then knowledge record `sync_state=SYNCED`; crash between → worker re-ACKs idempotently (server no-op) and re-applies the knowledge state update (idempotent upsert). |
| attempts/retry budget | payload integer, updated by point upsert | Same semantics; `MAX_ATTEMPTS=5` → DEAD. |

**Idempotency** is preserved end-to-end: deterministic `operationId` (payload), `UPSERT` upsert
semantics, server-side dedup already implemented (VERIFIED — `backend/src/routes/sync.ts` retrieves
by memoryId and compares `operationId` before upserting), and `UpdateMode::InsertOnly` available
natively for "only create, never clobber" where needed.

**Avoiding duplicate sync operations:** `operationId` is the single stable key per
record; re-enqueues **refresh the payload of the same outbox point** (latest-wins, exactly like
today's `refreshPayload`, never clobbering `IN_FLIGHT` — enforced by the single-writer check).

## 10.4 LOCAL_ONLY invariant (unchanged, VERIFIED mechanism carried over)

`SyncPayloadFactory` remains the *only* authority for what enters an outbox payload. A
`LOCAL_ONLY` record produces **no outbox point**, and a superseding LOCAL_ONLY decision deletes any
existing outbox point for that record (withdraw). This privacy invariant must be re-pinned by tests
in the Qdrant-native world (§15).

## 10.5 Tombstones and sync (TARGET DESIGN)

A local delete of a syncable record enqueues a `TOMBSTONE` outbox operation (operationType gains
`TOMBSTONE`; server applies `tombstone=true` — the backend payload schema already carries
`tombstone` — VERIFIED). Local behavior: record payload flipped to tombstone, vector deleted (or
kept for history per record type). Tombstoned records never serve retrieval (filter).

## 10.6 Conflict records (TARGET DESIGN)

`record_type=CONFLICT`, payload-only point, deterministic point id (UUID v5 over
`subject|localId|localHash|incomingId|incomingHash` — same derivation as today's conflictId),
payload holds both sides' evidence (title/content/version/hash/origin/authority), state
(`UNRESOLVED/RESOLVED_LOCAL/RESOLVED_CLOUD/DISMISSED`), `detected_at`, `reason`, `resolution`.
Idempotent re-pull: existence check by derived id before insert (same as today's
`insertOrIgnore` semantics, now a retrieve/scroll check).

## 10.7 Where Qdrant genuinely differs from a transactional database (explicit)

- **No multi-record atomicity.** We compensate with write-ordering + reconciliation + idempotency.
- **No CAS / guarded update.** We compensate with single-writer discipline + server idempotency.
- **No foreign keys / cascades.** Referential integrity is by convention and filter queries.
- **No unique secondary indexes.** Duplicate prevention is read-check-write under the store mutex.
- **No SQL joins.** Cross-record assembly happens in Kotlin from denormalized payload fields
  (document_title on chunks) or by id lookups (retrieve batch).
- **Deletes are per-point (or filtered batch).** Bulk "delete all chunks of document X" =
  scroll ids by filter, then delete points — two operations, not one transaction.

None of these differences blocks the design; each has a named compensation above. The one that
requires the most discipline is the knowledge-record/outbox-record pairing, which is why the
reconciliation scan is mandatory in the worker.

---

# 11. Document ingestion redesign

## 11.1 Current (VERIFIED)

```text
SAF pick → ContentResolverDocumentReader → extractor (PDF/MD/TXT) → normalize
→ chunk → embed all → Qdrant upsert batch (flush) → Room insertAll
(atomic via createAll; rollback vectors if Room fails)
```

## 11.2 Target (TARGET DESIGN)

```text
SAF pick → reader → extractor → normalize → chunk
    ↓
for each chunk: compose payload (record_type=DOCUMENT_CHUNK, chunk_id, document_id,
    document_title, chunk_index, chunk_count, text, page?, section?, source_name)
    embed chunk → upsert point (vector + payload)
    ↓
after all chunk points: single flush
    ↓
write DOCUMENT header point (record_type=DOCUMENT, chunk_count=N, format, source_uri…)
    → the header is the COMMIT MARKER; ingestion is not "done" until it exists
    ↓
flush
```

**Resume semantics:** if a crash interrupts ingestion, chunks exist but the header does not. At
startup (or next import), the repository scans `record_type=DOCUMENT_CHUNK` grouped by
`document_id` and deletes orphaned chunk groups whose header is absent. The header keeps
`chunk_count`, so a fully-written group can be verified by `count(filter document_id=X)`.

**Why this ordering:** Qdrant cannot atomically write N points; the commit-marker makes the
partial state *detectable and cleanable* instead of pretending it can't happen. This is the same
reasoning as the current "vector batch, then Room rows" order, now expressed with one store.

## 11.3 What improves vs today (TARGET DESIGN)

- No cross-store rollback logic (today: delete N vectors if Room insert fails) — a single upsert
  either lands or throws.
- Citations resolve from chunk payloads directly (page/section/source) without a Room join.
- `document_id` filter replaces the `metadata[documentId]` convention.
- Chunk listing for a document = scroll `record_type=DOCUMENT_CHUNK & document_id=X order_by
  chunk_index` — better than today's implicit ordering by Room row order.

---

# 12. Failure / crash safety

## 12.1 The platform reality (VERIFIED)

Android can kill the process at any time; qdrant-edge (as used) has no WAL replay on load, so
every durable write must be followed by `flush()` — this was the Phase-1-era fix
(`QdrantEdgeVectorStore` flushes after every upsert batch/delete, VERIFIED). The target keeps this
rule for **all** writes (payload and index operations included).

## 12.2 Scenario table (TARGET DESIGN)

| Scenario | Behavior | Residual risk / honest note |
|---|---|---|
| Process death / force-stop during idle | Nothing pending; last flush is durable (vectors proven today; payloads to be proven by spike) | Payload persistence unverified until Phase 10 spike |
| Crash during ingestion | Chunk points exist, header absent → orphan scan deletes them at next startup | Chunk points linger until next startup scan (bounded by scan frequency) |
| Crash during update (knowledge record) | Old or new payload — the last flushed point wins; update is a single point upsert + flush | A crash between the point write and flush loses the update entirely (acceptable: caller sees failure) |
| Crash during delete | Tombstone point or hard delete; if pre-flush, record survives (no deletion) | Never a half-deleted record: delete is one point operation |
| Duplicate operation (double-tap create, re-delivered sync) | Deterministic point ids / operation ids; server dedup; `InsertOnly` mode available | Same as today's guarantees, re-pinned by tests |
| Partially completed vector writes | Single upsert = one WAL entry + flush; no partial vector state is observable | Multi-chunk documents see partial *sets* (handled by commit marker) |
| Stale versions (two writers) | Single writer per process (Mutex); cross-process writers cannot happen (single app process + WorkManager single execution) | Cloud-vs-local staleness is handled by version/contentHash conflict logic, unchanged |

**Explicit non-claim:** there are **no atomic multi-record transactions** in this design. Every
multi-record operation uses an ordering + reconciliation pattern, and every such pattern is listed
above with its residual risk.

## 12.3 Startup recovery sequence (TARGET DESIGN)

```text
open shard → ensure indexes exist (CreateIndex idempotent)
→ orphan document-chunk scan
→ outbox stale IN_FLIGHT recovery
→ outbox↔knowledge reconciliation scan (PENDING without outbox → re-enqueue)
→ WorkManager requestSync()
```

---

# 13. Migration strategy (Room + Qdrant → Qdrant-only)

**The existing system must keep working until each stage is verified.** Rollback baseline = the
current Room + vector-only-Qdrant implementation at `69377a9` (and the preserved
`hackathon-prototype` tag).

## 13.1 Guiding rules (TARGET DESIGN)

1. No Room table is removed until its Qdrant replacement passes the full test matrix of §15
   **and** a device restart test.
2. Storage backend selection is a **feature flag** (runtime, persisted, or BuildConfig-gated —
   recommendation: runtime flag stored as a `sys_setting` record, defaulting to `room` until
   flipped; a BuildConfig default per build type is the fallback if the shard is not ready).
3. **Dual-read is rejected** (two sources of truth for reads = inconsistent scoring/ordering;
   unnecessary complexity). **Dual-write is used only for the memories table** during its cutover
   window, because that is the one table where a rollback must not lose user data written after
   the cutover (see 13.4).

## 13.2 Staged cutover (TARGET DESIGN)

| Stage | Change | Gate to proceed | Rollback |
|---|---|---|---|
| **S0** | Extend Rust/JNI: payload upsert, scroll, retrieve, count-with-filter, CreateIndex. No Kotlin behavior change (still empty payload). | Payload persistence spike passes: upsert payload → close → reopen → payload intact (host JVM + Android restart). Full suite green. | Revert Rust/JNI only — Room path untouched. |
| **S1** | Introduce `QdrantEdgeRecordStore` + `Record`/codecs behind new interfaces; **no consumers switched**. | New store unit tests green against real .so (host + Robolectric). | Delete new files. |
| **S2** | Cut over **memories table** to Qdrant records with **dual-write**: writes go to both Room and Qdrant; reads stay on Room. Ship a one-time migration job: Room rows → Qdrant records (payload + vector), verify by count + id-set comparison + spot payload equality. | Migration verification passes on device data; dual-write tests green. | Flag back to Room reads/writes; Qdrant copies are disposable. |
| **S3** | Switch reads to Qdrant (flag), Room becomes write-through only. Retrieve/search/list/keyword now Qdrant-native (BM25 sparse or Text index). | Retrieval parity tests: same queries → same result sets (modulo score tolerance); 244-suite green (adapted), device restart test. | Flag back to Room reads. |
| **S4** | Drop Room writes for memories; memories table frozen. | Green for N days/commits. | Restore from S3 (write-through still active) or export path. |
| **S5** | Migrate **outbox**, **conflicts**, **cloud_pull_cursor** to Qdrant records (each with its own flag + tests; outbox gets the reconciliation scan). | Outbox/conflict/cursor test matrix + crash tests green. | Per-table flag revert. |
| **S6** | Remove Room database + entities + `edge-memory.db` (Phase 18 gate). | All gates above + performance measurements recorded (§16). | `hackathon-prototype` tag / commit `69377a9` is the documented rollback baseline; data export tool preserved. |

## 13.3 Migration tool (TARGET DESIGN)

One-shot `RoomToQdrantMigrator` (run from app startup when flag says "migrating"):
per table: stream Room rows → build payloads → batch upserts (with vectors re-embedded where the
source row lacks a vector — not needed for memories, which have shard vectors by id; **do not
re-embed** — read the existing vector from the shard by point id where available) → flush →
verify counts + deterministic sample equality → write `sys_setting migration_state=done`.
Failure = stop, keep Room as source of truth, report.

**Important:** memory point ids are already the memoryIds (VERIFIED — repository uses
`UUID.randomUUID().toString()`), and those points already exist in the shard. Migration is
therefore **payload attach**, not data movement: for each Room row, upsert the existing point with
its full payload. This makes S2 cheap and low-risk.

## 13.4 Why dual-write for memories is justified (and only for memories)

During the read-cutover window, new memories must be visible to whichever backend the flag selects.
If the user creates memories after a cutover and we then roll back, Room-only rollback would lose
them. Dual-write (Room + Qdrant) for memories until S4 closes that hole. Outbox/conflicts/cursor do
not need dual-write: they are internal operational state, regenerable or loss-tolerant (cursor loss
= full re-pull; conflict loss = re-detect on next pull), and their Room versions can be
re-synthesized from the knowledge records if needed.

## 13.5 Feature flag design (TARGET DESIGN)

```text
storage_backend ∈ { room, dual_write, qdrant_reads, qdrant_only }   (sys_setting record or BuildConfig)
```

Every storage decision goes through `RecordStoreProvider` (or the `IndustrialKnowledgeRepository`
boundary) — one seam, trivially testable with both backends in the suite.

---

# 14. API / interface design (to introduce in later phases — not now)

```kotlin
/** Generic record CRUD over Qdrant Edge. The ONLY thing the store understands. */
interface LocalRecordStore {
    suspend fun ensureReady(dimension: Int)
    suspend fun upsert(record: UpsertRecord)                  // vector + payload, flush
    suspend fun upsertBatch(records: List<UpsertRecord>)      // single flush after batch
    suspend fun retrieve(ids: List<RecordId>): List<Record>
    suspend fun scroll(
        filter: List<FilterClause> = emptyList(),
        orderBy: String? = null,
        descending: Boolean = true,
        limit: Int = 50,
        offset: RecordId? = null,
        withVectors: Boolean = false,
    ): RecordPage
    suspend fun delete(id: RecordId, flush: Boolean = true)
    suspend fun count(filter: List<FilterClause> = emptyList(), exact: Boolean = false): Long
    suspend fun query(plan: QueryPlan): List<ScoredRecord>    // dense/hybrid/similar/recommend
    suspend fun facet(field: String, filter: List<FilterClause> = emptyList()): List<FacetValue>
    suspend fun ensurePayloadIndexes(specs: List<PayloadIndexSpec>)
    suspend fun optimize()
    suspend fun close()
}

/** Payload ←→ typed DTO codec. */
interface RecordCodec<T> {
    val recordType: RecordType
    fun encode(record: T): Map<String, JsonValue>
    fun decode(record: Record): T
}

/** Industrial knowledge repository: typed entities over LocalRecordStore. */
interface IndustrialKnowledgeRepository {
    suspend fun upsertMachine(m: Machine): Machine
    suspend fun getMachine(entityId: String): Machine?
    suspend fun listFailures(machineEntityId: String, status: FailureStatus?): List<Failure>
    suspend fun openFailures(plant: String, minSeverity: Severity): List<Failure>
    suspend fun similarFailures(seed: Failure, limit: Int): List<Failure>
    suspend fun maintenanceHistory(machineEntityId: String): List<MaintenanceRecord>
    suspend fun upsertProcedure(p: Procedure): Procedure
    suspend fun findProcedure(procedureId: String, revision: Int?): Procedure?
    suspend fun chunksOf(documentId: String): List<DocumentChunk>
    // … typed per-entity methods; all implemented over LocalRecordStore + codecs
}

/** Parses a user question into a QueryPlan. Deterministic rules first, LLM optional later. */
interface QueryPlanner {
    fun plan(question: String, scope: QueryScope = QueryScope()): PlannedQuery
    // PlannedQuery(plan: QueryPlan, extractedEntities: EntityMentions, confidence: Confidence)
}

/** Executes a QueryPlan against LocalRecordStore and returns scored records. */
interface QueryEngine {
    suspend fun execute(plan: QueryPlan): QueryResult
}

/** Local chat: plan → retrieve → context → grounded answer + citations + explanation. */
interface LocalChatService {
    suspend fun answer(question: String, onStage: (ChatStage) -> Unit): ChatResponse
    // ChatResponse carries: answer, citations, evidenceRecords, queryPlan, facets, status
}

/** Qdrant-backed implementation of LocalRecordStore (JNI). */
class QdrantEdgeRecordStore(directory: File, dispatcher: CoroutineDispatcher) : LocalRecordStore

/** Compatibility alias so Phase-2 style vector-only code can keep using the old shape. */
interface LocalVectorStore { /* unchanged */ }
```

The existing domain interfaces (`MemoryRepository`, `RetrievalService`, `RagService`,
`PolicyEngine`, `SyncEngine`, `SyncOutboxWriter`, `ConflictResolver`) stay as the compatibility
surface during migration; `LocalRecordStore` is introduced beside them and becomes the single
storage seam over time.

---

# 15. Test strategy (required before deleting Room)

Each item below is a gate. "Restart" means: write → close store → reopen in a **new process**
(host JVM child process and, where possible, Android device restart).

## 15.1 Native store (Rust/JNI) — Phase 10/12 gates

1. Payload persistence: upsert payload → flush → restart → payload identical (the #1 gate; mirrors
   the vector persistence fix pattern).
2. Payload-only points (empty vectors) upsert/retrieve/restart — or explicit decision to use
   vector-bearing system records.
3. Scroll: filter, pagination (point-id offset), `order_by` on an indexed field, with_payload.
4. Retrieve-by-ids, missing ids → empty.
5. Payload index: create index → restart → index survives; filtered query uses it (verify by
   behavior/INFO, not by timing).
6. Count with filter, exact vs approximate.
7. Filter correctness: match, in, range (numeric + datetime), bool, must/must_not, geo.
8. Dense query with filter + score_threshold + with_payload.
9. Recommend query (positive/negative).
10. BM25 sparse vector round-trip + fused query (when enabled).
11. Batch upsert = one flush; crash-simulation (no graceful close) → last batch visible.

## 15.2 Repository / domain gates (before any Room table removal)

12. CRUD parity: create/read/update/delete of memories via Qdrant records matches current behavior.
13. Duplicate IDs: upsert with same point id replaces (no ghost row); InsertOnly rejects.
14. Tombstone: excluded from search, list, count; history retained.
15. Supersede: superseded record excluded from retrieval; superseding record served.
16. Indexed filtering: `machine_id`, `failure_type`, `severity`, `status`, `record_type`.
17. Vector search ranking parity vs current dense path (tolerance-banded).
18. Hybrid search: identifier query (`SKF-6205`) returns the right record; RRF fusion behaves.
19. Outbox: PENDING→IN_FLIGHT→ACKED/FAILED/DEAD transitions; stale IN_FLIGHT recovery; claim
    single-winner; reconciliation scan re-enqueues orphaned PENDING records.
20. Sync idempotency: repeated worker runs process each op once; server dedup verified (backend
    tests already cover `PUT /sync/operations/:id` — extended to TOMBSTONE ops).
21. Conflict handling: detection, idempotent conflict point, keepLocal/keepCloud/dismiss.
22. Document ingestion: chunk points + header commit marker; orphan cleanup; restart mid-ingestion.
23. Chat retrieval: the three §8.2 query forms return correct grounded answers with citations.
24. Offline operation: all of the above with networking disabled (INTERNET permission presence
    must remain irrelevant to local behavior — existing test pattern).
25. Privacy: LOCAL_ONLY → zero outbox points; SYNC_REDACTED → only redacted bytes in outbox
    payload; CLOUD-origin never enqueued.
26. Migration: Room→Qdrant payload attach produces identical record sets (count + id + field
    spot-check); migration failure leaves Room intact.
27. Persistence across Android process restart (the existing `QdrantEdgePersistenceTest` pattern,
    extended to payloads and system records).

## 15.3 Verification commands (existing, VERIFIED to work)

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest   # currently 244 tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
cd backend && npm test                                                     # currently 29 tests
```

---

# 16. Performance

## 16.1 Likely bottlenecks (TARGET DESIGN)

| Bottleneck | Why | Mitigation | Measurement |
|---|---|---|---|
| JNI crossings | Today: one native call per point in a batch; payloads add string copies | Batch upserts (one `PointsList` per JNI call, one flush), keep JSON serialization off the UI thread | upsert batch latency by batch size (10/50/200) |
| Payload size | Full record payloads (content text, metadata) inflate WAL writes and JSON encode/decode | Keep payloads lean; large text lives in payload only (no page-cache duplication beyond mmap); avoid re-serializing on read-heavy paths | payload bytes per record; encode/decode µs |
| Embedding generation | Deterministic hashing is fast (CPU-bound); a future neural model dominates ingestion | Keep `EmbeddingService` swappable; batch-embed chunks; `Dispatchers.Default` | embed p50/p95 per chunk |
| Qdrant search | HNSW + filter interaction; exact counts are scans | `exact=false` for UI counts; score_threshold; candidate limits | query p50/p95 vs N records; with/without filter |
| Large scrolls | List screens scanning all records; `order_by` without index | `order_by updated_at` with datetime index; page size caps; point-id offset pagination (never large numeric offsets — documented crate warning) | scroll page latency vs page size |
| Payload indexing | Every CreateIndex adds write amplification on upserts | Index only §6.2 fields; measure before adding more | upsert latency delta per index count |
| Android memory | Payload populate-on-load (`on_disk_payload(false)` = InRamMmap loads payloads into RAM) | Consider `on_disk_payload(true)` (Mmap, read-from-memory-if-possible) once payloads are large; measure RSS | RSS at open; open latency vs record count |
| Local LLM (future) | Inference on device | Not in scope until after core is stable (spec) | n/a |

## 16.2 Measurements to collect at each phase gate

- Shard open latency (records, index load), upsert batch latency, query p50/p95 (semantic /
  hybrid / structured), scroll page latency, count latency, index creation time, shard directory
  size, process RSS, restart durability time budget.
- Regression tripwire: any operation > 100 ms on-device (demo scale) is investigated before
  adding features. Store measurements as tests (host JVM) + a manual device checklist — no fake
  dashboard numbers (spec rule).

---

# 17. Security / privacy

## 17.1 What changes with Qdrant-as-primary-store (TARGET DESIGN + explicit warnings)

- **Sensitive content now lives in Qdrant payloads** on disk (filesDir/local_qdrant) instead of
  (also) in Room. Storage at rest remains **unencrypted** — the same posture as today's Room file,
  and spec §53 defers at-rest encryption. Android sandboxing + `allowBackup="false"` (VERIFIED,
  Phase 8 hardening) remain the boundary.
- **Logs:** the no-`Log.*`/`println` discipline extends to record payloads (never log payload
  content). `last_error` stores only failure-kind ids (existing invariant, kept).
- **Cloud sync:** `SyncPayloadFactory` remains the only bytes allowed into outbox payloads.
  Payload-level `sync_decision` must not become a second authority — the factory wins, and the
  outbox point is created only by the writer that consults the factory. `LOCAL_ONLY` records:
  zero outbox points, enforced client-side before any I/O and rejected server-side (both VERIFIED
  behaviors today; re-pinned).
- **API keys / cloud credentials:** unchanged — backend-only (`.env`, gitignored). Nothing about
  Qdrant-local storage changes this.
- **Backup:** `allowBackup=false` covers the new shard directory automatically (same app dir).
  `dataExtractionRules` remain an optional hardening (noted in WORKING.md).
- **Exported data / file sharing:** the shard directory is app-private; no new export surface is
  introduced. Any future export feature must pass through policy + redaction.
- **Local chat escalation:** question-text-only to cloud (existing contract), unchanged.

## 17.2 Redaction and payloads (TARGET DESIGN)

For `SYNC_REDACTED` records, the **local** payload keeps the original content; the redacted
representation exists only in the outbox payload (as today: `redacted_title`/`redacted_content`).
Vectors are computed from the local original (retrieval quality) and never leave the device.

---

# 18. Risks

| # | Risk | Probability | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **Qdrant as application-state store** — no transactions/CAS/joins; subtle state machines (outbox claim/ACK) could diverge | Medium | High | Single-writer + idempotent operation ids + server dedup + reconciliation scans (§10.3, §12); every state transition pinned by tests; keep Room until §15 gates pass |
| R2 | Payload persistence does not survive Android restarts (unproven boundary feature) | Medium | Critical | Phase 10 spike first; fall back to keeping Room for metadata until proven (plan re-scoped, not abandoned) |
| R3 | Payload-only points unsupported in qdrant-edge 0.8.0 | Medium | Medium | Fallback: vector-bearing system records + `record_type`/`searchable` filters (§2.6) |
| R4 | Keyword/identifier retrieval regresses (`SKF-6205`) if BM25/Text index behaves differently than Room `LIKE` | Medium | High | Identifier filters on `entity_id`/`part_id` (indexed) make exact identifiers deterministic regardless of full-text behavior; keep `KeywordRetriever` behind a switch until parity tests pass |
| R5 | Performance regression on list/search with payload-heavy shard | Medium | Medium | §16 measurements at every gate; index discipline; `on_disk_payload` tunable |
| R6 | Migration bug corrupts or loses user memories | Low | Critical | Payload-attach (not data movement) for memories; verification gates; dual-write window; `hackathon-prototype` rollback baseline |
| R7 | qdrant-edge version upgrade churn (API drift in 0.x) | Medium | Medium | Pin 0.8.0 (already pinned); keep Rust surface narrow; re-run native test suite on any upgrade |
| R8 | Native shard growth on mobile (WAL + HNSW + payloads) | Medium | Medium | 4 MiB WAL segments already configured; `optimize()`; monitor dir size (§16) |
| R9 | Over-indexing makes ingestion slow on device | Medium | Medium | §6.2 only; measured trimming order defined |
| R10 | Team over-builds Qdrant-native before core value (industrial demo) | Medium | Medium | Phase gates (§19); STOP-after-Phase-9 discipline; smallest verifiable slice per AGENTS.md |

**The dominant risk is R1/R2** — and the design deliberately front-loads the Phase 10 payload
persistence spike so that R2 is resolved before any Room table is touched.

---

# 19. Phase breakdown after Phase 9

Based on the repository inspection, the suggested sequence is adjusted as follows: **payload
persistence is proven first** (it gates everything), the industrial domain model lands after the
generic record layer, and Room removal is the very last step.

| Phase | Name | Scope | Exit gate |
|---|---|---|---|
| **10** | Qdrant-native record layer | Rust/JNI: payload upsert, scroll, retrieve, count-with-filter, CreateIndex; Kotlin `QdrantEdgeRecordStore` + `Record` model; payload persistence spike (restart); payload-only point decision | Payload survives restart; new store tests green; full suite green; **Room untouched** |
| **11** | Memory migration | `MEMORY` records via dual-write; Room→Qdrant payload-attach migrator; reads switched behind flag; keyword path parity (identifier filters; BM25/Text index decision from spike) | Retrieval parity tests; device restart; flag rollback exercised |
| **12** | Industrial domain model | Payload schemas + codecs for Machine/Failure/MaintenanceRecord/Procedure/Part/Inspection/Document/DocumentChunk/Technician/Location; `IndustrialKnowledgeRepository`; capture/import forms | Typed CRUD + filter tests green |
| **13** | Indexes + filtering | CreateIndex at init for §6.2 fields; structured plans (`Structured`, range, date, geo); facet queries; UI filter rail | Index persistence + filtered query tests; facet tests |
| **14** | Query planner | `QueryPlanner` deterministic rules; `Hybrid` native fusion (dense + BM25 sparse) or Text-index hybrid; `Similar`/`Recommend` plans | The three §8.2 chat forms verified end-to-end |
| **15** | Local chat | `LocalChatService` over plans; context builder; explainability panel (plan + filters + scores + facets) | Grounded answers + citations + explanation tests; offline-only pass |
| **16** | Similarity / recommendation | Similar-incident retrieval UX; recommend queries with positive/negative feedback; parts/procedures recommendations | Similarity/recommend tests + demo scenario |
| **17** | Explainability | Retrieval explanation polish; provenance per citation; policy-decision explanation on industrial records | Explainability assertions in tests; UI review |
| **18** | Remove Room completely | Migrate outbox/conflicts/cursor; delete Room deps, entities, `edge-memory.db`; remove flags; reconciliation scans in worker | Full §15 matrix green; performance numbers recorded; rollback baseline documented |
| **19** | UI / demo | Industrial screens (machines, failures, maintenance, procedures, parts), capture flows, sync/conflict surfaces, demo data | Device demo script passes; no fake metrics |
| **20** | Hardening | Crash tests, size/perf tuning, backup rules, security/privacy re-audit, dependency review | §16 measurements within budget; security review clean |

Notes:

- Phases 10–11 do not change user-visible behavior (they are storage-internal).
- Phase 12–13 can partially overlap Phase 11's tail if the payload-attach migration is trivial
  (expected — point ids already exist).
- Phase 18 must not begin until the §15 gate list is green.
- Each phase ends with: build green, tests green, WORKING.md updated, and a STOP for review.

---

# 20. Open questions

1. **Payload-only points** in qdrant-edge 0.8.0 (upsert with no vector; restart persistence) —
   spike in Phase 10. (Q1)
2. **BM25 sparse vs Text index** for keyword/identifier retrieval — decide from Phase 10/11 spike
   results; BM25+fused-query preferred architecturally. (Q2)
3. **`on_disk_payload(true|false)`** — InRamMmap (populate on load) vs Mmap (read from memory if
   possible); decide after RSS/open-latency measurement. (Q3)
4. **Where do activity events live** — spec's ACTIVITY screen is not implemented today; target
   would be `record_type=sys_activity` payload-only points, retained with a cap. Deferred. (Q4)
5. **`SharedPreferences` UI prefs** — in scope for "application state in Qdrant" or not. (Q5)
6. **Sync of typed industrial records** — the backend payload schema is memory-shaped today;
   TOMBSTONE op + industrial fields need a backend schema update (backend work, out of Android
   scope). (Q6)
7. **Geo indexing in qdrant-edge** — geo filter support exists in the crate's types; runtime
   behavior on Android untested (low priority; demo optional). (Q7)

---

# Appendix A — Native API facts (VERIFIED against vendored qdrant-edge 0.8.0 source)

| Fact | Evidence |
|---|---|
| `EdgeShard::new(path, config)` / `load(path, Option<config>)`; fresh-vs-existing decided by `edge_config.json` presence | `edge/edge_shard/mod.rs`, `store.rs` (current code) |
| `update(CollectionUpdateOperations)` supports: `PointOperations` (upsert/delete; `UpdateMode::Upsert/InsertOnly/UpdateOnly`), `PayloadOps` (Set/Overwrite/Delete payload), `FieldIndexOperation` (`CreateIndex { field_name, field_schema }` / `DeleteIndex`), `VectorNameOperation` | `shard/operations/mod.rs`, `shard/operations/point_ops.rs`, `payload_ops.rs` |
| `query(QueryRequest)`: `prefetches`, `query: ScoringQuery`, `filter: Filter`, `score_threshold`, `limit`, `offset`, `with_payload`, `with_vector` | `edge/requests/query.rs`, `edge/builders/query_request.rs` |
| `QueryEnum`: `Nearest`, `RecommendBestScore`, `RecommendSumScores`, `Discover`, `Context`, `FeedbackNaive` | `shard/query/query_enum.rs` |
| `scroll(ScrollRequest)`: `filter`, `offset: PointIdType`, `limit`, `with_payload`, `with_vector`, `order_by: OrderByInterface` (sort by payload field) | `edge/requests/scroll.rs` |
| `retrieve(RetrieveRequest)` by ids; `count(CountRequest { filter, exact })`; `facet(FacetRequest)`; `info()` | `edge/edge_shard/shard_read.rs`, `edge/builders/count_request.rs`, `edge/requests/facet.rs` |
| `flush()` persists WAL/segments (used by the Android restart fix) | `edge/edge_shard/mod.rs`, current `jni.rs` |
| Point ids: `ExtendedPointId = NumId(u64) | Uuid` only; `FromStr` accepts u64 or UUID strings | `segment/types.rs` |
| Payload index types: `Keyword, Integer, Float, Geo, Text, Bool, Datetime, Uuid` | `segment/types.rs` (`PayloadSchemaType`) |
| Payload index schema persisted at `PAYLOAD_INDEX_CONFIG_FILE`, loaded on shard open | `edge/edge_shard/mod.rs` |
| Payload storage config path: `PayloadStorageType::Mmap` ("store on disk and in memory, read from memory if possible") / `InRamMmap` ("store on disk and in memory, populate on load"); both constructed via `PayloadStorageImpl::open_or_create` (mmap, disk-backed); non-persistent `InMemoryPayloadStorage` is "for tests only" and is **not** selected by `EdgeConfig` | `segment/types.rs`, `segment_constructor_base/payload_storage.rs`, `segment/payload_storage/*` |
| Sparse vectors + `EdgeBm25` (`embed_query`/`embed_document`) and `EdgeSparseVectorParams` in config | `edge/bm25_embed.rs`, `edge/builders/edge_config.rs` |
| Native RRF fusion exists in the query formula path | `shard/query/conversions.rs` (`FusionInternal::Rrf`) |
| Current shard config: one named dense vector `"semantic"` (Cosine, 512-dim from embedding service), `on_disk_payload(false)`, WAL 4 MiB segments, `retain_closed = 1` | `rust/edgememo_qdrant/src/store.rs` |
| Current JNI surface: create/open/upsert(empty payload)/delete/search/count/optimize/flush/close | `rust/edgememo_qdrant/src/jni.rs` |

# Appendix B — Repository inspection checklist (VERIFIED)

- Repository root: Kotlin Android app (`app/`), Rust crate (`rust/edgememo_qdrant/`), Node backend
  (`backend/`), docs (`docs/`), `WORKING.md`, `AGENTS.md`.
- Git: branch `main` @ `69377a9`; tag `hackathon-prototype` → `f457023`; working tree clean.
- Gradle: AGP with KSP2, Room 2.7.1, WorkManager 2.10.1, pdfbox-android, commonmark; Rust cross-build
  for `arm64-v8a` + `x86_64` via NDK 27.2.12479018.
- Room v4: `memories`, `sync_outbox`, `conflicts`, `cloud_pull_cursor` + migrations 1→2→3→4.
- Qdrant: `LocalVectorStore` → `QdrantEdgeVectorStore` → `NativeBridge` → `store.rs`/`jni.rs`.
- Embeddings: `FeatureHashingEmbeddingService` (512-dim deterministic hashing; lexical baseline).
- RAG: `DefaultRagService` + `EscalatingRagService`; LLM: `ExtractiveLLMService` (verbatim
  sentences + `[n]` citations).
- Policy: `DefaultPolicyEngine` + `DefaultRedactionService`; `SyncPayloadFactory` = privacy boundary.
- Sync: `RoomSyncOutboxWriter` (transactional), `DefaultSyncEngine` (claim/ACK/DEAD),
  `SyncWorker` + `SyncScheduler`; remotes: `HttpSyncRemoteDataSource` /
  `UnimplementedSyncRemoteDataSource`.
- Cloud: `DefaultCloudKnowledgeIngestor` (cursor), `DefaultKnowledgeClassifier`,
  `DefaultCloudKnowledgeWriter`, `DefaultCloudAnswerCache`, `DefaultConflictResolver`.
- DI: manual `AppContainer`; UI: Ask/Memory/Settings screens; `EdgeMindApplication`.
- Tests: 244 host/Robolectric tests (0 failures); 29 backend tests; build+lint green.
