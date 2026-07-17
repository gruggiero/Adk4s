# Design: Add `adk4s-memory-api` — durable, recallable agent memory capability

## Package Structure

<!-- Derived from capability-profile.md's detected stack. The new modules
     follow the existing adk4s layering: capability traits in a core-adjacent
     module, no infrastructure deps in the interface layer. -->

### Layers

| Layer | Package | Depends On | Must NOT Import | Ring 2 Rule |
|-------|---------|-----------|-----------------|---------------|
| Memory capability (interface) | `org.adk4s.memory` | `cats-core` (typeclasses), `java.time.Instant` | workflows4s, llm4s LLM client, logback, http, smithy4s, typesafe-config, fs2 (interface only) | No outbound imports except cats-core typeclasses + stdlib |
| Memory implementation (in-process double) | `org.adk4s.memory` (same package, `InMemoryAgentMemory`) | cats-effect (`Ref`, `Sync`), fs2 (for bridge), cats-core, `java.time.Instant` | workflows4s, llm4s, logback, http, smithy4s | May import cats-effect + fs2; no LLM/workflow deps |
| Memory bridge | `org.adk4s.memory` (same package, `MemoryRetriever`) | `org.adk4s.core.component.{Retriever, Document, RetrieverConfig}`, cats-effect (`Sync`), fs2, ujson | workflows4s, llm4s, logback, http, smithy4s | May import adk4s-core component types + cats-effect + fs2 + ujson |
| Memory testkit | `org.adk4s.memory.testkit` | `org.adk4s.memory.*`, cats-effect (`IO`), munit | workflows4s, llm4s, logback, http, smithy4s, fs2 | May import memory-api + cats-effect + munit only |
| Existing core (unchanged) | `org.adk4s.core.component` | (unchanged) | (unchanged) | Not touched by this change |
| Existing orchestration (unchanged) | `org.adk4s.orchestration.*` | (unchanged) | (unchanged) | Not touched by this change — memory hook deferred |
| Generated code | `structured-llm-test-models/target/...` | — | — | Excluded from checks (no new generated code in this change) |

### New Packages

| Package | Layer | Purpose |
|---------|-------|---------|
| `org.adk4s.memory` | Memory capability + implementation + bridge | `AgentMemory[F]`, `Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`, `TemporalScope`, `InMemoryAgentMemory`, `MemoryRetriever` |
| `org.adk4s.memory.testkit` | Memory testkit | `AgentMemoryLaws` (reusable behavioral contract for downstream backends) |

### New sbt Modules

| Module | Path | Depends On | Main Deps | Test Deps |
|--------|------|-----------|-----------|-----------|
| `adk4s-memory-api` | `adk4s-memory-api/` | `adk4s-core` | `catsEffect`, `fs2Core` (via `Dependencies.*`) | `Dependencies.testDeps` (munit + munit-cats-effect + hedgehog-munit) |
| `adk4s-memory-testkit` | `adk4s-memory-testkit/` | `adk4s-memory-api` | `catsEffect`, `munit` (NOT test-scoped — `AgentMemoryLaws` is a downstream-consumable main API) | `Dependencies.testDeps` |

> **Build wiring note**: there is no `root` aggregate project in `build.sbt`.
> The new modules are standalone `lazy val`s like the existing ones, invoked
> via `sbt "adk4s-memory-api/compile"` etc. A root aggregate is out of scope
> (matches current convention). The `implementation-order` artifact includes
> the `build.sbt` edit as the first task.

## Effect Boundaries

### Pure Code (Ring 6 candidates)

| Module / Function | Purpose | Ring 6? |
|-------------------|---------|---------|
| `MemoryRetriever.toDocument(hit: MemoryHit): Document` | Pure mapping from `MemoryHit` to `Document` (synthesizes `id`, packs `score`/`provenance`/`payload` into `metadata` as ujson values) | Candidate, but **not applied** — the mapping is trivial (field rearrangement + ujson boxing); the `bridge-id-stability` Hedgehog property covers id-purity more cheaply than a Stainless mirror. |
| `InMemoryAgentMemory.naiveScore(content: String, q: String): Double` | Pure substring/term-count scoring | Candidate, but **not applied** — too thin to justify a Stainless mirror; `naiveScore-monotonicity` Hedgehog property covers it. |
| `Episode.conversation` / `EpisodeOutcome.empty` / `EpisodeOutcome.isSuccess` | Pure factory/accessor methods | No — trivial, covered by scenario tests. |

> **Ring 6 decision**: The proposal's verification strategy explicitly does
> NOT check Ring 6 for this change. The pure functions above are candidates
> by structure (no effects) but are too thin to warrant Stainless mirrors.
> Hedgehog properties (Ring 3) cover the same invariants at lower cost.

### Effectful Code

| Module / Trait | Effect Type | Purpose |
|----------------|-------------|---------|
| `AgentMemory[F[_]]` | `F[_]` (no constraint on trait; `Monad` needed for `rememberAll` default) | Capability interface: `remember`, `recall`, `rememberAll` |
| `InMemoryAgentMemory[F[_]: Sync]` | `F: Sync` (uses `Ref[F, Vector[Episode]]`) | Substring-indexed in-process test double |
| `MemoryRetriever.apply[F[_]: Sync]` | `F: Sync` (builds `Retriever.retrieveStream` via `Stream.eval`) | Adapter: `AgentMemory[F]` → `Retriever[F]` |
| `AgentMemoryLaws` | `IO` (fixed to `IO` for downstream consumability) | Behavioral contract runner: `kBound`, `scoreOrdering`, `recallAfterRemember`, `temporalIgnorability`, `all` |

> **Effect constraint placement**: `Sync` lives on implementations/factories
> (`InMemoryAgentMemory`, `MemoryRetriever.apply`), NOT on the `AgentMemory[F]`
> trait. This lets a future no-`Sync` backend (e.g. one needing only `Async`)
> implement the trait. `AgentMemoryLaws` is fixed to `IO` because it is a
> test-time contract runner consumed by downstream test suites, not a
> runtime-polymorphic interface.

## Type Strategy — Invalid-State Prevention

| Invariant | Level (Best/Good/Okay/Risky) | Mechanism | Justification |
|-----------|------------------------------|-----------|---------------|
| `SourceType` is closed at 5 cases (no 6th variant constructible) | Best | Scala 3 `enum` with exactly 5 cases — the compiler rejects a 6th case arm as non-exhaustive | A closed enum makes the variant set unrepresentable beyond the definition; the exhaustive-match scenario proves it compiles without a default arm. |
| `AgentMemory[F]` trait has no `Sync`/`Async` constraint | Best | The trait declaration carries no `Sync`/`Async` bound; a `Sync`-only declaration is rejected by the compiler | Compile-negative test (`assertDoesNotCompile("trait AgentMemory[F[_]: Sync]")`) proves the constraint cannot be added at the trait level. The constraint lives on implementations. |
| `MemoryRetriever.apply` requires `Sync[F]` | Best | The factory signature carries `Sync[F]` — calling it without `Sync` in scope is a compile error | Compile-negative test proves `MemoryRetriever.apply[F](mem)` fails without `Sync[F]`. |
| `recall` returns at most `k` hits | Good | `InMemoryAgentMemory.recall` calls `.take(k)` on the sorted list; the `kBound` Hedgehog property verifies `hits.size <= k` for all generated `k` | The `.take(k)` is a smart-constructor-style guard at the return boundary; the property proves it holds across inputs. |
| `recall` results sorted by descending score | Good | `InMemoryAgentMemory.recall` calls `.sortBy(-_.score)` before `.take(k)`; the `scoreOrdering` Hedgehog property verifies non-increasing order | Sorting is applied before truncation; the property proves ordering holds. |
| `EpisodeOutcome.isSuccess == errors.isEmpty` | Best | Defined as `def isSuccess: Boolean = errors.isEmpty` — no way to set `isSuccess` true with non-empty errors | The method is a pure derivation from `errors`, not a settable field. |
| `Document.id` is a pure function of `MemoryHit` fields (stable, non-random) | Good | `MemoryRetriever.toDocument` computes `id` from hit fields (e.g. a hash of `text`/`score`/`provenance`/`payload`); the `bridge-id-stability` Hedgehog property verifies `toDocument(hit).id == toDocument(hit).id` | The id is derived, not stored; the property proves determinism. |
| `MemoryRetriever.retrieve` size `<= min(factoryK, config.topK)` | Good | The bridge calls `memory.recall(query, math.min(k, config.topK), scope)` then filters by `config.minScore`; the `bridge-size-bound` Hedgehog property verifies the bound | The `min(k, topK)` is computed at the call boundary; the property proves it holds. |
| `TemporalScope` ignored (not errored) by non-temporal backends | Good | `InMemoryAgentMemory.recall` accepts `scope` but does not branch on it; the `temporalIgnorability` Hedgehog property verifies `.attempt.isRight` and the scope=None-vs-Some scenario verifies identical results | Ignoring is structural (no branch), not a fallback; the property + scenario prove no-error and equality. |
| `rememberAll` default traverses `remember` | Good | The default method is `episodes.traverse(remember)`; the `rememberAll-size-match` Hedgehog property verifies `outcomes.size == episodes.size` | The default is a structural traversal; the property proves size correspondence. |

> No invariants are placed at "Okay" (validator) or "Risky" (evaluator
> fallback). No "Bad" (silent mapping) — forbidden.

## Refined Type Strategy

<!-- The detected stack has NO refined-type library (no Iron, no `refined`).
     See capability-profile.md. The existing project uses plain `opaque type`
     newtypes without constraints (RunPath, NodeKey, FieldPath).
     This change follows the same convention: no new constrained opaque types. -->

### New Refined Types

| Type | Underlying | Constraint | Rationale |
|------|-----------|------------|-----------|
| (none) | — | — | No refined-type library in the stack; follows existing convention of plain case classes / plain opaque types. |

### Types Kept as Plain

| Type | Why Not Refined |
|------|----------------|
| `Episode.content: String` | Human/agent-readable text; no structural constraint to enforce. |
| `Episode.groupId: Option[String]` | Caller-supplied session/thread id; opaque to the memory module. |
| `Episode.metadata: Map[String, String]` | Open-ended caller payload; no schema to constrain. |
| `MemoryHit.text: String` | Agent-facing rendering; no structural constraint. |
| `MemoryHit.score: Double` | Backend-defined scoring; the contract is ordering + k-bound, not a numeric range. A `Double` is the right type — refining to `NonNegativeDouble` would be wrong (some backends may use negative scores for exclusion signals). |
| `MemoryHit.provenance: Option[String]` | Backend-supplied; opaque to the interface. |
| `MemoryHit.payload: Map[String, String]` | Open-ended backend payload. |
| `TemporalScope.asOf: Instant` | Any instant is valid; the scope is a filter hint, not a bounded value. |
| `EpisodeOutcome` count fields (`Int`/`Long`) | Backend-reported counts; zero is valid (no extraction). |
| `Document.id` (synthesized in bridge) | A synthesized stable string; no external format contract. |

> **Rationale**: The existing project uses plain `opaque type` newtypes
> (`NodeKey`, `FieldPath`, `RunPath`) without Iron constraints. Introducing
> Iron just for this module would add a dependency not in the detected stack
> and break the convention. The invariants that matter (k-bound, score
> ordering, id stability) are enforced by code + Hedgehog properties, not
> types.

## IDL Model Layout

> This change introduces NO Smithy/protobuf IDL. `AgentMemory` is a Scala
> trait, not an IDL service — memory backends are out of scope and may use
> any storage technology. The existing Smithy models in
> `structured-llm-test-models` are untouched.

### Services

| Service | Operations | IDL File |
|---------|-----------|----------|
| (none) | — | — |

### Structures

| Structure | Fields | Used By |
|-----------|--------|---------|
| (none) | — | — |

## Error Strategy

<!-- The memory interface is effect-polymorphic: errors flow through `F[_]`,
     not through a sealed `Either` return. The interface itself defines NO
     error enum — backends raise errors via `F` (e.g. `IO.raiseError`).
     The contract is that `recall` with `Some(scope)` on a non-temporal
     backend does NOT raise (temporal ignorability). -->

### Error Modeling

| Error Enum | Variants | Used By |
|------------|----------|---------|
| (none — no new error enum) | — | — |

> **Design decision**: `AgentMemory[F]` does not define its own `AdkError`
> variants. Errors are backend-specific (a Neo4j backend raises connection
> errors; the in-memory double never errors). Defining a memory-specific
> error algebra would force every backend to map its errors into it, which
> is the same over-coupling the proposal avoids. The interface returns
> `F[EpisodeOutcome]` / `F[List[MemoryHit]]`; backends raise via `F`.
> The only error-related contract is **temporal ignorability**: `recall`
> with `Some(scope)` on a non-temporal backend MUST NOT raise.

### Error Propagation

| Boundary | Pattern | Example |
|----------|---------|---------|
| Backend → `AgentMemory[F]` | `F.raiseError` (backend-specific exceptions) | A Neo4j backend raises `IO.raiseError(new ConnectionException(...))` |
| `AgentMemory[F]` → caller | `F[_]` error channel (caller handles via `.attempt` / `MonadError`) | `mem.recall(...).attempt` yields `Left(ConnectionException)` |
| `AgentMemory[F]` → `Retriever[F]` (bridge) | Pass-through (bridge does not catch) | `MemoryRetriever.retrieve` delegates to `mem.recall`; errors propagate |
| `AgentMemoryLaws` → test | `IO[Boolean]` (laws catch via `.attempt` internally for `temporalIgnorability`) | `temporalIgnorability` returns `false` if `.attempt` yields `Left` |

> **No swallowed errors**: the bridge does not catch or default. The laws'
> `temporalIgnorability` is the only place `.attempt` is used, and it is
> intentional (testing the no-error contract).

## Compatibility Story (Ring 4)

> **Ring 4 does not apply.** This change touches no persisted data, no wire
> formats, no serialization, no event payloads, no snapshots. `Episode` and
> `MemoryHit` are in-memory value types. `metadata: Map[String, String]` and
> `payload: Map[String, String]` are not serialized in this module. The
> bridge maps to `Document.metadata: Map[String, ujson.Value]` which is an
> in-memory structure, not a wire format.

| Data | Format | Compatibility Mechanism | Test |
|------|--------|------------------------|------|
| (none) | — | — | — |

## Verification Map

<!-- Per capability-profile.md ring availability. R8 (adversarial review)
     applies to every code-changing module and was performed in spec-lint.md. -->

| Module | R0 | R1 | R2 | R3 | R4 | R5 | R6 | R7 | R8 | R9 |
|--------|----|----|----|----|----|----|----|----|----|----|
| `adk4s-memory-api` (interface: `AgentMemory`, value types) | ✅ | ✅ | ✅ (advisory: no heavy deps) | ✅ (Hedgehog + munit scenarios) | — | — | — (waived: too thin) | — | ✅ (spec-lint) | — |
| `adk4s-memory-api` (impl: `InMemoryAgentMemory`) | ✅ | ✅ | ✅ | ✅ (6 Hedgehog properties) | — | ✅ (Stryker4s, retarget to `InMemoryAgentMemory.scala`) | — | — | ✅ | — |
| `adk4s-memory-api` (bridge: `MemoryRetriever`) | ✅ | ✅ | ✅ | ✅ (4 Hedgehog properties) | — | ✅ (Stryker4s, retarget to `MemoryRetriever.scala`) | — | — | ✅ | — |
| `adk4s-memory-testkit` (`AgentMemoryLaws`) | ✅ | ✅ | ✅ | ✅ (2 Hedgehog properties + scenarios) | — | — (test harness, not production logic) | — | — | ✅ | — |
| `build.sbt` (new module wiring) | ✅ | ✅ | — | — | — | — | — | — | ✅ (manual: dependencyTree) | — |

> **Ring 5 retarget**: `stryker4s.conf` `mutate` list must be retargeted to
> `["**/memory/InMemoryAgentMemory.scala", "**/memory/MemoryRetriever.scala"]`
> before running. These are the only production files with branches. The
> testkit module's `AgentMemoryLaws` is a test harness, not mutation-target
> production logic.
>
> **Ring 2 advisory**: no custom scalafix arch rules are installed. The
> layer rules in the Package Structure table are enforced manually via
> `sbt "adk4s-memory-api/dependencyTree"` (confirm no `neo4j`/`lucene`/
> `http4s`) and code review.

## Technical Decisions

### Decision: `AgentMemory[F]` is effect-polymorphic with no `Sync` on the trait

**Context**: The interface must accommodate backends ranging from a `Ref`-backed double (needs `Sync`) to a temporal knowledge graph (may need only `Async`).

**Options considered**:
1. `trait AgentMemory[F[_]: Sync]` — simplest, but forces `Sync` on every backend.
2. `trait AgentMemory[F[_]]` with `Sync` on implementations/factories — flexible, matches the `Retriever[F]` precedent (no constraint on the trait, `Sync` on `fromFunction`).
3. `trait AgentMemory[F[_]: Monad]` — adds a `Monad` constraint for `rememberAll` default.

**Decision**: Option 2 + 3 hybrid. The trait carries no `Sync`/`Async` bound; `rememberAll` requires `Monad[F]` (via `using`). `InMemoryAgentMemory` requires `Sync[F]`; `MemoryRetriever.apply` requires `Sync[F]`. This matches the existing `Retriever[F]` pattern exactly.

**Consequences**: A future no-`Sync` backend can implement `AgentMemory[F]`. The compile-negative test proves the trait cannot be re-declared with `Sync`.

### Decision: `MemoryRetriever` carries `score` in `Document.metadata` as `ujson.Num`

**Context**: `Document` (verified shape) has no `score` field — only `id`, `content`, `metadata: Map[String, ujson.Value]`. The source doc assumed `Document(content, score = Some(...), metadata)`, which does not exist.

**Options considered**:
1. Carry `score` in `metadata("score")` as `ujson.Num` — uses the existing field, no schema change.
2. Wrap `Document` in a new `ScoredDocument` case class — adds a parallel type, breaks `Retriever[F]` conformance.
3. Drop the score — loses information callers may want for re-ranking.

**Decision**: Option 1. `metadata("score") = ujson.Num(hit.score)`, `metadata("provenance") = ujson.Str(...)` when present, `metadata` entries from `hit.payload` as `ujson.Str`. This preserves `Retriever[F]` conformance and loses no information.

**Consequences**: Callers reading `Document.metadata("score")` get a `ujson.Value` they must cast to `ujson.Num` to read `.num`. This matches the existing `Document.metadata` convention (`Map[String, ujson.Value]`).

### Decision: `MemoryRetriever` synthesizes `Document.id` as a pure function of hit fields

**Context**: `Document` requires an `id: String`, but `MemoryHit` has no identifier field.

**Options considered**:
1. Synthesize `id` from a hash of hit fields (`text`, `score`, `provenance`, `payload`) — stable, no random.
2. Use a UUID — random, not stable across calls (breaks equality).
3. Use `hit.text` as the id — collides on duplicate texts.
4. Use an incrementing counter — requires state, breaks purity.

**Decision**: Option 1 — a deterministic hash of the hit's fields. The `bridge-id-stability` Hedgehog property proves `toDocument(hit).id == toDocument(hit).id`.

**Consequences**: Two hits with identical fields get the same `id` (acceptable — they are the same fact). Hits differing in any field get different ids.

### Decision: `adk4s-memory-testkit` is a separate main-scoped module, not a Test-scope export

**Context**: `AgentMemoryLaws` must be consumable by downstream backends (GraphStore) so they can run the same assertions against their Neo4j-backed implementation.

**Options considered**:
1. Separate `adk4s-memory-testkit` module with `munit` in main scope — downstream adds a regular `libraryDependencies` line.
2. `AgentMemoryLaws` in `Test` scope of `adk4s-memory-api` + `sbt-testkit` export — requires downstream to use `% Test` + testkit export gymnastics.
3. Publish `AgentMemoryLaws` as part of `adk4s-memory-api` main scope — pollutes the interface module with test-only deps (`munit`).

**Decision**: Option 1. A `-testkit` sibling module is the cleanest downstream ergonomics: `libraryDependencies += "org.adk4s" %% "adk4s-memory-testkit" % version`. `munit` goes in main scope of the testkit module (it is a test-contract API, not a test-only utility).

**Consequences**: The testkit module has `munit` in main scope (unusual but intentional for a testkit). It depends on `adk4s-memory-api` + `cats-effect` only — no heavy deps.

### Decision: No `AgentEvent` variants added in this change

**Context**: The source doc (§6.4) proposes `MemoryRecalled` / `MemoryWritten` `AgentEvent` variants for observability parity.

**Options considered**:
1. Add the variants now — requires extending the sealed `AgentEvent` trait and updating all existing pattern matches.
2. Defer to a follow-up change — keeps the interface PR free of orchestration edits.

**Decision**: Option 2. The proposal explicitly defers §6 (orchestration hook + events) to a follow-up. `AgentEvent` is a sealed trait with 7 variants (verified); extending it now would force a review of every match site and is out of scope for the interface-only + testkit change.

**Consequences**: No observability events for memory operations in this change. A follow-up change will add `MemoryRecalled` / `MemoryWritten` alongside the `MemoryHook` / `MemoryAwareRunner` orchestration integration.

### Decision: `cats-effect` + `fs2-core` in main scope of `adk4s-memory-api`

**Context**: The source doc claimed "main scope pulls only `cats-core`" with `cats-effect` in Test. But `InMemoryAgentMemory` (main-scoped, useful beyond tests) needs `Ref`/`Sync`, and `MemoryRetriever.retrieveStream` needs `fs2.Stream`.

**Options considered**:
1. `cats-core` main + `cats-effect` Test — matches the doc, but `InMemoryAgentMemory` cannot live in main scope.
2. `cats-effect` + `fs2-core` main — supports both `InMemoryAgentMemory` and `MemoryRetriever`.
3. Move `InMemoryAgentMemory` to Test scope — loses it as a reusable demo/local-dev double.

**Decision**: Option 2. `cats-effect` and `fs2-core` go in main scope (via `Dependencies.catsEffect` + `Dependencies.fs2Core`). `cats-core` arrives transitively. This matches `adk4s-core`'s main-scope deps.

**Consequences**: The module's main classpath has `cats-effect` + `fs2-core` (not "only `cats-core`" as the doc claimed). The dependency-tree check (Ring 2/8) confirms no `neo4j`/`lucene`/`http4s` leak.
