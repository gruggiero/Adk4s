# Concept: ReactAgent

## Concept specification

```
concept ReactAgent
purpose
    Run a ReAct (Reasoning + Acting) loop: call the LLM, execute any tool
    calls, feed results back, and repeat until the LLM produces a final
    message with no tool calls or maxSteps is exhausted.
state
    name: ReactAgent -> String
    description: ReactAgent -> String
    model: ReactAgent -> ChatModel[IO]
    tools: ReactAgent -> List[InvokableTool[IO]]
    systemPrompt: ReactAgent -> Option[String]
    maxSteps: ReactAgent -> Int
    emitter: ReactAgent -> Option[AgentEventEmitter]
    toolsNode: ReactAgent -> ToolsNode
actions
    create [ model ; tools ; systemPrompt? ; maxSteps? ]
        => [ agent: ReactAgent ]
    createWithToolProvider [ model ; toolProvider ; systemPrompt? ; maxSteps? ]
        => [ agent: ReactAgent ]
    generate [ messages: List[Message] ; maxSteps: Int ]
        => [ message: AssistantMessage ]
    generate [ messages ; maxSteps ]
        => [ error: RuntimeException("ReactAgent: max steps exceeded") ]
    generate [ messages ; maxSteps ]
        => [ error: AgentInterruptedException(signal) ]
    stream [ messages ; maxSteps ]
        => [ chunks: Stream[IO, StreamedChunk] ]
operational principle
    Starting from the system prompt and user messages, the agent calls
    model.generate; if the assistant message has no tool calls it emits
    MessageOutput and returns. Otherwise it emits ToolCallRequested events,
    executes the tool calls via ToolsNode, emits ToolCallCompleted events,
    appends the tool messages to the conversation, and recurses with one
    fewer remaining step. When remainingSteps reaches zero the loop raises
    "ReactAgent: max steps exceeded".
```

## Implementation map

| Element | Code |
|---|---|
| trait `ReactAgent` | `trait ReactAgent extends Agent` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| impl `ReactAgentImpl` | `private final class ReactAgentImpl(config: Config)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| impl `DynamicReactAgentImpl` | `private final class DynamicReactAgentImpl` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| state `Config` | `final case class Config(name, description, model, tools, systemPrompt, maxSteps, emitter)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `create` | `ReactAgent.create(...)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `createWithToolProvider` | `ReactAgent.createWithToolProvider(...)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `generate` | `ReactAgentImpl.generate(messages, maxSteps): IO[AssistantMessage]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `stream` | `ReactAgentImpl.stream(messages, maxSteps): Stream[IO, StreamedChunk]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `executeToolCalls` | `ReactAgentImpl.executeToolCalls(toolCalls): IO[List[ToolMessage]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| error `maxSteps` | `RuntimeException("ReactAgent: max steps exceeded")` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| error `interrupt` | `AgentInterruptedException(signal)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.orchestration.agent` |

## Synchronizations

```
sync ToolCallsToToolsNode
when {
    ReactAgent/iterate: assistantMsg.toolCalls nonEmpty
}
then {
    ToolsNode/executeFromToolCalls: toolCalls -> ToolExecutionResult
}
```

impl: `ReactAgent.executeToolCalls` calls `toolsNode.executeFromToolCalls(toolCalls)` and re-raises `AgentInterruptedException` when `result.interruptSignal.isDefined` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
Deviation: the node is constructed once from `config.tools`; tools cannot be added per call without `createWithToolProvider`.

```
sync ToolResultToConversation
when {
    ToolsNode/execute: returns ToolExecutionResult with no interruptSignal
}
then {
    ReactAgent/iterate: appends assistantMsg + tool messages, recurses with remainingSteps - 1
}
```

impl: `ReactAgentImpl.generateLoop` appends `assistantMsg +: result.toLlm4sMessages()` to the conversation and recurses (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).

```
sync EventScopeEmission
when {
    ReactAgent/iterate: produces an AgentEvent
}
then {
    AgentEventEmitter/scoped: attaches the current RunPath
}
```

impl: `emitEvent` calls `emitter.foreach(_.emit(event))` for `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).

## Deviations from the pattern

- `stream` executes the entire tool loop via `resolveToolLoops` (non-streaming) before streaming the final response — the tool-calling phase is not streamed (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
- `maxSteps exceeded` raises a generic `RuntimeException` rather than the structured `MaxStepsExceededError` present in the `AdkError` hierarchy (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
- `DynamicReactAgentImpl` recreates the inner `ReactAgent` on each `generate` call from the current tool list, losing per-invocation state (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
