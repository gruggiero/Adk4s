# Spec: Evidence Capture

<!-- DELTA spec for the `evidence-capture` capability. Fixes D3 (high:
     ledger's exit field is agent-supplied) and D6 (medium: re-checkability
     promised, never mechanized) from the 2026-08-12 substratum review. -->

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
| `ledger.sh run` | bash subcommand (capture) | Script executes the command, records its own observed `$?`, stdout/stderr digest, wall time |
| `ledger.sh verify` | bash subcommand (replay) | Re-executes each row's command, compares exits, reports match/diverge/manual |
| `sha256` field | ledger record field | Artifact content hash at record time |
| `digest` field | ledger record field | stdout/stderr SHA-256 digest at record time |
| `wallTime` field | ledger record field | Wall-clock duration of the command execution |

## ADDED Requirements

### Requirement: Capture mode observes the exit code

`ledger.sh run` SHALL execute the given command itself, record the exit code
it observes (`$?`), and MUST NOT accept an `--exit` argument. The agent does
not supply the exit code; the script does.

**Given** a command `bats tests/evidence-ledger.bats` that exits 0
**When** `ledger.sh run --file F --change C --spec S --ring R --obligation O
--artifact A --baseline SHA -- bats tests/evidence-ledger.bats` is invoked
**Then** the ledger row records `exit: 0` (observed by the script, not
asserted by the agent)

**Rationale**: The current `append` mode accepts `--exit N` from the agent —
the one field fabrication needs. Capture mode converts "agent asserts an exit"
into "script observes an exit" at no philosophical cost. (Review D3.)

#### Scenario: a passing command records exit 0

**Given** `ledger.sh run` invoked with a command that exits 0
**When** the command completes
**Then** the ledger row has `exit: 0` and the row is valid

#### Scenario: a failing command records the real exit code

**Given** `ledger.sh run` invoked with a command that exits 1
**When** the command completes
**Then** the ledger row has `exit: 1` (the script records the failure, does
not suppress it)

#### Scenario: --exit is not accepted in run mode

**Given** `ledger.sh run` invoked with `--exit 0`
**When** the script parses arguments
**Then** it errors with "run mode does not accept --exit; the script observes
the exit code"

### Requirement: Capture records artifact content hash

`ledger.sh run` SHALL compute and record the SHA-256 hash of the artifact
file at record time, in a `sha256` field. This makes "the artifact that ran
green is the artifact present" checkable without re-running.

**Given** `ledger.sh run --artifact scanner/ledger.sh` invoked
**When** the row is written
**Then** the row contains a `sha256` field with the SHA-256 of
`scanner/ledger.sh` at that moment

**Rationale**: Without a content hash, a row says "the artifact ran green" but
cannot verify that the artifact present now is the one that ran. (Review D3.)

#### Scenario: sha256 is recorded

**Given** `ledger.sh run --artifact scanner/ledger.sh` invoked
**When** the row is written
**Then** the row's `sha256` field matches `sha256sum scanner/ledger.sh`

#### Scenario: sha256 is absent in legacy append mode

**Given** `ledger.sh append` invoked (legacy mode)
**When** the row is written
**Then** the row has no `sha256` field (backward compat: old rows lack it)

### Requirement: Capture records stdout/stderr digest and wall time

`ledger.sh run` SHALL record a `digest` field (SHA-256 of the command's
combined stdout+stderr) and a `wallTime` field (milliseconds elapsed), for
replay diagnostics.

**Given** `ledger.sh run` invoked with a command that produces output
**When** the command completes
**Then** the row contains `digest` and `wallTime` fields

**Rationale**: On replay divergence, the digest tells whether the output
changed; the wall time tells whether the environment degraded. (Review D3/D6.)

#### Scenario: digest and wallTime are recorded

**Given** `ledger.sh run` invoked with `echo hello`
**When** the command completes
**Then** the row's `digest` field is the SHA-256 of "hello\n" and `wallTime`
is a non-negative integer

### Requirement: Verify replays recorded commands

`ledger.sh verify` SHALL re-execute each row's `command` field, compare the
observed exit to the recorded exit, and report per-row: `replay matches`,
`replay diverges`, or `manual/unreplayable`.

**Given** a ledger with a row recording `command: "bats tests/x.bats"` and
`exit: 0`
**When** `ledger.sh verify --file F --change C` is invoked
**Then** the script re-executes `bats tests/x.bats`, compares the new exit to
0, and reports `replay matches` or `replay diverges`

**Rationale**: "A row records the command and its exit, so it is re-checkable
by re-running it" — nothing currently runs it. (Review D6.)

#### Scenario: a matching replay

**Given** a ledger row with `command: "true"` and `exit: 0`
**When** `ledger.sh verify` re-executes `true`
**Then** it reports `replay matches` for that row

#### Scenario: a diverging replay

**Given** a ledger row with `command: "false"` and `exit: 0` (the recorded
exit is wrong — `false` exits 1)
**When** `ledger.sh verify` re-executes `false`
**Then** it reports `replay diverges` (observed 1, recorded 0) and exits
non-zero

#### Scenario: a manual row is explicitly named

**Given** a ledger row with `ring: "manual"` and a non-runnable command text
**When** `ledger.sh verify` processes that row
**Then** it reports `manual/unreplayable` (never silently skipped)

### Requirement: Verify exits non-zero on any divergence

`ledger.sh verify` SHALL exit 0 only if every replayable row matches. Any
divergence MUST produce a non-zero exit.

**Given** a ledger with 5 rows: 4 match, 1 diverges
**When** `ledger.sh verify` is invoked
**Then** it exits non-zero and the diverging row is named in the output

**Rationale**: A verify run that silently passes on divergence is worse than
no verify — it certifies evidence as checked when it wasn't. (Review D6.)

#### Scenario: all match exits 0

**Given** a ledger with 3 rows, all replaying with matching exits
**When** `ledger.sh verify` is invoked
**Then** it exits 0

#### Scenario: one diverge exits non-zero

**Given** a ledger with 3 rows, one diverging
**When** `ledger.sh verify` is invoked
**Then** it exits non-zero and names the diverging row

### Requirement: New ledger fields are backward-compatible

The new fields (`sha256`, `digest`, `wallTime`) MUST NOT break existing
ledger readers (`chain-state.sh`, `checkpoint.sh`, `gate.sh`). Rows without
these fields (written by legacy `append` mode) MUST still be readable.

**Given** an existing ledger file with rows written by `append` mode (no
`sha256`/`digest`/`wallTime` fields)
**When** `chain-state.sh` reads the ledger
**Then** the rows are processed normally (missing fields are optional, not
errors)

**Rationale**: The archived `evidence-ledger.jsonl` and any in-flight ledgers
lack the new fields. Breaking them would make the fix worse than the disease.

#### Scenario: legacy rows are readable

**Given** a ledger file with rows from `append` mode (no new fields)
**When** `ledger.sh read` is called
**Then** the rows are returned with exit 0

#### Scenario: mixed legacy and capture rows

**Given** a ledger file with some rows from `append` and some from `run`
**When** `ledger.sh read` is called
**Then** both sets of rows are returned; the reader does not error on the
heterogeneous file

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| run mode observes exit code | Requirement: Capture mode observes the exit code + Scenario: a passing command records exit 0 | bats scenario: passing command records 0 | `tests/evidence-capture.bats` |
| run mode records failing exit | Requirement: Capture mode observes the exit code + Scenario: a failing command records the real exit code | bats scenario: failing command records 1 | `tests/evidence-capture.bats` |
| run mode rejects --exit | Requirement: Capture mode observes the exit code + Scenario: --exit is not accepted in run mode | bats scenario: --exit errors | `tests/evidence-capture.bats` |
| sha256 recorded | Requirement: Capture records artifact content hash + Scenario: sha256 is recorded | bats scenario: sha256 matches sha256sum | `tests/evidence-capture.bats` |
| sha256 absent in append | Requirement: Capture records artifact content hash + Scenario: sha256 is absent in legacy append mode | bats scenario: append row has no sha256 | `tests/evidence-capture.bats` |
| digest and wallTime recorded | Requirement: Capture records stdout/stderr digest and wall time + Scenario: digest and wallTime are recorded | bats scenario: echo hello | `tests/evidence-capture.bats` |
| verify matches | Requirement: Verify replays recorded commands + Scenario: a matching replay | bats scenario: true, exit 0 | `tests/evidence-capture.bats` |
| verify diverges | Requirement: Verify replays recorded commands + Scenario: a diverging replay | bats scenario: false, exit 0 recorded | `tests/evidence-capture.bats` |
| verify names manual rows | Requirement: Verify replays recorded commands + Scenario: a manual row is explicitly named | bats scenario: manual row named | `tests/evidence-capture.bats` |
| verify exits non-zero on divergence | Requirement: Verify exits non-zero on any divergence + Scenario: one diverge exits non-zero | bats scenario: one diverge | `tests/evidence-capture.bats` |
| verify exits 0 when all match | Requirement: Verify exits non-zero on any divergence + Scenario: all match exits 0 | bats scenario: all match | `tests/evidence-capture.bats` |
| legacy rows readable | Requirement: New ledger fields are backward-compatible + Scenario: legacy rows are readable | bats scenario: append rows read OK | `tests/evidence-capture.bats` |
| mixed legacy and capture rows | Requirement: New ledger fields are backward-compatible + Scenario: mixed legacy and capture rows | bats scenario: heterogeneous file | `tests/evidence-capture.bats` |
