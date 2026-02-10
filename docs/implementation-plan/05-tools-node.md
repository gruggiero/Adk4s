# Feature 05: Tools Node

## Overview

This document details the implementation of the ToolsNode, which executes tool calls from LLM responses. This is a critical component for ReAct agents and function-calling workflows.

## Prerequisites

- **Feature 01**: Core Types & Schema System
- **Feature 02**: Streaming Integration
- **Feature 03**: Component Abstractions
- **Feature 04**: Lambda & Runnable

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| LLM4S | ToolFunction, ToolRegistry | Local |
| Cats Effect | Effects, Concurrent | 3.6.3 |
| fs2 | Streaming | 3.9.x |
| Circe | JSON parsing | 0.14.x |

## Design Philosophy

ADK4S ToolsNode follows these principles:

1. **Wrap LLM4S ToolRegistry** - Leverage existing tool execution
2. **Support parallel and sequential execution** - Configurable
3. **Provide middleware support** - For logging, validation, etc.
4. **Handle unknown tools gracefully** - Custom handler option

## Implementation Tasks

### Task 1: Create ToolInput/ToolOutput Types

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/tools/ToolTypes.scala`

**Purpose**: Define input/output types for tool execution

**API Design**:
```scala
package org.adk4s.agent.tools

import org.llm4s.llmconnect.model.{ToolCall, Message as Llm4sMessage, ToolMessage}
import org.adk4s.structured.core.{Message, Role}
import io.circe.Json

/**
 * Input for tool execution.
 */
case class ToolInput(
  name: String,
  arguments: String,
  callId: String
)

object ToolInput:
  def fromToolCall(tc: ToolCall): ToolInput =
    ToolInput(tc.function.name, tc.function.arguments, tc.id)

  def fromToolCalls(calls: List[ToolCall]): List[ToolInput] =
    calls.map(fromToolCall)

/**
 * Output from tool execution.
 */
case class ToolOutput(
  name: String,
  result: String,
  callId: String,
  isError: Boolean = false
):
  def toMessage: Message =
    Message(Role.Tool, result)

  def toLlm4sMessage: ToolMessage =
    ToolMessage(result, callId)

/**
 * Batch tool execution result.
 */
case class ToolExecutionResult(
  outputs: List[ToolOutput],
  failedTools: List[ToolExecutionFailure] = Nil
):
  def toMessages: List[Message] =
    outputs.map(_.toMessage)

  def toLlm4sMessages: List[ToolMessage] =
    outputs.map(_.toLlm4sMessage)

  def allSucceeded: Boolean =
    failedTools.isEmpty

/**
 * Tool execution failure details.
 */
case class ToolExecutionFailure(
  input: ToolInput,
  error: Throwable
)
```

**Testing**:
- Test ToolInput from ToolCall
- Test ToolOutput to Message conversion
- Test batch result handling

---

### Task 2: Create ToolMiddleware

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/tools/ToolMiddleware.scala`

**Purpose**: Define middleware pattern for tool execution

**API Design**:
```scala
package org.adk4s.agent.tools

import cats.effect.IO
import cats.data.Kleisli

/**
 * Tool execution endpoint type.
 */
type ToolEndpoint = Kleisli[IO, ToolInput, ToolOutput]

/**
 * Tool middleware that wraps an endpoint.
 */
type ToolMiddleware = ToolEndpoint => ToolEndpoint

object ToolMiddleware:
  /**
   * Identity middleware (no-op).
   */
  val identity: ToolMiddleware = endpoint => endpoint

  /**
   * Logging middleware.
   */
  def logging(log: String => IO[Unit]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      for
        _ <- log(s"Tool call: ${input.name}(${input.arguments})")
        result <- endpoint.run(input).attempt
        _ <- result match
          case Right(output) =>
            log(s"Tool result: ${output.result.take(100)}${if output.result.length > 100 then "..." else ""}")
          case Left(error) =>
            log(s"Tool error: ${error.getMessage}")
        output <- IO.fromEither(result)
      yield output
    }

  /**
   * Timing middleware.
   */
  def timing(onTiming: (String, Long) => IO[Unit]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      for
        start <- IO.realTime
        result <- endpoint.run(input)
        end <- IO.realTime
        _ <- onTiming(input.name, (end - start).toMillis)
      yield result
    }

  /**
   * Validation middleware.
   */
  def validation(validate: ToolInput => IO[Either[String, Unit]]): ToolMiddleware = endpoint =>
    Kleisli { input =>
      validate(input).flatMap {
        case Right(_) => endpoint.run(input)
        case Left(error) => IO.pure(ToolOutput(input.name, s"Validation error: $error", input.callId, isError = true))
      }
    }

  /**
   * Retry middleware with exponential backoff.
   */
  def retry(maxRetries: Int, initialDelay: scala.concurrent.duration.FiniteDuration): ToolMiddleware = endpoint =>
    Kleisli { input =>
      def attempt(retriesLeft: Int, delay: scala.concurrent.duration.FiniteDuration): IO[ToolOutput] =
        endpoint.run(input).handleErrorWith { error =>
          if retriesLeft > 0 then
            IO.sleep(delay) *> attempt(retriesLeft - 1, delay * 2)
          else
            IO.raiseError(error)
        }
      attempt(maxRetries, initialDelay)
    }

  /**
   * Compose multiple middlewares.
   */
  def compose(middlewares: List[ToolMiddleware]): ToolMiddleware =
    middlewares.foldRight(identity)((m, acc) => endpoint => m(acc(endpoint)))

  /**
   * Compose middlewares using >> syntax.
   */
  extension (self: ToolMiddleware)
    def >>(next: ToolMiddleware): ToolMiddleware =
      endpoint => next(self(endpoint))
```

**Testing**:
- Test logging middleware
- Test timing middleware
- Test validation middleware
- Test middleware composition

---

### Task 3: Create ToolsNodeConfig

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/tools/ToolsNodeConfig.scala`

**Purpose**: Configuration for ToolsNode

**API Design**:
```scala
package org.adk4s.agent.tools

import cats.effect.IO
import org.llm4s.tools.{ToolFunction, ToolRegistry}
import org.adk4s.core.component.{Tool, InvokableTool}

/**
 * Configuration for ToolsNode.
 */
case class ToolsNodeConfig(
  /**
   * Tools available for execution.
   * Can be LLM4S ToolFunctions or ADK4S Tools.
   */
  tools: List[Either[ToolFunction, InvokableTool[IO]]] = Nil,

  /**
   * Handler for unknown tool calls.
   * If None, unknown tools raise an error.
   */
  unknownToolHandler: Option[(String, String) => IO[String]] = None,

  /**
   * Whether to execute tools sequentially or in parallel.
   */
  executeSequentially: Boolean = false,

  /**
   * Middleware to apply to each tool execution.
   */
  middlewares: List[ToolMiddleware] = Nil,

  /**
   * Handler to preprocess tool arguments before execution.
   */
  argumentsHandler: Option[(String, String) => IO[String]] = None,

  /**
   * Maximum concurrent tool executions (for parallel mode).
   */
  maxConcurrency: Int = 10
):
  /**
   * Get LLM4S ToolRegistry for tools that are ToolFunctions.
   */
  def toToolRegistry: ToolRegistry =
    ToolRegistry(tools.collect { case Left(tf) => tf })

  /**
   * Get ADK4S tools.
   */
  def adkTools: List[InvokableTool[IO]] =
    tools.collect { case Right(t) => t }

object ToolsNodeConfig:
  /**
   * Create config from LLM4S ToolRegistry.
   */
  def fromRegistry(registry: ToolRegistry): ToolsNodeConfig =
    ToolsNodeConfig(tools = registry.tools.map(Left(_)))

  /**
   * Create config from LLM4S ToolFunctions.
   */
  def fromToolFunctions(tools: List[ToolFunction]): ToolsNodeConfig =
    ToolsNodeConfig(tools = tools.map(Left(_)))

  /**
   * Create config from ADK4S Tools.
   */
  def fromAdkTools(tools: List[InvokableTool[IO]]): ToolsNodeConfig =
    ToolsNodeConfig(tools = tools.map(Right(_)))

  /**
   * Builder pattern for config.
   */
  def builder: ToolsNodeConfigBuilder = ToolsNodeConfigBuilder()

case class ToolsNodeConfigBuilder(
  private val config: ToolsNodeConfig = ToolsNodeConfig()
):
  def withTool(tool: ToolFunction): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Left(tool)))

  def withAdkTool(tool: InvokableTool[IO]): ToolsNodeConfigBuilder =
    copy(config = config.copy(tools = config.tools :+ Right(tool)))

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
```

**Testing**:
- Test config creation from different sources
- Test builder pattern
- Test tool registry conversion

---

### Task 4: Create ToolsNode

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/tools/ToolsNode.scala`

**Purpose**: Execute tool calls from LLM responses

**API Design**:
```scala
package org.adk4s.agent.tools

import fs2.Stream
import cats.effect.IO
import cats.syntax.all.*
import cats.data.Kleisli
import org.llm4s.llmconnect.model.{ToolCall, ToolMessage}
import org.llm4s.tools.ToolFunction
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.runnable.Runnable
import org.adk4s.core.error.{AdkError, ToolNotFoundError, ToolExecutionError}
import io.circe.parser

/**
 * ToolsNode executes tool calls from LLM responses.
 *
 * Input: List of ToolCalls (from AssistantMessage)
 * Output: List of ToolOutputs (to be sent back to LLM)
 */
class ToolsNode(config: ToolsNodeConfig):
  private val registry = config.toToolRegistry
  private val adkTools = config.adkTools
  private val middleware = ToolMiddleware.compose(config.middlewares)

  /**
   * Execute a single tool call.
   */
  def executeTool(input: ToolInput): IO[ToolOutput] =
    val endpoint = createEndpoint(input.name)
    middleware(endpoint).run(input)

  /**
   * Execute multiple tool calls.
   */
  def executeTools(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    if config.executeSequentially then
      executeSequentially(inputs)
    else
      executeParallel(inputs)

  /**
   * Execute tool calls from ToolCall objects.
   */
  def executeFromToolCalls(calls: List[ToolCall]): IO[ToolExecutionResult] =
    executeTools(ToolInput.fromToolCalls(calls))

  /**
   * Convert to Runnable.
   */
  def toRunnable: Runnable[List[ToolCall], List[ToolMessage]] =
    Runnable.fromInvoke { calls =>
      executeFromToolCalls(calls).map(_.toLlm4sMessages)
    }

  private def createEndpoint(toolName: String): ToolEndpoint =
    Kleisli { input =>
      for
        // Preprocess arguments if handler provided
        processedArgs <- config.argumentsHandler match
          case Some(handler) => handler(input.name, input.arguments)
          case None => IO.pure(input.arguments)

        // Find and execute tool
        result <- findTool(toolName) match
          case Some(Left(llm4sTool)) =>
            executeLlm4sTool(llm4sTool, input.copy(arguments = processedArgs))
          case Some(Right(adkTool)) =>
            executeAdkTool(adkTool, input.copy(arguments = processedArgs))
          case None =>
            handleUnknownTool(input)
      yield result
    }

  private def findTool(name: String): Option[Either[ToolFunction, InvokableTool[IO]]] =
    // First check LLM4S registry
    registry.tools.find(_.name == name).map(Left(_))
      .orElse(adkTools.find(t => ???).map(Right(_))) // TODO: need tool name accessor

  private def executeLlm4sTool(tool: ToolFunction, input: ToolInput): IO[ToolOutput] =
    for
      params <- parseArguments(input.arguments)
      result <- tool.handler(params) match
        case Right(output) => IO.pure(ToolOutput(input.name, output, input.callId))
        case Left(error) => IO.pure(ToolOutput(input.name, error, input.callId, isError = true))
    yield result

  private def executeAdkTool(tool: InvokableTool[IO], input: ToolInput): IO[ToolOutput] =
    tool.run(input.arguments)
      .map(result => ToolOutput(input.name, result, input.callId))
      .handleError(e => ToolOutput(input.name, e.getMessage, input.callId, isError = true))

  private def handleUnknownTool(input: ToolInput): IO[ToolOutput] =
    config.unknownToolHandler match
      case Some(handler) =>
        handler(input.name, input.arguments)
          .map(result => ToolOutput(input.name, result, input.callId))
      case None =>
        IO.pure(ToolOutput(input.name, s"Unknown tool: ${input.name}", input.callId, isError = true))

  private def parseArguments(args: String): IO[Map[String, Any]] =
    IO(parser.parse(args).toOption
      .flatMap(_.as[Map[String, Any]].toOption)
      .getOrElse(Map.empty))

  private def executeSequentially(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    inputs.foldLeftM(ToolExecutionResult(Nil)) { (acc, input) =>
      executeTool(input).attempt.map {
        case Right(output) =>
          acc.copy(outputs = acc.outputs :+ output)
        case Left(error) =>
          acc.copy(failedTools = acc.failedTools :+ ToolExecutionFailure(input, error))
      }
    }

  private def executeParallel(inputs: List[ToolInput]): IO[ToolExecutionResult] =
    fs2.Stream.emits(inputs)
      .parEvalMap(config.maxConcurrency) { input =>
        executeTool(input).attempt.map(input -> _)
      }
      .compile.toList
      .map { results =>
        val (successes, failures) = results.partitionMap {
          case (_, Right(output)) => Left(output)
          case (input, Left(error)) => Right(ToolExecutionFailure(input, error))
        }
        ToolExecutionResult(successes, failures)
      }

object ToolsNode:
  /**
   * Create ToolsNode with configuration.
   */
  def apply(config: ToolsNodeConfig): ToolsNode =
    new ToolsNode(config)

  /**
   * Create ToolsNode from LLM4S ToolFunctions.
   */
  def fromTools(tools: List[ToolFunction]): ToolsNode =
    new ToolsNode(ToolsNodeConfig.fromToolFunctions(tools))

  /**
   * Create ToolsNode from LLM4S ToolRegistry.
   */
  def fromRegistry(registry: org.llm4s.tools.ToolRegistry): ToolsNode =
    new ToolsNode(ToolsNodeConfig.fromRegistry(registry))
```

**Testing**:
- Test single tool execution
- Test parallel execution
- Test sequential execution
- Test unknown tool handling
- Test middleware application
- Test error handling

---

### Task 5: Create ToolsNode Runnable Integration

**Location**: `adk4s-agent/src/main/scala/org/adk4s/agent/tools/ToolsNodeRunnable.scala`

**Purpose**: Make ToolsNode work with graph orchestration

**API Design**:
```scala
package org.adk4s.agent.tools

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.model.{ToolCall, ToolMessage, AssistantMessage}
import org.adk4s.core.runnable.Runnable
import org.adk4s.structured.core.{Message, Role}

/**
 * ToolsNode as a Runnable for graph integration.
 *
 * Input: AssistantMessage (with tool calls) or List[ToolCall]
 * Output: List[ToolMessage] (results to send back to LLM)
 */
object ToolsNodeRunnable:
  /**
   * Create Runnable that takes AssistantMessage and returns ToolMessages.
   */
  def fromAssistantMessage(node: ToolsNode): Runnable[AssistantMessage, List[ToolMessage]] =
    Runnable.fromInvoke { msg =>
      node.executeFromToolCalls(msg.toolCalls).map(_.toLlm4sMessages)
    }

  /**
   * Create Runnable that takes tool calls and returns ToolMessages.
   */
  def fromToolCalls(node: ToolsNode): Runnable[List[ToolCall], List[ToolMessage]] =
    node.toRunnable

  /**
   * Create Runnable that works with ADK4S Messages.
   */
  def fromAdkMessage(node: ToolsNode): Runnable[Message, List[Message]] =
    Runnable.fromInvoke { msg =>
      // Extract tool calls from message (would need to extend Message type)
      // For now, this is a placeholder
      IO.pure(Nil)
    }

  /**
   * Create streaming Runnable that emits results as they complete.
   */
  def streaming(node: ToolsNode): Runnable[List[ToolCall], ToolMessage] =
    Runnable.fromStream { calls =>
      Stream.emits(ToolInput.fromToolCalls(calls))
        .parEvalMap(node.config.maxConcurrency)(node.executeTool)
        .map(_.toLlm4sMessage)
    }

  extension (node: ToolsNode)
    def asRunnable: Runnable[List[ToolCall], List[ToolMessage]] =
      fromToolCalls(node)

    def asStreamingRunnable: Runnable[List[ToolCall], ToolMessage] =
      streaming(node)
```

**Testing**:
- Test Runnable from AssistantMessage
- Test Runnable from ToolCalls
- Test streaming Runnable

---

## File Structure

```
adk4s-agent/
└── src/
    ├── main/
    │   └── scala/
    │       └── org/
    │           └── adk4s/
    │               └── agent/
    │                   └── tools/
    │                       ├── package.scala           # Exports
    │                       ├── ToolTypes.scala         # Input/Output types
    │                       ├── ToolMiddleware.scala    # Middleware pattern
    │                       ├── ToolsNodeConfig.scala   # Configuration
    │                       ├── ToolsNode.scala         # Main implementation
    │                       └── ToolsNodeRunnable.scala # Runnable integration
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── agent/
                        └── tools/
                            ├── ToolTypesTest.scala
                            ├── ToolMiddlewareTest.scala
                            ├── ToolsNodeConfigTest.scala
                            ├── ToolsNodeTest.scala
                            └── ToolsNodeRunnableTest.scala
```

## Testing Plan

### Unit Tests

1. **ToolTypes Tests**
   - ToolInput from ToolCall
   - ToolOutput to Message conversion
   - Batch result handling

2. **ToolMiddleware Tests**
   - Logging middleware logs correctly
   - Timing middleware records time
   - Validation middleware validates
   - Retry middleware retries
   - Composition works correctly

3. **ToolsNode Tests**
   - Single tool execution
   - Multiple tools parallel execution
   - Multiple tools sequential execution
   - Unknown tool handling
   - Middleware application
   - Error handling and recovery

4. **Integration Tests**
   - Test with real LLM4S tools
   - Test full flow: LLM response -> tool execution -> results

## Examples

### Basic Usage

```scala
import org.adk4s.agent.tools.*
import org.llm4s.tools.{ToolBuilder, StringSchema}

// Create tools
val weatherTool = ToolBuilder("get_weather")
  .description("Get weather for location")
  .parameter("location", StringSchema("City name"))
  .handler { params =>
    val location = params.getString("location").getOrElse("Unknown")
    Right(s"Weather in $location: Sunny, 25C")
  }
  .build

// Create ToolsNode
val toolsNode = ToolsNode.fromTools(List(weatherTool))

// Execute tool calls from LLM
val toolCalls: List[ToolCall] = assistantMessage.toolCalls
val results: IO[ToolExecutionResult] = toolsNode.executeFromToolCalls(toolCalls)
```

### With Middleware

```scala
val config = ToolsNodeConfig.builder
  .withTool(weatherTool)
  .withTool(calculatorTool)
  .withMiddleware(ToolMiddleware.logging(msg => IO.println(msg)))
  .withMiddleware(ToolMiddleware.timing((name, ms) => IO.println(s"$name took ${ms}ms")))
  .withUnknownHandler { (name, args) =>
    IO.pure(s"Tool '$name' not available")
  }
  .parallel(maxConcurrency = 5)
  .build

val toolsNode = ToolsNode(config)
```

### As Graph Node

```scala
// Use in graph orchestration
val toolsRunnable = toolsNode.asRunnable

// Or streaming version
val streamingTools = toolsNode.asStreamingRunnable
```

## Completion Criteria

- [ ] ToolInput/ToolOutput types implemented
- [ ] ToolMiddleware pattern complete
- [ ] ToolsNodeConfig with builder pattern
- [ ] ToolsNode with parallel and sequential execution
- [ ] Middleware support working
- [ ] Unknown tool handling working
- [ ] Runnable integration complete
- [ ] Unit tests passing with >90% coverage
- [ ] Documentation updated
