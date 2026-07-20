package org.adk4s.examples.memory

import cats.effect.kernel.Sync
import cats.syntax.all.*
import org.adk4s.memory.{
  AgentMemory,
  Episode,
  EpisodeOutcome,
  InMemoryAgentMemory,
  MemoryHit,
  SourceType,
  TemporalScope
}
import upickle.default.*

import java.nio.file.{ Files, Path, StandardOpenOption }
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * File-backed `AgentMemory[F]` for the cross-run memory demo.
 *
 * Persists episodes as JSON lines to `<dataDir>/episodes.jsonl`. `remember`
 * appends one line; `recall` reads the full file and scores with
 * `InMemoryAgentMemory.naiveScore` (same scoring as the in-memory double).
 * Ignores `TemporalScope` (permitted by law 4). Returns `EpisodeOutcome.empty`
 * counts (no extraction happens).
 *
 * The file is the SOLE state — no in-memory cache. Every `recall` reads the
 * file fresh, so a new instance pointed at the same path (even in a separate
 * JVM) recalls everything a prior instance wrote.
 *
 * Single-writer / single-reader by design. Concurrent writers are NOT
 * supported (documented non-goal).
 *
 * **Pass criteria**: this double must pass `AgentMemoryLaws(indexesContent =
 * true).all` — the same laws `InMemoryAgentMemory` honors.
 */
final class FileBackedAgentMemory[F[_]: Sync] private (dataDir: Path) extends AgentMemory[F]:
  import FileBackedAgentMemory.{ rwEpisode, rwInstant, rwSourceType }

  private val episodesFile: Path = dataDir.resolve("episodes.jsonl")

  def remember(episode: Episode): F[EpisodeOutcome] =
    val F: Sync[F] = summon[Sync[F]]
    F.blocking {
      val json: String = write(episode)
      Files.write(
        episodesFile,
        (json + "\n").getBytes("UTF-8"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
      EpisodeOutcome.empty
    }

  def recall(
    query: String,
    k: Int,
    scope: Option[TemporalScope] = None
  ): F[List[MemoryHit]] =
    val F: Sync[F] = summon[Sync[F]]
    F.blocking {
      if !Files.exists(episodesFile) then Nil
      else
        val lines: List[String] =
          Files.readAllLines(episodesFile).asScala.toList
        val episodes: Vector[Episode] =
          lines.iterator
            .filter(_.nonEmpty)
            .map((line: String) =>
              try Some(read[Episode](line))
              catch case _: Exception => None
            )
            .collect { case Some(ep) => ep }
            .toVector
        val queryLower: String = query.toLowerCase
        val scored: Vector[MemoryHit] = episodes.flatMap { (ep: Episode) =>
          val s: Double = InMemoryAgentMemory.naiveScore(
            ep.content.toLowerCase,
            queryLower
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
        scored.sortBy(-_.score).take(k).toList
    }

object FileBackedAgentMemory:

  /**
   * Smart constructor: creates `dataDir` if it does not exist, then returns
   * a fresh `FileBackedAgentMemory[F]` pointed at it.
   */
  def apply[F[_]: Sync](dataDir: Path): F[FileBackedAgentMemory[F]] =
    val F: Sync[F] = summon[Sync[F]]
    F.blocking {
      Files.createDirectories(dataDir)
      new FileBackedAgentMemory[F](dataDir)
    }

  // ── upickle serializers ──────────────────────────────────────────────────

  /** upickle does not serialize `java.time.Instant` by default. */
  given rwInstant: ReadWriter[Instant] =
    readwriter[String].bimap[Instant](_.toString, Instant.parse)

  /**
   * Manual `ReadWriter` for `SourceType` — Scala 3 enums are not case
   * classes, so `macroRW` cannot derive them. Serializes as the case name.
   */
  given rwSourceType: ReadWriter[SourceType] =
    readwriter[String].bimap[SourceType](
      (st: SourceType) => st.toString,
      (s: String) => SourceType.valueOf(s)
    )

  /** Derived `ReadWriter` for `Episode` — uses the givens above. */
  given rwEpisode: ReadWriter[Episode] = macroRW[Episode]
