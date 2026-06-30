# Implementation Order

<!-- This artifact determines the EXACT sequence for depth-first implementation.
     Each spec is processed one at a time through all applicable verification rings.
     The order is based on concept dependency analysis: a spec that introduces
     a concept must come before any spec that uses that concept. -->

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | specs/wartremover-option-partial/spec.md | (none) | `NodeKey`, `AdkError`, `CheckpointStore`, `StateRef`, Tool tier | simple |
| 2 | specs/wartremover-iterable-ops/spec.md | (none) | `RunPath`, `FieldPath`, `AdkError` | simple |
| 3 | specs/wartremover-var/spec.md | (none) | `SchemaAlignedParser`, `ToolMiddleware`, `AdkError` | medium |
| 4 | specs/wartremover-throw/spec.md | `NodeKeyError` | `AdkError`, `NodeKey`, `AgentInterruptedException`, `WIOGraphError`, `GraphExecutor` | high |
| 5 | specs/wartremover-asinstanceof/spec.md | (none — ToolDispatchResult withdrawn) | Tool tier, `ToolWrapper`, `SafeToolExecutable`, `ToolSchema`, `WIONode`, `WIONodeModifier`, `AdkError` | high |
| 6 | specs/wartremover-default-arguments/spec.md | (none) | `AdkToolInfo`, `ToolWrapper`, `RunResult`, `Runnable` | medium |
| 7 | specs/wartremover-any-string-plus-any/spec.md | (none) | `AdkError`, `InterruptSignal`, `AgentEvent` | simple |

**Dependency graph**: Specs 1–3 and 5–7 introduce no concepts, so they have
no inter-spec concept dependencies. Spec 4 introduces `NodeKeyError` (extends
`AdkError`); no other spec consumes `NodeKeyError`, so spec 4 is independent
of the others. The ordering is therefore by **rising risk/complexity**
(per design.md "Incremental, depth-first ordering"), with the constraint
that spec 4 (`Throw`) lands before spec 5 (`AsInstanceOf`) because the
`ToolInfer.decodeProduct` refactor in spec 5 removes a `try/catch` whose
`throw`-removal is owned by spec 4's error-strategy decision — implementing
Throw first means the AsInstanceOf spec's `decodeProduct` rewrite starts
from an already-throw-free baseline.

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | option-partial | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | minimal |
| 2 | iterable-ops | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | minimal |
| 3 | var | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅* | — | ✅ | — | minimal |
| 4 | throw | ✅ | ✅ | ✅ | ✅ | — | ✅ | ✅* | — | ✅ | — | full |
| 5 | asinstanceof | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 6 | default-arguments | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | minimal |
| 7 | any-string-plus-any | ✅ | ✅ | — | ✅ | — | — | — | — | ✅ | — | minimal |

✅* = Ring 6 optional candidate for the pure functions (`NodeKey.from`,
`JsonFixMiddleware` recursion); not required, no `verified`-module mirror
obligation. R4 (wire/persistence) is absent — no serialization/wire changes.
R9 (telemetry) is absent — no otel4s.

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | option-partial | `adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolInfer.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateRef.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateHandlers.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/GraphExecutor.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/DynamicToolRegistry.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/GraphValidation.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/InterruptibleNode.scala`, `adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala` (+ test sources) |
| 2 | iterable-ops | (files using `.init`/`.last` — identified by grep at apply time) |
| 3 | var | `adk4s-core/src/main/scala/org/adk4s/core/tools/JsonFixMiddleware.scala` (+ test mocks: `ComponentMockLLMClient.scala`, `RetrieverSpec.scala`) |
| 4 | throw | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala`, `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` (add `NodeKeyError`), `adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/GraphExecutor.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONode.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala` (+ test sources) |
| 5 | asinstanceof | `adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolInfer.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraph.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONode.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIONodeModifier.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/GraphExecutor.scala` |
| 6 | default-arguments | (case classes / methods with `= ` defaults — identified by grep at apply time, across all modules incl. examples) |
| 7 | any-string-plus-any | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`, `adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/WIOGraphError.scala`, `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolSchema.scala` (and other `s"..."` sites flagged by WartRemover) |

Ring 5 retargets `stryker4s.conf` `mutate` to the **git-diff** of each spec's
production files (never the fixed list). Threshold 90% (pure domain logic).

## Complexity Guide

- **SIMPLE** (specs 1, 2, 7): No new types, mechanical replacements. Typed
  contract: minimal. Rings: 0, 1, 3, 8.
- **MEDIUM** (specs 3, 6): Internal rewrite (Var recursion) or widespread
  API-surface change (DefaultArguments factories). Typed contract: minimal.
  Rings: 0, 1, 2, 3, 5, 8.
- **HIGH** (specs 4, 5): New error variant + control-flow change (Throw) and
  erased-type dispatch redesign (AsInstanceOf). Typed contract: full. Rings:
  0, 1, 2, 3, 5, 8 (+ optional 6).

## Implementation Sequence

- [ ] 1. `specs/wartremover-option-partial/spec.md` — replace `.get` on Option with total ops; re-enable `Wart.OptionPartial`
- [ ] 2. `specs/wartremover-iterable-ops/spec.md` — replace `.init`/`.last` with `dropRight(1)`/`lastOption`; re-enable `Wart.IterableOps`
- [ ] 3. `specs/wartremover-var/spec.md` — rewrite `JsonFixMiddleware` var state as recursion/fold; replace test `var` with `Ref[IO, _]`; re-enable `Wart.Var`
- [ ] 4. `specs/wartremover-throw/spec.md` — remove `throw` keyword; add `NodeKeyError`; switch to `Either[AdkError, A]` / `F.raiseError(AdkError)`; re-enable `Wart.Throw`
- [ ] 5. `specs/wartremover-asinstanceof/spec.md` — replace `asInstanceOf` dispatch with pattern matches over sealed `WIONode` + type-string match; re-enable `Wart.AsInstanceOf`
- [ ] 6. `specs/wartremover-default-arguments/spec.md` — replace defaults with companion factories/overloads; re-enable `Wart.DefaultArguments`
- [ ] 7. `specs/wartremover-any-string-plus-any/spec.md` — replace `s"..."` widening interpolations with `+` concatenation; re-enable `Wart.Any` + `Wart.StringPlusAny`

For each spec: read concept-inventory → typed contract (compiled in test
sources) → human review GATE → test oracle from spec+contract → human review
GATE → implement through applicable rings incl. Ring 8 adversarial review →
concept delta check + update concept-inventory → mark checkbox → STOP for
human validation before next spec.
