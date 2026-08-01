# Concept: Eval Harness

## Concept specification

```
concept eval-harness[F, I, O]
purpose
    Run a program over a labeled dataset in parallel, score each result
    with a metric, and aggregate into a single mean score with per-example
    rows. Failures score failureScore instead of aborting; maxErrors caps
    the failure count with cancellation.
state
    parallelism: Int
    failureScore: Double
    maxErrors: Option[Int]
    seed: Long
actions
    apply [ program: I -> F[O] ; devset: List[Example[I, O]] ; metric: metric-contract[F, I, O] ; config: eval-harness ]
        => [ result: F[EvaluationResult[I, O]] ]
    apply [ ... ]
        => [ error: TooManyErrors(count, max, partial) ]
operational principle
    The harness runs the program on every example with concurrency bounded
    by parallelism, returning rows in devset order regardless of completion
    order. Program and metric failures are caught per-example: the row gets
    EvalOutcome.Failed and Score(failureScore), and the run continues. When
    failures exceed maxErrors, the harness raises TooManyErrors carrying the
    partial rows collected so far and cancels all in-flight work. The
    aggregate score is the arithmetic mean of all row scores (including
    substituted failure scores); the empty devset yields score 0.0 with
    empty rows. The harness always passes trace = None to the metric.
```

## Implementation map

| Element | Code |
|---|---|
| object `Evaluate` | `object Evaluate` (`adk4s-eval/src/main/scala/org/adk4s/eval/Evaluate.scala`) |
| action `apply` | `Evaluate.apply[F, I, O](program, devset, metric, config): F[EvaluationResult[I, O]]` |
| parallel evaluation | fs2 `Stream.parEvalMap` (ordered — preserves devset order) |
| per-example failure catching | `program(input).attempt.flatMap { ... metric(...).attempt.flatMap { ... } }` |
| maxErrors cap | `Ref[F, Int]` failure counter + `F.raiseError(EvalError.TooManyErrors(...))` when count exceeds cap; fs2 scope cancellation cancels in-flight fibers |
| mean aggregate | `rows.map(_.score.value).sum / rows.size` (0.0 for empty) |
| trace = None | hardcoded `trace = None` in metric call |
| error `TooManyErrors` | `EvalError.TooManyErrors(count: Int, max: Int, partial: Vector[EvalRow[I, O]])` |
| config | `EvalConfig(parallelism: Int, failureScore: Double, maxErrors: Option[Int], seed: Long)` |
| runtime host | `org.adk4s.eval` |

## Synchronizations

```
sync MetricScoring
when {
    eval-harness/apply: program succeeds with value pred for example
}
then {
    metric-contract/apply: scores (example, pred, trace=None) -> Score
}
```

impl: `evalOne` calls `metric(example, pred, trace = None)` after the program succeeds (`Evaluate.scala`).

```
sync FailureSubstitution
when {
    eval-harness/apply: program OR metric raises for an example
}
then {
    eval-harness/apply: records EvalOutcome.Failed + Score(config.failureScore), run continues
}
```

impl: `evalOne` uses `.attempt` on both program and metric IOs, catching errors and substituting `Score(config.failureScore)` (`Evaluate.scala`).

```
sync MaxErrorsAbort
when {
    eval-harness/apply: failure count exceeds maxErrors cap
}
then {
    eval-harness/apply: raises TooManyErrors(count, max, partial), cancels in-flight work
}
```

impl: `evalCapped` uses a `Ref[F, Int]` counter; when `newCount > cap`, raises `EvalError.TooManyErrors`; fs2 `parEvalMap` scope cancellation cancels in-flight fibers (`Evaluate.scala`).

## Deviations from the pattern

- The `maxErrors` cap uses `count > cap` (strictly exceeds), meaning `maxErrors = Some(n)` triggers on the `(n+1)`-th failure. This is consistent with the spec's "exceeds the configured max-errors cap" wording and the "Cap exceeded" scenario (maxErrors=2 → 3rd failure). The requirement Given (maxErrors=3 → count=4) confirms this semantics.
- The `seed` field in `EvalConfig` is reserved for future shuffling features and is not used by the current implementation (the harness is deterministic given a pure program + metric).
