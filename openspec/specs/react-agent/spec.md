# Spec: react-agent (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Refines maxSteps to
     Int :| Positive at the internal boundary of ReactAgent/AgentRunner/
     HarnessAgent. Public API parameters stay Int (conservative strategy). -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [ReactAgent](../../../../concepts/react-agent.md) | maxSteps refined internally to Positive | `openspec/concepts/react-agent.md` |
| [AgentRunner](../../../../concepts/agent-runner.md) | maxSteps refined internally to Positive | `openspec/concepts/agent-runner.md` |

Updating the `react-agent` and `agent-runner` concept files' `maxSteps`
descriptions is PART of implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ReactAgent` | trait | `org.adk4s.orchestration.agent` |
| `AgentRunner` | class | `org.adk4s.orchestration.agent` |
| `HarnessAgent` | class | `org.adk4s.orchestration.agent` |
| `Positive` | type alias (Int :| numeric.Positive) | `org.adk4s.core.types` (introduced by core-types spec) |
| `ConfigError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| (none — reuses `Positive` from core-types spec) | — | `maxSteps` is refined internally using the shared `Positive` alias. |

## ADDED Requirements

### Requirement: maxSteps is refined to Positive at the internal boundary

The system SHALL refine the `maxSteps` parameter to `Int :| Positive` at the
internal boundary of `ReactAgent`, `AgentRunner`, and `HarnessAgent`, so that
a non-positive step limit cannot drive the ReAct loop. The public API
parameters remain `Int` for source compatibility; refinement happens
internally via `refineEither`, returning `Either[ConfigError, …]`.

**Given** a `maxSteps` value passed to a public `generate`/`run`/`stream`
method
**When** the value is zero or negative
**Then** the internal refinement returns `Left(ConfigError("maxSteps", …,
"Positive"))` and the agent does not enter the ReAct loop

**Rationale**: Today `maxSteps: Int = 10` accepts any `Int` including `0`
(loop never executes) or negatives (undefined behavior). The conservative
strategy keeps the public signature stable while rejecting invalid values
internally.

#### Scenario: Positive maxSteps enters the loop

**Given** `maxSteps = 5`
**When** `agent.generate(messages, 5)` is called
**Then** the internal refinement succeeds and the ReAct loop runs with up to
5 steps

#### Scenario: Zero maxSteps is rejected

**Given** `maxSteps = 0`
**When** `agent.generate(messages, 0)` is called
**Then** the result is an `IO` that fails with `ConfigError("maxSteps", "0",
"Positive")` (or returns `Left` where the API is `Either`-returning)

#### Scenario: Negative maxSteps is rejected

**Given** `maxSteps = -3`
**When** `agent.generate(messages, -3)` is called
**Then** the result is an `IO` that fails with `ConfigError`

#### Scenario: Default maxSteps 10 remains valid

**Given** `AgentRunner.run(messages)` with the default `maxSteps = 10`
**When** the runner is invoked
**Then** the internal refinement succeeds and the loop runs with up to 10
steps

### Requirement: maxSteps refinement does not alter valid-input behavior

The system SHALL preserve the existing behavior of the ReAct loop for all
positive `maxSteps` values; the refinement is a pure boundary check that
rejects invalid inputs without changing the loop's iteration count, tool
execution, or event emission for valid inputs.

**Given** a positive `maxSteps` value
**When** the agent runs before and after this change
**Then** the sequence of `AgentEvent`s, the final `RunResult`/`HarnessResult`,
and the number of LLM calls are identical

**Rationale**: The change is a type-safety hardening, not a behavioral change;
regression tests must pin this.

#### Scenario: Existing ReactAgent tests pass unchanged

**Given** the existing `ReactAgentTest` suite
**When** the tests are run after migration
**Then** all tests pass (no behavioral regression for valid `maxSteps`)

## Properties (Ring 3)

### Property: maxSteps refinement preserves valid-input behavior

**Invariant**: For every positive `maxSteps`, the agent's observable behavior
(events emitted, final result, LLM call count) is identical before and after
the refinement boundary.

**Generator strategy**: `Gen.int(Range.linear(1, 20))` — constructive, covers small and typical step counts. Uses `DeterministicChatModel` (existing test double) for deterministic LLM responses.

```
forAll { (maxSteps: Int) =>
  // run agent with DeterministicChatModel, compare event trace + result
  // before/after refinement boundary
  eventsAfter == eventsBefore && resultAfter == resultBefore
}
```

### Property: maxSteps rejects zero and negatives

**Invariant**: For `maxSteps <= 0`, the agent produces a `ConfigError` and
does not emit any `IterationCompleted` events.

**Generator strategy**: `Gen.int(Range.linear(-10, 0))` — constructive, covers zero and negatives.

```
forAll { (maxSteps: Int) =>
  val result: Either[ConfigError, _] = refineMaxSteps(maxSteps)
  result.isLeft && noIterationEventsEmitted(maxSteps)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| (none — public API stays `Int`; the constraint is enforced at the internal boundary, not the type level) | — | runtime property tests cover rejection |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Zero/negative maxSteps rejected | Requirement: maxSteps is refined to Positive at the internal boundary + Scenario: Zero maxSteps is rejected + Scenario: Negative maxSteps is rejected | smart constructor (`refineEither`) + property test | `ReactAgentSpec` (adk4s-orchestration/test) |
| Default 10 remains valid | Requirement: maxSteps is refined to Positive at the internal boundary + Scenario: Default maxSteps 10 remains valid | scenario test | `ReactAgentSpec` |
| Valid-input behavior preserved | Requirement: maxSteps refinement does not alter valid-input behavior + Scenario: Existing ReactAgent tests pass unchanged + Property: maxSteps refinement preserves valid-input behavior | regression test (existing suite) + Hedgehog property | `ReactAgentTest` (existing, must still pass) |
| Valid-input behavior identical | Property: maxSteps refinement preserves valid-input behavior | Hedgehog property with `DeterministicChatModel` | `ReactAgentSpec` |
| Zero/negatives rejected | Property: maxSteps rejects zero and negatives | Hedgehog property | `ReactAgentSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ReactAgent.generate` / `stream` | methods | `org.adk4s.orchestration.agent` (adk4s-orchestration) | public param stays `Int`; internal `refineEither[Positive]` |
| `AgentRunner.run` | method | `org.adk4s.orchestration.agent` (adk4s-orchestration) | same |
| `HarnessAgent.generate` / `resume` / `stream` | methods | `org.adk4s.orchestration.agent` (adk4s-orchestration) | same; `effectiveMaxSteps` computation refines |
| `Positive` | type alias | `org.adk4s.core.types` (adk4s-core) | reused from core-types spec |
| `DeterministicChatModel` | test double | `org.adk4s.harness.testkit` (adk4s-harness-testkit) | used in regression property |
| `sbt adk4s-orchestration/test` | build step | adk4s-orchestration | existing `ReactAgentTest` must still pass |
