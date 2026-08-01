# Concept: Metric Contract

## Concept specification

```
concept metric-contract[F, I, O]
purpose
    The scoring interface between a program's output and a labeled gold
    answer. The optional Trace argument toggles evaluation vs optimization
    behavior (DSPy's "trace is None" idiom): None means plain evaluation,
    Some means an optimizer is calling and may read the feedback channel.
state
    (none — stateless trait)
actions
    apply [ gold: Example[I, O] ; pred: O ; trace: Option[Trace] ]
        => [ score: F[Score] ]
operational principle
    A metric is a pure function from (gold, prediction, optional trace) to
    a Score. When trace is None the metric returns its raw score (evaluation
    mode). When trace is Some the metric MAY binarize or transform the score
    (optimization mode) — the harness never passes Some; only optimizers do.
    The Score.feedback channel rides along inertly: it is preserved verbatim
    into rows and exports but never influences the aggregate score. A metric
    failure (raise) is scored failureScore by the harness, never crashing it.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Metric` | `trait Metric[F[_], I, O]` (`adk4s-eval/src/main/scala/org/adk4s/eval/Metric.scala`) |
| action `apply` | `def apply(gold: Example[I, O], pred: O, trace: Option[Trace]): F[Score]` |
| helper `map` | `def map(f: Score => Score)(using Functor[F]): Metric[F, I, O]` |
| factory `fromPredicate` | `Metric.fromPredicate[F, I, O](f: (Example[I, O], O) => Boolean): Metric[F, I, O]` — Score(1.0) if true, Score(0.0) if false |
| factory `fromDouble` | `Metric.fromDouble[F, I, O](f: (Example[I, O], O) => Double): Metric[F, I, O]` — Score(f(...)) |
| built-in `exactMatch` | `Metrics.exactMatch[F]: Metric[F, String, String]` — exact string equality |
| built-in `containsAll` | `Metrics.containsAll[F]: Metric[F, String, String]` — all gold tokens present in prediction |
| runtime host | `org.adk4s.eval` |

## Synchronizations

```
sync EvaluationMode
when {
    metric-contract/apply: called with trace = None
}
then {
    metric-contract/apply: returns raw score (no binarization, no feedback transformation)
}
```

impl: The harness always calls `metric(example, pred, trace = None)` (`Evaluate.scala`). Built-in metrics (`exactMatch`, `containsAll`) ignore the trace argument entirely.

```
sync OptimizationMode
when {
    metric-contract/apply: called with trace = Some(trace)
}
then {
    metric-contract/apply: MAY binarize or transform the score based on trace data
}
```

impl: Not yet implemented (Phase 2/3 optimizers will pass `Some(trace)`). The signature is pinned now so optimizers can depend on it without breaking changes.

```
sync FeedbackInert
when {
    metric-contract/apply: returns Score with feedback
}
then {
    eval-harness/apply: preserves feedback in rows and exports, does NOT use it in aggregate
}
```

impl: `EvaluationResult.toJson` preserves `score.feedback` in JSON; `Evaluate.mean` uses only `score.value` (`Evaluate.scala`).

## Deviations from the pattern

- The `map` helper on `Metric` uses `this.apply(...)` inside a lambda, which creates a new `Metric` instance. This is the standard functional combinator pattern — no deviation.
- The `trace` argument is ignored by all built-in metrics (`exactMatch`, `containsAll`). This is correct for evaluation mode (trace = None), and for optimization mode these simple metrics do not need trace data. LLM judge metrics (Phase 2) may use the trace for binarization.
