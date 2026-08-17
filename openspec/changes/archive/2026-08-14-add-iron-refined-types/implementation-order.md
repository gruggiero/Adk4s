# Implementation Order

## Dependency Analysis

| # | Spec | Introduces | Depends On (concepts) | Complexity |
|---|------|-----------|----------------------|------------|
| 1 | error-hierarchy-dedup | `ConfigError`, `GraphCompilationError` | `AdkError`, `NodeKeyError`, `GraphValidationError` | medium — 2 new error variants + exhaustiveness migration |
| 2 | core-types | `NodeKey` (refined), `ReservedNodeKey`, `Positive`, `NonNegative` | `NodeKey`, `NodeKeyError`, `AdkError`, `RunInfo`, `ConfigError` (from #1) | high — new refined types + constraint aliases + compile-negative + Iron dep |
| 3 | harness-state | `MiddlewareName` (refined), `StateCell.CellId` (refined) | `MiddlewareName`, `StateCell.CellId`, `StateCell`, `ConfigError` (from #1), `NonEmpty` (Iron, from #2) | medium — 2 refined types + iron-upickle bridge + Ring 4 compat |
| 4 | checkpoint-store-fpoly | `CheckpointStore.CheckpointId` (refined) | `CheckpointStore.CheckpointId`, `CheckpointStore`, `ConfigError` (from #1), `NonEmpty` (Iron, from #2) | medium — transparent alias → opaque refined + API signature changes |
| 5 | memory-orchestration-hook | (none — reuses `NonNegative`) | `MemoryPolicy`, `NonNegative` (from #2), `ConfigError` (from #1) | simple — `require` → `refineEither`, one field |
| 6 | tools-node | (none — reuses `Positive`) | `ToolsNodeConfig`, `ToolsNodeConfigBuilder`, `Positive` (from #2), `ConfigError` (from #1) | simple — one field refined + `parallelEither` overload |
| 7 | structured-llm | (none — reuses `Positive`) | `StructuredLLM`, `Positive` (from #2), `ConfigError` (from #1) | simple — one factory param refined |
| 8 | react-agent | (none — reuses `Positive`) | `ReactAgent`, `AgentRunner`, `HarnessAgent`, `Positive` (from #2), `ConfigError` (from #1) | medium — 3 classes, internal boundary, regression risk |
| 9 | wio-graph | `ValidatedGraph` | `GraphExecutor`, `Graph`, `GraphValidationError`, `GraphCompilationError` (from #1) | high — proof-carrying type + error path migration + dead-code deletion |

## Ring Applicability

| # | Spec | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 | Typed Contract |
|---|------|----|----|----|----|----|----|----|----|----|----|----|
| 1 | error-hierarchy-dedup | ✅ | ✅ | ✅ | ✅ | — | — | — | — | ✅ | — | full |
| 2 | core-types | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 3 | harness-state | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | full |
| 4 | checkpoint-store-fpoly | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | — | ✅ | — | full |
| 5 | memory-orchestration-hook | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 6 | tools-node | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 7 | structured-llm | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 8 | react-agent | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |
| 9 | wio-graph | ✅ | ✅ | ✅ | ✅ | — | ✅ | — | — | ✅ | — | full |

**Ring notes**:
- R0 (compile): all specs — exhaustiveness escalation for #1; Iron compile-time constraints for #2-#4
- R1 (static): WartRemover/Scalafix — dead-code removal in #9
- R2 (architecture): layer rules — `org.adk4s.core.types` must not import effect libs
- R3 (property): Hedgehog properties in all specs
- R4 (compatibility): #3, #4 — iron-upickle round-trip + existing snapshot fixtures
- R5 (mutation): all code-changing specs — Stryker targets the changed files
- R6 (formal): none — no algorithmic candidates (per design)
- R7 (model check): none
- R8 (adversarial): all specs — fresh-context review
- R9 (temporal): none — no concurrent behavior

## Expected Changed Production Files (Ring 5 targeting)

| # | Spec | Expected Files |
|---|------|----------------|
| 1 | error-hierarchy-dedup | `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala` |
| 2 | core-types | `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala`, `project/Versions.scala`, `project/Dependencies.scala`, `build.sbt` |
| 3 | harness-state | `adk4s-harness-api/src/main/scala/org/adk4s/harness/MiddlewareName.scala`, `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateCell.scala`, `project/Dependencies.scala` |
| 4 | checkpoint-store-fpoly | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/InMemoryCheckpointStore.scala` |
| 5 | memory-orchestration-hook | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryPolicy.scala` |
| 6 | tools-node | `adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala` |
| 7 | structured-llm | `structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala` |
| 8 | react-agent | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala` |
| 9 | wio-graph | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/GraphExecutor.scala`, `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala` (or new `ValidatedGraph.scala`) |

## Human Gate Tier

| # | Spec | Tier | Justification |
|---|------|------|---------------|
| 1 | error-hierarchy-dedup | separate | complexity=medium; new error variants affect exhaustiveness across modules |
| 2 | core-types | separate | complexity=high; new Iron dependency + refined types + compile-negative |
| 3 | harness-state | separate | complexity=medium; Ring 4 compatibility risk (snapshots) |
| 4 | checkpoint-store-fpoly | separate | complexity=medium; source-incompatible API change |
| 5 | memory-orchestration-hook | combined | complexity=simple, correctness risk=low (per proposal: Tier B, medium value) |
| 6 | tools-node | combined | complexity=simple, risk=low |
| 7 | structured-llm | combined | complexity=simple, risk=low |
| 8 | react-agent | separate | complexity=medium; regression risk (ReAct loop behavior) |
| 9 | wio-graph | separate | complexity=high; proof-carrying type + error path migration + dead-code deletion |

## Complexity Guide

- **SIMPLE** (#5, #6, #7): No new types, ≤1 new method, no new error variants. Typed contract: full (per proposal — all code-changing specs get full). Rings: 0, 1, 2, 3, 5, 8.
- **MEDIUM** (#1, #3, #4, #8): New types OR complex logic OR API changes. Typed contract: full. Rings: 0, 1, 2, 3, 4 (where applicable), 5, 8.
- **HIGH** (#2, #9): New types AND complex logic AND new dependency or proof-carrying type. Typed contract: full. All applicable rings.

## Implementation Sequence

- [ ] 1. `specs/error-hierarchy-dedup/spec.md` — Introduce `ConfigError` + `GraphCompilationError` AdkError variants; fix exhaustiveness in all existing matches
- [ ] 2. `specs/core-types/spec.md` — Add Iron dependency (compile-spike); migrate `NodeKey` to `String :| (NonEmpty & Not[Reserved])`; introduce `ReservedNodeKey`, `Positive`, `NonNegative` aliases
- [ ] 3. `specs/harness-state/spec.md` — Migrate `MiddlewareName` to `String :| NonEmpty`; migrate `StateCell.CellId` to `String :| (NonEmpty & Match[…])`; wire `iron-upickle` ReadWriter
- [ ] 4. `specs/checkpoint-store-fpoly/spec.md` — Promote `CheckpointId` from transparent alias to opaque `String :| NonEmpty`; update `CheckpointStore[F]` API signatures
- [ ] 5. `specs/memory-orchestration-hook/spec.md` — Replace `MemoryPolicy.recallK` `require` with `refineEither[NonNegative]`; add `applyEither` returning `Either[ConfigError, MemoryPolicy]`
- [ ] 6. `specs/tools-node/spec.md` — Refine `ToolsNodeConfig.maxConcurrency` to `Positive` at boundary; add `parallelEither` overload
- [ ] 7. `specs/structured-llm/spec.md` — Refine `StructuredLLM` factory `maxParseAttempts` to `Positive` at boundary
- [ ] 8. `specs/react-agent/spec.md` — Refine `maxSteps` to `Positive` at internal boundary of `ReactAgent`/`AgentRunner`/`HarnessAgent`; preserve valid-input behavior
- [ ] 9. `specs/wio-graph/spec.md` — Introduce `ValidatedGraph` proof-carrying type; replace generic `Exception` with `GraphCompilationError` in `GraphExecutor`; delete dead commented-out throw blocks
