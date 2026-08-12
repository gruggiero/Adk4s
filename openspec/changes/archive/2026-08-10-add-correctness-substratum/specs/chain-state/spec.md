# Spec: Chain State

Computes the correctness definition's three clauses as a number that can be
read at any instant: how many requirements are bound, resolved, and discharged
— and which ones are not.

Depends on: `correctness-invariant` (the definition), `evidence-ledger`
(supplies the discharged clause).

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
| Chain State | derived metric | Counts of bound, resolved and discharged requirements for a change, plus the list of those that are not |

## ADDED Requirements

### Requirement: Chain state reports all three clauses separately

Chain state SHALL report the count of requirements that are bound, resolved and
discharged as three distinct figures, and MUST NOT collapse them into a single
pass or fail verdict.

**Given** a change with requirements at differing stages of verification
**When** chain state is computed
**Then** the report carries three separate counts against a total, so a change
that is fully bound but undischarged is distinguishable from one that is fully
discharged

**Rationale**: A single verdict is what the workflow already has, and it is
what let a pre-implementation change and a fully verified one both read as
clean. The three clauses fail for different reasons and must be visible
separately.

#### Scenario: bound but not resolved

**Given** a change whose every requirement is named by an obligation, where
some obligations name artifacts that do not exist
**When** chain state is computed
**Then** the bound count equals the total and the resolved count is lower

#### Scenario: resolved but not discharged

**Given** a change whose obligations all name existing artifacts, with no runs
recorded for the current baseline
**When** chain state is computed
**Then** the resolved count equals the total and the discharged count is zero

#### Scenario: a single verdict is not emitted in place of the counts

**Given** any change
**When** chain state is computed
**Then** the report contains the three counts, and does not replace them with a
lone pass or fail

### Requirement: Unresolved entries are named, not just counted

Chain state SHALL list every requirement that is not bound, not resolved, or
not discharged, naming the requirement and the reason it failed its clause.

**Given** a change with three requirements failing different clauses
**When** chain state is computed
**Then** each of the three is listed by name with its failing clause and reason

**Rationale**: A count tells an agent that something is wrong; a named list
tells it what to do. The count alone would be a slogan of the kind this change
exists to avoid.

#### Scenario: each failing clause produces a distinct reason

**Given** one requirement named by no obligation, one whose obligation names an
absent artifact, and one whose artifact has no run recorded
**When** chain state is computed
**Then** the three appear with reasons distinguishing unbound, unresolved and
undischarged

#### Scenario: a fully satisfied change lists nothing

**Given** a change whose every requirement is bound, resolved and discharged
**When** chain state is computed
**Then** the unresolved list is empty and the counts are equal to the total

### Requirement: Chain state derives its first two clauses from the existing lint

Chain state SHALL obtain the bound and resolved clauses from the existing
specification lint rather than reimplementing them, so that a chain state
report and a lint run can never disagree.

**Given** the specification lint, which already determines requirement to
obligation reachability and artifact resolution
**When** chain state is computed
**Then** the bound and resolved figures come from that lint's output

**Rationale**: The workflow already made this decision once for the session
gate, which delegates its applicability facts to the lint rather than
recomputing them, on the grounds that a second implementation could disagree
with the first. The same reasoning applies here.

#### Scenario: chain state and lint agree on an unbound requirement

**Given** a change containing a requirement no obligation names
**When** both the lint and chain state are run
**Then** both report that requirement as unbound

#### Scenario: chain state and lint agree on an unresolved artifact

**Given** a change containing an obligation naming an artifact that does not
exist
**When** both the lint and chain state are run
**Then** both report that obligation as unresolved

### Requirement: Absence of evidence is never reported as satisfaction

Chain state SHALL report a change with no ledger, an unreadable ledger, or a
lint that failed to run as having undetermined evidence, and MUST NOT report
any such condition as a discharged count.

**Given** a change for which the evidence source cannot be read
**When** chain state is computed
**Then** the report states that evidence is undetermined and returns a non-zero
status, rather than reporting zero discharged as though it were a measurement

**Rationale**: This is the defect class the whole change targets, applied to
the change's own tooling. A tool that reports "0 discharged" identically when
nothing ran and when the ledger is corrupt would reproduce it.

#### Scenario: an unreadable ledger yields undetermined, not zero

**Given** a corrupt ledger
**When** chain state is computed
**Then** the report states evidence is undetermined and the status is non-zero

#### Scenario: a failed lint yields undetermined, not zero

**Given** a specification lint that exits non-zero for a reason other than lint
findings
**When** chain state is computed
**Then** the report states evidence is undetermined and the status is non-zero

#### Scenario: a genuinely empty ledger is reported as zero discharged

**Given** a readable ledger containing no rows for this change
**When** chain state is computed
**Then** the discharged count is zero and the status reflects a successful
measurement, distinct from the undetermined case

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash: enumerated finite domains in
     bats, not sampled generators. See the evidence-ledger spec's note; the
     same adaptation and the same limitation apply here. -->

### Property: Counts never exceed the total and are monotone across clauses

**Invariant**: For any change, discharged is at most resolved, resolved is at
most bound, and bound is at most the requirement total.

**Generator strategy**: Enumerated over constructed changes covering every
ordering of the three clause populations: all-satisfied, none-satisfied, and
each single clause failing in isolation, plus a change with zero requirements.
Constructive — each change is built with a known expected triple.

```
forAll change in constructed_changes:
  0 <= discharged(change) <= resolved(change) <= bound(change) <= total(change)
```

### Property: The unresolved list and the counts agree

**Invariant**: For any change, the number of entries in the unresolved list
equals the total minus the discharged count.

**Generator strategy**: Enumerated over the same constructed change set, whose
expected unresolved membership is known from the construction rather than read
from the tool. Edge cases covered: zero requirements, all failing one clause,
and one requirement failing two clauses at once, which must be listed once.

```
forAll change in constructed_changes:
  size(unresolved(change)) == total(change) - discharged(change)
```

### Property: Unreadable evidence never yields a satisfied report

**Invariant**: For any corruption of the evidence sources, the report is
undetermined with non-zero status; it is never a successful report of
satisfaction.

**Generator strategy**: Enumerated over the corruption set from the
evidence-ledger spec, plus an absent lint executable and a lint returning a
non-finding error. Constructive — each condition is induced deliberately.

```
forAll condition in {ledger corruptions} + {lint absent, lint errored}:
  status(chain_state(condition)) != 0 and report is undetermined
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Three counts are reported separately against a total | Requirement: Chain state reports all three clauses separately | scenario tests per clause combination | `chain-state.bats` |
| A lone verdict is not emitted in place of the counts | Requirement: Chain state reports all three clauses separately + Scenario: a single verdict is not emitted in place of the counts | scenario test asserting the report shape | `chain-state.bats` |
| Counts are monotone and bounded by the total | Property: Counts never exceed the total and are monotone across clauses | property test over constructed changes | `chain-state.bats` |
| Every failing requirement is listed with its failing clause and reason | Requirement: Unresolved entries are named, not just counted | scenario test with one requirement per failing clause | `chain-state.bats` |
| The unresolved list size agrees with the counts | Property: The unresolved list and the counts agree | property test over constructed changes | `chain-state.bats` |
| A requirement failing two clauses is listed once | Property: The unresolved list and the counts agree | property test edge case | `chain-state.bats` |
| Bound and resolved come from the existing lint, not a reimplementation | Requirement: Chain state derives its first two clauses from the existing lint | scenario tests asserting agreement between the two tools on the same input | `chain-state.bats` |
| No second implementation of reachability or artifact resolution exists | Requirement: Chain state derives its first two clauses from the existing lint | manual review at the Step 8 adversarial gate — a duplicated implementation is a property of the source, which no output test can observe | Ring 8 report |
| Unreadable evidence yields undetermined with non-zero status | Requirement: Absence of evidence is never reported as satisfaction + Property: Unreadable evidence never yields a satisfied report | property test over induced failure conditions | `chain-state.bats` |
| An empty ledger is distinguished from an unreadable one | Requirement: Absence of evidence is never reported as satisfaction + Scenario: a genuinely empty ledger is reported as zero discharged | scenario test | `chain-state.bats` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/scanner/chain-state.sh` | script | scanner/ | NEW — computes and prints the triple plus the unresolved list |
| `openspec/schemas/verified-scala3/scanner/spec-lint.sh` | existing script | scanner/ | source of the bound clause (F7) and resolved clause (F9, `--artifacts`); NOT reimplemented |
| `openspec/schemas/verified-scala3/scanner/ledger.sh` | script | scanner/ | source of the discharged clause; shipped by the `evidence-ledger` spec |
| `openspec/schemas/verified-scala3/tests/chain-state.bats` | test | tests/ | NEW |
