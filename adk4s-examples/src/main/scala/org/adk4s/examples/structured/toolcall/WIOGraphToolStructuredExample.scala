package org.adk4s.examples.structured.toolcall

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.tools.{StructuredToolCall, ToolSchema, TypedTool}
import org.adk4s.core.types.NodeKey
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.wiograph.WIOGraph
import org.adk4s.orchestration.wiograph.WIONode
import org.adk4s.orchestration.wiograph.WIONodeRef
import org.adk4s.orchestration.wiograph.WIORunnableNode
import workflows4s.wio.ErrorMeta
import workflows4s.wio.WorkflowContext

import scala.reflect.ClassTag

/**
 * Demonstrates StructuredToolCall within WIOGraph nodes.
 *
 * Shows how to:
 * - Define typed tools using StructuredToolCall.createTool
 * - Execute tools within graph nodes
 * - Pass typed tool results to subsequent graph nodes
 * - Build tool-based workflows with compile-time type safety
 *
 * Graph: validate_input → execute_tool → format_result → END
 *
 * Supports both MockToolClient and real tool execution.
 */
object WIOGraphToolStructuredExample extends IOApp.Simple:

  object Ctx extends WorkflowContext:
    sealed trait GraphState
    final case class ToolWorkState(
      input: String,
      validated: Option[Boolean] = None,
      toolResult: Option[String] = None,
      formatted: Option[String] = None
    ) extends GraphState

    sealed trait GraphEvent
    final case class InputValidated(valid: Boolean) extends GraphEvent
    final case class ToolExecuted(result: String) extends GraphEvent
    final case class ResultFormatted(output: String) extends GraphEvent

    override type State = GraphState
    override type Event = GraphEvent

  import Ctx.GraphState
  import Ctx.InputValidated
  import Ctx.ToolExecuted
  import Ctx.ResultFormatted
  import Ctx.ToolWorkState

  // Tool input/output types
  case class ValidationInput(text: String)
  case class ValidationResult(isValid: Boolean, reason: String)

  case class ProcessingInput(data: String)
  case class ProcessingResult(output: String, status: String)

  case class FormattingInput(content: String)
  case class FormattingResult(formatted: String)

  given ToolSchema[ValidationInput] = ToolSchema.derive[ValidationInput]
  given ToolSchema[ValidationResult] = ToolSchema.derive[ValidationResult]
  given ToolSchema[ProcessingInput] = ToolSchema.derive[ProcessingInput]
  given ToolSchema[ProcessingResult] = ToolSchema.derive[ProcessingResult]
  given ToolSchema[FormattingInput] = ToolSchema.derive[FormattingInput]
  given ToolSchema[FormattingResult] = ToolSchema.derive[FormattingResult]

  private given errorMetaNothing: ErrorMeta[Nothing] = ErrorMeta.noError
  private given ClassTag[InputValidated] = scala.reflect.ClassTag(classOf[InputValidated])
  private given ClassTag[ToolExecuted] = scala.reflect.ClassTag(classOf[ToolExecuted])
  private given ClassTag[ResultFormatted] = scala.reflect.ClassTag(classOf[ResultFormatted])

  // Define typed tools
  private def createValidationTool: TypedTool[IO, ValidationInput, ValidationResult] =
    StructuredToolCall.createTool[IO, ValidationInput, ValidationResult](
      toolName = "validate_input",
      toolDescription = "Validates that input text is non-empty and well-formed"
    ) { (input: ValidationInput) =>
      if input.text.trim.isEmpty then
        IO.pure(ValidationResult(isValid = false, reason = "Input is empty"))
      else if input.text.length > 1000 then
        IO.pure(ValidationResult(isValid = false, reason = "Input too long"))
      else
        IO.pure(ValidationResult(isValid = true, reason = "Input is valid"))
    }

  private def createProcessingTool: TypedTool[IO, ProcessingInput, ProcessingResult] =
    StructuredToolCall.createTool[IO, ProcessingInput, ProcessingResult](
      toolName = "process_data",
      toolDescription = "Processes data by transforming it to uppercase"
    ) { (input: ProcessingInput) =>
      val transformed: String = input.data.toUpperCase
      IO.pure(ProcessingResult(output = transformed, status = "completed"))
    }

  private def createFormattingTool: TypedTool[IO, FormattingInput, FormattingResult] =
    StructuredToolCall.createTool[IO, FormattingInput, FormattingResult](
      toolName = "format_output",
      toolDescription = "Formats output with decorative borders"
    ) { (input: FormattingInput) =>
      val border: String = "=" * 50
      val formatted: String = s"$border\n${input.content}\n$border"
      IO.pure(FormattingResult(formatted = formatted))
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("WIOGraph Tool (Structured)")

      validationTool = createValidationTool
      processingTool = createProcessingTool
      formattingTool = createFormattingTool

      graph = buildGraph(validationTool, processingTool, formattingTool)

      _ <- ExampleUtils.printSubSection("Execute Tool Graph Workflow")
      runnable <- compileRunnable(graph)

      // Execute the workflow
      input: String = "hello world"
      _ <- IO.println(s"   Input: $input\n")

      result <- runnable.invoke(ToolWorkState(input))
      _ <- result match
        case state: ToolWorkState =>
          IO.println(s"   Validated: ${state.validated.getOrElse(false)}") *>
          IO.println(s"   Tool Result: ${state.toolResult.getOrElse("N/A")}") *>
          IO.println(s"   Formatted:\n${state.formatted.getOrElse("N/A")}")
        case other =>
          IO.println(s"   Unexpected state: $other")

      _ <- IO.println("\nWIOGraph tool example completed.")
    yield ()

  private def buildGraph(
    validationTool: TypedTool[IO, ValidationInput, ValidationResult],
    processingTool: TypedTool[IO, ProcessingInput, ProcessingResult],
    formattingTool: TypedTool[IO, FormattingInput, FormattingResult]
  ): WIOGraph[Ctx.Ctx, ToolWorkState, Nothing, GraphState] =

    // Node 1: Validate input using typed tool
    val validateRunnable: Runnable[ToolWorkState, ValidationResult] =
      Runnable.fromInvoke[ToolWorkState, ValidationResult]((state: ToolWorkState) =>
        validationTool.execute(ValidationInput(state.input))
      )

    // Node 2: Execute processing tool (only if validated)
    val processRunnable: Runnable[ToolWorkState, ProcessingResult] =
      Runnable.fromInvoke[ToolWorkState, ProcessingResult]((state: ToolWorkState) =>
        state.validated match
          case Some(true) =>
            processingTool.execute(ProcessingInput(state.input))
          case _ =>
            IO.pure(ProcessingResult(output = "INVALID", status = "skipped"))
      )

    // Node 3: Format result using typed tool
    val formatRunnable: Runnable[ToolWorkState, FormattingResult] =
      Runnable.fromInvoke[ToolWorkState, FormattingResult]((state: ToolWorkState) =>
        state.toolResult match
          case Some(result) =>
            formattingTool.execute(FormattingInput(result))
          case None =>
            IO.pure(FormattingResult(formatted = "No result to format"))
      )

    val node1Ref: WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState] =
      WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState](NodeKey.unsafeApply("validate_input"))
    val node2Ref: WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState] =
      WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState](NodeKey.unsafeApply("execute_tool"))
    val node3Ref: WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState] =
      WIONodeRef[Ctx.Ctx, ToolWorkState, ToolWorkState](NodeKey.unsafeApply("format_result"))
    val endRef: WIONodeRef[Ctx.Ctx, ToolWorkState, GraphState] =
      WIONodeRef[Ctx.Ctx, ToolWorkState, GraphState](NodeKey.unsafeApply("format_result"))

    val node1: WIORunnableNode[Ctx.Ctx, ToolWorkState, Nothing, InputValidated, ValidationResult, ToolWorkState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, ToolWorkState, InputValidated, ValidationResult, ToolWorkState](
        runnable = validateRunnable,
        toEvent = (result: ValidationResult) => InputValidated(result.isValid),
        toState = (state: ToolWorkState, evt: InputValidated) => state.copy(validated = Some(evt.valid))
      )

    val node2: WIORunnableNode[Ctx.Ctx, ToolWorkState, Nothing, ToolExecuted, ProcessingResult, ToolWorkState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, ToolWorkState, ToolExecuted, ProcessingResult, ToolWorkState](
        runnable = processRunnable,
        toEvent = (result: ProcessingResult) => ToolExecuted(result.output),
        toState = (state: ToolWorkState, evt: ToolExecuted) => state.copy(toolResult = Some(evt.result))
      )

    val node3: WIORunnableNode[Ctx.Ctx, ToolWorkState, Nothing, ResultFormatted, FormattingResult, ToolWorkState] =
      WIONode.fromRunnableSimple[Ctx.Ctx, ToolWorkState, ResultFormatted, FormattingResult, ToolWorkState](
        runnable = formatRunnable,
        toEvent = (result: FormattingResult) => ResultFormatted(result.formatted),
        toState = (state: ToolWorkState, evt: ResultFormatted) => state.copy(formatted = Some(evt.output))
      )

    WIOGraph[Ctx.Ctx, ToolWorkState, GraphState]
      .addNode("validate_input", node1)
      .addNode("execute_tool", node2)
      .addNode("format_result", node3)
      .addEdge(node1Ref, node2Ref)
      .addEdge(node2Ref, node3Ref)
      .setEntry(node1Ref)
      .addEndNode(endRef)

  private def compileRunnable(
    graph: WIOGraph[Ctx.Ctx, ToolWorkState, Nothing, GraphState]
  ): IO[Runnable[ToolWorkState, GraphState]] =
    graph.toRunnable match
      case Right(runnable) => IO.pure(runnable)
      case Left(errors) =>
        IO.raiseError(new IllegalStateException(
          s"Graph compilation failed: ${errors.toNonEmptyList.toList.mkString(", ")}"
        ))
