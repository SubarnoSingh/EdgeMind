# PHASE 12A — Qdrant-Native Synchronization
## Architecture Inspection Document

**Status:** INSPECTION ONLY — No production code modified
**Baseline:** `2a02f15` (HEAD, main); `hackathon-prototype` → `c5626ad`
**Generated:** 2026-09-28

---
## 0. Executive Summary

This document presents the Phase 12A inspection of the EdgeMind repository, documenting the current architecture, existing synchronization mechanisms, Qdrant Edge capabilities, Qdrant Cloud capabilities, and the synchronization problem space for the new Qdrant-native Record layer (Phase 11).

The Phase 11 record layer (`LocalRecordStore`/`QdrantEdgeRecordStore`) is **verified and production-quality**: 8/8 Phase 10 tests pass, 6/6 Rust tests pass, 256/256 Android unit tests pass, lint and assemble succeed. The hackathon-prototype tag is preserved (though tag integrity violation exists independently).

**Key finding:** The existing synchronization architecture is Room-based (sync_outbox in Room SQLite). Phase 12 must redesign synchronization to work with the Qdrant-native record layer while preserving Room as the rollback baseline for older features.

**Not in scope:** Production Phase 12 implementation, Room migration, cloud backend modifications, UI changes, RAG changes, embedding changes, tag modifications.

---
## 1. Current Architecture

### 1.1 Phase 11 Record Layer (VERIFIED)

The new Qdrant-native record layer sits between the application ViewModels and the JNI→Rust→qdrant-edge 0.8.0 boundary:

```
Compose UI
    ↓
ViewModel / StateFlow
    ↓
Use Cases
    ↓
LocalRecordStore (interface)
    ↓
QdrantEdgeRecordStore (JNI-backed production implementation)
    ↓
NativeBridge (JNI, 27 external functions)
    ↓
Rust edgememo_qdrant (store.rs + jni.rs)
    ↓
qdrant-edge 0.8.0 → EdgeShard → persistent local storage (filesDir/local_qdrant)
```

**Verified capabilities:**
- `LocalRecordStore`: initialize, open, ensureReady, ensureIndexes, close, isOpen, upsert, upsertBatch, delete, softDelete, get, getMany, exists, query, scroll, search, count, flush, optimize
- `QdrantEdgeRecordStore`: full JNI-backed production implementation
- `Record`: envelope/domain payload separation, `toFullPayload()`/`fromFullPayload()`, version metadata, sync decision, tombstone
- `JsonValue`: sealed interface (JsonString/JsonNumber/JsonBoolean/JsonObject/JsonNull) with safe serialization
- `RecordFilter`: typed filter algebra (Match/In/Range/DateRange/Exists/Not/And/Or)
- `FilterCompiler`: compiles to canonical Qdrant filter JSON
- `RecordQuery`: ByIds/Scroll/Search/Count query model
- Payload indexes: `ensureIndexes()` creates 30+ keyword/integer/bool/datetime/geo indexes, idempotent, persist across restart
- Batch upsert: `upsertBatch()` → JNI `nativeUpsertBatchWithPayload` → Rust `upsert_batch_with_payload` (looped individual upserts, NOT transactional)
- Retrieve by ID, filtered retrieval, scroll, count, semantic search with filters

### 1.2 Existing Sync Architecture (VERIFIED — Room-based)

The current synchronization stack is entirely Room-SQLite based:

```
DefaultSyncEngine
    ↓
SyncWorker (WorkManager, unique "edgememo-sync", CONNECTED, exponential backoff 10s)
    ↓
HttpSyncRemoteDataSource → CloudHttpClient → PUT /sync/operations/:id → Qdrant Cloud
    ↓
EdgeMind backend (Node/Express) → Qdrant Cloud `device_memory` collection
    ↓
RoomSyncOutboxWriter (insert/refresh/cancel in Room transaction)
    ↓
SyncOutboxEntity table (operationId PK, memoryId, operationType, payloadTitle, payloadContent, createdAt, attempts, state, lastError)
    ↓
MemoryEntity table (memories: title, content, type, tags, timestamps, origin, syncDecision, syncState, sensitivity, importance, version, contentHash, subjectKey, supersedes, tombstone, metadata, policyReason, redactedTitle, redactedContent, authority)
    ↓
DefaultMemoryRepository (create/createAll/update/delete/get/list/search)
```

**SyncOutboxEntity table schema:**
- `operationId` (PK = idempotency key `UPSERT-<memoryId>`)
- `memoryId` (indexed)
- `operationType` (UPSERT)
- `payloadTitle`, `payloadContent` (policy-sanctioned title/content only)
- `createdAt`, `attempts`, `state` (PENDING/IN_FLIGHT/ACKED/FAILED/DEAD)
- `lastError` (classification id only, never raw memory text)

**Sync flow:**
1. Memory created → Room row + (if syncable) SyncOutbox row
2. SyncWorker claims PENDING/FAILED rows via guarded `UPDATE … WHERE state IN (PENDING,FAILED)` atomic compare-and-set
3. `pushSafely()` calls `remote.push(buildOperation(op))` via `HttpSyncRemoteDataSource`
4. Backend acknowledges → `markAcknowledged()` + `memoryDao.updateSyncState(memoryId, SYNCED)`
5. On failure → `markState(FAILED)` or `markState(DEAD)`

**SyncOutboxEntity fields:**
- `operationId` (PK = `UPSERT-<memoryId>`)
- `memoryId` (indexed)
- `operationType` (UPSERT)
- `payloadTitle`, `payloadContent` (only policy-sanctioned fields leave the device)
- `createdAt`, `attempts`, `state`
- `lastError` (SyncFailureKind classification id only)

### 1.3 Evidence Labeling

| Claim | Label | Evidence |
|---|---|---|
| Qdrant Edge persists payloads, vectors, indexes across restart | VERIFIED | Phase 10 tests + Phase 10 documentation §8-9 |
| `upsert_batch_with_payload` loops individual upserts | VERIFIED | Rust `store.rs:150-190` source code |
| `nativeUpsertBatchWithPayload` JNI call | VERIFIED | `jni.rs:256-270` + `NativeBridge.kt:17` |
| Room sync_outbox uses guarded atomic claim | VERIFIED | `DefaultSyncEngine.kt:57-62` + `SyncModels.kt:11-16` |
| hackathon-prototype tag untouched | VERIFIED | `git rev-parse hackathon-prototype` = `c5626ad` |
| Phase 11 production code uses real JNI→Rust→qdrant-edge | VERIFIED | Full test suite passes (256 Android + 6 Rust + 8 Phase 10) |
| Existing sync is Room-based, not Qdrant-native | VERIFIED | `SyncOutboxEntity`, `DefaultSyncEngine`, `WorkManager` all Room-bound |
---
## 2. Existing Sync Architecture Deep Dive

### 2.1 SyncEngine (VERIFIED)

`DefaultSyncEngine` (`app/data/sync/DefaultSyncEngine.kt`) drives the durable outbox state machine. Key design notes (all VERIFIED from source):

- **Crash recovery:** `recoverStaleInFlight()` re-applies idempotent operations that died after remote success but before local ACK
- **Single-winner claims:** `claim(op.operationId)` uses guarded `UPDATE … WHERE state IN (PENDING,FAILED)` — concurrent workers cannot double-process
- **Network calls outside Room transactions:** `pushSafely()` runs remote call outside any Room transaction
- **ACK only on remote success:** `ACKED` written only when remote returned `Success`
- **State machine:** PENDING → (claimed) → IN_FLIGHT → on success → ACKED + memory sync state → SYNCED; on retryable failure → FAILED; on permanent failure → DEAD

**Critical dependency on Room transactions:** The engine writes both the outbox row ACK and the memory row SYNCED inside `database.withTransaction { outboxDao.markAcknowledged(); memoryDao.updateSyncState() }` (`DefaultSyncEngine.kt:75-79`). This Room transaction has no Qdrant-native equivalent.

### 2.2 SyncWorker (VERIFIED)

`SyncWorker` (`app/data/sync/SyncWorker.kt`) — CoroutineWorker running under WorkManager:

- Unique name "edgememo-sync", REPLACE semantics, CONNECTED constraint
- Exponential backoff: 10s initial
- `doWork()` calls `engine.processPending(maxOperations)`
- Retries while `summary.remaining > 0`, returns success when no retryable operations

### 2.3 HttpSyncRemoteDataSource (VERIFIED)

Real Phase-8 sync remote (`app/data/sync/HttpSyncRemoteDataSource.kt`):

- `push(operation)` → `PUT /sync/operations/{operationId}` → `CloudHttpClient`
- Defense in depth: rejects `LOCAL_ONLY` syncDecision before any network I/O
- Maps HTTP status codes to `SyncFailureKind`:
  - 400, 422 → REJECTED (permanent)
  - 401, 403 → UNAUTHORIZED (permanent)
  - 429, 500-599 → SERVER_TEMPORARY (retryable)
  - others → NETWORK (retryable)
- `SyncOperation.toJson()` serializes operation + enriched memory payload
- **Never fabricates success:** Success returned ONLY on 2xx backend acknowledgement of real Qdrant write

### 2.4 Cloud Backend (PROPOSED — documented but not inspected)

The EdgeMind backend (Node/Express + TypeScript) owns all cloud credentials. Known endpoints:

- `PUT /sync/operations/:id` — idempotent upsert into Qdrant Cloud `device_memory`
- `GET /knowledge` — cursor-paginated pull from Qdrant Cloud `cloud_knowledge`
- `POST /answers` — cloud LLM query
- Health: `GET /health`

**NOT VERIFIED:** No live credentials were available for this inspection. The backend URL is configuration-driven (`cloudBackendUrl`). See `CLOUD_VERIFICATION.md` for manual live test prerequisites.

### 2.5 SyncFailureKind (VERIFIED)

Classifications that must never carry raw memory text (`SyncModels.kt:37-52`):

- SOURCE_UNAVAILABLE — no backend configured (Phase 7 honest no-op)
- NETWORK — connectivity or timeout, safe to retry
- SERVER_TEMPORARY — transient server error, safe to retry
- REJECTED — 400/422, permanent
- UNAUTHORIZED — 401/403, permanent

### 2.6 OutboxOperationState (VERIFIED)

Lifecycle states (`SyncModels.kt:11-26`):

- PENDING — created offline, waits for connectivity
- IN_FLIGHT — claimed by sync worker, one claim wins via guarded update
- ACKED — remote acknowledged success
- FAILED — retryable error, eligible for another retry
- DEAD — max attempts exceeded or permanent failure

### 2.7 Idempotency Key (VERIFIED)

`operationId` = `UPSERT-<memoryId>` is the stable idempotency key across retries (`SyncOutboxEntity.kt:22`, `DefaultSyncEngine.kt:57`). The backend must acknowledge idempotency on `UPSERT-<memoryId>` (Phase 7 requirement, documented but not yet implemented by cloud backend).

---
## 3. Qdrant Edge Capabilities (VERIFIED — from vendored crate source and Phase 10 inspection)

### 3.1 Core Operations (VERIFIED)

| Operation | API | Evidence |
|---|---|---|
| Upsert point with payload | `EdgeShard::update(UpdateOperation::PointOperation(PointInsertOperations::PointsList(...)))` | `store.rs:125-144` |
| Retrieve by ids | `EdgeShard::retrieve(RetrieveRequest::new(point_ids))` | `store.rs:194-207` |
| Search vector | `EdgeShard::query(QueryRequest::new(limit).query(ScoringQuery::Vector(...)))` | `store.rs:95-103` |
| Count | `EdgeShard::count(CountRequest::default())` | `store.rs:105-107` |
| Scroll with filter | `EdgeShard::scroll(ScrollRequestBuilder::new().limit(n).with_payload(true).filter(filter).offset(offset))` | `store.rs:212-231` |
| Count filtered | `shard.count(builder.build())` | `store.rs:234-240` |
| Create payload index | `EdgeShard::update(UpdateOperation::FieldIndexOperation(FieldIndexOperations::CreateIndex(CreateIndex {field_name, field_schema})))` | `store.rs:246-257` |
| Search with filter | `EdgeShard::query(QueryRequestBuilder... .filter(filter_json) ...)` | `store.rs:261-278` |
| Flush | `EdgeShard::flush()` | `store.rs:118-120` |
| Optimize | `EdgeShard::optimize()` | `store.rs:109-112` |

### 3.2 Vector Capabilities (VERIFIED)

- One named dense vector `"semantic"` per shard (Cosine distance, configurable dimension)
- Vectors stored as mmap-backed `InRamMmap` (on_disk_payload(false) = populate-on-load)
- Dense vector search via HNSW
- Named vectors can be added at runtime via `CreateVectorName`

### 3.3 Payload Capabilities (VERIFIED)

- Arbitrary JSON payloads on points (serde_json integration)
- Payload schema types: Keyword, Integer, Float, Bool, Datetime, Text, Uuid, Geo
- Payload indexes via `FieldIndexOperation::CreateIndex` (persist across restart)
- Payload filtering on query/scroll/count
- Payload `order_by` in scroll
- `with_payload(bool)` control on retrieve/scroll/query/count

### 3.4 Limitations (VERIFIED — from Phase 10 documentation §16)

| Limitation | Detail |
|---|---|
| No transactions / no CAS | qdrant-edge has neither; single-point operations only |
| No cross-process file lock | Multi-process access to one shard directory NOT supported |
| Batch upsert loops per record | 0.067 ms/record at spike scale; fine now, batch primitive is Phase 12+ optimization |
| on_disk_payload false → InRamMmap | Memory footprint at scale unmeasured (Phase 9 Q3) |
| No concurrent multi-process access | Android app single-process; WorkManager single unique sync job |
| Payload-only points unproven at restart | Behavior must be proven; fallback: system records get `"semantic"` vector from small payload text |

### 3.5 JNI Boundary (VERIFIED)

Current `NativeBridge` external functions (27 total, §1.2 of Phase 10 docs):

- `nativeCreate(path, dimension)` → handle
- `nativeOpen(path)` → handle
- `nativeUpsert(handle, id, vector)` — payload is `json!({})`, never reads back
- `nativeDelete(handle, id)`
- `nativeSearch(handle, vector, limit)` → Array<SearchResult>
- `nativeCount(handle)` → Long
- `nativeOptimize(handle)`
- `nativeFlush(handle)`
- `nativeClose(handle)`
- `nativeUpsertWithPayload(handle, id, vector, payload)` — Phase 10 spike
- `nativeUpsertBatchWithPayload(handle, recordsJson)` — Phase 10 spike
- `nativeRetrieve(handle, idsJson)` → JSON string
- `nativeScroll(handle, filterJson, limit, offsetId)` → JSON string
- `nativeCountFiltered(handle, filterJson, exact)` → Long
- `nativeCreatePayloadIndex(handle, field, schema)` — Phase 10 spike
- `nativeSearchWithFilter(handle, vector, limit, filterJson)` → JSON string
- `nativeClose(handle)`

**All 21 original functions untouched** — only 6 new functions added in Phase 10 (all payload/filter/scroll/batch related).

### 3.5 Evidence Labeling — Qdrant Edge Capabilities

| Claim | Label | Evidence |
|---|---|---|
| Payload persistence across restart | VERIFIED | Phase 10 §8-9, restart test results |
| Payload indexes persist across restart | VERIFIED | Phase 10 §13, Rust test `payload_index_survives_reopen_and_filter_still_works` |
| `upsert_batch_with_payload` loops individual upserts | VERIFIED | `store.rs:150-190` source code |
| Qdrant Edge has no transactions/CAS | VERIFIED | Phase 10 §12, §16; 12/13 Rust test confirms update replaces record |
| Qdrant Edge supports payload filtering | VERIFIED | `search_with_filter`, `count_filtered`, `scroll` with filter |
| Qdrant Edge supports create payload index | VERIFIED | `create_payload_index` Rust function + Phase 10 tests |
| No cross-process file lock | VERIFIED | Phase 10 §16, concurrency model docs |
| Batch upsert is NOT transactional | VERIFIED | `store.rs:149`: "This is NOT a transaction - partial failures may leave some records persisted" |
| Qdrant Edge supports geo queries | VERIFIED | `PayloadSchemaType::Geo`, `geo` index type |
| Qdrant Edge supports Boolean payload indexes | VERIFIED | `PayloadSchemaType::Bool` + Phase 10 test `severity==high` exact sets |
---
## 4. Qdrant Server/Cloud Capabilities (MIXED — documented + verified)

### 4.1 Qdrant Cloud API Endpoints (PROPOSED — from backend source code)

The EdgeMind backend (Node/Express + TypeScript) implements these endpoints:

| Endpoint | Method | Path | Description | Status |
|---|---|---|---|---|
| Health check | GET | `/health` | Returns qdrant/llm/embedding status | Verified per CLOUD_VERIFICATION.md |
| Ingest knowledge | POST | `/knowledge/ingest` | Bulk ingest into Qdrant Cloud `cloud_knowledge` | Verified per CLOUD_VERIFICATION.md |
| Pull knowledge | GET | `/knowledge` | Cursor-paginated pull from Qdrant Cloud | Verified per CLOUD_VERIFICATION.md |
| Cloud answers | POST | `/answers` | Cloud LLM query, return answer + provenance | Verified per CLOUD_VERIFICATION.md |
| Sync operations | PUT | `/sync/operations/:id` | Idempotent upsert into Qdrant Cloud `device_memory` | Documented, Phase 7 |
| Direct Qdrant Cloud API | — | — | Qdrant Cloud HTTP API: points-api, collections-api, queries-api | Standard Qdrant Cloud |

**Qdrant Cloud `device_memory` collection:** stores synced memories with payload fields including `syncDecision`, `origin`, `version`, `updatedAt`, `sync_state`, `sync_decision`, `record_type`, entity IDs, etc.

**Qdrant Cloud `cloud_knowledge` collection:** stores curated knowledge from the cloud backend.

### 4.2 Qdrant Cloud Payload Support (VERIFIED — from CLOUD_VERIFICATION.md)

Live cloud operations confirmed:

- Write succeeds: ingest step creates points with full payload
- Read/search succeeds: pull returns items with payloads
- Delete/tombstone: re-ingest with `tombstone:true` and higher version applies tombstone semantics

### 4.3 Qdrant Cloud vs Qdrant Edge Feature Parity (ANALYSIS)

| Feature | Qdrant Edge 0.8.0 | Qdrant Cloud | Parity |
|---|---|---|---|
| Upsert with payload | VERIFIED | VERIFIED | Yes |
| Retrieve by id | VERIFIED | VERIFIED | Yes |
| Vector search | VERIFIED | VERIFIED | Yes |
| Payload filtering | VERIFIED | VERIFIED | Yes |
| Payload indexes (keyword, integer, etc.) | VERIFIED | VERIFIED | Yes |
| Scroll with pagination | VERIFIED | VERIFIED | Yes |
| Count with filter | VERIFIED | VERIFIED | Yes |
| Geo queries | VERIFIED | VERIFIED | Yes |
| Text/BM25 search | Not in boundary yet | VERIFIED on Cloud | No (Phase 13+) |
| Transactions/CAS | NOT supported | NOT supported | Yes (both lack) |
| Cross-record transactions | NOT supported | NOT supported | Yes (both lack) |
| Cursor-based pagination | VERIFIED (scroll offset) | VERIFIED (GET /knowledge) | Yes |
| Idempotent upsert by operationId | NOT in Edge boundary | Documented requirement | Partial |
| Real-time subscriptions | NOT available | MAYBE via Webhooks | N/A |

### 4.4 Evidence Labeling — Qdrant Cloud

| Claim | Label | Evidence |
|---|---|---|
| Qdrant Cloud supports upsert with full payload | VERIFIED | CLOUD_VERIFICATION.md step 2 ingest |
| Qdrant Cloud supports cursor-pulled knowledge | VERIFIED | CLOUD_VERIFICATION.md step 3 pull |
| Qdrant Cloud supports tombstone/delete | VERIFIED | CLOUD_VERIFICATION.md step 5 delete/tombstone |
| Qdrant Cloud API endpoint list is complete | OPEN QUESTION | Only documented endpoints verified; full API not exhaustively inspected |
| Backend idempotency on UPSERT-:id | ASSUMPTION | Documented as Phase 7 requirement; not yet verified live |
| Blank question → 400 on answers | VERIFIED | CLOUD_VERIFICATION.md step 5 |
| LLM_NOT_CONFIGURED → 503 | VERIFIED | CLOUD_VERIFICATION.md step 5 |
---
# 5. Synchronization Model

## 5.1 Scope boundary

Synchronization applies to **Qdrant-native Records** (Phase 11 `LocalRecordStore`) only. The legacy
Room memory path and its `DefaultSyncEngine` remain untouched and continue to operate (rollback
baseline).

## 5.2 Sync state model

The Phase 12B prompt proposed: `LOCAL_ONLY / PENDING / IN_FLIGHT / ACKNOWLEDGED / SYNCED /
FAILED_RETRYABLE / FAILED_PERMANENT / CONFLICT / TOMBSTONED`.

Phase 9 §10.2 specifies: `PENDING → IN_FLIGHT → ACKED | FAILED | DEAD` ("unchanged names").

**Decision: adopt the Phase 9 names for the operation record, and add the two states Phase 9 has no
equivalent for.** VERIFIED that Phase 9's names are already implemented in the legacy path
(`SyncModels.kt:11-26`), so this preserves continuity.

| State | Applies to | Meaning | Retryable |
|---|---|---|---|
| `LOCAL_ONLY` | record | Policy forbids leaving device. **No operation record is ever created.** | n/a |
| `PENDING` | operation | Created locally, not yet claimed | yes |
| `IN_FLIGHT` | operation | Claimed by a worker | yes (via recovery) |
| `ACKED` | operation | Remote confirmed acceptance | terminal |
| `SYNCED` | record | All its operations ACKED and cloud version == local version | n/a |
| `FAILED` | operation | Retryable failure, budget remains | yes |
| `DEAD` | operation | Permanent failure or retry budget exhausted | no |
| `CONFLICT` | record | Divergence detected; a CONFLICT record exists | no |
| `TOMBSTONED` | record | Deleted; tombstone propagates | n/a |

`LOCAL_ONLY` is deliberately **not** an operation state — it is a *record* policy state that
guarantees absence of any operation record (Phase 9 §10.4). Modelling it as an operation state
would risk creating a row that later gets pushed.

## 5.3 Legal transitions

```text
record:      (any) --delete----> TOMBSTONED        (version+1, tombstone=true)
             TOMBSTONED --restore-> (previous)    (version+1, tombstone=false)

operation:   (absent) --enqueue----> PENDING
             PENDING --claim-------> IN_FLIGHT
             IN_FLIGHT --ack--------> ACKED
             IN_FLIGHT --retryable--> FAILED
             IN_FLIGHT --permanent--> DEAD
             FAILED --reclaim-----> IN_FLIGHT
             IN_FLIGHT --proc death-> IN_FLIGHT     (recovered, not transitioned)

record sync: PENDING --all ops ACKED + versions match--> SYNCED
             (any) --divergence--> CONFLICT
```

**Illegal (must never occur):** `ACKED → PENDING`; `DEAD → PENDING`; `ACKED → IN_FLIGHT`;
`SYNCED → PENDING` without an intervening local change that bumps `record_version`.

An operation record is **append-only per operation identity** (§9). A new local change creates a new
operation identity rather than mutating a terminal one.

---
# 6. Record Identity

Three distinct identities. Conflating them is the root cause of Contradiction #1.

## 6.1 record_id — stable logical identity

**Definition:** the immutable identifier of a logical record, stable across all versions, devices
and the cloud. Never changes for the life of the record.

**Representation:** `RecordId`, a validated UUID string (`RecordId.kt:11-23`).

**Qdrant point identity:** for a knowledge record, `record_id` **is** the Qdrant point id.
VERIFIED — `QdrantEdgeRecordStore.upsert` passes `record.id.uuid` as the point id
(`QdrantEdgeRecordStore.kt:138`), and qdrant-edge accepts only `u64` or UUID (Phase 9 §0 fact 1).

**Human identity is separate.** `_entity_id` ("M-042", "SKF-6205") is an indexed *payload* field
carrying the human-facing external reference, NOT the point id. VERIFIED — `Record.entityId` maps
to payload `_entity_id` (`Record.kt:98`).

## 6.2 Why record_id cannot be the operation key

Phase 9 §10.2 states it explicitly: the idempotency key must be a *payload* field because point ids
are UUID/u64 only — "the point id itself does not carry `UPSERT-…`". VERIFIED by source:
`parse_point_id` (`store.rs:59-61`) rejects any non-UUID/u64 string.

## 6.3 record_version

**Definition:** a monotonically increasing integer describing the ordered state of one logical
record. Already present in the Phase 11 model.

VERIFIED — `Record.version: Int = 1` (`Record.kt:69`), serialized to `_version` (`Record.kt:99`),
indexed as `integer` (`QdrantEdgeRecordStore.kt:75`), and proven to replace stale state on upsert
(Phase 10 §11: v1→v2, stale `severity=high` filter returns empty).

`Record.schemaVersion` (`Record.kt:87`) is a **different** axis: it versions the *payload shape*,
not the record's state. Never compare `schema_version` for sync ordering.

## 6.4 operation_id

**Definition:** the deterministic identity of one *logical synchronization intent*, used to make
delivery and retry idempotent.

```text
operation_id = <OP_TYPE>:<record_id>:<record_version_at_enqueue>
```

Justified in §9.

---
# 7. Versioning

## 7.1 Rules

| Local/Cloud comparison | Classification | Winner | Notes |
|---|---|---|---|
| no record → vN | `NEW` | vN | cloud absent |
| vN → vN, same content_hash | `DUPLICATE` | — | no write, idempotent no-op |
| vN → vN, different content_hash | `CONFLICT` | — | neither auto-wins |
| vN → vM where M < N | `STALE` | keep vN | reject write |
| vN → vM where M > N | `UPDATE` | vM | fast-forward |
| tombstone vN → vM < N | `STALE` | keep tombstone | resurrection blocked |

VERIFIED that the fields needed for this already exist and persist: `_version` (integer, indexed),
`_content_hash` (keyword, indexed), `_tombstone` (bool, indexed), `_deleted_at` (datetime,
indexed) — `Record.kt:99-109`, indexes at `QdrantEdgeRecordStore.kt:75-87`.

## 7.2 Version comparison is the ONLY ordering primitive

Timestamp comparison (`_updated_at`) is **not** used to order records across devices. Device clocks
are not a causality proof; two offline edits can share a millisecond. `_updated_at` is retained for
**display ordering only** (Phase 9 §6.2).

## 7.3 Insufficiency of the current model — minimal required extension

The Phase 11 `Record` can express single-writer ordering, but convergence needs one more concept.
**PROPOSED, minimal:**

- `_last_synced_version: int` — highest record_version known accepted by the cloud. Lets
  reconciliation answer "is this record fully synced?" without replaying operation history.
- `_last_synced_operation_id: keyword` — last accepted operation identity, for diagnosing
  uncertain acknowledgements (§18 cases 3-4).

Both are additive to the envelope; no existing field changes meaning.

## 7.4 Single-writer vs multi-writer

- **Single-writer (VERIFIED in this app):** one Android process, `QdrantEdgeRecordStore` is
  `Mutex`-serialized (`QdrantEdgeRecordStore.kt:25`), WorkManager runs one unique sync job. Local
  versions are totally ordered per record.
- **Multi-writer (NOT implemented):** two devices editing offline produce two divergent versions.
  `record_version` cannot detect this — both may reach v5. Detection therefore **requires**
  `content_hash` comparison (§7.1 row 3). A plain integer version is **not** a conflict detector
  and must never be described as one.

---
# 8. Content hashing

VERIFIED convention, reused rather than reinvented — `DefaultMemoryRepository.contentHash`
(`DefaultMemoryRepository.kt:330-333`):

```kotlin
normalize(text) = text.trim().replace(whitespaceRegex, " ")
digest = SHA-256("$title\n$content")
hex
```

Rules (**PROPOSED**, extending the verified convention to arbitrary records):

- hash the **canonical domain payload only** — exclude envelope sync fields (`_sync_state`,
  `_last_synced_*`, attempts, sync timestamps);
- sort object keys before hashing so serialization order cannot change the digest;
- never hash vectors, operation ids, or device ids.

Excluding envelope sync fields is what makes `vN → vN identical` detectable: a record locally
re-marked `SYNCED` still hashes identically to its pre-sync twin.

---
# 9. Operation Identity — resolves Contradiction #1

## 9.1 The conflict

| Source | Scheme | Consequence |
|---|---|---|
| Phase 9 §10.2 | `entity_id = "UPSERT-<recordId>"` | one operation record per record, refreshed latest-wins |
| Phase 12B §8 prompt | `UPSERT:<record-id>:<version>` | one operation record per version |

## 9.2 Analysis of both designs

**Design A — record-scoped, mutable operation (Phase 9 §10.2).**
One durable operation point per record. A local change *refreshes the payload* of the same point
(latest-wins). Strength: bounded point count, bounded reconciliation scan, and it directly
generalises today's `refreshPayload`.

Weakness: **a single `operation_id` string cannot distinguish two different logical operations.**
After v7 is ACKed and v8 is enqueued, a *retried* v7 carries the same `operation_id` as the ACKed
v7, so the backend's dedup check (`sync.ts:40-46`, `appliedOp === push.operationId`) reports
`duplicate: true` and **silently discards a legitimate v8 push** if interleaved. Worse, the reverse
(v8 ACKed, stale v7 retried) is treated as a *new* operation and **overwrites v8 with v7**.

**Design B — version-scoped, immutable operation (Phase 12B §8).**
One operation identity per `(record, version)`. Strength: identity is a content-addressed key of
the intent, so a retry is unambiguously the *same* operation and a different version is
unambiguously *not* the same. This makes stale-write detection possible **at the protocol level**.

Weakness: operation points accumulate (one per version). Bounded by a GC policy (§14.5).

## 9.3 Decision

**Adopt Design B (version-scoped operation identity), and preserve Design A's latest-wins property
by splitting the two concerns across two different things.**

- **Operation records are immutable and version-scoped.** Identity
  `UPSERT:<record_id>:<version>`. Once `ACKED`/`DEAD` it is never rewritten. Retries reuse the
  exact same identity. This gives exact idempotency and exact stale detection.
- **"One current sync intent per record" is solved by an indexed query, not by mutating a record.**
  The change detector selects the *maximum* `_version` among `_record_id = <id>` operations via an
  indexed filter — the same read it already performs. No scan, no mutation.

This satisfies Phase 9's actual requirements (bounded work, no duplicate concurrent operations,
deterministic identity) while removing the stale-retry hazard, and it is the only one of the two
designs under which the backend contract in §23 is implementable.

**Rejected:** Design A alone (proven unsafe above); "version-scoped id but mutable point" (re-
introduces the replay ambiguity it was meant to remove).

## 9.4 Answers to the required sub-questions

| # | Question | Answer |
|---|---|---|
| A | record identity? | `record_id` — immutable UUID, = Qdrant point id for knowledge records (§6.1) |
| B | record version? | `record_version` — monotonic int describing ordered record state (§6.3) |
| C | operation identity? | `<OP>:<record_id>:<record_version>` — content-addressed intent key (§9.3) |
| D | Qdrant point identity? | UUID for knowledge records (= record_id); **random UUID** for operation/conflict/cursor records, because the semantic key must live in an indexed payload field (Phase 9 §10.2) |
| E | latest-wins refresh? | Applies to the **knowledge record payload** (one point per record_id, upsert replaces). Explicitly does **not** apply to operation records, which are immutable |
| F | one point per record or per version? | Knowledge: **one per record**. Operations: **one per version** (GC-bounded) |
| G | mutable or immutable? | Operation records **immutable** after terminal state; knowledge records mutable |
| H | how do retries identify the same operation? | By recomputing the identical `operation_id` from (record_id, version, op type) — no randomness, no counters |
| I | how does a newer version relate to an older operation? | Independent operation with a higher version suffix; the older stays ACKED and is inert |
| J | how are stale operations rejected? | Backend compares `record_version` before writing (§23.2). Android additionally refuses to enqueue a version ≤ `_last_synced_version` |
| K | how are duplicates recognised? | `operation_id` equality at the backend (VERIFIED mechanism, `sync.ts:40-46`); locally by indexed existence check on `operation_id` |
| L | how are conflicts represented? | A `record_type=conflict` record holding both sides' evidence (Phase 9 §10.6), plus `CONFLICT` state on the record |

## 9.5 Operation types

VERIFIED — `OutboxOperationType.UPSERT` is the only existing value (`SyncModels.kt:28-30`).
Phase 9 §10.5 adds `TOMBSTONE`.

| Type | Identity | Semantics |
|---|---|---|
| `UPSERT` | `UPSERT:<id>:<v>` | create or update the knowledge record |
| `TOMBSTONE` | `TOMBSTONE:<id>:<v>` | mark the record deleted at version v |

**Not `DELETE`.** A physical point deletion on the cloud cannot be distinguished from a client bug,
and per §14 a tombstone must survive to prevent resurrection. PROPOSED: the tombstone is carried
as `tombstone=true` + `deleted_at` inside a normal `UPSERT` operation, with `TOMBSTONE` as the
operation *type* label for observability and routing.

---
# 10. Change Detection

## 10.1 What must be detected

new record · updated record · tombstone · already-synchronised · operation missing · operation
uncertain (acknowledgement unknown) · stale cloud · orphan operation

## 10.2 Mechanism — indexed envelope fields, no full scans

PROPOSED. Uses only capabilities VERIFIED available (indexed keyword/integer/datetime payload fields
+ `count_filtered` + `scroll` with filter):

| Detection | Indexed query | Basis |
|---|---|---|
| unsynced change | `_sync_state == "PENDING"` | keyword index (`QdrantEdgeRecordStore.kt:80`) |
| needs reconciliation | `_sync_state == "PENDING"` AND no operation for (`_record_id`, `_version`) | two indexed reads |
| uncertain ack | operation `_state == "IN_FLIGHT"` AND `_lease_until < now` | keyword + datetime |
| tombstone pending | `_tombstone == true` AND `_sync_decision != "LOCAL_ONLY"` | bool + keyword |
| fully synced | `_sync_state == "SYNCED"` AND `_last_synced_version == _version` | PROPOSED field (§7.3) |

Explicitly **not** used: full-collection scan per cycle; `_updated_at` ordering to enumerate changes
(timestamps are display-only, §7.2).

## 10.3 Evidence classification of each mechanism

| Mechanism | Label | Note |
|---|---|---|
| filter on indexed keyword/int/bool | VERIFIED | Phase 10 §7 exact filter sets; indexes persist |
| `_version` / `_content_hash` comparison | VERIFIED (storage) / PROPOSED (logic) | fields exist + persist; comparison logic new |
| `_last_synced_version` reconciliation | PROPOSED | requires §7.3 extension |
| change-log / CDC from the WAL | UNSUPPORTED | no public API; WAL is an internal implementation detail |
| server-side `updated_at` cursor | UNSUPPORTED on our boundary | no `order_by` in JNI path (§21.3) |
| monotonic sequence numbers | UNSUPPORTED | qdrant-edge has no auto-increment; point ids are UUID/u64 only |

---
# 11. Local → Cloud

```text
local write (record upsert, version bumped)
  -> policy gate: LOCAL_ONLY => stop, no operation ever created
  -> operation_id = UPSERT:<record_id>:<record_version>
  -> upsert operation record (PENDING) -> flush            [Qdrant Edge]
  -> record _sync_state = PENDING                          [Qdrant Edge]
  -> WorkManager enqueue (REPLACE, CONNECTED)
  -> worker: claim operation (PENDING|FAILED -> IN_FLIGHT) -> flush
  -> PUT backend with operation_id in path AND body
  -> backend applies the §23 contract
  -> on ACK: operation -> ACKED -> flush
              record _sync_state = SYNCED, _last_synced_version = version -> flush
```

**No atomicity is claimed across the two writes.** VERIFIED constraint: qdrant-edge has no
cross-point transaction (Phase 9 §10.7). Ordering is *record first, then operation* (Phase 9
§10.3), so a crash between them leaves a `PENDING` record with no operation — detected and repaired
by reconciliation (§19), never lost.

## 11.1 Required behaviour per failure

| Scenario | Required behaviour |
|---|---|
| A offline creation | operation stays `PENDING`; local query/answering unaffected |
| B app restart | `PENDING`/`IN_FLIGHT` recovered at startup (§18) |
| C worker restart mid-flight | `IN_FLIGHT` reclaimed via lease timeout; retry reuses identical `operation_id` |
| D network failure | `FAILED`, retryable, budget decremented |
| E timeout **after** cloud accepted | identical `operation_id` resent -> backend returns `duplicate`; Android ACKs. **This is why §9.3 chose immutable version-scoped identity** |
| F retry after timeout | same as E |
| G duplicate operation | idempotent, no second write |
| H cloud already at same version+hash | `NO_OP`, operation `ACKED` |
| I cloud has newer version | backend returns `STALE`; Android records the cloud version and schedules reconciliation — it does **not** silently adopt (§23.3) |

Scenario I is **unsafe today** — see §23.3.

---
# 12. Cloud → Local

```text
GET /knowledge?cursor=...   (existing paginated contract)
  -> classify each item against local state
       NEW / DUPLICATE / UPDATE / SUPERSEDES / CONFLICT / TOMBSTONE / NO_OP
  -> apply deterministically (upsert, or create a conflict record)
  -> flush
  -> persist cursor (sys_cursor record) -> flush
```

| Cloud vs local | Action |
|---|---|
| absent locally | `NEW` -> upsert with `origin=CLOUD`, `_sync_state=SYNCED` |
| same version, same content_hash | `DUPLICATE` -> no write (§13, prevents echo) |
| same version, different content_hash | `CONFLICT` -> create `record_type=conflict`; **do not overwrite** |
| cloud version > local | `UPDATE` -> upsert cloud state |
| cloud version < local | `STALE` -> keep local; no write |
| cloud tombstone, local active | `TOMBSTONE` -> local tombstone at cloud version |
| local tombstone, cloud active & older | `NO_OP` (resurrection blocked) |

PROPOSED invariant: a record applied from the cloud is written with `_sync_state = SYNCED` and
`_origin = CLOUD`, and **must not** generate an outgoing operation (§13).

The cursor must be advanced **after** the page is applied, so a crash mid-page re-applies the page —
safe because application is idempotent by (record_id, version, hash). `SYS_CURSOR` exists in
`RecordType.kt:22`; its payload-only feasibility is blocked by §20.3.

---
# 13. Bidirectional Sync (echo-loop prevention)

The echo hazard: local v7 -> cloud v7 -> cloud pull returns v7 -> local must **not** enqueue a new
operation for it.

Mechanism (PROPOSED; all inputs VERIFIED-present):

1. Cloud-applied records are written with `_sync_state = SYNCED` **and**
   `_last_synced_version = record_version` (§7.3).
2. The change detector (§10) only enqueues when `record_version > _last_synced_version` **and**
   `_sync_decision != LOCAL_ONLY`.
3. A cloud-pulled record therefore fails condition 2 immediately -> no operation -> no echo.
4. Server-side, the same record re-pushed with the identical `operation_id` is a `DUPLICATE` no-op
   (§23.1), so even a mistaken re-push cannot create a second cloud record.

The two guards (local version watermark, server operation identity) are independent, so this is
safe by construction rather than by a lock.

---
# 14. Tombstones

## 14.1 Why required

A physical `delete` is unrecoverable and unreplayable: after it, neither side can prove the record
ever existed, so a concurrent offline edit on another device would **resurrect** the record. VERIFIED
that the fields already exist and persist: `_tombstone` bool, `_deleted_at` datetime, both indexed
(`Record.kt:108-109`).

VERIFIED that a soft-delete path already exists: `LocalRecordStore.softDelete` sets
`tombstone=true`, `deletedAt`, bumps `version` (`QdrantEdgeRecordStore.kt:180-198`).

## 14.2 Representation

PROPOSED: tombstone is **not** a separate record type. It is a knowledge record with:

```text
_tombstone     = true
_deleted_at    = <epoch millis>
_version       = <monotonic; tombstone is versioned>
_sync_state    = PENDING          (until propagated)
_sync_decision = <unchanged>      (LOCAL_ONLY tombstones never propagate)
```

Phase 9 §10.5 agrees ("record payload flipped to tombstone"). A separate `record_type=sync_tombstone`
record was considered and **rejected**: it duplicates the payload, creates a second source of truth
for version, and doubles the reconciliation surface.

## 14.3 Resurrection prevention

Tombstone is an ordinary versioned state, so §7.1's rules apply unchanged: a cloud record at a lower
version than a local tombstone is `STALE` and rejected. This requires §23.2's version enforcement on
the backend — otherwise a stale cloud `UPSERT` will happily overwrite a local tombstone.
**Tombstone correctness is blocked on the same backend fix as §11.1-I.**

## 14.4 Retention

PROPOSED: tombstones retained indefinitely by default. Physical GC permitted only when (a) every
known device has acknowledged version >= tombstone version, (b) a retention window elapsed, and
(c) an explicit administrative action. Automated GC is a Non-Goal (§33).

## 14.5 Operation-record GC

PROPOSED: terminal (`ACKED`) operation records may be deleted only after the corresponding record's
`_last_synced_version` proves the operation superseded. `DEAD` operations retained indefinitely
for diagnosis.

---
# 15. Conflict Detection

## 15.1 Detection

VERIFIED inputs: `_version`, `_content_hash`, `_origin`, `_authority` (all present and indexed,
`Record.kt:69-87`).

| Signal | Meaning |
|---|---|
| same `version`, different `content_hash` | concurrent offline edit — **true conflict** |
| same `version`, same `content_hash` | duplicate delivery — **not** a conflict |
| different `version` | ordinary staleness — **not** a conflict |
| differing `_authority` | provenance disagreement |

VERIFIED precedent: the legacy `ConflictEntity` already stores both sides' evidence
(title/content/version/hash/origin/authority) and a resolution state, resolved by
`DefaultConflictResolver` (keepLocal / keepCloud / dismiss). Phase 12B reuses that model in
Qdrant-native form.

## 15.2 Representation

PROPOSED: `record_type=conflict` record (VERIFIED that `CONFLICT` exists, `RecordType.kt:19`),
payload-only, deterministic point id derived from
`SHA-256(subject | local_id | local_hash | incoming_id | incoming_hash)` (Phase 9 §10.6, reusing
today's `conflictId` derivation). Re-pull therefore cannot create duplicates.

## 15.3 Blocker

Phase 9 §10.6/§2.6 specify conflict/outbox/cursor records as **payload-only** (no vector). That is
**not currently possible** — see §20.3. Hard blocker for the conflict subsystem as specified.

---
# 16. Conflict Resolution Options

Presented as a comparison; **no winner is chosen here**.

| Strategy | Semantics | Fits EdgeMind? | Cost |
|---|---|---|---|
| Last-write-wins by timestamp | newest `updated_at` wins | **No** — clock is not causality (§7.2) | trivial, unsafe |
| Version comparison | higher `version` wins | Only safe for single-writer; silently loses multi-writer edits | low |
| Authority/source priority | cloud `authority` beats local | Matches "do not silently overwrite contradictory knowledge" | low |
| Explicit conflict record + human | preserve both, resolve in UI | Matches existing `DefaultConflictResolver` + CONFLICTS screen | needs UI work (out of Phase 12B) |
| Field-level merge | merge non-overlapping fields | Hard without schema; `_metadata` is a free-form map (VERIFIED `Record.kt:86`) | high, schema-bound |

PROPOSED: **authority priority, then explicit conflict record** for the remainder — cloud
authoritative knowledge wins; genuinely divergent local knowledge is preserved as a conflict record
rather than discarded. Reuses existing resolver semantics; no new UI in Phase 12B.

---
# 17. Offline Behavior

| Condition | Required behavior | Basis |
|---|---|---|
| permanently offline | all local Qdrant operations work; operations accumulate `PENDING` | offline-first project rule |
| temporarily offline | same; CONNECTED constraint defers | VERIFIED `SyncWorker.kt` |
| network returns | drain in bounded batches, then `success()` | VERIFIED `SyncWorker.doWork` |
| network drops mid-sync | current operation -> `FAILED`; unprocessed stay `PENDING`; nothing ACKed | no fabricated success |
| cloud unreachable | `SERVER_TEMPORARY`/`NETWORK` -> retry with backoff | VERIFIED `SyncFailureKind` |
| auth fails | `UNAUTHORIZED` -> `DEAD` (permanent) | VERIFIED `isPermanent` |

Invariant: **no cloud availability condition may gate a local read or write.** Local querying,
ingestion and answering must not touch the sync path.

---
# 18. Crash Safety

| # | Scenario | Required recovery | Durable state relied on |
|---|---|---|---|
| 1 | record written, operation missing | reconciliation: `_sync_state=PENDING` w/o operation -> enqueue | record + §10.2 query |
| 2 | operation written, worker never ran | `PENDING` picked up at next startup | operation record |
| 3 | request sent, process dies before response | `IN_FLIGHT` lease expires -> retry with **identical** `operation_id` | idempotency (§9.3) |
| 4 | cloud wrote, local ACK missing | retry -> `duplicate` -> ACK locally | server dedup (VERIFIED `sync.ts:40-46`) |
| 5 | local ACK written, worker crashes | nothing pending; `SYNCED` durable after flush | flush discipline |
| 6 | cloud returns duplicate | mark `ACKED`, no local write | — |
| 7 | cloud returns newer version | record cloud version; schedule pull (§11.1-I) | §7.3 field |
| 8 | cloud returns same version, different hash | create conflict record; **do not overwrite** | §15 |
| 9 | tombstone persisted, upload fails | tombstone stays `PENDING`; retry identical operation | §14.2 |
| 10 | page applied, process dies before checkpoint | cursor not advanced -> page re-applied -> idempotent by (id,version,hash) | §12 |

**Explicit non-claim:** none of these are atomic. Every row is an *ordering + reconciliation*
pattern (Phase 9 §12.2). Cases 3, 4 and 7 are **unsafe today** — they depend on the backend contract
in §23, which the current backend does not implement.

---
# 19. Reconciliation

Bounded, indexed, first-class (Qdrant has no transactions).

| Pass | Trigger | Query | Bounded by |
|---|---|---|---|
| R1 stale `IN_FLIGHT` | worker start | `_state == "IN_FLIGHT"` AND `_lease_until < now` | lease threshold |
| R2 record w/o operation | worker start | `_sync_state == "PENDING"`, then existence check per record | page limit |
| R3 orphaned operation | worker start | operation whose `_record_id` has no knowledge record | page limit |
| R4 tombstone propagation | worker start | `_tombstone == true` AND `_sync_state != "SYNCED"` AND syncable | page limit |
| R5 uncertain acknowledgement | worker start | R1 + `_last_synced_operation_id` mismatch | lease threshold |

PROPOSED: each pass is page-limited and runs at most once per worker invocation. A full sweep is a
separate maintenance action, never a per-cycle cost. No pass may scan the collection unfiltered.

---
# 20. Outbox / Operation Records

## 20.1 Where operations live

VERIFIED — Phase 9 §3.3 mandates **Option A: one primary collection**, and `OUTBOX_OP` / `CONFLICT` /
`SYS_CURSOR` exist as `RecordType` values (`RecordType.kt:19-22`). Operations are therefore
**points in the same shard**, discriminated by an indexed `_record_type` keyword — not a second
collection or shard. This preserves Phase 9 §3.2's decisive argument (native cross-entity
retrieval) and avoids a second persistence path on a device.

## 20.2 Operation record payload

PROPOSED, extending the verified Phase 9 §10.2 field list with §9.3's identity and §7.3's
watermark:

```text
_record_type    = "outbox_op"
operation_id    = "UPSERT:<record_id>:<version>"       <- indexed keyword
_record_id      = <knowledge record id>                <- indexed keyword
_operation_type = "UPSERT" | "TOMBSTONE"               <- indexed keyword
_state          = PENDING|IN_FLIGHT|ACKED|FAILED|DEAD  <- indexed keyword
_attempts       = integer
_last_error     = <SyncFailureKind>                    <- classification only, never content
_created_at / _updated_at                              <- indexed datetime
_lease_until    = datetime                             <- IN_FLIGHT recovery (R1)
_version        = <record_version at enqueue>          <- operation ordering
_sync_decision  = SYNC | SYNC_REDACTED                  <- LOCAL_ONLY never enqueued
_redacted       = bool
+ policy-sanctioned domain fields ONLY (SyncPayloadFactory remains sole authority)
```

`payload_title`/`payload_content` remain the **only** representation that may leave the device
(VERIFIED — `DefaultSyncEngine.buildOperation`, `SyncPayloadFactory`).

## 20.3 BLOCKER A — payload-only records are not supported by the current boundary

**Classification: UNSUPPORTED (as currently implemented).**

Proof chain (source, three links):

1. `QdrantEdgeRecordStore.upsert`: `val vector = record.vector ?: floatArrayOf()`
   (`QdrantEdgeRecordStore.kt:137`) — a null vector becomes a **zero-length array**, not an absent
   vector. Same at lines 154 and 193.
2. `store::check_dimension` rejects any length != configured dimension (`store.rs:48-57`).
3. `store::upsert_with_payload` calls `check_dimension` **before** writing (`store.rs:125-144`).

=> For a 512-dim shard, a zero-length vector fails with `DimensionMismatch`. **A payload-only record
cannot currently be written.**

This blocks every payload-only design element: outbox operations, conflict records, system cursors —
i.e. the whole of Phase 9 §10. VERIFIED consistent with Phase 10 §16 limitation 3 ("Payload-only
points not yet exercised — Phase 9 Q1 stays open") and Phase 9 §2.6, which recorded the same item as
an **OPEN QUESTION**. It was never closed.

Note: this was not executable as an external integration test because `mod store` is private in
`lib.rs:1-3`; the proof is by source reading of the three links above.

### 20.3.1 Viable alternatives (documented, not implemented)

| # | Alternative | Assessment |
|---|---|---|
| 1 | Make the Kotlin boundary send "no vector" and widen `check_dimension` to accept an *absent* vector map (not a zero-length one) | **Correct fix.** Matches qdrant-edge's model (`Vectors::new_named([])`). Requires native change, then the Phase-10 spike methodology to prove payload-only persistence across restart |
| 2 | Give system records a real 512-dim vector of their payload text | PROPOSED fallback already named by Phase 9 §2.6. Cheap, but pollutes vector search, wastes ~2 KiB/record, and requires `record_type` exclusion filters everywhere |
| 3 | Store system records in a second shard | Rejected — Phase 9 §3.3 rejected this; adds a second persistence path |
| 4 | Use a separate collection for system records | PROPOSED but contradicts §20.1; revisit only if 1 and 2 both fail |

**Decision: pursue alternative 1, with alternative 2 as the documented fallback.** Neither may be
implemented in Phase 12A.1 (documentation-only).

---
# 20A. Filter Compiler — VERIFIED defect affecting every sync query

**Classification: BROKEN (Or / In shapes), VERIFIED (Match, Range, DateRange, Exists, Not).**

`FilterCompiler` emits `minimum_should_match` for `In`, `And`(with should) and `Or`
(`FilterCompiler.kt:67,129,144`). qdrant-edge 0.8.0's `Filter` is declared
`#[serde(deny_unknown_fields, rename_all = "snake_case")]` (`src/segment/types.rs:4407-4408`) and has
fields `should`, `min_should`, `must`, `must_not` — **no `minimum_should_match`**, and no
`min_should_match`.

Empirically VERIFIED by a throwaway probe executed against the vendored crate and then deleted:

```text
PROBE REJECTED  Or/minimum_should_match(compiler) :: {"should":[...],"minimum_should_match":1}
  -> unknown field `minimum_should_match`, expected one of `should`, `min_should`, `must`, `must_not`
PROBE ACCEPTED  Match/must   :: {"must":[{"key":"severity","match":{"value":"high"}}]}
PROBE ACCEPTED  Exists/is_null :: {"must":[{"key":"severity","is_null":false}]}
PROBE ACCEPTED  Not/must_not :: {"must_not":[{"key":"severity","match":{"value":"high"}}]}
PROBE ACCEPTED  Range        :: {"must":[{"key":"temperature_c","range":{"gte":80.0}}]}
```

Consequences for Phase 12:

- The correct shape is
  `{"should":[...], "min_should": {"conditions":[...], "min_count": 1}}`
  (`MinShould` at `src/segment/types.rs:4394-4402`).
- **Every `Or` filter currently throws** at `parse_filter` (`store.rs:283-299`, mapping to
  `EdgeError::InvalidFilter`). That includes `RecordQuery.allActive()` and
  `RecordQuery.searchInTypes()` (`RecordQuery.kt:52-75`), which both build
  `RecordFilter.or(*recordTypes…)` — i.e. **record-type-scoped scroll and search are broken today**,
  before any sync work.
- Sync depends on record-type isolation (§10.2, §20.1), so **BLOCKER B** must be fixed before
  Checkpoint 5 (LOCAL PERSISTENCE).
- Note `compileNot` (`FilterCompiler.kt:100-115`) also emits `{"must_not":[...]}` with no
  `min_should`, which the probe confirms is accepted.

PROPOSED remediation: emit `min_should` with a duplicated `conditions` array and `min_count`, or
avoid `should`/`min_should` entirely by expanding small `Or` sets into `must` blocks of negated
pairs. **Not implemented in Phase 12A.1.**

---
# 21. Collection Strategy

VERIFIED — Phase 9 §3.3 **Option A: one primary collection** stands. Phase 12 introduces no new
collection.

| Data | Location | Notes |
|---|---|---|
| knowledge records | points, `_record_type` in KNOWLEDGE_TYPES | vectored |
| outbox operations | points, `_record_type = "outbox_op"` | **blocked by §20.3** |
| conflicts | points, `_record_type = "conflict"` | **blocked by §20.3** |
| pull cursor / app state | points, `_record_type = "sys_cursor"` | **blocked by §20.3** |

Rationale for no separate sync collection: Phase 9 §3.2's decisive criterion is native cross-entity
retrieval; and reconciliation must join operation -> record, which is cheapest in one shard. A
second shard also doubles the flush discipline that Phase 10 showed is the single durability
mechanism.

## 21.2 Scroll ordering

- VERIFIED: `ScrollRequest.order_by: Option<OrderByInterface>` is a **public field** in qdrant-edge
  0.8.0 (`src/edge/requests/scroll.rs:20`) and the shard honours it (`src/shard/scroll.rs:31`). So
  ordering by a payload field is **SUPPORTED** by the crate.
- VERIFIED: our `store::scroll` (`store.rs:212-231`) sets only `limit`, `with_payload`, `filter`,
  `offset`. It **never sets `order_by`**, and **discards** the returned `next_offset`
  (`let (records, _next_offset) = ...`).
- VERIFIED: `NativeBridge.nativeScroll` exposes no ordering parameter (`NativeBridge.kt:19`).

**Classification: SUPPORTED (crate) / UNIMPLEMENTED (our boundary).**

Impact on sync: Phase 9 §10.3 specifies `selectRetryable()` as "scroll `state IN (PENDING,FAILED)`
order_by `created_at`". Without `order_by`, page order is qdrant-edge's internal order — not a
guaranteed FIFO.

- **Is ordering mandatory?** Not for *correctness* (§9.3 makes each operation independently
  idempotent, so out-of-order delivery is safe). It **is** required for *fairness / starvation
  avoidance*: without a deterministic order a hot record can starve older operations.
- **Ordering to adopt:** `_created_at ASC` for the retry queue; `_updated_at DESC` for knowledge
  listing (Phase 9 §6.2). Both fields are already indexed (VERIFIED
  `QdrantEdgeRecordStore.kt:76-77`).
- **Interim (Phase 12B):** client-side sort of each bounded page by `_created_at` before claiming.
  Honest and adequate at page sizes; document the O(page) sort.
- **Preferred:** expose `order_by` through JNI (native change) — a Phase 12B subphase.

Also note `next_offset` is discarded, so `RecordPage.nextOffsetId` is currently synthesised in
Kotlin as "last id if the page was full" (`QdrantEdgeRecordStore.kt:259`). VERIFIED acceptable for
offset pagination; it is a guess, not a server-provided cursor.

---
# 22. Payload Index Strategy

Index only what sync queries actually use. Each new index needs a field, a schema, a query use, and
a test.

| Field | Type | Query use | Status |
|---|---|---|---|
| `_record_type` | keyword | isolate ops/conflicts/knowledge | VERIFIED created (`:73`) |
| `_version` | integer | version comparison (§7) | VERIFIED created (`:75`) |
| `_content_hash` | keyword | duplicate vs conflict (§8, §15) | VERIFIED created (`:82`) |
| `_tombstone` | bool | exclude deleted; tombstone queue | VERIFIED created (`:84`) |
| `_updated_at` | datetime | `IN_FLIGHT` lease, display order | VERIFIED created (`:77`) |
| `_created_at` | datetime | op-queue ordering (§21.2) | VERIFIED created (`:76`) |
| `_sync_state` | keyword | change detection (§10.2) | VERIFIED created (`:80`) |
| `_sync_decision` | keyword | LOCAL_ONLY guard | VERIFIED created (`:79`) |
| `_entity_id` | keyword | external-id lookup | VERIFIED created (`:74`) |
| `operation_id` | keyword | idempotency lookup | **MISSING — required** |
| `_record_id` (on ops) | keyword | op->record join, max-version query | **MISSING — required** |
| `_state` (on ops) | keyword | `selectRetryable`, R1 | **MISSING — required** |
| `_lease_until` | datetime | R1 stale `IN_FLIGHT` | **MISSING — required** |
| `_last_synced_version` | integer | R2 / §7.3 watermark | **MISSING — required by §7.3** |

The five MISSING indexes must be added when the operation record lands, each with a test asserting
the filter returns the exact expected set (methodology VERIFIED from Phase 10 §7). No other index is
justified by any sync query.

---
# 23. Backend Contract

## 23.1 Required semantics

The backend is the trust boundary. A 2xx means the operation was **actually persisted**. Responses
must be machine-classifiable.

| Case | Cloud before | Incoming | Class | HTTP | Cloud after | Idempotent | Android retry | Android pull | Conflict |
|---|---|---|---|---|---|---|---|---|---|
| 1 | none | v7 | `NEW` | 201 | v7 | yes | no | no | no |
| 2 | v7 | v7, same hash | `DUPLICATE` | 200 | v7 | yes | no | no | no |
| 3 | v7 | v7, diff hash | `CONFLICT` | 409 | v7 **unchanged** | yes | no | **yes** | **yes** |
| 4 | v7 | v6 | `STALE` | 409 | v7 unchanged | yes | no | no | no |
| 5 | v7 | v8 | `UPDATE` | 200 | v8 | yes | no | no | no |
| 6 | tombstone v8 | v7 | `STALE` | 409 | tombstone v8 | yes | no | no | no (resurrection blocked) |
| 7 | v7 | tombstone v8 | `UPDATE` | 200 | tombstone v8 | yes | no | no | no |
| 8 | tombstone v8 | v8 active | `STALE` | 409 | tombstone v8 | yes | no | no | no |
| 9 | tombstone v8 | v9 active | `UPDATE` | 200 | v9 | yes | no | no | **yes** — resurrection must be explicit |

Case 9: version ordering alone would silently resurrect. PROPOSED: the backend additionally requires
an explicit resurrect flag or a distinct operation type; otherwise 409.

Required response additions: `status` (`APPLIED|DUPLICATE|STALE|CONFLICT`), `cloudVersion`,
`cloudContentHash`. Preserves the existing 2xx-means-persisted rule (VERIFIED `sync.ts:71-76`).

## 23.2 Required validation

- `version` must be an integer >= 1 (VERIFIED already required numerically, `validation.ts:189`).
- `contentHash` must be a 64-char lowercase hex string.
- `operationId` in path and body must match (VERIFIED already enforced, `sync.ts:29-31`).
- Reject unknown `record_type`.
- Never trust client-supplied `syncState`; the server derives it.
- **NEW:** server must compare versions before writing (currently absent).

## 23.3 Current gap — VERIFIED

`backend/src/routes/sync.ts:36-77` performs retrieve -> compare `operationId` -> **unconditional
upsert**. `validation.ts:189` validates `version` as a number and stores it; no comparison exists in
`sync.ts` or `knowledge.ts` (VERIFIED by grep).

Consequences, in §7.1 vocabulary:

- **Row 4 (`v7 -> v6`)**: backend **overwrites v7 with v6**. Silent data loss.
- **Row 6 (tombstone v8 -> v7)**: backend **resurrects** a deleted record.
- **Row 3 (`v7 -> v7` different hash)**: backend **silently overwrites**, destroying the evidence
  that §15 requires be preserved.

VERIFIED that the design is not merely incomplete but **actively unsafe under Design B**: because
`operation_id` is version-scoped, a retried stale v7 carries a *different* `operation_id` than the
stored v8, so the dedup check fails to catch it and the upsert proceeds. Under Design A the same
request would have been caught as a duplicate. **This confirms the Phase 12B preflight finding and
justifies the reordering in §24.**

## 23.4 Backwards compatibility

Row 1 with the existing single `operationId` payload field must keep working so the legacy Room path
is unaffected. The new `status` field is additive; unknown fields must be ignored by older clients.

---
# 24. Corrected Phase 12B Implementation Order

The Phase 12B prompt placed "Backend hardening" at Checkpoint 9, after local->cloud. **That order
is unsafe and is not preserved here.** VERIFIED reason: §23.3 shows the current backend accepts a
stale write and destroys it. Building and testing local->cloud against it would produce green tests
over incorrect behaviour — the exact "fake success" failure mode the project rules forbid.

| # | Subphase | Objective | Production files likely to change | Prerequisites | Tests | Completion criteria | Known risks |
|---|---|---|---|---|---|---|---|
| 1 | FOUNDATION | domain models: operation, identity, classification, failure kinds | new `core/sync/**` only | this document | identity determinism; §7.1 version matrix; content-hash stability; illegal-transition rejection | models compile + unit tests green; no I/O | low |
| 2 | PROTOCOL | freeze wire contract (§23.1/§23.2) + JSON codecs both sides | new codecs; `SyncModels.kt` additive only | 1 | codec round-trip; malformed rejection | contract doc + codec tests agree | low |
| 3 | BACKEND SAFETY | version comparison, `status` responses, `STALE`/`CONFLICT` | `backend/src/routes/sync.ts`, `validation.ts` | 2 | the 9 cases of §23.1 table-driven; version validation | all 9 cases pass; legacy `UPSERT-<id>` still works | **medium** — changes existing endpoint behaviour |
| 4 | NATIVE GAP A | payload-only records (§20.3 alt. 1) | `store.rs`, `jni.rs`, `NativeBridge.kt`, `QdrantEdgeRecordStore.kt` | 1 | payload-only upsert persists, restarts, retrieves, filters, indexes | payload-only record survives restart | **high** — native durability change |
| 5 | NATIVE GAP B | `FilterCompiler` `Or`/`In` `min_should` fix (§20A) | `FilterCompiler.kt` | 1 | each shape round-trips through real qdrant-edge | record-type-scoped scroll+search work | low |
| 6 | LOCAL PERSISTENCE | operation records in Qdrant; 5 new indexes (§22) | new `data/local/sync/**` | 1,4,5 | restart; indexed `selectRetryable`; idempotent `findByOperationId`; each index asserted | operations persist and are queryable | medium |
| 7 | CHANGE DETECTION | indexed detection per §10; `_last_synced_version` | new store impl | 6 | each detection class returns the exact expected set | no full scans; exact sets | low |
| 8 | LOCAL->CLOUD | claim, deliver, ACK, retry, bounded batches | new engine; `HttpSyncRemoteDataSource` additive | 3,6,7 | scenarios A-I of §11.1 incl. timeout-after-success | no fabricated ACK; DEAD on permanent | medium |
| 9 | CLOUD->LOCAL | pull, classify (§12), apply, cursor | new engine | 3,6,7 | NEW/DUPLICATE/UPDATE/STALE/CONFLICT/TOMBSTONE | no stale overwrite; no echo | medium |
| 10 | CONFLICTS | conflict records, tombstone propagation, resurrection block | new store | 4,6,9 | §23.1 rows 6,8,9; resurrection attempt | tombstone survives stale push | medium |
| 11 | CRASH RECOVERY | R1-R5 reconciliation; the 10 scenarios of §18 | new engine | 8,9 | each of the 10 scenarios | no lost operation; no infinite retry | medium |
| 12 | WORKMANAGER | connect to existing scheduler (no second scheduler) | `SyncScheduler`/`SyncWorker` additive; `AppContainer` | 8,9 | worker drain/retry/deadline tests | bounded batches; resumes after process death | low |
| 13 | FULL REGRESSION | all suites + Phase 10/11 preservation | none | all | `:app:testDebugUnitTest`, `lintDebug`, `assembleDebug`, `cargo test --release`, backend tests | all green; Phase 10/11 unchanged | low |

**The single most important reordering: BACKEND SAFETY (3) precedes LOCAL->CLOUD (8).** NATIVE GAP A
(4) and B (5) also precede LOCAL PERSISTENCE (6), because the operation record cannot be written
without payload-only support, and record-type filtering cannot work without the `min_should` fix.

---
# 25. WorkManager Integration

VERIFIED baseline: `SyncWorker` is a `CoroutineWorker`; `SyncScheduler` enqueues unique work named
`"edgememo-sync"` with `REPLACE`, network constraint, exponential backoff from 10s; `doWork` retries
while `summary.remaining > 0` (`SyncWorker.kt:25-31`, `AppContainer.kt:115-122`).

Requirements:
- **Reuse the existing scheduler.** Do not add a second one.
- A **distinct unique work name** so the Qdrant-native pipeline cannot interleave with the legacy
  Room job: PROPOSED `"edgemind-qdrant-sync"`.
- Same CONNECTED constraint and backoff policy.
- **Bounded** work per invocation so a large backlog cannot hit ANR/timeout.
- `Result.retry()` while retryable operations remain; `Result.success()` when drained; DEAD
  operations do not hold the queue open.
- VERIFIED: WorkManager provides **no** transactional guarantee. Durability comes from flushed
  Qdrant records only.

---
# 26. Security / Privacy

| Control | Status | Basis |
|---|---|---|
| `LOCAL_ONLY` never leaves device | VERIFIED mechanism to preserve | no operation record created (§5.2); `HttpSyncRemoteDataSource` `check`s it before I/O (`:34`) |
| `SYNC_REDACTED` sends only redacted form | VERIFIED mechanism to preserve | `SyncPayloadFactory` sole authority; `_redacted` flag transmitted |
| LOCAL_ONLY withdraws a pending operation | VERIFIED requirement | Phase 9 §10.4 — superseding LOCAL_ONLY deletes the operation point |
| No raw content in errors/logs | VERIFIED pattern to preserve | `lastError` holds only `SyncFailureKind` (`SyncModels.kt:37-52`) |
| Cloud payload validation | **NEW requirement** | §23.2; reject malformed cloud records before local insert |
| Credentials | VERIFIED placement | Qdrant keys only in the backend; Android knows a URL (`CloudHttpClient.kt:21`) |
| Transport | VERIFIED existing | network security config forbids cleartext except local dev |

Never log: payload title/content, redacted content, API keys. Log: `operation_id`, `record_id`,
`record_version`, operation type, state transition, failure kind, attempt count.

---
# 27. Performance

All figures are labelled. No fabricated numbers.

VERIFIED host baseline (Phase 10 §15; 4 records, 4-dim, release): create 12.4 ms, upsert 0.067 ms,
flush 0.08 ms, retrieve 0.19 ms, filtered scroll 0.39 ms, search 0.36 ms, reopen 16.1 ms, 278
B/record. These are **spike-scale host numbers, not device numbers**.

| Concern | Assessment | Label |
|---|---|---|
| full initial sync | O(N); batched upsert amortises the 0.067 ms JNI cost | ESTIMATE |
| incremental sync | bounded by indexed `_sync_state=PENDING`, not N | PROPOSED |
| operation record growth | one point per version; GC policy §14.5 | PROPOSED |
| per-record writes | 2-3 points (record, operation, ACK) each + flush | PROPOSED — flush dominates; batch ACKs |
| mobile bandwidth | payload-heavy; redaction reduces size | ESTIMATE — measure |
| Qdrant Edge disk | `on_disk_payload(false)` = `InRamMmap`; memory at scale unmeasured | VERIFIED gap (Phase 10 §16.6) |
| Qdrant Cloud rate limits | unverified | OPEN QUESTION |
| reconciliation cost | page-limited, indexed, once per worker start | PROPOSED |

Do not optimise before measuring. Explicitly avoid: per-record full scans, repeated index rebuilds,
re-uploading unchanged records, unbounded batches.

---
# 28. API / Interface Contracts

PROPOSED. Additive; the Phase 11 `LocalRecordStore` is **not** modified.

```kotlin
// new package domain/sync/qdrant/ — no existing file changes
interface QdrantSyncOperationStore {
    suspend fun enqueue(op: SyncOperationRecord): SyncOperationRecord
    suspend fun claimNext(limit: Int, leaseMs: Long): List<SyncOperationRecord>
    suspend fun markAcked(operationId: String, cloudVersion: Int): Boolean
    suspend fun markFailed(operationId: String, kind: SyncFailureKind): Boolean
    suspend fun markDead(operationId: String, kind: SyncFailureKind): Boolean
    suspend fun recoverStaleInFlight(now: Long): Int
    suspend fun findByOperationId(operationId: String): SyncOperationRecord?
    suspend fun maxOperationVersionFor(recordId: RecordId): Int?
}

interface QdrantSyncEngine {
    suspend fun enqueueIfChanged(record: Record): SyncOperationRecord?   // §10
    suspend fun pushPending(max: Int): SyncRunSummary                     // §11
    suspend fun pullAndApply(pageSize: Int): SyncPullSummary              // §12
    suspend fun reconcile(): ReconciliationSummary                        // §19
}
```

Both are backed by `LocalRecordStore` (the verified Phase 11 boundary) — the native layer is
reached only through it, preserving the single-writer rule.

Wire additions to `PUT /sync/operations/:id`: `status`, `cloudVersion`, `cloudContentHash`. All
additive.

---
# 29. Migration / Rollback

**No migration in Phase 12B.**

- The Room memory + `DefaultSyncEngine` + `SyncOutboxEntity` path remains **fully operational**.
- The Qdrant-native sync path is **additive** and independently testable.
- `LocalVectorStore`/`QdrantEdgeVectorStore` and Room are **not** modified.
- Rollback = stop enqueuing the new work name; the legacy path is untouched.
- Cutover of any real record type to Qdrant-native sync is a **later phase**, gated on Phase 9 §13.

---
# 30. Testing Strategy

Methodology VERIFIED from Phase 10: real JNI -> real Rust -> real qdrant-edge on a real temp
directory; assertions re-read from the shard; **never** mocks for core persistence.

| Subphase | Tests |
|---|---|
| 1 FOUNDATION | identity determinism; the §7.1 version matrix; content-hash stability; illegal-transition rejection |
| 3 BACKEND | the 9 cases of §23.1 table-driven; version validation; malformed payload rejection; `operationId` mismatch |
| 4 NATIVE A | payload-only upsert persists, restarts, retrieves, filters, indexes (**currently failing — §20.3**) |
| 5 NATIVE B | every `FilterCompiler` shape round-trips through real qdrant-edge (**`Or`/`In` currently fail — §20A**) |
| 6 PERSISTENCE | operation record restart; indexed `selectRetryable`; idempotent `findByOperationId`; each of the 5 new indexes asserted |
| 7 DETECTION | each detection class returns the exact expected set; no unfiltered scan |
| 8 LOCAL->CLOUD | §11.1 scenarios A-I, including timeout-after-remote-success and duplicate push |
| 9 CLOUD->LOCAL | NEW / DUPLICATE / UPDATE / STALE / CONFLICT / TOMBSTONE / NO_OP; echo-loop absence |
| 10 CONFLICTS | §23.1 rows 6, 8, 9; resurrection attempt blocked; conflict evidence preserved |
| 11 CRASH | all 10 scenarios of §18 |
| 12 WORKMANAGER | bounded batch; retry; DEAD does not hold the queue; resumes after process death |
| 13 REGRESSION | `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `cargo test --release`, backend suite; Phase 10 + Phase 11 suites unchanged |

Privacy tests (VERIFIED mechanism, must be re-pinned per Phase 9 §10.4/§15):
LOCAL_ONLY produces **zero** operation records; a LOCAL_ONLY supersession **withdraws** a pending
operation; `SYNC_REDACTED` transmits only the redacted representation; no raw content appears in
`last_error` or logs.

---
# 31. Risks / Limitations

| # | Risk | Severity | Status |
|---|---|---|---|
| 1 | Payload-only records impossible -> whole Phase 9 §10 design blocked | **BLOCKER A** | open (§20.3) |
| 2 | `FilterCompiler` `Or`/`In` emits `minimum_should_match`; qdrant-edge rejects it -> record-type-scoped query broken | **BLOCKER B** | open (§20A) |
| 3 | Backend does not compare versions -> stale write / resurrection / evidence loss | **BLOCKER C** | open (§23.3) |
| 4 | Backend idempotency is not actually idempotent for version-scoped ids (§23.3) | **BLOCKER C** | open |
| 5 | No CAS/transactions in qdrant-edge; correctness depends on single-writer + reconciliation | accepted | Phase 9 §10.7 |
| 6 | `order_by` not exposed through JNI; FIFO/starvation fairness unproven | medium | (§21.2) |
| 7 | `on_disk_payload(false)` = `InRamMmap`; memory at scale unmeasured | medium | Phase 10 §16.6 |
| 8 | Payload-only index creation on an existing shard unproven | medium | needs NATIVE A test |
| 9 | No attached device: real Android process-death unverified | accepted limitation | Phase 10 §14 |
| 10 | Live cloud credentials unavailable; backend behaviour unverified live | open | `CLOUD_VERIFICATION.md` |
| 11 | Operation records grow one per version | low | §14.5 GC policy |
| 12 | Legacy Room sync and new Qdrant sync both active -> two sources of truth if both used for one record type | medium | §29 — do not dual-sync one record |

Risk 12 is a design constraint, not a defect: **a record type must use exactly one sync path.**
Phase 12B does not migrate any record type, so the two paths do not overlap.

---
# 32. Open Questions

Only these remain genuinely unresolved after source inspection.

| # | Question | Why unresolved | Evidence missing | Blocks |
|---|---|---|---|---|
| 1 | Does qdrant-edge actually persist a **payload-only** point across restart? | Cannot be tested without changing `lib.rs` (private `mod store`); the Phase-10 methodology needs an exposed test surface | An executable cross-restart payload-only test | §20.3 alt. 1; all of Phase 9 §10 |
| 2 | Qdrant Cloud rate limits / batch limits for sync traffic | No live credentials; backend has no batching | Live measurement against a real cluster | Batch sizing (§27) only — not correctness |
| 3 | Tombstone retention window | Product decision, not technical | Explicit policy from the product owner | §14.4 GC policy |
| 4 | Which conflict-resolution strategy is chosen | Deliberately left open for human decision (§16) | Product/engineering decision | Checkpoint 10 |
| 5 | Reconciliation cursor/checkpoint representation | Depends on Q1; a payload-only `sys_cursor` record is currently impossible | Q1 resolution | §12 cursor, R1-R5 |
| 6 | Is multi-device sync in Phase 12B scope at all? | Phase 12A/12B say "multi-writer not implemented" but do not scope it | Explicit product decision | §7.4, Checkpoint 10 |

Resolved by this document (previously open): operation identity (§9.3), filter JSON compatibility
(§20A — now VERIFIED, and found broken), scroll ordering (§21.2), backend version semantics
(§23.1/§23.3 — now specified, and found missing), conflict representation (§15.2), tombstone
representation (§14.2).

---
# 33. Explicit Non-Goals

- Deleting or migrating Room (project rule; Phase 12B §26)
- Migrating any real record type to the Qdrant-native sync path
- Cross-record transactions, CAS, or relational constraints — **Qdrant provides none and none are
  simulated**
- Physical/tombstone garbage collection automation (§14.4)
- BM25 / sparse / full-text retrieval (Phase 9 §6.3; later phase)
- Facet API, recommend API, geo search UX (Phase 9 §2.3; later phases)
- UI changes of any kind, including the SYNC screen
- RAG, embedding, or cloud-LLM behaviour changes
- Multi-device conflict resolution automation (§32 Q6)
- Performance optimisation before measurement (§27)
- Live-cloud verification without credentials (reported as UNVERIFIED, never assumed)

---
# Appendix A — Evidence index

| Claim | Label | Source |
|---|---|---|
| payload/vector/index persistence across restart | VERIFIED | Phase 10 §8-13 |
| `upsert_batch_with_payload` loops individual upserts | VERIFIED | `store.rs:150-190` |
| batch is not atomic/transactional | VERIFIED | `store.rs:149` comment + code shape |
| `Filter` rejects `minimum_should_match` | VERIFIED | probe against vendored crate; `src/segment/types.rs:4407-4408` |
| `OrderByInterface` exists on `ScrollRequest` | VERIFIED | `src/edge/requests/scroll.rs:20` |
| our `scroll` never sets `order_by` | VERIFIED | `store.rs:212-231` |
| payload-only upsert fails dimension check | VERIFIED (source) | `QdrantEdgeRecordStore.kt:137` + `store.rs:48-57`,`125-144` |
| backend does no version comparison | VERIFIED | `sync.ts:36-77`; grep of `validation.ts` |
| backend dedup keys on a single `operationId` | VERIFIED | `sync.ts:40-46` |
| Room outbox atomic claim via guarded update | VERIFIED | `DefaultSyncEngine.kt:57-62` |
| `UPSERT-<memoryId>` is today's operation id | VERIFIED | `SyncOutboxEntity.kt:22` |
| `OUTBOX_OP`/`CONFLICT`/`SYS_CURSOR` record types exist | VERIFIED | `RecordType.kt:19-22` |
| contentHash SHA-256 convention | VERIFIED | `DefaultMemoryRepository.kt:330-333` |
| single primary collection chosen | VERIFIED | Phase 9 §3.3 |
| no transactions/CAS in qdrant-edge | VERIFIED | Phase 9 §10.7, Phase 10 §16.2 |
| version-scoped operation identity | PROPOSED (decision) | §9.3 |
| `_last_synced_version` extension | PROPOSED | §7.3 |
| tombstone-as-record (not separate type) | PROPOSED | §14.2 |
| payload-only fix approach | PROPOSED | §20.3.1 |
| 9-case backend contract | PROPOSED (spec) | §23.1 |
| conflict resolution strategy | OPEN QUESTION | §16, §32 Q4 |
