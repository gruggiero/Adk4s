# Spec: Judgment Ring Integrity

<!-- DELTA spec for the `judgment-ring-integrity` capability. Fixes D4 (high:
     judgment rings recorded as prose degrade re-checkability) from the
     2026-08-12 substratum review. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Workflow tooling only; no registry concept changes | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| command/exit disagreement lint | ledger validation rule | If `command` parses as a runnable shell command, replay must reproduce the recorded exit |
| resolvable report artifact | ledger requirement for manual rows | `manual`/R8 rows must name a report file that exists and is hashable |

## ADDED Requirements

### Requirement: Manual ring rows must name a resolvable report artifact

A ledger row with `ring: "manual"` or `ring: "R8"` SHALL include an
`artifact` field pointing to a report file that exists on disk. The ledger
validation SHALL verify the artifact's existence and record its SHA-256 hash.

**Given** a ledger row with `ring: "R8"` and `artifact:
"reviews/spec1-ring8-report.md"`
**When** the ledger is validated
**Then** the validation checks that `reviews/spec1-ring8-report.md` exists
and records its `sha256`

**Rationale**: Judgment rings (R2, R8) legitimately have no deterministic
command. Rather than pretending to one, they must name the report artifact
that IS the evidence — verified F9-style and hashed. (Review D4.)

#### Scenario: a manual row with a resolvable artifact

**Given** a ledger row with `ring: "R8"`, `artifact: "reviews/r8.md"`, and
`reviews/r8.md` exists
**When** the ledger is validated
**Then** validation passes and the row's `sha256` matches the file

#### Scenario: a manual row with a missing artifact

**Given** a ledger row with `ring: "R8"`, `artifact: "reviews/missing.md"`,
and `reviews/missing.md` does NOT exist
**When** the ledger is validated
**Then** validation fails with "manual row artifact does not resolve:
reviews/missing.md"

#### Scenario: a manual row without an artifact field

**Given** a ledger row with `ring: "R8"` and no `artifact` field
**When** the ledger is validated
**Then** validation fails with "manual/R8 rows must name a report artifact"

### Requirement: Runnable commands must agree with their recorded exit

A ledger row whose `command` field parses as a runnable shell command SHALL
have its recorded exit verified by replay. If replay produces a different
exit, the row is flagged as `command/exit disagreement`.

**Given** a ledger row with `command: "grep -qF 'curl ' scanner/checkpoint.sh"`
and `exit: 0`
**When** the command is replayed and `grep` finds no matches (exits 1)
**Then** the row is flagged as `command/exit disagreement` (recorded 0,
observed 1)

**Rationale**: The archived ledger already contains a row where `grep` with
no matches exits 1 but the recorded exit is 0 — the row is not re-checkable
as recorded. (Review D4.)

#### Scenario: grep no-match disagreement

**Given** a ledger row with `command: "grep -qF 'nonexistent_string'
somefile"` and `exit: 0`
**When** replayed (grep finds nothing, exits 1)
**Then** the row is flagged as disagreeing (recorded 0, observed 1)

#### Scenario: a matching command/exit pair

**Given** a ledger row with `command: "true"` and `exit: 0`
**When** replayed (true exits 0)
**Then** no disagreement is flagged

#### Scenario: a non-runnable command text is not flagged

**Given** a ledger row with `command: "fresh-context Agent subagent review
(spec+diff only) + reproduction…"` (prose, not a runnable command)
**When** the lint checks if the command is runnable
**Then** it is not flagged as a disagreement (it is a manual row, handled by
the resolvable-artifact requirement)

### Requirement: Disagreement detection is part of ledger validation

The command/exit disagreement check SHALL be part of `ledger.sh verify` (or
a dedicated `ledger.sh lint` subcommand), not a separate tool. It runs on
every verify invocation.

**Given** a ledger with one disagreeing row
**When** `ledger.sh verify` is invoked
**Then** the disagreement is reported alongside any replay divergence, and
the exit code is non-zero

**Rationale**: A separate tool would be forgotten. Integrating into verify
makes the check a side effect of the existing replay step. (Review D4/D6.)

#### Scenario: disagreement reported by verify

**Given** a ledger with a row where `command` is runnable and the exit
disagrees
**When** `ledger.sh verify` is invoked
**Then** the output names the row as `command/exit disagreement` and the
exit code is non-zero

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Manual row with resolvable artifact | Requirement: Manual ring rows must name a resolvable report artifact + Scenario: a manual row with a resolvable artifact | bats scenario: artifact exists | `tests/judgment-ring-integrity.bats` |
| Manual row with missing artifact | Requirement: Manual ring rows must name a resolvable report artifact + Scenario: a manual row with a missing artifact | bats scenario: artifact missing | `tests/judgment-ring-integrity.bats` |
| Manual row without artifact field | Requirement: Manual ring rows must name a resolvable report artifact + Scenario: a manual row without an artifact field | bats scenario: no artifact field | `tests/judgment-ring-integrity.bats` |
| grep no-match disagreement | Requirement: Runnable commands must agree with their recorded exit + Scenario: grep no-match disagreement | bats scenario: grep exits 1, recorded 0 | `tests/judgment-ring-integrity.bats` |
| Matching command/exit not flagged | Requirement: Runnable commands must agree with their recorded exit + Scenario: a matching command/exit pair | bats scenario: true, exit 0 | `tests/judgment-ring-integrity.bats` |
| Non-runnable command not flagged | Requirement: Runnable commands must agree with their recorded exit + Scenario: a non-runnable command text is not flagged | bats scenario: prose command | `tests/judgment-ring-integrity.bats` |
| Disagreement reported by verify | Requirement: Disagreement detection is part of ledger validation + Scenario: disagreement reported by verify | bats scenario: verify flags disagreement | `tests/judgment-ring-integrity.bats` |
