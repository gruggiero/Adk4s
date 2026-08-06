# Spec: Eval Core

<!-- This is a DELTA spec for Phase 1 of the DSPy port
     (docs/dspy-port-operative-plan.md). It introduces the eval harness —
     Example, Score, Metric, Trace (data only), Evaluate, EvaluationResult,
     CSV/JSON export, built-in string metrics, and the Dataset JSONL reader.
     The Metric signature is declared FROZEN by this change's design.md:
     the Option[Trace] argument is pinned now so optimizers (Phase 2/3) can
     depend on it without the signature ever changing.

     ALTITUDE: requirements and scenarios use behavioral vocabulary only
     (Concept/action references, domain terms, test vectors). Code
     identifiers — class names, error variants, build commands — live in
     Implementation Anchors and the Concepts Introduced table. The full
     typed contract (Scala signatures) lives in design.md. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| eval-harness (NEW — created by this spec) | The parallel evaluation harness that runs a program over a dataset, scores each result, and aggregates | `openspec/concepts/eval-harness.md` (created at apply Step 12) |
| metric-contract (NEW — created by this spec) | The metric trait with the trace-toggle idiom and the feedback channel | `openspec/concepts/metric-contract.md` (created at apply Step 12) |

Creating the `eval-harness.md` and `metric-contract.md` concept files is PART OF implementing this spec (apply Step 12).

## Concept Specifications (new concepts)

### Concept: metric-contract

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

### Concept: eval-harness

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

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ujson.Value` | JSON value | (upickle/ujson, transitive) |
| `upickle.default` | serialization | (upickle, transitive) |
| `Async` | type class | `cats.effect` |
| `Applicative` | type class | `cats` |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` |
| `AdkError` conventions | sealed trait (pattern) | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Example[I, O]` | case class | One evaluation datum: input, gold output, optional id, meta map |
| `Score` | case class | `value: Double` + optional `feedback: String`; factory helpers `zero`, `bool`, `withFeedback` |
| `TraceEntry` | case class | `(path: String, input: JsonValue, output: JsonValue)` — one predictor invocation record (data only); `JsonValue = smithy4s.Document` (immutable) |
| `Trace` | case class | `entries: Vector[TraceEntry]` + `forPredictor(path): Trace` prefix filter |
| `Metric[F, I, O]` | trait | `(Example[I, O], O, Option[Trace]) => F[Score]` — the metric contract with trace-toggle |
| `EvalConfig` | case class | `parallelism: Int`, `failureScore: Double`, `maxErrors: Option[Int]`, `seed: Long` |
| `EvalOutcome[+O]` | enum | `Succeeded(value: O)` / `Failed(error: Throwable)` |
| `EvalRow[I, O]` | case class | `(example: Example[I, O], outcome: EvalOutcome[O], score: Score)` |
| `EvaluationResult[I, O]` | case class | `score: Double` (mean) + `rows: Vector[EvalRow]` + `failures` + `toJson` + `toCsv` |
| `EvalError` | enum (extends Throwable) | `TooManyErrors(count: Int, max: Int, partial: Vector[?])` — stands alone in `org.adk4s.eval` |
| `Evaluate` | object (factory) | `apply[F, I, O](program, devset, metric, config): F[EvaluationResult]` — the harness |
| `Dataset` | object (factory) | `fromJsonl[I, O](path)(using Reader): Vector[Example[I, O]]` — JSONL dataset reader |
| `Metrics.exactMatch` | factory (Metric) | Pure `Applicative` metric: exact string equality → Score 1.0/0.0 |
| `Metrics.containsAll` | factory (Metric) | Pure `Applicative` metric: all gold substrings present → Score 1.0/0.0 |

## ADDED Requirements

### Requirement: Devset-order rows under parallelism

The harness SHALL return result rows in devset declaration order regardless of
completion order, with concurrency bounded by the configured parallelism.

**Given** a devset of N examples and a program with randomized per-call latency
**When** the harness runs with parallelism P < N
**Then** `result.rows.map(_.example.id) == devset.map(_.example.id)` — the row
order matches the devset order exactly.

**Rationale**: Downstream consumers (CSV export, per-example analysis) depend on
row order matching devset order. Using unordered parallel evaluation would
silently break this.

#### Scenario: Parallelism 4 with randomized latency

**Given** a 10-example devset and a program that sleeps a random duration (0–10ms)
per call
**When** the harness runs with parallelism 4
**Then** `rows.map(_.example.id) == devset.map(_.example.id)` — order is
preserved despite out-of-order completion.

#### Scenario: Parallelism 1 (sequential)

**Given** a 5-example devset and a program with constant latency
**When** the harness runs with parallelism 1
**Then** rows are in devset order (trivially — sequential execution).

### Requirement: Failure-score substitution

When the program raises for an example, the harness SHALL record a failed
outcome with the configured failure score and continue with the remaining
examples. The same SHALL apply when the metric raises.

**Given** a devset of 100 examples where one example's input causes the program
to raise
**When** the harness runs
**Then** 99 rows have real scores, 1 row has `EvalOutcome.Failed` with
`Score(config.failureScore)`, and no exception escapes the harness.

**Rationale**: A single bad example must not abort the entire evaluation run.
The aggregate must reflect the failure (as failureScore) but the run completes.

#### Scenario: Poisoned example among 100

**Given** example index 42 causes `program` to raise `RuntimeException("boom")`
**When** the harness runs over 100 examples
**Then** `rows(42).outcome == EvalOutcome.Failed(_)` and
`rows(42).score.value == config.failureScore`; all other rows have real
scores; `result.score` is the mean of all 100 scores.

#### Scenario: Metric raises

**Given** a metric that raises on a specific example's gold value
**When** the harness runs
**Then** that row gets `EvalOutcome.Failed` + `Score(failureScore)` and the
run continues — the metric failure is treated identically to a program
failure.

### Requirement: Max-errors abort with cancellation

The harness SHALL raise a too-many-errors error carrying the count and the
partial rows collected so far when the number of failed examples exceeds the
configured max-errors cap, and SHALL cancel all in-flight work.

**Given** a devset of 50 examples and `maxErrors = Some(3)` and a program that
raises on every call
**When** the harness runs
**Then** the harness raises `EvalError.TooManyErrors(count=4, max=3, partial)`
after the 4th failure (the cap is exceeded: 4 > 3), where `partial` contains
the rows collected before the abort, and all in-flight program calls are
cancelled (observable via a cancellation probe).

**Rationale**: Without a cap, a systematically broken program wastes the entire
budget. The cap must abort early AND cancel work actually in flight, not just
stop dispatching new work.

#### Scenario: Cap exceeded with in-flight work

**Given** `maxErrors = Some(2)` and a program that raises immediately
**When** the harness runs with parallelism 8 over 20 examples
**Then** the harness raises `EvalError.TooManyErrors` after the 3rd failure
(2 + 1 in-flight), and a `Deferred`-based cancellation probe observes that
in-flight fibers were cancelled (not allowed to complete).

#### Scenario: No cap (unlimited)

**Given** `maxErrors = None` and a program that raises on every call
**When** the harness runs over 10 examples
**Then** all 10 rows have `EvalOutcome.Failed` + `Score(failureScore)` and
the harness returns normally (no `TooManyErrors` raised).

### Requirement: Mean aggregate and empty-devset edge

The aggregate score SHALL be the arithmetic mean of all row scores (including
substituted failure scores). The empty devset SHALL yield score 0.0 with empty
rows, not an error.

**Given** a devset with scores [1.0, 0.0, 1.0, 0.5]
**When** the harness computes the aggregate
**Then** `result.score == 0.625` (the mean of the four scores).

**Rationale**: The mean is the standard aggregate for evaluation. The
empty-devset edge avoids a runtime crash on an empty dataset (e.g. a filtered
JSONL file that matched nothing).

#### Scenario: Empty devset

**Given** an empty devset (`Vector.empty`)
**When** the harness runs
**Then** `result.score == 0.0` and `result.rows.isEmpty` — no error raised.

#### Scenario: All-failure devset

**Given** a 5-example devset where every program call raises, and
`failureScore = 0.0`
**When** the harness runs with `maxErrors = None`
**Then** `result.score == 0.0` (mean of five 0.0 scores).

### Requirement: Trace is None in evaluation mode

The harness SHALL pass `trace = None` to the metric on every call. The metric
contract SHALL document that optimizers pass `Some(trace)` — this is pinned
now so the metric signature never changes.

**Given** a harness run over any devset
**When** the metric is invoked
**Then** the `trace` argument is `None` — the harness never runs in
optimization mode.

**Rationale**: DSPy's `trace is None` idiom distinguishes evaluation (raw score)
from optimization (binarized score + feedback). The harness is always in
evaluation mode. Pinning the signature now means Phase 2/3 optimizers can depend
on it without breaking changes.

#### Scenario: Counting metric verifies trace is None

**Given** a metric that records the `trace` argument on each call
**When** the harness runs over a 10-example devset
**Then** every recorded `trace` is `None` — the harness never passes `Some`.

### Requirement: Feedback preserved and inert

The score feedback SHALL be preserved verbatim into rows and exports and SHALL
NOT influence the aggregate score.

**Given** two runs over the same devset and program, identical except that one
run's metric produces feedback strings and the other produces none
**When** the harness computes the aggregate
**Then** both runs produce the same `result.score` — feedback does not affect
the aggregate.

**Rationale**: The feedback channel is read by GEPA-style optimizers (Phase 3)
to guide reflection. In evaluation mode it is inert. A bug where feedback leaks
into the mean would silently corrupt optimization.

#### Scenario: Feedback does not affect aggregate

**Given** a 3-example devset and a metric that returns `Score(0.5,
Some("reason"))` for all examples
**When** the harness runs
**Then** `result.score == 0.5` and every `row.score.feedback == Some("reason")`
— feedback is preserved in rows but the aggregate is the mean of values only.

### Requirement: Metric called exactly once per example

Each metric SHALL be invoked exactly once per example — the harness SHALL NOT
retry metric calls internally.

**Given** a counting metric that increments a counter on each call
**When** the harness runs over a 10-example devset
**Then** the counter equals exactly 10 — one call per example, no retries.

**Rationale**: Retrying metric calls internally would double-count LLM-judge
costs and break cost accounting. The harness delegates retry policy to the
caller (the program and metric are responsible for their own retry).

#### Scenario: Counting metric

**Given** a metric wrapping a `Ref[IO, Int]` counter
**When** the harness runs over a 20-example devset
**Then** the counter reads exactly 20 after the run completes.

### Requirement: TraceEntry field types migrate to JsonValue

The `TraceEntry` case class SHALL carry `input: JsonValue` and `output: JsonValue` (immutable `smithy4s.Document`), NOT `ujson.Value`. The `path: String` field is unchanged. The `Trace.forPredictor` prefix filter is unchanged.

**Given** a `TraceEntry(path = "program.predictor_0", input = DObject(Map("q" -> DString("hello"))), output = DObject(Map("a" -> DString("world"))))`
**When** the entry's fields are inspected
**Then** `input` and `output` are `JsonValue` (immutable `smithy4s.Document`), and the entry is serializable via `smithy4s.json.Json`

**Rationale**: `TraceEntry` is a public type in `org.adk4s.eval` whose `ujson.Value` fields introduce an undeclared transitive dependency (upickle arrives via llm4s, but `adk4s-eval` MUST NOT depend on the llm4s LLM client per the Ring 2 purity rule in `build.sbt`). Migrating to `JsonValue` closes this gap and makes `adk4s-eval` genuinely `ujson`-free in its own types.

#### Scenario: TraceEntry with JsonValue fields

**Given** a predictor invocation with input `{"q": "hello"}` and output `{"a": "world"}`
**When** a `TraceEntry` is constructed
**Then** `input` is `DObject(Map("q" -> DString("hello")))` and `output` is `DObject(Map("a" -> DString("world")))` — both `JsonValue`

#### Scenario: TraceEntry is immutable

**Given** a `TraceEntry` with `input: JsonValue`
**When** code attempts to mutate `input` in place
**Then** compilation fails — `JsonValue` has no in-place `update` method

### Requirement: JSON export round-trip

The JSON export SHALL include a `formatVersion: 1` field and a provided
from-JSON reader SHALL re-read what the export wrote. The export/import SHALL
use `smithy4s.json.Json` for `JsonValue`-typed fields (`TraceEntry.input`/`output`),
eliminating the unnecessary string round-trips (`writeJs(...).render()` on an
already-built tree; `read[I](tree.render())` instead of `Value.transform`)
present in the pre-change code. The harness itself SHALL be codec-free —
`Writer[I]`/`Writer[O]` are required on export only, not on the `Evaluate` call.

**Given** an `EvaluationResult[I, O]` with `Writer[I]` and `Writer[O]` in scope
**When** `result.toJson` is called and then `EvaluationResult.fromJson` reads
the JSON
**Then** the round-tripped result equals the original (value equality on score,
rows, outcomes, and feedback), and the `TraceEntry.input`/`output` fields are
`JsonValue` throughout.

**Rationale**: Compiled-state persistence (Phase 2) and CI artifact storage
depend on the export being re-readable. Versioning the format allows future
migrations. Keeping `Evaluate` codec-free means callers without upickle
writers can still run evaluations — export is opt-in. Using `smithy4s.json.Json`
directly eliminates the round-trips and makes the codec boundary explicit.

#### Scenario: Round-trip with feedback and failures

**Given** a result with 3 succeeded rows and 1 failed row, some with feedback,
and `TraceEntry` fields as `JsonValue`
**When** `toJson` then `fromJson` round-trips
**Then** the re-read result has the same score, the same number of rows, the
same outcomes (Succeeded/Failed), the same feedback strings, and the
`TraceEntry.input`/`output` fields are `JsonValue` (not `ujson.Value`).

#### Scenario: formatVersion present

**Given** any `EvaluationResult`
**When** `toJson` is called
**Then** the JSON object has a `"formatVersion": 1` field at the top level.

#### Scenario: No unnecessary string round-trip

**Given** a `TraceEntry` with `input: JsonValue`
**When** `toJson` serializes it
**Then** the serialization uses `smithy4s.json.Json` directly on the `JsonValue`
— no intermediate `ujson.Value` tree is built and no `.render()` call occurs on
an already-built tree.

### Requirement: Determinism under parallelism

With a pure program and metric, two runs over the same devset SHALL produce
equal `EvaluationResult`s regardless of the configured parallelism.

**Given** a pure program (deterministic output per input) and a pure metric
**When** the harness runs with parallelism 1, then with parallelism 2, then
with parallelism 8
**Then** all three runs produce equal `EvaluationResult`s (same score, same
rows in the same order, same outcomes, same feedback).

**Rationale**: Parallelism is a performance knob, not a semantic one. If
results differ by parallelism, the harness has a concurrency bug (e.g.
unordered evaluation, shared mutable state).

#### Scenario: Parallelism sweep

**Given** a 15-example devset, a pure program, and a pure metric
**When** the harness runs with parallelism ∈ {1, 2, 8}
**Then** all three results are equal (value equality).

### Requirement: CSV export column contract

The CSV export SHALL produce one header row followed by one row per example,
with columns: id, score, feedback, outcome, and meta. The outcome column SHALL
be "succeeded" or "failed". The CSV SHALL be write-only (no from-CSV reader is
required).

**Given** an `EvaluationResult` with 5 rows (3 succeeded, 2 failed)
**When** `result.toCsv` is called
**Then** the output has 6 lines (1 header + 5 data), the header is
`id,score,feedback,outcome,meta`, and the outcome column values are "succeeded"
or "failed".

**Rationale**: CSV is the format CI pipelines and spreadsheets consume. The
column contract is fixed so downstream tooling can parse it without guessing.

#### Scenario: Mixed outcomes

**Given** a result with 2 succeeded rows (one with feedback, one without) and
1 failed row
**When** `toCsv` is called
**Then** the CSV has 4 lines (header + 3 data), the failed row's outcome is
"failed", and the feedback column is empty for the row without feedback.

### Requirement: JSONL dataset reader

The dataset reader SHALL read a JSONL file (one JSON object per line) into a
`Vector[Example[I, O]]` using caller-supplied readers for `I` and `O`. For
`JsonValue`-typed fields, the reader SHALL use `smithy4s.json.Json.read`.
Malformed lines SHALL raise a descriptive error naming the line number AND the
actual cause (schema mismatch vs. JSON syntax error), NOT a generic "malformed
JSON" message.

**Given** a JSONL file with 20 valid lines and 1 line at position 15 that is
valid JSON but does not match the expected schema
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error naming line 15 AND identifying the cause as
a schema mismatch (not "malformed JSON").

**Rationale**: CI datasets are JSONL. The reader is ~20 lines and needed for
the acceptance example. Malformed-line reporting prevents silent data loss.
Distinguishing schema mismatch from JSON syntax error is a debugging aid for
CI dataset maintainers — the pre-change code reported "malformed JSON" for any
parse failure, including schema mismatches, which is misleading when the line
is syntactically valid JSON that simply doesn't match the expected shape.

#### Scenario: Valid JSONL

**Given** a 10-line JSONL file where each line is `{"input": ..., "gold": ...}`
**When** `Dataset.fromJsonl[I, O](path)` is called with matching readers
**Then** the result is a `Vector` of 10 `Example` values.

#### Scenario: Malformed JSON at position 15

**Given** a 20-line JSONL file where line 15 is not valid JSON
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error whose message names line 15 as the
malformed line and identifies the cause as a JSON syntax error.

#### Scenario: Schema mismatch at position 15

**Given** a 20-line JSONL file where line 15 is valid JSON but missing a
required field
**When** `Dataset.fromJsonl[I, O](path)` is called
**Then** the reader raises an error whose message names line 15 and identifies
the cause as a schema mismatch (not "malformed JSON").

#### Scenario: Empty JSONL

**Given** an empty file
**When** `Dataset.fromJsonl` is called
**Then** the result is `Vector.empty` — no error.

### Requirement: Built-in string metrics

The built-in string metrics SHALL be pure `Applicative` metrics (no LLM call).
Exact-match SHALL return Score 1.0 when the prediction equals the gold string
and 0.0 otherwise. Contains-all SHALL return Score 1.0 when every gold
substring is present in the prediction and 0.0 otherwise.

**Given** gold = "hello world" and prediction = "hello world"
**When** `Metrics.exactMatch` is applied
**Then** the score is `Score(1.0)`.

**Rationale**: Not every metric needs an LLM judge. Pure string metrics cover
exact-match and substring-containment use cases with zero cost and full
determinism — the simplest building blocks for regression testing.

#### Scenario: Exact match miss

**Given** gold = "hello world" and prediction = "hello"
**When** `Metrics.exactMatch` is applied
**Then** the score is `Score(0.0)`.

#### Scenario: Contains-all with partial match

**Given** gold = "Scala is great" and prediction = "Scala is good"
**When** `Metrics.containsAll` is applied with gold split into tokens
**Then** the score is `Score(0.0)` — "great" is missing.

## Properties (Ring 3)

### Property: devset-order-under-parallelism

**Invariant**: For any devset, program with randomized latency, and parallelism
P, the result rows are in devset declaration order.

**Generator strategy**: `genDevset` (constructive — generates N examples with
sequential ids 0..N); `genParallelism` (Range.linear 1 16); program sleeps
`Random.nextInt(10)` ms per call. Classify by parallelism bucket (1, 2, 4, 8+).

```
forAll { (devset: Vector[Example[String, String]], par: Int) =>
  val result = Evaluate[IO, String, String](randomLatencyProgram, devset, Metrics.exactMatch, EvalConfig(parallelism = par)).unsafeRunSync()
  result.rows.map(_.example.id) == devset.map(_.id)
}
```

### Property: failure-score-substitution

**Invariant**: When exactly one example causes a program failure, the result
has N-1 real scores and 1 failure-scored row, and the aggregate reflects all N.

**Generator strategy**: `genDevset` (constructive, N ∈ Range.linear 2 50);
`genFailureIndex` (Range.linear 0 N-1); `genFailureScore` (Range.linearF 0.0 1.0).

```
forAll { (devset: Vector[Example[String, String]], failIdx: Int, failScore: Double) =>
  val program = (i: String) => if i == devset(failIdx).input then IO.raiseError(new RuntimeException) else IO.pure(i)
  val result = Evaluate[IO, String, String](program, devset, Metrics.exactMatch, EvalConfig(failureScore = failScore)).unsafeRunSync()
  result.rows(failIdx).outcome == EvalOutcome.Failed &&
  result.rows(failIdx).score.value == failScore &&
  result.rows.count(_.outcome.isSucceeded) == devset.size - 1
}
```

### Property: mean-aggregate

**Invariant**: The aggregate score equals the arithmetic mean of all row scores
including substituted failure scores.

**Generator strategy**: `genScores` (Vector of Double in Range.linearF 0.0 1.0,
size ∈ Range.linear 1 30); `genFailureScore` (Range.linearF 0.0 1.0). Uses a
stub metric returning fixed scores.

```
forAll { (scores: Vector[Double], failScore: Double) =>
  val metric = Metric.fromDouble[IO, String, String]((_, _) => scores(idx))
  val result = Evaluate[IO, String, String](stubProgram, stubDevset(scores.size), metric, EvalConfig(failureScore = failScore)).unsafeRunSync()
  result.score == scores.sum / scores.size
}
```

### Property: empty-devset-score-zero

**Invariant**: The empty devset yields score 0.0 with empty rows.

**Generator strategy**: constant `Vector.empty` (no generator needed — edge case).

```
forAll { (_: Unit) =>
  val result = Evaluate[IO, String, String](stubProgram, Vector.empty, Metrics.exactMatch).unsafeRunSync()
  result.score == 0.0 && result.rows.isEmpty
}
```

### Property: feedback-inert

**Invariant**: Two runs identical except for feedback strings produce the same
aggregate score.

**Generator strategy**: `genDevset` (constructive, N ∈ Range.linear 1 20);
`genScoreValue` (Range.linearF 0.0 1.0); `genFeedback` (Gen.string).

```
forAll { (devset: Vector[Example[String, String]], value: Double, fb: String) =>
  val withFb = Metric.fromDouble[IO, String, String]((_, _) => value).map(s => Score.withFeedback(value, fb))
  val noFb = Metric.fromDouble[IO, String, String]((_, _) => value)
  val r1 = Evaluate[IO, String, String](stubProgram, devset, withFb).unsafeRunSync()
  val r2 = Evaluate[IO, String, String](stubProgram, devset, noFb).unsafeRunSync()
  r1.score == r2.score
}
```

### Property: metric-called-once-per-example

**Invariant**: A counting metric is invoked exactly devset.size times.

**Generator strategy**: `genDevset` (constructive, N ∈ Range.linear 1 30).

```
forAll { (devset: Vector[Example[String, String]]) =>
  val counter = Ref.of[IO](0)
  val metric = Metric.fromPredicate[IO, String, String]((_, _) => { counter.update(_ + 1); true })
  val result = Evaluate[IO, String, String](stubProgram, devset, metric).unsafeRunSync()
  counter.get.unsafeRunSync() == devset.size
}
```

### Property: determinism-under-parallelism

**Invariant**: With a pure program and metric, runs with different parallelism
produce equal results.

**Generator strategy**: `genDevset` (constructive, N ∈ Range.linear 1 25);
`genParallelism` (Gen.element1(1, 2, 8)). Classify by parallelism.

```
forAll { (devset: Vector[Example[String, String]]) =>
  val r1 = Evaluate[IO, String, String](pureProgram, devset, Metrics.exactMatch, EvalConfig(parallelism = 1)).unsafeRunSync()
  val r2 = Evaluate[IO, String, String](pureProgram, devset, Metrics.exactMatch, EvalConfig(parallelism = 2)).unsafeRunSync()
  val r8 = Evaluate[IO, String, String](pureProgram, devset, Metrics.exactMatch, EvalConfig(parallelism = 8)).unsafeRunSync()
  r1 == r2 && r2 == r8
}
```

### Property: json-round-trip

**Invariant**: `EvaluationResult.fromJson(result.toJson) == Right(result)` for
all results with matching writers/readers in scope.

**Generator strategy**: `genEvaluationResult` (constructive — generates score,
rows with mixed outcomes, feedback); uses `String` for I/O (upickle
`ReadWriter[String]` is built-in). Classify by outcome mix (all-succeeded,
all-failed, mixed).

```
forAll { (result: EvaluationResult[String, String]) =>
  EvaluationResult.fromJson[String, String](result.toJson) == Right(result)
}
```

### Property: trace-is-none-in-eval-mode

**Invariant**: The harness passes `trace = None` to the metric on every call.

**Generator strategy**: `genDevset` (constructive, N ∈ Range.linear 1 20). Uses
a recording metric that captures the trace argument.

```
forAll { (devset: Vector[Example[String, String]]) =>
  val traces = Ref.of[IO](Vector.empty[Option[Trace]])
  val metric = new Metric[IO, String, String]:
    def apply(gold: Example[String, String], pred: String, trace: Option[Trace]): IO[Score] =
      traces.update(_ :+ trace).as(Score(1.0))
  val result = Evaluate[IO, String, String](stubProgram, devset, metric).unsafeRunSync()
  traces.get.unsafeRunSync().forall(_.isEmpty)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `e match { case EvalOutcome.Succeeded(_) => () }` (non-exhaustive — missing `Failed`) | `EvalOutcome` is a sealed enum with two variants; exhaustiveness escalation makes this a compile error | `assertDoesNotCompile("e match { case EvalOutcome.Succeeded(v) => v }")` |
| `e match { case EvalError.TooManyErrors(_, _, _) => () }` (non-exhaustive if `EvalError` gains variants) | `EvalError` is a sealed enum; exhaustiveness escalation requires all variants | `assertDoesNotCompile("e match { case EvalError.TooManyErrors(c, m, p) => c }")` (if EvalError has >1 variant at time of test) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Rows in devset order under parallelism | Requirement: Devset-order rows under parallelism | property test (devset-order-under-parallelism) + adversarial review (check for `parEvalMapUnordered`) | EvaluateSpec |
| Failure scored failureScore, run continues | Requirement: Failure-score substitution + Scenario: Poisoned example among 100 | property test (failure-score-substitution) + scenario test | EvaluateSpec |
| Metric failure treated same as program failure | Requirement: Failure-score substitution + Scenario: Metric raises | scenario test | EvaluateSpec |
| maxErrors aborts with cancellation | Requirement: Max-errors abort with cancellation + Scenario: Cap exceeded with in-flight work | scenario test (Deferred-based cancellation probe via TestControl) | EvaluateSpec |
| Aggregate is arithmetic mean | Requirement: Mean aggregate and empty-devset edge | property test (mean-aggregate) | EvaluateSpec |
| Empty devset yields 0.0, not error | Requirement: Mean aggregate and empty-devset edge + Scenario: Empty devset | property test (empty-devset-score-zero) | EvaluateSpec |
| Harness passes trace = None | Requirement: Trace is None in evaluation mode + Scenario: Counting metric verifies trace is None | property test (trace-is-none-in-eval-mode) | EvaluateSpec |
| Feedback preserved, inert | Requirement: Feedback preserved and inert + Scenario: Feedback does not affect aggregate | property test (feedback-inert) + adversarial review (check aggregate code path) | EvaluateSpec |
| Metric called exactly once per example | Requirement: Metric called exactly once per example + Scenario: Counting metric | property test (metric-called-once-per-example) + adversarial review (check for retry around metric) | EvaluateSpec |
| JSON export round-trips with formatVersion | Requirement: JSON export round-trip + Scenario: Round-trip with feedback and failures | property test (json-round-trip) + scenario test (formatVersion present) | EvaluateSpec |
| CSV column contract | Requirement: CSV export column contract + Scenario: Mixed outcomes | scenario test (column header + outcome values) | EvaluateSpec |
| JSONL reader reports malformed line | Requirement: JSONL dataset reader + Scenario: Malformed line at position 15 | scenario test (line number in error) | DatasetSpec |
| Built-in string metrics | Requirement: Built-in string metrics + Scenario: Exact match miss + Scenario: Contains-all with partial match | scenario test (exact match, contains-all) | MetricsSpec |
| Determinism under parallelism | Requirement: Determinism under parallelism + Scenario: Parallelism sweep | property test (determinism-under-parallelism) | EvaluateSpec |
| EvalOutcome exhaustiveness | Compile-Negative: non-exhaustive match over EvalOutcome | compile-negative test (assertDoesNotCompile) | EvalTypeContract |
| EvalError stands alone (not AdkError) | Requirement: Failure-score substitution | adversarial review (check EvalError does not extend AdkError) | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `adk4s-eval` module | sbt module | `build.sbt` | new module: `.dependsOn(structured-llm)`, deps catsEffect/fs2Core/munitMain/munitCatsEffect/hedgehogMunit + testDeps, `scalacOptions ++= scala3Options`, aggregated by root |
| `Evaluate` | object (factory) | `adk4s-eval/src/main/scala/org/adk4s/eval/Evaluate.scala` | the harness — fs2 `parEvalMap` (ordered), `Async[F]` bound |
| `EvaluationResult` | case class | `adk4s-eval/src/main/scala/org/adk4s/eval/EvaluationResult.scala` | `toJson`/`toCsv`/`fromJson` + `failures` |
| `Metric` | trait | `adk4s-eval/src/main/scala/org/adk4s/eval/Metric.scala` | `(Example[I, O], O, Option[Trace]) => F[Score]`; `fromPredicate`/`fromDouble` helpers; `contramap` instances |
| `Example` / `Score` / `Trace` / `TraceEntry` | case classes | `adk4s-eval/src/main/scala/org/adk4s/eval/*.scala` | pure data types |
| `EvalConfig` / `EvalOutcome` / `EvalRow` / `EvalError` | case class / enum / enum / enum | `adk4s-eval/src/main/scala/org/adk4s/eval/*.scala` | config + result containers + error ADT |
| `Dataset` | object (factory) | `adk4s-eval/src/main/scala/org/adk4s/eval/Dataset.scala` | `fromJsonl` reader (~20 lines) |
| `Metrics` | object | `adk4s-eval/src/main/scala/org/adk4s/eval/Metrics.scala` | `exactMatch`, `containsAll` pure metrics |
| `EvalHarnessExample` | example | `adk4s-examples/src/main/scala/org/adk4s/examples/eval/EvalHarnessExample.scala` | mock extraction program, 20-example JSONL devset, one rule metric + one judge, CSV+JSON export |
