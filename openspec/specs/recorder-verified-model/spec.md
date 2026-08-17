# Spec: recorder-verified-model (Ring 6 Stainless model)

<!-- Delta spec for the add-adk4s-record change. Defines the Ring 6
     PureScala model in the `verified` leaf module for recorder coherence
     (RL1) and tool-call id normalization idempotence / order preservation
     (REC-4). Uses the verified-mirror pattern: a PureScala model reduced
     to observable effect, plus a mandatory bridge property test binding
     shipped code to the model. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | The model call surface whose recording coherence is modeled | `openspec/concepts/chat-model.md` |
| [Tool](../../../../concepts/tool.md) | Tool-call id normalization (REC-4) is modeled as an abstract ordered list | `openspec/concepts/tool.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `PredictorKernel` | PureScala model (Ring 6 precedent) | `org.adk4s.verified` |
| `SemilatticeKernel` | PureScala model (Ring 6 precedent) | `org.adk4s.verified` |
| `CallKey` | opaque type | `org.adk4s.record` (introduced by `call-key` spec) |
| `CallRecord` | enum ADT | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `Recorder[F[_]]` | trait | `org.adk4s.record` (introduced by `recorder-sink` spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RecorderCoherenceModel` | PureScala model (object) | Ring 6 finite-map model for RL1 record/lookup coherence |
| `NormalizationModel` | PureScala model (object) | Ring 6 model for REC-4 normalization idempotence and order preservation |

## ADDED Requirements

### Requirement: Recorder coherence model proves RL1 pair

The system SHALL provide a PureScala model `RecorderCoherenceModel` in the
`verified` leaf module that proves the RL1 coherence pair: `lookup(k)`
after `record(k, v)` returns `Some(v)`, and recording under a different
key does not affect prior lookups.

**Given** a finite-map model of a recorder with `record` and `lookup`
operations over integer keys and values
**When** `record(k, v)` is followed by `lookup(k)`, and `record(j, v)` is
followed by `lookup(k)`
**Then** the first lookup returns `Some(v)` and the second lookup returns
the same `Some(v)`, proven by Stainless `ensuring` clauses

**Rationale**: Recorder coherence is the correctness foundation of caching.
The model is a finite map; the proof is mechanical and the
`HarnessState` get/set coherence precedent (`SemilatticeKernel`) exists.

#### Scenario: Coherence model verifies under Stainless

**Given** the `RecorderCoherenceModel` with its `record`/`lookup` functions
**When** the formal verification command is run for the verified module
**Then** the verifier discharges the `ensuring` clauses without
counterexamples

### Requirement: Normalization model proves idempotence and order preservation

The system SHALL provide a PureScala model `NormalizationModel` in the
`verified` leaf module that proves tool-call id normalization is idempotent
(`normalize . normalize == normalize`) and preserves call order and the
assistant/tool pairing relation.

**Given** a model of a conversation as a list of messages where assistant
messages carry an ordered list of tool-call ids and tool replies reference
one id
**When** `normalize` is applied twice
**Then** the result equals applying `normalize` once, and the pairing
relation (assistant tool-call id at position i matches tool-reply id at
position i) is preserved, proven by Stainless `ensuring` clauses

**Rationale**: Normalization idempotence and order preservation are the
REC-4 properties, and they are the one canonicalization detail where a bug
is both easy to introduce and invisible in ordinary tests.

#### Scenario: Normalization model verifies under Stainless

**Given** the `NormalizationModel` with its `normalize` function
**When** the formal verification command is run for the verified module
**Then** the verifier discharges the idempotence and order-preservation
`ensuring` clauses without counterexamples

### Requirement: Bridge property tests bind shipped code to models

The system SHALL provide bridge property tests that run the shipped
`normalizeToolCallIds` function and the `Recorder` implementations against
the PureScala models on the same generated inputs, verifying that the
shipped code's observable effect matches the model's proven behavior.

**Given** generated conversations with tool calls
**When** the shipped `normalizeToolCallIds` and the model's `normalize` are
both applied
**Then** the results are equal (the shipped code matches the verified
model)

**Rationale**: Without a bridge test, the Ring 6 proof covers a program
that is not shipped. The bridge test is the artifact that binds the proof
to reality.

#### Scenario: Bridge test runs in the record module's tests

**Given** the normalization bridge test in the record module's test sources
**When** the record module's test suite is executed
**Then** the bridge test passes (shipped normalization matches the model)

### Requirement: Hash collision-freedom is assumed, not proven

The system SHALL model the digest as an injective abstract function and
state that assumption explicitly in the model, rather than proving
collision-freedom.

**Given** the `RecorderCoherenceModel`
**When** the model is inspected
**Then** the digest function is an uninterpreted function with an explicit
assumption that it is injective (no two distinct canonical forms produce
the same key)

**Rationale**: Proving hash collision-freedom is beyond Stainless's
capability for real hash functions. The assumption is stated explicitly so
it is not hidden.

#### Scenario: Injective assumption is stated in the model

**Given** the `RecorderCoherenceModel` source
**When** the model is inspected
**Then** the digest function is declared as an uninterpreted function with
an explicit `require` or comment stating the injective assumption

## Properties (Ring 3)

### Property: bridge-normalization-idempotence

**Invariant**: The shipped `normalizeToolCallIds` and the model's
`normalize` produce equal results for all generated conversations.

**Generator strategy**: `genConversationWithToolCalls` — constructive over
multi-turn conversations with 0–3 tool calls per turn. Edge cases: zero
tool calls, single tool call, multiple tool calls across turns.

```
forAll { (conv: Conversation) =>
  val shipped = normalizeToolCallIds(conv)
  val modeled = NormalizationModel.normalize(conv.toModel)
  shipped.toModel == modeled
}
```

### Property: bridge-recorder-coherence

**Invariant**: The shipped `Recorder.inMemory` and the model's
`RecorderCoherenceModel` agree on `record`/`lookup` coherence for all
generated key/record pairs.

**Generator strategy**: `genKeyRecordPair` — constructive over distinct
integer keys and arbitrary values. Edge cases: same key twice, empty
recorder.

```
forAll { (k: Int, j: Int, v: Int) =>
  for {
    shipped <- testShippedCoherence(k, j, v)
    modeled = RecorderCoherenceModel.testCoherence(k, j, v)
  } yield shipped == modeled
}
```

## Formal Contracts (Ring 6)

### Contract: RecorderCoherenceModel.record — lookup coherence

**Precondition** (`require`): key is a non-negative integer (model domain)
**Postcondition** (`ensuring`): `lookup(record(map, k, v), k) == Some(v)`

```scala
def record(map: Map[Int, Int], k: Int, v: Int): Map[Int, Int] = {
  require(k >= 0)
  map.updated(k, v)
}.ensuring(result => lookup(result, k) == Some(v))
```

### Contract: RecorderCoherenceModel.record — isolation

**Precondition** (`require`): keys k and j are distinct, k >= 0, j >= 0
**Postcondition** (`ensuring`): `lookup(record(record(map, k, v), j, w), k)
== lookup(record(map, k, v), k)`

```scala
def recordIsolated(map: Map[Int, Int], k: Int, j: Int, v: Int, w: Int): Map[Int, Int] = {
  require(k >= 0 && j >= 0 && k != j)
  record(record(map, k, v), j, w)
}.ensuring(result => lookup(result, k) == Some(v))
```

### Contract: NormalizationModel.normalize — idempotence

**Precondition** (`require`): input is a valid conversation model
**Postcondition** (`ensuring`): `normalize(normalize(conv)) == normalize(conv)`

```scala
def normalize(conv: List[Msg]): List[Msg] = {
  // positional replacement of tool-call ids in call order
}.ensuring(result => normalize(result) == result)
```

### Contract: NormalizationModel.normalize — order preservation

**Precondition** (`require`): input is a valid conversation model
**Postcondition** (`ensuring`): for every assistant/tool-reply pair at
position i, the normalized id is `call_i` and the pairing is preserved.

```scala
def normalize(conv: List[Msg]): List[Msg] = {
  // ...
}.ensuring(result => pairings(result).zipWithIndex.forall {
  case ((a, t), i) => result(a).toolCalls.head == s"call_$i" &&
                      result(t).toolCallId == s"call_$i"
})
```

> **Delegated to Ring 3**: key-determinism across processes (RL2),
> key-sensitivity (RL3), key-insensitivity (RL4), rollout-separation (RL6),
> version-isolation (RL12) — these require generated inputs over llm4s
> types not modelable in PureScala. The Ring 6 model covers only
> normalization idempotence/order-preservation and recorder coherence.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Coherence model proves RL1 pair | Requirement: Recorder coherence model proves RL1 pair | formal contract (Ring 6 Stainless) | `verified/RecorderCoherenceModel.scala` |
| Normalization model proves idempotence | Requirement: Normalization model proves idempotence and order preservation | formal contract (Ring 6 Stainless) | `verified/NormalizationModel.scala` |
| Normalization model proves order preservation | Requirement: Normalization model proves idempotence and order preservation | formal contract (Ring 6 Stainless) | `verified/NormalizationModel.scala` |
| Bridge test binds shipped normalization to model | Requirement: Bridge property tests bind shipped code to models | property test (bridge-normalization-idempotence) | `NormalizationBridgeSpec.scala` |
| Bridge test binds shipped recorder to model | Requirement: Bridge property tests bind shipped code to models | property test (bridge-recorder-coherence) | `RecorderCoherenceBridgeSpec.scala` |
| Hash collision-freedom assumed | Requirement: Hash collision-freedom is assumed, not proven | manual review (assumption stated explicitly in model) | `verified/RecorderCoherenceModel.scala` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RecorderCoherenceModel` | PureScala model | `org.adk4s.verified` | finite-map model; `record`/`lookup` with `ensuring` |
| `NormalizationModel` | PureScala model | `org.adk4s.verified` | `List[Msg]` model; `normalize` with `ensuring` |
| `NormalizationBridgeSpec` | bridge test | `adk4s-record/src/test` | runs shipped `normalizeToolCallIds` vs model on generated inputs |
| `RecorderCoherenceBridgeSpec` | bridge test | `adk4s-record/src/test` | runs shipped `Recorder.inMemory` vs model on generated key/record pairs |
| `verified` module | sbt module | `build.sbt` | pinned to Scala 3.7.2, StainlessPlugin, `stainlessEnabled := false` by default |
| `adk4s-record dependsOn(verified % Test)` | build wiring | `build.sbt` | TASTy backward compatible: 3.8.4 reads 3.7.2 |
| `sbt -J-Xmx6g ring6` | command | `build.sbt` | turns on Stainless verification for the `verified` module |
