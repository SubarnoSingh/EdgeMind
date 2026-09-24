import { Router } from "express";
import type { AppContext } from "../context.js";
import { validateQuestion } from "../validation.js";
import { ApiError, CONFIG_ERROR } from "../errors.js";
import { LlmError } from "../services/cloudLlm.js";

/**
 * POST /answers { question } — cloud question answering for the Android
 * escalation path. The ONLY context received is the question text (the
 * Phase 8 privacy contract); local evidence never reaches this endpoint.
 *
 * Failures are honest 5xx responses with a machine-readable code so Android's
 * EscalatingRagService reports a real unavailable/limitation state.
 */
export function answersRouter(ctx: AppContext): Router {
  const router = Router();

  router.post("/", async (req, res, next) => {
    try {
      const question = validateQuestion(req.body, ctx.config);
      const result = await ctx.llm.answer(question);
      res.json({
        answer: result.answer,
        authority: result.authority,
        provider: result.provider,
        requestId: result.requestId,
      });
    } catch (e) {
      if (e instanceof LlmError) {
        next(new ApiError(llmStatus(e.code), e.code, e.message));
        return;
      }
      if (e instanceof ApiError) {
        next(e);
        return;
      }
      next(CONFIG_ERROR("cloud answer service unavailable"));
    }
  });

  return router;
}

function llmStatus(code: string): number {
  switch (code) {
    case "LLM_AUTH_ERROR":
      return 502;
    case "LLM_RATE_LIMITED":
      return 429;
    case "LLM_TIMEOUT":
      return 504;
    case "LLM_INVALID_RESPONSE":
      return 502;
    case "LLM_NOT_CONFIGURED":
      return 503;
    default:
      return 502;
  }
}
