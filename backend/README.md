# EdgeMind Backend

Small Node.js/TypeScript cloud boundary for EdgeMind. The Android app talks
**only** to this backend — never directly to Qdrant Cloud or a cloud LLM. All
cloud credentials live here, in environment variables.

## Architecture

```text
Android (Qdrant Edge stays on-device)
   │  HTTPS
   ▼
EdgeMind Backend (Express + TypeScript)
   ├── Qdrant Cloud        (device_memory, cloud_knowledge collections)
   └── Cloud LLM provider  (OpenAI-compatible chat completions)
```

Endpoints (all deterministic JSON):

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | honest config status (booleans only, never secrets) |
| PUT | `/sync/operations/:operationId` | idempotent device knowledge push |
| GET | `/knowledge?cursor=&limit=` | incremental curated-knowledge pull (opaque cursor) |
| POST | `/knowledge/ingest` | curation endpoint (ADMIN_API_KEY guarded when configured) |
| GET | `/knowledge/search?q=` | cloud semantic search (requires embeddings provider) |
| POST | `/answers` | cloud question answering (question text only) |

## Setup

```bash
cd backend
npm install
cp .env.example .env   # fill in REAL values; .env is gitignored
npm run dev            # or: npm run build && npm start
```

## Environment variables

| Variable | Required for | Notes |
|---|---|---|
| `QDRANT_URL` | push/pull/search | Qdrant Cloud cluster URL |
| `QDRANT_API_KEY` | push/pull/search | never logged, never returned to clients |
| `QDRANT_DEVICE_MEMORY_COLLECTION` | push | default `device_memory` |
| `QDRANT_CLOUD_KNOWLEDGE_COLLECTION` | pull | default `cloud_knowledge` |
| `EMBEDDING_DIMENSION` | collection creation | must match the embeddings provider output |
| `CLOUD_LLM_API_KEY` / `CLOUD_LLM_BASE_URL` / `CLOUD_LLM_MODEL` | `/answers` | OpenAI-compatible provider; replaceable |
| `CLOUD_LLM_TIMEOUT_MS` | `/answers` | default 20000 |
| `CLOUD_EMBEDDING_API_KEY` / `CLOUD_EMBEDDING_MODEL` | `/knowledge/search` | optional; search is honestly disabled without it |
| `ADMIN_API_KEY` | ingest guard | when empty, ingest is open (development only) |
| `PORT`, `MAX_REQUEST_BYTES`, `MAX_QUESTION_CHARS`, `MAX_ANSWER_CHARS` | limits | see `.env.example` |

Secrets never appear in: source, tests, git, logs, README, error responses, or
the Android app.

## Qdrant Cloud setup

1. Create a cluster in Qdrant Cloud; copy its URL and API key into `.env`.
2. On first start the backend creates both collections (single named dense
   vector `semantic`, cosine, dimension `EMBEDDING_DIMENSION`).
3. Verify: `npm run verify:config` (authenticates and lists collections;
   prints no secrets).

## Cloud LLM setup

Any OpenAI-compatible endpoint works (`CLOUD_LLM_BASE_URL`,
`CLOUD_LLM_MODEL`, `CLOUD_LLM_API_KEY`). Without a credential the backend
still starts; `/answers` honestly reports `LLM_NOT_CONFIGURED` (503) — it
never fabricates an answer. Provider responses are validated: non-blank,
bounded length, parseable JSON, with auth/rate-limit/timeout classification.

## Local development

- `npm test` — deterministic suite (fake Qdrant gateway + scripted LLM
  provider; no cloud access required).
- `npm run build` — typecheck/compile with tsc.
- Android: point the app at this backend with
  `./gradlew -PcloudBackendUrl=http://10.0.2.2:8080 :app:assembleDebug`
  (cleartext is permitted only for 10.0.2.2/loopback by the app's network
  security config; everything else requires HTTPS).

## Tests

`node --import tsx --test tests/` — 29 tests covering: config handling,
request validation (LOCAL_ONLY rejection, CLOUD-origin push-back rejection,
size limits, type whitelists), sync push + operation idempotency, curated
knowledge ingest + cursor pagination, cloud answering (success, auth/rate/
timeout/malformed/oversized/empty), secret non-leakage, admin guard, and
health honesty.

## Deployment considerations

- Serve over TLS (reverse proxy); the Android app enforces HTTPS except for
  local development hosts.
- Set `ADMIN_API_KEY` for any shared deployment.
- Request bodies are capped (`MAX_REQUEST_BYTES`), answers capped
  (`MAX_ANSWER_CHARS`), LLM calls time out, and no request bodies or
  credentials are logged.
