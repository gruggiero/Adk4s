# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     The apply phase tracks detailed state in implementation-progress.md;
     tasks.md is regenerated from it at each checkpoint. -->

## 1. error-hierarchy-dedup ✓

- [x] Step 1 — typed contract: `ConfigError(field, invalidValue, constraint)` + `GraphCompilationError(errors: List[AdkError])` as AdkError variants; compile in adk4s-core/test (human gate)
- [x] Step 2 — test oracle: 7 scenarios + 2 Hedgehog properties (Show round-trip, list preservation) (human gate)
- [x] Step 3 — implementation: add variants to `AdkError.scala`; no existing pattern matches needed updating
- [x] Rings: R0 (compile + exhaustiveness), R1 (WartRemover + danger-scan), R2 (import audit), R3 (Hedgehog properties + 269 orchestration regression), R8 (adversarial: PASS)
- [x] Concept-delta check + update openspec/concept-inventory.md (AdkError row gains 2 variants) + checkpoint

## 2. core-types

- [ ] Prerequisite: add Iron dependency (`iron`, `iron-cats`) to `project/Versions.scala`, `project/Dependencies.scala`, `build.sbt` (adk4s-core); compile-spike with `NodeKey` only to verify Iron + Scala 3.8.4 + JDK 26
- [ ] Step 1 — typed contract: `NodeKey` as `String :| (NonEmpty & Not[Reserved])` with `RefinedTypeOps`; `ReservedNodeKey` enum; `Positive`/`NonNegative` aliases; compile in adk4s-core/test (human gate)
- [ ] Step 2 — test oracle: 4 scenarios + 3 Hedgehog properties (round-trip, rejection, ReservedNodeKey values) + 3 compile-negative stubs (human gate)
- [ ] Step 3 — implementation: migrate `NodeKey.scala`; introduce `ReservedNodeKey`; add `Positive`/`NonNegative` in `org.adk4s.core.types`; preserve `.value`/`Eq`/`Show`/`Order` via `iron-cats`
- [ ] Rings: R0 (compile-time Iron constraints), R1 (Scalafix RemoveUnused), R2 (`core.types` no effect imports), R3 (Hedgehog), R5 (Stryker targets `NodeKey.scala`), R8 (adversarial: no silent fallback in refinement)
- [ ] Concept-delta check + update openspec/concept-inventory.md (NodeKey constraint column; add ReservedNodeKey, Positive, NonNegative) + checkpoint

## 3. harness-state

- [ ] Prerequisite: add `iron-upickle` to adk4s-harness-api in `project/Dependencies.scala`
- [ ] Step 1 — typed contract: `MiddlewareName` as `String :| NonEmpty`; `StateCell.CellId` as `String :| (NonEmpty & Match["[^/]+/[^/]+"])`; `iron-upickle` ReadWriter instances; compile in adk4s-harness-api/test (human gate)
- [ ] Step 2 — test oracle: 6 scenarios + 3 Hedgehog properties (round-trip, JSON round-trip) + 3 compile-negative stubs; existing `HarnessState` snapshot fixtures must decode (human gate)
- [ ] Step 3 — implementation: migrate `MiddlewareName.scala`; migrate `StateCell.CellId`; wire `iron-upickle` ReadWriter; update `CellId.apply(owner, name)` to refine
- [ ] Rings: R0, R1, R2, R3 (Hedgehog), R4 (iron-upickle round-trip + existing snapshot fixtures), R5 (Stryker targets `MiddlewareName.scala`, `StateCell.scala`), R8 (adversarial: no silent fallback for empty/malformed)
- [ ] Concept-delta check + update openspec/concept-inventory.md (MiddlewareName, CellId constraint columns) + checkpoint

## 4. checkpoint-store-fpoly

- [ ] Step 1 — typed contract: `CheckpointId` promoted from transparent alias to opaque `String :| NonEmpty`; `CheckpointStore[F]` method signatures updated; `iron-upickle` ReadWriter; compile in adk4s-orchestration/test (human gate)
- [ ] Step 2 — test oracle: 5 scenarios + 2 Hedgehog properties (round-trip, JSON round-trip) + 2 compile-negative stubs (raw String assignment, empty literal) (human gate)
- [ ] Step 3 — implementation: promote `CheckpointId` to opaque refined; update `CheckpointStore[F]` trait (`get`, `set`, `delete`, `keys`); update `InMemoryCheckpointStore`; update all call sites that pass raw `String`
- [ ] Rings: R0, R1, R2, R3 (Hedgehog), R4 (iron-upickle round-trip for checkpoint metadata), R5 (Stryker targets `CheckpointStore.scala`, `InMemoryCheckpointStore.scala`), R8 (adversarial: no raw String accepted as CheckpointId)
- [ ] Concept-delta check + update openspec/concept-inventory.md (CheckpointId: transparent alias → opaque refined) + checkpoint

## 5. memory-orchestration-hook

- [ ] Step 1 — typed contract: `MemoryPolicy.applyEither(recallK): Either[ConfigError, MemoryPolicy]` using `refineEither[NonNegative]`; throwing `apply` delegates to `refineUnsafe`; `recallK` field becomes `Int :| NonNegative` (human gate, combined with test oracle)
- [ ] Step 2 — test oracle: 5 scenarios + 3 Hedgehog properties (round-trip, negatives rejected, default is 5) (human gate, combined)
- [ ] Step 3 — implementation: remove `require` from `MemoryPolicy`; add `applyEither`; keep throwing `apply` via `refineUnsafe`; update `MemoryPolicy.default`
- [ ] Rings: R0, R1, R2, R3 (Hedgehog), R5 (Stryker targets `MemoryPolicy.scala`), R8 (adversarial: no `IllegalArgumentException` from `applyEither`)
- [ ] Concept-delta check + update openspec/concept-inventory.md (MemoryPolicy.recallK constraint) + checkpoint

## 6. tools-node

- [ ] Step 1 — typed contract: `ToolsNodeConfigBuilder.parallelEither(n: Int): Either[ConfigError, ToolsNodeConfigBuilder]` using `refineEither[Positive]`; throwing `parallel` delegates to `refineUnsafe` (human gate, combined)
- [ ] Step 2 — test oracle: 4 scenarios + 2 Hedgehog properties (round-trip, zero/negatives rejected) (human gate, combined)
- [ ] Step 3 — implementation: add `parallelEither` to `ToolsNodeConfigBuilder`; refine `maxConcurrency` at boundary; preserve default `10`
- [ ] Rings: R0, R1, R2, R3 (Hedgehog), R5 (Stryker targets `ToolsNodeConfig.scala`), R8 (adversarial: no silent acceptance of zero/negative)
- [ ] Concept-delta check + update openspec/concept-inventory.md (ToolsNodeConfig.maxConcurrency constraint) + checkpoint

## 7. structured-llm

- [ ] Step 1 — typed contract: `StructuredLLM.fromClientWithMiddlewares` refines `maxParseAttempts` via `refineEither[Positive]` at boundary; returns `Either[ConfigError, …]` or lifts into `F[_]` (human gate, combined)
- [ ] Step 2 — test oracle: 4 scenarios + 2 Hedgehog properties (round-trip, zero/negatives rejected) (human gate, combined)
- [ ] Step 3 — implementation: add refinement boundary to `StructuredLLM` factories; preserve default `1`; existing tests must still pass
- [ ] Rings: R0, R1, R2, R3 (Hedgehog), R5 (Stryker targets `StructuredLLM.scala`), R8 (adversarial: no silent acceptance of zero/negative)
- [ ] Concept-delta check + update openspec/concept-inventory.md (StructuredLLM.maxParseAttempts constraint) + checkpoint

## 8. react-agent

- [ ] Step 1 — typed contract: `ReactAgent.generate`/`stream`, `AgentRunner.run`, `HarnessAgent.generate`/`resume`/`stream` internally refine `maxSteps` via `refineEither[Positive]`; public params stay `Int`; `IO.raiseError(ConfigError(…))` on invalid (human gate)
- [ ] Step 2 — test oracle: 5 scenarios + 2 Hedgehog properties (valid-input behavior preserved with `DeterministicChatModel`, zero/negatives rejected) (human gate)
- [ ] Step 3 — implementation: add internal `refineEither[Positive]` boundary to each method; lift `ConfigError` into `IO`; preserve default `10`; existing `ReactAgentTest` must pass unchanged
- [ ] Rings: R0, R1, R2, R3 (Hedgehog with `DeterministicChatModel`), R5 (Stryker targets `ReactAgent.scala`, `AgentRunner.scala`, `HarnessAgent.scala`), R8 (adversarial: no behavioral change for valid inputs)
- [ ] Concept-delta check + update openspec/concept-inventory.md (ReactAgent/AgentRunner/HarnessAgent maxSteps constraint) + checkpoint

## 9. wio-graph

- [ ] Step 1 — typed contract: `ValidatedGraph` as `Graph :| GraphValidated` with `ValidatedGraph.from(g)`; `GraphExecutor.execute`/`executeWithError` raise `GraphCompilationError` instead of `Exception`; `executeGraph`/`executeGraphParallel` accept `ValidatedGraph`; compile in adk4s-orchestration/test (human gate)
- [ ] Step 2 — test oracle: 7 scenarios + 2 Hedgehog properties (ValidatedGraph matches compile success, GraphCompilationError carries all errors) + 1 compile-negative stub (ValidatedGraph from raw Graph) (human gate)
- [ ] Step 3 — implementation: introduce `ValidatedGraph`; replace `IO.raiseError(new Exception(…))` with `IO.raiseError(GraphCompilationError(errors))`; update `executeGraph`/`executeGraphParallel` to accept `ValidatedGraph`; delete dead commented-out `throw` blocks (lines ~22-36, ~70-162)
- [ ] Rings: R0, R1 (Scalafix RemoveUnused catches dead code), R2, R3 (Hedgehog), R5 (Stryker targets `GraphExecutor.scala`, `ValidatedGraph.scala`), R8 (adversarial: no `case _` default in error handling, no silent fallback in ValidatedGraph construction)
- [ ] Concept-delta check + update openspec/concept-inventory.md (add ValidatedGraph; GraphExecutor error path) + checkpoint
