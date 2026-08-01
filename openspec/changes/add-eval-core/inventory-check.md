# Inventory Check

**Project inventory**: `openspec/concept-inventory.md` — verified 2026-08-01
**Consistency check**: 1 stale row fixed (listed below); spot-verified via semantic scanner (5 opaque types, 60 sealed types, 280 case classes, 14 service traits, 45 smithy models, 101 generators — inventory is a subset; it accumulates per-change, not per-scan)

## Stale rows fixed

| Concept | Was | Now | Provenance kept |
|---------|-----|-----|-----------------|
| `optimizable-surface.md` Implementation map | helper `updateEitherImpl` cited `updateWalk -> updateField -> updateVectorViaPredict / updateScalarField` | `updateWalk -> updateVectorViaPredict / updateScalarField` (removed nonexistent `updateField` — the actual derivation has no `updateField` function; `updateWalk` dispatches directly to `updateVectorViaPredict` or `updateScalarField`) | spec:add-optimizable-surface/optimizable-surface (archived 2026-08-01) |

## Behavioral Concepts (registry pass)

**registry-check.sh**: OK (653 implementation-map tokens verified, 0 spec concept references checked, 2 weak bindings to tighten — pre-existing `react-agent.md` weak bindings, unrelated to this change)
**Stale implementation-map rows**: 1 fixed (`optimizable-surface.md` `updateField` — see above)
**Unregistered actions / syncs / state components**: none

## Concepts relevant to THIS change

| Concept | Kind | Package | Reuse / Introduce |
|---------|------|---------|-------------------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` | reuse — judges call `complete`/`completeTemplate` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | reuse — judge output schemas via `Schema.instance` |
| `Prompt` | case class | `org.adk4s.structured.core` | reuse — judge prompt construction |
| `Constraint.check` / `Constraint.assert` | constraint API | `org.adk4s.structured.core` | reuse — R1.13 clamp out-of-range precision/recall |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` | reuse — judge parse failure surfaces as metric failure (R1.12) |
| `ParseError` | sealed trait | `org.adk4s.structured.core` | reuse — judge SAP parse failure error type |
| `ujson.Value` | JSON value | (upickle/ujson, transitive) | reuse — `TraceEntry.input`/`output`, `toJson`, `Dataset.fromJsonl` |
| `upickle.default` | serialization | (upickle, transitive) | reuse — `Writer[I]`/`Writer[O]` for JSON export (R1.8) |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` | reuse — all Ring 3 properties |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` | reuse — test base classes |
| `AdkError` conventions | sealed trait (pattern) | `org.adk4s.core.error` | reuse — pattern reference for `EvalError` ADT |
| `Async` / `Applicative` | type classes | `cats.effect` / `cats` | reuse — `Evaluate` requires `Async[F]`, `Metric` helpers require `Applicative[F]` |
| `Example[I, O]` | case class | `org.adk4s.eval` | introduce — one evaluation datum |
| `Score` | case class | `org.adk4s.eval` | introduce — value + optional feedback |
| `TraceEntry` | case class | `org.adk4s.eval` | introduce — one predictor invocation record (data only) |
| `Trace` | case class | `org.adk4s.eval` | introduce — entries + `forPredictor` prefix filter |
| `Metric[F, I, O]` | trait | `org.adk4s.eval` | introduce — the metric contract with trace-toggle |
| `EvalConfig` | case class | `org.adk4s.eval` | introduce — parallelism, failureScore, maxErrors, seed |
| `EvalOutcome[+O]` | enum | `org.adk4s.eval` | introduce — Succeeded / Failed per-example result |
| `EvalRow[I, O]` | case class | `org.adk4s.eval` | introduce — one row of the result matrix |
| `EvaluationResult[I, O]` | case class | `org.adk4s.eval` | introduce — mean score + rows + export |
| `EvalError` | enum (extends Throwable) | `org.adk4s.eval` | introduce — `TooManyErrors` |
| `Evaluate` | object (factory) | `org.adk4s.eval` | introduce — the harness |
| `Dataset` | object (factory) | `org.adk4s.eval` | introduce — `fromJsonl` reader |
| `Judges.semanticF1` | factory (Metric) | `org.adk4s.eval` | introduce — LLM-judge returning F1 |
| `Judges.completeAndGrounded` | factory (Metric) | `org.adk4s.eval` | introduce — LLM-judge returning completeness/groundedness |
| `Metrics.exactMatch` / `Metrics.containsAll` | factory (Metric) | `org.adk4s.eval` | introduce — built-in string metrics |
| `eval-harness` (concept doc) | concept entry | `openspec/concepts/eval-harness.md` | introduce — `Evaluate` harness semantics |
| `metric-contract` (concept doc) | concept entry | `openspec/concepts/metric-contract.md` | introduce — `Metric` trait + trace-toggle + feedback channel |
