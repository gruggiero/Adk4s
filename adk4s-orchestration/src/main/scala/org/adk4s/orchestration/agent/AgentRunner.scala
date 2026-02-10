package org.adk4s.orchestration.agent

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.error.{AdkError, AgentInterruptedException, CheckpointNotFoundError}
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, InterruptResult, RunPath, RunStep}
import org.adk4s.orchestration.interrupt.CheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Message, UserMessage}
import upickle.default.*

import java.util.UUID

/** Serializable checkpoint state for persisting across interrupt/resume. */
private[agent] final case class CheckpointState(
  messages: List[SerializableCheckpointMessage],
  interruptSignalJson: String,
  agentName: String
) derives ReadWriter

private[agent] final case class SerializableCheckpointMessage(
  role: String,
  content: String
) derives ReadWriter

/** Executes an agent with interrupt/resume and event streaming capabilities. */
final class AgentRunner(
  agent: ReactAgent,
  checkpointStore: CheckpointStore,
  emitter: AgentEventEmitter
):

  /** Run the agent to completion or until an interrupt is raised. */
  def run(messages: List[Message], maxSteps: Int = 10): IO[RunResult] =
    val agentStep: RunStep = RunStep(agent.name)
    val scopedEmitter: AgentEventEmitter = emitter.scoped(agentStep)

    agent.generate(messages, maxSteps).flatMap { (result: AssistantMessage) =>
      scopedEmitter.emit(AgentEvent.MessageOutput(
        runPath = RunPath.of(agent.name),
        message = result.content,
        role = "assistant"
      )) *>
      IO.pure(RunResult.Completed(result.content, messages))
    }.handleErrorWith {
      case interrupted: AgentInterruptedException =>
        val checkpointId: String = UUID.randomUUID().toString
        val state: CheckpointState = CheckpointState(
          messages = messages.map(m => SerializableCheckpointMessage(m.role.toString, m.content)),
          interruptSignalJson = upickle.default.write(interrupted.signal),
          agentName = agent.name
        )
        val serialized: Array[Byte] = upickle.default.write(state).getBytes("UTF-8")
        checkpointStore.set(checkpointId, serialized) *>
          scopedEmitter.emit(AgentEvent.Interrupted(RunPath.of(agent.name), interrupted.signal)) *>
          IO.pure(RunResult.Interrupted(checkpointId, interrupted.signal))
      case adkError: AdkError =>
        scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), adkError)) *>
          IO.pure(RunResult.Failed(adkError))
      case other: Throwable =>
        val wrapped: AdkError = org.adk4s.core.error.GenericError(other.getMessage)
        scopedEmitter.emit(AgentEvent.ErrorOccurred(RunPath.of(agent.name), wrapped)) *>
          IO.pure(RunResult.Failed(wrapped))
    }

  /** Resume an agent from a checkpoint with the provided interrupt results.
    *
    * NOTE: Current implementation appends resume data as user messages rather than
    * implementing full hierarchical address-based routing to nested AgentTools.
    * This is sufficient for simple interrupts but does not support complex composite
    * interrupts with multiple nested agents. Full routing would require:
    * 1. Matching InterruptResult addresses to the agent/tool hierarchy
    * 2. Injecting resume data at the correct level (e.g., into a specific AgentTool's state)
    * 3. Reconstructing the nested execution context for each level
    */
  def resume(checkpointId: String, results: List[InterruptResult], maxSteps: Int = 10): IO[RunResult] =
    checkpointStore.get(checkpointId).flatMap {
      case None =>
        IO.pure(RunResult.Failed(CheckpointNotFoundError(checkpointId)))
      case Some(data) =>
        val state: CheckpointState = upickle.default.read[CheckpointState](new String(data, "UTF-8"))
        // Reconstruct original messages preserving message types
        val originalMessages: List[Message] = state.messages.map { (m: SerializableCheckpointMessage) =>
          m.role.toLowerCase match
            case "user" | "user.user" => UserMessage(m.content)
            case "assistant"           => org.llm4s.llmconnect.model.AssistantMessage(contentOpt = Some(m.content), toolCalls = Seq.empty)
            case "system"              => org.llm4s.llmconnect.model.SystemMessage(m.content)
            case "tool"                => org.llm4s.llmconnect.model.ToolMessage(m.content, "")
            case _                     => UserMessage(m.content): Message
        }
        // Append resume data as user messages with address context
        // TODO: Implement hierarchical routing to target specific nested agents
        val resumeMessages: List[Message] = results.map { (r: InterruptResult) =>
          val addressPath: String = r.address.map(_.name).mkString(" > ")
          val dataJson: String = upickle.default.write(r.data)
          UserMessage(s"[Resume approval for $addressPath]: $dataJson"): Message
        }
        val allMessages: List[Message] = originalMessages ++ resumeMessages
        run(allMessages, maxSteps).flatMap { (result: RunResult) =>
          result match
            case _: RunResult.Completed =>
              // Clean up checkpoint on successful completion
              checkpointStore.delete(checkpointId).as(result)
            case _ =>
              IO.pure(result)
        }
    }

  /** Run the agent and return both the result and an event stream. */
  def runWithEvents(messages: List[Message], maxSteps: Int = 10): (IO[RunResult], Stream[IO, AgentEvent]) =
    val result: IO[RunResult] = run(messages, maxSteps).flatMap { (r: RunResult) =>
      emitter.complete.as(r)
    }
    val events: Stream[IO, AgentEvent] = emitter.subscribe
    (result, events)

object AgentRunner:
  def create(
    agent: ReactAgent,
    checkpointStore: CheckpointStore,
    emitter: AgentEventEmitter
  ): AgentRunner =
    new AgentRunner(agent, checkpointStore, emitter)

  def create(
    agent: ReactAgent,
    checkpointStore: CheckpointStore
  ): IO[AgentRunner] =
    AgentEventEmitter.create().map { (emitter: AgentEventEmitter) =>
      new AgentRunner(agent, checkpointStore, emitter)
    }
