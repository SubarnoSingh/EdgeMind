import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
import { FakeQdrantGateway, FakeCloudLlm, NoEmbedding, testConfig } from "./helpers.js";

function setup() {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm(() => ({
    answer: "ok",
    authority: "cloud-llm · test-model",
    provider: "test-model",
    requestId: null,
  }));
  const { app, ctx } = buildApp({
    config: testConfig(),
    qdrant,
    llm,
    embedding: new NoEmbedding(),
  });
  return { qdrant, llm, app, ctx };
}

test("health reports honest configuration booleans only", async () => {
  const { qdrant, llm, app } = setup();
  await qdrant.ensureCollection("device_memory", 1024);
  const { startServer, closeServer } = await import("./helpers.js");
  const server = await startServer(app);
  try {
    const res = await fetch(`${server.baseUrl}/health`);
    const body = await res.json();
    assert.equal(res.status, 200);
    assert.equal(body.status, "ok");
    assert.equal(body.qdrant, "configured");
    assert.equal(body.llm, "configured");
    assert.equal(body.embedding, "not_configured");
    // Only booleans/status strings — no configuration values.
    assert.equal(typeof body.qdrant, "string");
    assert.ok(!JSON.stringify(body).includes("test-qdrant"));
    assert.ok(!JSON.stringify(body).includes("test-llm"));
    void llm;
  } finally {
    await closeServer(server.server);
  }
});
