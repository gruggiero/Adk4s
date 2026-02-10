package org.adk4s.core.component

import cats.effect.IO
import cats.effect.Ref
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.AddressSegment
import org.adk4s.core.interrupt.AgentEvent
import org.adk4s.core.interrupt.AgentEventEmitter
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.interrupt.RunPath
import org.adk4s.core.interrupt.RunStep
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.UserMessage
import upickle.default.*

/** Configuration for AgentTool behavior. */
final case class AgentToolConfig(
  /** NOTE: withFullChatHistory is not yet functional - parent conversation is not passed to AgentTool.run().
    * This flag is reserved for future implementation. Currently all invocations use only the request field. */
  withFullChatHistory: Boolean = false,
  inputSchema: Option[ujson.Value] = None,
  maxSteps: Int = 10
)

object AgentToolConfig:
  val default: AgentToolConfig = AgentToolConfig()

  /** NOTE: This flag is reserved for future implementation. */
  def withFullChatHistory: AgentToolConfig =
    AgentToolConfig(withFullChatHistory = true)

  def withInputSchema(schema: ujson.Value): AgentToolConfig =
    AgentToolConfig(inputSchema = Some(schema))

/** Serializable state for persisting the inner agent's state across interrupt/resume. */
final case class AgentToolState(
  messages: List[SerializableMessage],
  iterationCount: Int
) derives ReadWriter

/** Simplified message representation for serialization. */
final case class SerializableMessage(
  role: String,
  content: String
) derives ReadWriter

object SerializableMessage:
  def fromMessage(msg: Message): SerializableMessage =
    SerializableMessage(role = msg.role.toString, content = msg.content)

  def toMessage(sm: SerializableMessage): Message =
    sm.role.toLowerCase match
      case "user" | "user.user"       => UserMessage(sm.content)
      case "assistant"                 => AssistantMessage(contentOpt = Some(sm.content), toolCalls = Seq.empty)
      case "system"                    => org.llm4s.llmconnect.model.SystemMessage(sm.content)
      case "tool"                      => org.llm4s.llmconnect.model.ToolMessage(sm.content, "")
      case _                           => UserMessage(sm.content) // fallback

/** Wraps an Agent as an InvokableTool[IO], allowing a parent agent to invoke sub-agents via tool calls. */
final class AgentTool private (
  innerAgent: Agent,
  config: AgentToolConfig,
  stateRef: Ref[IO, Option[AgentToolState]],
  emitter: Option[AgentEventEmitter]
) extends InvokableTool[IO]:

  private val defaultInputSchema: ujson.Value = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "request" -> ujson.Obj(
        "type" -> "string",
        "description" -> "The request to send to the agent"
      )
    ),
    "required" -> ujson.Arr("request")
  )

  def info: AdkToolInfo = AdkToolInfo(
    name = innerAgent.name,
    description = innerAgent.description,
    parameters = config.inputSchema.getOrElse(defaultInputSchema)
  )

  def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

  /** Create a new AgentTool with the given emitter (for event forwarding). */
  def withEmitter(newEmitter: AgentEventEmitter): AgentTool =
    new AgentTool(innerAgent, config, stateRef, Some(newEmitter))

  def run(arguments: ujson.Value): IO[ujson.Value] =
    for
      savedState <- stateRef.get
      messages   <- buildMessages(arguments, savedState)
      result     <- executeInnerAgent(messages)
    yield ujson.Str(result)

  private def buildMessages(
    arguments: ujson.Value,
    savedState: Option[AgentToolState]
  ): IO[List[Message]] =
    savedState match
      case Some(state) =>
        // Resume from persisted state
        IO.pure(state.messages.map(SerializableMessage.toMessage))
      case None =>
        if config.withFullChatHistory then
          // Extract messages from arguments (future extension)
          val request: String = extractRequest(arguments)
          IO.pure(List(UserMessage(request)))
        else
          val request: String = extractRequest(arguments)
          IO.pure(List(UserMessage(request)))

  private def extractRequest(arguments: ujson.Value): String =
    arguments match
      case obj: ujson.Obj =>
        obj.obj.get("request") match
          case Some(ujson.Str(s)) => s
          case _                  => upickle.default.write(arguments)
      case ujson.Str(s) => s
      case other        => upickle.default.write(other)

  private def executeInnerAgent(messages: List[Message]): IO[String] =
    val agentStep: RunStep = RunStep(innerAgent.name)
    val scopedEmitter: Option[AgentEventEmitter] = emitter.map(_.scoped(agentStep))

    innerAgent.generate(messages, config.maxSteps).flatMap { (result: AssistantMessage) =>
      // Normal completion — clear persisted state
      stateRef.set(None).as(result.content)
    }.handleErrorWith {
      case interrupted: AgentInterruptedException =>
        // Save inner agent state for later resumption
        val innerState: AgentToolState = AgentToolState(
          messages = messages.map(SerializableMessage.fromMessage),
          iterationCount = 0
        )
        stateRef.set(Some(innerState)) *> {
          // Wrap child interrupt as Composite with AgentTool's own state
          val agentToolStateJson: ujson.Value = upickle.default.writeJs(innerState)
          val compositeSignal: InterruptSignal.Composite = InterruptSignal.Composite(
            address = List(AddressSegment.Agent(innerAgent.name)),
            info = s"AgentTool '${innerAgent.name}' interrupted",
            state = agentToolStateJson,
            children = List(interrupted.signal)
          )
          scopedEmitter.fold(IO.unit)(_.emit(AgentEvent.Interrupted(RunPath.of(innerAgent.name), compositeSignal))) *>
            IO.raiseError(AgentInterruptedException(compositeSignal))
        }
      case other: Throwable =>
        // Non-interrupt errors: clear state and re-raise
        stateRef.set(None) *>
          IO.raiseError(other)
    }

object AgentTool:
  def fromAgent(agent: Agent): IO[AgentTool] =
    fromAgent(agent, AgentToolConfig.default, None)

  def fromAgent(agent: Agent, config: AgentToolConfig): IO[AgentTool] =
    fromAgent(agent, config, None)

  def fromAgent(
    agent: Agent,
    config: AgentToolConfig,
    emitter: Option[AgentEventEmitter]
  ): IO[AgentTool] =
    Ref.of[IO, Option[AgentToolState]](None).map { (ref: Ref[IO, Option[AgentToolState]]) =>
      new AgentTool(agent, config, ref, emitter)
    }

  // Aliases for ReactAgent (spec compatibility)
  def fromReactAgent(agent: Agent): IO[AgentTool] =
    fromAgent(agent)

  def fromReactAgent(agent: Agent, config: AgentToolConfig): IO[AgentTool] =
    fromAgent(agent, config)

  def fromReactAgent(
    agent: Agent,
    config: AgentToolConfig,
    emitter: Option[AgentEventEmitter]
  ): IO[AgentTool] =
    fromAgent(agent, config, emitter)

  /** Create an AgentTool from a function, treating it as a single-step agent. */
  def fromFunction(
    agentName: String,
    agentDescription: String,
    fn: List[Message] => IO[String]
  ): IO[AgentTool] =
    val functionAgent: Agent = new Agent:
      val name: String = agentName
      val description: String = agentDescription
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        fn(messages).map { (result: String) =>
          AssistantMessage(contentOpt = Some(result), toolCalls = Seq.empty)
        }
    fromAgent(functionAgent)
