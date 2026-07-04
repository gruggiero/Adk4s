# ADK4S Change Proposal: `adk4s-memory-api`

**Status:** Draft
**Target repo:** `gruggiero/Adk4s`
**Type:** New module + optional non-breaking hook in orchestration
**Scala:** 3.4.x · **Effect stack:** Cats Effect 3, fs2
**Depends on:** `adk4s-core` (for `Retriever` / `Document`)

> **Verification note.** Several signatures below reference existing ADK4S
> types (`Retriever[F]`, `Document`, `ReactAgent`, `AgentRunner`, `AgentEvent`,
> `Conversation`). They are reconstructed from the project README and MUST be
> checked against the real sources before merge. Every such spot is marked
> **`⚠ VERIFY`** with the file you should open to confirm the shape.

---

## 1. Motivation

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
the abstraction lives in core-adjacent code, implementations live elsewhere.

**Design goal:** an agent gains long-term memory by being handed an
`AgentMemory[F]`. It never learns that Neo4j exists. Test doubles satisfy the
same interface with zero infrastructure.

### Non-goals

- No storage engine, no embeddings, no graph logic in this module.
- No mandatory change to `ReactAgent` behavior. The memory hook (§6) is
  strictly opt-in and additive.
- No new transitive heavy dependencies. If a PR adds `neo4j`, `lucene`,
  `http4s`, or `cats-effect-std` beyond what `adk4s-core` already pulls, it is
  out of scope for this module.

---

## 2. Module layout

```
adk4s-memory-api/
└── src/
    ├── main/scala/org/adk4s/memory/
    │   ├── Episode.scala          // Episode, SourceType, EpisodeOutcome
    │   ├── MemoryHit.scala        // MemoryHit, TemporalScope
    │   ├── AgentMemory.scala      // the capability trait
    │   ├── MemoryRetriever.scala  // bridge: AgentMemory => Retriever
    │   └── InMemoryAgentMemory.scala // in-process test double
    └── test/scala/org/adk4s/memory/
        ├── AgentMemoryLaws.scala      // behavioral contract (reusable)
        └── InMemoryAgentMemorySpec.scala
```

### `build.sbt` addition

```scala
lazy val `adk4s-memory-api` = project
  .in(file("adk4s-memory-api"))
  .dependsOn(`adk4s-core`) // ⚠ VERIFY module id in build.sbt
  .settings(
    name := "adk4s-memory-api",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core"   % catsVersion,
      // test only
      "org.typelevel" %% "cats-effect" % catsEffectVersion % Test,
      "org.scalameta" %% "munit"       % munitVersion      % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCeVersion % Test
    )
  )

// Add to the aggregate root:
lazy val root = (project in file("."))
  .aggregate(/* existing */, `adk4s-memory-api`)
```

Note the **main** scope pulls only `cats-core` (typeclasses: `Monad`,
`Traverse`, `Functor`). Cats Effect appears in **Test** scope only, because the
test double and its spec need `Ref`/`IO`. The interface itself is
effect-polymorphic and imposes no `Async`/`Sync` constraint.

---

## 3. Value types

### 3.1 `Episode.scala`

```scala
package org.adk4s.memory

import java.time.Instant

/** A discrete unit of experience an agent commits to memory: a conversation
  * turn, a tool result, an ingested document, a structured record.
  *
  * `timestamp` is the *valid time* reference — when the described facts were
  * true in the world — not necessarily when the episode was recorded. Backends
  * with a bi-temporal model use this as `validFrom`.
  */
final case class Episode(
    content: String,
    sourceType: SourceType,
    timestamp: Instant,
    groupId: Option[String] = None,   // conversation / session / thread id
    metadata: Map[String, String] = Map.empty
)

object Episode:
  /** Convenience for the common "conversation turn happening now" case. */
  def conversation(content: String, groupId: String, at: Instant): Episode =
    Episode(content, SourceType.Conversation, at, Some(groupId))

enum SourceType:
  case Conversation, Document, StructuredData, ToolResult, ExternalApi

/** Result of committing one episode. Counts only — no infrastructure types
  * leak across the boundary. A backend that does no extraction (e.g. the test
  * double) reports zeros and still succeeds. */
final case class EpisodeOutcome(
    entitiesExtracted: Int,
    relationshipsCreated: Int,
    edgesInvalidated: Int,
    processingTimeMs: Long,
    errors: List[String] = Nil
):
  def isSuccess: Boolean = errors.isEmpty

object EpisodeOutcome:
  val empty: EpisodeOutcome = EpisodeOutcome(0, 0, 0, 0L, Nil)
```

### 3.2 `MemoryHit.scala`

```scala
package org.adk4s.memory

import java.time.Instant

/** A single recalled fact.
  *
  * `text` is the agent-facing rendering (e.g. "Alice works at Meta") suitable
  * for splicing into a prompt. `validFrom`/`validTo` expose the temporal window
  * when the backend tracks it (both `None` for non-temporal backends).
  * `provenance` points back to the episode/group that asserted the fact.
  */
final case class MemoryHit(
    text: String,
    score: Double,
    validFrom: Option[Instant] = None,
    validTo: Option[Instant] = None,
    provenance: Option[String] = None,
    payload: Map[String, String] = Map.empty
)

/** Optional point-in-time scoping for recall: "what did the agent know / what
  * was true as of `asOf`". Backends without temporal support MUST ignore this
  * rather than fail. */
final case class TemporalScope(asOf: Instant)
```

---

## 4. The capability trait

### `AgentMemory.scala`

```scala
package org.adk4s.memory

import cats.Monad
import cats.syntax.all.*

/** The capability ADK4S agents are missing: durable, recallable memory.
  *
  * Effect-polymorphic. Implementations range from an in-process `Ref`-backed
  * double (tests) to a temporal knowledge graph (GraphStore). Callers depend
  * only on this trait.
  */
trait AgentMemory[F[_]]:

  /** Commit an episode. Backends may extract entities/relationships, dedupe,
    * and invalidate contradicted facts; the report captures what happened. */
  def remember(episode: Episode): F[EpisodeOutcome]

  /** Retrieve the `k` most relevant facts for `query`, optionally scoped to a
    * point in time. Ordering is by descending `score`. */
  def recall(
      query: String,
      k: Int,
      scope: Option[TemporalScope] = None
  ): F[List[MemoryHit]]

  /** Batch ingest. Default is sequential via `Traverse`; backends with a
    * cheaper bulk path (single transaction, parallel extraction) override. */
  def rememberAll(episodes: List[Episode])(using Monad[F]): F[List[EpisodeOutcome]] =
    episodes.traverse(remember)

object AgentMemory:
  def apply[F[_]](using m: AgentMemory[F]): AgentMemory[F] = m
```

**Contract (informal laws — encoded in §7):**

1. **Recall-after-remember.** After `remember(e)` succeeds for an episode whose
   `content` contains term `t`, a subsequent `recall(t, k>=1)` returns a
   non-empty list *for backends that index content*. The test double satisfies
   this by substring match; graph backends satisfy it via extraction+search.
   (Stated as a capability flag, not a hard universal law — see §7.)
2. **Score ordering.** `recall` results are sorted by descending `score`.
3. **k bound.** `recall(_, k)` returns at most `k` hits.
4. **Temporal ignorability.** A backend that ignores `scope` MUST still return
   sensible results, never an error.

---

## 5. Bridge to the existing `Retriever`

ADK4S agents already consume a `Retriever[F]` (see `RetrieverExample`). Exposing
memory *as* a retriever means any current agent wiring accepts memory with no
new plumbing.

### `MemoryRetriever.scala`

```scala
package org.adk4s.memory

import cats.Functor
import cats.syntax.functor.*
import org.adk4s.core.component.{Retriever, Document} // ⚠ VERIFY path & shape
                                                      //   open adk4s-core Retriever.scala

/** Adapt any `AgentMemory` into a `Retriever`, so `ReactAgent` / `ToolsNode`
  * can use it without knowing about episodes or temporality. */
object MemoryRetriever:

  def apply[F[_]: Functor](
      memory: AgentMemory[F],
      k: Int = 8,
      scope: Option[TemporalScope] = None
  ): Retriever[F] =
    new Retriever[F]:
      def retrieve(query: String): F[List[Document]] =   // ⚠ VERIFY method name/sig
        memory.recall(query, k, scope).map(_.map(toDocument))

  private def toDocument(hit: MemoryHit): Document =
    // ⚠ VERIFY Document constructor. Assumed: content + optional score + metadata.
    Document(
      content = hit.text,
      score = Some(hit.score),
      metadata = hit.payload
        ++ hit.provenance.map("provenance" -> _).toMap
    )
```

> **If `Retriever` is a SAM / function type** (`type Retriever[F] = String =>
> F[List[Document]]`) rather than a trait, collapse the above to a lambda. The
> `⚠ VERIFY` on the trait vs. alias is the single most important thing to check
> before writing this file.

---

## 6. Optional memory hook in orchestration (opt-in, non-breaking)

This section adds memory *awareness* to the ReAct loop without changing default
behavior. It lives in `adk4s-orchestration`, which gains a dependency on
`adk4s-memory-api`. **If you want the memory-api module to ship first with zero
orchestration changes, defer this entire section to a follow-up PR.**

### 6.1 Design

Two seams around a ReAct turn:

- **Pre-turn recall (read):** before the LLM call, `recall` the user's latest
  input and inject the hits as an additional system/context message.
- **Post-turn write (remember):** after a turn completes, `remember` the
  user input and/or the assistant's final message.

Both are pure wrappers; if no `AgentMemory` is supplied, the agent behaves
exactly as today.

### 6.2 `MemoryHook.scala` (new, in orchestration)

```scala
package org.adk4s.orchestration.memory

import cats.effect.kernel.Async
import cats.syntax.all.*
import org.adk4s.memory.{AgentMemory, Episode, SourceType, TemporalScope, MemoryHit}
import org.adk4s.core.component.Conversation // ⚠ VERIFY Conversation/Message API
import java.time.Instant

/** Policy describing how an agent turn interacts with memory. */
final case class MemoryPolicy(
    recallK: Int = 8,
    scope: Option[TemporalScope] = None,
    writeUserInput: Boolean = true,
    writeAssistantOutput: Boolean = false,
    // Render recalled hits into a single context string injected pre-turn.
    render: List[MemoryHit] => String = MemoryPolicy.defaultRender
)

object MemoryPolicy:
  val defaultRender: List[MemoryHit] => String = hits =>
    if hits.isEmpty then ""
    else
      hits
        .map(h => s"- ${h.text}${h.validFrom.fold("")(t => s" (since $t)")}")
        .mkString("Relevant memory:\n", "\n", "")

/** Wraps recall/remember around a turn. Effect-polymorphic; the agent supplies
  * the `F`. Pure no-op when `memory` is `None`. */
final class MemoryHook[F[_]: Async](
    memory: Option[AgentMemory[F]],
    policy: MemoryPolicy
):

  /** Produce an optional context block to inject before the LLM call. */
  def preTurn(latestUserInput: String): F[Option[String]] =
    memory.traverse(_.recall(latestUserInput, policy.recallK, policy.scope))
      .map(_.map(policy.render).filter(_.nonEmpty))

  /** Persist selected parts of the completed turn. */
  def postTurn(
      groupId: String,
      userInput: String,
      assistantOutput: String,
      at: Instant
  ): F[Unit] =
    memory.fold(().pure[F]) { m =>
      val userEp =
        Option.when(policy.writeUserInput)(
          Episode(userInput, SourceType.Conversation, at, Some(groupId))
        )
      val botEp =
        Option.when(policy.writeAssistantOutput)(
          Episode(assistantOutput, SourceType.ToolResult, at, Some(groupId))
        )
      List(userEp, botEp).flatten.traverse_(m.remember)
    }
```

### 6.3 Wiring into `ReactAgent` / `AgentRunner`

> ⚠ VERIFY the exact `ReactAgent.create` signature and the run loop in
> `adk4s-orchestration`. The recommended integration is **at `AgentRunner`
> level**, not inside `ReactAgent`, so the core ReAct loop stays untouched.

Sketch of a memory-aware runner wrapper:

```scala
package org.adk4s.orchestration.memory

import cats.effect.kernel.Async
import cats.syntax.all.*
import org.adk4s.orchestration.{AgentRunner, RunResult} // ⚠ VERIFY
import java.time.Instant

/** Decorator: runs `preTurn` recall, delegates to the underlying runner,
  * then `postTurn` write. Additive — construct only when memory is desired. */
final class MemoryAwareRunner[F[_]: Async](
    underlying: AgentRunner[F],     // ⚠ VERIFY AgentRunner is F-polymorphic
    hook: MemoryHook[F],
    groupId: String,
    now: F[Instant]
):
  def run(userInput: String): F[RunResult] =
    for
      ctx    <- hook.preTurn(userInput)
      // ⚠ VERIFY how to prepend a context/system message to the runner input.
      result <- underlying.run(injectContext(ctx, userInput))
      t      <- now
      _      <- hook.postTurn(groupId, userInput, extractFinal(result), t)
    yield result

  private def injectContext(ctx: Option[String], input: String): String =
    ctx.fold(input)(c => s"$c\n\n$input") // ⚠ replace with proper Message API

  private def extractFinal(r: RunResult): String =
    ??? // ⚠ VERIFY: pull the final AssistantMessage content from RunResult
```

The key property: **`MemoryAwareRunner` is never constructed unless the caller
opts in.** Default ADK4S agents are byte-for-byte unchanged.

### 6.4 Optional: emit memory events

If you want observability parity with the rest of ADK4S, add two `AgentEvent`
variants (⚠ VERIFY `AgentEvent` is an open enum/sealed trait you can extend):

```scala
// In adk4s-core AgentEvent.scala (only if event stream parity is wanted)
case MemoryRecalled(runPath: RunPath, query: String, hitCount: Int)
case MemoryWritten(runPath: RunPath, episodes: Int)
```

This is cosmetic; skip it for the first cut.

---

## 7. Test double + reusable contract

### 7.1 `InMemoryAgentMemory.scala` (main scope, useful beyond tests)

```scala
package org.adk4s.memory

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

/** Zero-dependency, substring-indexed memory for tests, demos, and local dev.
  * No extraction, no embeddings, no temporality (ignores `scope`). */
final class InMemoryAgentMemory[F[_]: Sync](ref: Ref[F, Vector[Episode]])
    extends AgentMemory[F]:

  def remember(episode: Episode): F[EpisodeOutcome] =
    ref.update(_ :+ episode).as(EpisodeOutcome.empty)

  def recall(
      query: String,
      k: Int,
      scope: Option[TemporalScope] = None // intentionally ignored
  ): F[List[MemoryHit]] =
    ref.get.map { episodes =>
      val q = query.toLowerCase
      episodes
        .map(e => e -> naiveScore(e.content.toLowerCase, q))
        .collect { case (e, s) if s > 0.0 => toHit(e, s) }
        .sortBy(-_.score)
        .take(k)
        .toList
    }

  private def naiveScore(content: String, q: String): Double =
    if content.contains(q) then 1.0
    else
      val terms = q.split("\\s+").filter(_.nonEmpty)
      if terms.isEmpty then 0.0
      else terms.count(content.contains).toDouble / terms.length

  private def toHit(e: Episode, score: Double): MemoryHit =
    MemoryHit(
      text = e.content,
      score = score,
      validFrom = Some(e.timestamp),
      provenance = e.groupId,
      payload = e.metadata
    )

object InMemoryAgentMemory:
  def create[F[_]: Sync]: F[AgentMemory[F]] =
    Ref.of[F, Vector[Episode]](Vector.empty).map(new InMemoryAgentMemory[F](_))
```

### 7.2 `AgentMemoryLaws.scala` — a contract any backend can run

```scala
package org.adk4s.memory

import cats.effect.IO
import cats.syntax.all.*
import java.time.Instant

/** Reusable behavioral checks. GraphStore's test suite imports this and runs
  * the same assertions against its Neo4j-backed implementation (Testcontainers),
  * guaranteeing both backends honor one contract.
  *
  * `indexesContent` gates law (1): substring/extraction backends set it true;
  * a hypothetical write-only sink sets it false. */
final case class AgentMemoryLaws(indexesContent: Boolean):

  private val now = Instant.parse("2025-01-01T00:00:00Z")

  /** Law 3: recall returns at most k. */
  def kBound(mem: AgentMemory[IO]): IO[Boolean] =
    val eps = (1 to 10).toList.map(i =>
      Episode(s"fact number $i about widgets", SourceType.Document, now)
    )
    eps.traverse_(mem.remember) *>
      mem.recall("widgets", k = 3).map(_.size <= 3)

  /** Law 2: results are sorted by descending score. */
  def scoreOrdering(mem: AgentMemory[IO]): IO[Boolean] =
    mem.recall("anything", 10).map { hits =>
      hits.map(_.score) == hits.map(_.score).sortBy(-_)
    }

  /** Law 1 (gated): recall-after-remember finds the term. */
  def recallAfterRemember(mem: AgentMemory[IO]): IO[Boolean] =
    if !indexesContent then IO.pure(true)
    else
      val ep = Episode("Alice works at Meta", SourceType.Conversation, now, Some("g1"))
      mem.remember(ep) *> mem.recall("Meta", 5).map(_.nonEmpty)

  /** Law 4: ignoring scope never errors. */
  def temporalIgnorability(mem: AgentMemory[IO]): IO[Boolean] =
    mem.recall("x", 5, Some(TemporalScope(now))).attempt.map(_.isRight)

  def all(mem: AgentMemory[IO]): IO[Boolean] =
    (kBound(mem), scoreOrdering(mem), recallAfterRemember(mem), temporalIgnorability(mem))
      .mapN(_ && _ && _ && _)
```

### 7.3 `InMemoryAgentMemorySpec.scala`

```scala
package org.adk4s.memory

import cats.effect.IO
import munit.CatsEffectSuite

final class InMemoryAgentMemorySpec extends CatsEffectSuite:

  private val laws = AgentMemoryLaws(indexesContent = true)

  test("in-memory backend satisfies the AgentMemory contract"):
    InMemoryAgentMemory.create[IO].flatMap(laws.all).assertEquals(true)
```

---

## 8. Acceptance checklist

- [ ] `adk4s-memory-api` compiles with **only** `cats-core` on the main
      classpath (verify with `sbt "adk4s-memory-api/dependencyTree"` — no
      `neo4j`, `lucene`, `http4s`).
- [ ] `AgentMemory[F]`, value types, `MemoryRetriever`, `InMemoryAgentMemory`
      present and documented.
- [ ] `AgentMemoryLaws` published in a scope GraphStore can depend on
      (either `Test` with `sbt-testkit` export, or a small `-testkit` sibling).
- [ ] `MemoryRetriever` verified against real `Retriever`/`Document`
      (all `⚠ VERIFY` in §5 resolved).
- [ ] **Default agents unchanged**: existing examples run identically; the hook
      in §6 is only active when a `MemoryAwareRunner` is explicitly built.
- [ ] `adk4s-memory-api` added to the root aggregate and CI matrix.

---

## 9. Rollout

1. **PR 1 — interface only.** Ship §2–§5 and §7. No orchestration changes.
   GraphStore can begin implementing against it immediately.
2. **PR 2 — testkit export.** Make `AgentMemoryLaws` consumable downstream.
3. **PR 3 — opt-in hook.** Add §6 (`MemoryHook`, `MemoryAwareRunner`,
   optional events) after the real orchestration signatures are confirmed.

Keeping the interface PR free of orchestration edits means GraphStore is
unblocked without waiting on the `⚠ VERIFY` items in §6.
