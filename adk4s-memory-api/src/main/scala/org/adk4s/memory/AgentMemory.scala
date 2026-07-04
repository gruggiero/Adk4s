package org.adk4s.memory

import cats.Monad
import cats.syntax.all.*

/**
 * The capability ADK4S agents are missing: durable, recallable memory.
 *
 * Effect-polymorphic. Implementations range from an in-process `Ref`-backed
 * double (tests) to a temporal knowledge graph (GraphStore). Callers depend
 * only on this trait.
 */
trait AgentMemory[F[_]]:

  /**
   * Commit an episode. Backends may extract entities/relationships, dedupe,
   * and invalidate contradicted facts; the report captures what happened.
   */
  def remember(episode: Episode): F[EpisodeOutcome]

  /**
   * Retrieve the `k` most relevant facts for `query`, optionally scoped to a
   * point in time. Ordering is by descending `score`.
   */
  def recall(
    query: String,
    k: Int,
    scope: Option[TemporalScope] = None
  ): F[List[MemoryHit]]

  /**
   * Batch ingest. Default is sequential via `Traverse`; backends with a
   * cheaper bulk path (single transaction, parallel extraction) override.
   */
  def rememberAll(episodes: List[Episode])(using Monad[F]): F[List[EpisodeOutcome]] =
    episodes.traverse(remember)

object AgentMemory:
  def apply[F[_]](using m: AgentMemory[F]): AgentMemory[F] = m
