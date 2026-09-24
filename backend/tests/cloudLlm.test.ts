import assert from "node:assert/strict";
import test from "node:test";
import { createServer, type Server, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import {
  OpenAiCompatibleCloudLlm,
  LlmError,
} from "../src/services/cloudLlm.js";

/** Scripted OpenAI-compatible endpoint for provider-level validation tests. */
function startProvider(
  handler: (req: IncomingMessage, res: ServerResponse, body: string) => void,
): Promise<{ baseUrl: string; server: Server }> {
  return new Promise((resolve, reject) => {
    const server = createServer((req, res) => {
      let raw = "";
      req.on("data", (chunk) => {
        raw += chunk;
      });
      req.on("end", () => handler(req, res, raw));
    });
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address() as AddressInfo;
      resolve({ baseUrl: `http://127.0.0.1:${address.port}`, server });
    });
  });
}

function close(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((err) => (err ? reject(err) : resolve()));
  });
}

function json(res: ServerResponse, status: number, body: unknown): void {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

const CFG = {
  apiKey: "provider-test-key",
  baseUrl: "",
  model: "provider-test-model",
  timeoutMs: 3000,
  maxAnswerChars: 8000,
};

test("provider parses a valid answer with provenance", async () => {
  const provider = await startProvider((req, res, body) => {
    assert.ok(req.headers.authorization === "Bearer provider-test-key");
    assert.ok(body.includes("provider-test-model"));
    json(res, 200, {
      id: "chatcmpl-1",
      choices: [{ message: { content: "The torque is 45 Nm." } }],
    });
  });
  const llm = new OpenAiCompatibleCloudLlm({ ...CFG, baseUrl: provider.baseUrl });
  try {
    const result = await llm.answer("What is the torque?");
    assert.equal(result.answer, "The torque is 45 Nm.");
    assert.equal(result.authority, "cloud-llm · provider-test-model");
    assert.equal(result.requestId, "chatcmpl-1");
  } finally {
    await close(provider.server);
  }
});

test("provider rejects an empty answer", async () => {
  const provider = await startProvider((_req, res) => {
    json(res, 200, { choices: [{ message: { content: "   " } }] });
  });
  const llm = new OpenAiCompatibleCloudLlm({ ...CFG, baseUrl: provider.baseUrl });
  try {
    await assert.rejects(
      () => llm.answer("q"),
      (e: unknown) => e instanceof LlmError && e.code === "LLM_INVALID_RESPONSE",
    );
  } finally {
    await close(provider.server);
  }
});

test("provider rejects an oversized answer", async () => {
  const provider = await startProvider((_req, res) => {
    json(res, 200, { choices: [{ message: { content: "x".repeat(8001) } }] });
  });
  const llm = new OpenAiCompatibleCloudLlm({ ...CFG, baseUrl: provider.baseUrl });
  try {
    await assert.rejects(
      () => llm.answer("q"),
      (e: unknown) => e instanceof LlmError && e.code === "LLM_INVALID_RESPONSE",
    );
  } finally {
    await close(provider.server);
  }
});

test("provider rejects malformed provider responses", async () => {
  const provider = await startProvider((_req, res) => {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("{not-json");
  });
  const llm = new OpenAiCompatibleCloudLlm({ ...CFG, baseUrl: provider.baseUrl });
  try {
    await assert.rejects(
      () => llm.answer("q"),
      (e: unknown) => e instanceof LlmError && e.code === "LLM_INVALID_RESPONSE",
    );
  } finally {
    await close(provider.server);
  }
});

test("provider maps 401/429/5xx to typed failures", async () => {
  const cases: Array<[number, string]> = [
    [401, "LLM_AUTH_ERROR"],
    [403, "LLM_AUTH_ERROR"],
    [429, "LLM_RATE_LIMITED"],
    [500, "LLM_PROVIDER_ERROR"],
    [503, "LLM_PROVIDER_ERROR"],
  ];
  for (const [status, code] of cases) {
    const provider = await startProvider((_req, res) => {
      json(res, status, { error: { message: "failure" } });
    });
    const llm = new OpenAiCompatibleCloudLlm({ ...CFG, baseUrl: provider.baseUrl });
    try {
      await assert.rejects(
        () => llm.answer("q"),
        (e: unknown) => e instanceof LlmError && e.code === code,
      );
    } finally {
      await close(provider.server);
    }
  }
});

test("provider reports timeout instead of hanging", async () => {
  const provider = await startProvider((_req, res) => {
    // Never respond; the AbortSignal must fire.
    res.writeHead(200, { "Content-Type": "application/json" });
    res.write('{"choices":[');
    // keep the connection open beyond the timeout
  });
  const llm = new OpenAiCompatibleCloudLlm({
    ...CFG,
    baseUrl: provider.baseUrl,
    timeoutMs: 100,
  });
  try {
    await assert.rejects(
      () => llm.answer("q"),
      (e: unknown) => e instanceof LlmError && e.code === "LLM_TIMEOUT",
    );
  } finally {
    await close(provider.server);
  }
});

test("unconfigured provider is honest and never fabricates an answer", async () => {
  const llm = new OpenAiCompatibleCloudLlm({
    apiKey: null,
    baseUrl: "https://example.invalid/v1",
    model: null,
    timeoutMs: 1000,
    maxAnswerChars: 8000,
  });
  assert.equal(llm.configured, false);
  await assert.rejects(
    () => llm.answer("q"),
    (e: unknown) => e instanceof LlmError && e.code === "LLM_NOT_CONFIGURED",
  );
});
