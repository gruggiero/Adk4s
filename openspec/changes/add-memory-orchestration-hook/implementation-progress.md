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

- **BASELINE SHA**: _(recorded at Step 0)_
- **State**: not started

### Step 0 — baseline + concept check
- [ ] working tree clean
- [ ] record `git rev-parse HEAD` as BASELINE SHA above
- [ ] run semantic scanner into `inventory-snapshots/memory-orchestration-hook-before.md`
- [ ] read `openspec/concept-inventory.md`; import existing concepts; verify Proof Obligations table complete
- [ ] run `scanner/registry-check.sh` (must pass)
- [ ] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds new types, does not widen a sealed ADT)

### Prerequisite
- [ ] add `adk4s-orchestration → adk4s-memory-api` edge in `build.sbt`; verify `sbt adk4s-orchestration/compile` with empty `org.adk4s.orchestration.memory` package

### Step 1 — typed contract (HUMAN GATE 1 of 2)
- [ ] `MemoryAwareRunnerTypeContract` in `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/memory/typecontract/`
- [ ] compiles via `sbt adk4s-orchestration/Test/compile`
- [ ] `assertDoesNotCompile` for no-`AgentRunner` construction
- [ ] **STOP for human approval**

### Step 2 — test oracle (HUMAN GATE 2 of 2)
- [ ] `MemoryAwareRunnerSpec` + `MemoryHookSpec` + `MemoryPolicySpec` + `Generators`
- [ ] 5 Hedgehog properties (no-memory identity, postTurn iff Completed, recall exactly once, render pure/total, groupId shared)
- [ ] interrupt/Failed scenarios use `TestControl`
- [ ] ORACLE POLARITY run (red / green-by-design)
- [ ] **STOP for human approval**

### Step 3 — implementation
- [ ] `MemoryPolicy.scala`, `MemoryHook.scala`, `MemoryAwareRunner.scala` in `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/`
- [ ] `MemoryAwareRunner` pattern-matches on `RunResult` (exhaustiveness-enforced); `postTurn` only on `Completed`
- [ ] `preTurn` prepends a `UserMessage` with rendered block only when recall is non-empty
- [ ] NO event emission in this spec

### Rings
- [ ] Ring 0 — `sbt adk4s-orchestration/compile` (exhaustiveness escalation)
- [ ] Ring 1 — `sbt scalafixAll --check` + `sbt scalafmtCheck` (WartRemover Var/Null/Throw)
- [ ] Ring 2 — import audit on `org.adk4s.orchestration.memory`
- [ ] Ring 3 — `sbt adk4s-orchestration/test` (Hedgehog + TestControl green; oracle polarity green-by-design)
- [ ] Ring 8 — adversarial spec-compliance review (fresh context, before R5)
- [ ] Ring 5 — retarget `stryker4s.conf` mutate; `sbt "adk4s-orchestration/stryker4s"`; threshold break=90

### Step 12 — concept delta + inventory update
- [ ] re-run scanner into `inventory-snapshots/memory-orchestration-hook-after.md`; diff against `-before`
- [ ] BUILD-DEPENDENCY DELTA: `git diff <baseline> -- build.sbt project/`
- [ ] REMOVAL AUDIT: `scanner/removal-audit.sh --suggest <baseline>` (expected "none removed")
- [ ] create `openspec/concepts/memory-aware-runner.md`
- [ ] run `registry-check.sh`
- [ ] update `openspec/concept-inventory.md`

### Step 13 — checkpoint + commit
- [ ] mark spec 1 checkbox above
- [ ] regenerate `tasks.md` checkboxes from this file
- [ ] commit: `add-memory-orchestration-hook: spec 1/2 — memory-orchestration-hook`
- [ ] **STOP for human approval before spec 2**

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
