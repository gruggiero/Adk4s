# Implementation Order: llm4s-middleware-and-dedup

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/tool-abstraction-dedup/spec.md` | `ToolWrapper(toolFunction)`, `StructuredToolFunction.toToolFunction` | (none — independent) | medium |
| 2 | `specs/error-hierarchy-dedup/spec.md` | `RetryTrigger.shouldRetry(Throwable)`, `LlmCallError(underlying, cause)` | (none — independent) | medium |
| 3 | `specs/message-type-dedup/spec.md` | `Prompt(conversation: Conversation)`, deprecated `Message`/`Role` aliases | (none — independent) | high |
| 4 | `specs/llm4s-middleware-adoption/spec.md` | `StructuredOutputMiddleware`, `ParseRetryTrigger`, `fromClient(client, middlewares)` | `RetryTrigger.shouldRetry(Throwable)` (from #2), `Prompt(conversation: Conversation)` (from #3) | high |

**Topological order**: #1 and #2 and #3 are independent. #4 depends on #2 and #3. Order: #1 → #2 → #3 → #4.

**Rationale for #1 first**: `tool-abstraction-dedup` fixes a silent-drop bug (StructuredToolFunction tools invisible to LLM), is self-contained, and has no API-wide impact. Implementing it first delivers an immediate bug fix with minimal risk.

**Rationale for #2 before #3**: `error-hierarchy-dedup` is a smaller change (cause wiring + signature widening) and is needed by #4. Doing it before the larger #3 (Prompt refactor) keeps the risk gradient ascending.

**Rationale for #3 before #4**: `message-type-dedup` produces the `Prompt(conversation: Conversation)` shape that `StructuredOutputMiddleware` needs for schema injection. #4 cannot be implemented until #3 is done.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | tool-abstraction-dedup | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 2 | error-hierarchy-dedup | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — | full |
| 3 | message-type-dedup | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | full |
| 4 | llm4s-middleware-adoption | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |

**Ring 4 for #3**: `AgentToolState` checkpoint serialization — old-fixture decoding + round-trip.
**Ring 6 for #2**: `RetryTrigger.shouldRetry` and `ParseRetryTrigger` are pure classification functions — Stainless candidates.
**Ring 5 for all**: All changed production files contain pure domain logic suitable for mutation testing at 90%.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | tool-abstraction-dedup | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala` |
| 2 | error-hierarchy-dedup | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`, `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`, `structured-llm/src/main/scala/org/adk4s/structured/core/Retry.scala` |
| 3 | message-type-dedup | `structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala`, `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`, `adk4s-core/src/main/scala/org/adk4s/core/types/MessageConverter.scala` (delete), `adk4s-core/src/main/scala/org/adk4s/core/types/ConversationConverter.scala` (delete), `adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala` (update imports) |
| 4 | llm4s-middleware-adoption | `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`, `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredOutputMiddleware.scala` (new), `structured-llm/src/main/scala/org/adk4s/structured/core/Retry.scala` (deprecate `RetryStructuredLLM`) |

## Complexity Guide

- **#1 tool-abstraction-dedup**: MEDIUM — new `toToolFunction` synthesis, `ToolWrapper` signature change, bug fix. Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.
- **#2 error-hierarchy-dedup**: MEDIUM — cause wiring on 2 error variants, `shouldRetry` signature widening + logic change. Typed contract: full. Rings: 0, 1, 2, 3, 5, 6, 8.
- **#3 message-type-dedup**: HIGH — public API change (`Prompt` payload), persistence compatibility (`AgentToolState`), deletion of 2 files, deprecated aliases. Typed contract: full. Rings: 0, 1, 2, 3, 4, 5, 8.
- **#4 llm4s-middleware-adoption**: HIGH — new `StructuredOutputMiddleware` trait, new `ParseRetryTrigger` enum, new factory methods, deprecation of `fromClientWithRetry`, composition-order invariants. Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

## Implementation Sequence

- [ ] 1. `specs/tool-abstraction-dedup/spec.md` — collapse ToolWrapper dual storage, fix silent-drop bug for StructuredToolFunction tools
- [ ] 2. `specs/error-hierarchy-dedup/spec.md` — wire getCause on LlmCallError/LLMCallFailed, widen RetryTrigger.shouldRetry to Throwable
- [ ] 3. `specs/message-type-dedup/spec.md` — refactor Prompt to wrap Conversation, delete MessageConverter/ConversationConverter, deprecated aliases
- [ ] 4. `specs/llm4s-middleware-adoption/spec.md` — StructuredOutputMiddleware (schema injection), ParseRetryTrigger, fromClient(client, middlewares), deprecate fromClientWithRetry
