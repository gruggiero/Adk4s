# Spec: WartRemover OptionPartial Re-enablement

<!-- Delta spec. Re-enables Wart.OptionPartial by replacing every `.get` call
     on `Option` in compiled (main + test) sources with a total operation
     (`.getOrElse`, `.fold`, or pattern matching). -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `NodeKey` | opaque type | `org.adk4s.core.types` |
| `AdkError` | sealed trait | `org.adk4s.core.error` |
| `CheckpointStore` | service trait | `org.adk4s.orchestration.interrupt` |
| `StateRef[F, S]` | service trait | `org.adk4s.orchestration.state` |
| `Tool` / `InvokableTool` / `StreamableTool` | service trait tier | `org.adk4s.core.component` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none) | — | This spec introduces no new types; it is a pure refactor of `.get` call sites. |

## ADDED Requirements

### Requirement: No `.get` on Option in compiled sources

The system SHALL contain zero calls to `Option#get` in any main or test source compiled by a module whose `wartremoverErrors` includes `Wart.OptionPartial`.

**Given** a source file in `adk4s-core`, `adk4s-orchestration`, `structured-llm`, `structured-llm-test-models`, or `adk4s-examples` (main or test)
**When** WartRemover runs with `Wart.OptionPartial` enabled
**Then** no `OptionPartial` error is reported and the build compiles

**Rationale**: `Option#get` throws `NoSuchElementException` on `None`, violating the project's no-throw / total-function conventions (`AGENTS.md`).

#### Scenario: `.get` on a known-present Option replaced by pattern match

**Given** `Tool.scala` calls `params.obj.get("properties")` and `fieldSchema.obj.get("type")`
**When** the refactor replaces each with a `match` against `Some(v)` / `None` with an explicit fallback
**Then** the produced `ujson.Value` is identical to the pre-refactor output for the same input, and WartRemover reports no `OptionPartial`

#### Scenario: `.get` on a possibly-absent Option replaced by `.getOrElse`

**Given** `CheckpointStore.scala` calls `store.get(id).get` on a `Map[String, Checkpoint]`
**When** the refactor replaces `.get(id).get` with `.getOrElse(id, throw ...)` → returns `Either[CheckpointNotFoundError, Checkpoint]`
**Then** a missing checkpoint yields `Left(CheckpointNotFoundError(id))` instead of throwing, and existing tests expecting a missing checkpoint to fail still fail with the same error variant

#### Scenario: Test-source `.get` on assertions replaced by `.getOrElse` with munit `fail`

**Given** a test calls `result.toOption.get` to unwrap an assertion
**When** the refactor replaces `.get` with `.getOrElse(fail("expected Some, got None"))`
**Then** the test still fails on `None` with a munit `FailException`, and passes on `Some`

#### Scenario: Edge case — `.get` on a `Map` (not `Option`) is NOT touched

**Given** a call `myMap.get(key)` where `myMap` is a `Map` (returns `Option`, not `Option#get`)
**When** WartRemover runs
**Then** no `OptionPartial` is reported (WartRemover flags `Option#get`, not `Map#get`)

### Requirement: Re-enable `Wart.OptionPartial` in build.sbt

The build SHALL remove `Wart.OptionPartial` from the `filterNot` exclusion list in both the `ThisBuild / wartremoverErrors` block and the `adk4s-examples` override.

**Given** the `build.sbt` exclusion comment block documents `OptionPartial` as temporarily excluded
**When** all `.get` call sites are refactored
**Then** `Wart.OptionPartial` is removed from both `filterNot` blocks and the corresponding comment line is deleted

**Rationale**: Re-enabling the wart prevents regressions — any new `.get` will fail the build.

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.OptionPartial` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run across all modules
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: Refactored call sites are total (no throw on None)

**Invariant**: For every refactored site that previously called `.get` on an `Option[A]`, the replacement produces a defined value for all `Option` inputs (no `NoSuchElementException`).

**Generator strategy**: `Gen.option(Gen.string(Gen.alphaNum, Range.linear(0, 10)))` — constructive, covers `None` and `Some`. Label `None` vs `Some`.

```
forAll { (opt: Option[String]) =>
  val result: Either[String, String] = refactoredLookup(opt)
  result.isLeft || result.isRight  // total — never throws
}
```

### Property: Behavior preservation — `Some` inputs yield the same value as `.get`

**Invariant**: For all `Some(x)`, the refactored accessor returns the same value `x` that `.get` would have returned.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(1, 20)).map(Some(_))` — constructive `Some`-only generator. Label by string length bucket.

```
forAll { (s: String) =>
  val some: Option[String] = Some(s)
  refactoredAccessor(some) === Right(s)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `opt.get` where `opt: Option[?]` in a wart-enabled module | `OptionPartial` violation | `compileFail` test asserting `opt.get` fails WartRemover (or a Scalafix `noAsInstanceOf`-style check) — verified via `sbt compile` failing if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `.get` on Option in compiled sources | Requirement 1 | static rule (WartRemover `Wart.OptionPartial`) | build.sbt, `sbt compile` |
| Refactored sites total on None | Property 1 | Hedgehog property | OptionPartialSpec |
| `Some` inputs preserve value | Property 2 | Hedgehog property | OptionPartialSpec |
| Missing checkpoint returns `Left(CheckpointNotFoundError)` | Scenario 2 | scenario test (existing `CheckpointStoreTest` unchanged behavior) | CheckpointStoreTest |
| `Wart.OptionPartial` removed from exclusion list | Requirement 2 | build metadata review + `sbt compile` lint-clean | build.sbt |
| Map#get not flagged | Scenario 4 | scenario test / manual review | OptionPartialSpec |
