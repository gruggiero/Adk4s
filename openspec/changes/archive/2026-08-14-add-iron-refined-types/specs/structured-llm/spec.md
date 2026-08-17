# Spec: structured-llm (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Refines
     StructuredLLM's maxParseAttempts to Int :| Positive at the factory
     boundary. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [StructuredLLM](../../../../concepts/structured-llm.md) | StructuredLLM factory maxParseAttempts refined to Positive | `openspec/concepts/structured-llm.md` |

Updating the `structured-llm` concept file's `maxParseAttempts` description
is PART of implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM` | trait | `org.adk4s.structured.core` |
| `Positive` | type alias (Int :| numeric.Positive) | `org.adk4s.core.types` (introduced by core-types spec) |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none — reuses `Positive` from core-types spec) | — | `maxParseAttempts` is refined using the shared `Positive` alias. |

## ADDED Requirements

### Requirement: StructuredLLM maxParseAttempts is refined to Positive

The system SHALL refine the `maxParseAttempts` parameter of
`StructuredLLM.fromClientWithMiddlewares` (and related factories) to
`Int :| Positive` at the construction boundary, so that a non-positive parse
attempt count cannot configure the retry loop.

**Given** a `maxParseAttempts` value passed to a `StructuredLLM` factory
**When** the value is zero or negative
**Then** refinement fails and returns a `Left(ConfigError)`

**Rationale**: Today `maxParseAttempts: Int = 1` accepts any `Int` including
`0` (no attempts — immediate failure) or negatives (undefined behavior in the
retry loop).

#### Scenario: Positive literal compiles

**Given** the inline literal `3`
**When** it is used as `maxParseAttempts` in a factory call
**Then** it compiles without an explicit refine call

#### Scenario: Zero is rejected at runtime

**Given** a runtime-derived `maxParseAttempts = 0`
**When** it is refined via `refineEither[Positive]`
**Then** the result is `Left(ConfigError("maxParseAttempts", "0", "Positive"))`

#### Scenario: Negative is rejected at runtime

**Given** a runtime-derived `maxParseAttempts = -1`
**When** it is refined via `refineEither[Positive]`
**Then** the result is a `Left(ConfigError)`

#### Scenario: Default value 1 remains valid

**Given** the default `maxParseAttempts = 1` (no explicit value)
**When** a `StructuredLLM` factory is called
**Then** the refinement succeeds and `maxParseAttempts` is `1`

### Requirement: maxParseAttempts refinement preserves retry behavior for valid inputs

The system SHALL preserve the existing parse-retry behavior for all positive
`maxParseAttempts` values; the refinement is a pure boundary check that does
not alter the retry loop's attempt count, delay, or trigger logic.

**Given** a positive `maxParseAttempts` value
**When** the `StructuredLLM` retry loop runs before and after this change
**Then** the number of parse attempts, the retry trigger behavior, and the
final parse result are identical

**Rationale**: Type-safety hardening, not a behavioral change.

#### Scenario: Existing StructuredLLM tests pass unchanged

**Given** the existing `StructuredLLM` test suite
**When** the tests are run after migration
**Then** all tests pass (no behavioral regression for valid `maxParseAttempts`)

## Properties (Ring 3)

### Property: maxParseAttempts refineEither round-trips for positive inputs

**Invariant**: For every positive integer `n`,
`refineEither[Positive](n).map(_.value) == Right(n)`.

**Generator strategy**: `Gen.int(Range.linear(1, 10))` — constructive, covers typical attempt counts.

```
forAll { (n: Int) =>
  refineEither[Positive](n).map(_.value) == Right(n)
}
```

### Property: maxParseAttempts rejects zero and negatives

**Invariant**: For `n <= 0`, `refineEither[Positive](n).isLeft` is true.

**Generator strategy**: `Gen.int(Range.linear(-10, 0))` — constructive.

```
forAll { (n: Int) =>
  refineEither[Positive](n).isLeft
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| (none — `maxParseAttempts` is a runtime parameter; constraint enforced at the factory boundary) | — | runtime property tests cover rejection |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Zero/negative maxParseAttempts rejected | Requirement: StructuredLLM maxParseAttempts is refined… + Scenarios | smart constructor (`refineEither`) + property test | `StructuredLLMConfigSpec` (structured-llm/test) |
| Default 1 remains valid | Requirement: StructuredLLM maxParseAttempts is refined… + Scenario: Default value 1 remains valid | scenario test | `StructuredLLMConfigSpec` |
| Retry behavior preserved | Requirement: maxParseAttempts refinement preserves retry behavior… + Scenario: Existing StructuredLLM tests pass unchanged | regression test (existing suite) | existing `StructuredLLM` tests must still pass |
| Positive round-trips | Property: maxParseAttempts refineEither round-trips | Hedgehog property | `StructuredLLMConfigSpec` |
| Zero/negatives rejected | Property: maxParseAttempts rejects zero and negatives | Hedgehog property | `StructuredLLMConfigSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `StructuredLLM.fromClientWithMiddlewares` | factory method | `org.adk4s.structured.core` (structured-llm) | `maxParseAttempts` refined at boundary via `refineEither[Positive]` |
| `Positive` | type alias | `org.adk4s.core.types` (adk4s-core) | reused from core-types spec |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (adk4s-core) | reused from error-hierarchy-dedup spec |
| `sbt structured-llm/test` | build step | structured-llm | existing tests must still pass |
