# Tasks

<!-- Derived from implementation-order.md. The apply phase tracks detailed
     state in implementation-progress.md and regenerates this file's
     checkboxes from it at each checkpoint. Do not hand-maintain in parallel. -->

## 1. cross-run-memory-example

**Spec**: `specs/cross-run-memory-example/spec.md`
**Gate tier**: combined (simple + low risk — typed contract and test oracle at one stop)
**Rings**: R0, R1, R3, R4, R8

### Prerequisites

- [ ] Add `adk4s-memory-testkit` % Test dependency to `adk4s-examples` in `build.sbt`
- [ ] Verify `sbt adk4s-examples/Test/compile` succeeds with a scratch `AgentMemoryLaws` import
- [ ] Verify `sbt adk4s-examples/compile` still succeeds (no main-scope leakage)

### Typed contract + test oracle (combined gate)

- [ ] Write `FileBackedAgentMemory` signatures in `adk4s-examples/src/main/scala/org/adk4s/examples/memory/FileBackedAgentMemory.scala` (`apply[F[_]: Sync](dataDir: Path)`, `remember`, `recall`, `rememberAll`, `given ReadWriter[Instant]`, `ReadWriter[Episode]` derivation, `naiveScore` delegation)
- [ ] Compile signatures: `sbt adk4s-examples/compile`
- [ ] Write `FileBackedAgentMemorySpec.scala` in `adk4s-examples/src/test/scala/org/adk4s/examples/memory/` with `AgentMemoryLaws(indexesContent = true).all` + 4 Ring 3 properties + adversarial negative test (broken double fails ≥ 1 law)
- [ ] ORACLE POLARITY: confirm properties are RED against signature-only `FileBackedAgentMemory` (body not yet filled)
- [ ] Human gate: present typed contract + test oracle at one stop for approval

### Implementation

- [ ] Implement `FileBackedAgentMemory` body (`remember` append, `recall` read/parse/score/take-k, `rememberAll` batch)
- [ ] Run oracle: `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.FileBackedAgentMemorySpec"` → GREEN
- [ ] Write `CrossRunMemoryExample.scala` with private `MemoryDemoMockModel` (scans for `"Relevant memory:"` marker), CLI-arg `teach`/`recall`/`reset` dispatch, `MemoryAwareRunner.runWithEvents` wiring, observability report as `String`
- [ ] Compile: `sbt adk4s-examples/compile`
- [ ] Write `CrossRunMemorySmokeSpec.scala` (munit `CatsEffectSuite`): two-stack teach-then-recall, asserts A1 + A2 + observability sections + adversarial no-context scenario + reset scenario
- [ ] Run: `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.CrossRunMemorySmokeSpec"` → GREEN
- [ ] Write `MemoryRetrieverExample.scala`: seed 3 episodes, `MemoryRetriever[IO](memory, k = 2)`, `retrieve("favorite color", RetrieverConfig(...))`, print documents with metadata
- [ ] Compile: `sbt adk4s-examples/compile`

### Wiring + docs

- [ ] Register `crossrunmemory` (with `teach`/`recall`/`reset` sub-args) and `memoryretriever` keys in `adk4s-examples/run-example.sh`
- [ ] Manual run: `./adk4s-examples/run-example.sh crossrunmemory teach && ./adk4s-examples/run-example.sh crossrunmemory recall` → output contains "blue"
- [ ] Manual run: `./adk4s-examples/run-example.sh memoryretriever` → prints retrieved `Document`s with metadata
- [ ] README: add *Memory* category to examples table (3 rows: `CrossRunMemoryExample`, `MemoryRetrieverExample`, `FileBackedAgentMemory`)
- [ ] README: add `MemoryAwareRunner` / `MemoryPolicy` to `adk4s-orchestration` module bullet with wrap-the-runner snippet

### Rings

- [ ] **R0** — `sbt adk4s-examples/compile` clean (exhaustiveness escalation: `RunResult` match is exhaustive)
- [ ] **R1** — `sbt scalafixAll --check` clean, `sbt scalafmtCheck` clean, WartRemover (relaxed examples set) clean
- [ ] **R3** — `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.FileBackedAgentMemorySpec"` GREEN (laws + 4 properties + negative test)
- [ ] **R3** — `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.CrossRunMemorySmokeSpec"` GREEN (A1 + A2 + observability + adversarial)
- [ ] **R4** — JSON-lines round-trip property + reload-across-instances property GREEN (covered by `FileBackedAgentMemorySpec` properties 1 + 2)
- [ ] **R8** — fresh-context adversarial review: mock answers from context not script; two stacks share only temp dir; laws honored in spirit; no main-scope testkit leakage; observability sections in order. Produce PASS/PARTIAL/FAIL report.
- [ ] Full module test: `sbt adk4s-examples/test` GREEN (existing + new)

### Concept delta + inventory update + checkpoint

- [ ] Append `FileBackedAgentMemory`, `CrossRunMemoryExample`, `MemoryRetrieverExample`, JSON-lines `Episode` format to `openspec/concept-inventory.md` with `spec:add-cross-run-memory-example/cross-run-memory-example` provenance
- [ ] Update `openspec/capability-profile.md` module graph: `adk4s-examples % Test → adk4s-memory-testkit`
- [ ] Update `implementation-progress.md` and regenerate `tasks.md` checkboxes from it
- [ ] Checkpoint: all rings green, inventory + profile updated, ready for archive
