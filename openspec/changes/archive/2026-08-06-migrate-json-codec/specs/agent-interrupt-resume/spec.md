# Spec: Agent Interrupt Resume (Delta)

<!-- DELTA spec for the migrate-json-codec change. MODIFIES the
     `InterruptSignal.Stateful.state` and `InterruptSignal.Composite.state`
     field types from `ujson.Value` to `JsonValue`. The ADT shape, the
     address-routing protocol, the `derives ReadWriter` serialization, and
     all other requirements in the base spec are UNCHANGED. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| interrupt-signal | `Stateful.state` / `Composite.state` field type migrates from `ujson.Value` to `JsonValue`; routing protocol unchanged | `openspec/concepts/interrupt-signal.md` |

No change to the `interrupt-signal.md` concept file's actions, state, or synchronizations — only the `state` field's value type changes. The concept's Implementation map row for `Stateful.state` / `Composite.state` is updated at apply Step 12 to cite the new type.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `InterruptSignal` | sealed trait (derives ReadWriter; `Simple`, `Stateful`, `Composite`) | `org.adk4s.core.interrupt` |
| `AddressSegment` | sealed trait (derives ReadWriter; `Agent`, `Tool`) | `org.adk4s.core.interrupt` |
| `smithy4s.Document` | sealed trait (ADT) | `smithy4s` |
| `smithy4s.json.Json` | object (`read[A]`/`writeBlob`) | `smithy4s.json` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `JsonValue` | type alias (`= smithy4s.Document`) | Introduced by the json-value-model spec; referenced here as the new `state` field type |

## MODIFIED Requirements

### Requirement: InterruptSignal sealed trait hierarchy

The system SHALL provide an `InterruptSignal` sealed trait representing an agent's request to pause execution and await external input. Three variants SHALL exist: stateless, stateful, and composite. The `Stateful` and `Composite` variants SHALL carry their serialized agent state as `JsonValue` (an immutable type alias over `smithy4s.Document`), NOT `ujson.Value`. The `derives ReadWriter` serialization SHALL continue to round-trip the state field through JSON text byte-for-byte compatible with the pre-change wire format.

**Given** an `InterruptSignal.Stateful` constructed with `state: JsonValue` representing `{"iteration": 3, "messages": [...]}`
**When** the interrupt is serialized via `upickle.default.writeJs` (the existing `derives ReadWriter` path, adapted for `JsonValue`) and then deserialized
**Then** the round-tripped `state` field equals the original `JsonValue` (value equality), and the JSON text produced is byte-for-byte compatible with what the pre-change `ujson.Value`-based code produced for the same logical content

**Rationale**: `ujson.Value`'s mutable AST (`Obj` wraps `LinkedHashMap`, `Arr` wraps `mutable.ArrayBuffer`) is held by a checkpointed, compared-by-`equals` value — contradicting the project's immutability rule. `JsonValue` is immutable and exact on `Long`/`BigDecimal`. The wire format MUST NOT change so existing in-flight checkpoints remain resumable.

#### Scenario: Create stateful interrupt with JsonValue state

**Given** `InterruptSignal.stateful(info, state)` is called with `state: JsonValue = DObject(Map("iteration" -> DNumber(3)))`
**When** the `Stateful` is constructed
**Then** it carries the `JsonValue` state (not `ujson.Value`), and the state is immutable

#### Scenario: Create composite interrupt with JsonValue state

**Given** `InterruptSignal.composite(info, state, childSignals)` is called with `state: JsonValue` and one or more child `InterruptSignal` values
**Then** an `InterruptSignal.Composite` is created carrying the `JsonValue` state and wrapping the child signals

#### Scenario: Stateful state is immutable

**Given** an `InterruptSignal.Stateful` with `state: JsonValue = DObject(Map("k" -> DString("v")))`
**When** code attempts to mutate the state in place
**Then** compilation fails — `JsonValue` (=`smithy4s.Document`) has no in-place `update` method

#### Scenario: Checkpoint wire-format compatibility

**Given** a checkpoint saved by the pre-change `ujson.Value`-based code with state `{"iteration": 3}`
**When** the post-change `JsonValue`-based code loads the checkpoint
**Then** the `state` field deserializes to `DObject(Map("iteration" -> DNumber(3)))` — the wire JSON text is compatible

## Properties (Ring 3)

### Property: InterruptSignal state round-trips through JsonValue

**Invariant**: For every `InterruptSignal.Stateful` or `.Composite` with `state: JsonValue`, serializing the interrupt to JSON text and deserializing it yields an interrupt with an equal `state` field.

**Generator strategy**: `genInterruptSignal` (constructive — generates `Simple`, `Stateful` with `genJsonValue` state, `Composite` with `genJsonValue` state and recursive children; depth ∈ Range.linear 0 3; classify by variant). `genJsonValue` from the json-value-model spec covers all `Document` variants including `DNull`.

```
forAll { (signal: InterruptSignal) =>
  val json = writeInterruptSignal(signal)
  val roundTripped = readInterruptSignal(json)
  roundTripped == signal
}
```

### Property: Wire-format compatibility with pre-change checkpoints

**Invariant**: For every `InterruptSignal` with `state: JsonValue` that has a pre-change `ujson.Value` equivalent, the JSON text produced by the new serializer equals the JSON text the pre-change serializer produced for the equivalent `ujson.Value` (whitespace aside).

**Generator strategy**: `genInterruptSignal` (constructive, as above); the pre-change fixture is captured BEFORE any code change by running the current `ujson.Value`-based serializer on a representative set of signals. The property compares new-serializer output against the captured fixtures.

```
forAll { (signal: InterruptSignal) =>
  val newJson = writeInterruptSignal(signal)
  val oldJson = preChangeFixture(signal.equivalentUjsonSignal)
  normalizeWhitespace(newJson) == normalizeWhitespace(oldJson)
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Stateful.state is JsonValue not ujson | Requirement: InterruptSignal sealed trait hierarchy + Scenario: Create stateful interrupt with JsonValue state | type system (field type) + adversarial review | InterruptSignalTypeContract |
| Composite.state is JsonValue not ujson | Requirement: InterruptSignal sealed trait hierarchy + Scenario: Create composite interrupt with JsonValue state | type system (field type) + adversarial review | InterruptSignalTypeContract |
| State is immutable | Requirement: InterruptSignal sealed trait hierarchy + Scenario: Stateful state is immutable | compile-negative test (no in-place update) | InterruptSignalTypeContract |
| State round-trips through serialization | Requirement: InterruptSignal sealed trait hierarchy + Property: InterruptSignal state round-trips through JsonValue | Hedgehog property | InterruptSignalSpec |
| Wire-format compatibility | Requirement: InterruptSignal sealed trait hierarchy + Scenario: Checkpoint wire-format compatibility + Property: Wire-format compatibility with pre-change checkpoints | Hedgehog property (fixture-based) + scenario test | InterruptSignalWireCompatSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `InterruptSignal.Stateful` | case class | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala` | `state: ujson.Value` → `state: JsonValue` |
| `InterruptSignal.Composite` | case class | `adk4s-core/src/main/scala/org/adk4s/core/interrupt/InterruptSignal.scala` | `state: ujson.Value` → `state: JsonValue` |
| `derives ReadWriter` | serialization | `InterruptSignal.scala` | adapt for `JsonValue` — use `smithy4s.json.Json` or a custom `ReadWriter[JsonValue]` delegating to `JsonValueCodec` |
