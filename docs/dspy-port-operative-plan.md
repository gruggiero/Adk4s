# DSPy Port — Operative Plan (Phases 0–4 → OpenSpec changes)

*Originally 2026-07-23; updated 2026-08-12. Phase 0 (`add-optimizable-surface`) and Phase 1 (`add-eval-core`) are **implemented and archived** (both 2026-08-01). Their sections are marked ✅ and annotated with implementation notes (deviations from the original seeds, resolved DECISION items, actual file locations). Phases 2–4 are updated to reflect the current codebase state, including the `AgentMiddleware`/`HarnessAgent`/`HarnessState` system added by `add-harness-api-phase0` (archived 2026-08-12). Operative companion to `dspy-port-analysis.md`. One section per phase; each section contains everything needed to author the corresponding OpenSpec change: proposed change id, capability specs, scope, typed contracts (full Scala signatures), SHALL-form requirement seeds with scenarios, error paths, concepts reused/introduced, ring strategy, ⚠ VERIFY items, exit criteria, and risks. Requirement seeds are numbered `R<phase>.<n>` so delta specs can cite them.*

---

## How to use this document

Each phase becomes **one OpenSpec change** (Phase 2 contains four capability specs; all others one or two). The requirement seeds below are written in the repo's spec-lint dialect — normative SHALL/MUST statement first, then Given/When/Then — but they are *seeds*: the spec author expands each into full scenarios, adds the adversarial scenarios required by the "only/never/must not" rule, and resolves every `⚠ VERIFY` item against source before freezing the delta spec. Signatures given here are the intended typed contracts; where a decision is still open it is marked **DECISION** with the options and a recommendation, to be settled in the change's `design.md`.

**Phase dependency graph** (arrows = "must be archived before"):

```
Phase 0 (spike: erased surface) ✅ archived 2026-08-01
   ├──► Phase 2 (compiler MVP)  ──► Phase 3 (instruction optimization) ──► Phase 4 (integration)
Phase 1 (eval core) ──┘ ✅ archived 2026-08-01    ▲
   └────────────── (judges reused) ──────────┘
```

Phase 0 and Phase 1 are **done**. Phase 2 is the next change to author. Phase 1 was built in parallel with Phase 0 (no dependency) — both are now available as dependencies for Phase 2.

---

## Cross-phase conventions (apply to every change)

**Modules and packages.**

| Module | Package | Depends on | Status |
|---|---|---|---|
| `adk4s-optimize` | `org.adk4s.optimize` | `structured-llm`, `verified` (Test scope) | ✅ Phase 0 — skeleton + `Optimizable` surface + `OptimizerLaws` testkit. Phase 2 adds `adk4s-eval` dependency + real optimizers. |
| `adk4s-eval` | `org.adk4s.eval` | `structured-llm` (judges), cats-effect, fs2 | ✅ Phase 1 — `Evaluate`/`Metric`/`Example`/`Judges`/`Dataset`. MUST NOT depend on `adk4s-core`, `adk4s-orchestration`, `adk4s-optimize` (Ring 2 purity rule). |

Signature/adapter work lands **inside `structured-llm`** (package `org.adk4s.structured.signature` and `org.adk4s.structured.adapter`) because it generalizes `PromptTemplate`/`OutputFormatOptions`. The completion cache lands in **`adk4s-core`** (`org.adk4s.core.cache`) as `ChatModel` middleware. Examples go to `adk4s-examples/.../optimize/` with `run-example.sh` keys. Update the module graph in `openspec/project.md` and `capability-profile.md` in whichever change first adds a module.

> **Note (2026-08-12):** The `adk4s-optimize` module currently depends only on `structured-llm` and `verified` (Test scope) — it does **not** yet depend on `adk4s-eval`. The `adk4s-eval` dependency is added in Phase 2 when `BootstrapFewShot`/`BootstrapRS` consume `Evaluate` for candidate scoring. The original plan's "depends on `adk4s-core`" was dropped: `adk4s-optimize` stays decoupled from `adk4s-core` to keep the optimizer layer pure (the `OptimizeError` ADT stands alone, not extending `AdkError`).

**Effect discipline.** All new public APIs are `F[_]`-polymorphic (`Async`/`Concurrent` bounds, matching `structured-llm`), **not** IO-hardcoded like `Runnable`. No `throw` (WartRemover): error channels are `F.raiseError` with the typed ADTs defined per phase (`EvalError`, `OptimizeError`, `SignatureError`) hung off `AdkError` conventions — `⚠ VERIFY` whether new errors should extend `org.adk4s.core.error.AdkError` or stand alone (recommendation: stand alone in their module; bridge later).

**JSON.** upickle/ujson everywhere (repo standard — no circe). Type-erased demo/trace payloads are `ujson.Value`. Canonical serialization for cache keys is defined in Phase 2 (sorted object keys, no insignificant whitespace, UTF-8, SHA-256).

**Testing.** munit + munit-cats-effect + Hedgehog properties (Ring 3 mandatory for every library change). Every optimizer gets a **laws suite** (`OptimizerLaws`, defined in Phase 0) run through the testkit pattern established by `AgentMemoryLaws`. Deterministic seeds: every stochastic component takes an explicit `seed: Long`; no `scala.util.Random` without a seed (adversarial review item in every phase).

**LLM in tests.** Mock `ChatModel`/`StructuredLLM` by default; the mock must respond *from the prompt content* (echo/scan conventions per the cross-run-memory precedent), never a canned script detached from input. Optional real-LLM smoke behind `OPENAI_API_KEY` per existing convention.

**Exhaustiveness.** `-Wconf` escalates inexhaustive matches to errors: any new sealed hierarchy (e.g. `EvalOutcome`, `OptimizeEvent`) must be matched exhaustively everywhere from day one (Ring 0 consequence).

---

# Phase 0 — Spike: the erased predictor surface ✅ DONE

**Change id:** `add-optimizable-surface` · **Capability specs:** `optimizable-surface` (1 spec) · **Effort:** 1–1.5 pw · **Risk:** medium (design-freezing) · **Rings:** R0, R1, R3, R8 · **Status:** Archived 2026-08-01

> **Implementation notes (2026-08-12):** All artifacts are in `openspec/changes/archive/2026-08-01-add-optimizable-surface/`. The implemented code is in `adk4s-optimize/src/main/scala/org/adk4s/optimize/` and the `verified` module (`verified/src/main/scala/org/adk4s/verified/PredictorKernel.scala`). Key resolved DECISION items and deviations from the seeds below:
> - **R0.4 DECISION resolved:** `update` is total on valid paths and raises `OptimizeError` on invalid ones; `updateEither` is the total (safe) variant returning `Either[OptimizeError, P]`. Both are exposed. The raising `update` has a targeted `@SuppressWarnings(Array("org.wartremover.warts.Throw"))`.
> - **R0.2 DECISION resolved:** `Vector` recursion is supported (segment = index as string); `Map` recursion is deferred to Phase 3 as recommended.
> - **VERIFY item 1 resolved:** `Mirror` + `inline` derivation works on Scala 3.8.4 for the mixed leaf/subtree/Vector rule. The implementation uses `summonFrom` at the top of each inline match to resolve typeclass instances at the inline expansion site.
> - **VERIFY item 2 resolved:** `HasPredictorState` is a separate trait (not `Predictor[F]` supertrait), matching the recommendation.
> - **VERIFY item 3 resolved:** `frozen` is per-leaf only; subtree freezing = freezing all leaves (done by `CompiledState.freeze` in Phase 2).
> - **Deviation — Ring 6 added:** The original plan listed R0, R1, R3, R8. The implementation added Ring 6 (Stainless formal verification) via a `verified` module containing `PredictorKernel.scala` — a PureScala model mirroring the `Optimizable` traversal algorithm. The `verified` module is pinned to Scala 3.7.2 for Stainless and is a Test-scope dependency of `adk4s-optimize`.
> - **`OptimizeError` stands alone:** Per the cross-phase convention, `OptimizeError` extends `Throwable` but does NOT extend `AdkError` — keeping `adk4s-optimize` decoupled from `adk4s-core`.
> - The surface API is declared **frozen** in the change's `design.md`: any later change requires its own proposal.

## Why

Every optimizer needs to (a) enumerate the tunable LM-call sites inside an arbitrary user program, (b) read/replace their instructions and demos as plain data, and (c) produce a *new* program value with one site updated — without knowing the program's concrete types. Python DSPy does this with runtime reflection (`self.__dict__` walking); Scala needs a designed surface. This surface is load-bearing for Phases 2–4 and effectively irreversible once optimizers build on it, so it is spiked and frozen first, with **two structurally different toy optimizers as the acceptance test**.

## Scope

**In:** `adk4s-optimize` module skeleton; `PredictorState`, `PredictorPath`, `Optimizable[P]` typeclass + `Mirror`-based derivation; a *minimal placeholder* `Predict0[F, I, O]` (thin wrapper over `StructuredLLM.completeTemplate`, enough to carry state — replaced by the real `Predict` in Phase 2); `OptimizerLaws` testkit; two toy optimizers (`UppercaseInstructions`, `StaticDemoInjector`) used only as derivation exercisers; a two-predictor toy program in tests.

**Out:** real adapters, demo rendering into prompts (state is carried but not yet consumed), any real optimizer, trace capture, save/load (state shape must be *serializable-ready* but persistence is Phase 2).

## Typed contract (the core of the change)

```scala
package org.adk4s.optimize

/** Pure, serializable-ready view of one LM-call site's tunable state. */
final case class Demo(input: ujson.Value, output: ujson.Value)

final case class PredictorState(
  instructions: String,
  demos: Vector[Demo],
  frozen: Boolean
)

/** Stable address of a predictor inside a program. Segments are case-class
  * field names, outermost first, e.g. PredictorPath("rag", "answer"). */
final case class PredictorPath(segments: Vector[String]):
  def render: String = segments.mkString(".")   // used in traces, save files, logs

/** The optimizer-facing capability of a program type P. */
trait Optimizable[P]:
  def predictors(p: P): Vector[(PredictorPath, PredictorState)]
  def update(p: P, path: PredictorPath, f: PredictorState => PredictorState): P
  /** update over every non-frozen predictor */
  def updateAll(p: P, f: (PredictorPath, PredictorState) => PredictorState): P

object Optimizable:
  inline def derived[P <: Product](using m: Mirror.ProductOf[P]): Optimizable[P]
  def apply[P](using o: Optimizable[P]): Optimizable[P] = o

/** Anything that IS a predictor exposes its state; Predict implements this. */
trait HasPredictorState[Self]:
  def state(self: Self): PredictorState
  def withState(self: Self, s: PredictorState): Self
```

Derivation rule: for a case class `P`, each field that has a `HasPredictorState` instance contributes a leaf (path = field name); each field that has an `Optimizable` instance contributes its subtree with the field name prepended; other fields are ignored. Nested `Vector`/`List`/`Map[String, *]` of predictors: **DECISION** — support in Phase 0 (index/key becomes a path segment) or defer to Phase 3. Recommendation: support `Vector` (segment = index as string) now, defer `Map`; DSPy's tree walk recurses into both and multi-step programs will want collections.

Error ADT: `enum OptimizeError extends Throwable: case UnknownPath(path: PredictorPath); case FrozenPath(path: PredictorPath)`.

## Requirement seeds

- **R0.1** `Optimizable.derived` SHALL enumerate every predictor field of a case class in declaration order, with paths equal to the field names. *Given* a case class with predictors `a`, `b` and a non-predictor field, *when* `predictors` is called, *then* exactly `[(a, stateA), (b, stateB)]` is returned in that order.
- **R0.2** `predictors` SHALL recurse into fields that are themselves `Optimizable` products and into `Vector`s of predictors, prefixing paths. *Then* a nested predictor at `outer.inner` appears with `PredictorPath(Vector("outer","inner"))`; element 1 of a vector field `steps` appears as `steps.1`.
- **R0.3** `update` SHALL return a new program value with only the addressed predictor's state changed; the input program value SHALL NOT be observably modified (purity). *Adversarial scenario:* capture `predictors(p)` before and after `update` — the "before" snapshot is unchanged.
- **R0.4** `update` with a path not present in the program MUST raise (or return — **DECISION**: `F`-less pure API returns `Either[OptimizeError, P]`? recommendation: `update` is total on valid paths and the *safe* variant `updateEither` is the public one; spec-lint will demand the error path either way) `OptimizeError.UnknownPath`.
- **R0.5** A predictor whose state has `frozen = true` SHALL be excluded from `updateAll` and SHALL cause `updateEither` on its exact path to fail with `FrozenPath`; it SHALL still appear in `predictors` output (read access) with `frozen = true` visible. *Adversarial:* an optimizer that ignores the flag and calls `updateEither` on a frozen path gets the error, not silent success.
- **R0.6** Round-trip law: `update(p, path, identity) == p` on every enumerated path (value equality of the enumerated states; program equality if `P` is a case class of case classes).
- **R0.7** The two toy optimizers SHALL compile the same two-predictor toy program through the *same* `Optimizable` instance without any type-level knowledge of the program: one rewrites every instruction string, the other appends a fixed `Demo` to every predictor. Exit is both green under `OptimizerLaws`.
- **R0.8 (OptimizerLaws, shipped as testkit)** For every optimizer `opt: P => F[P]` claiming law-compliance: (a) the student value passed in is unchanged after compile; (b) frozen predictors' states are bit-identical in the result; (c) the result has the same predictor path set as the student (optimizers tune state, never structure).

## Concepts

Reused: `StructuredLLM`, `PromptTemplate` (placeholder Predict0 only), Hedgehog. Introduced (new `openspec/concepts/` entries): `optimizable-surface.md` (the typeclass + derivation semantics), `predictor-state.md`.

## ⚠ VERIFY / open design items for `design.md`

1. Scala 3.8.4 `Mirror` + `inline` derivation ergonomics for the mixed leaf/subtree rule — prototype before spec freeze; fallback is a macro-free "givens summonInline chain".
2. Whether `HasPredictorState` should instead be `Predictor[F]` supertrait — check interaction with `-source:future` and WartRemover `Any` exclusions.
3. `updateAll` semantics when a nested subtree is *entirely* frozen (frozen at what granularity? recommendation: `frozen` is per-leaf only; subtree freezing = freezing all leaves, done by `CompiledState.freeze` in Phase 2).

## Exit criteria

Both toy optimizers pass `OptimizerLaws` against the toy program; a *third* program shape (predictor inside a `Vector`) round-trips; the surface API is declared frozen in the change's `design.md` (any later change to it requires its own proposal).

---

# Phase 1 — Eval core (`adk4s-eval`) ✅ DONE

**Change id:** `add-eval-core` · **Capability specs:** `eval-core`, `llm-judges` (2 specs) · **Effort:** 4–5 pw · **Risk:** low-medium · **Rings:** R0, R1, R3, R4 (CSV/JSON export round-trip), R8 · **Status:** Archived 2026-08-01

> **Implementation notes (2026-08-12):** All artifacts are in `openspec/changes/archive/2026-08-01-add-eval-core/`. The implemented code is in `adk4s-eval/src/main/scala/org/adk4s/eval/`. Key resolved DECISION items and deviations from the seeds below:
> - **R1.8 DECISION resolved:** `toJson`/`fromJson` require `upickle.default.Writer[I]`/`Writer[O]` on export and `Reader[I]`/`Reader[O]` on import only — `Evaluate` itself is codec-free. The JSON export includes `formatVersion: 1`.
> - **R1.11 VERIFY resolved:** Judge schemas are hand-written `Schema.instance` definitions in `adk4s-eval/src/main/scala/org/adk4s/eval/Judges.scala` (NOT in `structured-llm-test-models`, which is test-only codegen). Each judge schema has a Smithy IDL string for prompt injection and a hand-written `smithy4s.schema.Schema` for JSON decoding. This compiles without the smithy4s-sbt-codegen plugin — matching the recommendation.
> - **Two judges shipped:** `Judges.semanticF1` (F1 from precision/recall, ported from DSPy commit 2974a655) and `Judges.completeAndGrounded` (average of completeness/groundedness, written from scratch following DSPy's structure). Both use `Constraint.check` for out-of-range clamping (R1.13) and the `trace.isDefined` binarization toggle (R1.10).
> - **`EvalError` stands alone:** `EvalError` extends `Throwable` but does NOT extend `AdkError` — keeping `adk4s-eval` decoupled from `adk4s-core` (same pattern as `OptimizeError`).
> - **`parEvalMap` (ordered) used** for evaluation, not `parEvalMapUnordered` — matching the R1.1 ordering requirement and the risk mitigation.
> - **`Dataset.fromJsonl` shipped** as a 20-line JSONL reader in `adk4s-eval/src/main/scala/org/adk4s/eval/Dataset.scala`.
> - **Built-in metrics** in `Metrics.scala`: `exactMatch` and `containsAll` — both `Applicative[F]`-bound (no `Async` needed).

## Why

ADK4S has no way to answer "did this prompt/model/agent change make results better or worse?" The eval harness is independently valuable (regression testing, CI scoring, model comparison) and is the substrate every Phase-2/3 optimizer consumes. Mirrors DSPy's `Example`/metric-contract/`Evaluate` with the documented semantics: failures score `failureScore` instead of aborting; the metric's optional `Trace` argument toggles eval-vs-optimization behavior; the `feedback` channel rides along inertly until GEPA-style optimizers read it.

## Scope

**In:** module `adk4s-eval`; `Example`, `Score`, `Metric`, `Trace`/`TraceEntry` (data types only — *capture* is Phase 2/3), `Evaluate` harness, `EvaluationResult` + CSV/JSON export, built-in string metrics (`exactMatch`, `containsAll`, normalized text helpers), two LLM-judges (`SemanticF1`-style, `CompleteAndGrounded`-style) as `structured-llm` programs, one runnable example + smoke test.

**Out:** any optimizer; trace *capture* (types only, so the `Metric` signature is final from day one); dataset loaders beyond an in-memory `Vector` + JSONL reader (`Dataset.fromJsonl` is in — it's 20 lines and CI needs it); result visualization.

## Typed contract

```scala
package org.adk4s.eval

final case class Example[I, O](input: I, gold: O, id: Option[String] = None, meta: Map[String, String] = Map.empty)

final case class Score(value: Double, feedback: Option[String] = None)
object Score:
  val zero: Score
  def bool(b: Boolean): Score                       // 1.0 / 0.0
  def withFeedback(v: Double, fb: String): Score

final case class TraceEntry(path: String, input: ujson.Value, output: ujson.Value)
final case class Trace(entries: Vector[TraceEntry]):
  def forPredictor(path: String): Trace             // prefix filter — GEPA's pred_trace

trait Metric[F[_], I, O]:
  /** trace = None → plain evaluation; Some → optimizer context (DSPy's `trace is None` idiom). */
  def apply(gold: Example[I, O], pred: O, trace: Option[Trace]): F[Score]
object Metric:
  def fromPredicate[F[_]: Applicative, I, O](f: (Example[I, O], O) => Boolean): Metric[F, I, O]
  def fromDouble[F[_]: Applicative, I, O](f: (Example[I, O], O) => Double): Metric[F, I, O]
  // instances: contramap on I/O, thresholded (binarize when trace.isDefined — the judge pattern)

final case class EvalConfig(
  parallelism: Int = 8,
  failureScore: Double = 0.0,
  maxErrors: Option[Int] = None,        // None = unlimited; Some(n): abort after n program/metric failures
  seed: Long = 0L                       // reserved: shuffling in future harness features
)

enum EvalOutcome[+O]:
  case Succeeded(value: O)
  case Failed(error: Throwable)         // program raised; scored failureScore

final case class EvalRow[I, O](example: Example[I, O], outcome: EvalOutcome[O], score: Score)

final case class EvaluationResult[I, O](score: Double, rows: Vector[EvalRow[I, O]]):
  def failures: Vector[EvalRow[I, O]]
  def toJson: ujson.Value               // schema versioned, see R1.8
  def toCsv: String                     // id, score, feedback, outcome, meta columns

enum EvalError extends Throwable:
  case TooManyErrors(count: Int, max: Int, partial: Vector[?])

object Evaluate:
  def apply[F[_]: Async, I, O](
    program: I => F[O],
    devset: Vector[Example[I, O]],
    metric: Metric[F, I, O],
    config: EvalConfig = EvalConfig()
  ): F[EvaluationResult[I, O]]
```

## Requirement seeds — `eval-core`

- **R1.1** `Evaluate` SHALL run `program` on every example with concurrency ≤ `config.parallelism` and SHALL return rows **in devset order** regardless of completion order. *Given* parallelism 4 and a program with randomized latency, *then* `rows.map(_.example.id) == devset.map(_.id)`.
- **R1.2** When `program` raises for an example, `Evaluate` SHALL record `EvalOutcome.Failed`, assign `Score(config.failureScore)`, and continue with the remaining examples. Same for a *metric* that raises. *Adversarial:* one poisoned example among 100 → 99 real scores present; aggregate reflects the failure score, no exception escapes.
- **R1.3** When failures exceed `maxErrors = Some(n)`, `Evaluate` MUST raise `EvalError.TooManyErrors` carrying the count and the partial rows collected so far, and MUST cancel in-flight work (fs2 scope cancellation — observable via a `Deferred`-based probe in the test).
- **R1.4** The aggregate `score` SHALL be the arithmetic mean of row scores (including substituted failure scores); the empty devset SHALL yield `score = 0.0` with empty rows (not an error).
- **R1.5** `Evaluate` SHALL pass `trace = None` to the metric (the harness never runs in optimizer mode); the `Metric` contract SHALL document that optimizers pass `Some(trace)` — pinned now so the signature never changes.
- **R1.6** `Score.feedback` SHALL be preserved verbatim into rows and exports and SHALL NOT influence the aggregate. *Adversarial:* two runs identical except feedback strings → identical `score`.
- **R1.7** Metric calls SHALL each be invoked exactly once per example (no retry inside the harness). *Adversarial:* counting metric, assert count == devset size.
- **R1.8** `toJson`/`toCsv` SHALL round-trip: `EvaluationResult` JSON export includes a `formatVersion: 1` field; a provided `EvaluationResult.fromJson` re-reads what `toJson` wrote (Ring 4 property; `I`/`O` payloads exported via caller-supplied writers — **DECISION**: require `upickle.default.Writer[I]/[O]` on export only, keeping `Evaluate` itself codec-free. Recommendation: yes).
- **R1.9** Determinism: with a pure program and metric, two runs over the same devset SHALL produce equal `EvaluationResult`s regardless of `parallelism` (Hedgehog property over parallelism ∈ {1, 2, 8}).

## Requirement seeds — `llm-judges`

- **R1.10** `Judges.semanticF1[F](llm: StructuredLLM[F], threshold: Double = 0.66)` SHALL return a `Metric` that (a) obtains `precision`/`recall` in `[0,1]` from a Smithy-schema'd judge completion, (b) returns the F1 as `Score.value` with the judge's stated reasoning as `feedback` when `trace.isEmpty`, and (c) returns the *binarized* `Score(if f1 >= threshold then 1.0 else 0.0)` when `trace.isDefined` — the eval-vs-optimization toggle. Both branches need scenarios.
- **R1.11** Judge schemas SHALL be defined in `structured-llm-test-models`… **⚠ VERIFY**: that module is test-only codegen; judges are *production* code → either a new small `adk4s-eval-models` Smithy area inside `adk4s-eval` (recommendation — check how `structured-llm` wires smithy4s codegen in `build.sbt` and replicate) or hand-written `Schema.instance` definitions.
- **R1.12** A judge whose completion fails SAP parsing after `structured-llm` retries SHALL surface as a metric failure (→ R1.2 failure-score path), never as a harness crash.
- **R1.13** Judge constraint: `precision`/`recall` outside `[0,1]` SHALL be clamped and flagged in `feedback` (uses existing `Constraint.check` — cite `constraint-validation` spec).

## Concepts

Reused: `StructuredLLM`, `Schema`, `Constraint`, `Prompt`, SAP, `StructuredTestFramework` (its integration-test result type is the model for `EvalRow` — cite, don't duplicate), fs2, Hedgehog. Introduced: concepts `eval-harness.md`, `metric-contract.md` (the trace-toggle and feedback-channel semantics live here — Phase 3 cites them).

## Exit criteria

Example `EvalHarnessExample` scores a mock extraction program on a 20-example JSONL devset with one rule metric and one judge, exports CSV+JSON; all Ring 3 properties green incl. R1.9 determinism; README gains an *Evaluation* section.

## Risks

Judge prompt quality (mitigate: port DSPy's published SemanticF1 prompt shape as the starting text; it's in their repo); ordering-under-parallelism subtleties (mitigate: `parEvalMap` (ordered) not `parEvalMapUnordered`, R1.1 property).

---

# Phase 2 — The compiler MVP

**Change id:** `add-prompt-compiler-mvp` · **Capability specs (4):** `signature-as-data`, `adapter-demo-rendering`, `bootstrap-fewshot`, `compiled-state-and-cache` · **Effort:** 6–8 pw · **Risk:** medium-high (refactor touches structured-llm's public surface) · **Rings:** R0, R1, R2 (module graph changes), R3, R4 (save/load + cache persistence), R5 candidate (bootstrap demo-slot logic is mutation-testing-worthy), R8 · **Status:** Not started — next change to author

> **Update (2026-08-12):** Phase 0 and Phase 1 are done, so this change builds on:
> - `adk4s-optimize` — `Demo`, `PredictorState`, `PredictorPath`, `HasPredictorState`, `Optimizable`, `OptimizeError`, `OptimizerLaws`, `Predict0` (placeholder). The `adk4s-optimize` module must gain an `adk4s-eval` dependency for `BootstrapRS` candidate scoring.
> - `adk4s-eval` — `Example`, `Metric`, `Score`, `Evaluate`, `EvaluationResult`, `Trace`, `TraceEntry`. The `Trace`/`TraceEntry` types are already defined here; Phase 2's `TraceCollector` (Spec 2a) should reuse them, not duplicate.
> - The `AgentMiddleware.wrapModelCall` hook (from `add-harness-api-phase0`) is now available as an alternative trace-capture vehicle — see Spec 2a's trace note.

> **Deviation from the analysis doc, made explicit:** minimal trace capture moves *into* Phase 2, because `BootstrapFewShot` on multi-predictor programs requires (predictor, input, output) triples to mint demos. Phase 2 ships a Ref-based collector wired through `Predict`; Phase 3 only *generalizes* it (ambient capture, per-predictor metric slicing).

## Spec 2a — `signature-as-data` (in `structured-llm`)

### Typed contract

```scala
package org.adk4s.structured.signature

final case class FieldMeta(name: String, typeLabel: String, description: Option[String])

/** Renders a typed input as named field values for the adapter. Mirror-derived. */
trait InputEncoder[I]:
  def fields: Vector[FieldMeta]
  def encode(i: I): Vector[(String, ujson.Value)]
object InputEncoder:
  inline def derived[I <: Product](using Mirror.ProductOf[I]): InputEncoder[I]
  given InputEncoder[String]   // single anonymous field "input"

final case class Signature[I, O](
  instructions: String,
  inputEncoder: InputEncoder[I],
  outputSchema: Schema[O],
  demos: Vector[Demo] = Vector.empty,        // org.adk4s.optimize.Demo — ⚠ VERIFY module direction, see below
  frozen: Boolean = false
):
  def withInstructions(s: String): Signature[I, O]
  def appendInstructions(s: String): Signature[I, O]   // joined with blank line (DSPy semantics)
  def withDemos(d: Vector[Demo]): Signature[I, O]
  def freeze: Signature[I, O]

object Signature:
  def apply[I: InputEncoder, O: Schema](instructions: String): Signature[I, O]

final class Predict[F[_]: Async, I, O](
  val signature: Signature[I, O],
  llm: StructuredLLM[F],
  adapter: Adapter = SmithyJsonAdapter.default
):
  def run(input: I): F[O]
  def runTraced(input: I, collector: TraceCollector[F], path: PredictorPath): F[O]
  def withSignature(s: Signature[I, O]): Predict[F, I, O]
// Predict gets HasPredictorState via signature.{instructions,demos,frozen}

/** ChainOfThought: same signature, adapter-level two-part output. */
final case class WithReasoning[O](reasoning: String, value: O)
object ChainOfThought:
  def apply[F[_]: Async, I, O](p: Predict[F, I, O]): Predict[F, I, WithReasoning[O]]

trait TraceCollector[F[_]]:
  def record(e: TraceEntry): F[Unit]
object TraceCollector:
  def ref[F[_]: Concurrent]: F[(TraceCollector[F], F[Trace])]
```

**Module-direction issue (⚠ VERIFY / DECISION — still open for Phase 2):** `Demo`/`PredictorState`/`PredictorPath`/`HasPredictorState` were introduced in `adk4s-optimize` (Phase 0, now frozen), but `structured-llm` cannot depend on `adk4s-optimize` (would invert the graph — `adk4s-optimize` depends on `structured-llm`). Resolution options: (a) move the four pure data types into `structured-llm` during this change, with `adk4s-optimize` re-exporting them (the Phase 0 surface is frozen on *API shape*, not on which module hosts the types — moving the types and re-exporting preserves the frozen API); (b) keep `Signature.demos: Vector[(ujson.Value, ujson.Value)]` raw and avoid the cross-module dependency. **Recommendation: (a) — move `Demo`, `PredictorState`, `PredictorPath`, `HasPredictorState` into `structured-llm`; `Optimizable` + derivation stay in `adk4s-optimize`.** State this explicitly in the proposal's What Changes. The `Trace`/`TraceEntry` types already live in `adk4s-eval` and should be reused as-is (do not move them — `structured-llm` already cannot depend on `adk4s-eval`; instead, `TraceCollector` can be defined in `adk4s-optimize` which depends on both, or `TraceEntry` can be a local type in `structured-llm` with a bridge).

### Requirement seeds

- **R2.1** `Signature` mutation methods SHALL be pure copies; `PromptTemplate` and the `prompt"..."` interpolator SHALL be retained and adapted to *produce* a `Signature` (or documented as legacy sugar — **DECISION** for design.md; either way no existing example may break, Ring 2 checks `adk4s-examples` compiles unchanged).
- **R2.2** `InputEncoder.derived` SHALL emit fields in case-class declaration order, and the adapter SHALL render them in that order (field-order-is-prompt-order, load-bearing per DSPy). *Then:* reordering two case-class fields observably reorders the rendered prompt sections.
- **R2.3** `Predict.run` SHALL render instructions + demos + encoded input through its adapter, call the LLM, and parse via the adapter (SAP path), preserving today's `completeTemplate` behavior when `demos.isEmpty` (golden-prompt regression test against current output — byte-stable except the schema block's documented delimiters).
- **R2.4** `Predict.runTraced` SHALL record exactly one `TraceEntry(path.render, encodedInput, outputJson)` per successful call, and none on failure; `run` SHALL record nothing. *Adversarial:* failure path leaves collector empty.
- **R2.5** `ChainOfThought` SHALL prepend a reasoning section to the output contract at the *adapter* level (free-text section + schema block), parse both, and return `WithReasoning(reasoning, value)`; the wrapped predictor's `Signature.instructions` SHALL be unchanged (module supplies strategy, signature supplies contract — DSPy's separation). Missing reasoning section in the completion → `reasoning = ""` + SAP proceeds on the JSON block (lenient), scenario required.
- **R2.6** `Predict` SHALL satisfy `HasPredictorState`: `withState` replaces instructions/demos/frozen and is observable on the next `run`'s rendered prompt (property: instructions string appears in prompt).

## Spec 2b — `adapter-demo-rendering` (in `structured-llm`)

### Typed contract

```scala
package org.adk4s.structured.adapter

trait Adapter:
  def format[I, O](sig: Signature[I, O], input: I): Prompt
  def parse[O](schema: Schema[O], raw: String): Either[StructuredLLMError.ParseFailed, O]

object SmithyJsonAdapter:                 // default; today's behavior + demos
  def default: Adapter
  def apply(options: OutputFormatOptions): Adapter
final class MarkerAdapter(...) extends Adapter          // [[ ## field ## ]] style
final class TwoStepAdapter[F[_]](main: Adapter, extractor: StructuredLLM[F]) // ⚠ shape: needs F — see R2.10
```

### Requirement seeds

- **R2.7** Demo rendering: each `Demo` SHALL become one user message (input fields rendered exactly as a live input would be) followed by one assistant message (the output JSON as the model is expected to produce it), inserted after the system/instructions message and before the live input, preserving demo order. *Adversarial:* 0 demos → prompt structurally identical to today's.
- **R2.8** `SmithyJsonAdapter.parse` SHALL delegate to `SchemaAlignedParser` unchanged (no second parse path — cite `type-aware-sap-coercion` spec).
- **R2.9** `MarkerAdapter` SHALL render one `[[ ## name ## ]]`-delimited section per output field and parse by marker regex, falling back to `SmithyJsonAdapter.parse` on marker mismatch (DSPy's ChatAdapter→JSON fallback), with the fallback **off** switchable for tests. Scenario for: clean parse, fallback taken, both fail → `ParseFailed` carrying both errors.
- **R2.10** `TwoStepAdapter` SHALL send the task instructions *without* format constraints to the main model, then run the extractor `StructuredLLM` over the free-form completion against `Schema[O]`. Because `Adapter.parse` is pure and two-step needs an effect, **DECISION**: model as `Predict`-level strategy (`Predict.twoStep(extractor)`) instead of an `Adapter` — recommendation: yes, keep `Adapter` pure; spec the strategy on `Predict`.
- **R2.11** Adapter choice SHALL be per-`Predict` construction (no global/default mutable configuration — explicitly *not* porting `dspy.settings`; adversarial scenario: two Predicts with different adapters in one program don't interfere).

## Spec 2c — `bootstrap-fewshot` (in `adk4s-optimize`)

### Typed contract

```scala
package org.adk4s.optimize

final case class BootstrapConfig(
  maxBootstrapped: Int = 4, maxLabeled: Int = 16, maxRounds: Int = 1,
  metricThreshold: Option[Double] = None,        // None → truthy = score > 0
  maxErrors: Option[Int] = None, seed: Long = 0L
)

trait ProgramRunner[F[_], P, I, O]:      // how an optimizer executes a candidate program
  def run(p: P, input: I, collector: TraceCollector[F]): F[O]

final class LabeledFewShot[F[_]: Async](k: Int = 16, seed: Long = 0L):
  def compile[P: Optimizable, I, O](student: P, trainset: Vector[Example[I, O]])(
    using enc: InputEncoder[I], out: upickle.default.Writer[O]): F[P]

final class BootstrapFewShot[F[_]: Async, P: Optimizable, I, O](
  metric: Metric[F, I, O],
  runner: ProgramRunner[F, P, I, O],
  teacher: Option[P] = None,                     // None → student itself (immutability makes deepcopy moot)
  config: BootstrapConfig = BootstrapConfig()
):
  def compile(student: P, trainset: Vector[Example[I, O]]): F[P]

final class BootstrapRS[F[_], P, I, O](/* wraps BootstrapFewShot */ numCandidates: Int = 16,
  valset: Option[Vector[Example[I, O]]] = None, stopAtScore: Option[Double] = None, ...):
  def compile(student: P, trainset: Vector[Example[I, O]]): F[CompileReport[P]]

final case class CompileReport[P](best: P, bestScore: Double,
  candidates: Vector[(Long /*seed*/, Double /*score*/)])   // BootstrapRS's ranked-list transparency
```

### Requirement seeds

- **R2.12** `LabeledFewShot.compile` SHALL attach up to `min(k, trainset.size)` demos — the example encoded via `InputEncoder`/`Writer[O]` — to **every non-frozen predictor**, sampled with the given seed (deterministic per seed, property-tested), making **zero** LLM calls (counting-mock scenario).
- **R2.13** `BootstrapFewShot.compile` SHALL, per training example and up to `maxRounds` attempts: run the teacher via `runner` with a fresh collector, score `metric(example, output, Some(trace))`, and on a passing score convert *each* trace entry into a `Demo` credited to the predictor at that entry's path. Passing = `score.value > 0` or `>= metricThreshold` when set (both scenarios).
- **R2.14** Demo-slot semantics per predictor SHALL be: first `maxBootstrapped` slots from passing traces (in trainset order), then labeled examples from the *unbootstrapped remainder* of the trainset up to `maxLabeled` total (DSPy's ballast rule). Property: counts never exceed caps; bootstrapped demos precede labeled ones.
- **R2.15** Leak rule: while bootstrapping example `e`, any demo minted *from `e`* MUST NOT be present on the teacher's predictors for that run. *Adversarial scenario (mandated by "must not"):* seed the teacher with a demo equal to `e`; verify the run's rendered prompts exclude it. (Trivially satisfied round 1 when the teacher starts demo-free; the scenario matters for `maxRounds > 1` reruns.)
- **R2.16** `compile` SHALL return a **new** program; the student value is unchanged; frozen predictors receive no demos (`OptimizerLaws` — cite R0.8; every optimizer in this spec runs the laws suite).
- **R2.17** Teacher/program failures count toward `maxErrors` (exceed → `OptimizeError.TooManyErrors` mirroring R1.3); a failing example simply yields no demos otherwise.
- **R2.18** `BootstrapRS.compile` SHALL generate `numCandidates` candidates including the three pinned baselines — zero-shot (no demos), labeled-only, unshuffled bootstrap — plus seeded shuffles; evaluate each on `valset` (default: trainset) via Phase-1 `Evaluate`; return the argmax with the full ranked list; `stopAtScore` SHALL short-circuit remaining candidate *evaluations* (not the already-started one — cancellation semantics scenario).
- **R2.19** All stochastic choices (sampling, shuffles) SHALL derive from the explicit seed; identical seed + deterministic metric/model → identical compiled program (Ring 3 property; this is the repo-wide determinism convention).

## Spec 2d — `compiled-state-and-cache`

### Typed contract

```scala
package org.adk4s.optimize

final case class CompiledState(formatVersion: Int, predictors: Map[String, PredictorState])
object CompiledState:
  def capture[P: Optimizable](p: P): CompiledState
  def applyTo[P: Optimizable](p: P, s: CompiledState): Either[OptimizeError, P]  // UnknownPath on mismatch
  def freeze: CompiledState => CompiledState
  def toJson(s: CompiledState): ujson.Value
  def fromJson(j: ujson.Value): Either[OptimizeError.InvalidState, CompiledState]

package org.adk4s.core.cache

final case class CacheKey private (sha256: String)
object CacheKey:
  /** canonical JSON: sorted keys, no ws, UTF-8; inputs: provider+model id,
    * CompletionOptions, full message list, rolloutId. */
  def of(modelId: String, options: ujson.Value, messages: ujson.Value, rolloutId: Long): CacheKey

trait CompletionCache[F[_]]:
  def get(k: CacheKey): F[Option[CachedCompletion]]
  def put(k: CacheKey, v: CachedCompletion): F[Unit]
object CompletionCache:
  def inMemory[F[_]: Concurrent](maxEntries: Int): F[CompletionCache[F]]   // LRU
  def jsonl[F[_]: Async](path: fs2.io.file.Path): F[CompletionCache[F]]   // append-log + index

object CachedChatModel:
  def apply[F[_]](underlying: ChatModel[F], cache: CompletionCache[F], rolloutId: Long = 0L): ChatModel[F]
```

### Requirement seeds

- **R2.20** `applyTo(capture(p)) == Right(p)` — save/load identity law (Ring 3 + Ring 4 across the JSON round-trip; JSON includes `formatVersion: 1`, unknown version → `InvalidState`).
- **R2.21** `applyTo` with a state whose path set differs from the program's MUST fail with `UnknownPath` naming the first offender — never silently skip (adversarial scenario: extra path; missing path).
- **R2.22** `CachedChatModel` SHALL return the cached completion on key hit **without invoking the underlying model** (counting-mock scenario), and SHALL populate the cache on miss. Streaming calls **DECISION**: bypass cache in v1 (recommendation — document; scenario asserts pass-through).
- **R2.23** Two calls identical except `rolloutId` MUST produce different keys (the resampling mechanism — BestOfN and bootstrap rounds depend on it); two calls differing in any message byte or any option MUST produce different keys; key derivation SHALL be stable across JVM runs (golden-value test pinning the sha256 of a fixture — Ring 4).
- **R2.24** `jsonl` cache: entries survive process restart (reload-across-instances property, mirroring the cross-run-memory precedent); corrupt trailing line → skipped with a surfaced warning count, not a crash.

## Phase-2 proposal boilerplate

**Existing concepts reused:** `Schema`, SAP, `OutputFormatOptions` (2b renders through it), `StructuredLLMError`, `PromptTemplate`, `ChatModel`, `Example`/`Metric`/`Evaluate` (Phase 1), `Optimizable`/`PredictorState` (Phase 0), upickle. **New concepts (registry entries):** `signature.md`, `adapter.md`, `bootstrap-fewshot.md`, `compiled-state.md`, `completion-cache.md`. **Typed contract: Full** for all four specs (new public API + persistence formats). **Exit criteria:** end-to-end example — a two-predictor RAG-style program over a bundled mini-dataset is compiled with `BootstrapRS` under the cache, beats its own zero-shot `Evaluate` score on a held-out split (assert `bestScore >= baselineScore`, tolerance documented), and round-trips through `CompiledState` JSON with identical behavior (same cache keys hit). **Risks:** structured-llm surface churn (mitigate: golden-prompt regression tests, R2.1/R2.3); demo-slot off-by-ones (mitigate: Ring 5 mutation testing targeted at 2c); cache key omissions silently corrupting results (mitigate: R2.23 golden values + Ring 8 review dedicated to key composition).

---

# Phase 3 — Instruction optimization

**Change id:** `add-instruction-optimizers` · **Capability specs (4):** `trace-capture-ambient`, `copro-optimizer`, `gepa-optimizer`, `inference-combinators` (BestOfN/Refine + KNNFewShot) · **Effort:** 6–8 pw · **Risk:** medium · **Rings:** R0, R1, R3, R8; R4 for GEPA run-log persistence · **Status:** Not started

> **Update (2026-08-12):** The `AgentMiddleware.wrapModelCall` hook (from `add-harness-api-phase0`, archived 2026-08-12) is now available as a trace-capture vehicle. A `TraceMiddleware` that plugs into `MiddlewareStack` can intercept every model call without modifying the `HarnessAgent` loop — this is a cleaner integration point than the `IOLocal`-only approach originally proposed. The `Traced.capture` design below should be updated to support both vehicles: (a) `IOLocal`-scoped ambient capture for standalone `Predict` programs, and (b) a `TraceMiddleware` for `HarnessAgent`-based programs. The `Trace`/`TraceEntry` types from `adk4s-eval` (Phase 1) are the canonical trace representation.

## Spec 3a — `trace-capture-ambient`

Generalizes Phase 2's explicit collector so *arbitrary* program shapes (not just `ProgramRunner` call sites) are traceable, and adds per-predictor slicing for GEPA.

```scala
object Traced:
  /** Runs fa with an ambient collector; returns result + trace. IO-specific impl via IOLocal;
    * generic F via cats-mtl Local — DECISION: ship IOLocal-backed for IO plus explicit-Ref
    * fallback for generic F (recommendation), rather than adding a cats-mtl dependency. */
  def capture[A](fa: IO[A]): IO[(A, Trace)]
```

- **R3.1** `Predict.run` under `Traced.capture` SHALL record entries without any code change at the call site; outside a capture scope it records nothing (both scenarios; adversarial: nested captures — inner scope's entries do not leak to outer... **DECISION** nested semantics: recommendation *inner appends to both* is DSPy-like; simpler *innermost-only* acceptable — pick in design.md, spec the choice).
- **R3.2** Concurrent branches (`parMapN`) SHALL not lose entries; entry order within one predictor is its call order (fiber-interleaved global order is unspecified — say so explicitly, spec-lint wants vagueness bounded).
- **R3.3** `Trace.forPredictor(path)` SHALL return exactly the entries whose path equals or is prefixed by `path` — GEPA's `pred_trace`.

## Spec 3b — `copro-optimizer`

```scala
final class Copro[F[_]: Async, P: Optimizable, I, O](
  metric: Metric[F, I, O], runner: ProgramRunner[F, P, I, O],
  proposer: StructuredLLM[F],            // the prompt-writing model
  breadth: Int = 8, depth: Int = 3, seed: Long = 0L, budget: Option[Int] = None):
  def compile(student: P, trainset: Vector[Example[I, O]]): F[CompileReport[P]]
```

- **R3.4** Per depth level and per non-frozen predictor, Copro SHALL obtain `breadth` candidate instruction strings from `proposer` (schema'd output: `Vector[String]`, SAP-parsed), evaluate each candidate program via `Evaluate`, and carry the best forward; ties broken by earlier candidate (determinism).
- **R3.5** Total metric calls SHALL NOT exceed `budget` when set; exceeding mid-level truncates remaining candidates (report notes truncation). *Adversarial:* budget smaller than one full evaluation → error `OptimizeError.BudgetTooSmall`, not a partial silent result.
- **R3.6** The proposer prompt SHALL include: current instructions, the signature's field metadata, and up to N (config) low-scoring examples with feedback — the prompt template text itself is a fixture file under test (golden test), so prompt-engineering iterations are diffable.

## Spec 3c — `gepa-optimizer`

```scala
final case class GepaConfig(
  budget: GepaBudget,                    // enum: MaxMetricCalls(n) | MaxFullEvals(n) | Auto(Light|Medium|Heavy)
  reflectionMinibatch: Int = 3, perfectScore: Double = 1.0, skipPerfect: Boolean = true,
  useMerge: Boolean = true, maxMerges: Int = 5,
  componentSelector: ComponentSelector = ComponentSelector.RoundRobin,   // | All | Custom(f)
  paretoSampling: Boolean = true, seed: Long = 0L, logDir: Option[Path] = None)

final class Gepa[F[_]: Async, P: Optimizable, I, O](
  metric: Metric[F, I, O],               // feedback-shaped strongly recommended; works degraded without
  runner: ProgramRunner[F, P, I, O],
  reflector: StructuredLLM[F],           // strong model for proposals
  config: GepaConfig):
  def compile(student: P, trainset: Vector[Example[I, O]],
              valset: Option[Vector[Example[I, O]]] = None): F[GepaReport[P]]

final case class GepaReport[P](best: P, bestScore: Double,
  candidates: Vector[CandidateRecord],   // lineage(parent indices), per-example scores, discovery eval-counts
  metricCallsUsed: Int)
```

- **R3.7** GEPA SHALL maintain per-candidate per-example score vectors; the next candidate to mutate SHALL be sampled (seeded) from the per-example Pareto frontier when `paretoSampling`, else the current aggregate best; the returned winner is always the aggregate argmax (frontier explores, aggregate selects — both scenarios).
- **R3.8** Each mutation SHALL: select predictor(s) per `componentSelector` (round-robin cycles across non-frozen predictors — scenario with a frozen one skipped); build a reflective minibatch of up to `reflectionMinibatch` *non-perfect* examples (`skipPerfect` drops rows with `score >= perfectScore` — adversarial: all-perfect minibatch → mutation skipped, budget not spent); call `reflector` once with current instructions + minibatch traces + each row's `Score.feedback`; produce one candidate with the proposed instructions at the selected path(s).
- **R3.9** When the metric returns no feedback, the reflection input SHALL degrade to score-only captions (never fail) — mirrors DSPy's documented degraded mode; scenario required.
- **R3.10** Per-predictor scoring: for the predictor under mutation GEPA SHALL invoke the metric a second time with `trace = Some(trace.forPredictor(path))`; a metric that ignores the argument keeps working (compat scenario).
- **R3.11** Merge: when enabled and two candidates are each strictly best on ≥1 example, GEPA MAY propose a merged candidate combining their per-predictor instructions (taking each predictor's instruction from the parent that scores better on aggregate); at most `maxMerges` merge evaluations per run (counting scenario).
- **R3.12** Budget SHALL be enforced as a ceiling on metric invocations across all evaluation passes; `MaxFullEvals(n)` = `n × (trainset ++ valset).size`; `Auto` presets map to candidate counts 6/12/18 with the derivation documented in the spec (numbers from DSPy's published translation). Exceeding mid-minibatch finishes the current example then stops (deterministic stopping rule — scenario).
- **R3.13** When `logDir` is set, GEPA SHALL write one JSONL line per candidate (instructions, scores, parent, eval count) — Ring 4 round-trip; a crashed run's log is readable up to the last complete line.
- **R3.14** `OptimizerLaws` (R0.8) SHALL hold; additionally: `metricCallsUsed <= budget` is a Hedgehog property under randomized tiny datasets with a deterministic mock model.

## Spec 3d — `inference-combinators`

```scala
type RewardFn[F[_], I, O] = (I, O) => F[Double]     // no gold — inference-time (DSPy reward shape)

object BestOfN:
  def apply[F[_]: Async, I, O](p: Predict[F, I, O], n: Int, reward: RewardFn[F, I, O],
    threshold: Double, failLimit: Option[Int] = None): Predict[F, I, O]
object RefineN:
  def apply[F[_]: Async, I, O](p: Predict[F, I, O], n: Int, reward: RewardFn[F, I, O],
    threshold: Double, advisor: StructuredLLM[F]): Predict[F, I, O]
object KnnFewShot:
  def apply[F[_]: Async, I, O](p: Predict[F, I, O], k: Int,
    pool: Vector[Example[I, O]], embedder: Embedder[F], render: I => String): F[Predict[F, I, O]]
```

- **R3.15** `BestOfN` SHALL run up to `n` attempts with distinct rollout ids (via `CachedChatModel` rollout mechanism when a cache is present — attempts MUST NOT be served from each other's cache entries; adversarial scenario with cache attached), short-circuit at `reward >= threshold`, else return the argmax attempt; attempt failures count against `failLimit` (default n) then re-raise the last error.
- **R3.16** `RefineN` SHALL, after each failing attempt, obtain advice from `advisor` (schema'd: one advice string) given the attempt's input/output/reward, and inject it as an additional instructions paragraph for the next attempt only (not persisted into the predictor's signature — adversarial: predictor state unchanged after the call).
- **R3.17** Existing `Constraint.assert` failures SHALL be usable as a reward source via a provided bridge `RewardFn.fromConstraints` (checks passed / total) — cite `constraint-validation`.
- **R3.18** `KnnFewShot` SHALL embed the pool once at construction; per call embed the input, select k nearest by cosine, attach them as demos for that call only. Pool re-embedding requires reconstruction (documented, scenario: constructor called once → embed count == pool size + 1 per run call).

**Exit criteria (phase):** on the Phase-2 example program, `Copro` or `Gepa` (mock-model-driven deterministic test *and* one optional real-LLM smoke) strictly improves the held-out `Evaluate` score over the Phase-2 bootstrap baseline; GEPA run-log replays into a `GepaReport` equal to the original.

**Risks:** reflection prompt quality (fixture-file + golden tests, iterate freely); GEPA loop complexity (mitigate: the seeded deterministic mock harness makes every scenario replayable); budget accounting bugs (R3.14 property).

---

# Phase 4 — Integration (opportunistic)

**Change id(s):** `add-optimizable-orchestration` (+ later `add-finetune-export` if ever needed) · **Capability specs:** `wiograph-predict-node`, `harness-agent-signature` · **Effort:** 4–6 pw · **Risk:** medium · **Rings:** R0, R1, R2 (orchestration layer), R3, R8 · **Status:** Not started

> **Update (2026-08-12):** The original plan targeted `ReactAgent` for agent-level optimization. Since then, `add-harness-api-phase0` (archived 2026-08-12) refactored the ReAct loop into `HarnessAgent` with an `AgentMiddleware` stack. `ReactAgent` is now a thin adapter (`ReactAgentAdapter`) over `HarnessAgent`. The spec is renamed from `react-agent-signature` to `harness-agent-signature` and the integration point shifts: the system prompt is already a `PromptSection` contributed by middleware, and `wrapModelCall` can intercept for tracing — the optimizer integration is cleaner than originally proposed.

Kept intentionally lighter — author the full seeds when Phase 3 experience is in. The proposal skeleton:

- **`wiograph-predict-node`.** A `PredictNode` wrapping `Predict` as a `WIONode`/`GraphNode`, plus `Optimizable` instances for graph/workflow types so a *workflow's* predictors are enumerable and updatable by path (path = node id chain — must be stable across graph rebuilds; that stability is the spec's hard requirement). Compile a workflow with `BootstrapFewShot`/`Gepa` unchanged (the optimizers must not know they're compiling a graph — that's the acceptance test). ⚠ VERIFY: how `WIONode` ids are assigned today and whether they survive DSL re-elaboration.
- **`harness-agent-signature`** (was `react-agent-signature`). Expose `HarnessAgent`'s tunable text — the base system prompt (`config.basePrompt`) and per-tool `description` — as `PredictorState`s (instructions = base system prompt; each tool description one pseudo-predictor, demos unused), so GEPA can optimize an *agent* against a task metric (DSPy's agent-optimization story). The `AgentMiddleware` system is the integration vehicle: a `TraceMiddleware` (Phase 3) plugged into `MiddlewareStack` provides the trace; the optimizer reads/writes `PredictorState` for the base prompt and tool descriptions. `ReactAgent` users get the optimization transparently via the `ReactAgentAdapter`. Requires an agent-level `ProgramRunner` that runs the `HarnessAgent` loop under tracing and a metric over final answers (`HarnessResult.Completed`). Adversarial requirement: tool *schemas* (parameter shapes) are never touched by optimizers — only description strings; `PromptSection`s contributed by middleware are optimizer-visible but their *source middleware* is not (the optimizer sees rendered text, not middleware identity).
- **Deliberately not planned:** `BootstrapFinetune`/provider finetune export, MIPROv2's Bayesian search, ProgramOfThought/CodeAct-style code sandboxes, any `dspy.settings` equivalent. Each would need its own proposal with fresh justification.

**Exit criteria:** a WIOGraph two-node pipeline and a one-tool `HarnessAgent` each compiled by an existing optimizer with zero optimizer code changes; orchestration examples updated; `ReactAgent.create` source compatibility preserved (the `ReactAgentAdapter` delegates to the optimized `HarnessAgent`).

---

## Appendix A — Change-by-change summary table

| Phase | Change id | Specs | Modules touched | New deps between modules | Effort | Status |
|---|---|---|---|---|---|---|
| 0 | `add-optimizable-surface` | `optimizable-surface` | `adk4s-optimize` (new), `verified` (new) | optimize → structured-llm, verified (Test) | 1–1.5 pw | ✅ Archived 2026-08-01 |
| 1 | `add-eval-core` | `eval-core`, `llm-judges` | `adk4s-eval` (new), examples | eval → structured-llm | 4–5 pw | ✅ Archived 2026-08-01 |
| 2 | `add-prompt-compiler-mvp` | `signature-as-data`, `adapter-demo-rendering`, `bootstrap-fewshot`, `compiled-state-and-cache` | structured-llm, adk4s-core (cache), adk4s-optimize, examples | optimize → eval; state types move into structured-llm (see 2a DECISION) | 6–8 pw | Not started (next) |
| 3 | `add-instruction-optimizers` | `trace-capture-ambient`, `copro-optimizer`, `gepa-optimizer`, `inference-combinators` | adk4s-optimize, structured-llm (Traced hooks), examples | — | 6–8 pw | Not started |
| 4 | `add-optimizable-orchestration` | `wiograph-predict-node`, `harness-agent-signature` | adk4s-orchestration, examples | orchestration → optimize (⚠ check graph direction; may need optimize-api split) | 4–6 pw | Not started |

## Appendix B — Global ⚠ VERIFY checklist (resolve at each change's spec time)

1. ~~smithy4s codegen wiring for non-test schemas (Phase 1 judges)~~ — ✅ **Resolved in Phase 1:** hand-written `Schema.instance` definitions in `Judges.scala`; no codegen plugin needed.
2. Exact current `PromptTemplate`/`function` call sites in `adk4s-examples` (Phase 2a must not break them — enumerate before freezing R2.1). **Still open for Phase 2.**
3. `CompletionOptions`/message JSON shape from llm4s 0.3.4 for the cache key canonicalization (R2.23) — derive from source, not docs. **Still open for Phase 2.**
4. `Embedder[F]` exact signature for `KnnFewShot` (R3.18). **Still open for Phase 3.**
5. `IOLocal` propagation semantics across `parMapN`/`Supervisor` on cats-effect 3.7.0 for R3.2 — verify with a probe test before committing to the ambient design. **Still open for Phase 3.** Note: the `AgentMiddleware.wrapModelCall` hook (added in `add-harness-api-phase0`) is an alternative capture vehicle that avoids `IOLocal` entirely for `HarnessAgent`-based programs.
6. `WIONode` id stability (Phase 4). **Still open for Phase 4.**
7. Whether `AgentEventEmitter` should *also* receive trace entries (observability parity) — nice-to-have, decide in Phase 3 design.md. **Still open for Phase 3.** The `AgentMiddleware.wrapModelCall` hook is now a third option alongside `IOLocal` and `AgentEventEmitter`.

## Appendix C — Cross-cutting adversarial review themes (Ring 8, every phase)

Seeded randomness everywhere (no unseeded sampling anywhere in optimize/eval); optimizers touch only `PredictorState`, never structure or field names (DSPy's inert-fields rule, enforced by construction but reviewed anyway); mocks answer from prompt content, never scripts; no global mutable configuration introduced under any disguise; frozen means frozen (bit-identical states through every optimizer); cache keys include *everything* that affects a completion.
