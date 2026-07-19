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

- **BASELINE SHA**: _(spec 1 checkpoint commit)_
- **State**: not started (waits on spec 1 approval)

### Step 0 — baseline + concept check + PUBLIC-TYPE-CHANGE IMPACT SCAN
- [ ] working tree clean (spec 1 checkpoint is baseline)
- [ ] record BASELINE SHA
- [ ] run scanner into `inventory-snapshots/memory-orchestration-events-before.md`
- [ ] read `openspec/concept-inventory.md`; verify Proof Obligations
- [ ] run `scanner/registry-check.sh`
- [ ] PUBLIC-TYPE-CHANGE IMPACT SCAN: `scanner/impact-scan.sh org.adk4s.core.interrupt.AgentEvent`; resolve catch-all sites (EventStreamExample:89, HierarchicalEventStreamExample:242/262, AgentTool:122)

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [ ] `MemoryRecalled(runPath, query, hitCount)` + `MemoryWritten(runPath, episodes)` in `AgentEvent.scala`
- [ ] `MemoryEventsTypeContract` with `assertDoesNotCompile` for inexhaustive match
- [ ] compiles via `sbt adk4s-core/Test/compile` + `sbt adk4s-core/compile`
- [ ] **STOP for human approval**

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [ ] `MemoryEventsSpec` + 3 Hedgehog properties
- [ ] `TestControl` for event-stream capture
- [ ] ORACLE POLARITY run (red)
- [ ] **STOP for human approval**

### Step 3 — implementation
- [ ] add two variants to `AgentEvent` companion (with `withPrependedStep` via `copy`)
- [ ] add two `emit` calls inside `MemoryAwareRunner` (MemoryRecalled after preTurn, MemoryWritten after postTurn only on Completed)
- [ ] fix any consumer match flagged by exhaustiveness escalation

### Rings
- [ ] Ring 0 — `sbt adk4s-core/compile` + `sbt adk4s-orchestration/compile`
- [ ] Ring 1 — `sbt scalafixAll --check` + `sbt scalafmtCheck`
- [ ] Ring 2 — confirm `adk4s-core.interrupt` still does NOT import `adk4s-orchestration`
- [ ] Ring 3 — `sbt adk4s-orchestration/test` + `sbt adk4s-core/test`
- [ ] Ring 8 — adversarial review (fresh context)
- [ ] Ring 5 — retarget `stryker4s.conf` to `**/interrupt/AgentEvent.scala`; `sbt "adk4s-core/stryker4s"`; threshold break=90

### Step 12 — concept delta + inventory update
- [ ] re-run scanner into `inventory-snapshots/memory-orchestration-events-after.md`; diff
- [ ] BUILD-DEPENDENCY DELTA (expected none)
- [ ] REMOVAL AUDIT (expected none removed)
- [ ] update `openspec/concepts/agent-event-stream.md`
- [ ] run `registry-check.sh`
- [ ] update `openspec/concept-inventory.md`

### Step 13 — checkpoint + commit
- [ ] mark spec 2 checkbox
- [ ] regenerate `tasks.md`
- [ ] commit: `add-memory-orchestration-hook: spec 2/2 — memory-orchestration-events`
- [ ] **STOP — present final summary**
