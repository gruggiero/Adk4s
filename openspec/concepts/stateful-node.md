# Concept: StatefulNode

## Concept specification

```
concept StatefulNode[I, O, S]
purpose
    Wrap a Runnable with pre/post state handlers that read or update a
    StateRef[IO, S] before/after each execution mode.
state
    inner: StatefulNode -> Runnable[I, O]
    stateRef: StatefulNode -> StateRef[IO, S]
    config: StatefulNode -> StatefulNodeConfig[I, O, S]
actions
    invoke [ input: I ]
        => [ output: O ]   # preHandler? -> inner.invoke -> postHandler?
    stream [ input: I ]
        => [ chunks: Stream[IO, O] ]
    collect [ input: Stream[IO, I] ]
        => [ output: O ]
    transform [ input: Stream[IO, I] ]
        => [ chunks: Stream[IO, O] ]
    wrap [ runnable ; stateRef ; config ]
        => [ node: StatefulNode[I, O, S] ]
    withPre [ runnable ; stateRef ; preHandler ]
        => [ node: StatefulNode ]
    withPost [ runnable ; stateRef ; postHandler ]
        => [ node: StatefulNode ]
operational principle
    A caller wraps a Runnable with a StateRef and optional pre/post
    handlers. Each execution mode applies the matching handler (stream
    handlers for stream/transform, scalar handlers for invoke/collect)
    around the inner Runnable, threading state through the StateRef.
```

## Implementation map

| Element | Code |
|---|---|
| class `StatefulNode` | `class StatefulNode[I, O, S](inner, stateRef, config)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| config `StatefulNodeConfig` | `case class StatefulNodeConfig[I, O, S](preHandler, postHandler, streamPreHandler, streamPostHandler)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| action `invoke` | `StatefulNode.invoke(input): IO[O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| action `stream` | `StatefulNode.stream(input): Stream[IO, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| action `collect` | `StatefulNode.collect(input): IO[O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| action `transform` | `StatefulNode.transform(input): Stream[IO, O]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| factory `wrap` | `StatefulNode.wrap(runnable, stateRef, config)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| factory `withPre` | `StatefulNode.withPre(runnable, stateRef, preHandler)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| factory `withPost` | `StatefulNode.withPost(runnable, stateRef, postHandler)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`) |
| state `StateRef` | `trait StateRef[F[_], S]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateRef.scala`) |
| handlers `StateHandlers` | `object StateHandlers` with `PreHandler`, `PostHandler`, `StreamPreHandler`, `StreamPostHandler` aliases (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateHandlers.scala`) |
| event-sourced `EventSourcedState` | `trait AdkWorkflowContext extends WorkflowContext` with `AgentStateContext` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/EventSourcedState.scala`) |
| runtime host | `org.adk4s.orchestration.state` |

## Deviations from the pattern

- `StateRef` is explicitly mutable (wraps `Ref[IO, S]`); `StateRef.empty` is a no-op implementation that ignores all operations (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateRef.scala`).
- `StatefulNodeConfig` allows mixing scalar and stream handlers without validating consistency — a config with `preHandler` but no `streamPreHandler` will silently skip the pre-handler in stream/transform mode (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`).
- `EventSourcedState.AgentStateContext.applyEvent` pattern-matches on events but does not validate event ordering — a `StepCompleted` before any `MessageAdded` produces a state with `stepCount > 0` and empty messages (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/EventSourcedState.scala`).
