# completion-witness-refusal Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: A turn is refused when a green result has no corroboration

The completion tier SHALL refuse turn completion when the change's evidence record
contains a result recorded as green for which no independent corroborating observation
exists, and it MUST NOT allow completion in that state.

**Given** a change whose evidence record contains a green result at the current baseline
**When** turn completion is attempted and no corroborating observation for that result
exists in the record
**Then** completion is refused, and the refusal names the uncorroborated result

**Applicability** (recorded at apply Ring 8): the refusal is scoped to a turn that
*presents* a completion claim — the per-session checkpoint-presentation marker the
harness writes before the Stop event. A session with no presentation marker makes no
completion claim, so mid-work stops pass silently; this precondition is predecessor
behaviour, not a relaxation of the refusal. Likewise the requirement quantifies over
*enumerable* changes: a changes directory that cannot be enumerated yields no scan
subjects, the same empty iteration the predecessor's `changes/*/` glob produces.

**Rationale**: a green result recorded by the party that produced it is a self-report. The
corroboration requirement is what makes the record evidence rather than testimony. The
predecessor refuses here; the port allows, which removes the tier's only teeth.

#### Scenario: Happy path — every green result is corroborated and completion proceeds

**Given** a change whose every green result carries a corroborating observation
**When** turn completion is attempted
**Then** completion proceeds

#### Scenario: Adversarial — a single uncorroborated green result refuses the turn

**Given** a change whose evidence record contains one green result with no corroborating
observation, alongside several corroborated ones
**When** turn completion is attempted
**Then** completion is refused and the refusal names that one result

#### Scenario: Edge case — a non-green result needs no corroboration

**Given** a change whose evidence record contains a red result with no corroborating
observation
**When** turn completion is attempted
**Then** completion proceeds — the corroboration requirement binds green results only

#### Scenario: Error path — an unreadable evidence record allows completion with a stated reason

**Given** a change whose evidence record cannot be read
**When** turn completion is attempted
**Then** completion proceeds and the tier states that the corroboration check could not be
performed, naming the unreadable record

### Requirement: The corroboration check fails open and says so

When the state the refusal depends on cannot be read, the tier SHALL allow completion and
state the reason, and it MUST NOT refuse on unread state.

**Given** a completion attempt whose corroboration state is unavailable — the record, the
baseline, or the per-session state area cannot be read
**When** the tier decides
**Then** completion is allowed, and the output names which input could not be read

**Rationale**: a hook that refuses on state it could not read converts an environment
fault into a blocked session. Failing open with a stated reason keeps the failure visible
without making it fatal — the same discipline every other tier already follows.

#### Scenario: Happy path — an unavailable state area allows with a named reason

**Given** a completion attempt in a repository with no resolvable per-session state area
**When** the tier decides
**Then** completion is allowed and the output names the state area as unavailable

#### Scenario: Adversarial — an unreadable record does not produce a refusal

**Given** a completion attempt whose evidence record exists but cannot be parsed
**When** the tier decides
**Then** completion is allowed, and the outcome is not a refusal

### Requirement: At most one refusal is issued per turn

The tier SHALL issue no more than one refusal within a single turn, and a second
completion attempt in the same turn MUST NOT produce a second refusal.

**Given** a turn in which the tier has already refused completion once
**When** completion is attempted again within that same turn
**Then** completion proceeds

**Rationale**: an unbounded refusal loop is indistinguishable from a hung session. The
bound already exists for the other tiers; the restored refusal must respect it.

#### Scenario: Happy path — the first refusal in a turn is issued

**Given** a turn with an uncorroborated green result and no prior refusal
**When** completion is attempted
**Then** completion is refused

#### Scenario: Adversarial — a second attempt in the same turn is not refused

**Given** a turn in which a refusal has already been issued
**When** completion is attempted a second time with the same uncorroborated result
**Then** completion proceeds

#### Scenario: Edge case — a new turn refuses again

**Given** a new turn with the same uncorroborated green result
**When** completion is attempted
**Then** completion is refused — the bound is per turn, not per change

