# Concept: AgentTool

## Concept specification

```
concept AgentTool
purpose
    Wrap an Agent as an InvokableTool[IO] so a parent agent can delegate to
    it as a tool call, with state persistence across interrupt/resume.
state
    innerAgent: AgentTool -> Agent
    config: AgentTool -> AgentToolConfig
    stateRef: AgentTool -> Ref[IO, Option[AgentToolState]]
    emitter: AgentTool -> Option[AgentEventEmitter]
actions
    fromAgent [ agent: Agent ]
        => [ tool: AgentTool ]
    fromAgent [ agent ; config ; emitter? ]
        => [ tool: AgentTool ]
    fromFunction [ name ; description ; fn: (List[Message], Int) => IO[AssistantMessage] ]
        => [ tool: AgentTool ]
    run [ arguments: ujson.Value ]
        => [ result: ujson.Str(content) ]
    run [ arguments ]
        => [ error: AgentInterruptedException(Composite(...)) ]
operational principle
    A parent agent calls the AgentTool like any tool. The tool extracts the
    "request" string from arguments, builds a message list (resuming from
    saved state if present), calls innerAgent.generate, and returns the
    assistant content as ujson.Str. If the inner agent raises
    AgentInterruptedException, the tool saves its state to stateRef, wraps
    the inner signal in InterruptSignal.Composite with its own state, and
    re-raises.
```

## Implementation map

| Element | Code |
|---|---|
| class `AgentTool` | `final class AgentTool` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| state `AgentToolState` | `final case class AgentToolState(messages: List[SerializableMessage], iterationCount: Int)` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| state `AgentToolConfig` | `final case class AgentToolConfig(withFullChatHistory, inputSchema, maxSteps)` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| state `SerializableMessage` | `final case class SerializableMessage(role, content)` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `fromAgent` | `AgentTool.fromAgent(agent): IO[AgentTool]` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `fromReactAgent` | `AgentTool.fromReactAgent(...)` aliases `fromAgent` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `fromFunction` | `AgentTool.fromFunction(name, description, fn)` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `run` | `AgentTool.run(arguments): IO[ujson.Value]` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `executeInnerAgent` | `AgentTool.executeInnerAgent(messages): IO[String]` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| action `buildMessages` | `AgentTool.buildMessages(arguments, savedState): IO[List[Message]]` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`) |
| runtime host | `org.adk4s.core.component` |

## Synchronizations

```
sync AgentToolStateWrap
when {
    Agent/executeInnerAgent: raises AgentInterruptedException(innerSignal)
}
where {
    AgentTool/stateRef: Option[AgentToolState]
}
then {
    InterruptSignal/Composite: wraps innerSignal with AgentTool's own state
}
```

impl: `AgentTool.executeInnerAgent` catches `AgentInterruptedException`, saves `AgentToolState(messages.map(SerializableMessage.fromMessage), iterationCount = 0)` to `stateRef`, builds `InterruptSignal.Composite(address = List(AddressSegment.Agent(innerAgent.name)), info = "AgentTool '<name>' interrupted", state = agentToolStateJson, children = List(interrupted.signal))`, emits `AgentEvent.Interrupted`, and re-raises (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
Deviation: `iterationCount` is always set to 0, never incremented — the field is dead state.

## Deviations from the pattern

- `AgentToolConfig.withFullChatHistory` is documented as "not yet functional" and is ignored by `buildMessages` (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
- `AgentToolState.iterationCount` is always 0 — dead state (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
- `SerializableMessage.toMessage` uses string matching on `role` to reconstruct messages, losing tool-call information from assistant messages (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
- Fixed to `IO`, not effect-polymorphic (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
- `asToolFunction` always returns `None`, so AgentTool cannot be reconstructed into a llm4s `ToolRegistry` — it only works through `ToolsNode`'s ADK-tool path (`adk4s-core/src/main/scala/org/adk4s/core/component/AgentTool.scala`).
