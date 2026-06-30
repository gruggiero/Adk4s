# Proposal: BAML Gap Features for structured-llm

## Why

The `structured-llm` module was inspired by BAML but built against an older version of the BAML codebase. A comprehensive gap analysis (`docs/structured-llm-baml-gap-analysis.md`) identified 12 features BAML has that `structured-llm` lacks. The most critical gap is that the current Schema-Aligned Parser (SAP) is a "JSON syntax fixer" — it repairs malformed JSON text and delegates to smithy4s' strict decoder. BAML's approach is fundamentally different: it parses into an intermediate `jsonish::Value` (which can be ambiguous/incomplete), then **coerces** that value against the target schema, trying multiple interpretations and scoring them. This is what enables BAML to achieve state-of-the-art accuracy on the Berkeley Function Calling Leaderboard.

Additionally, the gap analysis identified that `llm4s` (the LLM client dependency) already provides retry policies, middleware composition, streaming accumulation, and guardrails — meaning several BAML features can be implemented by **wiring** existing `llm4s` infrastructure rather than building from scratch.

## What Changes

This change implements the features from the BAML gap analysis in priority order, organized into 5 phases. Each phase produces independently testable, composable capabilities.

### Affected Capabilities

- `specs/type-aware-sap-coercion/spec.md` — JsonishValue ADT, TypeCoercer typeclass, scoring system, enum fuzzy matching, union variant selection (Phase 2, HIGH priority, core innovation)
- `specs/semantic-streaming/spec.md` — CompletionState, StreamingBehavior, partial parsing during stream, StreamState wrapper (Phase 3, HIGH priority)
- `specs/constraint-validation/spec.md` — @check/@assert constraints, Constraint[A] type, ValidationResult, bridge to llm4s Guardrail (Phase 1, HIGH priority)
- `specs/retry-policies/spec.md` — RetryPolicy config, retry on parse failures, bridge to llm4s ReliableClient (Phase 1, MEDIUM priority)
- `specs/fallback-round-robin/spec.md` — FallbackStrategy, RoundRobinStrategy for multi-client LLM calls (Phase 4, MEDIUM priority)
- `specs/output-format-rendering/spec.md` — OutputFormatOptions, configurable schema-to-prompt rendering, recursive type handling (Phase 4, MEDIUM priority)
- `specs/partial-types-streaming/spec.md` — Partial[A] typeclass, partial schema derivation, streamPartial API (Phase 3, MEDIUM priority)
- `specs/dynamic-type-builder/spec.md` — SchemaBuilder for runtime-constructed schemas (Phase 5, LOW-MEDIUM priority)
- `specs/structured-test-framework/spec.md` — StructuredTest, TestReport, runTests for CI/CD evaluation (Phase 5, LOW-MEDIUM priority)
- `specs/error-enrichment/spec.md` — AttemptRecord, AggregatedError with fallback history (Phase 4, LOW priority)
- `specs/unicode-quote-normalization/spec.md` — Unicode smart quote normalization in SAP (Phase 1, LOW priority, quick win)

### Out of Scope

- **GEPA prompt optimization** (gap analysis §4.9) — research-grade, very large effort, requires test framework first. Deferred to a future change.
- **llm4s type consolidation** (gap analysis §4.13.4) — Message/Tool/Error duplication between adk4s and llm4s is tracked as separate tech-debt items.
- **Bex/next-gen BAML compiler** — the new BAML compiler crates are not part of this change.

## Approach

The implementation follows the 5-phase plan from the gap analysis, adapted to leverage existing `llm4s` infrastructure:

### Phase 1: Quick Wins (no dependencies)
1. **Unicode quote normalization** (§4.12) — trivial addition to `fixQuotes` in `SchemaAlignedParser`
2. **Retry policies** (§4.4) — wire `StructuredLLM` to retry on parse failures using `llm4s` `RetryPolicy`/`ReliableClient`; add parse-failure retry adapter
3. **Constraint validation** (§4.3) — `Constraint[A]` type attached to `Schema[A]`, evaluated post-parse; bridge to `llm4s` `Guardrail`/`OutputGuardrail` rather than reinventing

### Phase 2: Core Parser Rewrite
4. **Type-aware SAP coercion** (§4.1) — the core differentiator:
   - Introduce `JsonishValue` ADT (sealed trait with AnyOf for ambiguity)
   - Implement `TypeCoercer[A]` typeclass derived from smithy4s schemas
   - Implement scoring system (lower score = fewer coercions = better)
   - Implement enum fuzzy matching (4 escalating strategies from `match_string.rs`)
   - Implement union variant selection with variant hints

### Phase 3: Streaming (builds on Phase 2)
5. **Semantic streaming** (§4.2) — `CompletionState` enum, `StreamingBehavior` per-field config, partial parsing during stream (throttled re-parse), `StreamState[A]` wrapper; extends `llm4s` `StreamingAccumulator`
6. **Partial types** (§4.7) — `Partial[A]` typeclass making all fields optional, derived from smithy4s schemas; `streamPartial` API

### Phase 4: Polish (builds on Phases 1-3)
7. **Configurable output format** (§4.6) — `OutputFormatOptions` with hoist strategies, map styles, field quoting; recursive type detection
8. **Fallback/round-robin clients** (§4.5) — multi-client strategies as `llm4s` middleware or `adk4s-core` `ChatModel` wrapper
9. **Error enrichment** (§4.11) — `AttemptRecord` with full fallback history in errors

### Phase 5: Optional
10. **Test framework** (§4.10) — `StructuredTest[I, A]`, `TestReport`, `runTests` for CI/CD
11. **Dynamic type builder** (§4.8) — `SchemaBuilder` for runtime-constructed schemas

### Architecture: Middleware, Not Monolith
Per gap analysis §4.13.3, adopt `llm4s` `LLMMiddleware` pattern early so retry/logging/rate-limiting compose via middleware rather than being reimplemented inside `StructuredLLM`:

```
LLMClient (raw)
    → RetryMiddleware (llm4s: ReliableClient)
    → RateLimitingMiddleware (llm4s)
    → LoggingMiddleware (llm4s)
    → StructuredLLMMiddleware (new: schema injection + SAP + constraints + parse-retry)
    → F[ValidatedResult[A]]
```

## Correctness Risk Level

**Risk**: high — the core parser rewrite (Phase 2) changes the fundamental parsing strategy from "text-level regex fixes → smithy4s strict decoder" to "jsonish parse → schema-driven coercion → scoring → selection." This affects every structured LLM output in the system. Coercion logic has many edge cases (string→int, enum fuzzy matching, union variant selection, single→array) where incorrect coercion produces silently wrong results. The scoring system must correctly rank parse candidates or accuracy degrades.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [ ] Ring 2: Architecture — project-specific layer dependencies, sealed domain types, effect discipline
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties for: coercion correctness (string→int, enum matching, union selection), scoring ordering, streaming state transitions, constraint evaluation, retry behavior
- [x] Ring 4: Wire/persistence compatibility — SAP parses LLM text responses (wire-adjacent); existing `SchemaSamplesParsingSuite` (21 tests) must continue passing; add round-trip properties for coercion
- [x] Ring 5: Mutation testing — Stryker4s on changed production files in `structured-llm/sap/` and `structured-llm/core/`, threshold 90% (pure domain logic — coercion, scoring, matching)
- [ ] Ring 6: Formal verification — Stainless not applicable (effectful code, fs2 streams)
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
| `specs/type-aware-sap-coercion/spec.md` | Full | Introduces JsonishValue ADT, TypeCoercer typeclass, scoring system — core parser rewrite |
| `specs/semantic-streaming/spec.md` | Full | New CompletionState enum, StreamingBehavior, StreamState, streamPartial API |
| `specs/constraint-validation/spec.md` | Full | New Constraint[A], ConstraintLevel, ValidationResult, completeValidated API |
| `specs/retry-policies/spec.md` | Full | New RetryPolicy, RetryStrategy, RetryTrigger types, fromClientWithRetry API |
| `specs/fallback-round-robin/spec.md` | Full | New FallbackStrategy, RoundRobinStrategy, withFallback/withRoundRobin APIs |
| `specs/output-format-rendering/spec.md` | Full | New OutputFormatOptions, HoistStrategy, MapStyle types |
| `specs/partial-types-streaming/spec.md` | Full | New Partial[A] typeclass, streamPartial API |
| `specs/dynamic-type-builder/spec.md` | Full | New SchemaBuilder, DynamicRecord, DynamicEnum types |
| `specs/structured-test-framework/spec.md` | Full | New StructuredTest, TestReport, runTests API |
| `specs/error-enrichment/spec.md` | Full | New AttemptRecord, AggregatedError error variants |
| `specs/unicode-quote-normalization/spec.md` | Minimal | Single function addition to existing fixQuotes |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | Extend with constraints, streaming behavior, output format options |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` | Add completeValidated, streamPartial, fromClientWithRetry methods |
| `SchemaAlignedParser` | object | `org.adk4s.structured.sap` | Core parser to rewrite with type-aware coercion |
| `ParserConfig` | case class | `org.adk4s.structured.sap` | Extend with coercion/scoring config |
| `ParseResult[A]` | type alias | `org.adk4s.structured.sap` | May change to support scored candidates |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` | Extend with ValidationFailed, AggregatedError |
| `Prompt` | case class | `org.adk4s.structured.core` | Reuse as-is for all new API methods |
| `PromptTemplate[I]` | case class | `org.adk4s.structured.core` | Reuse for test framework |
| `llm4s RetryPolicy` | class | `org.llm4s.llmconnect.middleware` | Reuse for retry policies — wrap, don't reinvent |
| `llm4s ReliableClient` | class | `org.llm4s.llmconnect.middleware` | Reuse for LLM-error retry; add parse-failure adapter |
| `llm4s LLMMiddleware` | trait | `org.llm4s.llmconnect.middleware` | Adopt middleware composition pattern |
| `llm4s StreamingAccumulator` | class | `org.llm4s.llmconnect.streaming` | Extend for semantic streaming |
| `llm4s Guardrail[A]` | trait | `org.llm4s.agent.guardrails` | Bridge constraints to guardrail framework |
| `llm4s OutputGuardrail` | trait | `org.llm4s.agent.guardrails` | Wire into structured-output parse path |
| `smithy4s Schema[A]` | trait | `smithy4s.schema` | Derive TypeCoercer instances from smithy4s schemas |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `JsonishValue` | sealed trait (ADT) | Intermediate parse value with ambiguity (AnyOf), completion state |
| `CompletionState` | enum | Pending / Incomplete / Complete for streaming |
| `TypeCoercer[A]` | typeclass | Schema-driven coercion from JsonishValue to typed A |
| `CoercionScore` | trait | Scoring system — lower score = fewer coercions = better parse |
| `CoercionFlag` | sealed trait | Recovery actions taken (ObjectToString, StrippedNonAlphaNumeric, etc.) |
| `StreamingBehavior` | case class | Per-field streaming config (done, needed, withState) |
| `StreamState[A]` | case class | Wrapper for @stream.with_state — value + completion state |
| `Constraint[A]` | case class | Label + level + predicate for @check/@assert |
| `ConstraintLevel` | enum | Check (non-failing) vs Assert (strict) |
| `ValidationResult[A]` | case class | Value + check results + failed asserts |
| `ResponseCheck` | case class | Name + expression + status (succeeded/failed) |
| `RetryPolicy` | case class | maxRetries + strategy + retry triggers |
| `RetryStrategy` | enum | ConstantDelay vs ExponentialBackoff |
| `RetryTrigger` | enum | LLMError / ParseFailure / ValidationFailure / All |
| `FallbackStrategy` | case class | Ordered client list for fallback |
| `RoundRobinStrategy` | case class | Rotating client list for load balancing |
| `OutputFormatOptions` | case class | Configurable schema-to-prompt rendering |
| `HoistStrategy` | enum | Auto / All / None / Subset for class hoisting |
| `MapStyle` | enum | Inline vs Verbose for map rendering |
| `Partial[A]` | typeclass | Makes all fields optional for streaming partials |
| `SchemaBuilder` | class | Runtime schema construction for dynamic types |
| `DynamicRecord` | type | Result type for dynamic class schemas |
| `StructuredTest[I, A]` | case class | Test definition for structured output evaluation |
| `TestReport` | case class | Aggregated test results with pass/fail/human-eval counts |
| `AttemptRecord` | case class | Single attempt in fallback history |
| `AggregatedError` | case class | Error with full attempt history |

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Parser rewrite breaks existing parsing accuracy | All 21 `SchemaSamplesParsingSuite` tests must pass unchanged; add comparative properties asserting new parser ≥ old parser on existing fixtures |
| Coercion produces silently wrong values | Scoring system ranks candidates; lowest-score (fewest coercions) wins; add properties verifying coercion correctness per type |
| Performance regression from re-parsing during streaming | Throttle partial parsing (BAML uses 50ms loop); benchmark against current token-level streaming |
| llm4s API changes break middleware integration | Pin llm4s 0.3.4; wrap behind internal adapter traits so llm4s upgrades are isolated |
| Scope creep — 11 specs is large | Phased implementation; each phase is independently shippable and testable; Phase 1 quick wins can ship before Phase 2 core rewrite |
| smithy4s schema introspection insufficient for TypeCoercer derivation | Fall back to manual TypeCoercer instances; smithy4s Schema has enough structure (field names, types, annotations) for derivation |
