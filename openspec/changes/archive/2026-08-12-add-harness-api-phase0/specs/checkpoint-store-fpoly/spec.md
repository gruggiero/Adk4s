# Spec: CheckpointStore[F] and CheckpointStateV2

<!-- DELTA spec for the `checkpoint-store-fpoly` capability. Generalizes
     today's IO-fixed `CheckpointStore` trait to `CheckpointStore[F[_]]` and
     replaces the lossy `CheckpointState` / `SerializableCheckpointMessage`
     pair with `CheckpointStateV2` — full-fidelity messages, a `version`
     field for v1-read compatibility, and a `harnessState: JsonValue` field
     carrying `HarnessState.snapshot`. `AgentRunner.resume` gains
     `HarnessState.restore(stack.allCells, cp.harnessState)` before
     re-entering the loop.

     Grounded in design doc §6.2 (CheckpointStore[F]) and §6.3
     (CheckpointState v2). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| CheckpointStore | Generalized from IO-fixed to `F[_]`-polymorphic; persisted state changes from `CheckpointState` to `CheckpointStateV2` | [checkpoint-store.md](../../../concepts/checkpoint-store.md) |
| AgentRunner | `resume` gains harness-state restore from `CheckpointStateV2.harnessState`; the fidelity defect (lossy `SerializableCheckpointMessage`) is fixed | [agent-runner.md](../../../concepts/agent-runner.md) |
| InterruptSignal | Serialized as `interruptSignalJson` in `CheckpointStateV2`; the signal's `Stateful.state` is now `JsonValue` | [interrupt-signal.md](../../../concepts/interrupt-signal.md) |
| harness-state (NEW — created by harness-state spec) | The `harnessState: JsonValue` field in `CheckpointStateV2` is a `HarnessState.snapshot`; `restore` reconstructs it on resume | `openspec/concepts/harness-state.md` (created at apply Step 12) |

Updating the `checkpoint-store.md` and `agent-runner.md` concept files (state gains F-polymorphism and `CheckpointStateV2`; `resume` action gains harness-state restore) is PART OF implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package | Role here |
|---------|------|---------|-----------|
| `CheckpointStore` | trait (concrete — no type param) | `org.adk4s.orchestration.interrupt` | Generalized to `CheckpointStore[F[_]]`; today's trait becomes `CheckpointStore[IO]` |
| `CheckpointState` | case class (private[agent]) | `org.adk4s.orchestration.agent` | Replaced by `CheckpointStateV2` (full-fidelity messages + `harnessState` + `version`) |
| `InterruptSignal` | sealed trait (derives ReadWriter: `Simple`/`Stateful`/`Composite`) | `org.adk4s.core.interrupt` | `interruptSignalJson: String` field carries the serialized signal; reused as-is |
| `JsonValue` | type alias (`= smithy4s.Document`) | `org.adk4s.core.json` | Serialization currency for `CheckpointStateV2.harnessState` (carries `HarnessState.snapshot`) |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` | Owns `CheckpointStore`; `resume` gains `HarnessState.restore(stack.allCells, cp.harnessState)` |
| `AdkError` / `CheckpointNotFoundError` | sealed trait / variant | `org.adk4s.core.error` | `resume` returns `RunResult.Failed(CheckpointNotFoundError(id))` for unknown checkpoint ids |
| `RunResult` | sealed trait (`Completed`/`Interrupted`/`Failed`) | `org.adk4s.orchestration.agent` | `resume` outcome; checkpoint deleted on `Completed` |
| `HarnessState` | final class | `org.adk4s.harness` | `snapshot` produces the `JsonValue` stored in `CheckpointStateV2.harnessState`; `restore` consumes it on resume (introduced by `harness-state` spec) |
| `StateCell` | final class | `org.adk4s.harness` | `stack.allCells` drives `HarnessState.restore` on resume (introduced by `harness-state` spec) |
| `AssistantMessage` / `ToolMessage` / `UserMessage` / `SystemMessage` / `ToolCall` | case class / sealed trait | `org.llm4s.llmconnect.model` | Full-fidelity `CheckpointMessage` carries `toolCalls` and `toolCallId` so resumed conversations are byte-faithful to the interrupted one |

## Concepts Introduced (new)

> **COMMITMENT**: the following concepts SHALL be introduced by this spec and
> registered in `openspec/concept-inventory.md` during apply Step 12.

| Concept | Kind | Package | Description |
|---------|------|---------|-------------|
| `CheckpointStore[F[_]]` | trait (generalized) | `org.adk4s.orchestration.interrupt` | F-polymorphic checkpoint store; `get`/`set`/`delete`/`keys` return `F[…]`; `inMemory[F[_]: Sync]` factory (Ref-backed) |
| `CheckpointStateV2` | case class (derives ReadWriter) | `org.adk4s.orchestration.agent` | Full-fidelity messages + `harnessState: JsonValue` + `version: Int` (= 2); v1 payloads remain readable |
| `CheckpointMessage` | case class (derives ReadWriter) | `org.adk4s.orchestration.agent` | Full-fidelity serializable message: `role`, `content`, `toolCalls: List[CheckpointToolCall]`, `toolCallId: Option[String]` — replaces lossy `SerializableCheckpointMessage` |
| `CheckpointToolCall` | case class (derives ReadWriter) | `org.adk4s.orchestration.agent` | Serializable tool call: `id`, `name`, `arguments: String` (JSON text) — the fidelity that `SerializableCheckpointMessage` flattened away |

## ADDED Requirements

### Requirement: CheckpointStore is F-polymorphic

The system SHALL provide `CheckpointStore[F[_]]` as a trait parameterized by an effect type `F[_]`, generalizing today's concrete `CheckpointStore` (which returns `IO[…]` from every method). The trait SHALL expose four methods: `get(checkpointId: CheckpointId): F[Option[Array[Byte]]]`, `set(checkpointId: CheckpointId, data: Array[Byte]): F[Unit]`, `delete(checkpointId: CheckpointId): F[Unit]`, and `keys: F[List[CheckpointId]]`. The companion object SHALL provide an `inMemory[F[_]: Sync]: F[CheckpointStore[F]]` factory backed by `Ref[F, Map[CheckpointId, Array[Byte]]]`. Today's `CheckpointStore` (no type parameter, `IO`-fixed) SHALL be replaced by `CheckpointStore[IO]`; all existing call sites (`AgentRunner`, `InterruptibleNode`, tests) SHALL compile against the generalized trait at `F = IO` without source changes beyond the type parameter.

**Given** the generalized `CheckpointStore[F[_]]` trait with `Sync[F]` bound on `inMemory`
**When** `CheckpointStore.inMemory[IO]` is called
**Then** an `IO[CheckpointStore[IO]]` is produced whose `get`/`set`/`delete`/`keys` methods are `IO`-effectful and backed by a `Ref[IO, Map[String, Array[Byte]]]`

#### Scenario: inMemory get returns None for missing key

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]`
**When** `store.get("missing")` is called
**Then** the result is `None`

#### Scenario: inMemory set then get round-trips bytes

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]` and `data = "hello".getBytes("UTF-8")`
**When** `store.set("key1", data)` then `store.get("key1")` is called
**Then** the result is `Some(data)` and `new String(result.getOrElse(fail("expected checkpoint to be defined")), "UTF-8")` equals `"hello"`

#### Scenario: inMemory set overwrites existing value

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]` with `store.set("key1", "first".getBytes("UTF-8"))` already executed
**When** `store.set("key1", "second".getBytes("UTF-8"))` then `store.get("key1")` is called
**Then** `new String(result.getOrElse(fail("expected checkpoint to be defined")), "UTF-8")` equals `"second"`

#### Scenario: inMemory delete removes a key

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]` with `store.set("key1", "data".getBytes("UTF-8"))` already executed
**When** `store.delete("key1")` then `store.get("key1")` is called
**Then** the result is `None`

#### Scenario: inMemory delete is no-op for missing key

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]`
**When** `store.delete("missing")` is called
**Then** the `IO[Unit]` completes successfully and `store.get("missing")` still returns `None`

#### Scenario: inMemory keys returns all stored keys

**Given** a `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]` with keys `"a"`, `"b"`, `"c"` set
**When** `store.keys` is called
**Then** the result contains exactly `List("a", "b", "c")` (order-independent; compare via `.sorted`)

#### Scenario: inMemory keys returns empty list when store is empty

**Given** a freshly created `CheckpointStore[IO]` from `CheckpointStore.inMemory[IO]`
**When** `store.keys` is called
**Then** the result is `List.empty[String]`

#### Scenario: F-polymorphism — a non-IO Sync compiles

**Given** a `Sync[F]` instance for some test effect `F` (e.g. `cats.effect.IO` or a test double)
**When** `CheckpointStore.inMemory[F]` is called
**Then** the result type is `F[CheckpointStore[F]]` — the trait does NOT bind to `IO` at the API level

#### Scenario: existing IO call sites compile unchanged

**Given** `AgentRunner` and `InterruptibleNode` which today reference `CheckpointStore` (no type param) returning `IO[…]`
**When** the generalized `CheckpointStore[F[_]]` is substituted at `F = IO`
**Then** all existing call sites compile without source changes beyond adding the `[IO]` type argument where the trait is named

### Requirement: CheckpointStateV2 carries full-fidelity messages

The system SHALL replace the current `CheckpointState` (which uses `SerializableCheckpointMessage(role, content)`) with `CheckpointStateV2`, a case class that `derives ReadWriter` and carries: `version: Int` (constant `2`), `messages: List[CheckpointMessage]`, `harnessState: JsonValue`, `interruptSignalJson: String`, and `agentName: String`. `CheckpointMessage` SHALL be a case class (derives ReadWriter) with fields `role: String`, `content: String`, `toolCalls: List[CheckpointToolCall]`, and `toolCallId: Option[String]`. `CheckpointToolCall` SHALL be a case class (derives ReadWriter) with fields `id: String`, `name: String`, and `arguments: String` (JSON text). The `messages` field SHALL preserve `AssistantMessage.toolCalls` and `ToolMessage.toolCallId` so that a resumed conversation is faithful to the interrupted one — providers that validate tool-call/tool-result pairing SHALL NOT reject a resumed conversation due to missing tool-call metadata.

**Given** an interrupted conversation containing an `AssistantMessage(contentOpt = Some("Let me check"), toolCalls = Seq(ToolCall("call_1", "search", ujson.Obj("q" -> ujson.Str("scala")))))` followed by a `ToolMessage(content = "result", toolCallId = "call_1")`
**When** `AgentRunner.run` catches the `AgentInterruptedException` and builds a `CheckpointStateV2`
**Then** the `messages` list contains a `CheckpointMessage` with `role = "assistant"`, `content = "Let me check"`, `toolCalls = List(CheckpointToolCall("call_1", "search", """{"q":"scala"}"""))`, `toolCallId = None` — followed by a `CheckpointMessage` with `role = "tool"`, `content = "result"`, `toolCalls = Nil`, `toolCallId = Some("call_1")`

#### Scenario: AssistantMessage with tool calls is serialized with full fidelity

**Given** an `AssistantMessage` with `toolCalls = Seq(ToolCall("call_42", "query", ujson.Obj("sql" -> ujson.Str("SELECT 1"))))`
**When** it is converted to a `CheckpointMessage` and serialized via `upickle.default.write`
**Then** the JSON contains the `toolCalls` array with `id = "call_42"`, `name = "query"`, and `arguments` as JSON text

#### Scenario: ToolMessage is serialized with toolCallId

**Given** a `ToolMessage(content = "42", toolCallId = "call_42")`
**When** it is converted to a `CheckpointMessage` and serialized via `upickle.default.write`
**Then** the JSON contains `toolCallId = "call_42"` and `toolCalls = []`

#### Scenario: Plain user message has no tool fields

**Given** a `UserMessage("hello")`
**When** it is converted to a `CheckpointMessage`
**Then** `toolCalls` is `Nil` and `toolCallId` is `None`

#### Scenario: Resumed conversation passes tool-call/tool-result pairing validation

**Given** a `CheckpointStateV2` whose `messages` include an assistant message with `toolCalls` and a matching tool message with `toolCallId`
**When** `AgentRunner.resume` reconstructs `List[Message]` from the `CheckpointMessage` list
**Then** the reconstructed `AssistantMessage.toolCalls` and `ToolMessage.toolCallId` match exactly, and `Message.validateConversation(reconstructed)` returns `Right(())`

#### Scenario: version field is 2

**Given** a `CheckpointStateV2` constructed by `AgentRunner.run` on interrupt
**When** the `version` field is inspected
**Then** it equals `2`

#### Scenario: harnessState carries HarnessState.snapshot

**Given** an agent running with a non-empty `MiddlewareStack` whose cells have been mutated during the run
**When** an interrupt is caught and `CheckpointStateV2` is built
**Then** the `harnessState` field is a `JsonValue` (a `smithy4s.Document.DObject`) equal to `HarnessState.snapshot(state)` at the point of interruption

#### Scenario: empty-stack harnessState is an empty object

**Given** an agent running with `MiddlewareStack.empty` (no state cells)
**When** an interrupt is caught and `CheckpointStateV2` is built
**Then** the `harnessState` field is `smithy4s.Document.DObject(Map.empty)` — an empty JSON object

### Requirement: CheckpointStateV2 is v1-read compatible

The system SHALL read v1 checkpoint payloads — those written by the current `CheckpointState` with `SerializableCheckpointMessage(role, content)` and no `version`/`harnessState` fields — producing a `CheckpointStateV2` with `version = 2`, `harnessState = DObject(Map.empty)`, and `messages` reconstructed from the v1 `role`+`content` pairs with `toolCalls = Nil` and `toolCallId = None`. A v1 payload SHALL NOT cause a decode failure. The `version` field SHALL be optional during decoding (absent ⇒ treated as v1); when present and equal to `2`, the full v2 schema (including `toolCalls`, `toolCallId`, and `harnessState`) SHALL be decoded.

**Given** a v1 checkpoint payload: `{"messages": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello"}], "interruptSignalJson": "...", "agentName": "test-agent"}` (no `version`, no `harnessState`, no `toolCalls`, no `toolCallId`)
**When** `upickle.default.read[CheckpointStateV2]` is called on the v1 JSON text
**Then** the result is a `CheckpointStateV2` with `version = 2` (or default), `harnessState = DObject(Map.empty)`, and `messages = List(CheckpointMessage("user", "hi", Nil, None), CheckpointMessage("assistant", "hello", Nil, None))`

#### Scenario: v1 payload with tool messages decodes successfully

**Given** a v1 payload containing `{"role": "tool", "content": "result"}` (no `toolCallId`)
**When** it is decoded as `CheckpointStateV2`
**Then** the resulting `CheckpointMessage` has `toolCallId = None` and `toolCalls = Nil` — the missing fields default rather than failing

#### Scenario: v1 payload with assistant tool calls loses fidelity (known limitation, not a failure)

**Given** a v1 payload where an assistant message that originally had `toolCalls` was serialized as `{"role": "assistant", "content": ""}` (tool calls flattened away by the old `SerializableCheckpointMessage`)
**When** it is decoded as `CheckpointStateV2`
**Then** decoding succeeds, `toolCalls = Nil` — the fidelity is lost (this is the v1 defect, not a v2 bug), but the payload is NOT rejected

#### Scenario: v2 payload decodes with full fidelity

**Given** a v2 payload with `version = 2`, `harnessState = {"counter": 3}`, and a `CheckpointMessage` containing `toolCalls` and `toolCallId`
**When** it is decoded as `CheckpointStateV2`
**Then** all fields are populated: `version = 2`, `harnessState = DObject(Map("counter" -> DNumber(3)))`, `toolCalls` and `toolCallId` are present

#### Scenario: corrupted payload fails to decode

**Given** a payload that is not valid JSON (e.g. `"{not json"`)
**When** `upickle.default.read[CheckpointStateV2]` is called
**Then** a `upickle.core.Abort` (or subclass) is thrown — corrupted checkpoints are a hard failure, not silent data loss

### Requirement: AgentRunner.resume restores harness state

The system SHALL modify `AgentRunner.resume` so that, after loading a `CheckpointStateV2` from the `CheckpointStore`, it calls `HarnessState.restore(stack.allCells, cp.harnessState)` before re-entering the loop. The restored `HarnessState` SHALL be the initial state passed to the loop. If `restore` returns `Left(StateDecodeError)`, `resume` SHALL return `RunResult.Failed` wrapping the decode error — corrupted cell data in a checkpoint is a hard failure, not silent data loss. For v1 checkpoints (where `harnessState` is an empty `DObject`), `restore` SHALL succeed with all cells at their declared `initial` values (lenient restore per the `harness-state` spec).

**Given** a `CheckpointStateV2` with `harnessState = HarnessState.snapshot(state)` where `state` has a cell `"counter/cell"` with value `3`
**When** `AgentRunner.resume(checkpointId, results)` loads the checkpoint and calls `HarnessState.restore(stack.allCells, cp.harnessState)`
**Then** the restored `HarnessState` has `get(counterCell) == 3` — the cell value is recovered

#### Scenario: resume with v1 checkpoint restores all cells to initial

**Given** a v1 checkpoint (no `harnessState`) decoded to `CheckpointStateV2` with `harnessState = DObject(Map.empty)`
**When** `HarnessState.restore(stack.allCells, DObject(Map.empty))` is called
**Then** the result is `Right(state)` where every cell reads as its declared `initial` value

#### Scenario: resume with corrupted harnessState fails

**Given** a `CheckpointStateV2` with `harnessState` containing a cell value that cannot be decoded by the cell's `ReadWriter` (e.g. a string where an `Int` is expected)
**When** `HarnessState.restore(stack.allCells, cp.harnessState)` is called
**Then** the result is `Left(StateDecodeError(cellId, cause))` and `AgentRunner.resume` returns `RunResult.Failed(...)`

#### Scenario: resume with unknown checkpoint id returns Failed

**Given** a `CheckpointStore` that does not contain `"nonexistent-id"`
**When** `AgentRunner.resume("nonexistent-id", results)` is called
**Then** the result is `RunResult.Failed(CheckpointNotFoundError("nonexistent-id"))`

#### Scenario: resume deletes checkpoint on successful completion

**Given** a resumed agent that completes without further interrupt
**When** the `RunResult.Completed` is produced
**Then** `checkpointStore.delete(checkpointId)` has been called — the checkpoint is cleaned up

### Requirement: CheckpointId type alias

The system SHALL introduce `CheckpointId` as a type alias for `String` in the `CheckpointStore` companion object, so that the `F`-polymorphic trait's method signatures read `get(checkpointId: CheckpointId)` rather than bare `String`. `CheckpointId` SHALL be a transparent alias (not an opaque type) to preserve source compatibility with existing `String` call sites.

**Given** the `CheckpointStore` companion object
**When** the `CheckpointId` type is inspected
**Then** it is `type CheckpointId = String` and any `String` value can be passed where `CheckpointId` is expected without conversion

#### Scenario: Transparent alias preserves source compatibility

**Given** an existing call site that passes a `String` checkpoint id to `CheckpointStore[IO].get`
**When** the call site is compiled against the new `CheckpointStore[F[_]]` trait with `CheckpointId` parameter type
**Then** the call site compiles unchanged — no `.asInstanceOf`, no wrapper construction, no implicit conversion

#### Scenario: Alias is not opaque — equality with String holds

**Given** two values `id1: CheckpointId = "ckpt-1"` and `id2: String = "ckpt-1"`
**When** `id1 == id2` is evaluated
**Then** the result is `true` — `CheckpointId` is a transparent alias, not an opaque newtype

## MODIFIED Requirements

### Requirement: CheckpointStore for interrupt state persistence

The system SHALL use `CheckpointStore[F[_]]` for persisting interrupt state, serialized as `CheckpointStateV2` (full-fidelity messages, `harnessState: JsonValue`, `interruptSignalJson`, `agentName`, `version = 2`). At `F = IO`, the existing `AgentRunner` and `InterruptibleNode` call sites SHALL compile unchanged. The stored state SHALL include the agent's accumulated messages (with `toolCalls` and `toolCallId` preserved), the interrupt signal (as JSON text), the agent name, and the harness state snapshot.

> **MODIFIED** from `openspec/specs/agent-interrupt-resume/spec.md` — the trait is generalized from `CheckpointStore` (IO-fixed) to `CheckpointStore[F[_]]`, and the persisted state changes from `CheckpointState` to `CheckpointStateV2`.

**Given** an agent that interrupts during a run with tool calls in flight
**When** `AgentRunner.run` catches the `AgentInterruptedException` and persists the checkpoint
**Then** the stored `CheckpointStateV2` includes: full-fidelity messages (with `toolCalls` and `toolCallId`), `interruptSignalJson` (the serialized `InterruptSignal`), `agentName`, `version = 2`, and `harnessState` (the `HarnessState.snapshot` at the point of interruption)

#### Scenario: Save checkpoint on interrupt

**Given** an agent that interrupts with `AssistantMessage(toolCalls = Seq(ToolCall("call_1", "search", ...)))` in the conversation
**When** the checkpoint is saved
**Then** the stored `CheckpointStateV2.messages` includes the assistant message with `toolCalls = List(CheckpointToolCall("call_1", "search", ...))` — NOT flattened to role+content

#### Scenario: Load checkpoint on resume

**Given** a checkpoint stored as `CheckpointStateV2`
**When** `AgentRunner.resume` loads it from the `CheckpointStore`
**Then** the agent's message history (with tool calls and tool-call ids), interrupt context, and harness state are all restored

#### Scenario: Delete checkpoint after completion

**Given** a resumed agent that completes successfully
**When** the `RunResult.Completed` is produced
**Then** the checkpoint is deleted from the store via `checkpointStore.delete(checkpointId)`

## Properties (Ring 3)

### Property: V1 read compatibility — v1 payload decodes to CheckpointStateV2

**Invariant**: For every v1 `CheckpointState` payload (serialized via the old `SerializableCheckpointMessage(role, content)` schema, no `version`/`harnessState`/`toolCalls`/`toolCallId` fields), `upickle.default.read[CheckpointStateV2](v1Json)` succeeds and produces a `CheckpointStateV2` with `harnessState = DObject(Map.empty)`, `messages` reconstructed from `role`+`content` pairs with `toolCalls = Nil` and `toolCallId = None`.

**Generator strategy**: `genV1CheckpointState` (constructive — generates a `List[SerializableCheckpointMessage]` via `genV1Message`, an `interruptSignalJson: String` via `genInterruptSignalJson`, and an `agentName: String` via `Gen.string(Gen.alphaNum, Range.linear 1 20)`; `genV1Message` generates `role` from `Gen.element1("user", "assistant", "system", "tool")` and `content` from `Gen.string(Gen.alphaNum, Range.linear 0 100)`; the v1 JSON is produced by `upickle.default.write(CheckpointState(messages, interruptSignalJson, agentName))` using the OLD schema). Covers the empty-messages edge (`List.empty`), the tool-role edge (no `toolCallId` in v1), and the assistant-with-empty-content edge.

```
forAll { (v1: V1CheckpointState) =>
  val v1Json: String = upickle.default.write(v1)(using v1ReadWriter)
  val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](v1Json)
  decoded.harnessState == smithy4s.Document.DObject(Map.empty) &&
  decoded.messages.length == v1.messages.length &&
  decoded.messages.forall(m => m.toolCalls.isEmpty && m.toolCallId.isEmpty)
}
```

### Property: V2 round-trip — write then read is identity

**Invariant**: For every `CheckpointStateV2`, `upickle.default.read[CheckpointStateV2](upickle.default.write(cpv2)) == cpv2` (value equality across all fields, including `toolCalls`, `toolCallId`, `harnessState`, and `version`).

**Generator strategy**: `genCheckpointStateV2` (constructive — generates `version = 2` (constant), `messages` via `genCheckpointMessage` (list of 0-10), `harnessState` via `genJsonValue` filtered to `DObject` variant (0-5 keys, depth ∈ Range.linear 0 3), `interruptSignalJson` via `genInterruptSignalJson`, `agentName` via `Gen.string(Gen.alphaNum, Range.linear 1 20)`). `genCheckpointMessage` generates `role` from `Gen.element1("user", "assistant", "system", "tool")`, `content` via `Gen.string(Gen.alphaNum, Range.linear 0 100)`, `toolCalls` via `Gen.list(genCheckpointToolCall, Range.linear 0 3)` (only non-empty when role is "assistant"), and `toolCallId` via `Gen.option(Gen.string(Gen.alphaNum, Range.linear 1 10), Range.linear 0 1)` (only `Some` when role is "tool"). `genCheckpointToolCall` generates `id` and `name` via `Gen.string(Gen.alphaNum, Range.linear 1 15)` and `arguments` via `Gen.string(Gen.alphaNum, Range.linear 0 50)` (JSON text). Covers the empty-messages edge, the empty-toolCalls edge, and the empty-harnessState edge.

```
forAll { (cpv2: CheckpointStateV2) =>
  val json: String = upickle.default.write(cpv2)
  val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](json)
  decoded == cpv2
}
```

### Property: Full-fidelity preservation — toolCalls and toolCallId survive round-trip

**Invariant**: For every `CheckpointStateV2` containing an assistant message with non-empty `toolCalls` and a tool message with `toolCallId = Some(id)`, the round-trip preserves `toolCalls` (each `CheckpointToolCall.id`, `.name`, `.arguments`) and `toolCallId` exactly.

**Generator strategy**: `genFidelityCheckpoint` (constructive — generates a `CheckpointStateV2` with exactly two messages: an assistant `CheckpointMessage` with `toolCalls = List(genCheckpointToolCall)` (1-3 tool calls, each with distinct `id`) and a tool `CheckpointMessage` with `toolCallId = Some(firstToolCallId)`; `harnessState = DObject(Map.empty)`, `version = 2`). The property checks that after `write` then `read`, the assistant message's `toolCalls` match the original and the tool message's `toolCallId` matches the first tool call's `id`. Covers the multi-tool-call edge (2-3 calls) and the arguments-text edge.

```
forAll { (cp: CheckpointStateV2) =>
  val json: String = upickle.default.write(cp)
  val decoded: CheckpointStateV2 = upickle.default.read[CheckpointStateV2](json)
  val origAssistant: CheckpointMessage = cp.messages.find(_.role == "assistant").getOrElse(fail("expected assistant message"))
  val decodedAssistant: CheckpointMessage = decoded.messages.find(_.role == "assistant").getOrElse(fail("expected assistant message"))
  decodedAssistant.toolCalls == origAssistant.toolCalls &&
  decoded.messages.find(_.role == "tool").flatMap(_.toolCallId) == cp.messages.find(_.role == "tool").flatMap(_.toolCallId)
}
```

### Property: harnessState snapshot round-trip — restore yields the original state

**Invariant**: For every `HarnessState` `s` over a declared cell list `cells`, `CheckpointStateV2` with `harnessState = HarnessState.snapshot(s)`, when restored via `HarnessState.restore(cells, cp.harnessState)`, yields `Right(s)` up to absent-equals-initial (cells not in the snapshot read as `initial`).

**Generator strategy**: `genHarnessStateWithCells` (constructive — generates a `List[StateCell[?]]` of 1-5 cells with `Int`/`String`/`Boolean` types via `genStateCell`, then generates a `HarnessState` by setting each cell to a generated value via `genCellValue(cell)`; the `CheckpointStateV2` is built with `harnessState = HarnessState.snapshot(s)`, `messages = Nil`, `version = 2`, `interruptSignalJson = ""`, `agentName = "test"`). Covers the single-cell edge, the all-cells-set edge, and the mixed-type edge. This property depends on the `harness-state` spec's `HarnessState.snapshot`/`restore` being implemented.

```
forAll { (stateWithCells: HarnessStateWithCells) =>
  val (cells: List[StateCell[?]], state: HarnessState) = stateWithCells.value
  val cp: CheckpointStateV2 = CheckpointStateV2(
    version = 2,
    messages = Nil,
    harnessState = HarnessState.snapshot(state),
    interruptSignalJson = "",
    agentName = "test"
  )
  val restored: Either[StateDecodeError, HarnessState] = HarnessState.restore(cells, cp.harnessState)
  restored.isRight &&
  cells.forall { cell =>
    restored.toOption.flatMap(_.get(cell) == state.get(cell))
  }
}
```

### Property: F-polymorphism — inMemory satisfies get/set/delete/keys semantics

**Invariant**: For any `Sync[F]`, `CheckpointStore.inMemory[F]` produces a `CheckpointStore[F]` satisfying: `get` after `set` returns the stored bytes; `get` for an unset key returns `None`; `set` overwrites; `delete` removes; `delete` on a missing key is a no-op; `keys` returns exactly the set keys.

**Generator strategy**: `genCheckpointOp` (constructive — generates a sequence of 1-20 operations: `Set(id, data)`, `Get(id)`, `Delete(id)`, `Keys` where `id` is from `Gen.string(Gen.alphaNum, Range.linear 1 10)` and `data` from `Gen.bytes(Range.linear 0 50)`). The property runs the operations against `CheckpointStore.inMemory[IO]` (the test `Sync` instance) and checks the final state matches a reference `Map[String, Array[Byte]]` model. Covers the empty-store edge, the overwrite edge, and the delete-missing edge. Run via `IO` (the concrete `Sync` available in tests).

```
forAll { (ops: List[CheckpointOp]) =>
  for
    store <- CheckpointStore.inMemory[IO]
    ref   <- Ref.of[IO, Map[String, Array[Byte]]](Map.empty)
    _     <- ops.traverse {
      case Set(id, data) => store.set(id, data) *> ref.update(_.updated(id, data))
      case Get(id)       => store.get(id).flatMap(s => ref.get.map(_.get(id) == s)).assert
      case Delete(id)    => store.delete(id) *> ref.update(_ - id)
      case Keys          => store.keys.flatMap(ks => ref.get.map(_.keys.toList.sorted == ks.sorted)).assert
    }
  yield ()
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `CheckpointStore` referenced without a type parameter (e.g. `val store: CheckpointStore = ...`) | The trait is now `CheckpointStore[F[_]]`; the unparameterized form no longer exists | `assertDoesNotCompile("val store: CheckpointStore = ???")` |
| `SerializableCheckpointMessage` used in new code (outside the v1-read compatibility decoder) | Replaced by `CheckpointMessage`; the old type exists only for the v1 decode path | code-review gate + grep for `SerializableCheckpointMessage` outside the v1 compat module |
| `CheckpointState` (v1) used in new code (outside the v1-read compatibility decoder) | Replaced by `CheckpointStateV2`; the old type exists only for the v1 decode path | code-review gate + grep for `CheckpointState` outside the v1 compat module |
| `upickle.default.read[CheckpointState]` (v1 read) in production resume path | `resume` SHALL read as `CheckpointStateV2` (which handles v1 payloads via the compatibility decoder) | code-review gate |
| `asInstanceOf` in `CheckpointStore` or `CheckpointStateV2` code | Project rule: NEVER use `asInstanceOf`; use pattern matching | WartRemover `AsInstanceOf` wart (scoped) |
| `Arbitrary`-based Hedgehog generators for `CheckpointStateV2` / `CheckpointMessage` | Project rule: Hedgehog Gen with explicit Range — NO `Arbitrary` | code-review gate (Hedgehog does not have `Arbitrary`; this is a ScalaCheck anti-pattern guard) |

## Formal Contracts (Ring 6)

`CheckpointStateV2` is a plain `derives ReadWriter` case class — its round-trip is a Ring 3 property (above), not a formal contract. The `CheckpointStore[F]` trait is an effectful interface — its semantics are Ring 3 properties (above). Neither is a Ring 6 candidate (no pure kernel with a decision/fold at its center).

The `HarnessState.restore` round-trip (Property: harnessState snapshot round-trip) borders on Ring 6 but is claimed by the `harness-state` spec's verified mirror; this spec depends on that property holding.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| CheckpointStore is F-polymorphic | Requirement: CheckpointStore is F-polymorphic | type system (trait `CheckpointStore[F[_]]`) + compile test (non-IO Sync compiles) | CheckpointStoreSpec |
| inMemory get returns None for missing key | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory get returns None for missing key | munit test | CheckpointStoreSpec |
| inMemory set then get round-trips bytes | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory set then get round-trips bytes | munit test | CheckpointStoreSpec |
| inMemory set overwrites existing value | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory set overwrites existing value | munit test | CheckpointStoreSpec |
| inMemory delete removes a key | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory delete removes a key | munit test | CheckpointStoreSpec |
| inMemory delete is no-op for missing key | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory delete is no-op for missing key | munit test | CheckpointStoreSpec |
| inMemory keys returns all stored keys | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory keys returns all stored keys | munit test | CheckpointStoreSpec |
| inMemory keys returns empty list when store is empty | Requirement: CheckpointStore is F-polymorphic + Scenario: inMemory keys returns empty list when store is empty | munit test | CheckpointStoreSpec |
| F-polymorphism — non-IO Sync compiles | Requirement: CheckpointStore is F-polymorphic + Scenario: F-polymorphism — a non-IO Sync compiles | compile test | CheckpointStoreSpec |
| Existing IO call sites compile unchanged | Requirement: CheckpointStore is F-polymorphic + Scenario: existing IO call sites compile unchanged | `sbt adk4s-orchestration/compile` | build |
| CheckpointStateV2 carries full-fidelity messages | Requirement: CheckpointStateV2 carries full-fidelity messages | Hedgehog property (Full-fidelity preservation) + munit scenarios | CheckpointStateV2Spec |
| version field is 2 | Requirement: CheckpointStateV2 carries full-fidelity messages + Scenario: version field is 2 | munit test | CheckpointStateV2Spec |
| harnessState carries HarnessState.snapshot | Requirement: CheckpointStateV2 carries full-fidelity messages + Scenario: harnessState carries HarnessState.snapshot | munit test (integration with harness-state) | CheckpointStateV2Spec |
| empty-stack harnessState is an empty object | Requirement: CheckpointStateV2 carries full-fidelity messages + Scenario: empty-stack harnessState is an empty object | munit test | CheckpointStateV2Spec |
| CheckpointStateV2 is v1-read compatible | Requirement: CheckpointStateV2 is v1-read compatible | Hedgehog property (V1 read compatibility) | CheckpointStateV2Spec |
| v1 payload with tool messages decodes successfully | Requirement: CheckpointStateV2 is v1-read compatible + Scenario: v1 payload with tool messages decodes successfully | munit test | CheckpointStateV2Spec |
| v2 payload decodes with full fidelity | Requirement: CheckpointStateV2 is v1-read compatible + Scenario: v2 payload decodes with full fidelity | Hedgehog property (V2 round-trip) | CheckpointStateV2Spec |
| corrupted payload fails to decode | Requirement: CheckpointStateV2 is v1-read compatible + Scenario: corrupted payload fails to decode | munit test (assertThrows) | CheckpointStateV2Spec |
| AgentRunner.resume restores harness state | Requirement: AgentRunner.resume restores harness state | Hedgehog property (harnessState snapshot round-trip) + munit integration test | AgentRunnerResumeSpec |
| resume with v1 checkpoint restores all cells to initial | Requirement: AgentRunner.resume restores harness state + Scenario: resume with v1 checkpoint restores all cells to initial | munit test | AgentRunnerResumeSpec |
| resume with corrupted harnessState fails | Requirement: AgentRunner.resume restores harness state + Scenario: resume with corrupted harnessState fails | munit test | AgentRunnerResumeSpec |
| resume with unknown checkpoint id returns Failed | Requirement: AgentRunner.resume restores harness state + Scenario: resume with unknown checkpoint id returns Failed | munit test | AgentRunnerResumeSpec |
| resume deletes checkpoint on successful completion | Requirement: AgentRunner.resume restores harness state + Scenario: resume deletes checkpoint on successful completion | munit test | AgentRunnerResumeSpec |
| CheckpointId type alias | Requirement: CheckpointId type alias | type system (transparent alias) + compile test | CheckpointStoreSpec |
| V1 read compatibility property | Requirement: CheckpointStateV2 is v1-read compatible | Hedgehog property (V1 read compatibility) | CheckpointStateV2Spec |
| V2 round-trip property | Requirement: CheckpointStateV2 carries full-fidelity messages | Hedgehog property (V2 round-trip) | CheckpointStateV2Spec |
| Full-fidelity preservation property | Requirement: CheckpointStateV2 carries full-fidelity messages | Hedgehog property (Full-fidelity preservation) | CheckpointStateV2Spec |
| harnessState snapshot round-trip property | Requirement: AgentRunner.resume restores harness state | Hedgehog property (harnessState snapshot round-trip) | AgentRunnerResumeSpec |
| F-polymorphism semantics property | Requirement: CheckpointStore is F-polymorphic | Hedgehog property (F-polymorphism) | CheckpointStoreSpec |
| No unparameterized CheckpointStore reference | Compile-Negative: CheckpointStore without type parameter | compile-negative test (`assertDoesNotCompile`) | CheckpointStoreSpec |
| No asInstanceOf in checkpoint code | Compile-Negative: asInstanceOf in CheckpointStore or CheckpointStateV2 | WartRemover `AsInstanceOf` wart | build |
| No SerializableCheckpointMessage in new code | Compile-Negative: SerializableCheckpointMessage outside v1 compat | code-review gate + grep | adversarial review |
| No Arbitrary-based generators | Compile-Negative: Arbitrary-based Hedgehog generators | code-review gate | adversarial review |
| Save checkpoint on interrupt uses CheckpointStateV2 | Requirement: CheckpointStore for interrupt state persistence (MODIFIED) + Scenario: Save checkpoint on interrupt | munit integration test | AgentRunnerResumeSpec |
| Load checkpoint on resume uses CheckpointStateV2 | Requirement: CheckpointStore for interrupt state persistence (MODIFIED) + Scenario: Load checkpoint on resume | munit integration test | AgentRunnerResumeSpec |
| Delete checkpoint after completion | Requirement: CheckpointStore for interrupt state persistence (MODIFIED) + Scenario: Delete checkpoint after completion | munit test | AgentRunnerResumeSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `CheckpointStore[F[_]]` | trait (generalized) | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/interrupt/CheckpointStore.scala` | Replaces current `trait CheckpointStore`; methods return `F[…]`; companion gains `inMemory[F[_]: Sync]` and `type CheckpointId = String` |
| `CheckpointStore.inMemory[F[_]: Sync]` | factory method | `CheckpointStore.scala` companion | `Ref[F, Map[CheckpointId, Array[Byte]]]`-backed; replaces `InMemoryCheckpointStore.create` (which returns `IO[CheckpointStore]`) |
| `CheckpointStateV2` | case class (derives ReadWriter) | `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/agent/AgentRunner.scala` (or a new `CheckpointStateV2.scala`) | `version: Int = 2`, `messages: List[CheckpointMessage]`, `harnessState: JsonValue`, `interruptSignalJson: String`, `agentName: String`; `private[agent]` visibility like the old `CheckpointState` |
| `CheckpointMessage` | case class (derives ReadWriter) | same file as `CheckpointStateV2` | `role: String`, `content: String`, `toolCalls: List[CheckpointToolCall]`, `toolCallId: Option[String]`; `toolCalls` defaults to `Nil`, `toolCallId` defaults to `None` (for v1-read compat) |
| `CheckpointToolCall` | case class (derives ReadWriter) | same file as `CheckpointStateV2` | `id: String`, `name: String`, `arguments: String` (JSON text, not `ujson.Value` — keeps orchestration free of ujson per `migrate-json-codec`) |
| `AgentRunner.run` (interrupt path) | modified method | `AgentRunner.scala` line ~47 | Builds `CheckpointStateV2` from full-fidelity `CheckpointMessage` list + `HarnessState.snapshot(state)` instead of lossy `SerializableCheckpointMessage` |
| `AgentRunner.resume` | modified method | `AgentRunner.scala` line ~75 | Reads as `CheckpointStateV2` (not `CheckpointState`); calls `HarnessState.restore(stack.allCells, cp.harnessState)` before re-entering the loop; on `Left(StateDecodeError)` returns `RunResult.Failed` |
| `CheckpointStoreTest` | modified test | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/interrupt/CheckpointStoreTest.scala` | Updated to use `CheckpointStore.inMemory[IO]` instead of `InMemoryCheckpointStore.create` |
| `genCheckpointStateV2` / `genCheckpointMessage` / `genCheckpointToolCall` | Hedgehog generators | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/.../Generators.scala` | Explicit `Gen` with `Range` — NO `Arbitrary`; used by Ring 3 properties |
| `CheckpointStateV2Spec` | Hedgehog property spec | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/CheckpointStateV2Spec.scala` | V1 read compat, V2 round-trip, full-fidelity preservation properties |
| `AgentRunnerResumeSpec` | Hedgehog + munit spec | `adk4s-orchestration/src/test/scala/org/adk4s/orchestration/agent/AgentRunnerResumeSpec.scala` | harnessState restore round-trip, v1 checkpoint resume, corrupted harnessState, unknown id, delete-on-completion |
