package org.adk4s.core.tools

import cats.effect.IO
import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.ToolRegistry
import org.adk4s.core.component.AgentTool
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.interrupt.AgentEventEmitter
import org.adk4s.core.tools.StructuredToolFunction.*
import scala.language.reflectiveCalls
import scala.language.implicitConversions

case class ToolsNodeConfig(
  tools: List[Either[ToolWrapper, InvokableTool[IO]]] = Nil,
  unknownToolHandler: Option[(String, String) => IO[String]] = None,
  executeSequentially: Boolean = false,
  middlewares: List[ToolMiddleware] = Nil,
  argumentsHandler: Option[(String, String) => IO[String]] = None,
  maxConcurrency: Int = 10,
  eventEmitter: Option[AgentEventEmitter] = None
)

object ToolsNodeConfig:
  def fromRegistry(registry: ToolRegistry): ToolsNodeConfig =
    ToolsNodeConfig(tools = registry.tools.map(t => Left(ToolWrapper(t))).toList)

  def fromToolFunctions[T, R](tools: List[ToolFunction[T, R]]): ToolsNodeConfig =
    ToolsNodeConfig(tools = tools.map(t => Left(ToolWrapper(t))))

  def fromAdkTools(tools: List[InvokableTool[IO]]): ToolsNodeConfig =
    ToolsNodeConfig(tools = tools.map(Right(_)))

  def builder: ToolsNodeConfigBuilder = ToolsNodeConfigBuilder()

case class ToolWrapper(
  toolFunction: ToolFunction[?, ?]
):
  def name: String = toolFunction.name
  def description: String = toolFunction.description
  def execute(args: ujson.Value): Either[Throwable, ujson.Value] =
    toolFunction.execute(args).left.map {
      case org.llm4s.toolapi.ToolCallError.ExecutionError(message, cause) =>
        new RuntimeException(s"Tool execution error: $message", cause)
      case err => new RuntimeException(err.toString)
    }

object ToolWrapper

case class ToolsNodeConfigBuilder(
  private val config: ToolsNodeConfig = ToolsNodeConfig()
):
  def withTool[T, R](tool: ToolFunction[T, R]): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Left(ToolWrapper(tool))))

  def withStructuredTool[I, O](tool: StructuredToolFunction[I, O]): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Left(tool.toToolWrapper)))

  def withAdkTool(tool: InvokableTool[IO]): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Right(tool)))

  def withAgentTool(agentTool: AgentTool): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Right(agentTool)))

  def withEventEmitter(emitter: AgentEventEmitter): ToolsNodeConfigBuilder =
    copy(config = config.copy(eventEmitter = Some(emitter)))

  def withUnknownHandler(handler: (String, String) => IO[String]): ToolsNodeConfigBuilder =
    copy(config = config.copy(unknownToolHandler = Some(handler)))

  def sequential: ToolsNodeConfigBuilder =
    copy(config = config.copy(executeSequentially = true))

  def parallel(maxConcurrency: Int = 10): ToolsNodeConfigBuilder =
    copy(config = config.copy(executeSequentially = false, maxConcurrency = maxConcurrency))

  def withMiddleware(middleware: ToolMiddleware): ToolsNodeConfigBuilder =
    copy(config = config.copy(middlewares = config.middlewares :+ middleware))

  def withArgumentsHandler(handler: (String, String) => IO[String]): ToolsNodeConfigBuilder =
    copy(config = config.copy(argumentsHandler = Some(handler)))

  def build: ToolsNodeConfig = config

extension (config: ToolsNodeConfig)
  /** Extract only LLM4S tools and build a ToolRegistry.
    * Includes ALL ToolWrappers (both ToolFunction-derived and StructuredToolFunction-derived). */
  def toToolRegistry: ToolRegistry =
    val llm4sWrappers: List[ToolWrapper] = config.tools.collect { case Left(tw) => tw }
    val toolFunctions: Seq[org.llm4s.toolapi.ToolFunction[?, ?]] =
      llm4sWrappers.map(_.toolFunction)
    ToolRegistry(toolFunctions)

  /** Extract only ADK4S InvokableTools. */
  def adkTools: List[InvokableTool[IO]] =
    config.tools.collect { case Right(tool) => tool }
