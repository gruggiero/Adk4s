## Target Module
`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/wiograph/`

## IMPLEMENTED Requirements

### Requirement: Workflows4s Integration ✅
The system provides event-sourced state management through Workflows4s integration using explicit `WorkflowContext` state and event types. `WIORunIONode`, `WIOHandleSignalNode`, and `WIOAwaitNode` all use `EventHandler`/`SignalHandler` patterns from Workflows4s, ensuring replays do not re-execute side effects (this is inherited from the Workflows4s runtime).

#### Scenario: Emit event from runIO node ✅
- **GIVEN** a WIOGraph with a `WIORunIONode` producing an `Evt <: WCEvent[Ctx]`
- **WHEN** the graph executes through WIO
- **THEN** the event is emitted and persisted before applying the handler

#### Scenario: Replay does not re-run side effects ✅
- **GIVEN** a previously executed `WIORunIONode` with a stored Event
- **WHEN** the workflow is replayed
- **THEN** the stored Event is applied without re-executing the IO effect

#### Scenario: Await node emits timer event ✅
- **GIVEN** a WIOGraph with a `WIOAwaitNode`
- **WHEN** the timer completes
- **THEN** a `WIO.Timer.Released` event is emitted and applied to advance state

#### Scenario: Signal node emits signal event ✅
- **GIVEN** a WIOGraph with a `WIOHandleSignalNode`
- **WHEN** a signal request is received
- **THEN** a signal Event is emitted and applied to update state

### Requirement: Checkpoint Event Emission ✅
`CheckpointModifier` (applied via `WIOGraph.withCheckpoint`) wraps a node's WIO in `WIO.Checkpoint`, emitting a checkpoint event on successful completion. On replay, the checkpointed state is restored from the stored event without re-executing the body. This is inherited from Workflows4s `WIO.Checkpoint` semantics.

#### Scenario: Checkpoint modifier emits checkpoint event ✅
- **GIVEN** a WIOGraph with a node and a `CheckpointModifier` applied via `WIOGraph.withCheckpoint`
- **WHEN** the node completes successfully
- **THEN** the checkpoint event is emitted via the provided `genEvent` function
- **AND** on replay, the event is detected and applied via `handleEvent` without re-executing the body

### Requirement: Retry Event-Sourced Semantics ✅
`RetryModifier` (applied via `WIOGraph.withRetry`) wraps a node's WIO in `WIO.Retry`, providing retry behavior with event-sourced semantics. Retry timing and event emission are inherited from Workflows4s `WIO.Retry` semantics.

#### Scenario: Retry modifier wraps node with retry behavior ✅
- **GIVEN** a WIOGraph with a node and a `RetryModifier` applied via `WIOGraph.withRetry`
- **WHEN** the body fails
- **THEN** the retry policy (provided via `onError`) determines when to retry
- **AND** retry events are managed by Workflows4s runtime
