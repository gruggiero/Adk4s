package org.adk4s.examples.eino.graph

import cats.data.Ior
import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.AdkToolInfo
import org.adk4s.core.component.Tool
import org.adk4s.core.tools.ToolInput
import org.adk4s.core.tools.ToolOutput
import org.adk4s.core.tools.ToolsNode
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.examples.eino.common.MockChatModel
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIOPureNode
import org.adk4s.orchestration.wiograph.WIORunIONode
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.ActiveWorkflow
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCEvent
import workflows4s.wio.WCState
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext
import workflows4s.wio.WCEffect
import workflows4s.wio.WCEffectLift
import workflows4s.wio.internal.WakeupResult

import java.time.Instant
import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/tool_call_agent/tool_call_agent.go
 *
 * Linear graph: ChatTemplate → ChatModel → ToolsNode → END
 * Demonstrates tool execution within a WIOGraph pipeline.
 */
object ToolCallAgentExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait AgentState
    final case class QueryState(query: String) extends AgentState
    final case class ConversationState(conversation: Conversation) extends AgentState
    final case class CompletionState(completion: Completion) extends AgentState
    final case class ToolResultState(query: String, llmResponse: String, toolResult: String) extends AgentState

    sealed trait AgentEvent
    final case class ChatCompleted(completion: Completion) extends AgentEvent
    final case class ToolExecuted(result: String) extends AgentEvent

    override type State = AgentState
    override type Event = AgentEvent

  import Ctx.AgentState
  import Ctx.ChatCompleted
  import Ctx.CompletionState
  import Ctx.ConversationState
  import Ctx.QueryState
  import Ctx.ToolExecuted
  import Ctx.ToolResultState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[ChatCompleted] = scala.reflect.ClassTag(classOf[ChatCompleted])
  private given ClassTag[ToolExecuted] = scala.reflect.ClassTag(classOf[ToolExecuted])

  // Mock tool: a simple calculator
  private val calculatorTool: InvokableTool[IO] = Tool.invokable[IO](
    "calculator",
    "Performs basic arithmetic",
    (args: ujson.Value) =>
      val expression: String = args.obj.get("expression").map(_.str).getOrElse("0")
      Right(ujson.Str(s"Result of '$expression' = 42"))
  )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Tool Call Agent Example (Eino: graph/tool_call_agent)")
      chatModel <- ExampleUtils.createChatModel

      wio <- buildGraph(chatModel) match
        case Left(err: WIOGraphError) =>
          IO.raiseError(new IllegalStateException(s"Graph build failed: $err"))
        case Right(graph: WIOGraph[Ctx.Ctx, QueryState, Nothing, AgentState]) =>
          graph.toWIO match
            case Right(wio) => IO.pure(wio)
            case Left(errors: NonEmptyChain[WIOGraphError]) =>
              IO.raiseError(new IllegalStateException(errors.toNonEmptyList.toList.mkString(", ")))

      input = QueryState("What is 6 * 7?")
      result <- executeWio(wio, input)

      _ <- result match
        case output: ToolResultState =>
          IO.println(s"   Query: ${output.query}") *>
            IO.println(s"   LLM Response: ${output.llmResponse}") *>
            IO.println(s"   Tool Result: ${output.toolResult}")
        case other =>
          IO.println(s"   Final state: $other")

      _ <- IO.println("\nTool call agent example completed.")
    yield ()

  private def buildGraph(chatModel: ChatModel[IO]): Either[WIOGraphError, WIOGraph[Ctx.Ctx, QueryState, Nothing, AgentState]] =
    val templateRef: WIONodeRef[Ctx.Ctx, QueryState, ConversationState] =
      WIONodeRef[Ctx.Ctx, QueryState, ConversationState](NodeKey.unsafeApply("template"))
    val chatRef: WIONodeRef[Ctx.Ctx, ConversationState, CompletionState] =
      WIONodeRef[Ctx.Ctx, ConversationState, CompletionState](NodeKey.unsafeApply("chat"))
    val toolRef: WIONodeRef[Ctx.Ctx, CompletionState, ToolResultState] =
      WIONodeRef[Ctx.Ctx, CompletionState, ToolResultState](NodeKey.unsafeApply("tool"))
    val endRef: WIONodeRef[Ctx.Ctx, CompletionState, AgentState] =
      WIONodeRef[Ctx.Ctx, CompletionState, AgentState](NodeKey.unsafeApply("tool"))

    val templateNode: WIOPureNode[Ctx.Ctx, QueryState, Nothing, ConversationState] =
      WIONode.pure[Ctx.Ctx, QueryState, ConversationState]((input: QueryState) =>
        ConversationState(Conversation(Seq(
          SystemMessage("You are a helpful assistant with access to a calculator tool."),
          UserMessage(input.query)
        )))
      )

    val chatNode: WIORunIONode[Ctx.Ctx, ConversationState, Nothing, ChatCompleted, CompletionState] =
      WIONode.runIO[Ctx.Ctx, ConversationState, ChatCompleted, CompletionState](
        runIO = (state: ConversationState) =>
          chatModel.generate(state.conversation).map((c: Completion) => ChatCompleted(c)),
        handleEvent = (_: ConversationState, evt: ChatCompleted) =>
          CompletionState(evt.completion)
      )

    val toolNode: WIORunIONode[Ctx.Ctx, CompletionState, Nothing, ToolExecuted, ToolResultState] =
      WIONode.runIO[Ctx.Ctx, CompletionState, ToolExecuted, ToolResultState](
        runIO = (state: CompletionState) =>
          val toolInput: ToolInput = ToolInput("calculator", """{"expression": "6 * 7"}""", "call-1")
          val toolsNode: ToolsNode = ToolsNode.fromAdkTools(List(calculatorTool))
          toolsNode.executeTool(toolInput).map((output: ToolOutput) => ToolExecuted(output.result)),
        handleEvent = (state: CompletionState, evt: ToolExecuted) =>
          ToolResultState(
            query = "What is 6 * 7?",
            llmResponse = state.completion.content,
            toolResult = evt.result
          )
      )

    for
      g1 <- WIOGraph[Ctx.Ctx, QueryState, AgentState].addNode("template", templateNode)
      g2 <- g1.addNode("chat", chatNode)
      g3 <- g2.addNode("tool", toolNode)
      g4 <- g3.addEdge(templateRef, chatRef)
      g5 <- g4.addEdge(chatRef, toolRef)
      g6 <- g5.setEntry(templateRef)
      g7 <- g6.addEndNode(endRef)
    yield g7

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def executeWio(
    wio: WIO[QueryState, Nothing, AgentState, Ctx.Ctx],
    input: QueryState
  ): IO[AgentState] =
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("tool-call-agent", "tool-call-agent")
    val start: ActiveWorkflow[Ctx.Ctx] =
      ActiveWorkflow[Ctx.Ctx](workflowId, wio.provideInput(input), input)

    def proceedOnce(
      workflow: ActiveWorkflow[Ctx.Ctx]
    ): IO[(ActiveWorkflow[Ctx.Ctx], Boolean)] =
      val liftEffect: WCEffectLift[Ctx.Ctx, IO] = [A] => (fa: WCEffect[Ctx.Ctx][A]) => fa.asInstanceOf[IO[A]]
      val wakeup: WakeupResult[IO, WCEvent[Ctx.Ctx]] = workflow.proceed(Instant.EPOCH, liftEffect)
      wakeup match
        case WakeupResult.Noop() => IO.pure((workflow, false))
        case WakeupResult.Processed(io) =>
          io.asInstanceOf[IO[Ior[Instant, WCEvent[Ctx.Ctx]]]].map { (result: Ior[Instant, WCEvent[Ctx.Ctx]]) =>
            val eventOpt: Option[WCEvent[Ctx.Ctx]] = result match
              case Ior.Right(event) => Some(event)
              case Ior.Both(_, event) => Some(event)
              case Ior.Left(_) => None
            eventOpt match
              case Some(event) =>
                (workflow.handleEvent(event).getOrElse(workflow), true)
              case None => (workflow, false)
          }

    def loop(workflow: ActiveWorkflow[Ctx.Ctx]): IO[ActiveWorkflow[Ctx.Ctx]] =
      proceedOnce(workflow).flatMap { case (next, continued) =>
        if continued then loop(next) else IO.pure(next)
      }

    loop(start).map(_.liveState)
