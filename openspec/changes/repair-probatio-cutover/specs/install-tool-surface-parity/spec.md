# Spec: Install Tool Surface Parity

The two installers have ported implementations whose argument surfaces are narrower than
their predecessors'. A shim swap onto a narrower surface silently removes capability: an
invocation the predecessor honoured becomes an error. Surface parity is the precondition
for swapping them.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The protocol's **Swap** action requires the ported tool to stand in for the predecessor. This spec establishes that precondition for the two installers before their swap. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum | `org.sinemenda.probatio.cli` |
| `CliError` | sealed abstract class | `org.sinemenda.probatio.cli` |
| `HelpRegistry` | object | `org.sinemenda.probatio.cli` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `DriftScan` | object | `org.sinemenda.probatio.core` |
| `InstallRoots` | final case class (six roots) | `org.sinemenda.probatio.core` |
| `SchemaPolicy` | object | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `InstallTarget` | enum (`AllPresentHarnesses`, `NamedHarness(name)`) | Which harness the hook installer wires, matching the predecessor's default-to-all-present behaviour. |
| `InstallMode` | enum (`DryRun`, `Apply`) | Whether the installer reports what it would write or writes it. `DryRun` is the default, matching the predecessor. |
| `PrerequisiteProbe` | final case class (name, present) | One declared prerequisite and whether it was found. |
| `PrerequisiteReport` | final case class (probes) — `missing`, `allPresent` | The result of probing the declared prerequisite set. |

### Type-Widening Impact

No public type is widened. `Subcommand` is unchanged. The two installer entrypoints'
argument types grow, which is a signature change, not a variant addition; existing call
sites are enumerated in Implementation Anchors.

## ADDED Requirements

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

## Properties (Ring 3)

### Property: surface-parity-with-the-predecessor

**Invariant**: for every invocation the predecessor accepts, the ported installer accepts
it too and terminates with the same status; and for every invocation the predecessor
rejects, the ported installer rejects it.

**Generator strategy**: `genInstallInvocation` — constructive over argument lists built
from the closed set of predecessor-accepted flags crossed with value shapes (present,
absent, empty, repeated), unioned with a set of arguments the predecessor rejects. Union
rather than filter, so both directions are covered by construction. Model-based: the
predecessor script runs as a subprocess.

```
property("surface parity with the predecessor") {
  for {
    inv <- genInstallInvocation.forAll
    ported = runPorted(inv)
    model  = runPredecessor(inv)
  } yield Result.assert(ported.exitStatus == model.exitStatus)
}
```

### Property: dry-run-writes-nothing

**Invariant**: for every project fixture and every invocation without the write
instruction, the fixture's file tree is byte-identical before and after.

**Generator strategy**: `genProjectFixture` — constructive over project roots carrying
zero to three harness configurations, each drawn from {absent, generated-by-installer,
foreign}. Edge cases: an empty project, a project with all three harnesses, a project with
a foreign configuration.

```
property("a dry run writes nothing") {
  for {
    fixture <- genProjectFixture.forAll
    before   = digestTree(fixture)
    _        = runInstaller(fixture, DryRun)
    after    = digestTree(fixture)
  } yield Result.assert(before == after)
}
```

### Property: install-covers-every-declared-directory

**Invariant**: for every project fixture and every skill document set, after an applied
install each declared agent directory contains each skill document.

**Generator strategy**: `genSkillSet` × `genProjectFixture` — constructive over skill
document sets of size 1–6 and the project fixtures above. Edge cases: one skill, the
maximum set, a project where one declared directory already exists with stale content.

```
property("install covers every declared directory") {
  for {
    skills  <- genSkillSet.forAll
    fixture <- genProjectFixture.forAll
    _        = runInstaller(fixture, Apply, skills)
  } yield Result.assert(
    declaredDirs(fixture).forall(d => skills.forall(s => exists(d, s)))
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| An install mode defaulting to write | The predecessor is dry-run by default; a write default silently modifies harness configuration | `assertDoesNotCompile("runInstaller(fixture)")` — the entrypoint requires the mode explicitly |
| A prerequisite report built from names alone | A report that carries names without their presence cannot distinguish checked-and-present from not-checked | `assertDoesNotCompile("PrerequisiteReport(List(\"jq\"))")` — the type holds probes, not names |
| A single-directory install target | A target that can name one directory permits the narrowed install this spec removes | `assertDoesNotCompile("InstallTarget.SingleDir(path)")` — the enum has no such variant |

## Formal Contracts (Ring 6)

No formal contracts. Both installers are I/O adapters over a directory set; the decisions
they make — which harness, which mode, whether a file is foreign — are small closed
classifications already covered by the compile-negative obligations and the properties.
There is no fold, recursion, or arithmetic invariant at their centre. This is a stated
skip, not an omission.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| All prerequisites present terminates clean | Requirement: The skill installer probes the declared prerequisite set + Scenario: Happy path — all prerequisites present terminates clean | scenario test + bats oracle | `InstallPreciselyOneSpec`, `harness-install-verification.bats` |
| A missing prerequisite is reported and counted | Requirement: The skill installer probes the declared prerequisite set + Scenario: Adversarial — a missing prerequisite is reported and not counted as present | scenario test | `InstallPreciselyOneSpec` |
| The probe names every prerequisite checked | Requirement: The skill installer probes the declared prerequisite set + Scenario: Consumer surface — the probe names each prerequisite it checked | scenario test | `InstallPreciselyOneSpec` |
| A prerequisite report cannot be built from names alone | Compile-Negative: A prerequisite report built from names alone | compile-negative test | `CliWiringCompileNegativeSpec` |
| Every declared agent directory receives every skill | Requirement: The skill installer writes to every declared agent directory + Scenario: Happy path — three agent directories each receive every skill + Property: install-covers-every-declared-directory | Hedgehog property | `InstallPreciselyOneSpec` |
| A single-directory install is reported incomplete | Requirement: The skill installer writes to every declared agent directory + Scenario: Adversarial — installing into a single directory is not the whole install | scenario test | `InstallPreciselyOneSpec` |
| An unwritable directory is could-not-determine | Requirement: The skill installer writes to every declared agent directory + Scenario: Error path — an unwritable agent directory is could-not-determine | scenario test | `InstallPreciselyOneSpec` |
| A single-directory target is unconstructible | Compile-Negative: A single-directory install target | compile-negative test | `CliWiringCompileNegativeSpec` |
| The default invocation writes nothing | Requirement: The hook installer reports before it writes + Scenario: Happy path — the default invocation writes nothing + Property: dry-run-writes-nothing | Hedgehog property | `HookCutoverSpec` |
| The explicit invocation writes | Requirement: The hook installer reports before it writes + Scenario: Happy path — the explicit invocation writes | scenario test | `HookCutoverSpec` |
| A default invocation does not modify an existing configuration | Requirement: The hook installer reports before it writes + Scenario: Adversarial — a default invocation does not modify an existing configuration | scenario test | `HookCutoverSpec` |
| A write-by-default entrypoint is unconstructible | Compile-Negative: An install mode defaulting to write | compile-negative test | `CliWiringCompileNegativeSpec` |
| A named harness is wired alone | Requirement: The hook installer selects harnesses and refuses to clobber + Scenario: Happy path — a named harness is wired alone | scenario test | `HookCutoverSpec` |
| No name wires every present harness | Requirement: The hook installer selects harnesses and refuses to clobber + Scenario: Happy path — no name wires every present harness | scenario test | `HookCutoverSpec` |
| An unrecognised configuration is not overwritten | Requirement: The hook installer selects harnesses and refuses to clobber + Scenario: Adversarial — an unrecognised configuration is not overwritten | scenario test | `HookCutoverSpec` |
| The help output names the selectors and the write instruction | Requirement: The hook installer selects harnesses and refuses to clobber + Scenario: Consumer surface — the help output names the harness selector and the write instruction | scenario test | `CliHelpSpec` |
| Both surfaces accept every predecessor invocation | Property: surface-parity-with-the-predecessor | Hedgehog model-based property (predecessor run as a subprocess) | `MigrationProtocolSpec` |
| The suite file reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `harness-install-verification.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `install-skills.sh` | predecessor implementation | `openspec/schemas/verified-scala3/scanner/install-skills.sh` | 64 lines. Prerequisite probe at lines 21–37; three agent directories at line 40. The model. |
| `install-hooks.sh` | predecessor implementation | `openspec/schemas/verified-scala3/hooks/install-hooks.sh` | 144 lines. Harness selector, project-root selector, write instruction and help at lines 30–35; the no-clobber refusal at line 72. The model. |
| `InstallSkillsCmd`, `InstallHooksCmd` | objects | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | Both currently take a single directory argument; both widen |
| `InstallTarget`, `InstallMode`, `PrerequisiteProbe`, `PrerequisiteReport` | new types | `workflow/core/src/main/scala/org/sinemenda/probatio/core/` | New; the decisions are pure, the probing is in the adapter |
| `HelpRegistry` | object | `.../cli/HelpRegistry.scala` | Both tools' help entries widen |
| `DriftScan.installRoots` | value | `.../core/DriftScan.scala:138` | Six roots searched for drift. Note the asymmetry recorded by this change's inventory-check: one directory the installer writes to is not among the six searched. Reconciling the two sets is **not** in this spec's scope — it is a verdict-affecting change under the feature freeze — but the asymmetry is named here so the next change inherits it. |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
