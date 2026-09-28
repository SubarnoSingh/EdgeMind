# PHASE 10 — Qdrant Edge Persistence Spike

> EdgeMind — Offline Industrial Intelligence
> Prove: **Android Kotlin → JNI → Rust → qdrant-edge 0.8.0 → persistent payload + vector →
> process termination → new shard instance → payload/vector recovery.**

**Status:** COMPLETE — **DECISION: PASS**
**Date:** 2026-09-28
**Baseline:** commit `69377a9` (unchanged); `hackathon-prototype` tag untouched.
**Predecessor:** `docs/PHASE_9_QDRANT_ONLY_ARCHITECTURE.md` (authoritative architecture).

---

## 1. Objective

Establish, through the **real JNI/native path only**, the persistence primitive Phase 11 needs:

1. A record (UUID point id + dense vector + JSON payload) written through JNI survives
   Android-style process termination and is recovered by a **new** Qdrant Edge instance against
   the same path — payload, vector, and indexes.
2. Payload filtering (`severity`, `status`, `machine_id`, combined must-filters) works and
   returns exact sets.
3. Update-in-place replaces the record (stale version is gone).
4. Delete persists.
5. Payload indexes survive restart **without recreation**.

No Room, no SQLite, no Postgres, no Kotlin-side mirror state. Every assertion in every test is
re-read from the Qdrant Edge shard.

**Not in scope (by design):** Room migration, memory migration, sync/chat/UI redesign, final
public API design, tombstones, payload-only points, batched upsert JNI.

---

## 2. Actual qdrant-edge 0.8.0 APIs used

All names verified against the vendored crate source
(`~/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/qdrant-edge-0.8.0/`) — nothing invented.

| Capability | API used |
|---|---|
| Upsert point with payload | `PointStruct::new(id: ExtendedPointId, Vectors::new_named([("semantic", vec)]), serde_json::Value)` → `UpdateOperation::PointOperation(PointOperations::UpsertPoints(PointInsertOperations::PointsList(vec![point])))` → `EdgeShard::update(op)` |
| Retrieve by ids | `EdgeShard::retrieve(RetrieveRequest::new(Vec<ExtendedPointId>))` → `Vec<RecordInternal>` (fields `id`, `payload: Option<Payload>`, `vector`, `order_value`) |
| Scroll with filter | `ScrollRequestBuilder::new().limit(n).with_payload(WithPayloadInterface::Bool(true)).filter(Filter).offset(PointIdType)` → `EdgeShard::scroll` → `(Vec<RecordInternal>, Option<PointIdType>)` |
| Count with filter | `CountRequestBuilder::new().exact(bool).filter(Filter)` → `EdgeShard::count` |
| Create payload index | `UpdateOperation::FieldIndexOperation(FieldIndexOperations::CreateIndex(CreateIndex { field_name: JsonPath, field_schema: Some(PayloadFieldSchema::FieldType(PayloadSchemaType::Keyword)) }))` → `EdgeShard::update(op)` |
| Filtered dense search | `QueryRequestBuilder::new(limit).query(ScoringQuery::Vector(QueryEnum::Nearest(NamedQuery::new(VectorInternal::Dense(q), "semantic")))).with_payload(Bool(true)).filter(Filter)` → `EdgeShard::query` → `Vec<ScoredPoint>` (with `payload`) |
| Filter parsing | `serde_json::from_str::<Filter>(json)` — canonical Qdrant filter JSON, e.g. `{"must":[{"key":"severity","match":{"value":"high"}}]}`. `Filter` derives `Deserialize` (`deny_unknown_fields`); `Condition` has a manual `Deserialize` handling `{"key":…,"match":…}` as `FieldCondition`; `Match::Value(MatchValue{ value: ValueVariants })` is untagged (`"high"` → `String`, `1` → `Integer`, `true` → `Bool`) |
| Flush | `EdgeShard::flush()` — existing function, unchanged |
| Point ids | `ExtendedPointId::from_str` — u64 or UUID only (all spike ids are UUIDs) |

**Index schema note:** `CreateIndex` **requires** a schema (`None` → `TypeInferenceError`).
The spike always passes `PayloadFieldSchema::FieldType(PayloadSchemaType::Keyword)`.

---

## 3. JNI changes (`rust/edgememo_qdrant/src/jni.rs`)

Six new native functions added; all eight existing ones untouched (existing `nativeFlush`
behavior preserved verbatim). JSON strings cross the boundary; errors surface as
`QdrantNativeException` (existing mechanism).

| JNI function | Signature | Semantics |
|---|---|---|
| `nativeUpsertWithPayload` | `(J String [F String)V` | upsert point with vector + JSON-object payload |
| `nativeRetrieve` | `(J String)String` | ids as JSON array `["uuid",…]` → JSON array `[{"id":…,"payload":{…}}]` |
| `nativeScroll` | `(J String I String)String` | optional filter JSON, limit, optional offset point-id → JSON array of records |
| `nativeCountFiltered` | `(J String Z)J` | optional filter JSON, exact flag → count |
| `nativeCreatePayloadIndex` | `(J String String)V` | field + schema type ("keyword" etc.) |
| `nativeSearchWithFilter` | `(J [F I String)String` | vector + limit + optional filter → JSON array `[{"id":…,"score":…,"payload":{…}}]` |

Conventions: empty/missing filter JSON (`null`, `""`, `"{}"`) means "no filter"; empty offset
means "from the beginning"; missing point ids simply absent from retrieve results (qdrant-edge
semantics, not an error).

## 4. Rust changes (`rust/edgememo_qdrant/src/store.rs`, `error.rs`)

New store functions (pure Rust, unit-testable without JNI):

```text
upsert_with_payload(shard, id, vector, payload)   // validates payload is a JSON object
retrieve(shard, ids) -> Vec<Record>
scroll(shard, filter_json?, limit, offset?) -> Vec<Record>
count_filtered(shard, filter_json?, exact) -> usize
create_payload_index(shard, field, schema)
search_with_filter(shard, query, limit, filter_json?) -> Vec<ScoredPoint>
```

New `EdgeError` variants: `InvalidPayload`, `InvalidFilter`, `InvalidIndex`. Six new Rust unit
tests (all passing) cover roundtrip, reopen persistence, exact filter sets, index reopen, update
overwrite, delete — against a real `EdgeShard` on a temp directory.

## 5. Kotlin changes

**Production Kotlin:** only `NativeBridge.kt` gained the six `external fun` declarations.
`QdrantEdgeVectorStore`, `LocalVectorStore`, Room, repositories, sync, chat, UI — all untouched.

**Test-side only** (`app/src/test/java/com/example/EdgeMemo/phase10/`):

- `SpikeStore.kt` — minimal test bridge managing the raw JNI handle + JSON round-trips
  (org.json). No production classes involved.
- `QdrantRecordPersistenceTest.kt` — 8 tests + child-process JVM phases (see §7).

## 6. Payload format

Canonical spike record (278 bytes as serialized):

```json
{
  "record_type": "maintenance_record",
  "id": "MR-001",
  "version": 1,
  "created_at": 1700000000000,
  "updated_at": 1700000000000,
  "content": "Hydraulic pump overheating on LINE-A",
  "machine_id": "M-042",
  "severity": "high",
  "status": "open",
  "plant": "Jamshedpur-02",
  "line": "LINE-A",
  "zone": "PRESS-04"
}
```

Point id = UUID (e.g. `00000000-0000-0000-0000-000000000001`); vector = deterministic 4-dim
unit vectors; dimension is the shard's configured dimension (existing `check_dimension` guard).

Test record set (Task 11):

| point uuid suffix | record | machine | severity | status | plant | line |
|---|---|---|---|---|---|---|
| …01 | MR-001 | M-042 | high | open | Jamshedpur-02 | LINE-A |
| …02 | MR-002 | M-042 | low | resolved | Jamshedpur-02 | LINE-B |
| …03 | MR-003 | M-100 | high | resolved | Jamshedpur-03 | LINE-A |
| …04 | MR-004 | M-200 | critical | open | Jamshedpur-02 | LINE-A |

## 7. Indexes

Six keyword indexes created at setup: `record_type`, `machine_id`, `severity`, `status`,
`plant`, `line`. Created **before** inserts in the fixture (creation over existing points was
exercised in the Rust update test too — both orders work).

Filter matrix verified with exact expected sets (Task 3 + 11):

| Filter | Result |
|---|---|
| `severity == "high"` | MR-001, MR-003 |
| `status == "resolved"` | MR-002, MR-003 |
| `machine_id == "M-042"` | MR-001, MR-002 |
| `record_type == maintenance_record AND severity == high AND line == LINE-A` | MR-001, MR-003 |
| above AND `machine_id == M-042` | MR-001 (unique) |
| count(`severity == high`) | 2 |
| count(all) | 4 |
| dense search scoped to `severity == critical` | MR-004 (vector + payload) |

Note: with the Task-11 record set, the 3-condition combined filter legitimately matches
MR-001 **and** MR-003 (both are high-severity LINE-A records); adding `machine_id` narrows to
MR-001. Tests assert both.

## 8. Persistence behavior

**VERIFIED — payloads, vectors, indexes and collection metadata all persist across restart.**

- In-process: create → indexes → 4 upserts → flush → close → reopen → retrieve (payload
  field-by-field verified), search (score + payload verified), filtered scroll, count.
- Cross-process crash-style: a **child JVM** creates the shard, writes indexes + records,
  flushes, and **exits without `nativeClose`/`Drop`** (the closest reproducible stand-in for
  Android process death on a host with no device attached). A **different process** (the test
  JVM) opens the same directory and re-reads payloads, vectors and filters.
- Two-separate-process: a **second child JVM** independently opens the same path and verifies
  retrieve + filters + search, printing `VERIFIED count=4 retrieve_ok=true high_ok=true
  combined_ok=true search_top=true`.

**Observed on-disk layout** (qdrant-edge 0.8.0, dumped during verification):

```text
<shard>/
  edge_config.json                                  ← collection metadata
  segments/<segment-uuid>/
    segment.json, version.info, mutable_id_tracker.*
    payload_storage/
      config.json, page_0.dat, tracker.dat, …       ← PAYLOADS (mmap-backed, on disk)
    payload_index/
      config.json                                   ← index schema
      <hash>-severity-map/ {config.json, page_0.dat, …}   ← per-field keyword index
      <hash>-record_t-map/ {…}
    vector_storage-semantic/
      vectors/chunk_0.mmap                          ← dense vectors (mmap)
      config.json
```

## 9. Flush behavior

From the crate source (`edge/edge_shard/mod.rs`): `EdgeShard::flush()` runs
`wal.lock().flush()` + `segments.read().flush_all(FlushMode::Sync, true)` — i.e. it
synchronously persists the WAL **and all segments** (dense vector mmaps, payload mmaps, payload
indexes, segment state) to disk, blocking until in-flight updates finish. `Drop` also flushes,
but Android kills run neither — the existing `nativeFlush` after every write batch is therefore
**required** and remains the durability mechanism (unchanged from the Phase-1-era fix).

Answers (Task 8):

- what flush guarantees: WAL + segments persisted synchronously (vectors ✓, payloads ✓,
  indexes ✓, segment state ✓ — empirically confirmed by the restart tests)
- collection metadata: `edge_config.json` written at create time; reopened via
  `EdgeShard::load` (reconstructs the collection from that file + segments)
- indexes: persisted with segments; **no recreation needed after restart** (verified)

## 10. Restart behavior

`open` on the same path loads `edge_config.json` + segments (vectors, payloads, index schema)
from disk. Retrieval, search and filtering after reopen were verified identical to pre-restart
state. Fresh-vs-existing is still decided by `edge_config.json` presence (unchanged
`ensureReady` logic in production code).

## 11. Update behavior

Upsert with the same point id **replaces** the record (vector + payload) — one point, no ghost.
Verified across two restarts:

- v1 (`severity=high, status=open, version=1`) → flush → close → reopen
- upsert v2 (`severity=critical, status=open, version=2, updated_at=1_700_000_600_000`) →
  flush → close → reopen
- after: `version==2`, `severity==critical`, `status==open`, `updated_at` == new value,
  `machine_id`/`line` intact
- stale v1 state gone: `version==1` filter → empty; `severity==high` filter → empty;
  `severity==critical` → exactly MR-001; count == 1

## 12. Delete behavior

`PointOperations::DeletePoints` + flush, verified across two restarts: retrieve → empty,
count → 0, scroll → empty. No tombstone introduced (deletion semantics are a later phase, per
instructions).

## 13. Index persistence behavior

**VERIFIED — indexes survive restart and need no recreation.** After reopen, filters run with
the same exact result sets with zero `CreateIndex` calls, and the on-disk schema
(`segments/*/payload_index/config.json` + per-field index directories) is asserted present.
(Filters would also work without an index — brute-force scan — but the persisted schema files
plus behavior prove the index itself survives; this matches Phase 9's assumption.)

## 14. Process-death test status

| Level | Implemented | Notes |
|---|---|---|
| A. Object destruction | yes (close/reopen in one process) | NOT labeled process death |
| B. Native store recreation | yes (new `EdgeShard` from same path) | part of all restart tests |
| C. Actual Android process termination | **NOT VERIFIED** | no device/emulator attached (`adb devices` empty). The strongest host-reproducible test is a **separate OS process** that writes and exits **without** close/Drop, followed by verification from other processes — genuine cross-process persistence, but not `am force-stop` on Android. Explicit limitation; device verification remains a follow-up (same known limitation as Phases 1–9). |

## 15. Performance observations (host JVM baseline — NOT device numbers)

4 records, 512→4-dim shard, host Linux x86-64, release build:

```text
create        12.39 ms     (includes index-less shard creation)
upsert avg     0.067 ms    (single-record JNI upsert)
flush          0.08 ms
retrieve       0.19 ms
filter scroll  0.39 ms
search         0.36 ms
reopen        16.07 ms
payload        278 bytes / record
```

All latencies trivial at spike scale. Known measurement gaps (later phases): batch upserts,
larger record sets, `order_by` scrolls, exact-vs-approximate counts, on-device timings, memory
(RSS) with payload populate-on-load (`on_disk_payload(false)` = `InRamMmap`).

## 16. Known limitations

1. **No on-device verification** (no attached Android device — process-death C-level test
   deferred). The cross-process JVM test is the strongest reproducible proof available here.
2. **No transactions / no CAS** (qdrant-edge has neither) — unchanged Phase 9 position; the
   spike uses single-point operations only.
3. **Payload-only points** (no vector) not yet exercised — Phase 9 Q1 stays open (not needed
   for this spike).
4. **Concurrency:** within one process the shard serializes writes internally (WAL mutex +
   segment update lock; `flush` blocks on both) and reads use segment read locks — safe for the
   Phase 9 single-writer + concurrent-readers model. There is **no cross-process file lock**
   (verified: no fslock/DirLock in the crate); multi-process access to one shard directory is
   NOT supported — acceptable because Android apps are single-process and WorkManager runs a
   single unique sync job.
5. **Batch upsert** still loops per record over JNI (0.067 ms/record at spike scale) — fine
   now; a batch primitive is a Phase 12/16 optimization, not a blocker.
6. **`on_disk_payload`** unchanged (`false` → `InRamMmap`, populate-on-load); memory footprint
   at scale unmeasured (Phase 9 Q3).
7. Spike-only APIs live in `NativeBridge` + test sources; **no final application API was
   designed** (Phase 11+ per Phase 9 §14).

## 17. Decision

**PASS.**

Evidence:

| Check | Result |
|---|---|
| Rust unit tests (`cargo test --release`) | **6/6 pass** |
| Kotlin spike tests through real JNI (Robolectric + child JVM processes) | **8/8 pass** |
| Full Android suite (`:app:testDebugUnitTest`) | **256 tests, 0 failures, 0 errors** (248 pre-existing + 8 new) |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` (rebuilt arm64-v8a + x86_64 `.so`, merged into APK) | BUILD SUCCESSFUL |
| Payload persists across restart (same process, separate process, crash-style) | VERIFIED |
| Vector persists across restart | VERIFIED |
| Filters return exact expected sets | VERIFIED |
| Update replaces record; stale version gone | VERIFIED |
| Delete persists | VERIFIED |
| Payload index survives restart without recreation | VERIFIED |
| Room deleted? | **NO** — untouched |
| Existing behavior changed? | NO — all pre-existing tests still green; only additive native functions + test code |
| `hackathon-prototype` tag | untouched |

**Remaining risks:** on-device process-death verification (no device), payload-only points
unproven, no CAS/transactions (accepted per Phase 9), payload populate-on-load memory at scale.
None block Phase 11.
