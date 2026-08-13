# Concept: ReactAgent

## Concept specification

```
concept ReactAgent
purpose
    Run a ReAct (Reasoning + Acting) loop: call the LLM, execute any tool
    calls, feed results back, and repeat until the LLM produces a final
    message with no tool calls or maxSteps is exhausted. As of the
    harness-api refactor, ReactAgent is sugar over HarnessAgent[IO] with
    MiddlewareStack.empty — the loop body lives in HarnessAgent.
state
    name: ReactAgent -> String
    description: ReactAgent -> String
    model: ReactAgent -> ChatModel[IO]
    tools: ReactAgent -> List[InvokableTool[IO]]
    systemPrompt: ReactAgent -> Option[String]
    maxSteps: ReactAgent -> Int (refined to Positive at internal boundary via refineEither[Positive])
    emitter: ReactAgent -> Option[AgentEventEmitter]
    harness: ReactAgent -> HarnessAgent[IO]
actions
    create [ model ; tools ; systemPrompt? ; maxSteps? ]
        => [ agent: ReactAgent ]
    create [ name ; description ; model ; tools ; systemPrompt ; maxSteps ]
        => [ agent: ReactAgent ]
    create [ name ; description ; model ; tools ; systemPrompt ; maxSteps ; emitter ]
        => [ agent: ReactAgent ]
    createWithToolProvider [ model ; toolProvider ; systemPrompt? ; maxSteps? ]
        => [ agent: ReactAgent ]
    generate [ messages: List[Message] ; maxSteps: Int ]
        => [ message: AssistantMessage ]
    generate [ messages ; maxSteps ]
        => [ error: MaxStepsExceededError(steps, max) ]
    generate [ messages ; maxSteps ]
        => [ error: AgentInterruptedException(signal) ]
    stream [ messages ; maxSteps ]
        => [ chunks: Stream[IO, StreamedChunk] ]
operational principle
    ReactAgent.create constructs a HarnessAgent[IO] with
    MiddlewareStack.empty and wraps it in a ReactAgentAdapter. The adapter
    delegates generate/stream to the harness, adapting HarnessResult back
    to IO[AssistantMessage] (raising MaxStepsExceededError on Failed,
    AgentInterruptedException on Interrupted). The loop itself runs in
    HarnessAgent: initialize state from HarnessState.initial(empty),
    run stack.beforeAgent (identity for empty stack), iterate — build
    ModelRequest with per-request tools and prompt, run
    stack.wrapModelCall(baseModelStep), execute tool calls through
    stack.wrapToolCall(baseToolStep), merge state, append messages,
    recurse until no tool calls or maxSteps. On normal termination run
    stack.afterAgent (identity for empty stack). On
    AgentInterruptedException, snapshot state without afterAgent.
```

## Implementation map

| Element | Code |
|---|---|
| trait `ReactAgent` | `trait ReactAgent extends Agent` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| adapter `ReactAgentAdapter` | `private final class ReactAgentAdapter(harness: HarnessAgent[IO])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| impl `DynamicReactAgentImpl` | `private final class DynamicReactAgentImpl` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| state `Config` | `final case class Config(name, description, model, tools, systemPrompt, maxSteps, emitter)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `create` | `ReactAgent.create(...)` → `fromHarnessConfig(config)` → `HarnessAgent[IO](harnessConfig)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `createWithToolProvider` | `ReactAgent.createWithToolProvider(...)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `generate` | `ReactAgentAdapter.generate` → `harness.generate` → adapt `HarnessResult` to `IO[AssistantMessage]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| action `stream` | `ReactAgentAdapter.stream` → `harness.stream` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`) |
| loop body | `HarnessAgent.loop` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`) |
| error `maxSteps` | `MaxStepsExceededError(steps, max)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| error `interrupt` | `AgentInterruptedException(signal)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.orchestration.agent` |

## Synchronizations

```
sync ToolCallsToHarnessAgent
when {
    HarnessAgent/iterate: assistantMsg.toolCalls nonEmpty
}
then {
    HarnessAgent/executeToolCalls: toolCalls -> (List[ToolMessage], HarnessState, Option[InterruptSignal])
}
```

impl: `HarnessAgent.executeToolCalls` runs each tool call through `stack.wrapToolCall(baseToolStep)`, merges states, and catches `AgentInterruptedException` to produce an interrupted outcome (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`).

```
sync ToolResultToConversation
when {
    HarnessAgent/executeToolCalls: returns no interruptSignal
}
then {
    HarnessAgent/loop: appends assistantMsg + tool messages, recurses with remainingSteps - 1
}
```

impl: `HarnessAgent.loop` appends `assistantMsg +: toolMessages` to the conversation and recurses (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`).

```
sync EventScopeEmission
when {
    HarnessAgent/iterate: produces an AgentEvent
}
then {
    AgentEventEmitter/emit: attaches the event
}
```

impl: `HarnessAgent.emitEvent` calls `emitter.fold(IO.unit)(_(event))` for `MessageOutput`, `ToolCallRequested`, `ToolCallCompleted`, `IterationCompleted` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`).

## Deviations from the pattern

- `stream` executes the entire tool loop via `generate` before streaming the final response — the tool-calling phase is not streamed (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`).
- `maxSteps exceeded` now raises `MaxStepsExceededError` (structured `AdkError`) instead of the previous generic `RuntimeException` — a behavior improvement from the refactor (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/HarnessAgent.scala`).
- `DynamicReactAgentImpl` recreates the inner `ReactAgent` on each `generate` call from the current tool list, losing per-invocation state (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
- `ReactAgent.create` is sugar for the empty-stack path; callers needing middleware MUST construct `HarnessAgent` directly — `ReactAgent.create` does not accept a `MiddlewareStack` argument (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/ReactAgent.scala`).
