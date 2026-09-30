import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
import { canonicalDomainHash } from "../src/services/syncSafety.js";
import {
  FakeQdrantGateway,
  FakeCloudLlm,
  NoEmbedding,
  testConfig,
  validPushBody,
  UUID_A,
  UUID_B,
  closeServer,
  startServer,
} from "./helpers.js";

function setup() {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm(() => ({
    answer: "ok",
    authority: "cloud-llm · test-model",
    provider: "test-model",
    requestId: null,
  }));
  const { app } = buildApp({
    config: testConfig(),
    qdrant,
    llm,
    embedding: new NoEmbedding(),
  });
  return { qdrant, app };
}

async function putOperation(baseUrl: string, body: Record<string, unknown>) {
  const operationId = body.operationId ?? body.operation_id;
  return fetch(`${baseUrl}/sync/operations/${operationId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function versionedBody(
  version: number,
  contentSeed: string,
  options: { tombstone?: boolean; operationType?: "UPSERT" | "TOMBSTONE"; updatedAt?: number } = {},
): Record<string, unknown> {
  const tombstone = options.tombstone ?? false;
  const operationType = options.operationType ?? (tombstone ? "TOMBSTONE" : "UPSERT");
  const title = "Pump procedure";
  const content = `Pump maintenance ${contentSeed}`;
  const domainPayload = {
    title,
    content,
    subjectKey: "P-101-PROCEDURE",
    type: "PROCEDURE",
    tags: ["pump"],
    chunkId: null,
    source: "USER_ENTRY",
    supersedes: null,
    tombstone,
    deletedAt: null,
    metadata: { scope: "site" },
  };
  return {
    ...validPushBody(),
    operationId: `${operationType}:${UUID_A}:${version}`,
    operationType,
    title,
    content,
    memory: {
      ...(validPushBody().memory as Record<string, unknown>),
      version,
      contentHash: canonicalDomainHash(domainPayload),
      tombstone,
      updatedAt: options.updatedAt ?? 1700000000000,
    },
  };
}

function envelopeBody(
  version: number,
  tombstone = false,
): Record<string, unknown> {
  const operationType = tombstone ? "TOMBSTONE" : "UPSERT";
  return {
    _record_type: "outbox_op",
    operation_id: `${operationType}:${UUID_A}:${version}`,
    _record_id: UUID_A,
    _operation_type: operationType,
    _state: "IN_FLIGHT",
    _attempts: 0,
    _created_at: 1700000000000,
    _updated_at: 1700000000001,
    _version: version,
    _sync_decision: "SYNC",
    _redacted: false,
    title: "Pump procedure",
    content: "Pump maintenance procedure revision 4.",
    tombstone,
    ...(tombstone ? { deletedAt: 1700000000001 } : {}),
  };
}

test("valid sync operation is accepted and stored", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const res = await putOperation(server.baseUrl, validPushBody());
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.equal(body.accepted, true);
    assert.equal(body.memoryId, UUID_A);

    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored.length, 1);
    assert.equal(stored[0].payload.memoryId, UUID_A);
    assert.equal(stored[0].payload.title, "Pump procedure");
    assert.equal(stored[0].payload.contentHash, "hash-abc");
    assert.equal(stored[0].payload.version, 3);
  } finally {
    await closeServer(server.server);
  }
});

test("replaying the same operationId is idempotent (no duplicate write)", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const body = validPushBody();
    const first = await putOperation(server.baseUrl, body);
    assert.equal(first.status, 201);

    // Simulate a crashed worker replaying the identical operation.
    const replay = await putOperation(server.baseUrl, body);
    assert.equal(replay.status, 200);
    const replayBody = await replay.json();
    assert.equal(replayBody.duplicate, true);
    assert.equal(replayBody.accepted, true);

    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored.length, 1);
  } finally {
    await closeServer(server.server);
  }
});

test("LOCAL_ONLY payloads are rejected server-side", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const body = validPushBody({
      memory: {
        ...(validPushBody().memory as Record<string, unknown>),
        syncDecision: "LOCAL_ONLY",
      },
    });
    const res = await putOperation(server.baseUrl, body);
    assert.equal(res.status, 400);
    const payload = await res.json();
    assert.equal(payload.error.code, "INVALID_REQUEST");
    assert.match(payload.error.message, /LOCAL_ONLY/);
    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored.length, 0);
  } finally {
    await closeServer(server.server);
  }
});

test("CLOUD-origin payloads can never be pushed back", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const body = validPushBody({
      memory: {
        ...(validPushBody().memory as Record<string, unknown>),
        origin: "CLOUD",
      },
    });
    const res = await putOperation(server.baseUrl, body);
    assert.equal(res.status, 400);
    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored.length, 0);
  } finally {
    await closeServer(server.server);
  }
});

test("redacted payloads are accepted and flagged as redacted", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const body = validPushBody({
      content: "Replaced [redacted] seal at site 7",
      memory: {
        ...(validPushBody().memory as Record<string, unknown>),
        syncDecision: "SYNC_REDACTED",
        redacted: true,
      },
    });
    const res = await putOperation(server.baseUrl, body);
    assert.equal(res.status, 201);
    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored[0].payload.redacted, true);
    assert.equal(stored[0].payload.syncDecision, "SYNC_REDACTED");
  } finally {
    await closeServer(server.server);
  }
});

test("malformed payloads are rejected deterministically", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const cases: Array<[string, Record<string, unknown>]> = [
      ["blank content", validPushBody({ content: "   " })],
      ["oversized title", validPushBody({ title: "x".repeat(1001) })],
      ["bad type", validPushBody({ memory: { ...(validPushBody().memory as Record<string, unknown>), type: "HACK" } })],
      ["non-uuid memoryId", validPushBody({ memoryId: "not-a-uuid", operationId: "UPSERT-not-a-uuid" })],
      ["path/body mismatch", validPushBody()],
    ];
    for (const [name, body] of cases.slice(0, 4)) {
      const res = await putOperation(server.baseUrl, body);
      assert.equal(res.status, 400, name);
      const payload = await res.json();
      assert.equal(payload.error.code, "INVALID_REQUEST", name);
    }
    // Path/body mismatch needs a custom URL.
    const mismatch = validPushBody();
    const res = await fetch(`${server.baseUrl}/sync/operations/UPSERT-${UUID_B}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(mismatch),
    });
    assert.equal(res.status, 400);
  } finally {
    await closeServer(server.server);
  }
});

test("unconfigured qdrant produces honest 502, never fake success", async () => {
  const { app } = setup();
  const server = await startServer(app);
  try {
    const res = await putOperation(server.baseUrl, validPushBody());
    assert.equal(res.status, 502);
    const payload = await res.json();
    assert.equal(payload.error.code, "QDRANT_UNAVAILABLE");
  } finally {
    await closeServer(server.server);
  }
});

test("version matrix prevents stale overwrites and reports cloud state", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const v7 = await putOperation(server.baseUrl, versionedBody(7, "a".repeat(64)));
    assert.equal(v7.status, 201);
    assert.equal((await v7.json()).status, "APPLIED");

    const v8Body = versionedBody(8, "b".repeat(64));
    const expectedV8Hash = (v8Body.memory as Record<string, unknown>).contentHash;
    const v8 = await putOperation(server.baseUrl, v8Body);
    assert.equal(v8.status, 200);
    const v8Response = await v8.json();
    assert.equal(v8Response.status, "APPLIED");
    assert.equal(v8Response.cloudVersion, 8);
    assert.equal(v8Response.cloudContentHash, expectedV8Hash);

    const duplicate = await putOperation(server.baseUrl, versionedBody(8, "b".repeat(64)));
    assert.equal(duplicate.status, 200);
    assert.equal((await duplicate.json()).status, "DUPLICATE");

    const conflict = await putOperation(server.baseUrl, versionedBody(8, "c".repeat(64)));
    assert.equal(conflict.status, 409);
    const conflictResponse = await conflict.json();
    assert.equal(conflictResponse.status, "CONFLICT");
    assert.equal(conflictResponse.cloudVersion, 8);

    const stale = await putOperation(server.baseUrl, versionedBody(7, "a".repeat(64), { updatedAt: 9999999999999 }));
    assert.equal(stale.status, 409);
    const staleResponse = await stale.json();
    assert.equal(staleResponse.status, "STALE");
    assert.equal(staleResponse.cloudVersion, 8);

    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored[0].payload.version, 8);
    assert.equal(stored[0].payload.contentHash, expectedV8Hash);
    assert.equal(qdrant.upsertCalls, 2);
  } finally {
    await closeServer(server.server);
  }
});

test("tombstones are retained and block stale resurrection", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    await putOperation(server.baseUrl, versionedBody(8, "a".repeat(64)));
    const tombstone = await putOperation(
      server.baseUrl,
      versionedBody(9, "b".repeat(64), { tombstone: true }),
    );
    assert.equal(tombstone.status, 200);
    assert.equal((await tombstone.json()).cloudTombstone, true);

    const stale = await putOperation(server.baseUrl, versionedBody(8, "a".repeat(64)));
    assert.equal(stale.status, 409);
    assert.equal((await stale.json()).status, "STALE");

    const sameVersionActive = await putOperation(server.baseUrl, versionedBody(9, "b".repeat(64)));
    assert.equal(sameVersionActive.status, 409);
    assert.equal((await sameVersionActive.json()).status, "STALE");

    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored[0].payload.version, 9);
    assert.equal(stored[0].payload.tombstone, true);
  } finally {
    await closeServer(server.server);
  }
});

test("the frozen Qdrant operation envelope is accepted and classified", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const body = envelopeBody(4);
    const res = await putOperation(server.baseUrl, body as Record<string, unknown>);
    assert.equal(res.status, 201);
    const response = await res.json();
    assert.equal(response.status, "APPLIED");
    assert.equal(response.cloudVersion, 4);
    assert.match(response.cloudContentHash, /^[0-9a-f]{64}$/);
    const stored = await qdrant.retrieve("device_memory", [UUID_A]);
    assert.equal(stored[0].payload.recordId, UUID_A);
    assert.equal(stored[0].payload.version, 4);
  } finally {
    await closeServer(server.server);
  }
});

test("canonical envelope validation rejects identity, enum, policy, and hash errors", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const cases: Array<Record<string, unknown>> = [
      { ...envelopeBody(1), operation_id: `UPSERT:${UUID_A}:2` },
      { ...envelopeBody(1), _operation_type: "DELETE", operation_id: `DELETE:${UUID_A}:1` },
      { ...envelopeBody(1), _sync_decision: "LOCAL_ONLY" },
      { ...envelopeBody(1), _redacted: false, _sync_decision: "SYNC_REDACTED" },
      { ...envelopeBody(1), _record_id: "not-a-uuid", operation_id: "UPSERT:not-a-uuid:1" },
      { ...envelopeBody(1), _version: 0, operation_id: `UPSERT:${UUID_A}:0` },
      { ...envelopeBody(1), _state: "UNKNOWN" },
      { ...envelopeBody(1), tombstone: "yes" },
    ];
    for (const body of cases) {
      const res = await putOperation(server.baseUrl, body);
      assert.equal(res.status, 400);
    }
    assert.equal(qdrant.upsertCalls, 0);
  } finally {
    await closeServer(server.server);
  }
});

test("stored cloud state is compared by version, never by timestamp", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    await putOperation(server.baseUrl, versionedBody(5, "a".repeat(64), { updatedAt: 1 }));
    const stale = await putOperation(
      server.baseUrl,
      versionedBody(4, "b".repeat(64), { updatedAt: 9999999999999 }),
    );
    assert.equal(stale.status, 409);
    assert.equal((await stale.json()).status, "STALE");
  } finally {
    await closeServer(server.server);
  }
});
