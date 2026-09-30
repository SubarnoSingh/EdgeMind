# EdgeMind

> **Offline Industrial Intelligence --- maintenance knowledge,
> retrieval, and grounded decision support at the edge.**

EdgeMind is an offline-first Android platform for capturing, storing,
retrieving, and synchronizing industrial maintenance knowledge directly
on a field device.

It is designed around a simple operational requirement:

**A technician should still be able to access useful, grounded
maintenance knowledge when connectivity is unavailable.**

Instead of making a remote database or cloud AI service the center of
the application, EdgeMind keeps the operational knowledge layer
on-device using **Qdrant Edge**. Cloud services are optional for
synchronization, centralized knowledge, and cloud-assisted workflows.

------------------------------------------------------------------------

## Table of Contents

-   [What is EdgeMind?](#what-is-edgemind)
-   [Why EdgeMind?](#why-edgemind)
-   [Core Features](#core-features)
-   [How EdgeMind Works](#how-edgemind-works)
-   [Architecture](#architecture)
-   [Data Flow](#data-flow)
-   [Qdrant Edge](#qdrant-edge)
-   [Hybrid Retrieval and RAG](#hybrid-retrieval-and-rag)
-   [Sync Architecture](#sync-architecture)
-   [Conflict Resolution](#conflict-resolution)
-   [Offline-First Behavior](#offline-first-behavior)
-   [Industrial Maintenance Workflow](#industrial-maintenance-workflow)
-   [UI and Application Modules](#ui-and-application-modules)
-   [Technology Stack](#technology-stack)
-   [Repository Structure](#repository-structure)
-   [Data Model](#data-model)
-   [Security and Data Policies](#security-and-data-policies)
-   [Testing and Verification](#testing-and-verification)
-   [Demo Scenario](#demo-scenario)
-   [Current Limitations](#current-limitations)
-   [Build and Run](#build-and-run)
-   [Development Architecture](#development-architecture)
-   [Documentation](#documentation)
-   [Project Status](#project-status)
-   [Future Directions](#future-directions)
-   [License](#license)

------------------------------------------------------------------------

## What is EdgeMind?

EdgeMind is an Android-based industrial knowledge and maintenance
intelligence system.

It combines:

-   local structured records,
-   local vector search,
-   keyword retrieval,
-   grounded question answering,
-   maintenance timelines,
-   asset-oriented views,
-   conflict management,
-   optional cloud synchronization,
-   and Qdrant Edge persistence.

The application is intended for environments such as:

-   manufacturing plants,
-   process plants,
-   utilities,
-   industrial maintenance teams,
-   field service operations,
-   remote facilities,
-   and environments with unreliable connectivity.

The key architectural principle is:

> **The local device remains useful and authoritative for its local
> operational knowledge even when the network disappears.**

------------------------------------------------------------------------

# Why EdgeMind?

Industrial environments often have an uncomfortable combination of
requirements:

1.  Maintenance data must be available in the field.
2.  Connectivity may be intermittent or unavailable.
3.  Technicians need answers quickly.
4.  Historical maintenance records contain valuable context.
5.  Cloud synchronization is useful, but should not make the local
    application unusable.
6.  AI-generated answers must be grounded in actual evidence rather than
    invented information.

EdgeMind addresses these requirements by moving the primary knowledge
and retrieval layer onto the Android device.

### Traditional cloud-first approach

``` text
Technician
    |
    v
Android App
    |
    v
Internet
    |
    v
Cloud Database / AI
    |
    v
Answer
```

If the network disappears, the workflow becomes severely degraded.

### EdgeMind approach

``` text
                 ┌──────────────────────┐
                 │      Android App     │
                 │                      │
                 │ Dashboard            │
                 │ Machines             │
                 │ Ask                  │
                 │ Activity             │
                 │ Sync                 │
                 └──────────┬───────────┘
                            │
                            v
                 ┌──────────────────────┐
                 │   Domain / Use Cases │
                 └──────────┬───────────┘
                            │
                            v
                 ┌──────────────────────┐
                 │    Qdrant Edge       │
                 │                      │
                 │ Records              │
                 │ Payloads             │
                 │ Vectors              │
                 │ Sync operations      │
                 │ Conflicts            │
                 │ Cursor/watermarks    │
                 └──────────┬───────────┘
                            │
                     Optional network
                            │
                            v
                 ┌──────────────────────┐
                 │     Cloud Backend    │
                 │                      │
                 │ Sync API             │
                 │ Knowledge API        │
                 │ Optional cloud AI    │
                 └──────────────────────┘
```

The local path does not require the cloud.

------------------------------------------------------------------------

# Core Features

## 1. Offline-first industrial knowledge

Maintenance records are stored locally on the Android device using
Qdrant Edge.

The local knowledge layer contains both:

-   structured payloads,
-   and vector representations.

This allows the application to continue retrieving relevant information
without a network connection.

------------------------------------------------------------------------

## 2. Qdrant Edge as the local system of record

Qdrant Edge is not used merely as a vector cache.

EdgeMind uses it for the production local record layer.

The same Qdrant Edge shard can contain:

-   maintenance records,
-   observations,
-   procedures,
-   incidents,
-   documents/knowledge records,
-   vectors,
-   tombstones,
-   sync operations,
-   conflict evidence,
-   synchronization cursor state.

This removes the need for a separate production Room/SQLite data path.

Room remains part of the repository primarily for legacy/rollback and
migration compatibility.

------------------------------------------------------------------------

## 3. Structured industrial records

EdgeMind represents operational knowledge as records.

Examples include:

-   maintenance events,
-   observations,
-   procedures,
-   incidents,
-   repairs,
-   operational notes,
-   knowledge documents.

Records can contain information such as:

``` text
recordId
recordType
title
content
subjectKey
source
createdAt
updatedAt
version
policy
syncState
contentHash
supersedes
tombstone
embedding
provenance
```

------------------------------------------------------------------------

## 4. Asset-oriented organization

Industrial assets are represented through record namespaces such as:

``` text
p-101/seal
p-101/maintenance
p-101/incident
p-101/procedure
```

The UI derives an asset workspace from these records.

For example:

``` text
P-101
│
├── Maintenance
├── Observations
├── Incidents
├── Procedures
└── Historical activity
```

This allows the same underlying record system to support an industrial
asset view without requiring a separate duplicated storage model.

------------------------------------------------------------------------

## 5. Grounded Ask

Technicians can ask questions about stored maintenance knowledge.

Example:

> Why does P-101 keep experiencing mechanical seal failures?

EdgeMind retrieves relevant local records and produces a grounded answer
with evidence.

The answer is not supposed to invent an unsupported diagnosis.

Instead, the system distinguishes between:

-   stored facts,
-   retrieved evidence,
-   and inference.

Example evidence:

``` text
Repeated Seal Failure
Seal Failure Investigation
Mechanical Seal Leakage
Mechanical Seal Replacement
```

The user can inspect citations and evidence details.

------------------------------------------------------------------------

## 6. Hybrid retrieval

EdgeMind combines multiple retrieval signals.

### Dense retrieval

Uses the vector representation stored in Qdrant Edge.

Useful for:

-   semantic similarity,
-   concept matching,
-   differently worded maintenance descriptions.

### Keyword retrieval

Uses structured payload fields and bounded Qdrant scrolling.

Useful for:

-   exact equipment identifiers,
-   part numbers,
-   fault codes,
-   maintenance terminology.

For example:

``` text
SKF-6205
P-101
INC-1042
mechanical seal
cavitation
```

### Hybrid ranking

The two retrieval paths are combined using Reciprocal Rank Fusion:

``` text
RRF(d) = Σ 1 / (K + rank(d))
```

with:

``` text
K = 60
```

Identifier matches can receive additional weighting where appropriate.

------------------------------------------------------------------------

# How EdgeMind Works

A typical lifecycle looks like this:

``` text
Technician captures information
            |
            v
       Create Record
            |
            v
   Apply data policy
            |
            v
 Calculate canonical hash
            |
            v
 Generate local embedding
            |
            v
      Qdrant Edge
       /       \
      /         \
 Payload       Vector
      \         /
       \       /
      Local record
            |
            v
       Retrieval
            |
            v
      Grounded Ask
            |
            v
 Evidence + citations
```

If synchronization is enabled:

``` text
Local Record
     |
     v
Change Detection
     |
     v
Sync Operation
     |
     v
Outbox in Qdrant Edge
     |
     v
Cloud Sync
     |
     +---- ACK
     |
     +---- Retry
     |
     +---- Conflict
     |
     +---- DEAD
```

------------------------------------------------------------------------

# Architecture

EdgeMind follows a layered Android architecture.

``` text
┌───────────────────────────────────────────────┐
│                 Presentation                  │
│                                               │
│ Dashboard │ Machines │ Ask │ Sync │ Conflicts │
│ Record creation │ Record details             │
└───────────────────────┬───────────────────────┘
                        │
                        v
┌───────────────────────────────────────────────┐
│                    Domain                     │
│                                               │
│ Memory use cases                              │
│ Conflict use cases                            │
│ Sync abstractions                             │
│ RAG / retrieval contracts                      │
└───────────────────────┬───────────────────────┘
                        │
                        v
┌───────────────────────────────────────────────┐
│                     Data                      │
│                                               │
│ Qdrant Record Repository                      │
│ Qdrant Retrieval                              │
│ Qdrant Sync Engine                            │
│ Cloud Knowledge Ingestion                     │
│ Conflict Store                                │
└───────────────────────┬───────────────────────┘
                        │
                        v
┌───────────────────────────────────────────────┐
│              Native / Qdrant Layer            │
│                                               │
│ Kotlin → JNI → Rust → Qdrant Edge             │
└───────────────────────────────────────────────┘
```

------------------------------------------------------------------------

# Android → JNI → Rust → Qdrant Edge

Qdrant Edge is integrated through native Rust.

The high-risk path is:

``` text
Android/Kotlin
      |
      v
NativeBridge
      |
      v
JNI
      |
      v
Rust
      |
      v
qdrant-edge 0.8.0
      |
      v
Local persistent storage
```

This path is intentionally kept explicit.

There is no silent replacement of Qdrant Edge with an in-memory mock or
another local vector database.

------------------------------------------------------------------------

# Qdrant Edge

The application uses:

``` text
qdrant-edge = 0.8.0
```

The local Qdrant shard is the authoritative production local data layer.

The architecture supports:

-   payload-only records,
-   vector-bearing records,
-   batch upserts,
-   filtering,
-   payload indexes,
-   vector search,
-   persistence,
-   restart recovery,
-   tombstones,
-   deterministic identifiers,
-   synchronization metadata.

## Payload-only records

Not every record needs a vector.

Qdrant Edge supports payload-only records in the application
architecture.

This is important for records such as:

-   sync operations,
-   cursor state,
-   conflict evidence,
-   operational metadata.

Vector-bearing records are used for knowledge retrieval.

------------------------------------------------------------------------

# Record Storage

The production repository follows:

``` text
CreateMemoryUseCase
        |
        v
QdrantRecordMemoryRepository
        |
        v
MemoryRecordMapper
        |
        v
QdrantEdgeRecordStore
        |
        v
Qdrant Edge
```

A normal create/update operation approximately follows:

``` text
Input
  ↓
Policy evaluation
  ↓
Canonical content hash
  ↓
Embedding generation
  ↓
Record construction
  ↓
Qdrant Edge upsert
  ↓
Change detection
  ↓
Optional sync operation
```

This means the same production path is used for records created from the
application UI and records used by the retrieval system.

------------------------------------------------------------------------

# Embeddings

The current local embedding implementation is a deterministic lexical
baseline:

``` text
FeatureHashingEmbeddingService
```

with:

``` text
512 dimensions
```

It is intentionally deterministic and local.

It does **not** require a remote embedding API.

This is important for the offline architecture.

The current implementation should be considered a production-compatible
deterministic baseline rather than a neural semantic embedding model.

------------------------------------------------------------------------

# Retrieval and RAG

The retrieval pipeline is Qdrant-native.

``` text
User question
      |
      v
Query processing
      |
      +-----------------------+
      |                       |
      v                       v
Dense retrieval        Keyword retrieval
      |                       |
      |                       |
      +-----------+-----------+
                  |
                  v
             RRF ranking
                  |
                  v
        Dedup / tombstone filter
                  |
                  v
          Evidence selection
                  |
                  v
          RAG sufficiency check
                  |
          +-------+-------+
          |               |
       Sufficient       Insufficient
          |               |
          v               v
   Grounded answer    Honest diagnostic
          |
          v
     Citations
```

## Evidence-first answering

The system is designed so that an answer can be traced back to retrieved
records.

The user can inspect:

-   source record,
-   title,
-   record type,
-   asset,
-   content,
-   provenance,
-   retrieval evidence.

If evidence is insufficient, the system can report insufficient evidence
instead of pretending that a diagnosis is known.

------------------------------------------------------------------------

# Sync Architecture

Cloud synchronization is optional.

The local system remains functional without it.

The sync subsystem is Qdrant-native and stores synchronization
operations inside Qdrant Edge.

## Operation identity

Operations use deterministic identities based on record identity and
version.

Conceptually:

``` text
<OP>:<record_uuid>:<record_version>
```

This provides idempotency.

## Operation types

The architecture supports:

``` text
UPSERT
TOMBSTONE
```

## Operation states

The sync lifecycle includes:

``` text
PENDING
IN_FLIGHT
ACKED
FAILED
DEAD
```

The system also tracks record synchronization states such as:

``` text
PENDING
SYNCED
CONFLICT
TOMBSTONED
```

------------------------------------------------------------------------

# Sync Lifecycle

``` text
Local change
     |
     v
Change detector
     |
     v
Is change new?
     |
     +---- No ----> Ignore / classify
     |
    Yes
     |
     v
Create deterministic operation
     |
     v
Qdrant operation store
     |
     v
PENDING
     |
     v
IN_FLIGHT
     |
     +----------+
     |          |
    ACK       Failure
     |          |
     v          v
  ACKED      Retry
                |
                v
              FAILED
                |
          max attempts?
            /      \
          No        Yes
          |          |
       Retry        DEAD
```

------------------------------------------------------------------------

# Cloud → Device

The system also supports cloud-to-local ingestion.

The flow is:

``` text
Cloud knowledge
      |
      v
Validate payload
      |
      v
Change classification
      |
      v
NEW / UPDATE / DUPLICATE /
STALE / CONFLICT / TOMBSTONE
      |
      v
Qdrant Edge
```

A durable cursor is stored in the Qdrant data layer so synchronization
can resume after restart.

------------------------------------------------------------------------

# Conflict Resolution

Distributed maintenance systems can encounter conflicting versions.

EdgeMind does not silently overwrite newer information.

The architecture uses:

-   version comparison,
-   deterministic conflict evidence,
-   authority information,
-   durable conflict records,
-   explicit resolution actions.

Supported user actions include:

``` text
KEEP_LOCAL
KEEP_CLOUD
DISMISS
```

Conflict resolution creates a deterministic follow-up state rather than
simply deleting the conflict.

------------------------------------------------------------------------

# Tombstones and Deletion

Deletion is represented as a record state rather than pretending the
record never existed.

This is important for synchronization.

Example:

``` text
Record version 4
      |
      v
TOMBSTONED
      |
      v
TOMBSTONE sync operation
      |
      v
Cloud
```

The system also prevents invalid resurrection of deleted records.

------------------------------------------------------------------------

# Offline-First Behavior

The most important operational property of EdgeMind is that the local
path does not depend on the cloud.

When connectivity disappears:

``` text
                    INTERNET
                       X
                       |
                       |
Technician ──> Android Device
                    |
                    v
               Qdrant Edge
                    |
                    v
               Local retrieval
                    |
                    v
               Local evidence
```

The device can still:

-   open local records,
-   browse machines/assets,
-   inspect maintenance history,
-   create new records,
-   retrieve local evidence,
-   run the local grounded Ask pipeline.

Cloud synchronization is deferred until connectivity is available.

------------------------------------------------------------------------

# Industrial Maintenance Workflow

A typical technician workflow is:

## Step 1 --- Select an asset

Example:

``` text
P-101
Industrial centrifugal pump
```

## Step 2 --- Inspect history

The technician sees:

``` text
Maintenance
Observations
Incidents
Procedures
Repairs
```

## Step 3 --- Ask a question

Example:

> Why does P-101 keep experiencing mechanical seal failures?

The application searches local knowledge.

## Step 4 --- Inspect evidence

The answer is accompanied by retrieved records.

The technician can inspect the source records instead of trusting an
unexplained AI response.

## Step 5 --- Capture new information

The technician can add:

``` text
Observation
Maintenance event
Operational note
Procedure
```

## Step 6 --- Continue offline

The new information is stored locally.

## Step 7 --- Synchronize later

When connectivity becomes available, eligible records can synchronize
with the cloud.

------------------------------------------------------------------------

# UI and Application Modules

EdgeMind currently uses an industrial-style dark UI.

## Dashboard

Provides an operational overview including:

-   assets,
-   record activity,
-   synchronization state,
-   operational indicators.

## Machines

Displays asset-oriented workspaces derived from locally stored record
namespaces.

Example:

``` text
P-101
```

## Machine Detail

Provides:

-   activity timeline,
-   maintenance records,
-   observations,
-   procedures,
-   record details,
-   Ask access,
-   conflict access,
-   new record actions.

## Ask

Grounded question answering with:

-   question input,
-   answer,
-   evidence,
-   citations,
-   provenance.

## Record Detail

Allows inspection of the actual underlying operational record.

## Sync

Displays synchronization state and operational sync information.

## Conflicts

Provides a workflow for inspecting and resolving synchronization
conflicts.

## Create Record

Allows a technician to capture new operational information directly on
the device.

------------------------------------------------------------------------

# Technology Stack

## Android

-   Kotlin
-   Jetpack Compose
-   Android Architecture Components
-   ViewModels
-   WorkManager
-   Kotlin coroutines

## Native

-   Rust
-   JNI
-   `qdrant-edge 0.8.0`

## Local intelligence

-   Qdrant Edge
-   deterministic 512-dimensional feature-hashing embeddings
-   hybrid retrieval
-   Reciprocal Rank Fusion
-   extractive/grounded answer pipeline

## Backend

-   TypeScript
-   Node.js
-   HTTP API
-   synchronization endpoints
-   knowledge endpoints
-   validation and sync-safety logic

## Legacy / migration

-   Room
-   KSP

Room is not the active production source for the final Qdrant-native
architecture.

------------------------------------------------------------------------

# Repository Structure

``` text
EdgeMemo/
│
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/example/EdgeMemo/
│       │   │
│       │   ├── core/
│       │   │   ├── record/
│       │   │   └── sync/
│       │   │
│       │   ├── data/
│       │   │   ├── cloud/
│       │   │   ├── conflict/
│       │   │   ├── local/
│       │   │   ├── remote/
│       │   │   ├── repository/
│       │   │   ├── retrieval/
│       │   │   └── sync/
│       │   │
│       │   ├── domain/
│       │   │   ├── conflict/
│       │   │   ├── memory/
│       │   │   └── sync/
│       │   │
│       │   ├── native/
│       │   │   └── qdrant/
│       │   │
│       │   ├── presentation/
│       │   │   ├── ask/
│       │   │   ├── conflicts/
│       │   │   ├── dashboard/
│       │   │   ├── machines/
│       │   │   ├── record/
│       │   │   ├── shell/
│       │   │   └── sync/
│       │   │
│       │   └── ui/
│       │       └── theme/
│       │
│       ├── test/
│       └── androidTest/
│
├── backend/
│   ├── src/
│   │   ├── models/
│   │   ├── routes/
│   │   ├── services/
│   │   └── validation.ts
│   │
│   └── tests/
│
├── rust/
│   └── edgememo_qdrant/
│       └── src/
│           ├── jni.rs
│           └── store.rs
│
├── docs/
│   ├── PHASE_12_QDRANT_SYNC_ARCHITECTURE.md
│   ├── PHASE_13_1_QDRANT_CUTOVER_AUDIT.md
│   ├── PHASE_13_2_QDRANT_APPLICATION_CUTOVER.md
│   ├── PHASE_13_3_QDRANT_RETRIEVAL_RAG_CUTOVER.md
│   ├── PHASE_13_4_FINAL_QDRANT_CUTOVER.md
│   ├── UI_PHASE_1_APPLICATION_SHELL.md
│   ├── UI_PHASE_2_GROUNDED_ASK.md
│   ├── UI_PHASE_3_MAINTENANCE_INTELLIGENCE.md
│   ├── UI_PHASE_4_CONFLICT_RESOLUTION.md
│   └── UI_PHASE_5_OPERATIONAL_ACTIVITY.md
│
├── AGENTS.md
├── WORKING.md
└── README.md
```

------------------------------------------------------------------------

# Data Model

A simplified record can be viewed conceptually as:

``` json
{
  "id": "record-uuid",
  "type": "MAINTENANCE",
  "title": "Mechanical Seal Leakage",
  "content": "Seal leakage observed during inspection...",
  "subjectKey": "p-101/seal",
  "version": 3,
  "createdAt": "...",
  "updatedAt": "...",
  "policy": "LOCAL_ONLY",
  "syncState": "SYNCED",
  "contentHash": "...",
  "tombstone": false
}
```

The actual implementation contains additional metadata and provenance
fields.

------------------------------------------------------------------------

# Data Policies

EdgeMind supports data policies including:

``` text
LOCAL_ONLY
SYNC
SYNC_REDACTED
```

## LOCAL_ONLY

The record remains local and does not become a normal cloud
synchronization operation.

## SYNC

The record can participate in synchronization.

## SYNC_REDACTED

The record can synchronize after applying the defined redaction
behavior.

This makes data sharing an explicit policy decision instead of an
accidental side effect.

------------------------------------------------------------------------

# Security and Safety Principles

EdgeMind follows several important principles.

### No fake operational data

Application metrics should represent actual stored state.

### No fake AI claims

The application should not claim that a model diagnosed something when
the evidence only supports an observation or inference.

### Evidence before inference

Answers should be grounded in retrieved records.

### Explicit synchronization

Local information does not automatically become cloud information
without passing through the synchronization policy.

### Deterministic synchronization

Operation identifiers, content hashes, and version comparisons are
deterministic.

### No silent backend replacement

Qdrant Edge is a required architectural component.

------------------------------------------------------------------------

# Testing and Verification

The project contains extensive Android, Rust, backend, integration, and
UI-oriented tests.

The final development phases were verified through:

``` text
Android unit tests
Rust tests
Backend tests
Android lint
Debug APK assembly
Physical-device verification
```

The Qdrant-native architecture was tested across:

-   record persistence,
-   payload-only records,
-   vector records,
-   filtering,
-   indexes,
-   restart persistence,
-   crash boundaries,
-   synchronization,
-   retries,
-   conflict resolution,
-   tombstones,
-   retrieval,
-   RAG,
-   UI workflows.

The final development verification reached:

``` text
Android tests: 486+
Rust tests:    10+
Backend tests: 35+
```

Additional UI and operational tests were added after the core cutover.

Some Compose/Robolectric end-to-end tests have had timing-related
failures during development; these are not treated as proof that the
production data path is broken. Physical-device behavior was separately
verified for the hackathon workflow.

------------------------------------------------------------------------

# Demo Scenario

The primary demonstration asset is:

``` text
P-101
Industrial centrifugal pump
```

The prepared local dataset contains seven records covering maintenance
and operational knowledge.

The demo can show:

### 1. Dashboard

``` text
EdgeMind
Offline Industrial Intelligence
```

### 2. Machines

Open:

``` text
P-101
```

### 3. Maintenance history

Show the timeline of stored events.

### 4. Ask

Ask:

> Why does P-101 keep experiencing mechanical seal failures?

The application returns a grounded answer with evidence.

### 5. Procedure question

Ask:

> What should a technician inspect before replacing the seal again?

The answer is grounded in procedure/evidence records.

### 6. Add information

Use the record creation workflow to capture new field information.

### 7. Offline story

Explain that the local Qdrant Edge data remains available without
requiring the cloud knowledge layer.

------------------------------------------------------------------------

# Current Limitations

This project is a hackathon prototype and intentionally has several
areas that can be expanded.

## Document ingestion UI

The UI architecture contains document/knowledge concepts, but the final
hackathon demo focuses on structured operational records rather than a
fully polished document-upload workflow.

## Asset model

Assets are currently derived from record namespaces such as:

``` text
p-101/...
```

rather than being represented by a fully independent first-class machine
database entity.

## Embedding model

The current local embedding service is deterministic feature hashing
rather than a neural embedding model.

This provides an offline-compatible baseline but can be replaced with a
stronger on-device embedding model in a future version.

## Cloud AI

Cloud AI is not required for the core offline workflow.

Cloud-assisted answering can be extended independently without making it
a prerequisite for local retrieval.

## Production cloud deployment

The backend integration exists as a synchronization/knowledge boundary,
but production-scale deployment, authentication, observability, fleet
management, and operational infrastructure would require additional
work.

------------------------------------------------------------------------

# Build and Run

## Requirements

Recommended development environment:

-   Android Studio
-   JDK 21
-   Android SDK
-   Android NDK
-   Rust toolchain
-   Node.js/npm for backend development

The project was developed and verified using an Android environment with
JDK 21.

------------------------------------------------------------------------

## Clone

``` bash
git clone https://github.com/SubarnoSingh/EdgeMind.git
cd EdgeMind
```

------------------------------------------------------------------------

## Android build

Set Java 21:

``` bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

Then:

``` bash
./gradlew assembleDebug
```

APK output:

``` text
app/build/outputs/apk/debug/app-debug.apk
```

------------------------------------------------------------------------

## Android tests

``` bash
./gradlew test
```

For lint:

``` bash
./gradlew lint
```

------------------------------------------------------------------------

## Install on a connected device

``` bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

------------------------------------------------------------------------

# Development Architecture

The project was built incrementally rather than replacing the
architecture in one step.

The major architectural progression was:

``` text
Phase 1
Qdrant Edge JNI foundation
        ↓
Phase 2
Local memory + vector storage
        ↓
Phase 3
Ingestion
        ↓
Phase 4
Hybrid retrieval / RAG
        ↓
Phase 5–8
Policy + legacy sync + cloud integration
        ↓
Phase 9
Qdrant-native architecture design
        ↓
Phase 10
Structured Qdrant persistence
        ↓
Phase 11
Qdrant-native record layer
        ↓
Phase 12
Qdrant-native synchronization
        ↓
Phase 13
Full application / retrieval / cloud cutover
        ↓
UI phases
Industrial operational interface
```

This history is documented in the `docs/` directory.

------------------------------------------------------------------------

# Architectural Guarantees

The final architecture is designed around the following guarantees:

### Local-first

The application does not require cloud availability to access local
knowledge.

### Qdrant-native

The production local record and retrieval architecture uses Qdrant Edge.

### Idempotent sync

Deterministic operation IDs allow repeated delivery attempts without
intentionally duplicating operations.

### Version-aware writes

Older versions should not silently overwrite newer knowledge.

### Durable conflict evidence

Conflicts remain inspectable until explicitly resolved.

### Tombstone safety

Deleted records are represented and synchronized as durable state.

### Crash recovery

Sync operations can recover from interrupted/in-flight processing.

### Grounded answers

The Ask pipeline is tied to retrieved evidence rather than unconstrained
generated claims.

------------------------------------------------------------------------

# Documentation

Detailed architecture documents are available under:

``` text
docs/
```

Important documents include:

  ----------------------------------------------------------------------------------
  Document                                       Purpose
  ---------------------------------------------- -----------------------------------
  `PHASE_12_QDRANT_SYNC_ARCHITECTURE.md`         Qdrant-native synchronization
                                                 architecture

  `PHASE_13_1_QDRANT_CUTOVER_AUDIT.md`           Audit of the old and new
                                                 persistence paths

  `PHASE_13_2_QDRANT_APPLICATION_CUTOVER.md`     Application persistence cutover

  `PHASE_13_3_QDRANT_RETRIEVAL_RAG_CUTOVER.md`   Retrieval and RAG cutover

  `PHASE_13_4_FINAL_QDRANT_CUTOVER.md`           Final Qdrant architecture

  `UI_PHASE_1_APPLICATION_SHELL.md`              Application shell

  `UI_PHASE_2_GROUNDED_ASK.md`                   Grounded Ask

  `UI_PHASE_3_MAINTENANCE_INTELLIGENCE.md`       Maintenance workspace

  `UI_PHASE_4_CONFLICT_RESOLUTION.md`            Conflict workflow

  `UI_PHASE_5_OPERATIONAL_ACTIVITY.md`           Operational activity and record
                                                 creation
  ----------------------------------------------------------------------------------

------------------------------------------------------------------------

# Project Status

**Status: Hackathon prototype / functional demonstration**

The current implementation includes:

-   Qdrant Edge native persistence
-   Kotlin/JNI/Rust integration
-   structured operational records
-   vector storage
-   hybrid retrieval
-   grounded Ask
-   asset-oriented maintenance workspace
-   local record creation
-   synchronization architecture
-   cloud integration boundary
-   conflict resolution
-   tombstone handling
-   Qdrant-native sync operations
-   physical Android device verification

The core demonstration path is functional end-to-end.

------------------------------------------------------------------------

# Future Directions

Potential next iterations include:

## Better on-device embeddings

Replace the deterministic feature-hashing baseline with a compact neural
embedding model optimized for Android/NNAPI/GPU/NPU execution.

## Document intelligence

Add a polished ingestion pipeline for:

-   PDF manuals,
-   maintenance reports,
-   inspection documents,
-   images,
-   scanned documents,
-   structured work orders.

## First-class asset management

Introduce a dedicated machine/asset domain model with:

-   equipment hierarchy,
-   plant → area → machine relationships,
-   serial numbers,
-   manufacturer metadata,
-   asset health.

## Multimodal maintenance intelligence

Allow technicians to attach:

-   photographs,
-   vibration data,
-   inspection images,
-   diagrams,
-   audio notes.

## Advanced local AI

Introduce fully on-device:

-   small language models,
-   rerankers,
-   specialized maintenance classifiers,
-   anomaly detection,
-   fault prediction.

## Fleet synchronization

Extend the cloud layer into a multi-device industrial knowledge platform
supporting:

-   technicians,
-   supervisors,
-   maintenance planners,
-   centralized knowledge,
-   fleet analytics.

------------------------------------------------------------------------

# Design Philosophy

EdgeMind is built around a simple principle:

> **AI should assist the technician, but the evidence should remain
> inspectable.**

The system therefore treats industrial knowledge as something that
should be:

-   available locally,
-   persisted reliably,
-   searchable,
-   traceable,
-   synchronized deliberately,
-   and presented with evidence.

The cloud is useful.

The network is useful.

AI is useful.

But the technician should not become dependent on any one of them merely
to access the knowledge already stored on the device.

------------------------------------------------------------------------

# Project

**EdgeMind --- Offline Industrial Intelligence**

Repository:

https://github.com/SubarnoSingh/EdgeMind

Built as an offline-first Android industrial maintenance intelligence
prototype using **Kotlin + Jetpack Compose + Rust + Qdrant Edge + hybrid
retrieval/RAG**.
