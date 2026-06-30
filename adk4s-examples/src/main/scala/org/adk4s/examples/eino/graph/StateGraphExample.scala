package org.adk4s.examples.eino.graph

import cats.effect.IO
import cats.effect.IOApp
import fs2.Stream
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIOGraphError
import org.adk4s.orchestration.wiograph.WIOGraphStreamExecutor
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIORunnableNode
import org.adk4s.orchestration.wiograph.WIOPureNode
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WCState
import workflows4s.wio.WorkflowContext

import scala.reflect.ClassTag

/**
 * Eino equivalent: compose/graph/state/state_graph.go
 *
 * Demonstrates a graph with three lambda nodes using different Runnable modes:
 *   InvokableLambda → StreamableLambda → TransformableLambda
 *
 * Each node transforms a string, and the graph is compiled to a Runnable
 * supporting invoke, stream, and transform execution modes.
 */
object StateGraphExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class TextState(text: String) extends GraphState

    sealed trait GraphEvent
    final case class TextProcessed(text: String) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.GraphState
  import Ctx.TextProcessed
  import Ctx.TextState

  private given ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[TextProcessed] = scala.reflect.ClassTag(classOf[TextProcessed])

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("State Graph Example (Eino: graph/state)")

      runnable <- compileRunnable(buildGraph())

      // 1. Invoke mode
      _ <- ExampleUtils.printSubSection("1. Invoke Mode")
      invokeResult <- runnable.invoke(TextState("how are you"))
      _ <- invokeResult match
        case result: TextState =>
          IO.println(s"   Invoke result: ${result.text}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      // 2. Stream mode
      _ <- ExampleUtils.printSubSection("2. Stream Mode")
      streamResults <- runnable.stream(TextState("how are you")).compile.toList
      _ <- IO.println(s"   Stream chunks:")
      _ <- streamResults.foldLeft(IO.unit) { (acc: IO[Unit], state: GraphState) =>
        acc *> (state match
          case result: TextState => IO.println(s"     chunk: ${result.text}")
          case other => IO.println(s"     unexpected: $other")
        )
      }

      // 3. Transform mode
      _ <- ExampleUtils.printSubSection("3. Transform Mode")
      inputStream = Stream.emit[IO, TextState](TextState("how are you"))
      transformResults <- runnable.transform(inputStream.map(identity)).compile.toList
      _ <- IO.println(s"   Transform results:")
      _ <- transformResults.foldLeft(IO.unit) { (acc: IO[Unit], state: GraphState) =>
        acc *> (state match
          case result: TextState => IO.println(s"     result: ${result.text}")
          case other => IO.println(s"     unexpected: $other")
        )
      }

      _ <- IO.println("\nState graph example completed.")
    yield ()

  private def buildGraph(): Either[WIOGraphError, WIOGraph[Ctx.Ctx, TextState, Nothing, GraphState]] =
    // Node 1: InvokableLambda — prepends "InvokableLambda: "
    val invokableRunnable: Runnable[TextState, String] =
      Runnable.fromInvoke[TextState, String]((input: TextState) =>
        IO.pure("InvokableLambda: " + input.text)
      )

    // Node 2: StreamableLambda — prepends "StreamableLambda: " and streams word-by-word
    val streamableRunnable: Runnable[TextState, String] =
      Runnable.fromStream[TextState, String]((input: TextState) =>
        val outStr: String = "StreamableLambda: " + input.text
        val words: List[String] = outStr.split(" ").toList
        Stream.emits(words).map((word: String) => word + " ")
      )

    // Node 3: TransformableLambda — prepends "TransformableLambda: " to the stream
    val transformableRunnable: Runnable[TextState, String] =
      Runnable.full[TextState, String](
        invokeFn = (input: TextState) => IO.pure("TransformableLambda: " + input.text),
        streamFn = (input: TextState) =>
          val prefix: String = "TransformableLambda: "
          val prefixWords: List[String] = prefix.split(" ").toList.map(_ + " ")
          val inputWords: List[String] = input.text.split(" ").toList.map(_ + " ")
          Stream.emits(prefixWords ++ inputWords),
        collectFn = (inputStream: Stream[IO, TextState]) =>
          inputStream.compile.lastOrError.map((s: TextState) => "TransformableLambda: " + s.text),
        transformFn = (inputStream: Stream[IO, TextState]) =>
          val prefix: Stream[IO, String] = Stream.emit("TransformableLambda: ")
          prefix ++ inputStream.map((s: TextState) => s.text)
      )

    val node1Ref: WIONodeRef[Ctx.Ctx, TextState, TextState] =
      WIONodeRef[Ctx.Ctx, TextState, TextState](NodeKey.unsafeApply("invokable"))
    val node2Ref: WIONodeRef[Ctx.Ctx, TextState, TextState] =
      WIONodeRef[Ctx.Ctx, TextState, TextState](NodeKey.unsafeApply("streamable"))
    val node3Ref: WIONodeRef[Ctx.Ctx, TextState, TextState] =
      WIONodeRef[Ctx.Ctx, TextState, TextState](NodeKey.unsafeApply("transformable"))
    val endRef: WIONodeRef[Ctx.Ctx, TextState, GraphState] =
      WIONodeRef[Ctx.Ctx, TextState, GraphState](NodeKey.unsafeApply("transformable"))

    val node1: WIORunnableNode[Ctx.Ctx, TextState, Nothing, TextProcessed, String, TextState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, TextState, TextProcessed, String, TextState](
        runnable = invokableRunnable,
        toEvent = (raw: String) => TextProcessed(raw),
        toState = (_: TextState, evt: TextProcessed) => TextState(evt.text)
      )

    val node2: WIORunnableNode[Ctx.Ctx, TextState, Nothing, TextProcessed, String, TextState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, TextState, TextProcessed, String, TextState](
        runnable = streamableRunnable,
        toEvent = (raw: String) => TextProcessed(raw),
        toState = (_: TextState, evt: TextProcessed) => TextState(evt.text)
      )

    val node3: WIORunnableNode[Ctx.Ctx, TextState, Nothing, TextProcessed, String, TextState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, TextState, TextProcessed, String, TextState](
        runnable = transformableRunnable,
        toEvent = (raw: String) => TextProcessed(raw),
        toState = (_: TextState, evt: TextProcessed) => TextState(evt.text)
      )

    for
      g1 <- WIOGraph[Ctx.Ctx, TextState, GraphState].addNode("invokable", node1)
      g2 <- g1.addNode("streamable", node2)
      g3 <- g2.addNode("transformable", node3)
      g4 <- g3.addEdge(node1Ref, node2Ref)
      g5 <- g4.addEdge(node2Ref, node3Ref)
      g6 <- g5.setEntry(node1Ref)
      g7 <- g6.addEndNode(endRef)
    yield g7

  private def compileRunnable(
    graphEither: Either[WIOGraphError, WIOGraph[Ctx.Ctx, TextState, Nothing, GraphState]]
  ): IO[Runnable[TextState, GraphState]] =
    graphEither match
      case Left(err: WIOGraphError) =>
        IO.raiseError(new IllegalStateException(s"Graph build failed: $err"))
      case Right(graph: WIOGraph[Ctx.Ctx, TextState, Nothing, GraphState]) =>
        graph.toRunnable match
          case Right(runnable) => IO.pure(runnable)
          case Left(errors) =>
            IO.raiseError(new IllegalStateException(
              s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
            ))
