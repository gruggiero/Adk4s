# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept.

     This file is generated from the specs, spec-lint (all PASS required),
     and design artifacts. The checkbox list at the bottom is the progress
     tracker used by the apply phase (tracks: implementation-progress.md). -->

## Dependency Analysis

<!-- For each spec, list what it introduces and what it consumes.
     This determines the topological sort order. -->

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | `specs/optimizable-surface/spec.md` | `Demo`, `PredictorState`, `PredictorPath`, `Optimizable[P]`, `HasPredictorState[Self]`, `Predict0[F, I, O]`, `OptimizeError` (`UnknownPath`, `FrozenPath`), `OptimizerLaws`, `UppercaseInstructions`, `StaticDemoInjector`, `adk4s-optimize` module, `optimizable-surface` concept doc, `predictor-state` concept doc | `StructuredLLM[F[_]]`, `Prompt`, `Schema[A]`, `PromptTemplate[I]`, `ujson.Value`, `AdkError` (pattern reference only), `AgentMemoryLaws` (pattern reference only), Hedgehog `HedgehogSuite`/`property`, `munit.FunSuite`/`munit.CatsEffectSuite` | high |

**Dependency graph**: single node, no inter-spec edges. This change has exactly one spec (`specs/optimizable-surface/spec.md`), which is self-contained — all concepts it consumes are pre-existing (in `openspec/concept-inventory.md` or source) and all concepts it introduces are new to this change. There is no topological ordering to perform; the spec is implemented in one depth-first pass.

**Intra-spec ordering** (the apply phase implements these in this order, driven by the spec's Implementation Anchors and the design's package layers — Domain first, then Adapter, then Testkit, then test fixtures):

1. **Build wiring** — `build.sbt` + `project/Dependencies.scala`: add `adk4s-optimize` module + main-scope munit/cats-effect/hedgehog variants. Must compile empty before any source is added (proves the module graph).
2. **Domain data types** — `Demo`, `PredictorState`, `PredictorPath`, `OptimizeError`: pure case classes + sealed enum. No dependencies on other new types.
3. **Leaf capability** — `HasPredictorState[Self]` typeclass: depends on `PredictorState`.
4. **Typeclass + derivation** — `Optimizable[P]` + `inline def derived` via `Mirror.ProductOf[P]`: depends on `PredictorState`, `PredictorPath`, `OptimizeError`, `HasPredictorState`. This is the highest-risk piece (⚠ VERIFY item 1: `Mirror` + `inline` ergonomics on Scala 3.8.4).
5. **Placeholder predictor** — `Predict0[F, I, O]` case class + `given HasPredictorState[Predict0[F, I, O]]`: depends on `HasPredictorState`, `PredictorState`, `StructuredLLM`, `PromptTemplate`, `Schema`.
6. **Laws testkit** — `OptimizerLaws` in `org.adk4s.optimize.testkit`: depends on `Optimizable`, `PredictorState`, `PredictorPath`. Main-scope munit + cats-effect (mirrors `AgentMemoryLaws`).
7. **Test fixtures + toy optimizers** — `TwoPredictors`, `Outer`/`Inner`, `Pipeline`, `UppercaseInstructions`, `StaticDemoInjector` in test sources: depend on all of the above.
8. **Test specs** — `Predict0Spec.scala`, `OptimizableSpec.scala`, `ToyOptimizerSpec.scala`, `OptimizerLawsSpec.scala`: depend on all of the above + Hedgehog.
9. **Concept docs + module-graph update** — `openspec/concepts/optimizable-surface.md`, `openspec/concepts/predictor-state.md`, append `adk4s-optimize` row to `openspec/project.md` + `openspec/capability-profile.md` + `openspec/concept-inventory.md` (apply Step 12).

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver (waiver requires
     explicit human approval; only for docs/formatting/test-only specs). -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | `optimizable-surface` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | deferred | — | — | ✅ (MANDATORY) | — | full |

**Ring-by-ring rationale** (from design.md §Verification Map):

- **R0 (Compile)**: ✅ — `sbt adk4s-optimize/compile`. Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) applies to the new `OptimizeError` enum from day one. The new module must compile under `scala3Options`.
- **R1 (Lint)**: ✅ — Scalafix (DisableSyntax, RemoveUnused, OrganizeImports, NoConfigFactory, NoSysEnv, NoKeywordTry/Catch/Finally) + WartRemover (`Warts.unsafe` minus `TripleQuestionMark`, `Any`, `DefaultArguments`). ⚠ VERIFY: `Mirror`-derived code (`summonInline`, `erasedValue`, `constValue`) and `ujson.Value` payloads may trip the temporarily-excluded `Any`/`AsInstanceOf` warts — use targeted `@SuppressWarnings` (mirrors `ToolInfer.scala`); the `update` raising variant needs `@SuppressWarnings(Array("org.wartremover.warts.Throw"))` (Decision: `update` raising mechanism).
- **R2 (Architecture)**: ✅ — `org.adk4s.optimize` package boundary is manual (no custom scalafix arch rules). Apply Step 12 appends the purity-rule row to `openspec/capability-profile.md`: MAY import cats-effect, fs2-core, structured-llm, ujson; MUST NOT import workflows4s, llm4s LLM client, adk4s-orchestration, adk4s-core.tools/component/interrupt. Enforced by code review + import audit (Ring 8).
- **R3 (Property tests)**: ✅ MANDATORY — Hedgehog 0.13.1 via `hedgehog-munit`. 14 properties (predictors-declaration-order, nested-recursion-paths, collection-recursion-paths, update-purity, update-only-target, round-trip-identity, updateAll-skips-frozen, updateAll-path-set-preserved, updateEither-unknown-path-error, updateEither-frozen-path-error, frozen-still-readable, optimizer-laws-purity, optimizer-laws-frozen-preserved, optimizer-laws-path-set-preserved). **No concurrent behavior** — `TestControl` NOT required.
- **R4 (Wire/persistence)**: — NOT applicable. State is serializable-ready by shape but no serialization is implemented or round-tripped in Phase 0 (deferred to Phase 2 per scope).
- **R5 (Mutation)**: deferred — sbt-stryker4s 0.21.0 + `stryker4s.conf` present. The production logic is `Mirror`-derived boilerplate + pure data covered by the laws suite. Retarget `mutate` list to `adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala` if a non-trivial helper emerges during implementation. Deferred unless implementation reveals a non-trivial pure helper.
- **R6 (Formal)**: — NOT applicable. The surface uses `Mirror`/`inline` and `ujson.Value` — not PureScala modules. Stainless is pinned to Scala 3.7.2 in `verified`; this module is on 3.8.4. Purity/round-trip laws enforced by Ring 3 Hedgehog properties instead.
- **R7 (Model checking)**: — NOT applicable. No TLA+/Apalache in the stack. No distributed/event-driven invariants.
- **R8 (Adversarial review)**: ✅ MANDATORY — fresh-context reviewer runs BEFORE R5/R6/R7. Checks for: silent fallback mappings in `update` (e.g. `case _ => p`), `case _` defaults swallowing `UnknownPath`/`FrozenPath`, partial functions in derivation, frozen-flag bypass via public APIs (e.g. a `PredictorState` with `frozen = true` that `updateAll` still mutates), private helpers that violate the purity law, `OptimizeError` extending `AdkError` (must stand alone — Decision: Error ADT placement), forbidden imports in the new module, `@SuppressWarnings` scopes broader than the single `update` method.
- **R9 (Telemetry)**: — NOT applicable. No otel4s/Daut in the stack. No API operations/event sequences affected.

**Typed Contract**: **full** — per the proposal's Typed Contract Decision table, this spec introduces a new module, a new typeclass (`Optimizable[P]`) with `Mirror`-based derivation semantics, new ADTs (`PredictorState`, `PredictorPath`, `Demo`, `OptimizeError`), a new leaf capability (`HasPredictorState`), and a new laws testkit (`OptimizerLaws`). This is the load-bearing erased surface for Phases 2–4 — full typed contract is mandatory.

## Expected Changed Production Files (Ring 5 targeting)

<!-- Per spec, the production files expected to change. Ring 5 dynamically
     retargets the Stryker mutate list to the files ACTUALLY changed by the
     spec (git diff against the spec's Step 0 baseline SHA), using this
     column as the starting estimate. NEVER rely on a fixed mutate list in
     stryker4s.conf. -->

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | `optimizable-surface` | `build.sbt` (new module wiring), `project/Dependencies.scala` (new main-scope munit/cats-effect/hedgehog variants), `adk4s-optimize/src/main/scala/org/adk4s/optimize/Demo.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorState.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/PredictorPath.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/OptimizeError.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/HasPredictorState.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/Optimizable.scala` (new — highest-value mutation target if Ring 5 is run), `adk4s-optimize/src/main/scala/org/adk4s/optimize/Predict0.scala` (new), `adk4s-optimize/src/main/scala/org/adk4s/optimize/testkit/OptimizerLaws.scala` (new), `adk4s-optimize/src/test/scala/org/adk4s/optimize/UppercaseInstructions.scala` (new — test fixture), `adk4s-optimize/src/test/scala/org/adk4s/optimize/StaticDemoInjector.scala` (new — test fixture), `adk4s-optimize/src/test/scala/org/adk4s/optimize/ToyPrograms.scala` (new — `TwoPredictors`, `Outer`, `Inner`, `Pipeline` test fixtures), `adk4s-optimize/src/test/scala/org/adk4s/optimize/Predict0Spec.scala` (new), `adk4s-optimize/src/test/scala/org/adk4s/optimize/OptimizableSpec.scala` (new — 11 Hedgehog properties + scenarios), `adk4s-optimize/src/test/scala/org/adk4s/optimize/ToyOptimizerSpec.scala` (new), `adk4s-optimize/src/test/scala/org/adk4s/optimize/OptimizerLawsSpec.scala` (new — 3 laws properties + pass/fail scenarios), `openspec/concepts/optimizable-surface.md` (new — apply Step 12), `openspec/concepts/predictor-state.md` (new — apply Step 12), `openspec/project.md` (edited — apply Step 12 appends `adk4s-optimize` to module graph), `openspec/capability-profile.md` (edited — apply Step 12 appends purity-rule row), `openspec/concept-inventory.md` (edited — apply Step 12 appends introduced concepts) |

**Ring 5 mutation targeting** (if un-deferred): the highest-value mutation target is `Optimizable.scala` (the `Mirror`-derived `predictors`/`update`/`updateEither`/`updateAll` logic — mutations here would surface as law/property failures). Secondary targets: `Predict0.scala` (`HasPredictorState` instance), `OptimizerLaws.scala` (law logic). The data types (`Demo`, `PredictorState`, `PredictorPath`, `OptimizeError`) are plain case classes/enum — low mutation value. Ring 5 dynamically retargets to the git-diff fileset, not this static list.

## Human Gate Tier

<!-- Per spec: `combined` (typed contract + test oracle presented at ONE
     gate) is allowed ONLY when complexity is `simple` AND the proposal's
     correctness risk is `low`. Everything else is `separate` (two gates —
     the default). Both steps are always executed in full either way; the
     tier changes only how many stops the human reviews. Rationale: human
     attention is the scarcest verification resource — a gate the human
     stops reading is worse than no gate. -->

| # | Spec | Tier (combined/separate) | Justification |
|---|------|--------------------------|---------------|
| 1 | `optimizable-surface` | separate | complexity=high (new types AND complex `Mirror`-based derivation logic AND load-bearing surface for Phases 2–4); proposal's correctness risk=medium ("the surface is load-bearing for Phases 2–4 and effectively irreversible once optimizers build on it; the `Mirror`-based derivation has ergonomics risk"). Both gates are executed in full: (gate 2) typed contract review, then (gate 3) test oracle polarity review. The two gates are separate stops so the human can catch contract drift before the oracle is built on top of it. |

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings. -->

**This spec is HIGH complexity.** It introduces 11 new concepts (3 data types, 2 typeclasses, 1 placeholder case class, 1 sealed error enum, 1 laws testkit, 2 toy optimizers, 1 sbt module) AND complex logic (`Mirror`-based structural derivation with a mixed leaf/subtree/`Vector` rule — the proposal's ⚠ VERIFY item 1). It does NOT involve Ring 6/7/9 (those are NOT applicable per the verification map), so the "involves Ring 6/7 or Ring 9" clause of the HIGH definition is not met — but the "new types AND complex logic" clause is met, which is sufficient for HIGH. The surface is load-bearing and effectively irreversible (proposal §Correctness Risk Level: medium), justifying full typed contract + all applicable rings + separate human gates.

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (combined-tier specs: merged into gate 3)
     3. Test oracle from spec + contract only (before implementation), run once for
        ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     5. Concept delta check (scanner diff) + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [ ] 1. `specs/optimizable-surface/spec.md` — Optimizable Predictor Surface (DSPy Port Phase 0): new `adk4s-optimize` module, `Optimizable[P]` typeclass with `Mirror`-based `derived`, `HasPredictorState[Self]` leaf capability, `PredictorState`/`PredictorPath`/`Demo` data types, `OptimizeError` ADT (`UnknownPath`/`FrozenPath`), `Predict0[F, I, O]` placeholder, `OptimizerLaws` testkit (purity/frozen-preserved/path-set-preserved), two toy optimizers (`UppercaseInstructions`/`StaticDemoInjector`) as acceptance test, 14 Hedgehog properties, concept docs + module-graph update. complexity=high, risk=medium, typed-contract=full, gates=separate.
