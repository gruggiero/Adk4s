# Concept: AgentEventStream

## Concept specification

```
concept AgentEventStream
purpose
    Observable, hierarchically-scoped events emitted during agent execution,
    backed by an fs2 Queue and scoped by RunPath.
state
    queue: AgentEventEmitter -> Queue[IO, Option[AgentEvent]]
    scopeStep: AgentEventEmitter -> Option[RunStep]
actions
    emit [ event: AgentEvent ]
        => [ Unit ]   # prepends scopeStep to event.runPath if present
    subscribe
        => [ Stream[IO, AgentEvent] ]   # terminates on complete
    complete
        => [ Unit ]   # offers None, terminating all subscribers
    scoped [ step: RunStep ]
        => [ AgentEventEmitter ]   # child sharing the same queue
operational principle
    A runner creates an AgentEventEmitter, hands scoped children to nested
    agents/tools, and calls emit for each AgentEvent. Subscribers receive
    events in order; calling complete offers None and terminates the
    stream. Each event carries a RunPath reflecting its scope.
```

## AgentEvent variants

- `MessageOutput(runPath, message, role)`
- `ToolCallRequested(runPath, toolName, arguments, callId)`
- `ToolCallCompleted(runPath, toolName, result, callId, isError)`
- `IterationCompleted(runPath, iteration, remainingSteps)`
- `Interrupted(runPath, signal: InterruptSignal)`
- `ErrorOccurred(runPath, error: AdkError)`
- `TokenDelta(runPath, delta: String)`

## Implementation map

| Element | Code |
|---|---|
| trait `AgentEvent` | `sealed trait AgentEvent` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `MessageOutput` | `final case class MessageOutput(runPath, message, role)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `ToolCallRequested` | `final case class ToolCallRequested(runPath, toolName, arguments, callId)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `ToolCallCompleted` | `final case class ToolCallCompleted(runPath, toolName, result, callId, isError)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `IterationCompleted` | `final case class IterationCompleted(runPath, iteration, remainingSteps)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `Interrupted` | `final case class Interrupted(runPath, signal)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `ErrorOccurred` | `final case class ErrorOccurred(runPath, error)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| variant `TokenDelta` | `final case class TokenDelta(runPath, delta)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEvent.scala`) |
| class `AgentEventEmitter` | `final class AgentEventEmitter(queue, scopeStep)` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| action `emit` | `AgentEventEmitter.emit(event): IO[Unit]` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| action `subscribe` | `AgentEventEmitter.subscribe: Stream[IO, AgentEvent]` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| action `complete` | `AgentEventEmitter.complete: IO[Unit]` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| action `scoped` | `AgentEventEmitter.scoped(step): AgentEventEmitter` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| factory `create` | `AgentEventEmitter.create(capacity: Int = 256): IO[AgentEventEmitter]` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`) |
| type `RunPath` | `RunPath` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/RunPath.scala`) |
| type `RunStep` | `RunStep` (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/RunPath.scala`) |
| runtime host | `org.adk4s.core.interrupt` |

## Deviations from the pattern

- Child emitters created by `scoped` share the parent's queue, coupling the scope hierarchy to a single termination point — `complete` on any emitter terminates all subscribers (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`).
- Termination uses `Option[AgentEvent]` with `None` as a sentinel, mixing event data with control signaling (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`).
- `withPrependedStep` is called at emit time only when `scopeStep.isDefined`; events emitted through the root emitter do not get scoped, so a downstream subscriber cannot distinguish root-emitted from child-emitted events by RunPath alone (`adk4s-core/src/main/scala/org/adk4s/core/interrupt/AgentEventEmitter.scala`).
