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
| 1 | `specs/harness-state/spec.md` | `HarnessState`, `StateCell[A]`, `CellVisibility` (Private/Inherited/Shared), `StateDecodeError`, `MiddlewareName`, `PromptSection`, `SystemPrompt`, `HarnessStateKernel` (Ring 6 mirror), `harness-state` concept doc, `adk4s-harness-api` module | `JsonValue` (= `smithy4s.Document`), `JsonValueCodec`, `AdkError` (all pre-existing in `adk4s-core`) | high |
| 2 | `specs/agent-middleware/spec.md` | `AgentMiddleware[F[_]]`, `ModelRequest[F]`/`ModelResponse`, `ToolCallCtx`/`ToolCallOut`, `ModelStep[F]`/`ToolStep[F]` (Kleisli aliases), `ToolStep.passthrough` lift, `AgentMiddleware.id`, `agent-middleware` concept doc | `HarnessState`, `StateCell`, `MiddlewareName`, `PromptSection`/`SystemPrompt` (from spec 1); `InvokableTool[F]`, `ToolInput`/`ToolOutput`, `ChatModel[F]`, `Completion`, `CompletionOptions`, `Message` (all pre-existing) | high |
| 3 | `specs/middleware-stack/spec.md` | `MiddlewareStack[F]`, `StackError` (DuplicateCellId/DuplicateToolName), `MiddlewareStack.empty`/`validated`/`++`, `StackKernel` (Ring 6 mirror for project/mergeBack), `middleware-stack` concept doc | `AgentMiddleware[F]` (from spec 2); `StateCell.CellId`, `HarnessState`, `MiddlewareName` (from spec 1); `Kleisli`, `Monad`/`Applicative` (cats) | high |
| 4 | `specs/checkpoint-store-fpoly/spec.md` | `CheckpointStore[F[_]]` (generalized), `CheckpointStateV2`, `CheckpointMessage`, `CheckpointToolCall`, `CheckpointId` (transparent alias), `checkpoint-store` concept doc update, `agent-runner` concept doc update | `HarnessState` (from spec 1, for `snapshot`/`restore`); `InterruptSignal` (pre-existing); `AgentRunner`, `RunResult`, `CheckpointNotFoundError` (pre-existing, refactor targets); `upickle.default.ReadWriter` (pre-existing) | high |
| 5 | `specs/harness-agent/spec.md` | `HarnessAgent[F[_]]`, `HarnessResult` (Completed/Interrupted/Failed), `HarnessAgent.Config[F]`, `IOHarnessAgent` type alias, `ReactAgent.create` sugar refactor, `ReactAgentImpl` refactor, `AgentRunner` refactor (resume gains restore), `react-agent`/`agent-runner` concept doc updates | `MiddlewareStack[F]` (from spec 3); `AgentMiddleware[F]`, `ModelRequest`/`ModelResponse`, `ModelStep`/`ToolStep` (from spec 2); `HarnessState`, `StateCell` (from spec 1); `CheckpointStateV2`, `CheckpointStore[F]` (from spec 4 — `AgentRunner.resume` uses `HarnessState.restore` on `cp.harnessState`); `ReactAgent`, `AgentRunner`, `RunResult`, `ChatModel[F]`, `InvokableTool[F]`, `AgentEvent`/`AgentEventEmitter`, `InterruptSignal`/`AgentInterruptedException`, `ToolsNode`, `StreamedChunk` (all pre-existing) | high |
| 6 | `specs/middleware-laws/spec.md` | `AgentMiddlewareLaws` (L0–L10), `SemilatticeLaws` (L11), `DeterministicChatModel` test double, `SemilatticeKernel` (Ring 6 mirror), `adk4s-harness-testkit` module, `agent-middleware` concept doc (laws section) | `AgentMiddleware[F]` (from spec 2); `MiddlewareStack[F]` (from spec 3); `HarnessState`, `StateCell` (from spec 1); `HarnessAgent[F]`, `HarnessResult` (from spec 5 — L0 gatekeeper compares empty-stack harness vs `ReactAgentImpl`); `ReactAgent`/`ReactAgentImpl` (pre-existing — L0 comparison target); `ChatModel[F]`, `InvokableTool[F]`, `AgentMemoryLaws` (pattern reference, pre-existing); Hedgehog `HedgehogSuite`/`property`, `munit.FunSuite`/`munit.CatsEffectSuite`, `TestControl` | high |

**Dependency graph** (topological sort):

```
spec 1 (harness-state)
├── spec 2 (agent-middleware)        [needs HarnessState, StateCell, MiddlewareName, PromptSection/SystemPrompt]
│   └── spec 3 (middleware-stack)    [needs AgentMiddleware, StateCell.CellId, HarnessState]
│       └── spec 5 (harness-agent)   [needs MiddlewareStack, AgentMiddleware, HarnessState, CheckpointStateV2]
│           └── spec 6 (middleware-laws) [needs HarnessAgent, MiddlewareStack, AgentMiddleware, HarnessState]
└── spec 4 (checkpoint-store-fpoly)  [needs HarnessState only]
    └── spec 5 (harness-agent)       [AgentRunner.resume uses HarnessState.restore on cp.harnessState]
```

**Topological order**: 1 → 2 → 3 → 4 → 5 → 6.

- Spec 1 (`harness-state`) is the foundation — no deps on new concepts.
- Spec 2 (`agent-middleware`) builds on `HarnessState`/`StateCell`/`MiddlewareName`/`PromptSection`.
- Spec 3 (`middleware-stack`) builds on `AgentMiddleware` and `StateCell.CellId`.
- Spec 4 (`checkpoint-store-fpoly`) builds on `HarnessState` only (for `snapshot`/`restore`); it is independent of specs 2–3 but must precede spec 5 because `harness-agent`'s resume scenario depends on `AgentRunner.resume` having gained `HarnessState.restore` (introduced by spec 4).
- Spec 5 (`harness-agent`) builds on `MiddlewareStack` (spec 3) and `CheckpointStateV2`/`CheckpointStore[F]` (spec 4).
- Spec 6 (`middleware-laws`) builds on all of the above — L0 compares `HarnessAgent(MiddlewareStack.empty)` vs `ReactAgentImpl`, so it must follow spec 5.

**Intra-spec ordering** (the apply phase implements these in this order, driven by each spec's Implementation Anchors and the design's package layers — API/data types first, then combinators, then tests, then concept docs):

For each spec, the general intra-spec order is:
1. **Build wiring** (if a new module is introduced) — `build.sbt` + `project/Dependencies.scala`: add the module + deps. Must compile empty before any source is added.
2. **Domain data types** — pure case classes / sealed enums / final classes.
3. **Leaf capabilities** — typeclasses, traits, companion-object factories.
4. **Combinators / aggregation** — stack composition, validated construction, fold-based hooks.
5. **Ring 6 mirror** (if applicable) — PureScala model in `verified/`; bridge test in the owning module's test sources.
6. **Testkit** (if applicable) — main-scope laws class, deterministic double, generators.
7. **Test specs** — Hedgehog properties, munit scenarios, compile-negative tests, typed contracts.
8. **Concept docs + inventory update** — apply Step 12.

## Ring Applicability

<!-- For each spec, determine which rings apply based on the proposal's
     verification strategy AND the spec's own sections.
     R3 and R8 are MANDATORY for every code-changing spec.
     The Typed Contract column is full / minimal / waiver (waiver requires
     explicit human approval; only for docs/formatting/test-only specs). -->

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | `harness-state` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | ✅ | — | ✅ (MANDATORY) | — | full |
| 2 | `agent-middleware` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | — | — | ✅ (MANDATORY) | — | full |
| 3 | `middleware-stack` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | ✅ | — | ✅ (MANDATORY) | — | full |
| 4 | `checkpoint-store-fpoly` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ | ✅ | — | — | ✅ (MANDATORY) | — | full |
| 5 | `harness-agent` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | ✅ | ✅ | — | — | ✅ (MANDATORY) | — | full |
| 6 | `middleware-laws` | ✅ | ✅ | ✅ | ✅ (MANDATORY) | — | ✅ | ✅ | — | ✅ (MANDATORY) | — | full |

**Ring-by-ring rationale** (from design.md §Verification Map):

- **R0 (Compile)**: ✅ all specs — `sbt compile` across the new + refactored modules. Exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) applies to `StackError` (spec 3), `HarnessResult` (spec 5), and `CellVisibility` (spec 1) from day one. `adk4s-examples/compile` is the source-compatibility gate for spec 5 (`ReactAgent.create` sugar).

- **R1 (Lint)**: ✅ all specs — Scalafix (DisableSyntax + RemoveUnused + OrganizeImports + `NoUjsonIn*`) + WartRemover (`Warts.unsafe` minus `TripleQuestionMark`/`Any`/`DefaultArguments`). ⚠ VERIFY: the single `asInstanceOf` in `HarnessState.get` (spec 1) needs `@SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))` scoped to the one method — the design doc justifies it via the construction-time id-uniqueness invariant + the Ring 6 mirror. The `verified` module is exempt (`wartremoverErrors := Seq.empty`, Scala 3.7.2).

- **R2 (Architecture)**: ✅ all specs — `org.adk4s.harness` and `org.adk4s.harness.testkit` package boundaries are manual (no custom scalafix arch rules). Enforced by code review + import audit (Ring 8). The new packages MUST NOT import workflows4s, llm4s LLM client, adk4s-orchestration, fs2-io, or logback (design §Package Structure).

- **R3 (Property tests)**: ✅ MANDATORY all specs — Hedgehog 0.13.1 via `hedgehog-munit`. Properties per spec:
  - spec 1: `get-set-coherence`, `set-preserves-other-cells`, `snapshot-restore-round-trip`, `restore-unknown-fields-ignored`, `restore-new-cells-default-initial`, `restore-corrupted-cell-hard-error`, `project-private-initial`, `project-inherited-shared-parent`, `mergeBack-shared-folds`, `mergeBack-private-inherited-unchanged`, `mergeBack-order-independence-semilattice`
  - spec 2: (scenario tests for hook defaults, tool/section contributions, `AgentMiddleware.id` neutrality)
  - spec 3: `monoid-identity-any-position`, `monoid-associativity`, `hook-distribution-wrapModelCall`, `disjoint-commutativity`, `validated-duplicate-detection`
  - spec 4: `V1-read-compatibility`, `V2-round-trip`, `full-fidelity-preservation`, `harnessState-snapshot-round-trip`, `F-polymorphism` (inMemory get/set/delete/keys)
  - spec 5: `empty-stack-equivalence` (L0 gatekeeper), `per-request-prompt-folding`, `per-request-tool-list`, `afterAgent-skipped-on-interrupt`, `parallel-tool-merge-order-independence` (CONCURRENCY — `TestControl`)
  - spec 6: `L0-conservative-refactor` through `L10-merge-back-neutrality`, `L11-semilattice-commutativity`/`associativity`/`idempotence`, `L11-mergeBack-order-independence` (CONCURRENCY — `TestControl`)
  - **Concurrency scenarios** (spec 5 parallel-tool-merge, spec 6 L0 interrupt, L11 mergeBack-order-independence) MUST use `TestControl` — never wall-clock sleeps.

- **R4 (Wire/persistence)**: ✅ spec 4 (`checkpoint-store-fpoly`) — touches persisted checkpoint data (`CheckpointState` v1 → `CheckpointStateV2` v2); ✅ spec 5 (`harness-agent`) — `ReactAgent.create` API surface (55+ examples source compatibility). — specs 1, 2, 3, 6 — no persisted/wire data.

- **R5 (Mutation)**: ✅ specs 1–6 — sbt-stryker4s 0.21.0 + `stryker4s.conf` retarget per spec's changed files. Highest-value targets: `HarnessState.scala` (spec 1), `MiddlewareStack.scala` (spec 3), `HarnessAgent.scala`/`HarnessResult.scala` (spec 5), `AgentMiddlewareLaws.scala` (spec 6). Threshold 85%.

- **R6 (Formal)**: ✅ spec 1 (`HarnessStateKernel` — get/set coherence), ✅ spec 3 (`StackKernel` — project/mergeBack + order-independence), ✅ spec 6 (`SemilatticeKernel` — commutativity/associativity/idempotence). — specs 2, 4, 5 — effectful or codec-bound; no PureScala-expressible kernel with a decision/fold at its centre. The `verified` leaf module gains `HarnessStateKernel.scala` (spec 1), `StackKernel.scala` (spec 3), `SemilatticeKernel.scala` (spec 6); bridge tests run in `adk4s-harness-api`/`adk4s-harness-testkit` test sources (`dependsOn(verified % Test)`, `stainlessEnabled := false`).

- **R7 (Model checking)**: — NOT applicable (no TLA+/Apalache in the stack).

- **R8 (Adversarial review)**: ✅ MANDATORY all specs — fresh-context reviewer runs BEFORE R5/R6/R7. Per-spec review focus:
  - spec 1: `asInstanceOf` scope in `HarnessState.get`, id-uniqueness invariant enforcement in `validated`, `snapshot`/`restore` leniency (unknown fields ignored, missing cells default, corrupted cell is hard error), `Private` cell privacy in `project`/`mergeBack`
  - spec 2: `AgentMiddleware.id` is truly neutral (no cells/tools/sections, identity hooks), `promptSections(state)` is state-aware (not construction-time), `wrapModelCall`/`wrapToolCall` defaults pass through
  - spec 3: private constructor (no direct `MiddlewareStack(List(...)`), `validated` accumulates ALL errors (not fail-fast), `StackError` exhaustiveness, `mergeBack` purity (no in-place mutation), stack-order semantics (beforeAgent forward, afterAgent reverse, wraps m1-outermost)
  - spec 4: v1-read compat (absent `version` ⇒ v1, missing fields default), v2 round-trip fidelity (`toolCalls`/`toolCallId` preserved), `harnessState` round-trip, `resume` returns `Failed` on corrupted cell (not silent data loss), `CheckpointId` transparent alias (not opaque), no `asInstanceOf` in checkpoint code
  - spec 5: empty-stack equivalence (L0 gatekeeper — refactor MUST NOT merge until green), per-request tool/prompt derivation (not construction-time baking), `afterAgent` skipped on interrupt, `ReactAgent.create` signature unchanged (no `MiddlewareStack` param), `Async[F]` context bound, event emission stays in loop (middlewares wrap, not replace)
  - spec 6: `DeterministicChatModel` has no wall-clock/I/O dependence, no `Arbitrary` in Hedgehog generators (explicit `Gen` + `Range`), `AgentMiddlewareLaws` in main scope (not Test), testkit has no heavy deps (no neo4j/lucene/http4s), `TestControl` used for concurrency scenarios

- **R9 (Telemetry)**: — NOT applicable (no otel4s/Daut in the stack).

**Typed Contract**: **full** for all 6 specs — each introduces new types (case classes, sealed traits/enums, traits, type aliases) and the change is load-bearing for Phases 1–3 (deepagents4s). The proposal's correctness risk is **high** (typed heterogeneous map with unchecked cast, core loop refactor across 55+ examples, persisted checkpoint format change). Full typed contract is mandatory for every spec.

## Expected Changed Production Files (Ring 5 targeting)

<!-- Per spec, the production files expected to change. Ring 5 dynamically
     retargets the Stryker mutate list to the files ACTUALLY changed by the
     spec (git diff against the spec's Step 0 baseline SHA), using this
     column as the starting estimate. NEVER rely on a fixed mutate list in
     stryker4s.conf. -->

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | `harness-state` | `build.sbt` (new `adk4s-harness-api` module + `dependsOn(adk4s-core)` + `dependsOn(verified % Test)`), `project/Dependencies.scala` (new deps if any), `adk4s-harness-api/src/main/scala/org/adk4s/harness/HarnessState.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateCell.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/CellVisibility.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareName.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/PromptSection.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/SystemPrompt.scala` (new), `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateDecodeError.scala` (new), `verified/src/main/scala/org/adk4s/verified/HarnessStateKernel.scala` (new — Ring 6 mirror), `adk4s-harness-api/src/test/scala/org/adk4s/harness/HarnessStateSpec.scala` (new), `adk4s-harness-api/src/test/scala/org/adk4s/harness/HarnessStateBoundarySpec.scala` (new — project/mergeBack), `adk4s-harness-api/src/test/scala/org/adk4s/harness/HarnessStateKernelBridgeSpec.scala` (new — Ring 6 bridge), `adk4s-harness-api/src/test/scala/org/adk4s/harness/Generators.scala` (new), `adk4s-harness-api/src/test/scala/org/adk4s/harness/typecontract/HarnessStateTypeContract.scala` (new), `openspec/concepts/harness-state.md` (new — apply Step 12), `openspec/concept-inventory.md` (edited — apply Step 12) |
| 2 | `agent-middleware` | `adk4s-harness-api/src/main/scala/org/adk4s/harness/AgentMiddleware.scala` (new — trait + `AgentMiddleware.id` + `ModelRequest`/`ModelResponse`/`ToolCallCtx`/`ToolCallOut`/`ModelStep`/`ToolStep`), `adk4s-harness-api/src/main/scala/org/adk4s/harness/ToolStep.scala` (new — `passthrough` lift), `adk4s-harness-api/src/test/scala/org/adk4s/harness/AgentMiddlewareSpec.scala` (new), `adk4s-harness-api/src/test/scala/org/adk4s/harness/typecontract/AgentMiddlewareTypeContract.scala` (new), `openspec/concepts/agent-middleware.md` (new — apply Step 12), `openspec/concept-inventory.md` (edited — apply Step 12) |
| 3 | `middleware-stack` | `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareStack.scala` (new — private constructor, `empty`/`validated`/`++`/`allCells`/`allTools`/`allSections(state)`/`beforeAgent`/`afterAgent`/`wrapModelCall`/`wrapToolCall`), `adk4s-harness-api/src/main/scala/org/adk4s/harness/StackError.scala` (new — enum), `verified/src/main/scala/org/adk4s/verified/StackKernel.scala` (new — Ring 6 mirror), `adk4s-harness-api/src/test/scala/org/adk4s/harness/MiddlewareStackSpec.scala` (new), `adk4s-harness-api/src/test/scala/org/adk4s/harness/MiddlewareStackLawsSpec.scala` (new), `adk4s-harness-api/src/test/scala/org/adk4s/harness/StackKernelBridgeSpec.scala` (new — Ring 6 bridge), `adk4s-harness-api/src/test/scala/org/adk4s/harness/typecontract/MiddlewareStackTypeContract.scala` (new), `openspec/concepts/middleware-stack.md` (new — apply Step 12), `openspec/concept-inventory.md` (edited — apply Step 12) |
| 4 | `checkpoint-store-fpoly` | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala` (modified — generalized to `CheckpointStore[F[_]]` + `inMemory[F[_]: Sync]` + `type CheckpointId = String`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/CheckpointStateV2.scala` (new — or in `AgentRunner.scala`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala` (modified — `run` builds `CheckpointStateV2`; `resume` reads as `CheckpointStateV2` + calls `HarnessState.restore`), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/interrupt/CheckpointStoreTest.scala` (modified — `CheckpointStore.inMemory[IO]`), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/CheckpointStateV2Spec.scala` (new), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/AgentRunnerResumeSpec.scala` (new), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/Generators.scala` (new — checkpoint generators), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/interrupt/typecontract/CheckpointStoreTypeContract.scala` (new), `openspec/concepts/checkpoint-store.md` (edited — apply Step 12), `openspec/concepts/agent-runner.md` (edited — apply Step 12), `openspec/concept-inventory.md` (edited — apply Step 12) |
| 5 | `harness-agent` | `build.sbt` (add `adk4s-orchestration .dependsOn(adk4s-harness-api)`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala` (new — `F[_]: Async`, `generate`/`stream`, `Config`, `IOHarnessAgent`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessResult.scala` (new — sealed trait), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala` (modified — `create` delegates to `HarnessAgent[IO]` with `MiddlewareStack.empty`), `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgentImpl.scala` (modified — loop body moves to `HarnessAgent`; retired or delegates), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/HarnessAgentSpec.scala` (new — L0 gatekeeper + per-request + interrupt + parallel-merge), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/typecontract/HarnessAgentTypeContract.scala` (new — compile-negative + signature contract), `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/Generators.scala` (new — harness-agent generators), `stryker4s.conf` (retarget `mutate` to `**/agent/HarnessAgent.scala`, `**/agent/HarnessResult.scala`), `openspec/concepts/react-agent.md` (edited — apply Step 12), `openspec/concepts/agent-runner.md` (edited — apply Step 12), `openspec/concept-inventory.md` (edited — apply Step 12) |
| 6 | `middleware-laws` | `build.sbt` (new `adk4s-harness-testkit` module + `dependsOn(adk4s-harness-api)` + `dependsOn(verified % Test)` + main-scope `cats-effect`/`munit`/`hedgehog-munit` + root aggregation), `project/Dependencies.scala` (new main-scope munit/hedgehog variants if not already present), `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/AgentMiddlewareLaws.scala` (new — L0–L10), `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/SemilatticeLaws.scala` (new — L11), `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/DeterministicChatModel.scala` (new), `adk4s-harness-testkit/src/main/scala/org/adk4s/harness/testkit/Generators.scala` (new), `verified/src/main/scala/org/adk4s/verified/SemilatticeKernel.scala` (new — Ring 6 mirror), `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/AgentMiddlewareLawsSpec.scala` (new), `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/SemilatticeLawsSpec.scala` (new), `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/DeterministicChatModelSpec.scala` (new), `adk4s-harness-testkit/src/test/scala/org/adk4s/harness/testkit/StackKernelBridgeSpec.scala` (new — Ring 6 bridge), `adk4s-harness-api/src/test/scala/org/adk4s/harness/SemilatticeModelBridgeSpec.scala` (new — Ring 6 bridge in harness-api test sources), `stryker4s.conf` (retarget `mutate` to `**/harness/testkit/*.scala`), `openspec/concepts/agent-middleware.md` (edited — apply Step 12, laws section), `openspec/concept-inventory.md` (edited — apply Step 12) |

**Ring 5 mutation targeting**: the highest-value mutation targets per spec are:
- spec 1: `HarnessState.scala` (get/set/project/mergeBack/snapshot/restore logic)
- spec 3: `MiddlewareStack.scala` (validated duplicate detection, fold-based hooks, wrap composition)
- spec 5: `HarnessAgent.scala` (loop orchestration, per-request derivation, interrupt handling), `HarnessResult.scala` (outcome shape)
- spec 6: `AgentMiddlewareLaws.scala` (L0–L10 law logic), `SemilatticeLaws.scala` (L11 law logic)

Data types (`CellVisibility`, `StackError`, `PromptSection`, `CheckpointMessage`, etc.) are plain case classes/enums — low mutation value. Ring 5 dynamically retargets to the git-diff fileset, not this static list.

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
| 1 | `harness-state` | separate | complexity=high (new types AND Ring 6 mirror AND single unchecked cast with invariant-based safety argument); proposal correctness risk=high. The typed heterogeneous map is the foundation every other spec builds on — contract drift here propagates everywhere. Two gates: (gate 2) typed contract review (cast justification, id-uniqueness, visibility semantics), then (gate 3) test oracle polarity review. |
| 2 | `agent-middleware` | separate | complexity=high (new trait with 4 hooks + state-aware `promptSections` + Kleisli-based request/response model); proposal correctness risk=high. The middleware trait is the extension point for all Phase 1–3 middlewares — contract drift here breaks every downstream middleware. Two gates. |
| 3 | `middleware-stack` | separate | complexity=high (new types AND Ring 6 mirror for project/mergeBack AND monoid laws); proposal correctness risk=high. The stack is the composition algebra — getting the order semantics or validation wrong silently breaks every stack-bearing agent. Two gates. |
| 4 | `checkpoint-store-fpoly` | separate | complexity=high (F-polymorphic trait generalization + persisted format change + v1-read compat); proposal correctness risk=high (changes persisted checkpoint format). A v1-read compat bug or a fidelity loss in `CheckpointStateV2` silently corrupts resumed conversations. Two gates. |
| 5 | `harness-agent` | separate | complexity=high (core loop refactor AND `ReactAgent.create` source compatibility across 55+ examples AND interrupt/resume semantics); proposal correctness risk=high. The L0 gatekeeper is the merge gate — the refactor MUST NOT merge until empty-stack equivalence is green. Two gates: (gate 2) typed contract review (loop shape, `Async[F]` bound, `HarnessResult` exhaustiveness, sugar signature), then (gate 3) test oracle polarity review (L0 oracle must be red before implementation, green after). |
| 6 | `middleware-laws` | separate | complexity=high (new testkit module AND Ring 6 mirror AND L0–L11 laws); proposal correctness risk=high. The laws are the statement that the middleware monoid structure is observationally real — a law that trivially passes (e.g. L6 without the disjointness precondition check) is worse than no law. Two gates. |

All 6 specs are `separate` tier — complexity is high across the board and the proposal's correctness risk is high. Every gate is executed in full; the two gates are separate stops so the human can catch contract drift before the oracle is built on top of it.

## Complexity Guide

<!-- Complexity determines review depth.

     SIMPLE: No new types, ≤1 new method on existing trait, no new error variants.
             Typed contract: minimal. Rings: 0, 1, 3, 8 minimum.

     MEDIUM: New types OR complex business logic OR new error handling paths.
             Typed contract: full. Rings: 0, 1, 2, 3, 5, 8.

     HIGH:   New types AND complex logic AND involves Ring 6/7 or Ring 9.
             Typed contract: full. All applicable rings. -->

**All 6 specs are HIGH complexity.** Each introduces new types AND complex logic:

- **spec 1 (harness-state)**: new types (`HarnessState`, `StateCell[A]`, `CellVisibility`, `StateDecodeError`, `MiddlewareName`, `PromptSection`, `SystemPrompt`) AND complex logic (typed heterogeneous map with single unchecked cast, visibility-based project/mergeBack, snapshot/restore with leniency) AND Ring 6 (`HarnessStateKernel` mirror).
- **spec 2 (agent-middleware)**: new types (`AgentMiddleware[F]`, `ModelRequest`/`ModelResponse`, `ToolCallCtx`/`ToolCallOut`, `ModelStep`/`ToolStep`) AND complex logic (four-hook trait with state-aware `promptSections`, Kleisli-based wrapping, `ToolStep.passthrough` lift). No Ring 6 (trait is effectful), but the complexity is high from the type design alone.
- **spec 3 (middleware-stack)**: new types (`MiddlewareStack[F]`, `StackError`) AND complex logic (monoid with stack-order semantics, validated construction with error accumulation, fold-based hooks) AND Ring 6 (`StackKernel` mirror for project/mergeBack).
- **spec 4 (checkpoint-store-fpoly)**: new types (`CheckpointStore[F]`, `CheckpointStateV2`, `CheckpointMessage`, `CheckpointToolCall`, `CheckpointId`) AND complex logic (F-polymorphic generalization with source compatibility, v1→v2 read compat with optional fields, full-fidelity message preservation) AND Ring 4 (persisted data).
- **spec 5 (harness-agent)**: new types (`HarnessAgent[F]`, `HarnessResult`, `HarnessAgent.Config`, `IOHarnessAgent`) AND complex logic (ReAct loop refactor with per-request tool/prompt derivation, interrupt-snapshot-without-afterAgent, parallel tool-call state merge, `ReactAgent.create` sugar) AND Ring 4 (API surface compatibility across 55+ examples).
- **spec 6 (middleware-laws)**: new types (`AgentMiddlewareLaws`, `SemilatticeLaws`, `DeterministicChatModel`, `adk4s-harness-testkit` module) AND complex logic (L0–L11 observational-equivalence laws with preconditions, semilattice laws, deterministic double) AND Ring 6 (`SemilatticeKernel` mirror).

The "involves Ring 6/7 or Ring 9" clause of the HIGH definition is met by specs 1, 3, 6 (Ring 6). Specs 2, 4, 5 meet the "new types AND complex logic" clause, which is sufficient for HIGH. Ring 7 and Ring 9 are NOT applicable to any spec (no TLA+/Apalache, no otel4s/Daut).

## Implementation Sequence

<!-- Process each spec in this exact order. For each spec:
     1. Record baseline SHA (clean tree) + inventory snapshot; read
        openspec/concept-inventory.md — import existing concepts; verify the spec's
        Proof Obligations table is complete
     2. Typed contract (mandatory) — genuinely compiled in test sources
        → human review GATE (combined-tier specs: merged into gate 3)
     3. Test oracle from spec + contract only (before implementation), run once
        for ORACLE POLARITY (red / green-by-design)
        → human review GATE
     4. Implement through all applicable rings (see table above) — Ring 8
        adversarial review (fresh context) runs BEFORE Rings 5/6/7
     5. Concept delta check (scanner diff) + build-dependency delta +
        update openspec/concept-inventory.md
     6. Mark checkbox below, regenerate tasks.md, COMMIT the spec
     7. STOP for human validation before next spec

     DO NOT skip ahead. DO NOT batch-implement. One spec at a time. -->

- [ ] 1. `specs/harness-state/spec.md` — HarnessState (typed heterogeneous map): new `adk4s-harness-api` module, `HarnessState` (total get, immutable set/update, snapshot/restore), `StateCell[A]` (mandatory ReadWriter, visibility, merge), `CellVisibility` (Private/Inherited/Shared), `StateDecodeError`, `MiddlewareName` (opaque), `PromptSection`/`SystemPrompt`, `HarnessStateKernel` Ring 6 mirror (get/set coherence), project/mergeBack boundary operations, 11+ Hedgehog properties, concept doc + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
- [ ] 2. `specs/agent-middleware/spec.md` — AgentMiddleware[F] (four-hook trait): `AgentMiddleware[F]` trait (beforeAgent/afterAgent/wrapModelCall/wrapToolCall + stateCells/tools/promptSections(state)), `ModelRequest`/`ModelResponse`/`ToolCallCtx`/`ToolCallOut`, `ModelStep`/`ToolStep` Kleisli aliases, `ToolStep.passthrough` lift, `AgentMiddleware.id`, scenario tests for hook defaults + state-aware promptSections, concept doc + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
- [ ] 3. `specs/middleware-stack/spec.md` — MiddlewareStack[F] (monoid + validated construction): `MiddlewareStack[F]` (private constructor, empty/validated/++, allCells/allTools/allSections(state), fold-based beforeAgent/afterAgent, foldRight wrapModelCall/wrapToolCall), `StackError` enum (DuplicateCellId/DuplicateToolName), `StackKernel` Ring 6 mirror (project/mergeBack + order-independence), monoid + hook-distribution + disjoint-commutativity + validated-duplicate-detection properties, concept doc + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
- [ ] 4. `specs/checkpoint-store-fpoly/spec.md` — CheckpointStore[F] + CheckpointStateV2 (F-polymorphic + full-fidelity): `CheckpointStore[F[_]]` (generalized from IO-fixed, inMemory[F[_]: Sync], CheckpointId transparent alias), `CheckpointStateV2` (version=2, full-fidelity CheckpointMessage/CheckpointToolCall, harnessState: JsonValue), v1-read compat decoder, `AgentRunner.run`/`resume` refactor (resume gains HarnessState.restore), V1-read-compat + V2-round-trip + full-fidelity + harnessState-round-trip + F-polymorphism properties, concept doc updates + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
- [ ] 5. `specs/harness-agent/spec.md` — HarnessAgent[F] (ReAct loop refactor): `HarnessAgent[F[_]: Async]` (generate/stream, Config, IOHarnessAgent), `HarnessResult` (Completed/Interrupted/Failed), `ReactAgent.create` sugar (delegates to HarnessAgent[IO] with MiddlewareStack.empty — 55+ examples source-compatible), `ReactAgentImpl` refactor (loop body moves to HarnessAgent), `AgentRunner` refactor (resume re-enters loop), per-request tool/prompt derivation, interrupt-snapshot-without-afterAgent, parallel tool-call state merge, L0 empty-stack-equivalence gatekeeper + per-request + interrupt + parallel-merge properties, concept doc updates + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
- [ ] 6. `specs/middleware-laws/spec.md` — AgentMiddlewareLaws + SemilatticeLaws (law testkit): new `adk4s-harness-testkit` module (main-scope munit/hedgehog), `AgentMiddlewareLaws` (L0–L10 observational-equivalence properties), `SemilatticeLaws` (L11 commutativity/associativity/idempotence + mergeBack-order-independence), `DeterministicChatModel` test double (scripted, trace-recording, no wall-clock), `SemilatticeKernel` Ring 6 mirror, Hedgehog generators (explicit Gen + Range, no Arbitrary), L0 gatekeeper (empty-stack harness ≍ ReactAgentImpl), concept doc update + inventory update. complexity=high, risk=high, typed-contract=full, gates=separate.
