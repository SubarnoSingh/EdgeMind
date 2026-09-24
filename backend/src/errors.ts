/**
 * Deterministic, non-sensitive API error. `code` is machine-readable and
 * `message` is safe for clients — neither may ever contain secrets, stack
 * traces, or raw payload content beyond short validation descriptors.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export const CONFIG_ERROR = (detail: string): ApiError =>
  new ApiError(503, "CONFIG_ERROR", detail);

export const QDRANT_UNAVAILABLE = (): ApiError =>
  new ApiError(502, "QDRANT_UNAVAILABLE", "cloud storage is unavailable");

export const INVALID_REQUEST = (detail: string): ApiError =>
  new ApiError(400, "INVALID_REQUEST", detail);

export const UNAUTHORIZED = (): ApiError =>
  new ApiError(401, "UNAUTHORIZED", "missing or invalid authorization");

export const NOT_FOUND = (): ApiError =>
  new ApiError(404, "NOT_FOUND", "resource not found");

export function toApiError(e: unknown): ApiError {
  if (e instanceof ApiError) return e;
  return new ApiError(500, "INTERNAL_ERROR", "internal error");
}
