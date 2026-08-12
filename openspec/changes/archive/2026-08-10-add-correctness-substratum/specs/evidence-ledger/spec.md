# Spec: Evidence Ledger

A machine-written, append-only record of which obligations were discharged, by
what command, with what result. Supplies the third clause of the correctness
definition — the one that currently has no mechanism anywhere in the workflow.

Depends on: `correctness-invariant` (the definition this ledger evidences).

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Workflow tooling only; no registry concept's purpose, state, actions or synchronizations change | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Evidence Ledger | persisted record (JSON lines, versioned) | Append-only log of discharged obligations, one row per observed run |
| Ledger writer | shell entry point | The single sanctioned way to append a row; scripts call it, agents do not hand-edit |

<!-- AMENDED by design.md Decision 1: the record format is JSON lines, not the
     tab-separated format this spec originally committed to. Rationale: the
     `command` field is required in every row and routinely contains quotes,
     and can contain tabs and newlines — so separator-inside-a-field is the
     normal case here, not an edge case. Delegating framing to the JSON
     encoder moves the invariant from caller discipline to structurally
     impossible. No case is removed: the awkward-value enumeration survives
     intact as JSON string content. -->


## ADDED Requirements

### Requirement: A ledger row records an observed run, not an intention

The ledger SHALL accept a row only when that row carries the command that was
executed, the exit status it returned, and the baseline revision it ran
against, so that every row is re-checkable by re-running the recorded command.

**Given** a verification step that has finished executing
**When** the step records its outcome
**Then** the recorded row carries the change name, spec name, ring, obligation,
artifact, the command executed, its exit status, the baseline revision, and a
timestamp

**Rationale**: A row that records only "ring 3 passed" is a self-report and
cannot be distinguished from a fabrication. Recording the command and exit
status makes the row falsifiable.

#### Scenario: a complete row is accepted

**Given** a verification step supplying every required field
**When** the row is appended
**Then** the ledger gains exactly one row and the writer reports success

#### Scenario: a row missing the exit status is rejected

**Given** a verification step supplying every field except the exit status
**When** the append is attempted
**Then** no row is written and the writer reports the missing field by name

#### Scenario: a row missing the command is rejected

**Given** a verification step supplying every field except the command
**When** the append is attempted
**Then** no row is written and the writer reports the missing field by name

### Requirement: The ledger is append-only through its writer

The ledger writer SHALL only append, and SHALL NEVER rewrite, reorder, or
delete an existing row; a request that would modify existing content MUST be
refused rather than performed.

**Given** a ledger containing rows from earlier steps
**When** any append occurs
**Then** every pre-existing row is byte-identical afterwards and the new row is
last

**Rationale**: The ledger's value is that a claim without a row is detectable.
If rows can be rewritten, the ledger becomes as forgeable as the prose it
replaces, with more authority.

#### Scenario: earlier rows survive an append

**Given** a ledger with three rows
**When** a fourth row is appended
**Then** the first three rows are unchanged and the file has four rows

#### Scenario: a field containing a record-framing character cannot corrupt a row

**Given** a verification step whose command text contains a newline, a double
quote, and a backslash
**When** the row is appended
**Then** the stored row still parses as exactly one row whose field count
equals the number of fields supplied, and every original field value is
recovered unchanged on read

#### Scenario: an attempt to modify an existing row is refused

**Given** a ledger with existing rows
**When** the writer is invoked in a way that would alter a stored row
**Then** the writer refuses, the file is unchanged, and a non-zero status is
returned

### Requirement: A ledger row is scoped to one change and one spec

Each ledger row SHALL name the change and the spec it belongs to, so that rows
from concurrently active changes cannot be read as evidence for one another.

**Given** a repository with more than one active change
**When** evidence is read for a particular change
**Then** only rows naming that change are considered, and rows naming another
change are excluded

**Rationale**: The repository holds multiple active changes at once. Evidence
that is not scoped would let one change's green runs satisfy another's
obligations.

#### Scenario: rows from another change are excluded

**Given** a ledger containing rows for two changes
**When** evidence is read for the first change
**Then** rows naming the second change do not appear in the result

#### Scenario: rows from an earlier spec of the same change are retained

**Given** a ledger containing rows for two specs of the same change
**When** evidence is read for that change
**Then** rows from both specs appear, each attributed to its own spec

### Requirement: A stale row is distinguishable from a current one

A ledger row SHALL be readable as stale when the baseline revision it records
differs from the revision currently under verification, and a stale row MUST
NOT be reported as discharging an obligation.

**Given** a ledger row recorded against one baseline revision
**When** evidence is read while a different revision is under verification
**Then** the row is reported as stale and does not discharge its obligation

**Rationale**: Without revision scoping, a green run from before an edit would
continue to discharge an obligation the edit invalidated — reproducing the
carried-over-verdict defect the correctness definition forbids.

#### Scenario: a row from the current baseline discharges

**Given** a row whose baseline revision equals the revision under verification
**When** evidence is read
**Then** the row discharges its obligation

#### Scenario: a row from a superseded baseline does not discharge

**Given** a row whose baseline revision differs from the revision under
verification
**When** evidence is read
**Then** the row is reported as stale and the obligation is reported as not
discharged

### Requirement: A malformed ledger fails loudly

Reading the ledger SHALL report a parse failure explicitly, and MUST NOT report
an unreadable or truncated ledger as containing no evidence.

**Given** a ledger file whose content does not parse
**When** evidence is read
**Then** a parse failure is reported with the offending line number, and the
read returns a non-zero status

**Rationale**: The project's own history contains two parsers that read a
legitimate notation as nothing and reported success. An empty result and an
unreadable input must never be the same outcome.

#### Scenario: a truncated final row is reported, not ignored

**Given** a ledger whose last line has fewer fields than required
**When** evidence is read
**Then** the failure names that line number and the read returns non-zero

#### Scenario: an absent ledger is distinguished from an empty one

**Given** no ledger file exists
**When** evidence is read
**Then** the result states that no ledger exists, which is reported distinctly
from a ledger that exists and holds zero rows

### Requirement: Every row carries a format version and an unknown one halts the read

Every ledger row SHALL carry a record format version, and a reader encountering
a version it does not recognise MUST report the read as undetermined rather
than skipping the row or treating the ledger as containing fewer rows.

**Given** a ledger containing a row whose format version the reader does not
recognise
**When** evidence is read
**Then** the read reports undetermined and returns a non-zero status, and no
count is produced

**Rationale**: Skipping unrecognised records is the tempting behaviour and the
wrong one — it would make a forward-incompatible ledger read as partially
discharged, which is the silent-degradation shape this workflow exists to
remove. The version field also makes a future format migration an explicit
decision rather than an inference.

#### Scenario: a recognised version reads normally

**Given** a ledger whose rows all carry the current format version
**When** evidence is read
**Then** every row is read and counted

#### Scenario: an unrecognised version halts the whole read

**Given** a ledger of ten rows where one carries an unrecognised format version
**When** evidence is read
**Then** the read reports undetermined, and does not report the other nine rows
as a complete result

#### Scenario: a row with no version field is rejected

**Given** an append supplying no format version
**When** the append is attempted
**Then** no row is written and the writer reports the missing field by name

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash.
     Hedgehog is Scala-only and this spec ships no Scala; bash has no
     generator/shrinker facility. Each property below is ENUMERATED over an
     explicitly listed finite domain in bats, and declares that enumeration as
     its generator strategy. Where the true domain is unbounded, the
     enumeration is a stated finite subset and that limit is recorded in the
     obligation rather than hidden. Claiming sampled-property coverage for
     these tests would misstate the oracle. -->

### Property: Append preserves all prior rows

**Invariant**: For any starting ledger and any well-formed row, appending that
row leaves the prior content byte-identical and increases the row count by
exactly one.

**Generator strategy**: Enumerated over starting ledgers of size 0, 1, 2 and
10, crossed with rows drawn from a listed field-value set that includes the
awkward cases: embedded newline, embedded double quote, embedded backslash,
embedded tab, empty optional field, and a non-ASCII character. Constructive —
every input is built, none filtered.

```
forAll (ledger, row) in sizes{0,1,2,10} x rows{plain, newline, quote, backslash, tab, utf8, empty-optional}:
  prior_bytes(append(ledger, row)) == bytes(ledger)
  and row_count(append(ledger, row)) == row_count(ledger) + 1
```

### Property: Write-then-read round-trips every field

**Invariant**: For any well-formed row, appending it and then reading it back
yields field values equal to those supplied.

**Generator strategy**: Enumerated over the same listed field-value set as
above, applied to every field position in turn so each field is independently
exercised with each awkward value. Constructive. Edge cases covered:
record-framing characters in every position, and a field that is the empty
string.

```
forAll row in rows, forAll i in field_positions:
  read_back(append(empty, with_value(row, i, awkward)))[i] == awkward
```

### Property: Evidence reads are scoped to their change and baseline

**Invariant**: For any ledger containing rows from multiple changes and
baselines, reading evidence for one change at one baseline returns exactly the
rows matching both, and no others.

**Generator strategy**: Enumerated over ledgers built from the cross product of
two change names, two spec names and two baseline revisions, so every
combination of match and mismatch on both axes is present. Constructive; the
expected result is computed from the construction, not from the reader.

```
forAll (c, s, b) in changes x specs x baselines:
  read(ledger, change=c, baseline=b) == { r in ledger : r.change == c and r.baseline == b }
```

### Property: A malformed ledger never reads as empty

**Invariant**: For any corrupted ledger, reading reports a failure; it never
returns success with zero rows.

**Generator strategy**: Enumerated over a listed corruption set applied to a
known-good ledger: truncated final line, a required field absent, an
unparseable record, a binary byte, a file of only whitespace, and a row
carrying an unrecognised format version. Constructive — each corruption is
applied deliberately.

```
forAll corruption in corruptions:
  read(corrupt(good_ledger, corruption)) is failure
  and not (read(...) is success with zero rows)
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A row without command, exit status or baseline is refused | Requirement: A ledger row records an observed run, not an intention | scenario tests, one per missing required field | `evidence-ledger.bats` |
| Every required field is present in an accepted row | Requirement: A ledger row records an observed run, not an intention + Property: Write-then-read round-trips every field | property test enumerated over field positions | `evidence-ledger.bats` |
| Appending never alters prior rows | Requirement: The ledger is append-only through its writer + Property: Append preserves all prior rows | property test over enumerated starting sizes | `evidence-ledger.bats` |
| A record-framing character inside a field cannot corrupt a row | Requirement: The ledger is append-only through its writer + Scenario: a field containing a record-framing character cannot corrupt a row | property test with framing characters in every field position | `evidence-ledger.bats` |
| A modifying invocation is refused with non-zero status | Requirement: The ledger is append-only through its writer + Scenario: an attempt to modify an existing row is refused | scenario test | `evidence-ledger.bats` |
| Evidence is scoped to change and spec | Requirement: A ledger row is scoped to one change and one spec + Property: Evidence reads are scoped to their change and baseline | property test over the change x spec x baseline cross product | `evidence-ledger.bats` |
| A row from a superseded baseline does not discharge | Requirement: A stale row is distinguishable from a current one | scenario tests, current and superseded baseline | `evidence-ledger.bats` |
| A malformed ledger reports failure and never reads as empty | Requirement: A malformed ledger fails loudly + Property: A malformed ledger never reads as empty | property test over the enumerated corruption set | `evidence-ledger.bats` |
| An absent ledger is reported distinctly from an empty one | Requirement: A malformed ledger fails loudly + Scenario: an absent ledger is distinguished from an empty one | scenario test | `evidence-ledger.bats` |
| A ledger written by this version still parses after later edits | Requirement: A ledger row records an observed run, not an intention | Ring 4 compatibility fixture — a committed ledger from this version, re-parsed and never regenerated to make a test pass | `tests/fixtures/evidence-ledger-v1.jsonl`, `evidence-ledger.bats` |
| Every row carries a format version | Requirement: Every row carries a format version and an unknown one halts the read + Scenario: a row with no version field is rejected | scenario test asserting the append is refused | `evidence-ledger.bats` |
| An unrecognised format version halts the whole read | Requirement: Every row carries a format version and an unknown one halts the read + Property: A malformed ledger never reads as empty | property test with unrecognised-version corruption; scenario test asserting the other rows are not reported as a complete result | `evidence-ledger.bats` |
| The writer is the only sanctioned append path | Requirement: The ledger is append-only through its writer | manual review at the Step 8 adversarial gate — whether callers bypass the writer is a property of call sites, not of the writer | Ring 8 report |
| The ledger cannot be made tamper-proof in-process | Requirement: The ledger is append-only through its writer | manual review, explicitly accepted limit — recording command and exit status makes a row re-checkable; it does not make forgery impossible | Ring 8 report |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/scanner/ledger.sh` | script | scanner/ | NEW — `append` and `read` subcommands; the only sanctioned write path; encodes and decodes with `jq` per design Decision 1 |
| `openspec/changes/<change>/evidence-ledger.jsonl` | persisted file | change dir | NEW — per-change ledger, one JSON object per line, `v` field first; archived with the change |
| `openspec/schemas/verified-scala3/tests/evidence-ledger.bats` | test | tests/ | NEW — layout established by the `correctness-invariant` spec |
| `openspec/schemas/verified-scala3/tests/fixtures/evidence-ledger-v1.jsonl` | Ring 4 fixture | tests/fixtures/ | NEW — compatibility baseline written by this version; committed, never regenerated |
| `jq` | prerequisite | host | supplies record framing; declared in the capability profile's prerequisite set |
| Step 0 baseline SHA | existing workflow concept | `schema.yaml` apply | supplies the `baseline` field of every row |
