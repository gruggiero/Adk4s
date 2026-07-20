# Spec: Cross-Run Memory Example

<!-- This is a DELTA spec. Use ## ADDED Requirements for new content.
     Each spec is implemented and verified INDEPENDENTLY through the full
     ring pipeline. Keep specs self-contained — one capability per spec.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - CONCURRENCY RULE: not applicable — no concurrent behavior in this change. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [MemoryAwareRunner](../../../../concepts/memory-aware-runner.md) | the example wraps `AgentRunner` with this decorator to get automatic pre-turn recall / post-turn remember around each turn; the example subscribes to its event stream for the recall hit count and write count | `openspec/concepts/memory-aware-runner.md` |
| [AgentEventStream](../../../../concepts/agent-event-stream.md) | the example subscribes to `MemoryRecalled` and `MemoryWritten` events for the observability report | `openspec/concepts/agent-event-stream.md` |
| [AgentRunner](../../../../concepts/agent-runner.md) | the underlying runner the example constructs before wrapping it with the memory-aware decorator | `openspec/concepts/agent-runner.md` |

> This spec does NOT create or alter any behavioral concept. It consumes the
> existing `MemoryAwareRunner`, `AgentEventStream`, and `AgentRunner` concepts
> to build runnable examples. No `openspec/concepts/*.md` file is created or
> modified by this spec.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AgentMemory[F[_]]` | service trait | `org.adk4s.memory` |
| `Episode` | case class | `org.adk4s.memory` |
| `EpisodeOutcome` | case class | `org.adk4s.memory` |
| `MemoryHit` | case class | `org.adk4s.memory` |
| `SourceType` | enum | `org.adk4s.memory` |
| `TemporalScope` | case class | `org.adk4s.memory` |
| `InMemoryAgentMemory` | class (companion `naiveScore` method) | `org.adk4s.memory` |
| `MemoryRetriever` | adapter class | `org.adk4s.memory` |
| `AgentMemoryLaws` | laws trait | `org.adk4s.memory.testkit` |
| `Retriever` | service trait | `org.adk4s.core.component` |
| `Document` | case class | `org.adk4s.core.component` |
| `RetrieverConfig` | case class | `org.adk4s.core.component` |
| `ChatModel[F[_]]` | service trait | `org.adk4s.core.component` |
| `ReactAgent` | class | `org.adk4s.orchestration.agent` |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` |
| `InMemoryCheckpointStore` | class | `org.adk4s.orchestration.interrupt` |
| `MemoryAwareRunner` | decorator class | `org.adk4s.orchestration.memory` |
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` |
| `MemoryHook` | class | `org.adk4s.orchestration.memory` |
| `MemoryRecalled` | AgentEvent variant | `org.adk4s.core.interrupt` |
| `MemoryWritten` | AgentEvent variant | `org.adk4s.core.interrupt` |
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` |
| `UserMessage` / `Message` | case class | `llm4s` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `FileBackedAgentMemory[F[_]]` | final class (`adk4s-examples` main scope) | `AgentMemory[F]` double that persists `Episode`s as JSON lines to a configurable file, demonstrating cross-process durable memory. Scoring delegates to `InMemoryAgentMemory.naiveScore`. |
| `CrossRunMemoryExample` | `IOApp.Simple` object (`adk4s-examples` main scope) | CLI-arg-driven (`teach` / `recall` / `reset`) entry point demonstrating cross-run recall via `MemoryAwareRunner`. |
| `MemoryRetrieverExample` | `IOApp.Simple` object (`adk4s-examples` main scope) | Single-run entry point demonstrating the `MemoryRetriever` → `Retriever` seam. |
| JSON-lines `Episode` wire format | wire format (`adk4s-examples` main scope) | One `Episode` per line as upickle-generated JSON via a `ReadWriter[Episode]`; round-tripped by `FileBackedAgentMemory`. |
| `adk4s-examples % Test → adk4s-memory-testkit` build dependency | build wiring (Test scope) | One-line `build.sbt` edit enabling `FileBackedAgentMemorySpec` to run `AgentMemoryLaws`. |

## ADDED Requirements

### Requirement: File-backed memory persists episodes across process boundaries

The system SHALL provide an `AgentMemory[F[_]]` implementation that persists every remembered `Episode` to a JSON-lines file on disk, such that a fresh instance of the memory pointed at the same file recalls all episodes written by any prior instance — including instances from previously exited processes.

**Given** a `FileBackedAgentMemory[F]` instance `m1` configured with a storage path `p`, and a non-empty list of `Episode` values `es`
**When** `m1.rememberAll(es)` completes, then a new `FileBackedAgentMemory[F]` instance `m2` is constructed pointing at the same path `p`, and `m2.recall(query, k = es.length, scope = None)` is invoked with a `query` that substring-matches the content of every episode in `es`
**Then** the `MemoryHit` list returned by `m2.recall` contains one hit per episode in `es`, each hit's `text` equal to the corresponding episode's `content`.

**Rationale**: `InMemoryAgentMemory` cannot demonstrate cross-process recall — its `Ref` dies with the JVM. The file-backed double is the proof that the `AgentMemory` contract is satisfiable by a storage engine that survives process exit. This is the entire point of the deliverable.

#### Scenario: Teach in one instance, recall in a fresh instance

**Given** a temp directory `d`, a `FileBackedAgentMemory[IO]` `m1` constructed with `d`, and an `Episode` with `content = "My favorite color is blue"`, `sourceType = SourceType.Conversation`
**When** `m1.remember(episode)` completes, then `m1` is discarded, then a new `FileBackedAgentMemory[IO]` `m2` is constructed with the same `d`, then `m2.recall("favorite color", k = 5, scope = None)` is invoked
**Then** the returned hit list is non-empty and the first hit's `text` contains the substring `"blue"` (case-insensitive).

#### Scenario: Empty file on first recall

**Given** a `FileBackedAgentMemory[IO]` constructed with a temp directory `d` that contains no episodes file
**When** `recall("anything", k = 5, scope = None)` is invoked
**Then** the returned hit list is empty and no exception is raised (the missing file is treated as zero episodes, not an error).

#### Scenario: Concurrent writers are out of scope (error path)

**Given** two `FileBackedAgentMemory[IO]` instances pointing at the same path `p`
**When** both call `remember` simultaneously
**Then** the result is unspecified — the double is single-writer/single-reader by design. This scenario is documented as a non-goal; the spec asserts only that a single writer followed by a single reader works. (No concurrent-write guarantee is claimed or tested.)

### Requirement: File-backed memory satisfies the AgentMemory laws

The system SHALL ensure `FileBackedAgentMemory` passes `AgentMemoryLaws(indexesContent = true).all` — the same laws suite that `InMemoryAgentMemory` passes — so the demo double honors the contract rather than satisfying only the letter of the example.

**Given** a `FileBackedAgentMemory[IO]` constructed with a fresh temp directory per test
**When** `AgentMemoryLaws(indexesContent = true).all` is run against it
**Then** every property in the laws suite passes.

**Rationale**: The laws are the contract. A demo double that violates them would teach users the wrong shape. This also gives `adk4s-memory-testkit` its second in-repo consumer, keeping the testkit honest.

#### Scenario: Laws suite green

**Given** a `FileBackedAgentMemorySpec` extending the laws suite with a fresh temp dir per property
**When** the suite runs
**Then** all four `AgentMemoryLaws` properties pass with `indexesContent = true`.

#### Scenario: Laws suite red on a broken double (adversarial)

**Given** a `FileBackedAgentMemory` variant whose `recall` returns `Nil` regardless of input (simulating a double that satisfies the example's happy path but violates the laws)
**When** `AgentMemoryLaws(indexesContent = true).all` is run against it
**Then** at least one property fails. (This scenario is a proof obligation on the laws suite, not on the production double — it asserts the laws actually catch violations.)

### Requirement: Cross-run recall via the memory-aware runner (pass criteria A1 and A2)

The system SHALL provide a `CrossRunMemoryExample` entry point that, when invoked first with the `teach` mode and then (in a separate invocation) with the `recall` mode against the same storage directory, produces a final assistant message containing the fact taught in the teach run and a printed injected-context block containing that same fact.

**Given** a clean storage directory `d` (no prior episodes), a `CrossRunMemoryExample` invocation with mode `teach` and user input `"My favorite color is blue, and I work at Acme Corp."`, followed by a separate `CrossRunMemoryExample` invocation with mode `recall`, user input `"What is my favorite color?"`, and the same storage directory `d`
**When** the recall invocation completes
**Then** (A1) the final assistant message output contains the substring `"blue"` (case-insensitive), AND (A2) the printed injected-context block is non-empty and contains the substring `"blue"` (case-insensitive).

**Rationale**: This is the proof of life for durable memory. A single-process demo proves nothing beyond `ReactMemoryExample`; cross-process persistence via the `AgentMemory` machinery is the entire point. The two pass criteria are assertable, not just eyeballed.

#### Scenario: Teach then recall across two fully rebuilt stacks (in-process approximation)

**Given** a fresh temp dir `d`, a first agent/runner/memory stack `S1` built with `d` and a mock `ChatModel`, a teach turn with input `"My favorite color is blue, and I work at Acme Corp."` run through `S1`'s `MemoryAwareRunner`, then `S1` discarded, then a second agent/runner/memory stack `S2` built with `d` (sharing ONLY the storage directory with `S1`), a recall turn with input `"What is my favorite color?"` run through `S2`'s `MemoryAwareRunner`
**When** the recall turn completes
**Then** (A1) the `RunResult.Completed.output` contains `"blue"` (case-insensitive), AND (A2) the injected-context block printed during the recall turn contains `"blue"` (case-insensitive).

#### Scenario: Recall without prior teach (error/edge path)

**Given** a clean storage directory `d` (no episodes), a `CrossRunMemoryExample` invocation with mode `recall` and user input `"What is my favorite color?"`
**When** the recall invocation completes
**Then** the injected-context block is empty (no hits), and the mock model's response does NOT contain `"blue"` — proving the model answers from injected context, not from a canned script. (This is the adversarial check that the mock is honest.)

#### Scenario: Reset clears the storage directory

**Given** a storage directory `d` containing episodes written by a prior teach run
**When** `CrossRunMemoryExample` is invoked with mode `reset` and directory `d`
**Then** the episodes file under `d` is deleted and a subsequent recall invocation returns an empty hit list.

### Requirement: Mock model answers from injected context, not from a script

The system SHALL ensure the example's mock `ChatModel` derives its recall-run response from the memory-context block injected into the conversation, not from a hardcoded canned answer keyed on the query string.

**Given** a recall run whose conversation contains an injected memory-context block produced by `MemoryPolicy.default.render(hits)` where `hits` contains a `MemoryHit` with `text` containing `"blue"`
**When** the mock `ChatModel` generates a response for that conversation
**Then** the response text contains the remembered value found in the injected-context block (scanned for the `"Relevant memory:"` marker and the hit text that follows), NOT a hardcoded `"blue"` literal keyed on the query `"What is my favorite color?"`.

**Rationale**: If the mock answers from a script, the demo is a lie — it would "recall" blue even if the memory machinery injected nothing. The mock must demonstrably depend on the injected context. This is the adversarial core of the deliverable.

#### Scenario: Mock echoes the injected value

**Given** a conversation whose latest user message is prepended with `"Relevant memory:\n- My favorite color is blue"` by the memory-aware runner's pre-turn recall
**When** the mock `ChatModel.generate` is called with that conversation
**Then** the response contains `"blue"` because the mock scanned the injected block, NOT because the query matched a canned entry.

#### Scenario: Mock does not echo when no context is injected (adversarial)

**Given** a conversation with NO injected memory-context block (empty hit list → `render` returns `""`)
**When** the mock `ChatModel.generate` is called with that conversation and the query `"What is my favorite color?"`
**Then** the response does NOT contain `"blue"` — the mock has no source for the value. (This proves the mock's "knowledge" comes from the context, not from the query.)

### Requirement: Observability — the recall run narrates its mechanism

The system SHALL print, during a recall run, four labeled sections in order: pre-turn recall hits (with score and provenance per hit), the injected context block, the agent answer, and the post-turn write report (episode count). The hit count and write count are sourced from the `MemoryRecalled` and `MemoryWritten` events on the memory-aware runner's event stream; the full hit details (text, score, provenance) are sourced from one direct `recall` call because the `MemoryRecalled` event carries only a count.

**Given** a recall run executed via `MemoryAwareRunner.runWithEvents(messages)`
**When** the run completes and the event stream is consumed
**Then** the printed output contains, in order: a `Pre-turn recall` section listing each hit's score and provenance, an `Injected context` section showing the rendered block, an `Agent answer` section showing the final assistant message, and a `Post-turn remember` section showing the episode count from the `MemoryWritten` event.

**Rationale**: The mechanism must be visible, not just the final answer. A demo that prints only "Your favorite color is blue" proves nothing about HOW it knew. The labeled sections make the recall → inject → answer → remember cycle legible.

#### Scenario: Full observability output on a recall run

**Given** a storage directory with one episode `"My favorite color is blue"`, a recall run with query `"What is my favorite color?"`
**When** the run completes
**Then** the printed output contains the four labeled sections, the `Pre-turn recall` section shows at least one hit with a positive score, the `Injected context` section contains `"Relevant memory:"`, and the `Post-turn remember` section shows an episode count ≥ 1.

#### Scenario: Observability with zero hits (edge path)

**Given** a clean storage directory (no episodes), a recall run with any query
**When** the run completes
**Then** the `Pre-turn recall` section shows zero hits, the `Injected context` section is empty or absent, the `Agent answer` section is present, and the `Post-turn remember` section shows the episode count for the recall query itself (the recall turn's user input is persisted by the post-turn remember).

### Requirement: Memory retriever example shows the second seam

The system SHALL provide a `MemoryRetrieverExample` entry point that seeds three episodes via `memory.rememberAll`, builds a `MemoryRetriever` with `k = 2`, invokes `retrieve` with `"favorite color"`, and prints the returned `Document`s with their metadata (score, provenance, synthesized id).

**Given** a `FileBackedAgentMemory[IO]` `m` with three episodes seeded (one containing `"favorite color"`, two containing other content), and a `MemoryRetriever[IO]` built with `m` and `k = 2`
**When** `retriever.retrieve("favorite color", RetrieverConfig(...))` is invoked
**Then** the returned `Document` list has length ≤ 2, and the printed output shows each document's content, score, provenance metadata, and a deterministic synthesized id.

**Rationale**: This shows the second integration seam — memory adapted into the existing `Retriever` interface — so users see both ways to wire memory into an agent (the automatic `MemoryAwareRunner` hook and the manual `Retriever` adapter).

#### Scenario: Retriever returns top-k documents

**Given** three episodes seeded, `k = 2`, query `"favorite color"`
**When** `retrieve` is invoked
**Then** at most 2 `Document`s are returned, ranked by score descending, and the top document's content contains `"favorite color"`.

#### Scenario: Retriever with no matching episodes (edge path)

**Given** an empty memory, `k = 2`, query `"favorite color"`
**When** `retrieve` is invoked
**Then** an empty `Document` list is returned and no exception is raised.

### Requirement: Build wiring — examples module depends on the memory testkit in test scope

The system SHALL add a test-scope dependency from `adk4s-examples` to `adk4s-memory-testkit` in `build.sbt`, so that `FileBackedAgentMemorySpec` can run `AgentMemoryLaws` against the file-backed double. No main-scope dependency is added.

**Given** the `adk4s-examples` project definition in `build.sbt`
**When** the build is loaded
**Then** `adk4s-examples` has a `% Test` dependency on `adk4s-memory-testkit`, and `adk4s-memory-testkit` is NOT a main-scope dependency of `adk4s-examples`.

**Rationale**: `AgentMemoryLaws` lives in `adk4s-memory-testkit` (main scope — it is a downstream-consumable laws trait). `adk4s-examples` currently has no dependency on the testkit; sbt does not propagate test dependencies transitively. The one-line edit is the only build change this deliverable makes.

#### Scenario: Test-scope dependency present

**Given** the edited `build.sbt`
**When** `sbt adk4s-examples/Test/compile` is run with a test source importing `org.adk4s.memory.testkit.AgentMemoryLaws`
**Then** compilation succeeds.

#### Scenario: No main-scope leakage (adversarial)

**Given** the edited `build.sbt`
**When** a main-scope source under `adk4s-examples/src/main/scala` imports `org.adk4s.memory.testkit.AgentMemoryLaws`
**Then** compilation FAILS — the testkit is test-scope only, proving no main-scope coupling was introduced.

### Requirement: Documentation — README memory category and orchestration snippet

The system SHALL add a new *Memory* category to the README examples table with rows for `CrossRunMemoryExample`, `MemoryRetrieverExample`, and `FileBackedAgentMemory`, and SHALL add `MemoryAwareRunner` and `MemoryPolicy` to the `adk4s-orchestration` module bullet with a short wrap-the-runner snippet.

**Given** the README file at the repo root
**When** the documentation update is applied
**Then** the examples table contains a *Memory* category with the three rows, AND the orchestration section mentions `MemoryAwareRunner` and `MemoryPolicy` with a code snippet showing `MemoryAwareRunner(runner, Some(memory), MemoryPolicy.default)`.

**Rationale**: The memory hook is currently undocumented in the orchestration docs. The examples catalog is how users discover runnable artifacts; without a Memory category, the deliverable is invisible.

#### Scenario: README memory category present

**Given** the updated README
**When** a user reads the examples table
**Then** a *Memory* category is present with three rows, each row naming the example and its one-line purpose.

#### Scenario: Orchestration snippet present

**Given** the updated README orchestration section
**When** a user reads the `adk4s-orchestration` module bullet
**Then** `MemoryAwareRunner` and `MemoryPolicy` are listed, and a snippet showing how to wrap an `AgentRunner` with memory is present.

## Properties (Ring 3)

### Property: Remember-all then recall-all round-trip

**Invariant**: For every non-empty list of distinct episodes, after `rememberAll(es)` completes on a `FileBackedAgentMemory` instance, a fresh instance at the same path recalls every episode whose content substring-matches a query derived from that content.

**Generator strategy**: `genEpisodes` (existing, from `adk4s-memory-api` test sources — `Gen[List[Episode]]` with `Range.linear(1, 20)`). Constructive — no filtering. Coverage label: `cover(100, "non-empty episode list")`. Edge cases covered by the existing generator: empty content, long content, special characters in content.

```
forAll { (episodes: List[Episode]) =>
  for {
    dir  <- tempDir
    m1   = FileBackedAgentMemory[IO](dir)
    _    <- m1.rememberAll(episodes)
    m2    = FileBackedAgentMemory[IO](dir)   // fresh instance, same path
    hits <- m2.recall(queryFromFirstEpisode(episodes), k = episodes.length, scope = None)
  } yield hits.length === episodes.length && hits.map(_.text).toSet === episodes.map(_.content).toSet
}
```

### Property: Reload-across-instances preserves recall equivalence

**Invariant**: For any episode `e` and any two `FileBackedAgentMemory` instances `m1`, `m2` pointed at the same path, if `m1.remember(e)` completes then `m2.recall(query, k, scope)` returns the same hits as `m1.recall(query, k, scope)` — proving persistence is real, not an in-memory artifact.

**Generator strategy**: `genEpisode` (existing) + `genQuery` (existing) + `genK` (existing, `Range.linear(1, 10)`). Constructive. Coverage labels: `cover(10, "empty query")`, `cover(10, "query matches content")`, `cover(10, "query does not match")`.

```
forAll { (episode: Episode, query: String, k: Int) =>
  for {
    dir  <- tempDir
    m1   = FileBackedAgentMemory[IO](dir)
    _    <- m1.remember(episode)
    h1   <- m1.recall(query, k, scope = None)
    m2    = FileBackedAgentMemory[IO](dir)   // fresh instance
    h2   <- m2.recall(query, k, scope = None)
  } yield h1 === h2   // same hits from both instances
}
```

### Property: Scoring delegates to InMemoryAgentMemory.naiveScore

**Invariant**: For every content string `c` and query string `q`, the score `FileBackedAgentMemory` assigns to a hit from an episode with content `c` equals `InMemoryAgentMemory.naiveScore(c.toLowerCase, q.toLowerCase)` — the file-backed double does not invent its own scoring.

**Generator strategy**: `genContent` (existing) + `genQuery` (existing). Constructive. Coverage label: `cover(20, "query is substring of content")`.

```
forAll { (content: String, query: String) =>
  for {
    dir  <- tempDir
    m    = FileBackedAgentMemory[IO](dir)
    _    <- m.remember(Episode(content, SourceType.Conversation, Instant.now, None, Map.empty))
    hits <- m.recall(query, k = 5, scope = None)
    expectedScore = InMemoryAgentMemory.naiveScore(content.toLowerCase, query.toLowerCase)
  } yield hits.headOption.map(_.score) === Some(expectedScore).filter(_ > 0.0) or hits.isEmpty === (expectedScore <= 0.0)
}
```

### Property: Empty storage returns empty hits (no error)

**Invariant**: For any query and any `FileBackedAgentMemory` pointed at a directory with no episodes file, `recall` returns `Nil` and raises no exception.

**Generator strategy**: `genQuery` (existing) + `genK` (existing). Constructive. Edge case: the directory itself may not exist yet.

```
forAll { (query: String, k: Int) =>
  for {
    dir <- tempDir   // empty, no episodes file
    m    = FileBackedAgentMemory[IO](dir)
    r   <- m.recall(query, k, scope = None).attempt
  } yield r.isRight && r.toOption.get.isEmpty
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| Main-scope `adk4s-examples` source importing `org.adk4s.memory.testkit.*` | The testkit is test-scope only; main-scope coupling would violate the build-wiring requirement | `assertDoesNotCompile("import org.adk4s.memory.testkit.AgentMemoryLaws")` in a main-scope compile-negative check (or verified by `sbt adk4s-examples/compile` failing if such an import is added to main sources) |

## Formal Contracts (Ring 6)

Ring 6 is NOT checked for this change (the file-backed memory is effectful `IO` wiring, not a PureScala module). No formal contracts.

## Temporal Properties (Ring 9)

Ring 9 is NOT checked (no telemetry stack detected). No temporal properties.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| File-backed memory persists across instances | Requirement 1, scenario 1 | Property test (reload-across-instances) + scenario test | `FileBackedAgentMemorySpec` |
| Empty file on first recall returns `Nil` | Requirement 1, scenario 2 | Property test (empty storage) | `FileBackedAgentMemorySpec` |
| No concurrent-write guarantee claimed | Requirement 1, scenario 3 | Manual review (documented non-goal) | spec §"Non-goals" |
| `FileBackedAgentMemory` passes `AgentMemoryLaws` | Requirement 2, scenario 1 | Laws suite run against the double | `FileBackedAgentMemorySpec` |
| Laws suite catches violations | Requirement 2, scenario 2 | Adversarial: run laws against a broken double, assert ≥ 1 failure | `FileBackedAgentMemorySpec` (negative test) |
| Cross-run recall A1 + A2 | Requirement 3, scenario 1 | Scenario test (two rebuilt stacks, shared temp dir only) | `CrossRunMemorySmokeSpec` |
| Recall without teach does not produce "blue" | Requirement 3, scenario 2 | Scenario test (adversarial — empty storage, assert no "blue") | `CrossRunMemorySmokeSpec` |
| Reset clears storage | Requirement 3, scenario 3 | Scenario test | `CrossRunMemorySmokeSpec` or manual |
| Mock answers from injected context, not script | Requirement 4, scenario 1 | Scenario test + adversarial review (Ring 8) | `CrossRunMemorySmokeSpec` + Ring 8 |
| Mock does not echo when no context injected | Requirement 4, scenario 2 | Scenario test (adversarial — no injected block, assert no "blue") | `CrossRunMemorySmokeSpec` |
| Observability — four labeled sections in order | Requirement 5, scenario 1 | Scenario test (assert printed output contains sections in order) | `CrossRunMemorySmokeSpec` |
| Observability with zero hits | Requirement 5, scenario 2 | Scenario test (edge path) | `CrossRunMemorySmokeSpec` |
| Retriever returns top-k with metadata | Requirement 6, scenario 1 | Scenario test | `MemoryRetrieverExampleSpec` or manual run |
| Retriever with no matches returns empty | Requirement 6, scenario 2 | Scenario test (edge path) | `MemoryRetrieverExampleSpec` |
| Test-scope dep on testkit present | Requirement 7, scenario 1 | `sbt adk4s-examples/Test/compile` succeeds with laws import | build + `FileBackedAgentMemorySpec` compilation |
| No main-scope testkit leakage | Requirement 7, scenario 2 | Compile-negative test (adversarial) | `assertDoesNotCompile` or `sbt adk4s-examples/compile` |
| README memory category present | Requirement 8, scenario 1 | Manual review | README |
| README orchestration snippet present | Requirement 8, scenario 2 | Manual review | README |
| Scoring delegates to `naiveScore` | Property 3 | Property test | `FileBackedAgentMemorySpec` |
| Remember-all/recall-all round-trip | Property 1 | Property test | `FileBackedAgentMemorySpec` |
| Two stacks share only temp dir (no in-memory leak) | Requirement 3, scenario 1 | Adversarial review (Ring 8) — verify no shared `Ref`/`InMemoryAgentMemory`/`AgentRunner` | Ring 8 |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `FileBackedAgentMemory.scala` | new file | `adk4s-examples/src/main/scala/org/adk4s/examples/memory/` | implements `AgentMemory[F[_]: Sync]`; scoring delegates to `InMemoryAgentMemory.naiveScore`; persistence via upickle `ReadWriter[Episode]` JSON-lines |
| `CrossRunMemoryExample.scala` | new file | `adk4s-examples/src/main/scala/org/adk4s/examples/memory/` | `IOApp.Simple`; CLI arg `args.headOption` selects `teach`/`recall`/`reset`; wiring: `ReactAgent.create` → `AgentRunner.create(_, InMemoryCheckpointStore())` → `MemoryAwareRunner(_, Some(memory), MemoryPolicy.default)` → `runWithEvents` |
| `MemoryRetrieverExample.scala` | new file | `adk4s-examples/src/main/scala/org/adk4s/examples/memory/` | `IOApp.Simple`; seeds 3 episodes, builds `MemoryRetriever[IO](memory, k = 2)`, calls `retrieve("favorite color", RetrieverConfig(...))` |
| `FileBackedAgentMemorySpec.scala` | new test file | `adk4s-examples/src/test/scala/org/adk4s/examples/memory/` | runs `AgentMemoryLaws(indexesContent = true).all` + the 4 Ring 3 properties; temp dir per test |
| `CrossRunMemorySmokeSpec.scala` | new test file | `adk4s-examples/src/test/scala/org/adk4s/examples/memory/` | munit `FunSuite`/`CatsEffectSuite`; two-stack teach-then-recall; asserts A1 + A2 + observability sections + adversarial no-context scenario |
| `build.sbt` | edit | `adk4s-examples` project def | add `` `adk4s-memory-testkit` % Test `` to `.dependsOn(...)` |
| `run-example.sh` | edit | `adk4s-examples/run-example.sh` | register keys `crossrunmemory` (with sub-arg `teach`/`recall`/`reset`) and `memoryretriever` |
| `README.md` | edit | repo root | new *Memory* category in examples table; `MemoryAwareRunner`/`MemoryPolicy` in orchestration section with wrap-the-runner snippet |
| mock `ChatModel` | new private impl inside `CrossRunMemoryExample` | `adk4s-examples/src/main/scala/org/adk4s/examples/memory/CrossRunMemoryExample.scala` | follows existing example convention (mock by default, OpenAI when `OPENAI_API_KEY` set); recall-run response scans conversation for `"Relevant memory:"` marker and echoes the hit text found there |
| `MemoryAwareRunner.runWithEvents` | reused method | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala:63` | returns `(IO[RunResult], Stream[IO, AgentEvent])` — the example subscribes for `MemoryRecalled`/`MemoryWritten` |
| `MemoryPolicy.default.render` | reused field/method | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryPolicy.scala:23,43` | `render: List[MemoryHit] => String`; `defaultRender` prefix is `"Relevant memory:"` — the mock scans for this marker |
| `InMemoryAgentMemory.naiveScore` | reused companion method | `adk4s-memory-api/src/main/scala/org/adk4s/memory/InMemoryAgentMemory.scala:55` | public; `FileBackedAgentMemory.recall` delegates scoring here |
| `MemoryRetriever.apply` | reused factory | `adk4s-memory-api/src/main/scala/org/adk4s/memory/MemoryRetriever.scala:32` | `MemoryRetriever[IO](memory, k)`; `retrieve(query, config)` returns `F[List[Document]]` |
