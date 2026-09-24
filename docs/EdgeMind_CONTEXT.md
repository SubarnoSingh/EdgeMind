# EdgeMind --- Master Context for OpenCode

## Purpose

This is the authoritative context for AI coding agents working on
**EdgeMind**, the PS03 hackathon project: **AI-Powered Edge Memory &
Intelligence Platform**.

Two research directions were compared: 1. A Kotlin/Android-first offline
AI memory/RAG app using Qdrant Edge and on-device embeddings. 2. A
broader EdgeMind platform research covering local semantic memory,
hybrid retrieval, policy-controlled sync, intermittent connectivity,
evolving memory, conflicts, and an edge-to-cloud learning loop.

We keep the **product ideas from both**, but the implementation is
intentionally **Kotlin-native Android + compatible native/cloud
components**. Do not copy the Python/FastAPI/React implementation from
the reference research.

## Product definition

> **EdgeMind is an offline-first AI work memory that helps people
> remember, retrieve, capture, and share knowledge from the environments
> where they work---even when the internet disappears.**

EdgeMind is NOT merely a chatbot, notes app, PDF reader, local vector DB
demo, or generic RAG wrapper. It is an **edge memory system**.

Core loop:

``` text
Initial knowledge → Local memory → Ask/Search → Work → Capture new knowledge
→ Policy decides local vs sync → Offline outbox → Reconnect → Sync/conflict handling
→ Shared knowledge returns → Local memory becomes richer
```

## PS03 alignment

  -----------------------------------------------------------------------
  Requirement                         EdgeMind
  ----------------------------------- -----------------------------------
  Local semantic memory               Qdrant Edge on Android

  Offline retrieval                   On-device embeddings + Qdrant Edge

  Hybrid search                       Dense + exact/keyword retrieval
                                      where practical

  Offline operation                   Core memory/search/QA flows work
                                      without network

  Local/cloud decision                Policy engine

  Intermittent connectivity           Durable Room outbox + WorkManager

  Edge→cloud                          Sync to Qdrant Server

  Cloud→edge                          Incremental cloud knowledge pull

  Evolving memory                     Versioning, hashes, timestamps,
                                      supersedes/tombstones

  Conflicts                           Detection + deterministic
                                      resolution/review

  UI                                  Memory, Ask, Capture, Activity,
                                      Sync, Conflict surfaces

  Edge-to-cloud intelligence          Local answer → optional escalation
                                      → knowledge returns to edge
  -----------------------------------------------------------------------

## Flagship scenario: field maintenance

Use industrial field maintenance as the **demo scenario**, not as a
hard-coded product limitation.

A technician has manuals, repair history and local observations at a
remote site. Connectivity may be unavailable. They ask questions,
retrieve local knowledge, capture observations, and later synchronize
safe knowledge. Example: - `"Why does P-101 keep leaking?"` - local
retrieval finds manuals + previous repair notes - technician records
`P-101 seal failed again; likely cavitation; suction pressure raised to 2.1 bar` -
policy may classify it as `SYNC_REDACTED` - when online, it enters the
outbox and syncs - cloud knowledge can later return to the device -
contradictory knowledge such as `45 Nm` vs `52 Nm` becomes a visible
conflict rather than a silent overwrite

## First-use experience

Do not force a huge onboarding process.

User can: - start with empty memory - import
PDFs/Markdown/TXT/documents - add notes/observations

Ingestion:

``` text
Input → Normalize → Chunk → Embed → Store locally → Ready offline
```

Show real processing state, never fake it.

## Android stack

Primary: - Kotlin - Jetpack Compose - Material 3 where useful -
Coroutines - StateFlow - ViewModel - Room - WorkManager - Android
document APIs - Android NDK/JNI where required for Qdrant Edge

Vector memory: - **Qdrant Edge is mandatory for the real local vector
layer.** - Room is for metadata/outbox/events/state, not a replacement
vector database.

Cloud: - Qdrant Server - small backend/service only where required for
auth, orchestration, cloud escalation/curation, or sync - backend
language is flexible; do not force Python because the reference research
used Python

Embeddings: - Prefer **on-device embeddings** for genuine offline
semantic memory. - Choose a mobile-compatible model/runtime rather than
a desktop-only model.

LLM: - abstract behind `LLMService` - possible implementations: local
LLM, cloud LLM, extractive fallback - do not let on-device LLM
complexity delay the Qdrant Edge/local-memory milestone

## Critical Qdrant Edge architecture

Qdrant Edge integration is the highest-risk native part. Isolate it:

``` text
Compose/ViewModel
 ↓
Repository/Use case
 ↓
LocalVectorStore
 ↓
JNI/FFI
 ↓
Rust native library
 ↓
qdrant-edge / EdgeShard
 ↓
local persistent storage
```

Recommended Kotlin boundary:

``` kotlin
interface LocalVectorStore {
    suspend fun initialize()
    suspend fun upsert(points: List<VectorPoint>)
    suspend fun search(vector: FloatArray, limit: Int): List<SearchResult>
    suspend fun delete(id: String)
    suspend fun count(): Long
    suspend fun optimize()
}
```

**First prove this exact path before building a large native layer:**

``` text
Android → JNI → Rust → Qdrant Edge
→ create shard → insert one vector → search
→ close → restart → search again
```

Do not silently replace Qdrant Edge with SQLite vectors, another DB,
remote Qdrant, Firebase, or fake cosine search.

## Recommended architecture

``` text
Compose UI
 ↓
ViewModel
 ↓
Use Cases
 ↓
Repositories
 ↓
Services
 ├─ MemoryRepository
 ├─ RetrievalService
 ├─ EmbeddingService
 ├─ RagService
 ├─ LLMService
 ├─ PolicyEngine
 ├─ SyncEngine
 └─ ConflictResolver
 ↓
Data/native layer
 ├─ Room
 ├─ Qdrant Edge
 ├─ WorkManager
 └─ Qdrant Server/network
```

Keep boundaries clean but do not over-engineer.

Suggested interfaces:

``` text
EmbeddingService
LocalVectorStore
MemoryRepository
RetrievalService
RagService
LLMService
PolicyEngine
SyncRepository
ConflictRepository
```

## Memory model

Conceptual fields:

``` text
memoryId, title, content, chunkId, source, type, tags,
createdAt, updatedAt, origin, syncDecision, syncState,
sensitivity, importance, version, contentHash, subjectKey,
supersedes, tombstone, metadata
```

Qdrant stores vectors + retrieval payload. Room stores metadata, sync
state, outbox, events, conflicts, version history and app state.

Initial memory types:

``` text
DOCUMENT
NOTE
OBSERVATION
PROCEDURE
REPAIR
EVENT
CLOUD_KNOWLEDGE
```

## Ingestion pipeline

``` text
User input
 ↓
Validate
 ↓
Normalize
 ↓
Chunk
 ↓
Generate local embedding
 ↓
Policy evaluation
 ↓
Duplicate/conflict check
 ↓
Qdrant Edge upsert
 ↓
Room metadata
 ↓
If syncable → outbox
 ↓
Activity event
```

## Retrieval

``` text
Question
 ↓
Normalize
 ↓
Local embedding
 ↓
Qdrant Edge dense search
 ↓
Optional keyword/BM25 search
 ↓
RRF / fusion
 ↓
Deduplicate
 ↓
Remove tombstoned/superseded items
 ↓
Rank
 ↓
Top-K context
 ↓
Answer engine
 ↓
Answer + citations
```

Hybrid retrieval matters for exact technical identifiers such as
`SKF-6205`, `E-4417`, `P-101`. If full BM25 is too expensive for the
first milestone, build dense retrieval first behind an abstraction and
add keyword retrieval later.

## RAG rules

-   Answer from retrieved evidence.
-   Never fabricate citations.
-   Clearly state when evidence is insufficient.
-   Show which local memories were used.
-   If local confidence is low and online, optional cloud escalation is
    allowed.
-   If offline and evidence is insufficient, say so rather than
    hallucinating.

## Policy engine

Initial decisions:

``` text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

Inputs may include sensitivity, type, scope, importance, user choice and
tags.

Examples:

``` text
"Gate code for Site 7 is 4412" → LOCAL_ONLY
"Replaced P-101 seal; root cause cavitation" → SYNC_REDACTED
"Pump maintenance procedure revision 4" → SYNC
```

Policy decisions must be explainable in UI.

### Privacy invariant

`LOCAL_ONLY` data must never leave the device.

``` text
Memory → Policy → LOCAL_ONLY?
                 ├─ YES → never enqueue
                 └─ NO  → enqueue permitted/redacted representation
```

Redacted sync must use a separate safe payload, never accidentally
upload the original private text.

## Offline-first behavior

Offline must still support: - local memory browsing - semantic search -
adding notes - document ingestion - local embeddings - Qdrant Edge
retrieval - local RAG/answering where available - activity logging -
outbox creation

Cloud-dependent operations should clearly show unavailable/pending
state.

## Sync

Use Room as durable outbox and WorkManager for retry/deferred work.

Conceptual outbox:

``` text
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

``` text
PENDING
IN_FLIGHT
ACKED
FAILED
DEAD
```

Flow:

``` text
Memory created → policy allows sync → outbox row
→ offline wait → connectivity returns → WorkManager
→ push → server ACK → ACKED → activity event
```

Use idempotent IDs so retries do not duplicate data.

## Cloud → edge

Do not blindly replace device-authored memory with a cloud snapshot.

Conceptual separation:

``` text
LOCAL MEMORY
CLOUD KNOWLEDGE
```

Cloud knowledge can be pulled incrementally, then conflict-scanned
against local memory.

## Evolving memory

A memory can be created, edited, superseded, deleted, duplicated,
contradicted, merged or synchronized.

Useful metadata:

``` text
version
contentHash
updatedAt
supersedes
tombstone
subjectKey
origin
authority
```

Never silently overwrite contradictory knowledge.

## Conflict handling

Types: 1. Version conflict --- same identity, different versions. 2.
Duplicate --- effectively same information. 3. Semantic contradiction
--- same subject, incompatible facts.

Example:

``` text
Local:  P-101 torque = 45 Nm
Cloud:  P-101 torque = 52 Nm
```

Use deterministic authority/version rules where safe. Otherwise put the
item in a review queue. Manual resolution should itself become a
versioned write.

## Edge-to-cloud intelligence loop

``` text
Device learns
 ↓
Policy
 ↓
Safe knowledge syncs
 ↓
Cloud aggregates/curates
 ↓
Updated knowledge published
 ↓
Devices pull
 ↓
Conflict detection
 ↓
Knowledge available offline
```

Optional question escalation:

``` text
Question → local retrieval → confidence high?
 YES → local answer
 NO → online? → cloud escalation → answer → optionally cache useful knowledge
              → offline → answer with limitation + queue verification
```

The cloud should extend the edge, not make the edge useless.

## UI direction

Visual style: - dark charcoal / near-black - muted purple/blue blurred
gradients - restrained glass surfaces - clean typography - subtle
technical indicators - premium engineering-tool feeling - not a generic
ChatGPT clone

Primary navigation:

``` text
Ask | Memory | Activity
```

Contextual surfaces: - Capture - Sync - Conflicts - Settings

Real status labels may include:

``` text
EDGE ONLY
OFFLINE
SYNC QUEUED
SYNCING
EDGE + CLOUD
SYNCED
SYNC ERROR
```

Never fake counts, latency, sync percentage, cloud status, retrieval
count or processing stages.

## Suggested project structure

``` text
app/src/main/java/.../
  core/model/
  core/common/
  core/logging/
  data/local/room/
  data/local/qdrant/
  data/remote/api/
  data/repository/
  domain/memory/
  domain/retrieval/
  domain/rag/
  domain/policy/
  domain/sync/
  domain/conflict/
  ai/embedding/
  ai/llm/
  native/qdrant/
  presentation/ask/
  presentation/memory/
  presentation/capture/
  presentation/activity/
  presentation/sync/
  presentation/conflict/
  presentation/components/
```

Inspect the existing repository before restructuring it. Do not refactor
for aesthetics.

## One-week implementation priority

1.  Qdrant Edge actually works on Android.
2.  Persistent local memory.
3.  On-device embeddings.
4.  Offline semantic retrieval.
5.  Grounded local RAG answer.
6.  Policy engine.
7.  Durable sync outbox.
8.  Qdrant Server sync.
9.  Cloud → edge knowledge.
10. Conflict demo.
11. UI polish.
12. Stretch features.

If time gets tight, protect the first 8 items. A simple real edge system
is better than a beautiful fake one.

## Explicitly postpone unless core is stable

-   elaborate animations
-   authentication complexity
-   large cloud dashboards
-   multi-agent systems
-   voice assistant
-   multimodal embeddings
-   image understanding
-   complicated cloud LLM orchestration
-   huge Rust abstractions
-   many screens
-   speculative optimization

## Testing

### Persistence

``` text
Insert → close app → reopen → retrieve
```

### Offline

``` text
Disable network → add memory → search → ask
```

### Privacy

``` text
LOCAL_ONLY memory → attempt sync → verify server does not contain it
```

### Sync

``` text
Offline → create SYNC memory → reconnect → outbox processed → server contains it
```

### Retry

``` text
Sync fails → outbox remains → retry → success
```

### Conflict

``` text
Contradictory local/cloud values → pull → detect → display resolution state
```

## Agent operating rules

1.  Read this file before coding.
2.  Inspect the repository, Gradle files, native code, current Qdrant
    integration and docs before editing.
3.  Identify what actually works and what is missing.
4.  Find the highest-risk blocker.
5.  Implement the smallest useful vertical slice.
6.  Build/test after meaningful changes.
7.  Report exactly what was verified.
8.  Update `WORKING.md` with actual state.
9.  Never fake core functionality.
10. Never silently replace Qdrant Edge.
11. Keep Rust/JNI behind a small Kotlin interface.
12. Preserve working behavior and make small changes.
13. Avoid premature abstractions and giant refactors.
14. Do not claim a feature works unless it was actually tested.

Required workflow:

``` text
Read context → inspect repo → compare state → identify blocker
→ implement smallest slice → build/test → report verification → update docs → repeat
```

## Definition of Done

Minimum credible product:

``` text
Android app
+ Qdrant Edge locally
+ persistent local vectors
+ on-device embeddings
+ offline semantic retrieval
+ grounded local answer
+ memory capture
+ policy decision
+ durable sync outbox
+ Qdrant Server sync
+ visible sync state
```

Strong version:

``` text
+ cloud→edge knowledge
+ conflict detection
+ redaction
+ activity timeline
+ edge-to-cloud escalation
+ field-maintenance demo
```

## Final mental model

``` text
                 ┌──────────────────────┐
                 │       Android        │
                 │ Ask / Capture / UI   │
                 │ Local AI             │
                 │ Policy Engine        │
                 │ Qdrant Edge          │
                 │ Room Outbox          │
                 └──────────┬───────────┘
                            │ intermittent connectivity
                            ▼
                 ┌──────────────────────┐
                 │        Cloud         │
                 │ Qdrant Server       │
                 │ Shared Knowledge    │
                 │ Optional AI         │
                 └──────────┬───────────┘
                            │ knowledge returns
                            ▼
                      richer local memory
```

The defining property is not "AI on Android."

> **A device has its own useful memory, can reason over that memory
> without the internet, controls what leaves the device, and becomes
> richer when connectivity returns.**

## Source basis

This context consolidates the official PS03 problem statement and the
supplied EdgeMind research/documentation. The supplied research
explicitly used a Python edge runtime and listed mobile-native apps as
out of scope for its first release; that implementation choice is
intentionally replaced here by the team's Kotlin-native Android
decision. The research concepts retained here are local semantic memory,
hybrid retrieval, policy, durable outbox/sync, cloud knowledge, evolving
memory/conflict handling, UI observability, and the edge-to-cloud AI
loop.
