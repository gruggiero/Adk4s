# Spec: Discharge Fidelity

<!-- DELTA spec for the `discharge-fidelity` capability. Fixes D1 (critical:
     discharged ignores the run's exit status) and D2 (critical: baseline =
     HEAD vs baseline = spec-start causes permanent red indictment) from the
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
| `failed` reason-code | discharge verdict | A recorded red run is visible as negative evidence, distinct from "no evidence" (undischarged) |
| `--forgive-unchanged` flag | ledger read option | A run remains discharged until the artifact it ran changes since the row's baseline |

## ADDED Requirements

### Requirement: Discharge requires a green run, not any run

Chain state SHALL only discharge an obligation when a ledger row exists for
that obligation with `.exit == 0`. A ledger row with a non-zero exit MUST NOT
discharge the obligation.

**Given** an obligation with one ledger row recording `exit: 1` (a failed run)
**When** chain state is computed
**Then** the obligation is NOT discharged, and its reason is `failed` (not
`undischarged`)

**Rationale**: The schema's own definition says "DISCHARGED — observed to run
**green**". The current code matches any row regardless of exit status,
contradicting the definition it enforces. (Review D1.)

#### Scenario: a failed run does not discharge

**Given** a spec with one obligation, and a ledger row for that obligation
with `exit: 1`
**When** chain state computes the discharge clause
**Then** the discharged count is 0, and the obligation's reason is `failed`

#### Scenario: a green run discharges

**Given** a spec with one obligation, and a ledger row for that obligation
with `exit: 0`
**When** chain state computes the discharge clause
**Then** the discharged count is 1, and the obligation's reason is `ok`

#### Scenario: both a failed and a green run exist for the same obligation

**Given** a spec with one obligation, and two ledger rows: one with `exit: 1`
and one with `exit: 0`
**When** chain state computes the discharge clause
**Then** the discharged count is 1 (the green run discharges; the failed run
is negative evidence but does not prevent discharge)

#### Scenario: only a failed run exists

**Given** a spec with one obligation, and one ledger row with `exit: 1`
**When** chain state computes the discharge clause
**Then** the discharged count is 0, and the obligation appears with reason
`failed`, not `undischarged`

### Requirement: Failed runs are distinguishable from no evidence

Chain state SHALL report a `failed` reason-code for obligations that have
ledger rows but none with `exit == 0`, distinct from `undischarged` (no rows
at all). The two cases MUST NOT be collapsed.

**Given** two obligations: one with no ledger rows, one with only a failed row
**When** chain state is computed
**Then** the first has reason `undischarged` and the second has reason `failed`

**Rationale**: A recorded red run is negative evidence — something was tried
and failed. No rows is absence of evidence. Collapsing them hides the
distinction and teaches dismissal of the counter. (Review D1.)

#### Scenario: undischarged vs failed in the same report

**Given** a spec with two obligations: obligation A has no ledger rows,
obligation B has one row with `exit: 1`
**When** chain state is computed
**Then** obligation A's reason is `undischarged` and obligation B's reason is
`failed`, and both appear in the unresolved list

### Requirement: Chain state uses per-spec baselines, not HEAD

The gate and checkpoint SHALL compute chain state using each spec's recorded
baseline SHA from `implementation-progress.md`, not `git rev-parse HEAD`.
A spec whose artifacts are unchanged since its baseline SHALL remain
discharged without re-running rings.

**Given** a change with two committed specs: spec 1 at baseline `aaa111`,
spec 2 at baseline `bbb222`, and HEAD is `ccc333`
**When** the gate computes chain state for spec 1
**Then** it uses baseline `aaa111` (from `implementation-progress.md`), not
`ccc333` (HEAD)

**Rationale**: After spec N commits, HEAD advances and every prior spec's
ledger rows read stale — the gate reports earlier specs undischarged forever
unless every ring is re-run after every commit. This teaches dismissal of the
invariant (alert fatigue). (Review D2.)

#### Scenario: a committed spec remains discharged at a later HEAD

**Given** spec 1 committed at baseline `aaa111` with all obligations
discharged, then spec 2 committed advancing HEAD to `ccc333`
**When** the gate computes chain state for spec 1 using its recorded baseline
`aaa111`
**Then** spec 1's discharged count is unchanged (its ledger rows at `aaa111`
are still valid)

#### Scenario: a spec with no recorded baseline falls back to HEAD

**Given** a spec whose `implementation-progress.md` section has no BASELINE
SHA field
**When** the gate computes chain state
**Then** it falls back to `git rev-parse HEAD` and records this fallback in
the trace output

### Requirement: Unchanged artifacts forgive stale evidence

`ledger.sh read` SHALL accept a `--forgive-unchanged` flag that, when set,
treats a ledger row as valid if the artifact it ran has not changed since the
row's baseline, regardless of whether the current HEAD matches the row's
baseline.

**Given** a ledger row at baseline `aaa111` for artifact `scanner/ledger.sh`,
and HEAD is `ccc333`, and `git diff --quiet aaa111 ccc333 -- scanner/ledger.sh`
succeeds (artifact unchanged)
**When** `ledger.sh read --forgive-unchanged` is called
**Then** the row is included in the output (forgiven, not stale)

**Rationale**: Semantically, evidence is stale only if the artifact it tested
has changed. A run remains valid until the code it ran differs. (Review D2,
fix option 2.)

#### Scenario: unchanged artifact is forgiven

**Given** a ledger row at baseline `aaa111` for artifact `scanner/ledger.sh`,
HEAD is `ccc333`, and `scanner/ledger.sh` is unchanged between `aaa111` and
`ccc333`
**When** `ledger.sh read --forgive-unchanged --baseline aaa111` is called
**Then** the row appears in the output

#### Scenario: changed artifact is NOT forgiven

**Given** a ledger row at baseline `aaa111` for artifact `scanner/ledger.sh`,
HEAD is `ccc333`, and `scanner/ledger.sh` was modified between `aaa111` and
`ccc333`
**When** `ledger.sh read --forgive-unchanged --baseline aaa111` is called
**Then** the row does NOT appear in the output (the evidence is stale because
the artifact changed)

#### Scenario: --forgive-unchanged absent preserves current behavior

**Given** a ledger row at baseline `aaa111` and HEAD is `ccc333`
**When** `ledger.sh read --baseline aaa111` is called (without
`--forgive-unchanged`)
**Then** the row is filtered out (current staleness behavior unchanged)

### Requirement: Gate and checkpoint share one forgiveness discipline

The gate (`hooks/gate.sh`) and checkpoint (`scanner/checkpoint.sh`) SHALL use
the same `--forgive-unchanged` discipline when reading the ledger, so that a
spec discharged in the gate is also discharged in the checkpoint, and vice
versa.

**Given** a spec with forgiven (unchanged-artifact) evidence
**When** both the gate and checkpoint compute chain state
**Then** both report the spec as discharged

**Rationale**: Two consumers of the ledger with different staleness rules
produce contradictory verdicts — the gate says discharged, the checkpoint
says undischarged. (Review D2, fix option 3.)

#### Scenario: gate and checkpoint agree on forgiven evidence

**Given** a spec with a ledger row at an old baseline for an unchanged
artifact
**When** the gate computes chain state and the checkpoint generates ring
results
**Then** both report the obligation as discharged

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Discharge requires `.exit == 0` | Requirement: Discharge requires a green run, not any run + Scenario: a failed run does not discharge | bats scenario: failed run does not discharge | `tests/discharge-fidelity.bats` |
| Green run discharges | Requirement: Discharge requires a green run, not any run + Scenario: a green run discharges | bats scenario: green run discharges | `tests/discharge-fidelity.bats` |
| Both failed and green: green wins | Requirement: Discharge requires a green run, not any run + Scenario: both a failed and a green run exist for the same obligation | bats scenario: mixed exits, discharged | `tests/discharge-fidelity.bats` |
| Only a failed run exists | Requirement: Discharge requires a green run, not any run + Scenario: only a failed run exists | bats scenario: only failed, reason `failed` | `tests/discharge-fidelity.bats` |
| `failed` reason-code distinct from `undischarged` | Requirement: Failed runs are distinguishable from no evidence + Scenario: undischarged vs failed in the same report | bats scenario: two obligations, two reasons | `tests/discharge-fidelity.bats` |
| Per-spec baselines from implementation-progress.md | Requirement: Chain state uses per-spec baselines, not HEAD + Scenario: a committed spec remains discharged at a later HEAD | bats scenario: two specs, two baselines | `tests/discharge-fidelity.bats` |
| Fallback to HEAD when no baseline recorded | Requirement: Chain state uses per-spec baselines, not HEAD + Scenario: a spec with no recorded baseline falls back to HEAD | bats scenario: missing BASELINE SHA | `tests/discharge-fidelity.bats` |
| `--forgive-unchanged` forgives unchanged artifacts | Requirement: Unchanged artifacts forgive stale evidence + Scenario: unchanged artifact is forgiven | bats scenario: unchanged artifact forgiven | `tests/discharge-fidelity.bats` |
| `--forgive-unchanged` does not forgive changed artifacts | Requirement: Unchanged artifacts forgive stale evidence + Scenario: changed artifact is NOT forgiven | bats scenario: changed artifact filtered | `tests/discharge-fidelity.bats` |
| `--forgive-unchanged` absent preserves current behavior | Requirement: Unchanged artifacts forgive stale evidence + Scenario: --forgive-unchanged absent preserves current behavior | bats scenario: no flag, row filtered | `tests/discharge-fidelity.bats` |
| Gate and checkpoint share forgiveness discipline | Requirement: Gate and checkpoint share one forgiveness discipline + Scenario: gate and checkpoint agree on forgiven evidence | bats scenario: both agree | `tests/discharge-fidelity.bats` |
