# Design: llm4s Middleware Adoption and Type Deduplication

## Package Structure

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Pure SAP kernel | `org.adk4s.structured.sap` | stdlib, smithy4s, ujson | fs2, cats-effect, llm4s LLM client, typesafe-config | No outbound imports to effect/infra |
| Structured core | `org.adk4s.structured.core` | SAP kernel, llm4s (LLMClient, Conversation, Message, middleware, reliability), cats-effect, smithy4s | fs2 (except Stream type alias), workflows4s, logback | May import llm4s middleware/reliability |
| Core tools | `org.adk4s.core.tools` | cats-effect, llm4s (toolapi, llmconnect), ujson | workflows4s, fs2, logback | May import llm4s toolapi |
| Core types | `org.adk4s.core.types` | llm4s (llmconnect.model), structured-llm (Prompt) | cats-effect, fs2, workflows4s | Boundary conversion only |
| Core errors | `org.adk4s.core.error` | llm4s (error), structured-llm (StructuredLLMError), interrupt | cats-effect, fs2 | Pure error ADT |
| Core components | `org.adk4s.core.component` | cats-effect, fs2, llm4s, ujson, structured-llm | workflows4s, logback | Effectful components |
| Orchestration | `org.adk4s.orchestration.*` | cats-effect, fs2, workflows4s, adk4s-core, structured-llm | logback, http | Workflow layer |
| Examples | `org.adk4s.examples.*` | everything | — | Edge code |
| Generated smithy4s | `smithy4s.*` | — | — | Excluded from checks |

### New Packages

No new packages are introduced. All changes are within existing packages:
- `org.adk4s.structured.core` — `StructuredOutputMiddleware`, `ParseRetryTrigger`, refactored `Prompt`, deprecated aliases
- `org.adk4s.structured.core` — refactored `StructuredLLM` factory methods
- `org.adk4s.core.tools` — refactored `ToolWrapper`, new `StructuredToolFunction.toToolFunction`
- `org.adk4s.core.error` — refactored `LlmCallError` (cause wiring)
- `org.adk4s.core.types` — `MessageConverter`/`ConversationConverter` deleted

## Effect Boundaries

| Code | Pure? | Effectful? | Ring 6 Candidate? |
|------|-------|-----------|-------------------|
| `SchemaAlignedParser.parse[A]` | ✅ Pure | No | ✅ (already verified in baml-gap) |
| `StructuredOutputMiddleware.wrap` | No (returns `LLMClient`) | Returns an `LLMClient` that wraps `next.complete` (synchronous `Either`) | ❌ (wraps side-effectful LLM call) |
| `StructuredLLMImpl.complete` | No | ✅ `F[A]` via `Async[F]` | ❌ |
| `ParseRetryTrigger.shouldRetry` | ✅ Pure | No | ✅ (pure classification function) |
| `RetryTrigger.shouldRetry` (refactored) | ✅ Pure | No | ✅ (pure classification function) |
| `Prompt.withOutputFormat[A]` | ✅ Pure | No | ✅ (pure Conversation transformation) |
| `ToolWrapper.execute` | ✅ Pure (delegates to `SafeToolExecutable`) | No | ❌ (delegates to ujson/external) |
| `StructuredToolFunction.toToolFunction` | ✅ Pure | No | ❌ (constructs a `ToolFunction` with a closure) |
| `LlmCallError` constructor | ✅ Pure | No | ❌ (Throwable construction) |

**Ring 6 candidates** (pure functions to extract into `verified` module):
- `ParseRetryTrigger.shouldRetry(error: Throwable): Boolean` — pure classification
- `RetryTrigger.shouldRetry(error: Throwable): Boolean` — pure classification
- `Prompt.withOutputFormat` — pure Conversation transformation (if extracted as a pure function)

## Type Strategy — Invalid-State Prevention

| Invariant | Placement | Justification |
|-----------|-----------|---------------|
| `Prompt` always contains a valid `Conversation` | Best — case class field `conversation: Conversation` | `Conversation` is a llm4s type that validates message ordering; invalid state is unrepresentable |
| `ToolWrapper` always has a `ToolFunction` | Best — case class field `toolFunction: ToolFunction[?, ?]` (non-optional) | The old `originalToolFunction: Option[...]` allowed `None` (the bug); non-optional field prevents this |
| `StructuredOutputMiddleware` schema injection appends to last user message | Good — smart constructor checks for last user message | If no user message exists, a new one is created (scenario covers this) |
| `RetryTrigger` classification is total over `Throwable` | Best — `shouldRetry(error: Throwable): Boolean` is total | Every `Throwable` is classified; no default branch returning a valid domain value |
| `LlmCallError.cause` is always the underlying `LLMError` | Best — set via `super(underlying)` constructor | `getCause` cannot return `null` for `LlmCallError` |
| `ParseRetryTrigger` only retries on parse/validation failures | Best — enum variants are exhaustive | `ParseFailed`, `ValidationFailed`, `All` — no `LLMError` variant (that's `ReliableClient`'s job) |

## Refined Type Strategy

This project does not use Iron or any refined-type library (per capability-profile.md). All domain values are plain case classes or opaque types without compile-time constraints. This change does not introduce Iron.

| Value | Type | Constraint Strategy |
|-------|------|---------------------|
| `maxAttempts` in `ParseRetryTrigger` config | `Int` | Runtime check: `require(maxAttempts >= 1)` in factory |
| `delay` in retry config | `Duration` | No constraint (any duration is valid) |

## IDL Model Layout

This change does not modify any Smithy IDL models. The `.smithy` files in `structured-llm-test-models/src/main/smithy/` are test fixtures and are unaffected.

## Error Strategy

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `StructuredLLMError` (existing, modified) | `LLMCallFailed(underlying, prompt)` — now sets `cause`; `ParseFailed(errors, rawResponse)`; `EmptyResponse(prompt)`; `ValidationFailed(failedAsserts)`; `Enriched(underlying, attempts)` | `StructuredLLM`, `StructuredOutputMiddleware` |
| `AdkError` (existing, modified) | `LlmCallError(underlying)` — now sets `cause`; all other variants unchanged | `adk4s-core`, `adk4s-orchestration` |
| `ParseRetryTrigger` (new enum) | `ParseFailed`, `ValidationFailed`, `All` | `StructuredOutputMiddleware` parse-retry loop |
| `LLMError` (llm4s, unchanged) | (llm4s-internal variants — `APIError`, `InvalidInputError`, `ProcessingError`, etc.) | `ReliableClient`, `LLMClient` |

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| `LLMClient` → `StructuredOutputMiddleware` | `Either[LLMError, Completion]` | `client.complete(conv, opts)` returns `Left(LLMError)` on failure |
| `StructuredOutputMiddleware` → `StructuredLLM` | `Either[LLMError, Completion]` (middleware is transparent) | Schema-injected conversation → inner client → `Either[LLMError, Completion]` |
| `StructuredLLM` → Caller | `F[A]` (raises `StructuredLLMError` via `Async[F].raiseError`) | `parseResponse[A]` lifts `ParseResult.Failure` to `StructuredLLMError.ParseFailed` |
| Parse-failure retry | `RetryTrigger.shouldRetry(error: Throwable): Boolean` | Classifies `StructuredLLMError.ParseFailed` → retry if `ParseRetryTrigger.ParseFailed` or `All` |
| LLM-error retry | `ReliableClient` (llm4s) | `ReliableClient.isRetryable(LLMError)` — handled entirely within the llm4s layer |

### Key Design Decision: StructuredOutputMiddleware Scope

**Context**: The spec (`specs/llm4s-middleware-adoption/spec.md`) states `StructuredOutputMiddleware extends LLMMiddleware` and describes it as performing "schema injection, SAP parsing, and constraint evaluation."

**Problem**: `LLMMiddleware.wrap(next: LLMClient): LLMClient` returns an `LLMClient`, whose `complete` method returns `Either[LLMError, Completion]` — not a parsed `A`. SAP parsing requires the `Schema[A]` typeclass, which is not available at the `LLMClient` level. Therefore, a single `LLMMiddleware` cannot both inject the schema and parse the response into `A`.

**Decision**: Split the structured-output concern into two parts:
1. **`StructuredOutputMiddleware` (an `LLMMiddleware`)** — does schema injection only. It wraps the inner `LLMClient` and, before calling `next.complete`, appends the schema block to the last user message in the `Conversation`. The `Schema[A]` is captured in the middleware instance at construction time. The wrapped `LLMClient` still returns `Either[LLMError, Completion]` — the response is unparsed.
2. **`StructuredLLMImpl` (the `StructuredLLM` trait implementation)** — does SAP parsing and constraint evaluation. It calls the (middleware-wrapped) `LLMClient`, extracts the response content, parses via `SchemaAlignedParser.parse[A]`, and evaluates constraints. Parse-failure retry is a loop here (re-calling the `LLMClient`).

**Consequences**:
- Schema injection is composable via the llm4s middleware stack (cross-cutting concern).
- SAP parsing stays type-specific (needs `Schema[A]`) and remains in `StructuredLLM`.
- `ReliableClient` (LLM-error retry) wraps the raw `LLMClient` before the middleware stack.
- Parse-failure retry is a structured-layer loop in `StructuredLLMImpl` (or a thin wrapper), driven by `ParseRetryTrigger`.
- The spec's requirement "StructuredOutputMiddleware implements LLMMiddleware" is satisfied — it does extend `LLMMiddleware` and does schema injection. The parsing/constraint evaluation is in `StructuredLLMImpl` as before; the spec's scenario "Constraint evaluation failure surfaces as ValidationFailed" is handled by `StructuredLLMImpl.completeValidated`, not the middleware.

### Key Design Decision: ReliableClient vs LLMMiddleware for retry

**Context**: `ReliableClient` is a `final class` that directly implements `LLMClient` (not `LLMMiddleware`). It wraps an `LLMClient` with `RetryPolicy` + circuit breaker.

**Decision**: Use `ReliableClient` as a pre-composed `LLMClient` wrapper, not as an `LLMMiddleware`. The `fromClient` factory accepts an `LLMClient` that may already be wrapped with `ReliableClient`. For convenience, provide a `fromClientWithRetry` helper that constructs `ReliableClient` internally.

**Composition order**:
```
LLMClient (raw)
    ↓ wrapped by
ReliableClient (llm4s)              ← LLM-error retry + circuit breaker
    ↓ wrapped by
LoggingMiddleware (llm4s, opt-in)   ← logging with redaction
    ↓ wrapped by
RateLimitingMiddleware (llm4s, opt-in) ← token-bucket rate limiting
    ↓ wrapped by
StructuredOutputMiddleware (new)    ← schema injection into Conversation
    ↓ produces
LLMClient (composed)                ← returns Either[LLMError, Completion]
    ↓ wrapped by
StructuredLLMImpl                   ← SAP parsing + constraints + parse-failure retry
    ↓ produces
F[A] / F[ValidationResult[A]]
```

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `AgentToolState` (checkpoint) | upickle JSON (via `derives ReadWriter`) | `SerializableMessage` format unchanged (`role: String`, `content: String`); `AgentToolState` struct unchanged | Round-trip property + old-fixture decoding test |
| `Prompt` (in-memory) | N/A (not persisted directly) | `Prompt` is not serialized directly; only `AgentToolState` is persisted. `Prompt`'s payload changes from `Vector[Message]` to `Conversation` but this is in-memory only | N/A — no persistence change for `Prompt` itself |
| `ToolWrapper` (in-memory) | N/A (not persisted) | `ToolWrapper` is not serialized; only used in `ToolsNodeConfig` at runtime | N/A |

**Fixture obligation**: `old fixture JSON string → read[AgentToolState] → expected AgentToolState` and `new AgentToolState → write → read → same AgentToolState`.

The old fixture JSON for `AgentToolState` is:
```json
{"messages":[{"role":"user","content":"hello"}],"iterationCount":1}
```
This must still decode correctly because `SerializableMessage`'s `role: String` and `content: String` fields are unchanged.

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `structured-llm/core` (middleware, Prompt, StructuredLLM) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — |
| `structured-llm/core` (RetryTrigger, ParseRetryTrigger) | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — |
| `adk4s-core/tools` (ToolWrapper, StructuredToolFunction) | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — |
| `adk4s-core/error` (LlmCallError cause wiring) | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅ | — | ✅ | — |
| `adk4s-core/types` (MessageConverter deletion) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `adk4s-core/component` (AgentToolState compat) | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |

**Ring 4 rationale**: `AgentToolState` is persisted in `CheckpointStore` for interrupt/resume. The message-type change does not alter `SerializableMessage`'s serialized fields, but Ring 4 verifies this with old-fixture decoding.

**Ring 6 rationale**: `RetryTrigger.shouldRetry` and `ParseRetryTrigger.shouldRetry` are pure classification functions suitable for Stainless. `LlmCallError` cause wiring is pure construction. These can be mirrored in the `verified` module.

**Ring 5 rationale**: All changed production files contain pure domain logic (classification, conversion, composition) suitable for mutation testing at 90% threshold.

## Technical Decisions

### Decision: StructuredOutputMiddleware does schema injection only

**Context**: `LLMMiddleware.wrap` returns `LLMClient` whose `complete` returns `Either[LLMError, Completion]`, not a parsed `A`. SAP parsing needs `Schema[A]`.

**Options considered**:
1. Make `StructuredOutputMiddleware` an `LLMMiddleware` that does schema injection only; keep SAP parsing in `StructuredLLMImpl`
2. Make `StructuredOutputMiddleware` a non-`LLMMiddleware` higher-level abstraction that does both
3. Make `StructuredOutputMiddleware` an `LLMMiddleware` that stores `Schema[A]` and parses inside `complete`, returning `Completion` with parsed content embedded (hacky)

**Decision**: Option 1. Schema injection is the cross-cutting concern that belongs in the middleware chain. SAP parsing is type-specific and stays in `StructuredLLMImpl`. This aligns with the llm4s middleware pattern and the gap analysis §4.13.3 diagram.

**Consequences**: The spec's "Constraint evaluation failure surfaces as ValidationFailed" scenario is handled by `StructuredLLMImpl.completeValidated`, not the middleware. The middleware is transparent to parsing. Parse-failure retry is a structured-layer loop.

### Decision: ReliableClient as pre-composed LLMClient, not LLMMiddleware

**Context**: `ReliableClient` is a `final class extends LLMClient`, not an `LLMMiddleware`. It cannot be passed as a middleware to `fromClient(client, middlewares: List[LLMMiddleware])`.

**Options considered**:
1. Accept `ReliableClient` as a pre-composed `LLMClient` — caller wraps before passing to `fromClient`
2. Wrap `ReliableClient` in an adapter `LLMMiddleware` — adds indirection
3. Change `fromClient` to accept `LLMClient` (possibly pre-wrapped) + `List[LLMMiddleware]` — middlewares compose on top of whatever `LLMClient` is passed

**Decision**: Option 3. `fromClient(client, middlewares)` composes middlewares via `foldRight(client)((mw, c) => mw.wrap(c))`. If the caller wants LLM-error retry, they pass `ReliableClient(rawClient)` as the `client` argument. The deprecated `fromClientWithRetry` helper does this internally.

**Consequences**: `ReliableClient` is not in the `middlewares` list — it's the base `LLMClient`. This is consistent with llm4s's design where `ReliableClient` is a client wrapper, not a middleware.

### Decision: Parse-failure retry stays at StructuredLLM layer

**Context**: `ReliableClient` only retries on `LLMError`. Parse failures (`StructuredLLMError.ParseFailed`) are not `LLMError`s, so `ReliableClient` cannot handle them.

**Options considered**:
1. Keep parse-failure retry as a structured-layer loop in `StructuredLLMImpl` (or a thin wrapper)
2. Convert `ParseFailed` to an `LLMError` so `ReliableClient` can retry it — but this requires a fresh LLM call, not just a re-parse, and `ReliableClient` retries the same call, not with a modified prompt
3. Make a custom `RetryPolicy` that retries on `ParseFailed` — but `RetryPolicy.isRetryable` takes `LLMError`, not `Throwable`

**Decision**: Option 1. Parse-failure retry is a loop in `StructuredLLMImpl` that re-calls the (middleware-composed) `LLMClient` with the same conversation. The loop is driven by `ParseRetryTrigger` and `maxAttempts`. This is the same logic as the current `Retry.withRetry`, but it now operates on top of a middleware-composed `LLMClient` (so LLM-error retry happens first via `ReliableClient`, then parse-failure retry happens at the structured layer).

**Consequences**: There are two retry layers: `ReliableClient` for `LLMError` (configurable via `RetryPolicy`), and `StructuredLLMImpl` for `ParseFailed` (configurable via `ParseRetryTrigger`). The total retry count is `reliableMaxAttempts * parseMaxAttempts` in the worst case. This is documented in the factory scaladoc.

### Decision: ToolFunction synthesis from StructuredToolFunction

**Context**: `ToolFunction[T, R]` requires `SchemaDefinition[T]` and `ReadWriter[R]`. `StructuredToolFunction[I, O]` has `ToolSchema[I]` and `ToolSchema[O]` (adk4s's own schema type), not llm4s's `SchemaDefinition`.

**Options considered**:
1. Synthesize a `ToolFunction[Any, Any]` with a `SchemaDefinition[Any]` that delegates to `ToolSchema[I].decoder` and `ToolSchema[O].encoder` — requires bridging `ToolSchema` to `SchemaDefinition`
2. Construct a `ToolFunction` directly with a custom `handler: SafeParameterExtractor => Either[String, R]` that uses `ToolSchema[I].decoder` — bypasses `SchemaDefinition` by using the raw `ujson.Value` from `SafeParameterExtractor`
3. Store the `StructuredToolFunction` in `ToolWrapper` alongside the `ToolFunction` — but this reintroduces dual storage

**Decision**: Option 2. `StructuredToolFunction.toToolFunction` constructs a `ToolFunction[Any, Any]` with:
- `name` = `stf.name`
- `description` = `stf.description`
- `schema` = a `SchemaDefinition[Any]` that accepts any JSON (the validation is done by `ToolSchema[I].decoder` in the handler)
- `handler` = `extractor => stf.inputSchema.decoder(extractor.value).flatMap(stf.handler).map(stf.outputSchema.encoder)`
- `ReadWriter[Any]` = a passthrough that reads/writes `ujson.Value`

**Consequences**: The synthesized `ToolFunction` has a permissive `SchemaDefinition` (accepts any JSON) because the real validation is in `ToolSchema[I].decoder`. This is acceptable because `ToolFunction`'s `schema` is used for LLM tool descriptions (OpenAI function calling), and the actual argument validation happens in the handler. The LLM sees the tool's `name` and `description`; the `SchemaDefinition` can be built from `ToolSchema[I]`'s JSON schema representation if available (investigate during implementation; fallback to a permissive schema).

### Decision: Deprecated type aliases for Message/Role

**Context**: `structured-llm`'s `Message` and `Role` are public API. Removing them breaks downstream callers.

**Options considered**:
1. Remove immediately — clean break
2. Deprecated type aliases for one release — `type Message = org.llm4s.llmconnect.model.Message`, `type Role = org.llm4s.llmconnect.model.MessageRole`
3. Keep `Message`/`Role` as wrapper case classes that delegate to llm4s — adds complexity

**Decision**: Option 2. Deprecated type aliases for one release. In-repo callers are updated in the same change. The aliases point to the llm4s types, so code using `Message.system("x")` needs to update to `SystemMessage("x")` (different API) — the alias is for type compatibility, not API compatibility. The deprecation warning guides migration.

**Consequences**: Callers using `Message(role, content)` constructor will not compile (the alias points to a sealed trait, not a case class). This is a source-breaking change for that constructor, but it's the only way to eliminate the lossy conversion. Document in migration notes.
