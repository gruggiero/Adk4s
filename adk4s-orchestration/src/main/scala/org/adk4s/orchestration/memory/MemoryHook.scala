package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.syntax.foldable.toFoldableOps
import cats.syntax.traverse.toTraverseOps
import org.adk4s.memory.{AgentMemory, Episode, SourceType}

import java.time.Instant

/** Pure recall/remember wrapper over `Option[AgentMemory[IO]]`.
  *
  * No-op when `memory` is `None`: `preTurn` returns `IO.pure(None)`,
  * `postTurn` returns `IO.unit`.
  * When `memory` is present: `preTurn` recalls and renders;
  * `postTurn` remembers per `policy`.
  */
final class MemoryHook(memory: Option[AgentMemory[IO]], policy: MemoryPolicy):

  /** Recall the `recallK` most relevant facts for `latestUserInput` and render
    * them via `policy.render`. Returns `None` when memory is absent, when
    * `recallK == 0`, or when recall yields an empty hit list.
    */
  def preTurn(latestUserInput: String): IO[Option[String]] =
    memory match
      case None => IO.pure(None)
      case Some(mem) =>
        if policy.recallK == 0 then IO.pure(None)
        else
          mem.recall(latestUserInput, policy.recallK, policy.scope).map { (hits: List[org.adk4s.memory.MemoryHit]) =>
            if hits.isEmpty then None
            else Some(policy.render(hits))
          }

  /** Persist the user input and/or assistant output as `Episode`s sharing the
    * given `groupId`. No-op when memory is absent or both write flags are false.
    */
  def postTurn(groupId: String, userInput: String, assistantOutput: String, at: Instant): IO[Unit] =
    memory match
      case None => IO.unit
      case Some(mem) =>
        val userEpisode: Option[Episode] =
          if policy.writeUserInput then
            Some(Episode(userInput, SourceType.Conversation, at, Some(groupId)))
          else None
        val assistantEpisode: Option[Episode] =
          if policy.writeAssistantOutput then
            Some(Episode(assistantOutput, SourceType.Conversation, at, Some(groupId)))
          else None
        val episodes: List[Episode] = userEpisode.toList ++ assistantEpisode.toList
        episodes.traverse_(mem.remember).void
