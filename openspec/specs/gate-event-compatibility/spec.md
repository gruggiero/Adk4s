# gate-event-compatibility Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: A recognised event name dispatches to its tier

Each of the six recognised event names SHALL dispatch to its corresponding tier, and no
recognised name MAY be routed to the injection tier.

**Given** a supplied event name that matches one of the six recognised names
**When** the gate dispatches
**Then** the corresponding tier runs

#### Scenario: Happy path — each recognised name reaches its tier

**Given** each of the six recognised event names in turn
**When** the gate dispatches
**Then** each reaches its own tier and no two names reach the same tier

#### Scenario: Adversarial — a recognised name is not absorbed by the injection tier

**Given** the recognised name for the pre-execution tier
**When** the gate dispatches
**Then** the pre-execution tier runs and the injection tier does not

### Requirement: An unrecognised event name routes to the injection tier

An event name that is not one of the six recognised names SHALL route to the context
injection tier and terminate with the clean status, and it MUST NOT terminate with an
error status.

**Given** a supplied event name that is not one of the six recognised names
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

**Rationale**: the predecessor behaves this way, and the harness adapters depend on it —
one harness names the prompt event `user-prompt-submit` while the gate's own vocabulary
calls it `prompt-submit`. Under the port that name produces an error status, which the
harness treats as a hook fault: the gate does not run at all. Restoring the fallback keeps
the vocabulary mismatch survivable while leaving it visible in the diagnostic output.

#### Scenario: Happy path — the alternate prompt-event name injects context

**Given** the alternate harness name for the prompt event
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

#### Scenario: Adversarial — an arbitrary unrecognised name does not error

**Given** an event name that no harness uses and that matches none of the six
**When** the gate dispatches
**Then** the process terminates with the clean status, not with an error status

#### Scenario: Edge case — an empty event name routes to the injection tier

**Given** an empty event name
**When** the gate dispatches
**Then** the injection tier runs and the process terminates with the clean status

### Requirement: The supplied name survives into the diagnostic output

The diagnostic output SHALL name the supplied value whenever an event name routes to the
injection tier because it was not recognised, and the fallback MUST NOT be silent.

**Given** an unrecognised event name routed to the injection tier
**When** the diagnostic output is produced
**Then** it states that the supplied name was not recognised and reproduces that name

**Rationale**: the predecessor's fallback is silent, which is why the vocabulary drift
between the adapters and the gate went unnoticed until it was measured. Restoring the
fallback without restoring the silence is not a verdict change — the tier that runs and
the exit status are unchanged; only the diagnostic gains a line.

#### Scenario: Happy path — an unrecognised name is named in the diagnostic

**Given** an unrecognised event name
**When** the gate runs
**Then** the diagnostic output contains that name

#### Scenario: Adversarial — a recognised name produces no unrecognised-name diagnostic

**Given** a recognised event name
**When** the gate runs
**Then** the diagnostic output contains no unrecognised-name line

