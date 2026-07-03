# Spec: Constraint Validation

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `SchemaData[A]` | case class | `org.adk4s.structured.core` |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `StructuredLLMError` | sealed trait | `org.adk4s.structured.core` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `Guardrail[A]` (llm4s) | trait | `org.llm4s.agent.guardrails` |
| `OutputGuardrail` (llm4s) | trait | `org.llm4s.agent.guardrails` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Constraint[A]` | case class | Label + level + predicate for @check/@assert |
| `ConstraintLevel` | enum | Check (non-failing) vs Assert (strict, raises error) |
| `ValidationResult[A]` | case class | Value + check results + failed asserts |
| `ResponseCheck` | case class | Name + expression + status (succeeded/failed) |
| `CheckStatus` | enum | Succeeded / Failed |
| `ValidationFailed` | case class | New StructuredLLMError variant for assert failures |

## ADDED Requirements

### Requirement: Schema-attached constraints

The system SHALL allow attaching `Constraint[A]` validators to `Schema[A]` instances via `withCheck` and `withAssert` extension methods, preserving the original schema's Smithy IDL and decoder.

**Given** a `Schema[A]` and a predicate `A => Boolean`
**When** `schema.withCheck("label")(predicate)` is called
**Then** the result is a new `Schema[A]` with the constraint attached, and `schema.withAssert("label")(predicate)` attaches a strict constraint

**Rationale**: Constraints must be attached to schemas so they are automatically evaluated after parsing, without requiring the caller to remember to validate.

#### Scenario: Attach check constraint

**Given** `Schema[Student]` and predicate `_.age > 5`
**When** `schema.withCheck("old_enough")(_.age > 5)`
**Then** the resulting schema has the constraint in its metadata

#### Scenario: Attach assert constraint

**Given** `Schema[Student]` and predicate `_.name.nonEmpty`
**When** `schema.withAssert("nonempty_name")(_.name.nonEmpty)`
**Then** the resulting schema has the assert constraint in its metadata

### Requirement: Post-parse constraint evaluation

The system SHALL evaluate all constraints after successful parsing, collecting `@check` results as warnings and raising `ValidationFailed` for failed `@assert` constraints.

**Given** a parsed value `A` with constraints attached to its `Schema[A]`
**When** constraints are evaluated
**Then** `@check` failures are collected into `ValidationResult.warnings` and `@assert` failures raise `StructuredLLMError.ValidationFailed`

**Rationale**: `@check` is non-failing (for inspection), `@assert` is strict (raises error). This mirrors BAML's constraint levels.

#### Scenario: All checks pass

**Given** a parsed `Student(age=20)` with check `_.age > 5` and assert `_.name.nonEmpty`
**When** constraints are evaluated
**Then** `ValidationResult` has all checks with `Succeeded` status and no failed asserts

#### Scenario: Check fails — non-failing

**Given** a parsed `Student(age=3)` with check `_.age > 5`
**When** constraints are evaluated
**Then** the check result has `Failed` status but the value is still returned

#### Scenario: Assert fails — error

**Given** a parsed `Student(name="")` with assert `_.name.nonEmpty`
**When** constraints are evaluated
**Then** `StructuredLLMError.ValidationFailed` is raised with the assert label

### Requirement: completeValidated API method

The system SHALL provide a `completeValidated[A: Schema]` method on `StructuredLLM[F]` that returns `F[ValidationResult[A]]` including both the parsed value and all constraint check results.

**Given** a `StructuredLLM[F]` instance and a `Prompt` with a schema that has constraints
**When** `completeValidated(prompt)` is called
**Then** the result is `F[ValidationResult[A]]` containing the value, check results, and any failed asserts

#### Scenario: Validated call with passing constraints

**Given** a mock LLM returning valid JSON and a schema with passing constraints
**When** `llm.completeValidated(prompt)`
**Then** the result is `ValidationResult(value, checks=allSucceeded, failedAsserts=empty)`

#### Scenario: Validated call with failing check

**Given** a mock LLM returning valid JSON and a schema with a failing `@check`
**When** `llm.completeValidated(prompt)`
**Then** the result is `ValidationResult(value, checks=oneFailed, failedAsserts=empty)`

### Requirement: Bridge to llm4s Guardrail

The system SHALL provide a conversion from `Constraint[String]` to `llm4s.OutputGuardrail`, enabling reuse of llm4s's guardrail composition framework.

**Given** a `Constraint[String]` with level `Assert`
**When** converted to `OutputGuardrail`
**Then** the resulting guardrail's `validate` method applies the constraint predicate and returns `Result[String]`

**Rationale**: llm4s already has a guardrail framework with composition (`CompositeGuardrail.all/any/sequence`). Bridging to it avoids reinventing validation composition.

## Properties (Ring 3)

### Property: Check constraints never fail the parse

**Invariant**: For any value `a: A` and any `@check` constraint, evaluating the constraint never changes the returned value — it only affects the check results list.

**Generator strategy**: `Gen` of `A` via smithy4s-derived generators, `Gen` of predicates `A => Boolean`, classify by predicate-passes vs predicate-fails.

```
forAll { (a: A, predicate: A => Boolean) =>
  val result = evaluateChecks(a, Vector(Constraint("test", Check, predicate)))
  result.value == a
}
```

### Property: Assert constraints fail the parse when predicate is false

**Invariant**: For any value `a: A` and any `@assert` constraint where `predicate(a) == false`, evaluation raises `ValidationFailed`.

**Generator strategy**: `Gen` of `A`, `Gen` of always-false predicates, classify by value-type.

```
forAll { (a: A) =>
  val result = evaluateAsserts(a, Vector(Constraint("test", Assert, _ => false)))
  result.isLeft && result.left.get.isInstanceOf[ValidationFailed]
}
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Constraints attached to schema | Requirement 1 | Scenario test | ConstraintSpec |
| Check non-failing | Requirement 2 | Hedgehog property | ConstraintSpec |
| Assert failing | Requirement 2 | Hedgehog property | ConstraintSpec |
| completeValidated returns ValidationResult | Requirement 3 | Scenario test | ConstraintSpec |
| Guardrail bridge produces equivalent validation | Requirement 4 | Scenario test | ConstraintSpec |
| ValidationFailed is StructuredLLMError variant | type definition | type system (sealed trait) | ConstraintSpec |
