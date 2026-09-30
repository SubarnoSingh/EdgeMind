# UI Phase 1 — Application Shell + Industrial Design System

## 1. Architecture

```text
MainActivity (theme/window bootstrapping only)
  └─ EdgeMindShell (presentation/shell/EdgeMindShell.kt)
       ├─ ShellViewModel          route + header badges (online, sync)
       ├─ EdgeNavigator           deterministic back-stack state machine
       ├─ content by route:
       │    DashboardTab   → DashboardViewModel   → ListMemories · SyncStatusReader · CountUnresolvedConflicts
       │    MachinesTab    → MachinesViewModel    → ListMemories · ListConflicts
       │    AskTab         → existing AskScreen (unchanged, hosted)
       │    SyncTab        → SyncViewModel        → SyncStatusReader · ListConflicts · ListMemories
       │    SettingsTab    → existing SettingsScreen (unchanged, hosted)
       │    Records route  → existing MemoryScreen (unchanged, pushed)
       │    MachineDetail  → MachineDetailViewModel (keyed per asset namespace)
       └─ EdgeBottomNavBar        five destinations, text + icon + semantics
```

The ViewModels consume ONLY the existing frozen application/domain APIs
(`ListMemoriesUseCase`, `SyncStatusReader`, `ListConflictsUseCase`,
`CountUnresolvedConflictsUseCase`, `ConnectivityStatusFlow.isOnline`,
`EdgeMindApplication`-scoped `AppContainer` factories). No ViewModel, screen
or component touches Room, `MemoryDao`, `QdrantEdgeRecordStore`, JNI, sync
internals or cloud HTTP clients — verified by a source-tree audit and pinned
by `EdgeMindShellComposeTest.uiGraphNeverInitializesRoomOrLegacyShards`.

No navigation library was added (none existed; five tabs do not justify one).
Navigation is a small testable state machine (`EdgeNavigator`) owned by the
shell ViewModel; configuration changes survive it (ViewModel-held).

## 2. Navigation model

* Tabs: DASHBOARD · MACHINES · ASK · SYNC · SETTINGS (bottom bar, always
  reachable, current tab indicated).
* Pushed routes: `Records` (existing capture/browser surface, back-supported),
  `MachineDetail(subjectKey)` (keyed ViewModel per asset).
* Tab switch collapses the push stack; system back pops; root tabs do not
  exit the app via back.
* `BackHandler` bound to route state — no ad-hoc booleans.

## 3. Design system (centralized — no scattered literals)

| Token file | Provides |
|---|---|
| `ui/theme/EdgeStatus.kt` | `EdgeStatus` (HEALTHY, WARNING, CRITICAL, OFFLINE, SYNCING, SYNCED, NEUTRAL) + `statusStyleFor()` semantic color resolution |
| `ui/theme/EdgeType.kt` | `EdgeType` roles (screenTitle, sectionTitle, body, metadata, label, numeric, metricValue — Monospace for telemetry) + `EdgeLayout` spacing/geometry tokens |
| `ui/theme/Color.kt` / `Theme.kt` | pre-existing industrial charcoal + restrained violet/blue identity (dark first), `EdgeColors` accents — unchanged, extended not replaced |
| `presentation/components/EdgeIndicators.kt` | `StatusDot`, `EdgeStatusBadge`, `MetricTile`, `EdgeBottomNavBar` |
| `presentation/components/EdgeStates.kt` | `EdgeLoadingState`, `EdgeEmptyState`, `EdgeErrorState` (with retry), `EdgePhasePlaceholder`, shared `EdgeUiTags` |
| `presentation/components/ShellIcons.kt` | locally drawn stroked nav icons matching the existing Sun/Moon/Settings set |

Rules enforced: status is never color-alone (dot + uppercase text +
contentDescription); dark charcoal near-black base with restrained violet/
periwinkle accents; hairline borders instead of heavy elevation; no
chatbot/glassmorphism/neon aesthetics; generous primary-action spacing.

## 4. Dashboard (real data only)

Metrics derived from genuine APIs:

* **Knowledge records** — active record count from `ListMemoriesUseCase`.
* **Assets referenced** — distinct `subjectKey` namespaces (see §5).
* **Maintenance records** — REPAIR/OBSERVATION/PROCEDURE/EVENT types.
* **Open conflicts** — `CountUnresolvedConflictsUseCase` (Qdrant conflict store).
* **Sync overview row** — real `SyncSummary` counts (pending · synced · failed).

Deliberately ABSENT (no domain API exists): machine operational status,
criticality, telemetry, per-machine maintenance scheduling counts. These are
documented as deferred, not faked (see §9).

Sections: honest offline notice ("Local intelligence stays fully
available") · Ask primary action · Recent activity (real records, recency
ordered, relative timestamps, per-record sync dot) · Records browser entry ·
Machines entry. Explicit Loading/Empty/Error(+retry)/Ready states.

## 5. Machine/asset foundation & the data-reality decision

The active production domain (`Memory`, 7 types) has **no first-class machine
entity**: `RecordType.MACHINE` exists in the frozen record layer but nothing
in the active graph writes or reads it (verified Phase 13.1). Fabricating a
machine repository would violate both the UI rules (no new repositories/
persistence) and the frozen architecture.

Decision (Option A — safe derivation): an **asset** is a `subjectKey`
namespace — the domain's real identity field for evolving knowledge (e.g.
`p101/torque` ⇒ asset `p101`). `AssetModel` groups real records into
`Asset(namespace, recordCount, maintenanceCount, pendingSyncCount,
unresolvedConflictCount, lastActivityAt, types, representativeTitle)`,
excludes tombstones and records without subject keys, and merges real
conflict counts from the conflict store. On a fresh device the honest result
is the EMPTY state with guidance — nothing hardcoded, P-101 appears only when
real records reference it.

**Reported gap:** a true machine domain (typed records, status, criticality,
relations) needs a domain-level API built over the existing record store in a
later phase; the UI is structured to switch to it without shell changes.

## 6. Machine detail (foundation)

Header: asset namespace + back. Sections: **Overview** (record counts,
maintenance count, pending sync, conflicts, record types + an explicit note
that operational status/criticality are not yet domain data), **Recent
records** (the actual memories, sync dots, relative time), **Ask about this
asset** (routes to the existing grounded Ask experience). Layout is ready to
absorb timelines/evidence once the machine API lands.

## 7. Connectivity & sync indicators

* Connection badge: driven solely by the event-driven `ConnectivityStatusFlow`
  (real OS callback) — ONLINE / OFFLINE text.
* Sync badge: derived from durable operation-store counts only:
  `SYNCING` (IN_FLIGHT>0) · `ATTENTION` (FAILED/DEAD>0) · `PENDING` ·
  `SYNCED` (ACKED>0) · `no sync` (empty). It can never claim synchronization
  the store does not record; offline simply shows the honest queue state.
* Conflict count from `CountUnresolvedConflictsUseCase` (Qdrant conflict
  points). "Sync now" enqueues the real unique WorkManager job.
* Activity timeline (event-sourced history) is intentionally deferred.

## 8. Offline behavior

Local CRUD, assets, retrieval, Ask/RAG and all dashboards render with zero
network: cloud is unconfigured or offline and every screen still reaches
Ready/Empty states (asserted in the compose tests, which run with no backend
configured). Nothing in the UI graph can create a Room file or the retired
`local_qdrant` shard (pinned by test).

## 9. Domain APIs consumed (complete list)

`ListMemoriesUseCase` · `SyncStatusReader.outboxCounts()` ·
`ListConflictsUseCase` · `CountUnresolvedConflictsUseCase` ·
`CreateMemoryUseCase` (tests + existing screens) ·
`ConnectivityStatusFlow.isOnline` · `AppContainer.requestQdrantSyncNow`
(via existing VM callback). No other data access exists in UI code.

## 10. Testing

`EdgeNavigatorTest` (7) · `EdgeShellViewModelsTest` (10 — dashboard
derivation/error/empty, machines derivation, detail grouping/unknown-asset,
sync badge matrix, failure degradation, connectivity flow, sync-now trigger)
· `EdgeMindShellComposeTest` (6, Robolectric + Compose): shell launch,
dashboard honest load/empty, machines tab navigation + empty foundation,
machine-detail navigation with a REAL record created through the production
repository, textual offline indicator, and the Room/`local_qdrant`
non-initialization architecture guard.

Test infra additions: `testImplementation` of the already-present
`androidx.compose.ui:ui-test-junit4` + BOM; `TestNativeLoader` now falls back
to a private per-classloader copy of the host JNI library when Robolectric
spans multiple sandboxes (test-only change).

### Verification totals (UI Phase 1)

Android **509/509** (486 Phase-13.4 baseline + 23 new: 7 navigator + 10
ViewModel + 6 Compose shell tests), 0 skipped, 0 weakened. Rust **10/10** ·
Backend **35/35** (untouched) · lint **0 errors** (29 pre-existing warnings,
none introduced) · `assembleDebug` **SUCCESSFUL**.

## 11. Known limitations / deferred to later UI phases

1. **Machine domain API gap** — assets derive from subject keys until a real
   typed machine repository/use cases land (recommended next data-adjacent
   work; UI is structured for the swap).
2. Activity timeline, conflict-resolution actions UI, document ingestion UX,
   grounded-answer presentation polish: later phases (intentional
   placeholders/empty states only).
3. Dashboard metrics refresh on navigation (no background polling by design).
4. `ContentResolverDocumentReader` import inside the existing MemoryViewModel
   predates this phase (SAF document reading, not data persistence).

## 12. Exact next phase candidates

UI Phase 2 — grounded Ask experience (citations UI, evidence drill-in) OR the
machine-domain read/write API + asset timeline, per product priority.
