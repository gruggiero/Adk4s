package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.syntax.all.toFlatMapOps
import fs2.Stream
import org.adk4s.core.interrupt.{AgentEvent, InterruptResult}
import org.adk4s.memory.AgentMemory
import org.adk4s.orchestration.agent.{AgentRunner, RunResult}
import org.llm4s.llmconnect.model.{Message, UserMessage}

import java.time.Instant
import java.util.UUID

/** Decorator over `AgentRunner` that makes an agent memory-aware.
  *
  * Wraps `run`/`runWithEvents`/`resume`: runs `preTurn` (recall + context
  * injection) before the underlying turn and `postTurn` (remember) after,
  * but ONLY calls `postTurn` when the `RunResult` is `Completed`.
  * `Interrupted`/`Failed` skip the write.
  *
  * NO event emission in this spec (memory events are the separate events spec).
  */
final class MemoryAwareRunner(
  agentRunner: AgentRunner,
  memory: Option[AgentMemory[IO]],
  policy: MemoryPolicy
):
  private val hook: MemoryHook = MemoryHook(memory, policy)

  /** Run the agent with pre-turn recall and post-turn write (only on `Completed`). */
  def run(messages: List[Message], maxSteps: Int = 10): IO[RunResult] =
    val latestUserInput: String = extractLatestUserInput(messages)
    for
      contextOpt <- hook.preTurn(latestUserInput)
      injected    = injectContext(messages, contextOpt)
      result     <- agentRunner.run(injected, maxSteps)
      _          <- postTurnIfCompleted(result, messages)
    yield result

  /** Run the agent and return both the result and an event stream.
    *
    * The event stream is the underlying runner's stream verbatim (no memory
    * events in this spec). The result `IO` runs `preTurn` before the
    * underlying result `IO` and `postTurn` (only on `Completed`) after it.
    *
    * NOTE: Context injection (prepending the rendered recall block to the
    * messages) is applied in `run` but NOT in `runWithEvents`, because the
    * underlying `runWithEvents` captures the messages at construction time
    * and the event stream is subscribed before we can run `preTurn`. The
    * spec's "Pre-turn recall injects context" requirement specifically names
    * `run`, not `runWithEvents`; `runWithEvents` only requires that
    * `preTurn` runs (for recall side-effects) and `postTurn` runs on
    * `Completed`. Context injection in the `runWithEvents` path is deferred
    * to the events spec, which will have access to the emitter for
    * `MemoryRecalled` events.
    */
  def runWithEvents(messages: List[Message], maxSteps: Int = 10): (IO[RunResult], Stream[IO, AgentEvent]) =
    val (underlyingIO: IO[RunResult], events: Stream[IO, AgentEvent]) = agentRunner.runWithEvents(messages, maxSteps)
    val latestUserInput: String = extractLatestUserInput(messages)
    val wrappedIO: IO[RunResult] =
      for
        _      <- hook.preTurn(latestUserInput)
        result <- underlyingIO
        _      <- postTurnIfCompleted(result, messages)
      yield result
    (wrappedIO, events)

  /** Resume from a checkpoint with pre-turn recall and post-turn write (only on `Completed`).
    *
    * `preTurn` is applied to the latest user input available before the resume
    * (extracted from the `results` — the resume data that `AgentRunner.resume`
    * appends as user messages). Context injection into the resumed messages is
    * architecturally limited: the underlying `AgentRunner.resume` reconstructs
    * messages internally from the checkpoint and does not expose them before
    * the run. The recall side-effect still runs (for memory consistency), and
    * `postTurn` extracts the actual latest user input from the `Completed`
    * result's messages field.
    */
  def resume(checkpointId: String, results: List[InterruptResult], maxSteps: Int = 10): IO[RunResult] =
    val resumeUserInput: String = extractLatestResumeInput(results)
    for
      _      <- hook.preTurn(resumeUserInput)
      result <- agentRunner.resume(checkpointId, results, maxSteps)
      _      <- postTurnIfCompleted(result, completedMessages(result))
    yield result

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Extract the latest user message content from the message list.
    * Returns "" if no user message is present.
    */
  private def extractLatestUserInput(messages: List[Message]): String =
    messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")

  /** Extract the latest user input from the resume results.
    * `AgentRunner.resume` appends each `InterruptResult` as a user message
    * containing the serialized data. We use the last result's data as the
    * best available "latest user input" for `preTurn` recall.
    * Returns "" if no results.
    */
  private def extractLatestResumeInput(results: List[InterruptResult]): String =
    results.lastOption.map { (r: InterruptResult) =>
      val addressPath: String = r.address.map(_.name).mkString(" > ")
      val dataJson: String = upickle.default.write(r.data)
      s"[Resume approval for $addressPath]: $dataJson"
    }.getOrElse("")

  /** Extract the messages from a `RunResult.Completed`; `Nil` for other variants.
    * Used to pass the actual conversation messages to `postTurnIfCompleted`
    * so it can extract the real latest user input.
    */
  private def completedMessages(result: RunResult): List[Message] =
    result match
      case RunResult.Completed(_, msgs) => msgs
      case _: RunResult.Interrupted => Nil
      case _: RunResult.Failed => Nil

  /** Prepend a `UserMessage` with the rendered context block if `contextOpt`
    * is non-empty; otherwise return `messages` unchanged.
    */
  private def injectContext(messages: List[Message], contextOpt: Option[String]): List[Message] =
    contextOpt match
      case None => messages
      case Some(context) => UserMessage(context) :: messages

  /** Run `postTurn` only when `result` is `Completed`. No-op otherwise. */
  private def postTurnIfCompleted(result: RunResult, originalMessages: List[Message]): IO[Unit] =
    result match
      case RunResult.Completed(output, _) =>
        val groupId: String = UUID.randomUUID().toString
        val latestUserInput: String = extractLatestUserInput(originalMessages)
        hook.postTurn(groupId, latestUserInput, output, Instant.now)
      case _: RunResult.Interrupted => IO.unit
      case _: RunResult.Failed => IO.unit
