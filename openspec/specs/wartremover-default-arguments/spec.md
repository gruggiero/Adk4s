# Spec: WartRemover DefaultArguments Re-enablement

<!-- Delta spec. Re-enables Wart.DefaultArguments by removing default argument
     values from case classes / methods in compiled sources, replacing them
     with explicit companion factory methods (preserving the old defaults) or
     explicit call-site arguments. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AdkToolInfo` | case class | `org.adk4s.core.component` |
| `ToolWrapper` | case class | `org.adk4s.core.tools` |
| `RunResult` | sealed trait | `org.adk4s.orchestration.agent` |
| `Runnable[I, O]` | trait | `org.adk4s.core.runnable` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | No new types. Companion factory methods are added to existing objects; they are not new domain concepts. |

## ADDED Requirements

### Requirement: No default arguments in compiled sources

The system SHALL contain zero method/case-class parameters with default values (`= ...`) in any source compiled by a module whose `wartremoverErrors` includes `Wart.DefaultArguments`.

**Given** a source file in a wart-enabled module
**When** WartRemover runs with `Wart.DefaultArguments` enabled
**Then** no `DefaultArguments` error is reported and the build compiles

**Rationale**: Default arguments generate synthetic `$default$` methods and hidden overloads, complicating the API surface and binary compatibility. The project prefers explicit companion factories (`AGENTS.md`: explicit, no hidden behavior).

#### Scenario: Case-class default replaced by companion factory

**Given** a case class `Foo(a: String, b: Int = 0)` is used with `Foo("x")` relying on the default
**When** the refactor changes it to `case class Foo(a: String, b: Int)` and adds `object Foo { def apply(a: String): Foo = Foo(a, 0) }`
**Then** `Foo("x")` still produces `Foo("x", 0)`, and WartRemover reports no `DefaultArguments`

#### Scenario: Method default replaced by explicit argument / overload

**Given** a method `def run(input: I, maxSteps: Int = 10): IO[O]` is called with `run(in)` relying on the default
**When** the refactor adds an explicit overload `def run(input: I): IO[O] = run(input, 10)` and removes the default
**Then** `run(in)` still uses `maxSteps = 10`, and no `DefaultArguments` is reported

#### Scenario: Edge case — example call sites across 55 example files

**Given** examples rely on defaulted constructors/methods
**When** the refactor adds companion factories / overloads preserving the old defaults
**Then** `sbt adk4s-examples/compile` succeeds without editing every example (factories preserve call-site compatibility)

### Requirement: Re-enable `Wart.DefaultArguments` in build.sbt

The build SHALL remove `Wart.DefaultArguments` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `DefaultArguments` as temporarily excluded
**When** all default-argument sites are refactored
**Then** `Wart.DefaultArguments` is removed from both `filterNot` blocks and the comment line is deleted

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.DefaultArguments` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run across all modules (including examples)
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: Companion factory preserves the old default value

**Invariant**: For every refactored case class `C` with a former default `b = d`, the companion `C.apply(a)` produces `C(a, d)` — the same value the default would have supplied.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 10))` for the explicit arg; the default `d` is a fixed constant per case class. Label by case class name.

```
forAll { (a: String) =>
  Foo(a) === Foo(a, 0)  // 0 was the former default
}
```

### Property: No synthetic `$default$` accessor remains

**Invariant**: After refactor, no compiled class in a wart-enabled module exposes a `<method>$default$<n>` accessor (the compiler-generated default-argument carrier), proving defaults are gone.

**Generator strategy**: Reflective scan over compiled module JARs / class names — a constructive `Gen` over the list of wart-enabled module names. Label by module.

```
forAll { (module: ModuleName) =>
  hasNoDefaultAccessor(module)  // reflection / scalap scan
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `def f(x: T = v)` / `case class C(x: T = v)` in a wart-enabled module | `DefaultArguments` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No default arguments in compiled sources | Requirement 1 | static rule (WartRemover `Wart.DefaultArguments`) | build.sbt |
| Companion factory preserves old default | Property 1 / Scenario 1 | Hedgehog property | DefaultArgumentsSpec |
| No `$default$` accessor remains | Property 2 | reflective scan property | DefaultArgumentsSpec |
| Examples compile without per-file edits | Scenario 3 | `sbt adk4s-examples/compile` | build |
| `Wart.DefaultArguments` removed from exclusion list | Requirement 2 | build metadata review + `sbt compile` | build.sbt |
