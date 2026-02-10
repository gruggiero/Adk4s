package org.adk4s.core.tools

import cats.effect.IO
import cats.syntax.all.*
import cats.data.Kleisli
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.toolapi.ToolRegistry
import org.adk4s.core.component.AgentTool
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.error.{AdkError, AgentInterruptedException, ToolNotFoundError, ToolExecutionError}
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter, InterruptSignal, RunPath, RunStep}
import org.adk4s.core.runnable.Runnable
import upickle.default.*
import scala.util.Try
import fs2.Stream

class ToolsNode(config: ToolsNodeConfig):
  private val llm4sTools: List[ToolWrapper] = config.tools.collect { case Left(tw) => tw }
  private val adkTools: List[InvokableTool[IO]] = config.tools.collect { case Right(t) => t }
  private val middleware = ToolMiddleware.compose(config.middlewares)

  def executeTool(input: ToolInput): IO[ToolOutput] =
    val endpoint = createEndpoint(input.name)
    middleware(endpoint).run(input).handleErrorWith {
      case e: AgentInterruptedException =>
        // Let interrupt exceptions propagate — they are caught at the batch level
        IO.raiseError(e)
      case e =>
        IO.pure(ToolOutput(input.name, e.getMessage, input.callId, isError = true))
    }

  def executeTools(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    if config.executeSequentially then
      executeSequentially(inputs)
    else
      executeParallel(inputs)

  def executeFromToolCalls(calls: List[ToolCall]): IO[ToolExecutionResult] =
    executeTools(ToolInput.fromToolCalls(calls))

  def executeFromAssistantMessage(message: AssistantMessage): IO[ToolExecutionResult] =
    executeFromToolCalls(message.toolCalls.toList)

  def toRunnable: Runnable[List[ToolCall], List[ToolCall]] =
    Runnable.fromInvoke { calls =>
      executeFromToolCalls(calls).map(_ => calls)
    }

  // Alias to match README naming
  def asRunnable: Runnable[List[ToolCall], List[ToolCall]] = toRunnable

  def maxConcurrency: Int = config.maxConcurrency

  private def createEndpoint(toolName: String): ToolEndpoint =
    Kleisli { input =>
      for
        processedArgs <- config.argumentsHandler match
          case Some(handler) => handler(input.name, input.arguments)
          case None => IO.pure(input.arguments)

        result <- findTool(toolName) match
          case Some(Left(llm4sTool)) =>
            executeLlm4sTool(llm4sTool, input.copy(arguments = processedArgs))
          case Some(Right(adkTool)) =>
            executeAdkTool(adkTool, input.copy(arguments = processedArgs))
          case None =>
            handleUnknownTool(input)
      yield result
    }

  private def findTool(name: String): Option[Either[ToolWrapper, InvokableTool[IO]]] =
    llm4sTools.find(_.name == name).map(Left(_))
      .orElse(adkTools.find(_.info.name == name).map(Right(_)))

  private def executeLlm4sTool(tool: ToolWrapper, input: ToolInput): IO[ToolOutput] =
    for
      args <- IO.fromEither(parseArguments(input.arguments))
      result <- IO.fromEither(tool.execute(args))
        .map(v => ToolOutput(input.name, v match {
          case ujson.Str(s) => s
          case other => other.toString
        }, input.callId))
        .recover { e => ToolOutput(input.name, e.getMessage, input.callId, isError = true) }
    yield result

  private def executeAdkTool(tool: InvokableTool[IO], input: ToolInput): IO[ToolOutput] =
    for
      args <- IO.fromEither(parseArguments(input.arguments))
      // If this is an AgentTool and we have an emitter, inject a scoped emitter
      toolWithEmitter <- tool match
        case agentTool: AgentTool =>
          config.eventEmitter match
            case Some(emitter) =>
              val scoped: AgentEventEmitter = emitter.scoped(RunStep(input.name))
              IO.pure(agentTool.withEmitter(scoped): InvokableTool[IO])
            case None =>
              IO.pure(tool)
        case _ =>
          IO.pure(tool)
      result <- toolWithEmitter.run(args)
        .map(result => ToolOutput(input.name, result match {
          case ujson.Str(s) => s
          case other => other.toString
        }, input.callId))
    yield result

  private def handleUnknownTool(input: ToolInput): IO[ToolOutput] =
    config.unknownToolHandler match
      case Some(handler) =>
        handler(input.name, input.arguments)
          .map(result => ToolOutput(input.name, result, input.callId))
      case None =>
        IO.pure(ToolOutput(input.name, s"Unknown tool: ${input.name}", input.callId, isError = true))

  private val MaxArgBytes = 64 * 1024 // 64KB limit

  private def parseArguments(args: String): Either[Throwable, ujson.Value] =
    if args == null then Left(new IllegalArgumentException("Arguments cannot be null"))
    else if args.length > MaxArgBytes then Left(new IllegalArgumentException(s"Arguments too large: ${args.length}b > $MaxArgBytes"))
    else Try(ujson.read(args)).toEither

  private def executeSequentially(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    inputs.foldLeftM(ToolExecutionResult(Nil)) { (acc, input) =>
      // If a previous tool already interrupted, skip remaining tools
      if acc.interruptSignal.isDefined then
        IO.pure(acc)
      else
        val endpoint = createEndpoint(input.name)
        emitToolCallRequested(input) *>
        middleware(endpoint).run(input).attempt.flatMap {
          case Right(output) =>
            emitToolCallCompleted(input, output) *>
            IO.pure(acc.copy(outputs = acc.outputs :+ output))
          case Left(interrupted: AgentInterruptedException) =>
            IO.pure(acc.copy(interruptSignal = Some(interrupted.signal)))
          case Left(error) =>
            IO.pure(acc.copy(failedTools = acc.failedTools :+ ToolExecutionFailure(input, error)))
        }
    }

  private def executeParallel(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    Stream.emits(inputs)
      .parEvalMap(config.maxConcurrency)(input =>
        val endpoint = createEndpoint(input.name)
        emitToolCallRequested(input) *>
        middleware(endpoint).run(input).attempt.map(input -> _)
      )
      .compile
      .toList
      .map { results =>
        val outputs: List[ToolOutput] = results.collect { case (_, Right(output)) => output }
        val interrupts: List[InterruptSignal] = results.collect {
          case (_, Left(e: AgentInterruptedException)) => e.signal
        }
        val failures: List[ToolExecutionFailure] = results.collect {
          case (input, Left(error)) =>
            error match
              case _: AgentInterruptedException => None
              case other => Some(ToolExecutionFailure(input, other))
        }.flatten
        val compositeSignal: Option[InterruptSignal] = interrupts match
          case Nil => None
          case single :: Nil => Some(single)
          case multiple =>
            val info: String = multiple.map(_.info).mkString("; ")
            Some(InterruptSignal.composite(info, ujson.Obj(), multiple))
        ToolExecutionResult(outputs, failures, compositeSignal)
      }

  private def emitToolCallRequested(input: ToolInput): IO[Unit] =
    config.eventEmitter.fold(IO.unit) { emitter =>
      emitter.emit(AgentEvent.ToolCallRequested(
        runPath = RunPath.empty,
        toolName = input.name,
        arguments = input.arguments,
        callId = input.callId
      ))
    }

  private def emitToolCallCompleted(input: ToolInput, output: ToolOutput): IO[Unit] =
    config.eventEmitter.fold(IO.unit) { emitter =>
      emitter.emit(AgentEvent.ToolCallCompleted(
        runPath = RunPath.empty,
        toolName = input.name,
        result = output.result,
        callId = input.callId,
        isError = output.isError
      ))
    }

object ToolsNode:
  def apply(config: ToolsNodeConfig): ToolsNode =
    new ToolsNode(config)

  def fromRegistry(registry: ToolRegistry): ToolsNode =
    new ToolsNode(ToolsNodeConfig.fromRegistry(registry))

  def fromAdkTools(tools: List[InvokableTool[IO]]): ToolsNode =
    new ToolsNode(ToolsNodeConfig.fromAdkTools(tools))
