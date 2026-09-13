# Spec: Cutover Gate

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler Migration Protocol | This spec supplies the gate the protocol names but whose green predicate was unsatisfiable, and adds the abort path as an executable decision rather than a described one | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |
| Conformance Property-Test Contract | The green predicate is this concept's; this spec changes it from "zero failures" to "no worse than the predecessor" | [conformance-property-test-contract.md](../../../../concepts/conformance-property-test-contract.md) |

**Both concept files are updated as part of implementing this spec.** The
Conformance Property-Test Contract's operational principle currently reads "the
oracle is green when `failed == 0`". Measured 2026-08-29, the predecessor itself
fails 20 of 282 tests, so that predicate is unsatisfiable by any implementation
including the one it is meant to certify — which is why the gate was bypassed
rather than met. The predicate becomes a comparison against the predecessor
control. The Strangler Migration Protocol's Abort action gains its executable form.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `OracleGreenGate` | object (test-only) | `org.sinemenda.probatio.migration` |
| `OracleGreenCheck` | object (test-only) | `org.sinemenda.probatio.migration` |
| `Stage` | enum (test-only) | `org.sinemenda.probatio.migration` |
| `ToolId` | enum (test-only) | `org.sinemenda.probatio.migration` |
| `SeamConfiguration` | final case class (test-only) | `org.sinemenda.probatio.migration` |
| `MigrationState` | final case class (test-only) | `org.sinemenda.probatio.migration` |
| `SwapOrder` | enum (test-only) | `org.sinemenda.probatio.migration` |
| `ShimSwap` | final case class (test-only) | `org.sinemenda.probatio.migration` |
| `ShimGenerator` | object | `org.sinemenda.probatio.plugin` |
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `DifferentialResult` | final case class | The per-file comparison of one oracle run under the predecessor seams against one under the ported seams: for each file, its total, its predecessor failures, and its ported failures |
| `CutoverVerdict` | enum (`Proceed`, `Revert`) | The gate's decision. `Revert` carries the `DifferentialResult` that produced it, so a refusal always names its evidence |

## ADDED Requirements

### Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold

The gate SHALL decide by comparing the ported implementation's per-file failure
counts against the predecessor's under the same repository and the same suite, and
SHALL proceed only when no file fails more tests under the ported implementation
than under the predecessor.

**Given** a seam configuration and a repository
**When** the gate decides
**Then** the decision is derived from a per-file comparison of the two runs

**Rationale**: An absolute zero-failure predicate is unsatisfiable here — the
predecessor fails 20 of 282 tests, a set recorded as pre-existing. A gate whose
condition cannot be met is a gate that gets skipped, which is what happened. A
comparison against the control is both satisfiable and strictly stronger than
"no new failures overall", because it forbids trading a regression in one file for
an improvement in another.

#### Scenario: Happy path — no file is worse and the gate proceeds

**Given** a comparison in which every file's ported failure count is at most its
predecessor failure count
**When** the gate decides
**Then** the decision is to proceed

#### Scenario: Adversarial — one file worse blocks even when the total improves

**Given** a comparison in which one file fails more tests under the ported
implementation while another fails enough fewer that the overall total is lower
**When** the gate decides
**Then** the decision is to revert, naming the file that got worse

#### Scenario: Happy path — a file that improves does not block

**Given** a comparison in which one file fails fewer tests under the ported
implementation and no file fails more
**When** the gate decides
**Then** the decision is to proceed

#### Scenario: Error path — a run that did not complete is not a comparison

**Given** a comparison in which either run did not produce a result for every file
in the suite
**When** the gate decides
**Then** the decision is to revert, naming that the comparison is incomplete — the
absent files are not treated as zero failures

#### Scenario: Adversarial — a suite whose file set differs between the runs is incomplete

**Given** two runs whose file sets differ
**When** the gate decides
**Then** the decision is to revert, naming the differing file set

### Requirement: Both runs of the comparison execute in the same repository under the same suite

The comparison SHALL run both implementations against the same repository and the
same unmodified suite, differing only in which implementation each seam resolves to.

**Given** a comparison
**When** it runs
**Then** both runs use the same repository, the same suite files, and the same
suite file set, and differ only in seam resolution

**Rationale**: Measured 2026-08-29, running the suite outside a repository makes
three files fail entirely for both implementations, masking real differences: the
predecessor scored 90 failures outside a repository and 20 inside it, on the same
commit. A comparison whose two arms differ in anything but the seams measures the
difference in that other thing.

#### Scenario: Adversarial — a comparison whose arms differ in repository is refused

**Given** a comparison whose two runs were made against different repositories
**When** the gate decides
**Then** the decision is to revert, naming that the runs are not comparable

#### Scenario: Adversarial — a comparison against a modified suite is refused

**Given** a comparison in which either run used a suite file whose content differs
from the repository's
**When** the gate decides
**Then** the decision is to revert, naming the modified suite file

#### Scenario: Happy path — a comparison with identical repository and suite is accepted

**Given** a comparison whose two runs used the same repository and byte-identical
suite files
**When** the gate decides
**Then** the comparison is accepted and the decision is derived from the counts

### Requirement: A seam resolves to exactly one implementation

At every point in the migration, each seam SHALL resolve to exactly one
implementation, and a configuration in which a seam resolves to both or to neither
SHALL NOT be constructible.

**Given** a seam configuration
**When** a seam's implementation is resolved
**Then** exactly one implementation is returned

**Rationale**: A seam resolving to both makes the comparison meaningless; a seam
resolving to neither makes the tool unavailable while the configuration claims it
is migrated.

#### Scenario: Adversarial — a configuration listing a seam as both ported and predecessor is unconstructible

**Given** an attempt to build a configuration in which one seam appears in both the
ported and the predecessor sets
**When** the configuration is built
**Then** the construction is rejected

#### Scenario: Happy path — every seam resolves

**Given** a configuration built through its smart constructor
**When** each seam is resolved
**Then** every seam yields exactly one implementation

### Requirement: A refused cutover restores the predecessor at every seam it had swapped

When the gate decides to revert, the seams it had swapped SHALL be restored to
their predecessor implementations, and the restoration SHALL be verified by
re-running the comparison.

**Given** a decision to revert and a set of seams already swapped
**When** the revert executes
**Then** each swapped seam resolves to its predecessor implementation, and a
re-run of the comparison shows the ported arm equal to the predecessor arm

**Rationale**: The predecessor implementations are retained on disk precisely so a
refusal has somewhere to go. A revert that is described but not executed leaves the
workflow in the state this change exists to repair. The re-run is what turns "we
reverted" from a claim into a fact.

#### Scenario: Happy path — a revert restores every swapped seam

**Given** three swapped seams and a decision to revert
**When** the revert executes
**Then** all three resolve to their predecessor implementations

#### Scenario: Adversarial — a revert that leaves any seam swapped is a failure

**Given** a revert in which one seam could not be restored
**When** the revert completes
**Then** the result reports failure naming that seam, and does not report the
revert as complete

#### Scenario: Error path — a missing predecessor implementation is could-not-determine

**Given** a seam whose predecessor implementation is not present on disk
**When** a revert of that seam is attempted
**Then** the result is could-not-determine naming the missing implementation

### Requirement: The gate's decision and its evidence are recorded before the swap proceeds

Every gate decision SHALL be recorded with the comparison that produced it, before
the swap or revert it authorises is executed.

**Given** a gate decision
**When** it is made
**Then** the decision and the per-file comparison are recorded, and the recording
precedes the action

**Rationale**: The archived porting change recorded that the gate was run and green
at every stage. The measurement in this change's proposal shows it was not. The
difference is that the recorded claim carried no per-file evidence, so nothing could
contradict it.

#### Scenario: Happy path — a proceed decision records its comparison

**Given** a decision to proceed
**When** it is recorded
**Then** the record carries the per-file counts for both arms

#### Scenario: Adversarial — a decision with no recorded comparison is not actionable

**Given** a decision presented without its comparison
**When** the swap is attempted
**Then** the swap does not proceed, and the reason names the missing comparison

#### Scenario: Edge case — a revert decision records its comparison too

**Given** a decision to revert
**When** it is recorded
**Then** the record carries the per-file counts and names every file that got worse

## Properties (Ring 3)

### Property: proceed-iff-no-file-worse

**Invariant**: The gate proceeds if and only if no file's ported failure count
exceeds its predecessor failure count, given a complete comparison.

**Generator strategy**: `genDifferentialResult` — constructive: a file set of size
1–20, each file with a generated total (1–40), a generated predecessor failure
count in `[0, total]`, and a generated ported failure count in `[0, total]` chosen
independently. No filtering. Hedgehog `cover`: `no-file-worse` ≥ 30%,
`exactly-one-worse` ≥ 25%, `worse-but-total-improves` ≥ 15%,
`all-files-equal` ≥ 10%.

```
forAll { (d: DifferentialResult) =>
  d.isComplete ==>
    ((gate.decide(d) == CutoverVerdict.Proceed) ==
      d.files.forall(f => f.portedFailures <= f.predecessorFailures))
}
```

### Property: total-improvement-does-not-excuse-a-regression

**Invariant**: A comparison in which any file is worse yields a revert, regardless
of the overall totals.

**Generator strategy**: `genRegressingDifferential` — constructive: builds the
regression directly by choosing one file and setting its ported count above its
predecessor count, then generating the remaining files so the overall total is
lower. This case is constructed, never filtered for. Hedgehog `cover`:
`total-lower` ≥ 60% (the generator targets it).

```
forAll { (d: RegressingDifferential) =>
  gate.decide(d) match
    case CutoverVerdict.Revert(evidence) => evidence.namesWorseFile
    case CutoverVerdict.Proceed          => false
}
```

### Property: incomplete-comparison-never-proceeds

**Invariant**: A comparison missing a result for any file in the suite never yields
proceed.

**Generator strategy**: `genIncompleteDifferential` — constructive: generates a
complete comparison, then removes a non-empty subset of files from one arm.
Hedgehog `cover`: `missing-from-ported` ≥ 40%, `missing-from-predecessor` ≥ 40%.

```
forAll { (d: IncompleteDifferential) =>
  gate.decide(d) != CutoverVerdict.Proceed
}
```

### Property: seam-resolves-to-exactly-one

**Invariant**: For every constructible configuration and every seam, resolution
yields exactly one implementation.

**Generator strategy**: `genSeamConfiguration` — constructive: chooses, for each
seam independently, ported or predecessor. The both/neither states are not
generated because they are unconstructible; the compile-negative covers them.
Hedgehog `cover`: `all-ported` ≥ 10%, `all-predecessor` ≥ 10%, `mixed` ≥ 60%.

```
forAll { (cfg: SeamConfiguration, seam: ToolId) =>
  cfg.resolve(seam).isDefined && cfg.portedTools.contains(seam) != cfg.predecessorTools.contains(seam)
}
```

### Property: revert-restores-every-swapped-seam

**Invariant**: After a revert, every seam that had been swapped resolves to its
predecessor implementation.

**Generator strategy**: `genSwapHistory` — constructive: a prefix of the swap order
of length 0–6, materialised as real shim files in a temporary tree with their
predecessor files present. Hedgehog `cover`: `empty-prefix` ≥ 10%,
`full-prefix` ≥ 15%, `partial-prefix` ≥ 60%.

```
forAll { (h: SwapHistory) =>
  val after = revert(h)
  h.swappedSeams.forall(s => after.resolve(s) == Implementation.Predecessor)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `SeamConfiguration` in which a seam appears in both the ported and the predecessor sets | A seam resolving to two implementations makes the comparison meaningless | `assertDoesNotCompile` on the raw constructor; construction goes through a smart constructor taking only the ported set |
| `CutoverVerdict.Revert` constructed without a `DifferentialResult` | A refusal must always name its evidence | `assertDoesNotCompile` in `CutoverGateSpec` |
| A gate decision function taking a single run rather than a comparison | An absolute threshold is the unsatisfiable predicate this spec replaces | `assertDoesNotCompile` in `CutoverGateSpec` |
| `CutoverVerdict` pattern match omitting a case | The decision must be handled exhaustively | `assertDoesNotCompile` in `CutoverGateSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. The gate decision is a comparison fold over two count
vectors.

### Contract: cutoverDecision

**Precondition** (`require`): the two count lists have equal length, and every count
is non-negative.

**Postcondition** (`ensuring`): the decision is proceed if and only if every ported
count is at most its paired predecessor count; and when the decision is revert, at
least one index witnesses it.

```scala
def cutoverDecision(predecessor: List[BigInt], ported: List[BigInt]): Boolean = {
  require(predecessor.length == ported.length &&
          predecessor.forall(_ >= 0) && ported.forall(_ >= 0))
  // pure model: true = proceed
}.ensuring { proceed =>
  proceed == predecessor.zip(ported).forall { case (p, q) => q <= p } &&
  (!proceed ==> predecessor.zip(ported).exists { case (p, q) => q > p })
}
```

**Bridge property test**: `CutoverBridgeSpec` runs the shipped decision and the
mirror on the same generated count vectors.

**Delegated to Ring 3**: comparison completeness, seam resolution, and revert
execution are delegated — all three involve filesystem state, which has no
PureScala model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The gate proceeds exactly when no file is worse | Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold + Property: proceed-iff-no-file-worse + Contract: cutoverDecision | property test + formal contract (Ring 6) + bridge test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/CutoverKernel.scala`; `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverBridgeSpec.scala` |
| A per-file regression blocks even when the total improves | Requirement 1 + Scenario: Adversarial — one file worse blocks even when the total improves + Property: total-improvement-does-not-excuse-a-regression | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| An incomplete comparison never proceeds | Requirement 1 + Scenario: Error path — a run that did not complete is not a comparison + Property: incomplete-comparison-never-proceeds | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| A differing file set between the arms is incomplete | Requirement 2 + Scenario: Adversarial — a suite whose file set differs between the runs is incomplete | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| A single-run decision function cannot be written | Requirement 1 + Compile-Negative: A gate decision function taking a single run rather than a comparison | compile-negative test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| Both arms run against the same repository and suite | Requirement: Both runs of the comparison execute in the same repository under the same suite + Scenario: Adversarial — a comparison whose arms differ in repository is refused | scenario tests | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarnessSpec.scala` |
| A modified suite file refuses the comparison | Requirement 2 + Scenario: Adversarial — a comparison against a modified suite is refused | scenario test comparing suite file digests against the repository's | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarnessSpec.scala` |
| A seam resolves to exactly one implementation | Requirement: A seam resolves to exactly one implementation + Property: seam-resolves-to-exactly-one | smart constructor (the configuration is built from the ported set alone, so the predecessor set is derived and the both/neither states are unconstructible) + property test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| A both-implementations configuration is unconstructible | Requirement 3 + Compile-Negative: A SeamConfiguration in which a seam appears in both the ported and the predecessor sets | smart constructor + compile-negative test | `SeamConfiguration` in `workflow/core/src/test/scala/org/sinemenda/probatio/migration/SeamTypes.scala`; `CutoverGateSpec` |
| A revert restores every swapped seam | Requirement: A refused cutover restores the predecessor at every seam it had swapped + Property: revert-restores-every-swapped-seam | property test over materialised shim trees | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverRevertSpec.scala` |
| An incomplete revert reports failure naming the seam | Requirement 4 + Scenario: Adversarial — a revert that leaves any seam swapped is a failure | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverRevertSpec.scala` |
| A missing predecessor implementation is could-not-determine | Requirement 4 + Scenario: Error path — a missing predecessor implementation is could-not-determine | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverRevertSpec.scala` |
| Every decision is recorded with its comparison, before the action | Requirement: The gate's decision and its evidence are recorded before the swap proceeds + Scenario: Happy path — a proceed decision records its comparison | scenario tests | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| A decision without a recorded comparison does not authorise a swap | Requirement 5 + Scenario: Adversarial — a decision with no recorded comparison is not actionable | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| A refusal always names its evidence | Requirement 5 + Compile-Negative: CutoverVerdict.Revert constructed without a DifferentialResult + Compile-Negative: CutoverVerdict pattern match omitting a case | type system (the variant requires the field; exhaustiveness escalated to error) + compile-negative tests | `CutoverVerdict` in `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverVerdict.scala`; `CutoverGateSpec` |
| The revert decision is recorded with its comparison | Requirement 5 + Scenario: Edge case — a revert decision records its comparison too | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| The two concept files are updated to the comparison-based predicate and the executable abort | Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold | manual review — the concept files are prose and their update is a commitment of this spec, checked at the concept delta step | `openspec/concepts/conformance-property-test-contract.md`, `openspec/concepts/strangler-migration-protocol.md` |
| The gate as implemented would have refused the cutover that shipped | Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold | scenario test replaying the measured 2026-08-29 comparison as a fixture | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGateSpec.scala` |
| No gate condition was weakened relative to what the protocol describes | Requirement: The gate's decision is a comparison against the predecessor, not an absolute threshold | adversarial review (Ring 8), fresh context | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `probatioOracleDiff` | sbt task (new) | `build.sbt` | materialises two seam-configured copies of the scanner tree inside the repository, runs the suite against each, parses both outputs, and emits a `DifferentialResult` |
| `DifferentialHarness` | object (new, test-only) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarness.scala` | the harness the sbt task drives; also callable from tests |
| `CutoverGate` | object (new, test-only) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/CutoverGate.scala` | the decision function |
| `OracleGreenCheck` | object (test-only) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/OracleGreenCheck.scala` | its single-run predicate is replaced by the comparison |
| `OracleGreenGate` | object (test-only) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/OracleGreenGate.scala` | delegates to `CutoverGate` |
| `SeamConfiguration` | final case class (test-only) | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/SeamTypes.scala` | gains the smart constructor and the `resolve` operation |
| `CutoverKernel` | Stainless object (new) | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/CutoverKernel.scala` | comparison-fold mirror |
| `openspec/schemas/verified-scala3/tests/*.bats` | oracle suite | repository | run unmodified by both arms; the harness verifies their digests match the repository's |
| `scanner/*.predecessor.bak`, `hooks/gate.sh.predecessor.bak` | revert targets | `openspec/schemas/verified-scala3/` | must remain present until this gate has held green across a full change cycle |
| `sbt probatioOracleDiff` | build step | root | Ring 3 acceptance for every other spec in this change |
