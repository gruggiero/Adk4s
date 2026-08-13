# Spec: core-types (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Migrates NodeKey to an
     Iron refined opaque type, introduces ReservedNodeKey for the START/END
     values, and adds shared Positive/NonNegative constraint aliases. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [CoreTypes](../../../../concepts/core-types.md) | NodeKey migrated from plain opaque type to Iron refined type; ReservedNodeKey, Positive, NonNegative introduced | `openspec/concepts/core-types.md` |

Updating the `NodeKey` concept file's type/constraint description is PART of
implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `NodeKey` | opaque type (String) | `org.adk4s.core.types` |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |
| `RunInfo` | case class (uses NodeKey) | `org.adk4s.core.types` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `NodeKey` (refined) | opaque type `String :| (NonEmpty & Not[Reserved])` | Compile-time/runtime-enforced non-empty, non-reserved node key. Replaces the plain opaque type. |
| `ReservedNodeKey` | enum (`Start`, `End`) | The `__start__`/`__end__` reserved values, distinct from the general `NodeKey` constraint. Replaces the `NodeKey.START`/`NodeKey.END` constants. |
| `Positive` | type alias (`Int :| numeric.Positive`) | Iron numeric constraint reused across `maxSteps`/`maxConcurrency`/`maxParseAttempts`. |
| `NonNegative` | type alias (`Int :| numeric.Positive0`) | Iron numeric constraint reused by `recallK`. |
| `ConfigError` | case class (AdkError) | Introduced by `error-hierarchy-dedup` spec; used here as the typed error for `NodeKey` refinement failures. |

## ADDED Requirements

### Requirement: NodeKey is a refined opaque type rejecting empty and reserved values

The system SHALL model `NodeKey` as an opaque type backed by `String :|
(NonEmpty & Not[Reserved])` where `Reserved` forbids the strings `__start__`
and `__end__`, so that an empty or reserved node key cannot be constructed as
a `NodeKey` except via the explicit `ReservedNodeKey` enum.

**Given** a string value being refined into a `NodeKey`
**When** the value is empty or one of the reserved strings (`__start__`,
`__end__`)
**Then** refinement fails and returns a `Left(ConfigError)` (or fails
compilation for inline literals)

**Rationale**: Replaces the duplicated `NodeKey.apply`/`from`/`unsafeApply`
constructors with a single `RefinedTypeOps`-backed smart constructor that
makes the invariant a type.

#### Scenario: Non-empty non-reserved literal compiles

**Given** the inline literal `"fetch"`
**When** it is used as a `NodeKey`
**Then** it compiles without an explicit refine call (Iron auto-refinement for
inline literals)

#### Scenario: Empty string is rejected at runtime

**Given** a runtime-derived empty string `""`
**When** it is refined via `NodeKey.refineEither`
**Then** the result is `Left(ConfigError("NodeKey", "", "NonEmpty & Not[Reserved]"))`

#### Scenario: Reserved string is rejected as a NodeKey

**Given** the runtime-derived string `"__start__"`
**When** it is refined via `NodeKey.refineEither`
**Then** the result is `Left(ConfigError("NodeKey", "__start__", "NonEmpty & Not[Reserved]"))`

#### Scenario: Reserved value is constructible as ReservedNodeKey

**Given** the `ReservedNodeKey.Start` enum value
**When** it is converted to its underlying string form
**Then** the value is `"__start__"` and it is NOT a `NodeKey`

### Requirement: NodeKey preserves Cats typeclass instances and value access

The system SHALL preserve the existing `.value` extension, `Eq[NodeKey]`,
`Show[NodeKey]`, and `Order[NodeKey]` instances after migration to the
refined type, so that existing call sites using these instances compile
unchanged.

**Given** a refined `NodeKey`
**When** `.value`, `Eq`, `Show`, or `Order` is used
**Then** the behavior is identical to the pre-migration plain opaque type

**Rationale**: Iron's `A :| C` is a subtype of `A`, so the instances delegate
to the underlying `String` instances without extra wiring.

#### Scenario: .value returns the underlying string

**Given** a `NodeKey` refined from `"fetch"`
**When** `.value` is called
**Then** the result is `"fetch"`

#### Scenario: Eq compares by underlying value

**Given** two `NodeKey`s refined from the same string
**When** they are compared with `Eq[NodeKey].eqv`
**Then** the result is `true`

#### Scenario: Show renders the underlying value

**Given** a `NodeKey` refined from `"fetch"`
**When** `Show[NodeKey].show` is called
**Then** the result is `"fetch"`

### Requirement: Positive and NonNegative constraint aliases are reusable

The system SHALL provide `Positive` and `NonNegative` type aliases for
Iron's numeric constraints, placed in `org.adk4s.core.types`, so that other
specs (`tools-node`, `react-agent`, `memory-orchestration-hook`,
`structured-llm`) can refine numeric parameters with a shared definition.

**Given** a numeric value being refined
**When** `Positive` or `NonNegative` is applied
**Then** the constraint rejects zero/negative (for `Positive`) or negative
(for `NonNegative`) values

**Rationale**: Centralizes the constraint definitions to avoid duplication
across modules.

#### Scenario: Positive rejects zero and negatives

**Given** the values `0`, `-1`, `-100`
**When** refined via `refineEither[Positive]`
**Then** each returns a `Left`

#### Scenario: NonNegative rejects negatives but accepts zero

**Given** the value `0`
**When** refined via `refineEither[NonNegative]`
**Then** the result is `Right(0)`

**Given** the value `-1`
**When** refined via `refineEither[NonNegative]`
**Then** the result is a `Left`

## Properties (Ring 3)

### Property: NodeKey refineEither round-trips for valid inputs

**Invariant**: For every non-empty, non-reserved string `s`,
`NodeKey.refineEither(s).map(_.value) == Right(s)`.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 30)).filter(s => s != "__start__" && s != "__end__")` — filtered (the reserved set is finite and tiny, so discard rate is negligible); covers single-char and multi-char names.

```
forAll { (s: String) =>
  NodeKey.refineEither(s).map(_.value) == Right(s)
}
```

### Property: NodeKey refineEither rejects all empty and reserved inputs

**Invariant**: For the empty string and both reserved strings,
`NodeKey.refineEither(s).isLeft` is true.

**Generator strategy**: `Gen.element1("", "__start__", "__end__")` — constructive, covers exactly the forbidden set.

```
forAll { (s: String) =>
  NodeKey.refineEither(s).isLeft
}
```

### Property: ReservedNodeKey underlying values are the reserved strings

**Invariant**: `ReservedNodeKey.Start.value == "__start__"` and
`ReservedNodeKey.End.value == "__end__"`.

**Generator strategy**: `Gen.element1(ReservedNodeKey.Start, ReservedNodeKey.End)` — constructive.

```
forAll { (r: ReservedNodeKey) =>
  r == ReservedNodeKey.Start && r.value == "__start__" ||
  r == ReservedNodeKey.End && r.value == "__end__"
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `NodeKey("")` as an inline literal | Empty string violates `NonEmpty` | `assertDoesNotCompile("val k: NodeKey = \"\"")` (or `assertErrors`) |
| `NodeKey("__start__")` as an inline literal | Reserved string violates `Not[Reserved]` | `assertDoesNotCompile("val k: NodeKey = \"__start__\"")` |
| `val k: NodeKey = ReservedNodeKey.Start` | `ReservedNodeKey` is not a `NodeKey` | `assertDoesNotCompile("val k: NodeKey = ReservedNodeKey.Start")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Empty/reserved NodeKey unrepresentable as inline literal | Requirement: NodeKey is a refined opaque type rejecting empty and reserved values + Compile-Negative: `NodeKey("")` | type system + compile-negative test | `NodeKeyTypeContract` (adk4s-core/test) |
| Empty/reserved NodeKey rejected at runtime | Requirement: NodeKey is a refined opaque type rejecting empty and reserved values + Scenario: Empty string is rejected at runtime + Scenario: Reserved string is rejected as a NodeKey | smart constructor (`refineEither`) + property test | `NodeKeySpec` (adk4s-core/test) |
| ReservedNodeKey is not a NodeKey | Requirement: NodeKey is a refined opaque type rejecting empty and reserved values + Scenario: Reserved value is constructible as ReservedNodeKey + Compile-Negative | type system + compile-negative test | `NodeKeyTypeContract` (adk4s-core/test) |
| .value/Eq/Show/Order preserved | Requirement: NodeKey preserves Cats typeclass instances and value access + Scenario: .value returns the underlying string + Scenario: Eq compares by underlying value + Scenario: Show renders the underlying value | property test + scenario test | `NodeKeySpec` (adk4s-core/test) |
| Positive rejects zero/negatives | Requirement: Positive and NonNegative constraint aliases are reusable + Scenario: Positive rejects zero and negatives | property test | `ConstraintSpec` (adk4s-core/test) |
| NonNegative rejects negatives, accepts zero | Requirement: Positive and NonNegative constraint aliases are reusable + Scenario: NonNegative rejects negatives but accepts zero | property test | `ConstraintSpec` (adk4s-core/test) |
| NodeKey round-trips for valid inputs | Property: NodeKey refineEither round-trips for valid inputs | Hedgehog property | `NodeKeySpec` |
| NodeKey rejects all empty/reserved | Property: NodeKey refineEither rejects all empty and reserved inputs | Hedgehog property | `NodeKeySpec` |
| ReservedNodeKey values are reserved strings | Property: ReservedNodeKey underlying values are the reserved strings | Hedgehog property | `NodeKeySpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `NodeKey` | opaque type | `org.adk4s.core.types` (adk4s-core) | migrated to `String :| (NonEmpty & Not[Reserved])` with `RefinedTypeOps` |
| `ReservedNodeKey` | enum | `org.adk4s.core.types` (adk4s-core) | new; replaces `NodeKey.START`/`NodeKey.END` constants |
| `Positive` / `NonNegative` | type aliases | `org.adk4s.core.types` (adk4s-core) | new; Iron numeric constraints |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` | retained for backward compat; `ConfigError` is the general form |
| `iron` / `iron-cats` / `iron-upickle` | library deps | `project/Dependencies.scala` | added to adk4s-core |
| `sbt adk4s-core/compile` | build step | adk4s-core | compile-spike first (Iron + NodeKey only) |
