# Spec: Native Gate Delivery

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The shim's target is what the protocol swaps to; this spec makes that target the artifact the packaging requirement names | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |

This spec does not alter the concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `BinaryResolution` | object | `org.sinemenda.probatio.packaging` |
| `Platform` | sealed hierarchy | `org.sinemenda.probatio.packaging` |
| `ReleaseManifest` | final case class | `org.sinemenda.probatio.packaging` |
| `ReleaseValidator` | object | `org.sinemenda.probatio.packaging` |
| `ChecksumVerifier` | object | `org.sinemenda.probatio.packaging` |
| `SbomModel` | final case class | `org.sinemenda.probatio.packaging` |
| `InstallResolver` | object | `org.sinemenda.probatio.plugin` |
| `ShimGenerator` | object | `org.sinemenda.probatio.plugin` |
| `ExitCodeMapping` | object | `org.sinemenda.probatio.plugin` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `LatencyMeasurement` | final case class | A recorded start-up measurement: the sample count, the observed median, the observed maximum, and the artifact kind measured |

## ADDED Requirements

### Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed

The delivery SHALL measure the per-turn tool's start-up latency on the target
platform and record the measurement before delivering against a latency budget,
and SHALL NOT report a budget as met without a recorded measurement.

**Given** a delivered per-turn tool
**When** its budget compliance is reported
**Then** the report cites a measurement taken on the target platform, with its
sample count and observed median

**Rationale**: The packaging requirement states that an unmet budget is a hard
blocker rather than a degradation to accept. That is only meaningful if the budget
is measured. Measured 2026-08-29 on this host, the currently-delivered launcher
takes 200–300 ms per invocation against a 150 ms median budget — a number nobody
had taken, on an artifact the shims already point at.

#### Scenario: Happy path — a measured median within budget is recorded and the delivery proceeds

**Given** a per-turn tool whose measured median start-up is below the budget
**When** compliance is reported
**Then** the measurement is recorded and the delivery proceeds

#### Scenario: Adversarial — an unmeasured tool is not reported as meeting the budget

**Given** a per-turn tool with no recorded measurement
**When** compliance is reported
**Then** the report states that the budget is undetermined, not that it is met

#### Scenario: Adversarial — a measured median above budget blocks the delivery

**Given** a per-turn tool whose measured median start-up exceeds the budget
**When** compliance is reported
**Then** the delivery does not proceed and the measurement is recorded as the
reason

#### Scenario: Edge case — a measurement with too few samples is not a median

**Given** a measurement taken from fewer samples than the minimum the measurement
procedure requires
**When** compliance is reported
**Then** the report states that the budget is undetermined, naming the insufficient
sample count

### Requirement: The per-turn tool is delivered as a native artifact where one exists for the platform

On a platform for which a native artifact is produced, the per-turn tool SHALL be
delivered as that native artifact, and SHALL NOT be delivered through the
interpreted-runtime launcher.

**Given** a platform for which a native artifact is produced
**When** the per-turn tool is delivered
**Then** the delivered artifact is the native one

**Rationale**: The resolution logic for this already exists and returns a blocked
result for exactly this case. Nothing calls it: the shims point at the launcher
directly, so the rule is stated in code that never runs.

#### Scenario: Adversarial — the launcher is not accepted for the per-turn tool on a native platform

**Given** a platform for which a native artifact is produced, and only the launcher
present
**When** the per-turn tool is resolved
**Then** the resolution is blocked, naming that a native artifact is required, and
the shim is not written to point at the launcher

#### Scenario: Happy path — the native artifact is resolved and the shim points at it

**Given** a platform for which a native artifact is produced, and that artifact
present
**When** the per-turn tool is resolved and the shim written
**Then** the shim points at the native artifact

#### Scenario: Edge case — a once-per-ring tool may use the launcher with exactly one warning

**Given** a platform for which a native artifact is produced, only the launcher
present, and a once-per-ring tool
**When** the tool is resolved
**Then** the launcher is used and exactly one warning line is emitted naming the
tool and the fallback

#### Scenario: Edge case — a platform with no native artifact uses the launcher for every tool

**Given** a platform for which no native artifact is produced
**When** any tool is resolved
**Then** the launcher is used and exactly one warning line is emitted

### Requirement: The resolution result determines the shim's target

The shim generation SHALL write the target the resolution returned, and SHALL NOT
write a target the resolution blocked.

**Given** a resolution result
**When** the shim is written
**Then** its target is the artifact the resolution returned, and no shim is written
when the resolution is blocked

**Rationale**: The shims currently carry a hardcoded path, so the resolution
logic's verdict cannot reach them. Binding the two makes the packaging rule
enforceable rather than documentary.

#### Scenario: Adversarial — a blocked resolution writes no shim

**Given** a resolution that returned blocked
**When** shim generation runs
**Then** no shim is written and the reason is reported

#### Scenario: Happy path — a resolved target is written and the write is repeatable

**Given** a resolution that returned an artifact
**When** shim generation runs twice
**Then** the shim points at that artifact after each run and its content is
identical between the runs

#### Scenario: Error path — an unwritable shim location is could-not-determine

**Given** a shim location that cannot be written
**When** shim generation runs
**Then** the result is could-not-determine naming the location

### Requirement: The build integration passes each tool the arguments its invocation requires

Each build task that delegates to a tool SHALL pass that tool the arguments its
invocation requires, and a task that cannot supply them SHALL report that rather
than invoking the tool without them.

**Given** a build task delegating to a tool
**When** it runs
**Then** the tool is invoked with the arguments its invocation requires

**Rationale**: The delegating tasks currently invoke each tool with its name and
nothing else. Every one of those tools requires parameters, so each task either
fails on a missing parameter or, for a tool with defaults, runs against the wrong
target. The task surface is present and inoperable.

#### Scenario: Happy path — a task supplies the required arguments and the tool runs

**Given** a build task configured with the values its tool requires
**When** it runs
**Then** the tool is invoked with them and the task outcome follows the tool's exit
status

#### Scenario: Error path — a task lacking a required value reports it rather than invoking

**Given** a build task with a required value unset
**When** it runs
**Then** the task reports the missing value and the tool is not invoked

#### Scenario: Adversarial — a tool's finding status and could-not-determine status produce distinguishable task failures

**Given** a tool that exits with the finding status, and the same tool exiting with
the could-not-determine status
**When** the task maps each outcome
**Then** the two task failures carry distinguishable messages

### Requirement: A release carries every artifact the delivery names

A release SHALL include, for each supported platform, the artifacts the delivery
names, and a release missing any of them SHALL NOT be reported complete.

**Given** a release manifest
**When** its completeness is reported
**Then** the report is complete only if every named artifact is present for every
supported platform

**Rationale**: The validation for this exists as a pure function and is not wired
to any release step, so a release's completeness is currently an unchecked claim.

#### Scenario: Happy path — a complete manifest reports complete

**Given** a manifest carrying every named artifact for every supported platform
**When** completeness is reported
**Then** the report is complete

#### Scenario: Adversarial — a manifest missing one artifact is not reported complete

**Given** a manifest missing one named artifact for one supported platform
**When** completeness is reported
**Then** the report names the missing artifact and is not complete

#### Scenario: Error path — a manifest whose checksum does not match its artifact is not complete

**Given** a manifest whose recorded checksum differs from the artifact's
**When** completeness is reported
**Then** the report names the mismatch and is not complete

## Properties (Ring 3)

### Property: budget-verdict-requires-a-measurement

**Invariant**: A budget is reported met only when a measurement with at least the
minimum sample count exists and its median is below the budget.

**Generator strategy**: `genLatencyMeasurement` — constructive: an optional
measurement (present/absent chosen 50/50), with sample counts drawn from a range
straddling the minimum and medians drawn from a range straddling the budget.
Hedgehog `cover`: `absent` ≥ 25%, `too-few-samples` ≥ 20%, `median-under` ≥ 20%,
`median-over` ≥ 20%.

```
forAll { (m: Option[LatencyMeasurement], budget: Int) =>
  (verdict(m, budget) == Met) ==
    m.exists(x => x.sampleCount >= minSamples && x.median < budget)
}
```

### Property: per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform

**Invariant**: For every platform producing a native artifact, resolving the
per-turn tool never returns the launcher.

**Generator strategy**: `genPlatform` × `genArtifactAvailability` × `genToolName` —
constructive: platforms enumerate the supported set plus one with no native
artifact; availability is a boolean; tool names are drawn from the exposed set with
the per-turn tool over-represented. Hedgehog `cover`: `per-turn-tool` ≥ 40%,
`native-platform` ≥ 60%, `artifact-absent` ≥ 40%.

```
forAll { (p: Platform, available: Boolean, tool: String) =>
  (p.hasNativeArtifact && isPerTurn(tool)) ==>
    (BinaryResolution.resolve(tool, p, available, launcherPath) match
       case ResolutionResult.JarFallback(_, _) => false
       case _                                  => true)
}
```

### Property: exactly-one-warning-on-fallback

**Invariant**: Every resolution that falls back to the launcher emits exactly one
warning line, containing no embedded line break.

**Generator strategy**: as above, restricted constructively to the fallback cases
(non-native platform, or native platform with a once-per-ring tool and no
artifact). Hedgehog `cover`: `non-native-platform` ≥ 40%,
`once-per-ring-fallback` ≥ 40%.

```
forAll { (r: FallbackResolution) =>
  r.warnings.length == 1 && !r.warnings.head.contains('\n')
}
```

### Property: shim-target-equals-resolution-and-is-repeatable

**Invariant**: For every non-blocked resolution, the generated shim names the
resolved artifact, and generating it twice yields identical content.

**Generator strategy**: `genResolutionResult` — constructive over the resolution
variants, with generated artifact paths including ones containing spaces and
non-ASCII characters. Hedgehog `cover`: `native` ≥ 30%, `launcher` ≥ 30%,
`path-with-space` ≥ 15%.

```
forAll { (r: ResolutionResult) =>
  !r.isBlocked ==> {
    val a = ShimGenerator.generateShim(r.artifactPath, subcommand)
    val b = ShimGenerator.generateShim(r.artifactPath, subcommand)
    a == b && a.contains(r.artifactPath)
  }
}
```

### Property: release-complete-iff-every-named-artifact-present-and-matching

**Invariant**: A manifest is reported complete exactly when every named artifact is
present for every supported platform and every recorded checksum matches.

**Generator strategy**: `genReleaseManifest` — constructive: for each supported
platform, each named artifact independently present or absent, and each present
artifact's checksum independently matching or not. Hedgehog `cover`:
`complete` ≥ 20%, `missing-artifact` ≥ 25%, `checksum-mismatch` ≥ 25%,
`missing-platform` ≥ 15%.

```
forAll { (m: ReleaseManifest) =>
  (ReleaseValidator.validateAll(m).isEmpty) ==
    (m.everyNamedArtifactPresent && m.everyChecksumMatches)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A budget verdict function that does not take a measurement | An assumed budget is the defect; the verdict cannot be computed without evidence | `assertDoesNotCompile` in `NativeGateDeliverySpec` |
| `ShimGenerator.generateShim` called with a literal path rather than a resolution result | A hardcoded shim target bypasses the resolution rule | `assertDoesNotCompile` in `NativeGateDeliverySpec` |
| A delegating build task constructed with only a tool name and no argument list | A task that cannot pass arguments cannot drive its tool | `assertDoesNotCompile` in `PluginSourceLintSpec` (existing suite, extended) |

## Ring 6 Applicability

Ring 6 does not apply to this spec, and no formal contract is declared. The decisions here — budget verdict, resolution,
completeness — are already covered by their Ring 3 properties and contain no fold
or recursion whose invariant a solver would establish beyond what the properties
state. The proposal classifies this spec's typed contract as minimal for the same
reason. Recorded as a stated decision rather than an omission.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A budget is reported met only against a recorded measurement | Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed + Property: budget-verdict-requires-a-measurement | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativeGateDeliverySpec.scala` |
| An unmeasured tool is reported undetermined, not met | Scenario: Adversarial — an unmeasured tool is not reported as meeting the budget + Compile-Negative: A budget verdict function that does not take a measurement | compile-negative test + scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativeGateDeliverySpec.scala` |
| A median above budget blocks the delivery | Scenario: Adversarial — a measured median above budget blocks the delivery | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativeGateDeliverySpec.scala` |
| An undersized sample set is not a median | Scenario: Edge case — a measurement with too few samples is not a median | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativeGateDeliverySpec.scala` |
| The measured median for the delivered per-turn tool is recorded in this change's evidence | Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed | manual run recorded in the evidence record — the measurement is taken on the target host and its record is the artifact | `openspec/changes/complete-probatio-cutover/evidence-ledger.jsonl` |
| The per-turn tool never resolves to the launcher on a native platform | Requirement: The per-turn tool is delivered as a native artifact where one exists for the platform + Property: per-turn-tool-never-resolves-to-the-launcher-on-a-native-platform | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` (existing suite, extended) |
| A blocked resolution writes no shim | Scenario: Adversarial — the launcher is not accepted for the per-turn tool on a native platform + Scenario: Adversarial — a blocked resolution writes no shim | scenario tests | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/HookCutoverShimSpec.scala` (existing suite, extended) |
| A fallback emits exactly one single-line warning | Scenario: Edge case — a once-per-ring tool may use the launcher with exactly one warning + Property: exactly-one-warning-on-fallback | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` |
| A non-native platform uses the launcher for every tool | Scenario: Edge case — a platform with no native artifact uses the launcher for every tool | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` |
| The shim's target is the resolution's result and generation is repeatable | Requirement: The resolution result determines the shim's target + Property: shim-target-equals-resolution-and-is-repeatable | property test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/ShimGeneratorSpec.scala` (existing suite, extended) |
| A hardcoded shim target cannot be written | Compile-Negative: ShimGenerator.generateShim called with a literal path rather than a resolution result | compile-negative test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/HookCutoverShimSpec.scala` |
| An unwritable shim location is could-not-determine | Scenario: Error path — an unwritable shim location is could-not-determine | scenario test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/ShimGeneratorSpec.scala` |
| Each delegating task passes its tool's required arguments | Requirement: The build integration passes each tool the arguments its invocation requires + Scenario: Happy path — a task supplies the required arguments and the tool runs | scenario test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/ExitCodeMappingSpec.scala` (existing suite, extended) |
| A task with a missing required value reports rather than invokes | Scenario: Error path — a task lacking a required value reports it rather than invoking + Compile-Negative: A delegating build task constructed with only a tool name and no argument list | compile-negative test + scenario test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/PluginSourceLintSpec.scala` |
| The finding and could-not-determine statuses produce distinguishable task failures | Scenario: Adversarial — a tool's finding status and could-not-determine status produce distinguishable task failures | scenario test | `workflow/plugin/src/test/scala/org/sinemenda/probatio/plugin/ExitCodeMappingSpec.scala` |
| A release is complete exactly when every named artifact is present and matching | Requirement: A release carries every artifact the delivery names + Property: release-complete-iff-every-named-artifact-present-and-matching | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` |
| A missing artifact and a checksum mismatch each prevent completeness | Scenario: Adversarial — a manifest missing one artifact is not reported complete + Scenario: Error path — a manifest whose checksum does not match its artifact is not complete | scenario tests | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` |
| The delivered artifact is the one the shims resolve, and it starts | Requirement: The resolution result determines the shim's target | subprocess conformance test (shared with the cli-entrypoint-contract spec) | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/SubprocessConformanceSpec.scala` |
| No budget was reported met without evidence, and no resolution rule was relaxed | Requirement: The per-turn tool's start-up latency is measured and recorded, never assumed | adversarial review (Ring 8), fresh context | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `nativeImage` | build step | `probatio-cli` | already configured (`--no-fallback`, `-O1`, output `target/native-image/probatio`); no binary has ever been produced. Requires a GraalVM toolchain on the host — a setup task, recorded in `capability-check.md` |
| `LatencyMeasurement` | final case class (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/LatencyMeasurement.scala` | carries the sample count so an undersized set cannot be read as a median |
| `BinaryResolution.resolve` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/BinaryResolution.scala` | unchanged; this spec wires it to shim generation |
| `ShimGenerator.generateShim` | method | `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/ShimGenerator.scala` | signature changes to take a resolution result rather than a path string |
| `ProbatioPlugin.probatioInstall` | task | `workflow/plugin/src/main/scala/org/sinemenda/probatio/plugin/ProbatioPlugin.scala` | currently assumes a present binary is checksum-valid and writes a launcher referencing an environment variable nothing sets; both are corrected |
| `ProbatioPlugin.runDelegatingTask` | method | same file | gains an argument list per task |
| `openspec/schemas/verified-scala3/bin/probatio` | launcher script | repository | remains as the non-native fallback; the shims' target becomes the resolution's result |
| `ReleaseValidator.validateAll` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/ReleaseValidator.scala` | unchanged; this spec wires it to the release step |
| `sbt "probatio-cli/nativeImage"` | build step | `probatio-cli` | produces the native artifact the per-turn tool requires |
