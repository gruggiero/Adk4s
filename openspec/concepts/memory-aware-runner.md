# Concept: MemoryAwareRunner

## Concept specification

```
concept MemoryAwareRunner
purpose
    Decorate an AgentRunner with opt-in memory awareness: recall relevant
    facts before each turn and persist new episodes after completed turns,
    without changing the underlying runner's behavior when memory is absent.
state
    agentRunner: MemoryAwareRunner -> AgentRunner
    memory: MemoryAwareRunner -> Option[AgentMemory[IO]]
    policy: MemoryAwareRunner -> MemoryPolicy
    hook: MemoryAwareRunner -> MemoryHook
actions
    run [ messages: List[Message] ; maxSteps: Int ]
        => [ result: RunResult.Completed(output, messages) ]
    run [ messages ; maxSteps ]
        => [ result: RunResult.Interrupted(checkpointId, signal) ]
    run [ messages ; maxSteps ]
        => [ result: RunResult.Failed(AdkError) ]
    resume [ checkpointId: String ; results: List[InterruptResult] ; maxSteps: Int ]
        => [ result: RunResult ]
    runWithEvents [ messages ; maxSteps ]
        => [ (IO[RunResult], Stream[IO, AgentEvent]) ]
operational principle
    A caller constructs a MemoryAwareRunner with an AgentRunner, an optional
    AgentMemory[IO], and a MemoryPolicy. On run, the decorator first calls
    hook.preTurn to recall the recallK most relevant facts for the latest
    user input; if recall yields non-empty hits, a UserMessage with the
    rendered context block is prepended to the messages before delegating to
    the underlying runner. After the run, if the RunResult is Completed,
    hook.postTurn persists the user input and/or assistant output as
    Conversation episodes sharing a groupId. On Interrupted or Failed, no
    episodes are written. When memory is None, preTurn and postTurn are
    no-ops and the decorator is behaviorally identical to the underlying
    runner. runWithEvents forwards the underlying event stream verbatim and
    wraps the result IO with preTurn/postTurn. resume delegates to the
    underlying runner after preTurn and applies postTurn only on Completed.
synchronizations
    RecallToContext:
        preTurn recalls via AgentMemory.recall(latestUserInput, recallK, scope)
        and renders hits via MemoryPolicy.render; non-empty render is injected
        as a prepended UserMessage before the underlying run.
    WriteEpisode:
        postTurn persists Episode(content, SourceType.Conversation, at,
        Some(groupId)) via AgentMemory.remember for user input and/or
        assistant output per MemoryPolicy write flags; fires only on
        RunResult.Completed.
```

## Implementation map

| Code identifier | Kind | File |
|-----------------|------|------|
| `MemoryPolicy` | final case class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryPolicy.scala` |
| `MemoryHook` | final class | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryHook.scala` |
| `MemoryAwareRunner` | final class (decorator) | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/memory/MemoryAwareRunner.scala` |

## Dependencies

- `AgentRunner` — the decorated runner (`org.adk4s.orchestration.agent`)
- `AgentMemory[F]` — the memory backend (`org.adk4s.memory`)
- `MemoryPolicy` — the configuration (this concept)
- `RunResult` — the result algebra (`org.adk4s.orchestration.agent`)
- `AgentEvent` / `AgentEventEmitter` — the event stream (forwarded verbatim; no memory events in this concept)
