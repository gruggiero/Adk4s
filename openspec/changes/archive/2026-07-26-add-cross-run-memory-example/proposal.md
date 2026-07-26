# Proposal: Cross-Run Memory Example

## Why

ADK4S ships a complete durable-memory stack — the `AgentMemory[F]` capability
(`adk4s-memory-api`), the `AgentMemoryLaws` contract (`adk4s-memory-testkit`),
the `MemoryRetriever` bridge, and the `MemoryAwareRunner` / `MemoryPolicy`
orchestration hook (`adk4s-orchestration`, landed by the archived
`2026-07-19-add-memory-orchestration-hook` change) — but no example exercises
it. `ReactMemoryExample` demonstrates only in-conversation memory (the message
list), which dies with the process. The durable-memory stack has no proof of
life: no runnable artifact shows an agent taught a fact in one process
recalling it in a completely separate process.

This change closes that gap. It is the proof of life for durable memory, and
it doubles as the second in-repo consumer of `AgentMemoryLaws` (the first
being `InMemoryAgentMemorySpec`), keeping the testkit honest.

## What Changes

A new **Memory (`memory/`)** category under `adk4s-examples` containing two
runnable examples plus a file-backed `AgentMemory` double, with associated
tests and README updates. **No library code changes are expected.** Any
discovered need for a library change (e.g. exposing recall hits from
`MemoryAwareRunner`) is reported back as its own small proposal rather than
smuggled in here.

### Affected Capabilities

- `specs/cross-run-memory-example/spec.md` — NEW. Specifies the two-run
  teach/recall contract (A1, A2), the `FileBackedAgentMemory` double and its
  laws obligation, the `MemoryRetrieverExample` single-run seam, the
  observability/printing requirements, and the README documentation
  deliverables. The single spec covers all three example files because they
  share the same `AgentMemory` machinery and the same mock-model convention.

### Out of Scope

- No new library features in `adk4s-memory-api`, `adk4s-memory-testkit`, or
  `adk4s-orchestration`. The one contained exception is `FileBackedAgentMemory`,
  which lives inside `adk4s-examples` precisely so no library module is
  touched.
- No performance or load characterization.
- No multi-user / multi-group scoping demo.
- No real-LLM integration beyond the existing example convention
  (mock by default, OpenAI when `OPENAI_API_KEY` is set). The smoke test runs
  against the mock only.
- No CI wiring for the cross-JVM shell invocations — the in-process rebuild
  in `CrossRunMemorySmokeSpec` is the unit-testable approximation; the shell
  invocations are documented in the README and acceptance checklist.

## Approach

Three example files under
`adk4s-examples/src/main/scala/org/adk4s/examples/memory/`:

1. **`FileBackedAgentMemory.scala`** — a ~60-line `AgentMemory[F]` that
   persists `Episode`s as JSON lines to a file under a configurable data
   directory (default `./.memory-demo/episodes.jsonl`). `remember` appends one
   line; `recall` reads the full file and scores with the same
   substring/term-overlap logic as `InMemoryAgentMemory` (extracted to a
   shared helper inside the examples package if the original is private — to
   be resolved in the spec's `⚠ VERIFY` step). Ignores `TemporalScope`
   (permitted by law 4). Returns `EpisodeOutcome`-style empty counts. Lives
   in `adk4s-examples` because the API module's design boundary is "no
   storage engines".

2. **`CrossRunMemoryExample.scala`** — a single `IOApp.Simple` main whose run
   mode is selected by CLI arg (`teach` / `recall` / `reset`). The
   cross-process nature is driven by *invocations*, not by anything clever
   inside. Wiring: `ReactAgent.create` → `AgentRunner.create` with
   `InMemoryCheckpointStore` → `MemoryAwareRunner(runner, Some(memory),
   MemoryPolicy.default)` → `mRunner.run(List(UserMessage(...)))` →
   `printReport`. The mock `ChatModel` answers **from injected context** for
   the recall run (it scans the conversation for the memory-context block and
   echoes the remembered value found there) — never from a canned script.

3. **`MemoryRetrieverExample.scala`** — small, single-run, shows the second
   seam: seed three episodes via `memory.rememberAll`, build
   `MemoryRetriever[IO](memory, k = 2)`, invoke the retriever with
   `"favorite color"`, print the returned `Document`s with their metadata
   (score, provenance, deterministic id).

Two test files under
`adk4s-examples/src/test/scala/org/adk4s/examples/memory/`:

- **`FileBackedAgentMemorySpec.scala`** — `AgentMemoryLaws(indexesContent =
  true).all` green against the file-backed double, temp dir per test.
- **`CrossRunMemorySmokeSpec.scala`** — drives teach-then-recall in-process
  but across two fully rebuilt agent/runner/memory stacks that share only the
  temp storage directory; asserts A1 (final assistant message contains
  `blue`, case-insensitive) and A2 (printed injected-context block non-empty
  and contains `blue`).

Observability: the recall run must print pre-turn recall hits, the injected
context block, the agent answer, and the post-turn write report (§5 of the
source doc). If `MemoryAwareRunner` exposes `MemoryRecalled` / `MemoryWritten`
`AgentEvent`s, the example subscribes and prints from the event stream;
otherwise it calls `memory.recall(...)` itself once, prints the hits, then
runs `MemoryAwareRunner` normally (a duplicated recall is acceptable in a
demo). The choice is resolved by the `⚠ VERIFY` step against
`adk4s-orchestration` source.

README: a new *Memory* category in the examples table with three rows, plus
`MemoryAwareRunner` and `MemoryPolicy` added to the `adk4s-orchestration`
module bullet with a short wrap-the-runner snippet (the memory hook is
currently undocumented in the orchestration docs).

`run-example.sh` registration: keys `crossrunmemory` and `memoryretriever`.

## Correctness Risk Level

**Risk**: low — examples and tests only, no library code changes; the one new
abstraction (`FileBackedAgentMemory`) is a demo double whose correctness is
pinned by the existing `AgentMemoryLaws` contract it must satisfy, and the
smoke test asserts the two cross-run pass criteria (A1, A2) directly.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags, refined types
- [x] Ring 1: Lint — Scalafix DisableSyntax, WartRemover, dangerous-pattern scan
- [ ] Ring 2: Architecture — example module only; no layer-dependency changes
- [x] Ring 3: Property-based tests — MANDATORY. `FileBackedAgentMemorySpec`
      runs the existing `AgentMemoryLaws(indexesContent = true).all` property
      suite against the file-backed double (temp dir per test).
      `CrossRunMemorySmokeSpec` is a scenario test asserting A1 + A2 across
      two fully rebuilt stacks sharing only the temp storage dir. **No
      concurrent behavior** is involved: `MemoryAwareRunner` is a sequential
      decorator around `AgentRunner`; the file-backed memory is a
      single-writer/single-reader JSON-lines file. No deterministic
      concurrency kit required.
- [x] Ring 4: Wire/persistence compatibility — `FileBackedAgentMemory`
      introduces a JSON-lines persistence format for `Episode`. The laws
      suite already covers `remember` → `recall` round-trip; the spec adds
      an explicit round-trip property (remember N distinct episodes, recall
      returns all N) and a reload-across-instances property (write with one
      `FileBackedAgentMemory` instance, recall with a fresh instance pointed
      at the same file) to prove cross-process persistence is real, not an
      in-memory artifact.
- [ ] Ring 5: Mutation testing — example/demo code; not retargeting
      stryker4s. The laws suite already provides the mutation-equivalent
      safety net for `FileBackedAgentMemory`.
- [ ] Ring 6: Formal verification — not pure-domain `verified` module code.
- [ ] Ring 7: Model checking — no distributed/event-driven invariants.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY for code
      changes. Fresh-context reviewer checks that the mock model answers
      from injected context (not a canned script), that the two stacks in
      the smoke test share only the temp storage dir (no accidental
      in-memory state sharing), and that `FileBackedAgentMemory` honors all
      four `AgentMemoryLaws` rather than satisfying the letter while
      violating the spirit.
- [ ] Ring 9: Telemetry — no otel4s / telemetry stack detected.

## Typed Contract Decision

| Change kind | Typed contract |
|---|---|
| New domain type / ADT-GADT variant | Full |
| New service method / actor command/event/state | Full |
| New IDL operation/structure | Full |
| Evaluator/desugarer/typechecker logic | Full |
| Public API signature change / error algebra change | Full |
| Persistence/serialization change / messaging wiring | Full |
| Pure internal refactor | Minimal (signatures of touched code) |
| Docs / formatting / test-only | Waiver (human-approved) |

**Per-spec classification**:

| Spec | Typed contract (full/minimal/waiver) | Justification |
|------|--------------------------------------|---------------|
| `specs/cross-run-memory-example/spec.md` | Full | Introduces a new `AgentMemory[F]` implementation (`FileBackedAgentMemory`) with a new JSON-lines persistence format, a new CLI-driven example entry point, and explicit pass-criteria contracts (A1, A2). The persistence format and the mock-model "answer from injected context" rule both have fallback/default paths that need a typed contract to pin down. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` | Implemented by `FileBackedAgentMemory`. Methods: `remember`, `recall`, `rememberAll`. |
| `Episode` | case class | `org.adk4s.memory` | Persisted as JSON lines; `sourceType` set to `SourceType.Conversation` for user turns. |
| `EpisodeOutcome` | case class | `org.adk4s.memory` | Returned by `remember`; demo double returns empty-count instances. |
| `MemoryHit` | case class | `org.adk4s.memory` | Returned by `recall`; rendered into the injected context block. |
| `SourceType` | enum | `org.adk4s.memory` | `Conversation` for user turns. |
| `TemporalScope` | case class | `org.adk4s.memory` | Ignored by `FileBackedAgentMemory` (law 4 permits this). |
| `AgentMemoryLaws` | testkit trait | `org.adk4s.memory.testkit` | `FileBackedAgentMemorySpec` extends/uses `AgentMemoryLaws(indexesContent = true).all`. |
| `InMemoryAgentMemory[IO]` | test double | `org.adk4s.memory` | Reference implementation; scoring logic reused or extracted. |
| `MemoryRetriever` | adapter class | `org.adk4s.memory` | Used by `MemoryRetrieverExample` to bridge `AgentMemory` → `Retriever`. |
| `Retriever` / `Document` | service trait / case class | `org.adk4s.core.component` | `MemoryRetriever` adapts into this; example prints returned `Document`s with metadata. |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` | `ReactAgent.create(...)` builds the agent. |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | `AgentRunner.create(agent, InMemoryCheckpointStore())` builds the runner. |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` | Pattern-matched by `printReport` (`Completed` / `Interrupted` / `Failed`). |
| `InMemoryCheckpointStore` | class | `org.adk4s.orchestration.interrupt` | Passed to `AgentRunner.create`. |
| `MemoryAwareRunner` | decorator class | `org.adk4s.orchestration.memory` | Wraps `AgentRunner` with pre-turn recall / post-turn remember. |
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` | `MemoryPolicy.default` provides the render function for injected context. |
| `MemoryRecalled` / `MemoryWritten` | AgentEvent variants | `org.adk4s.core.interrupt` | If exposed, the example subscribes and prints from the event stream. |
| `ChatModel[F[_]]` | service trait | `org.adk4s.core.component` | Mock by default, OpenAI when `OPENAI_API_KEY` set (existing example convention). |
| `UserMessage` / `Message` | case class | `llm4s` / `org.adk4s.core.types` | `mRunner.run(List(UserMessage(...)))`. |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `FileBackedAgentMemory[F[_]]` | final class (example-scoped, `adk4s-examples`) | `AgentMemory[F]` double that persists `Episode`s as JSON lines to a configurable file, demonstrating cross-process durable memory. |
| `CrossRunMemoryExample` | `IOApp.Simple` object (example) | CLI-arg-driven (`teach` / `recall` / `reset`) entry point demonstrating cross-run recall via `MemoryAwareRunner`. |
| `MemoryRetrieverExample` | `IOApp.Simple` object (example) | Single-run entry point demonstrating the `MemoryRetriever` seam. |
| JSON-lines `Episode` persistence format | wire format (example-scoped) | One `Episode` per line as upickle-generated JSON; round-tripped by `FileBackedAgentMemory`. |

## Risks and Mitigations

- **`⚠ VERIFY` items unresolved at spec time.** The source doc flags several
  signatures inferred from README docs rather than read from source
  (`MemoryAwareRunner` return type, `MemoryPolicy.default` render prefix,
  `Retriever` method name, mock-model convention, `InMemoryAgentMemory`
  scoring visibility). The spec's first implementation step resolves all of
  them against `adk4s-orchestration` and `adk4s-memory-api` source; any
  discovered gap becomes a finding reported as its own small proposal.
- **Mock model answers from a canned script.** The demo would be a lie. The
  spec pins the mock's response function to "scan the conversation for the
  memory-context block and echo the remembered value found there"; Ring 8
  adversarial review specifically checks this.
- **Accidental in-memory state sharing in the smoke test.** The two stacks
  in `CrossRunMemorySmokeSpec` must share only the temp storage dir. Ring 8
  checks that no `Ref` / `InMemoryAgentMemory` / shared `AgentRunner` leaks
  between them.
- **`FileBackedAgentMemory` satisfies the letter of `AgentMemoryLaws` but
  not the spirit.** Ring 4 adds an explicit reload-across-instances property
  (write with one instance, recall with a fresh instance at the same path)
  to prove persistence is real. Ring 8 reviews the laws compliance
  requirement-by-requirement.
- **Scoring logic duplication.** If `InMemoryAgentMemory`'s scoring is
  private, the spec prefers extracting a shared helper inside the examples
  package over copying. The decision is recorded in the spec's `⚠ VERIFY`
  resolution.
