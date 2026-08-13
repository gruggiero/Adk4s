# Spec: error-hierarchy-dedup (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Introduces two new
     AdkError variants that the other specs in this change depend on:
     ConfigError (refinement-boundary failures) and GraphCompilationError
     (typed graph-compile failures). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ErrorHierarchy](../../../../concepts/error-hierarchy.md) | sealed error hierarchy extended with two new variants (ConfigError, GraphCompilationError) | `openspec/concepts/error-hierarchy.md` |

Updating the `AdkError` concept file's variant list is PART of implementing
this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AdkError` | sealed trait | `org.adk4s.core.error` |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` |
| `AdkError` | sealed trait (graph validation errors) | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ConfigError` | case class (AdkError) | Typed error for invalid configuration/identifier refinement failures. Carries `field: String`, `invalidValue: String`, `constraint: String`. Replaces `require`-thrown `IllegalArgumentException` at refinement boundaries. |
| `GraphCompilationError` | case class (AdkError) | Typed error for graph-compilation failures. Carries `errors: List[AdkError]` (the validation errors from `graph.compile`). Replaces the generic `Exception` raised by `GraphExecutor.execute`/`executeWithError`. |

## ADDED Requirements

### Requirement: ConfigError variant for refinement-boundary failures

The system SHALL provide an `AdkError` variant named `ConfigError` that
carries the failing field name, the invalid value as a string, and the
constraint that was violated, so that refinement-boundary failures (invalid
identifiers, out-of-range numeric parameters) surface as typed errors instead
of thrown `IllegalArgumentException`.

**Given** a refinement boundary that rejects an input value for violating a
constraint
**When** the boundary returns an error
**Then** the error is a `ConfigError` whose `field` names the parameter, whose
`invalidValue` is the rejected value's string form, and whose `constraint`
names the violated constraint

**Rationale**: Replaces ad-hoc `require` throws (e.g.
`MemoryPolicy.recallK`) with a typed error that callers can pattern-match
without catching `IllegalArgumentException`.

#### Scenario: Valid input produces no ConfigError

**Given** a refinement boundary receiving an input that satisfies its
constraint
**When** the boundary refines the input
**Then** the result is a `Right` containing the refined value, and no
`ConfigError` is produced

#### Scenario: Invalid input produces ConfigError with field and constraint

**Given** a refinement boundary receiving `recallK = -1` (violates
`NonNegative`)
**When** the boundary refines the input
**Then** the result is a `Left(ConfigError("recallK", "-1", "NonNegative"))`

#### Scenario: ConfigError is an AdkError variant

**Given** a `ConfigError` instance
**When** it is matched against the `AdkError` sealed trait
**Then** it is recognized as a distinct variant and its `message` field
contains the field name, invalid value, and constraint name

### Requirement: GraphCompilationError variant for graph-compile failures

The system SHALL provide an `AdkError` variant named `GraphCompilationError`
that carries the list of graph validation errors, so that graph-compilation
failures surface as typed errors instead of a generic `Exception`.

**Given** a graph that fails `graph.compile`
**When** the graph executor handles the failure
**Then** the raised error is a `GraphCompilationError` whose `errors` field
contains every `AdkError` validation error produced by compilation

**Rationale**: Replaces `IO.raiseError(new Exception("Graph validation
failed: …"))` with a typed `AdkError` variant that callers can match
explicitly.

#### Scenario: Compiled graph produces no GraphCompilationError

**Given** a graph that passes `graph.compile`
**When** the graph executor proceeds to execution
**Then** no `GraphCompilationError` is raised

#### Scenario: Failed compile produces GraphCompilationError with all errors

**Given** a graph with two validation errors (missing entry node, dead-end
node)
**When** the graph executor handles the compile failure
**Then** the raised `GraphCompilationError.errors` list has length 2 and
contains both validation errors

#### Scenario: GraphCompilationError is an AdkError variant

**Given** a `GraphCompilationError` instance
**When** it is matched against the `AdkError` sealed trait
**Then** it is recognized as a distinct variant and its `message` field
contains the rendered validation errors

### Requirement: Existing AdkError pattern matches handle new variants

The system SHALL ensure that every existing pattern match over `AdkError`
that lacks a catch-all arm is updated to handle `ConfigError` and
`GraphCompilationError` explicitly, because the build's exhaustiveness
escalation (`-Wconf:name=PatternMatchExhaustivity:e`) makes non-exhaustive
matches fail compilation.

**Given** the two new `AdkError` variants `ConfigError` and
`GraphCompilationError`
**When** the codebase is compiled
**Then** every existing match over `AdkError` either handles the new variants
explicitly or already had a catch-all arm (which is recorded)

**Rationale**: The exhaustiveness escalation is the intended safety
mechanism; this requirement makes the migration explicit and auditable.

#### Scenario: Match without catch-all fails compilation until updated

**Given** an existing `AdkError` match with no catch-all arm
**When** the new variants are added
**Then** compilation fails with a `PatternMatchExhaustivity` error until the
match handles `ConfigError` and `GraphCompilationError`

#### Scenario: Match with catch-all continues to compile

**Given** an existing `AdkError` match with a `case _ =>` arm
**When** the new variants are added
**Then** compilation succeeds and the catch-all arm receives the new variants

## Properties (Ring 3)

### Property: ConfigError round-trips through AdkError Show

**Invariant**: For every `ConfigError`, `Show[AdkError].show(e)` contains the
field name, the invalid value, and the constraint name.

**Generator strategy**: `Gen.string(Gen.alpha, Range.linear(1, 20))` for
field/constraint; `Gen.string(Gen.alphaNum, Range.linear(1, 20))` for
invalidValue — constructive, covers single-char and multi-char names.

```
forAll { (field: String, invalidValue: String, constraint: String) =>
  val e: ConfigError = ConfigError(field, invalidValue, constraint)
  val shown: String = summon[Show[AdkError]].show(e)
  shown.contains(field) && shown.contains(invalidValue) && shown.contains(constraint)
}
```

### Property: GraphCompilationError preserves error list

**Invariant**: For every `GraphCompilationError`, the `errors` list is
preserved unchanged and `message` reflects its size.

**Generator strategy**: `Gen.list(Gen.const(NodeNotFoundError("node")), Range.linear(0, 5))` — constructive, covers empty and non-empty error lists (uses existing AdkError variant `NodeNotFoundError`).

```
forAll { (errors: List[AdkError]) =>
  val e: GraphCompilationError = GraphCompilationError(errors)
  e.errors == errors && e.message.contains(errors.length.toString)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ConfigError` constructed outside the `AdkError` hierarchy | It must be an `AdkError` variant so callers can match it uniformly | `assertCompiles("val e: AdkError = ConfigError(\"f\", \"v\", \"c\")")` (positive) + type-conformance check |
| `GraphCompilationError` constructed outside the `AdkError` hierarchy | Same | `assertCompiles("val e: AdkError = GraphCompilationError(Nil)")` (positive) + type-conformance check |

## Requirement ↔ Test Cross-Reference

| Requirement | Test Name | Status |
|-------------|-----------|--------|
| ConfigError variant / Invalid input produces ConfigError | IronErrorHierarchySpec: ConfigError carries field, invalidValue, and constraint | ✅ |
| ConfigError variant / ConfigError is an AdkError variant | IronErrorHierarchySpec: ConfigError is recognized as AdkError variant | ✅ |
| ConfigError variant / Valid input produces no ConfigError | IronErrorHierarchySpec: ConfigError message format is stable and parseable | ✅ |
| GraphCompilationError variant / Failed compile produces GraphCompilationError | IronErrorHierarchySpec: GraphCompilationError carries all validation errors | ✅ |
| GraphCompilationError variant / GraphCompilationError is an AdkError variant | IronErrorHierarchySpec: GraphCompilationError is recognized as AdkError variant | ✅ |
| GraphCompilationError variant / Compiled graph produces no GraphCompilationError | IronErrorHierarchySpec: GraphCompilationError with empty errors list has valid message | ✅ |
| Existing matches / Match with catch-all continues to compile | IronErrorHierarchySpec: ConfigError and GraphCompilationError handled by catch-all | ✅ |
| Existing matches / Match without catch-all fails compilation | Ring 0 (sbt compile exhaustiveness escalation) | ✅ |
| Property: ConfigError round-trips through AdkError Show | IronErrorHierarchySpec: Property ConfigError round-trips through AdkError Show | ✅ |
| Property: GraphCompilationError preserves error list | IronErrorHierarchySpec: Property GraphCompilationError preserves error list and message reflects size | ✅ |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| ConfigError carries field/value/constraint | Requirement: ConfigError variant for refinement-boundary failures + Scenario: Invalid input produces ConfigError with field and constraint | property test (Show round-trip) + scenario test | `ErrorHierarchySpec` (adk4s-core/test) |
| GraphCompilationError carries error list | Requirement: GraphCompilationError variant for graph-compile failures + Scenario: Failed compile produces GraphCompilationError with all errors | property test (list preservation) + scenario test | `ErrorHierarchySpec` (adk4s-core/test) |
| New variants are AdkError subtypes | Requirement: ConfigError variant for refinement-boundary failures + Requirement: GraphCompilationError variant for graph-compile failures | type system + compile-negative test | `ErrorHierarchyTypeContract` (adk4s-core/test) |
| Existing matches handle new variants | Requirement: Existing AdkError pattern matches handle new variants + Scenario: Match without catch-all fails compilation until updated | Ring 0 exhaustiveness escalation (`-Wconf:name=PatternMatchExhaustivity:e`) | `sbt adk4s-core/compile` + `sbt adk4s-orchestration/compile` |
| ConfigError message contains all fields | Property: ConfigError round-trips through AdkError Show | Hedgehog property | `ErrorHierarchySpec` |
| GraphCompilationError preserves error list | Property: GraphCompilationError preserves error list | Hedgehog property | `ErrorHierarchySpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `AdkError` | sealed trait | `org.adk4s.core.error` (adk4s-core) | gains `ConfigError`, `GraphCompilationError` variants |
| `NodeKeyError` | case class (AdkError) | `org.adk4s.core.error` | retained; `ConfigError` is the general form for identifier failures, `NodeKeyError` may delegate or be kept for backward compat |
| `AdkError` (validation errors) | sealed trait | `org.adk4s.core.error` | referenced by `GraphCompilationError.errors` |
| `sbt adk4s-core/compile` | build step | adk4s-core | exhaustiveness escalation catches unhandled matches |
