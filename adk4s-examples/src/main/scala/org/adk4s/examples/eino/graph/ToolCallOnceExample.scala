package org.adk4s.examples.eino.graph

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.tools.ToolInput
import org.adk4s.core.tools.ToolOutput
import org.adk4s.core.tools.ToolsNode
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOForkNode
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIOPureNode
import org.adk4s.orchestration.wiograph.WIORunnableNode
import org.adk4s.orchestration.wiograph.WIORunIONode
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCState
import workflows4s.wio.WIO
import workflows4s.wio.WorkflowContext

import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/tool_call_once/tool_call_once.go
 *
 * Graph: ChatTemplate → ChatModel → Branch(hasToolCalls?) → ToolsNode → END
 *                                                         → END (no tools)
 *
 * Demonstrates branching based on whether the model response contains tool calls.
 * Uses WIOForkNode to route between tool execution and direct output.
 */
object ToolCallOnceExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class InputState(query: String) extends GraphState
    final case class ConversationState(conversation: Conversation) extends GraphState
    final case class CompletionState(completion: Completion, query: String) extends GraphState
    final case class ToolResultState(query: String, toolResult: String) extends GraphState
    final case class OutputState(query: String, response: String) extends GraphState

    sealed trait GraphEvent
    final case class ChatCompleted(completion: Completion) extends GraphEvent
    final case class ToolExecuted(result: String) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.ChatCompleted
  import Ctx.CompletionState
  import Ctx.ConversationState
  import Ctx.GraphEvent
  import Ctx.GraphState
  import Ctx.InputState
  import Ctx.OutputState
  import Ctx.ToolExecuted
  import Ctx.ToolResultState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[ChatCompleted] = scala.reflect.ClassTag(classOf[ChatCompleted])
  private given ClassTag[ToolExecuted] = scala.reflect.ClassTag(classOf[ToolExecuted])

  // Mock tool: user_info lookup
  private val userInfoTool: InvokableTool[IO] = Tool.invokable[IO](
    "user_info",
    "Look up user info by name and email",
    (args: ujson.Value) =>
      val name: String = args.obj.get("name").map(_.str).getOrElse("unknown")
      val email: String = args.obj.get("email").map(_.str).getOrElse("unknown")
      Right(ujson.Obj(
        "name" -> name,
        "email" -> email,
        "company" -> "Awesome company",
        "position" -> "CEO",
        "salary" -> "9999"
      ))
  )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Tool Call Once Example (Eino: graph/tool_call_once)")
      chatModel <- ExampleUtils.createChatModel

      // Example 1: Query that triggers tool call
      _ <- ExampleUtils.printSubSection("1. Query with Tool Call")
      result1 <- executeGraph(chatModel, "I'm zhangsan, email zhangsan@example.com, recommend a property")
      _ <- result1 match
        case output: OutputState =>
          IO.println(s"   Query: ${output.query}") *>
            IO.println(s"   Response: ${output.response}")
        case toolResult: ToolResultState =>
          IO.println(s"   Query: ${toolResult.query}") *>
            IO.println(s"   Tool result: ${toolResult.toolResult}")
        case other =>
          IO.println(s"   Final state: $other")

      // Example 2: Simple query (no tool call)
      _ <- ExampleUtils.printSubSection("2. Simple Query (No Tool Call)")
      result2 <- executeGraph(chatModel, "What is the weather today?")
      _ <- result2 match
        case output: OutputState =>
          IO.println(s"   Query: ${output.query}") *>
            IO.println(s"   Response: ${output.response}")
        case other =>
          IO.println(s"   Final state: $other")

      _ <- IO.println("\nTool call once example completed.")
    yield ()

  private def executeGraph(chatModel: ChatModel[IO], query: String): IO[GraphState] =
    buildGraph(chatModel) match
      case Left(err: WIOGraphError) =>
        IO.raiseError(new IllegalStateException(s"Graph build failed: $err"))
      case Right(graph: WIOGraph[Ctx.Ctx, InputState, Nothing, GraphState]) =>
        graph.toRunnable match
          case Right(runnable) => runnable.invoke(InputState(query))
          case Left(errors) =>
            IO.raiseError(new IllegalStateException(
              s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
            ))

  private def buildGraph(chatModel: ChatModel[IO]): Either[WIOGraphError, WIOGraph[Ctx.Ctx, InputState, Nothing, GraphState]] =
    val templateRef: WIONodeRef[Ctx.Ctx, InputState, ConversationState] =
      WIONodeRef[Ctx.Ctx, InputState, ConversationState](NodeKey.unsafeApply("template"))
    val chatRef: WIONodeRef[Ctx.Ctx, ConversationState, CompletionState] =
      WIONodeRef[Ctx.Ctx, ConversationState, CompletionState](NodeKey.unsafeApply("chat"))
    val branchRef: WIONodeRef[Ctx.Ctx, CompletionState, GraphState] =
      WIONodeRef[Ctx.Ctx, CompletionState, GraphState](NodeKey.unsafeApply("branch"))
    val endRef: WIONodeRef[Ctx.Ctx, CompletionState, GraphState] =
      WIONodeRef[Ctx.Ctx, CompletionState, GraphState](NodeKey.unsafeApply("branch"))

    // Template node: build conversation from query
    val templateNode: WIOPureNode[Ctx.Ctx, InputState, Nothing, ConversationState] =
      WIONode.pure[Ctx.Ctx, InputState, ConversationState]((input: InputState) =>
        ConversationState(Conversation(Seq(
          SystemMessage("You are a real estate agent. Use the user_info API to look up user details. Email is required."),
          UserMessage(input.query)
        )))
      )

    // Chat node: call the model
    val chatNode: WIORunIONode[Ctx.Ctx, ConversationState, Nothing, ChatCompleted, CompletionState] =
      WIONode.runIO[Ctx.Ctx, ConversationState, ChatCompleted, CompletionState](
        runIO = (state: ConversationState) =>
          chatModel.generate(state.conversation).map((c: Completion) => ChatCompleted(c)),
        handleEvent = (state: ConversationState, evt: ChatCompleted) =>
          CompletionState(evt.completion, state.conversation.messages.collect {
            case msg: UserMessage => msg.content
          }.lastOption.getOrElse(""))
      )

    // Branch node: check if the query contains an email (simulating tool call detection).
    // In a real scenario, this would inspect tool_calls in the completion.
    // The tool branch executes the tool inline and produces ToolResultState.
    // The no-tool branch directly produces OutputState from the completion.
    val hasToolCall: CompletionState => Boolean = (state: CompletionState) =>
      state.query.contains("@")

    val toolBranchWio: WIO[CompletionState, Nothing, GraphState, Ctx.Ctx] =
      WIONode.runIO[Ctx.Ctx, CompletionState, ToolExecuted, ToolResultState](
        runIO = (state: CompletionState) =>
          val toolInput: ToolInput = ToolInput(
            "user_info",
            """{"name": "zhangsan", "email": "zhangsan@example.com"}""",
            "call-1"
          )
          val toolsNode: ToolsNode = ToolsNode.fromAdkTools(List(userInfoTool))
          toolsNode.executeTool(toolInput).map((output: ToolOutput) => ToolExecuted(output.result)),
        handleEvent = (state: CompletionState, evt: ToolExecuted) =>
          ToolResultState(state.query, evt.result)
      ).toWIO

    val endBranchWio: WIO[CompletionState, Nothing, GraphState, Ctx.Ctx] =
      WIONode.pure[Ctx.Ctx, CompletionState, OutputState]((state: CompletionState) =>
        OutputState(state.query, state.completion.content)
      ).toWIO

    val branchNode: WIOForkNode[Ctx.Ctx, CompletionState, Nothing, GraphState] =
      WIOForkNode.binaryFork[Ctx.Ctx, CompletionState, Nothing, GraphState](
        condition = hasToolCall,
        ifTrue = toolBranchWio,
        ifFalse = endBranchWio
      )

    for
      g1 <- WIOGraph[Ctx.Ctx, InputState, GraphState].addNode("template", templateNode)
      g2 <- g1.addNode("chat", chatNode)
      g3 <- g2.addNode("branch", branchNode)
      g4 <- g3.addEdge(templateRef, chatRef)
      g5 <- g4.addEdge(chatRef, branchRef)
      g6 <- g5.setEntry(templateRef)
      g7 <- g6.addEndNode(endRef)
    yield g7
