# Spec: Memory Retriever Bridge

<!-- This is a DELTA spec. It introduces `MemoryRetriever`, an adapter that
     exposes any `AgentMemory[F]` as the existing `Retriever[F]` so current
     agent wiring consumes memory with no new plumbing.
     This spec depends on the `agent-memory` spec's types. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Retriever[F]` | service trait | `org.adk4s.core.component` |
| `Document` | case class | `org.adk4s.core.component` |
| `RetrieverConfig` | case class | `org.adk4s.core.component` |
| `AgentMemory[F]` | service trait (introduced by spec:agent-memory) | `org.adk4s.memory` |
| `MemoryHit` | case class (introduced by spec:agent-memory) | `org.adk4s.memory` |
| `TemporalScope` | case class (introduced by spec:agent-memory) | `org.adk4s.memory` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MemoryRetriever` | object/factory | Adapts `AgentMemory[F]` into `Retriever[F]`. Requires `Sync[F]` (because `Retriever.retrieveStream` returns `fs2.Stream` and `Retriever.fromFunction` requires `Sync`). Synthesizes `Document.id`, carries `score`/`provenance` in `metadata` as ujson values. |

## ADDED Requirements

### Requirement: MemoryRetriever implements Retriever with both retrieve and retrieveStream

The system SHALL provide `MemoryRetriever.apply[F[_]: Sync](memory: AgentMemory[F], k: Int = 8, scope: Option[TemporalScope] = None): Retriever[F]` that implements both `retrieve(query: String, config: RetrieverConfig): F[List[Document]]` and `retrieveStream(query: String, config: RetrieverConfig): Stream[F, Document]`.

**Given** an `AgentMemory[IO]` and `MemoryRetriever(memory, k = 5)`
**When** `retrieve("query", RetrieverConfig())` is called on the resulting `Retriever[IO]`
**Then** the result is `IO[List[Document]]` whose size is `<= 5` (the `k` passed to the factory), derived from `memory.recall("query", 5, scope)`

**Rationale**: `Retriever[F]` is a trait with two methods (verified against `Retriever.scala`). A bridge that implements only `retrieve` would not satisfy the trait.

#### Scenario: retrieve maps recall hits to Documents

**Given** `InMemoryAgentMemory[IO]` with `Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1"))` remembered, and `MemoryRetriever(memory, k = 5)`
**When** `retrieve("Meta", RetrieverConfig())` is called
**Then** the result is a non-empty `List[Document]` whose first element has `content == "Alice works at Meta"`

#### Scenario: retrieveStream emits the same documents as retrieve

**Given** the same setup as above
**When** `retrieveStream("Meta", RetrieverConfig()).compile.toList` is run
**Then** the result equals the result of `retrieve("Meta", RetrieverConfig())`

#### Scenario: empty memory yields empty retrieve

**Given** `InMemoryAgentMemory[IO]` (empty) and `MemoryRetriever(memory, k = 5)`
**When** `retrieve("anything", RetrieverConfig())` is called
**Then** the result is `IO.pure(Nil)`

### Requirement: MemoryRetriever maps MemoryHit to Document with synthesized id and metadata

The system SHALL map each `MemoryHit` to a `Document(id: String, content: String, metadata: Map[String, ujson.Value])` where `id` is a synthesized stable identifier, `content` is `hit.text`, and `metadata` carries `score` (as `ujson.Num`), `provenance` (as `ujson.Str` when present), and the entries of `hit.payload` (as `ujson.Str`).

**Given** a `MemoryHit(text = "Alice works at Meta", score = 0.9, provenance = Some("g1"), payload = Map("role" -> "user"))`
**When** `MemoryRetriever` maps it to a `Document`
**Then** `content == "Alice works at Meta"`, `metadata("score") == ujson.Num(0.9)`, `metadata("provenance") == ujson.Str("g1")`, `metadata("role") == ujson.Str("user")`, and `id` is non-empty

**Rationale**: `Document` (verified shape: `id: String, content: String, metadata: Map[String, ujson.Value]`) has no `score` field, so score must ride inside `metadata` as a ujson value. A synthesized `id` is required because `MemoryHit` has no identifier field.

#### Scenario: score carried as ujson.Num

**Given** a hit with `score = 0.5`
**When** mapped to `Document`
**Then** `metadata("score")` is `ujson.Num(0.5)`

#### Scenario: provenance omitted when None

**Given** a hit with `provenance = None`
**When** mapped to `Document`
**Then** `metadata` does not contain the key "provenance"

#### Scenario: payload entries become ujson.Str values

**Given** a hit with `payload = Map("k1" -> "v1")`
**When** mapped to `Document`
**Then** `metadata("k1") == ujson.Str("v1")`

#### Scenario: synthesized id is stable for the same hit

**Given** the same `MemoryHit` mapped twice
**When** both `Document.id` values are compared
**Then** they are equal (the id is a pure function of the hit's fields, not random)

### Requirement: MemoryRetriever honors RetrieverConfig.topK as an upper bound

The system SHALL ensure `retrieve(query, config)` returns at most `min(k, config.topK)` documents, where `k` is the factory-supplied bound and `config.topK` is the caller-supplied bound.

**Given** `MemoryRetriever(memory, k = 10)` and `RetrieverConfig(topK = 3)`
**When** `retrieve("query", config)` is called against a memory with 10 matches
**Then** the result has size `<= 3`

**Rationale**: Both the factory `k` and the per-call `config.topK` are bounds; the tighter one wins so a caller can shrink the result without reconstructing the bridge.

#### Scenario: config.topK tighter than factory k

**Given** `MemoryRetriever(memory, k = 10)`, memory with 10 matches, `RetrieverConfig(topK = 2)`
**When** `retrieve("query", config)` is called
**Then** the result size is `<= 2`

#### Scenario: factory k tighter than config.topK

**Given** `MemoryRetriever(memory, k = 2)`, memory with 10 matches, `RetrieverConfig(topK = 10)`
**When** `retrieve("query", config)` is called
**Then** the result size is `<= 2`

### Requirement: MemoryRetriever filters by RetrieverConfig.minScore

The system SHALL ensure `retrieve(query, config)` excludes hits whose `score < config.minScore`.

**Given** `MemoryRetriever(memory, k = 10)`, memory with hits of scores `0.9` and `0.2`, `RetrieverConfig(topK = 10, minScore = 0.5)`
**When** `retrieve("query", config)` is called
**Then** the result contains only the hit with `score >= 0.5`

**Rationale**: `RetrieverConfig.minScore` is the existing mechanism for score gating; the bridge must honor it so callers can drop low-relevance memory.

#### Scenario: minScore filters out low-score hits

**Given** hits with scores 0.9 and 0.2, `minScore = 0.5`
**When** `retrieve` is called
**Then** only the 0.9-score hit appears in the result

#### Scenario: minScore = 0.0 keeps all hits

**Given** hits with scores 0.9 and 0.2, `RetrieverConfig(minScore = 0.0)`
**When** `retrieve` is called
**Then** both hits appear in the result

### Requirement: MemoryRetriever requires Sync[F]

The system SHALL require `Sync[F]` on `MemoryRetriever.apply` because `Retriever.retrieveStream` returns `fs2.Stream` and `Retriever.fromFunction` requires `Sync[F]`.

**Given** an `F[_]` with only a `Monad` available (no `Sync`)
**When** code attempts `MemoryRetriever.apply[F](memory)`
**Then** the code does not compile

**Rationale**: Verified against `Retriever.scala` — `fromFunction` builds `retrieveStream` via `Stream.eval`, which needs `Sync`. The constraint is on the factory, not on `AgentMemory[F]` itself.

#### Scenario: Sync[IO] is satisfied

**Given** an `AgentMemory[IO]`
**When** `MemoryRetriever(memory)` is constructed
**Then** it compiles and produces a `Retriever[IO]`

## Properties (Ring 3)

### Property: bridge-size-bound

**Invariant**: For all `AgentMemory[IO]`, all factory `k`, and all `RetrieverConfig(topK)`, `retrieve(query, config).map(_.size) <= math.min(k, config.topK)`.

**Generator strategy**: `genEpisodes`, `genQuery`, `genFactoryK: Gen[Int]` (`Range.linear(1, 20)`), `genTopK: Gen[Int]` (`Range.linear(0, 20)`). Classify by `topK < k`, `topK == k`, `topK > k`, `topK == 0`.

```
forAll { (episodes: List[Episode], query: String, k: Int, topK: Int) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- episodes.traverse_(mem.remember)
    r    <- MemoryRetriever(mem, k).retrieve(query, RetrieverConfig(topK = topK))
  } yield r.size <= math.min(k, topK)
}
```

### Property: bridge-minScore-filter

**Invariant**: For all `AgentMemory[IO]` and all `minScore: Double`, every document returned by `retrieve(query, RetrieverConfig(minScore = minScore))` has `metadata("score").num >= minScore`.

**Generator strategy**: `genEpisodes`, `genQuery`, `genMinScore: Gen[Double]` (`Range.linearFrac(0.0, 1.0)`). Classify by `minScore == 0.0`, `minScore > 0.5`, `minScore > 1.0` (yields empty).

```
forAll { (episodes: List[Episode], query: String, minScore: Double) =>
  for {
    mem  <- InMemoryAgentMemory.create[IO]
    _    <- episodes.traverse_(mem.remember)
    docs <- MemoryRetriever(mem, k = 20).retrieve(query, RetrieverConfig(minScore = minScore))
  } yield docs.forall(d => d.metadata("score").num >= minScore)
}
```

### Property: bridge-stream-equals-retrieve

**Invariant**: For all `AgentMemory[IO]` and all queries, `retrieveStream(query, config).compile.toList == retrieve(query, config)`.

**Generator strategy**: `genEpisodes`, `genQuery`, `genConfig: Gen[RetrieverConfig]` (constructive from `genTopK` + `genMinScore`). Classify by `result.isEmpty`, `result.size == 1`, `result.size > 1`.

```
forAll { (episodes: List[Episode], query: String, config: RetrieverConfig) =>
  for {
    mem    <- InMemoryAgentMemory.create[IO]
    _      <- episodes.traverse_(mem.remember)
    bridge  = MemoryRetriever(mem, k = 20)
    batch  <- bridge.retrieve(query, config)
    stream <- bridge.retrieveStream(query, config).compile.toList
  } yield batch == stream
}
```

### Property: bridge-id-stability

**Invariant**: For the same `MemoryHit`, the synthesized `Document.id` is identical across calls (pure function of hit fields).

**Generator strategy**: `genHit: Gen[MemoryHit]` (constructive from `genText`, `genScore`, `genProvenance: Gen[Option[String]]`, `genPayload: Gen[Map[String,String]]`). Run the mapping twice and compare ids.

```
forAll { (hit: MemoryHit) =>
  val d1 = MemoryRetriever.toDocument(hit)
  val d2 = MemoryRetriever.toDocument(hit)
  d1.id == d2.id
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `MemoryRetriever.apply[F]` without `Sync[F]` | `retrieveStream` requires `Sync` | `assertDoesNotCompile("MemoryRetriever.apply[F](mem)")` where only `Monad[F]` is in scope |

## Formal Contracts (Ring 6)

> Ring 6 is not applied for this spec. The mapping is pure but trivial; the
> Hedgehog `bridge-id-stability` and `bridge-stream-equals-retrieve`
> properties cover the correctness ground. This section is intentionally
> omitted.

## Temporal Properties (Ring 9)

> Ring 9 does not apply. This section is intentionally omitted.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| `MemoryRetriever` implements both `retrieve` and `retrieveStream` | Requirement: implements Retriever | type system (trait conformance) + scenario test | `MemoryRetrieverSpec.scala` |
| `retrieve` size `<= min(k, config.topK)` | Requirement + Property: bridge-size-bound | Hedgehog property | `MemoryRetrieverSpec.scala` |
| `retrieve` filters by `config.minScore` | Requirement + Property: bridge-minScore-filter | Hedgehog property + scenario | `MemoryRetrieverSpec.scala` |
| `retrieveStream` equals `retrieve` | Requirement + Property: bridge-stream-equals-retrieve | Hedgehog property | `MemoryRetrieverSpec.scala` |
| `Document.id` is a pure function of hit fields | Requirement + Property: bridge-id-stability | Hedgehog property | `MemoryRetrieverSpec.scala` |
| `score` carried as `ujson.Num` in `metadata` | Requirement + Scenario | scenario test | `MemoryRetrieverSpec.scala` |
| `provenance` omitted when `None` | Requirement + Scenario | scenario test | `MemoryRetrieverSpec.scala` |
| `Sync[F]` required on the factory | Requirement + Compile-Negative | compile-negative test | `MemoryRetrieverTypeContract.scala` |
| `Document` shape matches real `Retriever.scala` (no `score` field, `Map[String, ujson.Value]` metadata) | Proposal `⚠ VERIFY` resolution | adversarial review (Ring 8) | spec-lint |
