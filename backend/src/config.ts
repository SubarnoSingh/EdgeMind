import dotenv from "dotenv";

dotenv.config();

/**
 * Backend configuration, loaded exclusively from environment variables.
 *
 * Secrets (Qdrant API key, LLM API key, admin key) are NEVER logged, never
 * embedded in error messages, and never returned to clients. The Android app
 * only ever sees this backend over HTTPS (see network_security_config in the
 * app) and receives none of these values.
 */
export interface BackendConfig {
  port: number;
  qdrantUrl: string | null;
  qdrantApiKey: string | null;
  deviceMemoryCollection: string;
  cloudKnowledgeCollection: string;
  embeddingDimension: number;
  cloudLlmApiKey: string | null;
  cloudLlmBaseUrl: string;
  cloudLlmModel: string | null;
  cloudLlmTimeoutMs: number;
  cloudEmbeddingApiKey: string | null;
  cloudEmbeddingBaseUrl: string;
  cloudEmbeddingModel: string | null;
  adminApiKey: string | null;
  maxRequestBytes: number;
  maxQuestionChars: number;
  maxAnswerChars: number;
}

export class ConfigError extends Error {
  constructor(message: string) {
    // Messages only ever name the missing variable — never its value.
    super(message);
    this.name = "ConfigError";
  }
}

function read(env: NodeJS.ProcessEnv, key: string): string | null {
  const value = env[key];
  return value == null || value.trim() === "" ? null : value.trim();
}

function readInt(
  env: NodeJS.ProcessEnv,
  key: string,
  fallback: number,
  min: number,
): number {
  const raw = read(env, key);
  if (raw == null) return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value)) {
    throw new ConfigError(`${key} must be an integer`);
  }
  if (value < min) {
    throw new ConfigError(`${key} must be at least ${min}`);
  }
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BackendConfig {
  return {
    port: readInt(env, "PORT", 8080, 1),
    qdrantUrl: read(env, "QDRANT_URL"),
    qdrantApiKey: read(env, "QDRANT_API_KEY"),
    deviceMemoryCollection:
      read(env, "QDRANT_DEVICE_MEMORY_COLLECTION") ?? "device_memory",
    cloudKnowledgeCollection:
      read(env, "QDRANT_CLOUD_KNOWLEDGE_COLLECTION") ?? "cloud_knowledge",
    embeddingDimension: readInt(env, "EMBEDDING_DIMENSION", 1024, 1),
    cloudLlmApiKey: read(env, "CLOUD_LLM_API_KEY"),
    cloudLlmBaseUrl: read(env, "CLOUD_LLM_BASE_URL") ?? "https://api.openai.com/v1",
    cloudLlmModel: read(env, "CLOUD_LLM_MODEL"),
    cloudLlmTimeoutMs: readInt(env, "CLOUD_LLM_TIMEOUT_MS", 20000, 1000),
    cloudEmbeddingApiKey: read(env, "CLOUD_EMBEDDING_API_KEY"),
    cloudEmbeddingBaseUrl:
      read(env, "CLOUD_EMBEDDING_BASE_URL") ?? "https://api.openai.com/v1",
    cloudEmbeddingModel: read(env, "CLOUD_EMBEDDING_MODEL"),
    adminApiKey: read(env, "ADMIN_API_KEY"),
    maxRequestBytes: readInt(env, "MAX_REQUEST_BYTES", 1024 * 1024, 1024),
    maxQuestionChars: readInt(env, "MAX_QUESTION_CHARS", 2000, 1),
    maxAnswerChars: readInt(env, "MAX_ANSWER_CHARS", 8000, 1),
  };
}
