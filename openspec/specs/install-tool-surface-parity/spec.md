# install-tool-surface-parity Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: The skill installer probes the declared prerequisite set

The skill installer SHALL accept an invocation that probes the declared prerequisites and
report each as present or missing, and it MUST NOT report a prerequisite as present
without finding it.

**Given** the installer invoked in its prerequisite-probe mode
**When** the probe runs
**Then** each declared prerequisite is reported present or missing, and the tool
terminates with the finding status when any is missing

#### Scenario: Happy path — all prerequisites present terminates clean

**Given** an environment in which every declared prerequisite is available
**When** the probe runs
**Then** each is reported present and the tool terminates with the clean status

#### Scenario: Adversarial — a missing prerequisite is reported and not counted as present

**Given** an environment missing one declared prerequisite
**When** the probe runs
**Then** that prerequisite is reported missing, the count of missing is one, and the tool
terminates with the finding status

#### Scenario: Consumer surface — the probe names each prerequisite it checked

**Given** the probe run in any environment
**When** the output is produced
**Then** it names every prerequisite in the declared set, one line each

### Requirement: The skill installer writes to every declared agent directory

The skill installer SHALL install into each of the agent directories the predecessor
installs into, and it MUST NOT install into only one when several are declared.

**Given** the installer invoked against a project root
**When** the install runs
**Then** every declared agent directory under that root receives a copy of every skill
document

#### Scenario: Happy path — three agent directories each receive every skill

**Given** a project root and a schema carrying several skill documents
**When** the install runs
**Then** each declared agent directory contains each skill document

#### Scenario: Adversarial — installing into a single directory is not the whole install

**Given** an install that wrote to one declared agent directory only
**When** the result is checked against the declared directory set
**Then** the check reports the directories that were not written

#### Scenario: Error path — an unwritable agent directory is could-not-determine

**Given** a project root containing a declared agent directory that cannot be written
**When** the install runs
**Then** the outcome is could-not-determine naming that directory, and the tool does not
report a successful install

### Requirement: The hook installer reports before it writes

The hook installer SHALL default to reporting what it would write and SHALL write only
when explicitly instructed, and it MUST NOT write on an invocation that did not ask for
it.

**Given** the hook installer invoked without the instruction to write
**When** the installer runs
**Then** it reports each file it would write, and no file is created or modified

**Given** the hook installer invoked with the instruction to write
**When** the installer runs
**Then** it writes those files and reports each one written

**Rationale**: the predecessor is dry-run by default. An installer that writes by default,
swapped in behind an unchanged invocation, modifies a harness configuration nobody asked
it to touch.

#### Scenario: Happy path — the default invocation writes nothing

**Given** a project root with existing harness configuration
**When** the installer runs without the instruction to write
**Then** the configuration is byte-identical afterwards

#### Scenario: Happy path — the explicit invocation writes

**Given** the same project root
**When** the installer runs with the instruction to write
**Then** the declared files are written

#### Scenario: Adversarial — a default invocation does not modify an existing configuration

**Given** a project root whose harness configuration differs from what the installer would
write
**When** the installer runs without the instruction to write
**Then** the existing configuration is unchanged

### Requirement: The hook installer selects harnesses and refuses to clobber

The hook installer SHALL wire the harness named in the invocation, or every harness
already present when none is named, and it MUST NOT overwrite an existing configuration it
did not generate.

**Given** an invocation naming a harness
**When** the installer runs
**Then** only that harness is wired

**Given** an invocation naming no harness
**When** the installer runs
**Then** every harness already present in the project is wired

**Given** an existing configuration the installer did not generate
**When** the installer would write over it
**Then** it refuses, naming the file and the reason

#### Scenario: Happy path — a named harness is wired alone

**Given** an invocation naming one harness in a project where two are present
**When** the installer runs with the instruction to write
**Then** only the named harness's configuration is written

#### Scenario: Happy path — no name wires every present harness

**Given** an invocation naming no harness in a project where two are present
**When** the installer runs with the instruction to write
**Then** both harnesses' configurations are written

#### Scenario: Adversarial — an unrecognised configuration is not overwritten

**Given** an existing harness configuration whose content the installer did not generate
**When** the installer runs with the instruction to write
**Then** it refuses to write that file, names it, and states the reason

#### Scenario: Consumer surface — the help output names the harness selector and the write instruction

**Given** the hook installer invoked for help
**When** the help output is produced
**Then** it names the harness selector, the project-root selector, the write instruction,
and the three termination statuses

