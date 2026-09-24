import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
import {
  FakeQdrantGateway,
  FakeCloudLlm,
  NoEmbedding,
  testConfig,
  closeServer,
  startServer,
} from "./helpers.js";

/**
 * Security regression tests: no secret leakage, request-size limits,
 * deterministic error bodies.
 */
function setup() {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm(() => ({
    answer: "ok",
    authority: "cloud-llm · test-model",
    provider: "test-model",
    requestId: null,
  }));
  const config = testConfig({
    // Deliberately secret-looking values used only to prove they never leak.
    qdrantApiKey: "super-secret-qdrant-key-123",
    cloudLlmApiKey: "super-secret-llm-key-456",
    adminApiKey: "super-secret-admin-key-789",
    maxRequestBytes: 2048,
  });
  const { app } = buildApp({ config, qdrant, llm, embedding: new NoEmbedding() });
  return { qdrant, app };
}

const SECRETS = ["super-secret-qdrant-key-123", "super-secret-llm-key-456", "super-secret-admin-key-789"];

test("no endpoint ever returns credential values", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const probes: Array<() => Promise<string>> = [
      async () => (await (await fetch(`${server.baseUrl}/health`)).json()) && (await (await fetch(`${server.baseUrl}/health`)).text()),
      async () => (await fetch(`${server.baseUrl}/answers`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ question: "q" }) })).text(),
      async () => (await fetch(`${server.baseUrl}/knowledge/search?q=x`)).text(),
      async () => (await fetch(`${server.baseUrl}/nonexistent`)).text(),
      async () => (await fetch(`${server.baseUrl}/knowledge/ingest`, { method: "POST", headers: { "Content-Type": "application/json" }, body: "{broken" })).text(),
    ];
    for (const probe of probes) {
      const text = await probe();
      for (const secret of SECRETS) {
        assert.ok(!text.includes(secret), "secret leaked in response body");
      }
    }
  } finally {
    await closeServer(server.server);
  }
});

test("oversized request bodies are rejected with 413", async () => {
  const { qdrant, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const server = await startServer(app);
  try {
    const res = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ padding: "x".repeat(10000) }),
    });
    assert.equal(res.status, 413);
    const payload = await res.json();
    assert.equal(payload.error.code, "PAYLOAD_TOO_LARGE");
  } finally {
    await closeServer(server.server);
  }
});

test("admin-guarded ingest rejects missing/wrong credentials", async () => {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm(() => ({
    answer: "ok",
    authority: "cloud-llm · test-model",
    provider: "test-model",
    requestId: null,
  }));
  const { app } = buildApp({
    config: testConfig({ adminApiKey: "super-secret-admin-key-789" }),
    qdrant,
    llm,
    embedding: new NoEmbedding(),
  });
  await qdrant.ensureCollection("cloud_knowledge", 1024);
  const server = await startServer(app);
  try {
    const res = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify([]),
    });
    assert.equal(res.status, 401);
    const ok = await fetch(`${server.baseUrl}/knowledge/ingest`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer super-secret-admin-key-789",
      },
      body: JSON.stringify([
        {
          memoryId: "44444444-4444-4444-8444-444444444444",
          title: "t",
          content: "c",
          contentHash: "h",
          version: 1,
          updatedAt: 1,
          origin: "CLOUD",
        },
      ]),
    });
    assert.equal(ok.status, 201);
  } finally {
    await closeServer(server.server);
  }
});
