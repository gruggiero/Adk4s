# Design: Add the Eval Core (DSPy Port — Phase 1)

## Package Structure

<!-- The new `adk4s-eval` module is a leaf above `structured-llm`. It
     mirrors the precedent set by `adk4s-optimize` (a leaf above
     `structured-llm` landed by archived Phase 0). The forbidden-import
     list is derived from the detected stack in capability-profile.md:
     the eval module must stay decoupled from the orchestration/tool/
     workflow layers so it can be consumed by optimizers (Phase 2/3)
     without pulling in the full agent runtime. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Domain (pure data) | `org.adk4s.eval` (`Example`, `Score`, `TraceEntry`, `Trace`, `EvalConfig`, `EvalOutcome`, `EvalRow`, `EvaluationResult`, `EvalError`) | cats (kernel), ujson | cats-effect, fs2, structured-llm, workflows4s, llm4s LLM client, adk4s-core, adk4s-orchestration, adk4s-optimize | Pure data types + export codecs only; no effects, no streaming, no LLM client |
| Service (effectful) | `org.adk4s.eval` (`Evaluate`, `Metric`, `Metrics`, `Dataset`) | Domain + `org.adk4s.structured.core` (StructuredLLM, Schema, Prompt, Constraint) + cats-effect + fs2-core | workflows4s, llm4s LLM client, adk4s-core, adk4s-orchestration, adk4s-optimize | Effect-polymorphic `F[_]`; the only structured-llm surface touched is `StructuredLLM.complete`/`completeTemplate` (judges) and `Schema.instance` (judge schemas) |
| Judges (effectful) | `org.adk4s.eval` (`Judges`, judge schemas) | Service + `org.adk4s.structured.core` + `org.adk4s.structured.sap` (SAP) | All of the above forbidden + adk4s-core, adk4s-orchestration | Judges are `StructuredLLM` programs returning `Metric` instances |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.eval` | Domain + Service + Judges | All eval-core types in a single package (small module — no sub-packages needed; mirrors `org.adk4s.optimize` precedent) |

### Module wiring

The new sbt module `adk4s-eval` is added to `build.sbt` and `project/Dependencies.scala`, mirroring the `adk4s-optimize` precedent:

```scala
lazy val `adk4s-eval` = (project in file("adk4s-eval"))
  .dependsOn(`structured-llm`)
  .settings(
    name := "adk4s-eval",
    libraryDependencies ++= Seq(
      catsEffect,
      fs2Core,
      munitMain,
      munitCatsEffect,
      hedgehogMunit
    ) ++ testDeps,
    scalacOptions ++= scala3Options
  )
```

The module is **aggregated by the root build** so `sbt compile` includes it. The `munitMain` + `munitCatsEffect` + `hedgehogMunit` in main scope are needed if a laws testkit is added (not required by this spec — `OptimizerLaws` is Phase 0's pattern; eval has no equivalent laws suite). For now they are Test scope only via `testDeps`.

The module graph delta (recorded by apply Step 12):

```
adk4s-eval → structured-llm
adk4s-examples → adk4s-eval (if EvalHarnessExample is added — required by exit criteria)
```

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `Example`, `Score`, `TraceEntry`, `Trace`, `EvalConfig`, `EvalOutcome`, `EvalRow`, `EvaluationResult`, `EvalError` | Pure data types | No — data carriers, no algorithm |
| `Trace.forPredictor` | Prefix filter on trace entries | No — one-line `filter`; trivially correct by construction |
| `EvaluationResult.score` computation | Arithmetic mean of row scores | No — one `foldLeft` + division; trivially correct by construction; the R1.4 property tests the edge cases (empty devset, all-failure) |
| `EvaluationResult.toJson` / `fromJson` | JSON serialization round-trip | No — upickle codec wiring; Ring 4 property tests the round-trip |
| `EvaluationResult.toCsv` | CSV column formatting | No — string formatting; scenario test checks the column contract |
| `Metrics.exactMatch` / `Metrics.containsAll` | Pure string metrics | No — string equality / substring containment; trivially correct; scenario tests cover the paths |
| `Dataset.fromJsonl` | JSONL file reader | No — file I/O + upickle; effectful |

**Ring 6 verdict**: No candidate has a non-trivial pure kernel (decision, fold, or law) that a PureScala mirror could prove better than the Ring 3 properties already do. The aggregate-mean arithmetic is `scores.foldLeft(0.0)(_ + _.score.value) / scores.size` — one fold. The `Trace.forPredictor` filter is `Trace(entries.filter(_.path.startsWith(prefix)))` — one filter. Neither has branching logic, state, or a law that Stainless could verify beyond what the property tests already assert. If `design.md` reveals a non-trivial pure kernel (e.g. a text-normalization state machine in `Metrics`), Ring 6 is re-evaluated for that kernel.

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `Evaluate.apply[F, I, O]` | `F: Async` | Parallel evaluation via fs2 `parEvalMap` (ordered) + scope cancellation on maxErrors |
| `Metric[F, I, O]` | `F: Applicative` (trait); `F: Async` (judges) | Scoring interface; judges call `StructuredLLM.complete` |
| `Judges.semanticF1[F]` / `Judges.completeAndGrounded[F]` | `F: Async` (via StructuredLLM) | LLM-judge metrics |
| `Dataset.fromJsonl` | `F: Sync` (file read) | JSONL dataset reader |

## Type Strategy — Invalid-State Prevention

| Invariant | Level | Mechanism | Justification |
|-----------|-------|-----------|---------------|
| `EvalOutcome` exhaustiveness — all matches handle both `Succeeded` and `Failed` | Best | Sealed enum + `-Wconf` exhaustiveness escalation (compile error on non-exhaustive match) + compile-negative test | The type system enforces it; the compile-negative test proves the escalation works |
| `EvalError` exhaustiveness — all matches handle `TooManyErrors` | Best | Sealed enum + `-Wconf` exhaustiveness escalation | Same as above |
| `Score.value` is a `Double` (no range constraint) | Okay | No constraint at the type level; out-of-range values are the metric's responsibility (judges clamp via `Constraint.check`) | Score values from arbitrary metrics can be any Double (e.g. negative scores for penalties); constraining to [0,1] would be wrong for non-binary metrics |
| `EvalConfig.parallelism` is a positive `Int` | Good | Default value (8) + smart constructor validation (if `parallelism <= 0`, fall back to 1) | Parallelism 0 would cause fs2 to hang; the smart constructor prevents it |
| `EvalConfig.failureScore` is any `Double` | Okay | No constraint needed | Any Double is a valid failure score (0.0 is the default; negative values penalize failures more than zero) |
| `EvalConfig.maxErrors` is `Option[Int]` | Best | `None` = unlimited; `Some(n)` = cap. The `Option` encoding makes "unlimited" the default and explicit | The type system distinguishes "no cap" from "cap at N" |
| `Example.id` is `Option[String]` | Best | `None` = no id; `Some(id)` = labeled. The `Option` encoding makes unlabeled examples explicit | The type system distinguishes labeled from unlabeled |
| `Metric` trace argument is `Option[Trace]` | Best | `None` = evaluation mode; `Some` = optimization mode. The `Option` encoding pins the eval-vs-optimization toggle at the type level | The type system enforces the toggle; the harness always passes `None` (R1.5) |
| `EvaluationResult.formatVersion` is always 1 | Good | Hardcoded in `toJson`; `fromJson` checks the version and rejects mismatched versions | Version field allows future migrations; current version is hardcoded |
| `EvalError.TooManyErrors.partial` row type | Good | `Vector[EvalRow[I, O]]` — the partial rows collected before the abort. Type-safe: `I` and `O` are preserved | The partial result is typed, not erased to `Vector[?]` (the `?` in the operative plan was a placeholder; the actual type is `Vector[EvalRow[I, O]]`) |

## Refined Type Strategy

### New Refined Types

None. No opaque types or constrained newtypes are introduced. The eval module's
types are plain case classes and enums — the invariants are enforced by the type
system (sealed enums, `Option` encodings) and smart constructors, not by refined
types. No Iron/refined library is in the stack.

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `Score.value: Double` | Any Double is a valid score (metrics may return negative values, percentages >1.0, etc.) |
| `EvalConfig.parallelism: Int` | Smart constructor handles the `<= 0` case; a refined type would add a dependency for one check |
| `EvalConfig.failureScore: Double` | Any Double is valid |
| `EvalConfig.seed: Long` | Any Long is valid (reserved for future shuffling) |
| `Example.id: Option[String]` | Plain `Option` — no structural constraint on the id string |

## IDL Model Layout

No Smithy codegen is used in this module. Judge schemas are hand-written
`Schema.instance` definitions (Decision: Judge schema definition strategy below).

### Structures (hand-written Schema.instance)

| Structure | Fields | Used By |
|-----------|--------|---------|
| `SemanticF1Judge` | `precision: Double`, `recall: Double`, `reasoning: String` | `Judges.semanticF1` |
| `CompleteAndGroundedJudge` | `completeness: Double`, `groundedness: Double`, `reasoning: String` | `Judges.completeAndGrounded` |

Each structure is defined as:

```scala
given Schema[SemanticF1Judge] = Schema.instance(
  """structure SemanticF1Judge {
    |  @required precision: Double
    |  @required recall: Double
    |  reasoning: String
    |}""".stripMargin
)(using smithy4s.schema.Schema[SemanticF1Judge])
```

The smithy4s `Schema[SemanticF1Judge]` is derived via `smithy4s.schema.Schema`
implicit derivation (case class + `Smithy4sSchema` import). This compiles without
the smithy4s-sbt-codegen plugin — the codegen plugin is only needed for `.smithy`
files, not for hand-written `Schema.instance` definitions.

## Error Strategy

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| `EvalError` (extends Throwable) | `TooManyErrors(count: Int, max: Int, partial: Vector[EvalRow[I, O]])` | `Evaluate` — raised via `F.raiseError` when failures exceed `maxErrors` |

`EvalError` stands alone in `org.adk4s.eval` (does NOT extend `AdkError`). Rationale:
the cross-phase convention in `docs/dspy-port-operative-plan.md` recommends
standing alone in-module and bridging to `AdkError` later. `EvalError` is raised
via `F.raiseError` (not bare `throw`), satisfying WartRemover's `Throw` wart.

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Program raises → Harness | `EvalOutcome.Failed(error)` + `Score(failureScore)` | `program(input).map(EvalOutcome.Succeeded).handleError(e => EvalOutcome.Failed(e))` |
| Metric raises → Harness | Same as above (metric failure = program failure for scoring) | `metric(gold, pred, None).handleError(e => Score(failureScore))` |
| Failures exceed maxErrors → Caller | `F.raiseError(EvalError.TooManyErrors(...))` | `Ref` counter + `raiseWhen(count > max)` |
| Judge SAP parse failure → Metric | `F.raiseError(StructuredLLMError.ParseFailed(...))` → caught by harness as metric failure | Judge calls `StructuredLLM.complete` which raises on parse failure; the metric propagates the raise; the harness catches it |
| Out-of-range precision/recall → Clamped | `Constraint.check` clamps to [0,1] + flags in feedback | `Constraint.check(precision >= 0.0 && precision <= 1.0)` → clamp + feedback note |

### No swallowed errors

- Program/metric failures are ALWAYS recorded as `EvalOutcome.Failed` — never
  silently mapped to a succeeded row with a default score.
- `maxErrors` abort raises `EvalError.TooManyErrors` — never returns a partial
  `EvaluationResult` silently.
- Judge parse failures propagate as raises — never silently return `Score(0.0)`.

## Compatibility Story (Ring 4)

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| `EvaluationResult` export | JSON (upickle) | `formatVersion: 1` field; `fromJson` checks version; round-trip property | `json-round-trip` Hedgehog property + `formatVersion present` scenario |
| `EvaluationResult` export | CSV | Write-only; column contract (id, score, feedback, outcome, meta); no round-trip reader | `Mixed outcomes` scenario test (column header + outcome values) |
| `Dataset.fromJsonl` | JSONL (one JSON per line) | Caller-supplied `Reader[I]`/`Reader[O]`; malformed line → error naming line number | `Malformed line at position 15` scenario test |

**Fixture obligation**: `EvaluationResult.toJson → fromJson → same value` (Ring 4
property). No old-fixture decoding obligation (this is a new format — no previous
version exists). The `formatVersion` field is the migration mechanism: if the
format changes in a future change, `fromJson` checks the version and handles
migrations.

## Verification Map

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `org.adk4s.eval` (data types) | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |
| `org.adk4s.eval` (Evaluate harness) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.eval` (Metric, Metrics) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.eval` (Judges) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.eval` (Dataset) | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — |
| `org.adk4s.eval` (EvaluationResult export) | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | — | ✅ | — |

- R0: compile + exhaustiveness escalation for `EvalOutcome`/`EvalError`
- R1: WartRemover (`Throw` active — `EvalError` raised via `F.raiseError`, not `throw`; `Any` excluded — `ujson.Value` in export)
- R2: import audit — no forbidden imports (workflows4s, llm4s client, adk4s-core, adk4s-orchestration, adk4s-optimize)
- R3: 9 Hedgehog properties (eval-core) + 4 (llm-judges) + concurrent scenarios via `TestControl`
- R4: JSON round-trip property + CSV column contract scenario
- R5: deferred (no non-trivial mutation target; harness is fs2 orchestration)
- R6: skip (no pure kernel — justified above)
- R7: N/A (no TLA+/Apalache)
- R8: adversarial review (mandatory — checks for `parEvalMapUnordered`, feedback leaking, metric retry, `trace = Some` in harness, judge crash on parse failure)
- R9: N/A (no telemetry stack)

## Technical Decisions

### Decision: Judge schema definition strategy

**Context**: R1.11 ⚠ VERIFY — `structured-llm-test-models` is test-only codegen;
judges are production code. Two options: (a) hand-written `Schema.instance`
definitions in `adk4s-eval`, or (b) a new Smithy codegen area inside `adk4s-eval`.

**Options considered**:
1. Hand-written `Schema.instance` — no codegen plugin needed; `Schema.instance(idl)(smithy4sSchema)` bridges Smithy IDL + smithy4s schema manually
2. Smithy codegen in `adk4s-eval` — add `smithy4s-sbt-codegen` plugin + `.smithy` files; generates case classes + schemas

**Decision**: Hand-written `Schema.instance` definitions (option 1).

**Consequences**:
- No codegen plugin needed in `adk4s-eval` — simpler build wiring
- Judge schemas are 3-field structures (precision/recall/reasoning or
  completeness/groundedness/reasoning) — hand-writing is trivial
- The `Schema.instance` pattern is already used in `structured-llm` tests
  (see `structured-llm-test-models` for the codegen alternative)
- If a future change needs many judge schemas, it can add the codegen plugin then
- The smithy4s `Schema` implicit derivation for case classes works without the
  codegen plugin — `smithy4s.schema.Schema` is available via the `smithy4s` dep
  in `structured-llm` (transitive in `adk4s-eval`)

### Decision: EvalError stands alone (not AdkError)

**Context**: The cross-phase convention recommends standing alone in-module;
`AdkError` is in `adk4s-core` which `adk4s-eval` must not depend on (Ring 2).

**Options considered**:
1. `EvalError extends Throwable` — stands alone in `org.adk4s.eval`
2. `EvalError extends AdkError` — requires `adk4s-eval → adk4s-core` dependency

**Decision**: `EvalError extends Throwable` (option 1).

**Consequences**:
- `adk4s-eval` stays decoupled from `adk4s-core` (Ring 2 purity)
- `EvalError` is raised via `F.raiseError` — WartRemover `Throw` wart satisfied
- A future bridge to `AdkError` can be added if the error hierarchy needs unification
- Pattern matches on `EvalError` are exhaustive (sealed enum with one variant now;
  future variants are handled by the exhaustiveness escalation)

### Decision: Export codec-freeness

**Context**: R1.8 DECISION — should `Evaluate` require `Writer[I]`/`Writer[O]`
on every call, or only on export?

**Options considered**:
1. `Evaluate` requires `Writer[I]`/`Writer[O]` always — export is always available
2. `Evaluate` is codec-free; `toJson`/`toCsv` require `Writer[I]`/`Writer[O]` at the
   export call site only

**Decision**: Option 2 — `Evaluate` is codec-free; writers required on export only.

**Consequences**:
- Callers without upickle writers can still run evaluations
- Export is opt-in: `result.toJson(using writerI, writerO)`
- `fromJson` requires the corresponding `Reader` and is also opt-in
- The round-trip property test uses `String` for `I`/`O` (upickle
  `ReadWriter[String]` is built-in)

### Decision: Concurrency mechanism

**Context**: R1.1 requires devset-order rows; R1.3 requires cancellation on
maxErrors. fs2 offers `parEvalMap` (ordered) and `parEvalMapUnordered`.

**Decision**: `fs2.Stream.parEvalMap` (ordered) for evaluation; `Stream.interruptWhen` + `Deferred` for maxErrors cancellation.

**Consequences**:
- `parEvalMap` preserves devset order (R1.1) — the adversarial review checks for `Unordered`
- `interruptWhen` cancels in-flight work when the error counter hits the cap (R1.3)
- The cancellation probe test uses `Deferred[IO, Unit]` — a fiber sets the
  deferred when it observes cancellation, and the test asserts the deferred is
  set (deterministic, no wall-clock sleeps)
- `TestControl` is used for deterministic concurrency testing (cats-effect 3.7.0,
  transitively available)

### Decision: EvalError.TooManyErrors partial type

**Context**: The operative plan's typed contract has `partial: Vector[?]`. The
`?` was a placeholder for the existential type.

**Decision**: `partial: Vector[EvalRow[I, O]]` — the partial rows are typed with
the same `I` and `O` as the evaluation.

**Consequences**:
- The caller gets typed partial results, not erased `Vector[Any]`
- `EvalError` is parameterized: `EvalError.TooManyErrors[I, O](count, max, partial: Vector[EvalRow[I, O]])`
- This means `EvalError` is NOT a single sealed enum — it's
  `enum EvalError[+I, +O] extends Throwable` with `case TooManyErrors[I, O](...)`.
  Alternatively, `TooManyErrors` can be a case class (not an enum variant) if the
  type parameterization is awkward in a sealed enum. Settle in implementation:
  if `sealed enum EvalError` can't carry type parameters cleanly, use
  `sealed trait EvalError extends Throwable` with `case class TooManyErrors[I, O](...)`.

### Decision: Judge prompt text source

**Context**: DSPy's SemanticF1 prompt is the product of years of iteration.
The spec marks it MUST-CONFIRM with a pointer to DSPy's repo.

**Decision**: Port DSPy's published SemanticF1 prompt shape as the starting text.
The prompt text is a constant in `Judges.scala`, marked with a comment citing
the source. The defaults are documented as "not battle-calibrated" and
conservative thresholds (0.66) are shipped.

**Consequences**:
- The prompt text is a MUST-CONFIRM artifact — the apply phase stops and asks
  if the DSPy source has changed
- The threshold (0.66) is a default; callers can override it
- The `CompleteAndGrounded` prompt is written from scratch (DSPy doesn't have a
  direct equivalent) but follows the same structure (structured output + reasoning)
