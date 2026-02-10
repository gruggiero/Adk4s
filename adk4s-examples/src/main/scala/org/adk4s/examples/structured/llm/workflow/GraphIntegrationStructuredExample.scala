package org.adk4s.examples.structured.llm.workflow

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIORunnableNode
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{CategoryClassification, RoleDetection}
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WorkflowContext

import scala.reflect.ClassTag

/**
 * Demonstrates StructuredLLM integration within WIOGraph nodes.
 *
 * Shows how to:
 * - Use StructuredLLM inside graph nodes
 * - Chain multiple StructuredLLM nodes with different schemas
 * - Build orchestrated workflows with type-safe LLM parsing
 * - Compile graphs to Runnable for invoke/stream execution
 *
 * Graph: classify → detect_role → END
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object GraphIntegrationStructuredExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class QueryState(text: String, category: Option[String] = None, role: Option[String] = None) extends GraphState

    sealed trait GraphEvent
    final case class CategoryDetected(category: String, confidence: Double) extends GraphEvent
    final case class RoleDetected(role: String, confidence: Double) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.GraphState
  import Ctx.CategoryDetected
  import Ctx.QueryState
  import Ctx.RoleDetected

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[CategoryDetected] = scala.reflect.ClassTag(classOf[CategoryDetected])
  private given ClassTag[RoleDetected] = scala.reflect.ClassTag(classOf[RoleDetected])

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[CategoryClassification] = Schema.instance(
    """structure CategoryClassification {
      |  @required
      |  category: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[CategoryClassification]])

  given Schema[RoleDetection] = Schema.instance(
    """structure RoleDetection {
      |  @required
      |  role: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[RoleDetection]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new GraphIntegrationMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Graph Integration (Structured)")
      llmClient <- createLLMClient

      graph = buildGraph(llmClient)

      _ <- ExampleUtils.printSubSection("Execute Graph Workflow")
      runnable <- compileRunnable(graph)

      // Execute the workflow
      query: String = "I need help resetting my password for the admin dashboard"
      _ <- IO.println(s"   Query: $query\n")

      result <- runnable.invoke(QueryState(query))
      _ <- result match
        case state: QueryState =>
          IO.println(s"   Category: ${state.category.getOrElse("unknown")}") *>
          IO.println(s"   Role: ${state.role.getOrElse("unknown")}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      _ <- IO.println("\nGraph integration example completed.")
    yield ()

  private def buildGraph(llmClient: org.llm4s.llmconnect.LLMClient): WIOGraph[Ctx.Ctx, QueryState, Nothing, GraphState] =
    val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](llmClient)

    // Node 1: Classify query category
    val classifyRunnable: Runnable[QueryState, CategoryClassification] =
      Runnable.fromInvoke[QueryState, CategoryClassification]((input: QueryState) =>
        val prompt: Prompt = Prompt.simple(
          "You are a query classifier. Classify the query into a category (technical, account, billing).",
          s"Query: ${input.text}"
        )
        structured.complete[CategoryClassification](prompt)
      )

    // Node 2: Detect user role
    val roleRunnable: Runnable[QueryState, RoleDetection] =
      Runnable.fromInvoke[QueryState, RoleDetection]((input: QueryState) =>
        val prompt: Prompt = Prompt.simple(
          "You are a role detector. Detect the user role (customer, support, manager) from the query context.",
          s"Query: ${input.text}"
        )
        structured.complete[RoleDetection](prompt)
      )

    val node1Ref: WIONodeRef[Ctx.Ctx, QueryState, QueryState] =
      WIONodeRef[Ctx.Ctx, QueryState, QueryState](NodeKey.unsafeApply("classify"))
    val node2Ref: WIONodeRef[Ctx.Ctx, QueryState, QueryState] =
      WIONodeRef[Ctx.Ctx, QueryState, QueryState](NodeKey.unsafeApply("detect_role"))
    val endRef: WIONodeRef[Ctx.Ctx, QueryState, GraphState] =
      WIONodeRef[Ctx.Ctx, QueryState, GraphState](NodeKey.unsafeApply("detect_role"))

    val node1: WIORunnableNode[Ctx.Ctx, QueryState, Nothing, CategoryDetected, CategoryClassification, QueryState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, QueryState, CategoryDetected, CategoryClassification, QueryState](
        runnable = classifyRunnable,
        toEvent = (result: CategoryClassification) => CategoryDetected(result.category, result.confidence),
        toState = (state: QueryState, evt: CategoryDetected) => state.copy(category = Some(evt.category))
      )

    val node2: WIORunnableNode[Ctx.Ctx, QueryState, Nothing, RoleDetected, RoleDetection, QueryState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, QueryState, RoleDetected, RoleDetection, QueryState](
        runnable = roleRunnable,
        toEvent = (result: RoleDetection) => RoleDetected(result.role, result.confidence),
        toState = (state: QueryState, evt: RoleDetected) => state.copy(role = Some(evt.role))
      )

    WIOGraph[Ctx.Ctx, QueryState, GraphState]
      .addNode("classify", node1)
      .addNode("detect_role", node2)
      .addEdge(node1Ref, node2Ref)
      .setEntry(node1Ref)
      .addEndNode(endRef)

  private def compileRunnable(
    graph: WIOGraph[Ctx.Ctx, QueryState, Nothing, GraphState]
  ): IO[Runnable[QueryState, GraphState]] =
    graph.toRunnable match
      case Right(runnable) => IO.pure(runnable)
      case Left(errors) =>
        IO.raiseError(new IllegalStateException(
          s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
        ))

/**
 * Mock LLM client for graph integration examples.
 */
class GraphIntegrationMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val systemMessage: String = conversation.messages.collect {
      case msg: SystemMessage => msg.content
    }.headOption.getOrElse("")

    val response: String =
      if systemMessage.contains("query classifier") then
        """{"category": "technical", "confidence": 0.88}"""
      else if systemMessage.contains("role detector") then
        """{"role": "customer", "confidence": 0.85}"""
      else
        """{"category": "general", "confidence": 0.70}"""

    val assistantMessage: AssistantMessage = AssistantMessage(Some(response))
    Right(Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = response,
      model = "mock-model",
      message = assistantMessage,
      toolCalls = List.empty,
      usage = None,
      thinking = None
    ))

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Either[org.llm4s.error.LLMError, Completion] =
    complete(conversation, options)

  def getContextWindow(): Int = 8192
  def getReserveCompletion(): Int = 512
