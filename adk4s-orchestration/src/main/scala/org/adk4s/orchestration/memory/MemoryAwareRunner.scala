package org.adk4s.orchestration.memory

import cats.effect.IO
import cats.syntax.all.toFlatMapOps
import fs2.Stream
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, InterruptResult, RunPath, RunStep}
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
  * Event emission: when `emitter` and `agentName` are both `Some`, the
  * decorator emits `MemoryRecalled` after `preTurn` and `MemoryWritten`
  * after `postTurn` (only on `Completed`) on the same `AgentEventEmitter`
  * the underlying runner uses. When either is `None`, no memory events are
  * emitted (spec 1 behavior).
  */
final class MemoryAwareRunner(
  agentRunner: AgentRunner,
  memory: Option[AgentMemory[IO]],
  policy: MemoryPolicy,
  emitter: Option[AgentEventEmitter] = None,
  agentName: Option[String] = None
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
    * When `emitter` and `agentName` are both `Some`, the decorator emits
    * `MemoryRecalled` after `preTurn` and `MemoryWritten` after `postTurn`
    * (only on `Completed`) on the same `AgentEventEmitter` the underlying
    * runner uses. The event stream is `emitter.subscribe`.
    *
    * When either is `None` (spec 1 behavior), the event stream is the
    * underlying runner's stream verbatim (no memory events).
    *
    * NOTE: Context injection (prepending the rendered recall block to the
    * messages) is applied in `run` but NOT in `runWithEvents`. The spec's
    * "Pre-turn recall injects context" requirement specifically names `run`,
    * not `runWithEvents`; `runWithEvents` requires that `preTurn` runs (for
    * recall side-effects + `MemoryRecalled` emission) and `postTurn` runs on
    * `Completed` (for `MemoryWritten` emission).
    */
  def runWithEvents(messages: List[Message], maxSteps: Int = 10): (IO[RunResult], Stream[IO, AgentEvent]) =
    (emitter, agentName) match
      case (Some(em), Some(name)) =>
        val events: Stream[IO, AgentEvent] = em.subscribe
        val scopedEm: AgentEventEmitter = em.scoped(RunStep(name))
        val runPath: RunPath = RunPath.of(name)
        val latestUserInput: String = extractLatestUserInput(messages)
        val wrappedIO: IO[RunResult] =
          for
            pair         <- hook.preTurnWithHits(latestUserInput)
            (_, hitCount) = pair
            // MemoryRecalled fires iff memory is present (spec requirement).
            // When memory = None, preTurnWithHits returns hitCount = 0 and
            // we skip the emission entirely.
            _            <- emitMemoryRecalledIfMemoryPresent(scopedEm, runPath, latestUserInput, hitCount)
            result       <- agentRunner.run(messages, maxSteps)
            episodeCount <- postTurnWithCountIfCompleted(result, messages)
            // MemoryWritten fires iff memory is present AND Completed.
            _            <- emitMemoryWrittenIfMemoryPresentAndCompleted(scopedEm, runPath, result, episodeCount)
            _            <- em.complete
          yield result
        (wrappedIO, events)
      case _ =>
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

  /** Run `postTurnWithCount` only when `result` is `Completed`; return the
    * episode count. Returns 0 for `Interrupted`/`Failed` (no write).
    */
  private def postTurnWithCountIfCompleted(result: RunResult, originalMessages: List[Message]): IO[Int] =
    result match
      case RunResult.Completed(output, _) =>
        val groupId: String = UUID.randomUUID().toString
        val latestUserInput: String = extractLatestUserInput(originalMessages)
        hook.postTurnWithCount(groupId, latestUserInput, output, Instant.now)
      case _: RunResult.Interrupted => IO.pure(0)
      case _: RunResult.Failed => IO.pure(0)

  /** Emit `MemoryRecalled` only when `memory` is present (non-`None`).
    * When `memory = None`, `hitCount` is 0 and the spec forbids emission.
    */
  private def emitMemoryRecalledIfMemoryPresent(
    scopedEm: AgentEventEmitter,
    runPath: RunPath,
    query: String,
    hitCount: Int
  ): IO[Unit] =
    memory match
      case Some(_) => scopedEm.emit(AgentEvent.MemoryRecalled(runPath, query, hitCount))
      case None    => IO.unit

  /** Emit `MemoryWritten` only when `memory` is present (non-`None`) AND
    * `result` is `Completed`. The spec forbids emission when `memory = None`
    * or on `Interrupted`/`Failed`.
    */
  private def emitMemoryWrittenIfMemoryPresentAndCompleted(
    scopedEm: AgentEventEmitter,
    runPath: RunPath,
    result: RunResult,
    episodeCount: Int
  ): IO[Unit] =
    (memory, result) match
      case (Some(_), _: RunResult.Completed) =>
        scopedEm.emit(AgentEvent.MemoryWritten(runPath, episodeCount))
      case _ => IO.unit
