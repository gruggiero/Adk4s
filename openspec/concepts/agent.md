# Concept: Agent

## Concept specification

```
concept Agent
purpose
    Minimal agent interface: produce an assistant message from a list of
    input messages, bounded by maxSteps. Implemented by ReactAgent and
    by AgentTool-wrapped functions.
state
    name: Agent -> String
    description: Agent -> String
actions
    generate [ messages: List[Message] ; maxSteps: Int ]
        => [ message: AssistantMessage ]
operational principle
    A caller supplies the conversation so far and a step budget; the agent
    returns an AssistantMessage. The interface fixes the effect to IO and
    prescribes no state contract — implementations own their state.
```

## Implementation map

| Element | Code |
|---|---|
| trait `Agent` | `trait Agent` (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`) |
| action `name` | `Agent.name: String` (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`) |
| action `description` | `Agent.description: String` (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`) |
| action `generate` | `Agent.generate(messages, maxSteps): IO[AssistantMessage]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`) |
| runtime host | `org.adk4s.core.component` (implemented by `ReactAgent` in `adk4s-orchestration`) |

## Deviations from the pattern

- The interface is fixed to `IO`, not effect-polymorphic like `ChatModel` and `Tool` (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`).
- No state contract is prescribed; implementations (ReactAgent, AgentTool) define their own state, making the abstraction's behavioral guarantees implicit.
- The interface directly depends on llm4s message types (`Message`, `AssistantMessage`), coupling the abstraction to a transport library (`adk4s-core/src/main/scala/org/adk4s/core/component/Agent.scala`).
