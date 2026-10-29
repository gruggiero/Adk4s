# workflow-delivery-hygiene Specification

## Purpose
TBD - created by archiving change repair-probatio-cutover. Update Purpose after archive.
## Requirements
### Requirement: An in-repository forwarding script resolves its target relative to itself

A forwarding script committed to the repository SHALL resolve the tool it forwards to
relative to its own location, and it MUST NOT contain an absolute path to a particular
checkout.

**Given** a forwarding script committed to the repository
**When** its content is inspected
**Then** the target is expressed relative to the script's own location

**Rationale**: the launcher the scripts forward to already resolves relatively. The scripts
do not, so every clone, worktree and continuous-integration runner forwards to a path that
does not exist there. The workflow is meant to be adopted by other repositories; a
committed absolute path makes that impossible.

#### Scenario: Happy path — a script invoked from a fresh clone reaches the tool

**Given** a fresh clone of the repository at a different filesystem location
**When** a forwarding script is invoked
**Then** it reaches the tool and terminates with the tool's own status

#### Scenario: Happy path — a script invoked from a linked worktree reaches the tool

**Given** a linked worktree of the repository
**When** a forwarding script is invoked
**Then** it reaches the tool

#### Scenario: Adversarial — no committed script contains an absolute checkout path

**Given** every forwarding script committed to the repository
**When** their contents are inspected
**Then** none contains an absolute path beginning at a filesystem root

#### Scenario: Error path — a target that cannot be safely quoted is refused

**Given** a resolution whose target path contains a character that would change what the
script executes
**When** the script would be generated
**Then** generation is refused naming the character, and no script is written

### Requirement: The generated script states which scope it resolved

The script generator SHALL take the resolution scope explicitly, and it MUST NOT infer the
scope from the target's shape.

**Given** a generation request
**When** the script is generated
**Then** the request carried the scope explicitly

**Rationale**: inferring "this looks absolute, so it is an install" is exactly the
reasoning that produced a committed absolute path nobody noticed. Making the scope an
argument makes the choice visible at every call site.

#### Scenario: Happy path — a repository-relative request produces a relative target

**Given** a generation request carrying the repository-relative scope
**When** the script is generated
**Then** its target is relative

#### Scenario: Happy path — an install request produces an absolute target

**Given** a generation request carrying the install scope and an installed path
**When** the script is generated
**Then** its target is that absolute path

#### Scenario: Adversarial — a generation request without a scope does not compile

**Given** a caller requesting generation without stating the scope
**When** the code is compiled
**Then** compilation fails

### Requirement: Continuous integration runs the acceptance suite and the module suites

The continuous-integration configuration SHALL run the acceptance suite, the differential
comparison, and every module test suite on each change, and a configuration that runs none
of them MUST NOT be the repository's only one.

**Given** the repository's continuous-integration configuration
**When** its jobs are enumerated
**Then** the acceptance suite, the differential comparison, and every module test suite
each appear

**Rationale**: the repository's only configuration builds release binaries. Nothing runs
the suite that decides whether the workflow enforces anything, which is why a failing
guard and a twelve-test regression were both merged and archived without notice.

#### Scenario: Happy path — a change that regresses the acceptance suite fails the job

**Given** a change that makes one acceptance-suite file worse than the predecessor
**When** the continuous-integration job runs
**Then** the job fails naming that file

#### Scenario: Happy path — a change that breaks a module suite fails the job

**Given** a change that makes one module test suite fail
**When** the job runs
**Then** the job fails naming that suite

#### Scenario: Adversarial — a job that reports success on an unrun suite is not sufficient

**Given** a job configuration in which the acceptance suite step is skipped
**When** the configuration is checked
**Then** the check reports the skipped step, and the configuration does not satisfy this
requirement

### Requirement: The continuous-integration templates name the tools that exist

The shipped templates SHALL invoke only tools present in the tree, and a template MUST NOT
name a tool that has been replaced or removed.

**Given** each shipped continuous-integration template
**When** the tools it invokes are resolved against the tree
**Then** each resolves to a present tool

#### Scenario: Happy path — every template's tools resolve

**Given** each of the shipped templates in turn
**When** its invoked tools are resolved
**Then** each resolves

#### Scenario: Adversarial — a template naming a removed tool is reported

**Given** a template invoking a tool that is not present in the tree
**When** the templates are checked
**Then** the check names the template and the missing tool

