# Spec: Gate Payload

Turns the session gate from a positional status line into the standing
statement of the invariant plus the live count of claims that outrun their
evidence — injected on every turn, not once per session.

Depends on: `correctness-invariant` (the text), `chain-state` (the count).

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
| Gate Heartbeat | persisted marker | A record that the gate ran, making "never fired" distinguishable from "fired and stayed silent" without operator setup |

## ADDED Requirements

### Requirement: The injected payload states the invariant and the unresolved count

The session gate SHALL inject the correctness invariant together with the
current chain state, and the injected text MUST name every unresolved
requirement rather than reporting only a total.

**Given** a repository with an active change carrying unresolved requirements
**When** the gate produces its payload
**Then** the payload contains the invariant, the three counts, and one line per
unresolved requirement giving its name and failing reason

**Rationale**: The gate currently reports workflow position — which artifact
comes next — and reported "all planning artifacts exist" for a change carrying
78 unresolved artifact commitments. Position answers where the agent is;
nothing answered what must be true of what it says.

#### Scenario: unresolved requirements appear in the payload

**Given** an active change with three unresolved requirements
**When** the payload is produced
**Then** all three appear by name

#### Scenario: a clean change reports zero without a list

**Given** an active change whose requirements are all discharged
**When** the payload is produced
**Then** the counts show zero unresolved and no requirement lines follow

#### Scenario: position alone is not sufficient payload

**Given** an active change with unresolved requirements and all planning
artifacts present
**When** the payload is produced
**Then** it does not report readiness without also reporting the unresolved
count

### Requirement: The gate runs on every turn, not only at session start

The session gate SHALL be invoked on each prompt submission in addition to
session start, so that its facts survive context compaction.

**Given** a long session in which earlier context has been summarised away
**When** a new prompt is submitted
**Then** the gate runs and its facts are available again

**Rationale**: A payload injected once at session start is lost to compaction
in exactly the long runs where drift accumulates.

#### Scenario: facts are available after compaction

**Given** a session whose start-time injection is no longer in context
**When** a further prompt is submitted
**Then** the gate runs again for that prompt

### Requirement: An unchanged payload is not re-injected

The gate SHALL re-inject its payload only when the facts have changed since the
previous injection in the same session, and MUST NOT repeat an identical
payload on every turn.

**Given** a session in which the gate has already injected a payload
**When** a further prompt is submitted and no underlying fact has changed
**Then** no payload is injected for that prompt

**Rationale**: A block repeated verbatim every turn becomes wallpaper and costs
context for no information. Re-injecting on change is also the desired
behaviour when facts move mid-session: a newly created registry changes what
applies, and should be re-announced rather than remembered.

#### Scenario: a changed fact triggers re-injection

**Given** a session with a prior injection
**When** the unresolved count changes and a prompt is submitted
**Then** the payload is injected again

#### Scenario: repetition is suppressed when nothing changed

**Given** a session with a prior injection
**When** two further prompts are submitted with no fact change
**Then** no further payload is injected

### Requirement: The gate never blocks and never fails a session

The session gate SHALL always exit successfully and MUST NOT prevent a session
from starting or a prompt from proceeding, whatever the state of the repository
or of the tools it calls.

**Given** any repository state, including a missing or failing chain state tool
**When** the gate runs
**Then** it exits successfully and the session proceeds

**Rationale**: This constraint is inherited unchanged from the existing gate.
A gate that fails a session is worse than one that says nothing, and blocking
behaviour is deliberately confined to a later spec.

#### Scenario: a failing chain state tool does not fail the gate

**Given** a chain state tool that exits non-zero
**When** the gate runs
**Then** the gate exits successfully and reports the evidence as undetermined
in its payload

#### Scenario: a repository outside the workflow receives nothing

**Given** a repository with no workflow directory
**When** the gate runs
**Then** it exits successfully and injects nothing

### Requirement: A gate that never ran is detectable without operator setup

The gate SHALL record that it ran, on every invocation including those that
inject nothing, and that record MUST be readable by a check so that an
uninstalled gate is a detected condition.

**Given** a gate that is configured but whose command cannot be found
**When** a check for gate installation runs
**Then** the check reports that the gate has not run

**Rationale**: The gate currently offers a trace facility that is inert unless
an operator exports an environment variable, and a three-level manual
procedure documents how to verify it by hand. Nothing automated reads either,
so a silently uninstalled gate is indistinguishable from a quiet one.

#### Scenario: a silent invocation still records that it ran

**Given** a repository where the gate exits early because the workflow
directory is absent
**When** the gate runs
**Then** the record shows an invocation, distinguishing it from never running

#### Scenario: an uninstalled gate is reported

**Given** a configuration pointing at a gate command that does not exist
**When** the installation check runs
**Then** it reports the gate as not having run

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash: enumerated finite domains in
     bats, not sampled generators. See the evidence-ledger spec's note; the
     same adaptation and the same limitation apply. -->

### Property: Payload contents follow the chain state exactly

**Invariant**: For any chain state, the payload names exactly the unresolved
requirements that chain state reports, with no additions or omissions.

**Generator strategy**: Enumerated over constructed chain states with unresolved
counts of 0, 1, 2 and 12, including one requirement whose name contains a
character requiring escaping in the output format. Constructive — expected
membership is known from the construction.

```
forAll state in constructed_states:
  named_in_payload(state) == unresolved(state)
```

### Property: Every output format carries the same facts

**Invariant**: For any chain state, the facts present in one output format are
present in every other supported format.

**Generator strategy**: Enumerated over the supported format flags crossed with
the constructed chain states above. Edge cases covered: a requirement name
containing a quote and one containing a newline, which must survive the
structured format's escaping.

```
forAll (state, fmt) in states x formats:
  facts(payload(state, fmt)) == facts(payload(state, reference_fmt))
```

### Property: The gate always exits successfully

**Invariant**: For every induced failure condition, the gate exits with a
success status.

**Generator strategy**: Enumerated over induced conditions: absent workflow
directory, absent chain state tool, chain state exiting non-zero, unreadable
ledger, unreadable specification directory, and an unwritable heartbeat
location. Constructive — each is induced deliberately.

```
forAll condition in induced_failures:
  exit_status(gate(condition)) == 0
```

### Property: Suppression depends only on whether facts changed

**Invariant**: For any pair of consecutive invocations, a payload is injected
on the second if and only if the facts differ from the first.

**Generator strategy**: Enumerated over pairs drawn from the constructed chain
states, covering equal pairs, pairs differing only in count, pairs differing
only in requirement names, and pairs differing only in applicability facts.
Constructive.

```
forAll (s1, s2) in states x states:
  injects_on_second(s1, s2) == (facts(s1) != facts(s2))
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The payload carries the invariant text | Requirement: The injected payload states the invariant and the unresolved count | scenario test asserting the invariant is present | `gate-payload.bats` |
| Every unresolved requirement is named in the payload | Requirement: The injected payload states the invariant and the unresolved count + Property: Payload contents follow the chain state exactly | property test over constructed chain states | `gate-payload.bats` |
| Readiness is never reported without the unresolved count | Requirement: The injected payload states the invariant and the unresolved count + Scenario: position alone is not sufficient payload | scenario test | `gate-payload.bats` |
| All supported output formats carry the same facts | Property: Every output format carries the same facts | property test over format and state cross product | `gate-payload.bats` |
| The gate is invoked on prompt submission as well as session start | Requirement: The gate runs on every turn, not only at session start | scenario test over the harness configuration files | `gate-payload.bats` |
| An identical payload is not re-injected | Requirement: An unchanged payload is not re-injected + Property: Suppression depends only on whether facts changed | property test over consecutive state pairs | `gate-payload.bats` |
| A changed fact re-injects | Requirement: An unchanged payload is not re-injected + Scenario: a changed fact triggers re-injection | scenario test | `gate-payload.bats` |
| The gate exits successfully under every induced failure | Requirement: The gate never blocks and never fails a session + Property: The gate always exits successfully | property test over induced failure conditions | `gate-payload.bats` |
| A repository outside the workflow receives no injection | Requirement: The gate never blocks and never fails a session + Scenario: a repository outside the workflow receives nothing | scenario test | `gate-payload.bats` |
| Every invocation is recorded, including silent ones | Requirement: A gate that never ran is detectable without operator setup | scenario test over the silent-exit path | `gate-payload.bats` |
| An uninstalled gate is reported by a check | Requirement: A gate that never ran is detectable without operator setup + Scenario: an uninstalled gate is reported | scenario test with a configuration pointing at an absent command | `gate-payload.bats` |
| Suppression state does not leak between sessions | Requirement: An unchanged payload is not re-injected | scenario test asserting a new session injects regardless of the previous session's final state | `gate-payload.bats` |
| The payload remains within a size that does not crowd out working context | Requirement: The injected payload states the invariant and the unresolved count | manual review at the Step 8 adversarial gate — a threshold would be arbitrary; the reviewer judges the payload at a realistic unresolved count | Ring 8 report |
| The harness actually invokes the gate on prompt submission | Requirement: The gate runs on every turn, not only at session start | manual verification per harness — a test can assert the configuration names the event, but not that the harness fires it. Discharged by the heartbeat record plus the level-2/level-3 procedure already documented for this gate | `hooks/README.md` verification procedure, Ring 8 report |
| Facts remain available after context compaction | Requirement: The gate runs on every turn + Scenario: facts are available after compaction | manual verification — compaction is harness behaviour and cannot be induced from a shell test; the per-turn invocation is the mechanism, and its testable half is the obligation above | `hooks/README.md` verification procedure, Ring 8 report |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/hooks/gate.sh` | script | hooks/ | payload assembly; `--event prompt-submit` already maps to the prompt-submission event and is currently unwired |
| `openspec/schemas/verified-scala3/hooks/adapters/claude.settings.json` | adapter config | hooks/adapters/ | add the prompt-submission event alongside session start |
| `openspec/schemas/verified-scala3/hooks/adapters/pi/verified-scala3-gate.ts` | adapter | hooks/adapters/pi/ | fingerprinting logic moves OUT of here and into the shared script |
| `openspec/schemas/verified-scala3/hooks/adapters/devin.hooks.v1.json` | adapter config | hooks/adapters/ | same event addition |
| `openspec/schemas/verified-scala3/scanner/chain-state.sh` | script | scanner/ | shipped by the `chain-state` spec; the gate calls it |
| `openspec/schemas/verified-scala3/tests/gate-payload.bats` | test | tests/ | NEW |
