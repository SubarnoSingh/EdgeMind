# EdgeMind — OpenCode Agent Instructions

## 1. Project Identity

**Product:** EdgeMind
**Current repository:** EdgeMemo

EdgeMind is an **offline-first AI work-memory and intelligence platform for Android edge devices**.

The defining property of the project is:

> The device must have useful persistent memory, be able to retrieve and reason over that memory offline, control what information leaves the device, and become richer when connectivity returns.

This is not merely:

* a chatbot
* a notes application
* a PDF reader
* a generic RAG application
* a local vector database demo
* a cloud-first AI application

The complete product lifecycle is:

```text
Knowledge enters edge
        ↓
Local memory
        ↓
Offline retrieval
        ↓
AI reasoning
        ↓
New knowledge captured
        ↓
Policy decision
        ↓
Offline outbox
        ↓
Connectivity returns
        ↓
Cloud synchronization
        ↓
Cloud/shared knowledge
        ↓
Conflict detection/resolution
        ↓
Richer local memory
```

---

# 2. Authoritative Project Context

Before making architectural or implementation decisions, read:

```text
docs/EdgeMind_CONTEXT.md
docs/EdgeMind_COMPLETE_PROJECT_SPEC.md
```

These documents are the authoritative project specification.

Do not invent a competing architecture when the specification already defines one.

If the repository implementation conflicts with the specification:

1. Inspect the existing implementation.
2. Determine what currently works.
3. Identify the specific conflict.
4. Explain the conflict.
5. Make the smallest justified change.

Do not perform large rewrites merely because a different architecture appears cleaner.

---

# 3. Core Technical Requirements

The application is Android-first and should use:

* Kotlin
* Jetpack Compose
* Material 3 where useful
* Coroutines
* StateFlow
* ViewModel
* Room
* WorkManager
* Android document APIs
* Rust through JNI/FFI where required
* Qdrant Edge for local vector storage
* Qdrant Server for cloud/shared knowledge where required

Architecture should generally follow:

```text
Compose UI
    ↓
ViewModel / StateFlow
    ↓
Use Cases
    ↓
Repositories / Services
    ↓
Room / Qdrant Edge / AI / Sync
    ↓
Native Qdrant implementation where required
```

Keep responsibilities separated.

---

# 4. Qdrant Edge Is Mandatory

Qdrant Edge is a **core project requirement**.

Do NOT silently replace it with:

* SQLite vector storage
* Room vector storage
* a custom cosine-similarity implementation
* another vector database
* remote Qdrant
* Firebase
* an in-memory fake
* a mock pretending to be Qdrant Edge

The intended architecture is:

```text
Android
  ↓
Kotlin LocalVectorStore
  ↓
JNI / FFI
  ↓
Rust native library
  ↓
Qdrant Edge / EdgeShard
  ↓
Persistent local storage
```

The first high-risk milestone is proving:

```text
Android
 → JNI
 → Rust
 → Qdrant Edge
 → create shard
 → insert vector
 → search vector
 → close
 → restart
 → search again
```

A vector inserted before restart must be retrievable after restart.

Do not move on from this milestone while silently using a substitute.

---

# 5. LocalVectorStore Boundary

Keep the native Qdrant implementation behind a narrow Kotlin interface.

Preferred abstraction:

```kotlin
interface LocalVectorStore {
    suspend fun initialize()

    suspend fun upsert(
        points: List<VectorPoint>
    )

    suspend fun search(
        vector: FloatArray,
        limit: Int
    ): List<SearchResult>

    suspend fun delete(
        id: String
    )

    suspend fun count(): Long

    suspend fun optimize()
}
```

The exact implementation may evolve after inspecting the repository and current Qdrant Edge API.

Do not leak Rust/native implementation details throughout the Android application.

---

# 6. Room vs Qdrant

Use **Qdrant Edge** for:

* vectors
* semantic retrieval
* vector payload required for retrieval

Use **Room** for:

* memory metadata
* sync state
* outbox
* activity/events
* conflicts
* version history
* policy state
* application state

Do not turn Room into a replacement vector database.

---

# 7. Memory Model

The project should support the concepts defined in the specification, including:

* memoryId
* title
* content
* chunkId
* source
* type
* tags
* createdAt
* updatedAt
* origin
* syncDecision
* syncState
* sensitivity
* importance
* version
* contentHash
* subjectKey
* supersedes
* tombstone
* metadata

Memory types include:

```text
DOCUMENT
NOTE
OBSERVATION
PROCEDURE
REPAIR
EVENT
CLOUD_KNOWLEDGE
```

Do not remove these concepts simply to make the implementation smaller.

---

# 8. Ingestion

The intended ingestion flow is:

```text
Input
 ↓
Validate
 ↓
Normalize
 ↓
Chunk
 ↓
Embed locally
 ↓
Policy evaluation
 ↓
Duplicate/conflict check
 ↓
Qdrant Edge upsert
 ↓
Room metadata
 ↓
Outbox if syncable
 ↓
Activity event
```

Document ingestion should support the project-defined document types and preserve useful source metadata.

Processing must be real.

Never fake:

* indexing
* embedding
* vector insertion
* processing progress
* sync
* retrieval
* AI answers

---

# 9. Retrieval

The intended retrieval pipeline is:

```text
Question
 ↓
Normalize
 ↓
Local embedding
 ↓
Qdrant Edge dense search
 ↓
Optional keyword/BM25 retrieval
 ↓
Fusion / RRF
 ↓
Deduplication
 ↓
Remove tombstoned/superseded memories
 ↓
Ranking
 ↓
Top-K context
 ↓
Grounded answer + citations
```

Dense retrieval may be implemented first if BM25 is too expensive.

Exact identifiers such as:

```text
SKF-6205
E-4417
P-101
```

must remain retrievable.

---

# 10. RAG Rules

Answers must be grounded in retrieved evidence.

Never fabricate:

* sources
* citations
* retrieved memories
* confidence
* facts supposedly found in the user's memory

If the available evidence is insufficient, explicitly communicate that limitation.

The application should expose the local memories used for an answer where appropriate.

The local/offline path must remain meaningful even when cloud services are unavailable.

---

# 11. AI / LLM Architecture

Keep AI services behind interfaces.

Important abstractions include:

```text
EmbeddingService
LLMService
RetrievalService
RagService
```

Possible answer strategies include:

* extractive/local answer generation
* on-device LLM
* cloud LLM when explicitly permitted and online

Do not allow local LLM complexity to block the Qdrant Edge/local-memory milestone.

Do not introduce OpenAI, Gemini, Claude, remote embeddings, or other cloud AI dependencies into the core offline path unless explicitly requested.

---

# 12. Privacy and Policy

The policy engine must support:

```text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

Examples from the project specification include:

```text
Gate code
→ LOCAL_ONLY

P-101 repair information
→ SYNC_REDACTED

Procedure revision 4
→ SYNC
```

### LOCAL_ONLY

Information marked `LOCAL_ONLY` must never enter the sync outbox or leave the device.

### SYNC_REDACTED

Only a safe/redacted representation may leave the device.

The original private content must remain local.

Policy decisions should be explainable in the UI.

Never bypass policy merely because implementing the privacy boundary is inconvenient.

---

# 13. Offline-First Requirement

Core functionality must work without internet.

Offline functionality includes, where implemented:

* browsing local memory
* semantic search
* adding notes
* document ingestion
* local embeddings
* Qdrant Edge retrieval
* local RAG/answering where available
* activity logging
* creation of sync outbox operations

Cloud-dependent functionality must visibly indicate that it is unavailable, pending, or queued.

Do not design the application so that the cloud becomes a hidden dependency for core memory functionality.

---

# 14. Sync Architecture

Use a durable Room outbox.

Outbox concepts include:

```text
operationId
memoryId
operationType
payload
createdAt
attempts
state
lastError
```

States:

```text
PENDING
IN_FLIGHT
ACKED
FAILED
DEAD
```

Use idempotent operation IDs.

WorkManager should handle durable background synchronization and retries.

Do not implement sync as a fragile fire-and-forget network request.

---

# 15. Evolving Memory

Memory synchronization must account for:

```text
version
contentHash
updatedAt
supersedes
tombstone
subjectKey
origin
authority
```

Do not silently overwrite contradictory knowledge.

Conflicting knowledge should remain visible and be handled through the conflict system defined by the project.

---

# 16. UI Direction

The UI should follow the EdgeMind product direction:

* dark charcoal / near-black base
* muted purple/blue gradients
* restrained blur
* clean typography
* subtle technical indicators
* premium engineering-tool feeling
* minimal unnecessary decoration

Primary navigation:

```text
ASK
MEMORY
ACTIVITY
```

Secondary/contextual areas:

```text
CAPTURE
SYNC
CONFLICTS
SETTINGS
```

Avoid:

* generic ChatGPT clones
* giant gradients
* excessive glassmorphism
* fake dashboards
* unnecessary animations
* fabricated metrics

Every visible status should represent real application state.

Never display fake:

* memory counts
* latency
* sync status
* processing progress
* model status
* conflict counts

---

# 17. Performance

Heavy work must not block the UI thread.

This includes:

* embeddings
* document extraction
* chunking
* Qdrant operations
* Room operations
* sync
* JNI/native calls
* LLM inference

Use Kotlin coroutines and appropriate dispatchers.

Avoid:

* unnecessary allocations
* loading large documents entirely into memory when avoidable
* keeping multiple large models in memory unnecessarily
* expensive work from Compose recomposition
* blocking calls from UI code

---

# 18. Security

Do not:

* log raw sensitive memories
* upload LOCAL_ONLY information
* hardcode secrets
* expose credentials in source
* trust cloud responses blindly
* spread native implementation details across the codebase

Keep the JNI/native boundary narrow.

Full end-to-end encryption is not a first-week requirement unless the project specification or implementation phase explicitly calls for it.

---

# 19. Error Handling

Prefer specific errors such as:

```text
NO_NETWORK
LOCAL_STORAGE_ERROR
EMBEDDING_ERROR
QDRANT_ERROR
SYNC_ERROR
CLOUD_ERROR
INVALID_DOCUMENT
CONFLICT
```

Avoid generic:

```text
Something went wrong
```

when a useful specific state can be exposed.

---

# 20. Testing

Important tests include:

### Unit

* chunking
* policy decisions
* redaction
* data mapping
* sync transitions
* conflict resolution
* retrieval fusion/RRF

### Integration

```text
memory
 → embedding
 → Qdrant Edge
```

```text
memory
 → policy
 → outbox
```

```text
outbox
 → cloud
```

### Persistence

Verify that data survives:

* process restart
* application restart
* device restart where practical

### Offline

Disable networking and verify that the local memory path continues functioning.

### Privacy

Verify:

```text
LOCAL_ONLY
→ no outbox
→ no cloud transmission
```

---

# 21. Development Phases

Follow the project specification's development order unless there is a documented reason to change it.

## Phase 0 — Repository Inspection

Before coding:

1. Inspect the repository.
2. Inspect Gradle configuration.
3. Inspect Android source.
4. Inspect native/Rust code.
5. Inspect existing dependencies.
6. Inspect existing agents.
7. Inspect documentation.
8. Determine what actually works.
9. Compare implementation against the project specification.

Produce:

```text
CURRENT STATE
SPEC GAP ANALYSIS
HIGHEST-RISK BLOCKER
NEXT IMPLEMENTATION STEP
```

Do not implement features during this phase unless explicitly instructed.

---

## Phase 1 — Qdrant Edge Spike

Prove:

```text
Android
→ JNI
→ Rust
→ Qdrant Edge
→ EdgeShard
→ insert
→ search
→ close
→ restart
→ search
```

This is the highest-risk technical milestone.

---

## Phase 2 — Local Memory

Implement:

* Memory model
* Room metadata
* Qdrant adapter
* embedding service
* memory creation
* memory explorer
* offline semantic search

---

## Phase 3 — Documents

Implement the project-defined document ingestion flow:

```text
Import
→ extract
→ normalize
→ chunk
→ embed
→ Qdrant Edge
→ metadata
```

---

## Phase 4 — Local RAG

Implement:

```text
Question
→ retrieval
→ context
→ answer
→ citations
→ insufficient-evidence handling
```

---

## Phase 5 — Policy

Implement:

```text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

and explain the decision.

---

## Phase 6 — Offline Outbox

Implement:

* Room outbox
* WorkManager
* retries
* backoff
* sync states
* idempotency

---

## Later Phases

Continue according to the authoritative project specification for:

* Qdrant Server synchronization
* cloud → edge knowledge
* conflict detection
* conflict resolution
* redaction
* activity timeline
* optional cloud escalation
* final UI polish

Do not sacrifice the core local system for late-stage features.

---

# 22. Priority Rule

When time is limited, prioritize real functionality in this order:

1. Qdrant Edge actually works
2. Persistent local memory
3. On-device embeddings
4. Offline semantic retrieval
5. Grounded local answer
6. Policy engine
7. Durable sync outbox
8. Qdrant Server synchronization
9. Cloud → edge knowledge
10. Conflict demonstration
11. UI polish
12. Stretch features

A smaller real edge system is preferable to a polished fake implementation.

---

# 23. Explicitly Postpone Unless Core Is Stable

Do not prioritize these before the core system works:

* elaborate animations
* complex authentication
* large cloud dashboards
* multi-agent systems
* voice
* multimodal embeddings
* image understanding
* complicated cloud LLM orchestration
* huge Rust abstractions
* unnecessary screens
* speculative optimization

---

# 24. Agent Behavior

Every agent must:

1. Read the project context.
2. Inspect the existing implementation.
3. Identify what works.
4. Identify what is missing.
5. Identify the highest-risk blocker.
6. Implement the smallest useful vertical slice.
7. Build/test it.
8. Report what was actually verified.
9. Update `WORKING.md` when appropriate.
10. Stop instead of inventing functionality when evidence is insufficient.

Agents must not claim something works without verification.

---

# 25. Preserve Existing Working Behavior

Before modifying code:

* inspect it
* understand dependencies
* identify callers
* understand persistence implications
* understand native boundaries
* understand tests

Do not perform broad refactors without justification.

Prefer:

```text
small change
→ build
→ test
→ verify
→ next change
```

over:

```text
large rewrite
→ hope everything works
```

---

# 26. Specialist Agent Roles

Use the installed agents according to their specialties.

### software-architect

Architecture, repository inspection, boundaries, dependency decisions, implementation planning.

### ai-engineer

Embeddings, LLM integration, local AI strategy, inference architecture.

### rag-pipeline-engineer

Chunking, embeddings, retrieval, hybrid search, RRF, grounding and RAG.

### mobile-app-builder

Android/Kotlin/Compose/ViewModel/Room/WorkManager implementation.

### ui-designer

EdgeMind visual system and Compose UI.

### performance-benchmarker

Latency, memory, threading, resource usage, benchmarking.

### application-security-engineer

Application security, data handling, attack surfaces and secure implementation.

### data-privacy-officer

LOCAL_ONLY, SYNC_REDACTED, privacy boundaries and data-flow review.

### code-reviewer

Correctness, maintainability, regressions, architecture violations.

### reality-checker

Challenge assumptions, identify fake/incomplete functionality, verify claims against actual implementation.

Do not expect one agent to solve every aspect of EdgeMind.

---

# 27. Agent Collaboration Pattern

For major changes, prefer:

```text
software-architect
        ↓
implementation specialist
        ↓
performance-benchmarker
        ↓
security/privacy review
        ↓
code-reviewer
        ↓
reality-checker
```

Use only the specialists necessary for the current task.

Do not create unnecessary agent-driven complexity.

---

# 28. Documentation

Maintain:

```text
AGENTS.md
WORKING.md
ARCHITECTURE.md
docs/EdgeMind_CONTEXT.md
docs/EdgeMind_COMPLETE_PROJECT_SPEC.md
```

`WORKING.md` should describe actual current implementation state.

Do not write aspirational features as if they already exist.

When something is not implemented, say:

```text
NOT IMPLEMENTED
```

When something is partially implemented, describe exactly what works.

---

# 29. Completion Standard

A feature is not complete merely because code was written.

A feature is complete only when:

```text
implemented
+
build succeeds
+
relevant tests pass
+
runtime behavior verified where applicable
+
documentation reflects actual state
```

Never claim:

```text
Done
```

when only code generation has occurred.

---

# 30. Final Rule

When uncertain:

> Follow the authoritative EdgeMind project documents, inspect the real repository, preserve existing working behavior, implement the smallest verifiable change, and never fake the core edge system.
