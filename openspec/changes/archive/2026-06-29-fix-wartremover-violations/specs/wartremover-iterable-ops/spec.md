# Spec: WartRemover IterableOps Re-enablement

<!-- Delta spec. Re-enables Wart.IterableOps by replacing `.init` and `.last`
     (which throw on empty collections) with total operations
     (`dropRight(1)`, `.lastOption`, pattern matching). -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `RunPath` | opaque type (`List[RunStep]`) | `org.adk4s.core.interrupt` |
| `FieldPath` | opaque type (`Vector[String]`) | `org.adk4s.core.types` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | Pure refactor of `.init` / `.last` call sites; no new types. |

## ADDED Requirements

### Requirement: No `.init` or `.last` on collections in compiled sources

The system SHALL contain zero calls to `IterableOps#init` or `IterableOps#last` in any source compiled by a module whose `wartremoverErrors` includes `Wart.IterableOps`.

**Given** a source file in a wart-enabled module
**When** WartRemover runs with `Wart.IterableOps` enabled
**Then** no `IterableOps` error is reported and the build compiles

**Rationale**: `.init` and `.last` throw on empty collections, violating totality. `.lastOption` and `dropRight(1)` are total (the latter returns an empty collection for an empty input).

#### Scenario: `.last` on a non-empty path replaced by `.lastOption` with explicit fallback

**Given** code computes `path.last` to get the terminal segment of a `RunPath` / `FieldPath`
**When** the refactor replaces it with `path.lastOption.getOrElse(defaultSegment)` or a `match` on `lastOption`
**Then** an empty path yields the defined fallback instead of throwing, and a non-empty path yields the same segment as before

#### Scenario: `.init` on a non-empty collection replaced by `dropRight(1)`

**Given** code computes `xs.init` to drop the last element
**When** the refactor replaces it with `xs.dropRight(1)`
**Then** `dropRight(1)` returns the same elements as `.init` for non-empty `xs`, and returns `Nil`/empty for empty `xs` (where `.init` would throw)

#### Scenario: Edge case — empty collection

**Given** an empty `List` / `Vector`
**When** the refactored accessor runs
**Then** it returns the defined fallback (no `UnsupportedOperationException`)

### Requirement: Re-enable `Wart.IterableOps` in build.sbt

The build SHALL remove `Wart.IterableOps` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `IterableOps` as temporarily excluded
**When** all `.init` / `.last` call sites are refactored
**Then** `Wart.IterableOps` is removed from both `filterNot` blocks and the comment line is deleted

**Rationale**: Prevents regressions where new `.last` / `.init` calls reintroduce throw-on-empty.

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.IterableOps` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: `dropRight(1)` agrees with `.init` on non-empty collections

**Invariant**: For all non-empty lists `xs`, `xs.dropRight(1) == xs.init`.

**Generator strategy**: `Gen.list(Gen.int(Range.linear(-100, 100)), Range.linear(1, 20))` — constructive non-empty lists. Label by length bucket (1, 2-5, 6-20).

```
forAll { (xs: List[Int]) =>
  xs.nonEmpty ==> xs.dropRight(1) === xs.init
}
```

### Property: Refactored `.last` accessor is total on empty collections

**Invariant**: For all lists (including empty), the refactored terminal-segment accessor returns a defined result (an `Option` or a fallback value), never throws.

**Generator strategy**: `Gen.list(Gen.int(Range.linear(0, 100)), Range.linear(0, 20))` — constructive, covers empty. Label `empty` vs `nonEmpty`.

```
forAll { (xs: List[Int]) =>
  val result: Option[Int] = refactoredLast(xs)
  result.isDefined == xs.nonEmpty  // total, no throw
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `xs.last` / `xs.init` on an `IterableOps` in a wart-enabled module | `IterableOps` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `.init` / `.last` in compiled sources | Requirement 1 | static rule (WartRemover `Wart.IterableOps`) | build.sbt |
| `dropRight(1) == init` on non-empty | Property 1 | Hedgehog property | IterableOpsSpec |
| Refactored accessor total on empty | Property 2 | Hedgehog property | IterableOpsSpec |
| Empty path yields fallback not throw | Scenario 3 | scenario test | IterableOpsSpec |
| `Wart.IterableOps` removed from exclusion list | Requirement 2 | build metadata review + `sbt compile` | build.sbt |
