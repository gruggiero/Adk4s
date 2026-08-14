# Spec: Human Grant Lock

<!-- DELTA spec for the `human-grant-lock` capability. Fixes the "tacit
     approval" failure: the agent controls both execution and the protocol
     for when the human gets a turn, so silence becomes a ratchet. The fix
     moves approval to an input channel the agent does not control — a user
     prompt arriving after a checkpoint presentation. -->

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
| grant token | new git-dir state | `grant-<change>-<spec-N>` file written when a user prompt arrives after a checkpoint presentation for spec N |
| checkpoint-presentation record | new git-dir state | the hash of the `checkpoint.sh report` output the agent emitted at Step 13, marking "after which" a grant counts |

## ADDED Requirements

### Requirement: Next-spec Step-0 actions require a grant for the prior spec

The pre-execution gate SHALL refuse a tool call that begins spec N+1's
Step 0 (recording a new baseline SHA, editing spec N+1's section of
`implementation-progress.md`, or editing files under spec N+1's spec
directory) until a grant token for spec N exists in git-dir state. A grant
for spec N MUST NOT authorize spec N+2.

**Given** spec N's checkpoint has been presented (a checkpoint-presentation
record exists) and no grant for spec N exists
**When** a tool call records a new baseline SHA for spec N+1 or edits spec
N+1's `implementation-progress.md` section
**Then** the gate emits a block decision naming spec N and the missing
grant; the tool call does not run

**Rationale**: The failure report's "tacit approval" drift: "The user
didn't intervene during the session, which I interpreted as tacit approval.
But the user cannot intervene if I never stops." The agent wrote
`[x] **STOP for human approval** ◄ PASSED` in its own progress file — the
machine read an attestation. Approval must become an input the machine reads
from a place the agent cannot write unilaterally: a user turn that arrived
after the checkpoint. No harness in the set lets the agent fabricate a user
prompt.

#### Scenario: next-spec start blocked without a grant

**Given** a checkpoint-presentation record for spec N exists and no
`grant-<change>-<spec-N>` file exists
**When** a tool call edits the `## Spec (N+1)/M` section of
`implementation-progress.md`
**Then** the gate emits a block decision naming spec N and the missing grant

#### Scenario: next-spec start allowed with a grant

**Given** a `grant-<change>-<spec-N>` file exists
**When** a tool call edits spec N+1's section of `implementation-progress.md`
**Then** the gate emits no block and the tool call proceeds

#### Scenario: a grant for spec N does not authorize spec N+2

**Given** a `grant-<change>-<spec-N>` file exists but no grant for spec N+1
exists
**When** a tool call edits spec N+2's section of `implementation-progress.md`
**Then** the gate emits a block decision naming spec N+1 and the missing
grant

### Requirement: A grant is recorded only on a user prompt after a checkpoint presentation

A grant token for spec N SHALL be written to git-dir state when, and only
when, a user prompt arrives (the `prompt-submit` event) after a
checkpoint-presentation record for spec N exists. The gate MUST NOT write a
grant on an assistant turn, on a tool result, or in the absence of a
checkpoint-presentation record.

**Given** a checkpoint-presentation record for spec N exists
**When** the `prompt-submit` event fires (a user prompt arrived)
**Then** the gate writes `grant-<change>-<spec-N>` to git-dir state

**Rationale**: The approval proxy is deliberately coarse — "the user typed
anything after the checkpoint" — because the property that matters is that
the input arrived through the user channel, not the agent's output stream.
The "after" relation is well-defined because the checkpoint presentation is
itself a recorded fact (the hash of `checkpoint.sh report`'s output).

#### Scenario: a user prompt after a checkpoint writes a grant

**Given** a checkpoint-presentation record for spec N exists
**When** the `prompt-submit` event fires
**Then** `grant-<change>-<spec-N>` is written to git-dir state

#### Scenario: a user prompt before any checkpoint writes no grant

**Given** no checkpoint-presentation record exists for spec N
**When** the `prompt-submit` event fires
**Then** no grant for spec N is written

#### Scenario: an assistant turn writes no grant

**Given** a checkpoint-presentation record for spec N exists
**When** an event other than `prompt-submit` fires (e.g. `tool-call`,
`post-edit`)
**Then** no grant for spec N is written on that event

### Requirement: The checkpoint presentation is a recorded fact

The gate SHALL record a checkpoint-presentation record for spec N when
`checkpoint.sh report` produces output for spec N during the session (the
agent ran the Step-13 checkpoint command). The record is the hash of the
report output, so the "after" relation between a grant and a checkpoint is
machine-decidable.

**Given** the agent runs `checkpoint.sh report … --spec S` and it produces
non-empty output
**When** the gate next runs (any event)
**Then** a checkpoint-presentation record for S is written to git-dir state,
keyed by the report output's hash

**Rationale**: Without a recorded checkpoint, "a grant arrived after the
checkpoint" is not decidable — the agent could claim it presented a
checkpoint it never presented. The record makes the gate's "after" predicate
a fact, not an attestation.

#### Scenario: a checkpoint report writes a presentation record

**Given** `checkpoint.sh report … --spec S` produces non-empty output
**When** the gate next runs
**Then** a checkpoint-presentation record keyed by the report hash exists
for S

#### Scenario: no checkpoint report means no grant can be recorded

**Given** no `checkpoint.sh report` has run for spec N this session
**When** a user prompt arrives
**Then** no grant for spec N is written (there is no checkpoint to be after)

### Requirement: Grants and presentations are scoped to a session and cleared on a new turn

A grant token and checkpoint-presentation record SHALL be scoped to the
session (the same `--session` identity gate.sh already uses for fingerprint
suppression). The gate SHALL clear a prior turn's refusal state on the next
`prompt-submit`, matching the completion gate's bounded-refusal discipline.

**Given** a grant for spec N exists for session `S1`
**When** a different session `S2` queries the gate
**Then** session S2 sees no grant for spec N (the grant does not leak across
sessions)

**Rationale**: Session-scoping mirrors the existing fingerprint and
refusal-marker discipline. A grant that leaked across sessions would let one
session's approval authorize another's continuation.

#### Scenario: a grant does not leak across sessions

**Given** a grant for spec N exists under `--session S1`
**When** the gate is queried under `--session S2`
**Then** the gate behaves as if no grant exists for spec N under S2

#### Scenario: a new turn clears stale refusal state

**Given** the gate refused a next-spec start for spec N+1 in the previous
turn of session S
**When** the next `prompt-submit` for session S fires
**Then** the refusal marker for that turn is cleared (so a fresh grant can
authorize a fresh attempt without a deadlocked refusal)

## Properties (Ring 3)

No Hedgehog properties. All verification via bats scenario tests (the
workflow's convention for bash/jq tooling). Property-equivalent invariant:
**a grant is never written by an event other than `prompt-submit`, and a
next-spec start is never allowed without a grant for the immediately prior
spec.** Asserted by enumerating the event × state decision table in
`tests/human-grant-lock.bats`.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Next-spec start blocked without a grant | Requirement: Next-spec Step-0 actions require a grant for the prior spec + Scenario: next-spec start blocked without a grant | bats scenario: blocked without grant | `tests/human-grant-lock.bats` |
| Next-spec start allowed with a grant | Requirement: Next-spec Step-0 actions require a grant for the prior spec + Scenario: next-spec start allowed with a grant | bats scenario: allowed with grant | `tests/human-grant-lock.bats` |
| Grant for N does not authorize N+2 | Requirement: Next-spec Step-0 actions require a grant for the prior spec + Scenario: a grant for spec N does not authorize spec N+2 | bats scenario: grant scoped to next spec | `tests/human-grant-lock.bats` |
| Grant written on prompt after checkpoint | Requirement: A grant is recorded only on a user prompt after a checkpoint presentation + Scenario: a user prompt after a checkpoint writes a grant | bats scenario: prompt writes grant | `tests/human-grant-lock.bats` |
| No grant before any checkpoint | Requirement: A grant is recorded only on a user prompt after a checkpoint presentation + Scenario: a user prompt before any checkpoint writes no grant | bats scenario: no checkpoint no grant | `tests/human-grant-lock.bats` |
| No grant on a non-prompt event | Requirement: A grant is recorded only on a user prompt after a checkpoint presentation + Scenario: an assistant turn writes no grant | bats scenario: non-prompt no grant | `tests/human-grant-lock.bats` |
| Checkpoint presentation is recorded | Requirement: The checkpoint presentation is a recorded fact + Scenario: a checkpoint report writes a presentation record | bats scenario: report writes presentation | `tests/human-grant-lock.bats` |
| No checkpoint means no grant | Requirement: The checkpoint presentation is a recorded fact + Scenario: no checkpoint report means no grant can be recorded | bats scenario: no report no grant | `tests/human-grant-lock.bats` |
| Grant scoped to a session | Requirement: Grants and presentations are scoped to a session and cleared on a new turn + Scenario: a grant does not leak across sessions | bats scenario: grant session-scoped | `tests/human-grant-lock.bats` |
| New turn clears stale refusal | Requirement: Grants and presentations are scoped to a session and cleared on a new turn + Scenario: a new turn clears stale refusal state | bats scenario: new turn clears refusal | `tests/human-grant-lock.bats` |
