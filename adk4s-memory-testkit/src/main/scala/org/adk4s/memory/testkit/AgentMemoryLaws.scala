package org.adk4s.memory.testkit

import cats.effect.IO
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import java.time.Instant
import org.adk4s.memory.{ AgentMemory, Episode, SourceType, TemporalScope }

/**
 * Reusable behavioral contract that any `AgentMemory` backend can run.
 *
 * Downstream backends (e.g. GraphStore with Neo4j) depend on
 * `adk4s-memory-testkit` and run these laws against their implementation to
 * guarantee conformance with the same contract `InMemoryAgentMemory` honors.
 *
 * @param indexesContent
 *   `true` if the backend indexes content for substring/term recall. A
 *   write-only sink sets `false` to opt out of `recallAfterRemember` (law 1).
 */
final case class AgentMemoryLaws(indexesContent: Boolean):

  /**
   * Law 3: `recall(query, k)` returns at most `k` hits.
   *
   * Remembers 10 episodes containing "widgets", calls `recall("widgets", 3)`,
   * and returns `hits.size <= 3`.
   */
  def kBound(mem: AgentMemory[IO]): IO[Boolean] =
    val episodes: List[Episode] =
      (1 to 10).toList.map(i => Episode(s"widgets $i", SourceType.Document, AgentMemoryLaws.now))
    for
      _    <- episodes.traverse_(mem.remember)
      hits <- mem.recall("widgets", 3)
    yield hits.size <= 3

  /**
   * Law 2: `recall` results are sorted by descending score.
   *
   * Remembers three episodes, calls `recall("alpha", 10)`, and returns
   * `hits.map(_.score) == hits.map(_.score).sortBy(-_)`.
   */
  def scoreOrdering(mem: AgentMemory[IO]): IO[Boolean] =
    val episodes: List[Episode] = List(
      Episode("alpha beta gamma", SourceType.Document, AgentMemoryLaws.now),
      Episode("alpha gamma", SourceType.Document, AgentMemoryLaws.now),
      Episode("beta delta", SourceType.Document, AgentMemoryLaws.now)
    )
    for
      _    <- episodes.traverse_(mem.remember)
      hits <- mem.recall("alpha", 10)
      scores = hits.map(_.score)
    yield scores == scores.sortBy(-_)

  /**
   * Law 1: a remembered term is found by `recall`.
   *
   * When `indexesContent == true`: remembers
   * `Episode("Alice works at Meta", ...)`, calls `recall("Meta", 5)`, and
   * returns `hits.nonEmpty`.
   *
   * When `indexesContent == false`: no-op, returns `IO.pure(true)`.
   */
  def recallAfterRemember(mem: AgentMemory[IO]): IO[Boolean] =
    if indexesContent then
      val ep: Episode = Episode(
        content = "Alice works at Meta",
        sourceType = SourceType.Conversation,
        timestamp = AgentMemoryLaws.now,
        groupId = Some("g1")
      )
      for
        _    <- mem.remember(ep)
        hits <- mem.recall("Meta", 5)
      yield hits.nonEmpty
    else IO.pure(true)

  /**
   * Law 4: `recall` with `Some(scope)` never errors (temporal ignorability).
   *
   * Calls `recall("x", 5, Some(TemporalScope(now)))` and returns
   * `.attempt.isRight`.
   */
  def temporalIgnorability(mem: AgentMemory[IO]): IO[Boolean] =
    mem.recall("x", 5, Some(TemporalScope(AgentMemoryLaws.now))).attempt.map(_.isRight)

  /** Conjunction of all four laws. */
  def all(mem: AgentMemory[IO]): IO[Boolean] =
    for
      k <- kBound(mem)
      s <- scoreOrdering(mem)
      r <- recallAfterRemember(mem)
      t <- temporalIgnorability(mem)
    yield k && s && r && t

object AgentMemoryLaws:

  /** Fixed `now` used by the laws' fixture episodes. */
  val now: Instant = Instant.parse("2025-01-01T00:00:00Z")
