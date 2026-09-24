import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig } from "../src/config.js";

test("loadConfig reads values from environment", () => {
  const config = loadConfig({
    PORT: "9000",
    QDRANT_URL: "https://qdrant.example:6333",
    QDRANT_API_KEY: "secret-value",
    CLOUD_LLM_MODEL: "model-x",
    CLOUD_LLM_API_KEY: "another-secret",
  } as NodeJS.ProcessEnv);

  assert.equal(config.port, 9000);
  assert.equal(config.qdrantUrl, "https://qdrant.example:6333");
  assert.equal(config.qdrantApiKey, "secret-value");
  assert.equal(config.cloudLlmModel, "model-x");
  assert.equal(config.cloudLlmApiKey, "another-secret");
});

test("loadConfig defaults missing optional values", () => {
  const config = loadConfig({} as NodeJS.ProcessEnv);
  assert.equal(config.port, 8080);
  assert.equal(config.qdrantUrl, null);
  assert.equal(config.qdrantApiKey, null);
  assert.equal(config.cloudLlmApiKey, null);
  assert.equal(config.deviceMemoryCollection, "device_memory");
  assert.equal(config.cloudKnowledgeCollection, "cloud_knowledge");
});

test("loadConfig never reports secret values in errors", () => {
  try {
    loadConfig({ PORT: "not-a-number" } as NodeJS.ProcessEnv);
    assert.fail("expected ConfigError");
  } catch (e) {
    assert.ok(e instanceof Error);
    assert.match(e.message, /PORT/);
    assert.ok(!e.message.includes("not-a-number"));
  }
});

test("blank environment values are treated as unset", () => {
  const config = loadConfig({ QDRANT_API_KEY: "   " } as NodeJS.ProcessEnv);
  assert.equal(config.qdrantApiKey, null);
});
