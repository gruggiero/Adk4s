package org.adk4s.orchestration.agent

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.error.{ AdkError, AgentInterruptedException, CheckpointNotFoundError }
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter, InterruptResult, RunPath, RunStep }
import org.adk4s.harness.{ HarnessState, StateCell }
import org.adk4s.orchestration.interrupt.CheckpointStore
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, UserMessage }
import upickle.default.*

import java.util.UUID

/**
 * v1 serializable message — kept ONLY for v1-read compatibility.
 *
 * New code MUST use `CheckpointMessage` (full-fidelity). This type exists
 * solely so the v1 `ReadWriter` can decode old payloads.
 */
final private[agent] case class SerializableCheckpointMessage(
  role: String,
  content: String
) derives ReadWriter

/**
 * v1 checkpoint state — kept ONLY for v1-read compatibility.
 *
 * New code MUST use `CheckpointStateV2`. This type exists solely so the
 * v1 `ReadWriter` can decode old payloads.
 */
final private[agent] case class CheckpointState(
  messages: List[SerializableCheckpointMessage],
  interruptSignalJson: String,
  agentName: String
) derives ReadWriter

/**
 * Executes an agent with interrupt/resume and event streaming capabilities.
 *
 * spec: checkpoint-store-fpoly — Requirement: AgentRunner.resume restores harness state
 */
final class AgentRunner(
  agent: ReactAgent,
  checkpointStore: CheckpointStore[cats.effect.IO],
  emitter: AgentEventEmitter,
  cells: List[StateCell[?]] = Nil
):

  /** Run the agent to completion or until an interrupt is raised. */
  def run(messages: List[Message], maxSteps: Int = 10): IO[RunResult] =
    agent.harnessView match
      case Some(harness) =>
        // Harness-backed agent: run at the HarnessResult level so the live
        // HarnessState can be snapshotted into the checkpoint on interrupt.
        runHarness(harness, messages, None, maxSteps)
      case None =>
        runLegacy(messages, maxSteps)

  private def runLegacy(messages: List[Message], maxSteps: Int): IO[RunResult] =
    val agentStep: RunStep               = RunStep(agent.name)
    val scopedEmitter: AgentEventEmitter = emitter.scoped(agentStep)

    agent
      .generate(messages, maxSteps)
      .flatMap { (result: AssistantMessage) =>
        scopedEmitter.emit(
          AgentEvent.MessageOutput(
            runPath = RunPath.of(agent.name),
            message = result.content,
            role = "assistant"
          )
        ) *>
          IO.pure(RunResult.Completed(result.content, messages))
      }
      .handleErrorWith {
        case interrupted: AgentInterruptedException =>
          saveInterrupt(interrupted.signal, messages, HarnessState.initial(cells).snapshot, scopedEmitter)
        case adkError: AdkError =>
          scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), adkError)) *>
            IO.pure(RunResult.Failed(adkError))
        case other: Throwable =>
          val wrapped: AdkError = org.adk4s.core.error.GenericError(other.getMessage)
          scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), wrapped)) *>
            IO.pure(RunResult.Failed(wrapped))
      }

  /**
   * Run a harness-backed agent at the `HarnessResult` level. On interrupt,
   * the checkpoint persists the LIVE `HarnessState` snapshot (carried by
   * `HarnessResult.Interrupted`), not a fresh initial state. On resume,
   * `harness.resume` re-enters the loop with the restored state.
   *
   * spec: harness-agent — Requirement: Interrupt snapshots state without afterAgent
   */
  private def runHarness(
    harness: HarnessAgent[IO],
    messages: List[Message],
    restoredState: Option[HarnessState],
    maxSteps: Int
  ): IO[RunResult] =
    val agentStep: RunStep               = RunStep(agent.name)
    val scopedEmitter: AgentEventEmitter = emitter.scoped(agentStep)
    val action: IO[HarnessResult] = restoredState match
      case None        => harness.generate(messages, maxSteps)
      case Some(state) => harness.resume(messages, state, maxSteps)

    action
      .flatMap {
        case HarnessResult.Completed(assistant, _, _) =>
          scopedEmitter.emit(
            AgentEvent.MessageOutput(
              runPath = RunPath.of(agent.name),
              message = assistant.content,
              role = "assistant"
            )
          ) *>
            IO.pure(RunResult.Completed(assistant.content, messages))
        case HarnessResult.Interrupted(signal, _, state) =>
          saveInterrupt(signal, messages, state.snapshot, scopedEmitter)
        case HarnessResult.Failed(error, _, _) =>
          scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), error)) *>
            IO.pure(RunResult.Failed(error))
      }
      .handleErrorWith {
        case interrupted: AgentInterruptedException =>
          // The harness loop surfaces tool interrupts as
          // HarnessResult.Interrupted; an interrupt raised OUTSIDE the tool
          // step (e.g. by the model call itself) propagates as an error.
          saveInterrupt(interrupted.signal, messages, HarnessState.initial(cells).snapshot, scopedEmitter)
        case adkError: AdkError =>
          scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), adkError)) *>
            IO.pure(RunResult.Failed(adkError))
        case other: Throwable =>
          val wrapped: AdkError = org.adk4s.core.error.GenericError(other.getMessage)
          scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), wrapped)) *>
            IO.pure(RunResult.Failed(wrapped))
      }

  /** Persist a checkpoint for an interrupt and return the interrupted result. */
  private def saveInterrupt(
    signal: org.adk4s.core.interrupt.InterruptSignal,
    messages: List[Message],
    harnessStateJson: org.adk4s.core.json.JsonValue,
    scopedEmitter: AgentEventEmitter
  ): IO[RunResult] =
    val checkpointIdStr: String = UUID.randomUUID().toString
    val checkpointMessages: List[CheckpointMessage] =
      messages.map(CheckpointMessageConverter.toCheckpoint)
    val state: CheckpointStateV2 = CheckpointStateV2(
      version = CheckpointStateV2.CurrentVersion,
      messages = checkpointMessages,
      harnessState = harnessStateJson,
      interruptSignalJson = upickle.default.write(signal),
      agentName = agent.name
    )
    val serialized: Array[Byte] =
      upickle.default.write(state)(using CheckpointStateV2.readWriter).getBytes("UTF-8")
    IO.fromEither(CheckpointStore.CheckpointId.refineEither(checkpointIdStr)).flatMap {
      (checkpointId: CheckpointStore.CheckpointId) =>
        checkpointStore.set(checkpointId, serialized) *>
          scopedEmitter.emit(AgentEvent.Interrupted(RunPath.of(agent.name), signal)) *>
          IO.pure(RunResult.Interrupted(checkpointIdStr, signal))
    }

  /**
   * Resume an agent from a checkpoint with the provided interrupt results.
   *
   * Loads a `CheckpointStateV2` from the `CheckpointStore`, calls
   * `HarnessState.restore(cells, cp.harnessState)` to recover harness state,
   * and re-enters the loop. On `Left(StateDecodeError)`, returns
   * `RunResult.Failed`. For v1 checkpoints (empty `harnessState`), restore
   * succeeds with all cells at their declared `initial` values.
   *
   * spec: checkpoint-store-fpoly — Requirement: AgentRunner.resume restores harness state
   */
  def resume(checkpointId: String, results: List[InterruptResult], maxSteps: Int = 10): IO[RunResult] =
    IO.fromEither(CheckpointStore.CheckpointId.refineEither(checkpointId)).flatMap {
      (refinedId: CheckpointStore.CheckpointId) =>
        checkpointStore.get(refinedId).flatMap {
          case None =>
            IO.pure(RunResult.Failed(CheckpointNotFoundError(checkpointId)))
          case Some(data) =>
            val json: String          = new String(data, "UTF-8")
            val cp: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](json)(using CheckpointStateV2.readWriter)
            // Restore harness state from checkpoint
            HarnessState.restore(cells, cp.harnessState) match
              case Left(decodeError) =>
                IO.pure(RunResult.Failed(decodeError))
              case Right(restoredState) =>
                // Reconstruct original messages with full fidelity.
                // fromCheckpoint returns Left for unknown roles (corruption) —
                // fail the resume rather than silently mapping to UserMessage.
                val messagesResult: Either[String, List[Message]] =
                  cp.messages.foldLeft[Either[String, List[Message]]](Right(Nil)) {
                    case (Right(acc), cm) =>
                      CheckpointMessageConverter.fromCheckpoint(cm).map(msg => acc :+ msg)
                    case (Left(err), _) =>
                      Left(err)
                  }
                messagesResult match
                  case Left(err) =>
                    IO.pure(RunResult.Failed(org.adk4s.core.error.GenericError(err)))
                  case Right(originalMessages) =>
                    // Append resume data as user messages with address context
                    val resumeMessages: List[Message] = results.map { (r: InterruptResult) =>
                      val addressPath: String = r.address.map(_.name).mkString(" > ")
                      val dataJson: String    = upickle.default.write(r.data)
                      UserMessage(s"[Resume approval for $addressPath]: $dataJson"): Message
                    }
                    val allMessages: List[Message] = originalMessages ++ resumeMessages
                    // Harness-backed agents resume with the restored HarnessState;
                    // legacy agents re-enter with initial state (spec 4 behavior).
                    val resumed: IO[RunResult] = agent.harnessView match
                      case Some(harness) => runHarness(harness, allMessages, Some(restoredState), maxSteps)
                      case None          => runLegacy(allMessages, maxSteps)
                    resumed.flatMap { (result: RunResult) =>
                      result match
                        case _: RunResult.Completed =>
                          // Clean up checkpoint on successful completion
                          checkpointStore.delete(refinedId).as(result)
                        case _ =>
                          IO.pure(result)
                    }
        }
    }

  /** Run the agent and return both the result and an event stream. */
  def runWithEvents(messages: List[Message], maxSteps: Int = 10): (IO[RunResult], Stream[IO, AgentEvent]) =
    val result: IO[RunResult]          = run(messages, maxSteps).flatMap((r: RunResult) => emitter.complete.as(r))
    val events: Stream[IO, AgentEvent] = emitter.subscribe
    (result, events)

object AgentRunner:
  def create(
    agent: ReactAgent,
    checkpointStore: CheckpointStore[cats.effect.IO],
    emitter: AgentEventEmitter
  ): AgentRunner =
    new AgentRunner(agent, checkpointStore, emitter)

  def create(
    agent: ReactAgent,
    checkpointStore: CheckpointStore[cats.effect.IO]
  ): IO[AgentRunner] =
    AgentEventEmitter.create().map((emitter: AgentEventEmitter) => new AgentRunner(agent, checkpointStore, emitter))
