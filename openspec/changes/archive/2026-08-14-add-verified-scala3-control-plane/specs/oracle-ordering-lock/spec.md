# Spec: Oracle Ordering Lock

<!-- DELTA spec for the `oracle-ordering-lock` capability. The MANDATORY first
     spec of the control-plane change: a pre-execution tool gate makes
     oracle inversion (tests written after implementation, or tests that
     never ran red) impossible-at-write-time rather than detectable-at-
     checkpoint. Enforces the *temporal structure* that defines the oracle;
     test *content* remains Ring 8's judgment domain. -->

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
| `gate.sh --event tool-call` | new bash event | Pre-execution gate: consults oracle phase state, emits a block decision before a `write`/`edit` to a production source path runs |
| oracle phase state | new git-dir state machine | `phase-<change>-<spec>` file: `oracle` → `implementation` → `verified`; transitions only via recorded events |
| oracle-polarity predicate | new bash function | Pure function of ledger rows ⊕ `git merge-base --is-ancestor`: did test-execution evidence exist before any implementation file? |

## Implementation Anchors

The scenarios reference representative production and test source paths
illustratively. The normative rule is path-shape based (`*/src/main/**` is a
production edit; `*/src/test/**` is a test edit; `openspec/changes/**` is a
change artifact; `openspec/schemas/verified-scala3/**` is workflow tooling),
not tied to any specific module. Representative anchors used in scenarios:

- production source: `<module>/src/main/scala/**/*.scala` (e.g.
  `adk4s-core/src/main/scala/org/adk4s/core/X.scala`)
- test source: `<module>/src/test/scala/**/*.scala`
- change artifact: `openspec/changes/<change>/**`
- workflow tooling: `openspec/schemas/verified-scala3/**/*.sh`
- baseline recording: `git rev-parse HEAD` written to
  `openspec/changes/<change>/implementation-progress.md`
- RED/GREEN evidence: `ledger.sh run -- … -- sbt <module>/test` rows

## ADDED Requirements

### Requirement: Implementation edits require the oracle phase to have advanced

The pre-execution gate SHALL refuse any `write` or `edit` tool call whose
target path matches `*/src/main/**` while the (change, spec) phase state is
`oracle`. The gate MUST NOT refuse edits to test sources
(`*/src/test/**`), to change artifacts under `openspec/changes/`, or to the
workflow's own tooling — those edits are how the agent produces the evidence
that unblocks the implementation phase.

**Given** a change `C` with spec `S` whose phase state file reads `oracle`
**When** a `write`/`edit` tool call targets `adk4s-core/src/main/scala/X.scala`
**Then** the gate emits `{"decision":"block","reason":…}` (hook-json) or
exits 2 (text), and the tool call does not run; the reason names the spec,
the phase, and the missing RED evidence

**Rationale**: The failure report's first drift pattern is oracle inversion
— tests written after or alongside implementation, with "ORACLE POLARITY
run — all tests pass" written post-hoc. A pre-execution block on the
implementation edit makes the inversion impossible-at-write-time on every
harness that has a pre-execution tool block (Claude Code, Devin, pi — the
only point all three share). The agent is never blocked from producing the
unblocking evidence: oracle edits and `ledger.sh run` are not refused.

#### Scenario: implementation edit blocked while phase is oracle

**Given** phase state for (C, S) reads `oracle`
**When** the gate receives a `write` tool call targeting
`adk4s-core/src/main/scala/org/adk4s/core/X.scala`
**Then** the gate emits a block decision with a reason string containing the
spec name, the word `oracle`, and a pointer to `ledger.sh run`; the tool
call does not execute

#### Scenario: test edit allowed while phase is oracle

**Given** phase state for (C, S) reads `oracle`
**When** the gate receives a `write` tool call targeting
`adk4s-core/src/test/scala/org/adk4s/core/XSpec.scala`
**Then** the gate emits no block (allow) and the tool call proceeds

#### Scenario: change-artifact edit allowed while phase is oracle

**Given** phase state for (C, S) reads `oracle`
**When** the gate receives an `edit` tool call targeting
`openspec/changes/C/implementation-progress.md`
**Then** the gate emits no block (allow) and the tool call proceeds

#### Scenario: workflow-tooling edit allowed while phase is oracle

**Given** phase state for (C, S) reads `oracle`
**When** the gate receives an `edit` tool call targeting
`openspec/schemas/verified-scala3/hooks/gate.sh`
**Then** the gate emits no block (allow) and the tool call proceeds

### Requirement: The oracle phase advances only on a recorded RED run

Phase state SHALL transition `oracle → implementation` only when a
`ledger.sh run` row exists for spec `S` that records the spec's test command
with `.exit != 0` (a RED run) at a baseline that is an ancestor of the
current HEAD. The phase MUST NOT advance on agent prose, on a green run,
or on the absence of a row.

**Given** phase state for (C, S) reads `oracle`
**When** the gate evaluates the transition and a `ledger.sh run` row exists
for (C, S) with `.exit: 1` at baseline `B`, and `git merge-base --is-ancestor
B HEAD` succeeds
**Then** the phase transitions to `implementation` and the next
implementation edit is allowed

**Rationale**: The oracle's definition is that test-execution evidence
strictly precedes implementation existence. The RED run is the proof the
oracle *can* fail before trusting it (v12: "prove the oracle CAN fail"). A
green run does not prove the oracle can fail; a missing row proves nothing.
`ledger.sh run` (D3, shipped) observes the exit code itself, so a RED row is
unforgeable-in-flow: it can only be produced by actually running the suite
against not-yet-implemented code. The `sha256` field binds the row to the
exact test files that ran.

#### Scenario: a recorded RED run advances the phase

**Given** phase state for (C, S) reads `oracle`, and a `ledger.sh run` row
exists for (C, S) with `.exit: 1` at baseline `B`, an ancestor of HEAD
**When** the gate evaluates the phase transition before an implementation
edit
**Then** the phase transitions to `implementation` and the implementation
edit is allowed

#### Scenario: a green run does not advance the phase

**Given** phase state for (C, S) reads `oracle`, and the only ledger row for
(C, S) is a `run` with `.exit: 0`
**When** the gate evaluates the phase transition before an implementation
edit
**Then** the phase remains `oracle` and the implementation edit is blocked

#### Scenario: a row at a non-ancestor baseline does not advance the phase

**Given** phase state for (C, S) reads `oracle`, and a `ledger.sh run` row
exists for (C, S) with `.exit: 1` at baseline `B`, where `B` is NOT an
ancestor of HEAD
**When** the gate evaluates the phase transition
**Then** the phase remains `oracle` (the row is stale and does not count)

#### Scenario: no row does not advance the phase

**Given** phase state for (C, S) reads `oracle`, and no ledger row exists
for (C, S)
**When** the gate evaluates the phase transition before an implementation
edit
**Then** the phase remains `oracle` and the implementation edit is blocked

### Requirement: The verified phase requires a GREEN run after implementation

Phase state SHALL transition `implementation → verified` only when a
`ledger.sh run` row exists for spec `S` that records the spec's test command
with `.exit == 0` (a GREEN run) at a baseline that is an ancestor of HEAD and
that is a descendant of the baseline of the RED run that advanced the phase.

**Given** phase state for (C, S) reads `implementation`, and a RED run row
exists at baseline `B_red` (ancestor of HEAD), and a GREEN run row exists at
baseline `B_green` with `git merge-base --is-ancestor B_red B_green` succeeding
**When** the gate evaluates the transition
**Then** the phase transitions to `verified`

**Rationale**: The green run is the implementation evidence (v12: red →
green is the implementation evidence). Requiring it to descend from the RED
run's baseline ties the two phases together: the same oracle that failed
before now passes, against code that now exists.

#### Scenario: a green run after a red run verifies the phase

**Given** phase state for (C, S) reads `implementation`, a RED run row at
`B_red` (ancestor of HEAD), and a GREEN run row at `B_green` where
`B_red` is an ancestor of `B_green`
**When** the gate evaluates the transition
**Then** the phase transitions to `verified`

#### Scenario: a green run without a prior red run does not verify

**Given** phase state for (C, S) reads `implementation` and a GREEN run row
exists but no RED run row exists
**When** the gate evaluates the transition
**Then** the phase remains `implementation` (the oracle was never proven to
fail; this is the inversion the gate exists to prevent)

### Requirement: The gate fails open when state is unavailable

The `tool-call` gate SHALL allow the tool call and write a trace line when
the git-dir state directory cannot be established (no `.git`, unwritable
git-dir, disk full), rather than block without a safety net. A blocking
decision MAY only be made when the mechanism that bounds it (the state dir)
is actually available.

**Given** a repository where `git rev-parse --absolute-git-dir` fails or the
state directory cannot be created
**When** the gate evaluates an implementation edit while the (in-memory)
phase would be `oracle`
**Then** the gate allows the tool call and writes a trace line naming the
reason ("bounded-refusal state unavailable, failing open")

**Rationale**: The substratum review §6 found that the completion gate's
bounded-refusal guarantee was implemented entirely via a state file; if
STATE_DIR could not be established, the "already refused" check could never
be true and every completion attempt refused with no bound — the exact
"blocking hook that misfires and cannot be escaped" failure. The same
discipline applies to the pre-execution tier: a block without a bound is
not safe.

#### Scenario: no state dir means fail open

**Given** a repository whose git-dir is unwritable
**When** the gate evaluates an implementation edit
**Then** the gate emits no block and writes a trace line naming the reason

### Requirement: The VERIFIED_SCALA3_HOOKS escape hatch applies to the tool-call tier

Setting `VERIFIED_SCALA3_HOOKS=off` SHALL disable the `tool-call` gate
exactly as it disables the other tiers, emitting a trace line and allowing
the call.

**Given** `VERIFIED_SCALA3_HOOKS=off` is set in the gate process's environment
**When** the gate evaluates an implementation edit while the phase is `oracle`
**Then** the gate allows the call and writes a trace line naming the escape

**Rationale**: The escape hatch is the schema's release valve for a
misfiring hook. It must apply uniformly or the new tier is a regression in
operability.

#### Scenario: the escape hatch disables the tool-call block

**Given** `VERIFIED_SCALA3_HOOKS=off` and phase state `oracle`
**When** the gate evaluates an implementation edit
**Then** the gate allows the call and traces the skip

## Properties (Ring 3)

There are no Hedgehog properties in this spec. All verification is via bats
scenario tests with fixed fixtures (the workflow's own convention for
bash/jq tooling, established by the archived
`fix-verified-scala3-substratum-review` change). The config.yaml rule
requiring ≥2 Hedgehog properties applies to Scala code-changing specs; this
is a workflow-self-change with no Scala code, and bats scenarios are the
detected verification mechanism (capability-check Ring 3 row: "yes (bats)").

Property-equivalent invariant expressed as a bats property: **for every
( phase-state, edit-target, ledger-rows, baseline-ancestry ) tuple the gate
is queried with, the block decision is a pure function of those inputs and
never depends on agent prose.** Asserted by enumerating the decision table
in `tests/oracle-ordering-lock.bats` — enumerate, don't claim sampled.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Implementation edit blocked in oracle phase | Requirement: Implementation edits require the oracle phase to have advanced + Scenario: implementation edit blocked while phase is oracle | bats scenario: implementation edit blocked | `tests/oracle-ordering-lock.bats` |
| Test edit allowed in oracle phase | Requirement: Implementation edits require the oracle phase to have advanced + Scenario: test edit allowed while phase is oracle | bats scenario: test edit allowed | `tests/oracle-ordering-lock.bats` |
| Change-artifact edit allowed in oracle phase | Requirement: Implementation edits require the oracle phase to have advanced + Scenario: change-artifact edit allowed while phase is oracle | bats scenario: artifact edit allowed | `tests/oracle-ordering-lock.bats` |
| Workflow-tooling edit allowed in oracle phase | Requirement: Implementation edits require the oracle phase to have advanced + Scenario: workflow-tooling edit allowed while phase is oracle | bats scenario: tooling edit allowed | `tests/oracle-ordering-lock.bats` |
| Phase advances only on a recorded RED run | Requirement: The oracle phase advances only on a recorded RED run + Scenario: a recorded RED run advances the phase | bats scenario: red run advances | `tests/oracle-ordering-lock.bats` |
| Green run does not advance the phase | Requirement: The oracle phase advances only on a recorded RED run + Scenario: a green run does not advance the phase | bats scenario: green run does not advance | `tests/oracle-ordering-lock.bats` |
| Non-ancestor baseline row does not advance | Requirement: The oracle phase advances only on a recorded RED run + Scenario: a row at a non-ancestor baseline does not advance the phase | bats scenario: stale baseline rejected | `tests/oracle-ordering-lock.bats` |
| No row does not advance | Requirement: The oracle phase advances only on a recorded RED run + Scenario: no row does not advance the phase | bats scenario: no row rejected | `tests/oracle-ordering-lock.bats` |
| Verified requires green after red | Requirement: The verified phase requires a GREEN run after implementation + Scenario: a green run after a red run verifies the phase | bats scenario: green after red verifies | `tests/oracle-ordering-lock.bats` |
| Green without red does not verify | Requirement: The verified phase requires a GREEN run after implementation + Scenario: a green run without a prior red run does not verify | bats scenario: green without red rejected | `tests/oracle-ordering-lock.bats` |
| Fail open when state unavailable | Requirement: The gate fails open when state is unavailable + Scenario: no state dir means fail open | bats scenario: fail open no state dir | `tests/oracle-ordering-lock.bats` |
| Escape hatch disables the block | Requirement: The VERIFIED_SCALA3_HOOKS escape hatch applies to the tool-call tier + Scenario: the escape hatch disables the tool-call block | bats scenario: escape hatch allows | `tests/oracle-ordering-lock.bats` |
