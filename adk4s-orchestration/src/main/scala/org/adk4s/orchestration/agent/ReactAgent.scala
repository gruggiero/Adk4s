package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, RunPath, RunStep}
import org.adk4s.core.tools.ToolsNode
import org.adk4s.core.tools.ToolsNodeConfig
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.toolapi.ToolFunction

/**
 * A ReAct (Reasoning + Acting) agent that loops between LLM generation and tool execution.
 *
 * The agent:
 *   1. Sends messages to the ChatModel
 *   2. If the response contains tool calls, executes them and feeds results back
 *   3. Repeats until no tool calls remain or maxSteps is reached
 */
trait ReactAgent extends org.adk4s.core.component.Agent:
  def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage]
  def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk]

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
    new ReactAgentImpl(config)

  def create(
    name: String,
    description: String,
    model: ChatModel[IO],
    tools: List[InvokableTool[IO]],
    systemPrompt: Option[String],
    maxSteps: Int
  ): ReactAgent =
    val config: Config = Config(name, description, model, tools, systemPrompt, maxSteps)
    new ReactAgentImpl(config)

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
    new ReactAgentImpl(config)

  def createWithToolProvider(
    model: ChatModel[IO],
    toolProvider: IO[List[InvokableTool[IO]]],
    systemPrompt: Option[String] = None,
    maxSteps: Int = 10
  ): ReactAgent =
    new DynamicReactAgentImpl(model, toolProvider, systemPrompt, maxSteps)

  private def buildToolsNode(tools: List[InvokableTool[IO]], emitter: Option[AgentEventEmitter]): ToolsNode =
    emitter match
      case Some(e) =>
        val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(tools).copy(eventEmitter = Some(e))
        ToolsNode(config)
      case None =>
        ToolsNode.fromAdkTools(tools)

  private def buildToolFunctions(tools: List[InvokableTool[IO]]): Seq[ToolFunction[?, ?]] =
    tools.map { (tool: InvokableTool[IO]) =>
      tool.asToolFunction.getOrElse(tool.info.toToolFunction)
    }

  private def buildConversation(
    systemPrompt: Option[String],
    messages: List[Message]
  ): Conversation =
    val systemMessages: List[Message] = systemPrompt match
      case Some(prompt) =>
        val alreadyHasSystem: Boolean = messages.exists {
          case _: SystemMessage => true
          case _                => false
        }
        if alreadyHasSystem then List.empty
        else List(SystemMessage(prompt))
      case None => List.empty
    Conversation(systemMessages ++ messages)

  private def buildCompletionOptions(tools: List[InvokableTool[IO]]): CompletionOptions =
    val toolFunctions: Seq[ToolFunction[?, ?]] = buildToolFunctions(tools)
    CompletionOptions(tools = toolFunctions)

  private final class ReactAgentImpl(config: Config) extends ReactAgent:
    val name: String = config.name
    val description: String = config.description
    private val toolsNode: ToolsNode = buildToolsNode(config.tools, config.emitter)
    private val completionOptions: CompletionOptions = buildCompletionOptions(config.tools)

    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      val effectiveMaxSteps: Int = math.min(maxSteps, config.maxSteps)
      val conversation: Conversation = buildConversation(config.systemPrompt, messages)
      generateLoop(conversation, effectiveMaxSteps)

    def stream(messages: List[Message], maxSteps: Int): Stream[IO, StreamedChunk] =
      val effectiveMaxSteps: Int = math.min(maxSteps, config.maxSteps)
      val conversation: Conversation = buildConversation(config.systemPrompt, messages)
      // Execute tool loops via generate, then stream the final response
      Stream.eval(resolveToolLoops(conversation, effectiveMaxSteps)).flatMap { (finalConversation: Conversation) =>
        config.model.stream(finalConversation, completionOptions)
      }

    private val totalMaxSteps: Int = config.maxSteps

    private def emitEvent(event: AgentEvent): IO[Unit] =
      config.emitter.fold(IO.unit)(_.emit(event))

    private def generateLoop(conversation: Conversation, remainingSteps: Int): IO[AssistantMessage] =
      if remainingSteps <= 0 then
        IO.raiseError(new RuntimeException("ReactAgent: max steps exceeded"))
      else
        config.model.generate(conversation, completionOptions).flatMap { (completion: Completion) =>
          val assistantMsg: AssistantMessage = completion.message
          val currentIteration: Int = totalMaxSteps - remainingSteps + 1
          if assistantMsg.toolCalls.isEmpty then
            emitEvent(AgentEvent.MessageOutput(
              runPath = RunPath.empty,
              message = assistantMsg.content,
              role = "assistant"
            )) *>
            emitEvent(AgentEvent.IterationCompleted(
              runPath = RunPath.empty,
              iteration = currentIteration,
              remainingSteps = remainingSteps - 1
            )) *>
            IO.pure(assistantMsg)
          else
            val toolCalls: List[ToolCall] = assistantMsg.toolCalls.toList
            // Emit ToolCallRequested for each tool call
            val emitRequested: IO[Unit] = toolCalls.traverse_ { (tc: ToolCall) =>
              emitEvent(AgentEvent.ToolCallRequested(
                runPath = RunPath.empty,
                toolName = tc.name,
                arguments = tc.arguments.toString,
                callId = tc.id
              ))
            }
            emitRequested *>
            executeToolCalls(toolCalls).flatMap { (toolMessages: List[ToolMessage]) =>
              // Emit ToolCallCompleted for each tool result
              val emitCompleted: IO[Unit] = toolCalls.zip(toolMessages).traverse_ { case (tc: ToolCall, tm: ToolMessage) =>
                emitEvent(AgentEvent.ToolCallCompleted(
                  runPath = RunPath.empty,
                  toolName = tc.name,
                  result = tm.content,
                  callId = tc.id,
                  isError = false
                ))
              }
              emitCompleted *>
              emitEvent(AgentEvent.IterationCompleted(
                runPath = RunPath.empty,
                iteration = currentIteration,
                remainingSteps = remainingSteps - 1
              )) *> {
                val updatedConversation: Conversation = Conversation(
                  conversation.messages ++ Seq(assistantMsg) ++ toolMessages
                )
                generateLoop(updatedConversation, remainingSteps - 1)
              }
            }
        }

    private def resolveToolLoops(conversation: Conversation, remainingSteps: Int): IO[Conversation] =
      if remainingSteps <= 0 then
        IO.pure(conversation)
      else
        config.model.generate(conversation, completionOptions).flatMap { (completion: Completion) =>
          val assistantMsg: AssistantMessage = completion.message
          if assistantMsg.toolCalls.isEmpty then
            IO.pure(conversation)
          else
            executeToolCalls(assistantMsg.toolCalls.toList).flatMap { (toolMessages: List[ToolMessage]) =>
              val updatedConversation: Conversation = Conversation(
                conversation.messages ++ Seq(assistantMsg) ++ toolMessages
              )
              resolveToolLoops(updatedConversation, remainingSteps - 1)
            }
        }

    private def executeToolCalls(toolCalls: List[ToolCall]): IO[List[ToolMessage]] =
      toolsNode.executeFromToolCalls(toolCalls).flatMap { result =>
        result.interruptSignal match
          case Some(signal) =>
            IO.raiseError(AgentInterruptedException(signal))
          case None =>
            IO.pure(result.toLlm4sMessages())
      }

  private final class DynamicReactAgentImpl(
    model: ChatModel[IO],
    toolProvider: IO[List[InvokableTool[IO]]],
    systemPrompt: Option[String],
    defaultMaxSteps: Int
  ) extends ReactAgent:
    val name: String = "dynamic-react-agent"
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
