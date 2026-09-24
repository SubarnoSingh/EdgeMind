import type { AddressInfo } from "node:net";
import { createServer, type Server } from "node:http";
import type { Express } from "express";
import type {
  CloudPoint,
  QdrantGateway,
  ScrollPage,
} from "../src/qdrant/gateway.js";
import type { CloudLlmResult, CloudLlmService, LlmErrorCode } from "../src/services/cloudLlm.js";
import type { CloudEmbeddingService } from "../src/services/cloudEmbedding.js";
import { LlmError } from "../src/services/cloudLlm.js";
import type { BackendConfig } from "../src/config.js";

/** Deterministic in-memory Qdrant fake for route tests. */
export class FakeQdrantGateway implements QdrantGateway {
  readonly configured = true;

  readonly collections = new Map<string, Map<string, CloudPoint>>();

  readonly ensureCalls: string[] = [];

  async listCollections(): Promise<string[]> {
    return [...this.collections.keys()];
  }

  async ensureCollection(name: string, _dimension: number): Promise<void> {
    this.ensureCalls.push(name);
    if (!this.collections.has(name)) {
      this.collections.set(name, new Map());
    }
  }

  private require(name: string): Map<string, CloudPoint> {
    const collection = this.collections.get(name);
    if (collection == null) {
      throw new Error(`collection ${name} does not exist`);
    }
    return collection;
  }

  async upsert(collection: string, points: CloudPoint[]): Promise<void> {
    const target = this.require(collection);
    for (const point of points) {
      target.set(point.id, point);
    }
  }

  async retrieve(collection: string, ids: string[]): Promise<CloudPoint[]> {
    const target = this.require(collection);
    return ids.filter((id) => target.has(id)).map((id) => target.get(id)!);
  }

  async scroll(
    collection: string,
    offset: string | null,
    limit: number,
  ): Promise<ScrollPage> {
    const target = this.require(collection);
    const ids = [...target.keys()].sort();
    let start = 0;
    if (offset != null) {
      const index = ids.indexOf(offset);
      start = index >= 0 ? index + 1 : ids.length;
    }
    const pageIds = ids.slice(start, start + limit);
    const points = pageIds.map((id) => target.get(id)!);
    // Qdrant semantics: next_page_offset is the LAST id of the page and is
    // null once the final page is reached.
    const hasMore = start + limit < ids.length;
    const nextOffset = hasMore && pageIds.length > 0 ? pageIds[pageIds.length - 1] : null;
    return { points, nextOffset };
  }

  async deletePoints(collection: string, ids: string[]): Promise<void> {
    const target = this.require(collection);
    for (const id of ids) {
      target.delete(id);
    }
  }

  async search(collection: string, _vector: number[], limit: number): Promise<CloudPoint[]> {
    const target = this.require(collection);
    return [...target.values()].slice(0, limit);
  }
}

export class FakeCloudLlm implements CloudLlmService {
  readonly configured = true;

  readonly questions: string[] = [];

  constructor(
    private readonly behavior: (question: string) => CloudLlmResult,
  ) {}

  async answer(question: string): Promise<CloudLlmResult> {
    this.questions.push(question);
    return this.behavior(question);
  }
}

export class FailingCloudLlm implements CloudLlmService {
  readonly configured: boolean;

  constructor(private readonly code: LlmErrorCode, configured = true) {
    this.configured = configured;
  }

  async answer(): Promise<CloudLlmResult> {
    throw new LlmError(this.code, `llm failure ${this.code}`);
  }
}

export class FakeEmbedding implements CloudEmbeddingService {
  readonly configured = true;

  async embed(): Promise<number[]> {
    return [0.1, 0.2, 0.3];
  }
}

export class NoEmbedding implements CloudEmbeddingService {
  readonly configured = false;

  async embed(): Promise<number[]> {
    throw new Error("not configured");
  }
}

/** Default test config with fake (non-production) values. */
export function testConfig(overrides: Partial<BackendConfig> = {}): BackendConfig {
  return {
    port: 0,
    qdrantUrl: "https://qdrant.invalid",
    qdrantApiKey: "test-qdrant-api-key-placeholder",
    deviceMemoryCollection: "device_memory",
    cloudKnowledgeCollection: "cloud_knowledge",
    embeddingDimension: 1024,
    cloudLlmApiKey: "test-llm-api-key-placeholder",
    cloudLlmBaseUrl: "https://llm.invalid/v1",
    cloudLlmModel: "test-model",
    cloudLlmTimeoutMs: 1000,
    cloudEmbeddingApiKey: null,
    cloudEmbeddingBaseUrl: "https://llm.invalid/v1",
    cloudEmbeddingModel: null,
    adminApiKey: null,
    maxRequestBytes: 1024 * 1024,
    maxQuestionChars: 2000,
    maxAnswerChars: 8000,
    ...overrides,
  };
}

export interface TestServer {
  baseUrl: string;
  server: Server;
}

/** Starts the given express app on an ephemeral port and returns its base URL. */
export function startServer(app: Express): Promise<TestServer> {
  return new Promise((resolve, reject) => {
    const server = createServer(app);
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address() as AddressInfo;
      resolve({ baseUrl: `http://127.0.0.1:${address.port}`, server });
    });
  });
}

export function closeServer(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((err) => (err ? reject(err) : resolve()));
  });
}

export const UUID_A = "11111111-1111-4111-8111-111111111111";
export const UUID_B = "22222222-2222-4222-8222-222222222222";
export const UUID_C = "33333333-3333-4333-8333-333333333333";

export function validPushBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    operationId: `UPSERT-${UUID_A}`,
    memoryId: UUID_A,
    operationType: "UPSERT",
    title: "Pump procedure",
    content: "Pump maintenance procedure revision 4.",
    memory: {
      syncDecision: "SYNC",
      origin: "LOCAL",
      redacted: false,
      version: 3,
      contentHash: "hash-abc",
      subjectKey: "P-101-PROCEDURE",
      type: "PROCEDURE",
      tags: ["pump"],
      chunkId: null,
      source: "USER_ENTRY",
      supersedes: null,
      tombstone: false,
      updatedAt: 1700000000000,
      metadata: { scope: "site" },
    },
    ...overrides,
  };
}
