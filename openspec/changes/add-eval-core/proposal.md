# Proposal: Add the Eval Core (DSPy Port — Phase 1)

## Why

ADK4S has no way to answer "did this prompt/model/agent change make results better
or worse?" Today, prompt tweaks, model swaps, and agent modifications are judged by
eyeballing a few outputs — there is no harness that scores a program against a
labeled dataset with a repeatable metric and produces a single number. This is the
single most-requested missing piece for anyone running ADK4S in production:
regression testing, CI scoring of prompt changes, and model comparison all need it.

The eval harness is **independently valuable before any optimizer exists** — it is
agent regression testing, CI scoring, and model-comparison harness in one. It is
also the substrate every Phase-2/3 optimizer consumes: `BootstrapFewShot` filters
teacher traces through a `Metric`, `BootstrapRS` selects candidates by valset score
via `Evaluate`, and GEPA's per-example score matrix is `Evaluate` output sliced by
predictor. Building it now — in parallel with Phase 0, since Phase 1 has no
dependency on the erased surface — means the optimizer phases start with a working
score function instead of building one under deadline pressure.

This change mirrors DSPy's `Example` / metric-contract / `Evaluate` with the
documented semantics: failures score `failureScore` instead of aborting the run; the
metric's optional `Trace` argument toggles eval-vs-optimization behavior (DSPy's
`trace is None` idiom); the `feedback` channel rides along inertly until GEPA-style
optimizers read it. Two LLM-judges (`SemanticF1`-style, `CompleteAndGrounded`-style)
ship as `structured-llm` programs so the harness is usable out of the box for
semantic similarity scoring, not just exact-match rules.

## What Changes

### Affected Capabilities

- `specs/eval-core/spec.md` — new capability: the `Example` / `Score` / `Metric` /
  `Trace` / `TraceEntry` data types, the `Evaluate` harness with `EvalConfig` /
  `EvalOutcome` / `EvalRow` / `EvaluationResult`, the `EvalError` ADT, CSV/JSON
  export with `formatVersion` round-trip, built-in string metrics (`exactMatch`,
  `containsAll`, normalized text helpers), and the `Dataset.fromJsonl` reader.
- `specs/llm-judges/spec.md` — new capability: `Judges.semanticF1` and
  `Judges.completeAndGrounded` as `Metric` instances backed by `StructuredLLM`
  judge programs over Smithy schemas, including the eval-vs-optimization toggle
  (binarize when `trace.isDefined`) and the constraint-clamp + feedback path.

### Out of Scope

- Any optimizer (bootstrap, COPRO, GEPA, MIPRO, KNN) — Phases 2–4.
- Trace *capture* — only the `Trace` / `TraceEntry` data types are introduced so
  the `Metric` signature is final from day one. Instrumented predictors that
  populate traces are Phase 2/3.
- Dataset loaders beyond an in-memory `Vector` + JSONL reader
  (`Dataset.fromJsonl` is in — it is ~20 lines and CI needs it). No CSV dataset
  loader, no HuggingFace-style streaming, no train/val split utilities.
- Result visualization / dashboards — export is CSV + JSON; plotting is the
  caller's job.
- Signature-as-data / adapter demo rendering — Phase 2.
- The completion cache (`org.adk4s.core.cache`) — Phase 2.
- WIOGraph integration (evaluating graph-based programs by running their
  `Runnable`) — the harness takes `I => F[O]`, which a `Runnable[I, O]` already
  satisfies via `.invoke`; no graph-specific wiring is needed or wanted here.

## Approach

Introduce a new `adk4s-eval` sbt module (package `org.adk4s.eval`) depending on
`structured-llm` (for judges), cats-effect, and fs2 — matching the cross-phase
convention in `docs/dspy-port-operative-plan.md`. The module is pure orchestration:
run a program over a dataset, score each result, aggregate, export.

**Data types.** `Example[I, O]` (input + gold + optional id + meta), `Score`
(`value: Double` + optional `feedback`), `TraceEntry` / `Trace` (data only —
`forPredictor` is a prefix filter for GEPA's `pred_trace`), `Metric[F, I, O]`
(gold, pred, `Option[Trace]` → `F[Score]`), `EvalConfig` (parallelism,
failureScore, maxErrors, seed), `EvalOutcome` (Succeeded / Failed), `EvalRow`,
`EvaluationResult` (mean score + rows + `toJson` / `toCsv`), `EvalError`
(`TooManyErrors`).

**Harness.** `Evaluate.apply` runs `program` over `devset` with fs2
`parEvalMap` (ordered, not unordered — R1.1 demands devset-order rows). Program
and metric failures are caught per-example: `EvalOutcome.Failed` + `Score(failureScore)`
+ continue (R1.2). When failures exceed `maxErrors`, raise
`EvalError.TooManyErrors` carrying the partial rows and cancel in-flight work via
fs2 scope cancellation (R1.3). The aggregate is the arithmetic mean including
substituted failure scores; the empty devset yields `score = 0.0` with empty rows
(R1.4). The harness always passes `trace = None` (R1.5) — the `Some(trace)` path
is reserved for optimizers and pinned now so the `Metric` signature never changes.

**Judges.** `Judges.semanticF1` and `Judges.completeAndGrounded` are
`StructuredLLM` programs over a judge schema (precision/recall or
completeness/groundedness fields). When `trace.isEmpty` they return the raw score
+ reasoning as `feedback`; when `trace.isDefined` they binarize against a
threshold (the eval-vs-optimization toggle). Judge schemas are defined via
`Schema.instance` hand-written definitions in `adk4s-eval` (not in
`structured-llm-test-models`, which is test-only codegen — ⚠ VERIFY item R1.11).
A judge whose completion fails SAP parsing after `structured-llm` retries surfaces
as a metric failure (R1.2 path), never a harness crash (R1.12). Out-of-range
precision/recall is clamped and flagged in `feedback` via `Constraint.check`
(R1.13).

**Export.** `EvaluationResult.toJson` produces a `formatVersion: 1` JSON object;
`EvaluationResult.fromJson` round-trips it (Ring 4 property). `toCsv` produces
id, score, feedback, outcome, meta columns. `I` / `O` payloads in JSON export use
caller-supplied `upickle.default.Writer[I]` / `[O]` — `Evaluate` itself is
codec-free (R1.8 DECISION: yes, require writers on export only).

**Effect discipline.** All public APIs are `F[_]`-polymorphic (`Async` bound on
`Evaluate`, `Applicative` on `Metric` helpers), matching `structured-llm`. No
`throw` (WartRemover): `EvalError` is a sealed enum `extends Throwable`, raised
via `F.raiseError`. No `scala.util.Random` without a seed — `EvalConfig.seed` is
explicit (reserved for future shuffling features; the harness itself is
deterministic given a pure program + metric).

## Correctness Risk Level

**Risk**: low-medium — the harness is pure orchestration over fs2 with no
irreversible API decisions (unlike Phase 0's erased surface). The real risk
concentrates in two places: (1) ordering-under-parallelism (R1.1 + R1.9 —
`parEvalMap` ordered vs unordered is a one-word mistake that silently breaks
devset-order guarantees), and (2) the `maxErrors` cancellation path (R1.3 —
in-flight work must actually cancel, observable via a probe). Both are covered by
deterministic Hedgehog properties. The export round-trip (R1.8) is a Ring 4
property. Judge prompt quality is a soft risk (mitigate by porting DSPy's
published SemanticF1 prompt shape as the starting text).

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, `-Wconf` exhaustiveness
      escalation (the new `EvalOutcome` and `EvalError` enums must be matched
      exhaustively everywhere from day one).
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover (`Any` exclusion
      interaction with `ujson.Value` in export, `throw` exclusion interaction
      with `EvalError extends Throwable` raised via `F.raiseError` — verify no
      bare `throw`), OrganizeImports.
- [ ] Ring 2: Architecture — new module `adk4s-eval` added to the module graph;
      `openspec/project.md` and `capability-profile.md` updated in this change.
      (Ring 2 is not a configured gate in this repo's profile; recorded for the
      apply phase to update the module graph artifacts.)
- [x] Ring 3: Property-based tests — MANDATORY. Hedgehog properties for: R1.1
      devset-order rows under parallelism; R1.2 failure-score substitution (one
      poisoned example among 100 → 99 real scores); R1.3 `maxErrors` abort +
      cancellation (Deferred-based probe); R1.4 mean aggregate + empty-devset
      edge; R1.6 feedback verbatim + no influence on aggregate; R1.7
      counting-metric (exactly one call per example); R1.9 determinism
      (parallelism ∈ {1, 2, 8} → equal results). **Concurrent behavior present**:
      parallel evaluation + cancellation. Scenarios use deterministic mocks
      (no wall-clock sleeps; `TestControl` if needed for cancellation timing).
- [x] Ring 4: Wire/persistence compatibility — `toJson` / `fromJson` round-trip
      with `formatVersion: 1` (R1.8); `toCsv` column contract. CSV is not
      round-tripped (write-only) but its column schema is asserted.
- [ ] Ring 5: Mutation testing — deferred unless `design.md` identifies a
      non-trivial helper. The harness is fs2 orchestration (low mutation value);
      the string metrics (`exactMatch`, `containsAll`, normalization) are
      candidates if they grow beyond one-liners. Stryker4s is available.
- [ ] Ring 6: Formal verification — NOT applicable. The harness is fs2
      orchestration over `F[_]` with no pure decision/fold/law at the centre
      that a PureScala mirror could prove better than the Ring 3 properties
      already do. The aggregate-mean and failure-substitution arithmetic is
      trivially correct by construction (one `foldLeft`). The `Trace.forPredictor`
      prefix filter is a one-line `filter` — no algorithm to verify. If
      `design.md` reveals a non-trivial pure kernel (e.g. a normalization
      state machine), Ring 6 is re-evaluated there.
- [ ] Ring 7: Model checking — NOT applicable (no distributed/event-driven
      invariants).
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY. Fresh-context
      reviewer checks every requirement against the implementation diff for:
      silent fallback mappings in failure handling (e.g. `case _ =>` swallowing
      `TooManyErrors`), `parEvalMapUnordered` instead of `parEvalMap` (breaks
      R1.1), feedback leaking into the aggregate (R1.6 violation), metric
      retried inside the harness (R1.7 violation), `trace` ever set to `Some`
      by the harness (R1.5 violation), judge parse failure crashing the harness
      instead of scoring `failureScore` (R1.12 violation), out-of-range
      precision/recall not clamped (R1.13 violation), `fromJson` not
      round-tripping `formatVersion` (R1.8 violation).
- [ ] Ring 9: Telemetry — NOT applicable (no telemetry stack detected).

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

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/eval-core/spec.md` | Full | Introduces a new module, new ADTs (`Example`, `Score`, `Metric`, `Trace`, `TraceEntry`, `EvalConfig`, `EvalOutcome`, `EvalRow`, `EvaluationResult`, `EvalError`), a new harness (`Evaluate`), export codecs (`toJson`/`fromJson`/`toCsv`), and built-in string metrics. The `Metric` signature is load-bearing for Phases 2–4 (pinned now so it never changes) — full typed contract is mandatory. |
| `specs/llm-judges/spec.md` | Full | Introduces `Judges.semanticF1` and `Judges.completeAndGrounded` as `Metric` instances backed by `StructuredLLM` programs over judge schemas, with the eval-vs-optimization toggle (binarize on `trace.isDefined`), constraint-clamp + feedback path, and the judge-schema definition strategy (⚠ VERIFY R1.11). Full typed contract is mandatory. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `StructuredLLM[F[_]]` | service trait | `org.adk4s.structured.core` | Judges call `complete` / `completeTemplate` to obtain structured judge completions; reused as-is, not extended. |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` | Judge output schemas via `Schema.instance(idl)(smithy4sSchema)` — same pattern as test models but hand-written in `adk4s-eval` (⚠ VERIFY R1.11). |
| `Prompt` | case class | `org.adk4s.structured.core` | Judge prompt construction; reused as-is. |
| `Constraint.check` / `Constraint.assert` | constraint API | `org.adk4s.structured.core` | R1.13 — clamp out-of-range precision/recall and flag in feedback; cite `constraint-validation` spec. |
| SAP (`SchemaAlignedParser`) | parser | `org.adk4s.structured.sap` | Judge completion parsing with lenient recovery; R1.12 — parse failure after retries surfaces as metric failure. |
| `StructuredTestFramework` | test framework | `org.adk4s.structured` | Its integration-test result type is the model for `EvalRow` — cite, don't duplicate. |
| `ujson.Value` | JSON value | (upickle/ujson, transitive) | `TraceEntry.input`/`output`, `EvaluationResult.toJson`, `Dataset.fromJsonl` — repo standard JSON, NOT circe. |
| `upickle.default` | serialization | (upickle, transitive) | `Writer[I]`/`Writer[O]` for JSON export (R1.8); `ReadWriter` for `fromJson` round-trip. |
| fs2 `Stream` / `parEvalMap` | streaming | `fs2` | Parallel evaluation with ordered results (R1.1) + scope cancellation (R1.3). |
| Hedgehog `HedgehogSuite` / `property` | property test kit | `hedgehog.munit` | All Ring 3 properties extend this (project convention; NOT ScalaCheck). |
| `munit.FunSuite` / `munit.CatsEffectSuite` | test framework | `munit`, `munit-cats-effect` | Test base classes (NOT ScalaTest). |
| `AdkError` conventions | sealed trait (error modeling pattern) | `org.adk4s.core.error` | Pattern reference for the new `EvalError` ADT — `EvalError` stands alone in `org.adk4s.eval` per the cross-phase convention (bridge to `AdkError` later); to be settled in `design.md`. |
| `Async` / `Applicative` | type classes | `cats.effect` / `cats` | `Evaluate` requires `Async[F]` (parallel + cancellation); `Metric` helpers require `Applicative[F]`. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `Example[I, O]` | case class | One evaluation datum: input, gold output, optional id, meta map. DSPy's `Example` with input-key marking implicit (gold is always the labeled output). |
| `Score` | case class | `value: Double` + optional `feedback: String`. DSPy's `Prediction(score, feedback)`. Factory helpers: `zero`, `bool`, `withFeedback`. |
| `TraceEntry` | case class | `(path: String, input: ujson.Value, output: ujson.Value)` — one predictor invocation record. Data only in Phase 1; capture is Phase 2/3. |
| `Trace` | case class | `entries: Vector[TraceEntry]` + `forPredictor(path): Trace` prefix filter. GEPA's `pred_trace` slicing. |
| `Metric[F, I, O]` | trait | `(Example[I, O], O, Option[Trace]) => F[Score]`. The `trace` argument toggles eval-vs-optimization behavior (DSPy's `trace is None` idiom). Pinned now — never changes. |
| `EvalConfig` | case class | `parallelism`, `failureScore`, `maxErrors`, `seed`. Defaults: parallelism 8, failureScore 0.0, maxErrors None (unlimited). |
| `EvalOutcome[+O]` | enum | `Succeeded(value: O)` / `Failed(error: Throwable)`. Per-example result container. |
| `EvalRow[I, O]` | case class | `(example, outcome, score)` — one row of the result matrix. |
| `EvaluationResult[I, O]` | case class | `score: Double` (mean) + `rows: Vector[EvalRow]` + `failures` + `toJson` + `toCsv`. |
| `EvalError` | enum (extends Throwable) | `TooManyErrors(count, max, partial)`. Stands alone in `org.adk4s.eval`. |
| `Evaluate` | object (factory) | `apply[F, I, O](program, devset, metric, config): F[EvaluationResult]` — the harness. |
| `Dataset` | object (factory) | `fromJsonl[I, O](path)(using Reader): Vector[Example[I, O]]` — JSONL dataset reader (~20 lines, CI needs it). |
| `Judges.semanticF1` | factory (Metric) | LLM-judge returning F1 of precision/recall; binarized on `trace.isDefined`. |
| `Judges.completeAndGrounded` | factory (Metric) | LLM-judge returning completeness/groundedness score; binarized on `trace.isDefined`. |
| `Metrics.exactMatch` / `Metrics.containsAll` | factory (Metric) | Built-in string metrics — no LLM call, pure `Applicative` metrics. |
| `eval-harness` (concept doc) | concept entry | `openspec/concepts/eval-harness.md` — the `Evaluate` harness semantics (failure-score, maxErrors, ordering, mean aggregate). |
| `metric-contract` (concept doc) | concept entry | `openspec/concepts/metric-contract.md` — the `Metric` trait, trace-toggle semantics, feedback channel. Phase 3 cites this. |

## Risks and Mitigations

- **Ordering under parallelism** (R1.1) — using `parEvalMapUnordered` instead of
  `parEvalMap` is a one-word mistake that silently breaks devset-order
  guarantees. *Mitigation*: R1.1 Hedgehog property asserts
  `rows.map(_.example.id) == devset.map(_.id)` under randomized latency;
  adversarial review (R8) explicitly checks for `Unordered`.
- **`maxErrors` cancellation correctness** (R1.3) — in-flight work must actually
  cancel when the error cap is hit, not just stop dispatching new work.
  *Mitigation*: R1.3 test uses a `Deferred`-based probe that asserts the
  cancelled fiber observes cancellation; fs2 scope cancellation is the
  mechanism, not a manual `Ref` flag.
- **Feedback leaking into the aggregate** (R1.6) — a bug where `Score.feedback`
  influences the mean would silently corrupt optimization in Phase 3.
  *Mitigation*: R1.6 adversarial scenario — two runs identical except feedback
  strings → identical `score`; Ring 8 checks the aggregate code path.
- **Metric retried inside the harness** (R1.7) — wrapping the metric call in a
  retry would double-count LLM-judge calls and break cost accounting.
  *Mitigation*: R1.7 counting-metric property asserts exactly `devset.size`
  calls; Ring 8 checks for any `.retry` / `Retry` around the metric invocation.
- **Judge schema definition strategy** (R1.11 ⚠ VERIFY) —
  `structured-llm-test-models` is test-only codegen; judges are production code.
  *Mitigation*: hand-written `Schema.instance` definitions in `adk4s-eval`
  (recommendation); alternatively a small Smithy codegen area inside
  `adk4s-eval`. Settle in `design.md` after checking how `structured-llm` wires
  smithy4s codegen in `build.sbt`.
- **Judge prompt quality** — DSPy's SemanticF1 prompt is the product of years of
  iteration; a from-scratch prompt may score poorly. *Mitigation*: port DSPy's
  published SemanticF1 prompt shape as the starting text (it is in their repo);
  document that defaults are not battle-calibrated and ship conservative
  thresholds.
- **`EvalError` vs `AdkError`** — the cross-phase convention recommends standing
  alone in-module (bridge later). *Mitigation*: `EvalError` extends `Throwable`
  and is raised via `F.raiseError`; settle the `AdkError` bridge decision in
  `design.md`.
- **Export codec-freeness** (R1.8 DECISION) — requiring `Writer[I]`/`Writer[O]`
  on export only keeps `Evaluate` itself codec-free, but means callers without
  upickle writers cannot export. *Mitigation*: accept the trade-off (recommendation:
  yes); `fromJson` requires the corresponding `Reader` and is opt-in.
