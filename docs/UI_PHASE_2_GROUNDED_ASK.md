# UI Phase 2 — Grounded Ask / RAG Experience

## 1. Ask architecture

The Ask destination is the production grounded-intelligence console. It does
NOT introduce a new RAG path — it renders the **existing** one:

```text
AskViewModel (presentation)
  └─ AskQuestionUseCase (domain)
       └─ EscalatingRagService (existing)
            ├─ DefaultRagService → QdrantRecordRetrievalService (13.3) → RRF → ExtractiveLLMService
            └─ CloudAnswerDataSource (existing; honest no-op when unconfigured)
```

The screen is `presentation/ask/AskScreen.kt`. The shared `AskViewModel` is
owned by the shell and reused by the citation detail route, so the result is
retained while the user inspects evidence and survives config changes.
The UI does not touch Qdrant, Room, JNI, sync, or HTTP directly.

## 2. ViewModel / state machine

`AskPhase` is unchanged and maps 1:1 to real `RagStage`s:
`IDLE → RETRIEVING → (GENERATING | ESCALATING) → SUCCESS | INSUFFICIENT | ERROR`.

Two honest fields were added to `AskUiState`:
- `assetNamespace` — set only via the navigator when opened from Machine Detail.
- `executedQuestion` — the exact text sent to the pipeline (shown verbatim so
  the user sees what was searched).

Derived, never invented:
- `provenance: AskProvenance { NONE | LOCAL | CLOUD }` — computed ONLY from the
  real `escalation` on the response. A cloud answer requires
  `CloudEscalation.Answered`; there is no inference from connectivity.
- `isOffline` / `isCloudUnavailable` / `canSave` — pre-existing, unchanged.

`retry()` re-executes the last executed query verbatim (re-run, not a
regeneration). `setAssetContext()` / `openAsk()` carry asset context.

## 3. Application / domain APIs consumed

`AskQuestionUseCase`, `CacheCloudAnswerUseCase`, `CloudEscalation`,
`SourceReference`, `RagResponse`, `RagStage`. Nothing else. The ViewModels do
not perform retrieval, filtering, or provenance logic — they only coordinate
the existing use case and reflect its result.

## 4. Retrieval / RAG path used

The frozen Phase-13.3 pipeline: dense (Qdrant vector search) + keyword
(subject-namespace scan) → RRF K=60 → dedup → top-K → sufficiency
(`minDenseScore = 0.25`, unchanged) → `ExtractiveLLMService`. **No retrieval
semantics changed.** The existing 13.3/13.4 retrieval tests remain green and
are the source of truth for those semantics; the UI sits above them unchanged.

## 5. Answer presentation

Industrial, non-conversational. No chat bubbles/avatars/typing dots. A single
Q → provenance → A → evidence flow:
- QUESTION (submitted text; plus "executed query: …" when asset context was applied)
- ANSWER via `MarkdownBody` (existing renderer), wrapped in an `EdgeCard`.
- Provenance chip: LOCAL KNOWLEDGE / CLOUD ESCALATION, + "Grounded in N
  sources" where N is `sources.size` (never a fabricated confidence/%).
Loading uses an indeterminate indicator + the real stage label (never fake
progress/streaming). Insufficient-evidence is a distinct, non-error surface
that refuses to invent an answer. Errors (real `RagError`) are separated from
insufficiency and offer retry.

## 6. Sufficiency behavior

Rendered straight from `AnswerStatus.INSUFFICIENT_EVIDENCE` → a "NOT ENOUGH
EVIDENCE" card that shows any real weak evidence the pipeline actually
returned and never produces a substitute answer. The 0.25 threshold is untouched.

## 7. Citation / evidence UI

`EvidenceSection` renders one compact `EvidenceCard` per real
`SourceReference`: `[n]`, title, type · source · page · section · chunk ·
chunk-id, and the snippet. Empty fields are omitted (no invented rows). Tapping
a card pushes `EdgeRoute.CitationDetail(sourceIndex)`.

## 8. Citation detail

`CitationDetailScreen` resolves the retained `AskUiState` (source by index +
matching `EvidenceItem`) — nothing is copied through navigation args, so there
is no duplicated state. It shows LOCATION, RETRIEVED CONTENT (real record
content), RETRIEVAL BASIS (rank, fused score, real dense/keyword scores,
matched terms), and a user-meaningful RECORD section (record id, subject,
version, sync state, origin, tags). It shows NO storage internals (no JNI
handles, shard names, or Rust structs). If the Ask result was reset before the
detail opened, it renders an honest "no longer available" empty state.
Back returns to the retained answer.

## 9. Offline behavior

Offline is informational, not a gate. The Ask screen is fully usable offline:
the local Qdrant retrieval + extractive answer path answers with no network
(pinned by `GroundedAskEndToEndTest`, which runs offline). Cloud escalation
only ever happens when the real `CloudEscalation` state says so; when
insufficient locally and offline, the pipeline returns
`CloudEscalation.Offline` and the UI honestly reports the limitation while
keeping local knowledge usable.

## 10. Cloud escalation behavior

Escalation is surfaced only from the real `escalation` field:
`RETRIEVING → (local insufficient) → ESCALATING → CLOUD ESCALATION`
provenance, plus a factual note that a cloud answer is not local evidence and
is not auto-stored. The existing explicit `saveToMemory()` path (conflict-
prevented / already-present / saved) is unchanged and still the only way a
cloud answer may localize. Offline / Unavailable are reported distinctly,
never as fake answers. Sync status remains separate from answer provenance.

## 11. Asset-context behavior (and the honest gap)

Machine Detail → "Ask about this asset" routes to the SAME Ask screen via
`EdgeNavigator.openAsk(assetNamespace)` (no second Ask surface). The
namespace is appended to the executed query so the real identifier-weighted
retrieval is grounded on the asset.

**Domain gap (documented, not faked):** the frozen 13.3 retrieval exposes no
hard asset-scope filter (the `_subject_key` keyword index matches whole values
only — no namespace prefix search — and adding one would change retrieval
semantics / persistence, which this phase forbids). So the UI does NOT claim
"results are filtered to this asset"; it shows the executed query verbatim.
A real scoped-retrieval API is a Phase-13-domain / later-phase item, not
something the UI should fake.

## 12. Navigation

`EdgeRoute.CitationDetail(sourceIndex)` added (tab-mapped to ASK). Deterministic
push/pop; `openAsk(namespace)` sets `navigator.askAsset` and lands on the Ask
root (tab re-selection clears the context); `clearAskAsset()` clears without
leaving Ask. Back from a citation returns to the retained answer. Existing
tab/collapse-stack behavior and the Phase-1 shell tests are unchanged.

## 13. Tests (17 new; all existing remain green)

JVM (`AskViewModelPhase2Test`, 6): asset token appended to the real query and
never duplicated when already present; clear drops context; `retry()`
re-executes verbatim after an error; provenance derived only from real
escalation (local/cloud/offline-insufficient); source count from real citations
only (no fabricated confidence).

`EdgeNavigatorPhase2Test` (6): asset-context openAsk, tab reselect clears,
citation push/pop/dedupe determinism, machine-detail→ask navigation.

`AskPipelineInsufficientDiagnosticTest` (1, real Qdrant): an irrelevant
question yields `INSUFFICIENT_EVIDENCE` with every cited source backed by a
real stored record.

`GroundedAskEndToEndTest` (4, Robolectric + Compose + real `AppContainer`):
1) seeded P-101 records answered through the REAL pipeline with traceable
citations opened to full detail and deterministic back/new-question; 2) an
unsupported question shows honest INSUFFICIENT (not an error, no answer card,
no fabricated citations) and stays usable offline; 3) suggestion prompts fill
the composer WITHOUT submitting; 4) Machine Detail opens the shared Ask with
the asset chip and the executed query visibly includes the asset. No answer,
source, score, or service is faked. Architecture guard (`Room`/`local_qdrant`
non-creation) and all Phase-1 shell tests remain green.

### Verification totals (UI Phase 2)

- Android **526/526** (509 + 17 new; 0 skipped, 0 weakened).
- Presentation package: 67/67. Rust **10/10**. Backend **35/35**
  (untouched). lintDebug **0 errors** (29 pre-existing warnings, none from
  phase files). assembleDebug **SUCCESSFUL**.

## 14. Known limitations

1. No hard asset-scoped retrieval (honest gap in §11); asset grounding is by
   query token only.
2. Cloud escalation cannot be exercised against a live backend here
   (credential-gated); the escalation UI states are verified via the real
   domain contract and the offline/Unavailable branches.
3. History is session-only by design (no persistent question history was
   added, per the phase rules and the absence of an existing history
   mechanism / allowed persistence).

## 15. Deferred work

- Real scoped/asset-domain read API + typed machine model (later data phase).
- Maintenance timeline, document-ingestion UI, conflict-resolution actions UI,
  analytics, and final demo polish — explicitly out of this phase.
