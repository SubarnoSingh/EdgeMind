import { buildApp } from "./app.js";

const { app, ctx } = buildApp();

/**
 * On startup, ensure the two cloud collections exist (creates them if
 * missing) when Qdrant is configured, so the first push/pull never races
 * collection creation.
 */
async function ensureCollections(): Promise<void> {
  if (!ctx.qdrant.configured) {
    return;
  }
  await ctx.qdrant.ensureCollection(
    ctx.config.deviceMemoryCollection,
    ctx.config.embeddingDimension,
  );
  await ctx.qdrant.ensureCollection(
    ctx.config.cloudKnowledgeCollection,
    ctx.config.embeddingDimension,
  );
}

ensureCollections()
  .then(() => {
    app.listen(ctx.config.port, () => {
      // Honest, secret-free startup summary.
      console.log(
        `[edgemind-backend] listening on :${ctx.config.port} ` +
          `(qdrant: ${ctx.qdrant.configured ? "configured" : "NOT configured"}, ` +
          `llm: ${ctx.llm.configured ? "configured" : "NOT configured"}, ` +
          `embedding: ${ctx.embedding.configured ? "configured" : "NOT configured"})`,
      );
    });
  })
  .catch((e) => {
    console.error("[edgemind-backend] failed to initialize Qdrant collections:", e instanceof Error ? e.message : "unknown error");
    process.exitCode = 1;
  });
