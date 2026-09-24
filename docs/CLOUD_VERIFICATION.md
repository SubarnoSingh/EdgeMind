# Manual Live Cloud Verification

Deterministic tests cover the code paths; this document is the manual live
smoke test that exercises the REAL Qdrant Cloud cluster, the REAL cloud LLM
provider, and a REAL Android build against the running backend.

Prerequisites: `backend/.env` filled with real values (never commit it).

## 1. Backend + Qdrant Cloud

```bash
cd backend
npm install
npm run verify:config          # authenticates to Qdrant Cloud, lists collections
npm run dev                    # starts the backend, creates collections if missing
```

Check `/health`:

```bash
curl -s http://localhost:8080/health
# {"status":"ok","qdrant":"configured","llm":"configured","embedding":"..."}
```

## 2. Curated knowledge (cloud → edge source)

Ingest one curated item (UUID ids only):

```bash
curl -s -X POST http://localhost:8080/knowledge/ingest \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $ADMIN_API_KEY" \
  -d '[{
    "memoryId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "subjectKey":"FT-983.3",
    "title":"FT-983.3 procedure",
    "content":"FT-983.3 procedure revision 4.",
    "contentHash":"seed-hash-1",
    "version":4,"updatedAt":1700000000000,
    "origin":"CLOUD","authority":"central-engineering",
    "supersedes":null,"tombstone":false,"metadata":{}
  }]'
```

Pull it back through the paginated contract:

```bash
curl -s 'http://localhost:8080/knowledge?limit=50'
```

## 3. Cloud answer

```bash
curl -s -X POST http://localhost:8080/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"What is the latest approved procedure revision?"}'
# {"answer":"...","authority":"cloud-llm · <model>","provider":"<model>","requestId":"..."}
```

Expected failure honesty: blank question → 400; unset LLM credential → 503
`LLM_NOT_CONFIGURED`.

## 4. Android against the backend

Build with the backend URL (emulator loopback):

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
./gradlew -PcloudBackendUrl=http://10.0.2.2:8080 :app:assembleDebug
```

On an attached device/emulator, manually verify:

1. Create a SYNC memory → Sync screen shows pending → worker pushes →
   becomes `SYNCED`; backend upserts into `device_memory` (re-run the push
   with the same memory by editing it — the stable `UPSERT-<memoryId>`
   operation must not duplicate).
2. Create a LOCAL_ONLY memory (gate code) → never leaves the device: zero
   outbox rows; `device_memory` never contains it.
3. Create a SYNC_REDACTED memory → only the redacted representation appears
   in `device_memory` (verify the stored point payload contains no private
   identifiers).
4. Pull cloud knowledge (Memory screen) → seeded FT-983.3 item enters the
   Phase 7 classifier as CLOUD-origin knowledge and is searchable offline.
5. Ask a question with insufficient local evidence while online → ESCALATING
   → cloud answer with provenance ("not verified locally"), NOT stored.
6. Press [Save to memory] → cached as CLOUD_KNOWLEDGE (SYNCED, no outbox
   row), searchable offline; restart the app → still there.
7. Ask the same question offline after saving → answered from local memory
   with citations; zero cloud calls.

## 5. Cloud Qdrant checks (direct)

Using the Qdrant Cloud dashboard (or the backend's ingest/pull endpoints):

- write succeeds (step 2 ingest / step 4 push)
- read/search succeeds (pull returns the items)
- delete/tombstone: tombstone the seeded item by re-ingesting it with
  `tombstone:true` and a higher version, then pull — the device applies the
  Phase 7 tombstone semantics.

## Reporting

If any live step fails, record the exact request, status code, and error
body. Do NOT substitute mocks and claim success.
