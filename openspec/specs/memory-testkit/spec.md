# memory-testkit Specification

## Purpose
TBD - created by archiving change add-memory-api. Update Purpose after archive.
## Requirements
### Requirement: AgentMemoryLaws encodes four behavioral laws gated by indexesContent

The system SHALL provide `AgentMemoryLaws(indexesContent: Boolean)` with methods `kBound(mem: AgentMemory[IO]): IO[Boolean]`, `scoreOrdering(mem: AgentMemory[IO]): IO[Boolean]`, `recallAfterRemember(mem: AgentMemory[IO]): IO[Boolean]`, `temporalIgnorability(mem: AgentMemory[IO]): IO[Boolean]`, and `all(mem: AgentMemory[IO]): IO[Boolean]` that ANDs the four. `recallAfterRemember` SHALL be a no-op `IO.pure(true)` when `indexesContent == false`.

**Given** `AgentMemoryLaws(indexesContent = true)` and an `AgentMemory[IO]` satisfying the contract
**When** `all(mem)` is run
**Then** the result is `IO.pure(true)`

**Rationale**: GraphStore's test suite imports this and runs the same assertions against its Neo4j-backed implementation (Testcontainers), guaranteeing both backends honor one contract. A write-only sink sets `indexesContent = false` to opt out of law 1.

#### Scenario: all four laws pass for InMemoryAgentMemory

**Given** `AgentMemoryLaws(indexesContent = true)` and `InMemoryAgentMemory.create[IO]`
**When** `all(mem)` is run
**Then** the result is `true`

#### Scenario: recallAfterRemember is a no-op when indexesContent is false

**Given** `AgentMemoryLaws(indexesContent = false)` and any `AgentMemory[IO]`
**When** `recallAfterRemember(mem)` is run
**Then** the result is `IO.pure(true)` regardless of whether `recall` would find anything

### Requirement: kBound law asserts recall returns at most k

The system SHALL implement `kBound(mem)` to remember 10 episodes containing "widgets", call `recall("widgets", k = 3)`, and return `hits.size <= 3`.

**Given** an `AgentMemory[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `kBound(mem)` is run
**Then** the result is `true` iff `recall("widgets", 3).map(_.size <= 3)`

**Rationale**: This is law 3 of the informal contract — the k-bound — encoded as a runnable assertion.

#### Scenario: kBound passes for InMemoryAgentMemory

**Given** `InMemoryAgentMemory.create[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `kBound(mem)` is run
**Then** the result is `true`

### Requirement: scoreOrdering law asserts descending score

The system SHALL implement `scoreOrdering(mem)` to call `recall("anything", 10)` and return `hits.map(_.score) == hits.map(_.score).sortBy(-_)`.

**Given** an `AgentMemory[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `scoreOrdering(mem)` is run
**Then** the result is `true` iff the scores are non-increasing

**Rationale**: This is law 2 — score ordering — encoded as a runnable assertion.

#### Scenario: scoreOrdering passes for InMemoryAgentMemory

**Given** `InMemoryAgentMemory.create[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `scoreOrdering(mem)` is run
**Then** the result is `true`

### Requirement: recallAfterRemember law asserts recall finds a remembered term

The system SHALL implement `recallAfterRemember(mem)` (when `indexesContent == true`) to remember `Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1"))`, call `recall("Meta", 5)`, and return `hits.nonEmpty`.

**Given** an `AgentMemory[IO]` that indexes content and `AgentMemoryLaws(indexesContent = true)`
**When** `recallAfterRemember(mem)` is run
**Then** the result is `true` iff `recall("Meta", 5)` returns a non-empty list

**Rationale**: This is law 1 — recall-after-remember — gated by `indexesContent` so write-only sinks are not falsely penalized.

#### Scenario: recallAfterRemember passes for InMemoryAgentMemory

**Given** `InMemoryAgentMemory.create[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `recallAfterRemember(mem)` is run
**Then** the result is `true`

### Requirement: temporalIgnorability law asserts scope never errors

The system SHALL implement `temporalIgnorability(mem)` to call `recall("x", 5, Some(TemporalScope(now)))` and return `.attempt.isRight`.

**Given** an `AgentMemory[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `temporalIgnorability(mem)` is run
**Then** the result is `true` iff the `recall` with a `Some(scope)` completes without error

**Rationale**: This is law 4 — temporal ignorability — a backend that ignores `scope` MUST still complete without error (`.attempt` yields `Right`), returning the same hits it would return for `None`.

#### Scenario: temporalIgnorability passes for InMemoryAgentMemory

**Given** `InMemoryAgentMemory.create[IO]` and `AgentMemoryLaws(indexesContent = true)`
**When** `temporalIgnorability(mem)` is run
**Then** the result is `true`

### Requirement: adk4s-memory-testkit module publishes AgentMemoryLaws in main scope

The system SHALL provide an `adk4s-memory-testkit` sbt module with `dependsOn(adk4s-memory-api)`, `cats-effect` and `munit` in **main** scope (not Test), so a downstream backend can add `libraryDependencies += "org.adk4s" %% "adk4s-memory-testkit" % version` and import `AgentMemoryLaws` directly.

**Given** a downstream project with `libraryDependencies += "org.adk4s" %% "adk4s-memory-testkit" % "0.1.0-SNAPSHOT"`
**When** the downstream project compiles a test that imports `org.adk4s.memory.testkit.AgentMemoryLaws`
**Then** the import resolves and the test compiles

**Rationale**: A `-testkit` sibling module (main-scoped) is preferred over `Test` scope export / `sbt-testkit` gymnastics because it keeps GraphStore's dependency a plain `libraryDependencies` line.

#### Scenario: testkit module compiles independently

**Given** the `adk4s-memory-testkit` module
**When** `sbt "adk4s-memory-testkit/compile"` is run
**Then** it compiles without requiring `adk4s-memory-api` test sources

#### Scenario: testkit module has no heavy deps

**Given** the `adk4s-memory-testkit` module
**When** `sbt "adk4s-memory-testkit/dependencyTree"` is run
**Then** the tree contains no `neo4j`, `lucene`, `http4s`, `cats-effect-std` beyond what `adk4s-memory-api` already pulls

