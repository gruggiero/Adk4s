# Spec: checkpoint-store-fpoly (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Promotes
     CheckpointStore.CheckpointId from a transparent type alias to an
     Iron refined opaque type with a NonEmpty constraint. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [CheckpointStore](../../../../concepts/checkpoint-store.md) | CheckpointId promoted from transparent alias to opaque refined type | `openspec/concepts/checkpoint-store.md` |

Updating the `checkpoint-store` concept file's `CheckpointId` type
description is PART of implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `CheckpointStore.CheckpointId` | transparent type alias (String) | `org.adk4s.orchestration.interrupt` |
| `CheckpointStore` | trait (F-polymorphic) | `org.adk4s.orchestration.interrupt` |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `CheckpointStore.CheckpointId` (refined) | opaque type `String :| NonEmpty` | Non-empty checkpoint identifier. Promoted from transparent `type CheckpointId = String` alias, which accepted any `String` including the empty string. |

## ADDED Requirements

### Requirement: CheckpointId is an opaque refined type rejecting empty strings

The system SHALL model `CheckpointStore.CheckpointId` as an opaque type
backed by `String :| NonEmpty`, so that an empty checkpoint id cannot be
constructed and the type is no longer a transparent alias that accepts any
`String`.

**Given** a string being refined into a `CheckpointId`
**When** the string is empty
**Then** refinement fails and returns a `Left(ConfigError)`

**Rationale**: Today `type CheckpointId = String` is a transparent alias, so
any `String` — including `""` — is a valid `CheckpointId`. The opaque refined
type closes this hole and restores type safety (a raw `String` is no longer
assignable to `CheckpointId` without refinement).

#### Scenario: Non-empty literal compiles

**Given** the inline literal `"ckpt-1"`
**When** it is used as a `CheckpointId`
**Then** it compiles without an explicit refine call

#### Scenario: Empty string is rejected at runtime

**Given** a runtime-derived empty string
**When** it is refined via `CheckpointId.refineEither`
**Then** the result is `Left(ConfigError("CheckpointId", "", "NonEmpty"))`

#### Scenario: Raw String is not assignable to CheckpointId

**Given** a raw `String` value `"ckpt-1"`
**When** it is assigned to a `CheckpointId`-typed variable without refinement
**Then** compilation fails (opaque type boundary)

### Requirement: CheckpointStore API uses the refined CheckpointId

The system SHALL update the `CheckpointStore[F]` trait's methods
(`get`, `set`, `delete`, `keys`) and the `inMemory` factory to use the
refined `CheckpointId` type, so that callers cannot pass a raw `String` where
a `CheckpointId` is expected.

**Given** the `CheckpointStore[F]` trait after migration
**When** a caller invokes `store.get(checkpointId)` with a raw `String`
**Then** compilation fails unless the `String` is refined into a
`CheckpointId` first

**Rationale**: The transparent alias let callers pass `String` directly; the
opaque type restores the type boundary.

#### Scenario: get accepts a refined CheckpointId

**Given** a `CheckpointStore[IO]` and a `CheckpointId` refined from `"ckpt-1"`
**When** `store.get(checkpointId)` is called
**Then** it compiles and returns `IO[Option[Array[Byte]]]`

#### Scenario: get rejects a raw String

**Given** a `CheckpointStore[IO]` and a raw `String` `"ckpt-1"`
**When** `store.get("ckpt-1")` is called
**Then** compilation fails

### Requirement: Refined CheckpointId serializes via iron-upickle

The system SHALL provide a upickle `ReadWriter` for `CheckpointId` via the
`iron-upickle` bridge, so that refined checkpoint ids round-trip through JSON
identically to the pre-migration transparent alias.

**Given** a refined `CheckpointId`
**When** it is serialized to JSON and deserialized back
**Then** the deserialized value equals the original

**Rationale**: Checkpoint ids are persisted in checkpoint metadata; the
round-trip must not regress.

#### Scenario: CheckpointId round-trips through JSON

**Given** a `CheckpointId` refined from `"ckpt-1"`
**When** it is written to JSON and read back
**Then** the result is `Right(CheckpointId("ckpt-1"))` and `.value` is `"ckpt-1"`

## Properties (Ring 3)

### Property: CheckpointId refineEither round-trips for non-empty inputs

**Invariant**: For every non-empty string `s`,
`CheckpointId.refineEither(s).map(_.value) == Right(s)`.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 30))` — constructive.

```
forAll { (s: String) =>
  CheckpointId.refineEither(s).map(_.value) == Right(s)
}
```

### Property: CheckpointId JSON round-trip

**Invariant**: For every refined `CheckpointId`,
`read[CheckpointId](write(c)) == c`.

**Generator strategy**: same as above; uses `iron-upickle` `ReadWriter`.

```
forAll { (c: CheckpointId) =>
  read[CheckpointId](write(c)) == c
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `val id: CheckpointId = "ckpt-1"` (raw String assignment) | Opaque type boundary — raw `String` is not a `CheckpointId` | `assertDoesNotCompile("val id: CheckpointStore.CheckpointId = \"ckpt-1\"")` |
| `CheckpointId("")` as inline literal | Empty violates `NonEmpty` | `assertDoesNotCompile("val id: CheckpointId = \"\"")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Empty CheckpointId unrepresentable | Requirement: CheckpointId is an opaque refined type rejecting empty strings + Compile-Negative | type system + compile-negative test | `CheckpointStoreTypeContract` (adk4s-orchestration/test) |
| Empty CheckpointId rejected at runtime | Requirement: CheckpointId is an opaque refined type rejecting empty strings + Scenario: Empty string is rejected at runtime | smart constructor + property test | `CheckpointStoreSpec` (adk4s-orchestration/test) |
| Raw String not assignable to CheckpointId | Requirement: CheckpointId is an opaque refined type rejecting empty strings + Scenario: Raw String is not assignable to CheckpointId + Compile-Negative | type system + compile-negative test | `CheckpointStoreTypeContract` |
| CheckpointStore API uses refined type | Requirement: CheckpointStore API uses the refined CheckpointId + Scenario: get rejects a raw String | compile-negative test | `CheckpointStoreTypeContract` |
| JSON round-trip | Requirement: Refined CheckpointId serializes via iron-upickle + Scenario: CheckpointId round-trips through JSON + Property: CheckpointId JSON round-trip | Ring 4 round-trip property | `CheckpointStoreSpec` |
| CheckpointId round-trips for non-empty | Property: CheckpointId refineEither round-trips | Hedgehog property | `CheckpointStoreSpec` |
| JSON round-trip | Property: CheckpointId JSON round-trip | Hedgehog property (Ring 4) | `CheckpointStoreSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `CheckpointStore.CheckpointId` | type (was transparent alias) | `org.adk4s.orchestration.interrupt` (adk4s-orchestration) | promoted to opaque `String :| NonEmpty` |
| `CheckpointStore[F]` | trait | `org.adk4s.orchestration.interrupt` (adk4s-orchestration) | method signatures use refined `CheckpointId` |
| `InMemoryCheckpointStore` | object | `org.adk4s.orchestration.interrupt` (adk4s-orchestration) | factory delegates to `CheckpointStore.inMemory[IO]` |
| `iron-upickle` | library dep | `project/Dependencies.scala` | added to adk4s-orchestration |
| `sbt adk4s-orchestration/Test/compile` | build step | adk4s-orchestration | typed contract compiles |
