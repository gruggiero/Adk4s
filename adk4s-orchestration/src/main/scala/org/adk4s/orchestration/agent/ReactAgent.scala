package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.syntax.flatMap.toFlatMapOps
import fs2.Stream
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter }
import org.adk4s.harness.MiddlewareStack
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, StreamedChunk }

/**
 * A ReAct (Reasoning + Acting) agent that loops between LLM generation and tool execution.
 *
 * The agent:
 *   1. Sends messages to the ChatModel
 *   2. If the response contains tool calls, executes them and feeds results back
 *   3. Repeats until no tool calls remain or maxSteps is reached
 *
 * spec: harness-agent — Requirement: ReactAgent.create is source-compatible sugar
 */
trait ReactAgent extends org.adk4s.core.component.Agent:
  def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage]
  def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk]

  /**
   * The backing `HarnessAgent`, when this `ReactAgent` is harness-backed
   * (i.e. constructed via `ReactAgent.create`/`ReactAgent.fromHarness`).
   *
   * `AgentRunner` uses this view (when present) to run at the harness level:
   * on interrupt it snapshots the LIVE `HarnessState` into the checkpoint,
   * and on resume it restores that state before re-entering the loop.
   * Agents not backed by a harness keep the legacy behavior (empty harness
   * state snapshot).
   *
   * spec: harness-agent — Requirement: Interrupt snapshots state without afterAgent
   */
  def harnessView: Option[HarnessAgent[IO]] = None

object ReactAgent:

  final case class Config(
    name: String,
    description: String,
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String],
    maxSteps: Int,
    emitter: Option[AgentEventEmitter] = None
  )

  def create(
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String] = None,
    maxSteps: Int = 10
  ): ReactAgent =
    val config: Config = Config("react-agent", "ReAct agent", model, tools, systemPrompt, maxSteps)
    fromHarnessConfig(config)

  def create(
    name: String,
    description: String,
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String],
    maxSteps: Int
  ): ReactAgent =
    val config: Config = Config(name, description, model, tools, systemPrompt, maxSteps)
    fromHarnessConfig(config)

  def create(
    name: String,
    description: String,
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String],
    maxSteps: Int,
    emitter: AgentEventEmitter
  ): ReactAgent =
    val config: Config = Config(name, description, model, tools, systemPrompt, maxSteps, Some(emitter))
    fromHarnessConfig(config)

  def createWithToolProvider(
    model: ChatModel[IO],
    toolProvider: IO[List[InvokableTool[IO]]],
    systemPrompt: Option[String] = None,
    maxSteps: Int = 10
  ): ReactAgent =
    new DynamicReactAgentImpl(model, toolProvider, systemPrompt, maxSteps)

  /**
   * Wraps an existing `HarnessAgent[IO]` (which may carry a non-empty
   * `MiddlewareStack`) as a `ReactAgent`. This is the path for callers who
   * want middleware behavior AND `AgentRunner` interrupt/resume support:
   * the returned agent exposes the harness via `harnessView`, so
   * `AgentRunner` snapshots and restores the live `HarnessState`.
   *
   * spec: harness-agent — Scenario: A caller passing a non-empty stack MUST use HarnessAgent directly
   */
  def fromHarness(harness: HarnessAgent[IO]): ReactAgent =
    new ReactAgentAdapter(harness)

  /**
   * Constructs a `HarnessAgent[IO]` with `MiddlewareStack.empty` and wraps it
   * in a `ReactAgent` that adapts `HarnessResult` back to `AssistantMessage`.
   *
   * spec: harness-agent — Requirement: ReactAgent.create is source-compatible sugar
   */
  private def fromHarnessConfig(config: Config): ReactAgent =
    val harnessConfig: HarnessAgent.Config[IO] = HarnessAgent.Config(
      name = config.name,
      description = config.description,
      model = config.model,
      stack = MiddlewareStack.empty[IO],
      baseTools = config.tools,
      basePrompt = config.systemPrompt,
      maxSteps = config.maxSteps,
      emitter = config.emitter.map(e => (event: AgentEvent) => e.emit(event))
    )
    val harness: HarnessAgent[IO] = new HarnessAgent[IO](harnessConfig)
    new ReactAgentAdapter(harness)

  /**
   * Wraps a `HarnessAgent[IO]` as a `ReactAgent`, adapting `HarnessResult` to
   * `IO[AssistantMessage]` (raising exceptions on non-Completed outcomes,
   * matching the pre-refactor behavior).
   */
  final private class ReactAgentAdapter(harness: HarnessAgent[IO]) extends ReactAgent:
    val name: String        = harness.name
    val description: String = harness.description

    override def harnessView: Option[HarnessAgent[IO]] = Some(harness)

    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      harness.generate(messages, maxSteps).flatMap {
        case HarnessResult.Completed(assistant, _, _) =>
          IO.pure(assistant)
        case HarnessResult.Interrupted(signal, _, _) =>
          IO.raiseError(AgentInterruptedException(signal))
        case HarnessResult.Failed(error, _, _) =>
          IO.raiseError(error)
      }

    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      harness.stream(messages, maxSteps)

  final private class DynamicReactAgentImpl(
    model: ChatModel[IO],
    toolProvider: IO[List[InvokableTool[IO]]],
    systemPrompt: Option[String],
    defaultMaxSteps: Int
  ) extends ReactAgent:
    val name: String        = "dynamic-react-agent"
    val description: String = "Dynamic ReAct agent with tool provider"

    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      toolProvider.flatMap { (tools: List[InvokableTool[IO]]) =>
        val agent: ReactAgent = ReactAgent.create(model, tools, systemPrompt, math.min(maxSteps, defaultMaxSteps))
        agent.generate(messages, maxSteps)
      }

    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      Stream.eval(toolProvider).flatMap { (tools: List[InvokableTool[IO]]) =>
        val agent: ReactAgent = ReactAgent.create(model, tools, systemPrompt, math.min(maxSteps, defaultMaxSteps))
        agent.stream(messages, maxSteps)
      }
