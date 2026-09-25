# Spec: Delivery Verified

The workflow's continuous-integration job exists and has never run. Its "a regressing
change fails the job" obligation was discharged by inference from a local command's exit
status. No release has ever been built, and the release workflow would build with a
different compiler toolchain from the one every test ran against. This spec replaces each
of those inferences with an observation.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Gate** action is meant to run on every change without being asked. This spec observes that it does. The protocol's actions and state are unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ReleaseCheck` | object | `org.sinemenda.probatio.packaging` |
| `ReleaseValidator` | object | `org.sinemenda.probatio.packaging` |
| `ReleaseManifest` | final case class | `org.sinemenda.probatio.packaging` |
| `LatencyMeasurement` | final case class | `org.sinemenda.probatio.packaging` |
| `BudgetVerdict` | enum | `org.sinemenda.probatio.packaging` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ToolchainIdentity` | final case class (distribution, version) | The compiler toolchain a native binary was built with, read from the binary rather than assumed from configuration. |

### Type-Widening Impact

No public type is widened. `ToolchainIdentity` is new; the release check gains a field that
carries it.

## ADDED Requirements

### MUST-CONFIRM — running the job requires publishing the branch

The job runs only on the hosting service, on a pull request or a push to the main branch.
Making it run means pushing this branch there — an outward action that publishes the
branch. **It is performed only with the maintainer's explicit authorisation at apply
time**, and never inferred from this spec's approval.

### Requirement: The CI job is observed to run and pass

The continuous-integration job SHALL be observed to run to completion and pass at a commit
where every suite is green, and its success MUST NOT be inferred from local commands.

**Given** a commit at which the acceptance suite, the differential comparison and every
module suite pass locally
**When** the CI job runs on the hosting service for that commit
**Then** it completes and passes, and the run's identifier and outcome are recorded in the
evidence record

#### Scenario: Happy path — the job passes at a green commit

**Given** a green commit, published with the maintainer's authorisation
**When** the job runs
**Then** it passes, and its run is recorded

#### Scenario: Adversarial — a locally inferred outcome is not a recorded run

**Given** an evidence entry that records a local command's exit status as the job's outcome
**When** the evidence for this requirement is checked
**Then** it does not satisfy this requirement — only a recorded hosted run does

### Requirement: The CI job is observed to fail on a regression

The continuous-integration job SHALL be observed to fail on a branch that deliberately
regresses one acceptance file, and the failure MUST name the regressing file.

**Given** a branch that makes one acceptance file worse than the predecessor control
**When** the job runs for that branch
**Then** it fails, and its log names the regressing file

#### Scenario: Happy path — a regressing branch fails the job, naming the file

**Given** a deliberately regressing branch, published with authorisation
**When** the job runs
**Then** it fails and names the regressing acceptance file

#### Scenario: Adversarial — a regression in a module suite also fails the job

**Given** a branch that makes one module test suite fail and leaves the acceptance suite green
**When** the job runs
**Then** it fails, naming that suite

### Requirement: The CI job runs the tools through the archive

The job SHALL exercise the acceptance suite with the tools run through the assembly
archive, as a fresh clone does, and it MUST NOT depend on a natively built executable.

**Given** the job's configuration
**When** its steps are read
**Then** the acceptance suite runs against the archive, with no native build step before it

#### Scenario: Happy path — the job's acceptance step uses the archive

**Given** the job's configuration
**When** its acceptance step runs
**Then** the tools it invokes resolve to the archive

#### Scenario: Adversarial — a native build step before the acceptance step is reported

**Given** a configuration that builds the native executable before running the acceptance
suite
**When** the configuration check runs
**Then** it reports that the acceptance suite no longer exercises the archive path

### Requirement: The delivered binary is built with the toolchain that was tested

A native binary the release workflow produces SHALL be built with the same toolchain as the
binary the conformance suite and the latency budget were measured against, and a release
candidate built with an untested toolchain MUST be rejected by the release check.

**Given** a release candidate
**When** the release check runs
**Then** the candidate's toolchain identity equals the toolchain identity recorded for the
tested binary

**Rationale**: on 2026-09-25 the locally built binary reported GraalVM 22.3.1 (the build
plugin's unpinned default), and the release workflow installs GraalVM CE 21.0.2. Every
test, every review of the built artifact, and the recorded 129.4 ms latency measurement
concerned 22.3.1. A release would ship a binary none of them ran.

#### Scenario: Happy path — a candidate on the tested toolchain is accepted

**Given** a release candidate whose toolchain identity matches the tested one
**When** the release check runs
**Then** it accepts the candidate

#### Scenario: Adversarial — a candidate on a different toolchain is rejected

**Given** a release candidate built with a toolchain version other than the tested one
**When** the release check runs
**Then** it rejects the candidate, naming both toolchain identities

#### Scenario: Error path — an unreadable toolchain identity is could-not-determine

**Given** a binary whose embedded toolchain identity cannot be read
**When** the release check runs
**Then** the outcome is could-not-determine naming the binary, and the candidate is not
accepted

### Requirement: A release candidate is built and validated locally

A release candidate SHALL be built and validated by the release check on the maintainer's
machine, every artifact the release names being present with a matching checksum, and
cutting a public release MUST remain a separate decision.

**Given** a release candidate build
**When** the release check runs
**Then** every named artifact is present and its checksum matches

#### Scenario: Happy path — a complete candidate validates

**Given** a candidate with the native binary, the archive, checksums, the bill of materials
and the sources
**When** the release check runs
**Then** it passes

#### Scenario: Adversarial — a candidate missing one artifact is rejected

**Given** a candidate missing its bill of materials
**When** the release check runs
**Then** it fails naming the missing artifact

## Properties (Ring 3)

### Property: toolchain-check-accepts-iff-identical

**Invariant**: for every pair of tested and candidate toolchain identities, the release check
accepts if and only if they are equal; an unreadable identity is never accepted.

**Generator strategy**: `genToolchainPair` — constructive over identities drawn from a small
closed set of distributions and versions, paired so that equal, differing-version and
differing-distribution cases arise by construction; plus an unreadable case.

```
property("the toolchain check accepts iff identical") {
  for {
    (tested, candidate) <- genToolchainPair.forAll
    v = toolchainVerdict(tested, candidate)
  } yield Result.assert(v.accepted == (candidate.readable && candidate == tested))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A release check that omits the toolchain identity | Checking checksums without the toolchain is exactly what would let an untested binary ship | `assertDoesNotCompile("ReleaseCheck.validate(manifest)")` — validation requires the tested toolchain identity |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: an equality check and an artifact-presence check with no
fold or law at their centre; pinned by the property and the release manifest suite.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Publishing the branch is authorised, not inferred | MUST-CONFIRM: running the job requires publishing the branch | manual — the maintainer's authorisation is recorded before the push | `verify.yml` run recorded in the evidence ledger |
| The job passes at a green commit | Requirement: The CI job is observed to run and pass + Scenario: Happy path — the job passes at a green commit | observed hosted run, identifier and outcome recorded | `verify.yml` run recorded in the evidence ledger |
| A local inference is not a recorded run | Requirement: The CI job is observed to run and pass + Scenario: Adversarial — a locally inferred outcome is not a recorded run | manual review of the evidence entry at the checkpoint | `verify.yml` run recorded in the evidence ledger |
| A regressing branch fails the job, naming the file | Requirement: The CI job is observed to fail on a regression + Scenario: Happy path — a regressing branch fails the job, naming the file | observed hosted run on a deliberately regressing branch | `verify.yml` run recorded in the evidence ledger |
| A module-suite regression fails the job | Requirement: The CI job is observed to fail on a regression + Scenario: Adversarial — a regression in a module suite also fails the job | observed hosted run on a deliberately breaking branch | `verify.yml` run recorded in the evidence ledger |
| The acceptance step uses the archive | Requirement: The CI job runs the tools through the archive + Scenario: Happy path — the job's acceptance step uses the archive | scenario test over the configuration | `PluginSourceLintSpec` |
| A native build before the acceptance step is reported | Requirement: The CI job runs the tools through the archive + Scenario: Adversarial — a native build step before the acceptance step is reported | scenario test over the configuration | `PluginSourceLintSpec` |
| A candidate on the tested toolchain is accepted | Requirement: The delivered binary is built with the toolchain that was tested + Scenario: Happy path — a candidate on the tested toolchain is accepted | scenario test | `ReleaseManifestIOSpec` |
| A candidate on a different toolchain is rejected | Requirement: The delivered binary is built with the toolchain that was tested + Scenario: Adversarial — a candidate on a different toolchain is rejected + Property: toolchain-check-accepts-iff-identical | Hedgehog property | `ReleaseManifestIOSpec` |
| An unreadable toolchain identity is could-not-determine | Requirement: The delivered binary is built with the toolchain that was tested + Scenario: Error path — an unreadable toolchain identity is could-not-determine | scenario test | `ReleaseManifestIOSpec` |
| The release check cannot omit the toolchain | Compile-Negative: A release check that omits the toolchain identity | compile-negative test | `NativeGateDeliveryTypeContract` |
| A complete candidate validates | Requirement: A release candidate is built and validated locally + Scenario: Happy path — a complete candidate validates | manual build, recorded in the evidence ledger | `NativeGateDeliverySpec` |
| A candidate missing an artifact is rejected | Requirement: A release candidate is built and validated locally + Scenario: Adversarial — a candidate missing one artifact is rejected | scenario test | `ReleaseManifestIOSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| CI job | workflow configuration | `.github/workflows/verify.yml` | Triggers on pull requests and pushes to `main`; builds only the archive; tracked with `git add -f` because `.github/` is globally ignored |
| Release workflow | workflow configuration | `.github/workflows/release-probatio.yml:37,41,45` | Installs GraalVM CE 21.0.2 |
| Local native build | build setting | `build.sbt` (the `probatio-cli` native-image settings) | Pins no `nativeImageVersion`; the plugin default produced a 22.3.1 binary |
| `ReleaseCheck` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/packaging/ReleaseCheck.scala` | Gains the toolchain comparison |
| Latency record | evidence | the archived `complete-probatio-cutover` evidence record (129.4 ms median, 100 runs) | Measured on 22.3.1; re-measured if the release toolchain differs |
| Ring 5 note | — | `stryker4s.conf` | Retarget to the release check |
