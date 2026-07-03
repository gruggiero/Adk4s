# Tasks: llm4s Middleware Adoption and Type Deduplication

## 1. tool-abstraction-dedup

- [x] Step 1 — typed contract: signatures of refactored `ToolWrapper(toolFunction: ToolFunction[?, ?])`, `StructuredToolFunction.toToolFunction: ToolFunction[Any, Any]`, simplified `toToolRegistry` (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (all ToolWrappers appear in registry, execute matches ToolFunctionAdapter, synthesized name matches) + scenario tests (ToolWrapper from ToolFunction, from StructuredToolFunction, synthesized execute, handler errors, all tools in registry, no silent drop) + compile-negative stub (ToolWrapper with originalToolFunction field) (human gate)
- [x] Step 3 — implementation: refactor `ToolWrapper` to single `toolFunction` field, derive `executable` via `ToolFunctionAdapter`, implement `StructuredToolFunction.toToolFunction` synthesizing `ToolFunction[Any, Any]` from schemas + handler, simplify `toToolRegistry` to `map(_.toolFunction)`, update `ToolsNodeConfigBuilder.withStructuredTool`
- [x] Rings: R0 `sbt adk4s-core/compile` R1 WartRemover R2 layer deps R3 Hedgehog R5 Stryker4s R8 adversarial review
- [x] Concept-delta check (ToolWrapper refactored, toToolFunction introduced) + update concept-inventory.md + checkpoint

## 2. error-hierarchy-dedup

- [x] Step 1 — typed contract: signatures of refactored `LlmCallError(underlying: LLMError)` with `super(underlying)` cause wiring, `StructuredLLMError.LLMCallFailed(underlying, prompt)` with cause wiring, `RetryTrigger.shouldRetry(error: Throwable): Boolean` (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (getCause returns underlying for all wrappers, RetryTrigger classification is wrapper-agnostic, ParseFailed does not trigger LLMError retry) + scenario tests (getCause on LlmCallError, getCause on LLMCallFailed, retry on raw LLMError, retry on wrapped LLMError, no retry on ParseFailed, AdkError.LlmCallError triggers retry, underlying field preserved) (human gate)
- [x] Step 3 — implementation: add `super(underlying)` to `LlmCallError` and `LLMCallFailed` constructors, widen `RetryTrigger.shouldRetry` to `Throwable` and inspect `getCause`/`underlying` for `LLMError`, update all call sites of `shouldRetry` (Retry.withRetry, ClientStrategy.executeFallback)
- [x] Rings: R0 `sbt adk4s-core/compile` + `sbt structured-llm/compile` R1 WartRemover R2 layer deps R3 Hedgehog R5 Stryker4s R6 Stainless (pure shouldRetry functions) R8 adversarial review
- [x] Concept-delta check (shouldRetry signature changed, LlmCallError cause wired) + update concept-inventory.md + checkpoint

## 3. message-type-dedup

- [x] Step 1 — typed contract: signature of refactored `Prompt(conversation: Conversation)`, deprecated `type Message = org.llm4s.llmconnect.model.Message`, deprecated `type Role = org.llm4s.llmconnect.model.MessageRole`, updated `withOutputFormat[A]` signature, `toConversation` identity (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (Conversation round-trip identity, withOutputFormat preserves non-last messages, SerializableMessage round-trip) + scenario tests (Prompt.simple produces correct Conversation, ToolMessage preserves toolCallId, AssistantMessage preserves toolCalls, schema appended to user message, no user message edge case, deprecated alias resolves, toConversation identity, AgentToolState round-trip, old checkpoint format readable) + compile-negative stub (Prompt with messages field) (human gate)
- [x] Step 3 — implementation: refactor `Prompt` to `case class Prompt(conversation: Conversation)`, update all constructors (`simple`, `user`, `system`, `empty`, `single`, `apply`), update `withOutputFormat[A]` to append schema to last `UserMessage` in `Conversation`, add deprecated type aliases `Message`/`Role`, delete `MessageConverter.scala` and `ConversationConverter.scala`, update `StructuredLLMImpl.toConversation` to identity, update `AgentTool.scala` imports, update all in-repo callers (grep for `Prompt(`, `Message.`, `Role.`)
- [x] Rings: R0 `sbt compile` (all modules) R1 WartRemover R2 layer deps R3 Hedgehog R4 old-fixture decoding + round-trip R5 Stryker4s R8 adversarial review
- [x] Concept-delta check (Prompt refactored, Message/Role deprecated aliases, MessageConverter/ConversationConverter deleted) + update concept-inventory.md + checkpoint

## 4. llm4s-middleware-adoption

- [x] Step 1 — typed contract: signatures of `StructuredOutputMiddleware` (extends `LLMMiddleware`, schema injection in `wrap`), `ParseRetryTrigger` enum, `StructuredLLM.fromClient(client: LLMClient, middlewares: List[LLMMiddleware])`, `fromClientWithMiddleware` alias, deprecated `fromClientWithRetry` delegating to new factory (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (retry count bounded by maxAttempts, no retry on success, middleware composition order preserves innermost-first) + scenario tests (successful structured completion via middleware, parse failure surfaces, constraint failure surfaces, retry wraps structured, empty middleware list preserves behavior, parse failure retried successfully, parse failure retries exhausted, ParseRetryTrigger.All retries on both, deprecated factory same retry count, LoggingMiddleware no alteration, RateLimitingMiddleware throttles) + compile-negative stub (new RetryStructuredLLM from outside) (human gate)
- [x] Step 3 — implementation: create `StructuredOutputMiddleware` extending `LLMMiddleware` (schema injection in `wrap`), create `ParseRetryTrigger` enum, implement `fromClient(client, middlewares)` composing via `foldRight(client)((mw, c) => mw.wrap(c))` then `StructuredLLMImpl`, implement parse-failure retry loop in `StructuredLLMImpl` driven by `ParseRetryTrigger`, deprecate `fromClientWithRetry` and reimplement as delegate to `fromClient` with `ReliableClient` + parse-retry, wire `LoggingMiddleware`/`RateLimitingMiddleware` as opt-in
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R2 layer deps R3 Hedgehog R5 Stryker4s R8 adversarial review
- [x] Concept-delta check (StructuredOutputMiddleware, ParseRetryTrigger, fromClient factory introduced, RetryStructuredLLM deprecated) + update concept-inventory.md + checkpoint
