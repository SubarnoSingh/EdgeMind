import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "../src/app.js";
import {
  FakeQdrantGateway,
  FakeCloudLlm,
  FailingCloudLlm,
  NoEmbedding,
  testConfig,
  closeServer,
  startServer,
} from "./helpers.js";

async function ask(baseUrl: string, question: string) {
  return fetch(`${baseUrl}/answers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question }),
  });
}

test("configured LLM answers with provenance", async () => {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm((question) => ({
    answer: `Answered: ${question}`,
    authority: "cloud-llm · test-model",
    provider: "test-model",
    requestId: "req-1",
  }));
  const { app } = buildApp({
    config: testConfig(),
    qdrant,
    llm,
    embedding: new NoEmbedding(),
  });
  const server = await startServer(app);
  try {
    const res = await ask(server.baseUrl, "What is the torque?");
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.answer, "Answered: What is the torque?");
    assert.equal(body.authority, "cloud-llm · test-model");
    assert.equal(body.requestId, "req-1");
    assert.equal(llm.questions.length, 1);
    assert.equal(llm.questions[0], "What is the torque?");
  } finally {
    await closeServer(server.server);
  }
});

test("blank question is rejected without touching the LLM", async () => {
  const qdrant = new FakeQdrantGateway();
  const llm = new FakeCloudLlm(() => {
    throw new Error("must not be called");
  });
  const { app } = buildApp({
    config: testConfig(),
    qdrant,
    llm,
    embedding: new NoEmbedding(),
  });
  const server = await startServer(app);
  try {
    const res = await ask(server.baseUrl, "   ");
    assert.equal(res.status, 400);
    assert.equal(llm.questions.length, 0);
  } finally {
    await closeServer(server.server);
  }
});

test("provider failures map to honest non-2xx responses", async () => {
  const cases: Array<[string, number]> = [
    ["LLM_AUTH_ERROR", 502],
    ["LLM_RATE_LIMITED", 429],
    ["LLM_TIMEOUT", 504],
    ["LLM_INVALID_RESPONSE", 502],
    ["LLM_PROVIDER_ERROR", 502],
    ["LLM_NOT_CONFIGURED", 503],
  ];
  for (const [code, status] of cases) {
    const qdrant = new FakeQdrantGateway();
    const { app } = buildApp({
      config: testConfig(),
      qdrant,
      llm: new FailingCloudLlm(code),
      embedding: new NoEmbedding(),
    });
    const server = await startServer(app);
    try {
      const res = await ask(server.baseUrl, "question");
      assert.equal(res.status, status, code);
      const body = await res.json();
      assert.equal(body.error.code, code);
      assert.ok(!body.error.message.includes("test-llm-api-key-placeholder"));
    } finally {
      await closeServer(server.server);
    }
  }
});
