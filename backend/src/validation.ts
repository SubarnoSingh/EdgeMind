import { ApiError, INVALID_REQUEST } from "./errors.js";
import type { BackendConfig } from "./config.js";

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const OPERATION_ID_RE = /^[A-Za-z0-9_-]{1,128}$/;

const MEMORY_TYPES = new Set([
  "DOCUMENT",
  "NOTE",
  "OBSERVATION",
  "PROCEDURE",
  "REPAIR",
  "EVENT",
  "CLOUD_KNOWLEDGE",
]);

const ORIGINS = new Set(["LOCAL", "CLOUD", "SYNCED"]);

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(
  body: Record<string, unknown>,
  field: string,
  maxLen: number,
  minLen = 0,
): string {
  const value = body[field];
  if (!isString(value)) {
    throw INVALID_REQUEST(`${field} must be a string`);
  }
  const trimmed = value.trim();
  if (trimmed.length < minLen) {
    throw INVALID_REQUEST(`${field} must not be empty`);
  }
  if (value.length > maxLen) {
    throw INVALID_REQUEST(`${field} exceeds ${maxLen} characters`);
  }
  return value;
}

function optionalString(
  body: Record<string, unknown>,
  field: string,
  maxLen: number,
): string | null {
  const value = body[field];
  if (value === undefined || value === null) return null;
  if (!isString(value)) throw INVALID_REQUEST(`${field} must be a string or null`);
  if (value.length > maxLen) throw INVALID_REQUEST(`${field} exceeds ${maxLen} characters`);
  return value;
}

function requireInt(
  body: Record<string, unknown>,
  field: string,
  min: number,
): number {
  const value = body[field];
  if (typeof value !== "number" || !Number.isFinite(value) || !Number.isInteger(value)) {
    throw INVALID_REQUEST(`${field} must be an integer`);
  }
  if (value < min) {
    throw INVALID_REQUEST(`${field} must be at least ${min}`);
  }
  return value;
}

function requireBool(body: Record<string, unknown>, field: string): boolean {
  const value = body[field];
  if (typeof value !== "boolean") {
    throw INVALID_REQUEST(`${field} must be a boolean`);
  }
  return value;
}

function requireUuid(body: Record<string, unknown>, field: string): string {
  const value = requireString(body, field, 64, 1);
  if (!UUID_RE.test(value)) {
    throw INVALID_REQUEST(`${field} must be a UUID`);
  }
  return value;
}

function stringArray(body: Record<string, unknown>, field: string, maxItems: number, maxLen: number): string[] {
  const value = body[field];
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value)) throw INVALID_REQUEST(`${field} must be an array of strings`);
  if (value.length > maxItems) throw INVALID_REQUEST(`${field} exceeds ${maxItems} items`);
  const result: string[] = [];
  for (const item of value) {
    if (!isString(item) || item.length > maxLen) {
      throw INVALID_REQUEST(`${field} items must be strings of at most ${maxLen} characters`);
    }
    result.push(item);
  }
  return result;
}

function metadataMap(body: Record<string, unknown>, field: string): Record<string, string> {
  const value = body[field];
  if (value === undefined || value === null) return {};
  if (!isObject(value)) throw INVALID_REQUEST(`${field} must be an object`);
  const entries = Object.entries(value);
  if (entries.length > 64) throw INVALID_REQUEST(`${field} exceeds 64 entries`);
  const result: Record<string, string> = {};
  for (const [key, item] of entries) {
    if (key.length > 128 || !isString(item) || item.length > 2048) {
      throw INVALID_REQUEST(`${field} entries must be short strings`);
    }
    result[key] = item;
  }
  return result;
}

/**
 * Validates a device sync push. Enforces the Phase 5/8 privacy invariants on
 * the server side: LOCAL_ONLY records and CLOUD-origin records must never be
 * pushed back, and SYNC_REDACTED payloads are accepted as redacted.
 */
export function validateSyncPush(body: unknown, cfg: BackendConfig): {
  operationId: string;
  memoryId: string;
  operationType: "UPSERT";
  title: string;
  content: string;
  memory: {
    syncDecision: "SYNC" | "SYNC_REDACTED";
    origin: "LOCAL" | "SYNCED";
    redacted: boolean;
    version: number;
    contentHash: string;
    subjectKey: string | null;
    type: string;
    tags: string[];
    chunkId: string | null;
    source: string;
    supersedes: string | null;
    tombstone: boolean;
    updatedAt: number;
    metadata: Record<string, string>;
  };
} {
  if (!isObject(body)) {
    throw INVALID_REQUEST("body must be a JSON object");
  }

  const operationId = requireString(body, "operationId", 128, 1);
  if (!OPERATION_ID_RE.test(operationId)) {
    throw INVALID_REQUEST("operationId must match [A-Za-z0-9_-]{1,128}");
  }
  const memoryId = requireUuid(body, "memoryId");
  const operationTypeRaw = requireString(body, "operationType", 32, 1);
  if (operationTypeRaw !== "UPSERT") {
    throw INVALID_REQUEST("operationType must be UPSERT");
  }
  const title = requireString(body, "title", 1000);
  const content = requireString(body, "content", 20000, 1);

  const memoryRaw = body.memory;
  if (!isObject(memoryRaw)) {
    throw INVALID_REQUEST("memory must be an object");
  }

  const syncDecision = requireString(memoryRaw, "syncDecision", 32, 1);
  if (syncDecision === "LOCAL_ONLY") {
    // Privacy invariant enforced on the server too: LOCAL_ONLY never uploads.
    throw INVALID_REQUEST("LOCAL_ONLY memories can never be uploaded");
  }
  if (syncDecision !== "SYNC" && syncDecision !== "SYNC_REDACTED") {
    throw INVALID_REQUEST("memory.syncDecision must be SYNC or SYNC_REDACTED");
  }

  const origin = requireString(memoryRaw, "origin", 32, 1);
  if (origin === "CLOUD") {
    // Cloud-origin knowledge arrived from the cloud and is never pushed back.
    throw INVALID_REQUEST("CLOUD-origin knowledge can never be pushed back");
  }
  if (origin !== "LOCAL" && origin !== "SYNCED") {
    throw INVALID_REQUEST("memory.origin must be LOCAL or SYNCED");
  }

  const redacted = requireBool(memoryRaw, "redacted");
  const version = requireInt(memoryRaw, "version", 1);
  const contentHash = requireString(memoryRaw, "contentHash", 128, 1);
  const subjectKey = optionalString(memoryRaw, "subjectKey", 256);
  const type = requireString(memoryRaw, "type", 32, 1);
  if (!MEMORY_TYPES.has(type)) {
    throw INVALID_REQUEST(`memory.type must be one of ${[...MEMORY_TYPES].join(", ")}`);
  }
  const tags = stringArray(memoryRaw, "tags", 32, 128);
  const chunkId = optionalString(memoryRaw, "chunkId", 128);
  const source = requireString(memoryRaw, "source", 256);
  const supersedes = optionalString(memoryRaw, "supersedes", 64);
  const tombstone = requireBool(memoryRaw, "tombstone");
  const updatedAt = requireInt(memoryRaw, "updatedAt", 0);
  const metadata = metadataMap(memoryRaw, "metadata");

  void cfg;
  return {
    operationId,
    memoryId,
    operationType: "UPSERT",
    title,
    content,
    memory: {
      syncDecision,
      origin,
      redacted,
      version,
      contentHash,
      subjectKey,
      type,
      tags,
      chunkId,
      source,
      supersedes,
      tombstone,
      updatedAt,
      metadata,
    },
  };
}

export interface IngestedKnowledgeItem {
  memoryId: string;
  subjectKey: string | null;
  title: string;
  content: string;
  contentHash: string;
  version: number;
  updatedAt: number;
  origin: string;
  authority: string | null;
  supersedes: string | null;
  tombstone: boolean;
  metadata: Record<string, string>;
}

/** Validates curated cloud knowledge ingest (POST /knowledge/ingest). */
export function validateKnowledgeIngest(body: unknown): IngestedKnowledgeItem[] {
  if (!Array.isArray(body)) {
    throw INVALID_REQUEST("body must be an array of knowledge items");
  }
  if (body.length === 0 || body.length > 200) {
    throw INVALID_REQUEST("body must contain 1..200 items");
  }
  return body.map((raw, index) => {
    const prefix = `items[${index}]`;
    if (!isObject(raw)) {
      throw INVALID_REQUEST(`${prefix} must be an object`);
    }
    const memoryId = requireUuid(raw, "memoryId");
    const subjectKey = optionalString(raw, "subjectKey", 256);
    const title = requireString(raw, "title", 1000);
    const content = requireString(raw, "content", 20000, 1);
    const contentHash = requireString(raw, "contentHash", 128, 1);
    const version = requireInt(raw, "version", 1);
    const updatedAt = requireInt(raw, "updatedAt", 0);
    const origin = requireString(raw, "origin", 32, 1);
    if (!ORIGINS.has(origin)) {
      throw INVALID_REQUEST(`${prefix}.origin must be one of ${[...ORIGINS].join(", ")}`);
    }
    const authority = optionalString(raw, "authority", 256);
    const supersedes = optionalString(raw, "supersedes", 64);
    const tombstone = raw.tombstone === undefined ? false : requireBool(raw, "tombstone");
    const metadata = metadataMap(raw, "metadata");
    return {
      memoryId,
      subjectKey,
      title,
      content,
      contentHash,
      version,
      updatedAt,
      origin,
      authority,
      supersedes,
      tombstone,
      metadata,
    };
  });
}

/** Validates the question sent to the cloud answer endpoint. */
export function validateQuestion(body: unknown, cfg: BackendConfig): string {
  if (!isObject(body)) {
    throw INVALID_REQUEST("body must be a JSON object");
  }
  const question = requireString(body, "question", cfg.maxQuestionChars, 1);
  return question.trim();
}

/** Used by tests to keep shared limits in one place. */
export function internalLimits(): { maxRequestBytes: number } {
  return { maxRequestBytes: 1024 * 1024 };
}
