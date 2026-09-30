import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
import {
  FakeQdrantGateway,
  FakeCloudLlm,
  NoEmbedding,
  testConfig,
  UUID_A,
  UUID_B,
  UUID_C,
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

function ingestItem(memoryId: string, title: string, version = 1) {
  return {
    memoryId,
    subjectKey: "FT-983.3",
    title,
    content: `${title} — curated knowledge.`,
    contentHash: `hash-${memoryId}`,
    version,
    updatedAt: 1700000000000 + version,
    origin: "CLOUD",
    authority: "central-engineering",
    supersedes: null,
    tombstone: false,
    metadata: { kind: "procedure" },
  };
}

test("ingested curated knowledge can be pulled back incrementally with cursors", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const res = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([ingestItem(UUID_A, "revision 1"), ingestItem(UUID_B, "revision 2"), ingestItem(UUID_C, "revision 3")]),
    });
    assert.equal(res.status, 201);

    // Page 1
    const page1 = await (await fetch(`${server.baseUrl}/knowledge?limit=2`)).json();
    assert.equal(page1.items.length, 2);
    assert.ok(page1.nextCursor != null);
    assert.equal(page1.items[0].origin, "CLOUD");
    assert.equal(page1.items[0].authority, "central-engineering");

    // Page 2 continues after the cursor.
    const page2 = await (await fetch(`${server.baseUrl}/knowledge?limit=2&cursor=${page1.nextCursor}`)).json();
    assert.equal(page2.items.length, 1);
    assert.equal(page2.nextCursor, null);

    const all = [...page1.items, ...page2.items].map((i: { memoryId: string }) => i.memoryId);
    assert.deepEqual(new Set(all), new Set([UUID_A, UUID_B, UUID_C]));
  } finally {
    await closeServer(server.server);
  }
});

test("empty collection pull returns empty batch with null cursor", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const page = await (await fetch(`${server.baseUrl}/knowledge`)).json();
    assert.deepEqual(page, { items: [], nextCursor: null });
  } finally {
    await closeServer(server.server);
  }
});

test("ingest rejects malformed items", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const bad = ingestItem(UUID_A, "title");
    (bad as unknown as Record<string, unknown>).content = "   ";
    const res = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([bad]),
    });
    assert.equal(res.status, 400);
    const stored = await qdrant.retrieve("cloud_knowledge", [UUID_A]);
    assert.equal(stored.length, 0);
  } finally {
    await closeServer(server.server);
  }
});

test("cloud search is honest 503 without an embedding provider", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const res = await fetch(`${server.baseUrl}/knowledge/search?q=pump+procedure`);
    assert.equal(res.status, 503);
    const payload = await res.json();
    assert.equal(payload.error.code, "CONFIG_ERROR");
  } finally {
    await closeServer(server.server);
  }
});

test("curated knowledge ingest does not overwrite newer cloud state", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const current = ingestItem(UUID_A, "revision 2", 2);
    const first = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([current]),
    });
    assert.equal(first.status, 201);

    const stale = ingestItem(UUID_A, "revision 1", 1);
    const staleResponse = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([stale]),
    });
    assert.equal(staleResponse.status, 201);
    assert.deepEqual(await staleResponse.json(), {
      ingested: 0,
      duplicates: 0,
      stale: 1,
      conflicts: 0,
    });

    const conflicting = { ...current, contentHash: "different-hash" };
    const conflictResponse = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([conflicting]),
    });
    assert.deepEqual(await conflictResponse.json(), {
      ingested: 0,
      duplicates: 0,
      stale: 0,
      conflicts: 1,
    });
    const stored = await qdrant.retrieve("cloud_knowledge", [UUID_A]);
    assert.equal(stored[0].payload.version, 2);
  } finally {
    await closeServer(server.server);
  }
});
