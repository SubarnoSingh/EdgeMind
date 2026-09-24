/**
 * Cloud answer generation boundary. The provider is replaceable; credentials
 * come exclusively from environment variables. A provider must NEVER fabricate
 * success: failures map to LlmError codes that the API layer turns into
 * honest 5xx responses so Android reports a real unavailable state.
 */

export interface CloudLlmResult {
  answer: string;
  /** Provenance shown to the user (e.g. "cloud-llm · <model>"). */
  authority: string;
  provider: string;
  requestId: string | null;
}

export type LlmErrorCode =
  | "LLM_NOT_CONFIGURED"
  | "LLM_AUTH_ERROR"
  | "LLM_RATE_LIMITED"
  | "LLM_PROVIDER_ERROR"
  | "LLM_TIMEOUT"
  | "LLM_INVALID_RESPONSE";

export class LlmError extends Error {
  readonly code: LlmErrorCode;

  constructor(code: LlmErrorCode, message: string) {
    super(message);
    this.name = "LlmError";
    this.code = code;
  }
}

export interface CloudLlmService {
  readonly configured: boolean;
  answer(question: string): Promise<CloudLlmResult>;
}

interface LlmConfig {
  apiKey: string | null;
  baseUrl: string;
  model: string | null;
  timeoutMs: number;
  maxAnswerChars: number;
}

const SYSTEM_PROMPT =
  "You are the EdgeMind cloud knowledge assistant for industrial field " +
  "maintenance. Answer the user's question directly and factually. If you " +
  "do not know, say so. Never invent sources. Keep answers under 400 words.";

/**
 * OpenAI-compatible chat-completions provider (works with any endpoint that
 * speaks the OpenAI protocol). Validates the provider response: non-blank,
 * bounded length, parseable JSON.
 */
export class OpenAiCompatibleCloudLlm implements CloudLlmService {
  readonly configured: boolean;

  private readonly cfg: LlmConfig;

  constructor(cfg: LlmConfig) {
    this.cfg = cfg;
    this.configured = cfg.apiKey != null && cfg.model != null;
  }

  async answer(question: string): Promise<CloudLlmResult> {
    if (!this.configured) {
      throw new LlmError("LLM_NOT_CONFIGURED", "no cloud LLM credential is configured");
    }

    const apiKey = this.cfg.apiKey as string;
    const model = this.cfg.model as string;
    const url = `${this.cfg.baseUrl.replace(/\/+$/, "")}/chat/completions`;
    const signal = AbortSignal.timeout(this.cfg.timeoutMs);

    let response: Response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          model,
          messages: [
            { role: "system", content: SYSTEM_PROMPT },
            { role: "user", content: question },
          ],
          max_tokens: 1000,
        }),
        signal,
      });
    } catch (e) {
      const isTimeout = e instanceof DOMException && e.name === "TimeoutError";
      throw new LlmError(
        isTimeout ? "LLM_TIMEOUT" : "LLM_PROVIDER_ERROR",
        isTimeout ? "cloud LLM request timed out" : "cloud LLM request failed",
      );
    }

    if (response.status === 401 || response.status === 403) {
      throw new LlmError("LLM_AUTH_ERROR", "cloud LLM rejected the credential");
    }
    if (response.status === 429) {
      throw new LlmError("LLM_RATE_LIMITED", "cloud LLM rate limit reached");
    }
    if (!response.ok) {
      throw new LlmError("LLM_PROVIDER_ERROR", `cloud LLM provider error ${response.status}`);
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      if (signal.aborted) {
        throw new LlmError("LLM_TIMEOUT", "cloud LLM request timed out");
      }
      throw new LlmError("LLM_INVALID_RESPONSE", "cloud LLM returned malformed JSON");
    }

    const content = extractContent(data);
    const trimmed = content?.trim();
    if (trimmed == null || trimmed.length === 0) {
      throw new LlmError("LLM_INVALID_RESPONSE", "cloud LLM returned an empty answer");
    }
    if (trimmed.length > this.cfg.maxAnswerChars) {
      throw new LlmError(
        "LLM_INVALID_RESPONSE",
        `cloud LLM answer exceeds ${this.cfg.maxAnswerChars} characters`,
      );
    }

    const requestId =
      typeof data === "object" && data !== null && "id" in data && typeof (data as { id?: unknown }).id === "string"
        ? ((data as { id: string }).id as string)
        : null;

    return {
      answer: trimmed,
      authority: `cloud-llm · ${model}`,
      provider: model,
      requestId,
    };
  }
}

function extractContent(data: unknown): string | null {
  if (typeof data !== "object" || data === null) return null;
  const choices = (data as { choices?: unknown }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const first = choices[0] as { message?: unknown };
  const message = first?.message as { content?: unknown } | undefined;
  return typeof message?.content === "string" ? message.content : null;
}

/** Honest no-credential provider: always reports LLM_NOT_CONFIGURED. */
export class UnconfiguredCloudLlm implements CloudLlmService {
  readonly configured = false;

  async answer(): Promise<CloudLlmResult> {
    throw new LlmError("LLM_NOT_CONFIGURED", "no cloud LLM credential is configured");
  }
}
