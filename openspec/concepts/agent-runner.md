# Concept: AgentRunner

## Concept specification

```
concept AgentRunner
purpose
    Execute a ReactAgent with interrupt/resume lifecycle against a
    CheckpointStore, returning a RunResult and optionally an event stream.
state
    agent: AgentRunner -> ReactAgent
    checkpointStore: AgentRunner -> CheckpointStore
    emitter: AgentRunner -> AgentEventEmitter
actions
    run [ messages: List[Message] ; maxSteps: Int ]
        => [ result: RunResult.Completed(output, messages) ]
    run [ messages ; maxSteps ]
        => [ result: RunResult.Interrupted(checkpointId, signal) ]
    run [ messages ; maxSteps ]
        => [ result: RunResult.Failed(AdkError) ]
    resume [ checkpointId: String ; results: List[InterruptResult] ; maxSteps: Int ]
        => [ result: RunResult ]
    resume [ checkpointId ; ... ]
        => [ result: RunResult.Failed(CheckpointNotFoundError(checkpointId)) ]
    runWithEvents [ messages ; maxSteps ]
        => [ (IO[RunResult], Stream[IO, AgentEvent]) ]
operational principle
    A caller invokes run with messages; on success the runner emits
    MessageOutput and returns Completed. On AgentInterruptedException it
    serializes the conversation and signal to a CheckpointState, stores it
    under a UUID checkpointId, emits Interrupted, and returns Interrupted.
    Later, resume loads the checkpoint, reconstructs the messages, appends
    the resume results as user messages, and re-runs; on Completed the
    checkpoint is deleted.
```

## Implementation map

| Element | Code |
|---|---|
| class `AgentRunner` | `final class AgentRunner(agent, checkpointStore, emitter)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| state `CheckpointState` | `private[agent] final case class CheckpointState(messages, signal)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| state `SerializableCheckpointMessage` | `private[agent] final case class SerializableCheckpointMessage(role, content)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| action `run` | `AgentRunner.run(messages, maxSteps): IO[RunResult]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| action `resume` | `AgentRunner.resume(checkpointId, results, maxSteps): IO[RunResult]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| action `runWithEvents` | `AgentRunner.runWithEvents(messages, maxSteps): (IO[RunResult], Stream[IO, AgentEvent])` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| factory `create` | `AgentRunner.create(agent, checkpointStore, emitter)` and `AgentRunner.create(agent, checkpointStore)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`) |
| error `CheckpointNotFoundError` | `CheckpointNotFoundError(checkpointId)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.orchestration.agent` |

## Synchronizations

```
sync InterruptToCheckpoint
when {
    ReactAgent/generate: raises AgentInterruptedException(signal)
}
where {
    CheckpointStore: Map[String, Array[Byte]]
}
then {
    CheckpointStore/set: checkpointId -> serialized CheckpointState
}
```

impl: `AgentRunner.run` catches `AgentInterruptedException`, generates a UUID `checkpointId`, serializes `CheckpointState(messages, signal)` via upickle, calls `checkpointStore.set(checkpointId, data)`, emits `Interrupted`, returns `RunResult.Interrupted(checkpointId, signal)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`).
Deviation: best-effort — if `checkpointStore.set` fails, the exception propagates and the interrupt is lost.

## Deviations from the pattern

- `resume` reconstructs messages via string matching on `role` (lossy: tool-call info lost), then appends resume data as user messages rather than routing to specific nested agents — a TODO comment admits "does not support complex composite interrupts with multiple nested agents" (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`).
- `resume` re-runs the entire agent from the reconstructed conversation; intermediate tool-execution state is not restored, only the message list (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`).
- On non-`AdkError` throwables, the runner wraps them as `GenericError` — but `AdkError` subclasses are matched first, so the wrap is order-dependent (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala`).
