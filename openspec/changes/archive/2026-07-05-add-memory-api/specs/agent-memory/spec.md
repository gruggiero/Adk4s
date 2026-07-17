# Spec: Agent Memory

<!-- This is a DELTA spec. It introduces a NEW capability — durable, recallable
     agent memory — as an effect-polymorphic trait `AgentMemory[F]` with
     immutable value types and a zero-dependency in-process test double.
     No storage engine, embeddings, or graph logic live in this spec. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Retriever[F]` | service trait | `org.adk4s.core.component` |
| `Document` | case class | `org.adk4s.core.component` |
| `RetrieverConfig` | case class | `org.adk4s.core.component` |

> Note: `Retriever`/`Document`/`RetrieverConfig` are referenced here for
> context; they are *consumed* by the `memory-retriever-bridge` spec, not by
> this spec's implementation. This spec depends only on `cats-core`
> typeclasses (`Monad`, `Traverse`, `Functor`) and `java.time.Instant` for the
> interface, plus `cats-effect` (`Ref`, `Sync`) for `InMemoryAgentMemory`.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `AgentMemory[F[_]]` | service trait | Effect-polymorphic capability: `remember`, `recall`, `rememberAll` (default via `Traverse`). No `Sync` constraint on the trait. |
| `Episode` | case class | A discrete unit of experience: `content: String`, `sourceType: SourceType`, `timestamp: Instant`, `groupId: Option[String]`, `metadata: Map[String, String]`. |
| `SourceType` | enum | `Conversation`, `Document`, `StructuredData`, `ToolResult`, `ExternalApi`. |
| `EpisodeOutcome` | case class | Commit result counts: `entitiesExtracted: Int`, `relationshipsCreated: Int`, `edgesInvalidated: Int`, `processingTimeMs: Long`, `errors: List[String]`. `isSuccess: Boolean`. `empty` companion. |
| `MemoryHit` | case class | A recalled fact: `text: String`, `score: Double`, `validFrom: Option[Instant]`, `validTo: Option[Instant]`, `provenance: Option[String]`, `payload: Map[String, String]`. |
| `TemporalScope` | case class | Optional point-in-time scoping: `asOf: Instant`. Non-temporal backends MUST ignore it. |
| `InMemoryAgentMemory[F[_]]` | class (main scope) | Zero-dependency substring-indexed test double backed by `Ref[F, Vector[Episode]]`; requires `Sync[F]`. |

## ADDED Requirements

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

## Properties (Ring 3)

### Property: recall-k-bound

**Invariant**: For all `AgentMemory[IO]` implementations and all non-negative `k`, `recall(query, k).map(_.size)` is `<= k`.

**Generator strategy**: `genEpisodes: Gen[List[Episode]]` (constructive — content from `Gen.string(Gen.alphaNum, Range.linear(1, 20))`, sourceType from `Gen.element1(SourceType.values.toList)`, timestamp from `Gen.instant`), `genQuery: Gen[String]` (alphaNum, 1..10 chars), `genK: Gen[Int]` from `Range.linear(0, 20)`. Classify by `k == 0`, `k < episodes.size`, `k >= episodes.size`.

```
forAll { (episodes: List[Episode], query: String, k: Int) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- episodes.traverse_(mem.remember)
    hits <- mem.recall(query, k)
  } yield hits.size <= k
}
```

### Property: recall-score-ordering

**Invariant**: For all `AgentMemory[IO]` and all queries, `recall(query, k).map(_.map(_.score))` is non-increasing.

**Generator strategy**: `genEpisodes`, `genQuery`, `genK` (k from `Range.linear(1, 20)` to avoid the trivial empty case). Classify by `hits.size == 0`, `hits.size == 1`, `hits.size > 1`.

```
forAll { (episodes: List[Episode], query: String, k: Int) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- episodes.traverse_(mem.remember)
    hits <- mem.recall(query, k)
  } yield hits.map(_.score) == hits.map(_.score).sortBy(-_)
}
```

### Property: recall-after-remember (gated by indexesContent)

**Invariant**: For backends with `indexesContent = true`, after `remember(episode)` succeeds for an episode whose `content` contains term `t`, a subsequent `recall(t, k>=1)` returns a non-empty list.

**Generator strategy**: `genTerm: Gen[String]` (alphaNum, 3..8 chars), embed the term in a longer content string. `genK: Gen[Int]` from `Range.linear(1, 10)`. Classify by `k == 1`, `k > 1`.

```
forAll { (term: String, k: Int) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- mem.remember(Episode(s"fact about $term here", SourceType.Document, now))
    hits <- mem.recall(term, k)
  } yield hits.nonEmpty
}
```

### Property: temporal-ignorability

**Invariant**: For all `AgentMemory[IO]` and all `TemporalScope`, `recall(query, k, Some(scope)).attempt` is `Right`.

**Generator strategy**: `genEpisodes`, `genQuery`, `genK`, `genScope: Gen[TemporalScope]` (constructive from `Gen.instant`). Classify by `episodes.isEmpty`, `episodes.nonEmpty`.

```
forAll { (episodes: List[Episode], query: String, k: Int, scope: TemporalScope) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- episodes.traverse_(mem.remember)
    r    <- mem.recall(query, k, Some(scope)).attempt
  } yield r.isRight
}
```

### Property: naiveScore-monotonicity

**Invariant**: For `InMemoryAgentMemory`, if content `c1` contains query `q` as a substring and content `c2` does not contain `q` but shares fewer terms, then `score(c1, q) > score(c2, q)`.

**Generator strategy**: `genQuery: Gen[String]` (alphaNum, 1..8), `genSurrounding: Gen[String]` (alphaNum, 0..10). Construct `c1 = surrounding + query + surrounding`, `c2 = surrounding` only. Classify by `c2.contains(query)` (should be false).

```
forAll { (query: String, surrounding: String) =>
  val c1 = surrounding + query + surrounding
  val c2 = surrounding
  InMemoryAgentMemory.naiveScore(c1.toLowerCase, query.toLowerCase) >
    InMemoryAgentMemory.naiveScore(c2.toLowerCase, query.toLowerCase)
}
```

> Note: `naiveScore` is exposed for property testing (private to the object but
> reachable from the same-module test). If kept private, the property tests via
> `recall` outcomes instead.

### Property: rememberAll-size-match

**Invariant**: For all `AgentMemory[IO]` and all episode lists, `rememberAll(eps).map(_.size) == eps.size`.

**Generator strategy**: `genEpisodes` (size 0..10 via `Range.linear(0, 10)`). Classify by `eps.isEmpty`, `eps.size == 1`, `eps.size > 1`.

```
forAll { (episodes: List[Episode]) =>
  for {
    mem      <- InMemoryAgentMemory.create[IO]
    outcomes <- mem.rememberAll(episodes)
  } yield outcomes.size == episodes.size
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `AgentMemory[F]` requiring `Sync[F]` on the trait | The trait must be effect-polymorphic with only `Monad` for the default `rememberAll`; `Sync` lives on implementations | `assertDoesNotCompile("trait AgentMemory[F[_]: Sync]")` (documentary — the actual trait definition must not carry the constraint) |
| A 6th `SourceType` case | The enum is closed at 5 cases | compile-negative: a pattern match with a 6th case arm fails to compile (covered by exhaustive-match scenario) |

## Formal Contracts (Ring 6)

> Ring 6 is not applied for this spec (per proposal: `naiveScore` is too thin to
> justify a Stainless mirror; Hedgehog properties cover the same ground). This
> section is intentionally omitted.

## Temporal Properties (Ring 9)

> Ring 9 does not apply (no telemetry stack detected; no `AgentEvent` variants
> added in this change). This section is intentionally omitted.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| `AgentMemory[F]` has no `Sync` constraint on the trait | Requirement: trait is effect-polymorphic | type system + compile-negative test | `AgentMemoryTypeContract.scala` |
| `SourceType` is closed at 5 cases | Requirement: SourceType is a closed enum | type system (enum) + exhaustive-match scenario | `AgentMemorySpec.scala` |
| `recall` returns `<= k` hits | Requirement + Property: recall-k-bound | Hedgehog property | `AgentMemorySpec.scala` |
| `recall` results sorted by descending score | Requirement + Property: recall-score-ordering | Hedgehog property | `AgentMemorySpec.scala` |
| `recall` with `Some(scope)` never errors on non-temporal backend | Requirement + Property: temporal-ignorability | Hedgehog property + scenario | `AgentMemorySpec.scala` |
| `remember` returns `EpisodeOutcome.empty` for the in-memory double | Requirement + Scenario | scenario test | `AgentMemorySpec.scala` |
| `rememberAll` default traverses `remember` | Requirement + Property: rememberAll-size-match | Hedgehog property | `AgentMemorySpec.scala` |
| `Episode.conversation` sets `SourceType.Conversation` and `groupId` | Requirement + Scenario | scenario test | `AgentMemorySpec.scala` |
| `EpisodeOutcome.empty.isSuccess == true` | Requirement + Scenario | scenario test | `AgentMemorySpec.scala` |
| `naiveScore` substring > term-count > no-match | Property: naiveScore-monotonicity | Hedgehog property | `AgentMemorySpec.scala` |
| `InMemoryAgentMemory.create` yields empty memory | Requirement + Scenario | scenario test | `AgentMemorySpec.scala` |
| `MemoryHit` defaults (`validTo=None`, `provenance=None`, `payload=Map.empty`) | Requirement + Scenario | scenario test | `AgentMemorySpec.scala` |
| No heavy deps (`neo4j`/`lucene`/`http4s`) in `adk4s-memory-api` | Proposal acceptance checklist | manual review + `sbt "adk4s-memory-api/dependencyTree"` | adversarial review (Ring 8) |
