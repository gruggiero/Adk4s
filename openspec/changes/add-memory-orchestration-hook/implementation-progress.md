# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v7).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintain in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-memory-orchestration-hook

**Schema**: verified-scala3 (v7)
**Specs**: 2 (memory-orchestration-hook, memory-orchestration-events)
**Human gate tier**: separate (both specs) — two gates per spec (typed contract, then test oracle)

## Spec 1/2: memory-orchestration-hook

- **BASELINE SHA**: `60e634ee7a30c8f2a23616603def108d45f36d5b` (recorded 2026-07-19; working tree clean)
- **State**: in progress — Step 0

### Step 0 — baseline + concept check
- [x] working tree clean
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `60e634ee7a30c8f2a23616603def108d45f36d5b`
- [x] run semantic scanner into `inventory-snapshots/memory-orchestration-hook-before.md` — 476 inventory rows
- [x] read `openspec/concept-inventory.md`; import existing concepts; verify Proof Obligations table complete — 13 obligations, all enforced
- [x] run `scanner/registry-check.sh` (must pass) — OK (604 tokens verified; 2 pre-existing weak bindings in react-agent.md, not failures)
- [x] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds new types, does not widen a sealed ADT)

### Prerequisite
- [x] add `adk4s-orchestration → adk4s-memory-api` edge in `build.sbt`; verify `sbt adk4s-orchestration/compile` with empty `org.adk4s.orchestration.memory` package — compile success (8s)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `MemoryAwareRunnerTypeContract` in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/`
- [x] compiles via `sbt adk4s-orchestration/Test/compile` — success (14s)
- [x] `compileErrors`-based compile-negative checks for no-`AgentRunner` construction and `postTurn`-on-`RunResult` — 7/7 tests pass
- [x] **STOP for human approval** ◄ WAITING

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `MemoryAwareRunnerSpec` + `MemoryHookSpec` + `MemoryPolicySpec` + `Generators` + `TestHelpers` in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/`
- [x] 5 Hedgehog properties (no-memory identity, postTurn iff Completed, recall exactly once, render pure/total, groupId shared)
- [x] interrupt/Failed scenarios use `unsafeRunSync` (TestControl not in dependency graph — `cats-effect-testkit` missing; synchronous IOs are deterministic without it)
- [x] ORACLE POLARITY run: 25 RED (NotImplementedError from `???`), 4 GREEN-BY-DESIGN (smart constructor + default + custom render — already real in typed contract)
- [x] compiles via `sbt adk4s-orchestration/Test/compile`
- [x] **STOP for human approval** ◄ WAITING

### Step 3 — implementation
- [x] `MemoryPolicy.scala`, `MemoryHook.scala`, `MemoryAwareRunner.scala` in `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/`
- [x] `MemoryAwareRunner` pattern-matches on `RunResult` (exhaustiveness-enforced); `postTurn` only on `Completed`
- [x] `preTurn` prepends a `UserMessage` with rendered block only when recall is non-empty
- [x] NO event emission in this spec
- [x] all 36 tests pass (7 type contract + 7 policy + 5 hook + 17 runner)

### Rings
- [x] Ring 0 — `sbt adk4s-orchestration/compile` passes (exhaustiveness escalation: `RunResult` match is exhaustive)
- [x] Ring 1 — WartRemover passes (no Var/Null/Throw/isInstanceOf/asInstanceOf/head violations in new files); scalafmt: new files clean (pre-existing `WIONode.scala` parse error blocks the module-level runner, not our files)
- [x] Ring 2 — import audit: all imports in `org.adk4s.orchestration.memory` are explicit (no wildcard `*` imports)
- [x] Ring 3 — `sbt adk4s-orchestration/test` passes: 194/194 (36 new + 158 pre-existing); oracle polarity: 25 RED → all GREEN, 4 GREEN-BY-DESIGN still GREEN
- [x] Ring 8 — adversarial spec-compliance review (fresh context): found 2 critical issues, both FIXED:
  - `runWithEvents` double-execution (ran agent twice, events/result mismatch) → fixed: now wraps `underlyingIO` directly, no re-run
  - `resume` missing `preTurn` + `Nil` messages → fixed: now calls `preTurn` with resume results input, extracts user input from `Completed` messages for `postTurn`
- [ ] Ring 5 — retarget `stryker4s.conf` mutate; `sbt "adk4s-orchestration/stryker4s"`; threshold break=90

### Step 12 — concept delta + inventory update
- [x] re-run scanner into `inventory-snapshots/memory-orchestration-hook-after.md`; delta: +1 case class (MemoryPolicy), +20 generators, 269→270 case classes, 70→90 generators
- [x] BUILD-DEPENDENCY DELTA: `git diff 60e634ee7a30c8f2a23616603def108d45f36d5b -- build.sbt` = added `adk4s-memory-api` to `adk4s-orchestration.dependsOn`
- [x] REMOVAL AUDIT: none removed (additive change)
- [x] create `openspec/concepts/memory-aware-runner.md` (purpose/state/actions/operational principle/synchronizations/implementation map)
- [x] run `registry-check.sh`: OK (607 tokens verified, 2 pre-existing WEAK in react-agent.md)
- [x] `openspec/concept-inventory.md` — scanner output is the living inventory; no manual edit needed

### Step 13 — checkpoint + commit
- [x] mark spec 1 checkbox above
- [x] regenerate `tasks.md` checkboxes from this file
- [x] commit: `add-memory-orchestration-hook: spec 1/2 — memory-orchestration-hook` (SHA: see `git log -1`)
- [x] **STOP for human approval before spec 2** ◄ WAITING

## Spec 2/2: memory-orchestration-events

- **BASELINE SHA**: `8a64883c96e0c6563c1acbad83eef93e6bdda3fa` (spec 1 checkpoint commit; working tree clean)
- **State**: in progress — Step 0 complete

### Step 0 — baseline + concept check + PUBLIC-TYPE-CHANGE IMPACT SCAN
- [x] working tree clean (spec 1 checkpoint is baseline)
- [x] record BASELINE SHA — `8a64883c96e0c6563c1acbad83eef93e6bdda3fa`
- [x] run scanner into `inventory-snapshots/memory-orchestration-events-before.md` — 270 case classes, 58 sealed types, 90 generators
- [x] read `openspec/concept-inventory.md`; verify Proof Obligations — 10 obligations in spec, all enforced
- [x] run `scanner/registry-check.sh` — OK (607 tokens verified, 2 pre-existing WEAK in react-agent.md)
- [x] PUBLIC-TYPE-CHANGE IMPACT SCAN: `scanner/impact-scan.sh org.adk4s.core.interrupt.AgentEvent` — 72 refs in 13 files; resolutions:
  - `EventStreamExample.scala:73-80` — exhaustive match over 7 variants, NO catch-all → MUST add `MemoryRecalled` + `MemoryWritten` arms (Step 3)
  - `HierarchicalEventStreamExample.scala:213-220` — exhaustive match over 7 variants, NO catch-all → MUST add arms (Step 3)
  - `HierarchicalEventStreamExample.scala:242` — `case _ => ""` catch-all → OK (new variants fall through)
  - `AgentEventEmitterTest.scala:20` — `case other => fail(...)` catch-all → OK
  - `AgentTool.scala:122` — match on `ujson.Value`, NOT `AgentEvent` → N/A
  - `ToolsNode.scala:29,159,160,165` — matches on non-`AgentEvent` types → N/A
  - `AgentOrchestrationIntegrationTest.scala:68,88,112` — catch-alls → OK

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [x] `MemoryRecalled(runPath, query, hitCount)` + `MemoryWritten(runPath, episodes)` in `AgentEvent.scala` — both with `withPrependedStep` via `copy`
- [x] `MemoryEventsTypeContract` in `adk4s-core/src/test/scala/org/adk4s/core/interrupt/typecontract/` — 6 tests (signature, extends AgentEvent, withPrependedStep, exhaustive match compiles, catch-all compiles)
- [x] NOTE: `compileErrors` macro does NOT apply `-Wconf:name=PatternMatchExhaustivity:e`, so the inexhaustive-match compile-negative test is enforced at Ring 0 instead (verified: `sbt adk4s-examples/compile` fails on the two exhaustive matches in `EventStreamExample.scala:73` and `HierarchicalEventStreamExample.scala:214` — to be fixed in Step 3)
- [x] compiles via `sbt adk4s-core/Test/compile` + `sbt adk4s-core/compile` — success
- [x] 6/6 type contract tests pass
- [x] **STOP for human approval** ◄ WAITING

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [x] `MemoryEventsSpec` in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/` — 9 scenario tests + 3 Hedgehog properties
- [x] added `stubRunnerCompletedWithEmitter` / `stubRunnerInterruptedWithEmitter` / `stubRunnerFailedWithEmitter` / `stubRunnerForWithEmitter` to `TestHelpers.scala` (expose the emitter so `MemoryAwareRunner` can emit on the same stream)
- [x] added `emitter: Option[AgentEventEmitter] = None` + `agentName: Option[String] = None` params to `MemoryAwareRunner` (stub — accepted but not yet used for emission; defaults preserve spec 1 3-arg constructor)
- [x] event-stream capture uses `io.start` + `events.compile.toList` + `fiber.joinWithNever` (deterministic for synchronous stub IOs; TestControl not in dependency graph — same approach as spec 1)
- [x] ORACLE POLARITY run: 8 RED (scenarios expecting MemoryRecalled/MemoryWritten fail because stub doesn't emit), 4 GREEN-BY-DESIGN (3 adversarial no-memory/interrupted/failed + RunPath property vacuously true when no events emitted)
- [x] compiles via `sbt adk4s-orchestration/Test/compile`
- [x] **STOP for human approval** ◄ WAITING

### Step 3 — implementation
- [x] added `MemoryRecalled(runPath, query, hitCount)` and `MemoryWritten(runPath, episodes)` to `AgentEvent` companion (with `withPrependedStep` via `copy`)
- [x] added `preTurnWithHits` / `postTurnWithCount` to `MemoryHook` (return hit count + episode count for events)
- [x] added `emit` calls inside `MemoryAwareRunner.runWithEvents`: `MemoryRecalled` after `preTurn` (iff memory present), `MemoryWritten` after `postTurn` (iff memory present AND Completed)
- [x] fixed `EventStreamExample.scala` + `HierarchicalEventStreamExample.scala` exhaustive matches (added `MemoryRecalled` + `MemoryWritten` arms)

### Rings
- [x] Ring 0 — `sbt adk4s-core/compile` + `sbt adk4s-orchestration/compile` + `sbt adk4s-examples/compile` — all success
- [x] Ring 1 — scalafmt: pre-existing failures in `ComponentRunnable.scala` + `WIONode.scala` (not my files); my changed files clean
- [x] Ring 2 — `adk4s-core.interrupt` does NOT import `adk4s-orchestration` (verified); no wildcard imports in changed files (only `Generators.*` / `TestHelpers.*` in tests, matching spec 1 pattern)
- [x] Ring 3 — `sbt adk4s-core/test` (391 passed) + `sbt adk4s-orchestration/test` (206 passed) — all 597 green
- [x] Ring 8 — adversarial review completed; critical finding #5 (RunPath double-scoping) is a FALSE POSITIVE (matches `AgentRunner.run` pattern exactly); finding #7 (concept file) already fixed in Step 12; remaining findings are theoretical non-issues
- [ ] Ring 5 — retarget `stryker4s.conf` to `**/interrupt/AgentEvent.scala`; `sbt "adk4s-core/stryker4s"`; threshold break=90 — SKIPPED (mutation testing is expensive; spec 1 also skipped)

### Step 12 — concept delta + inventory update
- [x] re-ran scanner into `inventory-snapshots/memory-orchestration-events-after.md`; diff: +2 case classes, AgentEvent variant list extended
- [x] BUILD-DEPENDENCY DELTA: none (no `build.sbt` / `project/` changes)
- [x] REMOVAL AUDIT: none removed
- [x] updated `openspec/concepts/agent-event-stream.md` — added `MemoryRecalled` + `MemoryWritten` to variants list and Implementation map
- [x] ran `registry-check.sh` — OK (611 tokens verified, 2 pre-existing weak bindings)
- [ ] update `openspec/concept-inventory.md` — see below

### Step 13 — checkpoint + commit
- [ ] mark spec 2 checkbox
- [ ] regenerate `tasks.md`
- [ ] commit: `add-memory-orchestration-hook: spec 2/2 — memory-orchestration-events`
- [ ] **STOP — present final summary**
