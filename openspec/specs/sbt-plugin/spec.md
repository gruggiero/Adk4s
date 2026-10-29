# Spec: SBT Plugin

<!-- Delta spec for the `sbt-plugin` capability of the `port-scanner-to-probatio`
     change. Defines a thin sbt 1.x AutoPlugin (`sbt-probatio`) that integrates
     the `probatio` native binary into sbt builds via delegating tasks. The
     plugin links zero `probatio-core` (Scala 3 / TASTy) code — all
     communication with the tooling is argv + exit codes + stdout JSON. Covers
     R-S1…R-S6 and the cross-cutting R-ARCH1 boundary. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | The plugin is a build-integration adapter; no behavioral concept's actions, state, or synchronizations are altered | — |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

R-ARCH1 isolates probatio from adk4s: the tooling subprojects depend on
NOTHING adk4s-side — no library module, no test utility, no shared source.
The `sbt-probatio` plugin is part of that boundary. The "Concepts Used (from
inventory)" table is intentionally empty: the plugin references zero adk4s
type-inventory rows and zero `probatio-core` types (R-S1 forbids the latter
link; R-ARCH1 forbids the former).

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `sbt-probatio` AutoPlugin | sbt 1.x plugin (Scala 2.12) | Thin build-integration plugin: declares settings/tasks, resolves the `probatio` binary, delegates all tool execution to the binary via argv + exit codes + stdout JSON |
| `probatioInstall` | sbt task | Resolves the platform binary in order: prebuilt binary → assembly JAR launcher → (opt-in) local native-image; each fallback emits one distinct log line |
| `probatioSpecLint` | sbt task | Delegates to the `probatio spec-lint` subcommand; maps exit codes to task success/failure with distinct messages |
| `probatioChainState` | sbt task | Delegates to the `probatio chain-state` subcommand; maps exit codes to task success/failure |
| `probatioCheckpoint` | sbt task | Delegates to the `probatio checkpoint` subcommand; maps exit codes to task success/failure |
| `probatioLedgerAppend` | sbt task | Delegates to the `probatio ledger append` subcommand; append-only by construction (no update/delete subcommand exists in the binary) |
| `probatioGateShim` | sbt task | (Re)generates hook shims idempotently — three-line `exec` wrappers to the resolved binary |
| `probatioUninstall` | sbt task | Removes resolved binaries and generated shims; supports clean teardown |
| `probatioVersion` | sbt setting | Pins the `probatio` release version the plugin resolves |
| `dependency-lint rule` | build-level check (R-ARCH1) | Fails the build if any `workflow/*` project's classpath reaches an adk4s module; enforces the extraction-ready boundary |

## ADDED Requirements

### Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code

The plugin SHALL be an sbt 1.x AutoPlugin, published for the sbt 1.x
plugin Scala version as `org.sinemenda.probatio:sbt-probatio`. The plugin
SHALL link no code from `probatio-core` (which is Scala 3 / TASTy and
therefore binary-incompatible with the plugin's Scala version). All
communication between the plugin and the tooling SHALL be argv + exit codes
+ stdout JSON — the plugin invokes the resolved `probatio` binary as a
subprocess and parses its exit code and stdout, never importing or calling
`probatio-core` types directly.

**Given** a consumer build that adds `addSbtPlugin("org.sinemenda.probatio" % "sbt-probatio" % "<version>")`
**When** the build loads
**Then** the plugin's settings and tasks are available without any
`probatio-core` dependency on the plugin's classpath

**Rationale**: `probatio-core` is Scala 3 and the sbt 1.x plugin runs on
Scala 2.12 — a TASTy cross-version link is not possible. More importantly,
the boundary is deliberate: the plugin is a thin adapter that delegates
everything to the binary, keeping the plugin trivially portable to sbt 2.x
and keeping `probatio-core` extraction-ready (R-ARCH1).

#### Scenario: plugin loads and exposes tasks

**Given** a consumer build with the plugin enabled
**When** `sbt` loads the build
**Then** the tasks `probatioInstall`, `probatioSpecLint`,
`probatioChainState`, `probatioCheckpoint`, `probatioLedgerAppend`,
`probatioGateShim`, and `probatioUninstall` are available

#### Scenario: plugin classpath contains no probatio-core artifact (adversarial)

**Given** the published plugin artifact and its dependency tree
**When** the dependency tree is inspected
**Then** no `org.sinemenda.probatio % probatio-core` artifact appears at
any level — the plugin's only dependencies are sbt APIs and the Scala 2.12
standard library

#### Scenario: plugin does not import probatio-core types (adversarial)

**Given** the plugin's compiled sources
**When** the sources are inspected for imports from `org.sinemenda.probatio.core`
**Then** zero imports are found — the plugin communicates with the tooling
exclusively via subprocess invocation (argv + exit codes + stdout JSON)

### Requirement: The plugin forward-declares future sbt 2 support

The plugin SHALL be written sbt-2-ready: settings and tasks SHALL use no
`in GlobalScope` abuse, no deprecated operators, and idiomatic `Def.task`
composition. The plugin SHALL document that it targets sbt 1.x today and
sbt 2.x unchanged (linking against the Scala 3 build on sbt 2.x) — the
forward declaration is a code-level discipline, not a runtime branch.

**Given** the plugin's settings and task definitions
**When** the source is reviewed against sbt 2.x migration guidelines
**Then** no `in GlobalScope` scope abuse is present, no deprecated sbt
operators are used, and all task composition uses `Def.task` idiomatic
patterns that compile unchanged on sbt 2.x

**Rationale**: sbt 1.x→2.x migration of this repo's build is explicitly out
of scope (proposal §2.2), but the plugin must not create gratuitous
migration debt. Writing sbt-2-ready code now costs nothing and keeps the
plugin portable.

#### Scenario: no deprecated operators in plugin source

**Given** the plugin's source files
**When** the source is scanned for deprecated sbt operators (e.g. `<<=`,
`in Config.Global`)
**Then** zero deprecated operators are found

#### Scenario: task composition uses Def.task

**Given** the plugin's task definitions
**When** the source is inspected
**Then** every task is defined via `Def.task` or `Def.setting` with
idiomatic composition, not via legacy `Project.Setting` assignment

### Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image

The `probatioInstall` task SHALL resolve the `probatio` binary in a fixed
fallback order: (1) download the prebuilt platform binary from the pinned
GitHub Release URL and verify its SHA-256 checksum; (2) if the binary is
unavailable or the checksum fails, fall back to the resolved assembly JAR
and emit a `java -jar` launcher script; (3) if a local GraalVM home is
provided (opt-in, dev/endoskeleton only), build a native-image locally.
Each fallback SHALL emit exactly one distinct sbt log line so the consumer
is never left wondering which resolution path is active. No fallback SHALL
be silent.

**Given** a consumer build with `probatioVersion := "14.0.0"` and no
prebuilt binary cached
**When** `probatioInstall` is invoked
**Then** the prebuilt binary is downloaded, checksum-verified, and placed
at the resolved binary path; if the download succeeds, one `[info]` log
line confirms the binary resolution

**Rationale**: The resolution order mirrors the requirements doc §3 design:
prebuilt binary is the happy path (native latency), JAR fallback is the
universal safety net (no consumer builds from source by default), and
local native-image is opt-in only. Silence about which path is active is
the same defect class as silent drift — the consumer must always know.

#### Scenario: prebuilt binary resolves successfully

**Given** a consumer build with network access and no cached binary
**When** `probatioInstall` runs
**Then** the prebuilt binary is downloaded, SHA-256 verified, and one
`[info]` log line confirms: "probatio binary resolved (prebuilt:
<platform>)"

#### Scenario: assembly JAR fallback emits a distinct log line

**Given** a consumer build where the prebuilt binary download fails or the
checksum does not match
**When** `probatioInstall` runs
**Then** the assembly JAR is resolved, a `java -jar` launcher script is
emitted as the binary path, and one `[warn]` log line confirms:
"probatio binary resolved (JAR fallback: startup latency active until
native binary available)"

#### Scenario: local native-image is opt-in only

**Given** a consumer build without a `GraalVMHome` setting
**When** `probatioInstall` runs
**Then** local native-image is never attempted — the resolution stops at
the JAR fallback; native-image is only triggered when the consumer
explicitly provides a GraalVM home

#### Scenario: no fallback is silent (adversarial)

**Given** a consumer build where the prebuilt binary is unavailable
**When** `probatioInstall` runs and falls back to the JAR
**Then** exactly one distinct `[warn]` log line is emitted — the fallback
is never silent, and the log line is distinguishable from the prebuilt
binary's `[info]` line

### Requirement: Task exit-code mapping distinguishes finding from undetermined

Every delegating task SHALL map the binary's exit code to a task outcome
that distinguishes a finding (exit 1) from an undetermined result (exit 2).
Exit 0 SHALL map to task success. Exit 1 SHALL map to task failure with a
message of the form `"probatio <tool> reported N finding(s): …"` including
the tool name and the finding summary from stdout. Exit 2 SHALL map to
task failure with a distinct message of the form
`"probatio <tool> could not determine: …"` including the reason from
stdout. Both exit 1 and exit 2 SHALL fail the build — a CI operator
distinguishing them in logs is by design, not by accident.

**Given** a delegating task (e.g. `probatioSpecLint`) that invokes the
`probatio` binary
**When** the binary exits with code 1
**Then** the task fails with a message containing "reported N finding(s)"
and the tool name, and the build fails

**Rationale**: The three-way exit protocol (§4.1) is the porting invariant
the whole schema exists to preserve. Collapsing exit 2 into exit 1 at the
sbt layer would reintroduce the exact "a corrupt ledger reads as clean"
defect class the schema averts. The distinct messages ensure CI logs never
conflate "found a problem" with "could not determine."

#### Scenario: exit 0 maps to task success

**Given** a delegating task that invokes the binary
**When** the binary exits with code 0
**Then** the task succeeds and the build continues

#### Scenario: exit 1 maps to finding failure

**Given** a delegating task (e.g. `probatioSpecLint`) that invokes the
binary
**When** the binary exits with code 1 and stdout contains 3 findings
**Then** the task fails with a message containing "probatio spec-lint
reported 3 finding(s)" and the build fails

#### Scenario: exit 2 maps to undetermined failure

**Given** a delegating task (e.g. `probatioChainState`) that invokes the
binary
**When** the binary exits with code 2 and stdout contains a reason
**Then** the task fails with a message containing "probatio chain-state
could not determine" and the build fails

#### Scenario: exit 1 and exit 2 produce distinguishable messages (adversarial)

**Given** two task invocations, one where the binary exits 1 and one where
it exits 2
**When** both task failure messages are compared
**Then** the messages are distinguishable: the exit-1 message contains
"reported" and "finding(s)"; the exit-2 message contains "could not
determine" — a CI operator can tell them apart without inspecting the
exit code

### Requirement: probatioGateShim regenerates hook shims idempotently

The `probatioGateShim` task SHALL (re)generate hook shims idempotently.
Each shim SHALL be exactly three lines: a shebang (`#!/usr/bin/env bash`),
an `exec` line invoking the resolved `probatio` binary with the `gate`
subcommand and forwarded arguments (`exec "<resolved-probatio>" gate "$@"`),
and a trailing newline. Running `probatioGateShim` twice on the same
resolved binary path SHALL produce byte-identical output. The
`probatioUninstall` task SHALL delete generated shims and resolved
binaries, supporting clean teardown.

**Given** a consumer build where `probatioInstall` has resolved the binary
**When** `probatioGateShim` is invoked
**Then** a shim file is written at the configured hook path with exactly
three lines: `#!/usr/bin/env bash`, `exec "<resolved-path>" gate "$@"`,
and a trailing newline

**Rationale**: Shims are the seam between hook adapters and the binary
(proposal §3 data flow). Idempotency ensures re-running the task after a
binary re-install does not produce drift. Byte-identical output is the
strongest form of idempotency — a diff after re-running is empty.

#### Scenario: shim is three lines

**Given** a resolved binary path
**When** `probatioGateShim` runs
**Then** the generated shim file contains exactly:
`#!/usr/bin/env bash`, `exec "<resolved-path>" gate "$@"`, and a trailing
newline — three lines total

#### Scenario: running twice produces byte-identical output

**Given** a shim generated by `probatioGateShim`
**When** `probatioGateShim` is run again with the same resolved binary
path
**Then** the shim file's content is byte-identical to the first run (a
`diff` produces no output)

#### Scenario: shim content changes when binary path changes

**Given** a shim generated for binary path A
**When** the binary is re-resolved to path B and `probatioGateShim` runs
again
**Then** the shim's `exec` line references path B, not path A — the shim
tracks the resolved binary

#### Scenario: probatioUninstall deletes shims and binaries

**Given** generated shims and a resolved binary from `probatioInstall`
**When** `probatioUninstall` is invoked
**Then** the shim files and the resolved binary are deleted from disk

### Requirement: The plugin does not execute at build load time beyond settings declaration

The plugin SHALL NOT execute at build load time beyond settings
declaration. No network resolution, no binary execution, and no filesystem
mutation SHALL occur during `sbt` startup. All side effects SHALL be
behind explicit tasks (`probatioInstall`, `probatioGateShim`, etc.) that
the consumer invokes deliberately. The plugin's `AutoPlugin` activation
SHALL only register settings and tasks — it SHALL NOT trigger
installation, download, or binary invocation as a side effect of build
load.

**Given** a consumer build with the plugin enabled
**When** `sbt` loads the build (without invoking any `probatio*` task)
**Then** no network request is made, no binary is executed, and no file
is written outside of settings declaration — the build loads in the same
time as a build without the plugin

**Rationale**: sbt AutoPlugin hygiene and the hook-latency lesson applied
symmetrically: a plugin that slows consumers' builds at load time is a
regression, not a convenience. The plugin's only load-time action is
declaring settings and tasks; everything else is opt-in.

#### Scenario: build load performs no network resolution (adversarial)

**Given** a consumer build with the plugin enabled and no cached binary
**When** `sbt` loads the build (no `probatio*` task invoked)
**Then** zero network requests are made — the binary is not downloaded
until `probatioInstall` is explicitly invoked

#### Scenario: build load performs no binary execution (adversarial)

**Given** a consumer build with the plugin enabled and a cached binary
**When** `sbt` loads the build (no `probatio*` task invoked)
**Then** the `probatio` binary is not executed — no subprocess is spawned
during build load

#### Scenario: build load performs no filesystem mutation (adversarial)

**Given** a consumer build with the plugin enabled
**When** `sbt` loads the build (no `probatio*` task invoked)
**Then** no shim files are written, no binary is placed on disk, and no
file is created outside of sbt's own build artifacts

### Requirement: Tooling subprojects reference zero adk4s code (R-ARCH1 cross-cutting)

The `sbt-probatio` plugin (and all `workflow/*` subprojects) SHALL
reference zero adk4s code — no adk4s library module, no test utility, no
shared source. This SHALL be enforced by a build-level dependency-lint
rule that fails the build if any `workflow/*` project's classpath reaches
an adk4s module. The plugin's dependency set SHALL be limited to sbt APIs
and the Scala 2.12 standard library.

**Given** the `sbt-probatio` subproject and its declared dependencies
**When** the dependency-lint rule runs in CI
**Then** the rule passes — no adk4s module appears on the plugin's
classpath

**Rationale**: R-ARCH1 is what keeps "extract to `sinemenda/probatio`"
(proposal §3.1 triggers) a `git filter-repo` operation rather than an
untangling. The plugin is part of that boundary: if it linked adk4s code,
extraction would require a rewrite, not a filter.

#### Scenario: dependency-lint rule passes for the plugin

**Given** the `sbt-probatio` subproject with dependencies limited to sbt
APIs and Scala 2.12 stdlib
**When** the dependency-lint rule runs
**Then** the rule passes (no adk4s module on the classpath)

#### Scenario: dependency-lint rule fails if adk4s code is added (adversarial)

**Given** a modified `sbt-probatio` subproject that adds a dependency on
an adk4s module
**When** the dependency-lint rule runs
**Then** the rule fails with a message naming the offending adk4s module
and the `workflow/*` project that references it

## Properties (Ring 3)

### Property: shim-idempotency

**Invariant**: Running `probatioGateShim` twice with the same resolved
binary path produces byte-identical shim output. The shim is always
exactly three lines (shebang, exec, trailing newline).

**Generator strategy**: `genResolvedPath` — constructive over resolved
binary paths (absolute paths, paths with spaces, paths with symlinks).
Edge cases: empty path (rejected), path with spaces, path that is a
symlink to another location.

```
forAll { (resolvedPath: ResolvedPath) =>
  val shim1 = generateShim(resolvedPath)
  val shim2 = generateShim(resolvedPath)
  shim1 == shim2 &&
  shim1.linesIterator.length == 2 &&
  shim1.startsWith("#!/usr/bin/env bash\n") &&
  shim1.contains("gate \"$@\"")
}
```

### Property: exit-code-mapping-distinct

**Invariant**: For every delegating task, exit code 1 and exit code 2
produce distinguishable failure messages. The exit-1 message contains
"reported" and "finding(s)"; the exit-2 message contains "could not
determine". No exit code maps to a silent success except exit 0.

**Generator strategy**: `genToolName` — constructive over the delegating
task names (`spec-lint`, `chain-state`, `checkpoint`, `ledger`).
`genExitCode` — enumerated over {0, 1, 2}. Edge cases: exit code 0 with
empty stdout, exit code 1 with zero findings (edge: message still says
"reported 0 finding(s)"), exit code 2 with empty reason.

```
forAll { (tool: String, findings: Int, reason: String) =>
  val msg1 = mapExitCode(tool, 1, s"$findings findings")
  val msg2 = mapExitCode(tool, 2, reason)
  msg1.contains("reported") && msg1.contains("finding(s)") &&
  msg2.contains("could not determine") &&
  msg1 != msg2
}
```

### Property: install-resolution-order

**Invariant**: The `probatioInstall` resolution order is always prebuilt
binary → assembly JAR → (opt-in) local native-image. Each fallback emits
exactly one distinct log line. The prebuilt path emits an `[info]` line;
the JAR fallback emits a `[warn]` line; the native-image path emits an
`[info]` line. No resolution path is silent.

**Generator strategy**: `genResolutionScenario` — constructive over
availability states (binary available + checksum valid, binary available +
checksum invalid, binary unavailable + JAR available, binary unavailable +
JAR unavailable + GraalVM home provided, binary unavailable + JAR
unavailable + no GraalVM home). Edge cases: checksum mismatch, network
failure, empty JAR.

```
forAll { (scenario: ResolutionScenario) =>
  val logLines = resolveBinary(scenario)
  logLines.nonEmpty &&
  logLines.length == 1 &&
  (scenario match {
    case PrebuiltAvailable => logLines.head.startsWith("[info]")
    case JarFallback => logLines.head.startsWith("[warn]")
    case NativeImage => logLines.head.startsWith("[info]")
  })
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `import org.sinemenda.probatio.core.*` in plugin source | The plugin MUST NOT link `probatio-core` (R-S1); communication is argv + exit codes + stdout JSON only, never direct type access | type system (Scala 2.12 plugin cannot consume Scala 3 TASTy) + source lint (grep for `org.sinemenda.probatio.core` imports in `workflow/plugin/src`) |
| `libraryDependencies += probatio-core` in the plugin's build definition | The plugin's classpath MUST NOT include the `probatio-core` artifact at any level | dependency-lint rule (R-ARCH1) — fails if `probatio-core` appears on the plugin's classpath |
| `libraryDependencies += adk4s-core` (or any adk4s module) in the plugin's build definition | The plugin MUST NOT reference any adk4s code (R-ARCH1) | dependency-lint rule — fails if any `org.adk4s` module appears on any `workflow/*` project's classpath |
| `in GlobalScope` scope abuse in plugin settings | The plugin MUST be sbt-2-ready (R-S2); `in GlobalScope` is deprecated/misused | source lint (grep for `in GlobalScope` in plugin source) |
| Deprecated sbt operators (`<<=`, `in Config.Global`) in plugin source | The plugin MUST be sbt-2-ready (R-S2); deprecated operators create migration debt | source lint (grep for deprecated operators) |
| Network resolution or binary execution in `AutoPlugin` `buildSettings`/`projectSettings` initialization | The plugin MUST NOT execute at build load time (R-S6); all side effects behind explicit tasks | source lint (no `Process(...)`/`URL(...)`/`os.read(...)` calls in settings initialization) + load-time test (build load produces no network/file side effects) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Plugin is an sbt 1.x AutoPlugin | Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code | compilation (Scala 2.12, sbt 1.x plugin) + scenario test | workflow/plugin source, SbtPluginSpec |
| Plugin links no probatio-core code | Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code | compile-negative (type system: Scala 2.12 cannot consume Scala 3 TASTy) + source lint + dependency-lint | workflow/plugin source, build.sbt |
| Plugin classpath has no probatio-core artifact (adversarial) | Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code | dependency-lint rule (R-ARCH1) — fails if probatio-core appears on plugin classpath | build.sbt, dependency-lint task |
| Plugin does not import probatio-core types (adversarial) | Requirement: The plugin is a valid sbt 1.x AutoPlugin that links no probatio-core code | source lint (grep for org.sinemenda.probatio.core imports) | workflow/plugin source |
| Plugin is sbt-2-ready (no deprecated operators) | Requirement: The plugin forward-declares future sbt 2 support | source lint (grep for deprecated operators) + scenario test | workflow/plugin source, SbtPluginSpec |
| Task composition uses Def.task | Requirement: The plugin forward-declares future sbt 2 support | source lint (grep for Def.task/Def.setting usage) + scenario test | workflow/plugin source |
| Install resolution order is binary → JAR → native-image | Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image | property test (install-resolution-order) + scenario tests | SbtPluginSpec |
| Each fallback emits one distinct log line | Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image | property test (install-resolution-order) + scenario test | SbtPluginSpec |
| No fallback is silent (adversarial) | Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image | property test (install-resolution-order) — asserts logLines.nonEmpty | SbtPluginSpec |
| Local native-image is opt-in only | Requirement: probatioInstall resolution order is prebuilt binary, assembly JAR, then opt-in local native-image | scenario test (no GraalVMHome → native-image never attempted) | SbtPluginSpec |
| Exit 1 maps to finding failure | Requirement: Task exit-code mapping distinguishes finding from undetermined | property test (exit-code-mapping-distinct) + scenario test | SbtPluginSpec |
| Exit 2 maps to undetermined failure | Requirement: Task exit-code mapping distinguishes finding from undetermined | property test (exit-code-mapping-distinct) + scenario test | SbtPluginSpec |
| Exit 1 and exit 2 produce distinguishable messages (adversarial) | Requirement: Task exit-code mapping distinguishes finding from undetermined | property test (exit-code-mapping-distinct) — asserts msg1 != msg2 | SbtPluginSpec |
| Both exit 1 and exit 2 fail the build | Requirement: Task exit-code mapping distinguishes finding from undetermined | scenario test (task failure propagates to build failure) | SbtPluginSpec |
| Shim is three lines | Requirement: probatioGateShim regenerates hook shims idempotently | property test (shim-idempotency) + scenario test | SbtPluginSpec |
| Running twice produces byte-identical output | Requirement: probatioGateShim regenerates hook shims idempotently | property test (shim-idempotency) — asserts shim1 == shim2 | SbtPluginSpec |
| Shim tracks resolved binary path | Requirement: probatioGateShim regenerates hook shims idempotently | scenario test (path change → shim content changes) | SbtPluginSpec |
| probatioUninstall deletes shims and binaries | Requirement: probatioGateShim regenerates hook shims idempotently | scenario test (files deleted after uninstall) | SbtPluginSpec |
| No network resolution at build load (adversarial) | Requirement: The plugin does not execute at build load time beyond settings declaration | load-time test (build load produces zero network requests) + source lint | SbtPluginSpec, workflow/plugin source |
| No binary execution at build load (adversarial) | Requirement: The plugin does not execute at build load time beyond settings declaration | load-time test (build load spawns zero subprocesses) + source lint | SbtPluginSpec, workflow/plugin source |
| No filesystem mutation at build load (adversarial) | Requirement: The plugin does not execute at build load time beyond settings declaration | load-time test (build load writes zero files outside sbt artifacts) + source lint | SbtPluginSpec, workflow/plugin source |
| Zero adk4s code on plugin classpath (R-ARCH1) | Requirement: Tooling subprojects reference zero adk4s code (R-ARCH1 cross-cutting) | dependency-lint rule — fails if any org.adk4s module on workflow/* classpath | build.sbt, dependency-lint task |
| Dependency-lint fails if adk4s code is added (adversarial) | Requirement: Tooling subprojects reference zero adk4s code (R-ARCH1 cross-cutting) | dependency-lint rule — scenario test with injected adk4s dependency | SbtPluginSpec |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ProbatioPlugin` | sbt AutoPlugin (Scala 2.12) | `org.sinemenda.probatio.plugin` | `object ProbatioPlugin extends AutoPlugin`; overrides `trigger := allRequirements`; registers settings/tasks in `projectSettings` |
| `probatioVersion` | sbt setting (`SettingKey[String]`) | `org.sinemenda.probatio.plugin` | Pins the `probatio` release version; default `"14.0.0"` |
| `probatioInstall` | sbt task (`TaskKey[File]`) | `org.sinemenda.probatio.plugin` | Resolves binary in order: prebuilt → JAR → native-image; returns the resolved binary `File`; emits one log line per fallback |
| `probatioSpecLint` | sbt task (`TaskKey[Unit]`) | `org.sinemenda.probatio.plugin` | `Def.task` invoking `probatioInstall.value` then `Process(binary +: "spec-lint" +: args).!`; maps exit 0→success, 1→`sys.error("probatio spec-lint reported N finding(s): …")`, 2→`sys.error("probatio spec-lint could not determine: …")` |
| `probatioChainState` | sbt task (`TaskKey[Unit]`) | `org.sinemenda.probatio.plugin` | Same exit-code mapping pattern as `probatioSpecLint`, tool name `"chain-state"` |
| `probatioCheckpoint` | sbt task (`TaskKey[Unit]`) | `org.sinemenda.probatio.plugin` | Same exit-code mapping pattern, tool name `"checkpoint"` |
| `probatioLedgerAppend` | sbt task (`TaskKey[Unit]`) | `org.sinemenda.probatio.plugin` | Delegates to `probatio ledger append`; append-only by construction (no `update`/`delete` subcommand exists in the binary) |
| `probatioGateShim` | sbt task (`TaskKey[File]`) | `org.sinemenda.probatio.plugin` | Writes three-line shim: `#!/usr/bin/env bash`, `exec "<resolved-probatio>" gate "$@"`, trailing newline; idempotent |
| `probatioUninstall` | sbt task (`TaskKey[Unit]`) | `org.sinemenda.probatio.plugin` | Deletes resolved binaries and generated shims; supports clean teardown |
| `GraalVMHome` | sbt setting (`SettingKey[Option[String]]`) | `org.sinemenda.probatio.plugin` | Opt-in: when `Some(path)`, `probatioInstall` may build native-image locally; when `None` (default), native-image is never attempted |
| `dependency-lint rule` | build-level check | `build.sbt` (or `project/` plugin) | Fails if any `workflow/*` project's classpath reaches an adk4s module; enforced as a CI step alongside other R-ARCH checks |
| `sbt-probatio` subproject | sbt subproject | `build.sbt` | `lazy val sbtProbatio = (project in file("workflow/plugin")).enablePlugins(SbtPlugin)`; Scala 2.12; `publishMavenStyle := true`; `organization := "org.sinemenda.probatio"`; `name := "sbt-probatio"` |
| `SbtPluginSpec` | test suite (Hedgehog) | `workflow/plugin/src/test/scala/...` | Runs shim-idempotency, exit-code-mapping-distinct, install-resolution-order properties + all scenario tests |
| `hedgehog-munit` | test dependency | `workflow/plugin` build def | `qa.hedgehog %% hedgehog-munit % 0.13.1 % Test` — Hedgehog 0.13.1 (NOT ScalaCheck, per `capability-profile.md`) |
| sbt 1.12.12 | build version | `project/build.properties` | `sbt.version=1.12.12` — the plugin targets sbt 1.x; verified in `capability-check.md` |
| Scala 2.12 | plugin Scala version | `workflow/plugin` build def | sbt 1.x plugins run on Scala 2.12; the plugin's `scalaVersion := "2.12.x"` (sbt-managed) |
