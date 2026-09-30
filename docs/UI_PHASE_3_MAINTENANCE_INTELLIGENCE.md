# UI Phase 3 — Maintenance Intelligence / Asset Detail Deepening

## 1. Asset representation (unchanged, honest)

There is still **no first-class machine entity**, and none was invented. An
"asset" remains exactly what UI Phase 1 established: the **namespace of a
real `Memory.subjectKey`** (e.g. `p101/seal` ⇒ asset `p101`), derived from
records already stored in the Qdrant application shard by the production
write path. `AssetModel` is the single derivation (pure function over the
domain model; tombstone-safe; deterministic sort: `updatedAt` desc with
`memoryId` desc tie-break).

What is displayed as "asset identity":
- **Identifier** — the real namespace token (never invented).
- **Name line** — only a real record's title (the most recently updated
  record's), labelled as a representative record, never as a machine name.
- Everything else (manufacturer, model, serial, location, criticality,
  operational status, health/telemetry) is **absent from the domain** and is
  therefore **not rendered**; a visible "DOMAIN NOTES" disclosure states this.

## 2. Data sources used (existing domain/application APIs only)

`ListMemoriesUseCase` (bounded full read of active knowledge),
`ListConflictsUseCase` (Qdrant conflict store), `CreateMemoryUseCase`
(production write path), `SyncStatusReader` (operation store — shell header),
`ConnectivityStatusFlow` (shell header). Presentation-layer import audit:
zero `data.*`/`room`/`native.*` imports in machines/shell/dashboard/sync/ask
packages (except the pre-existing SAF `ContentResolverDocumentReader` in
MemoryViewModel).

## 3. Asset detail architecture

`MachineDetailScreen` (workspace) ← `MachineDetailViewModel` (single
immutable `AssetDetailUiState` StateFlow: `data: Loading|Ready|Failed`,
`categoryFilter`, `composer`). `RecordDetailScreen` ← dedicated
`RecordDetailViewModel` resolving one record through `ListMemoriesUseCase`.
The workspace is a `Box(tag=SCREEN){ Column(scroll) }` — all content
composed and deterministic to drive in tests (per-asset record sets are
already fully loaded by the domain read; no lazy-list scrolling gaps).
`LoadableState` from UI Phase 1 provides Loading/Empty/Error(+retry);
offline is never an error state.

Navigation: `EdgeRoute.RecordDetail(memoryId)` added to the deterministic
`EdgeNavigator` (tab stays MACHINES, dedupe, pop). "Ask about this asset"
still routes to the SAME Ask surface via `openAsk(namespace)` (UI Phase 2
behavior preserved — asset context is appended to the executed query; no
fake result filtering is claimed).

## 4. Record categorization

Categories are a presentation projection of the existing authoritative
`MemoryType` taxonomy (no second taxonomy):
REPAIR→Maintenance, OBSERVATION→Observations, EVENT→Incidents,
PROCEDURE→Procedures, DOCUMENT→Documents, NOTE/CLOUD_KNOWLEDGE→Other.
Overview shows per-category counts of the asset's real records (zero
categories are not rendered). Lightweight category filtering is
presentation-side over the already-loaded bounded set only.

## 5. Maintenance/activity timeline

Chronological (newest→oldest, deterministic tie-break), each entry:
category label, real title, content excerpt, relative timestamp (pure
`relativeTimeLabel` helper), and the record's durable sync state as a
textual dot+label indicator. Entries open the record detail. No timestamps
are fabricated (missing ⇒ em-dash via existing helper).

## 6. Record detail

`RecordDetailScreen` renders only fields the domain really carries: title,
full content (Markdown), type, subject, tags, importance, sensitivity,
source, origin, authority, created/updated (UTC-formatted, monospace),
policy decision + explanation + redacted sync title, sync state, version,
supersedes, document/chunk metadata (documentId/page/section/chunk/counts/
format + any real extra metadata), record id and chunk id. A deleted/
missing record shows an honest "no longer available" state.

## 7. Evidence/documents

Document-derived records expose their real ingestion metadata (document
title, file name, page, section, chunk index/count, format). No document
viewer was added — the detail view is honest evidence display; opening
source files remains a later-phase concern (the existing SAF picker in the
records browser is untouched).

## 8. Conflict display (DISPLAY ONLY)

The workspace lists the asset's unresolved conflicts from the real Qdrant
conflict store: subject, local version vs cloud version, origins,
authority, reason, detected-ago, and an explicit note that resolution
actions come in a later phase. No keep-local/keep-cloud/dismiss controls
were added.

## 9. Sync status

Header sync badge and workspace chips come from real store state:
per-record `syncState` (PENDING/FAILED/SYNCED/LOCAL — the durable states
the engine writes on enqueue/ACK) for the asset's records; the shell badge
from `SyncStatusReader` operation counts. Nothing is marked synced after a
local write; offline shows "queued", never success.

## 10. Offline behavior

The workspace, timeline, record detail, filters and add-record list refresh
all operate purely on the local shard. Robolectric (no network) is the E2E
environment, so offline asset browsing is continuously proven. A dedicated
assertion verifies no fabricated telemetry labels ("Health", "Uptime",
"Vibration", "Risk score") exist anywhere in the tree.

## 11. Ask-about-asset integration

Unchanged Phase-2 route (`navigator.openAsk(namespace)`), same shared Ask
screen, honest context chip and executed-query display. Verified in E2E.

## 12. Add-observation capability

Implemented through the real production path: composer → `CreateMemoryUseCase`
(subjectKey `<namespace>/<type>`, type from OBSERVATION/REPAIR/EVENT/
PROCEDURE/NOTE, optional explicit sync choice else policy-decided). E2E
proves the record lands in the shard, the asset view refreshes, and the
change detection ran (sync chip shows pending for sanctioned types).
Validation and domain errors stay in the form for retry; no fake success.
A UX bug surfaced by testing and fixed: the composer now marks
`submitting` at click time so the button never silently no-ops.

## 13. Domain gaps (reported, not faked)

- **No typed machine entity**: status/criticality/telemetry/manufacturer/
  location cannot be shown because no domain API carries them. A machine
  domain read/write API over `RecordType.MACHINE` (with active writers) is
  the enabling work; the UI is structured to adopt it without rework.
- **No hard asset-scoped retrieval** (unchanged from Phase 2).
- **No by-id domain read**: record detail resolves via the bounded list read;
  a `GetMemoryUseCase` is a trivial later addition.
- Conflicts are display-only until Phase 4's resolution workflow.

## 14. Tests

- `AssetWorkspacePhase3Test` (11, JVM): real-domain loading & categories,
  deterministic chronological order incl. ties, honest empty unknown asset,
  error→retry, conflict-per-namespace from the conflict store, sync counts
  from record states only, presentation-side filter, add-record through
  `CreateMemoryUseCase` incl. subjectKey/sync-choice correctness, validation
  & write-failure handling, record/Ask navigation, record-detail found /
  honest-missing / error.
- `EdgeNavigatorPhase3Test` (2): RecordDetail push/pop/dedupe/tab-mapping
  determinism.
- `AssetWorkspaceEndToEndTest` (5, Robolectric+Compose+real container):
  seeded P-101 records render (counts, chips, timeline, unrelated records
  excluded); record detail shows real content/subject/version; filtering by
  timeline tags; add-record persists through the production repository and
  refreshes; Ask integration with asset chip; offline browsing + no-fabricated-
  telemetry + Room/`local_qdrant`/single-shard architecture guard.
- UI Phase 1 shell tests updated only where the workspace layout legitimately
  changed (representative title now shows real record text; scroll to reveal
  below-fold content). All remain green.

### Verification totals (UI Phase 3)

- Android: **544/544** (526 baseline + 18 new; 0 skipped, 0 weakened — the
  two adjusted Phase-1 assertions verify strictly the same intents).
- Presentation package: 85/85.
- Rust: **10/10** · Backend: **35/35** (untouched).
- lintDebug: **0 errors**, 29 pre-existing warnings (none from phase files).
- assembleDebug: **BUILD SUCCESSFUL**.

## 15. Known limitations

1. The asset header shows the newest record's title as the closest honest
   "name"; there is no machine name field.
2. Record detail re-reads via the bounded list (no by-id use case yet).
3. Add-record composer exposes sync choice as auto/sync/local-only;
   SYNC_REDACTED remains policy-driven only (never user-forced).
4. No maintenance timeline grouping by machine identity beyond namespace.

## 16. Deferred work (next phases)

Conflict resolution workflow (UI Phase 4), typed machine domain API +
migration of namespaces into machine entities, by-id record use case,
document viewing/opening, analytics, demo seeding flow.
