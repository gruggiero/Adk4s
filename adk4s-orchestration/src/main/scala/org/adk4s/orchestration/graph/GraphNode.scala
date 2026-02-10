package org.adk4s.orchestration.graph

import cats.effect.IO
import cats.{Monad, MonadError}
import org.adk4s.core.component.ChatModel
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.tools.{StructuredToolCall, StructuredToolCallError, ToolSchema, ToolsNode as AdkToolsNode}
import org.adk4s.core.types.{PromptTemplate, Schema}
import org.adk4s.orchestration.execution.{GraphWorkflowContext, NodeExecutable}
import org.adk4s.orchestration.fork.ForkSpec
import org.adk4s.structured.core.StructuredLLM
import org.llm4s.llmconnect.model.{Conversation, Completion, ToolCall, ToolMessage}
import workflows4s.wio.{ErrorMeta, WCState, WIO, WorkflowContext}

import scala.reflect.ClassTag

/** GraphNode represents a typed node in a graph.
  *
  * Type parameters:
  * - I: Input type
  * - O: Output type
  */
sealed trait GraphNode[I, O]:
  def executable: NodeExecutable[I, O]

object GraphNode:
  /** Type alias for GraphWorkflowContext.type for cleaner syntax. */
  type GCtx = GraphWorkflowContext.type

  private val ioMonad: Monad[IO] = cats.Monad[IO]
  private val structuredToolCallMonadError: MonadError[IO, StructuredToolCallError] =
    new MonadError[IO, StructuredToolCallError]:
      override def pure[A](x: A): IO[A] = IO.pure(x)
      override def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
      override def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
        ioMonad.tailRecM[A, B](a)(f)
      override def raiseError[A](e: StructuredToolCallError): IO[A] = IO.raiseError(e)
      override def handleErrorWith[A](fa: IO[A])(f: StructuredToolCallError => IO[A]): IO[A] =
        fa.handleErrorWith { (err: Throwable) =>
          err match
            case typed: StructuredToolCallError => f(typed)
            case other: Throwable => IO.raiseError(other)
        }

  /** Given instance for ErrorMeta[Nothing] - required for nodes that never fail. */
  private given nothingErrorMeta: ErrorMeta[Nothing] = ErrorMeta.noError

  /** LambdaNode wraps a Lambda function as a graph node.
    *
    * Uses GraphWorkflowContext as the context where State = Any,
    * allowing arbitrary output types.
    */
  case class LambdaNode[I, O](
    lambda: Lambda[I, O]
  )(using classTag: ClassTag[O]) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O](lambda.runnable.invoke)

  /** ChatModelNode wraps a ChatModel for conversation to completion. */
  case class ChatModelNode(
    model: ChatModel[IO]
  )(using classTag: ClassTag[Completion]) extends GraphNode[Conversation, Completion]:
    val executable: NodeExecutable[Conversation, Completion] =
      NodeExecutable.fromLambda[Conversation, Completion](model.generate)

  /** ToolsNode executes tool calls and returns tool messages. */
  case class ToolsNode(
    toolsNode: AdkToolsNode
  )(using classTag: ClassTag[List[ToolMessage]]) extends GraphNode[List[ToolCall], List[ToolMessage]]:
    val executable: NodeExecutable[List[ToolCall], List[ToolMessage]] =
      NodeExecutable.fromLambda[List[ToolCall], List[ToolMessage]](
        (calls: List[ToolCall]) => toolsNode.executeFromToolCalls(calls).map(_.toLlm4sMessages())
      )

  /** StructuredModelNode uses a StructuredLLM to extract typed output from a prompt template. */
  case class StructuredModelNode[I, O](
    model: StructuredLLM[IO],
    template: PromptTemplate[I]
  )(using schema: Schema[O], classTag: ClassTag[O]) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O](
        (input: I) => model.completeTemplate[I, O](template, input)(using schema)
      )

  /** StructuredToolNode executes structured tool calls with type-safe input/output. */
  case class StructuredToolNode[ToolIn, ToolOut](
    structuredToolCall: StructuredToolCall[IO],
    toolName: String
  )(using
    inputSchema: ToolSchema[ToolIn],
    outputSchema: ToolSchema[ToolOut],
    classTag: ClassTag[ToolOut]
  ) extends GraphNode[ToolCall, ToolOut]:
    val executable: NodeExecutable[ToolCall, ToolOut] =
      NodeExecutable.fromLambda[ToolCall, ToolOut](
        (toolCall: ToolCall) =>
          structuredToolCall.execute[ToolIn, ToolOut](toolCall)(using inputSchema, outputSchema, structuredToolCallMonadError)
      )

  /** SubGraphNode embeds another graph as a node. */
  case class SubGraphNode[I, O](
    graph: Graph[I, O]
  )(using classTag: ClassTag[O]) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O](_ => IO.raiseError(new UnsupportedOperationException("SubGraphNode execution not yet implemented")))

  /** MergeNode combines two inputs into a single output. */
  case class MergeNode[A, B, C](
    combine: (A, B) => C
  )(using classTag: ClassTag[C]) extends GraphNode[(A, B), C]:
    val executable: NodeExecutable[(A, B), C] =
      NodeExecutable.fromPure[(A, B), C](
        (tuple: (A, B)) => combine(tuple._1, tuple._2)
      )

  /** ForkNode represents a branching point with multiple possible paths.
    *
    * The ForkSpec defines the conditions and workflows for each branch.
    */
  case class ForkNode[I, O](
    forkSpec: ForkSpec[I, O]
  ) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O](_ => IO.raiseError(new UnsupportedOperationException(
        "ForkNode should be executed via GraphExecutor"
      )))

  /** RunIONode wraps an IO operation with explicit event handling.
    *
    * This is the most general node type, supporting:
    * - IO-based computation
    * - Event emission for replay
    * - Error handling
    */
  case class RunIONode[I, Evt, O](
    runIO: I => IO[Evt],
    handleEvent: (I, Evt) => Either[Throwable, O]
  )(using classTag: ClassTag[Evt]) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O]((input: I) => runIO(input).flatMap(evt => IO.fromEither(handleEvent(input, evt))))

  /** PureNode represents a pure (non-IO) transformation. */
  case class PureNode[I, O](
    transform: I => Either[Throwable, O]
  ) extends GraphNode[I, O]:
    val executable: NodeExecutable[I, O] =
      NodeExecutable.fromLambda[I, O]((input: I) => IO.fromEither(transform(input)))
