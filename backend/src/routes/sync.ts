import { Router } from "express";
import type { AppContext } from "../context.js";
import { validateSyncPush } from "../validation.js";
import {
  ApiError,
  INVALID_REQUEST,
  QDRANT_UNAVAILABLE,
} from "../errors.js";

/**
 * PUT /sync/operations/:operationId — device knowledge push.
 *
 * Idempotent by operationId: replaying the same operation after a client
 * crash returns `accepted` without changing anything, so the Android engine's
 * crash-recovery re-application is safe. A real Qdrant upsert happens before
 * any success response — no fake acknowledgements.
 */
export function syncRouter(ctx: AppContext): Router {
  const router = Router();

  router.put("/operations/:operationId", async (req, res, next) => {
    try {
      const pathOperationId = req.params.operationId;
      const push = validateSyncPush(req.body, ctx.config);
      if (pathOperationId !== push.operationId) {
        throw INVALID_REQUEST("operationId in path and body must match");
      }

      const collection = ctx.config.deviceMemoryCollection;

      // Idempotency check first (retrieve is cheaper than a write).
      const existing = await ctx.qdrant.retrieve(collection, [push.memoryId]);
      if (existing.length > 0) {
        const appliedOp = existing[0].payload["operationId"];
        if (appliedOp === push.operationId) {
          res.status(200).json({
            accepted: true,
            operationId: push.operationId,
            memoryId: push.memoryId,
            duplicate: true,
          });
          return;
        }
      }

      await ctx.qdrant.upsert(collection, [
        {
          id: push.memoryId,
          payload: {
            operationId: push.operationId,
            memoryId: push.memoryId,
            operationType: push.operationType,
            title: push.title,
            content: push.content,
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
            updatedAt: push.memory.updatedAt,
            metadata: push.memory.metadata,
          },
        },
      ]);

      res.status(201).json({
        accepted: true,
        operationId: push.operationId,
        memoryId: push.memoryId,
        duplicate: false,
      });
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
