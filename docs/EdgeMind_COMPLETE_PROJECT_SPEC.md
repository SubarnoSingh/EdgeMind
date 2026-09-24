# EdgeMind --- Complete Project Specification & OpenCode Context

> **Problem Statement 03 --- AI-Powered Edge Memory & Intelligence
> Platform**

## 1. Executive Summary

**EdgeMind** is an offline-first AI work-memory platform for Android
edge devices.

Its purpose is not merely to answer questions. Its purpose is to give a
device its own persistent, searchable memory that remains useful when
the internet disappears, protects information that should remain local,
and learns from new information when connectivity returns.

The core promise is:

> **A device has its own useful memory, can reason over that memory
> without the internet, controls what leaves the device, and becomes
> richer when connectivity returns.**

The flagship demonstration is a **field maintenance assistant**. A
technician can carry an Android device containing manuals, previous
repair records, observations and procedures. They can search and ask
questions completely offline, capture new observations, and later
synchronize approved knowledge with a central Qdrant Server.

------------------------------------------------------------------------

# 2. What Problem Are We Solving?

Modern AI applications often assume:

-   permanent internet access
-   cloud vector databases
-   cloud LLMs
-   cloud storage
-   stable connectivity
-   willingness to send data away from the device

That assumption fails in environments such as:

-   remote industrial sites
-   factories
-   warehouses
-   vehicles
-   field operations
-   robots
-   sensitive enterprise environments
-   locations with unreliable connectivity

PS03 asks for an edge-native system that can:

1.  maintain local vector memory
2.  retrieve information locally
3.  continue operating offline
4.  handle changing information
5.  decide what should stay local and what can synchronize
6.  synchronize with Qdrant Server
7.  handle evolving memory and conflicts
8.  expose a meaningful edge-to-cloud workflow

EdgeMind combines these into one product.

------------------------------------------------------------------------

# 3. The Product in One Sentence

> **EdgeMind gives an Android edge device a private, persistent memory
> that can be searched and used for AI reasoning offline, while
> intelligently synchronizing useful knowledge with the cloud when
> connectivity returns.**

------------------------------------------------------------------------

# 4. What EdgeMind Is NOT

This distinction is important.

EdgeMind is not:

-   just a chatbot
-   just a notes application
-   just a PDF reader
-   just a RAG application
-   just a local vector database
-   just an Android UI
-   just Qdrant embedded into an app
-   a cloud-first AI assistant

The differentiator is the entire lifecycle:

``` text
Knowledge enters the edge
        ↓
Local memory is created
        ↓
Knowledge can be retrieved offline
        ↓
AI reasons over local evidence
        ↓
User creates new knowledge
        ↓
Policy determines what can leave
        ↓
Offline changes wait in an outbox
        ↓
Connectivity returns
        ↓
Approved knowledge synchronizes
        ↓
Cloud knowledge comes back
        ↓
Conflicts are detected
        ↓
The edge device becomes more knowledgeable
```

------------------------------------------------------------------------

# 5. Product Philosophy

There are four major principles.

## 5.1 Edge first

The device should remain useful without the network.

The cloud is an enhancement, not the foundation.

## 5.2 Memory is persistent

Information should survive:

-   screen changes
-   process restarts
-   app restarts
-   offline periods

## 5.3 Privacy is a system behavior

The app should not merely display "private".

It must actually prevent `LOCAL_ONLY` data from entering the
synchronization pipeline.

## 5.4 The device learns through work

The user does not need to "train an AI model".

Instead:

``` text
documents
notes
observations
procedures
repair history
cloud knowledge
        ↓
      memory
```

The memory continuously evolves.

------------------------------------------------------------------------

# 6. Flagship Scenario --- Field Maintenance

Use field maintenance for the demo because it demonstrates almost every
PS03 requirement naturally.

## Persona

A field technician services equipment such as:

-   pumps
-   compressors
-   turbines
-   industrial machinery

The technician may work where:

-   internet is unreliable
-   latency is undesirable
-   site information is sensitive
-   procedures change over time

## Before EdgeMind

Information is fragmented:

``` text
PDF manuals
Cloud tickets
Personal notes
Memory of technicians
Site-specific observations
```

Finding useful information is difficult.

## With EdgeMind

``` text
Manuals
Repair history
Observations
Procedures
        ↓
   EdgeMind Memory
        ↓
 Semantic + keyword retrieval
        ↓
   Offline answer
```

New observations are captured directly into the same memory system.

------------------------------------------------------------------------

# 7. Example End-to-End Story

Imagine a pump called `P-101`.

The technician imports:

``` text
P-101 Maintenance Manual.pdf
P-101 Repair History.txt
```

The app extracts the text, chunks it and creates embeddings.

The data is stored locally.

The technician disconnects the phone from the internet.

They ask:

> Why does P-101 keep leaking?

EdgeMind searches its local Qdrant Edge memory.

It retrieves:

-   maintenance manual sections
-   previous repair notes
-   relevant observations

The answer includes citations.

The technician then captures:

> P-101 seal failed again. Root cause appears to be cavitation. Suction
> pressure raised to 2.1 bar.

EdgeMind evaluates the information.

If policy permits:

``` text
SYNC_REDACTED
```

The memory enters the local outbox.

The technician continues working offline.

Later, connectivity returns.

The outbox uploads the permitted information to Qdrant Server.

The cloud can aggregate knowledge from multiple devices.

A new procedure may then be published.

The Android device pulls that knowledge back.

If the new procedure conflicts with an older local note, EdgeMind
detects the conflict instead of silently overwriting it.

------------------------------------------------------------------------

# 8. System Architecture

The final architecture is Android-first.

``` text
┌───────────────────────────────────────────────────────────┐
│                    Android Application                    │
│                                                           │
│  Compose UI                                               │
│      │                                                    │
│      ▼                                                    │
│  ViewModels / StateFlow                                   │
│      │                                                    │
│      ▼                                                    │
│  Domain / Use Cases                                       │
│      │                                                    │
│      ├──────────────┬───────────────┬────────────────┐   │
│      ▼              ▼               ▼                ▼   │
│   Memory        Retrieval        Policy           Sync   │
│   Service       Service          Engine           Engine │
│      │              │               │                │   │
│      └──────────────┴───────────────┴────────────────┘   │
│                         │                                 │
│              ┌──────────┴──────────┐                     │
│              ▼                     ▼                      │
│            Room             Qdrant Edge                  │
│       metadata/outbox       local vectors                │
│              │                     │                      │
│              └──────────┬──────────┘                      │
│                         │                                 │
│                 Local AI Runtime                          │
│                 Embeddings / LLM                          │
└─────────────────────────┬─────────────────────────────────┘
                          │
                   intermittent network
                          │
                          ▼
┌───────────────────────────────────────────────────────────┐
│                         Cloud                             │
│                                                           │
│                      Qdrant Server                        │
│                           │                               │
│                  Cloud knowledge                          │
│                           │                               │
│             Optional backend / cloud AI                   │
└───────────────────────────────────────────────────────────┘
```

------------------------------------------------------------------------

# 9. Technology Stack

## Android

-   Kotlin
-   Jetpack Compose
-   Material 3 where useful
-   Coroutines
-   StateFlow
-   ViewModel
-   Navigation Compose
-   Room
-   WorkManager

## Local vector database

**Qdrant Edge**

This is mandatory for the local vector memory.

## Native integration

Because Qdrant Edge's primary native integration is through Rust,
isolate it behind a Kotlin interface.

Conceptually:

``` text
Kotlin
 ↓
JNI / FFI
 ↓
Rust
 ↓
qdrant-edge
 ↓
EdgeShard
 ↓
device storage
```

## Embeddings

Use an Android-compatible on-device embedding runtime.

The exact model should be selected based on:

-   Android compatibility
-   model size
-   latency
-   embedding quality
-   offline operation
-   licensing

The embedding model dimension must be discovered from the actual
selected model and used consistently by the Qdrant collection.

## LLM

Use an abstraction:

``` text
LLMService
```

Possible implementations:

``` text
ExtractiveAnswerService
LocalLLMService
CloudLLMService
```

The first milestone should not be blocked by a complicated local LLM.

## Cloud

-   Qdrant Server
-   small backend/service if required for orchestration
-   optional cloud LLM
-   optional curation service

------------------------------------------------------------------------

# 10. Why Room AND Qdrant?

They solve different problems.

## Qdrant Edge

Responsible for:

-   vectors
-   semantic retrieval
-   vector payload
-   similarity search

## Room

Responsible for:

-   memory metadata
-   sync state
-   outbox
-   events
-   conflict records
-   version information
-   policy decisions
-   app state

Do not turn Room into a replacement vector database.

The architecture is:

``` text
              MEMORY
                 │
       ┌─────────┴─────────┐
       ▼                   ▼
 Qdrant Edge              Room
 vectors/search       metadata/state
```

------------------------------------------------------------------------

# 11. Qdrant Edge Integration

This is the highest-risk technical component.

Do NOT begin by implementing the entire product.

First prove:

``` text
Android
  ↓
JNI
  ↓
Rust
  ↓
Qdrant Edge
  ↓
Create EdgeShard
  ↓
Insert vector
  ↓
Search vector
  ↓
Close
  ↓
Restart
  ↓
Search again
```

Only after this succeeds should the rest of the application be built
around it.

## Kotlin interface

Use something similar to:

``` kotlin
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

The rest of Kotlin should not care whether the underlying implementation
uses JNI, Rust or another internal mechanism.

------------------------------------------------------------------------

# 12. Native Boundary

The native boundary should be intentionally small.

Bad:

``` text
Compose
 ↓
Rust details everywhere
 ↓
JNI everywhere
 ↓
business logic in native code
```

Good:

``` text
Compose
 ↓
Kotlin domain
 ↓
LocalVectorStore
 ↓
QdrantEdgeVectorStore
 ↓
NativeBridge
 ↓
Rust
```

This lets the Android application evolve without spreading native
complexity throughout the codebase.

------------------------------------------------------------------------

# 13. Core Data Model

A conceptual memory:

``` text
Memory
├── memoryId
├── title
├── content
├── chunkId
├── source
├── type
├── tags
├── createdAt
├── updatedAt
├── origin
├── syncDecision
├── syncState
├── sensitivity
├── importance
├── version
├── contentHash
├── subjectKey
├── supersedes
├── tombstone
└── metadata
```

Not every field must live in Qdrant.

------------------------------------------------------------------------

# 14. Memory Types

Start small:

``` text
DOCUMENT
NOTE
OBSERVATION
PROCEDURE
REPAIR
EVENT
CLOUD_KNOWLEDGE
```

Avoid over-modeling.

------------------------------------------------------------------------

# 15. Memory Origins

Possible origins:

``` text
LOCAL
CLOUD
SYNCED
```

The exact enum names can change, but origin must remain distinguishable.

------------------------------------------------------------------------

# 16. Sync Decisions

Every memory can have one of:

``` text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

## LOCAL_ONLY

The information must remain on the device.

Example:

``` text
Gate code for Site 7 is 4412
```

## SYNC

Safe to upload as-is.

Example:

``` text
Maintenance procedure revision 4
```

## SYNC_REDACTED

Useful knowledge can be shared, but private identifiers should be
removed.

Example:

``` text
Technician John repaired pump P-101 at Site 7
```

could become conceptually:

``` text
Pump P-101 had recurring seal failure caused by cavitation.
```

------------------------------------------------------------------------

# 17. Ingestion Pipeline

Every memory should pass through a consistent pipeline.

``` text
INPUT
  ↓
Validation
  ↓
Normalization
  ↓
Chunking
  ↓
Embedding
  ↓
Policy evaluation
  ↓
Duplicate/conflict check
  ↓
Qdrant Edge
  ↓
Room metadata
  ↓
Outbox if permitted
  ↓
Activity event
```

------------------------------------------------------------------------

# 18. Document Pipeline

For PDF/TXT/Markdown:

``` text
Document
 ↓
Extract text
 ↓
Normalize
 ↓
Split into chunks
 ↓
Generate embeddings
 ↓
Create vector points
 ↓
Store in Qdrant Edge
 ↓
Store metadata
```

Each chunk should retain enough metadata to explain where it came from.

Example:

``` text
source = P-101 Maintenance Manual
page = 17
section = Seal Inspection
chunk = 4
```

Citations should use real metadata.

------------------------------------------------------------------------

# 19. Chunking

Do not use arbitrary giant chunks.

Chunks should be:

-   semantically coherent
-   small enough for retrieval
-   large enough to preserve context

The exact chunk size should be tested against the selected embedding and
answer model.

Metadata should preserve:

``` text
document ID
chunk ID
page/section where available
source
```

------------------------------------------------------------------------

# 20. Retrieval Pipeline

The main path:

``` text
User question
      ↓
Query normalization
      ↓
Local embedding
      ↓
Qdrant Edge search
      ↓
Optional keyword retrieval
      ↓
Fusion
      ↓
Deduplication
      ↓
Filtering
      ↓
Ranking
      ↓
Top-K context
```

------------------------------------------------------------------------

# 21. Hybrid Retrieval

The deeper research identifies an important issue.

Dense embeddings are excellent for meaning.

They can be weaker for exact identifiers such as:

``` text
SKF-6205
E-4417
P-101
```

Therefore EdgeMind should support:

``` text
Dense semantic retrieval
+
Keyword/exact retrieval
+
fusion
```

A practical later implementation can use:

``` text
Dense results
+
BM25 results
↓
RRF
↓
final ranking
```

If the first Android milestone cannot support BM25 efficiently,
implement dense retrieval first while keeping the retrieval interface
ready for the keyword layer.

Do not sacrifice the Qdrant Edge milestone for premature hybrid-search
complexity.

------------------------------------------------------------------------

# 22. RAG Pipeline

``` text
Question
 ↓
Retrieve top K memories
 ↓
Build context
 ↓
Answer engine
 ↓
Answer
 ↓
Citations
```

The answer engine must follow strict grounding rules.

It should:

-   use retrieved evidence
-   cite retrieved memories
-   acknowledge insufficient evidence
-   avoid inventing facts
-   never fabricate citations

------------------------------------------------------------------------

# 23. Confidence

The system can conceptually classify:

``` text
HIGH CONFIDENCE
MEDIUM CONFIDENCE
LOW CONFIDENCE
```

High confidence:

``` text
answer locally
```

Low confidence:

``` text
ONLINE
 ↓
optional cloud escalation
```

Offline:

``` text
return limited answer
+
tell user verification is needed
```

Do not present an arbitrary confidence number unless the system actually
computes it.

------------------------------------------------------------------------

# 24. Local LLM Strategy

The project does NOT require a local LLM to be the first thing
implemented.

Recommended order:

``` text
Qdrant Edge
 ↓
local embeddings
 ↓
retrieval
 ↓
extractive answer / controlled answer
 ↓
local LLM later
```

The architecture should allow:

``` text
LLMService
```

so a local model can be added without rewriting the application.

This protects the one-week schedule.

------------------------------------------------------------------------

# 25. Policy Engine

The policy engine answers:

> What should happen to this memory?

Inputs may include:

``` text
content
type
tags
sensitivity
scope
importance
user choice
site rules
```

Output:

``` text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

It should also produce a reason.

Example:

``` text
Decision: LOCAL_ONLY

Reason:
Detected site-sensitive access information.
```

------------------------------------------------------------------------

# 26. Policy Pipeline

``` text
New memory
 ↓
PolicyEngine.evaluate()
 ↓
PolicyDecision
 ├── decision
 ├── reason
 ├── sensitivity
 └── redactedContent?
```

This decision is then stored in Room.

------------------------------------------------------------------------

# 27. Privacy Architecture

The critical rule:

``` text
LOCAL_ONLY
      ↓
NO OUTBOX ENTRY
      ↓
NO CLOUD REQUEST
```

Do not rely on UI behavior to enforce this.

For redaction:

``` text
Original private memory
       ↓
Redaction
       ↓
Sync-safe representation
       ↓
Outbox
```

The original must remain local.

------------------------------------------------------------------------

# 28. Offline Architecture

Offline is not an error.

It is a supported operating mode.

Offline functionality:

``` text
✓ View memories
✓ Search memories
✓ Add memory
✓ Import supported documents
✓ Generate embeddings
✓ Query Qdrant Edge
✓ Generate local/extractive answer
✓ Queue syncable changes
✓ View activity
```

Cloud-only operations should visibly explain their limitation.

------------------------------------------------------------------------

# 29. Sync Outbox

Room should hold a durable outbox.

Example:

``` text
SyncOutbox
├── operationId
├── memoryId
├── operationType
├── payload
├── createdAt
├── attempts
├── state
└── lastError
```

States:

``` text
PENDING
IN_FLIGHT
ACKED
FAILED
DEAD
```

------------------------------------------------------------------------

# 30. WorkManager

Use WorkManager for deferred sync.

Example:

``` text
Memory created
 ↓
Policy says SYNC
 ↓
Outbox row
 ↓
No internet
 ↓
Wait
 ↓
Connectivity returns
 ↓
WorkManager executes
 ↓
Upload
 ↓
Server acknowledgement
 ↓
ACKED
```

Use retry/backoff.

------------------------------------------------------------------------

# 31. Idempotency

A retry must not create duplicate cloud memories.

Use deterministic IDs / stable memory identifiers.

Conceptually:

``` text
memoryId
+
version
+
operationId
```

The server should be able to recognize repeated operations.

------------------------------------------------------------------------

# 32. Cloud Architecture

Qdrant Server is the centralized knowledge layer.

Possible logical collections:

``` text
device_memory
cloud_knowledge
```

The exact schema can evolve.

The key conceptual separation is:

``` text
device-authored information
        ≠
shared curated knowledge
```

This prevents cloud synchronization from blindly replacing local work.

------------------------------------------------------------------------

# 33. Push Flow

``` text
Room Outbox
 ↓
Select pending operations
 ↓
Check connectivity
 ↓
Send to cloud
 ↓
Server validates
 ↓
Server upserts/deletes
 ↓
ACK
 ↓
Mark local operation completed
 ↓
Activity event
```

On failure:

``` text
attempt++
 ↓
backoff
 ↓
retry
```

------------------------------------------------------------------------

# 34. Pull Flow

``` text
Check cloud knowledge version
 ↓
Changed?
 ├── NO → nothing
 └── YES
       ↓
    fetch changes
       ↓
    local staging
       ↓
    update cloud knowledge
       ↓
    conflict scan
       ↓
    activity event
```

Do not replace local memory blindly.

------------------------------------------------------------------------

# 35. Evolving Memory

Memory can change.

Possible operations:

``` text
CREATE
UPDATE
DELETE
SUPERSEDE
MERGE
RESTORE
```

Maintain:

``` text
version
contentHash
updatedAt
supersedes
tombstone
```

This enables the system to explain why information changed.

------------------------------------------------------------------------

# 36. Conflict Types

## Version conflict

Same logical memory, different versions.

## Duplicate

Two memories say essentially the same thing.

## Semantic conflict

Two memories refer to the same subject but disagree.

Example:

``` text
Local:
Torque = 45 Nm

Cloud:
Torque = 52 Nm
```

This should become:

``` text
CONFLICT
```

rather than a silent overwrite.

------------------------------------------------------------------------

# 37. Conflict Resolution

Possible actions:

``` text
KEEP_LOCAL
KEEP_CLOUD
KEEP_BOTH
MERGE
```

Automatic resolution is appropriate only when the rule is deterministic.

Otherwise:

``` text
Conflict Queue
 ↓
User review
 ↓
Resolution
 ↓
New versioned state
```

------------------------------------------------------------------------

# 38. Cloud → Edge Learning

This is one of the strongest differentiators.

Example:

``` text
Device A:
repair observation
        ↓
Cloud
        ↓
multiple devices contribute
        ↓
curated knowledge
        ↓
Device B
```

Device B can then use the knowledge while offline.

This demonstrates that EdgeMind is more than local RAG.

It is a **knowledge lifecycle**.

------------------------------------------------------------------------

# 39. Edge-to-Cloud AI Escalation

Optional but valuable.

``` text
Question
 ↓
Local retrieval
 ↓
Can local memory answer confidently?
 ├── YES → local answer
 └── NO
      ↓
    online?
      ├── YES → cloud escalation
      └── NO → answer with limitation
```

If cloud answers the question, the result can optionally be cached as a
local knowledge item with an explicit source and origin.

Do not treat cloud-generated answers as unquestionable truth.

------------------------------------------------------------------------

# 40. Android UI

Visual style:

``` text
Dark charcoal
+
muted purple/blue gradients
+
subtle blur
+
technical status indicators
+
minimal typography
```

Avoid:

-   generic ChatGPT clone
-   huge gradients
-   excessive glassmorphism
-   fake dashboards
-   unnecessary animation

------------------------------------------------------------------------

# 41. Main Navigation

Recommended:

``` text
ASK
MEMORY
ACTIVITY
```

Secondary actions:

``` text
CAPTURE
SYNC
CONFLICTS
SETTINGS
```

------------------------------------------------------------------------

# 42. Ask Screen

Structure:

``` text
┌──────────────────────────────┐
│ EDGE ONLY      OFFLINE       │
│                              │
│ Ask EdgeMind                 │
│                              │
│ Why does P-101 keep leaking? │
│                              │
│ ──────────────────────────── │
│                              │
│ Answer                       │
│                              │
│ ...                          │
│                              │
│ Sources                      │
│ • Manual §4.2                │
│ • Repair note                │
│ • Observation                │
│                              │
│ 3 local memories used        │
└──────────────────────────────┘
```

------------------------------------------------------------------------

# 43. Memory Screen

Show:

``` text
Search memory...
```

Cards:

``` text
P-101 seal failure
OBSERVATION
LOCAL • PENDING SYNC
```

``` text
Maintenance Manual
DOCUMENT
LOCAL
```

``` text
Site access information
NOTE
LOCAL ONLY
```

------------------------------------------------------------------------

# 44. Capture Screen

``` text
New memory

Title
Type
Content
Tags

Policy preview

SYNC_REDACTED
Useful maintenance knowledge detected.
Site identifiers will be removed.

[Save]
```

The policy preview should reflect actual policy evaluation.

------------------------------------------------------------------------

# 45. Activity Screen

Example:

``` text
12:41
QUESTION_RECEIVED

12:41
EMBEDDING_GENERATED
Local

12:41
QDRANT_EDGE_SEARCH
4 results

12:41
CONTEXT_BUILT

12:41
ANSWER_GENERATED

12:48
MEMORY_CREATED

12:48
POLICY_DECISION
SYNC_REDACTED

12:48
OUTBOX_ENQUEUED
```

This is both useful UX and powerful hackathon observability.

------------------------------------------------------------------------

# 46. Sync Screen

Show:

``` text
Connectivity
ONLINE

Local memories
124

Pending sync
3

Last push
2 min ago

Last pull
2 min ago

Cloud knowledge
v17
```

When offline:

``` text
OFFLINE

3 changes waiting
They will synchronize automatically
when connectivity returns.
```

------------------------------------------------------------------------

# 47. Conflict Screen

Example:

``` text
CONFLICT DETECTED

P-101 Torque

LOCAL
45 Nm

CLOUD
52 Nm

Why?
Both records describe the same
procedure but contain different values.

[Keep Local]
[Keep Cloud]
[Keep Both]
[Merge]
```

------------------------------------------------------------------------

# 48. Activity Events

Use a controlled event model.

Examples:

``` text
MEMORY_CREATED
DOCUMENT_IMPORTED
EMBEDDING_CREATED
VECTOR_UPSERTED
SEARCH_STARTED
SEARCH_COMPLETED
ANSWER_GENERATED
POLICY_EVALUATED
OUTBOX_ENQUEUED
SYNC_STARTED
SYNC_COMPLETED
SYNC_FAILED
CLOUD_KNOWLEDGE_UPDATED
CONFLICT_DETECTED
CONFLICT_RESOLVED
```

------------------------------------------------------------------------

# 49. Recommended Package Structure

``` text
app/
└── src/main/
    ├── java/com/...
    │
    ├── core/
    │   ├── common/
    │   ├── logging/
    │   └── model/
    │
    ├── data/
    │   ├── local/
    │   │   ├── room/
    │   │   └── qdrant/
    │   ├── remote/
    │   │   └── api/
    │   └── repository/
    │
    ├── domain/
    │   ├── memory/
    │   ├── retrieval/
    │   ├── rag/
    │   ├── policy/
    │   ├── sync/
    │   └── conflict/
    │
    ├── ai/
    │   ├── embedding/
    │   └── llm/
    │
    ├── native/
    │   └── qdrant/
    │
    └── presentation/
        ├── ask/
        ├── memory/
        ├── capture/
        ├── activity/
        ├── sync/
        ├── conflict/
        └── components/
```

Do not restructure an existing repository unnecessarily.

------------------------------------------------------------------------

# 50. Important Interfaces

``` kotlin
interface EmbeddingService {
    suspend fun embed(text: String): FloatArray
}

interface LocalVectorStore {
    suspend fun initialize()
    suspend fun upsert(points: List<VectorPoint>)
    suspend fun search(
        vector: FloatArray,
        limit: Int
    ): List<SearchResult>
    suspend fun delete(id: String)
    suspend fun count(): Long
}

interface MemoryRepository {
    suspend fun create(memory: Memory): Memory
    suspend fun update(memory: Memory): Memory
    suspend fun delete(id: String)
    suspend fun get(id: String): Memory?
    suspend fun list(): List<Memory>
}

interface RetrievalService {
    suspend fun search(
        query: String,
        limit: Int
    ): List<RetrievedMemory>
}

interface RagService {
    suspend fun answer(
        question: String
    ): RagAnswer
}

interface PolicyEngine {
    suspend fun evaluate(
        memory: Memory
    ): PolicyDecision
}

interface SyncEngine {
    suspend fun sync()
}

interface ConflictResolver {
    suspend fun detect(memory: Memory): List<Conflict>
}
```

The exact APIs can evolve after repository inspection.

------------------------------------------------------------------------

# 51. Dependency Injection

Use a lightweight DI approach.

If Hilt is already part of the Android project, use it.

If not, do not introduce Hilt solely because it is fashionable.

The priority is:

``` text
testable dependencies
+
clear construction
```

not framework count.

------------------------------------------------------------------------

# 52. Threading

Heavy operations must not block the UI thread.

Examples:

-   embedding
-   document extraction
-   Qdrant operations
-   database operations
-   sync
-   native calls
-   LLM inference

Use Coroutines appropriately.

State should flow through:

``` text
Repository
 ↓
ViewModel
 ↓
StateFlow
 ↓
Compose
```

------------------------------------------------------------------------

# 53. Security

Minimum:

-   do not log raw sensitive memories
-   do not upload `LOCAL_ONLY`
-   do not put secrets in source control
-   cloud credentials belong in secure configuration
-   validate cloud responses
-   protect local databases where practical
-   keep native boundaries narrow

Full end-to-end encryption is not a first-week requirement unless time
permits.

------------------------------------------------------------------------

# 54. Error Handling

The application must distinguish:

``` text
NO_NETWORK
LOCAL_STORAGE_ERROR
EMBEDDING_ERROR
QDRANT_ERROR
SYNC_ERROR
CLOUD_ERROR
INVALID_DOCUMENT
CONFLICT
```

Do not show:

``` text
Something went wrong
```

for every failure.

The user should know whether:

-   the device is offline
-   local storage failed
-   cloud sync failed
-   the document could not be parsed

------------------------------------------------------------------------

# 55. Observability

Track real values where practical:

``` text
memory count
retrieval latency
embedding latency
pending outbox
sync attempts
last sync
conflict count
```

Do not invent metrics.

If benchmarking is not implemented, do not display a fake benchmark.

------------------------------------------------------------------------

# 56. Testing Plan

## Unit tests

Test:

-   chunking
-   policy decisions
-   redaction
-   memory mapping
-   sync state transitions
-   conflict rules
-   RRF if implemented

## Integration tests

Test:

``` text
memory → embedding → Qdrant Edge
```

``` text
memory → policy → outbox
```

``` text
outbox → cloud
```

## Persistence test

``` text
write
 ↓
restart
 ↓
read
```

## Offline test

``` text
network disabled
 ↓
add
 ↓
search
 ↓
answer
```

## Privacy test

``` text
LOCAL_ONLY
 ↓
sync attempt
 ↓
server inspection
 ↓
memory absent
```

## Sync test

``` text
offline
 ↓
create
 ↓
reconnect
 ↓
sync
 ↓
server contains permitted memory
```

## Conflict test

``` text
local value = A
cloud value = B
 ↓
pull
 ↓
conflict
```

------------------------------------------------------------------------

# 57. Development Phases

## Phase 0 --- Repository inspection

Before changing anything:

-   inspect current project
-   inspect Gradle
-   inspect source
-   inspect native code
-   inspect existing agents
-   inspect documentation
-   determine what works

Output:

``` text
CURRENT STATE
BLOCKERS
NEXT STEP
```

------------------------------------------------------------------------

# 58. Phase 1 --- Qdrant Edge Spike

Goal:

> Prove Qdrant Edge can actually operate inside the Android application.

Implement only:

``` text
Android
 ↓
JNI
 ↓
Rust
 ↓
Qdrant Edge
```

Test:

``` text
create
insert
search
restart
search
```

Definition of done:

> A vector inserted before restart can be retrieved after restart.

------------------------------------------------------------------------

# 59. Phase 2 --- Local Memory

Build:

-   Memory model
-   Room metadata
-   Qdrant adapter
-   embedding service
-   memory creation
-   memory explorer

Definition:

> User can add a memory and find it semantically while offline.

------------------------------------------------------------------------

# 60. Phase 3 --- Documents

Add:

-   PDF/TXT/Markdown import
-   text extraction
-   chunking
-   embeddings
-   Qdrant insertion
-   source metadata

Definition:

> User can import a manual and search its content offline.

------------------------------------------------------------------------

# 61. Phase 4 --- Local RAG

Add:

-   query
-   retrieval
-   context builder
-   answer service
-   citations
-   insufficient-evidence handling

Definition:

> User can ask a work question and receive a grounded local answer.

------------------------------------------------------------------------

# 62. Phase 5 --- Policy

Add:

``` text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

Add explanation.

Definition:

> Every captured memory receives a real policy decision.

------------------------------------------------------------------------

# 63. Phase 6 --- Offline Outbox

Add:

-   Room outbox
-   WorkManager
-   retry
-   backoff
-   sync states

Definition:

> A syncable memory created offline survives until connectivity returns.

------------------------------------------------------------------------

# 64. Phase 7 --- Qdrant Server

Add:

-   server connection
-   push
-   acknowledgement
-   idempotency
-   cloud knowledge collection

Definition:

> EdgeMind can synchronize approved memory with Qdrant Server.

------------------------------------------------------------------------

# 65. Phase 8 --- Cloud → Edge

Add:

-   knowledge version
-   pull
-   local cloud knowledge
-   conflict scan

Definition:

> Cloud knowledge can become available locally without destroying local
> memory.

------------------------------------------------------------------------

# 66. Phase 9 --- Conflicts

Add:

-   version conflicts
-   duplicate detection
-   at least one semantic conflict demo
-   conflict UI
-   resolution

Definition:

> EdgeMind visibly handles changing and contradictory knowledge.

------------------------------------------------------------------------

# 67. Phase 10 --- Demo Polish

Polish:

-   UI
-   activity
-   sync visualization
-   conflict visualization
-   sample data
-   loading/error states
-   offline indicator

Do not polish before the core works.

------------------------------------------------------------------------

# 68. One-Week Hackathon Strategy

If only one week is available, do not attempt equal depth everywhere.

Priority:

``` text
DAY 1
Qdrant Edge proof

DAY 2
Local memory + embeddings

DAY 3
Offline retrieval + RAG

DAY 4
Policy + Room outbox

DAY 5
Qdrant Server sync

DAY 6
Cloud → edge + conflict demo

DAY 7
UI polish + testing + demo rehearsal
```

If Qdrant Edge takes longer:

``` text
Do NOT sacrifice Qdrant Edge
to build decorative features.
```

------------------------------------------------------------------------

# 69. Two-Person Team Split

Recommended division:

## Developer A --- Android / AI

Own:

-   Compose
-   ViewModels
-   memory UI
-   embedding
-   retrieval
-   RAG
-   Room

## Developer B --- Native / Sync / Cloud

Own:

-   Rust/Qdrant Edge
-   JNI
-   Qdrant Server
-   sync engine
-   WorkManager
-   policy
-   conflicts

Both should agree on interfaces early.

------------------------------------------------------------------------

# 70. Parallel Contract

Developer A can work against:

``` kotlin
LocalVectorStore
EmbeddingService
SyncEngine
```

Developer B implements the actual infrastructure behind them.

This prevents both people from editing the same parts constantly.

------------------------------------------------------------------------

# 71. Demo Data

Use deterministic demo data.

Example memories:

### Manual

``` text
P-101 Maintenance Manual
Seal inspection and cavitation procedure.
```

### Repair

``` text
P-101 repair on 2026-09-18.
Seal failed.
Initial investigation suggested cavitation.
```

### Local-only

``` text
Gate code for Site 7 is 4412.
```

### Syncable

``` text
P-101 seal replacement indicates recurring cavitation.
```

### Conflict

``` text
Old local procedure:
Torque = 45 Nm
```

``` text
New cloud procedure:
Torque = 52 Nm
```

------------------------------------------------------------------------

# 72. Final Demo Sequence

## Step 1

Open EdgeMind.

Show:

``` text
EDGE READY
```

## Step 2

Import manual.

Show real processing:

``` text
Extracting
Chunking
Embedding
Storing locally
```

## Step 3

Disable network.

Show:

``` text
OFFLINE
```

## Step 4

Ask:

> Why does P-101 keep leaking?

Show:

``` text
Answer
+
sources
+
local retrieval
```

## Step 5

Capture observation.

Show:

``` text
SYNC_REDACTED
```

## Step 6

Create multiple offline memories.

Show:

``` text
3 pending sync
```

## Step 7

Reconnect.

Show:

``` text
SYNCING
3 uploaded
SYNCED
```

## Step 8

Introduce cloud knowledge.

Show:

``` text
NEW CLOUD KNOWLEDGE
```

## Step 9

Create conflict:

``` text
45 Nm vs 52 Nm
```

Show:

``` text
CONFLICT DETECTED
```

## Step 10

Resolve.

## Step 11

Go offline again.

Ask a question that now depends on the updated knowledge.

Show that the device still works.

------------------------------------------------------------------------

# 73. What Makes This Technically Interesting

The important technical story is:

``` text
On-device memory
+
on-device embeddings
+
Qdrant Edge
+
offline RAG
+
privacy policy
+
durable synchronization
+
cloud knowledge
+
conflict resolution
```

The strongest demo is not:

> "Look, our AI can answer a question."

It is:

> "Disconnect the internet. The device still knows. Add new knowledge.
> Keep private information local. Reconnect. Share approved knowledge.
> Receive knowledge from elsewhere. Detect when knowledge conflicts.
> Disconnect again. The device remains useful."

------------------------------------------------------------------------

# 74. Agent Instructions

This file is also the operating context for OpenCode agents.

Before coding:

1.  Read this entire file.
2.  Inspect the actual repository.
3.  Inspect current implementation.
4.  Identify existing working functionality.
5.  Identify the highest-risk missing component.
6.  Propose the smallest next step.
7.  Implement.
8.  Build.
9.  Test.
10. Report exactly what was verified.
11. Update `WORKING.md`.
12. Continue.

------------------------------------------------------------------------

# 75. Non-Negotiable Agent Rules

## Never fake Qdrant Edge

Do not replace it with SQLite vectors.

## Never fake offline behavior

Do not simply hide network errors.

## Never fake sync

Do not display "Synced" unless synchronization actually happened.

## Never fake retrieval

Do not hard-code search results.

## Never fake citations

Every citation must correspond to real stored source metadata.

## Never upload LOCAL_ONLY data

This is a core privacy invariant.

## Never silently overwrite conflicts

Contradictory knowledge must be represented explicitly.

## Never build everything at once

Work incrementally.

## Never rewrite working code without reason

Preserve existing behavior.

## Never claim tests that were not run

Report actual verification.

------------------------------------------------------------------------

# 76. OpenCode Working Protocol

For each task, the agent should respond internally according to:

``` text
TASK
↓
Repository inspection
↓
Relevant architecture
↓
Implementation plan
↓
Change
↓
Build
↓
Test
↓
Result
↓
Documentation update
```

If blocked:

``` text
BLOCKER
WHY
EVIDENCE
OPTIONS
RECOMMENDED NEXT EXPERIMENT
```

Do not hide blockers behind generated code.

------------------------------------------------------------------------

# 77. Documentation Files

Maintain:

``` text
README.md
WORKING.md
ARCHITECTURE.md
```

## README.md

For:

-   project overview
-   screenshots
-   setup
-   demo
-   architecture summary

## WORKING.md

For:

-   current implementation state
-   completed features
-   known issues
-   commands
-   environment
-   current blockers
-   next task

## ARCHITECTURE.md

For:

-   system architecture
-   data flow
-   native boundary
-   Qdrant Edge
-   sync
-   policy
-   conflict model

Documentation must describe reality.

------------------------------------------------------------------------

# 78. Definition of Minimum Viable EdgeMind

The project is minimally credible when all of these are real:

``` text
✓ Kotlin Android app
✓ Qdrant Edge running locally
✓ persistent local vector memory
✓ on-device embeddings
✓ offline semantic retrieval
✓ grounded answer
✓ memory capture
✓ policy decision
✓ durable sync outbox
✓ Qdrant Server synchronization
✓ visible sync state
```

A stronger implementation adds:

``` text
✓ cloud → edge knowledge
✓ redaction
✓ conflict detection
✓ conflict resolution
✓ hybrid retrieval
✓ local LLM
✓ edge-to-cloud escalation
✓ activity timeline
```

------------------------------------------------------------------------

# 79. Final Architecture Mental Model

``` text
                         EDGEMIND
                            │
             ┌──────────────┴──────────────┐
             │                             │
         ANDROID EDGE                    CLOUD
             │                             │
       ┌─────┴─────┐                 Qdrant Server
       │           │                      │
     Local AI   Local Memory              │
       │           │                      │
   Embeddings  Qdrant Edge                │
       │           │                      │
       └─────┬─────┘                      │
             │                            │
          Policy                          │
             │                            │
       ┌─────┴─────┐                      │
       │           │                      │
 LOCAL_ONLY    SYNCABLE                   │
       │           │                      │
       │        Room Outbox ──────────────┤
       │                                  │
       │                            Shared Knowledge
       │                                  │
       └──────────────────────────────────┘
                       │
                       ▼
                 Knowledge returns
                       │
                       ▼
                  Local Memory
                       │
                       ▼
                 Offline AI
```

------------------------------------------------------------------------

# 80. Final Product Statement

EdgeMind should ultimately feel like this:

> **Your device has a memory of its own.**
>
> It remembers the documents, observations and knowledge that matter to
> your work.
>
> It can search that memory and help you reason over it without the
> internet.
>
> It knows which information is private.
>
> It can work for hours or days without connectivity.
>
> When connectivity returns, it shares only what it is allowed to share.
>
> It can receive useful knowledge from the wider system.
>
> When information changes or conflicts, it makes that visible instead
> of silently hiding it.
>
> The result is an AI system that is not merely connected to a cloud ---
> it has a useful identity and memory at the edge.

------------------------------------------------------------------------

# 81. Absolute Final Rule

When forced to choose between:

``` text
more features
```

and

``` text
one more core feature actually working offline
```

choose the working offline feature.

When forced to choose between:

``` text
beautiful simulation
```

and

``` text
real Qdrant Edge behavior
```

choose real Qdrant Edge behavior.

When forced to choose between:

``` text
cloud dependency
```

and

``` text
local capability
```

choose local capability.

**EdgeMind is an edge-memory system first and an AI interface second.**
