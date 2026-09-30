# Phase 13.4 — Final Qdrant Cutover + Legacy Retirement

## 1. Before (post-13.3)

```text
authoring  ✔ Qdrant (qdrant_sync_store)
retrieval  ✔ Qdrant (qdrant_sync_store)
pull       ✘ DefaultCloudKnowledgeIngestor → Room memories + cloud_pull_cursor
              + local_qdrant vectors + Room conflicts
conflicts  ✘ ConflictDao / RoomConflictRepository / DefaultConflictResolver
answers    ✘ DefaultCloudAnswerCache → Room writer + ConflictDao
sync local→cloud (legacy rows still scheduled) ✘ RoomSyncOutboxWriter /
              DefaultSyncEngine / SyncWorker ("edgememo-sync")
Room       constructed eagerly by AppContainer; local_qdrant constructed eagerly
```

## 2. Final (this phase)

```text
                     Application / UI
                            ↓
                    Domain use cases (unchanged contracts)
                            ↓
        QdrantRecordMemoryRepository · QdrantRecordRetrievalService
        QdrantCloudAnswerCache · QdrantConflictStore
        QdrantNativeCloudKnowledgeIngestor · QdrantSyncStatusReader
                            ↓
          ONE application shard: filesDir/qdrant_sync_store
              (records+vectors · operations · sys_cursor · conflicts)
                            ↓
        QdrantChangeDetector → QdrantSyncOperationStore
        → DefaultQdrantSyncEngine { reconcile | push | pull | resolve }
        → QdrantSyncWorker ("edgemind-qdrant-sync", the ONLY worker)
                            ↓
              Cloud (HttpQdrantSyncRemote / HttpCloudKnowledge…)

RAG consumes the Qdrant retrieval → ExtractiveLLMService → cloud escalation
(unchanged, local-first).
```

Room: not constructed by `AppContainer`. Only opened by `RoomRecordImporter`
(conditional, one-time) and by legacy/rollback tests. `local_qdrant` /
`QdrantEdgeVectorStore`: no active construction — dead in the production
graph; class retained for rollback/tests only.

## 3. Cloud pull cutover

`PullCloudKnowledgeUseCase` unchanged; its `CloudKnowledgeIngestor` is now
`QdrantNativeCloudKnowledgeIngestor`, a thin adapter over the frozen 12B.9
`DefaultQdrantSyncEngine.pullAndApply`. One §12 pipeline total: validation
(UUID/version/64-hex-hash) → classification → idempotent apply → conflict
recording → tombstones → echo immunity. Pulled records are written into the
application shard and are **immediately retrievable and RAG-answerable**
(proven e2e). One engine addition: an optional `cloudEmbedding` hook so NEW
cloud items receive the same deterministic 512-d FeatureHashing vector the
repository writes (vectors are excluded from content identity, so sync/echo
semantics are untouched; an embedding failure degrades to payload-only
without failing sync).

## 4. Cursor

Active cursor is the 12B `sys_cursor` payload-only point (LOCAL_ONLY,
deterministic id `edgemind:cursor:knowledge`), advanced strictly AFTER the
page is applied. Verified: persists across reopen; resume starts from it;
crash-before-cursor replays idempotently (DUPLICATE); cursor never enters
application memory scope (type-scoped retrieval). The Room `cloud_pull_cursor`
table is inactive.

## 5. Conflict cutover

`QdrantConflictStore` implements the domain `ConflictRepository` AND
`ConflictResolver` over the 12B conflict points +
`QdrantConflictResolver`/`QdrantConflictRecorder`. All 12B.9/12B.10
invariants carry over unchanged (deterministic two-sided evidence, immutable
resolved records, frozen-intent crash protocol, strictly-newer resolution
versions, deterministic follow-up operations, tombstone/resurrection rules,
authority auto-pass in the worker). The UI use cases
(`ListConflicts`/`CountUnresolved`/`ResolveConflict`) are rewired to it;
domain models untouched. Room `conflicts` table inactive.

## 6. Answer cache

`QdrantCloudAnswerCache` keeps the Phase 7/8 decision semantics verbatim
(subject-key derivation, classifier, NEW-only-save, DUPLICATE no-op,
divergence refusal + durable conflict, blank/oversize validation, saved
memory returned) but writes through the engine's SAME single-item pipeline
(`applyCloudItem`) into the shard. Saved answers are immediately
retrievable; CLOUD origin + watermark ⇒ zero echo operations.

## 7. Legacy Room sync retirement

`AppContainer` no longer constructs `EdgeMindDatabase`, `RoomSyncOutboxWriter`,
`DefaultSyncEngine`, `HttpSyncRemoteDataSource`, `QdrantEdgeVectorStore`,
the Room ingestor/writer/cursor/answer-cache stack or the Room conflict graph.
`requestSync()` has zero production callers; `SyncWorker`'s container fallback
was removed (it honestly fails without an injected rollback engine). Only
ONE sync worker can ever run: `QdrantSyncWorker` / `"edgemind-qdrant-sync"`.
The same record can never carry both identities — `UPSERT-<memoryId>` cannot
be minted because no application path writes the Room outbox.

## 8. Room status & 9. Migration decision

Room schema/DAOs/legacy classes: **present, unwired, not deleted** —
rollback + importer + legacy test surface. Migration decision (13.1 §9 /
13.2 §13 follow-up): **a real one-time importer IS implemented** —
`RoomRecordImporter`:

* no-ops instantly when no `edge-memory.db` exists (fresh installs never
  touch Room or the shard for migration);
* completion marker = `SYS_SETTING` point `edgemind:sys:room-import-v1`;
* idempotent twice over (marker + per-record existence) and crash-safe
  (marker written only after full pass; rerun completes without duplicates
  or second operations);
* preserves ids, content, metadata, tags, policy decision/redaction/
  explanation, origin, authority, subjectKey, supersedes, version,
  tombstones, timestamps; restamps `CanonicalContentHash`;
* legacy `SYNCED` rows receive the §7.3 watermark ⇒ no re-push; legacy
  `PENDING` rows are restated as unsynced and re-minted through the FROZEN
  detector as `UPSERT:<uuid>:<version>` (the frozen Room outbox's old
  identity is retired with it); LOCAL_ONLY stays operation-free; CLOUD
  origin never echoes;
* non-UUID ids are counted `skippedInvalid` and REMAIN in Room — never
  silently dropped;
* imports never delete Room rows/tables/files;
* failures are recorded on the container (`legacyImportError`) and retried
  next start; produced sync work triggers the authoritative worker.

Invoked from `EdgeMindApplication.onCreate` off the main thread.

## 10. Runtime/shard lifecycle

One `QdrantEdgeRecordStore` instance per process owns the shard; repository,
retrieval, engine/detector, conflict store, answer cache, importer and worker
all share it (single-writer, `Mutex`-serialized, flush-on-write). No code
path opens a second application shard anymore. Reopen/process-recreation is
covered by the 12B persistence suite plus the new pull/cursor reopen and
importer tests.

## 11. Offline · 12. Policy · 13. Crash recovery

Offline: authoring, listing, search, retrieval, RAG answering, conflicts
read/resolve, tombstones, sync-status all run with no network (asserted by
the container graph test with an unconfigured backend). Policy: LOCAL_ONLY /
SYNC / SYNC_REDACTED semantics unchanged; the 13.2 policy tests + new
importer/cloud tests re-verify no-raw-leak and withdrawal. Crash recovery:
the engine's R1–R5, cursor-window, conflict-window, resolution-window and
child-JVM process-death suites all pass UNCHANGED against the refactored
engine (20 pull-loop/apply lines were moved verbatim into `applyItem`).

## 14. Tests

New this phase (20): `QdrantCloudPullCutoverTest` (10 — pull→shard→retrieve→
RAG, cursor durability/advance/idempotent replay, reopen resume, honest
CloudUnavailable, malformed rejection, conflict evidence + keep-local,
keep-cloud convergence + single deterministic follow-up op + immutable
re-resolution, stale-resurrection guard, no-echo state counts);
`QdrantCloudAnswerCacheTest` (5); `RoomRecordImporterTest` (4 — fresh-install
no-touch, full preservation matrix, idempotency incl. crash-before-marker,
imported-live-in-retrieval); `AppContainerQdrantSyncWiringTest` +1 (full
production cycle with zero Room artifacts and exactly one Qdrant shard).

Verification totals: Android **486/486** (0 skipped, none deleted/weakened;
466 baseline + 20), Rust **10/10** (no Rust change), backend **35/35** (no
backend change), lint **0 errors** (29 pre-existing warnings, none on phase
files), `assembleDebug` **SUCCESSFUL**.

## 15. Remaining legacy references (classification)

| Reference | Class |
|---|---|
| `EdgeMindDatabase`, DAOs, entities, mappers, converters, migrations | LEGACY/ROLLBACK (runtime-opened only by importer) |
| `DefaultMemoryRepository`, `DefaultRetrievalService`, `KeywordRetriever` | LEGACY/ROLLBACK (unwired; TEST ONLY usage) |
| `RoomSyncOutboxWriter`, `DefaultSyncEngine`, `SyncWorker`, `SyncScheduler.requestSync/buildRequest`, `HttpSyncRemoteDataSource`, `UnimplementedSyncRemoteDataSource`, `SyncOutboxEntity/Dao` | LEGACY/ROLLBACK — unreachable in production graph |
| `DefaultCloudKnowledgeIngestor/Writer`, `CloudConflictRecorder`, `CloudCursorDao/Entity`, `RoomConflictRepository`, `DefaultConflictResolver`, `ConflictDao/Entity`, `DefaultCloudAnswerCache` | LEGACY/ROLLBACK (unwired; direct-class tests still guard behavior parity) |
| `QdrantEdgeVectorStore`, `local_qdrant` | LEGACY/UNUSED — no active construction anywhere |
| `SyncOutboxWriter`, `SyncEngine`, `SyncRemoteDataSource` domain ifaces | retained for legacy classes; `SyncStatusReader` is the active boundary |
| Room/local_qdrant word matches in Qdrant-native files | documentation comments only |

## 16. Known limitations

1. Legacy Room conflicts are NOT migrated (re-resolvable: replayed cloud
   pages re-record divergence into Qdrant conflict points).
2. Legacy rows whose pre-cutover push was ACKed but whose Room `syncState`
   never advanced (crash in that legacy window) may re-push under the new
   identity; the backend's version/hash classification absorbs this as
   DUPLICATE/CONFLICT evidence — no data loss, possible conflict evidence.
3. Device-runtime (emulator/phone) behavior and live cloud remain unverified
   (unchanged constraints; no device, credentials gated).
4. `legacyImportError` is currently surfaced only via the container property
   (no UI — UI is a later phase by rule).
5. Keyword scan remains O(active records) with a 5 000 cap (13.3 note).

## 17. UI handoff

Data architecture is FROZEN and Qdrant-native. The Industrial UI should
consume ONLY: `MemoryViewModel`/`AskViewModel` factories (or the same use
cases directly), `SyncStatusReader` (real operation-store state),
`ListConflicts`/`CountUnresolved`/`ResolveConflict` (Qdrant-backed),
`PullCloudKnowledgeUseCase`, `ConnectivityStatusFlow`. No screen may add a
Room/vector-store dependency; none exists to add.
