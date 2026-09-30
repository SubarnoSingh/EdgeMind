import { createHash } from "node:crypto";
import type { CloudPoint } from "../qdrant/gateway.js";

/**
 * Best-effort per-process serialization for Qdrant's read/classify/upsert
 * sequence. Qdrant itself provides no compare-and-set; multi-instance
 * deployments still require an external coordination mechanism.
 */
export class KeyedMutex {
  private readonly tails = new Map<string, Promise<void>>();

  async run<T>(key: string, operation: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(key) ?? Promise.resolve();
    let release!: () => void;
    const current = new Promise<void>((resolve) => { release = resolve; });
    const tail = previous.then(() => current);
    this.tails.set(key, tail);
    await previous;
    try {
      return await operation();
    } finally {
      release();
      if (this.tails.get(key) === tail) this.tails.delete(key);
    }
  }
}

export const cloudRecordMutex = new KeyedMutex();

export type SyncWriteClassification =
  | "NEW"
  | "DUPLICATE"
  | "STALE"
  | "UPDATE"
  | "CONFLICT"
  | "TOMBSTONE"
  | "NO_OP";

export interface ValidatedSyncPush {
  operationId: string;
  memoryId: string;
  operationType: "UPSERT" | "TOMBSTONE";
  title: string;
  content: string;
  /** Domain fields carried by a Phase 12 operation envelope. */
  domainPayload: Record<string, unknown>;
  protocol: "LEGACY" | "PHASE_12";
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
    deletedAt: number | null;
    updatedAt: number;
    metadata: Record<string, string>;
  };
}

/**
 * Compare only record version/hash/tombstone state. Timestamps are deliberately
 * absent: they are display metadata, never a sync ordering primitive.
 */
export function classifyVersionedCloudState(
  current: CloudPoint | null,
  incoming: { version: number; contentHash: string; tombstone: boolean },
): SyncWriteClassification {
  if (current == null) return incoming.tombstone ? "TOMBSTONE" : "NEW";

  const payload = current.payload;
  const currentVersion = integerPayload(payload.version);
  const currentHash = typeof payload.contentHash === "string" ? payload.contentHash : null;
  const currentTombstone = payload.tombstone === true;

  // A tombstone at vN blocks an active vN or older record from resurrecting it.
  if (currentTombstone && !incoming.tombstone && incoming.version <= currentVersion) {
    return "STALE";
  }
  if (incoming.version < currentVersion) return "STALE";

  if (incoming.version === currentVersion) {
    if (currentHash === incoming.contentHash && currentTombstone === incoming.tombstone) {
      return "DUPLICATE";
    }
    return "CONFLICT";
  }

  return incoming.tombstone ? "TOMBSTONE" : "UPDATE";
}

export function classifyCloudWrite(
  current: CloudPoint | null,
  incoming: ValidatedSyncPush,
): SyncWriteClassification {
  return classifyVersionedCloudState(current, {
    version: incoming.memory.version,
    contentHash: incoming.memory.contentHash,
    tombstone: incoming.memory.tombstone,
  });
}

/** Cloud payload written for both legacy and Phase 12 pushes. */
export function cloudPayload(push: ValidatedSyncPush): Record<string, unknown> {
  return {
    operationId: push.operationId,
    memoryId: push.memoryId,
    recordId: push.memoryId,
    operationType: push.operationType,
    title: push.title,
    content: push.content,
    payload: push.domainPayload,
    syncDecision: push.memory.syncDecision,
    origin: push.memory.origin,
    redacted: push.memory.redacted,
    version: push.memory.version,
    contentHash: push.memory.contentHash,
    subjectKey: push.memory.subjectKey,
    type: push.memory.type,
    tags: push.memory.tags,
    chunkId: push.memory.chunkId,
    source: push.memory.source,
    supersedes: push.memory.supersedes,
    tombstone: push.memory.tombstone,
    deletedAt: push.memory.deletedAt,
    updatedAt: push.memory.updatedAt,
    metadata: push.memory.metadata,
  };
}

/**
 * Canonical hash compatible with core/sync/CanonicalContentHash for ordinary
 * JSON domain values. Envelope/transport keys are removed before hashing.
 */
export function canonicalDomainHash(payload: Record<string, unknown>): string {
  const canonical = canonicalizeObject(payload, true);
  return createHash("sha256").update(canonical, "utf8").digest("hex");
}

export function isContentHash(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{64}$/.test(value);
}

function integerPayload(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 1) {
    throw new Error("cloud record has invalid version");
  }
  return value;
}

function canonicalize(value: unknown, root = false): string {
  if (value === null) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("domain payload contains non-finite number");
    return Number.isInteger(value) ? String(value) : String(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalize(item)).join(",")}]`;
  }
  if (typeof value === "object") {
    return canonicalizeObject(value as Record<string, unknown>, root);
  }
  throw new Error("domain payload contains unsupported value");
}

function canonicalizeObject(
  value: Record<string, unknown>,
  root: boolean,
): string {
  const entries = Object.entries(value)
    .filter(([key]) => !root || !excludedRootKey(key))
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([key, child]) => `${JSON.stringify(key)}:${canonicalize(child)}`);
  return `{${entries.join(",")}}`;
}

function excludedRootKey(key: string): boolean {
  if (key.startsWith("_")) return true;
  const normalized = key.replace(/[^A-Za-z0-9]/g, "").toLowerCase();
  return (
    normalized === "vector" ||
    normalized === "vectors" ||
    normalized === "operationid" ||
    normalized === "operationids" ||
    normalized === "opid" ||
    normalized === "deviceid" ||
    normalized === "deviceids" ||
    normalized === "timestamp" ||
    normalized === "timestamps" ||
    normalized === "createdat" ||
    normalized === "updatedat" ||
    normalized.startsWith("lastsynced")
  );
}
