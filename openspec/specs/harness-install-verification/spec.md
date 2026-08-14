# Spec: Harness Install Verification

<!-- DELTA spec for the `harness-install-verification` capability. The
     failing Devin session's first mechanical question — "were the hooks
     even installed and firing?" — was unanswered. `gate.sh
     --check-installed` exists for exactly this, and the Devin adapter's
     Stop blocking behavior is documented but never observed (per the
     hooks README). This spec makes installation verification an
     apply-Step-0 requirement, executes the README's three-level
     verification for Devin, and wires the new `tool-call` event into all
     three adapter configs — the wiring that makes specs 1–2 real on every
     harness. -->

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
| apply-Step-0 `--check-installed` requirement | new schema instruction | The hooks README's three-level verification (script works → harness invokes it → model received it) becomes a mandatory apply-Step-0 check, recorded in the checkpoint |
| `PreToolUse` / `tool_call` adapter wiring | new adapter entries | The `tool-call` event added to claude.settings.json, devin.hooks.v1.json, and the pi adapter so specs 1–2 enforce on every harness |
| schema v13 changelog entry | new schema.yaml entry | Records this change's defect class: prose-only control plane; Tier A promoted on 2/3 harnesses while the universal (pre-execution) tier shipped nothing |

## ADDED Requirements

### Requirement: Apply Step 0 verifies the gate is installed and firing

The apply phase's Step 0 SHALL run `gate.sh --check-installed --repo .` and
record the result (installed: true/false, last_run, event) in the spec's
checkpoint. If the result is `installed: false`, Step 0 SHALL stop and
direct the agent to install the hooks (`install-hooks.sh --apply`) before
proceeding.

**Given** an apply phase begins Step 0 for a spec
**When** `gate.sh --check-installed --repo .` runs
**Then** the result is recorded in the checkpoint; if `installed: false`,
the apply phase stops before recording a baseline

**Rationale**: The failure report's session ran in Devin, and nothing in
the record shows whether the gate was installed or firing. The heartbeat
`--check-installed` reads exists for exactly this: it is written
unconditionally on every invocation, including silent ones, so `installed:
false` after a session has definitely started means the config never
reached the harness. Making it an apply-Step-0 requirement turns "was the
gate even on?" from a post-mortem question into a pre-flight check.

#### Scenario: an installed gate proceeds

**Given** `gate.sh --check-installed` returns `installed: true`
**When** apply Step 0 runs
**Then** the result is recorded and the apply phase proceeds to baseline
recording

#### Scenario: an uninstalled gate stops the apply phase

**Given** `gate.sh --check-installed` returns `installed: false`
**When** apply Step 0 runs
**Then** the apply phase stops with a direction to run
`install-hooks.sh --apply` and does not record a baseline

### Requirement: The Devin adapter's blocking behavior is verified or corrected

The Devin adapter's `Stop` and `PreToolUse` blocking behavior SHALL be
verified by executing the README's three-level procedure (script works →
harness invokes it → model received it) against a live Devin session;
the behavior is documented as "not first-hand verified" in the hooks
README. The README's adapter table MUST be updated to reflect what Devin
actually does: if blocking is honored, the "not first-hand verified" note
is removed; if `additionalContext` is dropped or a `Stop` refusal is
ignored, the adapter is switched to `--format text` or the table is
corrected to record the gap.

**Given** the hooks README adapter table records Devin as "not first-hand
verified"
**When** the three-level verification runs against a live Devin session
**Then** the table is updated to record the verified result (blocking
honored, or the specific gap and the chosen correction)

**Rationale**: The harness-agnostic design rests on which harnesses can
block, and where. "Documented but never observed" is a claim the invariant
forbids: a blocking tier cited as stronger than it is, is the defect class
this workflow exists to remove. The Devin session that produced the
failure report is the exact case where an unverified blocking claim would
have mattered most.

#### Scenario: Devin honors the Stop block

**Given** a live Devin session with the adapter installed
**When** the three-level verification runs (heartbeat shows the Stop hook
fired; a completion claim while chain state is undetermined is refused)
**Then** the README adapter table's "not first-hand verified" note for
Devin is removed and replaced with the verified result

#### Scenario: Devin drops additionalContext

**Given** a live Devin session where a level-3 check shows the model
cannot answer a CONTEXT-block question without a tool call
**When** the three-level verification runs
**Then** the Devin adapter is switched to `--format text` (per the README's
own guidance) and the table records the correction

#### Scenario: Devin ignores a Stop refusal

**Given** a live Devin session where a Stop refusal does not prevent turn
completion
**When** the three-level verification runs
**Then** the README adapter table is corrected to record that Devin's
Stop tier is not a genuine block, and the enforcement consequence (Devin
relies on the pre-execution `tool-call` tier, not Stop) is documented

### Requirement: The tool-call event is wired into all three adapters

The `gate.sh --event tool-call` event (specs 1–2) SHALL be wired into all
three adapter configs: Claude Code `PreToolUse` (matcher `Bash|Edit|Write|
MultiEdit`), Devin `PreToolUse` (same matcher), and the pi adapter's
`tool_call` handler (mapping `{"decision":"block","reason":…}` →
`{block:true,reason}`). The adapters SHALL pass the tool name and input
path/command to gate.sh so it can consult phase and grant state.

**Given** `gate.sh --event tool-call` is implemented
**When** the adapter configs are updated
**Then** Claude Code's `PreToolUse` matcher includes the edit/write/bash
tools; Devin's `PreToolUse` includes the same; the pi adapter's
`tool_call` handler shells out to `gate.sh --event tool-call` and maps a
block decision to `{block:true,reason}`

**Rationale**: Specs 1–2 are inert without the wiring that makes the
pre-execution gate actually fire on every harness. The wiring is the
contract surface that makes the control plane harness-agnostic in
practice, not just in design.

#### Scenario: Claude Code PreToolUse wired

**Given** the Claude Code adapter is updated
**When** an `Edit` tool call targets `*/src/main/**` while phase is oracle
**Then** the `PreToolUse` hook runs `gate.sh --event tool-call`, which
emits a block, and Claude Code does not execute the edit

#### Scenario: Devin PreToolUse wired

**Given** the Devin adapter is updated
**When** a `Write` tool call targets `*/src/main/**` while phase is oracle
**Then** the `PreToolUse` hook runs `gate.sh --event tool-call`, which
emits a block (verified per the prior requirement), and Devin does not
execute the write

#### Scenario: pi tool_call handler wired

**Given** the pi adapter is updated
**When** a `write` tool call targets `*/src/main/**` while phase is oracle
**Then** the `tool_call` handler runs `gate.sh --event tool-call`, receives
`{"decision":"block","reason":…}`, and returns `{block:true,reason}` to pi

### Requirement: The schema changelog records the defect class

The schema.yaml changelog SHALL record a v13 entry naming the defect class
this change addresses: a prose-only control plane where the completion tier
(Tier A) was promoted on 2/3 harnesses while the universal pre-execution
tier shipped nothing, and approval evidence lived in the agent's own
output stream. The entry SHALL state the fix (pre-execution `tool-call`
gate, harness-observed grants) and the date.

**Given** this change's defect class
**When** the schema.yaml changelog is updated
**Then** a v13 entry records the defect class, the fix, and the date, in
the same fault-dataset style as the v12 entries

**Rationale**: The v12 changelog established "changelog as fault dataset" —
every entry records one defect repeated until a mechanism removes it. This
change's defect (prose-only control plane) is the next entry in that
dataset. Recording it is part of the change, not a consequence of it: the
next agent's honest picture of what is and isn't enforced depends on the
changelog being current.

#### Scenario: the changelog has a v13 entry

**Given** the schema.yaml changelog
**When** the update lands
**Then** a v13 entry exists naming the defect class, the pre-execution
fix, and the date

#### Scenario: the v13 entry names the harness asymmetry

**Given** the v13 changelog entry
**When** it is read
**Then** it states that the completion tier is a 2/3-harness backstop and
the pre-execution tier is the universal gate, so the next agent does not
cite the completion gate as stronger than it is on pi

## Properties (Ring 3)

No Hedgehog properties. All verification via bats scenario tests.
Property-equivalent invariant: **apply Step 0 never proceeds with
`installed: false`, and every adapter config contains a `tool-call`
wiring.** Asserted by enumerating the adapter configs and the
`--check-installed` decision in `tests/harness-install-verification.bats`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Installed gate proceeds | Requirement: Apply Step 0 verifies the gate is installed and firing + Scenario: an installed gate proceeds | bats scenario: installed proceeds | `tests/harness-install-verification.bats` |
| Uninstalled gate stops | Requirement: Apply Step 0 verifies the gate is installed and firing + Scenario: an uninstalled gate stops the apply phase | bats scenario: uninstalled stops | `tests/harness-install-verification.bats` |
| Devin Stop verified or corrected | Requirement: The Devin adapter's blocking behavior is verified or corrected + Scenario: Devin honors the Stop block | bats scenario: devin stop verified (or documented gap) | `tests/harness-install-verification.bats` + recorded verification log |
| Devin additionalContext drop corrected | Requirement: The Devin adapter's blocking behavior is verified or corrected + Scenario: Devin drops additionalContext | bats scenario: devin text fallback | `tests/harness-install-verification.bats` + recorded verification log |
| Devin Stop-ignored corrected | Requirement: The Devin adapter's blocking behavior is verified or corrected + Scenario: Devin ignores a Stop refusal | bats scenario: devin stop not a block | `tests/harness-install-verification.bats` + recorded verification log |
| Claude PreToolUse wired | Requirement: The tool-call event is wired into all three adapters + Scenario: Claude Code PreToolUse wired | bats scenario: claude pretooluse wired | `tests/harness-install-verification.bats` |
| Devin PreToolUse wired | Requirement: The tool-call event is wired into all three adapters + Scenario: Devin PreToolUse wired | bats scenario: devin pretooluse wired | `tests/harness-install-verification.bats` |
| pi tool_call wired | Requirement: The tool-call event is wired into all three adapters + Scenario: pi tool_call handler wired | bats scenario: pi tool_call wired | `tests/harness-install-verification.bats` |
| Changelog v13 entry exists | Requirement: The schema changelog records the defect class + Scenario: the changelog has a v13 entry | bats scenario: v13 entry present | `tests/harness-install-verification.bats` |
| v13 entry names harness asymmetry | Requirement: The schema changelog records the defect class + Scenario: the v13 entry names the harness asymmetry | bats scenario: v13 names asymmetry | `tests/harness-install-verification.bats` |
