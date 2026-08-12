package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.applicativeError.catsSyntaxApplicativeError
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.foldable.toFoldableOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.{ ChatModel, InvokableTool }
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter, RunPath }
import org.adk4s.core.tools.{ ToolsNode, ToolsNodeConfig }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  SystemMessage,
  ToolCall,
  ToolMessage
}
import org.llm4s.toolapi.ToolFunction

/**
 * TEST-ONLY differential comparison target for the L0 gatekeeper.
 *
 * Faithful restoration of the pre-refactor ReAct loop (the private
 * `ReactAgentImpl` deleted in commit 448a4518, restored from baseline
 * 827a0c1). The control flow, event emission, tool execution (via
 * `ToolsNode`), conversation assembly, and max-steps / interrupt error
 * channels are reproduced exactly. The ONLY changes are test-observability
 * probes, which do not alter behavior:
 *
 *   1. `generate` returns the final message list alongside the final
 *      `AssistantMessage` (the pre-refactor return type exposed only the
 *      assistant message; the message list was internal).
 *   2. `emitFromToolsNode` toggles whether the emitter is injected into
 *      `ToolsNode` as the pre-refactor code did. With `true` (faithful),
 *      every tool event is emitted TWICE: once by the loop and once by
 *      `ToolsNode` — a pre-existing double-emission quirk of the old
 *      implementation. The harness-agent spec pins event emission in the
 *      loop ("middlewares SHALL observe these via wrapping; the loop owns
 *      the observability channel"), so the differential event comparison
 *      runs with `false` (loop-level sequence) and the divergence is
 *      documented by a dedicated test.
 *
 * spec: harness-agent — Property: empty-stack-equivalence (L0 gatekeeper)
 */
final class LegacyReactAgent(
  val name: String,
  val description: String,
  model: ChatModel[IO],
  tools: List[InvokableTool[IO]],
  systemPrompt: Option[String],
  maxSteps: Int,
  emitter: Option[AgentEventEmitter] = None,
  emitFromToolsNode: Boolean = true
):

  private val toolsNode: ToolsNode =
    val toolsNodeEmitter: Option[AgentEventEmitter] =
      if emitFromToolsNode then emitter else None
    toolsNodeEmitter match
      case Some(e) =>
        val config: ToolsNodeConfig = ToolsNodeConfig.fromAdkTools(tools).copy(eventEmitter = Some(e))
        ToolsNode(config)
      case None =>
        ToolsNode.fromAdkTools(tools)

  private val completionOptions: CompletionOptions =
    val toolFunctions: Seq[ToolFunction[?, ?]] =
      tools.map((tool: InvokableTool[IO]) => tool.asToolFunction.getOrElse(tool.info.toToolFunction))
    CompletionOptions(tools = toolFunctions)

  private val totalMaxSteps: Int = maxSteps

  def generate(messages: List[Message], maxStepsArg: Int): IO[(AssistantMessage, List[Message])] =
    val effectiveMaxSteps: Int     = math.min(maxStepsArg, maxSteps)
    val conversation: Conversation = buildConversation(systemPrompt, messages)
    generateLoop(conversation, effectiveMaxSteps)

  private def emitEvent(event: AgentEvent): IO[Unit] =
    emitter.fold(IO.unit)((e: AgentEventEmitter) => e.emit(event))

  private def generateLoop(
    conversation: Conversation,
    remainingSteps: Int
  ): IO[(AssistantMessage, List[Message])] =
    if remainingSteps <= 0 then IO.raiseError(new RuntimeException("ReactAgent: max steps exceeded"))
    else
      model.generate(conversation, completionOptions).flatMap { (completion: Completion) =>
        val assistantMsg: AssistantMessage = completion.message
        val currentIteration: Int          = totalMaxSteps - remainingSteps + 1
        if assistantMsg.toolCalls.isEmpty then
          emitEvent(
            AgentEvent.MessageOutput(
              runPath = RunPath.empty,
              message = assistantMsg.content,
              role = "assistant"
            )
          ) *>
            emitEvent(
              AgentEvent.IterationCompleted(
                runPath = RunPath.empty,
                iteration = currentIteration,
                remainingSteps = remainingSteps - 1
              )
            ) *>
            IO.pure((assistantMsg, conversation.messages.toList :+ assistantMsg))
        else
          val toolCalls: List[ToolCall] = assistantMsg.toolCalls.toList
          val emitRequested: IO[Unit] = toolCalls.traverse_ { (tc: ToolCall) =>
            emitEvent(
              AgentEvent.ToolCallRequested(
                runPath = RunPath.empty,
                toolName = tc.name,
                arguments = tc.arguments.toString,
                callId = tc.id
              )
            )
          }
          emitRequested *>
            executeToolCalls(toolCalls).flatMap { (toolMessages: List[ToolMessage]) =>
              val emitCompleted: IO[Unit] =
                toolCalls.zip(toolMessages).traverse_ { case (tc: ToolCall, tm: ToolMessage) =>
                  emitEvent(
                    AgentEvent.ToolCallCompleted(
                      runPath = RunPath.empty,
                      toolName = tc.name,
                      result = tm.content,
                      callId = tc.id,
                      isError = false
                    )
                  )
                }
              emitCompleted *>
                emitEvent(
                  AgentEvent.IterationCompleted(
                    runPath = RunPath.empty,
                    iteration = currentIteration,
                    remainingSteps = remainingSteps - 1
                  )
                ) *> {
                  val updatedConversation: Conversation = Conversation(
                    conversation.messages ++ Seq(assistantMsg) ++ toolMessages
                  )
                  generateLoop(updatedConversation, remainingSteps - 1)
                }
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

  private def buildConversation(
    sysPrompt: Option[String],
    messages: List[Message]
  ): Conversation =
    val systemMessages: List[Message] = sysPrompt match
      case Some(prompt) =>
        val alreadyHasSystem: Boolean = messages.exists {
          case _: SystemMessage => true
          case _                => false
        }
        if alreadyHasSystem then List.empty
        else List(SystemMessage(prompt))
      case None => List.empty
    Conversation(systemMessages ++ messages)
