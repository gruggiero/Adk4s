# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v7).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintain in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-optimizable-surface

**Schema**: verified-scala3 (v7)
**Specs**: 1 (optimizable-surface)
**Human gate tier**: separate — two gates (typed contract, then test oracle)

## Spec 1/1: optimizable-surface

- **BASELINE SHA**: `773900b9f89a48fe760eba4c6921f4c1dec49d68` (recorded 2026-07-27; working tree clean)
- **State**: in progress — Step 13 (checkpoint + commit)

### Step 0 — baseline + concept check
- [x] working tree clean
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `773900b9f89a48fe760eba4c6921f4c1dec49d68`
- [x] read `openspec/concept-inventory.md`; verify Proof Obligations table complete — 40+ obligations, all enforced
- [x] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds new types, does not widen a sealed ADT)

### Step 0 — build wiring
- [x] add `adk4s-optimize` module to `build.sbt` (`.dependsOn(structured-llm)`, `.dependsOn(verified % Test)`, deps `catsEffect`/`fs2Core`/`munitMain`/`munitCatsEffect`/`hedgehogMunit` + `testDeps`, `scalacOptions ++= scala3Options`, aggregated by root)
- [x] verify `sbt adk4s-optimize/compile` succeeds on an empty module

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `OptimizableTypeContract.scala` in `adk4s-optimize/src/test/scala/org/adk4s/optimize/`
- [x] compiles via `sbt adk4s-optimize/Test/compile`
- [x] **STOP for human approval** — APPROVED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `Predict0Spec.scala`, `OptimizableSpec.scala`, `ToyOptimizerSpec.scala`, `OptimizerLawsSpec.scala` with 15 Hedgehog properties + scenarios + compile-negative
- [x] ORACLE POLARITY run: properties RED (stubs), compile-negative GREEN-by-design
- [x] **STOP for human approval** — APPROVED

### Step 3 — implementation
- [x] (a) `Demo.scala`, `PredictorState.scala`, `PredictorPath.scala`
- [x] (b) `OptimizeError.scala` (sealed enum extends Throwable, stands alone)
- [x] (c) `HasPredictorState.scala`
- [x] (d) `Optimizable.scala` (`inline def derived` via Mirror + summonFrom chain; update path uses closure-based `predictCollectionWithUpdater` to resolve `HasPredictorState` at the same inline depth as the predict path)
- [x] (e) `Predict0.scala` + `given HasPredictorState[Predict0[F, I, O]]`
- [x] (f) `testkit/OptimizerLaws.scala`
- [x] (g) test fixtures `ToyPrograms.scala` + `UppercaseInstructions.scala` + `StaticDemoInjector.scala`

### Rings
- [x] Ring 0 — `sbt adk4s-optimize/compile` + `sbt adk4s-optimize/test` pass (64 tests, 0 failures)
- [x] Ring 1 — scalafmt + WartRemover clean (scalafix has environment dependency issue, unrelated)
- [x] Ring 2 — import audit (no forbidden imports: no adk4s-core, adk4s-orchestration, workflows4s, or llm4s client)
- [x] Ring 3 — all 15 Hedgehog properties GREEN (12 in OptimizableSpec + 3 in OptimizerLawsSpec)
- [x] Ring 6 — `PredictorKernel.scala` model + `PredictorModelBridgeSpec.scala` bridge (6 bridge tests, all GREEN)
- [x] Ring 8 — adversarial spec-compliance review (fresh context): 15 PASS, 1 PARTIAL (R12 compile-negative test enforced at compiler level via `-Wconf` flag, not via munit macro — justified), 0 FAIL

### Step 12 — concept delta + inventory + module-graph update
- [x] create `openspec/concepts/optimizable-surface.md` + `openspec/concepts/predictor-state.md`
- [x] append introduced concepts to `openspec/concept-inventory.md` (OptimizeError, Prog model, PredictorState, Demo, PredictorPath, Predict0, Optimizable, HasPredictorState)
- [x] append `adk4s-optimize` row to `openspec/project.md` module dependency graph + module descriptions

### Step 13 — checkpoint + commit
- [ ] regenerate `tasks.md` checkboxes from this file
- [ ] commit the spec
- [ ] **STOP for human validation** ◄ WAITING (none — this is the only spec)
