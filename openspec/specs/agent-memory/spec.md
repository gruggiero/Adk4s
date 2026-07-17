# agent-memory Specification

## Purpose
TBD - created by archiving change add-memory-api. Update Purpose after archive.
## Requirements
### Requirement: AgentMemory trait is effect-polymorphic with no Sync constraint

The system SHALL provide an `AgentMemory[F[_]]` trait with `remember(episode: Episode): F[EpisodeOutcome]`, `recall(query: String, k: Int, scope: Option[TemporalScope]): F[List[MemoryHit]]`, and a `rememberAll(episodes: List[Episode])(using Monad[F]): F[List[EpisodeOutcome]]` default method, and the trait SHALL NOT impose a `Sync`/`Async`/`MonadCancel` constraint on `F`.

**Given** an `AgentMemory[F]` implementation parameterized on an arbitrary `F[_]` with only a `Monad` available
**When** `rememberAll` is called with a non-empty list of episodes
**Then** the result is `F[List[EpisodeOutcome]]` produced by traversing `remember` over the list, with no `Sync`/`Async` requirement

**Rationale**: Implementations range from a `Ref`-backed double (needs `Sync`) to a temporal knowledge graph (may need only `Async`). The constraint lives on implementations/factories, not on the trait, so a future no-`Sync` backend can implement it.

#### Scenario: rememberAll default uses Traverse

**Given** an `AgentMemory[IO]` and a list of 3 episodes
**When** `rememberAll(episodes)` is called without overriding the default
**Then** the result is `IO[List[EpisodeOutcome]]` containing 3 outcomes, each equal to what `remember` would return for the corresponding episode

#### Scenario: recall accepts default None scope

**Given** an `AgentMemory[IO]` with at least one remembered episode
**When** `recall("query", 5)` is called (scope omitted, defaulting to `None`)
**Then** the call compiles and returns `IO[List[MemoryHit]]` without error

### Requirement: Episode records valid-time timestamp and optional group/metadata

The system SHALL model an episode as `Episode(content: String, sourceType: SourceType, timestamp: Instant, groupId: Option[String] = None, metadata: Map[String, String] = Map.empty)` where `timestamp` is the *valid time* (when the described facts were true in the world), not necessarily the recording time.

**Given** an `Episode` constructed with `timestamp = Instant.parse("2025-01-01T00:00:00Z")`
**When** the `timestamp` field is read
**Then** it returns `Instant.parse("2025-01-01T00:00:00Z")` exactly

**Rationale**: Backends with a bi-temporal model use `timestamp` as `validFrom`. Conflating it with recording time would make point-in-time recall impossible.

#### Scenario: Episode.conversation convenience factory

**Given** `Episode.conversation("hello", "session-1", Instant.parse("2025-01-01T00:00:00Z"))`
**When** the resulting `Episode` is inspected
**Then** `sourceType` is `SourceType.Conversation`, `content` is "hello", `groupId` is `Some("session-1")`, `timestamp` is the given instant, and `metadata` is `Map.empty`

#### Scenario: metadata defaults to empty map

**Given** `Episode("x", SourceType.Document, Instant.parse("2025-01-01T00:00:00Z"))`
**When** `metadata` is read
**Then** it is `Map.empty`

### Requirement: SourceType is a closed enum of five cases

The system SHALL define `SourceType` as an enum with exactly the cases `Conversation`, `Document`, `StructuredData`, `ToolResult`, `ExternalApi`, and no others.

**Given** the `SourceType` enum
**When** its cases are enumerated
**Then** the result is exactly the set `{Conversation, Document, StructuredData, ToolResult, ExternalApi}`

**Rationale**: A closed enum enables exhaustive pattern matching in backends that handle sources differently.

#### Scenario: exhaustive match compiles

**Given** a `SourceType` value
**When** a pattern match handles all five cases with no default arm
**Then** the code compiles without a warning about non-exhaustive match

### Requirement: EpisodeOutcome reports counts and errors with isSuccess

The system SHALL model a commit result as `EpisodeOutcome(entitiesExtracted: Int, relationshipsCreated: Int, edgesInvalidated: Int, processingTimeMs: Long, errors: List[String] = Nil)` with `isSuccess: Boolean = errors.isEmpty`, and provide `EpisodeOutcome.empty` with all counts zero, `processingTimeMs` zero, and `errors` empty.

**Given** `EpisodeOutcome.empty`
**When** `isSuccess` is read
**Then** it is `true`

**Rationale**: Counts let backends report what happened without leaking infrastructure types across the boundary. A backend that does no extraction reports `empty` and still succeeds.

#### Scenario: errors make isSuccess false

**Given** `EpisodeOutcome(0, 0, 0, 0L, List("timeout"))`
**When** `isSuccess` is read
**Then** it is `false`

#### Scenario: empty has zero counts

**Given** `EpisodeOutcome.empty`
**When** all fields are read
**Then** `entitiesExtracted == 0`, `relationshipsCreated == 0`, `edgesInvalidated == 0`, `processingTimeMs == 0L`, `errors == Nil`

### Requirement: MemoryHit carries agent-facing text, score, and temporal window

The system SHALL model a recalled fact as `MemoryHit(text: String, score: Double, validFrom: Option[Instant] = None, validTo: Option[Instant] = None, provenance: Option[String] = None, payload: Map[String, String] = Map.empty)` where `text` is suitable for splicing into a prompt.

**Given** a `MemoryHit(text = "Alice works at Meta", score = 0.9, validFrom = Some(Instant.parse("2025-01-01T00:00:00Z")))`
**When** the fields are read
**Then** `text` is "Alice works at Meta", `score` is 0.9, `validFrom` is `Some(Instant.parse("2025-01-01T00:00:00Z"))`, `validTo` is `None`, `provenance` is `None`, `payload` is `Map.empty`

**Rationale**: `text` is the agent-facing rendering; `validFrom`/`validTo` expose the temporal window (both `None` for non-temporal backends); `provenance` points back to the episode/group.

#### Scenario: non-temporal backend returns None windows

**Given** a `MemoryHit` produced by `InMemoryAgentMemory`
**When** `validTo` is read
**Then** it is `None` (the in-memory double does not track validity end)

### Requirement: recall returns at most k hits sorted by descending score

The system SHALL ensure `recall(query, k, scope)` returns at most `k` `MemoryHit`s sorted by descending `score`.

**Given** an `AgentMemory[F]` with N remembered episodes where N > k
**When** `recall(query, k)` is called with a query matching all N episodes
**Then** the result has size `<= k` and `result.map(_.score) == result.map(_.score).sortBy(-_)`

**Rationale**: A bounded, score-ordered result is the contract every backend must honor so callers can rely on it.

#### Scenario: k=3 with 10 matches returns 3

**Given** an `AgentMemory[IO]` with 10 episodes all containing "widgets"
**When** `recall("widgets", 3)` is called
**Then** the result has size 3 and the scores are non-increasing

#### Scenario: k=0 returns empty list

**Given** an `AgentMemory[IO]` with remembered episodes
**When** `recall("anything", 0)` is called
**Then** the result is `Nil`

#### Scenario: no matches returns empty list

**Given** an `AgentMemory[IO]` with episodes about "widgets"
**When** `recall("socks", 5)` is called
**Then** the result is `Nil`

### Requirement: TemporalScope is ignorable by non-temporal backends

The system SHALL ensure a backend that does not support temporality completes `recall` without raising an error (i.e. `.attempt` yields `Right`) when called with a `Some(TemporalScope)`, returning the same hits it would return for `None`.

**Given** `InMemoryAgentMemory[IO]` (which ignores `scope`)
**When** `recall("x", 5, Some(TemporalScope(Instant.parse("2025-01-01T00:00:00Z"))))` is called
**Then** the result is `IO[List[MemoryHit]]` completing successfully (right of an `Either`/no exception)

**Rationale**: Forcing every backend to implement temporality would block the test double and simple stores. Ignoring — not failing — is the contract.

#### Scenario: scope does not crash the in-memory double

**Given** `InMemoryAgentMemory[IO]` with one remembered episode
**When** `recall("query", 5, Some(TemporalScope(Instant.parse("2030-01-01T00:00:00Z"))))` is attempted
**Then** the `IO` completes successfully (`.attempt` yields `Right`)

#### Scenario: scope=None and scope=Some return identical hits for non-temporal backend

**Given** `InMemoryAgentMemory[IO]` with `Episode("alpha", SourceType.Document, now)` remembered
**When** `recall("alpha", 5, None)` and `recall("alpha", 5, Some(TemporalScope(now)))` are both called
**Then** the two resulting `List[MemoryHit]` are equal (same size, same texts, same scores)

### Requirement: InMemoryAgentMemory is substring-indexed and Ref-backed

The system SHALL provide `InMemoryAgentMemory[F[_]: Sync]` backed by `Ref[F, Vector[Episode]]` that scores episodes by substring/term-count match, returns `EpisodeOutcome.empty` on `remember`, and ignores `TemporalScope` on `recall`.

**Given** `InMemoryAgentMemory[IO]` with `Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1"))` remembered
**When** `recall("Meta", 5)` is called
**Then** the result is non-empty and the first hit's `text` is "Alice works at Meta"

**Rationale**: A zero-dependency double lets tests, demos, and local dev exercise the memory contract without infrastructure.

#### Scenario: exact substring scores 1.0

**Given** an episode with content "the quick brown fox" remembered
**When** `recall("quick brown", 5)` is called
**Then** the first hit has `score == 1.0` (the query is a substring of the content, case-insensitive)

#### Scenario: partial term match scores fractionally

**Given** an episode with content "the quick brown fox" remembered
**When** `recall("quick socks", 5)` is called
**Then** the first hit has `score == 0.5` (one of two terms matches: "quick" yes, "socks" no)

#### Scenario: no term match returns no hit

**Given** an episode with content "the quick brown fox" remembered
**When** `recall("elephant", 5)` is called
**Then** the result is `Nil`

#### Scenario: remember returns EpisodeOutcome.empty

**Given** `InMemoryAgentMemory[IO]`
**When** `remember(Episode("x", SourceType.Document, now))` is called
**Then** the result is `IO.pure(EpisodeOutcome.empty)`

#### Scenario: create factory yields an empty memory

**Given** `InMemoryAgentMemory.create[IO]`
**When** the resulting `AgentMemory[IO]` is queried with `recall("anything", 10)`
**Then** the result is `Nil` (no episodes have been remembered)

### Requirement: rememberAll default traverses remember

The system SHALL provide a `rememberAll(episodes: List[Episode])(using Monad[F]): F[List[EpisodeOutcome]]` default method that traverses `remember` over the list, and backends with a cheaper bulk path MAY override it.

**Given** an `AgentMemory[IO]` and a list of 3 episodes
**When** `rememberAll(episodes)` is called
**Then** the result is `IO[List[EpisodeOutcome]]` of size 3, each equal to `remember(ep)` for the corresponding episode

**Rationale**: The default lets simple backends inherit batch ingest for free; bulk-optimized backends override.

#### Scenario: empty list yields empty outcomes

**Given** an `AgentMemory[IO]`
**When** `rememberAll(Nil)` is called
**Then** the result is `IO.pure(Nil)`

