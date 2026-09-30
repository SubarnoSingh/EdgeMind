import { ApiError, INVALID_REQUEST } from "./errors.js";
import type { BackendConfig } from "./config.js";
import {
  canonicalDomainHash,
  isContentHash,
  type ValidatedSyncPush,
} from "./services/syncSafety.js";

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const OPERATION_ID_RE = /^[A-Za-z0-9_:-]{1,128}$/;
const LEGACY_OPERATION_ID_RE = /^UPSERT-[0-9a-fA-F-]{36}$/;
const VERSIONED_OPERATION_ID_RE = /^(UPSERT|TOMBSTONE):[0-9a-fA-F-]{36}:[1-9][0-9]*$/;

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

function optionalInt(
  body: Record<string, unknown>,
  field: string,
  min: number,
): number | null {
  const value = body[field];
  if (value === undefined || value === null) return null;
  return requireInt(body, field, min);
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
export function validateSyncPush(body: unknown, cfg: BackendConfig): ValidatedSyncPush {
  if (!isObject(body)) {
    throw INVALID_REQUEST("body must be a JSON object");
  }

  const operationId = requireString(body, "operationId", 128, 1);
  if (!OPERATION_ID_RE.test(operationId)) {
    throw INVALID_REQUEST("operationId has an invalid format");
  }
  const memoryId = requireUuid(body, "memoryId");
  const operationTypeRaw = requireString(body, "operationType", 32, 1);
  if (operationTypeRaw !== "UPSERT" && operationTypeRaw !== "TOMBSTONE") {
    throw INVALID_REQUEST("operationType must be UPSERT or TOMBSTONE");
  }
  const operationType = operationTypeRaw as "UPSERT" | "TOMBSTONE";
  const legacyOperation = LEGACY_OPERATION_ID_RE.test(operationId);
  const versionedOperation = VERSIONED_OPERATION_ID_RE.test(operationId);
  if (!legacyOperation && !versionedOperation) {
    throw INVALID_REQUEST("operationId must be legacy UPSERT-<uuid> or TYPE:<uuid>:<version>");
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
  if (legacyOperation && !MEMORY_TYPES.has(type)) {
    throw INVALID_REQUEST(`memory.type must be one of ${[...MEMORY_TYPES].join(", ")}`);
  }
  if (!legacyOperation && type.trim().length === 0) {
    throw INVALID_REQUEST("memory.type must not be empty");
  }
  if (versionedOperation && !isContentHash(contentHash)) {
    throw INVALID_REQUEST("memory.contentHash must be 64 lowercase hexadecimal characters");
  }
  const tags = stringArray(memoryRaw, "tags", 32, 128);
  const chunkId = optionalString(memoryRaw, "chunkId", 128);
  const source = requireString(memoryRaw, "source", 256);
  const supersedes = optionalString(memoryRaw, "supersedes", 64);
  const tombstone = requireBool(memoryRaw, "tombstone");
  const deletedAtRaw = memoryRaw.deletedAt;
  const deletedAt =
    deletedAtRaw === undefined || deletedAtRaw === null
      ? null
      : requireInt(memoryRaw, "deletedAt", 0);
  const updatedAt = requireInt(memoryRaw, "updatedAt", 0);
  const metadata = metadataMap(memoryRaw, "metadata");
  const domainPayload = {
    title,
    content,
    subjectKey,
    type,
    tags,
    chunkId,
    source,
    supersedes,
    tombstone,
    deletedAt,
    metadata,
  };

  if (versionedOperation) {
    const expected = `${operationType}:${memoryId.toLowerCase()}:${version}`;
    if (operationId !== expected) {
      throw INVALID_REQUEST("operationId does not match operation type, record id, and version");
    }
    if (canonicalDomainHash(domainPayload) !== contentHash) {
      throw INVALID_REQUEST("memory.contentHash does not match the canonical domain payload");
    }
    if ((operationType === "TOMBSTONE") !== tombstone) {
      throw INVALID_REQUEST("TOMBSTONE operations must carry tombstone=true and UPSERT operations must be active");
    }
  } else if (operationType !== "UPSERT") {
    throw INVALID_REQUEST("legacy operation IDs support only UPSERT");
  }

  void cfg;
  return {
    operationId,
    memoryId,
    operationType,
    title,
    content,
    domainPayload,
    protocol: versionedOperation ? "PHASE_12" : "LEGACY",
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
      deletedAt,
      updatedAt,
      metadata,
    },
  };
}

const OPERATION_STATES = new Set(["PENDING", "IN_FLIGHT", "ACKED", "FAILED", "DEAD"]);
const FAILURE_KINDS = new Set([
  "SOURCE_UNAVAILABLE",
  "NETWORK",
  "SERVER_TEMPORARY",
  "REJECTED",
  "UNAUTHORIZED",
]);

function payloadString(
  payload: Record<string, unknown>,
  field: string,
  maxLen: number,
  defaultValue: string,
): string {
  const value = payload[field];
  if (value === undefined || value === null) return defaultValue;
  if (!isString(value) || value.length > maxLen) {
    throw INVALID_REQUEST(`payload.${field} must be a string`);
  }
  return value;
}

function payloadNullableString(
  payload: Record<string, unknown>,
  field: string,
  maxLen: number,
): string | null {
  const value = payload[field];
  if (value === undefined || value === null) return null;
  if (!isString(value) || value.length > maxLen) {
    throw INVALID_REQUEST(`payload.${field} must be a string or null`);
  }
  return value;
}

function payloadStringArray(payload: Record<string, unknown>, field: string): string[] {
  const value = payload[field];
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || value.some((item) => !isString(item) || item.length > 128)) {
    throw INVALID_REQUEST(`payload.${field} must be an array of short strings`);
  }
  return value as string[];
}

/**
 * Validate the Qdrant-native §20.2 envelope. It is intentionally additive to
 * validateSyncPush(), which preserves the legacy Room-sync request shape.
 */
export function validateSyncOperationEnvelope(
  body: unknown,
  cfg: BackendConfig,
): ValidatedSyncPush {
  if (!isObject(body)) throw INVALID_REQUEST("body must be a JSON object");
  const recordType = requireString(body, "_record_type", 32, 1);
  if (recordType !== "outbox_op") throw INVALID_REQUEST("_record_type must be outbox_op");

  const operationId = requireString(body, "operation_id", 128, 1);
  if (!VERSIONED_OPERATION_ID_RE.test(operationId)) {
    throw INVALID_REQUEST("operation_id must be TYPE:<uuid>:<version>");
  }
  const recordId = requireUuid(body, "_record_id").toLowerCase();
  const operationTypeRaw = requireString(body, "_operation_type", 32, 1);
  if (operationTypeRaw !== "UPSERT" && operationTypeRaw !== "TOMBSTONE") {
    throw INVALID_REQUEST("_operation_type must be UPSERT or TOMBSTONE");
  }
  const operationType = operationTypeRaw as "UPSERT" | "TOMBSTONE";
  const version = requireInt(body, "_version", 1);
  const expectedOperationId = `${operationType}:${recordId}:${version}`;
  if (operationId !== expectedOperationId) {
    throw INVALID_REQUEST("operation_id does not match operation type, record id, and version");
  }

  const state = requireString(body, "_state", 32, 1);
  if (!OPERATION_STATES.has(state)) throw INVALID_REQUEST("_state is not a valid operation state");
  const attempts = requireInt(body, "_attempts", 0);
  void attempts;
  const lastError = optionalString(body, "_last_error", 64);
  if (lastError !== null && !FAILURE_KINDS.has(lastError)) {
    throw INVALID_REQUEST("_last_error is not a valid SyncFailureKind");
  }
  requireInt(body, "_created_at", 0);
  requireInt(body, "_updated_at", 0);
  optionalInt(body, "_lease_until", 0);

  const syncDecision = requireString(body, "_sync_decision", 32, 1);
  if (syncDecision !== "SYNC" && syncDecision !== "SYNC_REDACTED") {
    throw INVALID_REQUEST("_sync_decision must be SYNC or SYNC_REDACTED");
  }
  const redacted = requireBool(body, "_redacted");
  if (syncDecision === "SYNC_REDACTED" && !redacted) {
    throw INVALID_REQUEST("SYNC_REDACTED operations must be marked redacted");
  }

  const envelopeFields = new Set([
    "_record_type", "operation_id", "_record_id", "_operation_type", "_state",
    "_attempts", "_last_error", "_created_at", "_updated_at", "_lease_until",
    "_version", "_sync_decision", "_redacted",
  ]);
  const payload: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (envelopeFields.has(key)) continue;
    if (key.startsWith("_")) throw INVALID_REQUEST(`unknown envelope field ${key}`);
    payload[key] = value;
  }

  const title = payloadString(payload, "title", 1000, "");
  const content = payloadString(payload, "content", 20000, "");
  const tombstoneRaw = payload.tombstone;
  if (tombstoneRaw !== undefined && typeof tombstoneRaw !== "boolean") {
    throw INVALID_REQUEST("payload.tombstone must be boolean");
  }
  const tombstone = tombstoneRaw === true;
  if ((operationType === "TOMBSTONE") !== tombstone) {
    throw INVALID_REQUEST("TOMBSTONE operations must carry payload.tombstone=true");
  }
  const deletedAtRaw = payload.deletedAt ?? payload.deleted_at;
  if (deletedAtRaw !== undefined && deletedAtRaw !== null) {
    if (typeof deletedAtRaw !== "number" || !Number.isSafeInteger(deletedAtRaw) || deletedAtRaw < 0) {
      throw INVALID_REQUEST("payload.deletedAt must be a non-negative integer");
    }
  }

  const subjectKey = payloadNullableString(payload, "subjectKey", 256);
  const type = payloadString(payload, "type", 64, "MEMORY");
  const tags = payloadStringArray(payload, "tags");
  const chunkId = payloadNullableString(payload, "chunkId", 128);
  const source = payloadString(payload, "source", 256, "USER_ENTRY");
  const supersedes = payloadNullableString(payload, "supersedes", 64);
  const metadata = metadataMap(payload, "metadata");
  const originRaw = payloadString(payload, "origin", 32, "LOCAL");
  if (originRaw !== "LOCAL" && originRaw !== "SYNCED") {
    throw INVALID_REQUEST("payload.origin must be LOCAL or SYNCED");
  }
  const updatedAtRaw = payload.updatedAt;
  const updatedAt =
    updatedAtRaw === undefined
      ? requireInt(body, "_updated_at", 0)
      : typeof updatedAtRaw === "number" && Number.isSafeInteger(updatedAtRaw) && updatedAtRaw >= 0
        ? updatedAtRaw
        : (() => { throw INVALID_REQUEST("payload.updatedAt must be a non-negative integer"); })();

  const contentHash = canonicalDomainHash(payload);
  if (!isContentHash(contentHash)) {
    throw INVALID_REQUEST("canonical content hash could not be produced");
  }

  void cfg;
  return {
    operationId,
    memoryId: recordId,
    operationType,
    title,
    content,
    domainPayload: payload,
    protocol: "PHASE_12",
    memory: {
      syncDecision,
      origin: originRaw,
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
      deletedAt: typeof deletedAtRaw === "number" ? deletedAtRaw : null,
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
