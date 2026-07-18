# Concept: CheckpointStore

## Concept specification

```
concept CheckpointStore
purpose
    A key-value store for persisting agent run checkpoint data across
    interrupt/resume cycles.
state
    store: InMemoryCheckpointStore -> Ref[IO, Map[String, Array[Byte]]]
actions
    get [ checkpointId: String ]
        => [ data: Option[Array[Byte]] ]
    set [ checkpointId: String ; data: Array[Byte] ]
        => [ Unit ]
    delete [ checkpointId: String ]
        => [ Unit ]
    keys
        => [ ids: List[String] ]
operational principle
    On interrupt, AgentRunner serializes the run state to bytes and calls
    set under a UUID checkpointId. On resume, it calls get with the same
    id; if present, it deserializes and re-runs; on success it calls
    delete. The InMemory implementation loses data on process exit.
```

## Implementation map

| Element | Code |
|---|---|
| trait `CheckpointStore` | `trait CheckpointStore` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| action `get` | `CheckpointStore.get(checkpointId): IO[Option[Array[Byte]]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| action `set` | `CheckpointStore.set(checkpointId, data): IO[Unit]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| action `delete` | `CheckpointStore.delete(checkpointId): IO[Unit]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| action `keys` | `CheckpointStore.keys: IO[List[String]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| impl `InMemoryCheckpointStore` | `object InMemoryCheckpointStore` backed by `Ref[IO, Map[String, Array[Byte]]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| factory `create` | `InMemoryCheckpointStore.create: IO[CheckpointStore]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| node `InterruptibleNode` | `class InterruptibleNode` uses CheckpointStore for interrupt state (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/InterruptibleNode.scala`) |
| runtime host | `org.adk4s.orchestration.interrupt` |

## Deviations from the pattern

- The only shipped implementation is `InMemoryCheckpointStore`, which loses all data on process exit — there is no persistent implementation in the repo (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`).
- All operations assume success; `Ref` operations cannot fail, but a real persistent implementation would need error handling that the trait does not prescribe (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`).
- No size limit or eviction policy — the in-memory map grows unbounded across runs (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`).
