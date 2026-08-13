# Spec: memory-orchestration-hook (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Migrates
     MemoryPolicy.recallK from a require-throwing constructor to a
     refineEither-typed boundary returning ConfigError. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [MemoryAwareRunner](../../../../concepts/memory-aware-runner.md) | MemoryPolicy.recallK refined to NonNegative; require throw removed | `openspec/concepts/memory-aware-runner.md` |

Updating the `memory-aware-runner` concept file's `recallK` description is
PART of implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` |
| `NonNegative` | type alias (Int :| numeric.NonNegative) | `org.adk4s.core.types` (introduced by core-types spec) |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none — reuses `NonNegative` from core-types spec) | — | `recallK` is refined using the shared `NonNegative` alias. |

## MODIFIED Requirements

### Requirement: MemoryPolicy.recallK is non-negative via typed refinement

The system SHALL enforce that `MemoryPolicy.recallK` is non-negative via
`refineEither[NonNegative]` returning `Either[ConfigError, MemoryPolicy]`,
replacing the existing `require(recallK >= 0, …)` that throws
`IllegalArgumentException`.

**Given** a `recallK` value being passed to `MemoryPolicy.apply`
**When** the value is negative
**Then** the result is `Left(ConfigError("recallK", …, "NonNegative"))` and no
`IllegalArgumentException` is thrown

**Rationale**: The `require` throw is the single clearest `require` → Iron
migration in the codebase. It replaces a throwing constructor with a typed
`Either` that fits the project's error-modeling convention (sealed `AdkError`,
no raw throws).

#### Scenario: Non-negative recallK constructs successfully

**Given** `recallK = 5`
**When** `MemoryPolicy.apply(recallK = 5)` is called (via the `Either`-returning overload)
**Then** the result is `Right(MemoryPolicy)` with `recallK == 5`

#### Scenario: Zero recallK constructs successfully

**Given** `recallK = 0`
**When** `MemoryPolicy.apply(recallK = 0)` is called (via the `Either`-returning overload)
**Then** the result is `Right(MemoryPolicy)` with `recallK == 0` (zero is valid for `NonNegative`)

#### Scenario: Negative recallK returns ConfigError

**Given** `recallK = -1`
**When** `MemoryPolicy.apply(recallK = -1)` is called (via the `Either`-returning overload)
**Then** the result is `Left(ConfigError("recallK", "-1", "NonNegative"))`

#### Scenario: Throwing overload preserved for source compatibility

**Given** a caller using the existing throwing `MemoryPolicy.apply(recallK)`
**When** called with `recallK = -1`
**Then** it throws (delegates to `refineUnsafe`) with a message containing
`"recallK"` and `"NonNegative"` — preserving source compatibility for callers
that catch `IllegalArgumentException`

#### Scenario: Default policy remains recallK = 5

**Given** `MemoryPolicy.default`
**When** it is accessed
**Then** `recallK == 5` (no regression)

## Properties (Ring 3)

### Property: recallK refineEither round-trips for non-negative inputs

**Invariant**: For every non-negative integer `n`,
`refineEither[NonNegative](n).map(_.value) == Right(n)`.

**Generator strategy**: `Gen.int(Range.linear(0, 100))` — constructive, covers zero and positive values.

```
forAll { (n: Int) =>
  refineEither[NonNegative](n).map(_.value) == Right(n)
}
```

### Property: recallK rejects all negatives

**Invariant**: For `n < 0`, `MemoryPolicy.applyEither(recallK = n).isLeft`
is true and the error is a `ConfigError`.

**Generator strategy**: `Gen.int(Range.linear(-100, -1))` — constructive, covers negatives.

```
forAll { (n: Int) =>
  MemoryPolicy.applyEither(recallK = n).isLeft &&
  MemoryPolicy.applyEither(recallK = n).left.get.isInstanceOf[ConfigError]
}
```

### Property: MemoryPolicy.default recallK is 5

**Invariant**: `MemoryPolicy.default.recallK == 5`.

**Generator strategy**: `Gen.unit` — constant property.

```
forAll { (_: Unit) =>
  MemoryPolicy.default.recallK == 5
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| (none — `recallK` is a runtime value, not a literal; the constraint is enforced at the smart constructor, not the type level) | — | runtime property tests cover rejection |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Negative recallK returns ConfigError, no throw | Requirement: MemoryPolicy.recallK is non-negative via typed refinement + Scenario: Negative recallK returns ConfigError | smart constructor (`refineEither`) + property test | `MemoryPolicySpec` (adk4s-orchestration/test) |
| Zero recallK accepted | Requirement: MemoryPolicy.recallK is non-negative via typed refinement + Scenario: Zero recallK constructs successfully | scenario test | `MemoryPolicySpec` |
| Throwing overload preserved | Requirement: MemoryPolicy.recallK is non-negative via typed refinement + Scenario: Throwing overload preserved for source compatibility | scenario test (delegates to `refineUnsafe`) | `MemoryPolicySpec` |
| Default recallK is 5 | Requirement: MemoryPolicy.recallK is non-negative via typed refinement + Scenario: Default policy remains recallK = 5 + Property: MemoryPolicy.default recallK is 5 | scenario test + property | `MemoryPolicySpec` |
| Non-negative round-trips | Property: recallK refineEither round-trips | Hedgehog property | `MemoryPolicySpec` |
| All negatives rejected | Property: recallK rejects all negatives | Hedgehog property | `MemoryPolicySpec` |
| No silent fallback for invalid recallK | Requirement: MemoryPolicy.recallK is non-negative via typed refinement (adversarial: never throws IllegalArgumentException for the Either overload) | Ring 8 adversarial review | `MemoryPolicySpec` + review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MemoryPolicy` | case class | `org.adk4s.orchestration.memory` (adk4s-orchestration) | `recallK` field becomes `Int :| NonNegative`; `require` removed |
| `MemoryPolicy.apply` | smart constructor | `org.adk4s.orchestration.memory` (adk4s-orchestration) | gains `applyEither` returning `Either[ConfigError, MemoryPolicy]`; throwing overload delegates to `refineUnsafe` |
| `NonNegative` | type alias | `org.adk4s.core.types` (adk4s-core) | reused from core-types spec |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (adk4s-core) | reused from error-hierarchy-dedup spec |
| `sbt adk4s-orchestration/test` | build step | adk4s-orchestration | `MemoryPolicySpec` runs |
