package org.adk4s.memory

import java.time.Instant

/**
 * A discrete unit of experience an agent commits to memory: a conversation
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
  groupId: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

object Episode:
  /** Convenience for the common "conversation turn happening now" case. */
  def conversation(content: String, groupId: String, at: Instant): Episode =
    Episode(content, SourceType.Conversation, at, Some(groupId))

/** Source classification for an `Episode`. */
enum SourceType:
  case Conversation, Document, StructuredData, ToolResult, ExternalApi

/**
 * Result of committing one episode. Counts only — no infrastructure types
 * leak across the boundary. A backend that does no extraction (e.g. the test
 * double) reports zeros and still succeeds.
 */
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
