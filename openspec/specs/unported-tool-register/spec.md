# unported-tool-register Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: Every tool in the tree is either on the ported surface or in the register

Each executable tool in the workflow's tool directories SHALL be classified as either
named on the ported tool surface or recorded in the register, and a tool MUST NOT be
absent from both.

**Given** the set of executable tools in the workflow's tool directories
**When** each is classified
**Then** each is either named on the ported surface or present in the register

**Rationale**: the traceability tool was scoped out of the port, was not on the ported
surface, and appeared in no checked record — so nothing noticed that the correctness
verdict depended on it. The register's value is not the list; it is that the classification
is total and checked, so the next tool cannot fall into the same gap.

#### Scenario: Happy path — every present tool classifies

**Given** the current tool directories
**When** each executable is classified
**Then** each classifies as ported or registered

#### Scenario: Adversarial — a tool in neither is reported

**Given** a tool directory containing an executable named on no ported surface and in no
register entry
**When** the classification runs
**Then** the check reports that tool as unclassified and terminates with the finding
status

#### Scenario: Adversarial — a third classification is unconstructible

**Given** a caller attempting to classify a tool as neither ported nor registered
**When** the code is compiled
**Then** compilation fails: the classification type has exactly two variants

#### Scenario: Error path — an unreadable tool directory is could-not-determine

**Given** a tool directory that cannot be read
**When** the classification runs
**Then** the outcome is could-not-determine naming that directory, and no tool is reported
as unclassified on the strength of an unread directory

### Requirement: Every register entry states what blocks its port

Each register entry SHALL name what blocks the tool's port, drawn from the closed set of
blockers, and an entry MUST NOT record a tool without a blocker.

**Given** a register entry
**When** it is read
**Then** it names the tool, its location, its blocker, and the workflow instructions that
still cite it

#### Scenario: Happy path — an entry names its blocker and citations

**Given** a register entry for a tool blocked on an unrun investigation
**When** the entry is read
**Then** it names that investigation as the blocker and lists the instructions citing the
tool

#### Scenario: Adversarial — an entry without a blocker is unconstructible

**Given** a caller building a register entry with no blocker
**When** the code is compiled
**Then** compilation fails: the entry type requires the blocker

#### Scenario: Adversarial — a blocker outside the closed set is unconstructible

**Given** a caller building a register entry with a free-text blocker
**When** the code is compiled
**Then** compilation fails: the blocker is a closed enumeration, not text

### Requirement: A registered tool's citations resolve

Each workflow instruction a register entry cites SHALL resolve to a document present in
the tree, and a citation MUST NOT name a document that does not exist.

**Given** a register entry's citation list
**When** each citation is resolved against the tree
**Then** each resolves to a present document

**Rationale**: the register's citations are what make the blast radius of a future port
visible. A citation that does not resolve overstates or understates that radius, and a
register nobody can trust is worse than none.

#### Scenario: Happy path — every citation resolves

**Given** the register's entries
**When** each citation is resolved
**Then** each resolves

#### Scenario: Adversarial — a citation naming an absent document is reported

**Given** a register entry citing a document not present in the tree
**When** the register is checked
**Then** the check names the entry and the unresolvable citation

### Requirement: A tool that becomes ported leaves the register

When a tool gains an implementation on the ported surface, its register entry SHALL be
removed, and a tool MUST NOT be both named on the ported surface and present in the
register.

**Given** a tool named on the ported surface
**When** the register is checked
**Then** that tool has no register entry

**Rationale**: a tool recorded as unported while being ported is the dual-implementation
condition the migration protocol already forbids for seams, applied to the record rather
than the seam. This change ports the traceability tool, so its removal from the register is
this requirement's first exercise.

#### Scenario: Happy path — the newly ported tool has no register entry

**Given** the traceability tool, ported by this change
**When** the register is checked
**Then** it has no register entry

#### Scenario: Adversarial — a tool both ported and registered is reported

**Given** a tool named on the ported surface and also present in the register
**When** the register is checked
**Then** the check names that tool as doubly classified and terminates with the finding
status

