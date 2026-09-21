# Spec: Differential Harness Integrity

The acceptance comparison that decides whether the cutover may stand must compare two
different implementations. Today it compares one implementation with itself and reports
that nothing is worse — a verdict that is true by construction and therefore carries no
information.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | This spec implements the protocol's **Gate** action as declared. The concept's Synchronizations already state that the comparison "materialises two seam-configured scanner trees"; the shipped code did not. The concept's **State** section declared a six-position swap order; this spec adds the `Checkpoint` position and registers the new `ArmTree`/`SeamResolution`/`ArmDivergence` state entries in the concept — an extension to match the seven-seam reality, not a change to its purpose, actions, or synchronizations. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, or synchronizations. Its **State**
section is extended with the seam-set and arm types this spec introduces, and the swap order
gains the `Checkpoint` position the seam widening requires.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `DifferentialHarness` | object (`runSuite`, `diff`, `verifySuiteDigests`) | `org.sinemenda.probatio.migration` |
| `DifferentialResult` | final case class | `org.sinemenda.probatio.migration` |
| `FileComparison` | final case class | `org.sinemenda.probatio.migration` |
| `CutoverGate` | object (`decide`, `record`) | `org.sinemenda.probatio.migration` |
| `CutoverVerdict` | enum (Proceed, Revert) | `org.sinemenda.probatio.migration` |
| `GateRecord` | final case class | `org.sinemenda.probatio.migration` |
| `ToolId` (migration) | enum (SpecLint, ChainState, DangerScan, Reconcile, Gate) | `org.sinemenda.probatio.migration` |
| `SeamConfiguration` | final case class | `org.sinemenda.probatio.migration` |
| `SwapOrder` | enum (LedgerFirst, ChainState, SpecLint, DangerScan, Reconcile, GateLast) | `org.sinemenda.probatio.migration` |
| `OracleGreenCheck` | final class extending a munit suite | `org.sinemenda.probatio.migration` |
| `OracleGreenGate` | object | `org.sinemenda.probatio.migration` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ArmTree` | final case class, private constructor | A materialised copy of the tool tree for one side of the comparison, carrying the resolved implementation of every seam. Constructible only by materialisation, so an arm that was never built cannot be passed to the comparison. |
| `ArmDivergence` | enum (`Diverged(perSeam)`, `Identical(seams)`) | The verdict of comparing the two arms' resolved implementations before the suite runs. |
| `SeamResolution` | final case class (seam, implementationDigest, sourcePath) | What one seam resolved to in one arm, identified by content digest rather than by path, so two paths holding the same bytes are recognised as the same implementation. |

`ToolId` is **modified**: it gains a ledger seam and a checkpoint seam, bringing the seam
set to seven and matching the swap order the migration concept already declares.

### Type-Widening Impact

`ToolId` gains two cases. Every match over it must be re-examined; the probatio modules
compile with exhaustiveness escalated to an error, so an unhandled case fails Ring 0
rather than falling through. The known matches are enumerated in Implementation Anchors.
Each must **handle the new seams exhaustively** — none may absorb them into a catch-all,
because a seam silently classified as "some other tool" is precisely the defect that let
the ledger seam go unmeasured for the whole migration.

## ADDED Requirements

### Requirement: The comparison resolves each arm to a materialised tree

Each side of the differential comparison SHALL be a materialised copy of the tool tree in
which every seam has been resolved to a named implementation, and the comparison MUST NOT
accept a side that was not materialised.

**Given** a seam configuration naming which tools are on the ported implementation
**When** an arm is prepared for that configuration
**Then** a tree is materialised in which each seam holds the implementation that
configuration names, and each seam's resolved implementation is recorded with its content
digest

#### Scenario: Happy path — the predecessor arm holds predecessor implementations

**Given** a configuration in which no tool is ported
**When** the predecessor arm is materialised
**Then** every seam resolves to its predecessor implementation, and no seam resolves to
the ported binary

#### Scenario: Happy path — the ported arm holds ported implementations

**Given** a configuration in which every tool is ported
**When** the ported arm is materialised
**Then** every seam resolves to the ported binary

#### Scenario: Error path — a seam with no predecessor implementation is could-not-determine

**Given** a configuration naming a seam whose predecessor implementation is absent from
the tree
**When** the arm is materialised
**Then** the result is could-not-determine naming the absent seam, and no comparison is
attempted

#### Scenario: Adversarial — an unmaterialised side cannot be compared

**Given** a caller that assembles a comparison side without materialising it
**When** the code is compiled
**Then** compilation fails: the side's type has no constructor reachable without
materialisation

### Requirement: A comparison whose arms resolve identically is refused

The comparison SHALL refuse to produce a verdict when both arms resolve every seam to the
same implementation, and a refusal MUST NOT be reported as a passing verdict.

**Given** two materialised arms
**When** their per-seam resolved implementations are compared before the suite runs
**Then** arms that differ at one or more seams proceed to the suite, and arms identical at
every seam yield a refusal naming the identical seams

**Rationale**: this is the defect this spec exists to remove. A comparison of a run with
itself cannot show a regression, so the absence of a regression carries no evidence. The
refusal must be distinguishable from "no file is worse" — an operator reading the output
must not be able to mistake one for the other.

#### Scenario: Adversarial — identical arms yield a refusal, not Proceed

**Given** two arms in which every seam resolves to the same implementation
**When** the comparison runs
**Then** the outcome is a refusal naming every identical seam, and the outcome is not the
proceed verdict

#### Scenario: Adversarial — arms differing at one seam only are still compared

**Given** two arms differing at exactly one seam and identical at the other six
**When** the comparison runs
**Then** the comparison proceeds and the refusal is not raised

#### Scenario: Edge case — two paths holding identical bytes are the same implementation

**Given** two arms whose seams resolve to different file paths holding byte-identical
content
**When** the arms are compared
**Then** the seams are treated as identical, and the refusal is raised

### Requirement: Every swapped seam is represented in the comparison

The seam set the comparison ranges over SHALL contain every tool whose live invocation
path the cutover replaced, and a tool absent from that set MUST NOT be reported as
compared.

**Given** the set of tools whose live invocation paths have been swapped to the ported
binary
**When** the comparison's seam set is enumerated
**Then** the seam set contains each of them, including the evidence-ledger tool and the
checkpoint tool

**Rationale**: the six oracle files covering the evidence ledger and the checkpoint were
reported "at parity" through the whole migration while both arms executed the same
predecessor script. Those files never measured the port. A seam that is not in the set is
not compared, and reporting it as compared is a claim without evidence.

#### Scenario: Happy path — the seam set matches the declared swap order

**Given** the swap order the migration protocol declares
**When** the seam set is enumerated
**Then** every position in the swap order has a corresponding seam

#### Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared

**Given** a suite file that exercises a tool absent from the seam set
**When** the comparison reports its per-file results
**Then** that file is reported as not-compared rather than as at-parity

### Requirement: The comparison reproduces the recorded predecessor control

The failure counts the comparison reports for the predecessor arm SHALL equal the counts
obtained by running the suite against a tree holding only predecessor implementations.

**Given** the predecessor control measured on 2026-09-20 at the change's baseline — 18
failing tests of 282, concentrated in four suite files
**When** the repaired comparison runs its predecessor arm at that same baseline
**Then** its reported predecessor failure counts equal that control, per file

**Rationale**: a recorded limitation is re-established before it is relied upon. The
control carries its date and the mechanism by which it was obtained. This requirement is
the spec's exit criterion: a harness that cannot reproduce a hand-measured control is not
yet measuring the predecessor.

#### Scenario: Happy path — the predecessor arm matches the control per file

**Given** the repaired comparison at the recorded baseline
**When** the predecessor arm's per-file failure counts are compared to the control
**Then** every file's count is equal

#### Scenario: Error path — a comparison at a different baseline is not checked against the control

**Given** a comparison run at a baseline other than the recorded one
**When** the control check is attempted
**Then** the check reports could-not-determine naming the baseline mismatch, rather than
passing or failing

#### Scenario: Adversarial — a predecessor arm reporting the ported counts is rejected

**Given** a comparison whose predecessor arm reports the failure counts measured for the
ported implementation — 30 failing tests concentrated in seven suite files
**When** the control check runs at the recorded baseline
**Then** the check fails naming the files whose counts differ from the control, and the
comparison does not yield the proceed verdict

### Requirement: The suite is retargeted at the implementation it now measures

Every suite test that inspects an implementation's source text SHALL name the
implementation that is live at the seam it tests, and no test MAY be left asserting over
a source that the seam no longer holds.

**Given** the two suite tests that assert over the source text of a replaced tool — one
checking that the drift-remediation message names the installer that exists, one checking
that the session payload's working directory is read with the declared parsing tool rather
than a pattern substitution
**When** the seam holds the ported implementation
**Then** each test asserts the same property over the ported implementation's source

**Rationale**: these two tests cannot pass against a three-line forwarding script, and
cannot fail against one either — they assert over text that is no longer where the
behaviour lives. Left red, they destroy the suite's ability to distinguish a stale test
from a new regression, which is the signal every other spec's exit criterion depends on.

#### Scenario: Happy path — the retargeted drift-message test passes against the ported tool

**Given** the ported implementation whose drift-remediation message names the installer
that exists
**When** the retargeted test runs
**Then** it passes

#### Scenario: Adversarial — the retargeted test fails when the ported tool names a non-existent installer

**Given** a ported implementation whose drift-remediation message names an installer that
is not present in the tree
**When** the retargeted test runs
**Then** it fails

#### Scenario: Adversarial — the retargeted payload test fails when the working directory is read by pattern substitution

**Given** a ported implementation that extracts the working directory from the session
payload by pattern substitution rather than by structured parsing
**When** the retargeted test runs
**Then** it fails

## Properties (Ring 3)

### Property: identical-arms-never-proceed

**Invariant**: for every seam configuration, if the two arms resolve every seam to the
same implementation then the comparison's outcome is a refusal — never the proceed
verdict, and never a revert verdict carrying evidence.

**Generator strategy**: `genSeamConfiguration` — constructive over subsets of the seam
set, reusing the existing generator and widening it to the seven-seam set. Edge cases:
the empty set (all predecessor), the full set (all ported), and each singleton. The arms
are then built so that both sides resolve identically, which is the condition under test.

```
property("identical arms never proceed") {
  for {
    config <- genSeamConfiguration.forAll
    left    = materialise(config)
    right   = materialise(config)
    outcome = compare(left, right)
  } yield Result.assert(outcome.isRefusal && !outcome.isProceed)
}
```

### Property: divergence-is-detected-by-content-not-path

**Invariant**: for every pair of arms, the divergence verdict depends only on the seams'
content digests — two arms whose seams hold byte-identical content are `Identical`
regardless of the paths those contents were read from, and two arms whose seams hold
differing content are `Diverged` regardless of the paths being equal.

**Generator strategy**: `genArmPair` — constructive. Generates a seam set, then for each
seam independently generates (content, path) pairs from a small alphabet of contents and
a small alphabet of paths, so that content-equal/path-differing and
content-differing/path-equal cases are both produced by construction rather than reached
by filtering. Edge cases: empty content, one seam, all seams equal, all seams different.

```
property("divergence follows content, not path") {
  for {
    pair <- genArmPair.forAll
    verdict = divergence(pair.left, pair.right)
  } yield Result.assert(
    verdict.isIdentical == pair.left.digests == pair.right.digests
  )
}
```

### Property: seam-set-covers-the-swap-order

**Invariant**: for every position in the declared swap order there is exactly one seam in
the seam set, and for every seam there is exactly one position — the two enumerations are
in bijection.

**Generator strategy**: enumerated, not sampled. Both domains are finite closed sets
(seven positions, seven seams), so the property loops over the full enumeration. This
finite-domain limit is stated rather than presented as sampled coverage.

```
property("seam set and swap order are in bijection") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(
    swapOrder.map(seamOf).toSet == allSeams.toSet &&
    swapOrder.length == allSeams.length
  )
}
```

### Property: comparison-is-monotone-in-failures

**Invariant**: for every pair of suite runs, the comparison reports a file as worse if and
only if that file's ported failure count exceeds its predecessor failure count. Equal
counts are never worse; fewer are never worse.

**Generator strategy**: `genSuiteRunPair` — constructive over per-file (total, predecessor
failures, ported failures) triples with failures bounded by total. Edge cases: zero
failures both sides, equal nonzero failures, ported strictly greater, ported strictly
fewer, a file present in one run only.

```
property("worse iff ported failures exceed predecessor failures") {
  for {
    pair <- genSuiteRunPair.forAll
    d     = diff(pair.predecessor, pair.ported, repo)
  } yield Result.assert(
    d.worseFiles.map(_.fileName).toSet ==
      pair.files.filter(f => f.ported > f.predecessor).map(_.name).toSet
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A comparison side constructed without materialisation | An unmaterialised side has no resolved seams, so the divergence guard would compare nothing and pass vacuously | `assertDoesNotCompile("ArmTree(Nil)")` — the type's constructor is private; only the materialising factory returns one |
| A divergence verdict constructed from paths alone | Path equality is not implementation equality; the whole defect is two different paths holding the same forwarding script | `assertDoesNotCompile("ArmDivergence.Identical(List(\"a.sh\"))")` — the variant carries `SeamResolution`, which requires a digest |
| A seam identifier written as a free string | A seam named by string can name a seam that does not exist, which is how a tool drops out of the comparison unnoticed | `assertDoesNotCompile("SeamConfiguration(Set(\"ledger\"))")` — the field is typed by the seam enum |

## Formal Contracts (Ring 6)

The divergence decision and the worse-file fold are the two decisions at the centre of
this spec. Both are total functions over finite structures and are mirrored in the
verification module.

### Contract: divergence

```
def divergence(left: List[SeamResolution], right: List[SeamResolution]): ArmDivergence = {
  require(left.length == right.length)
  require(left.map(_.seam) == right.map(_.seam))
  ...
} ensuring { result =>
  // identical iff every paired seam has the same digest
  result.isIdentical == left.zip(right).forall((l, r) => l.digest == r.digest) &&
  // a diverged verdict names at least one genuinely differing seam
  (!result.isIdentical ==> result.divergingSeams.nonEmpty)
}
```

### Contract: worseFiles

```
def worseFiles(files: List[FileComparison]): List[FileComparison] = {
  ...
} ensuring { result =>
  result.forall(f => f.portedFailures > f.predecessorFailures) &&
  files.filter(f => f.portedFailures > f.predecessorFailures).length == result.length
}
```

A bridge property binds the shipped functions to these models, per the verified-mirror
pattern — the mirror is evidence only insofar as the shipped code is shown to agree with
it.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Each arm is a materialised tree with resolved seams | Requirement: The comparison resolves each arm to a materialised tree | smart constructor (materialising factory is the only path) + scenario test | `DifferentialHarnessSpec` |
| The predecessor arm holds predecessor implementations | Requirement: The comparison resolves each arm to a materialised tree + Scenario: Happy path — the predecessor arm holds predecessor implementations | scenario test | `DifferentialHarnessSpec` |
| The ported arm holds ported implementations | Requirement: The comparison resolves each arm to a materialised tree + Scenario: Happy path — the ported arm holds ported implementations | scenario test | `DifferentialHarnessSpec` |
| An absent predecessor implementation is could-not-determine | Requirement: The comparison resolves each arm to a materialised tree + Scenario: Error path — a seam with no predecessor implementation is could-not-determine | scenario test | `DifferentialHarnessSpec` |
| An unmaterialised side is unconstructible | Compile-Negative: A comparison side constructed without materialisation | compile-negative test | `DifferentialHarnessCompileNegative` |
| Identical arms yield a refusal, never proceed | Requirement: A comparison whose arms resolve identically is refused + Property: identical-arms-never-proceed | Hedgehog property + scenario test | `DifferentialHarnessSpec` |
| Arms differing at one seam are still compared | Requirement: A comparison whose arms resolve identically is refused + Scenario: Adversarial — arms differing at one seam only are still compared | scenario test | `DifferentialHarnessSpec` |
| Identical bytes at differing paths are the same implementation | Requirement: A comparison whose arms resolve identically is refused + Scenario: Edge case — two paths holding identical bytes are the same implementation + Property: divergence-is-detected-by-content-not-path | Hedgehog property | `DifferentialHarnessSpec` |
| A divergence verdict cannot be built from paths alone | Compile-Negative: A divergence verdict constructed from paths alone | compile-negative test | `DifferentialHarnessCompileNegative` |
| The seam set contains every swapped tool | Requirement: Every swapped seam is represented in the comparison + Property: seam-set-covers-the-swap-order | Hedgehog property (enumerated finite domain) | `SwapOrderSpec` |
| A file exercising an unrepresented tool is reported not-compared | Requirement: Every swapped seam is represented in the comparison + Scenario: Adversarial — a suite file exercising an unrepresented tool is not claimed as compared | scenario test | `DifferentialHarnessSpec` |
| A seam cannot be named by a free string | Compile-Negative: A seam identifier written as a free string | compile-negative test | `DifferentialHarnessCompileNegative` |
| The predecessor arm reproduces the recorded control | Requirement: The comparison reproduces the recorded predecessor control | manual run, recorded in the evidence ledger — the control is a measured fact at a named baseline, not a unit-testable function | `probatioOracleDiff` run recorded in `evidence-ledger.jsonl` |
| A comparison at a different baseline is could-not-determine | Requirement: The comparison reproduces the recorded predecessor control + Scenario: Error path — a comparison at a different baseline is not checked against the control | scenario test | `DifferentialHarnessSpec` |
| The drift-message test asserts over the live implementation | Requirement: The suite is retargeted at the implementation it now measures + Scenario: Happy path — the retargeted drift-message test passes against the ported tool | bats oracle | `workflow-hygiene.bats` |
| The retargeted drift-message test fails on a bad implementation | Requirement: The suite is retargeted at the implementation it now measures + Scenario: Adversarial — the retargeted test fails when the ported tool names a non-existent installer | bats oracle (negative fixture) | `workflow-hygiene.bats` |
| The retargeted payload test fails on pattern substitution | Requirement: The suite is retargeted at the implementation it now measures + Scenario: Adversarial — the retargeted payload test fails when the working directory is read by pattern substitution | bats oracle (negative fixture) | `workflow-hygiene.bats` |
| The worse-file fold is exact | Property: comparison-is-monotone-in-failures | Hedgehog property | `CutoverGateSpec` |
| The divergence and worse-file decisions are verified | Invariant: divergence is content-based and total + Property: comparison-is-monotone-in-failures | Stainless verification + bridge property test | `CutoverKernel` (extended) + `CutoverBridgeSpec` |
| New seam variants are handled exhaustively | Type-Constraint: the seam enum gains a ledger seam and a checkpoint seam | compiler exhaustiveness escalation (error, not warning) | Ring 0 on `probatio-core`, `probatio-cli` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `DifferentialHarness.runSuite` | object method | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarness.scala:54` | The defect site: builds `*_OVERRIDE` env vars and runs `bats` in place. Replaced by materialise-then-run. |
| `DifferentialHarness.diff` | object method | same file, `:97` | Correct; reused unchanged |
| `DifferentialHarness.verifySuiteDigests` | object method | same file, `:129` | Already exists; reused for the suite-integrity check |
| `ArmTree`, `ArmDivergence`, `SeamResolution` | new types | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/` | New |
| `SeamTypes.ToolId` | enum | `.../migration/SeamTypes.scala:26` | Gains `Ledger`, `Checkpoint`. Matches over it: `overrideEnvVar` (`:47`), `swapOrder` (`:55`), the `ReadWriter` bimap (`:34–43`) — the bimap's `case other => sys.error` arm must be checked: it is a decoder guard, not a variant catch-all, and must still reject unknown strings while accepting the two new names. |
| `SwapOrder` | enum | `.../migration/SwapOrder.scala:25` | Already six positions; gains the checkpoint position |
| `OracleGreenGate.apply` | object method | `.../migration/OracleGreenGate.scala:48,78` | Instantiates `new OracleGreenCheck()`; unchanged by this spec but its seam set widens |
| `OracleDiffRunner` | munit suite | `.../migration/OracleDiffRunner.scala` | The `probatioOracleDiff` entry point; asserts the arms diverged before reporting a verdict |
| `probatioOracleDiff` | sbt command alias | `build.sbt:714` | Unchanged name; now runs a real comparison |
| `workflow-hygiene.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Two tests retargeted (D7 drift message, D8 working-directory parse) |
| `*.predecessor.bak` | predecessor implementations | `openspec/schemas/verified-scala3/{hooks,scanner}/` | The predecessor arm's source; must remain on disk |
| `CutoverKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/CutoverKernel.scala` | Extended with the divergence and worse-file contracts |
| Ring 5 note | — | `stryker4s.conf` | This spec's implementation lives in **test** sources; Stryker collects coverage for main sources only, so the move-to-main-and-back procedure applies |
