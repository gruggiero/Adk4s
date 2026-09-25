# chain-state-undetermined-fidelity Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: A verdict is produced only from a completed pre-pass

The correctness verdict SHALL be computed only from a mechanical pre-pass that ran to
completion, and a pre-pass that did not run MUST NOT yield bound, resolved or discharged
counts.

**Given** a change whose mechanical pre-pass is invoked
**When** the pre-pass completes with a result
**Then** the verdict is computed from that result and carries the three counts

**When** the pre-pass does not complete — it is absent, it cannot be executed, or it exits
outside its declared outcome range
**Then** the outcome is could-not-determine carrying the stated reason, and no counts are
emitted

**Rationale**: the predecessor returns could-not-determine here. The port returns a
finding with a report asserting counts it derived from nothing, which is a claim
outrunning its evidence inside the tool whose purpose is to detect exactly that.

#### Scenario: Happy path — a completed pre-pass yields a verdict with counts

**Given** a change whose pre-pass completes and reports one bound, resolved and discharged
requirement
**When** the verdict is computed
**Then** the outcome is ran-and-found-nothing, and the report carries total 1, bound 1,
resolved 1, discharged 1

#### Scenario: Error path — a pre-pass that cannot be executed is could-not-determine

**Given** a change whose pre-pass cannot be executed at all
**When** the verdict is computed
**Then** the outcome is could-not-determine naming the pre-pass as the reason, and no
report is emitted

#### Scenario: Adversarial — a pre-pass exiting outside its outcome range emits no counts

**Given** a change whose pre-pass terminates with a status outside its declared
outcome range
**When** the verdict is computed
**Then** the outcome is could-not-determine, and the output contains no total, bound,
resolved or discharged figure

#### Scenario: Adversarial — a non-completed pre-pass cannot produce a report

**Given** a caller holding a pre-pass outcome that did not complete
**When** the caller attempts to build a verdict report from it
**Then** compilation fails: the report's constructor is not reachable from that outcome

### Requirement: Every distinct could-not-determine reason is named

Each could-not-determine outcome SHALL carry a reason that names which input could not be
read, and a reason MUST NOT be reported as a generic failure.

**Given** a verdict computation that cannot proceed
**When** the outcome is emitted
**Then** the reason names the specific unreadable input — the pre-pass, the evidence
record, the change directory, or the baseline

#### Scenario: Happy path — an unreadable evidence record names the evidence record

**Given** a change whose evidence record cannot be read
**When** the verdict is computed
**Then** the outcome is could-not-determine naming the evidence record

#### Scenario: Edge case — an empty but readable evidence record is a measurement, not an unknown

**Given** a change whose evidence record is present, readable and empty
**When** the verdict is computed
**Then** the outcome is a successful measurement reporting zero discharged, not
could-not-determine

#### Scenario: Adversarial — a reason that names nothing is not emitted

**Given** a verdict computation that cannot proceed for an unclassified cause
**When** the outcome is emitted
**Then** the reason still names the input under inspection rather than a bare failure
marker

### Requirement: The exit status distinguishes could-not-determine from a finding

The tool SHALL exit with its could-not-determine status when no verdict could be computed
and with its finding status only when a verdict was computed and something was found, and
the two MUST NOT be collapsed.

**Given** a verdict computation
**When** the process exits
**Then** a completed verdict finding something exits with the finding status, a completed
verdict finding nothing exits with the clean status, and a computation that produced no
verdict exits with the could-not-determine status

#### Scenario: Happy path — a satisfied change exits clean

**Given** a change whose every requirement is bound, resolved and discharged
**When** the tool runs
**Then** it exits with the clean status and the report's unresolved list is empty

#### Scenario: Adversarial — an uncomputable verdict does not exit with the finding status

**Given** a change whose pre-pass did not complete
**When** the tool runs
**Then** it exits with the could-not-determine status, and not with the finding status

