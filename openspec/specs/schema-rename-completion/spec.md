# schema-rename-completion Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: Installed instruction documents carry the current stamp

Every instruction document installed into a searched root SHALL carry the schema's current
name and version, and a document MUST NOT carry a pre-rename stamp after this change.

**Given** the searched install roots
**When** each installed instruction document's stamp is read
**Then** each names the current schema and the current version

**Rationale**: the drift detector reports these stamps correctly at every session start —
the detector works and the documents were never regenerated. The stamp is what the detector
compares against, so a stale stamp means every session opens with a warning that nothing
acts on.

#### Scenario: Happy path — every installed document carries the current stamp

**Given** the searched install roots after the installer has run
**When** each stamp is read
**Then** each names the current schema and version

#### Scenario: Adversarial — a pre-rename stamp is still reported as migration, not drift

**Given** a root carrying a document with a pre-rename stamp
**When** the drift scan runs
**Then** it reports a migration message naming that root, and does not report it as a
version mismatch

#### Scenario: Error path — an unreadable root is could-not-determine, not absent

**Given** a searched root that exists but cannot be read
**When** the drift scan runs
**Then** it reports that root as unreadable, and does not report it as carrying no
document

### Requirement: The cache and state directory migration runs on first use

The cache and state directory migration SHALL be invoked by the shipped tool, and its
verified logic MUST NOT remain without a caller.

**Given** a first invocation in an environment carrying the previous directory
**When** the tool runs
**Then** the previous directory's contents are moved to the current one

**Given** a subsequent invocation
**When** the tool runs
**Then** no migration is performed and the current directory is unchanged

**Rationale**: the migration function is implemented, unit-tested and formally reachable,
and nothing calls it. A verified function with no caller discharges nothing — it is the
same shape of gap as a specification with no implementation, in the opposite direction.

#### Scenario: Happy path — a previous directory is migrated once

**Given** an environment carrying the previous directory and no current one
**When** the tool runs
**Then** the contents appear under the current directory

#### Scenario: Happy path — a second run performs no migration

**Given** the environment after that migration
**When** the tool runs again
**Then** nothing is moved

#### Scenario: Edge case — a fresh environment with neither directory migrates nothing

**Given** an environment carrying neither directory
**When** the tool runs
**Then** no migration is performed and no error is reported

#### Scenario: Adversarial — a previous directory is not migrated over an existing current one

**Given** an environment carrying both the previous and the current directory
**When** the tool runs
**Then** the current directory's contents are unchanged and nothing is overwritten

### Requirement: The shipped documents and templates use the current name

The tutorial documents and the shipped continuous-integration templates SHALL use the
schema's current name, and they MUST NOT present the previous name as current.

**Given** the shipped tutorial documents and continuous-integration templates
**When** their content is read
**Then** the current name is used where the schema is named as itself

**Given** a passage describing the rename or the pre-rename identity
**When** its content is read
**Then** the previous name may appear, marked as the former identity

#### Scenario: Happy path — the tutorial names the current schema

**Given** the tutorial's entry document
**When** its title and body are read
**Then** they name the current schema

#### Scenario: Adversarial — no template presents the previous name as current

**Given** each shipped continuous-integration template
**When** its content is read
**Then** no occurrence of the previous name is presented as the schema's current name

#### Scenario: Edge case — the changelog retains the previous name as history

**Given** the changelog's entry describing the rename
**When** its content is read
**Then** the previous name appears, marked as the pre-rename identity

### Requirement: The acceptance suite asserts the schema's actual version

The acceptance suite SHALL assert the version the schema declares, and it MUST NOT assert a
version the schema no longer carries.

**Given** the acceptance suite's schema-version assertion
**When** it runs against the schema
**Then** it asserts the declared version and passes

#### Scenario: Happy path — the assertion matches the declared version

**Given** the schema at its current version
**When** the assertion runs
**Then** it passes

#### Scenario: Adversarial — the assertion fails against a different version

**Given** a schema declaring a version other than the asserted one
**When** the assertion runs
**Then** it fails naming both versions

### Requirement: The directory rename is deferred with its coupling recorded

The schema's directory SHALL NOT be renamed by this change, and the deferral MUST be
recorded with the coupling that blocks it.

**Given** this change
**When** the schema's directory is inspected
**Then** it retains its current name

**Given** the recorded deferral
**When** it is read
**Then** it names the coupling: the workflow tool resolves a schema by its directory name,
the project configuration pins that name, and every archived change records it — so
renaming the directory without a resolution alias breaks schema resolution for all of them

**Rationale**: this is not an oversight being deferred but a dependency being respected.
Recording the coupling is what turns the deferral into an inheritable decision rather than a
problem the next change rediscovers.

#### Scenario: Happy path — the deferral names the coupling and the blocked items

**Given** the recorded deferral
**When** it is read
**Then** it names the directory rename, the resolution coupling, and the count of recorded
changes that pin the current name

#### Scenario: Adversarial — a deferral without a recorded reason is not accepted

**Given** a deferral entry carrying no reason
**When** the deferral record is checked
**Then** the check reports that entry as incomplete

