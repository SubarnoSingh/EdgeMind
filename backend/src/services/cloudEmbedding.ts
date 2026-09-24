/**
 * Optional embeddings provider used ONLY for cloud-side semantic search
 * (GET /knowledge/search). Push/pull/idempotency work without it; when it is
 * unconfigured the search endpoint reports EMBEDDING_NOT_CONFIGURED instead of
 * fabricating vectors.
 */
export class EmbeddingNotConfiguredError extends Error {
  constructor() {
    super("no cloud embedding credential is configured");
    this.name = "EmbeddingNotConfiguredError";
  }
}

export interface CloudEmbeddingService {
  readonly configured: boolean;
  embed(text: string): Promise<number[]>;
}

export class OpenAiCompatibleCloudEmbedding implements CloudEmbeddingService {
  readonly configured: boolean;

  constructor(
    private readonly apiKey: string | null,
    private readonly baseUrl: string,
    private readonly model: string | null,
    private readonly timeoutMs = 15000,
  ) {
    this.configured = apiKey != null && model != null;
  }

  async embed(text: string): Promise<number[]> {
    if (!this.configured) throw new EmbeddingNotConfiguredError();
    const url = `${this.baseUrl.replace(/\/+$/, "")}/embeddings`;
    let response: Response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${this.apiKey}`,
        },
        body: JSON.stringify({ model: this.model, input: text }),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
    } catch {
      throw new Error("cloud embedding request failed");
    }
    if (!response.ok) {
      throw new Error(`cloud embedding provider error ${response.status}`);
    }
    const data = (await response.json()) as { data?: { embedding?: number[] }[] };
    const vector = data.data?.[0]?.embedding;
    if (!Array.isArray(vector) || vector.length === 0) {
      throw new Error("cloud embedding returned no vector");
    }
    return vector;
  }
}
