# ADK4S Agent - Tools Node

A tool execution node for LLM-based agents with support for ReAct patterns and function calling workflows.

## Features

- **Tool Execution**: Execute tool calls from LLM responses
- **Parallel/Sequential Execution**: Configurable execution modes with concurrency limits
- **Middleware Support**: Composable middleware for logging, timing, validation, and retry
- **Type Safety**: Support for both LLM4S ToolFunctions and ADK4S InvokableTools
- **Runnable Integration**: Seamless integration with graph orchestration and workflows
- **Error Handling**: Graceful error handling with custom unknown tool handlers

## Usage

### Basic Usage

```scala
import org.adk4s.agent.tools.*
import org.adk4s.core.component.Tool

// Create a tool
val weatherTool = Tool.invokable[IO](
  name = "get_weather",
  description = "Get weather for a location",
  handler = args => Right(s"Weather in ${args.obj("location").str}")
)

// Create ToolsNode
val toolsNode = ToolsNode.fromAdkTools(List(weatherTool))

// Execute tool calls
val result: IO[ToolExecutionResult] = 
  toolsNode.executeFromToolCalls(llmToolCalls)
```

### With Middleware

```scala
val config = ToolsNodeConfig.builder
  .withAdkTool(weatherTool)
  .withAdkTool(calculatorTool)
  .withMiddleware(ToolMiddleware.logging(msg => IO.println(msg)))
  .withMiddleware(ToolMiddleware.timing((name, ms) => 
    IO.println(s"$name took ${ms}ms")))
  .withUnknownHandler { (name, args) =>
    IO.pure(s"Tool '$name' not available")
  }
  .parallel(maxConcurrency = 5)
  .build

val toolsNode = ToolsNode(config)
```

### As Graph Node

```scala
val toolsRunnable = toolsNode.asRunnable

val streamingTools = toolsNode.asStreamingRunnable
```

## Components

### ToolInput/ToolOutput

Types for tool execution inputs and outputs with conversion methods.

### ToolMiddleware

Middleware pattern for cross-cutting concerns:
- `logging`: Log tool calls and results
- `timing`: Record execution time
- `validation`: Pre-validate inputs
- `retry`: Retry failed executions with exponential backoff

### ToolsNodeConfig

Configuration with builder pattern for:
- Registering tools (LLM4S and ADK4S)
- Setting execution mode (sequential/parallel)
- Configuring middleware
- Setting unknown tool handlers
- Setting argument preprocessors

### ToolsNode

Main execution node with:
- Single and batch tool execution
- Sequential and parallel execution modes
- Error handling and recovery
- LLM4S and ADK4S tool support

### ToolsNodeRunnable

Runnable wrappers for graph orchestration:
- `fromToolCalls`: Batch execution
- `streaming`: Real-time result streaming
- Extension methods: `asRunnable`, `asStreamingRunnable`
