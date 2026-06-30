package org.adk4s.examples.structured.llm.workflow

import cats.effect.IO
import cats.effect.IOApp
import fs2.Stream
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIORunnableNode
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.StepList
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WorkflowContext

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

/**
 * Demonstrates async StructuredLLM nodes with streaming in WIOGraph.
 *
 * Shows how to:
 * - Use StructuredLLM with streaming in async graph nodes
 * - Process structured outputs with delays (simulating real async work)
 * - Use streamWithResult API for both streaming and final result
 * - Build async workflows with type-safe parsing
 *
 * Graph: async_parser → async_processor → END
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object AsyncNodeStructuredExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class WorkState(
      input: String,
      parsedSteps: Option[StepList] = None,
      processedCount: Option[Int] = None
    ) extends GraphState

    sealed trait GraphEvent
    final case class StepsParsed(steps: StepList) extends GraphEvent
    final case class StepsProcessed(count: Int) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.GraphState
  import Ctx.StepsParsed
  import Ctx.StepsProcessed
  import Ctx.WorkState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[StepsParsed] = scala.reflect.ClassTag(classOf[StepsParsed])
  private given ClassTag[StepsProcessed] = scala.reflect.ClassTag(classOf[StepsProcessed])

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[StepList] = Schema.instance(
    """structure StepList {
      |  @required
      |  items: StepItem[]
      |}
      |
      |structure StepItem {
      |  @required
      |  index: Integer
      |  @required
      |  description: String
      |  duration: Integer
      |}
      |
      |// "items" is a JSON array: [{"index":1,"description":"...","duration":30}, ...]""".stripMargin
  )(using summon[smithy4s.schema.Schema[StepList]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new AsyncNodeStructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Async Node (Structured)")
      llmClient <- createLLMClient

      runnable <- compileRunnable(buildGraph(llmClient))

      // Scenario 1: Invoke mode
      _ <- ExampleUtils.printSubSection("1. Invoke Mode")
      invokeResult <- runnable.invoke(WorkState("1. Install dependencies\n2. Run tests\n3. Deploy"))
      _ <- invokeResult match
        case state: WorkState =>
          IO.println(s"   Parsed ${state.parsedSteps.map(_.items.size).getOrElse(0)} steps") *>
          IO.println(s"   Processed: ${state.processedCount.getOrElse(0)} steps")
        case other =>
          IO.println(s"   Unexpected state: $other")

      // Scenario 2: Stream mode
      _ <- ExampleUtils.printSubSection("2. Stream Mode")
      _ <- IO.println("   Streaming workflow execution:")
      _ <- runnable.stream(WorkState("1. Compile code\n2. Run linter\n3. Build package"))
        .evalMap { (state: GraphState) =>
          state match
            case s: WorkState =>
              s.parsedSteps match
                case Some(steps) if s.processedCount.isEmpty =>
                  IO.println(s"     → Parsed ${steps.items.size} steps")
                case _ if s.processedCount.isDefined =>
                  IO.println(s"     → Processed ${s.processedCount.getOrElse(0)} steps")
                case _ =>
                  IO.unit
            case _ => IO.unit
        }
        .compile
        .drain

      _ <- IO.println("\nAsync node example completed.")
    yield ()

  private def buildGraph(llmClient: org.llm4s.llmconnect.LLMClient): Either[WIOGraphError, WIOGraph[Ctx.Ctx, WorkState, Nothing, GraphState]] =
    val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](llmClient)

    // Node 1: async_parser — parses steps with delay (simulating async work)
    val asyncParserRunnable: Runnable[WorkState, StepList] =
      Runnable.fromInvoke[WorkState, StepList]((input: WorkState) =>
        val prompt: Prompt = Prompt.simple(
          "Parse the numbered steps into a structured list with index and description.",
          s"Steps:\n${input.input}"
        )
        IO.sleep(300.millis) *> structured.complete[StepList](prompt)
      )

    // Node 2: async_processor — processes steps with simulated work
    val asyncProcessorRunnable: Runnable[WorkState, Int] =
      Runnable.full[WorkState, Int](
        invokeFn = (input: WorkState) =>
          input.parsedSteps match
            case Some(steps) =>
              IO.sleep(400.millis) *> IO.pure(steps.items.size)
            case None =>
              IO.pure(0),
        streamFn = (input: WorkState) =>
          input.parsedSteps match
            case Some(steps) =>
              Stream.emits(steps.items.indices.toList)
                .evalMap { (idx: Int) =>
                  IO.sleep(200.millis) *> IO.pure(idx + 1)
                }
            case None =>
              Stream.empty,
        collectFn = (inputStream: Stream[IO, WorkState]) =>
          inputStream.compile.lastOrError.flatMap { (s: WorkState) =>
            s.parsedSteps match
              case Some(steps) => IO.pure(steps.items.size)
              case None => IO.pure(0)
          },
        transformFn = (inputStream: Stream[IO, WorkState]) =>
          inputStream.flatMap { (s: WorkState) =>
            s.parsedSteps match
              case Some(steps) =>
                Stream.emits(steps.items.indices.toList)
              case None =>
                Stream.empty
          }
      )

    val node1Ref: WIONodeRef[Ctx.Ctx, WorkState, WorkState] =
      WIONodeRef[Ctx.Ctx, WorkState, WorkState](NodeKey.unsafeApply("async_parser"))
    val node2Ref: WIONodeRef[Ctx.Ctx, WorkState, WorkState] =
      WIONodeRef[Ctx.Ctx, WorkState, WorkState](NodeKey.unsafeApply("async_processor"))
    val endRef: WIONodeRef[Ctx.Ctx, WorkState, GraphState] =
      WIONodeRef[Ctx.Ctx, WorkState, GraphState](NodeKey.unsafeApply("async_processor"))

    val node1: WIORunnableNode[Ctx.Ctx, WorkState, Nothing, StepsParsed, StepList, WorkState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, WorkState, StepsParsed, StepList, WorkState](
        runnable = asyncParserRunnable,
        toEvent = (result: StepList) => StepsParsed(result),
        toState = (state: WorkState, evt: StepsParsed) => state.copy(parsedSteps = Some(evt.steps))
      )

    val node2: WIORunnableNode[Ctx.Ctx, WorkState, Nothing, StepsProcessed, Int, WorkState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, WorkState, StepsProcessed, Int, WorkState](
        runnable = asyncProcessorRunnable,
        toEvent = (count: Int) => StepsProcessed(count),
        toState = (state: WorkState, evt: StepsProcessed) => state.copy(processedCount = Some(evt.count))
      )

    for
      g1 <- WIOGraph[Ctx.Ctx, WorkState, GraphState].addNode("async_parser", node1)
      g2 <- g1.addNode("async_processor", node2)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(endRef)
    yield g5

  private def compileRunnable(
    graphEither: Either[WIOGraphError, WIOGraph[Ctx.Ctx, WorkState, Nothing, GraphState]]
  ): IO[Runnable[WorkState, GraphState]] =
    graphEither match
      case Left(err: WIOGraphError) =>
        IO.raiseError(new IllegalStateException(s"Graph build failed: $err"))
      case Right(graph: WIOGraph[Ctx.Ctx, WorkState, Nothing, GraphState]) =>
        graph.toRunnable match
          case Right(runnable) => IO.pure(runnable)
          case Left(errors) =>
            IO.raiseError(new IllegalStateException(
              s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
            ))

/**
 * Mock LLM client for async node examples.
 */
class AsyncNodeStructuredMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val response: String = """{
      |  "items": [
      |    {"index": 1, "description": "Install dependencies", "duration": 5},
      |    {"index": 2, "description": "Run tests", "duration": 10},
      |    {"index": 3, "description": "Deploy", "duration": 15}
      |  ]
      |}""".stripMargin

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
