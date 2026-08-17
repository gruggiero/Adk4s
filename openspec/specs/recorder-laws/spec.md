# Spec: recorder-laws (RecorderLaws property testkit)

<!-- Delta spec for the add-adk4s-record change. Defines RecorderLaws — a
     main-scope Hedgehog property testkit parameterized over Recorder[F],
     producing RL0–RL12 properties any backend author can run. Ships in
     main scope (the adk4s-optimize OptimizerLaws precedent and the
     adk4s-harness-testkit precedent). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ChatModel](../../../../concepts/chat-model.md) | RL0 transparency and RL5 zero-call hit use a ChatModel double | `openspec/concepts/chat-model.md` |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChatModel[F[_]]` | trait | `org.adk4s.core.component` |
| `Embedder[F[_]]` | trait | `org.adk4s.core.component` |
| `ToolMiddleware` | type alias | `org.adk4s.core.tools` |
| `DeterministicChatModel` | test double | `org.adk4s.harness.testkit` |
| `RecordedRequest` | case class | `org.adk4s.harness.testkit` |
| `Observation` | case class (observational equivalence `≍`) | `org.adk4s.harness.testkit` |
| `AgentMiddlewareLaws` | class (L0–L10 Hedgehog properties, main scope) | `org.adk4s.harness.testkit` |
| `SemilatticeLaws` | class (L11 Hedgehog properties, main scope) | `org.adk4s.harness.testkit` |
| `CallKey` | opaque type | `org.adk4s.record` (introduced by `call-key` spec) |
| `CallRecord` | enum ADT | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `Recorder[F[_]]` | trait | `org.adk4s.record` (introduced by `recorder-sink` spec) |
| `RolloutId` | opaque type (Iron) | `org.adk4s.record` (introduced by `call-key` spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RecorderLaws` | class (main scope) | RL0–RL12 Hedgehog `Property` values, parameterized over `Recorder[F]` |
| `RequestMutation` | ADT | Mutation generator for RL3 (key sensitivity) and RL4 (key insensitivity) |
| `NonAffectingMutation` | ADT | Mutation generator for non-output-affecting field changes (complement of `RequestMutation`) |

## ADDED Requirements

### Requirement: RecorderLaws ships in main scope as downstream-consumable properties

The system SHALL package `RecorderLaws` in main scope (not Test scope) as a
class with Hedgehog `Property` values RL0–RL12, parameterized over a
`Recorder[F]` under test, so that downstream backend authors can run the
same properties by depending on the module as a regular library.

**Given** a downstream module depending on `adk4s-record`
**When** `RecorderLaws(recorder).rl0_transparency` is accessed
**Then** a Hedgehog `Property` is available that can be run in the
downstream module's test suite

**Rationale**: The `adk4s-optimize` `OptimizerLaws` precedent and the
`adk4s-harness-testkit` `AgentMiddlewareLaws` precedent both ship laws in
main scope. This ensures every backend is tested against the same oracle.

#### Scenario: Laws are accessible from a downstream test

**Given** a downstream module with `libraryDependencies += adk4s-record`
**When** a test imports `RecorderLaws`
**Then** the class and its `Property` values are available without a
Test-scope dependency

### Requirement: RL0 transparency property

The system SHALL include an RL0 property asserting that
`RecordedChatModel(under, noop)` is observationally equivalent to `under`
across generated conversations, tool sets, and failures.

**Given** a generated conversation, tool set, and completion options
**When** `RecordedChatModel(under, Recorder.noop)` and `under` are both
called
**Then** the results are observationally equivalent (`≍`)

#### Scenario: RL0 runs against noop recorder

**Given** the RL0 property with a deterministic model double as the
underlying model
**When** the property is executed
**Then** it passes (noop wrapping is transparent)

### Requirement: RL1 record/lookup coherence property

The system SHALL include an RL1 property asserting that
`lookup(k)` after `record(k, v)` returns `Some(v)`, and that recording
under a different key `j` does not affect `lookup(k)`.

**Given** a recorder, a key K, a key J (J != K), and a record V
**When** `record(K, V)` is followed by `lookup(K)`, then `record(J, V)` is
followed by `lookup(K)`
**Then** the first lookup returns `Some(V)` and the second lookup returns
the same `Some(V)` (unaffected by the record at J)

#### Scenario: RL1 coherence holds

**Given** a recorder with keys K and J (K != J) and a record V
**When** `record(K, V)` then `lookup(K)`, then `record(J, V)` then
`lookup(K)`
**Then** both lookups return `Some(V)`

### Requirement: RL3 and RL4 are mutation-generator-driven

The system SHALL implement RL3 (key sensitivity) and RL4 (key insensitivity)
as generator-driven mutation properties over a `RequestMutation` ADT, not as
example tests.

**Given** a generated base request and a generated mutation
**When** the mutation is applied and the key is recomputed
**Then** for `RequestMutation` (output-affecting), the key changes; for
`NonAffectingMutation`, the key is unchanged

**Rationale**: RL3 and RL4 together are the real specification of the
canonicalizer. Example tests would only cover the specific mutations the
author thought of; a mutation ADT covers the full space systematically.

#### Scenario: RL3 mutation changes key

**Given** a `RequestMutation.ChangeTemperature` mutation
**When** it is applied to a base request and the key is recomputed
**Then** the key differs from the base key

#### Scenario: RL4 mutation preserves key

**Given** a `NonAffectingMutation.RegenerateToolCallIds` mutation
**When** it is applied to a base request and the key is recomputed
**Then** the key equals the base key

### Requirement: RL5 zero-call hit property

The system SHALL include an RL5 property asserting that, with a warm
recorder and a call-counting `ChatModel` double, a hit performs exactly
zero underlying calls and returns the recorded completion.

**Given** a call-counting deterministic model double and a warm recorder
**When** the same call is made twice
**Then** the underlying call count after the second call equals the count
after the first call (zero additional calls)

#### Scenario: RL5 zero-call hit holds

**Given** a warm recorder and a call-counting model double
**When** the same call is made twice
**Then** the underlying call count does not increase on the second call

### Requirement: RL9 failure fidelity property

The system SHALL include an RL9 property asserting that a recorded failure
replays as an equal failure, not as a success and not as a different error.

**Given** a recorded failure variant with a specific recorded error
**When** the record is looked up and replayed
**Then** the replayed result is a failure with the same error, not a success

#### Scenario: RL9 failure fidelity holds

**Given** a recorded failure with a specific error
**When** the record is looked up and replayed
**Then** the replayed result is a failure matching the original error

### Requirement: RL10 write-failure containment property

The system SHALL include an RL10 property asserting that, when the sink
throws on `record`, the wrapped call's result still reaches the caller.

**Given** a recorder whose `record` operation throws
**When** a call is made through `RecordedChatModel`
**Then** the call's result is returned successfully (not an exception)

#### Scenario: RL10 write-failure containment holds

**Given** a recorder whose `record` operation throws
**When** a call is made through the recording wrapper
**Then** the call's result is returned successfully despite the sink failure

### Requirement: RL11 redaction neutrality property

The system SHALL include an RL11 property asserting that redaction changes
the stored payload and does not change the key.

**Given** a redaction function and two identical calls
**When** both are recorded with the redaction function
**Then** the stored payloads are redacted, and the second call hits the
cache (key was computed before redaction)

#### Scenario: RL11 redaction neutrality holds

**Given** a redaction function and two identical calls
**When** both are recorded with the redaction function
**Then** the stored payloads are redacted and the second call hits the cache

### Requirement: RL12 version isolation property

The system SHALL include an RL12 property asserting that records written
under `keyVersion = n` are invisible to lookups at `keyVersion = n+1`.

**Given** a record written under `keyVersion = 1`
**When** a lookup is performed at `keyVersion = 2`
**Then** the lookup returns `None` (the record is invisible, not an error)

#### Scenario: RL12 version isolation holds

**Given** a record written under `keyVersion = 1`
**When** a lookup is performed at `keyVersion = 2`
**Then** the lookup returns `None` (the record is invisible)

## Properties (Ring 3)

### Property: all-laws-parameterized

**Invariant**: All 13 laws (RL0–RL12) are Hedgehog `Property` values on
`RecorderLaws`, parameterized over `Recorder[F]`, runnable against `noop`,
`inMemory`, and `file` backends.

**Generator strategy**: `genRecorder` — constructive over the three
reference backends. Each law uses its own generator (see individual law
requirements above). Coverage labels: `cover 33 "noop"`, `cover 33
"inMemory"`, `cover 33 "file"`.

```
forAll { (backend: RecorderBackend) =>
  val laws = RecorderLaws(backend.recorder)
  // all 13 properties pass
  laws.rl0_transparency && laws.rl1_coherence && ... && laws.rl12_version_isolation
}
```

> **Note**: This is a meta-property description. Each individual law (RL0–RL12)
> is a separate `Property` on `RecorderLaws` and is run independently. The
> `forAll` above is illustrative of the parameterization, not a single
> combined property.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Laws in main scope | Requirement: RecorderLaws ships in main scope as downstream-consumable properties | type system (main-scope `hedgehogMunitMain` dependency) + scenario test | `build.sbt`, `RecorderLawsSpec.scala` |
| RL0 transparency | Requirement: RL0 transparency property | property test (RL0) | `RecorderLawsSpec.scala` |
| RL1 coherence | Requirement: RL1 record/lookup coherence property | property test (RL1) | `RecorderLawsSpec.scala` |
| RL3/RL4 mutation-driven | Requirement: RL3 and RL4 are mutation-generator-driven | property test (RL3, RL4) with `RequestMutation` ADT | `RecorderLawsSpec.scala` |
| RL5 zero-call hit | Requirement: RL5 zero-call hit property | property test (RL5) | `RecorderLawsSpec.scala` |
| RL9 failure fidelity | Requirement: RL9 failure fidelity property | property test (RL9) | `RecorderLawsSpec.scala` |
| RL10 write-failure containment | Requirement: RL10 write-failure containment property | property test (RL10) | `RecorderLawsSpec.scala` |
| RL11 redaction neutrality | Requirement: RL11 redaction neutrality property | property test (RL11) | `RecorderLawsSpec.scala` |
| RL12 version isolation | Requirement: RL12 version isolation property | property test (RL12) | `RecorderLawsSpec.scala` |
| RL2 key determinism | (from call-key spec, Property: key-determinism) | property test (RL2) | `RecorderLawsSpec.scala` |
| RL6 rollout separation | (from call-key spec, Property: rollout-separation) | property test (RL6) | `RecorderLawsSpec.scala` |
| RL7 codec round trip | (from recorder-sink spec, Property: codec-round-trip) | property test (RL7) | `RecorderLawsSpec.scala` |
| RL8 append-only monotonicity | (from recorder-sink spec, Property: append-only-monotonicity) | property test (RL8) | `RecorderLawsSpec.scala` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `RecorderLaws` | class (main scope) | `org.adk4s.record` | `class RecorderLaws[F[_]](recorder: Recorder[F])` with `Property` vals `rl0`–`rl12` |
| `RequestMutation` | ADT | `org.adk4s.record` | `ChangeProvider`, `ChangeModel`, `ReorderMessages`, `ChangeTemperature`, etc. |
| `NonAffectingMutation` | ADT | `org.adk4s.record` | `RegenerateToolCallIds`, `ChangeProviderRequestId`, `ChangeLatency`, etc. |
| `hedgehogMunitMain` | dependency (main scope) | `build.sbt` | `qa.hedgehog %% hedgehog-munit % 0.13.1` (not `% Test`) — precedent: `adk4s-harness-testkit` |
| `RecorderLawsSpec` | test suite | `adk4s-record/src/test` | runs all 13 laws against `noop`, `inMemory`, `file` |
