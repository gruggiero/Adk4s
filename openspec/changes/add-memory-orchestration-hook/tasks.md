# Tasks

<!-- Stock OpenSpec task checklist, derived from implementation-order.md.
     This file lets `openspec list` and task tooling report progress; the
     apply phase also tracks detailed state in implementation-progress.md.
     implementation-progress.md is the SINGLE SOURCE OF TRUTH; tasks.md is
     regenerated from it at each checkpoint — never hand-maintain in parallel.

     RULES:
     - One `## <n>. <spec-name>` section per spec, in implementation-order.md order
     - Per-spec checkboxes follow the schema cycle: typed contract (human gate) →
       test oracle (human gate) → implementation → applicable rings → concept-delta
       + inventory update + checkpoint
     - List only the rings that apply to that spec (skip those marked `—` in the
       Ring Applicability table)
     - Prerequisite work (build restructure, deps, static-analysis config) goes
       first in the owning spec's section
     - Every task is observable and stack-specific — never "implement the spec" -->

## 1. memory-orchestration-hook

- [ ] Prerequisite — add `adk4s-orchestration → adk4s-memory-api` edge in `build.sbt` (`.dependsOn`); verify `sbt adk4s-orchestration/compile` still passes with an empty `org.adk4s.orchestration.memory` package
- [ ] Step 1 — typed contract (human gate, separate tier): `MemoryPolicy` (final case class + companion `default`/`defaultRender` + `require(recallK >= 0)`), `MemoryHook` (final class — `preTurn`/`postTurn` signatures over `Option[AgentMemory[IO]]`), `MemoryAwareRunner` (final class — `run`/`runWithEvents`/`resume` signatures wrapping `AgentRunner`); placed in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/MemoryAwareRunnerTypeContract.scala`; compiles via `sbt adk4s-orchestration/Test/compile`; includes `assertDoesNotCompile` for the no-`AgentRunner` construction
- [ ] Step 2 — test oracle (human gate, separate tier): `MemoryAwareRunnerSpec` + `MemoryHookSpec` + `MemoryPolicySpec` scenarios and 5 Hedgehog properties (no-memory identity, postTurn iff Completed, recall exactly once, render pure/total, groupId shared) + `Generators.scala` (`genPolicy`, `genHitList`, `genMessages`, `genRunResult`); run once for ORACLE POLARITY (red — implementation stubs fail the properties); interrupt/Failed scenarios use `TestControl`
- [ ] Step 3 — implementation: `MemoryPolicy.scala`, `MemoryHook.scala`, `MemoryAwareRunner.scala` in `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/`; `MemoryAwareRunner` pattern-matches on `RunResult` (exhaustiveness-enforced) and calls `postTurn` only on `Completed`; `preTurn` prepends a `UserMessage` with the rendered block only when recall is non-empty; NO event emission in this spec
- [ ] Ring 0 — `sbt adk4s-orchestration/compile` (exhaustiveness escalation on the `RunResult` match)
- [ ] Ring 1 — `sbt scalafixAll --check` + `sbt scalafmtCheck` (WartRemover `Var`/`Null`/`Throw` on the new files)
- [ ] Ring 2 — import audit: `org.adk4s.orchestration.memory` imports only `cats-effect`, `fs2`, `org.adk4s.orchestration.agent`, `org.adk4s.core.interrupt`, `org.adk4s.memory`, `org.llm4s.llmconnect.model` (Message only); no `workflows4s`, no llm4s `LLMClient`
- [ ] Ring 3 — `sbt adk4s-orchestration/test` (Hedgehog properties + `TestControl` scenarios green; ORACLE POLARITY now green-by-design)
- [ ] Ring 8 — adversarial spec-compliance review (fresh context, before R5): confirm no silent fallback to no-memory behavior on a memory error; confirm `Interrupted`/`Failed` produce zero writes; confirm `memory = None` is byte-for-byte identity
- [ ] Ring 5 — retarget `stryker4s.conf` `mutate` to `["**/memory/MemoryPolicy.scala", "**/memory/MemoryHook.scala", "**/memory/MemoryAwareRunner.scala"]`; `sbt "adk4s-orchestration/stryker4s"`; threshold break=90
- [ ] Concept-delta — create `openspec/concepts/memory-aware-runner.md` (purpose/state/actions `run`/`runWithEvents`/`resume`/operational principle/Implementation map/syncs `RecallToContext` + `WriteEpisode`); run `registry-check.sh`; update `openspec/concept-inventory.md` (mark `MemoryPolicy`/`MemoryHook`/`MemoryAwareRunner` as pre-existing); checkpoint + commit

## 2. memory-orchestration-events

- [ ] Step 1 — typed contract (human gate, separate tier): `MemoryRecalled(runPath: RunPath, query: String, hitCount: Int)` and `MemoryWritten(runPath: RunPath, episodes: Int)` as `AgentEvent` variants in `adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala` (each implementing `withPrependedStep` via `copy`); `MemoryEventsTypeContract` with `assertDoesNotCompile` for an inexhaustive match over `AgentEvent`; compiles via `sbt adk4s-core/Test/compile` + `sbt adk4s-core/compile` (exhaustiveness escalation surfaces any consumer match missing the new variants)
- [ ] Step 2 — test oracle (human gate, separate tier): `MemoryEventsSpec` scenarios and 3 Hedgehog properties (MemoryRecalled fires iff memory present, MemoryWritten fires iff Completed+memory present, new variants' RunPath equals runner's other events); `TestControl` for event-stream capture; run once for ORACLE POLARITY (red)
- [ ] Step 3 — implementation: add the two variants to `AgentEvent`'s companion; add the two `emit` calls inside `MemoryAwareRunner` (`MemoryRecalled` after `preTurn`, `MemoryWritten` after `postTurn` only on `Completed`); fix any consumer match that the exhaustiveness escalation flags
- [ ] Ring 0 — `sbt adk4s-core/compile` + `sbt adk4s-orchestration/compile` (exhaustiveness escalation on every `AgentEvent` match)
- [ ] Ring 1 — `sbt scalafixAll --check` + `sbt scalafmtCheck`
- [ ] Ring 2 — confirm `adk4s-core.interrupt` still does NOT import `adk4s-orchestration` (the new variants carry only `RunPath`/`String`/`Int`)
- [ ] Ring 3 — `sbt adk4s-orchestration/test` + `sbt adk4s-core/test` (Hedgehog properties green; ORACLE POLARITY green-by-design)
- [ ] Ring 8 — adversarial spec-compliance review (fresh context, before R5): confirm `MemoryRecalled`/`MemoryWritten` are absent when `memory = None`; confirm `hitCount`/`episodes` match the recall/remember counts; confirm `runPath` matches the runner's other events
- [ ] Ring 5 — retarget `stryker4s.conf` `mutate` to `["**/interrupt/AgentEvent.scala"]` (the new `withPrependedStep` impls); `sbt "adk4s-core/stryker4s"`; threshold break=90
- [ ] Concept-delta — update `openspec/concepts/agent-event-stream.md` (add `MemoryRecalled` + `MemoryWritten` to "AgentEvent variants" + Implementation map); run `registry-check.sh`; update `openspec/concept-inventory.md` (mark `MemoryRecalled`/`MemoryWritten` as pre-existing); checkpoint + commit
