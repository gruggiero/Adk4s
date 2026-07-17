# Proposal: Add `adk4s-memory-api` — durable, recallable agent memory capability

## Why

ADK4S agents are currently *stateless across runs*. `ReactMemoryExample`
demonstrates only in-conversation memory (the message list). There is no
contract for **durable, cross-session, semantically-searchable memory** — the
thing that lets an agent recall a fact asserted three conversations ago, or
answer "what did we decide last week."

We deliberately do **not** put a memory *implementation* in ADK4S. A real
temporal knowledge-graph backend (GraphStore) pulls in Neo4j, Lucene, an
embedding client, and a connection pool. Forcing those onto every agent
project is unacceptable. Instead we add a **lightweight capability interface**
— the same architectural move ADK4S already made with `Tool` and `Retriever`:
the abstraction lives in a core-adjacent module, implementations live elsewhere.

**Design goal:** an agent gains long-term memory by being handed an
`AgentMemory[F]`. It never learns that Neo4j exists. Test doubles satisfy the
same interface with zero infrastructure.

This change covers the source design in `docs/adk4s-memory-api.md` §2–§5 and
§7, plus the testkit export (the doc's PR1 + PR2). The optional orchestration
hook (§6: `MemoryHook` / `MemoryAwareRunner` / `AgentEvent` variants) is
**deferred to a follow-up change** so the interface ships without waiting on
the `⚠ VERIFY` items against the live `ReactAgent` / `AgentRunner` signatures.

## What Changes

A new sbt module `adk4s-memory-api` is added, depending on `adk4s-core` (for
`Retriever` / `Document`). It is effect-polymorphic and imposes no
`Async`/`Sync` constraint on the interface itself; `Sync` appears only in the
in-process test double and the `Retriever` bridge (which must implement
`retrieveStream`).

### Affected Capabilities

- `specs/agent-memory/spec.md` — NEW. The `AgentMemory[F]` capability trait,
  value types (`Episode`, `SourceType`, `EpisodeOutcome`, `MemoryHit`,
  `TemporalScope`), the `InMemoryAgentMemory` test double, and the behavioral
  contract (informal laws: recall-after-remember, score ordering, k-bound,
  temporal ignorability).
- `specs/memory-retriever-bridge/spec.md` — NEW. `MemoryRetriever`: adapts any
  `AgentMemory[F]` into the existing `Retriever[F]` so current agent wiring
  consumes memory with no new plumbing.
- `specs/memory-testkit/spec.md` — NEW. `AgentMemoryLaws` reusable contract +
  the mechanism that makes it consumable downstream (a `-testkit` sibling
  module or `Test` scope export) so GraphStore can run the same assertions
  against its Neo4j-backed implementation.

### Out of Scope

- No storage engine, no embeddings, no graph logic in this module.
- No mandatory change to `ReactAgent` behavior. The memory hook (doc §6:
  `MemoryHook`, `MemoryAwareRunner`, optional `AgentEvent` variants) is
  deferred to a follow-up change.
- No new transitive heavy dependencies. If a PR adds `neo4j`, `lucene`,
  `http4s`, or `cats-effect-std` beyond what `adk4s-core` already pulls, it is
  out of scope.
- No `AgentEvent` extensions (doc §6.4) — cosmetic; deferred with the hook.

## Approach

Mirror the existing `Tool` / `Retriever` capability pattern: a thin,
effect-polymorphic trait (`AgentMemory[F]`) with `remember` / `recall` /
`rememberAll`, surrounded by immutable value types (`Episode`, `MemoryHit`,
`TemporalScope`, `EpisodeOutcome`, `SourceType`). A zero-dependency
substring-indexed `InMemoryAgentMemory` (backed by `cats-effect` `Ref`) serves
tests, demos, and local dev. A `MemoryRetriever` adapter exposes memory as the
existing `Retriever[F]` so any current agent wiring accepts it unchanged.

**Verified deltas from the source doc** (the doc's `⚠ VERIFY` items, resolved
against the live sources):

- `Retriever[F]` is a **trait** (not a SAM/alias) with
  `retrieve(query: String, config: RetrieverConfig): F[List[Document]]` **and**
  `retrieveStream(query: String, config: RetrieverConfig): Stream[F, Document]`.
  `MemoryRetriever` must implement *both* and accept a `RetrieverConfig`, not
  the doc's `retrieve(query: String)`-only sketch.
- `Document` is `final case class Document(id: String, content: String,
  metadata: Map[String, ujson.Value])` — there is **no `score` field** and
  metadata values are `ujson.Value`, not `String`. The bridge must synthesize
  an `id` and carry `score`/`provenance` inside `metadata` as ujson values.
- `Retriever.fromFunction` requires `Sync[F]` (it builds `retrieveStream` via
  `Stream.eval`), so `MemoryRetriever` requires `Sync[F]`, not the `Functor[F]`
  the doc assumed.
- The build has **no standalone `cats-core` dependency** and no `catsVersion` /
  `catsEffectVersion` / `munitVersion` constants; `cats-core` arrives
  transitively via `cats-effect`, and the build uses `Dependencies.*` +
  `Versions.*`. The module will reuse `Dependencies.testDeps` and add
  `cats-effect` to main scope (the test double needs `Ref`/`Sync` in main
  scope, so the doc's "cats-effect Test-only" claim does not hold for this
  repo — `InMemoryAgentMemory` is main-scoped and useful beyond tests).
- There is **no explicit `root` aggregate project** in `build.sbt`; the module
  is defined as a `lazy val` like the others. A small build-wiring task will
  decide whether to introduce a root aggregate or rely on per-module
  `sbt "adk4s-memory-api/compile"` (matching how the existing modules are
  invoked today).

The testkit (PR2) exposes `AgentMemoryLaws` in a scope a downstream backend
can depend on. The preferred mechanism is a small `adk4s-memory-testkit`
sibling module (main-scoped, depends on `adk4s-memory-api` + `cats-effect` +
`munit`), so GraphStore can add it as a regular dependency rather than
`sbt-testkit` export gymnastics. This is finalized in the design phase.

## Correctness Risk Level

**Risk**: low — the module introduces a new capability interface and immutable
value types plus a substring-indexed in-process double. There is no
persistence, no concurrency beyond a `Ref`, no fallback/default paths that
could silently corrupt data, and no change to existing agent behavior. The
only logic with measurable complexity is `InMemoryAgentMemory.naiveScore`
(contains/term-count scoring) and the `MemoryRetriever` mapping, both pure and
trivially testable.

## Verification Strategy

- [x] Ring 0: Compilation — strict scalac flags (`-deprecation`, `-feature`,
  `-unchecked`, `-Xkind-projector:underscores`, `-source:future`), explicit
  types, no `Any`.
- [x] Ring 1: Lint — Scalafix DisableSyntax + RemoveUnused + OrganizeImports,
  WartRemover `Warts.unsafe` (minus the project's existing exclusions). The new
  module inherits `ThisBuild` wartremoverErrors; `InMemoryAgentMemory` uses
  `Ref` (no `var`) and pattern matching (no `asInstanceOf`).
- [ ] Ring 2: Architecture — the new module depends only on `adk4s-core`; no
  heavy deps (`neo4j`/`lucene`/`http4s`). Verified via
  `sbt "adk4s-memory-api/dependencyTree"`. (Ring 2 is not a configured gate in
  this repo's tooling today; tracked as a manual acceptance check.)
- [x] Ring 3: Property-based tests — MANDATORY. munit + munit-cats-effect
  scenario tests for the four laws (k-bound, score ordering,
  recall-after-remember gated by `indexesContent`, temporal ignorability), plus
  Hedgehog properties for `naiveScore` monotonicity and `recall` k-bound over
  generated episode sets. No waiver.
- [ ] Ring 4: Wire/persistence compatibility — N/A. No serialization, no
  persisted events/snapshots, no wire formats touched. `Episode`/`MemoryHit`
  are in-memory value types; `metadata: Map[String, String]` is not serialized
  in this module.
- [x] Ring 5: Mutation testing — Stryker4s on
  `InMemoryAgentMemory` and `MemoryRetriever` (the only production logic with
  branches), threshold 90% (pure domain logic). `stryker4s.conf` mutate list
  retargeted per spec.
- [ ] Ring 6: Formal verification — not applied. The module is not pinned to
  the `verified`/Stainless Scala 3.7.2 setup and `naiveScore` is too thin to
  justify a Stainless mirror; the Hedgehog properties cover the same ground
  more cheaply.
- [ ] Ring 7: Model checking — N/A. No distributed/event-driven invariants.
- [x] Ring 8: Adversarial spec-compliance review — MANDATORY. Performed in the
  `spec-lint` artifact: check that the spec's laws are observable, that the
  `Retriever` bridge contract matches the real `Retriever`/`Document` shapes,
  and that no `⚠ VERIFY` item from the source doc survives into
  implementation.
- [ ] Ring 9: Telemetry — N/A. No `AgentEvent` variants added (deferred with
  the orchestration hook); otel4s is not present in the stack.

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
| `specs/agent-memory/spec.md` | Full | Introduces a new capability trait (`AgentMemory[F]`), new ADT (`SourceType` enum), new case classes (`Episode`, `MemoryHit`, `TemporalScope`, `EpisodeOutcome`), and a new main-scoped implementation (`InMemoryAgentMemory`) with scoring logic. |
| `specs/memory-retriever-bridge/spec.md` | Full | New public adapter mapping `AgentMemory[F]` → `Retriever[F]`; signature depends on the real `Retriever`/`Document`/`RetrieverConfig`/`ujson.Value` shapes and must implement both `retrieve` and `retrieveStream`. |
| `specs/memory-testkit/spec.md` | Full | New `AgentMemoryLaws` contract (public API for downstream backends) + a new build module (`adk4s-memory-testkit`) wiring decision. |

## Existing Concepts to Reuse

| Concept | Kind | Package | Notes |
|---------|------|---------|-------|
| `Retriever[F]` | trait | `org.adk4s.core.component` | The bridge target. `retrieve(query, RetrieverConfig)` + `retrieveStream(query, RetrieverConfig)`. `MemoryRetriever` implements it directly. |
| `Document` | case class | `org.adk4s.core.component` | `id: String`, `content: String`, `metadata: Map[String, ujson.Value]`. No `score` field — score carried in `metadata`. |
| `RetrieverConfig` | case class | `org.adk4s.core.component` | `topK: Int`, `minScore: Double`. The bridge maps `AgentMemory.recall(k)` onto `topK` and optionally filters by `minScore`. |
| `Retriever.empty` / `Retriever.fromFunction` | factory | `org.adk4s.core.component` | Reference for the SAM-vs-trait question — confirmed trait. `fromFunction` requires `Sync[F]`. |
| `Dependencies.testDeps` | seq | `project/Dependencies.scala` | munit + munit-cats-effect + hedgehog-munit; reused for the new module's Test scope. |
| `Dependencies.catsEffect` / `fs2` | seq | `project/Dependencies.scala` | `cats-effect` needed in main scope for `Ref`/`Sync` (`InMemoryAgentMemory`); `fs2-core` needed in main scope for `retrieveStream`. |
| `Versions.*` | constants | `project/Versions.scala` | Centralized versions; no new version constants introduced. |
| `scala3Options` | seq | `build.sbt` | Shared scalac flags; reused via `scalacOptions ++= scala3Options`. |
| `ThisBuild` wartremoverErrors | settings | `build.sbt` | Inherited automatically; no per-module override needed (no `var`/`null`/`throw`/`asInstanceOf`/`Any` introduced). |

## New Concepts to Introduce

| Concept | Kind | Purpose |
|---------|------|---------|
| `AgentMemory[F[_]]` | trait | The capability: `remember`, `recall`, `rememberAll` (default via `Traverse`). Effect-polymorphic, no `Sync` constraint on the trait. |
| `Episode` | case class | A discrete unit of experience: `content`, `sourceType`, `timestamp` (valid time), `groupId?`, `metadata`. |
| `SourceType` | enum | `Conversation`, `Document`, `StructuredData`, `ToolResult`, `ExternalApi`. |
| `EpisodeOutcome` | case class | Commit result counts: `entitiesExtracted`, `relationshipsCreated`, `edgesInvalidated`, `processingTimeMs`, `errors`; `isSuccess`. |
| `MemoryHit` | case class | A recalled fact: `text`, `score`, `validFrom?`, `validTo?`, `provenance?`, `payload`. |
| `TemporalScope` | case class | Optional point-in-time scoping: `asOf: Instant`. Non-temporal backends ignore it. |
| `InMemoryAgentMemory[F[_]]` | class (main) | Zero-dependency substring-indexed test double backed by `Ref[F, Vector[Episode]]`; requires `Sync[F]`. |
| `MemoryRetriever` | object/factory | Adapts `AgentMemory[F]` into `Retriever[F]`; requires `Sync[F]` (for `retrieveStream`). Synthesizes `Document.id`, carries `score`/`provenance` in `metadata` as ujson values. |
| `AgentMemoryLaws` | case class | Reusable behavioral contract (`kBound`, `scoreOrdering`, `recallAfterRemember` gated by `indexesContent`, `temporalIgnorability`, `all`). Lives in the testkit module. |
| `adk4s-memory-api` | sbt module | New module: `dependsOn(adk4s-core)`, main deps `cats-effect` + `fs2-core`, test deps `testDeps`. |
| `adk4s-memory-testkit` | sbt module | New sibling module (PR2): `dependsOn(adk4s-memory-api)`, main deps `cats-effect` + `munit`; publishes `AgentMemoryLaws` for downstream backends. |

## Risks and Mitigations

- **Bridge shape drift.** The doc's `MemoryRetriever` sketch did not match the
  real `Retriever`/`Document`. *Mitigation:* already resolved by reading
  `Retriever.scala`; the spec encodes the verified shapes and Ring 8 confirms.
- **Testkit export mechanism.** `-testkit` sibling vs `Test` scope export is a
  build-design decision with downstream ergonomics impact. *Mitigation:*
  decided in the `design` artifact; preferred sibling module keeps
  GraphStore's dependency a plain `libraryDependencies` line.
- **`Sync` constraint leakage.** The interface stays `Monad`-only, but the
  test double and bridge need `Sync`. *Mitigation:* the constraint lives on
  the implementations/factories, not on `AgentMemory[F]`, so a future
  no-`Sync` backend can implement the trait.
- **WartRemover `DefaultArguments`.** `recall(query, k, scope = None)` uses a
  default arg; `DefaultArguments` is already excluded project-wide, so this is
  consistent with existing code (e.g. `RetrieverConfig()` defaults).
- **No root aggregate.** Adding a module without a root aggregate means
  `sbt compile` does not auto-build it. *Mitigation:* the tasks artifact
  includes a build-wiring step (introduce a root aggregate or document the
  per-module invocation, matching the current convention).
