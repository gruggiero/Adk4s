# Spec: harness-state (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Migrates
     MiddlewareName and StateCell.CellId to Iron refined opaque types with
     NonEmpty (and format for CellId) constraints. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [HarnessState](../../../../concepts/harness-state.md) | StateCell.CellId gains a NonEmpty+format constraint; MiddlewareName gains NonEmpty | `openspec/concepts/harness-state.md` |

Updating the `harness-state` concept file's type descriptions is PART of
implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `MiddlewareName` | opaque type (String) | `org.adk4s.harness` |
| `StateCell.CellId` | opaque type (String) | `org.adk4s.harness` |
| `StateCell` | final class | `org.adk4s.harness` |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `MiddlewareName` (refined) | opaque type `String :| NonEmpty` | Non-empty middleware identity. Replaces the unvalidated plain opaque type. |
| `StateCell.CellId` (refined) | opaque type `String :| (NonEmpty & Match["[^/]+/[^/]+"])` | Format-checked cell identity (`owner/name`). Replaces the unvalidated plain opaque type. |

## ADDED Requirements

### Requirement: MiddlewareName rejects empty strings

The system SHALL model `MiddlewareName` as an opaque type backed by
`String :| NonEmpty`, so that an empty middleware name cannot be constructed.

**Given** a string being refined into a `MiddlewareName`
**When** the string is empty
**Then** refinement fails and returns a `Left(ConfigError)`

**Rationale**: Today `MiddlewareName.apply("")` silently succeeds, producing
a cell id like `"/n"` from `CellId(owner, name)`. The constraint closes this
hole.

#### Scenario: Non-empty literal compiles

**Given** the inline literal `"todo"`
**When** it is used as a `MiddlewareName`
**Then** it compiles without an explicit refine call

#### Scenario: Empty string is rejected at runtime

**Given** a runtime-derived empty string
**When** it is refined via `MiddlewareName.refineEither`
**Then** the result is `Left(ConfigError("MiddlewareName", "", "NonEmpty"))`

#### Scenario: .value returns the underlying string

**Given** a `MiddlewareName` refined from `"todo"`
**When** `.value` is called
**Then** the result is `"todo"`

### Requirement: StateCell.CellId rejects empty and malformed values

The system SHALL model `StateCell.CellId` as an opaque type backed by
`String :| (NonEmpty & Match["[^/]+/[^/]+"])`, so that a cell id that is empty
or does not match the `owner/name` format cannot be constructed.

**Given** a string being refined into a `CellId`
**When** the string is empty or does not contain exactly one `/` separating
two non-empty segments
**Then** refinement fails and returns a `Left(ConfigError)`

**Rationale**: Today `CellId.apply(owner, name)` concatenates with no
validation; an empty `name` yields `"owner/"`. The constraint enforces the
format at the type level.

#### Scenario: Well-formed owner/name compiles

**Given** the inline literal `"counter/n"`
**When** it is used as a `CellId`
**Then** it compiles without an explicit refine call

#### Scenario: Empty string is rejected at runtime

**Given** a runtime-derived empty string
**When** it is refined via `CellId.refineEither`
**Then** the result is a `Left(ConfigError)`

#### Scenario: Missing name segment is rejected

**Given** the runtime-derived string `"counter/"`
**When** it is refined via `CellId.refineEither`
**Then** the result is a `Left(ConfigError)` (violates `Match["[^/]+/[^/]+"]`)

#### Scenario: CellId.apply constructs a valid id from owner and name

**Given** a `MiddlewareName` refined from `"counter"` and the name `"n"`
**When** `CellId.apply(owner, name)` is called
**Then** the result is a `CellId` whose `.value` is `"counter/n"`

### Requirement: Refined MiddlewareName and CellId serialize via iron-upickle

The system SHALL provide upickle `ReadWriter` instances for `MiddlewareName`
and `StateCell.CellId` via the `iron-upickle` bridge, so that refined values
round-trip through JSON identically to the pre-migration plain opaque types.

**Given** a refined `MiddlewareName` or `CellId`
**When** it is serialized to JSON and deserialized back
**Then** the deserialized value equals the original

**Rationale**: Both types flow through upickle in `HarnessState` snapshots.
Iron's subtype-of-underlying property means they serialize as `String`, but
the `iron-upickle` codec must be wired and round-trip-tested.

#### Scenario: MiddlewareName round-trips through JSON

**Given** a `MiddlewareName` refined from `"todo"`
**When** it is written to JSON and read back
**Then** the result is `Right(MiddlewareName("todo"))` and `.value` is `"todo"`

#### Scenario: CellId round-trips through JSON

**Given** a `CellId` refined from `"counter/n"`
**When** it is written to JSON and read back
**Then** the result is `Right(CellId("counter/n"))` and `.value` is `"counter/n"`

#### Scenario: Existing HarnessState snapshots still decode

**Given** a pre-migration `HarnessState` JSON snapshot containing cell ids
**When** it is decoded with the refined `CellId` codec
**Then** all valid cell ids decode successfully (no regression)

## Properties (Ring 3)

### Property: MiddlewareName refineEither round-trips for non-empty inputs

**Invariant**: For every non-empty string `s`,
`MiddlewareName.refineEither(s).map(_.value) == Right(s)`.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 30))` — constructive, covers single-char and multi-char names.

```
forAll { (s: String) =>
  MiddlewareName.refineEither(s).map(_.value) == Right(s)
}
```

### Property: CellId refineEither round-trips for well-formed inputs

**Invariant**: For every string matching `[^/]+/[^/]+`,
`CellId.refineEither(s).map(_.value) == Right(s)`.

**Generator strategy**: constructive — `for owner <- Gen.string(Gen.alpha, Range.linear(1, 10)); name <- Gen.string(Gen.alpha, Range.linear(1, 10)); yield s"$owner/$name"` — covers single and multi-segment names.

```
forAll { (owner: String, name: String) =>
  val s: String = s"$owner/$name"
  CellId.refineEither(s).map(_.value) == Right(s)
}
```

### Property: MiddlewareName and CellId JSON round-trip

**Invariant**: For every refined `MiddlewareName`/`CellId`,
`read[MiddlewareName](write(m)) == m` and `read[CellId](write(c)) == c`.

**Generator strategy**: same as above two properties; uses `iron-upickle` `ReadWriter`.

```
forAll { (m: MiddlewareName) =>
  read[MiddlewareName](write(m)) == m
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `MiddlewareName("")` as inline literal | Empty violates `NonEmpty` | `assertDoesNotCompile("val m: MiddlewareName = \"\"")` |
| `CellId("")` as inline literal | Empty violates `NonEmpty` | `assertDoesNotCompile("val c: CellId = \"\"")` |
| `CellId("no-slash")` as inline literal | Missing `/` violates `Match` | `assertDoesNotCompile("val c: CellId = \"no-slash\"")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Empty MiddlewareName unrepresentable | Requirement: MiddlewareName rejects empty strings + Compile-Negative | type system + compile-negative test | `HarnessStateTypeContract` (adk4s-harness-api/test) |
| Empty MiddlewareName rejected at runtime | Requirement: MiddlewareName rejects empty strings + Scenario: Empty string is rejected at runtime | smart constructor + property test | `MiddlewareNameSpec` (adk4s-harness-api/test) |
| Empty/malformed CellId unrepresentable | Requirement: StateCell.CellId rejects empty and malformed values + Compile-Negative | type system + compile-negative test | `HarnessStateTypeContract` (adk4s-harness-api/test) |
| Empty/malformed CellId rejected at runtime | Requirement: StateCell.CellId rejects empty and malformed values + Scenario: Empty string is rejected at runtime + Scenario: Missing name segment is rejected | smart constructor + property test | `CellIdSpec` (adk4s-harness-api/test) |
| CellId.apply constructs valid id | Requirement: StateCell.CellId rejects empty and malformed values + Scenario: CellId.apply constructs a valid id from owner and name | scenario test | `CellIdSpec` |
| JSON round-trip for MiddlewareName/CellId | Requirement: Refined MiddlewareName and CellId serialize via iron-upickle + Scenario: MiddlewareName round-trips through JSON + Scenario: CellId round-trips through JSON | Ring 4 round-trip property + existing snapshot test | `HarnessStateSpec` (adk4s-harness-api/test) |
| Existing snapshots decode | Requirement: Refined MiddlewareName and CellId serialize via iron-upickle + Scenario: Existing HarnessState snapshots still decode | Ring 4 compatibility fixture | `HarnessStateSpec` |
| MiddlewareName round-trips for non-empty | Property: MiddlewareName refineEither round-trips | Hedgehog property | `MiddlewareNameSpec` |
| CellId round-trips for well-formed | Property: CellId refineEither round-trips | Hedgehog property | `CellIdSpec` |
| JSON round-trip | Property: MiddlewareName and CellId JSON round-trip | Hedgehog property (Ring 4) | `HarnessStateSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MiddlewareName` | opaque type | `org.adk4s.harness` (adk4s-harness-api) | migrated to `String :| NonEmpty` |
| `StateCell.CellId` | opaque type | `org.adk4s.harness` (adk4s-harness-api) | migrated to `String :| (NonEmpty & Match[…])` |
| `StateCell.apply` | factory | `org.adk4s.harness` (adk4s-harness-api) | `CellId.apply` now refines via `refineEither` |
| `iron-upickle` | library dep | `project/Dependencies.scala` | added to adk4s-harness-api for ReadWriter bridge |
| `sbt adk4s-harness-api/Test/compile` | build step | adk4s-harness-api | typed contract compiles against real classpath |
