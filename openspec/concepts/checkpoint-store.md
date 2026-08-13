# Concept: CheckpointStore

## Concept specification

```
concept CheckpointStore
purpose
    A key-value store for persisting agent run checkpoint data across
    interrupt/resume cycles. Effect-polymorphic over F[_]: Sync.
state
    store: InMemoryCheckpointStore[F] -> Ref[F, Map[String, Array[Byte]]]
actions
    get [ checkpointId: CheckpointId ]
        => [ data: F[Option[Array[Byte]]] ]
    set [ checkpointId: CheckpointId ; data: Array[Byte] ]
        => [ F[Unit] ]
    delete [ checkpointId: CheckpointId ]
        => [ F[Unit] ]
    keys
        => [ ids: F[List[CheckpointId]] ]
operational principle
    On interrupt, AgentRunner serializes the run state (CheckpointStateV2)
    to bytes and calls set under a UUID checkpointId. On resume, it calls
    get with the same id; if present, it deserializes CheckpointStateV2 and
    re-runs; on success it calls delete. The InMemory implementation loses
    data on process exit.
```

## Implementation map

| Element | Code |
|---|---|
| trait `CheckpointStore[F[_]]` | `trait CheckpointStore[F[_]]` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala`) |
| type `CheckpointId` | `CheckpointStore.CheckpointId` — opaque refined type `String :| NonEmpty` (Iron `RefinedType`, in companion) |
| action `get` | `CheckpointStore.get(checkpointId): F[Option[Array[Byte]]]` |
| action `set` | `CheckpointStore.set(checkpointId, data): F[Unit]` |
| action `delete` | `CheckpointStore.delete(checkpointId): F[Unit]` |
| action `keys` | `CheckpointStore.keys: F[List[CheckpointId]]` |
| factory `inMemory` | `CheckpointStore.inMemory[F[_]: Sync]: F[CheckpointStore[F]]` |
| impl `InMemoryCheckpointStore` | `object InMemoryCheckpointStore` backed by `Ref[F, Map[String, Array[Byte]]]` (backward-compatible `create: IO[CheckpointStore[IO]]`) |
| state `CheckpointStateV2` | `case class CheckpointStateV2(version, messages, harnessState, interruptSignalJson, agentName)` (`adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/CheckpointStateV2.scala`) |
| message `CheckpointMessage` | `case class CheckpointMessage(role, content, toolCalls, toolCallId)` — full-fidelity message with tool calls |
| tool call `CheckpointToolCall` | `case class CheckpointToolCall(id, name, arguments)` — serialized tool call |
| converter `CheckpointMessageConverter` | `CheckpointMessageConverter.toCheckpoint/fromCheckpoint` — bridges llm4s `Message` ↔ `CheckpointMessage` |
| v1-compat `ReadWriter` | Custom `ReadWriter[CheckpointStateV2]` that decodes v1 `CheckpointState` payloads (harnessState defaults to empty, toolCalls/toolCallId default to Nil/None) |
| node `InterruptibleNode` | `class InterruptibleNode` uses `CheckpointStore[IO]` for interrupt state |
| runtime host | `org.adk4s.orchestration.interrupt`, `org.adk4s.orchestration.agent` |

## Deviations from the pattern

- The only shipped implementation is `InMemoryCheckpointStore`, which loses all data on process exit — there is no persistent implementation in the repo.
- All operations assume success; `Ref` operations cannot fail, but a real persistent implementation would need error handling that the trait does not prescribe.
- No size limit or eviction policy — the in-memory map grows unbounded across runs.
- V1 `CheckpointState` payloads lose tool-call fidelity when decoded as `CheckpointStateV2` (toolCalls and toolCallId default to empty) — this is a known limitation, not a failure.
