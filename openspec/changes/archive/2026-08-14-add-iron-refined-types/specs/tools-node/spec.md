# Spec: tools-node (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Refines
     ToolsNodeConfig.maxConcurrency to Int :| Positive at the construction
     boundary. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ToolsNode](../../../../concepts/tools-node.md) | ToolsNodeConfig.maxConcurrency gains a Positive bound | `openspec/concepts/tools-node.md` |

Updating the `tools-node` concept file's `maxConcurrency` description is PART
of implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ToolsNodeConfig` | case class | `org.adk4s.core.tools` |
| `ToolsNodeConfigBuilder` | case class (builder) | `org.adk4s.core.tools` |
| `Positive` | type alias (Int :| numeric.Positive) | `org.adk4s.core.types` (introduced by core-types spec) |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none — reuses `Positive` from core-types spec) | — | `maxConcurrency` is refined using the shared `Positive` alias. |

## ADDED Requirements

### Requirement: ToolsNodeConfig.maxConcurrency is refined to Positive

The system SHALL refine `ToolsNodeConfig.maxConcurrency` to `Int :| Positive`
at the construction boundary, so that a non-positive concurrency value cannot
be used to configure parallel tool execution.

**Given** a `maxConcurrency` value being set on a `ToolsNodeConfig` or its
builder
**When** the value is zero or negative
**Then** refinement fails and returns a `Left(ConfigError)`

**Rationale**: Today `maxConcurrency: Int = 10` accepts any `Int` including
`0` (which would stall `parEvalMap`) or negatives (undefined behavior in
fs2's `parEvalMap`).

#### Scenario: Positive literal compiles

**Given** the inline literal `4`
**When** it is used as `maxConcurrency` via `ToolsNodeConfig.builder.parallel(4)`
**Then** it compiles without an explicit refine call

#### Scenario: Zero is rejected at runtime

**Given** a runtime-derived `maxConcurrency = 0`
**When** it is refined via `refineEither[Positive]`
**Then** the result is `Left(ConfigError("maxConcurrency", "0", "Positive"))`

#### Scenario: Negative is rejected at runtime

**Given** a runtime-derived `maxConcurrency = -1`
**When** it is refined via `refineEither[Positive]`
**Then** the result is a `Left(ConfigError)`

#### Scenario: Default value 10 remains valid

**Given** the default `ToolsNodeConfig()` (no explicit `maxConcurrency`)
**When** the config is constructed
**Then** `maxConcurrency` is `10` (a valid `Positive`) and no error is raised

### Requirement: ToolsNodeConfigBuilder.parallel returns typed error for invalid input

The system SHALL provide an overload of
`ToolsNodeConfigBuilder.parallel` that returns
`Either[ConfigError, ToolsNodeConfigBuilder]` for runtime-derived values,
alongside the existing throwing overload (which delegates to `refineUnsafe`
for source compatibility).

**Given** a runtime-derived `maxConcurrency` value
**When** `parallelEither(maxConcurrency)` is called
**Then** the result is `Right(builder)` for positive values or
`Left(ConfigError)` for zero/negative values

**Rationale**: Replaces silent acceptance of invalid values with a typed
error path; the throwing overload preserves source compatibility.

#### Scenario: parallelEither accepts positive value

**Given** `maxConcurrency = 8`
**When** `builder.parallelEither(8)` is called
**Then** the result is a `Right` containing the updated builder

#### Scenario: parallelEither rejects zero

**Given** `maxConcurrency = 0`
**When** `builder.parallelEither(0)` is called
**Then** the result is a `Left(ConfigError)`

## Properties (Ring 3)

### Property: maxConcurrency refineEither round-trips for positive inputs

**Invariant**: For every positive integer `n`,
`refineEither[Positive](n).map(_.value) == Right(n)`.

**Generator strategy**: `Gen.int(Range.linear(1, 100))` — constructive, covers small and large positive values.

```
forAll { (n: Int) =>
  refineEither[Positive](n).map(_.value) == Right(n)
}
```

### Property: maxConcurrency rejects zero and negatives

**Invariant**: For `n <= 0`, `refineEither[Positive](n).isLeft` is true.

**Generator strategy**: `Gen.int(Range.linear(-100, 0))` — constructive, covers zero and negatives.

```
forAll { (n: Int) =>
  refineEither[Positive](n).isLeft
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ToolsNodeConfig(maxConcurrency = 0)` as inline literal | Zero violates `Positive` | `assertDoesNotCompile("ToolsNodeConfig(maxConcurrency = 0)")` (if the field type becomes `Int :| Positive`); otherwise runtime property covers it |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Zero/negative maxConcurrency rejected | Requirement: ToolsNodeConfig.maxConcurrency is refined… + Scenarios | smart constructor (`refineEither`) + property test | `ToolsNodeConfigSpec` (adk4s-core/test) |
| Default 10 remains valid | Requirement: ToolsNodeConfig.maxConcurrency is refined… + Scenario: Default value 10 remains valid | scenario test | `ToolsNodeConfigSpec` |
| parallelEither returns typed error | Requirement: ToolsNodeConfigBuilder.parallel returns typed error… + Scenarios | scenario test | `ToolsNodeConfigSpec` |
| Positive round-trips | Property: maxConcurrency refineEither round-trips | Hedgehog property | `ToolsNodeConfigSpec` |
| Zero/negatives rejected | Property: maxConcurrency rejects zero and negatives | Hedgehog property | `ToolsNodeConfigSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ToolsNodeConfig` | case class | `org.adk4s.core.tools` (adk4s-core) | `maxConcurrency` refined at boundary; field may stay `Int` with internal refinement or become `Int :| Positive` (design decision) |
| `ToolsNodeConfigBuilder.parallel` | method | `org.adk4s.core.tools` (adk4s-core) | gains `parallelEither` overload returning `Either[ConfigError, …]` |
| `Positive` | type alias | `org.adk4s.core.types` (adk4s-core) | reused from core-types spec |
| `sbt adk4s-core/Test/compile` | build step | adk4s-core | typed contract compiles |
