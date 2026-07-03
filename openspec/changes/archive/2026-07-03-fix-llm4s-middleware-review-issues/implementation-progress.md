# Implementation Progress: fix-llm4s-middleware-review-issues

## 1. review-fixes (7 findings)
- [x] Step 1 — typed contract (minimal; no new public types)
- [x] Step 2 — test oracle (new + tightened tests, see below)
- [x] Step 3 — implementation
- [x] Rings: R0 R1 R3 R8
- [x] Concept-delta + checkpoint

### Fixes applied
- #1 `StructuredLLM.fromClientWithRetry`: `parseTrigger` now `Option[ParseRetryTrigger]`; `LLMError → None`.
- #2 `MessageStream.messageForRole`: exhaustive match, `Tool → ToolMessage(content, toolCallId = "")`.
- #3 `PromptSyntax.messageForRole`: `Tool → ToolMessage(content, toolCallId = "")`.
- #4 `ChatTemplate.substituteMessageContent`: `ToolMessage` content substituted, `toolCallId` preserved.
- #5 Removed `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable` (dead code).
- #6 `StructuredToolFunction.toToolFunction`: schema derived from `inputSchema.jsonSchema` via `propertiesFromJsonSchema`.
- #7 `ClientStrategy.executeFallback`: index-based naming (`client-<i>`); final error wrapped in `Enriched` when multiple attempts so `AttemptRecord` names are observable.

### Tests
- `MiddlewareAdoptionSpec`: + "Deprecated fromClientWithRetry with LLMError trigger does NOT retry on parse failures" (#1).
- `ToolAbstractionDedupSpec`: tightened "surfaces handler errors with field detail" (faithful `HandlerError`, #6-error); + "exposes its input parameters to the LLM" (#6-schema); compile-negative test + property updated to not reference removed symbols (#5).
- `MessageStreamTest`: + "Concatenate Tool role keeps the tool role" (#2).
- `ChatTemplateSubstitutionSpec` (new): ToolMessage substitution + id preservation (#4).
- `FallbackRoundRobinSpec`: + "short clientNames vector yields index-based names, not repeated unknown" (#7).
- `ToolAbstractionDedupTypeContract`: comment updated (#5).

### Verification results
- R0: `structured-llm/compile`, `adk4s-core/compile` — clean.
- R1: WartRemover (compiler plugin) — clean. (scalafix fetch failed offline.)
- R3: `structured-llm/test` + `adk4s-core/test` — **480 passed, 0 failed** (95 structured-llm + 385 adk4s-core).
- R8: every review finding addressed with a regression test.
