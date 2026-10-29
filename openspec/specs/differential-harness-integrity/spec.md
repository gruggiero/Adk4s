# differential-harness-integrity Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
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

