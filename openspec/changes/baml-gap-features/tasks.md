# Tasks: BAML Gap Features

## 1. unicode-quote-normalization

- [ ] Step 1 — typed contract (minimal): signature of `normalizeUnicodeQuotes` in `SchemaAlignedParser` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (idempotent normalization, ASCII quotes preserved) + scenario tests (smart double/single quotes) (human gate)
- [ ] Step 3 — implementation: add Unicode quote normalization regex to `fixQuotes` in `SchemaAlignedParser.scala`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check (none introduced) + checkpoint

## 2. constraint-validation

- [ ] Step 1 — typed contract: signatures of `Constraint[A]`, `ConstraintLevel`, `ValidationResult[A]`, `ResponseCheck`, `CheckStatus`, `ValidationFailed` + `completeValidated` method (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (check non-failing, assert failing) + scenario tests (passing/failing check, failing assert, guardrail bridge) (human gate)
- [ ] Step 3 — implementation: create `core/Constraint.scala`, extend `Schema.scala` with `withCheck`/`withAssert`, extend `StructuredLLM.scala` with `completeValidated`, add `ValidationFailed` to `StructuredLLMError`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R5 Stryker4s R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 3. retry-policies

- [ ] Step 1 — typed contract: signatures of `RetryTrigger`, `ParseRetryAdapter`, `fromClientWithRetry` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (retry count ≤ maxAttempts, no retry on success) + scenario tests (parse failure retry, max retries exhausted, LLM error no retry) (human gate)
- [ ] Step 3 — implementation: create `core/Retry.scala`, extend `StructuredLLM.scala` with `fromClientWithRetry`, bridge to llm4s `ReliableClient` + `RetryPolicy`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 4. type-aware-sap-coercion

- [ ] Step 1 — typed contract: signatures of `JsonishValue` ADT, `CompletionState`, `TypeCoercer[A]`, `CoercionScore`, `CoercionFlag`, `BamlValueWithFlags[A]`, `ParsingContext` (human gate)
- [ ] Step 2 — test oracle: 3 Hedgehog properties (score ordering, coercion preserves value, backward compat) + scenario tests (AnyOf, string→int, string→bool, single→array, enum matching, union selection, coercion failure) (human gate)
- [ ] Step 3 — implementation: create `sap/JsonishValue.scala`, `sap/TypeCoercer.scala`, `sap/CoercionScore.scala`, `sap/EnumMatching.scala`, `sap/UnionCoercion.scala`; rewrite `SchemaAlignedParser` to use type-aware coercion
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 backward compat (21 existing tests) R5 Stryker4s R6 Stainless (pure coercion) R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 5. semantic-streaming

- [ ] Step 1 — typed contract: signatures of `StreamingBehavior`, `StreamState[A]`, `SemanticStreamingAccumulator`, `streamPartial` method (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (final value equals complete, @stream.done null until complete) + scenario tests (partial emission, @stream.done, @stream.not_null, final complete) (human gate)
- [ ] Step 3 — implementation: create `sap/SemanticStreaming.scala`, extend `Schema.scala` with `StreamingBehavior`, extend `StructuredLLM.scala` with `streamPartial`, extend llm4s `StreamingAccumulator`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 6. partial-types-streaming

- [ ] Step 1 — typed contract: signatures of `Partial[A]` typeclass, `streamPartial[A: Partial]` method (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (round-trip, subset acceptance) + scenario tests (derive partial, fromPartial fills defaults, stream partials) (human gate)
- [ ] Step 3 — implementation: create `core/Partial.scala`, derive from smithy4s schemas, extend `StructuredLLM.scala` with `streamPartial[A: Partial]`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 round-trip R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 7. output-format-rendering

- [ ] Step 1 — typed contract: signatures of `OutputFormatOptions`, `HoistStrategy`, `MapStyle`, `renderOutputFormat` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (default backward compat, hoist All no inline) + scenario tests (hoist all, quote fields, recursive types) (human gate)
- [ ] Step 3 — implementation: create `core/OutputFormat.scala`, extend `Schema.scala` with `withOutputFormat`, implement recursive type detection
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 backward compat R5 Stryker4s R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 8. fallback-round-robin

- [ ] Step 1 — typed contract: signatures of `FallbackStrategy`, `RoundRobinStrategy`, `ClientStrategyMiddleware`, `withFallback`, `withRoundRobin` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (fallback order, round-robin distribution) + scenario tests (first succeeds, fallback, all fail, rotation, fallthrough) (human gate)
- [ ] Step 3 — implementation: create `core/ClientStrategy.scala`, extend `StructuredLLM.scala` with `withFallback`/`withRoundRobin`, implement as llm4s `LLMMiddleware`
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 9. error-enrichment

- [ ] Step 1 — typed contract: signatures of `AttemptRecord`, `AggregatedError` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (all attempts recorded, no aggregation for single attempt) + scenario tests (all fail aggregated, some succeed no aggregation, single attempt) (human gate)
- [ ] Step 3 — implementation: create `core/ErrorEnrichment.scala`, add `AggregatedError` to `StructuredLLMError`, wire into retry/fallback paths
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 10. dynamic-type-builder

- [ ] Step 1 — typed contract: signatures of `SchemaBuilder`, `DynamicRecord`, `DynamicEnum` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (round-trip, missing required rejected) + scenario tests (build class, build enum, build list) (human gate)
- [ ] Step 3 — implementation: create `core/DynamicSchema.scala`, implement `SchemaBuilder` with runtime `SchemaData` construction
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 round-trip R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint

## 11. structured-test-framework

- [ ] Step 1 — typed contract: signatures of `StructuredTest[I, A]`, `TestReport`, `TestResult`, `TestStatus`, `runTests` (human gate)
- [ ] Step 2 — test oracle: 2 Hedgehog properties (counts consistent, one result per test) + scenario tests (passing test, failing assert, human eval, mixed results) (human gate)
- [ ] Step 3 — implementation: create `core/TestFramework.scala`, implement `runTests` with `StructuredLLM[F]` + `Constraint` evaluation
- [ ] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [ ] Concept-delta check + update concept-inventory.md + checkpoint
