import { Router } from "express";
import type { AppContext } from "../context.js";
import {
  validateSyncOperationEnvelope,
  validateSyncPush,
} from "../validation.js";
import {
  classifyCloudWrite,
  cloudPayload,
  cloudRecordMutex,
  type ValidatedSyncPush,
} from "../services/syncSafety.js";
import {
  ApiError,
  INVALID_REQUEST,
  QDRANT_UNAVAILABLE,
} from "../errors.js";

function isPhase12Envelope(body: unknown): boolean {
  if (typeof body !== "object" || body === null || Array.isArray(body)) return false;
  const value = body as Record<string, unknown>;
  return "operation_id" in value || "_record_type" in value || "_operation_type" in value;
}

function currentState(point: { payload: Record<string, unknown> } | null) {
  if (point == null) return null;
  const version = point.payload.version;
  const contentHash = point.payload.contentHash;
  return {
    version: typeof version === "number" ? version : null,
    contentHash: typeof contentHash === "string" ? contentHash : null,
    tombstone: point.payload.tombstone === true,
  };
}

function responseBody(
  push: ValidatedSyncPush,
  status: "APPLIED" | "DUPLICATE" | "STALE" | "CONFLICT",
  duplicate: boolean,
  cloud: { version: number; contentHash: string; tombstone: boolean },
) {
  return {
    accepted: status === "APPLIED" || status === "DUPLICATE",
    operationId: push.operationId,
    memoryId: push.memoryId,
    duplicate,
    status,
    cloudVersion: cloud.version,
    cloudContentHash: cloud.contentHash,
    cloudTombstone: cloud.tombstone,
  };
}

/**
 * PUT /sync/operations/:operationId — device knowledge push.
 *
 * The legacy Room-sync request shape remains accepted for rollback
 * compatibility. The Phase 12 envelope is additive and is validated before
 * the same version-aware Qdrant write path.
 */
export function syncRouter(ctx: AppContext): Router {
  const router = Router();

  router.put("/operations/:operationId", async (req, res, next) => {
    try {
      const pathOperationId = req.params.operationId;
      const push = isPhase12Envelope(req.body)
        ? validateSyncOperationEnvelope(req.body, ctx.config)
        : validateSyncPush(req.body, ctx.config);
      if (pathOperationId !== push.operationId) {
        throw INVALID_REQUEST("operationId in path and body must match");
      }

      const result = await cloudRecordMutex.run(push.memoryId, async () => {
        const collection = ctx.config.deviceMemoryCollection;
        const existing = await ctx.qdrant.retrieve(collection, [push.memoryId]);
        const current = existing[0] ?? null;
        const classification = classifyCloudWrite(current, push);
        const currentVersion = currentState(current);

        if (classification === "DUPLICATE") {
          const cloud = currentVersion;
          if (cloud == null || cloud.version == null || cloud.contentHash == null) {
            throw QDRANT_UNAVAILABLE();
          }
          return {
            status: 200,
            body: responseBody(push, "DUPLICATE", true, {
              version: cloud.version,
              contentHash: cloud.contentHash,
              tombstone: cloud.tombstone,
            }),
          };
        }

        if (classification === "STALE" || classification === "CONFLICT") {
          const cloud = currentVersion;
          if (cloud == null || cloud.version == null || cloud.contentHash == null) {
            throw QDRANT_UNAVAILABLE();
          }
          return {
            status: 409,
            body: responseBody(push, classification, false, {
              version: cloud.version,
              contentHash: cloud.contentHash,
              tombstone: cloud.tombstone,
            }),
          };
        }

        await ctx.qdrant.upsert(collection, [
          {
            id: push.memoryId,
            payload: cloudPayload(push),
          },
        ]);

        return {
          status: current == null ? 201 : 200,
          body: responseBody(push, "APPLIED", false, {
            version: push.memory.version,
            contentHash: push.memory.contentHash,
            tombstone: push.memory.tombstone,
          }),
        };
      });
      res.status(result.status).json(result.body);
    } catch (e) {
      if (e instanceof ApiError) {
        next(e);
        return;
      }
      next(QDRANT_UNAVAILABLE());
    }
  });

  return router;
}
