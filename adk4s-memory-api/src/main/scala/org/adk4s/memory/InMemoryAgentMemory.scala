package org.adk4s.memory

import cats.effect.kernel.{ Ref, Sync }
import cats.syntax.all.*

/**
 * Zero-dependency, substring-indexed memory for tests, demos, and local dev.
 * No extraction, no embeddings, no temporality (ignores `scope`).
 *
 * Scoring (`naiveScore`):
 *   - If the query (lowercased) is a substring of the content (lowercased):
 *     score = 1.0 (exact substring match).
 *   - Otherwise: split the query into whitespace-separated terms and count
 *     how many appear as substrings of the content. score = matched / total.
 *   - If no terms match: the episode is not returned (no hit).
 */
final class InMemoryAgentMemory[F[_]: Sync](ref: Ref[F, Vector[Episode]]) extends AgentMemory[F]:

  def remember(episode: Episode): F[EpisodeOutcome] =
    ref.update(_ :+ episode).as(EpisodeOutcome.empty)

  def recall(
    query: String,
    k: Int,
    scope: Option[TemporalScope] = None
  ): F[List[MemoryHit]] =
    ref.get.map { episodes =>
      val scored: Vector[MemoryHit] = episodes.flatMap { ep =>
        val s: Double = InMemoryAgentMemory.naiveScore(
          ep.content.toLowerCase,
          query.toLowerCase
        )
        if s > 0.0 then
          Some(
            MemoryHit(
              text = ep.content,
              score = s,
              validFrom = None,
              validTo = None,
              provenance = ep.groupId,
              payload = ep.metadata
            )
          )
        else None
      }
      scored
        .sortBy(-_.score)
        .take(k)
        .toList
    }

object InMemoryAgentMemory:

  /** Pure scoring function exposed for property testing. */
  def naiveScore(contentLower: String, queryLower: String): Double =
    if contentLower.contains(queryLower) then 1.0
    else
      val terms: List[String] = queryLower.split("\\s+").filter(_.nonEmpty).toList
      if terms.isEmpty then 0.0
      else
        val matched: Int = terms.count(t => contentLower.contains(t))
        matched.toDouble / terms.size.toDouble

  def create[F[_]: Sync]: F[AgentMemory[F]] =
    Ref.of[F, Vector[Episode]](Vector.empty).map(new InMemoryAgentMemory[F](_))
