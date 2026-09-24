import type { BackendConfig } from "./config.js";
import type { QdrantGateway } from "./qdrant/gateway.js";
import type { CloudLlmService } from "./services/cloudLlm.js";
import type { CloudEmbeddingService } from "./services/cloudEmbedding.js";

/** Shared wiring for all route handlers (assembled in app.ts). */
export interface AppContext {
  config: BackendConfig;
  qdrant: QdrantGateway;
  llm: CloudLlmService;
  embedding: CloudEmbeddingService;
}
