# Spec: WartRemover Any / StringPlusAny Re-enablement

<!-- Delta spec. Re-enables Wart.Any and Wart.StringPlusAny by replacing
     `s"..."` interpolations that widen interpolated values to `Any` (a known
     Scala 3 WartRemover false-positive pattern) with `+` string concatenation
     per the accordant4s pattern. Done last so earlier specs stabilize the
     build. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AdkError` | sealed trait (uses `s"..."` in `message`) | `org.adk4s.core.error` |
| `InterruptSignal` | sealed trait (`signal.info` interpolated) | `org.adk4s.core.interrupt` |
| `AgentEvent` | sealed trait | `org.adk4s.core.interrupt` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | Pure refactor of `s"..."` → `+` concatenation at flagged sites; no new types. |

## ADDED Requirements

### Requirement: No `Wart.Any` / `Wart.StringPlusAny` violations in compiled sources

The system SHALL contain zero `s"..."` interpolations (and other expressions) that trigger `Wart.Any` or `Wart.StringPlusAny` in any source compiled by a module whose `wartremoverErrors` includes those warts.

**Given** a source file in a wart-enabled module
**When** WartRemover runs with `Wart.Any` and `Wart.StringPlusAny` enabled
**Then** no `Any` or `StringPlusAny` error is reported and the build compiles

**Rationale**: `s"..."` interpolation of non-`String` values widens to `Any` in Scala 3's WartRemover analysis (a known false-positive on the interpolator). The project convention (`AGENTS.md`: "NEVER use the 'Any' type") is enforced by switching flagged interpolations to `+` concatenation, which keeps values typed as `String`.

#### Scenario: `AdkError.message` interpolations replaced by `+` concatenation

**Given** `AdkError` variants use `s"Node '$nodeKey' not found in graph"`, `s"Tool '$toolName' execution failed: ${cause.getMessage}"`, etc.
**When** the refactor replaces each with `"Node '" + nodeKey + "' not found in graph"` and `"Tool '" + toolName + "' execution failed: " + cause.getMessage`
**Then** the produced `message` string is identical to the pre-refactor output for the same inputs, and WartRemover reports no `Any` / `StringPlusAny`

#### Scenario: `InterruptSignal.info` interpolation replaced

**Given** `InterruptSignal` variants interpolate `signal.info` and `signal.address.map(_.name).mkString(" > ")`
**When** the refactor replaces `s"..."` with `+` concatenation
**Then** the `info` string is identical, and no `Any` / `StringPlusAny` is reported

#### Scenario: Edge case — interpolation of a pure `String` is NOT flagged

**Given** an `s"..."` where every interpolated expression is already a `String` (e.g. `s"prefix $str"` with `str: String`)
**When** WartRemover runs
**Then** it is not flagged (WartRemover only flags widening to `Any`); such sites may remain as `s"..."` OR be converted to `+` for consistency — the requirement is only that no violation is reported

### Requirement: Re-enable `Wart.Any` and `Wart.StringPlusAny` in build.sbt

The build SHALL remove `Wart.Any` and `Wart.StringPlusAny` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `Any` (s"..." false positive) and `StringPlusAny` as temporarily excluded
**When** all flagged interpolations are refactored
**Then** both warts are removed from both `filterNot` blocks and the comment lines are deleted

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.Any` and `Wart.StringPlusAny` are removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: Refactored `message` strings are identical to the `s"..."` originals

**Invariant**: For every refactored `AdkError` variant, `refactoredMessage(args) == originalMessage(args)` (reference oracle captured before refactor).

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 20))` for `nodeKey` / `toolName`; `Gen.string(Gen.ascii, Range.linear(0, 50))` for `cause.getMessage`. Label by error variant.

```
forAll { (nodeKey: String) =>
  refactoredNodeNotFoundMessage(nodeKey) === ("Node '" + nodeKey + "' not found in graph")
}
```

### Property: No interpolated expression widens to `Any` after refactor

**Invariant**: For every string-building expression in a wart-enabled module, the inferred type of each concatenated piece is `String` (not `Any`), so `Wart.Any` / `Wart.StringPlusAny` cannot fire.

**Generator strategy**: Constructive over the list of refactored string-building expressions (by source location). Label by file.

```
forAll { (expr: RefactoredStringExpr) =>
  staticTypeIsString(expr)  // verified by WartRemover passing + compile
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `s"...$nonString..."` widening to `Any` in a wart-enabled module | `Any` / `StringPlusAny` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `Any` / `StringPlusAny` violations | Requirement 1 | static rule (WartRemover `Wart.Any`, `Wart.StringPlusAny`) | build.sbt |
| `message` strings identical to originals | Property 1 / Scenario 1 | Hedgehog property (reference oracle) | AnyStringPlusAnySpec |
| No interpolated piece widens to `Any` | Property 2 | static rule + compile | build.sbt |
| `Wart.Any` / `Wart.StringPlusAny` removed from exclusion list | Requirement 2 | build metadata review + `sbt compile` | build.sbt |
