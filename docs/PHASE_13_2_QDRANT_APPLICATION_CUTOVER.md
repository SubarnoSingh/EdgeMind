# Phase 13.2 — Qdrant-Native Application Data-Layer Cutover

## 1. Architecture before (Phase 13.1 audit, verified)

```text
UI → ViewModel → UseCase
  → DefaultMemoryRepository
      → Room MemoryDao   (authoritative metadata)
      → QdrantEdgeVectorStore "local_qdrant" (vectors only, no payload)
      → RoomSyncOutboxWriter → Room sync_outbox
          → DefaultSyncEngine → legacy SyncWorker ("edgememo-sync")
12B Qdrant-native stack (record store, detector, engine, worker):
    ORPHANED — shard "qdrant_sync_store" received no application writes.
```

## 2. Architecture after (this phase, verified by tests)

```text
UI → ViewModel → UseCase
  → QdrantRecordMemoryRepository (NEW, domain MemoryRepository impl)
      → LocalRecordStore / QdrantEdgeRecordStore
      → ONE application shard: filesDir/qdrant_sync_store
        (vector + payload + envelope in the same points; payload-only
         points for operations share the same collection)
  on every mutation:
      → QdrantSyncEngine.enqueueIfChanged  (frozen 12B.7 detector)
      → QdrantSyncOperationStore           (deterministic UPSERT:/TOMBSTONE:)
      → QdrantSyncWorker ("edgemind-qdrant-sync") → cloud

Room: NOT dual-written, NOT deleted. Its memory/outbox paths receive zero
application writes (proved: tests assert Room count==0 after CRUD). Retained
as frozen rollback + still read/written ONLY by not-yet-cut-over paths:
retrieval/RAG (13.3) and cloud-pull + conflicts (13.4).
```

## 3. Memory ↔ Record mapping (`data/repository/MemoryRecordMapper.kt`)

Deliberate, lossless. Rule: **content identity lives in the domain payload
(included in the frozen `CanonicalContentHash`); sync/policy/provenance lives
in the `_`-prefixed record envelope (excluded from the hash).**

| Memory field | Record placement | Notes |
|---|---|---|
| memoryId (UUID) | `Record.id` (`RecordId`, UUID-validated) | |
| title, content | payload `title`, `content` | same keys the 12B protocol fixture uses |
| type (MemoryType ×7) | payload `type` (authoritative, verbatim) + envelope `_record_type` via deterministic table | RecordType(16)≠MemoryType(7): NOTE→MEMORY, DOCUMENT→DOCUMENT, OBSERVATION→INSPECTION, PROCEDURE→PROCEDURE, REPAIR→MAINTENANCE_RECORD, EVENT→FAILURE, CLOUD_KNOWLEDGE→MEMORY(`_origin=CLOUD`). Reverse reads payload `type` first; envelope type is a filter axis only. Nothing discarded. |
| chunkId | payload `chunkId` | content-bearing (a chunk edit is a content edit) |
| sensitivity, importance | payload `sensitivity`, `importance` | content attributes: changing them re-syncs deliberately and honestly |
| source | envelope `_source` | |
| createdAt, updatedAt | envelope `_created_at`, `_updated_at` | hash-excluded |
| version | envelope `_version` | monotonic; repository bumps on update, `softDelete` bumps on tombstone |
| origin | envelope `_origin` | LOCAL/CLOUD/SYNCED names align across both enums |
| syncDecision | envelope `_sync_decision` | `core.model.SyncDecision` ≠ `core.record.SyncDecision` — two distinct enums, mapped by name explicitly |
| syncState | envelope `_sync_state` | LOCAL/PENDING/SYNCED/FAILED align by name |
| subjectKey, supersedes | envelope `_subject_key`, `_supersedes` | |
| contentHash | envelope `_content_hash` | NOW the **CanonicalContentHash** of the domain payload (was repo `SHA256("title\ncontent")`). Repository and detector stamp the identical value → one authority. |
| tombstone | envelope `_tombstone` | |
| tags, metadata | envelope `_tags`, `_metadata` | |
| policyReason, redactedTitle, redactedContent | envelope `_policy_reason`, `_redacted_title`, `_redacted_content` | policy stays explainable + redaction durable |
| authority | envelope `_authority` | |
| (none) | envelope `_last_synced_version/_operation_id/_content_hash` | §7.3 watermark is record-layer-only; `Memory` intentionally does not expose it |
| (none) | `Record.deletedAt` | survives on the tombstone for sync semantics; not part of the domain model |
| (none) | `Record.entityId` | reserved for future domain entities; application memories write null |
| (embedding) | `Record.vector` | embedding(title+content) at 512 dims, stored in the SAME point |

## 4. Shard strategy

* ONE authoritative application shard: `filesDir/qdrant_sync_store`
  (`QdrantEdgeRecordStore`, mixed vector + payload-only collection, 512-dim,
  indexes by `ensureIndexes()`, flushed per write).
* One process owner: `AppContainer` constructs exactly one store instance and
  shares it with `QdrantRecordMemoryRepository`, `QdrantSyncRuntime`
  (engine/detector/resolver/operations) and `QdrantSyncStatusReader`. The
  `Mutex`-serialized store + single `QdrantEdgeRecordStore` instance preserve
  the 12A §20 single-writer rule.
* `local_qdrant` (Phase-2 `QdrantEdgeVectorStore`) is **no longer written by
  any authoring path** — reclassified as the non-authoritative legacy
  retrieval index consumed only by the not-yet-cut-over `DefaultRetrievalService`
  / `DefaultCloudKnowledgeWriter` / `DefaultConflictResolver` until 13.3/13.4.
  It is a temporary compatibility adapter, not a second source of truth for
  application records (per 13.1 audit §12 option "adapt so the application no
  longer treats it as authoritative").

## 5. Repository design (`QdrantRecordMemoryRepository`)

Implements the unchanged domain `MemoryRepository` contract (UI/use-case
boundary stable). Preserved semantics: whitespace normalization, empty-input
rejection, policy evaluation at persistence time, user-choice preservation on
update (hard rules still win), tombstone/supersede exclusion from reads,
`list()` ORDER updatedAt DESC, `search()` = dense candidates ×4 then top-K,
returned copy marked PENDING exactly when an operation was enqueued.

Deliberate architectural changes (required by Phase-12 protocol):
* `delete` is a **tombstone** (`softDelete` + deterministic TOMBSTONE
  operation), never a physical delete; repeated delete is a no-op.
* content hash = `CanonicalContentHash` (see §3).
* `count()` counts active records (legacy counted tombstoned history rows).
* LOCAL_ONLY demotion (update trips a hard rule) withdraws claimable
  operations (detector) and re-stamps the record LOCAL (repository).

## 6. Mutation flow

```text
create/update → normalize → policy evaluate → build Memory (v+1 on update,
canonical hash) → embed → QdrantEdgeRecordStore.upsert (vector+payload+envelope,
flush) → recordStore.get(id)  ← MUST re-read: retrieve round-trips the vector
→ syncEngine.enqueueIfChanged(stored) → detector stamps _sync_state=PENDING +
contentHash and enqueues UPSERT:<uuid>:<version> (idempotent; LOCAL_ONLY
refused+withdrawn; CLOUD origin ignored) → returned Memory reflects state.
```

Batch (ingestion): embed all → `upsertBatch` (one flush) → per-record detect.
Crash windows remain covered by reconciliation R2/R4 (record PENDING without
operation → re-enqueued; tombstone without operation → propagated).

## 7. Sync flow

Application writes enter ONLY the Qdrant-native operation store. The legacy
Room outbox receives zero rows (proved). `onMemoriesChanged` and
`onAppForeground()` now enqueue the `QdrantSyncWorker`
(`"edgemind-qdrant-sync"`); `SyncScheduler.requestSync()` (legacy) has no
production caller anymore. Sync status (`SyncSummary` chip) reads real
operation-store state through `QdrantSyncStatusReader` — never Room. The same
record can never be pushed under both `UPSERT-<memoryId>` and
`UPSERT:<uuid>:<version>` because application mutations no longer enter Room.

## 8. Tombstone flow

Repository `delete` → `QdrantEdgeRecordStore.softDelete` (version+1,
`_tombstone=true`, sync-eligible previously-SYNCED → PENDING per 12B.13 fix)
→ `enqueueIfChanged(tombstoned)` → deterministic `TOMBSTONE:<uuid>:<version>`.
Resurrection rules, R4 propagation and restart safety are the existing
Phase-12 implementations (unchanged, still covered by their suites).

## 9. Policy handling

`DefaultPolicyEngine` runs at persistence time exactly as before (ordered
rules, explanation, redaction computation). The frozen detector enforces the
wire rules: LOCAL_ONLY → zero operations + withdrawal; SYNC → full domain
payload sanctioned; SYNC_REDACTED → only redacted title/content (+tags/
metadata), `RedactionUnavailable` refuses raw substitution. Tests prove all
three on the repository path, including that a raw protected identifier
never appears in an operation payload.

## 10. Legacy Room status

**FROZEN, not deleted, not active for application data.** Still constructed in
`AppContainer` (Room DB, `RoomSyncOutboxWriter`, `DefaultSyncEngine`, legacy
`SyncWorker`) so pre-cutover rows remain a rollback path. Zero production
callers feed or schedule them from application mutations. Room continues to
be the store for not-yet-migrated subsystems until their phases: conflicts +
cloud-pull cursor/rows (13.4), retrieval metadata reads (13.3).

## 11. Old vector store status

`QdrantEdgeVectorStore` (`local_qdrant`): no authoring writes after this
phase; read-only legacy index for `DefaultRetrievalService`/cloud writer/
conflict resolver until 13.3/13.4 retires it. NOT an application source of
truth. No compatibility adapter was added; simply demoted by rewiring.

## 12. Tests (21 + 1 wiring; all green)

`QdrantRecordMemoryRepositoryTest` (real JNI shard + real Room kept open to
prove emptiness + production policy engine/embedding/detector/engine):
create→retrieve; update→retrieve(+version); update-missing→MemoryNotFound;
list recency order; filtered type counts (indexed `_record_type`);
count active-only; semantic search from one store; reopen persistence of
payload+vector+state (restart); unified shard holds vector records AND
payload-only operation points; tombstone (identity `TOMBSTONE:<uuid>:2`,
durable soft record, invisible to get/list); repeated delete idempotent;
version progression with per-version op identities; canonical hash equality +
state-stamp hash stability; LOCAL_ONLY stored with zero operations; SYNC →
exactly one sanctioned UPSERT with real content; SYNC_REDACTED → flagged
redacted op, only redacted form leaves, raw identifier absent; hard-rule
demotion withdraws pending operation and re-stamps LOCAL; detection
idempotency (single operation for same version); **Room memories/outbox/
conflicts receive zero writes and all operations use UPSERT:/TOMBSTONE:
identities**; batch createAll phases + per-chunk operations; blank input
rejected without side effects.

`AppContainerQdrantSyncWiringTest` adds: authoring through the REAL
production container graph lands in Qdrant, produces a PENDING operation, and
the Room rollback DB stays empty.

Suite totals (13.2 final): Android **449/449**, Rust **10/10**, backend
**35/35**, lint **0 errors** (no new warnings), assembleDebug **SUCCESSFUL**.

## 13. Data migration of existing Room rows — DELIBERATELY NOT IMPLEMENTED

No shipped install base exists (everything after Phase 10 is uncommitted
development state) and no user data contract requires import. Silently
dropping Room was rejected; faking a migration was rejected. If any device
holds real Room rows before 13.4, a deterministic one-time Room→Record
importer (id-preserving, hash-recomputing, per-record detect) must be added
in Phase 13.4 BEFORE Room retirement. This is listed as a prerequisite, not a
gap hidden by deletion.

## 14. Known transitional consequences (staged cutover, no dual-write)

* Ask/RAG (`DefaultRetrievalService` still Room + `local_qdrant`) does not
  see records created after this cutover until Phase 13.3 — expected per the
  13.1 sequencing; Memory-tab authoring/list/search/delete/sync-status are
  fully consistent on Qdrant.
* Cloud pull (MemoryScreen pill) still lands in Room+`local_qdrant` until
  13.4, so pulled items are not yet visible to the Qdrant-native list. The
  12B engine's `pullAndApply` already writes into the application shard and
  is rewired there.
* No dual-write exists anywhere; the two graphs are disjoint by identity
  (Room rows vs Qdrant records), so the double-push hazard from 13.1 §11
  cannot occur.

## 15. Risks for Phase 13.3/13.4

* [HIGH] Until 13.3 lands, RAG sees stale data — 13.3 must cut over
  `RetrievalService` + `KeywordRetriever` + superseded/tombstone filters to
  the application shard and retire `local_qdrant`.
* [MEDIUM] 13.4 must move pull/conflicts/cursor onto the 12B engine AND
  provide the Room→Record data import decision (or explicit clean-slate).
* [LOW] `update()` preserves record-layer watermark fields by re-read
  (`get`) — a crash between upsert and detection is covered by R2/R4 as
  before.
