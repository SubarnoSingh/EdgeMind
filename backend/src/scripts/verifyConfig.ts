/**
 * Manual cloud verification helper (npm run verify:config).
 *
 * Checks configuration presence and — when Qdrant credentials are present —
 * authenticates against Qdrant Cloud and lists collections. Never prints
 * credential values.
 */
import { loadConfig } from "../config.js";
import { RealQdrantGateway } from "../qdrant/realGateway.js";

async function main(): Promise<void> {
  const config = loadConfig();

  console.log("configuration check:");
  console.log(`  QDRANT_URL:        ${config.qdrantUrl ? "set" : "MISSING"}`);
  console.log(`  QDRANT_API_KEY:    ${config.qdrantApiKey ? "set" : "MISSING"}`);
  console.log(`  CLOUD_LLM_API_KEY: ${config.cloudLlmApiKey ? "set" : "MISSING"}`);
  console.log(`  CLOUD_LLM_MODEL:   ${config.cloudLlmModel ? config.cloudLlmModel : "MISSING"}`);
  console.log(
    `  CLOUD_EMBEDDING:   ${config.cloudEmbeddingApiKey && config.cloudEmbeddingModel ? "set" : "not set (cloud search disabled)"}`,
  );

  if (!config.qdrantUrl || !config.qdrantApiKey) {
    console.log("Qdrant Cloud: NOT CHECKED — configure QDRANT_URL and QDRANT_API_KEY in backend/.env");
    return;
  }

  try {
    const gateway = new RealQdrantGateway(config.qdrantUrl, config.qdrantApiKey);
    const collections = await gateway.listCollections();
    console.log(`Qdrant Cloud: AUTHENTICATED — ${collections.length} collection(s):`);
    for (const name of collections) {
      console.log(`  - ${name}`);
    }
  } catch (e) {
    console.error("Qdrant Cloud: FAILED —", e instanceof Error ? e.message : "unknown error");
    process.exitCode = 1;
  }
}

void main();
