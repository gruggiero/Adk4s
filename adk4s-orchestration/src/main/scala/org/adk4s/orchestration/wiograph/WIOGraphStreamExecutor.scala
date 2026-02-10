package org.adk4s.orchestration.wiograph

import cats.data.NonEmptyChain
import cats.effect.IO
import fs2.Stream
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.types.NodeKey
import workflows4s.wio.{WCState, WorkflowContext}

/** Provides stream-based execution for WIOGraph.
  *
  * Compiles a WIOGraph into a Runnable that supports all four execution modes:
  * invoke, stream, collect, and transform. This is an alternative to the
  * WIO-based compilation (toWIO) that bypasses the event-sourced workflow model
  * and directly chains node execution.
  *
  * Use this when you need streaming support (e.g., for Eino-style graph examples
  * that use StreamableLambda, TransformableLambda, or async nodes).
  */
object WIOGraphStreamExecutor:

  /** Result of a node execution, carrying the node key and its output. */
  final case class NodeResult[O](nodeKey: NodeKey, output: O)

  /** Compile a WIOGraph into a Runnable.
    * Returns Left with validation errors if the graph is invalid.
    */
  def compile[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Err, Out]
  ): Either[NonEmptyChain[WIOGraphError], Runnable[In, Out]] =
    graph.toRunnable

  /** Invoke a compiled graph with the given input, returning the final output. */
  def invoke[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Err, Out],
    input: In
  ): IO[Out] =
    graph.toRunnable match
      case Right(runnable) => runnable.invoke(input)
      case Left(errors) =>
        val message: String = errors.toNonEmptyList.toList.mkString("Graph compilation failed: ", ", ", "")
        IO.raiseError(new RuntimeException(message))

  /** Stream a compiled graph with the given input, returning a stream of outputs. */
  def stream[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Err, Out],
    input: In
  ): Stream[IO, Out] =
    graph.toRunnable match
      case Right(runnable) => runnable.stream(input)
      case Left(errors) =>
        val message: String = errors.toNonEmptyList.toList.mkString("Graph compilation failed: ", ", ", "")
        Stream.raiseError[IO](new RuntimeException(message))

  /** Collect from a stream of inputs, returning the final output. */
  def collect[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Err, Out],
    input: Stream[IO, In]
  ): IO[Out] =
    graph.toRunnable match
      case Right(runnable) => runnable.collect(input)
      case Left(errors) =>
        val message: String = errors.toNonEmptyList.toList.mkString("Graph compilation failed: ", ", ", "")
        IO.raiseError(new RuntimeException(message))

  /** Transform a stream of inputs into a stream of outputs. */
  def transform[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]](
    graph: WIOGraph[Ctx, In, Err, Out],
    input: Stream[IO, In]
  ): Stream[IO, Out] =
    graph.toRunnable match
      case Right(runnable) => runnable.transform(input)
      case Left(errors) =>
        val message: String = errors.toNonEmptyList.toList.mkString("Graph compilation failed: ", ", ", "")
        Stream.raiseError[IO](new RuntimeException(message))
