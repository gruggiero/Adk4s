# feature-freeze-guard-integrity Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: The guard locates its corpus wherever the change resides

The guard SHALL locate its specification corpus whether the change is active or archived,
and it MUST NOT depend on a single fixed location.

**Given** a change carrying specification fixtures
**When** the guard resolves its corpus
**Then** the corpus is found whether the change is in the active area or the archived area

**Rationale**: archiving is a normal, expected step in the workflow's own lifecycle. A
guard that a normal lifecycle step breaks is a guard that will be broken most of the time.

#### Scenario: Happy path — an active change's corpus resolves

**Given** a change in the active area carrying specification fixtures
**When** the guard resolves its corpus
**Then** the corpus is found

#### Scenario: Happy path — an archived change's corpus resolves

**Given** the same change moved to the archived area
**When** the guard resolves its corpus
**Then** the corpus is found and contains the same fixtures

#### Scenario: Adversarial — a corpus present in neither location is not silently accepted

**Given** a change name that exists in neither the active nor the archived area
**When** the guard resolves its corpus
**Then** the resolution reports not-found naming every location searched

### Requirement: An empty corpus is could-not-determine, never a pass

When the guard's corpus cannot be resolved or resolves empty, the guard SHALL report
could-not-determine, and it MUST NOT report the freeze as upheld.

**Given** a guard run whose corpus is unresolvable or empty
**When** the guard reports
**Then** the outcome is could-not-determine naming the corpus, and the outcome is not
freeze-upheld

**Rationale**: this is the same boundary the correctness verdict enforces, applied to the
guard itself. A guard that passes when it has nothing to check is indistinguishable from
a guard that checked and found nothing wrong — and the second is a claim the first has not
earned.

#### Scenario: Adversarial — an unresolvable corpus does not report the freeze upheld

**Given** a guard run whose corpus resolves to nothing
**When** the guard reports
**Then** the outcome is could-not-determine, and not freeze-upheld

#### Scenario: Adversarial — a resolved but empty corpus does not report the freeze upheld

**Given** a guard run whose corpus resolves to a location containing no fixtures
**When** the guard reports
**Then** the outcome is could-not-determine naming the empty corpus

#### Scenario: Adversarial — an empty corpus cannot be constructed

**Given** a caller building a corpus from an empty fixture list
**When** the code is compiled
**Then** compilation fails: the corpus type has no constructor accepting an empty list

### Requirement: The check-identifier set stays closed across the port

The guard SHALL reject any check identifier outside the recorded closed set, and a new
identifier MUST NOT be accepted as a pass.

**Given** the recorded closed set of check identifiers
**When** the ported implementation's emitted identifiers are compared against it
**Then** an identifier outside the set is a freeze violation

#### Scenario: Happy path — the ported implementation emits only known identifiers

**Given** the ported implementation run over the corpus
**When** its emitted identifiers are collected
**Then** every identifier is in the recorded closed set

#### Scenario: Adversarial — an unknown identifier is a violation

**Given** an implementation emitting an identifier outside the recorded set
**When** the guard runs
**Then** the guard reports a freeze violation naming that identifier

### Requirement: Verdict stability is measured against the predecessor

For every fixture in the corpus, the ported implementation's verdict SHALL equal the
predecessor implementation's verdict for that fixture.

**Given** a fixture in the corpus
**When** both implementations produce a verdict for it
**Then** the two verdicts are equal

#### Scenario: Happy path — every fixture's verdict agrees

**Given** the full corpus
**When** both implementations run
**Then** every fixture's verdicts agree

#### Scenario: Adversarial — a differing verdict is reported with both values

**Given** a fixture on which the two implementations disagree
**When** the guard runs
**Then** the guard reports a violation naming the fixture, the predecessor's verdict and
the ported verdict

