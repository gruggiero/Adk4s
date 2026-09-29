# Spec: Hermetic Test Processes

A test result must not depend on who runs the test. Today it does: a test that starts a
workflow tool as a subprocess passes the subprocess whatever environment the invoking
shell has. When that shell belongs to a harness session, the harness's session variable
reaches the tool and outranks the session the test meant to supply. The completion-tier
parity property fails in every run inside a harness session and passes outside one.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Gate** action rests on the acceptance oracle and on parity properties that run the predecessor as a subprocess. This spec makes those measurements independent of the invoking environment. The protocol's actions and state are unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `SessionId` | opaque type (`fromRaw`, `encoded`) | `org.sinemenda.probatio.core` |
| `GateStateDir` | final case class | `org.sinemenda.probatio.cli` |
| `DifferentialHarness` | object | `org.sinemenda.probatio.migration` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `HermeticEnv` | final case class, private constructor | The environment a test gives a spawned tool: a fixed base (the search path, the home directory, the temporary directory) plus exactly the variables the test declares. Constructible only through the shared helper. |
| `ControlledVariable` | enum | The closed set of variables that change a workflow tool's behaviour — the harness session variables and the workflow's own control variables. A `HermeticEnv` contains one of these only when the test declares it. |

### Type-Widening Impact

No existing public type is widened. Both new types are test infrastructure, closed, and
matched only in code written by this change.

## ADDED Requirements

### Requirement: A spawned tool sees only the variables its test declares

Every test that starts a workflow tool as a separate process SHALL give it a hermetic
environment, and a controlled variable MUST NOT reach the tool unless the test declares
it.

**Given** a test that starts a workflow tool as a process
**When** the invoking shell carries a harness session variable the test does not declare
**Then** the tool does not see that variable

**Given** a test that declares a session for the tool
**When** the tool starts
**Then** it sees exactly that session

#### Scenario: Happy path — a declared session reaches the tool

**Given** a test declaring the session `parity-session` for a spawned gate
**When** the gate starts
**Then** the gate's session is `parity-session`

#### Scenario: Adversarial — an inherited harness session does not reach the tool

**Given** an invoking shell carrying a harness session variable, and a test that declares
a different session
**When** the spawned gate resolves its session
**Then** it resolves the declared session, not the inherited one

#### Scenario: Adversarial — an undeclared workflow control variable does not reach the tool

**Given** an invoking shell that sets the workflow's hook-control variable to disable the
hooks
**When** a test spawns the gate without declaring that variable
**Then** the gate runs its tiers, rather than honouring the inherited switch

### Requirement: The parity properties agree whoever runs them

Every property that compares the ported tool with the predecessor SHALL produce the same
verdict whether or not the invoking shell carries harness session variables.

**Given** a parity property and a fixed seed
**When** it runs once in a shell with harness session variables set and once without
**Then** both runs yield the same verdict

**Rationale**: on 2026-09-25 the completion-tier parity property failed 3 of 3 runs inside
a harness session and passed with the variable unset. Reproduced by hand on the same
fixture: without the variable, both gates refuse identically. The test handed the port its
session by flag and the predecessor by a variable that the predecessor ranks below the
inherited harness variable.

#### Scenario: Happy path — the completion-tier parity property passes in both environments

**Given** the completion-tier parity property
**When** it runs with and without a harness session variable in the invoking shell
**Then** it passes both times

#### Scenario: Adversarial — both sides receive the session through the same channel

**Given** a parity property that runs the port and the predecessor on one fixture
**When** it prepares the two processes
**Then** both receive the session through the hermetic environment, and neither through a
channel the other lacks

### Requirement: The acceptance suites clear controlled variables in one place

The acceptance and implementation-shape suites SHALL clear every controlled variable in
their shared setup, and no suite file MAY depend on an inherited controlled variable.

**Given** any suite file
**When** its tests run in a shell carrying controlled variables
**Then** they observe none of them unless a test sets one

#### Scenario: Happy path — every suite file passes in a harness session and outside one

**Given** the full acceptance suite
**When** it runs once with harness session variables set and once without
**Then** each file's failure count is identical

#### Scenario: Adversarial — a suite file that needs an inherited variable is reported

**Given** a test whose result changes when an inherited controlled variable is removed
**When** the two-environment comparison runs
**Then** that test is named as environment-dependent

### Requirement: Process construction in test code goes through the shared helper

Test sources SHALL construct processes only through the shared helper, and a raw process
construction in test code MUST be rejected by the lint.

**Given** the test sources of the workflow modules
**When** the lint runs
**Then** every process construction uses the shared helper

#### Scenario: Happy path — the lint is clean on the helper's callers

**Given** test sources that start tools only through the shared helper
**When** the lint runs
**Then** it reports nothing

#### Scenario: Adversarial — a raw process construction in a test is rejected

**Given** a test that constructs a process directly
**When** the lint runs
**Then** it reports that construction with its file and line

### Requirement: A missed coverage minimum fails the property

A property that declares a minimum frequency for a labelled case SHALL fail when a run
produces that case less often than declared, and it MUST NOT pass on falsification-freedom
alone.

**Given** a property with a declared coverage minimum
**When** a run produces the labelled case below that minimum
**Then** the property fails naming the label and both frequencies

**Rationale**: the completion-tier property's failing run reported its core case at 3%
against a declared 20%. Whether such a shortfall fails a *passing* run has not been
established, and must be.

#### Scenario: Happy path — a met minimum does not fail the property

**Given** a property whose labelled case is generated above its declared minimum
**When** it runs
**Then** coverage does not fail it

#### Scenario: Adversarial — a missed minimum fails an otherwise passing property

**Given** a property with no counterexample whose labelled case is generated below its
declared minimum
**When** it runs
**Then** it fails naming the label

## Properties (Ring 3)

### Property: result-is-independent-of-the-invoking-environment

**Invariant**: for every process-spawning test and every combination of controlled
variables in the invoking shell, the test's outcome is the same as with none of them set.

**Generator strategy**: `genInvokingEnvironment` — constructive over subsets of the closed
set of controlled variables, each given a value from a small alphabet that differs from any
value the test declares. Edge cases: the empty subset, the full set, and the harness
session variable alone. Crossed with the fixed list of process-spawning suites; the suites
themselves are the enumerated domain, and that finite limit is stated.

```
property("result is independent of the invoking environment") {
  for {
    env   <- genInvokingEnvironment.forAll
    suite <- Gen.element(processSpawningSuites).forAll
  } yield Result.assert(runSuite(suite, env) == runSuite(suite, Map.empty))
}
```

### Property: hermetic-env-contains-only-declared-controls

**Invariant**: for every declared variable set and every invoking environment, the built
hermetic environment contains a controlled variable if and only if the test declared it.

**Generator strategy**: `genDeclared` × `genInvokingEnvironment` — both constructive over
subsets of the controlled set, drawn independently, so the "declared and inherited",
"inherited only" and "declared only" cases all arise by construction.

```
property("only declared controls survive") {
  for {
    declared  <- genDeclared.forAll
    inherited <- genInvokingEnvironment.forAll
    env        = HermeticEnv.build(declared, inherited)
  } yield Result.assert(
    ControlledVariable.values.forall(v => env.has(v) == declared.contains(v))
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A hermetic environment built outside the shared helper | A hand-built environment can copy the inherited one wholesale | `assertDoesNotCompile("new HermeticEnv(sys.env)")` — the constructor is private |
| A hermetic environment built from the inherited environment | Inheriting wholesale is the defect | `assertDoesNotCompile("HermeticEnv.inherit()")` — no such factory exists |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: environment filtering is set membership over a closed
enumeration, fully pinned by the type and `hermetic-env-contains-only-declared-controls`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A declared session reaches the tool | Requirement: A spawned tool sees only the variables its test declares + Scenario: Happy path — a declared session reaches the tool | scenario test | `SessionIdSpec` |
| An inherited harness session does not reach the tool | Requirement: A spawned tool sees only the variables its test declares + Scenario: Adversarial — an inherited harness session does not reach the tool | scenario test | `GateEventSpec` |
| An undeclared control variable does not reach the tool | Requirement: A spawned tool sees only the variables its test declares + Scenario: Adversarial — an undeclared workflow control variable does not reach the tool | scenario test | `GateEventSpec` |
| Only declared controls survive | Property: hermetic-env-contains-only-declared-controls | Hedgehog property | `SessionIdSpec` |
| A hermetic environment cannot be built outside the helper | Compile-Negative: A hermetic environment built outside the shared helper | compile-negative test | `CompileNegative` |
| A hermetic environment cannot inherit wholesale | Compile-Negative: A hermetic environment built from the inherited environment | compile-negative test | `CompileNegative` |
| The completion-tier parity property passes in both environments | Requirement: The parity properties agree whoever runs them + Scenario: Happy path — the completion-tier parity property passes in both environments | property run in two environments, recorded in the evidence ledger | `GateBannerCompatSpec` |
| Both sides receive the session through the same channel | Requirement: The parity properties agree whoever runs them + Scenario: Adversarial — both sides receive the session through the same channel | scenario test | `GateBannerCompatSpec` |
| Every suite file passes identically in both environments | Requirement: The acceptance suites clear controlled variables in one place + Scenario: Happy path — every suite file passes in a harness session and outside one | two-environment suite run, recorded in the evidence ledger | `hook-tiers.bats` |
| An environment-dependent test is named | Requirement: The acceptance suites clear controlled variables in one place + Scenario: Adversarial — a suite file that needs an inherited variable is reported | scenario test | `DifferentialHarnessSpec` |
| Results are independent of the invoking environment | Property: result-is-independent-of-the-invoking-environment | Hedgehog property over the enumerated suite list | `DifferentialHarnessSpec` |
| The lint is clean on the helper's callers | Requirement: Process construction in test code goes through the shared helper + Scenario: Happy path — the lint is clean on the helper's callers | static rule (scalafix) | `.scalafix-tests.conf` (`.scalafix-tests-2.12.conf` for the plugin) wired via `Test / scalafixConfig` |
| A raw process construction is rejected | Requirement: Process construction in test code goes through the shared helper + Scenario: Adversarial — a raw process construction in a test is rejected | static rule (scalafix) with a negative fixture | `workflow/verify-test-lint.sh` plants a violation per banned shape |
| A met coverage minimum does not fail | Requirement: A missed coverage minimum fails the property + Scenario: Happy path — a met minimum does not fail the property | scenario test | `OutcomeSpec` |
| A missed coverage minimum fails a passing property | Requirement: A missed coverage minimum fails the property + Scenario: Adversarial — a missed minimum fails an otherwise passing property | scenario test with a deliberately under-covering generator | `OutcomeSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The defect site | munit suite | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/GateBannerCompatSpec.scala:506–560` | The port gets `--session parity-session`; the predecessor subprocess gets `VERIFIED_SCALA3_SESSION_ID` and inherits `CLAUDE_CODE_SESSION_ID` |
| Predecessor session precedence | predecessor script | `openspec/schemas/verified-scala3/hooks/gate.sh.predecessor.bak:196–197` | Ranks `CLAUDE_CODE_SESSION_ID` above `VERIFIED_SCALA3_SESSION_ID` |
| Process-spawning test files | munit suites | 19 files under `workflow/*/src/test/scala/**` (measured 2026-09-25) | Only `LiveFactBannerSpec` handles the harness session variable |
| Bats shared setup | bats helper | `openspec/schemas/verified-scala3/tests/helpers.bash` | 6 of 17 files unset the harness variable individually; this moves it to one place |
| The lint | scalafix `DisableSyntax` block | `.scalafix-tests.conf`, `.scalafix-tests-2.12.conf` | Scalafix has no `fileFilter` field — the dedicated test confs are wired through `Test / scalafixConfig` on the three workflow projects, so the bans apply to test sources only |
| `HermeticEnv`, `ControlledVariable` | new types | shared test-support source | New |
| Hedgehog cover behaviour | test framework | Hedgehog 0.13.1 | Whether a missed cover minimum fails a passing run is established by the adversarial scenario above, not assumed |
| Ring 5 note | — | `stryker4s.conf` | Test infrastructure only; the move-to-main-and-back procedure applies |
