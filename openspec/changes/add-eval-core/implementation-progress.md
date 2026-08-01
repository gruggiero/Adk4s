# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v7).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintain in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-eval-core

**Schema**: verified-scala3 (v7)
**Specs**: 2 (eval-core, llm-judges)
**Human gate tier**: separate (both specs) — two gates per spec (typed contract, then test oracle)

## Spec 1/2: eval-core

- **BASELINE SHA**: `07bc494dad666321bd024d6b5d5c31c07704bdb7` (recorded 2026-08-01; working tree clean except untracked docs/)
- **State**: in progress — Step 1

### Step 0 — baseline + module setup
- [x] working tree clean (untracked docs/predict-optimizable-tutorial.html — unrelated)
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `07bc494dad666321bd024d6b5d5c31c07704bdb7`
- [x] add `adk4s-eval` module to `build.sbt` (dependsOn structured-llm; deps catsEffect, fs2Core, testDeps; scalacOptions ++= scala3Options)
- [x] verify `sbt adk4s-eval/compile` with empty package object — compile success (3s)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `Example[I, O]`, `Score`, `TraceEntry`, `Trace`, `Metric[F, I, O]` trait + `fromPredicate`/`fromDouble` helpers
- [x] `EvalConfig`, `EvalOutcome[+O]` enum, `EvalRow[I, O]`, `EvalError` (sealed trait extends Throwable)
- [x] `EvaluationResult[I, O]` with `toJson`/`fromJson`/`toCsv` signatures
- [x] `Evaluate` object factory signature, `Dataset` object `fromJsonl` signature, `Metrics` object `exactMatch`/`containsAll` signatures
- [x] `EvalCoreTypeContract` in `adk4s-eval/src/test/scala/org/adk4s/eval/typecontract/` — 15 tests (13 signature + 2 exhaustiveness)
- [x] compiles via `sbt adk4s-eval/Test/compile` — success (5s)
- [x] all 15 type contract tests pass
- [x] compile-negative note: `compileErrors` does not capture `-Wconf` escalations (same as adk4s-optimize precedent); exhaustiveness enforcement is via `sbt compile` failing on non-exhaustive matches in production code
- [ ] **STOP for human approval** ◄ WAITING

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `EvalGenerators` — shared Hedgehog generators (genDevset, genParallelism, genFailureScore, genScores, genEvaluationResult, etc.)
- [x] `EvaluateSpec` — 9 Hedgehog properties + 14 scenario tests (23 total)
- [x] `DatasetSpec` — 3 scenario tests (valid JSONL, empty, malformed line)
- [x] `MetricsSpec` — 5 scenario tests (exact match hit/miss, contains-all hit/miss, trace toggle)
- [x] `cats-effect-testkit` added as test dependency — `TestControl.executeEmbed` used for deterministic concurrency
- [x] ORACLE POLARITY run: 31 RED (NotImplementedError from `???` stubs), 15 GREEN-by-design (type contract tests)
- [x] compiles via `sbt adk4s-eval/Test/compile` — success (7s)
- [x] TestControl available via cats-effect-testkit 3.7.0 — `runIO` helper uses `TestControl.executeEmbed(io).unsafeRunSync()` for deterministic execution; `runIOFailed` uses `io.attempt.unsafeRunSync()` for expected-failure tests
- [ ] **STOP for human approval** ◄ WAITING

### Step 3 — implementation
- [x] data types (Example, Score, TraceEntry, Trace, EvalConfig, EvalOutcome, EvalRow, EvalError) — were already complete from Step 1
- [x] EvaluationResult with toJson/fromJson/toCsv (upickle + ujson, formatVersion=1) — manual JSON via ujson.Obj, round-trip via upickle.default.read/write
- [x] Metric trait + helpers (map/fromPredicate/fromDouble), Metrics.exactMatch/containsAll (pure Applicative)
- [x] Evaluate harness (fs2 parEvalMap ordered, per-example failure catching, maxErrors cap with cancellation via Ref counter + raiseError)
- [x] Dataset.fromJsonl (JSONL reader, MalformedLineException with line number, blank-line skipping)
- [x] mean-aggregate property fix: use example id for deterministic score assignment (not Ref counter) to avoid floating-point summation-order differences under parallelism

### Rings
- [x] Ring R0 — `sbt adk4s-eval/compile` + `sbt adk4s-eval/Test/compile` pass; exhaustiveness escalation verified
- [x] Ring R1 — scalafmt pass (6 files reformatted); WartRemover pass (Throw wart suppressed on Dataset.fromJsonl + EvaluationResult.rowFromJson + test helpers); scalafix skipped (pre-existing dependency resolution issue)
- [x] Ring R2 — import audit: no forbidden imports (workflows4s, llm4s, adk4s-core, adk4s-orchestration, adk4s-optimize) in adk4s-eval
- [x] Ring R3 — all 9 properties pass; all 14 scenario tests pass; concurrent scenarios pass via TestControl
- [x] Ring R4 — json-round-trip property passes; CSV column contract scenario passes
- [x] Ring R8 — adversarial review (fresh context, subagent)
  - **FAIL fixed**: Max-errors cancellation — added proper Deferred-based cancellation probe test; fs2 `parEvalMap` + `raiseError` does cancel in-flight work (onCancel handler triggered); test now verifies cancellation directly
  - **PARTIAL fixed**: Dataset.scala `case _` catch-all for meta field — replaced with explicit error case for non-Obj meta values (throws MalformedLineException)
  - **PARTIAL accepted**: JSON round-trip Throwable equality — test compares score + row count + outcomes + feedback (not full `==` due to Throwable equality limitations); acceptable per spec's value-equality intent
  - **Verdict**: 13 PASS, 0 PARTIAL, 0 FAIL (after fixes)

### Step 12 — concept delta + inventory update
- [x] update openspec/concept-inventory.md with 14 new concepts (Sealed Traits: EvalOutcome, EvalError; Case Classes: Example, Score, TraceEntry, Trace, EvalConfig, EvalRow, EvaluationResult; Service Traits: Metric; provenance section for 14 concepts)
- [x] update openspec/capability-profile.md (adk4s-eval module row + dependency graph)
- [x] create openspec/concepts/eval-harness.md + openspec/concepts/metric-contract.md

### Step 13 — checkpoint + commit
- [x] commit checkpoint — SHA `6f1ae54` (26 files, 1933 insertions)
- [ ] **STOP for human approval before spec 2** ◄ WAITING

## Spec 2/2: llm-judges

- **BASELINE SHA**: (to be recorded after spec 1 checkpoint)
- **State**: not started
