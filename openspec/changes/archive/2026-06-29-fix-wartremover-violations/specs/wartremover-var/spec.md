# Spec: WartRemover Var Re-enablement

<!-- Delta spec. Re-enables Wart.Var by replacing mutable `var` state with
     immutable recursive / fold-based / Ref[IO, _] approaches. The primary
     site is JsonFixMiddleware's `applyHeuristicFixes` and
     `replaceSingleQuotes` (imperative parsing loops with `var`). -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SchemaAlignedParser` | object (lenient JSON recovery) | `org.adk4s.structured.sap` |
| `ToolMiddleware` | Kleisli-based middleware | `org.adk4s.core.tools` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | Pure refactor of `var` state into recursion / fold; no new public types. Internal helpers may be added as private functions. |

## ADDED Requirements

### Requirement: No `var` in compiled main sources

The system SHALL contain zero `var` declarations in any main source compiled by a module whose `wartremoverErrors` includes `Wart.Var`.

**Given** a main source file in `adk4s-core`, `adk4s-orchestration`, `structured-llm`, or `structured-llm-test-models`
**When** WartRemover runs with `Wart.Var` enabled
**Then** no `Var` error is reported and the build compiles

**Rationale**: `var` breaks immutability and referential transparency (`AGENTS.md`: "Do NOT use mutable variables"). `JsonFixMiddleware.replaceSingleQuotes` is the canonical offender — an imperative `while` loop with `var i` and `var inDoubleQuote`.

#### Scenario: `replaceSingleQuotes` rewritten as recursion

**Given** `JsonFixMiddleware.replaceSingleQuotes` uses `var i`, `var inDoubleQuote`, and a `StringBuilder` mutated in a `while` loop
**When** the refactor rewrites it as a tail-recursive helper `loop(i: Int, inDoubleQuote: Boolean, acc: List[Char]): String` (or a fold over the character sequence)
**Then** for every input string the output is byte-identical to the pre-refactor output, and no `var` remains

#### Scenario: `applyHeuristicFixes` rewritten without `var result`

**Given** `applyHeuristicFixes` uses `var result: String = s` and reassigns it across several fix steps
**When** the refactor rewrites it as a `foldLeft` over the fix steps (each step: `String => String`)
**Then** the final string equals the pre-refactor result for every input, and no `var` remains

#### Scenario: Edge case — empty string input

**Given** `replaceSingleQuotes("")` and `applyHeuristicFixes("")`
**When** the refactored (recursive/fold) implementations run
**Then** they return `""` (same as pre-refactor), with no `var` and no exception

### Requirement: Test-source `var` replaced with `Ref[IO, _]` or val recursion

The system SHALL contain zero `var` declarations in test sources of wart-enabled modules, replacing mutable test fixtures with `cats.effect.Ref[IO, _]` or val-based recursion.

**Given** a test uses `var calls: Int = 0` to count invocations (e.g. `RetrieverSpec`, `ComponentMockLLMClient`)
**When** the refactor replaces it with `Ref[IO, Int]` updated via `.update`
**Then** the test's assertion on the count still holds, and no `var` remains

**Rationale**: Test sources are also compiled with WartRemover in this build (`wartremoverErrors` is `ThisBuild`-scoped, not main-only).

#### Scenario: Mock LLM client call-count via Ref

**Given** `ComponentMockLLMClient` uses `var callCount` to track calls
**When** the refactor uses `Ref[IO, Int]`
**Then** concurrent and sequential call assertions still pass

### Requirement: Re-enable `Wart.Var` in build.sbt

The build SHALL remove `Wart.Var` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `Var` as temporarily excluded (`JsonFixMiddleware.scala`)
**When** all `var` sites are refactored
**Then** `Wart.Var` is removed from both `filterNot` blocks and the comment line is deleted

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.Var` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: `replaceSingleQuotes` output is identical pre- and post-refactor

**Invariant**: For all strings `s`, `refactoredReplaceSingleQuotes(s) == originalReplaceSingleQuotes(s)` (captured as a reference oracle).

**Generator strategy**: `Gen.string(Gen.frequency1('"' -> 5, '\'' -> 5, '\\' -> 2, Gen.alphaNum -> 3), Range.linear(0, 50))` — constructive, covers quote chars, escapes, and empty. Label `hasSingleQuote` / `hasEscape` / `empty`.

```
forAll { (s: String) =>
  refactoredReplaceSingleQuotes(s) === referenceReplaceSingleQuotes(s)
}
```

### Property: `applyHeuristicFixes` is a pure fold (no mutation observable)

**Invariant**: For all strings `s`, applying `applyHeuristicFixes` twice yields the same result as applying it once (idempotence of the fix pipeline on already-fixed input), proving no hidden mutable state leaks.

**Generator strategy**: `Gen.string(Gen.oneOf(List('{', '}', ',', '"', '\'', 'a', ' ')), Range.linear(0, 40))` — constructive JSON-ish fragments. Label `balanced` / `unbalanced` / `hasSingleQuote`.

```
forAll { (s: String) =>
  val once: String  = applyHeuristicFixes(s)
  val twice: String = applyHeuristicFixes(once)
  once === twice
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `var x: T = ...` in a wart-enabled module | `Var` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `var` in main sources | Requirement 1 | static rule (WartRemover `Wart.Var`) | build.sbt |
| No `var` in test sources | Requirement 2 | static rule (WartRemover `Wart.Var`) | build.sbt |
| `replaceSingleQuotes` byte-identical | Property 1 / Scenario 1 | Hedgehog property (reference oracle) | VarSpec |
| `applyHeuristicFixes` idempotent | Property 2 | Hedgehog property | VarSpec |
| Empty string returns empty | Scenario 3 | scenario test | VarSpec |
| Mock call-count via Ref preserves assertions | Scenario 4 | scenario test (existing `RetrieverSpec` / `ComponentMockLLMClient` tests) | existing tests |
| `Wart.Var` removed from exclusion list | Requirement 3 | build metadata review + `sbt compile` | build.sbt |
