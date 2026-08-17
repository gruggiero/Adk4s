# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v12).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintain in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-iron-refined-types

**Schema**: verified-scala3 (v12)
**Specs**: 9 (error-hierarchy-dedup, core-types, harness-state, checkpoint-store-fpoly, memory-orchestration-hook, tools-node, structured-llm, react-agent, wio-graph)
**Human gate tier**: separate (specs 1-4, 8-9), combined (specs 5-7)

## Spec 1/9: error-hierarchy-dedup

- **BASELINE SHA**: `0c0cf393b7397715cde838e461d191691fa47dcd` (recorded 2026-08-12; working tree clean except untracked change dir + docs)
- **State**: in progress — Step 1

### Step 0 — baseline + module setup
- [x] working tree clean (untracked change dir + docs — unrelated)
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `0c0cf393b7397715cde838e461d191691fa47dcd`
- [x] read `openspec/concept-inventory.md` — verify `AdkError` row (23 variants) and `GraphValidationError` row
- [x] verify spec's Proof Obligations table is complete (all 3 requirements named)
- [x] registry-check.sh passes (OK, 795 tokens, 11 spec refs, 4 weak bindings pre-existing)
- [x] impact-scan.sh for AdkError: 110 refs in 18 files; 6 catch-all arms — ALL are Throwable/Option/HarnessResult matches, NOT AdkError matches; no updates needed
- [x] created openspec/concepts/core-types.md and openspec/concepts/error-hierarchy.md (registry concept files)
- [x] fixed behavioral table link format in all 9 specs

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `ConfigError(field: String, invalidValue: String, constraint: String)` extends `AdkError` with `message` field
- [x] `GraphCompilationError(errors: List[AdkError])` extends `AdkError` with `message` field
- [x] `IronErrorHierarchyTypeContract` in `adk4s-core/src/test/scala/org/adk4s/core/error/typecontract/` — 10 type conformance tests
- [x] compiles via `sbt adk4s-core/Test/compile` — success (no warnings)
- [x] `sbt adk4s-core/compile` passes — exhaustiveness escalation verified (no broken matches)
- [x] `sbt adk4s-orchestration/compile` passes — no broken matches downstream
- [ ] **STOP for human approval** ◄ WAITING

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `IronErrorHierarchySpec` in `adk4s-core/src/test/scala/org/adk4s/core/error/` — 7 scenario tests + 2 Hedgehog properties (Show round-trip, list preservation)
- [x] ORACLE POLARITY run: 19 tests, all GREEN-BY-DESIGN (trivial case class impl completed in Step 1)
  - Oracle caught 1 impl gap: GraphCompilationError.message didn't include error count → fixed to include `(N error(s))`
  - Seed for falsified run: 49576668894529715 (empty list case)
- [x] compiles via `sbt adk4s-core/Test/compile` — success (7 deprecation warnings on assert-with-clue, non-blocking)
- [x] all 19 tests pass: 10 type contract + 7 scenario + 2 properties
- [ ] **STOP for human approval** ◄ WAITING

### Step 3 — implementation
- [x] add `ConfigError` case class to `AdkError.scala` (done in Step 1)
- [x] add `GraphCompilationError` case class to `AdkError.scala` (done in Step 1)
- [x] fix all existing pattern matches over `AdkError` flagged by exhaustiveness escalation — none found (impact scan confirmed all matches use catch-all Throwable arms)
- [x] verify `sbt adk4s-core/compile` passes — success
- [x] verify `sbt adk4s-orchestration/compile` passes — success

### Rings
- [x] Ring R0 — `sbt adk4s-core/compile` + `sbt adk4s-core/Test/compile` + `sbt adk4s-orchestration/compile` pass; exhaustiveness escalation verified (no broken matches)
- [x] Ring R1 — WartRemover passed during compile (no new warts); danger-scan.sh clean; scalafmt/scalafix have pre-existing issues unrelated to this change
- [x] Ring R2 — import audit: no new imports added to AdkError.scala; `org.adk4s.core.error` imports only cats + existing types
- [x] Ring R3 — 19/19 tests pass (10 type contract + 7 scenario + 2 Hedgehog properties); cross-module regression: 269/269 orchestration tests pass
- [x] Ring R8 — adversarial review (fresh context): all 3 requirements PASS; no dangerous patterns; no `case _` defaults swallowing new variants

### Step 12 — concept-delta + inventory update + checkpoint
- [x] concept-delta check: `ConfigError`, `GraphCompilationError` added to AdkError sealed trait
- [x] update `openspec/concept-inventory.md`: AdkError row variants column gains `ConfigError`, `GraphCompilationError`
- [x] update `openspec/concepts/error-hierarchy.md`: variant list already updated
- [x] build-dependency delta: none (no new libraries)
- [x] spec cross-reference table added to spec.md
- [x] regenerate `tasks.md` from this file
- [x] COMMIT the spec — `0de98e5c911fbf1e7fec94c0133a3151d217298f`
- [ ] **STOP for human validation before next spec**

## Spec 2/9: core-types

- **BASELINE SHA**: `0de98e5c911fbf1e7fec94c0133a3151d217298f` (spec 1 commit)
- **State**: in progress — Step 1+2+3 combined (implementation done, rings pending)

### Step 0 — baseline + module setup
- [x] record baseline SHA — `0de98e5c911fbf1e7fec94c0133a3151d217298f` (spec 1 commit)
- [x] add Iron dependency: `iron` 3.3.2, `iron-cats` 3.3.2, `iron-upickle` 3.3.2 to `project/Versions.scala`, `project/Dependencies.scala`, `build.sbt` (adk4s-core)
- [x] compile-spike: verified Iron 3.3.2 + Scala 3.8.4 + JDK 26 — `scala-cli` tests confirmed `refineEither` extension method, `Not[Blank]` for NonEmpty, `numeric.Positive`/`Positive0` types
- [x] read `openspec/concept-inventory.md` — verified `NodeKey` row (opaque type, String)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `NodeKey` as `type NodeKey = NodeKey.T` with `object NodeKey extends RefinedType[String, NonEmpty & Not[Reserved]]` — opaque via Iron's `RefinedType`
- [x] `NonEmpty` defined as `Not[Blank]` (leverages Iron's compile-time checking; rejects empty + whitespace-only)
- [x] `Reserved` custom constraint with `given Constraint[String, Reserved]` — rejects `__start__` and `__end__`
- [x] `ReservedNodeKey` enum (`Start`, `End`) with `.value` returning `"__start__"` / `"__end__"`
- [x] `Positive` type alias (`Int :| numeric.Positive`) in `org.adk4s.core.types`
- [x] `NonNegative` type alias (`Int :| numeric.Positive0`) in `org.adk4s.core.types`
- [x] `NodeKeyTypeContract` in `adk4s-core/src/test/scala/org/adk4s/core/types/typecontract/` — 18 type conformance tests
- [x] compiles via `sbt adk4s-core/Test/compile` — success
- [x] **STOP for human approval** ◄ PASSED (user approved removing unsafeApply)

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `NodeKeyTest` updated — 18 tests (existing tests migrated from unsafeApply to NodeKey("literal") and NodeKey.either)
- [x] `NodeKeyTypeContract` — 18 tests (compile-time refinement, runtime either, ReservedNodeKey, Positive/NonNegative)
- [x] `ThrowSpec` updated — 4 tests (NodeKey.either is total, NodeKey.from typed error)
- [x] ORACLE POLARITY run: all tests GREEN (implementation completed in Step 1 per RefinedType pattern)
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] migrate `NodeKey.scala` to Iron `RefinedType[String, NonEmpty & Not[Reserved]]`
- [x] introduce `ReservedNodeKey` enum (replaces `NodeKey.START`/`NodeKey.END` constants)
- [x] add `Positive`/`NonNegative` aliases in `org.adk4s.core.types`
- [x] preserve `.value` extension (inherited from `Refined`), `Eq[NodeKey]`, `Show[NodeKey]`, `Order[NodeKey]` via `iron-cats`
- [x] remove `unsafeApply`, `NodeKey.apply` (Either overload), `NodeKey.START`, `NodeKey.END` — per user decision to eliminate asInstanceOf
- [x] update all call sites: `NodeKey.unsafeApply("literal")` → `NodeKey("literal")` (compile-time)
- [x] update runtime call sites: `NodeKey.unsafeApply(runtimeVar)` → `NodeKey.from(key)` with proper error handling
- [x] introduce `RouteTarget` sealed trait in `branch` package — replaces `NodeKey.END` as routing target
- [x] update `Branch.scala`, `Router.scala` — condition returns `RouteTarget`, not `NodeKey`
- [x] update `Graph.scala` — 11 methods use `NodeKey.from(key).toValidatedNec` pattern
- [x] update `Workflow.scala` — builder methods use `NodeKey.from(key).fold(throw _, identity)` with `@SuppressWarnings`
- [x] update `WIOGraph.scala` — `addNode` uses `NodeKey.from(key)` with `WIOGraphError.InvalidNodeKey`
- [x] update `Chain.scala` — `NodeKey("chain")` compile-time literal
- [x] update all test files (NodeKeyTest, NodeKeyTypeContract, ThrowSpec, RunInfoTest, BranchTest, BranchingIntegrationTest, WIOGraphTest, WIORunnableNodeTest, WIONodeModifierTest, WorkflowTest)
- [x] update all example files (11 files) — `NodeKey("literal")` + `import ...{Reserved, given}`
- [x] verify `sbt adk4s-core/compile` + `sbt adk4s-core/Test/compile` — success
- [x] verify `sbt adk4s-orchestration/compile` + `sbt adk4s-orchestration/Test/compile` — success
- [x] verify `sbt adk4s-examples/compile` — success
- [x] verify `sbt adk4s-core/test` — 421/421 passed
- [x] verify `sbt adk4s-orchestration/test` — 269/269 passed

### Rings
- [x] Ring R0 — compile-time Iron constraints for inline literals; `sbt compile` passes for all modules
- [x] Ring R1 — WartRemover passed (no asInstanceOf; @SuppressWarnings for Throw in Workflow.scala builder methods)
- [x] Ring R2 — `org.adk4s.core.types` imports only cats + iron (no cats-effect/fs2)
- [x] Ring R3 — 433/433 core tests pass (including 5 NodeKeySpec + 4 ConstraintSpec + 21 NodeKeyTypeContract); 269/269 orchestration tests pass
- [ ] Ring R5 — Stryker targets `NodeKey.scala` (pending — not blocking checkpoint)
- [x] Ring R8 — adversarial review (fresh context): 3 PARTIAL + 3 FAIL found → all fixed
  - Added `NodeKey.refineEither` returning `Either[ConfigError, NodeKey]` (spec-required API)
  - Created `NodeKeySpec.scala` with 5 Hedgehog properties (round-trip, rejection, ReservedNodeKey values, Eq, Show)
  - Created `ConstraintSpec.scala` with 4 Hedgehog properties (Positive/NonNegative accept/reject)
  - Added 3 compile-negative tests to `NodeKeyTypeContract` (empty literal, reserved literal, ReservedNodeKey-to-NodeKey assignment)
  - Pre-existing asInstanceOf in Tool.scala/WIOGraph.scala/WIONode.scala are @SuppressWarnings-annotated and unrelated to this spec

### Step 12 — concept-delta + inventory update + checkpoint
- [x] concept-delta: `NodeKey` (modified — now Iron RefinedType), `ReservedNodeKey` (new enum), `Positive` (new alias), `NonNegative` (new alias)
- [x] update `openspec/concept-inventory.md`: NodeKey constraint column updated; ReservedNodeKey, Positive, NonNegative rows added
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 3/9: harness-state

- **BASELINE SHA**: 97c34ca6628153b66d0a836ceb4b81747e43e877
- **State**: in progress

### Step 0 — baseline + module setup
- [x] record baseline SHA
- [x] add `iron-upickle` to adk4s-harness-api in `build.sbt` (also added `iron` + `iron-cats`)
- [x] read inventory: verify `MiddlewareName`, `StateCell.CellId` rows (lines 54-55)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `MiddlewareName` as `type MiddlewareName = MiddlewareName.T` via `RefinedType[String, NonEmpty]`
- [x] `StateCell.CellId` as `type CellId = CellId.T` via `RefinedType[String, NonEmpty & Match["[^/]+/[^/]+"]]`
- [x] `iron-upickle` ReadWriter instances for both types (via `import io.github.iltotore.iron.upickle.given`)
- [x] `HarnessStateTypeContract` — 3 compile-negative tests added (empty MW name, empty CellId, no-slash CellId)
- [x] compiles via `sbt adk4s-harness-api/Test/compile`
- [x] **STOP for human approval** ◄ PASSED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `MiddlewareNameSpec` — 3 scenario tests + 2 Hedgehog properties (refineEither round-trip + JSON round-trip)
- [x] `CellIdSpec` — 6 scenario tests + 2 Hedgehog properties (refineEither round-trip + JSON round-trip)
- [x] `HarnessStateSpec` — existing snapshot decoding test added (Ring 4 compatibility)
- [x] ORACLE POLARITY run — all 33 tests pass
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] migrate `MiddlewareName.scala` to `RefinedType[String, NonEmpty]`
- [x] migrate `StateCell.CellId` to `RefinedType[String, NonEmpty & Match["[^/]+/[^/]+"]]`
- [x] wire `iron-upickle` ReadWriter for both (via `given` import)
- [x] update `CellId.apply(owner, name)` to refine via `refineEither`
- [x] verify `sbt adk4s-harness-api/compile` + `sbt adk4s-harness-api/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [x] Ring R4 — iron-upickle round-trip + existing snapshot fixtures decode
- [ ] Ring R5 — Stryker targets `MiddlewareName.scala`, `StateCell.scala` (deferred)
- [x] Ring R8 — adversarial: no silent fallback for empty/malformed (compile-negative + runtime tests)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update inventory: MiddlewareName, CellId constraint columns
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 4/9: checkpoint-store-fpoly

- **BASELINE SHA**: 70650cede8798750a0ff4553f50a705fa7baab5e
- **State**: in progress

### Step 0 — baseline + module setup
- [x] record baseline SHA
- [x] add `ironUpickle` to adk4s-orchestration in `build.sbt`
- [x] read inventory: verify `CheckpointStore.CheckpointId` row (line 56 — transparent alias)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `CheckpointId` promoted to `type CheckpointId = CheckpointId.T` via `RefinedType[String, NonEmpty]`
- [x] `CheckpointStore[F]` method signatures updated (`get`, `set`, `delete`, `keys`)
- [x] `iron-upickle` ReadWriter for `CheckpointId` (via `import io.github.iltotore.iron.upickle.given`)
- [x] `CheckpointIdSpec` — compile-negative tests (raw String assignment, empty literal)
- [x] compiles via `sbt adk4s-orchestration/Test/compile`
- [x] **STOP for human approval** ◄ PASSED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `CheckpointIdSpec` — 4 scenario tests + 2 Hedgehog properties (refineEither round-trip, JSON round-trip)
- [x] `CheckpointStoreSpec` — updated existing tests for refined type, replaced transparent-alias tests with compile-negative
- [x] ORACLE POLARITY run — all 30 tests pass
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] promote `CheckpointId` from `type CheckpointId = String` to `RefinedType[String, NonEmpty]`
- [x] update `CheckpointStore[F]` trait signatures
- [x] update `InMemoryCheckpointStore` implementation
- [x] update `AgentRunner.saveInterrupt` and `AgentRunner.resume` to refine via `IO.fromEither`
- [x] update `InterruptibleNode.invoke` and `InterruptibleNode.resume` to refine via `IO.fromEither`
- [x] update all test call sites that pass raw `String` where `CheckpointId` is expected
- [x] verify `sbt adk4s-orchestration/compile` + `sbt adk4s-orchestration/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [x] Ring R4 — iron-upickle round-trip for CheckpointId
- [ ] Ring R5 — Stryker targets `CheckpointStore.scala` (deferred)
- [x] Ring R8 — adversarial: no raw String accepted as CheckpointId (compile-negative + runtime tests)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update inventory: CheckpointId (transparent alias → opaque refined)
- [x] update concept file: CheckpointId type description
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 5/9: memory-orchestration-hook

- **BASELINE SHA**: 1d3a751
- **State**: complete
- **Human gate tier**: combined (complexity=simple, risk=low)

### Step 0 — baseline
- [x] record baseline SHA
- [x] read inventory: verify `MemoryPolicy` row (line 188)

### Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE)
- [x] `MemoryPolicy.applyEither(recallK: Int): Either[ConfigError, MemoryPolicy]` using `refineEither[Positive0]`
- [x] throwing `apply` delegates to `applyEither` and throws `ConfigError` (source compat)
- [x] `recallK` field becomes `NonNegative` (`Int :| Positive0`)
- [x] `MemoryPolicySpec` — 8 scenario tests + 4 Hedgehog properties (render pure, round-trip, negatives rejected, default is 5)
- [x] ORACLE POLARITY run — all 12 tests pass
- [x] compiles via `sbt adk4s-orchestration/Test/compile`
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] remove `require(recallK >= 0, …)` from `MemoryPolicy`
- [x] add `applyEither` returning `Either[ConfigError, MemoryPolicy]`
- [x] keep throwing `apply` via `applyEither.fold(throw _, identity)` with `@targetName("applyThrowing")`
- [x] verify `sbt adk4s-orchestration/compile` + `sbt adk4s-orchestration/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [ ] Ring R5 — Stryker targets `MemoryPolicy.scala` (deferred)
- [x] Ring R8 — adversarial: no `IllegalArgumentException` from `applyEither` (returns ConfigError)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update inventory: MemoryPolicy.recallK constraint
- [x] update concept file: MemoryPolicy recallK type description
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 6/9: tools-node

- **BASELINE SHA**: 8e44610
- **State**: complete
- **Human gate tier**: combined

### Step 0 — baseline
- [x] record baseline SHA
- [x] read inventory: `ToolsNodeConfig` not in inventory as a type entry (config case class)

### Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE)
- [x] `ToolsNodeConfigBuilder.parallelEither(n: Int): Either[ConfigError, ToolsNodeConfigBuilder]` using `refineEither[Positive]`
- [x] throwing `parallel` delegates to `parallelEither.fold(throw _, identity)` with `@targetName("parallelThrowing")`
- [x] `ToolsNodeConfigTest` + `ToolsNodeConfigIronSpec` — 5 scenario tests + 2 Hedgehog properties (round-trip, zero/negatives rejected)
- [x] ORACLE POLARITY run — all 14 tests pass
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] add `parallelEither` to `ToolsNodeConfigBuilder`
- [x] refine `maxConcurrency` at boundary (field stays `Int`, refinement at builder)
- [x] preserve default `10`
- [x] verify `sbt adk4s-core/compile` + `sbt adk4s-core/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [ ] Ring R5 — Stryker targets `ToolsNodeConfig.scala` (deferred)
- [x] Ring R8 — adversarial: no silent acceptance of zero/negative (parallelEither returns ConfigError)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update concept file: ToolsNodeConfig maxConcurrency description
- [x] no inventory update needed (field type unchanged, refinement at boundary)
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 7/9: structured-llm

- **BASELINE SHA**: f33b376
- **State**: complete
- **Human gate tier**: combined

### Step 0 — baseline
- [x] record baseline SHA
- [x] read inventory: `StructuredLLM` not in inventory as a refined type (trait)

### Step 1+2 — typed contract + test oracle (COMBINED HUMAN GATE)
- [x] `StructuredLLM.fromClientWithMiddlewaresEither` refines `maxParseAttempts` via `refineEither[Positive]` at boundary
- [x] `StructuredLLM.fromClientWithRetryEither` refines `maxAttempts` via `refineEither[Positive]` at boundary
- [x] throwing `fromClientWithMiddlewares`/`fromClientWithRetry` delegate to `Either` variants and throw on `Left`
- [x] `StructuredLLMConfigSpec` — 4 scenario tests + 2 Hedgehog properties (round-trip, zero/negatives rejected)
- [x] ORACLE POLARITY run — all 6 tests pass
- [x] **STOP for human approval** ◄ PASSED
- [x] NOTE: `ConfigError` not used because `structured-llm` cannot depend on `adk4s-core` (circular); uses `Either[String, ...]` (Iron default)

### Step 3 — implementation
- [x] add `fromClientWithMiddlewaresEither` and `fromClientWithRetryEither` to `StructuredLLM`
- [x] throwing factories delegate to `Either` variants
- [x] preserve default `1`
- [x] existing `StructuredLLM` tests must still pass (all 116 structured-llm tests pass)
- [x] verify `sbt structured-llm/compile` + `sbt structured-llm/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [ ] Ring R5 — Stryker targets `StructuredLLM.scala` (deferred)
- [x] Ring R8 — adversarial: no silent acceptance of zero/negative (Either variants return Left)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update concept file: StructuredLLM maxParseAttempts description
- [x] no inventory update needed (factory boundary refinement, not a type change)
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 8/9: react-agent

- **BASELINE SHA**: cc57994
- **State**: complete

### Step 0 — baseline
- [x] record baseline SHA
- [x] read inventory: `ReactAgent`, `AgentRunner`, `HarnessAgent` rows verified

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `HarnessAgent.generate`/`resume` internally refine `maxSteps` via `refineEither[Positive]` (stream delegates to generate)
- [x] public params stay `Int`; `Async[F].raiseError(ConfigError(…))` on invalid
- [x] compiles via `sbt adk4s-orchestration/Test/compile`
- [x] **STOP for human approval** ◄ PASSED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `ReactAgentMaxStepsSpec` — 4 scenario tests (zero rejected, negative rejected, positive completes, default 10 valid)
- [x] `ReactAgentMaxStepsProps` — 2 Hedgehog properties (zero/negatives rejected, valid-input behavior preserved)
- [x] ORACLE POLARITY run — all 6 tests pass
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] add `refineMaxSteps` private helper to `HarnessAgent` using `refineEither[Positive]`
- [x] lift `ConfigError` into `F` via `Async[F].raiseError`
- [x] `generate` and `resume` wrapped with `refineMaxSteps(maxSteps).flatMap`
- [x] `stream` delegates to `generate` (already refined)
- [x] `AgentRunner` delegates to `HarnessAgent` (already refined)
- [x] preserve default `10`
- [x] existing `ReactAgentTest` must pass unchanged (all 290 orchestration tests pass)
- [x] verify `sbt adk4s-orchestration/compile` + `sbt adk4s-orchestration/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 (Hedgehog) — all pass
- [ ] Ring R5 — Stryker targets `HarnessAgent.scala` (deferred)
- [x] Ring R8 — adversarial: no behavioral change for valid inputs (property test confirms)

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update concept file: react-agent maxSteps description
- [x] no inventory update needed (internal refinement, public API unchanged)
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**

## Spec 9/9: wio-graph

- **BASELINE SHA**: 8ec64ff
- **State**: complete

### Step 0 — baseline
- [x] record baseline SHA
- [x] read inventory: `GraphExecutor`, `Graph`, `GraphValidationError` rows verified
- [x] read `GraphExecutor.scala` — identified dead commented-out throw blocks (lines 22-36, 70-162)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `GraphExecutor.execute`/`executeWithError`/`executeParallel` raise `GraphCompilationError` instead of `Exception`
- [x] `ValidatedGraph` as opaque type with `ValidatedGraph.from(g)` smart constructor
- [x] dead commented-out throw blocks deleted
- [x] compiles via `sbt adk4s-orchestration/compile`
- [x] **STOP for human approval** ◄ PASSED

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `GraphExecutorSpec` — 5 scenario tests (failed compile raises GraphCompilationError, matchable as AdkError, carries validation errors, ValidatedGraph.from rejects invalid graph, no commented-out throw blocks remain)
- [x] ORACLE POLARITY run — all 5 tests pass
- [x] **STOP for human approval** ◄ PASSED

### Step 3 — implementation
- [x] replace `IO.raiseError(new Exception(...))` with `IO.raiseError(GraphCompilationError(errors.toList))` in execute, executeWithError, executeParallel
- [x] introduce `ValidatedGraph` opaque type with `from(g)` that validates via `graph.validateGraph`
- [x] delete dead commented-out `toWIO` and `compileFromNodeUnsafe` blocks
- [x] verify `sbt adk4s-orchestration/compile` + `sbt adk4s-orchestration/Test/compile`

### Rings
- [x] Ring R0, R1, R2, R3 — all pass (295 orchestration tests)
- [ ] Ring R5 — Stryker targets `GraphExecutor.scala`, `ValidatedGraph.scala` (deferred)
- [x] Ring R8 — adversarial: no `case _` default in error handling; GraphCompilationError carries all errors

### Step 12 — concept-delta + inventory update + checkpoint
- [x] update concept file: graph.md compile action description
- [x] no inventory update needed (error type change, not a type addition to inventory)
- [ ] regenerate `tasks.md`; COMMIT; **STOP for human validation**
