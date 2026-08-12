# Spec: Checkpoint From Ledger

Makes the per-spec checkpoint a report generated from recorded evidence rather
than prose composed by the agent about what it believes it did.

Depends on: `evidence-ledger`, `chain-state`.

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
| Generated Checkpoint | derived report | The per-spec checkpoint summary, produced from ledger rows and chain state rather than authored |

## ADDED Requirements

### Requirement: Ring outcomes in the checkpoint come from recorded rows

The checkpoint summary SHALL derive every ring outcome it reports from ledger
rows for the current spec and baseline, and MUST NOT accept a ring outcome
supplied as free text.

**Given** a spec whose verification steps have appended rows to the ledger
**When** the checkpoint is produced
**Then** each ring outcome shown is taken from a row, and any ring with no row
is shown as having no recorded evidence

**Rationale**: The checkpoint currently asks the agent to write a per-ring list
of results. That is a self-report at the workflow's most load-bearing moment,
and nothing compares it against the runs that actually happened.

#### Scenario: a ring with a recorded green row is shown as green

**Given** a ledger row recording a successful run for a ring at the current
baseline
**When** the checkpoint is produced
**Then** that ring is shown as green, citing the recorded command

#### Scenario: a ring with no row is shown as unevidenced

**Given** a ring for which no row exists at the current baseline
**When** the checkpoint is produced
**Then** that ring is shown as having no recorded evidence, not as skipped and
not as green

#### Scenario: free-text ring outcomes are not accepted

**Given** an attempt to supply a ring outcome as text rather than as a row
**When** the checkpoint is produced
**Then** the supplied text does not appear as a ring outcome

### Requirement: A checkpoint reports its own evidence gaps

The checkpoint SHALL report the chain state for the spec, and a checkpoint
produced while requirements remain unresolved MUST show that fact in the
summary rather than omitting it.

**Given** a spec with unresolved requirements
**When** the checkpoint is produced
**Then** the summary carries the three counts and the unresolved list

**Rationale**: A checkpoint is the moment a human decides whether to approve
the next spec. Presenting it without the outstanding count asks for approval on
an incomplete picture.

#### Scenario: unresolved requirements appear in the summary

**Given** a spec with two unresolved requirements
**When** the checkpoint is produced
**Then** both appear in the summary

#### Scenario: a fully discharged spec shows a complete chain

**Given** a spec whose requirements are all discharged at the current baseline
**When** the checkpoint is produced
**Then** the summary shows the counts equal to the total and an empty
unresolved list

### Requirement: A stale ring outcome is never presented as current

The checkpoint SHALL only report rows whose baseline matches the revision being
checkpointed, and MUST NOT present a row from a superseded baseline as evidence
for the current one.

**Given** a spec that was re-verified after an edit, where earlier rows exist
against the previous baseline
**When** the checkpoint is produced
**Then** only rows at the current baseline are reported, and the superseded
rows are absent

**Rationale**: Reporting a pre-edit green run as current evidence is the
carried-over-verdict defect that the correctness definition forbids, occurring
at the point of maximum consequence.

#### Scenario: rows from a superseded baseline are excluded

**Given** rows at two baselines for the same ring
**When** the checkpoint is produced at the later baseline
**Then** only the later row is reported

#### Scenario: an edit after verification invalidates the outcome

**Given** a spec whose rings ran green, followed by a source edit that moves
the baseline
**When** the checkpoint is produced
**Then** the affected rings show no recorded evidence at the new baseline

### Requirement: The tracker and the derived checklist agree

The checkpoint SHALL regenerate the derived task checklist from the progress
tracker, so that the two never carry independently maintained state.

**Given** a progress tracker updated at the checkpoint
**When** the checkpoint completes
**Then** the derived checklist matches the tracker

**Rationale**: The workflow already designates one file the single source of
truth and the other derived output, because two hand-maintained trackers drift.
Generating the checkpoint is the moment to enforce that.

#### Scenario: the checklist follows the tracker

**Given** a tracker in which a spec has been marked complete
**When** the checkpoint completes
**Then** the derived checklist shows that spec complete

#### Scenario: a divergent checklist is corrected, not merged

**Given** a derived checklist that disagrees with the tracker
**When** the checkpoint completes
**Then** the checklist is regenerated from the tracker and the divergent
content does not survive

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash: enumerated finite domains in
     bats, not sampled generators. See the evidence-ledger spec's note; the
     same adaptation and the same limitation apply. -->

### Property: Every reported outcome traces to a row

**Invariant**: For any ledger and baseline, every ring outcome in the generated
checkpoint corresponds to exactly one row at that baseline, and every such row
is represented.

**Generator strategy**: Enumerated over constructed ledgers covering: no rows,
one row, one row per ring, duplicate rows for one ring, rows split across two
baselines, and rows for a different change. Constructive — expected membership
is computed from the construction.

```
forAll (ledger, baseline) in constructed:
  reported_outcomes(checkpoint) == rows(ledger, change, spec, baseline)
```

### Property: No baseline mismatch is ever reported as current

**Invariant**: For any ledger containing rows at more than one baseline, the
checkpoint at one baseline reports no row from another.

**Generator strategy**: Enumerated over ledgers built with two and three
distinct baselines, with every ring present at each, so that every possible
mismatch is exercised. Constructive.

```
forAll (ledger, b) in constructed x baselines:
  forAll r in reported(checkpoint(ledger, b)): r.baseline == b
```

### Property: Regeneration is idempotent

**Invariant**: For any tracker state, generating the derived checklist twice
produces the same content as generating it once.

**Generator strategy**: Enumerated over tracker states covering none complete,
some complete, all complete, and a tracker whose spec names contain characters
requiring escaping in the checklist format. Constructive.

```
forAll tracker in constructed_trackers:
  generate(generate(tracker)) == generate(tracker)
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every reported ring outcome traces to a ledger row | Requirement: Ring outcomes in the checkpoint come from recorded rows + Property: Every reported outcome traces to a row | property test over constructed ledgers | `checkpoint-from-ledger.bats` |
| A ring with no row shows as unevidenced, not green and not skipped | Requirement: Ring outcomes in the checkpoint come from recorded rows + Scenario: a ring with no row is shown as unevidenced | scenario test | `checkpoint-from-ledger.bats` |
| Free-text ring outcomes do not appear | Requirement: Ring outcomes in the checkpoint come from recorded rows + Scenario: free-text ring outcomes are not accepted | scenario test | `checkpoint-from-ledger.bats` |
| The summary carries the chain state counts and unresolved list | Requirement: A checkpoint reports its own evidence gaps | scenario tests, unresolved and fully discharged | `checkpoint-from-ledger.bats` |
| Rows from a superseded baseline are excluded | Requirement: A stale ring outcome is never presented as current + Property: No baseline mismatch is ever reported as current | property test over multi-baseline ledgers | `checkpoint-from-ledger.bats` |
| An edit after verification clears the affected outcomes | Requirement: A stale ring outcome is never presented as current + Scenario: an edit after verification invalidates the outcome | scenario test | `checkpoint-from-ledger.bats` |
| The derived checklist matches the tracker after a checkpoint | Requirement: The tracker and the derived checklist agree | scenario test | `checkpoint-from-ledger.bats` |
| Regeneration is idempotent | Property: Regeneration is idempotent | property test over constructed trackers | `checkpoint-from-ledger.bats` |
| A divergent checklist does not survive regeneration | Requirement: The tracker and the derived checklist agree + Scenario: a divergent checklist is corrected, not merged | scenario test | `checkpoint-from-ledger.bats` |
| The workflow's checkpoint instruction directs generation rather than authoring | Requirement: Ring outcomes in the checkpoint come from recorded rows | manual review at the Step 8 adversarial gate — whether the instruction text actually removes the authoring path is a reading judgement | Ring 8 report |
| An agent cannot satisfy the checkpoint by appending rows it did not earn | Requirement: Ring outcomes in the checkpoint come from recorded rows | manual review, explicitly accepted limit — the ledger records command and exit status, making a row re-checkable, but forgery remains possible in-process | Ring 8 report |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/scanner/checkpoint.sh` | script | scanner/ | NEW — renders the summary from ledger rows and chain state |
| `openspec/schemas/verified-scala3/schema.yaml` | workflow definition | schema root | apply Step 13 changes from "present this structure" to "generate and present"; ring list is no longer authored |
| `openspec/changes/<change>/implementation-progress.md` | tracker | change dir | single source of truth, unchanged in role |
| `openspec/changes/<change>/tasks.md` | derived output | change dir | regenerated by the checkpoint, never hand-maintained |
| `openspec/schemas/verified-scala3/tests/checkpoint-from-ledger.bats` | test | tests/ | NEW |
