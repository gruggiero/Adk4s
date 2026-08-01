# Implementation Order

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | specs/eval-core/spec.md | `Example`, `Score`, `TraceEntry`, `Trace`, `Metric`, `EvalConfig`, `EvalOutcome`, `EvalRow`, `EvaluationResult`, `EvalError`, `Evaluate`, `Dataset`, `Metrics` | `ujson.Value`, `upickle.default`, `Async`, `Applicative`, Hedgehog, munit, `AdkError` conventions (all existing) | medium |
| 2 | specs/llm-judges/spec.md | `Judges.semanticF1`, `Judges.completeAndGrounded`, `SemanticF1Judge` schema, `CompleteAndGroundedJudge` schema | `Metric` (introduced by eval-core), `StructuredLLM`, `Schema`, `Prompt`, `Constraint`, `StructuredLLMError`, `ParseError`, `Async`, `Applicative` (all existing or from eval-core) | medium |

**Topological order**: eval-core → llm-judges. The llm-judges spec depends on
`Metric` (introduced by eval-core) — it cannot be implemented first. eval-core
has no dependency on llm-judges, so it goes first.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | eval-core | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — | full |
| 2 | llm-judges | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | full |

- R0: compile + exhaustiveness escalation for `EvalOutcome`/`EvalError` (eval-core); compile judge schemas (llm-judges)
- R1: WartRemover (`Throw` active — `EvalError` via `F.raiseError`; `Any` excluded — `ujson.Value`); Scalafix + scalafmt
- R2: import audit — no forbidden imports in `org.adk4s.eval`
- R3: 9 Hedgehog properties (eval-core) + 4 (llm-judges) + concurrent scenarios via `TestControl`
- R4: JSON round-trip property + CSV column contract (eval-core only)
- R5: deferred (no non-trivial mutation target)
- R6: skip (no pure kernel — justified in design.md)
- R7: N/A (no TLA+/Apalache)
- R8: adversarial review (mandatory, fresh-context, before R5/R6/R7)
- R9: N/A (no telemetry stack)
- Typed Contract: full for both (per operative plan — pin `Metric` signature)

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | eval-core | `adk4s-eval/src/main/scala/org/adk4s/eval/Example.scala`, `Score.scala`, `TraceEntry.scala`, `Trace.scala`, `Metric.scala`, `EvalConfig.scala`, `EvalOutcome.scala`, `EvalRow.scala`, `EvaluationResult.scala`, `EvalError.scala`, `Evaluate.scala`, `Dataset.scala`, `Metrics.scala` + `build.sbt` + `project/Dependencies.scala` |
| 2 | llm-judges | `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` (judge schemas + factory methods) |

## Human Gate Tier

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | eval-core | separate | complexity=medium (new types + complex logic — parallel evaluation, cancellation, export); correctness risk=medium (concurrency + serialization) |
| 2 | llm-judges | separate | complexity=medium (new types + LLM integration); correctness risk=medium (judge reliability, clamping) |

## Implementation Sequence

- [ ] 1. `specs/eval-core/spec.md` — Eval harness: Example/Score/Metric/Trace data types, Evaluate harness with parallel evaluation + failure-score substitution + maxErrors cancellation, EvaluationResult with JSON/CSV export, Dataset JSONL reader, built-in string metrics
- [ ] 2. `specs/llm-judges/spec.md` — LLM judges: SemanticF1 and CompleteAndGrounded judge metrics with hand-written Schema.instance definitions, eval-vs-optimization binarize toggle, out-of-range clamping via Constraint.check, parse-failure → metric-failure propagation
