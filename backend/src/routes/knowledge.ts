import { Router } from "express";
import type { AppContext } from "../context.js";
import { validateKnowledgeIngest, validateQuestion } from "../validation.js";
import type { CloudKnowledgeItem } from "../models/models.js";
import {
  ApiError,
  CONFIG_ERROR,
  INVALID_REQUEST,
  QDRANT_UNAVAILABLE,
  UNAUTHORIZED,
} from "../errors.js";
import type { CloudPoint } from "../qdrant/gateway.js";
import {
  classifyVersionedCloudState,
  cloudRecordMutex,
} from "../services/syncSafety.js";

const PAGE_LIMIT = 50;
const MAX_SEARCH_RESULTS = 20;

function pointToItem(point: CloudPoint): CloudKnowledgeItem | null {
  const payload = point.payload;
  if (typeof payload.memoryId !== "string") return null;
  return {
    memoryId: payload.memoryId,
    subjectKey: typeof payload.subjectKey === "string" ? payload.subjectKey : null,
    title: typeof payload.title === "string" ? payload.title : "",
    content: typeof payload.content === "string" ? payload.content : "",
    contentHash: typeof payload.contentHash === "string" ? payload.contentHash : "",
    version: typeof payload.version === "number" ? payload.version : 1,
    updatedAt: typeof payload.updatedAt === "number" ? payload.updatedAt : 0,
    origin: typeof payload.origin === "string" ? payload.origin : "CLOUD",
    authority: typeof payload.authority === "string" ? payload.authority : null,
    supersedes: typeof payload.supersedes === "string" ? payload.supersedes : null,
    tombstone: payload.tombstone === true,
    metadata:
      typeof payload.metadata === "object" && payload.metadata !== null
        ? (payload.metadata as Record<string, string>)
        : {},
  };
}

export function knowledgeRouter(ctx: AppContext): Router {
  const router = Router();

  /**
   * GET /knowledge?cursor=&limit= — incremental curated-knowledge pull. The
   * cursor is the Qdrant next_page_offset (opaque to Android); null starts a
   * fresh pass. Mirrors CloudKnowledgeBatch semantics 1:1.
   */
  router.get("/", async (req, res, next) => {
    try {
      const cursorRaw = req.query.cursor;
      const cursor = typeof cursorRaw === "string" && cursorRaw.length > 0 ? cursorRaw : null;
      const limitRaw = req.query.limit;
      const limit =
        typeof limitRaw === "string" && /^\d{1,3}$/.test(limitRaw)
          ? Math.min(Number.parseInt(limitRaw, 10), PAGE_LIMIT)
          : PAGE_LIMIT;

      const page = await ctx.qdrant.scroll(
        ctx.config.cloudKnowledgeCollection,
        cursor,
        limit,
      );
      const items: CloudKnowledgeItem[] = [];
      for (const point of page.points) {
        const item = pointToItem(point);
        if (item !== null) items.push(item);
      }
      res.json({ items, nextCursor: page.nextOffset });
    } catch (e) {
      if (e instanceof ApiError) {
        next(e);
        return;
      }
      next(QDRANT_UNAVAILABLE());
    }
  });

  /**
   * POST /knowledge/ingest — curation endpoint for seeded/curated cloud
   * knowledge (demo data, admin tooling). Guarded by ADMIN_API_KEY when
   * configured; deployment guidance says never run unguarded in production.
   */
  router.post("/ingest", async (req, res, next) => {
    try {
      if (ctx.config.adminApiKey != null) {
        const header = req.headers.authorization;
        const token = header?.startsWith("Bearer ") ? header.slice("Bearer ".length) : null;
        if (token !== ctx.config.adminApiKey) {
          throw UNAUTHORIZED();
        }
      }

      const items = validateKnowledgeIngest(req.body);
      const points: CloudPoint[] = [];
      let duplicates = 0;
      let stale = 0;
      let conflicts = 0;
      for (const item of items) {
        await cloudRecordMutex.run(item.memoryId, async () => {
          const existing = (await ctx.qdrant.retrieve(ctx.config.cloudKnowledgeCollection, [item.memoryId]))[0] ?? null;
          const classification = classifyVersionedCloudState(existing, {
            version: item.version,
            contentHash: item.contentHash,
            tombstone: item.tombstone,
          });
          if (classification === "DUPLICATE") {
            duplicates++;
            return;
          }
          if (classification === "STALE") {
            stale++;
            return;
          }
          if (classification === "CONFLICT") {
            conflicts++;
            return;
          }

          const vector = ctx.embedding.configured
            ? await ctx.embedding.embed(`${item.title}\n${item.content}`)
            : undefined;
          const point: CloudPoint = {
            id: item.memoryId,
            payload: {
              memoryId: item.memoryId,
              subjectKey: item.subjectKey,
              title: item.title,
              content: item.content,
              contentHash: item.contentHash,
              version: item.version,
              updatedAt: item.updatedAt,
              origin: item.origin,
              authority: item.authority,
              supersedes: item.supersedes,
              tombstone: item.tombstone,
              metadata: item.metadata,
            },
            vector,
          };
          await ctx.qdrant.upsert(ctx.config.cloudKnowledgeCollection, [point]);
          points.push(point);
        });
      }
      res.status(201).json({
        ingested: points.length,
        duplicates,
        stale,
        conflicts,
      });
    } catch (e) {
      if (e instanceof ApiError) {
        next(e);
        return;
      }
      next(QDRANT_UNAVAILABLE());
    }
  });

  /**
   * GET /knowledge/search?q=&limit= — cloud-side semantic search. Requires the
   * embeddings provider; without it the endpoint honestly reports
   * EMBEDDING_NOT_CONFIGURED instead of returning fake results.
   */
  router.get("/search", async (req, res, next) => {
    try {
      if (!ctx.embedding.configured) {
        throw CONFIG_ERROR("cloud embedding provider is not configured");
      }
      const question = validateQuestion(req.query as unknown as Record<string, unknown>, ctx.config);
      const vector = await ctx.embedding.embed(question);
      const hits = await ctx.qdrant.search(
        ctx.config.cloudKnowledgeCollection,
        vector,
        MAX_SEARCH_RESULTS,
      );
      const items: CloudKnowledgeItem[] = [];
      for (const point of hits) {
        const item = pointToItem(point);
        if (item !== null) items.push(item);
      }
      res.json({ items });
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
