/**
 * Narrow abstraction over the Qdrant client so routes stay testable against a
 * deterministic in-memory fake and the real client remains an implementation
 * detail. Point ids are memory UUIDs (Qdrant accepts UUID string ids).
 */
export interface CloudPoint {
  id: string;
  payload: Record<string, unknown>;
  vector?: number[];
}

export interface ScrollPage {
  points: CloudPoint[];
  nextOffset: string | null;
}

export interface QdrantGateway {
  /** True when the gateway has cloud credentials configured. */
  readonly configured: boolean;

  listCollections(): Promise<string[]>;

  /** Creates the collection (with a single named dense vector) if missing. */
  ensureCollection(name: string, dimension: number): Promise<void>;

  upsert(collection: string, points: CloudPoint[]): Promise<void>;

  retrieve(collection: string, ids: string[]): Promise<CloudPoint[]>;

  /**
   * Paginated payload read. `offset` is opaque (Qdrant next_page_offset);
   * `null` starts from the beginning.
   */
  scroll(
    collection: string,
    offset: string | null,
    limit: number,
  ): Promise<ScrollPage>;

  deletePoints(collection: string, ids: string[]): Promise<void>;

  /** Semantic search over the named dense vector. Requires stored vectors. */
  search(collection: string, vector: number[], limit: number): Promise<CloudPoint[]>;
}

export interface KnowledgePushPoint {
  id: string;
  payload: Record<string, unknown>;
  vector?: number[];
}
