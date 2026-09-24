import { Router } from "express";
import type { AppContext } from "../context.js";

/**
 * GET /health — honest configuration status. Only booleans leave the server:
 * no secret values, no credential presence beyond configured/not-configured.
 */
export function healthRouter(ctx: AppContext): Router {
  const router = Router();

  router.get("/", (_req, res) => {
    res.json({
      status: "ok",
      service: "edgemind-backend",
      qdrant: ctx.qdrant.configured ? "configured" : "not_configured",
      llm: ctx.llm.configured ? "configured" : "not_configured",
      embedding: ctx.embedding.configured ? "configured" : "not_configured",
    });
  });

  return router;
}
