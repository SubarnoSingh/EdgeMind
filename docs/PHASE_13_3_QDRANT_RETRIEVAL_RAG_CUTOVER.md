# Phase 13.3 — Qdrant-Native Retrieval + RAG Cutover

## 1. Old retrieval architecture (verified pre-phase)

```text
DefaultRagService → DefaultRetrievalService
    dense:   QdrantEdgeVectorStore (local_qdrant, vectors-only)
    keyword: KeywordRetriever → Room MemoryDao LIKE
    resolve: Room MemoryDao.getByIds + supersededIds
```
Consequence (from 13.1/13.2): records authored through the Qdrant-native
repository were invisible to Ask/RAG.

## 2. New retrieval architecture (active now)

```text
DefaultRagService (UNCHANGED) → EscalatingRagService (UNCHANGED)
    ↓
QdrantRecordRetrievalService (NEW, implements core.retrieval.RetrievalService)
    dense:   QdrantEdgeRecordStore.search(RecordQuery.searchInTypes)
    keyword: bounded scroll of active application records + same scoring
    fuse:    ReciprocalRankFusion (same class, same DEFAULT_K = 60.0)
    resolve: the ranked Qdrant Record itself (payload travels in the point)
    filter:  envelope indexes (_tombstone, _record_type) + Exists(_supersedes)
    ↓
ONE shard: filesDir/qdrant_sync_store
```

Room and `local_qdrant` are not consulted by the active retrieval path.
No dual-source query or merge exists.

## 3. Dense retrieval

Real vector search via the production JNI boundary
(`nativeSearchWithFilter`): same 512-dim `FeatureHashingEmbeddingService`
representation the 13.2 repository writes with (consistency proven by an
equality test against a direct `store.search` call). Score, record identity
and full payload come back in one point; `limit × candidateMultiplier`
candidate behaviour preserved; tombstones excluded by the compiled
`_tombstone=false` + application-type filter.

## 4. Keyword retrieval (truthful equivalence)

qdrant-edge 0.8.0 exposes **no full-text/substring search** through this
boundary; keyword indexes match whole values, not substrings. The legacy
path was a per-term case-insensitive `LIKE %term%` over Room. The smallest
faithful equivalent is a **bounded scan** (200/page, 5 000 cap, indexed
active+type prefilter) applying the identical rules in Kotlin:

* term weight `1.0`, identifier weight `2.0` (`QueryNormalizer.isIdentifier`);
* case-insensitive substring over title + content;
* score-desc, id-asc ordering; per-term `matchedTerms` reporting.

Ranking, weights and matched-term semantics are identical to the legacy
retriever — only the execution site moved from SQLite to the authoritative
shard. Semantic difference vs the old Room path: scan cost is O(active
records) instead of a SQLite LIKE query; acceptable at edge scale and
revisitable when qdrant-edge exposes a text index. No second database was
introduced.

## 5. Hybrid / RRF

Unchanged algorithm: `ReciprocalRankFusion.fuse(denseRanking, keywordRanking,
k = DEFAULT_K 60.0)`. Per-source scores (`denseScore`, `keywordScore`) and
`matchedTerms` retained on `EvidenceItem`; `useHybrid=false` clears the
keyword channel exactly as before; deduplication unchanged (content-hash +
documentId#chunkIndex chunk identity); superseded exclusion reproduces the
legacy exhaustive `SELECT DISTINCT supersedes` semantics via an indexed
`Exists(_supersedes)` scroll (including references from tombstoned records);
top-K and rank assignment identical; `candidateCount` semantics identical.

## 6. Filters

Envelope filters execute as real compiled Qdrant filters through the frozen
12B.5 `FilterCompiler` (verified shapes: must/should/must_not, indexed
keyword/integer/bool fields). Retrieval uses `activeOnly` + application
`RecordType` scoping (shared `MemoryRecordMapper.APP_MEMORY_RECORD_TYPES`,
now the single canonical set used by BOTH writes and reads). System points
(`outbox_op`, `conflict`, `sys_cursor`) are structurally outside the scope —
pinned by a test that plants an outbox-style point in the shard and asserts
it never surfaces. The full Match/In/Range/DateRange/Exists/Not/And/Or
matrix remains covered by `FilterCompilerTest` (unchanged, real shard).

## 7. Record → RetrievalResult / citation mapping

`MemoryRecordMapper.toMemory` (13.2) resolves every `EvidenceItem.memory`
field: title, content, type (payload-authoritative), memoryId, source,
chunkId (payload), metadata (envelope `_metadata`: documentId, chunkIndex,
page, section, sourceName…), timestamps, origin/authority, policy state.
`DefaultRagService.buildSources` reads exactly those fields — a dedicated
test writes a record with document/page/section metadata, retrieves it, and
asserts every citation input survived; the end-to-end tests additionally
verify every emitted citation references a real stored Qdrant point.
Nothing Room-only was dropped: the legacy citation fields were already
metadata-map entries, which the 13.2 envelope carries.

## 8. Sufficiency / grounding

`DefaultRagService` untouched. `minDenseScore` remains 0.25; sufficiency is
`any keywordScore > 0 OR max denseScore ≥ 0.25`; insufficient evidence still
returns the honest `INSUFFICIENT_EVIDENCE` message with real evidence lists.
No thresholds were changed to make tests pass; scores come from the shard,
proven by an equality assertion against a direct vector-search call.

## 9. Offline behavior

The whole chain — repository → shard → retrieval → `DefaultRagService` →
`ExtractiveLLMService` — has zero network collaborators. E2E tests answer
from local evidence with no backend configured. `EscalatingRagService`
(unchanged) consults connectivity only when local evidence is insufficient;
an offline query can never silently become a cloud query.

## 10. Cloud escalation

Unmodified: `HttpCloudAnswerDataSource`, cache path and escalation policy
are byte-for-byte the pre-phase behavior. The RAG service boundary changed
only in what feeds `RetrievalService`.

## 11. Production bug found and fixed by this phase

`QdrantEdgeRecordStore` serialized nested `JsonValue` payloads with
`value.toJson()` (Kotlin `Map`/`List`) into `org.json.JSONObject.put`, which
**stringifies** unknown objects — `{"metadata":"{a=1}"}`. Every nested
payload field (`_metadata`, `_tags`, object/array domain fields) was stored
corrupted and came back empty/corrupt. Latent since 12B.4 because no prior
test round-tripped a nested payload through the store; it would have broken
citations and ingestion metadata on the Qdrant path. Fixed with a recursive
`toOrgJsonValue()` conversion (production file `QdrantEdgeRecordStore.kt`).
Pinned by the new citation test + the existing full suite (466/466).

## 12. Old `local_qdrant` status

`QdrantEdgeVectorStore` remains constructed in `AppContainer` solely for the
two explicitly deferred 13.4 paths (cloud-pull writer, legacy conflict
resolver). It is NOT a retrieval source, NOT authoritative, and receives no
application memory writes. There is no dual-source retrieval.

## 13. Remaining Room references (classified)

| File | Classification |
|---|---|
| `DefaultRetrievalService`, `KeywordRetriever` | LEGACY/ROLLBACK — unwired; classes + their passing tests intact |
| `DefaultMemoryRepository` | LEGACY/ROLLBACK — unwired |
| `RoomSyncOutboxWriter`, `DefaultSyncEngine`, `SyncWorker` | LEGACY/ROLLBACK — frozen, no scheduler callers |
| `MemoryDao` in `EdgeMindDatabase` | schema definition |
| `MemoryDao` in `DefaultCloudKnowledgeWriter` / `DefaultCloudKnowledgeIngestor` / `CloudCursorDao` / `CloudConflictRecorder` / `DefaultCloudAnswerCache` | DEFERRED 13.4 — cloud pull / answer cache still write Room |
| `MemoryDao` in `DefaultConflictResolver` / `RoomConflictRepository` | DEFERRED 13.4 — conflicts still Room-backed |
| `MemoryDao` in `DefaultRetrievalService` / `KeywordRetriever` | covered by rows above (legacy) |

No ACTIVE retrieval path reads Room or `local_qdrant`.

## 14. Tests (17 new, all real-shard)

`QdrantRecordRetrievalServiceTest` (12): dense find; empty-query rejection;
keyword substring parity; identifier weighting parity; system/tombstone
exclusion; hybrid dual-channel; dense-only channel clearing; dedup;
superseded exclusion; citation field survival (metadata round-trip — the
bug-pinning test); reopen-equal-retrieval; real-score equality/ordering.
`QdrantRetrievalRagEndToEndTest` (4): §11 chain (repository write → shard
point with vector → immediate dense+keyword retrieval → ANSWERED with real
citations); §12 P-101 scenario (four record types, all selected as evidence,
every citation resolvable in the shard, SYNC_REDACTED record still raw
locally); restart → retrieve → answer; offline pure-local answering.
`AppContainerQdrantSyncWiringTest` (+1): full production container graph
(`createMemory → retrieveMemories → askQuestion`), proving end-to-end wiring
and that Room stayed empty.

### Verification totals (13.3)
Android **466/466** (449 + 17), Rust **10/10**, backend **35/35**,
lint **0 errors** (no new warnings), assembleDebug **SUCCESSFUL**.

## 15. Known limitations

1. Keyword scan is O(active records) with a 5 000-record cap per query
   (truthful equivalence; document the tradeoff, revisit with a text index).
2. Transitional until 13.4: cloud pull + answer cache still write Room, so
   pulled cloud knowledge is not yet in the Qdrant retrieval scope (12B's
   `engine.pullAndApply` already reads/writes the application shard; the
   rewiring itself is 13.4 work). Conflicts UI likewise reads Room.
3. No Room→Record data import (unchanged 13.2 decision; 13.4 prerequisite).

## 16. Phase 13.4 handoff

* Re-point `PullCloudKnowledgeUseCase` to `DefaultQdrantSyncEngine.pullAndApply`.
* Unify conflicts onto the Qdrant conflict points; provide the
  `ConflictRepository` reader over the record store.
* Retire `local_qdrant`, `QdrantEdgeVectorStore`, and the legacy Room sync
  graph from the constructed container (deletion decision or feature flag).
* Decide Room→Record one-time import for any device with legacy rows.
* Only then may Room be marked fully frozen/rollback-only.
