import express from "express";
import type { Express, NextFunction, Request, Response } from "express";
import { loadConfig, type BackendConfig } from "./config.js";
import { ApiError, toApiError } from "./errors.js";
import { RealQdrantGateway, UnconfiguredQdrantGateway } from "./qdrant/realGateway.js";
import type { QdrantGateway } from "./qdrant/gateway.js";
import {
  OpenAiCompatibleCloudEmbedding,
  type CloudEmbeddingService,
} from "./services/cloudEmbedding.js";
import {
  OpenAiCompatibleCloudLlm,
  UnconfiguredCloudLlm,
  type CloudLlmService,
} from "./services/cloudLlm.js";
import type { AppContext } from "./context.js";
import { healthRouter } from "./routes/health.js";
import { syncRouter } from "./routes/sync.js";
import { knowledgeRouter } from "./routes/knowledge.js";
import { answersRouter } from "./routes/answers.js";

export interface BuiltApp {
  app: Express;
  ctx: AppContext;
}

export interface BuildOverrides {
  config?: BackendConfig;
  qdrant?: QdrantGateway;
  llm?: CloudLlmService;
  embedding?: CloudEmbeddingService;
}

/**
 * Assembles the backend from environment configuration. Never logs request
 * bodies, never returns secret values, and maps every failure to a
 * deterministic JSON error `{ error: { code, message } }`.
 *
 * Overrides exist for deterministic tests (fake gateway/LLM); production uses
 * the real environment-driven components.
 */
export function buildApp(overrides: BuildOverrides = {}): BuiltApp {
  const config = overrides.config ?? loadConfig();

  const qdrant =
    overrides.qdrant ??
    (config.qdrantUrl != null && config.qdrantApiKey != null
      ? new RealQdrantGateway(config.qdrantUrl, config.qdrantApiKey)
      : new UnconfiguredQdrantGateway());

  const llm =
    overrides.llm ??
    (config.cloudLlmApiKey != null && config.cloudLlmModel != null
      ? new OpenAiCompatibleCloudLlm({
          apiKey: config.cloudLlmApiKey,
          baseUrl: config.cloudLlmBaseUrl,
          model: config.cloudLlmModel,
          timeoutMs: config.cloudLlmTimeoutMs,
          maxAnswerChars: config.maxAnswerChars,
        })
      : new UnconfiguredCloudLlm());

  const embedding =
    overrides.embedding ??
    new OpenAiCompatibleCloudEmbedding(
      config.cloudEmbeddingApiKey,
      config.cloudEmbeddingBaseUrl,
      config.cloudEmbeddingModel,
    );

  const ctx: AppContext = { config, qdrant, llm, embedding };

  const app = express();
  app.disable("x-powered-by");
  app.use(express.json({ limit: config.maxRequestBytes }));

  app.use("/health", healthRouter(ctx));
  app.use("/sync", syncRouter(ctx));
  app.use("/knowledge", knowledgeRouter(ctx));
  app.use("/answers", answersRouter(ctx));

  app.use((_req, res) => {
    res.status(404).json({ error: { code: "NOT_FOUND", message: "not found" } });
  });

  app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
    // Express body-parser and query errors are surfaced deterministically.
    const apiError =
      err instanceof ApiError
        ? err
        : err instanceof SyntaxError && "status" in err && (err as { status?: number }).status === 400
          ? new ApiError(400, "INVALID_REQUEST", "malformed JSON body")
          : err && typeof err === "object" && "type" in err && (err as { type?: string }).type === "entity.too.large"
            ? new ApiError(413, "PAYLOAD_TOO_LARGE", "request body exceeds the size limit")
            : toApiError(err);
    res.status(apiError.status).json({
      error: { code: apiError.code, message: apiError.message },
    });
  });

  return { app, ctx };
}
