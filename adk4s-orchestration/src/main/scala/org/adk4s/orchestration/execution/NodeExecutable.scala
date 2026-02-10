package org.adk4s.orchestration.execution

import cats.effect.IO
import workflows4s.wio.{ErrorMeta, WCState, WIO, WorkflowContext}

import scala.reflect.ClassTag

/** NodeExecutable provides execution capabilities for graph nodes.
  *
  * Each NodeExecutable exposes:
  * - `invoke`: IO-based execution with typed input
  */
trait NodeExecutable[I, O]:
  def invoke(input: I): IO[O]

object NodeExecutable:
  /** Type alias for GraphWorkflowContext.type for cleaner syntax. */
  type GCtx = GraphWorkflowContext.type

  /** Create a NodeExecutable from an IO-returning function. */
  def fromLambda[I, O](
    f: I => IO[O]
  ): NodeExecutable[I, O] =
    new NodeExecutable[I, O]:
      def invoke(input: I): IO[O] = f(input)

  /** Create a NodeExecutable from a pure function. */
  def fromPure[I, O](
    f: I => O
  ): NodeExecutable[I, O] =
    new NodeExecutable[I, O]:
      def invoke(input: I): IO[O] = IO.pure(f(input))

  
