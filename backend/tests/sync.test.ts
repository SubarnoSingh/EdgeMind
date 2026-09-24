import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
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
  return fetch(`${baseUrl}/sync/operations/${body.operationId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
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
