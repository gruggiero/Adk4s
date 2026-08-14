# Spec: Ambient Evidence Capture

<!-- DELTA spec for the `ambient-evidence-capture` capability. The failure
     report's branch has NO evidence ledger at all — chain state is
     "undetermined" because the agent treated recording as optional
     bookkeeping. `ledger.sh run` (D3, shipped) solves this for rings the
     apply phase drives explicitly; this spec closes the last gap: bash
     commands the agent runs outside `ledger.sh run` never produce rows.
     The observation channel each harness already has (PostToolUse /
     tool_result on Bash) appends rows with the harness-observed exit,
     so the absence of rows becomes machine-visible. -->

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
| `gate.sh --event post-bash` | new bash event | Post-execution observation: matches a bash command against known ring shapes and appends a ledger row with the harness-observed exit code |
| ring-shape match table | new enumerated list | The commands the apply instruction names (`sbt .*test`, `danger-scan.sh`, `registry-check.sh`, `spec-lint.sh`, `checkpoint.sh report`, `ledger.sh run`) — conservative match, non-matching commands are simply not recorded |

## ADDED Requirements

### Requirement: Ring-shaped bash commands produce a ledger row automatically

The `post-bash` gate SHALL append a `ledger.sh` row when a `bash` tool
call completes and its command matches a known ring shape, recording the
command, the harness-observed exit code, the change, the spec (when
derivable), and the baseline. The row MUST use the existing ledger JSONL
format (no schema change to the ledger record).

**Given** a `bash` tool call ran `sbt adk4s-core/test` and exited 0
**When** the `post-bash` gate runs
**Then** a ledger row is appended with `command: "sbt adk4s-core/test"`,
`exit: 0`, and the change derived from the active change directory

**Rationale**: The failure report's branch has an undetermined chain state
because no ledger rows exist. `ledger.sh run` requires the agent to remember
to use it; ambient capture makes recording a side effect of the
observation channel each harness already fires on tool completion. The
absence of rows then becomes the machine-visible signal that triggers the
completion gate's block (Claude/Devin) and poisons every injected context
(pi). The `sha256` field (D3) binds the row to the artifact that ran.

#### Scenario: a matching test command records a green row

**Given** a `bash` tool call ran `sbt adk4s-core/test` and exited 0
**When** the `post-bash` gate runs
**Then** a ledger row is appended with `exit: 0` and `command` matching the
invocation

#### Scenario: a matching test command records a red row

**Given** a `bash` tool call ran `sbt adk4s-core/test` and exited 1
**When** the `post-bash` gate runs
**Then** a ledger row is appended with `exit: 1` (negative evidence, per
the D1 fix — red runs are visible, not silently discharge-blocking)

#### Scenario: a non-matching command records nothing

**Given** a `bash` tool call ran `ls -la` (not a ring shape)
**When** the `post-bash` gate runs
**Then** no ledger row is appended (the match is conservative; a
non-matching command is simply not recorded, never a false block)

#### Scenario: an explicit ledger.sh run is not double-recorded

**Given** a `bash` tool call ran `ledger.sh run -- …` (the agent already
drove capture explicitly)
**When** the `post-bash` gate runs
**Then** the gate does not append a second row for the same command
(de-duplicated by command + observed exit + turn)

### Requirement: The match table is enumerated and conservative

The ring-shape match table SHALL be an enumerated list of the commands the
apply instruction names, matched conservatively (a command that does not
match is not recorded). The table MUST NOT use a broad pattern that matches
arbitrary `sbt` or `bash` invocations.

**Given** the match table contains `sbt .*test` and a command `sbt
adk4s-core/compile` runs
**When** the `post-bash` gate evaluates the command
**Then** no row is appended (`compile` does not match `.*test`)

**Rationale**: A broad match would record noise and inflate the ledger
with non-evidence; a conservative match records only the commands the
workflow itself designates as ring executions. The agent can still
`ledger.sh run` explicitly for any command the shape matcher misses —
ambient capture is a superset, not a replacement.

#### Scenario: sbt compile is not a ring shape

**Given** the match table contains `sbt .*test` and `sbt adk4s-core/compile`
runs
**When** the `post-bash` gate evaluates the command
**Then** no row is appended

#### Scenario: a danger-scan invocation is a ring shape

**Given** the match table contains `danger-scan.sh` and a command invoking
`openspec/schemas/verified-scala3/scanner/danger-scan.sh <sha>` runs
**When** the `post-bash` gate evaluates the command
**Then** a row is appended for the danger-scan ring

### Requirement: The ledger format is unchanged

The rows appended by ambient capture SHALL conform to the existing
`ledger-record-contract.jq` (no new required fields, no removed fields).
Existing ledger readers (`chain-state.sh`, `checkpoint.sh`, `gate.sh`)
MUST decode ambient rows unchanged.

**Given** an ambient-captured row appended with `command`, `exit`,
`change`, `spec` (when derivable), `baseline`, and the D3 `sha256` field
**When** `chain-state.sh` reads the ledger
**Then** the row is decoded as an ordinary ledger row (no special-casing)

**Rationale**: Ambient capture is a new *producer* of rows, not a new
*format* of rows. The D1–D8 fixes already made the format sound; this spec
reuses it. Ring 4 backward-compat: existing `evidence-ledger.jsonl` fixtures
decode unchanged, and ambient rows decode with existing readers.

#### Scenario: an ambient row decodes with existing readers

**Given** an ambient-captured row in the ledger
**When** `chain-state.sh` and `checkpoint.sh` read the ledger
**Then** both decode the row without error and attribute it to the
recorded change/spec

#### Scenario: a legacy fixture decodes unchanged

**Given** an archived `evidence-ledger.jsonl` fixture from before ambient
capture
**When** the current `chain-state.sh` reads it
**Then** the fixture decodes unchanged (no format regression)

### Requirement: The post-bash gate never blocks

The `post-bash` gate SHALL always exit 0 and never emit a block decision.
It is an observation tier, not an enforcement tier — the bash command has
already run by the time it fires, so there is nothing to strand.

**Given** a `bash` tool call has completed
**When** the `post-bash` gate runs
**Then** the gate exits 0 regardless of the command's exit code or whether
a row was appended

**Rationale**: The README's tier discipline: post-edit/post-bash can't
strand (post-hoc); only pre-execution (`tool-call`) and completion can
block. A post-bash block would be the "blocking hook that misfires"
failure the schema avoids.

#### Scenario: a failed command does not block the post-bash gate

**Given** a `bash` tool call exited 1
**When** the `post-bash` gate runs
**Then** the gate exits 0 (it records the red row but does not block)

## Properties (Ring 3)

No Hedgehog properties. All verification via bats scenario tests.
Property-equivalent invariant: **the set of commands that produce an
ambient row is exactly the enumerated match table, and a post-bash gate
never emits a block decision.** Asserted by enumerating the match table
and the block-decision output in `tests/ambient-evidence-capture.bats`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Matching test command records a green row | Requirement: Ring-shaped bash commands produce a ledger row automatically + Scenario: a matching test command records a green row | bats scenario: green row recorded | `tests/ambient-evidence-capture.bats` |
| Matching test command records a red row | Requirement: Ring-shaped bash commands produce a ledger row automatically + Scenario: a matching test command records a red row | bats scenario: red row recorded | `tests/ambient-evidence-capture.bats` |
| Non-matching command records nothing | Requirement: Ring-shaped bash commands produce a ledger row automatically + Scenario: a non-matching command records nothing | bats scenario: non-matching no row | `tests/ambient-evidence-capture.bats` |
| Explicit ledger.sh run not double-recorded | Requirement: Ring-shaped bash commands produce a ledger row automatically + Scenario: an explicit ledger.sh run is not double-recorded | bats scenario: no double record | `tests/ambient-evidence-capture.bats` |
| sbt compile not a ring shape | Requirement: The match table is enumerated and conservative + Scenario: sbt compile is not a ring shape | bats scenario: compile not recorded | `tests/ambient-evidence-capture.bats` |
| danger-scan is a ring shape | Requirement: The match table is enumerated and conservative + Scenario: a danger-scan invocation is a ring shape | bats scenario: danger-scan recorded | `tests/ambient-evidence-capture.bats` |
| Ambient row decodes with existing readers | Requirement: The ledger format is unchanged + Scenario: an ambient row decodes with existing readers | bats scenario: existing readers decode | `tests/ambient-evidence-capture.bats` |
| Legacy fixture decodes unchanged | Requirement: The ledger format is unchanged + Scenario: a legacy fixture decodes unchanged | bats scenario: legacy fixture compat | `tests/ambient-evidence-capture.bats` |
| post-bash never blocks | Requirement: The post-bash gate never blocks + Scenario: a failed command does not block the post-bash gate | bats scenario: failed command no block | `tests/ambient-evidence-capture.bats` |
