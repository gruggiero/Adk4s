# Implementation Order

## Spec analysis

This change has a single spec: `specs/cross-run-memory-example/spec.md`.

| Spec | Concepts Introduced | Concepts Used | Applicable Rings | Complexity | Typed contract | Human gate tier |
|------|---------------------|---------------|------------------|------------|----------------|-----------------|
| `cross-run-memory-example` | `FileBackedAgentMemory[F[_]]`, `CrossRunMemoryExample`, `MemoryRetrieverExample`, JSON-lines `Episode` wire format, `adk4s-examples % Test → adk4s-memory-testkit` build dep | 23 existing concepts (full memory-api + orchestration memory surface) | R0, R1, R3, R4, R8 | **simple** — no new library types, one ~60-line demo double pinned by existing laws, two example entry points, one build.sbt one-liner, README edits | **Full** (per proposal: new `AgentMemory` impl + new persistence format + pass-criteria contracts) | **combined** — simple + low risk per proposal; typed-contract and test-oracle gates presented at ONE stop |

**Complexity justification**: simple. The change introduces no new library types, no new ADT variants, no new error algebra. The one new abstraction (`FileBackedAgentMemory`) is a demo double whose correctness is pinned by the existing `AgentMemoryLaws` contract. The mock `ChatModel` is a private deterministic string-scan. The build change is a one-line test-scope dep.

**Risk justification**: low (per proposal). Examples and tests only; no library code changes; the smoke test asserts the two cross-run pass criteria (A1, A2) directly.

## Dependency graph

Single spec — no inter-spec dependencies. The spec's internal implementation order is driven by the build wiring prerequisite and the `⚠ VERIFY` resolution already completed in the inventory-check artifact.

## Implementation sequence

The spec is implemented as a single ordered track. The `⚠ VERIFY` step from the source doc is already DONE (resolved in `inventory-check.md`), so the sequence starts directly from the build wiring.

### Track: cross-run-memory-example (combined gate — simple + low risk)

- [ ] **Step 1: Build wiring** — add `adk4s-memory-testkit` % Test dep to `adk4s-examples` in `build.sbt`. Verify `sbt adk4s-examples/Test/compile` succeeds with a scratch import of `AgentMemoryLaws`. Verify `sbt adk4s-examples/compile` still succeeds (no main-scope leakage).
- [ ] **Step 2: Typed contract** (combined gate — presented with the test oracle at one stop) — `FileBackedAgentMemory` signatures: `apply[F[_]: Sync](dataDir: Path): F[FileBackedAgentMemory[F]]`, `remember`, `recall`, `rememberAll`; the `given ReadWriter[Instant]` and `ReadWriter[Episode]` derivation; the `naiveScore` delegation. Land in `adk4s-examples/src/main/scala/org/adk4s/examples/memory/FileBackedAgentMemory.scala`. Compile: `sbt adk4s-examples/compile`.
- [ ] **Step 3: Test oracle (Ring 3 + Ring 4)** — `FileBackedAgentMemorySpec.scala` with: (a) `AgentMemoryLaws(indexesContent = true).all` run against the double (temp dir per test), (b) the 4 Ring 3 properties (round-trip, reload equivalence, scoring delegation, empty storage), (c) the adversarial negative test (broken double fails ≥ 1 law). Land in `adk4s-examples/src/test/scala/org/adk4s/examples/memory/`. Run: `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.FileBackedAgentMemorySpec"`. ORACLE POLARITY: properties RED before implementation of `FileBackedAgentMemory` body (Step 2 provides signatures only — the body is filled after the oracle is confirmed red).
- [ ] **Step 4: Implement `FileBackedAgentMemory` body** — fill in `remember` (append JSON line), `recall` (read file, parse, score via `naiveScore`, sort, take-k), `rememberAll` (batch append). Run the oracle: `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.FileBackedAgentMemorySpec"` → GREEN.
- [ ] **Step 5: `CrossRunMemoryExample` + mock model** — `CrossRunMemoryExample.scala` with: the private `MemoryDemoMockModel` (scans for `"Relevant memory:"` marker, echoes hit text or returns generic acknowledgment), the CLI-arg `teach`/`recall`/`reset` dispatch, the `MemoryAwareRunner.runWithEvents` wiring, the observability report returned as a `String` (not printed directly — for testability). Compile: `sbt adk4s-examples/compile`.
- [ ] **Step 6: `CrossRunMemorySmokeSpec`** — munit `CatsEffectSuite`: two-stack teach-then-recall (shared temp dir only), asserts A1 + A2 + observability sections in order + adversarial no-context scenario (no "blue" without teach) + reset scenario. Run: `sbt "adk4s-examples/testOnly org.adk4s.examples.memory.CrossRunMemorySmokeSpec"` → GREEN.
- [ ] **Step 7: `MemoryRetrieverExample`** — `MemoryRetrieverExample.scala`: seed 3 episodes, build `MemoryRetriever[IO](memory, k = 2)`, call `retrieve("favorite color", RetrieverConfig(...))`, print documents with metadata. Compile: `sbt adk4s-examples/compile`. Manual run: `./adk4s-examples/run-example.sh memoryretriever`.
- [ ] **Step 8: `run-example.sh` registration** — add keys `crossrunmemory` (with sub-arg handling for `teach`/`recall`/`reset`) and `memoryretriever`. Manual run: `./adk4s-examples/run-example.sh crossrunmemory teach && ./adk4s-examples/run-example.sh crossrunmemory recall` → output contains "blue".
- [ ] **Step 9: README updates** — new *Memory* category in examples table (3 rows); `MemoryAwareRunner` / `MemoryPolicy` in orchestration section with wrap-the-runner snippet. Manual review.
- [ ] **Step 10: Full module test** — `sbt adk4s-examples/test` → all green (existing examples tests + new memory tests).
- [ ] **Step 11: Ring 0 + Ring 1 full** — `sbt adk4s-examples/compile && sbt scalafixAll --check && sbt scalafmtCheck` → clean.
- [ ] **Step 12: Ring 8 adversarial review** — fresh-context reviewer checks: (a) mock answers from injected context not script, (b) two smoke-test stacks share only temp dir, (c) `FileBackedAgentMemory` honors all four laws in spirit, (d) no main-scope testkit leakage, (e) observability sections in order. Produce requirement-by-requirement PASS/PARTIAL/FAIL report.
- [ ] **Step 13: Update project inventory + profile** — append `FileBackedAgentMemory`, `CrossRunMemoryExample`, `MemoryRetrieverExample`, JSON-lines format to `openspec/concept-inventory.md` with `spec:add-cross-run-memory-example/cross-run-memory-example` provenance. Update `openspec/capability-profile.md` module graph with `adk4s-examples % Test → adk4s-memory-testkit`.

## Production files expected to change (Ring 5 targeting — NOT used, demo code)

Ring 5 is skipped for this change (examples/demo code; the laws suite provides the mutation-equivalent safety net). Recorded for completeness:

| File | Kind | Ring 5? |
|------|------|---------|
| `adk4s-examples/src/main/scala/org/adk4s/examples/memory/FileBackedAgentMemory.scala` | new | skipped (demo) |
| `adk4s-examples/src/main/scala/org/adk4s/examples/memory/CrossRunMemoryExample.scala` | new | skipped (demo) |
| `adk4s-examples/src/main/scala/org/adk4s/examples/memory/MemoryRetrieverExample.scala` | new | skipped (demo) |
| `build.sbt` | edit (one line) | skipped (build wiring) |
| `adk4s-examples/run-example.sh` | edit | skipped (script) |
| `README.md` | edit | skipped (docs) |

## Estimated size

~300 lines including tests (per source doc §9): ~60 lines `FileBackedAgentMemory`, ~80 lines `CrossRunMemoryExample` (incl. mock), ~40 lines `MemoryRetrieverExample`, ~60 lines `FileBackedAgentMemorySpec`, ~60 lines `CrossRunMemorySmokeSpec`, plus build/README/script edits.
