# Tasks

<!-- Regenerated from implementation-progress.md (verified-scala3 schema v12).
     tasks.md is DERIVED OUTPUT — do not hand-maintain in parallel with
     implementation-progress.md (dual trackers drift). The progress file is
     the single source of truth for the apply phase. -->

## 1. error-hierarchy-dedup ✓

- [x] Step 1 — typed contract: `ConfigError(field, invalidValue, constraint)` + `GraphCompilationError(errors: List[AdkError])` as AdkError variants; compile in adk4s-core/test (human gate — PASSED)
- [x] Step 2 — test oracle: 7 scenarios + 2 Hedgehog properties (Show round-trip, list preservation) (human gate — PASSED)
- [x] Step 3 — implementation: add variants to `AdkError.scala`; no existing pattern matches needed updating (impact scan confirmed all matches use catch-all Throwable arms)
- [x] Rings: R0 (compile + exhaustiveness), R1 (WartRemover + danger-scan), R2 (import audit), R3 (19 tests + 269 orchestration regression), R8 (adversarial: 3/3 PASS)
- [x] Concept-delta check + update openspec/concept-inventory.md (AdkError row gains 2 variants) + checkpoint — committed `0de98e5`

## 2. core-types ✓

- [x] Prerequisite: add Iron dependency (`iron`, `iron-cats`, `iron-upickle` 3.3.2) to `project/Versions.scala`, `project/Dependencies.scala`, `build.sbt` (adk4s-core); compile-spike verified Iron 3.3.2 + Scala 3.8.4 + JDK 26
- [x] Step 1 — typed contract: `NodeKey` as `RefinedType[String, NonEmpty & Not[Reserved]]`; `ReservedNodeKey` enum; `Positive`/`NonNegative` aliases; 18 type-conformance tests (human gate — PASSED, user approved removing unsafeApply)
- [x] Step 2 — test oracle: 18 NodeKeyTest + 18 NodeKeyTypeContract + 4 ThrowSpec + 5 NodeKeySpec + 4 ConstraintSpec Hedgehog properties (human gate — PASSED)
- [x] Step 3 — implementation: migrate `NodeKey.scala`; introduce `ReservedNodeKey`; add `Positive`/`NonNegative` in `org.adk4s.core.types`; preserve `.value`/`Eq`/`Show`/`Order` via `iron-cats`; remove `unsafeApply`/`NodeKey.START`/`NodeKey.END`; update all call sites (Branch, Router, Graph, Workflow, WIOGraph, Chain, 11 example files, all test files)
- [x] Rings: R0 (compile-time Iron constraints), R1 (WartRemover — @SuppressWarnings for Throw in Workflow.scala), R2 (`core.types` imports only cats + iron), R3 (433 core + 269 orchestration tests), R8 (adversarial: 3 PARTIAL + 3 FAIL found → all fixed: added `refineEither`, NodeKeySpec, ConstraintSpec, 3 compile-negative tests)
- [ ] Ring R5 — Stryker targets `NodeKey.scala` (DEFERRED — not blocking checkpoint)
- [x] Concept-delta check + update openspec/concept-inventory.md (NodeKey constraint column; add ReservedNodeKey, Positive, NonNegative) — checkpoint pending commit

## 3. harness-state ✓

- [x] Prerequisite: add `iron` + `iron-cats` + `iron-upickle` to adk4s-harness-api in `build.sbt`
- [x] Step 1 — typed contract: `MiddlewareName` as `RefinedType[String, NonEmpty]`; `StateCell.CellId` as `RefinedType[String, NonEmpty & Match["[^/]+/[^/]+"]]`; `iron-upickle` ReadWriter instances; 3 compile-negative tests (human gate — PASSED)
- [x] Step 2 — test oracle: MiddlewareNameSpec (3 scenarios + 2 Hedgehog), CellIdSpec (6 scenarios + 2 Hedgehog), HarnessStateSpec snapshot decoding; 33 tests pass (human gate — PASSED)
- [x] Step 3 — implementation: migrate `MiddlewareName.scala`; migrate `StateCell.CellId`; wire `iron-upickle` ReadWriter; update `CellId.apply(owner, name)` to refine via `refineEither`
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R4 (iron-upickle round-trip + existing snapshot fixtures decode), R8 (adversarial: no silent fallback for empty/malformed)
- [ ] Ring R5 — Stryker targets `MiddlewareName.scala`, `StateCell.scala` (DEFERRED)
- [x] Concept-delta check + update openspec/concept-inventory.md (MiddlewareName, CellId constraint columns) — checkpoint pending commit

## 4. checkpoint-store-fpoly ✓

- [x] Prerequisite: add `ironUpickle` to adk4s-orchestration in `build.sbt`
- [x] Step 1 — typed contract: `CheckpointId` promoted to `RefinedType[String, NonEmpty]`; `CheckpointStore[F]` method signatures updated (`get`, `set`, `delete`, `keys`); `iron-upickle` ReadWriter; compile-negative tests (human gate — PASSED)
- [x] Step 2 — test oracle: CheckpointIdSpec (4 scenarios + 2 Hedgehog), CheckpointStoreSpec updated; 30 tests pass (human gate — PASSED)
- [x] Step 3 — implementation: promote `CheckpointId` to opaque refined; update `CheckpointStore[F]` trait; update `InMemoryCheckpointStore`; update `AgentRunner.saveInterrupt`/`resume` + `InterruptibleNode.invoke`/`resume` to refine via `IO.fromEither`; update all test call sites
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R4 (iron-upickle round-trip for CheckpointId), R8 (adversarial: no raw String accepted as CheckpointId)
- [ ] Ring R5 — Stryker targets `CheckpointStore.scala` (DEFERRED)
- [x] Concept-delta check + update openspec/concept-inventory.md (CheckpointId: transparent alias → opaque refined) + update concept file — checkpoint pending commit

## 5. memory-orchestration-hook ✓

- [x] Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE — PASSED): `MemoryPolicy.applyEither(recallK): Either[ConfigError, MemoryPolicy]` using `refineEither[Positive0]`; throwing `apply` delegates via `@targetName("applyThrowing")`; `recallK` field becomes `NonNegative`; MemoryPolicySpec (8 scenarios + 4 Hedgehog properties); 12 tests pass
- [x] Step 3 — implementation: remove `require(recallK >= 0, …)`; add `applyEither`; keep throwing `apply` via `applyEither.fold(throw _, identity)`; preserve default `5`
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R8 (adversarial: no `IllegalArgumentException` from `applyEither`)
- [ ] Ring R5 — Stryker targets `MemoryPolicy.scala` (DEFERRED)
- [x] Concept-delta check + update openspec/concept-inventory.md (MemoryPolicy.recallK constraint) + update concept file — checkpoint pending commit

## 6. tools-node ✓

- [x] Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE — PASSED): `ToolsNodeConfigBuilder.parallelEither(n: Int): Either[ConfigError, ToolsNodeConfigBuilder]` using `refineEither[Positive]`; throwing `parallel` delegates via `@targetName("parallelThrowing")`; ToolsNodeConfigTest + ToolsNodeConfigIronSpec (5 scenarios + 2 Hedgehog properties); 14 tests pass
- [x] Step 3 — implementation: add `parallelEither` to `ToolsNodeConfigBuilder`; refine `maxConcurrency` at boundary (field stays `Int`); preserve default `10`
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R8 (adversarial: no silent acceptance of zero/negative)
- [ ] Ring R5 — Stryker targets `ToolsNodeConfig.scala` (DEFERRED)
- [x] Concept-delta check + update concept file (ToolsNodeConfig maxConcurrency description) — no inventory update needed (field type unchanged, refinement at boundary) — checkpoint pending commit

## 7. structured-llm ✓

- [x] Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE — PASSED): `StructuredLLM.fromClientWithMiddlewaresEither` + `fromClientWithRetryEither` refine via `refineEither[Positive]` at boundary; throwing factories delegate to `Either` variants; StructuredLLMConfigSpec (4 scenarios + 2 Hedgehog properties); 6 tests pass. NOTE: uses `Either[String, ...]` (Iron default) not `ConfigError` — structured-llm cannot depend on adk4s-core (circular)
- [x] Step 3 — implementation: add `fromClientWithMiddlewaresEither` and `fromClientWithRetryEither` to `StructuredLLM`; throwing factories delegate; preserve default `1`; all 116 structured-llm tests pass
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R8 (adversarial: no silent acceptance of zero/negative)
- [ ] Ring R5 — Stryker targets `StructuredLLM.scala` (DEFERRED)
- [x] Concept-delta check + update concept file (StructuredLLM maxParseAttempts description) — no inventory update needed (factory boundary refinement) — checkpoint pending commit

## 8. react-agent ✓

- [x] Step 1 — typed contract (HUMAN GATE — PASSED): `HarnessAgent.generate`/`resume` internally refine `maxSteps` via `refineEither[Positive]` (stream delegates to generate); public params stay `Int`; `Async[F].raiseError(ConfigError(…))` on invalid
- [x] Step 2 — test oracle (HUMAN GATE — PASSED): ReactAgentMaxStepsSpec (4 scenarios) + ReactAgentMaxStepsProps (2 Hedgehog properties); 6 tests pass
- [x] Step 3 — implementation: add `refineMaxSteps` private helper to `HarnessAgent`; lift `ConfigError` into `F` via `Async[F].raiseError`; `generate`/`resume` wrapped with `refineMaxSteps(maxSteps).flatMap`; `stream` delegates to `generate`; `AgentRunner` delegates to `HarnessAgent`; preserve default `10`; all 290 orchestration tests pass
- [x] Rings: R0, R1, R2, R3 (Hedgehog), R8 (adversarial: no behavioral change for valid inputs — property test confirms)
- [ ] Ring R5 — Stryker targets `HarnessAgent.scala` (DEFERRED)
- [x] Concept-delta check + update concept file (react-agent maxSteps description) — no inventory update needed (internal refinement, public API unchanged) — checkpoint pending commit

## 9. wio-graph ✓

- [x] Step 1 — typed contract (HUMAN GATE — PASSED): `GraphExecutor.execute`/`executeWithError`/`executeParallel` raise `GraphCompilationError` instead of `Exception`; `ValidatedGraph` as opaque type with `ValidatedGraph.from(g)` smart constructor; dead commented-out throw blocks deleted
- [x] Step 2 — test oracle (HUMAN GATE — PASSED): GraphExecutorSpec (5 scenarios — failed compile raises GraphCompilationError, matchable as AdkError, carries validation errors, ValidatedGraph.from rejects invalid graph, no commented-out throw blocks remain); 5 tests pass
- [x] Step 3 — implementation: replace `IO.raiseError(new Exception(...))` with `IO.raiseError(GraphCompilationError(errors.toList))`; introduce `ValidatedGraph` opaque type with `from(g)` validating via `graph.validateGraph`; delete dead commented-out `toWIO` and `compileFromNodeUnsafe` blocks
- [x] Rings: R0, R1, R2, R3 (295 orchestration tests), R8 (adversarial: no `case _` default in error handling; GraphCompilationError carries all errors)
- [ ] Ring R5 — Stryker targets `GraphExecutor.scala`, `ValidatedGraph.scala` (DEFERRED)
- [x] Concept-delta check + update concept file (graph.md compile action description) — no inventory update needed (error type change) — checkpoint pending commit

## Summary

- **Specs complete**: 9/9 (all Steps 0–3 + Rings R0–R4, R8)
- **Human gates passed**: all (separate gates for specs 1–4, 8–9; combined gates for specs 5–7)
- **Code committed**: `5c2db2e` "Added Iron refined types" (all 9 specs in one bulk commit; spec 1 also has per-spec checkpoint `0de98e5`)
- **Ring R5 (Stryker)**: DEFERRED for all 9 specs — conscious deferral, not a failure
- **Verification (just run)**: compile PASS (core, orchestration, structured-llm, examples); tests PASS (437 core + 295 orchestration + 116 structured-llm = 848 total, 0 failures)
- **Remaining before archive**: resolve `chain-state.sh` exit 2; decide whether R5 must run or is permanently waived; run `openspec-archive-change`
