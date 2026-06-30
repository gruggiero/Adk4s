# Spec: WartRemover Throw Re-enablement

<!-- Delta spec. Re-enables Wart.Throw by removing the `throw` keyword from
     compiled main sources, replacing it with `Either[AdkError, A]` returns
     (pure code) or `IO.raiseError(AdkError(...))` (effectful code that already
     runs in F[_]). Note: `Wart.Throw` flags the `throw` *keyword*; `IO.raiseError`
     is not flagged, but raw `new Exception(...)` inside it should be replaced
     with `AdkError` variants per the project's sealed-error convention. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `AdkError` | sealed trait (21 variants) | `org.adk4s.core.error` |
| `NodeKey` | opaque type (`unsafeApply` throws) | `org.adk4s.core.types` |
| `AgentInterruptedException` | case class extends `AdkError` | `org.adk4s.core.error` |
| `WIOGraphError` | sealed trait | `org.adk4s.orchestration.wiograph` |
| `GraphExecutor` | object (graph execution) | `org.adk4s.orchestration.execution` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `NodeKeyError` | case class extends `AdkError` | Returned by `NodeKey.from` (renamed/added total constructor) when input is empty or reserved, replacing `unsafeApply`'s `throw new IllegalArgumentException`. Field: `invalidKey: String`. Exact name finalized in design.md. |

### Enum/GADT Extension Behavior

`NodeKeyError` is a new variant of the sealed `AdkError` hierarchy. Existing
pattern matches over `AdkError` must be audited:
- The existing `given Show[AdkError] = Show.show(_.message)` is variant-agnostic
  (uses `.message`), so it handles `NodeKeyError` without modification.
- Any exhaustive `match` over `AdkError` subtypes (none found in main sources
  at scan time — `AdkError` is consumed via `.message` and `cats.Show`, not
  pattern-matched exhaustively) would gain a new branch. If such a match
  exists, the implementation MUST add a `NodeKeyError` branch; the compiler
  will flag any missing branch as a non-exhaustive warning (Scala 3 sealed
  exhaustiveness). This is recorded as a compile-time proof obligation.

## ADDED Requirements

### Requirement: No `throw` keyword in compiled main sources

The system SHALL contain zero uses of the `throw` keyword in any main source compiled by a module whose `wartremoverErrors` includes `Wart.Throw`.

**Given** a main source file in `adk4s-core`, `adk4s-orchestration`, `structured-llm`, or `structured-llm-test-models`
**When** WartRemover runs with `Wart.Throw` enabled
**Then** no `Throw` error is reported and the build compiles

**Rationale**: `throw` breaks totality and the project's sealed-error convention (`AGENTS.md`: "No side effects outside F[_] / IO — no ... throw"). Errors must be values (`AdkError`).

#### Scenario: `NodeKey.unsafeApply` replaced by total `NodeKey.from`

**Given** `NodeKey.unsafeApply(key)` does `apply(key).getOrElse(throw new IllegalArgumentException(...))`
**When** the refactor adds `NodeKey.from(key): Either[NodeKeyError, NodeKey]` and keeps `unsafeApply` only as a deprecated bridge that calls `from(key).fold(e => throw e.toException, identity)` — OR removes `unsafeApply` and updates call sites to handle `Either`
**Then** no `throw` keyword remains in `NodeKey.scala` main source; call sites that previously used `unsafeApply` now use `from` and handle the `Left` explicitly

#### Scenario: `GraphExecutor` validation errors via `IO.raiseError(AdkError)`

**Given** `GraphExecutor.execute` does `IO.raiseError(new Exception("Graph validation failed: ..."))`
**When** the refactor replaces `new Exception(...)` with an `AdkError` variant (e.g. `GenericError(...)` or a new `GraphValidationError` reusing existing `EdgeValidationError`/`NodeNotFoundError`)
**Then** no `throw` keyword remains; the error surfaced to callers is an `AdkError` (sealed), and existing tests catching the failure still fail with a message containing "Graph validation failed"

#### Scenario: `Tool.invokable` handler error via `IO.raiseError(ToolExecutionError)`

**Given** `Tool.invokable`'s `run` does `throw org.adk4s.core.error.ToolExecutionError(name, new Exception(err))` inside `F.delay`
**When** the refactor replaces `throw ...` with `F.raiseError(ToolExecutionError(name, new Exception(err)))`
**Then** no `throw` keyword remains; the `F[ujson.Value]` still fails with `ToolExecutionError`, and `ToolsNodeTest` assertions on tool failure still pass

#### Scenario: Edge case — interrupt control flow preserved

**Given** `AgentInterruptedException` is currently thrown across the tool/agent boundary and caught by `AgentRunner` / `ToolsNode`
**When** the refactor replaces `throw AgentInterruptedException(signal)` with `F.raiseError(AgentInterruptedException(signal))` (it already extends `AdkError`)
**Then** `AgentRunner.run` still catches it (via `.attempt` / `HandleError`), saves a checkpoint, and returns `RunResult.Interrupted`; `ToolsNodeInterruptTest` and `AgentRunnerTest` pass unchanged

### Requirement: Test-source `throw` replaced with munit `fail` / `intercept`

The system SHALL contain zero `throw` keywords in test sources of wart-enabled modules, replacing them with munit `fail(...)` (for assertion failures) or `intercept[...]` (for expected-exception tests).

**Given** a test does `throw new RuntimeException("expected")` to simulate a failing tool
**When** the refactor uses `IO.raiseError(...)` (effectful test) or `intercept[RuntimeException] { ... }` (expected-throw test)
**Then** the test still passes and no `throw` keyword remains

#### Scenario: Effectful test failure simulated via `IO.raiseError`

**Given** `StreamOpsTest` does `throw new RuntimeException(...)` inside an `IO` to simulate a stream failure
**When** the refactor replaces it with `IO.raiseError(new RuntimeException(...))` (no `throw` keyword)
**Then** the test's assertion on the failed stream still passes, and WartRemover reports no `Throw` for the test source

#### Scenario: Expected-exception test via `intercept`

**Given** a test asserts that a code path throws `IllegalArgumentException`
**When** the refactor wraps the call in `intercept[IllegalArgumentException] { ... }` and removes any `throw` the test itself performed
**Then** the test still passes when the path raises the exception, and fails (munit `FailException`) when it does not

### Requirement: Re-enable `Wart.Throw` in build.sbt

The build SHALL remove `Wart.Throw` from the `filterNot` exclusion list in both wartremover blocks.

**Given** the `build.sbt` comment documents `Throw` as temporarily excluded (`Tool.scala`)
**When** all `throw` sites are refactored
**Then** `Wart.Throw` is removed from both `filterNot` blocks and the comment line is deleted

#### Scenario: Build is lint-clean after re-enablement

**Given** `Wart.Throw` is removed from the exclusion list
**When** `sbt compile` and `sbt Test/compile` run
**Then** both succeed with zero WartRemover errors

## Properties (Ring 3)

### Property: `NodeKey.from` is total and rejects empty/reserved keys

**Invariant**: For all strings `s`, `NodeKey.from(s)` returns `Left(NodeKeyError(s))` when `s` is empty or reserved (`__start__`, `__end__`), and `Right(NodeKey)` otherwise. It never throws.

**Generator strategy**: `Gen.string(Gen.alphaNum, Range.linear(0, 10))` plus explicit `Gen.element(List("", "__start__", "__end__"))` for the reserved/empty edge cases. Label `empty` / `reserved` / `accepted` (non-empty, non-reserved).

```
forAll { (s: String) =>
  val result: Either[NodeKeyError, NodeKey] = NodeKey.from(s)
  (s.isEmpty || s == "__start__" || s == "__end__") ==> result.isLeft
  (!s.isEmpty && s != "__start__" && s != "__end__") ==> result.isRight
}
```

### Property: Refactored error paths surface the same `AdkError` variant the tests expect

**Invariant**: For every refactored site that previously threw, the `F`/`Either` failure carries an `AdkError` whose `message` contains the same distinguishing substring the existing tests assert on.

**Generator strategy**: `Gen.element(List("Graph validation failed", "Node key cannot be empty", "Tool execution failed"))` — constructive over the known error substrings. Label by error kind.

```
forAll { (expectedSubstring: String) =>
  val error: AdkError = refactoredErrorPath(triggerFor(expectedSubstring))
  error.message.contains(expectedSubstring)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `throw new X` in a wart-enabled module main/test source | `Throw` violation | `sbt compile` fails if reintroduced |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| No `throw` keyword in main sources | Requirement 1 | static rule (WartRemover `Wart.Throw`) | build.sbt |
| No `throw` keyword in test sources | Requirement 2 | static rule (WartRemover `Wart.Throw`) | build.sbt |
| `NodeKey.from` total, rejects empty/reserved | Property 1 / Scenario 1 | Hedgehog property | ThrowSpec |
| Error messages preserve distinguishing substring | Property 2 | Hedgehog property | ThrowSpec |
| Interrupt control flow preserved | Scenario 4 | scenario test (existing `ToolsNodeInterruptTest`, `AgentRunnerTest` unchanged) | existing tests |
| `Tool.invokable` failure still `ToolExecutionError` | Scenario 3 | scenario test (existing `ToolsNodeTest`) | existing tests |
| `Wart.Throw` removed from exclusion list | Requirement 3 | build metadata review + `sbt compile` | build.sbt |
| `NodeKeyError` is the only new concept | Concepts Introduced | concept delta check at apply Step 12 | concept-inventory.md |
| Exhaustive `AdkError` matches handle `NodeKeyError` | Enum/GADT Extension | Scala 3 sealed exhaustiveness (compiler warning) + grep audit | ThrowSpec, compile |
