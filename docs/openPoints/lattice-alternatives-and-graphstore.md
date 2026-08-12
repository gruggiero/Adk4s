# Open Points: Lattice Merge Alternatives, CRDTs, and the GraphStore Option

> Research conducted on 2026-08-09. Covers the alternatives to adk4s's
> current join-semilattice merge for parallel multi-agent delegation state,
> whether a CRDT structure is possible, and whether graphStore's in-memory
> structures could serve as (or be adapted to) a shared session state.

---

## 1. What adk4s currently has

### The join-semilattice design

adk4s's `HarnessState` uses a **join semilattice** for merging state from
parallel sub-agents back to a parent. The "join" is the user-provided
`merge: (A, A) => A` function on each `StateCell[A]`, which must satisfy:

- **Commutativity**: `merge(a, b) == merge(b, a)`
- **Associativity**: `merge(a, merge(b, c)) == merge(merge(a, b), c)`
- **Idempotence**: `merge(a, a) == a`

Source: `adk4s-harness-api/src/main/scala/org/adk4s/harness/StateCell.scala` (lines 37-44)
and `docs/deepagents4s-phase0-agent-middleware-DESIGN.md` (lines 256-278).

The default merge is **last-write-wins** (`(_, child) => child`), which is
correct for sequential delegation but not for parallel. For parallel
delegation, the user must provide a semilattice merge (e.g., set-union for
`Set[String]`, `max` for `Int`).

The design doc explicitly states this is "the CRDT discipline applied to
harness state" — it uses the algebraic properties without providing specific
CRDT data types.

### Key characteristics

- **No explicit partial order**: The lattice is defined algebraically by the
  merge operation, not by an explicit `≤` relation.
- **Per-cell merge**: Each `StateCell` carries its own merge function. There
  is no global merge strategy.
- **Three visibility levels**: `Private`, `Inherited`, `Shared`. Only `Shared`
  cells participate in `mergeBack`.
- **Order-independent**: Guaranteed when semilattice properties hold — this is
  deliberately stronger than LangGraph's order-sensitive reducers.
- **Property-tested**: `HarnessStateBoundarySpec` verifies order-independence
  and merge-back neutrality with Hedgehog.

---

## 2. Alternatives to the semilattice approach

### 2a. CRDTs (Conflict-free Replicated Data Types)

#### What they are

CRDTs are data structures whose merge operations automatically satisfy the
semilattice laws. They come in two flavors:

- **State-based (CvRDT)**: Each replica maintains full state forming a
  join-semilattice. Merging computes the least upper bound. This is
  mathematically identical to what adk4s already does — but with
  **pre-defined, composable data types**.
- **Operation-based (CmRDT)**: Updates are broadcast as operations. Concurrent
  operations must commute. Requires causal delivery guarantees.

Source: Shapiro et al., "Conflict-free replicated data types" (SSS 2011):
https://perso.lip6.fr/Marc.Shapiro/papers/2011/CRDTs_SSS-2011.pdf

#### Relationship to the current semilattice

**CRDTs are semilattices with batteries included.** A state-based CRDT is
exactly a join-semilattice with a known, correct merge function for a specific
data type. adk4s's current design is "bring your own semilattice" — the user
must write and verify the merge function. CRDTs provide off-the-shelf,
composable, law-tested merge semantics.

#### Standard CRDT types and their adk4s mappings

| CRDT type | Merge semantics | adk4s use case |
|---|---|---|
| **G-Counter** | `max` per replica | Step counts, token usage across parallel sub-agents |
| **PN-Counter** | G-Counter + G-Counter (inc/dec) | Budget tracking (tokens used / remaining) |
| **LWW-Register** | Highest timestamp wins | Simple scalar state (current default in adk4s) |
| **MV-Register** | Preserves all concurrent writes | When all parallel sub-agent outputs must be kept |
| **OR-Set** | Add-wins, remove supported | Todo lists, discovered facts (matches adk4s's set-union example) |
| **LWW-Map** | Per-key LWW | Structured state with independent keys |
| **OR-Map** | Per-key nested CRDTs | Composable structured state (the most expressive option) |

#### CRDTs in multi-agent / LLM frameworks

CRDTs have been used in LLM agent systems:

- **CodeCRDT** — Observation-driven coordination for multi-agent LLM code
  generation using CRDTs for lock-free concurrent editing.
  Source: https://arxiv.org/html/2510.18893
- **llm-sync (Rust)** — CRDT and vector clock primitives for distributed LLM
  agent state synchronization (GCounter, PNCounter, GSet, LWWRegister, ORMap).
  Source: https://docs.rs/llm-sync/latest/llm_sync/
- **crdt-merge** — Two-layer CRDT architecture for convergent multi-agent AI:
  Layer 1 = AgentState (LWWMap, ORSet, PNCounter), Layer 2 = ContextMerge
  (deterministic resolution strategies).
  Source: https://github.com/mgillr/crdt-merge/blob/main/docs/guides/convergent-multi-agent-ai.md
- **agentcrdt (Python)** — Semantic-causal CRDT for agent-mutable world state
  with LWW merge and a semantic rule engine for detecting logical
  contradictions.
  Source: https://pypi.org/project/agentcrdt/

#### Scala-native CRDT libraries

- **Pekko Distributed Data** — The most relevant option given the project's
  use of Pekko. Provides `GCounter`, `PNCounter`, `ORSet`, `LWWRegister`,
  `LWWMap`, `ORMap`, `Flag`. All are state-based CRDTs with proven merge
  semantics.
  Source: https://pekko.apache.org/docs/pekko/current/typed/distributed-data.html
- **cats-crdt** — A lightweight Cats-compatible CRDT library.
  Source: https://github.com/andriimartynov/cats-crdt
- **Automerge** — JSON-like CRDT with a Rust core; could be bridged via JNI
  but is heavier-weight.
  Source: https://github.com/automerge/automerge

#### Advantages over the raw semilattice

1. **Composability**: CRDTs compose (e.g., `ORMap` containing nested CRDTs).
   adk4s's current `StateCell[A]` with a custom `merge` does not compose
   automatically — nesting requires the user to write a merge function for the
   outer structure.
2. **Known-correct merge semantics**: No need for each user to write and
  law-test a merge function. The CRDT library guarantees the semilattice laws.
3. **Standard vocabulary**: `ORSet`, `LWWRegister`, `PNCounter` are
   well-understood; users don't need to reason about abstract semilattices.
4. **Strong eventual consistency**: Mathematically proven convergence — the
   same guarantee adk4s already has, but with less user burden.

#### Disadvantages

1. **Metadata overhead**: CRDTs carry per-element metadata (tombstones,
   vector clocks, replica IDs). For small in-memory state this is negligible;
   for large state it can be 10-100x the payload size.
2. **Semantic mismatch**: CRDT merge semantics may not match application
   intent. E.g., a PN-Counter cannot enforce "balance must not go negative."
3. **Dependency weight**: Pekko Distributed Data pulls in Pekko cluster
   infrastructure. For adk4s's current single-JVM use case, a lighter
   CRDT library or a small in-house set of CRDT types would be more
   appropriate.

#### Verdict for adk4s

**CRDTs are a natural evolution, not a replacement.** The current design is
already CRDT-discipline; the improvement would be to provide pre-built CRDT
`StateCell` instances (e.g., `StateCell.orSet`, `StateCell.lwwRegister`,
`StateCell.pnCounter`) so users don't write merge functions by hand. This could
be done with a small in-house module (no Pekko dependency needed) or by
wrapping Pekko Distributed Data types if Pekko is already on the classpath.

---

### 2b. Last-Write-Wins (LWW)

#### What it is

Each write is tagged with a timestamp. Merge selects the value with the
highest timestamp. LWW is a **special case of a semilattice** — the
LWW-Register is a CRDT where the join is "max by timestamp."

#### When it works

- Single-writer scenarios (only one sub-agent writes to a given key)
- Temporal ordering is the desired conflict resolution
- The application can tolerate data loss on concurrent writes

#### When it fails

- Multiple parallel sub-agents write to the same key concurrently — all but
  one write is silently lost
- All updates must be reflected in the final state (use MV-Register instead)

#### Verdict for adk4s

**Already the default.** adk4s's `StateCell.apply` defaults to
`(_, child) => child` (last-write-wins). The design doc correctly notes this is
"only valid for sequential delegation." For parallel delegation, a semilattice
or CRDT merge is required. No change needed here — the default is appropriate
for the common sequential case.

---

### 2c. Operational Transformation (OT)

#### What it is

Each operation carries position + content. When applying operation B
concurrent with A, B is transformed against A to account for A's effect.
Requires a central server to define canonical order. The transform function
must satisfy TP1 and TP2 properties for all operation pairs.

#### Verdict for adk4s

**Not suitable.** OT requires central coordination (defeats the purpose of
parallel execution), is notoriously difficult to implement correctly (the
literature is full of correctness bugs in published OT implementations), and
has no adoption in agent frameworks. CRDTs provide the same convergence
guarantees with simpler implementation. OT is only relevant for collaborative
text editing (Google Docs), not for agent state merge.

---

### 2d. Event Sourcing / Log-Based Merge

#### What it is

Events are stored in a globally replicated log with vector timestamps. When
concurrent events are detected, a conflict resolver function merges them. The
resolver must be pure and deterministic for convergence.

#### Relationship to the lattice approach

Event sourcing with conflict resolvers **is** implementing a semilattice merge
— the resolver function is the join operation, and vector clocks provide the
partial ordering. The difference is architectural: event sourcing persists the
event log (enabling replay and audit), while adk4s's `HarnessState` merges
in-memory without persisting the individual operations.

#### LangGraph's reducers as an example

LangGraph effectively uses event-sourcing-style merge:
- State is a `TypedDict` where each field is a "channel"
- Each channel has a reducer: `(old_value, new_value) -> merged_value`
- Built-in reducers: `overwrite` (LWW), `add_messages` (append), `operator.add`
  (sum)
- Nodes return partial updates; the engine folds updates into running state

Source: https://docs.langchain.com/oss/python/langgraph/graph-api

LangGraph's reducers are **not guaranteed to be commutative** — `add_messages`
appends in arrival order. adk4s's design is deliberately stronger: the
semilattice laws guarantee order-independence.

#### Verdict for adk4s

**Partially applicable.** adk4s already has event-sourcing infrastructure
(`EventSourcedState`, `CheckpointStore`). The `HarnessState` merge could be
backed by an event log for auditability and replay, with the semilattice merge
as the conflict resolver. This would combine the convergence guarantees of the
current design with the auditability of event sourcing. However, this is an
architectural extension, not a replacement for the merge strategy itself.

---

### 2e. Transactional / Locking Approaches (STM, Mutexes)

#### What they are

- **STM (Software Transactional Memory)**: Atomic transactions over in-memory
  state with optimistic concurrency and automatic retries. Scala implementations:
  `cats-stm` (https://github.com/TimWSpence/cats-stm), `bengal-stm`.
- **Mutex/Lock-based**: Pessimistic concurrency control with explicit lock
  acquisition/release.

#### Verdict for adk4s

**Not suitable for parallel delegation.** Locking and STM coordinate access to
shared mutable state — but adk4s's parallel sub-agents run in isolation and
merge results after completion. The whole point of the semilattice/CRDT
approach is to **avoid coordination** during execution. Locks would serialize
parallel work and introduce deadlock risk. STM would add retry overhead and is
single-JVM only.

The only scenario where STM makes sense is if sub-agents need to read/write
shared state **during** execution (not just merge at the end). That would be a
fundamentally different concurrency model — closer to Google ADK's
`session.state` (which has known race condition bugs with parallel agents).

---

### 2f. Google ADK's session.state

#### What it is

Google ADK provides a mutable `session.state` dict shared across sub-agents.
All sub-agents in a `ParallelAgent` share the same session.

#### How it handles concurrent writes

**It doesn't — it has known bugs.** Concurrent writes to the same key from
parallel agents cause race conditions where one agent's writes are silently
dropped. The recommended workaround is to use distinct state keys per
sub-agent.

Source: https://github.com/google/adk-python/issues/5244,
https://github.com/google/adk-python/discussions/4922

#### Verdict

**Not a model to follow.** Google ADK's approach is effectively last-write-wins
on a shared dict with no formal merge semantics and known data-loss bugs.
adk4s's semilattice approach is strictly superior.

---

### 2g. LangGraph's Channel Reducers

#### What they are

Per-field merge functions (reducers) on a shared state `TypedDict`. Each
channel has a reducer: `(Value, Value) -> Value`. Built-in reducers include
overwrite, `add_messages` (append), `operator.add` (sum).

#### Are they semilattice-based?

**Not necessarily.** Reducers must be pure and deterministic, but they are
**not required to be commutative or idempotent**. `add_messages` appends in
arrival order — the result depends on which node completes first. This is
exactly the weakness adk4s's design explicitly addresses.

Source: https://docs.langchain.com/oss/python/langgraph/graph-api

#### Verdict

**A weaker version of what adk4s already has.** LangGraph's reducers are more
flexible (any pure function) but less safe (no convergence guarantee). adk4s's
semilattice requirement is the strict subset that guarantees order-independence.
The LangGraph model would be a **regression** for adk4s.

---

### 2h. Deepagents (Google/LangChain) State Merge

#### What it is

Deepagents uses LangGraph's reducer mechanism. It has known issues with
parallel sub-agents causing `InvalidUpdateError` due to key collisions (e.g.,
`skills_metadata`), fixed by excluding problematic keys from state merge.

Source: https://github.com/langchain-ai/deepagents/pull/954

#### Verdict

**Same as LangGraph** — a weaker, order-sensitive merge with known collision
bugs. adk4s's semilattice is deliberately stronger.

---

## 3. Summary comparison of all alternatives

| Approach | Convergence guarantee | Order-independent | Composable | Complexity | Suitable for adk4s? |
|---|---|---|---|---|---|
| **Join semilattice (current)** | Yes (if laws hold) | Yes | Manual (user writes merge) | Low | **Current design** |
| **CRDTs** | Yes (proven per type) | Yes | Yes (nested CRDTs) | Low (use library) | **Natural evolution** |
| **LWW** | No (data loss) | N/A (single value) | No | Very low | Already the default for sequential |
| **OT** | Yes (with central server) | N/A | No | Very high | No — requires central coordination |
| **Event sourcing + resolver** | Yes (if resolver is semilattice) | Yes (if resolver is commutative) | Manual | Medium | Possible extension for auditability |
| **STM / Locking** | N/A (coordination-based) | N/A | Yes | Medium | No — defeats parallelism |
| **Google ADK session.state** | No (known bugs) | No | No | Low | No — strictly worse |
| **LangGraph reducers** | No (not required) | No (order-sensitive) | Yes | Low | No — regression |
| **Deepagents** | No (same as LangGraph) | No | Yes | Low | No — regression |

---

## 4. The GraphStore option: an in-memory structure simulating the graph store

### 4a. What graphStore already has

The graphStore project at `/home/gruggiero/git/rs/graphStore` is a Scala 3
reimagining of Graphiti's core ideas: bi-temporal edges, episodic ingestion,
hybrid retrieval, contradiction-driven invalidation. It is wired into adk4s
as durable agent memory via `ProjectRef`.

#### The `GraphStore[F[_]]` algebra

```scala
trait GraphStore[F[_]]:
  def createNode(n: Node): F[Unit]
  def getNode(id: NodeId): F[Option[Node]]
  def createNodes(ns: List[Node]): F[Unit]
  def createEdge(e: TemporalEdge): F[Unit]
  def createEdges(es: List[TemporalEdge]): F[Unit]
  def invalidateEdge(id: EdgeId, at: Instant): F[Unit]
  def findEdges(source, target, relType, validAt): F[List[TemporalEdge]]
  def nodeAt(id: NodeId, at: Instant): F[Option[Node]]
  def edgesAt(id: NodeId, at: Instant): F[List[TemporalEdge]]
  def listNodes(validAt: Option[Instant]): F[List[Node]]
  def findSimilarNodes(name, entityType, threshold): F[List[(Node, Double)]]
  def neighbors(id: NodeId, relType, dir): F[List[Node]]
  def shortestPath(from: NodeId, to: NodeId, maxHops): F[Option[Int]]
```

Source: `/home/gruggiero/git/rs/graphStore/modules/domain/src/main/scala/io/gruggiero/graphstore/domain/algebra/GraphStore.scala`

This is a **bi-temporal property graph** — nodes have labels and properties,
edges have valid-time and transaction-time intervals, and edges can be
invalidated (contradiction-driven). It is designed for durable, cross-session,
semantically-searchable agent memory.

#### The `InMemoryGraphStore` implementation

```scala
final class InMemoryGraphStore[F[_]: Sync](ref: Ref[F, InMemoryGraphStore.State])
    extends GraphStore[F]:

object InMemoryGraphStore:
  final case class State(
      nodes: Map[NodeId, Node] = Map.empty,
      edges: Map[EdgeId, TemporalEdge] = Map.empty
  )
  def apply[F[_]: Sync](state: State = State()): F[InMemoryGraphStore[F]] =
    Ref.of[F, State](state).map(new InMemoryGraphStore[F](_))
```

Source: `/home/gruggiero/git/rs/graphStore/modules/neo4j/src/test/scala/io/gruggiero/graphstore/neo4j/testkit/InMemoryGraphStore.scala`

This is a **`Ref[F, State]`-backed in-memory implementation** of the full
`GraphStore` algebra. It lives in the `neo4j` module's **Test scope** (used for
fast unit tests without Testcontainers). It supports:

- Node CRUD (create, get, list)
- Edge CRUD with bi-temporal validity (`isValidAt`, `invalidateEdge`)
- Graph traversal (`neighbors`, `shortestPath` via BFS)
- Lexical similarity matching (`findSimilarNodes` — trivial 1.0/0.0 score)

The state is **immutable** (`State` is a case class with immutable `Map`s),
updated via `ref.update` (Cats Effect `Ref`). This is the same pattern as
adk4s's `HarnessState`.

#### The `GraphStoreMemory` adapter

```scala
final class GraphStoreMemory[F[_]: Async](
    processor: EpisodeProcessor[F],
    search: HybridSearch[F]
) extends AgentMemory[F]:
  def remember(e: ApiEpisode): F[EpisodeOutcome] = ...
  def recall(query: String, k: Int, scope: Option[TemporalScope]): F[List[MemoryHit]] = ...
```

Source: `/home/gruggiero/git/rs/graphStore/modules/episode/src/main/scala/io/gruggiero/graphstore/memory/GraphStoreMemory.scala`

This implements adk4s's `AgentMemory[F]` trait on top of graphStore. An adk4s
agent receives a `GraphStoreMemory` and calls `remember`/`recall` — it never
knows that Neo4j or Lucene exist underneath.

#### The `EmbeddingCache`

```scala
final class EmbeddingCache[F[_]: Sync](ref: Ref[F, Map[String, EmbeddingVector]])
```

Source: `/home/gruggiero/git/rs/graphStore/modules/embedder/src/main/scala/io/gruggiero/graphstore/embedder/EmbeddingCache.scala`

A simple `Ref`-backed cache for embedding vectors. Not relevant to session
state, but shows the same `Ref[F, Map]` pattern used throughout.

### 4b. Could `InMemoryGraphStore` serve as shared session state?

**Yes, with adaptation.** Here is the analysis:

#### What it provides that `HarnessState` doesn't

1. **Bi-temporal semantics**: Every edge has `validFrom`/`validTo` (when the
   fact was true in reality) and `createdAt`/`expiredAt` (when the system knew
   it). This enables point-in-time queries — "what did the sub-agent know at
   time T?" — which `HarnessState` cannot do.
2. **Graph structure**: State is not a flat key-value map but a property
   graph. Sub-agents can create nodes (entities, facts, decisions) and edges
   (relationships, causal links) that other sub-agents can traverse.
3. **Contradiction-driven invalidation**: When a sub-agent asserts a fact that
   contradicts an existing one, the old edge is invalidated
   (`invalidateEdge`). This is a form of **semantic conflict resolution** that
   goes beyond CRDT merge semantics.
4. **Semantic search**: `findSimilarNodes` and (via `HybridSearch`) keyword +
   embedding retrieval. Sub-agents can query the shared state semantically,
   not just by key lookup.
5. **Provenance tracking**: Every edge carries `episodeId` and `confidence`.
   The parent agent can trace which sub-agent asserted which fact and with
   what confidence.

#### What it lacks for the session-state use case

1. **No merge semantics for parallel writes**: `InMemoryGraphStore` uses
   `ref.update` (last-write-wins on the `Map`). If two parallel sub-agents
   create nodes with the same `NodeId.from(name, type)`, the deterministic ID
   means they collapse to the same node (idempotent ingestion by design).
   But if they create edges with different `EdgeId.generate()` (random UUIDs),
   both edges coexist — there is no conflict detection or merge. This is fine
   for a knowledge graph (additive), but not for mutable scalar state.
2. **No visibility levels**: `InMemoryGraphStore` has no concept of
   `Private`/`Inherited`/`Shared`. All state is globally visible. The
   `HarnessState` visibility model would need to be layered on top.
3. **It lives in Test scope**: `InMemoryGraphStore` is in
   `modules/neo4j/src/test/scala/`. To use it in production (or in adk4s's
   harness), it would need to be extracted to a `testkit` module or promoted
   to main scope. The AGENTS.md notes this: "downstream modules' tests that
   need it must add `dependsOn(neo4j % Test)` or extract a `testkit` module."
4. **Heavier than `HarnessState`**: `HarnessState` is a typed heterogeneous
   map with ~160 lines. `InMemoryGraphStore` is a full graph engine with
   traversal, temporal queries, and similarity search (~117 lines but with
   the domain model adding more). For simple session state (a few scalars and
   sets), this is overkill.

### 4c. The hybrid option: `HarnessState` + `InMemoryGraphStore` for different tiers

The most promising architecture is **not** to replace one with the other, but
to use them for **different state tiers**:

| Tier | Structure | Purpose | Merge strategy |
|---|---|---|---|
| **Ephemeral session state** | `HarnessState` + `StateCell` | Scalars, sets, counters shared during a single agent run | Semilattice / CRDT merge (current design) |
| **Episodic memory** | `InMemoryGraphStore` (or Neo4j) | Facts, entities, relationships that persist across runs and sub-agent boundaries | Additive (graph is append-only with invalidation) |
| **Cross-session memory** | `GraphStoreMemory` → Neo4j/Lucene | Durable, semantically searchable memory | Additive + contradiction-driven invalidation |

In this model:
- `HarnessState` handles the **short-lived, merge-sensitive** state that
  parallel sub-agents need to coordinate during a single run (todos, counters,
  flags). The semilattice/CRDT merge ensures order-independent convergence.
- `InMemoryGraphStore` handles the **structured, queryable** state that
  sub-agents build collaboratively (entity graph, causal relationships,
  decisions with provenance). The graph is additive — parallel sub-agents add
  nodes and edges without conflict, and contradiction-driven invalidation
  handles semantic conflicts.
- `GraphStoreMemory` (the `AgentMemory[F]` adapter) provides the **durable,
  cross-session** memory that persists beyond a single run.

### 4d. Would an in-memory structure simulating the graph store be another possibility?

**Yes, and `InMemoryGraphStore` already is that structure.** It is a
`Ref[F, State]`-backed implementation of the same `GraphStore[F]` algebra that
Neo4j implements. It can be used:

1. **As-is for testing**: Sub-agent state sharing in tests without Neo4j.
2. **Promoted to a testkit/main module for production**: Extract
   `InMemoryGraphStore` into a `graphstore-testkit` module (or
   `graphstore-inmemory` main module) so adk4s can depend on it without
   pulling in Neo4j/Testcontainers.
3. **As the shared session state backend**: Wire `InMemoryGraphStore` into
   `AgentTool` so that sub-agents write facts/edges to the shared graph
   instead of (or in addition to) returning a string. The parent agent can
   then traverse the graph to synthesize results.

The key advantage over `HarnessState` is **queryability**: sub-agents can
traverse the graph (`neighbors`, `shortestPath`, `findSimilarNodes`) to
discover what other sub-agents have asserted, rather than only seeing merged
scalar values. The key disadvantage is **no merge semantics for mutable
state** — the graph is additive, which is fine for facts but not for "current
value of X."

### 4e. Concrete recommendation

1. **Keep the semilattice/CRDT merge for `HarnessState`** — it is the right
   tool for ephemeral, merge-sensitive scalar state. Consider adding pre-built
   CRDT `StateCell` factories (`StateCell.orSet`, `StateCell.lwwRegister`,
   `StateCell.pnCounter`) to reduce user burden.

2. **Extract `InMemoryGraphStore` from Test scope** into a
   `graphstore-inmemory` module (or `graphstore-testkit` if kept test-only).
   This makes it available as a lightweight shared state backend for adk4s
   without requiring Neo4j.

3. **Wire `InMemoryGraphStore` (or `GraphStore[F]` generally) into
   `AgentTool`** as an optional shared context. When provided, sub-agents
   can read/write to the shared graph during execution. This addresses the
   session-state gap identified in the previous research: sub-agents would
   gain a structured, queryable shared state, not just the request string.

4. **Use `HarnessState` for scalar coordination, `GraphStore` for structured
   knowledge**: The two are complementary, not competing. `HarnessState`
   merges scalars with semilattice guarantees; `GraphStore` accumulates
   structured facts with bi-temporal provenance and contradiction-driven
   invalidation.

---

## 5. Summary

| Question | Answer |
|---|---|
| What are the alternatives to the lattice merge? | CRDTs, LWW, OT, event sourcing, STM/locking, Google ADK's dict, LangGraph reducers, deepagents |
| Is a CRDT structure possible? | **Yes** — CRDTs are semilattices with pre-built types. They are a natural evolution of the current design, not a replacement. Pekko Distributed Data or a small in-house CRDT module would work. |
| Does graphStore have an in-memory structure? | **Yes** — `InMemoryGraphStore` in `modules/neo4j/src/test/scala/.../testkit/InMemoryGraphStore.scala`. It is a `Ref[F, State]`-backed implementation of the full `GraphStore[F]` algebra. |
| Could it be used as shared session state? | **Yes, with adaptation.** It provides graph structure, bi-temporal semantics, and semantic search that `HarnessState` lacks. But it has no merge semantics for mutable scalars and no visibility levels. It lives in Test scope and would need extraction. |
| What is the best architecture? | **Hybrid**: `HarnessState` (semilattice/CRDT) for ephemeral scalar coordination + `InMemoryGraphStore`/`GraphStore` for structured, queryable, bi-temporal shared knowledge. The two are complementary. |
