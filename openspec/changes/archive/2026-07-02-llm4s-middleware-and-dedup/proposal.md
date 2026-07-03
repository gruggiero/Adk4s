# Proposal: llm4s Middleware Adoption and Type Deduplication

## Why

The BAML gap analysis (`docs/structured-llm-baml-gap-analysis.md` §4.13) made two
recommendations that the `baml-gap-features` change deliberately deferred and
tracked as tech debt. Both are now causing concrete friction:

- **§4.13.3 — Middleware, not monolith.** Retry, logging, and rate-limiting
  should compose via llm4s `LLMMiddleware` wrapping the raw `LLMClient`, keeping
  `StructuredLLM` a thin orchestration layer. The implemented solution instead
  wraps at the `StructuredLLM` level: `RetryStructuredLLM`
  (<ref_snippet file="/home/gruggiero/git/rs/adk4s/structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala" lines="393-424" />)
  applies a hand-rolled `Retry.withRetry` loop
  (<ref_snippet file="/home/gruggiero/git/rs/adk4s/structured-llm/src/main/scala/org/adk4s/structured/core/Retry.scala" lines="37-57" />)
  around `complete` calls, rather than using llm4s `ReliableClient` /
  `RetryPolicy` / `LLMMiddleware`. Consequences:
  (a) retry only covers `StructuredLLM.complete*` paths — raw `LLMClient` calls
  elsewhere (e.g. `ChatModel` in `adk4s-core`) get no retry;
  (b) the same `LLMClient` cannot be shared between a retrying structured caller
  and a non-structured caller with consistent middleware;
  (c) `LoggingMiddleware` and `RateLimitingMiddleware` (already production-grade
  in llm4s) are not wired in at all;
  (d) the parse-failure retry adapter is a `structured-llm`-private concern
  instead of a reusable `LLMMiddleware`.

- **§4.13.4 — Existing duplication to consolidate.** Three areas duplicate llm4s
  types and were marked out-of-scope for the BAML change but explicitly tracked as
  tech debt. Each has a concrete defect today:
  1. **Message types** — `structured-llm` defines `Message(role: Role, content:
     String)` and `Role` enum
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/structured-llm/src/main/scala/org/adk4s/structured/core/Prompt.scala" lines="6-40" />),
     then converts to llm4s messages via `MessageConverter`
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/adk4s-core/src/main/scala/org/adk4s/core/types/MessageConverter.scala" lines="7-19" />).
     The conversion is **lossy**: `ToolMessage` hardcodes `toolCallId = "unknown"`
     (line 12) / `"tool-call-id"` in `StructuredLLMImpl.toConversation`
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala" lines="321-329" />),
     and `AssistantMessage` drops `toolCalls` (line 11/18). This makes `Prompt`
     unusable for tool-calling conversations.
  2. **Tool abstractions** — `ToolWrapper` stores both
     `originalToolFunction: Option[ToolFunction[?, ?]]` and
     `executable: SafeToolExecutable`
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala" lines="58-74" />)
     because the reverse cast (`SafeToolExecutable` → `ToolFunction`) fails at
     runtime. As a result, `toToolRegistry` uses
     `llm4sWrappers.flatMap(_.originalToolFunction)` (line 118), which means
     `StructuredToolFunction`-derived tools (which set `originalToolFunction =
     None`, see <ref_snippet file="/home/gruggiero/git/rs/adk4s/adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala" lines="145-151" />)
     are **silently dropped** from the LLM-visible `ToolRegistry`. Tools defined
     via `StructuredToolFunction` are invisible to the LLM.
  3. **Error hierarchies** — `AdkError.LlmCallError(underlying: LLMError)`
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala" lines="12-13" />)
     and `StructuredLLMError.LLMCallFailed(underlying: LLMError, prompt: Prompt)`
     both re-wrap the same `LLMError`. `RetryTrigger.shouldRetry` must match on
     `StructuredLLMError.LLMCallFailed` to detect LLM errors
     (<ref_snippet file="/home/gruggiero/git/rs/adk4s/structured-llm/src/main/scala/org/adk4s/structured/core/Retry.scala" lines="20-24" />),
     so retry logic is coupled to the wrapper shape, not the underlying error
     category. This is the direct cause of the middleware-not-adopted gap: had
     retry operated on `LLMClient` (which raises `LLMError` directly), the
     bridge would be unnecessary.

Resolving these removes the friction the BAML-gap implementation already
encountered (the `RetryStructuredLLM` wrapper exists *because* the middleware
pattern was not adopted), fixes the silent tool-drop bug, and unblocks future
work that needs lossless tool-calling conversations (structured tool-call
results, agent delegation chains).

## What Changes

### Affected Capabilities

- `specs/llm4s-middleware-adoption/spec.md` — adopt the llm4s `LLMMiddleware`
  stack as the retry/logging/rate-limiting composition point. Introduce a
  `StructuredOutputMiddleware` (an `LLMMiddleware`) that injects the schema,
  parses via SAP, evaluates constraints, and retries on `ParseFailed` via a
  `ParseRetryTrigger`. Replace `RetryStructuredLLM` with middleware-composed
  factories. Deprecate `fromClientWithRetry` (delegate internally for binary
  compat). Wire `LoggingMiddleware` / `RateLimitingMiddleware` as opt-in.
- `specs/message-type-dedup/spec.md` — replace `structured-llm`'s `Message` /
  `Role` with llm4s `Message` subtypes. Make `Prompt` wrap a llm4s
  `Conversation` directly. Delete the lossy `MessageConverter` /
  `ConversationConverter`. Provide a deprecated type alias + constructor for
  one release to ease migration. Update `Prompt.withOutputFormat[A]` to append
  the schema block to the last user message in the wrapped `Conversation`.
- `specs/tool-abstraction-dedup/spec.md` — collapse `ToolWrapper`'s dual
  storage. Derive `executable` from `ToolFunction` via the existing
  `ToolFunctionAdapter` and store only the `ToolFunction`. For
  `StructuredToolFunction`-derived tools, synthesize a `ToolFunction` from the
  input/output schemas so they appear in `toToolRegistry` (fixing the silent
  drop). Remove `originalToolFunction: Option[...]`.
- `specs/error-hierarchy-dedup/spec.md` — thin `AdkError` / `StructuredLLMError`
  so wrappers expose the underlying llm4s error via a `cause: LLMError` field
  (already a `Throwable` field) rather than re-encoding its fields. Make
  `RetryTrigger` match on the underlying `LLMError` category so retry no longer
  depends on the wrapper shape. Remove redundant variants that only re-wrap a
  single llm4s error with no added context.

### Out of Scope

- **GEPA prompt optimization** (gap analysis §4.9) — still deferred; research
  effort, unrelated to middleware/dedup.
- **Full collapse of `adk4s-core` tool API into llm4s `ToolFunction`** — the
  agent layer (`ReactAgent`, `AgentTool`, `ToolsNode`) will continue to expose
  `Tool` / `InvokableTool` / `StreamableTool` as its public API. Only the
  *internal* storage (`ToolWrapper`) and the *boundary* conversion
  (`toToolRegistry`) are simplified. A full public-API collapse is a larger
  breaking change tracked separately.
- **PureConfig migration** — `.scalafix.conf` has aspirational guards against
  `ConfigFactory`/`sys.env` in core sources, but `Adk4sConfig` does not yet
  exist. This change does not introduce it.
- **Stainless verification of effectful code** — the middleware adapters are
  effectful (Cats Effect / fs2) and not amenable to Ring 6; only pure adapter
  helpers (e.g. error-mapping functions) are candidates, and only if they
  extract cleanly.
- **`adk4s-core` `ChatModel` middleware wiring** — `ChatModel[F[_]]` wraps
  `LLMClient` and could benefit from the same middleware stack, but wiring it
  is a separate change. This change establishes the middleware composition
  primitives in `structured-llm` so `adk4s-core` can adopt them later.

## Approach

### Phase 1: Middleware adoption (§4.13.3)

1. Introduce `StructuredOutputMiddleware` — an `LLMMiddleware` that wraps an
   `LLMClient` and, on each `complete`/`streamComplete`, performs: schema
   injection into the conversation, call the inner client, parse via SAP,
   evaluate constraints. Because `LLMMiddleware` operates on `LLMClient`
   (pre-parse), parse-failure retry is handled by composing a `ReliableClient`
   (for `LLMError` retry) **above** the structured middleware, plus a
   structured-layer retry loop **around the parse step only** that re-calls the
   inner client on `ParseFailed` (driven by `ParseRetryTrigger`).
2. Provide `StructuredLLM.fromClient(client, middlewares: List[LLMMiddleware])`
   that composes llm4s `MiddlewareClient` with the supplied stack (retry,
   logging, rate-limiting) and then wraps in `StructuredOutputMiddleware`.
   Existing `fromClient` / `fromClientWithLogging` become thin specializations.
3. Deprecate (not remove) `fromClientWithRetry`; have it delegate to the new
   middleware-composed factory internally so behavior is identical.
4. Wire `LoggingMiddleware` and `RateLimitingMiddleware` (llm4s) as opt-in
   middlewares in the factory, with defaults (logging off, no rate limiting)
   that preserve current behavior.

### Phase 2: Message type dedup (§4.13.4 item 1)

1. Make `Prompt` wrap a llm4s `Conversation` directly (field:
   `conversation: Conversation`). Keep convenience constructors
   (`Prompt.simple`, `Prompt.user`, `Prompt.system`) building llm4s messages.
2. Remove `Message` / `Role` from `structured-llm`'s public API; callers use
   llm4s `SystemMessage` / `UserMessage` / `AssistantMessage` / `ToolMessage`
   (which carry `toolCallId` / `toolCalls` losslessly). Provide a deprecated
   type alias + deprecated constructor for one release.
3. Delete `MessageConverter` / `ConversationConverter` (the conversion becomes
   the identity). `StructuredLLMImpl.toConversation` collapses to
   `Async[F].pure(prompt.conversation)`.
4. Update `Prompt.withOutputFormat[A]` to append the schema block to the last
   user message in the wrapped `Conversation` (returns a new `Prompt`).

### Phase 3: Tool abstraction dedup (§4.13.4 item 2)

1. Replace `ToolWrapper`'s dual storage with a single
   `toolFunction: ToolFunction[?, ?]` plus a lazily-derived `executable:
   SafeToolExecutable` computed via `ToolFunctionAdapter` (the existing llm4s
   adapter, <ref_snippet file="/home/gruggiero/git/rs/adk4s/adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala" lines="41-56" />).
   The reverse-cast workaround is no longer needed because we never cast back —
   we always derive forward.
2. For `StructuredToolFunction`-derived tools, synthesize a `ToolFunction` from
   the `inputSchema` / `outputSchema` / `handler` so they appear in
   `toToolRegistry` (fixing the silent drop). This replaces
   `originalToolFunction = None` with a real `ToolFunction`.
3. Simplify `toToolRegistry` to `ToolRegistry(llm4sWrappers.map(_.toolFunction))`
   — no `flatMap` on `Option`.
4. `ToolWrapper.execute` continues to delegate to `executable` (now derived).

### Phase 4: Error hierarchy dedup (§4.13.4 item 3)

1. Ensure `AdkError.LlmCallError` and `StructuredLLMError.LLMCallFailed` expose
   the underlying `LLMError` via the standard `Throwable.getCause` (set via the
   constructor), so callers can recover the root cause without matching on the
   wrapper variant. Keep the `underlying` field for source compatibility.
2. Make `RetryTrigger.shouldRetry` inspect the underlying `LLMError` category
   (via `getCause`/`underlying`) rather than the wrapper variant, so retry
   works uniformly whether the error is raised by the middleware layer
   (`LLMError`) or the structured layer (`StructuredLLMError.LLMCallFailed`).
3. Remove redundant error variants that only re-wrap a single llm4s error with
   no added context (identified in design.md). Keep variants that add domain
   context (e.g. `ParseFailed` adds `errors: List[ParseError]` +
   `rawResponse`).

## Correctness Risk Level

**Risk**: high — this is a refactor of the LLM call path, the message type
system, the tool registry construction, and the error algebra simultaneously.
The message-type change alters the public `Prompt` API used by every caller and
persisted in `AgentToolState` checkpoint state. The tool-abstraction change
fixes a silent-drop bug, which means tools that were previously invisible to the
LLM will now appear — this changes LLM behavior in `ReactAgent` flows. The
middleware change moves retry from a wrapper to the middleware stack, where
incorrect composition order (retry must wrap logging must wrap rate-limiting
must wrap the structured middleware) produces silently wrong retry/logging
behavior. Error-algebra changes affect every `attempt`/`raiseError` site.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [ ] Ring 2: Architecture — project-specific layer dependencies, sealed domain types, effect discipline
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties for: behavior preservation (retry count ≤ maxAttempts, no retry on success — regression vs current `RetryStructuredLLM`); middleware composition order (retry wraps logging wraps structured); lossless message round-trip (`Conversation` → `Prompt` → `Conversation` = identity, including `toolCallId`/`toolCalls`); tool registry completeness (every `ToolWrapper` appears in `toToolRegistry`, including `StructuredToolFunction`-derived); error cause recovery (`getCause` returns the original `LLMError` for all wrapper variants)
- [x] Ring 4: Wire/persistence compatibility — `Prompt` is serialized in `AgentToolState` checkpoint state (messages + iteration count); the message-type change alters the persisted shape. Add round-trip properties for checkpoint serialization and verify old fixtures still parse (or provide a migration).
- [x] Ring 5: Mutation testing — Stryker4s on changed production files in `structured-llm/core/` (middleware, retry, Prompt) and `adk4s-core/tools/` (ToolWrapper, ToolsNodeConfig), threshold 90% (pure domain logic — composition, conversion)
- [ ] Ring 6: Formal verification — Stainless not applicable (effectful code, fs2 streams, Throwable-based errors)
- [ ] Ring 7: Model checking — not applicable
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for code changes
- [ ] Ring 9: Telemetry — not applicable (no telemetry stack detected)

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract | Justification |
|------|----------------|---------------|
| `specs/llm4s-middleware-adoption/spec.md` | Full | New `StructuredOutputMiddleware` trait, new `fromClient(client, middlewares)` factory signature, deprecation of `fromClientWithRetry`, retry-composition-order invariants |
| `specs/message-type-dedup/spec.md` | Full | Public API change — `Prompt` field type changes, `Message`/`Role` removed from public API, persistence shape change (`AgentToolState`) |
| `specs/tool-abstraction-dedup/spec.md` | Full | `ToolWrapper` signature change (removes `originalToolFunction`, adds `toolFunction`), `toToolRegistry` behavior change (no longer drops `StructuredToolFunction` tools), new `ToolFunction` synthesis from `StructuredToolFunction` |
| `specs/error-hierarchy-dedup/spec.md` | Full | Error algebra change — `RetryTrigger.shouldRetry` signature/semantics change, `getCause` wiring on wrapper variants, removal of redundant variants |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `LLMMiddleware` | trait | `org.llm4s.llmconnect.middleware` | The composition point for retry/logging/rate-limiting — adopt as the primary abstraction |
| `MiddlewareClient` | class | `org.llm4s.llmconnect.middleware` | Composes a list of `LLMMiddleware` over an `LLMClient` |
| `ReliableClient` | final class | `org.llm4s.reliability` | Wraps `LLMClient` with `RetryPolicy` + deadline; retries on `LLMError` — reuse for LLM-error retry layer |
| `RetryPolicy` | sealed trait | `org.llm4s.reliability` | `ExponentialBackoff`/`LinearBackoff`/`FixedDelay`/`NoRetry`/`Custom` — reuse instead of hand-rolled `Retry.withRetry` |
| `LoggingMiddleware` | class | `org.llm4s.llmconnect.middleware` | Production-grade logging with redaction — wire as opt-in |
| `RateLimitingMiddleware` | class | `org.llm4s.llmconnect.middleware` | Token-bucket rate limiting — wire as opt-in |
| `LLMClient` | trait | `org.llm4s.llmconnect` | The raw client being wrapped |
| `Conversation` | case class | `org.llm4s.llmconnect.model` | Will become the direct payload of `Prompt` |
| `Message` (llm4s) | sealed trait | `org.llm4s.llmconnect.model` | `SystemMessage`/`UserMessage`/`AssistantMessage`/`ToolMessage` — replace adk4s `Message`/`Role` |
| `ToolFunction[T, R]` | trait | `org.llm4s.toolapi` | The single source of truth for tool metadata + execution |
| `ToolFunctionAdapter` | object | `org.adk4s.core.tools` | Already converts `ToolFunction` → `SafeToolExecutable`; reuse as the single derivation point |
| `SafeToolExecutable` | trait | `org.adk4s.core.tools` | Execution interface used by `ToolsNode` |
| `ToolRegistry` | case class | `org.llm4s.toolapi` | Built from `Seq[ToolFunction]` — simplify `toToolRegistry` to feed all wrappers |
| `LLMError` | sealed trait | `org.llm4s.error` | The underlying error category — retry should match on this, not the wrapper |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` | Keep, but expose `cause` and thin redundant variants |
| `AdkError` | sealed trait | `org.adk4s.core.error` | Keep, but `LlmCallError` exposes `cause` via `Throwable.getCause` |
| `RetryTrigger` | enum | `org.adk4s.structured.core` | Change `shouldRetry` to inspect underlying `LLMError` |
| `Prompt` | case class | `org.adk4s.structured.core` | Change payload from `Vector[Message]` to `Conversation` |
| `PromptTemplate[-I]` | trait | `org.adk4s.structured.core` | Reuse as-is; `render` returns the new `Prompt` shape |
| `SchemaAlignedParser` | object | `org.adk4s.structured.sap` | Reuse as-is; called from `StructuredOutputMiddleware` |
| `Constraint` | case class | `org.adk4s.structured.core` | Reuse as-is; evaluated inside `StructuredOutputMiddleware` |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `StructuredOutputMiddleware` | trait (extends `LLMMiddleware`) | `LLMMiddleware` that injects schema, calls inner client, parses via SAP, evaluates constraints; the new home for the structured-output concern |
| `ParseRetryTrigger` | enum | Controls parse-failure retry within the structured layer (`ParseFailed` / `ValidationFailed` / `All`) — distinct from LLM-error retry handled by `ReliableClient` |
| `StructuredLLM.fromClient(client, middlewares)` | factory method | Composes `LLMMiddleware` stack then wraps in `StructuredOutputMiddleware` |
| `ToolFunction.fromStructured[I, O]` | factory (on llm4s `ToolFunction` companion or an adk4s extension) | Synthesizes a `ToolFunction` from `StructuredToolFunction`'s schemas + handler so it appears in `ToolRegistry` |
| `Prompt(conversation: Conversation)` | case class (refactor) | `Prompt` now wraps `Conversation` directly; `messages` field removed |
| `Message` / `Role` (deprecated alias) | type alias | One-release shim pointing to llm4s types for source compatibility |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Middleware composition order produces silently wrong retry/logging | Property tests asserting retry count and log emission order for all permutations of {retry, logging, rate-limit, structured}; document the required order in `StructuredLLM` scaladoc |
| `Prompt` payload change breaks checkpoint deserialization of in-flight `AgentToolState` | Ring 4 round-trip properties; if old shape cannot parse, provide a one-time migration in `CheckpointStore` and document the breaking change |
| `StructuredToolFunction` tools becoming LLM-visible changes `ReactAgent` behavior (LLM now sees tools it couldn't call before) | Add a scenario test asserting all registered tools appear in `ToolRegistry`; run the existing `ReactAgent` test suite; document as a behavior fix (bug → feature) |
| Removing `Message`/`Role` breaks downstream callers (examples, adk4s-orchestration) | Deprecated type alias for one release; update all in-repo callers in the same change; compile the whole workspace (`sbt compile`) as Ring 0 |
| Error-algebra change breaks `attempt`/`raiseError` sites that pattern-match on wrapper variants | Ring 3 regression tests covering every `RetryTrigger` case; grep for `case _: StructuredLLMError.LLMCallFailed` and `case LlmCallError(` and update all sites |
| llm4s `LLMMiddleware` API is under-documented; composition semantics may differ from expectation | Pin llm4s 0.3.4; read `MiddlewareClient` source to confirm composition order; wrap behind internal adapter traits so llm4s upgrades are isolated |
| `ToolFunction` synthesis for `StructuredToolFunction` requires JSON-schema metadata that `ToolSchema` may not expose | Inspect `ToolSchema[I]` capabilities in design.md; if insufficient, fall back to building a ujson-based `ToolFunction` directly (the handler already operates on `ujson.Value`) |
