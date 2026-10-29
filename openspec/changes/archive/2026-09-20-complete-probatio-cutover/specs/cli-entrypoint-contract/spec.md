# Spec: CLI Entrypoint Contract

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The shim is the only production caller of the tool surface; this spec fixes what the shim's invocation actually reaches | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |

This spec does not alter the concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Subcommand` | enum (16 cases) | `org.sinemenda.probatio.cli` |
| `MulticallDispatch` | object | `org.sinemenda.probatio.cli` |
| `ProbatioMain` | object | `org.sinemenda.probatio.cli` |
| `CliError` | sealed abstract class | `org.sinemenda.probatio.cli` |
| `ExitCode` | enum (Clean, Finding, Undetermined) | `org.sinemenda.probatio.cli` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |
| `HelpRegistry` | object | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ProgramArgs` | opaque type over `List[String]` | The arguments a process receives, program name EXCLUDED. Constructed only from a runtime entry point or from an explicit test fixture; makes "did this list include the program name?" a type-level question rather than a convention |
| `InvocationName` | opaque type over `String` | The name the process was invoked under, obtained from the runtime, never from an argument |

## ADDED Requirements

### Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments

Every tool invocation SHALL determine which tool to run from (a) the name the
process was started under and (b) at most the first argument the caller supplied,
and SHALL pass every remaining caller argument, in order and without omission, to
that tool.

**Given** a caller that starts the tool surface under some invocation name and
supplies a list of arguments
**When** the surface selects a tool
**Then** the selected tool receives every supplied argument except the one (if
any) that named the tool, in the original order

**Rationale**: The current surface treats the caller's first argument as the
invocation name and the caller's second argument as the tool name, so it discards
two caller arguments where it should discard at most one. Every shim invocation
therefore hands the selected tool an argument list beginning with a bare value,
which the tool rejects as an unknown flag. The result is a tool surface that
cannot be driven at all from a shim, while remaining fully drivable from a test
that hand-builds the list the surface expects.

#### Scenario: Named-tool invocation reaches the tool with all its arguments

**Given** the surface is started under the invocation name `probatio` with the
argument list `["gate", "--event", "session-start", "--format", "hook-json"]`
**When** the surface selects a tool
**Then** the gate tool is selected and receives exactly
`["--event", "session-start", "--format", "hook-json"]`

#### Scenario: Symlink invocation reaches the tool with all its arguments

**Given** the surface is started under the invocation name `chain-state` with the
argument list `["--change-dir", "d", "--change", "c", "--baseline", "abc1234"]`
**When** the surface selects a tool
**Then** the chain-state tool is selected and receives exactly
`["--change-dir", "d", "--change", "c", "--baseline", "abc1234"]` — no argument is
consumed as a tool name, because the invocation name already supplied one

#### Scenario: Error path — the first argument names nothing and the invocation name names nothing

**Given** the surface is started under the invocation name `probatio` with the
argument list `["frobnicate", "--x"]`
**When** the surface selects a tool
**Then** no tool runs, the offending token `frobnicate` is named on the error
channel, and the exit status is the finding status

#### Scenario: Edge case — no arguments at all under the generic invocation name

**Given** the surface is started under the invocation name `probatio` with an
empty argument list
**When** the surface selects a tool
**Then** no tool runs, the error names that no tool was supplied, and the exit
status is the finding status

#### Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name

**Given** the surface is started under the invocation name `chain-state` with the
argument list `["--change", "gate", "--baseline", "abc1234", "--change-dir", "d"]`
**When** the surface selects a tool
**Then** the chain-state tool is selected — not the gate tool — and it receives
the argument list unchanged, because a value in argument position is never read as
a tool name

### Requirement: An argument list that includes the program name is not constructible at the entry point

The type carrying a process's arguments SHALL be constructible only from a
runtime entry point or from a fixture that declares itself as such, and SHALL NOT
be constructible by passing a list that begins with the program name.

**Given** an author writing a test or a new entry point
**When** they attempt to build the argument-carrying value from a list whose first
element is the program name
**Then** the construction is rejected before the program runs

**Rationale**: The defect this spec repairs was invisible because the test suite
constructed the argument list the way the implementation expected rather than the
way a process delivers it. Making the two shapes distinct types means a test can
no longer silently adopt the implementation's assumption; it must state which
shape it is providing.

#### Scenario: Happy path — a runtime entry point produces the argument value

**Given** the runtime hands the entry point its argument array
**When** the entry point wraps it
**Then** the wrapped value carries exactly the runtime's arguments and the
invocation name is obtained separately, from the runtime

#### Scenario: Adversarial — a fixture cannot pass a program-name-prefixed list as if it were runtime arguments

**Given** a test that builds `["probatio", "gate", "--event", "session-start"]`
**When** it attempts to supply that list as the process's arguments
**Then** the attempt does not compile

### Requirement: Every tool is exercised through the built artifact, not only through in-process calls

The verification of this surface SHALL include, for every tool the surface
exposes, at least one check that starts the built artifact as a separate process
and asserts its output channels and exit status.

**Given** the built artifact
**When** the conformance check runs
**Then** each exposed tool has been started as a separate process at least once,
and any tool that cannot be started this way is reported as a failure rather than
skipped

**Rationale**: An in-process check of the selection function cannot observe how
the runtime delivers arguments, which is precisely where the defect lived. The
only check that would have caught it is one that runs the artifact the way a
shim runs it.

#### Scenario: Happy path — every exposed tool starts and reports a documented exit status

**Given** the built artifact and the list of tools the surface exposes
**When** each tool is started as a separate process with the shortest argument
list that tool accepts without reporting a missing or unrecognised flag
**Then** each exits with one of the three documented statuses, and none exits
with a status reporting an unrecognised argument

#### Scenario: Error path — a tool that cannot be started is a failure, not a skip

**Given** a tool whose entry point is missing from the built artifact
**When** the conformance check runs
**Then** the check fails and names that tool; it does not pass with that tool
omitted

#### Scenario: Edge case — the artifact under check is the one the shims resolve

**Given** the shims' configured resolution path
**When** the conformance check selects an artifact to start
**Then** it starts the artifact at that same resolution path, so a check that
passes cannot coexist with shims pointing at a different artifact

### Requirement: A tool that has no implementation is not nameable on the tool surface

The tool surface SHALL expose only tools that perform their described work, and a
tool whose work is not implemented SHALL NOT be selectable — it must be
unrecognised rather than recognised-and-silent.

**Given** a caller naming a tool whose behaviour has not been ported
**When** the surface selects a tool
**Then** no tool runs, the offending token is named, and the exit status is the
finding status

**Rationale**: Eight of sixteen tools currently accept their arguments, do
nothing, and exit clean. A caller cannot distinguish that from success. The
existing tool-name type already uses this argument for the absent mutation
commands — "unparseable, not merely denylisted" — and it applies identically here.

#### Scenario: Adversarial — an unported tool name is rejected, not silently accepted

**Given** the built artifact
**When** it is started with the argument list `["registry-check", "--change", "c"]`
**Then** no tool runs, the token `registry-check` is named on the error channel,
and the exit status is the finding status — the surface does not exit clean

#### Scenario: Happy path — a ported tool is still selectable

**Given** the built artifact
**When** it is started with the argument list `["ledger", "read", "--file", f, "--change", "c"]`
**Then** the ledger tool runs

#### Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves

**Given** the built artifact
**When** it is started with the argument list `["metals", "start"]`
**Then** the metals tool runs its start sub-action; the sub-actions that were
never implemented are unrecognised sub-actions, named on the error channel

## Properties (Ring 3)

### Property: argument-preservation

**Invariant**: For every invocation name and every argument list, the argument
list the selected tool receives equals the supplied list with at most its first
element removed, and the first element is removed if and only if it named the
selected tool.

**Generator strategy**: `genInvocationName` (constructive — chooses from the exposed
tool names plus the two generic names `probatio` and `prob`, plus a
non-tool name) × `genArgList` (constructive — builds a list from a pool of flag
tokens, value tokens, and tool-name-shaped value tokens; sizes 0–12; deliberately
includes lists whose first element is a tool name and lists whose *values* spell
tool names). Hedgehog `cover` labels: `named-tool-first` ≥ 20%,
`symlink-no-tool-token` ≥ 20%, `tool-name-in-value-position` ≥ 10%,
`empty-args` ≥ 5%. Constructive, not filtered.

```
forAll { (name: InvocationName, args: ProgramArgs) =>
  resolveAndSplit(name, args) match
    case Right((tool, rest)) =>
      if args.headOption.exists(namesTool(_, tool)) then rest == args.tail
      else rest == args
    case Left(_) => true
}
```

### Property: dispatch-equivalence-across-signals

**Invariant**: For every tool and every argument list, invoking under the generic
name with the tool named as the first argument selects the same tool, and delivers
the same remaining arguments, as invoking under the tool's own name with no tool
argument.

**Generator strategy**: `genPortedTool` (constructive — the exposed tool enum,
exhaustive by construction since the enum is small) × `genArgList` as above,
filtered only to exclude lists whose first element is itself a tool name (that
case is covered by `argument-preservation`). Hedgehog `cover`: each tool ≥ 5%.

```
forAll { (tool: Subcommand, args: ProgramArgs) =>
  resolveAndSplit(generic, cons(cliName(tool), args)) ==
    resolveAndSplit(invocationName(tool), args)
}
```

### Property: no-silent-selection

**Invariant**: Selection never succeeds for a token that is not an exposed tool
name, under any invocation name.

**Generator strategy**: `genNonToolToken` (constructive — alphanumeric and
kebab tokens, then a rejection filter against the exposed name set; the filter
rejects a vanishingly small fraction because the name set has ≤ 9 members) ×
`genInvocationName`. Hedgehog `cover`: `resembles-removed-tool` ≥ 15% — tokens
drawn from the seven removed names (`registry-check`, `scan`, `removal-audit`,
`impact-scan`, `concept-scanner`, `graph`, plus `update`), which is the case that
must not regress to clean.

```
forAll { (name: InvocationName, token: String) =>
  !isExposedToolName(token) && !isExposedToolName(basename(name)) ==>
    resolveAndSplit(name, ProgramArgs(List(token))).isLeft
}
```

### Property: subprocess-agrees-with-in-process

**Invariant**: For every exposed tool and every argument list in the fixture
corpus, starting the built artifact as a separate process yields the same exit
status as calling the selection-and-run path in process with the same invocation
name and arguments.

**Generator strategy**: `genFixtureInvocation` — constructive, drawn from a fixed
corpus of one minimal-valid and one minimal-invalid argument list per exposed
tool (so the corpus size is 2 × |tools|, small enough to run as a process each
time). This is a model-based property: the in-process path is the model, the
subprocess is the system. Hedgehog `cover`: `valid` ≥ 40%, `invalid` ≥ 40%.

```
forAll { (inv: FixtureInvocation) =>
  runAsSubprocess(builtArtifact, inv).exitStatus ==
    runInProcess(inv).exitStatus
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ProgramArgs(List("probatio", "gate", "--event", "x"))` from a test, as if it were runtime arguments | The program name is not an argument; allowing it is what let the test suite adopt the implementation's wrong assumption | `assertDoesNotCompile` in `EntrypointContractSpec` |
| `Subcommand.RegistryCheck` (and `Scan`, `RemovalAudit`, `ImpactScan`, `ConceptScanner`, `Graph`) | An unported tool must be unnameable, not silently clean | `assertDoesNotCompile` in `EntrypointContractSpec` |
| `MulticallDispatch.resolve(argv0, argv1)` taking the tool token from a second argument position | The two-argument-consuming shape is the defect; removing the overload prevents its reintroduction | `assertDoesNotCompile` in `EntrypointContractSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. The selection-and-split function is a total function
over `(InvocationName, List[String])`; the shipped code returns `Either[CliError,
…]` over an opaque type, which the Stainless frontend cannot model. The mirror
states the contract over the reduced abstraction: an invocation name reduced to
`Option[toolIndex]`, and the argument list reduced to `List[Int]` of token
classifications (0 = not a tool name, n = tool index n).

### Contract: resolveAndSplit

**Precondition** (`require`): none — the function is total over all inputs; that
totality is itself the obligation.

**Postcondition** (`ensuring`): when a tool is selected, the returned remainder is
a suffix of the input whose dropped prefix has length 0, 1, or 2 — length 0 under
name dispatch, length 1 iff the dropped element classified as the selected tool,
and length 2 iff the dropped prefix was a POSIX `--` separator followed by the
selected tool (DEFECT-3: the separator is consumed before subcommand resolution
under a generic name; token classification `-1` encodes the separator).

```scala
def resolveAndSplit(nameTool: Option[BigInt], tokens: List[BigInt]):
    Option[(BigInt, List[BigInt])] = {
  // pure model
}.ensuring { res => res match
  case None => true
  case Some((tool, rest)) =>
    rest == tokens ||
    tokens == Cons(tool, rest) ||
    tokens == Cons(Sep, Cons(tool, rest))   // Sep = -1: POSIX `--`
}
```

**Bridge property test**: `EntrypointBridgeSpec` runs the shipped
`resolveAndSplit` and the mirror on the same generated inputs (mapping tool names
to indices) and asserts equal results. Without it, the ring proves a property of a
model nobody runs.

**Delegated to Ring 3**: the equivalence of the two invocation signals
(`dispatch-equivalence-across-signals`) is delegated — it quantifies over the tool
enum and the argument list jointly, which the solver does not discharge in
reasonable time.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| At most one argument is consumed as a tool name | Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments + Property: argument-preservation | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` |
| Both invocation signals select the same tool with the same remainder | Requirement 1 + Property: dispatch-equivalence-across-signals | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` |
| A value that spells a tool name is not read as a tool name | Requirement 1 + Scenario: Adversarial — a flag value that happens to spell a tool name is not treated as a tool name | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` |
| A program-name-prefixed list cannot be supplied as runtime arguments | Requirement: An argument list that includes the program name is not constructible at the entry point + Compile-Negative: ProgramArgs from a program-name-prefixed list | opaque type + compile-negative test | `ProgramArgs` in `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ProgramArgs.scala`; `EntrypointContractSpec` |
| The two-argument-consuming resolve shape cannot be reintroduced | Requirement 1 + Compile-Negative: MulticallDispatch.resolve taking the tool token from a second argument position | compile-negative test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` |
| Every exposed tool starts as a separate process and reports a documented status | Requirement: Every tool is exercised through the built artifact, not only through in-process calls + Property: subprocess-agrees-with-in-process | property test over a fixture corpus, executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` |
| A missing tool entry point fails the conformance check rather than being skipped | Requirement 3 + Scenario: Error path — a tool that cannot be started is a failure, not a skip | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` |
| The checked artifact is the artifact the shims resolve | Requirement 3 + Scenario: Edge case — the artifact under check is the one the shims resolve | scenario test reading the shim resolution path | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` |
| An unported tool is unnameable | Requirement: A tool that has no implementation is not nameable on the tool surface + Compile-Negative: Subcommand.RegistryCheck | type system (enum case absent) + compile-negative test | `Subcommand` in `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/Subcommand.scala`; `EntrypointContractSpec` |
| An unported tool name exits with the finding status, never clean | Requirement 4 + Scenario: Adversarial — an unported tool name is rejected, not silently accepted | scenario test executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` |
| Selection never succeeds for a non-tool token | Requirement 1 + Property: no-silent-selection | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointContractSpec.scala` |
| The selection-and-split function is total and its remainder is a bounded suffix | Requirement: The tool surface resolves its command from the invocation name and the first user argument, never by consuming two user arguments + Property: argument-preservation + Contract: resolveAndSplit | formal contract (Ring 6) + bridge property test | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/DispatchKernel.scala`; `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/EntrypointBridgeSpec.scala` |
| No requirement of this spec is satisfied by a test that encodes the implementation's argument shape | Requirement: Every tool is exercised through the built artifact, not only through in-process calls | adversarial review (Ring 8), fresh context, verifying against the built artifact | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ProbatioMain.main` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ProbatioMain.scala` | obtains the invocation name from the runtime (`sun.java.command` / `ProcessHandle.current().info().command()` for the JAR path, the executable name under native-image) and wraps `args` as `ProgramArgs` unchanged |
| `ProbatioMain.dispatch` | method | same file | signature changes from `Array[String] => Int` to `(InvocationName, ProgramArgs) => Int` |
| `MulticallDispatch.resolve` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/MulticallDispatch.scala` | replaced by `resolveAndSplit(InvocationName, ProgramArgs)`; the old two-argument form is removed |
| `Subcommand` | enum | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/Subcommand.scala` | loses `RegistryCheck`, `Scan`, `RemovalAudit`, `ImpactScan`, `ConceptScanner`, `Graph`; `cliName` and every match over it must be updated or Ring 0 fails on exhaustiveness |
| `SubcommandEntrypoints.scala` | file | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/` | six entrypoint objects deleted with their enum cases; `MetalsCmd` loses its `stop` and `call` arms |
| `HelpRegistry.helpFor` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/HelpRegistry.scala` | exhaustive over `Subcommand`; shrinks with it |
| `CliWiringContractSpec`, `MulticallDispatchSpec`, `ProbatioDispatchSpec`, `CliSurfaceSpec` | test suites | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/` | existing suites that hand-build argv0-prefixed arrays — they must be migrated to `ProgramArgs` + `InvocationName`, which is what forces the defect into the open |
| `openspec/schemas/verified-scala3/bin/probatio` | launcher script | repository | must invoke the artifact so the runtime sees the intended invocation name; the current `exec java -jar "$JAR" "$@"` is correct once `main` stops consuming an argument as the program name |
| `sbt "probatio-cli/assembly"` | build step | `probatio-cli` | the subprocess conformance suite consumes the assembly output; it must run after it |
