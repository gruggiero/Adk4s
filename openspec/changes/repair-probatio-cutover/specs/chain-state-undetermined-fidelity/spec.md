# Spec: Chain-State Undetermined Fidelity

The correctness verdict must not report a measurement it did not take. Today, when the
mechanical pre-pass cannot run, the verdict tool returns a finding carrying a full
bound/resolved/discharged count — numbers derived from an invocation that never happened.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The ported verdict tool sits at a swapped seam; this spec restores the predecessor's three-way outcome at that seam. No protocol action changes. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `ChainState` | object (`compute`) | `org.sinemenda.probatio.core` |
| `ChainStateReport` | final case class | `org.sinemenda.probatio.core` |
| `ChainStateUndetermined` | final case class | `org.sinemenda.probatio.core` |
| `ChainState.Requirement` | final case class | `org.sinemenda.probatio.core` |
| `RequirementVerdict` | final case class | `org.sinemenda.probatio.core` |
| `UnresolvedReason` | enum | `org.sinemenda.probatio.core` |
| `LintReport` | final class, private constructor | `org.sinemenda.probatio.core` |
| `ChainStateKernel` | Stainless object | `org.sinemenda.probatio.verified` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `PrePassOutcome` | enum (`Completed(report)`, `DidNotRun(reason)`) | What the mechanical pre-pass produced for one change. `DidNotRun` carries the stated reason and **no counts**, so a measurement cannot be read out of a pre-pass that did not complete. |

`ChainStateReport` is **modified**: its constructor becomes reachable only from a
completed pre-pass, so a report cannot be assembled alongside a `DidNotRun`.

### Type-Widening Impact

No public type is widened to a richer alias. `PrePassOutcome` is a new closed type; every
match over it is written in this change. `ChainStateReport`'s constructor narrows rather
than widens — existing call sites that build a report from a non-completed pre-pass stop
compiling, which is the intended forcing function and is enumerated in Implementation
Anchors.

## ADDED Requirements

### Requirement: A verdict is produced only from a completed pre-pass

The correctness verdict SHALL be computed only from a mechanical pre-pass that ran to
completion, and a pre-pass that did not run MUST NOT yield bound, resolved or discharged
counts.

**Given** a change whose mechanical pre-pass is invoked
**When** the pre-pass completes with a result
**Then** the verdict is computed from that result and carries the three counts

**When** the pre-pass does not complete — it is absent, it cannot be executed, or it exits
outside its declared outcome range
**Then** the outcome is could-not-determine carrying the stated reason, and no counts are
emitted

**Rationale**: the predecessor returns could-not-determine here. The port returns a
finding with a report asserting counts it derived from nothing, which is a claim
outrunning its evidence inside the tool whose purpose is to detect exactly that.

#### Scenario: Happy path — a completed pre-pass yields a verdict with counts

**Given** a change whose pre-pass completes and reports one bound, resolved and discharged
requirement
**When** the verdict is computed
**Then** the outcome is ran-and-found-nothing, and the report carries total 1, bound 1,
resolved 1, discharged 1

#### Scenario: Error path — a pre-pass that cannot be executed is could-not-determine

**Given** a change whose pre-pass cannot be executed at all
**When** the verdict is computed
**Then** the outcome is could-not-determine naming the pre-pass as the reason, and no
report is emitted

#### Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts

**Given** a change whose pre-pass terminates with a status outside its declared
outcome range
**When** the verdict is computed
**Then** the outcome is could-not-determine, and the output contains no total, bound,
resolved or discharged figure

#### Scenario: Adversarial — a non-completed pre-pass cannot produce a report

**Given** a caller holding a pre-pass outcome that did not complete
**When** the caller attempts to build a verdict report from it
**Then** compilation fails: the report's constructor is not reachable from that outcome

### Requirement: Every distinct could-not-determine reason is named

Each could-not-determine outcome SHALL carry a reason that names which input could not be
read, and a reason MUST NOT be reported as a generic failure.

**Given** a verdict computation that cannot proceed
**When** the outcome is emitted
**Then** the reason names the specific unreadable input — the pre-pass, the evidence
record, the change directory, or the baseline

#### Scenario: Happy path — an unreadable evidence record names the evidence record

**Given** a change whose evidence record cannot be read
**When** the verdict is computed
**Then** the outcome is could-not-determine naming the evidence record

#### Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown

**Given** a change whose evidence record is present, readable and empty
**When** the verdict is computed
**Then** the outcome is a successful measurement reporting zero discharged, not
could-not-determine

#### Scenario: Adversarial — a reason that names nothing is not emitted

**Given** a verdict computation that cannot proceed for an unclassified cause
**When** the outcome is emitted
**Then** the reason still names the input under inspection rather than a bare failure
marker

### Requirement: The exit status distinguishes could-not-determine from a finding

The tool SHALL exit with its could-not-determine status when no verdict could be computed
and with its finding status only when a verdict was computed and something was found, and
the two MUST NOT be collapsed.

**Given** a verdict computation
**When** the process exits
**Then** a completed verdict finding something exits with the finding status, a completed
verdict finding nothing exits with the clean status, and a computation that produced no
verdict exits with the could-not-determine status

#### Scenario: Happy path — a satisfied change exits clean

**Given** a change whose every requirement is bound, resolved and discharged
**When** the tool runs
**Then** it exits with the clean status and the report's unresolved list is empty

#### Scenario: Adversarial — an uncomputable verdict does not exit with the finding status

**Given** a change whose pre-pass did not complete
**When** the tool runs
**Then** it exits with the could-not-determine status, and not with the finding status

## Properties (Ring 3)

### Property: no-counts-without-a-completed-pre-pass

**Invariant**: for every pre-pass outcome, if the outcome did not complete then the
verdict output contains no count field — total, bound, resolved and discharged are all
absent, not zero.

**Generator strategy**: `genPrePassOutcome` — constructive over the two variants. The
`Completed` arm draws a report from `genLintReport` (constructive over requirement lists
of size 0–8 with varied verdicts); the `DidNotRun` arm draws a reason from the closed set
of named inputs. Edge cases: a completed pre-pass with zero requirements (which must
still emit counts, all zero), and every `DidNotRun` reason.

```
property("no counts without a completed pre-pass") {
  for {
    outcome <- genPrePassOutcome.forAll
    rendered = render(compute(outcome))
  } yield Result.assert(
    outcome.completed || !rendered.hasAnyCountField
  )
}
```

### Property: outcome-status-is-total-and-disjoint

**Invariant**: for every input, the tool's exit status is exactly one of clean, finding,
or could-not-determine; could-not-determine holds if and only if no verdict was computed;
and finding holds only when a verdict was computed and its unresolved list is non-empty.

**Generator strategy**: `genVerdictInput` — constructive over (pre-pass outcome, evidence
record state, baseline state) triples, each drawn from its own closed variant set rather
than filtered. Edge cases: every combination in which exactly one input is unreadable, and
the all-readable case.

```
property("exit status is total and disjoint") {
  for {
    input <- genVerdictInput.forAll
    status = exitStatus(compute(input))
  } yield Result.assert(
    status.isExactlyOneOf(Clean, Finding, Undetermined) &&
    (status == Undetermined) == !computed(input) &&
    ((status == Finding) ==> (computed(input) && unresolved(input).nonEmpty))
  )
}
```

### Property: parity-with-predecessor-on-the-undetermined-boundary

**Invariant**: for every generated change fixture, the ported tool's exit status equals
the predecessor's exit status for the same fixture. The predecessor is executed as the
model.

**Generator strategy**: `genChangeFixture` — constructive over change directories built
from a small closed alphabet: requirement count 0–3, pre-pass behaviour drawn from
{completes clean, completes with findings, cannot execute, exits outside range}, evidence
record drawn from {absent, empty, populated, unreadable}. Model-based: the predecessor
script is run as a subprocess on the same fixture. Determinism: the pre-pass behaviours
are recorded stub outcomes, not wall-clock dependent; no sleeps.

```
property("undetermined boundary agrees with the predecessor") {
  for {
    fixture <- genChangeFixture.forAll
    ported   = runPorted(fixture)
    model    = runPredecessor(fixture)
  } yield Result.assert(ported.exitStatus == model.exitStatus)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A verdict report built from a pre-pass that did not complete | This is the defect: counts assembled without the invocation that produces them | `assertDoesNotCompile("ChainStateReport.from(PrePassOutcome.DidNotRun(r))")` — the factory accepts only the completed variant |
| A could-not-determine outcome carrying counts | A could-not-determine that carries numbers invites a reader to use them | `assertDoesNotCompile("ChainStateUndetermined(reason, total = 3)")` — the type has no count field |
| A could-not-determine reason built from an empty string | A reason that names nothing fails the requirement that every reason names an input | `assertDoesNotCompile("UndeterminedReason(\"\")")` — the smart constructor returns an Either |

## Formal Contracts (Ring 6)

The bound → resolved → discharged fold is already mirrored. This spec extends the mirror
with the boundary condition that makes a report unconstructible without its pre-pass.

### Contract: computeVerdict

```
def computeVerdict(outcome: PrePassOutcome, evidence: EvidenceState): VerdictResult = {
  require(evidence.isReadable || outcome.didNotRun)
  ...
} ensuring { result =>
  // a non-completed pre-pass never yields counts
  (outcome.didNotRun ==> result.isUndetermined) &&
  (result.isUndetermined ==> !result.hasCounts) &&
  // a completed pre-pass always yields counts, even when there is nothing to count
  (outcome.completed && evidence.isReadable ==> result.hasCounts) &&
  // the counts are monotone: discharged <= resolved <= bound <= total
  (result.hasCounts ==>
    (result.discharged <= result.resolved &&
     result.resolved   <= result.bound &&
     result.bound      <= result.total))
}
```

A bridge property binds the shipped computation to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A completed pre-pass yields a verdict with counts | Requirement: A verdict is produced only from a completed pre-pass + Scenario: Happy path — a completed pre-pass yields a verdict with counts | scenario test | `ChainStateSpec` |
| A pre-pass that cannot be executed is could-not-determine | Requirement: A verdict is produced only from a completed pre-pass + Scenario: Error path — a pre-pass that cannot be executed is could-not-determine | scenario test + bats oracle | `ChainStateSpec`, `chain-state.bats` |
| A pre-pass exiting outside its range emits no counts | Requirement: A verdict is produced only from a completed pre-pass + Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts | scenario test + bats oracle | `ChainStateSpec`, `chain-state.bats` |
| No counts without a completed pre-pass, for all inputs | Property: no-counts-without-a-completed-pre-pass | Hedgehog property | `ChainStateSpec` |
| A report cannot be built from a non-completed pre-pass | Compile-Negative: A verdict report built from a pre-pass that did not complete | compile-negative test | `ChainStateCompileNegative` |
| A could-not-determine outcome cannot carry counts | Compile-Negative: A could-not-determine outcome carrying counts | compile-negative test | `ChainStateCompileNegative` |
| Every could-not-determine reason names an input | Requirement: Every distinct could-not-determine reason is named | smart constructor (reason is non-empty by construction) + scenario test | `ChainStateSpec` |
| An unreadable evidence record names the evidence record | Requirement: Every distinct could-not-determine reason is named + Scenario: Happy path — an unreadable evidence record names the evidence record | scenario test | `ChainStateSpec` |
| An empty readable evidence record is a measurement | Requirement: Every distinct could-not-determine reason is named + Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown | scenario test + bats oracle | `chain-state.bats` |
| A reason naming nothing is unconstructible | Compile-Negative: A could-not-determine reason built from an empty string | compile-negative test | `ChainStateCompileNegative` |
| Exit status is total and disjoint | Requirement: The exit status distinguishes could-not-determine from a finding + Property: outcome-status-is-total-and-disjoint | Hedgehog property | `ChainStateSpec` |
| A satisfied change exits clean | Requirement: The exit status distinguishes could-not-determine from a finding + Scenario: Happy path — a satisfied change exits clean | bats oracle | `chain-state.bats` |
| An uncomputable verdict does not exit with the finding status | Requirement: The exit status distinguishes could-not-determine from a finding + Scenario: Adversarial — an uncomputable verdict does not exit with the finding status | bats oracle | `chain-state.bats` |
| The ported boundary agrees with the predecessor | Property: parity-with-predecessor-on-the-undetermined-boundary | Hedgehog model-based property (predecessor run as a subprocess) | `ChainStateParitySpec` |
| The fold and boundary are formally verified | Invariant: counts are monotone and absent without a completed pre-pass | Stainless verification + bridge property test | `ChainStateKernel` (extended) + `VerifiedKernelBridgeSpec` |
| The suite file reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `chain-state.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ChainState.compute` | object method | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainState.scala` | The fold; gains the pre-pass-outcome parameter |
| `ChainStateReport` | final case class | `.../core/ChainStateReport.scala` | Constructor narrowed to the completed pre-pass path |
| `ChainStateUndetermined` | final case class | `.../core/BlockReason.scala` | Carries the reason; must carry no counts |
| `PrePassOutcome` | new enum | `.../core/` | New |
| `ChainStateCmd` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala:1799` | The adapter that invokes the pre-pass and maps its termination to the outcome; the exit-status mapping lives here |
| `ChainStateParitySpec` | munit suite | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateParitySpec.scala` | Existing parity suite; extended with the boundary fixtures |
| `chain-state.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Tests 11 and 23 are the regression; 6 further failures are pre-existing in both arms |
| `ChainStateKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/ChainStateKernel.scala` | Extended with the boundary contract |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
