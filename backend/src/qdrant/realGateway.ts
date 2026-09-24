import { QdrantClient } from "@qdrant/js-client-rest";
import type { CloudPoint, QdrantGateway, ScrollPage } from "./gateway.js";

function isUuidLike(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

/**
 * Real Qdrant Cloud gateway. Credentials come exclusively from environment
 * configuration and never appear in code, logs, or error messages.
 */
export class RealQdrantGateway implements QdrantGateway {
  private readonly client: QdrantClient | null;

  readonly configured: boolean;

  constructor(url: string | null, apiKey: string | null) {
    if (url && apiKey) {
      this.client = new QdrantClient({ url, apiKey, timeout: 15000 });
      this.configured = true;
    } else {
      this.client = null;
      this.configured = false;
    }
  }

  private requireClient(): QdrantClient {
    if (this.client == null) {
      throw new Error("Qdrant is not configured");
    }
    return this.client;
  }

  async listCollections(): Promise<string[]> {
    const result = await this.requireClient().getCollections();
    return result.collections.map((c) => c.name);
  }

  async ensureCollection(name: string, dimension: number): Promise<void> {
    const client = this.requireClient();
    const exists = await client.collectionExists(name);
    if (!exists) {
      await client.createCollection(name, {
        vectors: {
          semantic: {
            size: dimension,
            distance: "Cosine",
          },
        },
      });
    }
  }

  async upsert(collection: string, points: CloudPoint[]): Promise<void> {
    if (points.length === 0) return;
    // Payload-only points are legal in Qdrant (optional vectors): omit the
    // vector entirely when no embedding provider is configured. The OpenAPI
    // generated typing requires `vector`, so the boundary is cast once here.
    const clientPoints = points.map((p) => ({
      id: p.id,
      payload: p.payload,
      ...(p.vector ? { vector: { semantic: p.vector } } : {}),
    }));
    await this.requireClient().upsert(collection, { points: clientPoints as never });
  }

  async retrieve(collection: string, ids: string[]): Promise<CloudPoint[]> {
    if (ids.length === 0) return [];
    const result = await this.requireClient().retrieve(collection, {
      ids,
      with_payload: true,
      with_vector: false,
    });
    return result.map((r) => ({
      id: String(r.id),
      payload: (r.payload ?? {}) as Record<string, unknown>,
    }));
  }

  async scroll(
    collection: string,
    offset: string | null,
    limit: number,
  ): Promise<ScrollPage> {
    const result = await this.requireClient().scroll(collection, {
      offset: offset ?? undefined,
      limit,
      with_payload: true,
      with_vector: false,
    });
    const next = isUuidLike(result.next_page_offset)
      ? String(result.next_page_offset)
      : null;
    return {
      points: result.points.map((p) => ({
        id: String(p.id),
        payload: (p.payload ?? {}) as Record<string, unknown>,
      })),
      nextOffset: next,
    };
  }

  async deletePoints(collection: string, ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    await this.requireClient().delete(collection, { points: ids });
  }

  async search(
    collection: string,
    vector: number[],
    limit: number,
  ): Promise<CloudPoint[]> {
    const result = await this.requireClient().query(collection, {
      query: vector,
      using: "semantic",
      limit,
      with_payload: true,
    });
    return result.points.map((r) => ({
      id: String(r.id),
      payload: (r.payload ?? {}) as Record<string, unknown>,
    }));
  }
}

/** Used when no credentials are configured: every operation fails honestly. */
export class UnconfiguredQdrantGateway implements QdrantGateway {
  readonly configured = false;

  async listCollections(): Promise<string[]> {
    throw new Error("Qdrant is not configured");
  }

  async ensureCollection(): Promise<void> {
    throw new Error("Qdrant is not configured");
  }

  async upsert(): Promise<void> {
    throw new Error("Qdrant is not configured");
  }

  async retrieve(): Promise<CloudPoint[]> {
    throw new Error("Qdrant is not configured");
  }

  async scroll(): Promise<ScrollPage> {
    throw new Error("Qdrant is not configured");
  }

  async deletePoints(): Promise<void> {
    throw new Error("Qdrant is not configured");
  }

  async search(): Promise<CloudPoint[]> {
    throw new Error("Qdrant is not configured");
  }
}
