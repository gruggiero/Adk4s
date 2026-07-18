# Concept: ToolsNode

## Concept specification

```
concept ToolsNode
purpose
    Execute a batch of LLM tool calls with composable middleware, parallel
    or sequential strategy, unknown-tool handling, and interrupt propagation.
state
    config: ToolsNode -> ToolsNodeConfig
    llm4sTools: ToolsNode -> List[ToolWrapper]
    adkTools: ToolsNode -> List[InvokableTool[IO]]
    middleware: ToolsNode -> ToolEndpoint
actions
    execute [ input: ToolInput ]
        => [ output: ToolOutput ]
    execute [ input: ToolInput ]
        => [ interrupt: AgentInterruptedException ]            # re-raised
    execute [ input: ToolInput ]
        => [ output: ToolOutput(isError=true, e.getMessage) ]  # other errors
    executeTools [ inputs: List[ToolInput] ]
        => [ result: ToolExecutionResult ]
    executeFromToolCalls [ calls: List[ToolCall] ]
        => [ result: ToolExecutionResult ]
    handleUnknownTool [ input: ToolInput ]
        => [ output: ToolOutput(isError=true, "Unknown tool: <name>") ]   # no handler
operational principle
    Given a list of ToolCalls from an LLM, the caller invokes
    executeFromToolCalls; the node resolves each call to a registered tool
    (llm4s ToolFunction or ADK InvokableTool), runs it through the composed
    middleware chain, and returns a ToolExecutionResult aggregating outputs,
    failures, and an optional interruptSignal. In sequential mode the first
    interrupt stops remaining tools; in parallel mode multiple interrupts are
    composed into InterruptSignal.Composite.
```

## Implementation map

| Element | Code |
|---|---|
| state `config` | `ToolsNodeConfig` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala`) |
| state `llm4sTools` | `config.tools.collect { case Left(tw) => tw }` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| state `adkTools` | `config.tools.collect { case Right(t) => t }` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| state `middleware` | `ToolMiddleware.compose(config.middlewares)` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `execute` | `ToolsNode.executeTool(input): IO[ToolOutput]` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `executeTools` | `ToolsNode.executeTools(inputs): IO[ToolExecutionResult]` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `executeFromToolCalls` | `ToolsNode.executeFromToolCalls(calls): IO[ToolExecutionResult]` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `executeSequentially` | `ToolsNode.executeSequentially` skips remaining on interruptSignal (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `executeParallel` | `ToolsNode.executeParallel` composes multiple interrupts via `InterruptSignal.composite` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| action `handleUnknownTool` | `ToolsNode.handleUnknownTool` returns `ToolOutput(isError=true, "Unknown tool: <name>")` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| guard `MaxArgBytes` | `64 * 1024` hardcoded; rejects with `"Arguments too large: <n>b > <MaxArgBytes>"` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`) |
| runtime host | `org.adk4s.core.tools` |

## Synchronizations

```
sync ToolCallsToToolsNode
when {
    ReactAgent/iterate: produces List[ToolCall]
}
then {
    ToolsNode/executeFromToolCalls: calls -> ToolExecutionResult
}
```

impl: `ReactAgent.executeToolCalls` calls `toolsNode.executeFromToolCalls(toolCalls)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
Deviation: tight construction-time coupling — `ReactAgent` builds the `ToolsNode` from its own tool list, so the node cannot be reconfigured per call.

## Deviations from the pattern

- `ToolsNode` pattern-matches on `AgentTool` to inject a scoped `AgentEventEmitter`, violating uniform tool handling (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`).
- `ToolWrapper` stores `toolFunction: ToolFunction[?, ?]` with existential types, losing type safety; its `execute` wraps errors as generic `RuntimeException("Tool execution error: <message>", cause)` rather than `ToolExecutionError` (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala`).
- `MaxArgBytes = 64 * 1024` is hardcoded and not configurable per-tool (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`).
- Parallel interrupt composition uses `InterruptSignal.composite(info, ujson.Obj(), multiple)` with an empty state object (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`).
- Event emission is performed inline in the execution path rather than via a pure effect (`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNode.scala`).
