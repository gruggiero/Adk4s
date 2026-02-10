package org.adk4s.examples.eino.graph

import cats.effect.IO
import cats.effect.IOApp
import fs2.Stream
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIORunnableNode
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCState
import workflows4s.wio.WorkflowContext

import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/async_node/main.go + service.go
 *
 * Demonstrates async lambda nodes inside a graph:
 *   Scenario 1: Background report generation (invokable) — simulates a long-running job.
 *   Scenario 2: Live transcription stream (streamable) — emits tokens over time.
 *
 * Graph: async_invokable → async_streamable → END
 * Compiled to Runnable supporting both invoke and stream modes.
 */
object AsyncNodeExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class TextState(text: String) extends GraphState

    sealed trait GraphEvent
    final case class TextProduced(text: String) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.GraphState
  import Ctx.TextProduced
  import Ctx.TextState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[TextProduced] = scala.reflect.ClassTag(classOf[TextProduced])

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Async Node Example (Eino: graph/async_node)")

      graph = buildGraph()
      runnable <- compileRunnable(graph)

      // Scenario 1: Invoke mode — background report generation
      _ <- ExampleUtils.printSubSection("1. Invoke Mode (Background Report)")
      invokeResult <- runnable.invoke(TextState("Quarterly Sales Report"))
      _ <- invokeResult match
        case result: TextState =>
          IO.println(s"   Report URL: ${result.text}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      // Scenario 2: Stream mode — live transcription
      _ <- ExampleUtils.printSubSection("2. Stream Mode (Live Transcription)")
      _ <- IO.print("   Tokens: ")
      _ <- runnable.stream(TextState("hello world from async node"))
        .evalMap { (state: GraphState) =>
          state match
            case result: TextState => IO.print(s"${result.text} ")
            case _ => IO.unit
        }
        .compile
        .drain
      _ <- IO.println("")

      _ <- IO.println("\nAsync node example completed.")
    yield ()

  private def buildGraph(): WIOGraph[Ctx.Ctx, TextState, Nothing, GraphState] =
    // Node 1: async_invokable — simulates background report generation
    // Waits briefly then produces a URL based on the input
    val asyncInvokableRunnable: Runnable[TextState, String] =
      Runnable.fromInvoke[TextState, String]((input: TextState) =>
        val content: String = input.text
        IO.sleep(500.millis) *> {
          if content.toLowerCase.contains("error") then
            IO.raiseError(new RuntimeException("report generation failed"))
          else
            val url: String = "https://example.com/report/" +
              content.toLowerCase.replace(" ", "-")
            IO.pure(url)
        }
      )

    // Node 2: async_streamable — simulates live transcription
    // Emits words one at a time with delays, converting to uppercase
    val asyncStreamableRunnable: Runnable[TextState, String] =
      Runnable.full[TextState, String](
        invokeFn = (input: TextState) =>
          IO.pure(input.text.toUpperCase),
        streamFn = (input: TextState) =>
          val words: List[String] = input.text.split("\\s+").toList
          Stream.emits(words)
            .evalMap { (word: String) =>
              IO.sleep(200.millis) *> {
                if word.equalsIgnoreCase("error") then
                  IO.raiseError(new RuntimeException("transcription stream error"))
                else
                  IO.pure(word.toUpperCase)
              }
            },
        collectFn = (inputStream: Stream[IO, TextState]) =>
          inputStream.compile.lastOrError.map((s: TextState) => s.text.toUpperCase),
        transformFn = (inputStream: Stream[IO, TextState]) =>
          inputStream.flatMap { (s: TextState) =>
            val words: List[String] = s.text.split("\\s+").toList
            Stream.emits(words).map(_.toUpperCase)
          }
      )

    val node1Ref: WIONodeRef[Ctx.Ctx, TextState, TextState] =
      WIONodeRef[Ctx.Ctx, TextState, TextState](NodeKey.unsafeApply("async_invokable"))
    val node2Ref: WIONodeRef[Ctx.Ctx, TextState, TextState] =
      WIONodeRef[Ctx.Ctx, TextState, TextState](NodeKey.unsafeApply("async_streamable"))
    val endRef: WIONodeRef[Ctx.Ctx, TextState, GraphState] =
      WIONodeRef[Ctx.Ctx, TextState, GraphState](NodeKey.unsafeApply("async_streamable"))

    val node1: WIORunnableNode[Ctx.Ctx, TextState, Nothing, TextProduced, String, TextState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, TextState, TextProduced, String, TextState](
        runnable = asyncInvokableRunnable,
        toEvent = (raw: String) => TextProduced(raw),
        toState = (_: TextState, evt: TextProduced) => TextState(evt.text)
      )

    val node2: WIORunnableNode[Ctx.Ctx, TextState, Nothing, TextProduced, String, TextState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, TextState, TextProduced, String, TextState](
        runnable = asyncStreamableRunnable,
        toEvent = (raw: String) => TextProduced(raw),
        toState = (_: TextState, evt: TextProduced) => TextState(evt.text)
      )

    WIOGraph[Ctx.Ctx, TextState, GraphState]
      .addNode("async_invokable", node1)
      .addNode("async_streamable", node2)
      .addEdge(node1Ref, node2Ref)
      .setEntry(node1Ref)
      .addEndNode(endRef)

  private def compileRunnable(
    graph: WIOGraph[Ctx.Ctx, TextState, Nothing, GraphState]
  ): IO[Runnable[TextState, GraphState]] =
    graph.toRunnable match
      case Right(runnable) => IO.pure(runnable)
      case Left(errors) =>
        IO.raiseError(new IllegalStateException(
          s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
        ))
