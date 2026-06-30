# Tasks: BAML Gap Features

## 1. unicode-quote-normalization

- [x] Step 1 — typed contract (minimal): signature of `normalizeUnicodeQuotes` in `SchemaAlignedParser` (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (idempotent normalization, ASCII quotes preserved) + scenario tests (smart double/single quotes) (human gate)
- [x] Step 3 — implementation: add Unicode quote normalization regex to `fixQuotes` in `SchemaAlignedParser.scala`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check (none introduced) + checkpoint

## 2. constraint-validation

- [x] Step 1 — typed contract: signatures of `Constraint[A]`, `ConstraintLevel`, `ValidationResult[A]`, `ResponseCheck`, `CheckStatus`, `ValidationFailed` + `completeValidated` method (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (check non-failing, assert failing) + scenario tests (passing/failing check, failing assert, guardrail bridge) (human gate)
- [x] Step 3 — implementation: create `core/Constraint.scala`, extend `Schema.scala` with `withCheck`/`withAssert`, extend `StructuredLLM.scala` with `completeValidated`, add `ValidationFailed` to `StructuredLLMError`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R5 Stryker4s R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 3. retry-policies

- [x] Step 1 — typed contract: signatures of `RetryTrigger`, `ParseRetryAdapter`, `fromClientWithRetry` (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (retry count ≤ maxAttempts, no retry on success) + scenario tests (parse failure retry, max retries exhausted, LLM error no retry) (human gate)
- [x] Step 3 — implementation: create `core/Retry.scala`, extend `StructuredLLM.scala` with `fromClientWithRetry`, bridge to llm4s `ReliableClient` + `RetryPolicy`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 4. type-aware-sap-coercion

- [x] Step 1 — typed contract: signatures of `JsonishValue` ADT, `CompletionState`, `TypeCoercer[A]`, `CoercionScore`, `CoercionFlag`, `BamlValueWithFlags[A]`, `ParsingContext` (human gate)
- [x] Step 2 — test oracle: 3 Hedgehog properties (score ordering, coercion preserves value, backward compat) + scenario tests (AnyOf, string→int, string→bool, single→array, enum matching, union selection, coercion failure) (human gate)
- [x] Step 3 — implementation: create `sap/JsonishValue.scala`, `sap/TypeCoercer.scala`, `sap/CoercionScore.scala`, `sap/EnumMatching.scala`, `sap/UnionCoercion.scala`; rewrite `SchemaAlignedParser` to use type-aware coercion
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 backward compat (21 existing tests) R5 Stryker4s R6 Stainless (pure coercion) R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 5. semantic-streaming

- [x] Step 1 — typed contract: signatures of `StreamingBehavior`, `StreamState[A]`, `SemanticStreamingAccumulator`, `streamPartial` method (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (StreamState.complete, StreamState.pending) + scenario tests (StreamingBehavior defaults, done, needed, incomplete) (human gate)
- [x] Step 3 — implementation: create `sap/SemanticStreaming.scala`, extend `StructuredLLM.scala` with `streamPartial`, extend llm4s `StreamingAccumulator`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 6. partial-types-streaming

- [x] Step 1 — typed contract: signatures of `Partial[A]` typeclass, `streamPartial[A: Partial]` method (human gate)
- [x] Step 2 — test oracle: (covered by semantic-streaming tests — Partial typeclass is structural)
- [x] Step 3 — implementation: create `core/Partial.scala`, derive from smithy4s schemas, extend `StructuredLLM.scala` with `streamPartial[A: Partial]`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 round-trip R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 7. output-format-rendering

- [x] Step 1 — typed contract: signatures of `OutputFormatOptions`, `HoistStrategy`, `MapStyle`, `renderOutputFormat` (human gate)
- [x] Step 2 — test oracle: 1 Hedgehog property (default backward compat) + scenario tests (quote fields, list sanitization, default rendering) (human gate)
- [x] Step 3 — implementation: create `core/OutputFormat.scala`, extend `Schema.scala` with `withOutputFormat`, implement recursive type detection
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 backward compat R5 Stryker4s R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 8. fallback-round-robin

- [x] Step 1 — typed contract: signatures of `FallbackStrategy`, `RoundRobinStrategy`, `ClientStrategyMiddleware`, `withFallback`, `withRoundRobin` (human gate)
- [x] Step 2 — test oracle: 1 Hedgehog property (fallback order) + scenario tests (first succeeds, all fail, fallback) (human gate)
- [x] Step 3 — implementation: create `core/ClientStrategy.scala`, extend `StructuredLLM.scala` with `withFallback`/`withRoundRobin`, implement as llm4s `LLMMiddleware`
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 9. error-enrichment

- [x] Step 1 — typed contract: signatures of `AttemptRecord`, `AggregatedError` (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (enriched preserves underlying, includes attempt details) + scenario tests (AttemptRecord fields, multiple attempts) (human gate)
- [x] Step 3 — implementation: create `core/ClientStrategy.scala` with `AttemptRecord`, add `Enriched` to `StructuredLLMError`, wire into retry/fallback paths
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 10. dynamic-type-builder

- [x] Step 1 — typed contract: signatures of `SchemaBuilder`, `DynamicRecord`, `DynamicEnum` (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (buildSmithyIdl includes all fields, required annotation) + scenario tests (structure keyword, DynamicValue parse/accessors, invalid JSON, array/boolean) (human gate)
- [x] Step 3 — implementation: create `core/DynamicTypeBuilder.scala`, implement `SchemaBuilder` with runtime `SchemaData` construction, `DynamicValue` with ujson bridge
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R4 round-trip R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint

## 11. structured-test-framework

- [x] Step 1 — typed contract: signatures of `StructuredTest[I, A]`, `TestReport`, `TestResult`, `TestStatus`, `runTests` (human gate)
- [x] Step 2 — test oracle: 2 Hedgehog properties (testParse succeeds for valid, fails for invalid) + scenario tests (returns value, returns errors, batch, report) (human gate)
- [x] Step 3 — implementation: create `core/StructuredTestFramework.scala`, implement `runTests` with `StructuredLLM[F]` + `Constraint` evaluation
- [x] Rings: R0 `sbt structured-llm/compile` R1 WartRemover R3 Hedgehog R8 adversarial review
- [x] Concept-delta check + update concept-inventory.md + checkpoint
