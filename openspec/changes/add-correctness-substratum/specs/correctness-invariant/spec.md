# Spec: Correctness Invariant

Establishes what "correct" MEANS in this workflow, as normative text, and
repairs the two documents that currently assert a superseded rule. Ships no
executable logic — the enforcement specs depend on the definition this one
fixes.

## Concepts Used (behavioral)

This change alters the workflow that PRODUCES concepts, not any concept's
purpose, state, actions, or synchronizations. No registry concept is touched.

| Concept | Role here | File |
|---------|-----------|------|
| (none) | This spec changes workflow definition text only; `registry-check.sh` must still pass unchanged at Step 12 | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

No Scala type is read or written. The Step 12 concept delta for this spec MUST
be empty.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Correctness Invariant | normative text | The three-clause definition of "correct" plus the one-line operative rule, carried in the workflow definition |
| Prerequisite Set | declared tool list | `bash`, `git`, `jq`, `shellcheck`, `bats`, `shfmt` — replaces the superseded zero-dependency portability rule |

## ADDED Requirements

### Requirement: Correctness is defined by an unbroken evidence chain

The workflow definition SHALL define a claim of correctness as requiring an
unbroken chain of three conditions, and SHALL state that definition in the
schema description so that it is carried into every artifact instruction.

**Given** a change under this workflow with one or more requirements
**When** any claim of correctness is made about that change
**Then** the claim holds only if every requirement is bound to at least one
enforcement mechanism, each mechanism resolves to an artifact that exists, and
each artifact has been observed to run green in the current session

**Rationale**: The workflow previously defined correctness extensionally — as
"passes the rings" — which is a procedure, not a definition, and leaves an
agent with nothing decidable at the moment of making a claim.

#### Scenario: all three conditions hold

**Given** a change whose every requirement is named by an obligation, whose
every obligation names an artifact present in the tree, and whose artifacts
have all been run green in this session
**Then** the change satisfies the definition

#### Scenario: an obligation names an artifact that does not exist

**Given** a change whose requirement is bound to an obligation naming a test
suite that was never written
**Then** the change does NOT satisfy the definition, because the second
condition fails

#### Scenario: an artifact exists but was never run in this session

**Given** a change whose obligations all name artifacts present in the tree,
and where no run of those artifacts has been observed in the current session
**Then** the change does NOT satisfy the definition, because the third
condition fails, regardless of results recorded in a previous session

### Requirement: A verdict not obtained in the session is not a verdict

The workflow definition SHALL state that an assertion about repository state,
check applicability, or check outcome is a CLAIM until evidence for it is
obtained in the current session, and SHALL name the recurring phrasings that
disguise a claim as a verdict.

**Given** an agent about to record an outcome for any check
**When** the agent has not obtained that outcome from the filesystem, a
command's exit status, or a tool's output during this session
**Then** the outcome is recorded as unobtained, never as a verdict

**Rationale**: Recorded verdicts sourced from memory rather than observation
are the defect the workflow's own version history documents repeatedly. The
phrasings `N/A`, `passes`, `not applicable`, and `already handled` are the
observed disguises and must be named explicitly, because a general instruction
to "verify" has demonstrably not prevented them.

#### Scenario: applicability asserted without looking

**Given** a repository that contains a behavioural concept registry
**When** an agent records the altitude check as not applicable without reading
the filesystem
**Then** the record is a claim, not a verdict, and the definition is violated

#### Scenario: outcome carried over from a previous session

**Given** a check that ran green in a previous session and has not been run in
this one
**When** an agent records it as passing
**Then** the record is a claim, not a verdict

### Requirement: The prerequisite set replaces the zero-dependency rule

The workflow definition SHALL declare an explicit prerequisite tool set, and
the two documents asserting the superseded zero-dependency rule SHALL be
amended so that no workflow document states a rule the workflow does not
follow.

**Given** the hook documentation and the session-gate script header, both of
which assert that the workflow depends on nothing beyond bash and git and must
never use a JSON processor
**When** this spec is implemented
**Then** both documents state the declared prerequisite set instead, and no
workflow document asserts the superseded rule

**Rationale**: A documented rule that the practice contradicts is the drift
class this change exists to remove. Shipping the change while leaving the
contradiction in place would introduce an instance of the defect it targets.

#### Scenario: no document asserts the superseded rule

**Given** the implemented change
**When** the workflow's documentation is searched for an assertion that a JSON
processor must never be used
**Then** no such assertion remains

#### Scenario: the prerequisite set is discoverable

**Given** a contributor reading the hook documentation
**When** they look for what must be installed
**Then** the six declared prerequisites are listed, each with the ring or
function that requires it

### Requirement: A superseded constraint is never inherited without retest

The workflow definition SHALL require that a recorded limitation, exclusion, or
"known issue" carries the date and the mechanism by which it was established,
and SHALL require that it be re-established before being relied upon in a later
change.

**Given** a living document recording that some tool or check does not work
**When** a later change relies on that record to choose a weaker method
**Then** the record is re-tested first, and the outcome of that retest replaces
the record

**Rationale**: The project's concept inventory recorded that its semantic
scanner returned no results and that manual scanning was authoritative. The
underlying defect was fixed in a later schema version, but the recorded
consequence was inherited unexamined, so subsequent verifications used the
weaker method while the working one sat unused.

#### Scenario: a recorded limitation is re-tested and found obsolete

**Given** a living document recording that a scanner produces no results on
this project
**When** a change relies on that record
**Then** the scanner is run before the record is trusted, and the record is
corrected when the run succeeds

#### Scenario: a recorded limitation carries no date or mechanism

**Given** a recorded limitation with no date and no statement of how it was
established
**When** a later change encounters it
**Then** it is treated as unestablished and re-tested, not inherited

## Properties (Ring 3)

<!-- ADAPTATION NOTE — property testing in bash.
     The detected property framework (Hedgehog) is Scala-only and this spec
     ships no Scala. There is no generator/shrinker facility for bash, so a
     "property" here is enumerated over a finite, explicitly listed input
     domain in bats rather than sampled from a generator. Each property below
     therefore declares its ENUMERATION as its generator strategy, and states
     the domain exhaustively. Where a domain is unbounded in principle, the
     enumeration is a stated finite subset and that limitation is recorded in
     the obligation, not hidden. This adaptation is itself an obligation:
     claiming Hedgehog-style coverage for bash tests would be an oracle
     misstatement. -->

### Property: No workflow document asserts the superseded dependency rule

**Invariant**: For every markdown and shell file under the workflow definition
directory, the file does not assert that a JSON processor is forbidden.

**Generator strategy**: Enumerated — every tracked `*.md` and `*.sh` file under
the workflow definition directory, discovered at test time rather than listed,
so a newly added file is covered automatically. Edge cases covered: files that
mention the processor in a permitted context (the prerequisite table itself),
which must not be flagged.

```
forAll file in tracked(schema_dir, "*.md" | "*.sh"):
  not asserts_forbidden(file, json_processor)
```

### Property: Every declared prerequisite is justified by a named consumer

**Invariant**: For every entry in the declared prerequisite set, the workflow
definition names at least one ring or function requiring it.

**Generator strategy**: Enumerated over the six declared prerequisites, read
from the prerequisite table at test time rather than hard-coded, so adding a
prerequisite without a justification fails the property. Edge case covered: a
prerequisite listed with an empty justification cell.

```
forAll tool in declared_prerequisites():
  exists justification(tool) and justification(tool) is non-empty
```

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The three-clause definition appears in the workflow definition and is carried into artifact instructions | Requirement: Correctness is defined by an unbroken evidence chain | scenario test asserting the text is present and reachable from the schema description | `correctness-invariant.bats` |
| A change failing any one clause does not satisfy the definition | Requirement: Correctness is defined by an unbroken evidence chain + Scenario: an obligation names an artifact that does not exist | scenario test per failing clause | `correctness-invariant.bats` |
| The four disguising phrasings are named explicitly in the definition text | Requirement: A verdict not obtained in the session is not a verdict | scenario test asserting each phrase appears | `correctness-invariant.bats` |
| No workflow document asserts the superseded dependency rule | Requirement: The prerequisite set replaces the zero-dependency rule + Property: No workflow document asserts the superseded dependency rule | property test enumerated over tracked workflow files | `correctness-invariant.bats` |
| The prerequisite set is stated with a consumer for each entry | Requirement: The prerequisite set replaces the zero-dependency rule + Property: Every declared prerequisite is justified by a named consumer | property test enumerated over the prerequisite table | `correctness-invariant.bats` |
| A recorded limitation carries a date and an establishing mechanism | Requirement: A superseded constraint is never inherited without retest | scenario test over the living documents' limitation entries | `correctness-invariant.bats` |
| The retest rule is stated in the artifact instructions that read living documents | Requirement: A superseded constraint is never inherited without retest | manual review at the Step 8 adversarial gate — the rule governs agent behaviour, which no test in this spec can observe | Ring 8 report |
| The workflow definition remains loadable after the text changes | Requirement: Correctness is defined by an unbroken evidence chain | scenario test asserting the definition file parses and the change status still resolves | `correctness-invariant.bats` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `openspec/schemas/verified-scala3/schema.yaml` | workflow definition | schema root | `description` gains the three-clause definition; version → 12; changelog entry |
| `openspec/schemas/verified-scala3/templates/spec.md` | template | templates/ | carries the operative rule into every new spec |
| `openspec/schemas/verified-scala3/hooks/README.md` | doc | hooks/ | replace the zero-dependency rule with the prerequisite set |
| `openspec/schemas/verified-scala3/hooks/gate.sh` | script header comment | hooks/ | same; comment only, no behaviour change in this spec |
| `openspec/schemas/verified-scala3/ci/*.yml` | CI | ci/ | install the prerequisites; add `shellcheck` and `bats` steps |
| `openspec/schemas/verified-scala3/tests/` | test layout | schema root | NEW — establishes the `.bats` location and naming convention; no such layout exists today |
| `bats`, `shellcheck`, `shfmt`, `jq` | prerequisites | host | verified installed at capability-check Pass 2 |
