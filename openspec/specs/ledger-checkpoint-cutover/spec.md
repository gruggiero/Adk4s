# ledger-checkpoint-cutover Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: Each seam is swapped only after its oracle files reach control parity

A seam SHALL be swapped only when every oracle file exercising it fails no more tests
under the ported implementation than under the predecessor, and a seam MUST NOT be swapped
on an unmeasured file.

**Given** a seam and the set of oracle files that exercise it
**When** the differential comparison runs with that seam ported and the rest on the
predecessor
**Then** the swap proceeds only if no such file is worse, and otherwise the seam is left
on the predecessor

**Rationale**: these two seams were reported at parity for the whole migration without ever
being measured. The requirement is that the measurement precede the swap, not that the
measurement be favourable — an unfavourable measurement is a result, an absent one is not.

#### Scenario: Happy path — a seam whose files are at parity is swapped

**Given** a seam whose every exercising oracle file is at or below the predecessor's
failure count
**When** the swap decision is taken
**Then** the seam is swapped and the decision records the comparison it rested on

#### Scenario: Adversarial — a seam with one worse file is not swapped

**Given** a seam whose exercising files are all at parity except one, which is worse
**When** the swap decision is taken
**Then** the seam is left on the predecessor and the decision names the worse file

#### Scenario: Adversarial — a seam with an unmeasured file is not swapped

**Given** a seam one of whose exercising oracle files produced no result in one arm
**When** the swap decision is taken
**Then** the seam is left on the predecessor and the decision names the unmeasured file

### Requirement: The evidence record format stays byte-compatible across the swap

The ported evidence tool SHALL accept every record the executable record contract accepts
and reject every record it rejects, and it MUST NOT accept a record the contract rejects.

**Given** a record and the executable record contract
**When** both the ported validator and the contract judge it
**Then** the two judgments agree

#### Scenario: Happy path — a conforming record is accepted by both

**Given** a record satisfying every contract clause
**When** both judge it
**Then** both accept

#### Scenario: Adversarial — a record violating one clause is rejected by both

**Given** a record violating exactly one contract clause, for each clause in turn
**When** both judge it
**Then** both reject

#### Scenario: Edge case — a record carrying only the optional fields round-trips

**Given** a record carrying every optional field
**When** it is written and read back
**Then** the read record equals the written one

#### Scenario: Error path — a record at an unrecognised format version is could-not-determine

**Given** a record declaring a format version the tool does not recognise
**When** the tool reads it
**Then** the outcome is could-not-determine naming the version, and the record is not
skipped silently

### Requirement: The checkpoint reports every requested ring from recorded evidence

The checkpoint SHALL report an outcome for every ring it was asked about, drawn from
recorded evidence at the current baseline, and it MUST NOT report a ring outcome for which
no evidence was recorded.

**Given** a checkpoint invocation naming a set of rings
**When** the checkpoint is generated
**Then** every named ring appears, each either carrying its recorded evidence or marked as
having none

#### Scenario: Happy path — a fully evidenced ring set reports every ring

**Given** a ring set for which every ring has recorded evidence at the current baseline
**When** the checkpoint is generated
**Then** every ring appears with its evidence

#### Scenario: Adversarial — a ring with no recorded evidence is marked, not inferred

**Given** a ring set containing one ring with no recorded evidence
**When** the checkpoint is generated
**Then** that ring appears marked as having no recorded evidence, and no outcome is
asserted for it

#### Scenario: Adversarial — evidence recorded at another baseline does not count

**Given** a ring whose only evidence was recorded at a different baseline
**When** the checkpoint is generated at the current baseline
**Then** that ring is marked as having no recorded evidence at this baseline

### Requirement: The predecessor implementations remain the revert target

Each swapped predecessor implementation SHALL remain on disk until the comparison has held
at parity across a full change cycle, and a predecessor implementation MUST NOT be removed
by this change.

**Given** a swapped seam
**When** the tree is inspected
**Then** the predecessor implementation for that seam is present

#### Scenario: Happy path — every swapped seam has its predecessor on disk

**Given** the set of swapped seams after this change
**When** the tree is inspected
**Then** each has its predecessor implementation present

#### Scenario: Adversarial — a swap whose predecessor is absent is refused

**Given** a seam whose predecessor implementation is not present
**When** the swap decision is taken
**Then** the swap is refused naming the absent predecessor

