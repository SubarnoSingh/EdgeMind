# WORKING.md — EdgeMind Current Implementation State

Destination skeleton — all claims are verified and reproducible via the
commands below unless explicitly marked NOT VERIFIED.

---

## UI redesign + repair pass (presentation layer, uncommitted)

### Previous pass (kept)

- **Design system** — `ui/theme/`: EdgeMind light palette + deliberate dark
  palette (charcoal `#0D1017`, surfaces `#151A24`/`#1D2433`, periwinkle
  `#A8B6F4`), Material dynamic color OFF, new typography scale,
  `LocalEdgeColors` for positive/code/accent tokens.
- **Component library** — `presentation/components/EdgeComponents.kt`
  (EdgeCard/EdgeCardSecondary, StatusChip, PillButton/TonalPill/IconPill,
  ModeSwitch, SectionHeader, TechLabel, locally drawn Sun/Moon/Settings
  icons).
- **Answer renderer** — `AnswerMarkdown.kt`: commonmark AST → Compose +
  `[n]` citation highlighting + LaTeX→Unicode math layer. Pinned by
  `MarkdownMathTest`.
- **MemoryScreen** — single LazyColumn: search, Capture/Import/Pull
  cloud/Conflicts pills, live policy preview, ingestion progress, sync
  summary, memory cards with provenance/policy chips. Functionality
  unchanged.
- `app_name` → "EdgeMind"; unused template `colors.xml` removed.

### Repair pass (this session)

- **Ask conversation state fixed** — `AskUiState` gained
  `submittedQuestion` (the submitted query) separated from `question` (the
  live input text). `AskViewModel.ask()` moves the query into
  `submittedQuestion` and clears the input, so the user message is rendered
  exactly once, above the answer, and survives scrolling/recomposition.
  New tests: `askMovesQuestionIntoConversationAndClearsInput`,
  `errorPathStillKeepsTheSubmittedQuestionVisible`; `clearResetsToIdle`
  also asserts the conversation is reset.
- **AskScreen conversation** — question bubble → busy indicator → answer
  card → sources → "New question", all as stable keyed LazyColumn items;
  auto-scroll follows the newest conversation content only (input edits no
  longer trigger scrolling); greeting now persists while typing and only
  yields to the conversation after submit; greeting hour is computed per
  recomposition (no stale `remember`).
- **Floating header** — `EdgeTopBar` is no longer one full-width capsule:
  three separate floating controls — circular theme button (left), truly
  centered Ask|Memory ModeSwitch, circular settings button (right) — each
  with its own tinted surface, hairline border and gentle shadow. The theme
  control uses a neutral onSurface-α container that stays visible in both
  themes; ModeSwitch carries semantics (contentDescription/selected) and a
  44dp touch target.
- **Gradients tuned** — light base `#F1F4FA` (cool white) with five large
  low-alpha radial fields (blue, lavender/periwinkle, pink/lilac, pale
  cyan, cool-white lift); dark base `#0D1017` with five fields (indigo,
  violet, deep blue, restrained magenta/lilac, bottom depth wash). Still
  near-flat at first glance by design.
- **Theme/system-bar consistency** — `MainActivity` syncs status/nav-bar
  icon appearance with the in-app toggle via `WindowCompat` insets
  controller, and paints the launch window the saved theme color before
  Compose draws (no white flash in dark mode). `values/themes.xml` and
  `values-night/themes.xml` carry matching `android:windowBackground`.
- **Settings reorganized** — Appearance → Personalization (Name field,
  persisted in `SharedPreferences("edgemind_ui", "profile_name")`, never
  touches the memory DB) → Memory → Synchronization → Ask behavior →
  About. Greeting uses the stored name (`greetingFor`), generic when blank.

### Stale Memory-tab state fix (after 30-point device verification)

- **Defect**: after "Save to memory" on the Ask screen, the Memory tab showed
  "0 memories" until Pull cloud (or another VM action) reloaded it. Data was
  never lost (Room + qdrant persisted correctly); the Activity-scoped
  `MemoryViewModel` simply never observed repository mutations made by other
  paths (`CacheCloudAnswerUseCase` → `CloudKnowledgeWriter`).
- **Fix**: `MemoryScreen` reloads via `LaunchedEffect(Unit) {
  viewModel.refresh() }` every time it becomes visible (tab switch / return
  from Settings). No polling, no delays, no schema or architecture changes.
- **Regression tests**: `MemoryViewModelRefreshTest` (4 tests, real Room +
  real qdrant-edge + real embedding): saved-cloud-answer appears after
  refresh; repeated refresh never duplicates cards; delete/create stay
  correct across refreshes; cloud pull updates the list and survives a
  follow-up refresh.
- Host suite: **244 tests, 0 failures** (was 240). `:app:assembleDebug` and
  `:app:lintDebug` BUILD SUCCESSFUL.
- **Device re-verified (Xiaomi 2109119DI, live backend + real cloud LLM)**:
  Ask → cloud question (quantum) → "Cloud answer · not verified locally" →
  Save to memory → Memory tab immediately showed "3 memories · on this
  device" with the new card (title/type/origin/policy/relative time correct),
  no pull needed; tab cycling produced no duplicate cards; theme toggle,
  greeting, input clearing, local RAG answer + citations, sync summary all
  re-checked; zero crashes.

### NOT VERIFIED (this session)

- **Physical device** — `adb devices` is empty; no phone attached to this
  machine (USB bus shows only mouse/keyboard/camera). The checklist
  (dark/light landing, toggle visibility, header alignment, keyboard +
  long/short query, name persistence across restart, local/cloud answer,
  save, memory, sync) was NOT re-run after this pass. All changes are
  unit-tested and build/lint-clean only.

### Native fix from the previous pass (kept, non-UI)

- **Defect**: Qdrant Edge did NOT persist vectors across Android process
  restarts. `EdgeShard` has no WAL replay on load; it persists only on
  graceful Drop or explicit flush, and Android process deaths run neither.
  Verified on two physical devices (OPPO CPH2723 + Xiaomi 2109119DI).
- **Fix**: `store::flush` exported as `nativeFlush` via JNI;
  `QdrantEdgeVectorStore` flushes after every upsert batch and delete.
  Pinned by `QdrantEdgePersistenceTest.vectorSurvivesWithoutGracefulClose`.
- Host suite: **240 tests, 0 failures** (was 238; +2 Ask conversation
  tests). `:app:assembleDebug` and `:app:lintDebug` BUILD SUCCESSFUL.

---

## Phase 1 — Qdrant Edge spike (complete)


### Verified working

The chain

```text
Java/Kotlin (JNI)
  → Rust cdylib (libedgememo_qdrant.so)
  → qdrant-edge 0.8.0
  → EdgeShard
  → create → upsert → search → close → reopen → search
```

is proven on the **host JVM** (Linux x86-64, JUnit 4.13.2). Persistence across
**process restart** is proven: a child JVM writes vectors, a different child
JVM process opens the same directory and retrieves them.

Gradable verification (5 JUnit tests, all passing):

```text
vectorSurvivesReopenWithinSameProcess   create→upsert(3)→search→close→open→search
vectorSurvivesSeparateProcessRestart    process A write, process B open+search
searchOnEmptyShardReturnsNothing        empty shard → 0 count, empty results
dimensionMismatchFails                  4-dim shard rejects 2-dim vector (QdrantNativeException)
deleteRemovesPoint                      upsert→count 1→delete→count 0
```

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
```

### Android packaging verified at build level (runtime NOT VERIFIED)

- Rust cross-compiles cleanly for both targets with NDK 27.2.12479018:
  - `aarch64-linux-android` → `arm64-v8a` (for Android 24)
  - `x86_64-linux-android` → `x86_64` (for Android 24)
- `./gradlew :app:assembleDebug` bundles both `.so` files into the APK:
  - `lib/arm64-v8a/libedgememo_qdrant.so`
  - `lib/x86_64/libedgememo_qdrant.so`
- Empty-hosted Gradle, no Android emulator/device attached.

**ANDROID RUNTIME: NOT VERIFIED** — no Android device/emulator is attached on
this machine (`adb devices` is empty; `adb` itself works). Nothing has been
loaded on a real Android runtime.

### Build/test commands that work today

```bash
# host native build
cargo build --release --manifest-path rust/edgememo_qdrant/Cargo.toml

# Android cross builds
export PATH="$HOME/.cargo/bin:$PATH"   # rustup-managed toolchain
export NDK=/opt/android-sdk/ndk/27.2.12479018
export LLVM=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
cargo build --release --target aarch64-linux-android \
  -C ... # via env, see app/build.gradle.kts cargoBuildAndroid_* tasks

# full surface
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

### Artifacts

- `rust/edgememo_qdrant/` — Rust crate (`store.rs`, `jni.rs`, `error.rs`)
- `app/src/main/java/com/example/EdgeMemo/native/qdrant/` —
  `LocalVectorStore` (interface, per AGENTS.md), `QdrantEdgeVectorStore`
  (implementation), `NativeBridge` (external fns), `SearchResult`,
  `QdrantNativeException`, `VectorPoint`
- `app/src/test/java/com/example/EdgeMemo/native/qdrant/QdrantEdgePersistenceTest.kt`
- `app/build.gradle.kts` — `cargoBuildHost`, `copyHostNativeLibrary`,
  `cargoBuildAndroid_arm64_v8a`/`cargoBuildAndroid_x86_64`,
  `copyAndroidLib_*`, `abiFilters { arm64-v8a, x86_64 }`

---

## Phase 2 — Persistent local memory (complete)

### Verified working (host JVM + Robolectric, 29 unit tests, 0 failures)

The offline slice

```text
Compose UI
  → MemoryViewModel (StateFlow)
  → use cases
  → DefaultMemoryRepository
  → EmbeddingService (real, deterministic)
  → QdrantEdgeVectorStore (real .so) + Room metadata
```

is implemented and proven end to end by tests using **real Room**, the
**real host qdrant-edge `.so`**, and the **real embedding service** (no mocks
of storage or vectors).

- **Memory model** — `Memory` with the full spec field set (memoryId, title,
  content, chunkId, source, type, tags, createdAt, updatedAt, origin,
  syncDecision, syncState, sensitivity, importance, version, contentHash,
  subjectKey, supersedes, tombstone, metadata) plus `MemoryType`,
  `MemoryOrigin`, `SyncDecision`, `MemorySyncState`, `MemorySensitivity`,
  `CreateMemoryInput`, `RetrievedMemory`; typed `EdgeError` hierarchy.
- **Room metadata** — `MemoryEntity`, `MemoryDao`, `EdgeMindDatabase` (v1),
  `MemoryTypeConverters`, `MemoryMappers` (KSP2 + Room 2.7.1).
- **Offline embedding** — `EmbeddingService` +
  `FeatureHashingEmbeddingService`: deterministic hashing-trick embedding over
  word unigrams/bigrams and char n-grams, 512-dim, L2-normalized. **Lexical
  baseline, not a neural model**; swappable behind the interface.
- **Qdrant boundary** — `LocalVectorStore` extended with
  `open()`/`close()`/`ensureReady(dimension)`. `QdrantEdgeVectorStore`
  creates a shard when `edge_config.json` is absent, otherwise reopens it.
- **Repository** — `DefaultMemoryRepository`: normalize → embed →
  `ensureReady` → Qdrant upsert → Room insert, with best-effort vector
  rollback if the Room insert fails. Search embeds the query, does a Qdrant
  dense search, then maps point ids back to Room rows (tombstoned rows
  filtered), returning `RetrievedMemory` with scores.
- **DI / app wiring** — `AppContainer` (Room `edge-memory.db`,
  `QdrantEdgeVectorStore(filesDir/local_qdrant)`, shared embedding service,
  use cases, `viewModelFactory`), `EdgeMindApplication`, `MainActivity` →
  `MemoryScreen` (Material 3: create, type chips, semantic search, list).
- **Manifest** — `android:name=".EdgeMindApplication"`; no `INTERNET`
  permission (asserted by test D).

Gradable verification (all passing):

```text
FeatureHashingEmbeddingServiceTest   6  determinism, dimension, unit norm, related>unrelated, id overlap, blank rejected
MemoryMappersTest                    3  full/optional round trip, converter round trip
DefaultMemoryRepositoryTest         12  A create, B semantic ranking, C Room+shard restart,
                                        D no INTERNET permission, E metadata equality,
                                        F1 blank, F2 Room-failure rollback, F3 embedding failure,
                                        F4 vector-failure ordering, delete, update
MemoryViewModelTest                  3  create/clear draft, semantic search, delete
QdrantEdgePersistenceTest            5  Phase 1 chain (unchanged semantics)
ExampleUnitTest                      1
```

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

### Fixed during Phase 2

- `MemoryViewModel.create()`/`delete()` used to fire `refresh()` as a separate
  coroutine, racing the write and occasionally reading pre-write state. The
  reload now runs *after* the mutation completes, and the displayed count is
  derived from the loaded (tombstone-filtered) list.
- KSP + AGP 9 built-in Kotlin requires
  `android.disallowKotlinSourceSets=false` in `gradle.properties`.
- Host `.so` is loaded through a shared `TestNativeLoader`; the Phase 1
  persistence test now runs under Robolectric so all native tests in the unit
  test JVM share one classloader (`System.load` cannot load the same library
  from two classloaders).

> Note: the Phase 2 manifest bullet above ("no `INTERNET` permission") was true
> through Phase 5. Phase 6 added the `INTERNET` permission for the sync worker;
> test D was updated from "no network permission" to
> `offlineMemoryPathIsIndependentOfNetworkExecution` (asserts presence), because
> a permission in the manifest does not make local memory network-dependent.

### Android runtime NOT VERIFIED

No Android device/emulator is attached on this machine (`adb devices` is
empty), so the Phase 2 UI/repository path has not been exercised on a device or
emulator. The debug APK builds and bundles both `.so` ABIs, but on-device
behavior is unproven here.

---


## Phase 3 — Offline document ingestion (complete at host/Robolectric level)

### Verified working (host JVM + Robolectric, 54 unit tests, 0 failures)

The offline ingestion slice

```text
SAF pick (Android document API)
  → ContentResolverDocumentReader (bytes + metadata, no network)
  → DocumentExtractor (real PDF / Markdown / TXT parsing)
  → TextNormalizer → DocumentChunker (deterministic, page/section aware)
  → DocumentIngestionService
  → DefaultMemoryRepository.createAll (embed → Qdrant Edge upsert → Room insert, atomic)
  → Memory rows of type DOCUMENT, searchable offline
```

is implemented and proven end to end by tests using **real Room**, the **real
host qdrant-edge `.so`**, the **real embedding service**, and **real PDFBox /
commonmark parsing** (no mocks of storage, vectors, or extraction).

- **Document model** — `DocumentSource`, `DocumentBlock`/`ExtractedDocument`,
  `DocumentChunk`, `TextNormalizer`, `DocumentChunker`.
- **Extractors** — `DocumentExtractor` interface + `TxtDocumentExtractor`
  (`text/plain`), `MarkdownDocumentExtractor` (commonmark 0.21.0, tracks
  heading path as `section`), `PdfDocumentExtractor`
  (pdfbox-android 2.0.27.0, one block per page, real text only),
  `DocumentExtractorRegistry`, `ContentResolverDocumentReader`.
- **Ingestion** — `DocumentIngestionService` / `IngestDocumentUseCase` emit
  real stages (`EXTRACTING → CHUNKING → EMBEDDING → STORING → COMPLETED`,
  `FAILED` on error). Each chunk becomes a `Memory` with `type = DOCUMENT`,
  `source = <display name>`, `chunkId = "<documentId>#<index>"`, and metadata
  (`documentId`, `documentTitle`, `sourceUri`, `sourceName`, `chunkIndex`,
  `chunkCount`, `format`, `page?`, `section?`).
- **Atomic batch write** — `MemoryRepository.createAll(inputs, onPhase)`:
  embeds all chunks, upserts all vectors, then inserts all rows; if the Room
  insert fails it deletes the batch's vectors and reports
  `LocalStorageError`. `MemoryDao.insertAll` / `deleteByIds` added.
  `CreateMemoryInput` gained optional `chunkId` / `metadata` (backward
  compatible).
- **UI** — `MemoryScreen` gained an "Import document" card using
  `ActivityResultContracts.OpenDocument` (no new permissions) that shows the
  live stage and chunk count; `MemoryCard` shows source/page/section/chunk for
  DOCUMENT rows. `MemoryUiState` gained `DocumentIngestionState`.

Gradable verification (all passing):

```text
TextNormalizerTest                   4  CRLF/CR, whitespace+control collapse, BOM, inline
DocumentChunkerTest                  5  empty/blank, page+section preserved, bounded long text,
                                        overlap, determinism
TxtDocumentExtractorTest             3  paragraphs+title, blank rejected, supports by mime/ext
MarkdownDocumentExtractorTest        3  heading sections + inline text, title fallback, empty rejected
PdfDocumentExtractorTest             2  real per-page text extraction, image-only rejected (Robolectric)
DocumentIngestionServiceTest         8  txt ingest+searchable+chunk ids, markdown sections,
                                        pdf page metadata, stage order, unsupported type,
                                        empty file, batch vector rollback, survives restart
(default) previously Phase 1+2 tests  29
```

Total: **54 tests, 0 failures.**

### PDF / document limitations (explicit)

- **Image-only / scanned PDFs are not supported.** PDFBox-Android performs text
  extraction only; there is no OCR. Such files fail with
  `EdgeError.InvalidDocument` and an explicit "image-only or scanned" message.
- TXT is decoded as UTF-8 (BOM stripped); other encodings are not detected.
- Markdown images/links contribute their inline text; images are not fetched.

### Fixed during Phase 3

- `DocumentChunker` overlap could exceed `maxChars` when a seeded overlap tail
  was combined with a large next unit; the overlap is now dropped unless
  `overlap + 1 + unit` fits within `maxChars`.

### Android runtime NOT VERIFIED (Phase 3)

Same `adb` limitation as Phases 1–2: the SAF picker, `ContentResolver` reads,
and on-device PDF extraction have **not** been exercised on a device or
emulator. They are covered by Robolectric tests only. The debug APK builds,
bundles both `.so` ABIs, and packages the PDFBox-Android resource assets.

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

---

## Phase 4 — Local retrieval + grounded RAG (complete at host/Robolectric level)

### Verified working (host JVM + Robolectric, 87 unit tests, 0 failures)

The offline retrieval/RAG slice

```text
Compose ASK UI
  → AskViewModel (StateFlow, real phases)
  → AskQuestionUseCase → RagService (DefaultRagService)
  → RetrievalService (DefaultRetrievalService)
  → dense: EmbeddingService → Qdrant Edge (.so) search
  → keyword: Room LOWER LIKE per term
  → ReciprocalRankFusion → resolve → filter → dedup → rank → top-K evidence
  → LLMService (extractive fallback, verbatim sentences + [n] citations)
  → grounded answer + real sources
```

is implemented and proven end to end by tests using **real Room**, the **real
host qdrant-edge `.so`**, and the **real embedding service** (no mocks of
storage, vectors, or embedding).

- **Retrieval models** — `core/retrieval/`: `QueryNormalizer` (deterministic
  normalization/tokenization, stopword removal, identifier detection),
  `ReciprocalRankFusion` (RRF with K=60, deterministic tie-break by id,
  scale-free across dense & keyword scores), `RetrievalService`,
  `RetrievalQuery`/`RetrievalOptions`/`RetrievalResult`/`EvidenceItem`.
- **Hybrid retrieval** — `DefaultRetrievalService`: normalize → embed → Qdrant
  dense search + `KeywordRetriever` (case-insensitive Room `LOWER LIKE` on
  title/content, identifiers like `p-101`/`skf-6205` weighted 2.0 vs 1.0) →
  RRF fusion → resolve point ids to rows → exclude tombstones and
  `supersededIds` → dedup same `contentHash` and `documentId#chunkIndex` →
  rank (1-based) → top-K `EvidenceItem` (memory + dense/keyword/fused scores +
  matched terms). `RetrievalOptions.useHybrid = false` runs dense-only.
- **DAO additions** — `MemoryDao.searchByKeyword(term, limit)` (tombstone-free,
  case-insensitive) and `MemoryDao.supersededIds()` (active superseded ids).
- **RAG** — `core/rag/` `AnswerStatus`, `RagStage` (RETRIEVING → GENERATING,
  no fabricated progress percentages), `SourceReference` (index ↔ `[n]`,
  memoryId, chunkId, source, page/section, score, snippet), `RagError`.
  `DefaultRagService`: empty question → `EmptyQuestion`; retrieval failure →
  `RetrievalFailed`; sufficiency gate = keyword match OR top dense score
  ≥ `DEFAULT_MIN_DENSE_SCORE` (0.25, calibration-tested); guarantees no
  fabrication — an unanswerable query returns the explicit
  `INSUFFICIENT_MESSAGE` and no invented answer.
- **Answer engine** — `ai/llm/LLMService` interface (swappable later for an
  on-device LLM) + `ExtractiveLLMService`: answers composed **only** from
  verbatim evidence sentences that overlap the question (question-token
  overlap, no paraphrase), each with a `[n]` citation; falls back to the top
  evidence's first sentence verbatim. `AskQuestionUseCase` / `RetrieveMemoriesUseCase`.
- **ASK UI** — `presentation/ask/`: `AskUiState`/`AskViewModel` with real
  phases (`IDLE / RETRIEVING / GENERATING / SUCCESS / INSUFFICIENT / ERROR`),
  question field, answer, `SourceReference` cards ("Inspect evidence" expands
  snippet + page/section/source), insufficient-evidence message, error text,
  `clear()`. `MainActivity` now hosts ASK + MEMORY tabs over the existing
  memory screen.
- **Metadata keys** — shared `core/model/MemoryMetadataKeys`; phase 3
  `DocumentIngestionService` constants delegate to it (same values).

Gradable verification (all passing, 87 total):

```text
QueryNormalizerTest                 4+c  normalization, tokens, stopwords, identifier detection
ReciprocalRankFusionTest           4    hybrid win, rank contribution, tie-break determinism
ExtractiveLLMServiceTest           5    verbatim + [n] citations, empty, fallback, maxSentences,
                                        never fabricates
DefaultRetrievalServiceTest        9    dense resolve+rank, keyword identifier (SKF-6205), tombstone
                                        excluded, superseded excluded, duplicate content dedup,
                                        chunk source metadata, restart persistence, empty query,
                                        hybrid off
DefaultRagServiceTest              5    grounded answer+citations+sources, insufficient explicit,
                                        empty question, retrieval failure, generation failure
AskViewModelTest                   6    stages→success, insufficient, error, blank no-op,
                                        source inspector toggle, clear
```

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

### Phase 4 limitations (explicit)

- Answer generation is **extractive/controlled**: it quotes retrieved sentences
  verbatim with citations. It is not an on-device neural LLM; `LLMService` is
  the extension point for one. It never paraphrases from model knowledge.
- Keyword/exact retrieval is a Room `LIKE` scan (N terms × index misses), not
  BM25 or a full-text index — intentionally lightweight per spec Phase 4.
- `DEFAULT_MIN_DENSE_SCORE = 0.25` is the sufficiency gate and is
  calibration-tested against related/unrelated queries via the RAG integration
  tests.

### Android runtime NOT VERIFIED (Phase 4)

Same `adb` limitation as Phases 1–3: the ASK screen, ViewModel wiring, and
native retrieval have **not** been exercised on a device or emulator. Covered
by Robolectric tests only; the debug APK builds and bundles both `.so` ABIs.

---

## Phase 5 — Policy + privacy (LOCAL_ONLY / SYNC / SYNC_REDACTED)

### Verified working (host JVM + Robolectric, 123 unit tests, 0 failures)

The policy slice

```text
Capture/Update
  → PolicyInput (title, content, type, tags, sensitivity, importance, scope, userSyncChoice)
  → DefaultPolicyEngine (ordered, deterministic rules)
  → PolicyDecision (decision + human-readable reason [+ redacted representation])
  → persisted on Memory & Room (policyReason, redactedTitle, redactedContent)
  → SyncPayloadFactory = the ONLY sync representation (Phase 6 will consume it)
```

is implemented and proven end to end by tests using **real Room**, the **real
host qdrant-edge `.so`**, and the **real policy engine** (no mocks of policy,
storage, or vectors).

- **Models** — `core/policy/PolicyModels.kt`: `PolicyInput`, `PolicyDecision`,
  `RedactedRepresentation`, `SyncPayload`, and `SyncPayloadFactory` — the
  architectural privacy boundary. `LOCAL_ONLY` → no payload; `SYNC` → original
  text; `SYNC_REDACTED` → the stored redacted representation only, and `null`
  (never the private original) if the redaction is missing.
- **Policy engine** — `domain/policy/PolicyEngine` (deterministic,
  synchronous) + `data/policy/DefaultPolicyEngine` with ordered rules:
  1. HARD `LOCAL_ONLY`: access/credential phrases (gate code, access code,
     passcode, password, pin, security code, credentials), `RESTRICTED`
     sensitivity, `personal` scope, or `local-only`/`private`/`personal` tags —
     **never overridable** (privacy invariant is architectural).
  2. Explicit user choice (`SYNC` / `SYNC_REDACTED`).
  3. `SENSITIVE` → `SYNC_REDACTED`.
  4. PROCEDURE / DOCUMENT → `SYNC` (team-shareable).
  5. REPAIR → `SYNC_REDACTED`.
  6. Technical identifiers (`p-101`, `skf-6205`, ...) in content →
     `SYNC_REDACTED`.
  7. Safe default for everything else → `LOCAL_ONLY`.
  Every decision persists a deterministic, explainable reason.
- **Redaction** — `domain/policy/RedactionService` +
  `data/policy/DefaultRedactionService`: conservative, deterministic,
  no-randomness; strips role-name candidates (`Technician John`), e-mails,
  standalone 4+ digit runs, and mixed letter+digit technical identifiers
  (hyphen/underscore/slash aware); records removed tokens; never mutates the
  original input. Prose is preserved.
- **Persistence** — `Memory`/`MemoryEntity`/`MemoryMappers` gained
  `policyReason`, `redactedTitle`, `redactedContent`; Room bumped to **version
  2** with `MIGRATION_1_2` (three `ALTER TABLE` adds), wired in `AppContainer`.
  `DefaultMemoryRepository` now evaluates policy on create and re-evaluates on
  update (prior decision is treated as the user's retained choice; hard
  privacy rules still win). `scope` is remembered in metadata under `"scope"`.
- **UI** — `MemoryScreen` capture card shows a live "Sync policy" preview
  (decision + reason + auto/LOCAL_ONLY/SYNC/SYNC_REDACTED choice chips) before
  saving; `MemoryCard` shows `policy · …` with eligibility
  (`stays on device` / `sync eligible` / `syncs redacted copy`), the reason,
  and the stored redacted representation for `SYNC_REDACTED` rows.
  `MemoryViewModel` exposes the draft preview and sync-choice setter.

Gradable verification (all passing, 123 total):

```text
DefaultPolicyEngineTest            14   gate code/credentials LOCAL_ONLY + reason, RESTRICTED, personal scope,
                                           local-only tag, PROCEDURE/DOCUMENT SYNC, SENSITIVE→SYNC_REDACTED,
                                           REPAIR+identifier→SYNC_REDACTED, note identifier→SYNC_REDACTED,
                                           generic NOTE→LOCAL_ONLY, user choice honored, hard rule beats user
                                           choice, determinism
DefaultRedactionServiceTest         8    determinism, role-name, e-mail, digit runs, identifiers, prose
                                           preserved, input never mutated, dedup removed tokens
SyncPayloadFactoryTest              5    LOCAL_ONLY null, SYNC original, SYNC_REDACTED redacted only
                                           (no private text), no fallback when redaction missing,
                                           no fallback when only content redacted
DefaultMemoryRepositoryPolicyTest   7    procedure SYNC+reason persisted, gate code LOCAL_ONLY + still
                                           retrievable offline, REPAIR keeps original + redacts, policy
                                           survives Room+shard restart, update re-evaluates + hard rule wins,
                                           user choice preserved across create/update then overridden,
                                           default NOTE LOCAL_ONLY + searchable
EdgeMindDatabaseMigrationTest       2    hand-built v1 db + row migrates to v2 (no fabricated policy
                                           state), fresh db opens at v2
```

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

### Phase 5 guarantees that are actually enforced by tests

- `LOCAL_ONLY` → `SyncPayloadFactory.build()` returns `null` (nothing that a
  Phase 6 outbox could enqueue, hence nothing can leave the device) while the
  memory stays fully searchable/grounded offline with its original text.
- `SYNC_REDACTED` → the sync payload contains only the redacted representation;
  private identifiers never appear in a payload in any tested path, and a
  missing redaction blocks sync instead of leaking the original.
- Deciding is deterministic and every stored decision carries a reason.

### Phase 5 limitations (explicit)

- **Outbox / WorkManager / Qdrant Server sync not implemented** — Phase 6.
  `SyncPayloadFactory` is the boundary those phases must consume; nothing
  leaves the device today.
- `scope` is user-provided free text for now; `SYNC_REDACTED` content is shown
  in the *stored redacted* form in the UI, not a preview of the original.
- Sensitivity is `CreateMemoryInput.sensitivity` (STANDARD default in the UI);
  the auto-classifier ability (spec §23) is untouched.

### Android runtime NOT VERIFIED (Phase 5)

Same `adb` limitation as Phases 1–4: the policy chips/preview and the
`MIGRATION_1_2` path have **not** been exercised on a device or emulator.
Covered by Robolectric tests only; the debug APK builds and bundles both `.so`
ABIs.

---

## Phase 6 — Durable offline outbox + WorkManager sync (complete at host/Robolectric level)

### Verified working (host JVM + Robolectric, 142 unit tests, 0 failures)

The durable sync slice

```text
create/update/delete (repository)
  → RoomSyncOutboxWriter (single withTransaction: memory + outbox)
  → SyncPayloadFactory (ONLY authority: LOCAL_ONLY → no row; SYNC → original;
                        SYNC_REDACTED → redacted representation only)
  → sync_outbox Room table (operationId PK, idempotent "UPSERT-<memoryId>")
  → SyncScheduler.requestSync() — unique work "edgememo-sync", REPLACE,
        NetworkType.CONNECTED constraint, exponential backoff (10 s)
  → SyncWorker (one worker, drains outbox)
  → DefaultSyncEngine.processPending()
        recoverStaleInFlight → select → guarded claim (single winner)
        → remote.push (OUTSIDE any Room transaction)
        → ACK + memory SYNCED (withTransaction) | FAILED/DEAD + classified error
  → SyncRemoteDataSource (Unimplemented: SOURCE_UNAVAILABLE, honest no-op)
```

is implemented and proven end to end by tests using **real Room**, the **real
host qdrant-edge `.so`**, the **real policy engine**, the **real outbox
writer**, and a **real `SyncWorker`** (see limitations below for the remote).

- **Outbox model** — `core/sync/SyncModels.kt`: `OutboxOperationState`
  (PENDING / IN_FLIGHT / ACKED / FAILED / DEAD), `OutboxOperationType` (UPSERT),
  `SyncFailureKind` (SOURCE_UNAVAILABLE / NETWORK / SERVER_TEMPORARY /
  REJECTED / UNAUTHORIZED), `SyncPushResult`, `SyncOperation`, `SyncSummary`
  (pending/syncing/synced/failed/localOnly — every number from real state).
- **Room** — `SyncOutboxEntity` table `sync_outbox`, `SyncOutboxDao`
  (`insertOrIgnore`, latest-wins `refreshPayload` that never clobbers
  `IN_FLIGHT`, `selectRetryable`, `recoverStaleInFlight`, guarded `claim`,
  `markAcknowledged`, `markState`, counts, `deleteByMemoryId`); `MemoryDao.
  updateSyncState`; DB bumped to **version 3** with `MIGRATION_2_3`
  (CREATE TABLE + 2 indices). `operationId = "UPSERT-<memoryId>"` is
  deterministic and stable across retries/re-enqueues.
- **Writer** — `RoomSyncOutboxWriter` writes memory + outbox row **inside one
  `database.withTransaction`** so the two can never diverge. `LOCAL_ONLY` →
  zero rows (and a superseding LOCAL_ONLY update *withdraws* any prior operation);
  SYNC → original; SYNC_REDACTED → redacted only. Re-enqueue refreshes the
  payload (latest wins) and resets to PENDING; it never resets `attempts` and
  never clobbers an in-flight claim. Delete cancels pending operations.
- **Engine** — `DefaultSyncEngine`: crash recovery (`recoverStaleInFlight` —
  a worker that died post-push/pre-ACK re-applies the idempotent operation),
  single-winner guarded claims (concurrent runs never double-process an
  operation), network call always outside Room transactions, ACK only on real
  remote `Success`, retry budget `MAX_ATTEMPTS = 5` → DEAD, permanent failures
  (REJECTED / UNAUTHORIZED) → DEAD immediately, retryable failures
  (NETWORK / SERVER_TEMPORARY / SOURCE_UNAVAILABLE) → FAILED → worker retries.
  `lastError` stores only the `SyncFailureKind` id — never content.
- **WorkManager** — `SyncWorker` (CoroutineWorker; 2-arg ctor resolves the
  engine from `EdgeMindApplication.container.syncEngine`, 3-arg ctor injected
  for tests) returns `retry()` while operations remain pending/failed, `success()`
  when drained. `SyncScheduler` builds a unique one-shot work named
  `edgememo-sync`, `ExistingWorkPolicy.REPLACE`, `NetworkType.CONNECTED`
  constraint, `BackoffPolicy.EXPONENTIAL` 10 s. WorkManager is **not** touched
  during `AppContainer` init (would initialize WorkManager in every Robolectric
  test); the initial sync is requested from `MainActivity.onCreate`, and the
  ViewModel fires requests after successful writes.
- **Remote boundary** — `domain/sync/SyncRemoteDataSource` +
  `data/sync/UnimplementedSyncRemoteDataSource` — an honest Phase 6 no-op that
  returns `SOURCE_UNAVAILABLE` (retryable) so operations stay durably pending
  and are **never** acknowledged without a real backend. No credentials, no
  endpoint, nothing fake. Qdrant Server connection is spec Phase 7.
- **UI** — real persisted sync state only: `MemoryScreen` "Synchronization"
  card shows `local only · pending · syncing · synced · failed` from
  `outboxCounts()` (localOnly computed from the loaded memory list);
  `MemoryCard` renders human labels (`local only` / `pending sync` / `synced` /
  `failed`) from `syncState`. No fabricated metrics.
- **Dependencies** — `androidx.room:room-ktx` 2.7.1 (for `withTransaction`),
  `androidx.work:work-runtime-ktx` 2.10.1, `androidx.work:work-testing`
  (testImplementation). `INTERNET` permission added.

Gradable verification (all passing, 142 total):

```text
OutboxPrivacyTest                7    LOCAL_ONLY → zero rows + local text intact; SYNC → original in
                                        outbox with stable "UPSERT-<id>"; SYNC_REDACTED → redacted only,
                                        no "P-101" in payload title/content; update→LOCAL_ONLY withdraws
                                        prior operation; repeated updates keep ONE row latest-payload;
                                        outbox survives Room+shard restart with identical redacted payload;
                                        local delete cancels pending ops
DefaultSyncEngineTest            8    success→ACKED+memory SYNCED; retryable→FAILED(kind id, never content)
                                        then ACKED on retry; permanent REJECTED→DEAD+memory FAILED; budget
                                        exhaustion→DEAD at MAX_ATTEMPTS+1 with no further pushes; operationId
                                        stable across retries; crash recovery re-applies stale IN_FLIGHT;
                                        concurrent runs process each op exactly once; summary reflects
                                        persisted state
SyncWorkerTest                   3    real worker drains all ops→success; one failed op→worker retry();
                                        scheduled request is CONNECTED-gated with exponential 10 s backoff
EdgeMindDatabaseMigrationTest     3    v1 row migrates through v3 (null policy state, empty outbox);
                                        v2 db with row migrates scarlessly to v3; fresh db opens at v3
(previously) Phases 1–5         123
```

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest   # 142 tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleDebug
```

### Phase 6 guarantees that are actually enforced by tests

- `LOCAL_ONLY` → **zero** outbox rows; a superseding LOCAL_ONLY decision
  removes previously-enqueued operations; the only local original stays
  fully searchable offline.
- `SYNC_REDACTED` → the outbox / network payload contains only the redacted
  representation; private identifiers never appear in a payload in any tested
  path. `lastError` holds only a `SyncFailureKind.id`.
- No operation is ever `ACKED` without a real remote `Success`.
- Durable operation ids + guarded claims make concurrent and crashed workers
  safe: every op is processed exactly once per test, and stale `IN_FLIGHT`
  rows are recovered and re-applied after a restart.
- The outbox is genuinely durable: rows and payloads survive a closing/reopening
  of the Room database and the qdrant shard.

### Phase 6 limitations (explicit)

- **The remote is `UnimplementedSyncRemoteDataSource`.** Real Qdrant Server /
  cloud synchronization is spec **Phase 7**; until then every push reports
  `SOURCE_UNAVAILABLE` (retryable), so operations remain durably pending and
  the worker legitimately keeps retrying with backoff while offline.
  `cloud → edge` pull, conflict detection/resolution, cloud LLM escalation and
  sync dashboards are also later phases.
- Server-side idempotency keyed on `operationId` is a Phase 7 requirement;
  it is designed for (stable id) and documented in `DefaultSyncEngine`, but a
  real backend has not yet acknowledged it.
- `attempts` is not reset by re-enqueues (by design — retry budget is
  monotonic); updates during FAILED identically refresh payload and go back to
  PENDING.

### Android runtime NOT VERIFIED (Phase 6)

Same `adb` limitation as Phases 1–5: `WorkManager` job scheduling, the
connectivity constraint, and on-device worker execution have **not** been
exercised on a device or emulator. The worker path is covered by Robolectric +
`work-testing` tests against real Room only; the debug APK builds and bundles
both `.so` ABIs.

---

## Phase 7 — Evolving memory: cloud → edge knowledge, conflicts (complete at host/Robolectric level)

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --console=plain :app:testDebugUnitTest`
→ **175 tests, 0 failures** (was 142 at end of Phase 6; +33 net new, including 32 new
tests in three new files). `:app:assembleDebug` and `:app:lintDebug` both `BUILD
SUCCESSFUL`.

### Phase 7 scope (what this slice actually delivers)

Spec §34–37 (evolving memory), §47 (conflicts), §50 (cloud 云 edge knowledge) sliced
to the smallest vertical path:

```text
CloudKnowledgeRemoteDataSource.pullKnowledge(cursor)
        ↓
cursor checkpoint (Room cloud_pull_cursor)
        ↓
classify per item (deterministic, no AI)
        ↓
apply writer (Room metadata + qdrant-edge vector)
        ↓   or
persist conflict (Room conflicts, both sides' evidence)
        ↓
retrieval + local records update; conflicting knowledge stays visible
        ↓
ConflictResolver (keepLocal / keepCloud / dismiss — deterministic)
```

### Verified working (host JVM + Robolectric, 175 tests, 0 failures)

- **Room v4 schema + MIGRATION_3_4** (`authority TEXT` + `conflicts` +
  `cloud_pull_cursor`). Migration test covers v1→v2→v3→v4, v2→v3→v4, v3→v4
  (memory + outbox rows preserved), and a fresh DB opening at v4.
- **`DefaultCloudKnowledgeIngestor`** — durable cursor resume across pulls
  (`requestedCursors == [null, "cursor-1"]` verified); checkpoint cleared when
  cloud returns no next cursor.
- **`DefaultKnowledgeClassifier`** branching order verified:
  NEW / TOMBSTONE / SUPERSEDES / DUPLICATE (hash) / UPDATE (same id, newer
  version) / CONFLICT / NO_OP, with LOCAL_ONLY protected — a cloud item that
  contradicts a `LOCAL_ONLY` memory **always** becomes CONFLICT, never overwrites.
- **Cloud knowledge records** land with `origin=CLOUD`, `type=CLOUD_KNOWLEDGE`,
  `syncState=SYNCED`, `syncDecision=SYNC`, `authority` stored, and are
  **semantically searchable offline** (real deterministic embedding + real
  qdrant-edge shard, vector insert before and after shard restart).
- **In-place update** replaces the Room row and the shard point (upsert REPLACE,
  `count()==1`, no duplicate vector).
- **Supersede** tombstones the target (`supersededIds()` excludes it); the real
  `DefaultRetrievalService` pipeline returns only the superseding record.
- **Cloud tombstone** deletes the vector + marks the row tombstoned; it is no
  longer retrievable and serves no search results.
- **Conflict persistence + idempotence** — re-pulling the same contradiction
  creates exactly one unresolved conflict row (`insertOrIgnore` on the
  deterministic SHA-256-derived `conflictId` keyed on
  `subject|localId|localHash|incomingId|incomingHash`).
- **Conflict storability** — un-resolved conflicts and resolved-by-KEEP_CLOUD
  records both survive Room + shard restart (two restart tests).
- **`DefaultConflictResolver`** deterministic behavior verified:
  - keepLocal → `RESOLVED_LOCAL`, local record + vector untouched;
  - keepCloud (different id) → `RESOLVED_CLOUD`, incoming active + old local
    tombstoned as history;
  - keepCloud (same id) → overwrite in place (single vector);
  - dismiss → `DISMISSED`, keep-both semantics (evidence stays in the conflict
    row);
  - **no `MERGE`** — a merged-reconciliation path is explicitly not implemented;
  - double resolution of the same conflict id is rejected.
- **Outbox invariant** — no Phase 7 path (apply, update, supersede, tombstone,
  conflict, mixed batch) ever inserts an outbox operation; cloud knowledge is
  never pushed back to the cloud.
- **Cloud-origin UI** — Memory screen shows conflicts (unresolved badge count,
  expandable list, per-side evidence with origin/authority/version/hash,
  keepLocal/keepCloud/dismiss actions) and a `cloud · v<n> · authority · updated`
  card line for `origin==CLOUD` records. Conflicts live in `MemoryViewModel`
  (no separate ConflictsViewModel), surfaced through `MemoryUiState.conflicts` /
  `unresolvedConflictCount` / `pullStatus`.

### What is NOT VERIFIED yet

- **Real cloud connectivity** — `UnimplementedCloudKnowledgeRemoteDataSource`
  throws `EdgeError.CloudUnavailable`; the pull path is exercised only against
  explicit in-test fixture remotes (`ScriptedRemote`), never a fake "real"
  endpoint. Qdrant Server IPv4-ID + FT-983.3 knowledge (FT-983.3-100, spec §53)
  is out of scope here.
- **Android runtime** — same `adb` limitation as Phases 1–6. The database
  migration runs on-device at app open (WORKING.md Phase 2 note applies), the
  memory screen cloud line, pull/conflict UI, and `authority` display have not
  been exercised on a device/emulator. The debug APK builds and bundles both
  `.so` ABIs.
- **Conflict UI resolve actions** on a device (Robolectric-compiled only).

### Known intentional limitations

- Deterministic classification and conflict resolution — **no LLM/AI**, no
  auto-merge, no redacted-cloud variant (SYNC_REDACTED remote knowledge is a
  later phase).
- Where both sides have edited the same cloud-origin record (`origin==CLOUD` on
  both), the incoming cloud row **wins** (hash-or-boundary heuristic) because
  cloud is the system of record for cloud-origin rows; the losing row is
  tombstoned as history rather than silently deleted.
- No merge strategy (explicitly deferred), no conflict notifications/foreground
  work (pulls are on-demand from the memory screen), no per-conflict TTL.

---
## Phase 8 — Edge ↔ Cloud intelligence loop (complete at host/Robolectric level)

`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --console=plain :app:testDebugUnitTest --rerun-tasks`
→ **213 tests, 0 failures, 0 errors** (was 175 at end of Phase 7; +38: 8 escalation
unit, 17 cloud-answer-cache integration, 5 end-to-end loop scenarios, 6 Ask
ViewModel tests, 1 sync-payload factory test, 1 manifest privacy test). The
cache suite includes the security-review regression tests (blank/oversized
rejection, gate-code zero-outbox). `:app:assembleDebug` and `:app:lintDebug`
both `BUILD SUCCESSFUL`.

The lifecycle is now demonstrable as one loop:

```text
Question → local RAG (Phase 4, unchanged)
   ├─ sufficient/HIGH → local grounded answer (NO cloud request)
   └─ INSUFFICIENT (LOW)
        ├─ offline → honest limitation (escalation=Offline)
        └─ online  → ask cloud (QUESTION TEXT ONLY)
             ├─ answered  → attributed cloud answer, provenance shown, NOT stored
             │    └─ explicit [Save to memory] → CloudAnswerCache
             └─ unavailable/error → graceful limitation (escalation=Unavailable)
                      → local CLOUD_KNOWLEDGE memory (origin CLOUD, SYNCED, never in outbox)
                      → becomes retrievable offline in later queries
```

### Verified working (host JVM + Robolectric, 213 tests, 0 failures)

- **`EscalatingRagService`** (decorator over the existing Phase 4 `RagService`):
  - local `ANSWERED`/`ERROR` → returned unchanged, **zero** cloud requests;
  - `INSUFFICIENT_EVIDENCE` + offline → limitation, `CloudEscalation.Offline`,
    no cloud request, no `ESCALATING` stage;
  - `INSUFFICIENT_EVIDENCE` + online → `RagStage.ESCALATING` emitted, question
    sent (question only — never local evidence), cloud answer returned as
    `CloudEscalation.Answered(question, answer, authority)` with local
    sources/evidence cleared (cloud answers are never merged into local evidence);
  - online + `CloudUnavailable`/generic error → limitation with
    `CloudEscalation.Unavailable` (graceful, never faked);
  - connectivity probe failure → treated as offline.
- **`CloudAnswerDataSource` / `CloudAnswer`** — narrow edge→cloud question
  interface; `UnimplementedCloudAnswerDataSource` throws `EdgeError.CloudUnavailable`
  (honest; test fixtures implement the interface, real cloud NOT VERIFIED).
- **`ConnectivityMonitor` / `AndroidConnectivityMonitor`** — real quorum over
  `ConnectivityManager` active network + `NET_CAPABILITY_INTERNET`; test fakes
  elsewhere.
- **`CloudAnswerCache` / `DefaultCloudAnswerCache`** — the ONLY path by which a
  cloud answer enters local memory, invoked exclusively by the explicit user
  "Save to memory" action:
  - answer → `CloudKnowledgeItem` (uuid id, `answer:<sha256(normalized question)>`
    subject, CLOUD_ANSWER metadata) → existing Phase 7 `CloudKnowledgeClassifier`;
  - `NEW` → `CloudKnowledgeWriter.applyNew` → CLOUD-origin, SYNCED, searchable,
    **zero outbox rows**, survives Room+shard restart;
  - `DUPLICATE` → `AlreadyPresent` no-op (no second vector, no conflict);
  - anything implying an existing divergent record (CONFLICT et al.) →
    `ConflictPrevented` AND a persisted `ConflictEntity` written through the
    same `CloudConflictRecorder` the Phase 7 pull path uses — both sides'
    evidence retained, idempotent conflict id, **never** silently overwrites,
    resolvable by the existing `DefaultConflictResolver` (keepLocal / keepCloud
    / dismiss), zero outbox rows. LOCAL_ONLY records are never overwritten or
    uploaded by a save; tombstoned records are never resurrected; superseded
    targets stay superseded.
- **Ask UI** — real states: RETRIEVING / GENERATING / ESCALATING,
  INSUFFICIENT+offline limitation hint, INSUFFICIENT+unavailable hint, CLOUD
  ANSWER card with authority provenance (labeled "not verified locally") and a
  `[Save to memory]` button that only appears for cloud answers. ViewModel holds
  escalation/save state; no fabricated status.
- **End-to-end scenario suite** (`EdgeCloudLoopScenarioTest`, real Room + real
  qdrant-edge + deterministic embedding, fixture cloud):
  1. local P-101 torque memory → question answered locally, **no** cloud call;
  2. unrelated question → insufficiency → online escalation → attributed cloud
     answer; NOT stored (count/origin/outbox unchanged);
  3. explicit save → CLOUD-origin row, SYNCED, vector present, no outbox;
  4. same question offline → answered from local memory (no cloud, cites local
     evidence) — cloud → edge → offline demonstrated;
  5. cloud answer contradicting a local record for the same subject →
     `ConflictPrevented` + a persisted, resolvable conflict; local content
     untouched.

### NOT VERIFIED (Phase 8)

- **Real cloud connectivity** — `UnimplementedCloudAnswerDataSource` throws
  `CloudUnavailable`; escalation is exercised only against explicit in-test
  fixture remotes, never a real endpoint. Qdrant Server + real backend remain
  later-phase.
- **Android runtime** — same limitation as Phases 1–7: no device/emulator is
  attached on this machine (`adb devices` is empty), so the Ask Screen
  escalation states, save button, offline/unavailable hints, and the
  `AndroidConnectivityMonitor` quorum have not been exercised on a device or
  emulator (Robolectric compile/render level only).

### Known intentional limitations (Phase 8)

- Ask sends **only the question string** to the cloud; any permitted-context
  windowing is a deliberate scope cut (question-only is the smallest honest
  escalation contract).
- No "verification queue" for offline insufficient questions (context doc's
  optional offline queue is deferred); offline limitation is surfaced in text.
- Deterministic escalation/caching — no neural cloud LLM; `LLMService` remains
  `ExtractiveLLMService` and is untouched.
- Cloud answers are never auto-stored. Divergence surfaces as
  `ConflictPrevented` AND a persisted conflict row that the user resolves via
  the existing Conflicts UI (Phase 7 `ConflictResolver`); there is no
  auto-merge.
- Each save builds a fresh UUID / version 1 item, so the Phase 7
  same-identity UPDATE path (same memoryId, newer version) is never triggered
  by a cache save; matching is governed by the subjectKey + contentHash rules
  (DUPLICATE when identical, CONFLICT when divergent), which preserve any
  existing newer local/cloud record.

### Phase 8 security/privacy review (application-security-engineer, all fixes verified)

15-point audit of the escalation + save path against spec §16/§25/§27/§39/§53/
§75. Result: **13 PASS, 1 HIGH issue fixed, 1 LOW issue fixed, 1 INFO hardening
applied. No CRITICAL findings.**

- **ISSUE-1 (HIGH, pre-existing, FIXED)** — Android Auto Backup was enabled
  (`allowBackup="true"`) with include-all rule templates, so the Room database
  (LOCAL_ONLY originals, SYNC_REDACTED originals, outbox payloads) and the
  qdrant shard could leave the device through the platform backup channel.
  Fix: `android:allowBackup="false"` in the manifest, the two template rule
  files removed, and a regression test asserts `FLAG_ALLOW_BACKUP` is absent.
- **ISSUE-2 (LOW, FIXED)** — cloud responses were persisted/displayed without
  validation. Fix: `DefaultCloudAnswerCache` rejects blank questions, blank
  answers and answers over 8,000 chars with `EdgeError.InvalidInput` (no
  state written); `EscalatingRagService` treats a blank cloud answer as
  `CloudEscalation.Unavailable` instead of displaying it as an answer.
- **ISSUE-3 (INFO, HARDENED)** — cloud-origin rows carry `syncDecision=SYNC`
  although they are never policy-evaluated, so the no-push-back invariant
  depended on the call graph alone. Fix: `SyncPayloadFactory.build` now
  returns `null` for any CLOUD-origin memory, encoding "cloud knowledge is
  never pushed back" in the privacy boundary itself.

Verified PASS points (each test-pinned): LOCAL_ONLY never leaves the device;
SYNC_REDACTED never sends originals; escalation sends question text only;
cloud answers are CLOUD-origin and never merged into local evidence;
save-to-memory is explicit-only; cloud rows never enter the sync outbox
(including a saved answer whose text is LOCAL_ONLY-classified); conflict
records preserve both sides' provenance; no sensitive content in logs (no
`Log.*`/`println` in main sources, `lastError` stores only failure-kind ids);
no hardcoded API keys/secrets; offline guarantees intact (escalation only
after insufficiency + online quorum); the JNI/native boundary is unchanged by
Phase 8; the Ask UI cannot show cloud data as local evidence/citations; the
Conflict UI distinguishes local vs cloud provenance.

Residual risks (accepted): the question text itself is user-initiated and may
contain sensitive wording (documented Phase 8 limitation); storage at rest is
unencrypted (deferred per spec §53); TLS/endpoint validation requirements
activate only when a real cloud backend lands. Note: `allowBackup="false"`
disables backup on all API levels, but the attribute is deprecated on Android
12+; adding a `dataExtractionRules` file with explicit excludes is a possible
later hardening (not a privacy hole today — backup is off).

---

These facts were confirmed by reading the crate source at
`~/.cargo/registry/src/.../qdrant-edge-0.8.0/` and are baked into `store.rs`:

- `EdgeShard::new(path, config)` creates; `EdgeShard::load(path, Option<EdgeConfig>)`
  reopens. Fresh-vs-existing is decided by `edge_config.json` presence.
- Upsert path: `shard.update(UpdateOperation::PointOperation(
  PointOperations::UpsertPoints(PointInsertOperations::PointsList(vec![PointStruct::new(...).into()]))))`.
- Delete path: `PointOperations::DeletePoints { ids }`.
- Search: `shard.query(QueryRequest)` with
  `ScoringQuery::Vector(QueryEnum::Nearest(NamedQuery::new(VectorInternal::Dense(v), "semantic")))`.
- Count: `shard.count(CountRequest)`.
- Vector config: single named dense vector `"semantic"`, `Distance::Cosine`.
- WAL segment capacity forced to 4 MiB (down from deprecated 32 MiB default)
  for mobile footprint; `EdgeConfigBuilder::wal_options(...)`.
- **Point IDs are NOT arbitrary strings.** `ExtendedPointId` accepts only
  `NumId(u64)` or `Uuid`. Kotlin string ids must parse as a u64 or a UUID or upsert fails.
- `store::flush` exists but is currently unused (`#[allow(dead_code)]`) —
  `EdgeShard::drop` already flushes; kept as the batching extension point.

### Environment notes

- System rust is Arch-packaged; **rustup** was installed to
  `~/.cargo/bin` with `stable-x86_64-unknown-linux-gnu` + android targets for
  the cross-builds. Host builds work with either.
- Default `/opt/jdk-25` is broken; builds use `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`.
- AGP 9.4.1: `testOptions.unitTests.all{...}` is gone; `Test.systemProperty`
  cannot be fingerprinted (config-cache/task failure) so the native lib path is
  resolved at test runtime relative to the module `build/generated/native-libs/host/`.

---

## Real cloud integration — EdgeMind backend + Android HTTP remotes (code complete, LIVE CONNECTIVITY NOT VERIFIED)

The previously honest `Unimplemented*` remotes now have real production
implementations behind the same interfaces, plus a new `backend/`
Node.js/TypeScript service that owns all cloud credentials.

### Architecture (implemented)

```text
Android (Qdrant Edge unchanged, all local behavior unchanged)
   │  HTTPS (cleartext only for 10.0.2.2/loopback via network_security_config)
   ▼
EdgeMind Backend (Express + TS) — owns QDRANT_API_KEY / CLOUD_LLM_API_KEY
   ├── Qdrant Cloud  `device_memory` (push) + `cloud_knowledge` (curated pull)
   └── Cloud LLM     OpenAI-compatible chat completions (replaceable)
```

- **Backend** (`backend/`, see `backend/README.md`): `PUT /sync/operations/:id`
  (idempotent by operationId — replay after crash is a no-op), `GET /knowledge`
  (opaque Qdrant next_page_offset cursor, `CloudKnowledgeBatch` shape),
  `POST /knowledge/ingest` (curation, ADMIN_API_KEY-guarded when configured),
  `GET /knowledge/search` (honest 503 without an embeddings provider),
  `POST /answers` (question text only), `GET /health` (booleans only).
  Collection schema mirrors the EdgeMind model: memoryId, chunkId, subjectKey,
  contentHash, version, origin, source, syncDecision, redacted flag,
  supersedes, tombstone, authority, timestamps, tags, metadata. Payload-only
  points are used when no embedding provider is configured (push/pull/idempotency
  work without it; cloud semantic search needs `CLOUD_EMBEDDING_*`).
- **Android** — three real remotes behind the existing interfaces:
  `HttpSyncRemoteDataSource` (400/422→REJECTED, 401/403→UNAUTHORIZED,
  429/5xx→SERVER_TEMPORARY, transport→NETWORK; success only on a real 2xx
  backend ACK), `HttpCloudKnowledgeRemoteDataSource` (1:1 `CloudKnowledgeBatch`
  mapping; failures → `CloudUnavailable`), `HttpCloudAnswerDataSource`
  (answer/authority/requestId; failures/blank → `CloudUnavailable`).
  `DefaultSyncEngine` now enriches each push with the Room memory row
  (version/contentHash/subjectKey/type/origin/redacted/tags/metadata) — the
  outbox table and its privacy payload are unchanged. `CloudHttpClient` is a
  dependency-free HttpURLConnection wrapper (5s connect / 15s read, 25s for
  answers). Backend URL comes from `BuildConfig.CLOUD_BACKEND_URL`
  (`-PcloudBackendUrl=...` or `CLOUD_BACKEND_URL`); blank keeps the honest
  unimplemented fallbacks. LOCAL_ONLY is refused client-side before any I/O
  and rejected server-side; CLOUD-origin push-back is rejected server-side;
  `network_security_config.xml` enforces HTTPS except local dev hosts.
- **Security** — secrets live only in `backend/.env` (gitignored;
  `.env.example` has placeholders only). No request bodies or credentials are
  logged; responses never contain secret values; request size
  (`MAX_REQUEST_BYTES`), question/answer length caps, and LLM timeouts are
  enforced; provider responses are validated (blank/oversized/malformed →
  typed failures, never fabricated success).

### VERIFIED (deterministic tests only)

- Backend: `cd backend && npm test` → **29 tests, 0 failures**; `npm run build`
  (tsc) clean. Covers config handling, LOCAL_ONLY rejection, CLOUD-origin
  push-back rejection, payload validation/size limits, sync idempotency,
  curated ingest + cursor pagination, cloud answer success/failure mapping,
  blank/oversized/malformed provider responses, provider timeout,
  LLM-not-configured honesty, admin guard, health honesty, secret non-leakage.
- Android: full suite → **231 tests, 0 failures, 0 errors** (was 213; +18:
  real HTTP remotes against an in-test local backend — status mapping,
  full-schema request body, redacted flag, LOCAL_ONLY refusal, cursor
  pass-through, question-only body, provenance parsing, failure honesty).
- `:app:assembleDebug` and `:app:lintDebug` both BUILD SUCCESSFUL.

### NOT VERIFIED

- **Live Qdrant Cloud connectivity** — no `backend/.env` credentials exist on
  this machine yet. Nothing has authenticated to Qdrant Cloud, no real point
  has been written/read/deleted, and no Android build has talked to a real
  backend. The manual smoke test is `docs/CLOUD_VERIFICATION.md`; the exact
  credentials needed are listed there (QDRANT_URL, QDRANT_API_KEY,
  CLOUD_LLM_*).
- **Android runtime** — same as Phases 1–8: no device/emulator attached, so
  the HTTPS remotes, network security config, and WorkManager-driven real
  sync have not run on-device.

### INTENTIONAL LIMITATIONS

- Cloud-side semantic search requires a separately configured embeddings
  provider; push/pull/idempotency work without it (payload-only points).
- `/knowledge/ingest` is a development curation endpoint; production must set
  `ADMIN_API_KEY` (enforced when configured).
- No backend→Qdrant vector embedding for device pushes (the device's
  deterministic hashing embedding is not recomputed server-side); device
  knowledge is stored payload-first, and cloud search operates over curated
  knowledge when embeddings are configured.
- Real cloud answer flow sends the question text only (Phase 8 contract);
  no local evidence ever leaves the device.

---

## NOT IMPLEMENTED (later phases)

- Qdrant payload handling in the native boundary (currently empty `{}` payload;
  retrieval maps point ids back to Room metadata instead)
- On-device neural embedding (current embedding is a lexical hashing baseline)
- On-device neural / cloud LLM (Phase 4 uses the replaceable `LLMService` with an extractive fallback)
- **Live cloud deployment** — the backend and its Android remotes are
  implemented and deterministically tested, but no real Qdrant Cloud / cloud
  LLM connectivity has been exercised (see the real cloud integration section
  above and `docs/CLOUD_VERIFICATION.md`). Real-credential verification,
  backend hosting/TLS deployment, and on-device sync verification remain.
- ACTIVITY screen (spec §41/§45) — primary navigation currently has ASK and
  MEMORY only; the activity event timeline is not implemented.
- Cloud knowledge sync state (`SYNCED` on pull) and conflict resolution are
  one-way today; no push-back of resolved-cloud results or tombstone propagation
  to the cloud (later phase).
- UI polish beyond the Phase 2–6 screens (later phase)