# Design: BAML Gap Features

## 1. Package Structure

### New packages in `structured-llm`

```
structured-llm/src/main/scala/org/adk4s/structured/
├── core/
│   ├── Schema.scala              # EXTEND: add constraints, streaming behavior, output format options
│   ├── StructuredLLM.scala       # EXTEND: add completeValidated, streamPartial, fromClientWithRetry
│   ├── Prompt.scala              # unchanged
│   ├── Constraint.scala          # NEW: Constraint[A], ConstraintLevel, ValidationResult, ResponseCheck
│   ├── Retry.scala               # NEW: RetryTrigger, ParseRetryAdapter
│   ├── ClientStrategy.scala      # NEW: FallbackStrategy, RoundRobinStrategy, ClientStrategyMiddleware
│   ├── OutputFormat.scala        # NEW: OutputFormatOptions, HoistStrategy, MapStyle, renderOutputFormat
│   ├── Partial.scala             # NEW: Partial[A] typeclass
│   ├── DynamicSchema.scala       # NEW: SchemaBuilder, DynamicRecord, DynamicEnum
│   ├── TestFramework.scala       # NEW: StructuredTest, TestReport, TestResult, TestStatus, runTests
│   └── ErrorEnrichment.scala     # NEW: AttemptRecord, AggregatedError
├── sap/
│   ├── SchemaAlignedParser.scala # EXTEND: add Unicode quote normalization
│   ├── JsonishValue.scala        # NEW: JsonishValue ADT with AnyOf
│   ├── TypeCoercer.scala         # NEW: TypeCoercer[A] typeclass + derived instances
│   ├── CoercionScore.scala       # NEW: CoercionFlag, CoercionScore, BamlValueWithFlags
│   ├── EnumMatching.scala        # NEW: 4-strategy fuzzy enum matching
│   ├── UnionCoercion.scala       # NEW: Union variant selection with hints
│   └── SemanticStreaming.scala   # NEW: SemanticStreamingAccumulator, CompletionState, StreamingBehavior
└── template/
    └── PromptSyntax.scala        # unchanged
```

### Layer dependency rules (Ring 2)

| Layer/Package | Must NOT import | May import |
|---------------|-----------------|------------|
| `org.adk4s.structured.sap` (pure parser kernel) | fs2, cats-effect, llm4s | stdlib, smithy4s, ujson, regex, `org.adk4s.structured.core` (types only) |
| `org.adk4s.structured.core` (API + types) | fs2 (except streaming methods), workflows4s | stdlib, smithy4s, ujson, cats-effect (for F[_]), llm4s (for LLMClient, RetryPolicy, Guardrail, StreamingAccumulator) |
| `org.adk4s.structured.sap.SemanticStreaming` | workflows4s | fs2, cats-effect, llm4s StreamingAccumulator, `org.adk4s.structured.sap` (JsonishValue, TypeCoercer) |

## 2. Effect Boundaries

### Pure code (Ring 6 candidates)
- `JsonishValue` ADT and parser — pure string → JsonishValue transformation
- `TypeCoercer[A]` — pure JsonishValue → A coercion
- `CoercionScore`, `CoercionFlag` — pure scoring arithmetic
- `EnumMatching` — pure string matching
- `UnionCoercion` — pure variant selection
- `Constraint[A]` evaluation — pure predicate application
- `OutputFormat` rendering — pure schema → string
- `Partial[A]` derivation — pure schema transformation
- `SchemaBuilder` — pure schema construction
- Unicode quote normalization — pure string transformation

### Effectful code (not Ring 6)
- `StructuredLLM` methods (complete, streamPartial, etc.) — F[_] effects
- `ParseRetryAdapter` — retry with `Temporal[F].sleep`
- `ClientStrategyMiddleware` — multi-client coordination
- `SemanticStreamingAccumulator` — mutable state for accumulation (extends llm4s StreamingAccumulator)
- `runTests` — F[_] effects for LLM calls

## 3. Type Strategy — Invalid-State Prevention

| Invariant | Strategy | Justification |
|-----------|----------|---------------|
| JsonishValue is exhaustive | Best: sealed trait ADT | Compiler enforces exhaustive pattern matching |
| CompletionState is exactly 3 values | Best: enum | Compiler enforces exhaustive matching |
| ConstraintLevel is exactly 2 values | Best: enum | Check vs Assert — no other levels |
| CoercionFlag variants are closed | Best: sealed trait | All recovery actions enumerated |
| Schema with constraints preserves type A | Good: smart constructor (`withCheck`/`withAssert` return `Schema[A]`) | Cannot change type when adding constraints |
| RetryTrigger controls retry behavior | Good: enum + pattern matching | Exhaustive matching on trigger type |
| Score is non-negative | Good: smart constructor (score computed from flags, not user-constructed) | Score = sum of flag penalties, always ≥ 0 |
| AggregatedError only from multiple attempts | Good: smart constructor (private constructor, factory from attempt list) | Cannot construct single-attempt AggregatedError |
| TestStatus is exactly 3 values | Best: enum | Passed / Failed / HumanEvalRequired |
| HoistStrategy is closed | Best: enum with Subset parameterized | All hoisting modes enumerated |
| MapStyle is exactly 2 values | Best: enum | Inline vs Verbose |

## 4. Refined Type Strategy

No Iron/refined library is available in this project. Type safety is achieved through:
- **Sealed traits/enums** for closed domain types (JsonishValue, CompletionState, ConstraintLevel, etc.)
- **Opaque types** for Schema[A] (existing pattern)
- **Smart constructors** for constrained values (score, AggregatedError)
- **Typeclasses** for extensible behavior (TypeCoercer[A], Partial[A])

No new opaque types are introduced in this change. All new types use sealed traits, enums, or case classes following existing project patterns.

## 5. IDL Model Layout

No new Smithy models are introduced. The change extends the existing `Schema[A]` typeclass which bridges Smithy IDL and smithy4s schemas. The `TypeCoercer[A]` typeclass is derived from existing `smithy4s.schema.Schema[A]` instances, not from new Smithy models.

The `SchemaBuilder` (dynamic type builder) constructs `Schema[DynamicRecord]` instances at runtime by building `SchemaData` directly, bypassing Smithy IDL — the Smithy definition string is generated from the builder configuration.

## 6. Error Strategy

### Error hierarchy extensions

`StructuredLLMError` (existing sealed trait) gets new variants:
- `ValidationFailed(failedAsserts: Vector[String], value: ujson.Value)` — from @assert constraint failures
- `AggregatedError(attempts: Vector[AttemptRecord], finalError: StructuredLLMError)` — from retry/fallback

### Error flow
1. **Parse errors** → `ParseFailed(errors, rawResponse)` (existing) — enriched with attempt history when retry is used
2. **Constraint failures** → `ValidationFailed` (new) — raised immediately for @assert, collected for @check
3. **Retry exhaustion** → `AggregatedError` (new) — wraps the final error with all attempt records
4. **Client strategy failures** → `AggregatedError` — all clients tried, all failed

### No swallowed errors
- Every coercion failure produces a `ParsingError` with the path, expected type, and actual value
- Every constraint failure produces a `ResponseCheck` with status `Failed`
- Every retry attempt is recorded in `AttemptRecord` regardless of success/failure

## 7. Compatibility Story (Ring 4)

### SAP backward compatibility
- The existing `SchemaAlignedParser.parse[A: Schema](response: String): ParseResult[A]` API is preserved
- The new type-aware coercion is an internal implementation detail — the public API signature is unchanged
- All 21 existing `SchemaSamplesParsingSuite` tests must pass unchanged
- Unicode quote normalization is applied before existing cleaning strategies — it's additive, not replacing

### Schema extension compatibility
- `Schema[A]` extension methods (`withCheck`, `withAssert`, `withStreamingBehavior`, `withOutputFormat`) return new `Schema[A]` instances — existing schemas without extensions work unchanged
- `SchemaData[A]` gets new optional fields with defaults — existing construction via `Schema.instance` works unchanged

### StructuredLLM API compatibility
- New methods (`completeValidated`, `streamPartial`, `fromClientWithRetry`, `withFallback`, `withRoundRobin`) are additive
- Existing methods (`complete`, `completeRaw`, `streamWithResult`) are unchanged in signature
- Internal implementation of `complete` may use the new type-aware SAP, but the return type is unchanged

### Wire format compatibility
- No changes to JSON wire format — LLM responses are still JSON text
- No changes to Smithy IDL format in prompts (except when `OutputFormatOptions` is explicitly used)
- Default `OutputFormatOptions` produces identical output to current `outputFormatBlock`

## 8. Verification Map

| Module/Package | Ring 0 | Ring 1 | Ring 3 | Ring 4 | Ring 5 | Ring 6 | Ring 8 |
|----------------|--------|--------|--------|--------|--------|--------|--------|
| `sap/JsonishValue` | ✅ | ✅ | ✅ properties | ✅ round-trip | ✅ | ✅ pure | ✅ |
| `sap/TypeCoercer` | ✅ | ✅ | ✅ properties | ✅ backward compat | ✅ | ✅ pure | ✅ |
| `sap/EnumMatching` | ✅ | ✅ | ✅ properties | — | ✅ | ✅ pure | ✅ |
| `sap/UnionCoercion` | ✅ | ✅ | ✅ properties | — | ✅ | ✅ pure | ✅ |
| `sap/SemanticStreaming` | ✅ | ✅ | ✅ properties | — | — | — effectful | ✅ |
| `core/Constraint` | ✅ | ✅ | ✅ properties | — | ✅ | ✅ pure | ✅ |
| `core/Retry` | ✅ | ✅ | ✅ properties | — | — | — effectful | ✅ |
| `core/ClientStrategy` | ✅ | ✅ | ✅ properties | — | — | — effectful | ✅ |
| `core/OutputFormat` | ✅ | ✅ | ✅ properties | ✅ backward compat | ✅ | ✅ pure | ✅ |
| `core/Partial` | ✅ | ✅ | ✅ properties | ✅ round-trip | ✅ | — typeclass | ✅ |
| `core/DynamicSchema` | ✅ | ✅ | ✅ properties | ✅ round-trip | — | — | ✅ |
| `core/TestFramework` | ✅ | ✅ | ✅ properties | — | — | — effectful | ✅ |
| `core/ErrorEnrichment` | ✅ | ✅ | ✅ properties | — | — | — | ✅ |
| `sap/UnicodeQuotes` | ✅ | ✅ | ✅ properties | — | ✅ | ✅ pure | ✅ |

### Ring 6 (Stainless) candidates
The pure parser kernel (`JsonishValue`, `TypeCoercer`, `CoercionScore`, `EnumMatching`, `UnionCoercion`) and constraint evaluation are candidates for formal verification. These would be mirrored in the `verified` module as pure-model functions with `require`/`ensuring` contracts. Priority: coercion correctness (string→int, enum matching, score ordering).

### Ring 5 (Stryker4s) targets
Production logic in `sap/` (coercion, matching, scoring) and `core/` (constraint evaluation, output format rendering) should be mutation-tested. Threshold: 90% for pure domain logic, 80% for effectful adapters.
