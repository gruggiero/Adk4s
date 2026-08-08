# Spec: Hook Tiers

Moves correction from "whenever the agent chooses to run a script" to "when the
harness fires an event". Adds after-the-fact correction at the moment of edit,
and one genuine block at the only point where blocking cannot strand anyone.

Depends on: `chain-state`. Lands LAST — the blocking tier is adopted only after
the non-blocking path has run in daily use.

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
| Post-edit correction tier | harness integration | Runs the relevant existing check after a file edit and returns its findings, without blocking |
| Completion gate | harness integration | Refuses turn completion when a checkpoint is claimed while requirements remain unresolved |

## ADDED Requirements

### Requirement: A specification edit is checked immediately

Editing a specification file SHALL run the specification lint for that change
and return its findings, so that a malformed specification is reported at the
edit rather than at the lint artifact.

**Given** an agent editing a specification file of an active change
**When** the edit completes
**Then** the lint runs for that change and its findings are returned

**Rationale**: The lint already exists and already finds these defects. What is
missing is that it runs only when invoked, so the findings arrive several
artifacts after the mistake.

#### Scenario: findings are returned after a malformed edit

**Given** an edit introducing a requirement with no normative statement
**When** the edit completes
**Then** the lint finding for that requirement is returned

#### Scenario: a clean edit returns nothing

**Given** an edit that leaves the specification lint-clean
**When** the edit completes
**Then** no findings are returned

#### Scenario: an edit outside the specification directory does not trigger the lint

**Given** an edit to a file that is not a specification of an active change
**When** the edit completes
**Then** the specification lint does not run

### Requirement: A production source edit is scanned immediately

Editing a production source file SHALL run the dangerous-pattern scan against
the current baseline and return any unjustified findings.

**Given** an agent editing a production source file
**When** the edit completes
**Then** the scan runs against the recorded baseline and unjustified findings
are returned

**Rationale**: The scan targets a defect class — a catch-all arm that maps an
unrecognised variant to a value the domain accepts, so the failure is silent —
which is cheapest to repair at the keystroke and most expensive to repair once
it has been reasoned about downstream.

#### Scenario: an unjustified catch-all is reported at the edit

**Given** an edit introducing a catch-all arm with no justification comment
**When** the edit completes
**Then** the finding is returned

#### Scenario: a justified occurrence is not reported

**Given** an edit introducing a catch-all arm carrying its justification
comment
**When** the edit completes
**Then** no finding is returned for that line

### Requirement: Post-edit correction never blocks the edit

The post-edit tier SHALL return findings as information only, and MUST NOT
prevent, revert, or reject the edit that triggered it.

**Given** an edit producing findings
**When** the tier runs
**Then** the edit remains applied and the agent proceeds

**Rationale**: The edit has already happened, so this tier cannot strand an
agent. Preserving that property is what makes it adoptable ahead of the
blocking tier.

#### Scenario: the edit survives its own findings

**Given** an edit producing several findings
**When** the tier runs
**Then** the file content is exactly as edited

#### Scenario: a failing check does not reject the edit

**Given** a check that cannot run at all
**When** the tier runs
**Then** the edit remains applied and the failure is reported as a check
failure rather than an edit rejection

### Requirement: A turn claiming completion while evidence is missing is refused

Turn completion SHALL be refused when the turn asserts a checkpoint, spec
completion, or ring success while chain state reports unresolved requirements,
and the refusal MUST name the unresolved requirements.

**Given** a turn asserting that a spec is complete
**When** chain state reports unresolved requirements for that spec
**Then** completion is refused and the unresolved requirements are named

**Rationale**: This is the invariant at its cheapest enforcement point.
Refusing completion cannot interrupt work in progress; the worst outcome is one
additional turn. It is the only place in the workflow where a block is
justified.

#### Scenario: a completion claim with unresolved requirements is refused

**Given** a turn asserting spec completion and two unresolved requirements
**When** completion is attempted
**Then** it is refused and both requirements are named

#### Scenario: a completion claim with a full chain proceeds

**Given** a turn asserting spec completion where every requirement is
discharged
**When** completion is attempted
**Then** it proceeds

#### Scenario: a turn making no completion claim proceeds

**Given** a turn that asserts nothing about completion, with unresolved
requirements outstanding
**When** completion is attempted
**Then** it proceeds

#### Scenario: undetermined evidence does not silently permit completion

**Given** a turn asserting spec completion where chain state reports evidence
as undetermined
**When** completion is attempted
**Then** it is refused, and the refusal states that evidence could not be
determined rather than that requirements are unresolved

### Requirement: The completion gate cannot deadlock a session

The completion gate SHALL refuse the same turn at most once, and a repeated
attempt after the refusal MUST be permitted so that a session can always make
progress.

**Given** a turn refused by the completion gate
**When** the agent attempts completion again after being told why
**Then** completion proceeds

**Rationale**: A gate that refuses indefinitely is the stranding failure the
hook documentation declined to risk. Refusing once delivers the correction;
refusing forever removes the agent's ability to hand control back to the human.

#### Scenario: a second attempt proceeds

**Given** a turn already refused once by the gate
**When** completion is attempted again
**Then** it proceeds

#### Scenario: refusal state does not carry into the next turn

**Given** a turn refused by the gate
**When** a new turn begins and makes no completion claim
**Then** the new turn is unaffected

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash: enumerated finite domains in
     bats, not sampled generators. See the evidence-ledger spec's note; the
     same adaptation and the same limitation apply. -->

### Property: The post-edit tier is triggered by path, exhaustively

**Invariant**: For any edited path, the specification lint runs exactly when
the path is a specification of an active change, and the dangerous-pattern scan
runs exactly when the path is a production source file.

**Generator strategy**: Enumerated over a listed path set covering: an active
change specification, an archived change specification, a template with a
similar name, a production source, a test source, a generated source, a
documentation file, and a path containing a space. Constructive; the expected
trigger for each is stated in the construction.

```
forAll path in listed_paths:
  runs_spec_lint(path) == is_active_change_spec(path)
  and runs_danger_scan(path) == is_production_source(path)
```

### Property: The post-edit tier never alters the edited file

**Invariant**: For any edit and any check outcome, the edited file's content
after the tier runs equals its content immediately after the edit.

**Generator strategy**: Enumerated over the cross product of the listed path
set and check outcomes {clean, findings, check unavailable, check errored}.
Constructive — each outcome is induced deliberately.

```
forAll (path, outcome) in paths x outcomes:
  content_after_tier(path) == content_after_edit(path)
```

### Property: Completion is refused exactly when a claim outruns evidence

**Invariant**: For any turn, completion is refused if and only if the turn
asserts completion and chain state is not fully discharged or is undetermined.

**Generator strategy**: Enumerated over the cross product of turn kinds
{asserts completion, asserts a ring result, asserts nothing} and chain states
{fully discharged, partially discharged, none discharged, undetermined}.
Constructive; covers every combination including the two that must proceed
despite unresolved requirements.

```
forAll (turn, state) in turn_kinds x chain_states:
  refused(turn, state) == (asserts_completion(turn) and not fully_discharged(state))
```

### Property: Refusal is bounded

**Invariant**: For any sequence of completion attempts on one turn, at most one
is refused.

**Generator strategy**: Enumerated over attempt sequences of length 1, 2, 3 and
5, each against a chain state that would refuse. Constructive.

```
forAll n in {1,2,3,5}:
  count_refusals(attempts(n)) <= 1
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A specification edit triggers the lint and returns findings | Requirement: A specification edit is checked immediately | scenario test with a malformed edit | `hook-tiers.bats` |
| A production source edit triggers the scan | Requirement: A production source edit is scanned immediately | scenario test with an unjustified catch-all | `hook-tiers.bats` |
| A justified occurrence produces no finding | Requirement: A production source edit is scanned immediately + Scenario: a justified occurrence is not reported | scenario test | `hook-tiers.bats` |
| Triggering is exhaustive and correct by path | Property: The post-edit tier is triggered by path, exhaustively | property test over the listed path set | `hook-tiers.bats` |
| An edit outside the specification directory does not trigger the lint | Requirement: A specification edit is checked immediately + Scenario: an edit outside the specification directory does not trigger the lint | scenario test | `hook-tiers.bats` |
| The post-edit tier never alters the edited file | Requirement: Post-edit correction never blocks the edit + Property: The post-edit tier never alters the edited file | property test over path and outcome cross product | `hook-tiers.bats` |
| A check that cannot run does not reject the edit | Requirement: Post-edit correction never blocks the edit + Scenario: a failing check does not reject the edit | scenario test | `hook-tiers.bats` |
| Completion is refused exactly when a claim outruns evidence | Requirement: A turn claiming completion while evidence is missing is refused + Property: Completion is refused exactly when a claim outruns evidence | property test over turn kind and chain state cross product | `hook-tiers.bats` |
| A refusal names the unresolved requirements | Requirement: A turn claiming completion while evidence is missing is refused | scenario test | `hook-tiers.bats` |
| Undetermined evidence refuses with a distinct reason | Requirement: A turn claiming completion while evidence is missing is refused + Scenario: undetermined evidence does not silently permit completion | scenario test | `hook-tiers.bats` |
| A turn making no completion claim proceeds | Requirement: A turn claiming completion while evidence is missing is refused + Scenario: a turn making no completion claim proceeds | scenario test | `hook-tiers.bats` |
| Refusal is bounded to once per turn | Requirement: The completion gate cannot deadlock a session + Property: Refusal is bounded | property test over attempt sequences | `hook-tiers.bats` |
| Refusal state does not carry into the next turn | Requirement: The completion gate cannot deadlock a session + Scenario: refusal state does not carry into the next turn | scenario test | `hook-tiers.bats` |
| Detecting a completion claim from turn text is approximate | Requirement: A turn claiming completion while evidence is missing is refused | manual review at the Step 8 adversarial gate, explicitly accepted limit — the gate reads assertions from text and will both miss paraphrases and occasionally fire on a discussion of completion; bounded refusal is what makes that acceptable | Ring 8 report |
| The blocking tier is adopted only after the non-blocking tier has run in use | Requirement: The completion gate cannot deadlock a session | manual review — an ordering commitment, not a testable property of the code | Ring 8 report, implementation-order |
| The harness honours a refusal and does not complete the turn | Requirement: A turn claiming completion while evidence is missing is refused | manual verification per harness — the refusal decision and its output are testable from the script, but whether the harness acts on them is outside the script's observation. Verified once per supported harness and recorded | `hooks/README.md` verification procedure, Ring 8 report |
| The harness delivers post-edit findings back to the agent | Requirement: A specification edit is checked immediately + Requirement: A production source edit is scanned immediately | manual verification per harness — the tier's output is testable; whether the agent receives it is a harness property | `hooks/README.md` verification procedure, Ring 8 report |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/hooks/gate.sh` | script | hooks/ | gains post-edit and completion events; keeps its always-succeed contract for the injection events |
| `openspec/schemas/verified-scala3/hooks/adapters/claude.settings.json` | adapter config | hooks/adapters/ | post-tool and stop events |
| `openspec/schemas/verified-scala3/hooks/adapters/devin.hooks.v1.json` | adapter config | hooks/adapters/ | same |
| `openspec/schemas/verified-scala3/hooks/adapters/pi/verified-scala3-gate.ts` | adapter | hooks/adapters/pi/ | same, via the shared script |
| `openspec/schemas/verified-scala3/scanner/spec-lint.sh` | existing script | scanner/ | invoked by the post-edit tier; unchanged |
| `openspec/schemas/verified-scala3/scanner/danger-scan.sh` | existing script | scanner/ | invoked by the post-edit tier; unchanged |
| `openspec/schemas/verified-scala3/hooks/README.md` | doc | hooks/ | records the promotion from the non-blocking tier, with the evidence that justified it |
| `openspec/schemas/verified-scala3/tests/hook-tiers.bats` | test | tests/ | NEW |
