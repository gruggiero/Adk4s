# Spec: JAR Launcher Dispatch

The workflow's tools are reachable two ways: a native executable built with GraalVM, or
the platform-independent assembly archive run on a JVM. Today only the first works. Run
through the archive, the tool reads the archive's own file name as the program name,
finds no tool by that name, and exits with an error before doing anything. Every
environment without a locally built native executable — a fresh clone, an adopter, the
CI runner, Windows — runs a gate that silently does nothing.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Gate** action compares two materialised trees. This spec makes a materialised arm carry the built tool, so a test that invokes the tool directly measures the same thing in both arms. The protocol's actions and state are otherwise unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `InvocationName` | opaque type (`fromRuntime`) | `org.sinemenda.probatio.cli` |
| `MulticallDispatch` | object (`resolveAndSplit`) | `org.sinemenda.probatio.cli` |
| `ProgramArgs` | opaque type | `org.sinemenda.probatio.cli` |
| `Subcommand` | enum (11 cases) | `org.sinemenda.probatio.cli` |
| `CliError` | sealed abstract class | `org.sinemenda.probatio.cli` |
| `ShimGenerator` | object | `org.sinemenda.probatio.plugin` |
| `InstallResolver` | object | `org.sinemenda.probatio.plugin` |
| `BinaryResolution` | object | `org.sinemenda.probatio.packaging` |
| `ArmTree` | final case class, private constructor | `org.sinemenda.probatio.migration` |
| `DifferentialHarness` | object | `org.sinemenda.probatio.migration` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `InvocationSource` | enum (`NamedExecutable(basename)`, `Archive(path)`) | Where the program name came from. An `Archive` source is never read as a tool name: its presence means "no name was given, dispatch by the first argument", exactly as for the generic name. |

`InvocationName` is **modified**: it is constructed from an `InvocationSource`, so the
dispatcher can distinguish an archive path from an executable name without inspecting
string suffixes at the decision site.

### Type-Widening Impact

No existing public type gains a variant. `InvocationSource` is new and closed; every match
over it is written in this change. The dispatcher's three-way rule — tool name, generic
name, anything else — keeps its third branch: a named executable that is neither a tool
nor the generic name is still rejected. Only the archive case moves, from the third branch
to the second.

## ADDED Requirements

### Requirement: A tool run through its archive dispatches by its first argument

The tool SHALL select its subcommand from the first program argument when it is run
through its assembly archive, and it MUST NOT treat the archive's file name as a tool
name.

**Given** the tool run through its assembly archive with a subcommand as the first
program argument
**When** it dispatches
**Then** that subcommand runs with the remaining arguments, exactly as when the tool is
run under its generic name

**Rationale**: the archive's file name identifies a build artifact, not a tool. Reading it
as a tool name is what makes every archive invocation fail.

#### Scenario: Happy path — the gate runs through the archive

**Given** the tool run through its archive as the gate subcommand for the prompt event,
with text output, against a repository carrying the workflow
**When** it dispatches
**Then** the gate's injection tier runs and the tool terminates with the clean status

#### Scenario: Happy path — help runs through the archive

**Given** the tool run through its archive with the help flag as its only argument
**When** it dispatches
**Then** it lists every subcommand and terminates with the clean status

#### Scenario: Adversarial — the archive's file name is never reported as an unknown subcommand

**Given** the tool run through its archive with the name of an existing subcommand as its first argument
**When** it dispatches
**Then** no output names the archive's file name as an unknown subcommand

#### Scenario: Adversarial — an unknown first argument through the archive is still rejected

**Given** the tool run through its archive with a first argument that names no tool
**When** it dispatches
**Then** it is rejected naming that argument, and it does not terminate with the clean
status

#### Scenario: Edge case — an executable with an unrecognised name is still rejected

**Given** the tool run as a native executable whose name is neither a tool name nor the
generic name
**When** it dispatches
**Then** it is rejected naming that executable name — the strictness for named
executables is unchanged

### Requirement: Every shipped launcher works when only the archive is present

Each launcher the workflow ships SHALL run the requested tool when the native executable
is absent and the assembly archive is present, and it MUST NOT fail before reaching the
tool.

**Given** an environment with the assembly archive built and no native executable
**When** a tool is invoked through the repository's launcher, through a forwarding script,
or through the launcher the build plugin installs
**Then** the tool runs and its termination status is the tool's own

#### Scenario: Happy path — a forwarding script reaches the tool with only the archive

**Given** an environment with only the archive built
**When** the gate is invoked through its forwarding script
**Then** the gate runs and terminates with the gate's own status

#### Scenario: Happy path — the plugin-installed launcher reaches the tool

**Given** a user-level install whose launcher points at an assembly archive
**When** a tool is invoked through that launcher
**Then** the tool runs

#### Scenario: Adversarial — neither archive nor executable is could-not-determine, named

**Given** an environment with neither the archive nor the native executable built
**When** a tool is invoked through the repository's launcher
**Then** the launcher reports that no built tool was found, names the locations it
searched, and does not terminate with the clean status

### Requirement: The archive path has its own subprocess conformance run

The conformance run that starts every exposed tool as a separate process SHALL be executed
against the assembly archive as well as against the native executable, and a missing
archive MUST be reported as a failure, not a skip.

**Given** the subprocess conformance run and a built archive
**When** it is executed against the archive
**Then** every exposed tool is started through the archive and each conforms

#### Scenario: Happy path — every tool conforms through the archive

**Given** a built archive
**When** the archive conformance run executes
**Then** every exposed tool starts and conforms

#### Scenario: Adversarial — a missing archive fails the run

**Given** no built archive
**When** the archive conformance run executes
**Then** the run fails naming the missing archive, and it is not reported as skipped

### Requirement: A comparison arm carries the built tool for direct invocations

Each materialised arm of the differential comparison SHALL make the built tool available to
tests that invoke it directly, so that the predecessor arm's per-file failure counts equal
those of an independent predecessor control.

**Given** a materialised predecessor arm and an independent predecessor control at the
same baseline
**When** the acceptance suite runs in both
**Then** the per-file failure counts are equal

**Rationale**: on 2026-09-25 the harness's predecessor arm reported three `fact-extraction`
failures where the independent control reported one. Tests that call the tool directly ran
inside an arm with no built output. A predecessor arm that is pessimistic can mask a
regression of the same size.

#### Scenario: Happy path — the predecessor arm matches the control per file

**Given** the repaired harness and an independent control at one baseline
**When** both run
**Then** every file's predecessor failure count is equal

#### Scenario: Adversarial — an arm with no built tool is could-not-determine

**Given** a materialisation where no built tool is available to provide
**When** the arm is prepared
**Then** the result is could-not-determine naming the missing artifact, and no comparison
is reported

## Properties (Ring 3)

### Property: archive-and-generic-dispatch-agree

**Invariant**: for every argument list, dispatch through the archive yields the same
subcommand and remaining arguments as dispatch under the generic name.

**Generator strategy**: `genProgramArgs` — constructive over a union of three closed
alphabets: each subcommand name, the separator token, and arbitrary strings of
length 0–20 including ones ending in the archive suffix. Lists of length 0–5. Union rather
than filter, so subcommand names are hit by construction. Edge cases: the empty list, a
separator alone, a first argument that is itself an archive path.

```
property("archive and generic dispatch agree") {
  for {
    args <- genProgramArgs.forAll
    viaArchive = dispatch(InvocationSource.Archive(archivePath), args)
    viaGeneric = dispatch(InvocationSource.NamedExecutable("probatio"), args)
  } yield Result.assert(viaArchive == viaGeneric)
}
```

### Property: named-executable-strictness-is-preserved

**Invariant**: for every executable name that is neither a tool name nor the generic name,
dispatch is rejected naming that executable name — whatever the arguments.

**Generator strategy**: `genForeignExecutableName` — constructive: strings of length 1–20
over a mixed alphabet, mapped away from the finite set of tool names and generic names by
prefixing, so foreign names are produced by construction rather than by discarding. Crossed
with `genProgramArgs`.

```
property("a foreign executable name is rejected") {
  for {
    name <- genForeignExecutableName.forAll
    args <- genProgramArgs.forAll
  } yield Result.assert(
    dispatch(InvocationSource.NamedExecutable(name), args) == Left(UnknownSubcommand(name))
  )
}
```

### Property: archive-conformance-matches-native-conformance

**Invariant**: for every exposed tool and every invocation in the conformance corpus, the
archive run and the native run produce the same termination status and the same standard
output.

**Generator strategy**: the conformance corpus is fixed and constructive — one invocation
per exposed tool, as the existing native run uses — crossed with the two artifacts.
Determinism: only termination status and output are compared; no timing is observed, and
each process runs in the hermetic environment defined by `hermetic-test-processes`.

```
property("archive and native conform identically") {
  for {
    inv <- genConformanceInvocation.forAll
  } yield Result.assert(runArchive(inv) == runNative(inv))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A program name built from an unclassified string | Reading an archive path as a name is the defect; a name must say where it came from | `assertDoesNotCompile("InvocationName(\"probatio-cli-assembly.jar\")")` — construction requires an `InvocationSource` |
| An archive source carrying a tool name | An archive has no tool name; allowing one reopens the defect | `assertDoesNotCompile("InvocationSource.Archive(Subcommand.Gate)")` — the variant takes a path |

## Formal Contracts (Ring 6)

The dispatch rule is a total three-way classification already mirrored by the dispatch
kernel. This spec extends the mirror with the archive case.

### Contract: resolveSource

```
def resolveSource(source: SourceModel, args: List[BigInt]): DispatchModel = {
  ...
} ensuring { result =>
  // an archive source dispatches exactly as the generic name does
  (source.isArchive ==> result == resolveSource(GenericName, args)) &&
  // a named executable that is neither a tool nor generic is rejected by its own name
  (source.isForeignName ==> result.isRejectionOf(source.name)) &&
  // totality
  (result.isTool || result.isRejection)
}
```

A bridge property binds the shipped dispatcher to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The gate runs through the archive | Requirement: A tool run through its archive dispatches by its first argument + Scenario: Happy path — the gate runs through the archive | subprocess scenario test | `SubprocessConformanceSpec` |
| Help runs through the archive | Requirement: A tool run through its archive dispatches by its first argument + Scenario: Happy path — help runs through the archive | subprocess scenario test | `SubprocessConformanceSpec` |
| The archive's name is never reported as an unknown subcommand | Requirement: A tool run through its archive dispatches by its first argument + Scenario: Adversarial — the archive's file name is never reported as an unknown subcommand | scenario test | `MulticallDispatchSpec` |
| An unknown first argument through the archive is rejected | Requirement: A tool run through its archive dispatches by its first argument + Scenario: Adversarial — an unknown first argument through the archive is still rejected | scenario test | `MulticallDispatchSpec` |
| A foreign executable name is still rejected | Requirement: A tool run through its archive dispatches by its first argument + Scenario: Edge case — an executable with an unrecognised name is still rejected + Property: named-executable-strictness-is-preserved | Hedgehog property | `MulticallDispatchSpec` |
| Archive and generic dispatch agree | Property: archive-and-generic-dispatch-agree | Hedgehog property | `MulticallDispatchSpec` |
| A program name cannot be an unclassified string | Compile-Negative: A program name built from an unclassified string | compile-negative test | `EntrypointContractTypeContract` |
| An archive source cannot carry a tool name | Compile-Negative: An archive source carrying a tool name | compile-negative test | `EntrypointContractTypeContract` |
| A forwarding script reaches the tool with only the archive | Requirement: Every shipped launcher works when only the archive is present + Scenario: Happy path — a forwarding script reaches the tool with only the archive | bats oracle run in an archive-only tree | `gate-payload.bats` |
| The plugin-installed launcher reaches the tool | Requirement: Every shipped launcher works when only the archive is present + Scenario: Happy path — the plugin-installed launcher reaches the tool | scenario test | `InstallResolverSpec` |
| No built tool is named and non-clean | Requirement: Every shipped launcher works when only the archive is present + Scenario: Adversarial — neither archive nor executable is could-not-determine, named | scenario test | `HookCutoverShimSpec` |
| Every tool conforms through the archive | Requirement: The archive path has its own subprocess conformance run + Scenario: Happy path — every tool conforms through the archive + Property: archive-conformance-matches-native-conformance | subprocess property | `SubprocessConformanceSpec` |
| A missing archive fails, not skips | Requirement: The archive path has its own subprocess conformance run + Scenario: Adversarial — a missing archive fails the run | scenario test | `SubprocessConformanceSpec` |
| The predecessor arm matches the control per file | Requirement: A comparison arm carries the built tool for direct invocations + Scenario: Happy path — the predecessor arm matches the control per file | manual run of both, recorded in the evidence ledger — the control is a measured fact at a baseline | `DifferentialHarnessSpec` |
| An arm with no built tool is could-not-determine | Requirement: A comparison arm carries the built tool for direct invocations + Scenario: Adversarial — an arm with no built tool is could-not-determine | scenario test | `DifferentialHarnessSpec` |
| The dispatch classification is verified | Invariant: archive dispatch equals generic dispatch; foreign names are rejected | Stainless verification + bridge property test | `DispatchKernel` (extended) + `EntrypointBridgeSpec` |
| The acceptance suite passes in an archive-only tree | Criterion: this spec's exit criterion | bats oracle run with no native executable present | `hook-tiers.bats`, `evidence-ledger.bats`, `gate-payload.bats` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `MulticallDispatch.resolveAndSplit` | object method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/MulticallDispatch.scala:56` | The three-way rule; the archive case currently falls to the third branch |
| `MulticallDispatch.genericNames` | value | same file, `:40` | `{alias, probatio}` |
| `ProbatioMain` name resolution | object | `.../cli/ProbatioMain.scala:141` | Reads the runtime command, falling back to the process info; under `java -jar` the command is the archive path |
| `InvocationName` | opaque type | `.../cli/InvocationName.scala` | Gains construction from `InvocationSource` |
| `bin/probatio` | launcher script | `openspec/schemas/verified-scala3/bin/probatio` | Falls back to `exec java -jar`; its own comment says the fallback cannot dispatch |
| Plugin launcher | generated script | `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/ProbatioPlugin.scala:334` | Emits the same `exec java -jar` |
| `ChainStateParitySpec` workaround | test code | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateParitySpec.scala:595–597` | Symlinks the archive as `probatio`. Once dispatch handles the archive case it is no longer needed; remove it, so the test exercises the product path |
| `SubprocessConformanceSpec` | munit suite | `.../cli/SubprocessConformanceSpec.scala:52` | Currently resolves only the native artifact path |
| `ArmTree.materialise` | object method | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/ArmTypes.scala:124` | Creates a worktree at the baseline; build outputs are gitignored, so the arm has none |
| `DispatchKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/DispatchKernel.scala` | Extended with the archive-source case |
| Ring 5 note | — | `stryker4s.conf` | Dispatch is in main sources; `ArmTypes` is in test sources, so the move-to-main-and-back procedure applies to that part |
