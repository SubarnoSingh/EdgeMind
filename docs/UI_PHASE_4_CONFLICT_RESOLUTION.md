# UI Phase 4 — Conflict Resolution Workflow

This phase exposes the **existing** Qdrant-native conflict system (12B.9 store,
13.x resolver, domain use cases) through production UI. It adds **no new data
architecture**: resolution rules, storage, and follow-up sync behavior remain
exclusively owned by the code that already implements them.

## 1. Scope and ownership boundaries

Owned by this phase (presentation only):

- `presentation/conflicts/ConflictListScreen.kt` + `ConflictListViewModel.kt`
- `presentation/conflicts/ConflictDetailScreen.kt` + `ConflictDetailViewModel.kt`
- Navigation: `EdgeRoute.Conflicts` and `EdgeRoute.ConflictDetail(conflictId)`
  (`currentTab = null` — both are pushed destinations, not tabs)
- Entry points: dashboard "Open conflicts" tile
  (`EdgeUiTags.OPEN_CONFLICTS`), Sync screen conflict rows
  (`edge-sync-conflict-<id>`), asset screen conflict rows
  (`AssetUiTags.CONFLICT_PREFIX`), all clickable and backed by real counts.

Owned by the existing system (never duplicated here):

- `QdrantConflictStore` — conflict records live in the Qdrant conflict shard.
- `QdrantConflictResolver` — deterministic resolution rules, idempotency,
  version arithmetic (`max + 1`), follow-up outbox operations, tombstone
  handling. The UI calls `ResolveConflictUseCase` and reports exactly what the
  resolver returned; it decides nothing itself.
- `DefaultQdrantSyncEngine.applyCloudItem` — the only production path that
  *creates* conflicts (pull classification). Unchanged.

## 2. Minimal domain additions

Two read adapters were added because the domain layer had no single-item reads:

- `GetConflictUseCase(conflictId)` → `Conflict?` (from `ConflictRepository.get`)
- `GetMemoryUseCase(memoryId)` → `MemoryRecord?` (from `MemoryRepository.get`)

`Conflict` gained `localTombstone` / `incomingTombstone` (Boolean, default
`false`), mapped from the evidence already recorded by the 12B.9 store in
`QdrantConflictStore.toDomainConflict()`. Nothing else in the model changed.

Architecture guard holds: the new presentation files import only
`domain`/`core`/`presentation`; no Room, no native, no `data.*`.

## 3. Conflict list (`ConflictListScreen`)

- Shows unresolved conflicts from `GetConflictsUseCase` through
  `ObserveUnresolvedConflictsUseCase`-style refresh on entry
  (`LaunchedEffect(Unit) { refresh() }`), so returning from a detail after a
  resolution re-reads durable state instead of showing a stale list.
- Rows: subject key, both versions/hashes summary, detection time, state chip.
- Deterministic ordering, honest empty state
  ("No unresolved conflicts"), real error state with retry.
- Row tap → `navigator.openConflict(conflictId)`; `conflictId` is the real
  conflict uuid — navigation carries identifiers only, never copied data.

## 4. Conflict detail and evidence comparison (`ConflictDetailScreen`)

Two evidence panels rendered strictly from the stored `Conflict`:

- LOCAL RECORD: title, `vN · origin · hash`, content excerpt, record id,
  tombstone flag when set.
- CLOUD RECORD: same, plus `authority` when present.
- "WHY IT CONFLICTS": the real reason (e.g. `PULL_CONFLICT`) and the version/
  hash pair that made the deterministic classifier flag it.
- Status chip is the authoritative lowercase rendering of
  `conflict.state`; the header never invents a status.

If the conflict id no longer resolves (deleted/impossible state), the screen
shows an honest missing-state, not a blank.

## 5. Resolution state machine (explicit, no implicit writes)

`ResolutionUi` sealed states in `ConflictDetailViewModel`:

```text
Idle ──tap KEEP_LOCAL/KEEP_CLOUD──▶ Confirming(action)
Confirming ──CANCEL──▶ Idle            (nothing written)
Confirming ──CONFIRM──▶ Resolving ──▶ Resolved(outcome)   ─┐
                              └──error──▶ Failed(message) ──┴▶ retry → Resolving
```

- **Confirmation gate**: a resolution requires two deliberate taps; the
  confirmation card names the exact consequence ("You are keeping the
  LOCAL/CLOUD version…").
- **Single-flight**: `Resolving` ignores further taps (double-tap cannot
  submit twice).
- **Resolving** re-reads the durable conflict *after* the resolver call;
  `wasAlreadyResolved` is computed from `state before ≠ state after`, never
  from the in-memory request — an idempotent re-resolution honestly reports
  "already resolved".
- **Stale/race**: if the conflict vanished or is already settled upstream, the
  outcome says so with the durable state, not a fake success.
- `Resolved` outcome reports the real `ConflictResolution`: kept side, whether
  record content changed, whether a sync operation was produced, resolver note,
  resolved-at time.

## 6. What the real resolver does (verified, not asserted)

Unchanged 12B.9 behavior, now reachable from the UI:

- **KEEP LOCAL** — conflict settled `RESOLVED_LOCAL`; local record content,
  version, and hash untouched; **no** outbox operation.
- **KEEP CLOUD** — incoming content applied with version `max + 1`, a **single**
  idempotent `UPSERT:<conflictUuid>:<version>` outbox operation queued
  (never the legacy `UPSERT-<memoryId>` form), conflict settled
  `RESOLVED_CLOUD`. The Sync screen then shows the honest `QUEUED FOR SYNC`
  pending state; the asset screen conflict row disappears on return refresh.

## 7. Tests

`ConflictWorkflowPhase4Test` — 14 Robolectric/Compose tests with contract
fakes for `ConflictRepository`/`ConflictResolver` covering: list loading, empty
state, ordering, retry, routing; detail evidence, missing conflict,
confirmation gate, cancel-writes-nothing, keep-local, keep-cloud, double-tap
single-flight, already-resolved honesty, stale race, resolver failure + retry,
navigation determinism.

`ConflictResolutionEndToEndTest` — 2 tests against the **real** stack (Qdrant
Edge shard + real sync engine + real resolver + real Room-free metadata):
seed a genuine conflict via `applyCloudItem`, resolve through the UI from both
the dashboard workspace and the asset screen, and assert durable truth
(state, version arithmetic, exactly one queued operation, record content).

## 8. Verification totals (UI Phase 4)

```text
Android unit/integration/UI   560 / 560   (544 baseline + 16 new)
Rust (qdrant-native)           10 / 10
Backend                         35 / 35
lint                          0 errors (29 pre-existing warnings)
assembleDebug                 BUILD SUCCESSFUL
```

## 9. Known limitations

- Resolution reasons/notes are the resolver's fixed vocabulary; no free-text
  adjudication (not in the domain model).
- The list observes on entry/refresh, not a live push flow from the conflict
  shard.
- Cloud-side tombstone propagation for resolved-cloud results remains
  one-way (unchanged from 13.4).

## 10. Deferred

- Conflict resolution from the Activity timeline (ACTIVITY screen itself is a
  later phase).
- Batch/related-conflict views (subject grouping exists in data, not surfaced).
- Policy interaction UI (`SYNC_REDACTED` representation at conflict time).
