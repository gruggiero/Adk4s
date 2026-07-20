# Implementation Progress

<!-- SINGLE SOURCE OF TRUTH for the apply phase (verified-scala3 schema v7).
     tasks.md is REGENERATED from this file at each checkpoint — never
     hand-maintained in parallel (dual trackers drift).

     One spec at a time. After completing ALL steps for a spec (Steps 0–13),
     STOP and wait for explicit human approval before starting the next spec. -->

## Change: add-cross-run-memory-example

**Schema**: verified-scala3 (v7)
**Specs**: 1 (cross-run-memory-example)
**Human gate tier**: combined (simple + low risk — typed contract and test oracle at one stop)

## Spec 1/1: cross-run-memory-example

- **BASELINE SHA**: `0e1e4593107f05e6db6f680c1f06e8ee9c4a7309` (recorded 2026-07-19; working tree clean after spec commit)
- **State**: COMPLETE — all rings passed, ready for checkpoint

### Step 0 — baseline + concept check
- [x] working tree clean (after spec commit)
- [x] record `git rev-parse HEAD` as BASELINE SHA above — `0e1e4593107f05e6db6f680c1f06e8ee9c4a7309`
- [x] registry-check.sh: OK (611 tokens verified, 2 pre-existing weak bindings in react-agent.md)
- [x] all ⚠ VERIFY items resolved in inventory-check.md
- [x] no PUBLIC-TYPE-CHANGE IMPACT SCAN (spec adds new types, does not widen a sealed ADT)

### Prerequisite — build wiring
- [x] add `adk4s-memory-testkit` % Test dep to `adk4s-examples` in `build.sbt`
- [x] verify `sbt adk4s-examples/Test/compile` succeeds with scratch AgentMemoryLaws import
- [x] verify `sbt adk4s-examples/compile` still succeeds (no main-scope leakage)

### Step 1 — typed contract + test oracle (COMBINED GATE — simple + low risk)
- [x] FileBackedAgentMemory signatures in adk4s-examples/src/main/scala/org/adk4s/examples/memory/FileBackedAgentMemory.scala
- [x] FileBackedAgentMemorySpec in adk4s-examples/src/test/scala/org/adk4s/examples/memory/ (laws + 4 properties + negative test)
- [x] ORACLE POLARITY: 7 RED against signature-only FileBackedAgentMemory, 1 GREEN-BY-DESIGN (adversarial broken-double test)

### Step 2 — implementation
- [x] implement FileBackedAgentMemory body (remember, recall, rememberAll)
- [x] FileBackedAgentMemorySpec GREEN (8/8)
- [x] CrossRunMemoryExample + mock model (CLI modes: teach/recall/reset/demo)
- [x] CrossRunMemorySmokeSpec GREEN (8/8 — A1, A2, observability, adversarial no-teach, adversarial no-context, reset, mock-echo)
- [x] MemoryRetrieverExample (3 episodes, k=2 per spec)

### Wiring + docs
- [x] run-example.sh registration (crossrunmemory, memoryretriever)
- [x] README memory category (3 rows: CrossRunMemoryExample, MemoryRetrieverExample, FileBackedAgentMemory)
- [x] README orchestration snippet (MemoryAwareRunner + MemoryPolicy wrap-the-runner)

### Rings
- [x] R0 — sbt adk4s-examples/compile clean
- [x] R1 — scalafmt clean (pre-existing AsyncNodeStructuredExample.scala error is not ours)
- [x] R3 — FileBackedAgentMemorySpec (8/8) + CrossRunMemorySmokeSpec (8/8) GREEN
- [x] R4 — JSON-lines round-trip + reload properties GREEN
- [x] R8 — adversarial review: 7 findings identified and fixed (CLI args, adversarial tests, observability, retriever params, README)
- [x] full module test: sbt adk4s-examples/test GREEN (16/16)

### Ring 8 adversarial review findings (all fixed)
1. CrossRunMemoryExample lacked CLI args (teach/recall/reset) → added IOApp with arg dispatch
2. Missing adversarial test "Recall without prior teach" → added (proves no "blue" from empty storage)
3. Missing test "Reset clears storage" → added
4. Missing adversarial test "Mock does not echo when no context injected" → added (proves mock honesty)
5. Observability incomplete → added four labeled sections (Pre-turn recall, Injected context, Agent answer, Post-turn remember)
6. MemoryRetrieverExample params wrong (2 episodes, k=5) → fixed to 3 episodes, k=2 per spec
7. README missing FileBackedAgentMemory row + orchestration snippet → added

### Concept delta + inventory update + checkpoint
- [ ] append new concepts to openspec/concept-inventory.md
- [ ] update openspec/capability-profile.md module graph
- [ ] checkpoint
