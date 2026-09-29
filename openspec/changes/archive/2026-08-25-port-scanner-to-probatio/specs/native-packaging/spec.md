# Spec: Native Packaging

<!-- This is a DELTA spec for the `port-scanner-to-probatio` change. It covers
     the `native-packaging` capability (R-N1…R-N5): distribution of the ported
     `probatio` tooling as GraalVM native binaries, the per-turn gate latency
     budget, release artifact composition, CI-reproducible release pipeline, and
     the scalameta native-image spike gate.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - CONCURRENCY RULE: requirements about concurrent execution state a
       DETERMINISTIC observable. Latency measurement is NOT a concurrency
       requirement — it is a Ring 5 measurement gate (see R-N1's distinction).
     - ALTITUDE RULE: behavioral vocabulary in requirements; code identifiers
       (sbt task names, file paths, class names) live in Implementation Anchors.
     - R-ARCH1: this spec introduces zero adk4s types. "Concepts Used (from
       inventory)" is empty by construction. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | Packaging is a distribution concern; no behavioral concept from the registry is consumed | — |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

> R-ARCH1: the probatio tooling subprojects reference zero adk4s code — no
> library module, test utility, or shared source. This table is empty by
> construction, enforced by a build-level dependency-lint rule (declared in
> `specs/non-goals-guard/spec.md`). The packaging artifacts are self-contained.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| probatio multicall binary | native-image launcher | A single GraalVM native-image executable that dispatches on `argv(1)` (or `argv(0)` symlink) to one subcommand per predecessor script; the per-turn `gate` subcommand is the latency-critical path |
| release artifacts | distribution bundle | The set of artifacts every release MUST include: per-platform native binary, assembly JAR, SHA-256 checksums per artifact, SBOM (SPDX JSON), and a sources JAR |
| release pipeline | CI workflow | A GitHub Actions matrix workflow that builds `sbt probatioCli/nativeImage` per platform and publishes the release artifacts, reproducible given the same toolchain |
| native-image spike gate | pre-implementation gate | The V1 (GraalVM native-image builds probatio without hand-maintained reflection config) and V2 (gate latency budget) spikes that MUST be confirmed before any porting begins; V1 also gates scalameta (concept-scanner) specifically |

## ADDED Requirements

### Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget

The per-turn `gate` binary SHALL start and emit its hook payload within a
latency budget measured on linux-x86_64: warm start (steady-state repeated
invocation) at p50 below 150 ms, and cold start (first invocation after boot
on a typical development machine) at p50 below 1 s. The measurement SHALL be
performed with `hyperfine` on the `gate --event prompt-submit` happy path
(no active changes, empty ledger). If the budget is unmet at `-O1`
native-image optimization, the change SHALL NOT ship — the measurement is a
Ring 5 gate, not a post-release discovery.

> **CONCURRENCY RULE distinction**: The CONCURRENCY RULE requires that
> wall-clock expectations state a deterministic observable tested without
> wall-clock dependence. Latency measurement is explicitly NOT a Ring 3
> property and NOT a wall-clock CI test — it is a Ring 5 measurement gate
> performed with `hyperfine` on a calibrated development machine. No CI
> pipeline asserts a wall-clock threshold (CI environments have
> non-deterministic scheduling). The gate is a human-verified measurement
> recorded in the release checklist, with the `hyperfine` command and its
> output committed as evidence. The deterministic observable IS the
> committed `hyperfine` output file, not a runtime assertion.

**Given** a native-image `gate` binary built at `-O1` on linux-x86_64
**When** `hyperfine` measures `gate --event prompt-submit` over 100 warm
runs and 10 cold runs (first-invocation-after-boot)
**Then** the warm p50 is below 150 ms and the cold p50 is below 1 s, and the
`hyperfine` output is committed as release evidence

**Rationale**: The per-turn gate runs on every prompt-submit hook event. A
binary that adds perceptible latency to every turn would make the workflow
unusable in practice — this is the sole reason a JVM implementation was
previously excluded. Native packaging removes that argument only if the
budget is actually met; an unmet budget is a hard blocker, not a degradation
to document.

#### Scenario: warm-start latency meets budget

**Given** a `gate` binary on linux-x86_64, warmed by 10 prior invocations
**When** `hyperfine --runs 100 gate --event prompt-submit` is executed
**Then** the reported p50 is below 150 ms and the result is recorded in the
release evidence file

#### Scenario: cold-start latency meets budget

**Given** a `gate` binary on linux-x86_64, with no prior invocation since
boot
**When** the first invocation of `gate --event prompt-submit` is timed
**Then** the cold-start p50 over 10 boot-fresh invocations is below 1 s

#### Scenario: unmet budget blocks the release (adversarial)

**Given** a `gate` binary whose warm p50 measures 200 ms (above the 150 ms
budget)
**When** the release checklist is evaluated
**Then** the release is blocked — the change does not ship until the budget
is met, and the blocking is recorded as a failed Ring 5 gate, not a warning

### Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands

The `gate` subcommand SHALL be distributed as a GraalVM native-image binary;
it MUST NOT fall back to a JAR launcher on platforms where a native binary is
available. The once-per-ring subcommands (`spec-lint`, `chain-state`,
`ledger`, `checkpoint`, and others) MAY run from an assembly JAR where a
native binary is unavailable, and the resolution SHALL emit one distinct
warning line to stderr when the JAR fallback is active. No consumer machine
SHALL build a native image unless it explicitly opts in.

**Given** a consumer machine on a platform with a released native binary
**When** the `gate` subcommand is invoked
**Then** the native binary is executed directly, with no JVM startup and no
JAR fallback path taken

**Given** a consumer machine on a platform without a released native binary
(e.g. windows-x86_64)
**When** a once-per-ring subcommand (e.g. `spec-lint`) is invoked
**Then** the assembly JAR launcher is used and exactly one warning line is
emitted to stderr stating that native-image startup latency is active until
a native binary is available

**Rationale**: The gate is per-turn and latency-critical (R-N1); the
once-per-ring tools are invoked rarely and their JVM startup cost is
acceptable. Making native-image mandatory only for the gate keeps the
release matrix tractable while preserving the per-turn budget. Silent
fallback to JAR for the gate would reintroduce the latency the whole change
exists to remove.

#### Scenario: gate uses native binary, never JAR, on a supported platform

**Given** a linux-x86_64 machine with the native binary installed
**When** `gate --event prompt-submit` is invoked
**Then** the process is the native binary (no `java` process appears) and no
JAR fallback warning is emitted

#### Scenario: gate falls back to JAR only on a platform with no native binary

**Given** a windows-x86_64 machine (JAR-fallback-only platform per R-N3)
**When** `gate --event prompt-submit` is invoked
**Then** the assembly JAR launcher is used and a warning is emitted to stderr
— this is the documented exception, not a silent degradation

#### Scenario: once-per-ring tool on JAR fallback emits exactly one warning (adversarial)

**Given** a machine without a native binary for `spec-lint`
**When** `spec-lint` is invoked via the JAR launcher
**Then** exactly one warning line appears on stderr (not zero, not
duplicated), and the warning text names the subcommand and the fallback

#### Scenario: no consumer machine builds native-image by default (adversarial)

**Given** a consumer machine running `probatioInstall` without any opt-in
setting
**When** installation resolves the binary
**Then** a prebuilt binary is downloaded or a JAR fallback is used — no
native-image compilation is triggered on the consumer machine

### Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources

Every release SHALL include, at minimum: one native binary per committed
platform (linux-x86_64, macos-aarch64, macos-x86_64), one assembly JAR,
one SHA-256 checksum file per artifact, one SBOM in SPDX JSON format, and
one sources JAR. The plugin's installation task SHALL verify the SHA-256
checksum of the downloaded binary before executing it for the first time;
if the checksum does not match, installation SHALL fail and the binary
SHALL NOT be executed. Windows-x86_64 SHALL be a JAR-fallback-only
platform, documented as such — no native binary is produced for it.

**Given** a published release at a given version tag
**When** the release artifacts are enumerated
**Then** the set includes: `probatio-linux-x86_64` (binary),
`probatio-macos-aarch64` (binary), `probatio-macos-x86_64` (binary),
`probatio-assembly.jar`, a SHA-256 checksum file for each of those, a
`probatio-sbom.spdx.json` file, and a `probatio-sources.jar`

**Given** a consumer running the installation task for the first time
**When** the native binary is downloaded
**Then** the installation task computes the SHA-256 of the downloaded file,
compares it to the published checksum, and proceeds only if they match

**Rationale**: Checksum verification prevents a tampered or truncated
download from executing as the gate — the tool that decides whether a spec
may proceed. The SBOM makes the dependency surface auditable. The sources
JAR satisfies source-availability norms and enables downstream inspection.
Windows is JAR-only because the workflow's hook tiers are POSIX-shaped
today; a native Windows port is a separate change if a consumer needs it.

#### Scenario: all required artifacts are present in a release

**Given** a release at version tag `v14.0.0`
**When** the release asset list is checked
**Then** it contains the three platform binaries, the assembly JAR, a
checksum file per artifact, the SPDX JSON SBOM, and the sources JAR — none
is missing

#### Scenario: checksum mismatch blocks first execution (adversarial)

**Given** a downloaded binary whose SHA-256 does not match the published
checksum
**When** the installation task verifies the checksum
**Then** installation fails with a message naming the expected and actual
checksums, and the binary is not executed

#### Scenario: windows-x86_64 release has no native binary, documented (adversarial)

**Given** the release artifact set
**When** a windows-x86_64 native binary is searched for
**Then** it is absent, and the release notes document windows-x86_64 as
JAR-fallback-only — the absence is declared, not silent

#### Scenario: SBOM is present and parseable as SPDX JSON

**Given** the release artifact set
**When** the SBOM file is parsed as JSON
**Then** it parses as SPDX JSON with a non-empty package name, a version
matching the release tag, and a non-empty dependency list

### Requirement: The release pipeline SHALL be CI-reproducible

The release pipeline SHALL build native images via `sbt
probatioCli/nativeImage` in GitHub Actions, with a matrix on `os` covering
the three committed platforms. The same git tag, given the same toolchain
versions (GraalVM, sbt, Scala), SHALL produce byte-identical native binaries
and therefore byte-identical SHA-256 checksums. The pipeline SHALL NOT
produce release artifacts from a developer's local machine — all artifacts
come from CI.

**Given** two CI runs of the release pipeline at the same git tag with the
same toolchain versions
**When** the resulting native binaries are compared
**Then** their SHA-256 checksums are identical

**Given** a release pipeline run
**When** the workflow executes
**Then** each platform in the matrix builds its native image via `sbt
probatioCli/nativeImage` on a runner matching that platform's native
architecture (linux-x86_64 on a linux runner, macos-aarch64 on an
apple-silicon runner, macos-x86_64 on an intel macos runner)

**Rationale**: Reproducibility means a third party can verify that a released
binary was produced by CI from a known tag, not by an unknown local
environment. Non-reproducible binaries break the trust chain that checksum
verification establishes. Cross-compilation (e.g. building macos-aarch64 on
an intel runner via Rosetta) is explicitly rejected — the results are
unreproducible (risk R8).

#### Scenario: same tag produces identical checksums across CI runs

**Given** two release pipeline runs at tag `v14.0.0` with identical
toolchain versions
**When** the SHA-256 checksums of the linux-x86_64 binaries are compared
**Then** they are byte-identical

#### Scenario: each platform builds on its native runner

**Given** the release pipeline matrix
**When** the macos-aarch64 build job executes
**Then** it runs on an apple-silicon GitHub-hosted runner, not a
cross-compiled or Rosetta-translated build

#### Scenario: artifacts from a local machine are rejected (adversarial)

**Given** a developer who builds a native binary locally and attempts to
attach it to a release
**When** the release is audited
**Then** the artifact is not accepted — the release pipeline is the sole
source of release artifacts, and locally built binaries are not release
artifacts

#### Scenario: cross-compilation is not used (adversarial)

**Given** a release pipeline configuration that attempts to build
macos-aarch64 on an intel runner via Rosetta
**When** the configuration is reviewed
**Then** it is rejected — only native CI builds are accepted, because
Rosetta-translated native-image output is unreproducible

### Requirement: scalameta SHALL be spike-verified under native image before its port is scheduled

The scalameta dependency (used only by the concept-scanner port) SHALL be
spike-verified under GraalVM native image (the V1 spike) before the
concept-scanner port is scheduled. If the spike fails — scalameta cannot be
native-imaged without hand-maintained reflection configuration that is
deemed unmaintainable — the concept scanner SHALL stay on the JAR launcher
and the change SHALL ship without a native concept-scanner binary, documented
as a known non-natived tool. The concept scanner is a once-per-change Ring 0
tool, not a per-turn tool, so JAR fallback for it does not violate R-N1.

**Given** the V1 spike task to native-image a module using scalameta
**When** the spike is executed
**Then** the result is recorded as confirmed (native-image succeeds without
hand-maintained reflection config) or failed (requires unmaintainable
reflection config), and the concept-scanner port is scheduled only if
confirmed

**Given** a failed V1 scalameta spike
**When** the concept-scanner port is evaluated
**Then** the concept scanner stays on the JAR launcher, the release notes
document it as a non-natived tool, and no native concept-scanner binary is
produced — the fallback is declared, not silent

**Rationale**: scalameta is the known GraalVM risk in the dependency set
(risk R2). uPickle/ujson/os-lib/mainargs are believed GraalVM-safe; scalameta
is not. Spiking it before scheduling its port prevents discovering the
failure after the port is half-complete. Because the concept scanner runs
once per change (not per turn), JAR fallback for it alone does not violate
the gate latency budget.

#### Scenario: V1 scalameta spike succeeds

**Given** a native-image build of a module using scalameta for concept
extraction
**When** the build completes without hand-maintained reflection
configuration
**Then** the spike is recorded as confirmed and the concept-scanner port is
scheduled

#### Scenario: V1 scalameta spike fails — concept scanner stays on JAR

**Given** a native-image build of a scalameta module that fails or requires
unmaintainable reflection configuration
**When** the spike result is evaluated
**Then** the concept scanner is documented as JAR-only, no native
concept-scanner binary is produced, and the change ships with this
documented exception

#### Scenario: silent JAR fallback for concept scanner is forbidden (adversarial)

**Given** a failed scalameta spike where the concept scanner is moved to JAR
**When** the release is published
**Then** the release notes explicitly name the concept scanner as a
non-natived tool — the fallback MUST be declared, never silent

## Properties (Ring 3)

<!-- Properties use Hedgehog 0.13.1 (NOT ScalaCheck) per the detected stack in
     openspec/capability-profile.md. The requirements doc R-X3 names ScalaCheck;
     the design phase resolves this by using Hedgehog, matching the profile's
     "NOT ScalaCheck" consequence. All generators use explicit `Gen` with
     `Range`; no `Arbitrary`. -->

### Property: SHA-256 checksum round-trip

**Invariant**: For any artifact in a release, the SHA-256 computed by the
installation task over the downloaded bytes equals the SHA-256 published in
the release's checksum file, and a single-bit corruption of the downloaded
bytes produces a mismatch.

**Generator strategy**: Constructive over a set of artifact-like byte arrays
(small binaries, JAR-shaped zips, JSON SBOM text). For each, compute the
expected SHA-256, then generate a corrupted variant by flipping one bit at
a random offset. Edge cases: empty file, single-byte file, file exactly at
a block boundary.

```
forAll (bytes: Array[Byte], corrupt: Boolean) =>
  val expected = sha256(bytes)
  val actual = sha256(if corrupt then flipOneBit(bytes) else bytes)
  (actual == expected) == !corrupt
```

### Property: SBOM presence and parseability per release

**Invariant**: For any release version, the artifact set contains exactly one
SBOM file, and that file parses as valid SPDX JSON with a non-empty package
name and version matching the release tag.

**Generator strategy**: Enumerated over constructed release manifests, each
with a version string and a varying artifact set (some with SBOM, some
without, some with a malformed SBOM). Constructive — each manifest is built
with a known expected verdict.

```
forAll release in constructed_releases:
  hasExactlyOneSbom(release) && parsesAsSpdxJson(release.sbom)
    == release.wasWellFormed
```

### Property: Platform coverage is complete for committed platforms

**Invariant**: For any release, the artifact set contains a native binary for
each of the three committed platforms (linux-x86_64, macos-aarch64,
macos-x86_64) and does not contain a native binary for windows-x86_64.

**Generator strategy**: Enumerated over constructed release manifests with
varying platform coverage (all three, missing one, extra windows binary).
Constructive — each manifest's expected verdict is known from construction.

```
forAll release in constructed_releases:
  hasBinary(release, "linux-x86_64") &&
  hasBinary(release, "macos-aarch64") &&
  hasBinary(release, "macos-x86_64") &&
  !hasBinary(release, "windows-x86_64")
    == release.wasComplete
```

### Property: Checksum verification gates first execution

**Invariant**: For any downloaded binary, the installation task executes the
binary if and only if the computed SHA-256 matches the published checksum. A
mismatch always results in a failure with no execution.

**Generator strategy**: Constructive over (binary bytes, published checksum,
expected-match) triples. For each, generate a matching or mismatching
checksum. Edge cases: empty binary, checksum file with extra whitespace,
checksum file naming a different artifact.

```
forAll (bytes: Array[Byte], publishedChecksum: String) =>
  val matches = sha256(bytes) == publishedChecksum.trim
  if matches then installProceeds(bytes) else installFails(bytes) && !binaryExecuted(bytes)
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A wall-clock latency assertion in CI (e.g. `assert(timeout < 150.ms)`) | Latency is a Ring 5 measurement gate via `hyperfine`, not a CI wall-clock test; CI scheduling is non-deterministic | static: grep CI workflow and test sources for wall-clock threshold assertions against the gate binary; confirm latency evidence is a committed `hyperfine` output file, not a test |
| `gate` subcommand falling back to JAR on a platform with a native binary | R-N2 mandates native-image for gate; silent JAR fallback for gate reintroduces the latency the change exists to remove | scenario test: invoke `gate` on linux-x86_64 with native binary present; assert no `java` process and no JAR-fallback warning |
| Consumer machine triggering `native-image` compilation without opt-in | No consumer builds from source by default; native-image is dev/opt-in only | static: grep plugin sources for `nativeImage` task invocation; confirm it is behind an opt-in setting, not in the default install path |
| Release artifacts produced from a local machine | All release artifacts come from CI; local builds are not release artifacts | manual review (Ring 8) + CI workflow inspection: confirm the release workflow is the sole upload path |
| Cross-compilation or Rosetta-translated native-image builds | Rosetta output is unreproducible (risk R8); only native-architecture CI builds are accepted | static: inspect GitHub Actions matrix; confirm `runs-on` matches the target architecture per platform |
| ScalaCheck used in any packaging property | The detected stack is Hedgehog 0.13.1 (NOT ScalaCheck); `capability-profile.md` says "NOT ScalaCheck" and the profile wins | static: grep test sources for `ScalaCheck`/`org.scalacheck` absence |
| Hedgehog `Arbitrary` used in any packaging property | The spec mandates explicit `Gen` with `Range`; `Arbitrary` hides the generator strategy | static: grep test sources for `Arbitrary`/`arbitrary` absence |
| scalameta concept-scanner port scheduled before V1 spike confirmation | R-N5 requires the spike to be confirmed before the port is scheduled; scheduling without confirmation violates the gate | manual review (Ring 8) + tasks.md inspection: confirm the concept-scanner port task is gated on the V1 spike task |
| Silent JAR fallback for concept scanner when V1 spike fails | The fallback MUST be declared in release notes, not silent | manual review (Ring 8): confirm release notes name the concept scanner as non-natived when the spike fails |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Warm-start gate latency is below 150 ms p50 on linux-x86_64 | Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget + Scenario: warm-start latency meets budget | Ring 5 measurement gate (`hyperfine --runs 100`), evidence committed as release file | `hyperfine` output file in release evidence |
| Cold-start gate latency is below 1 s p50 on linux-x86_64 | Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget + Scenario: cold-start latency meets budget | Ring 5 measurement gate (first-invocation-after-boot timing), evidence committed | `hyperfine` output file in release evidence |
| Unmet latency budget blocks the release | Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget + Scenario: unmet budget blocks the release (adversarial) | manual review (Ring 8) — release checklist asserts the gate passed before publish | release checklist |
| Gate uses native binary, never JAR, on supported platforms | Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands + Scenario: gate uses native binary, never JAR, on a supported platform | scenario test: assert no `java` process and no JAR-fallback warning when native binary is present | `native-packaging.bats` / scenario test |
| Gate JAR fallback only on platforms with no native binary | Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands + Scenario: gate falls back to JAR only on a platform with no native binary | scenario test: windows-x86_64 invokes JAR launcher with warning | `native-packaging.bats` / scenario test |
| Once-per-ring tool JAR fallback emits exactly one warning | Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands + Scenario: once-per-ring tool on JAR fallback emits exactly one warning (adversarial) | scenario test: count warning lines on stderr == 1 | `native-packaging.bats` / scenario test |
| No consumer machine builds native-image by default | Requirement: Native-image SHALL be mandatory for gate and optional-with-warning for other subcommands + Scenario: no consumer machine builds native-image by default (adversarial) | static: grep plugin sources; confirm native-image is behind opt-in setting | adversarial review |
| All required artifacts present in a release | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Scenario: all required artifacts are present in a release | scenario test: enumerate release assets, assert all present | release artifact enumeration test |
| SHA-256 checksum mismatch blocks first execution | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Scenario: checksum mismatch blocks first execution (adversarial) + Property: Checksum verification gates first execution | Hedgehog property (checksum round-trip) + scenario test (mismatch blocks) | packaging property suite |
| Windows-x86_64 has no native binary, documented | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Scenario: windows-x86_64 release has no native binary, documented (adversarial) | scenario test: assert no windows binary + release notes mention JAR-fallback | release artifact enumeration test |
| SBOM is present and parseable as SPDX JSON | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Scenario: SBOM is present and parseable as SPDX JSON + Property: SBOM presence and parseability per release | Hedgehog property (SBOM presence) + scenario test (SPDX JSON parse) | packaging property suite |
| SHA-256 checksum round-trip holds over generated artifacts | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Property: SHA-256 checksum round-trip | Hedgehog property (checksum round-trip) | packaging property suite |
| Platform coverage is complete for committed platforms | Requirement: Every release SHALL include per-platform binary, assembly JAR, SHA-256 checksums, SBOM, and sources + Property: Platform coverage is complete for committed platforms | Hedgehog property (platform coverage) | packaging property suite |
| Same tag produces byte-identical checksums across CI runs | Requirement: The release pipeline SHALL be CI-reproducible + Scenario: same tag produces identical checksums across CI runs | CI reproducibility check: run pipeline twice at same tag, compare checksums | release pipeline workflow |
| Each platform builds on its native runner | Requirement: The release pipeline SHALL be CI-reproducible + Scenario: each platform builds on its native runner | static: inspect GitHub Actions matrix `runs-on` per platform | adversarial review |
| Artifacts from a local machine are rejected | Requirement: The release pipeline SHALL be CI-reproducible + Scenario: artifacts from a local machine are rejected (adversarial) | manual review (Ring 8) + CI workflow inspection | adversarial review |
| Cross-compilation is not used | Requirement: The release pipeline SHALL be CI-reproducible + Scenario: cross-compilation is not used (adversarial) | static: inspect matrix; confirm no Rosetta/cross-compile | adversarial review |
| V1 scalameta spike is confirmed before concept-scanner port is scheduled | Requirement: scalameta SHALL be spike-verified under native image before its port is scheduled + Scenario: V1 scalameta spike succeeds | MUST-CONFIRM task (Phase 0 spike); gate recorded in tasks.md before port task starts | `tasks.md` Phase 0 |
| V1 scalameta spike failure keeps concept scanner on JAR, documented | Requirement: scalameta SHALL be spike-verified under native image before its port is scheduled + Scenario: V1 scalameta spike fails — concept scanner stays on JAR + Scenario: silent JAR fallback for concept scanner is forbidden (adversarial) | MUST-CONFIRM task (Phase 0 spike) + manual review: release notes name concept scanner as non-natived | `tasks.md` Phase 0 + release notes |
| V2 gate latency spike is confirmed before porting begins | Requirement: The per-turn gate binary SHALL start and emit its payload within the latency budget | MUST-CONFIRM task (Phase 0 spike); V2 measures cold/warm latency before any porting | `tasks.md` Phase 0 |
| No wall-clock latency assertion in CI | Compile-Negative: wall-clock latency assertion in CI | static: grep CI workflow and test sources for wall-clock threshold assertions | adversarial review |
| No ScalaCheck in packaging properties | Compile-Negative: ScalaCheck used in any packaging property | static: grep test sources for `ScalaCheck`/`org.scalacheck` absence | adversarial review |
| No Hedgehog Arbitrary in packaging properties | Compile-Negative: Hedgehog Arbitrary used in any packaging property | static: grep test sources for `Arbitrary`/`arbitrary` absence | adversarial review |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `workflow/cli` sbt subproject `probatio-cli` | sbt module | `build.sbt` | `org.sinemenda.probatio:probatio-cli_3`; mainargs entrypoints; multicall binary `probatio` (alias `prob`); native-image target |
| `sbt probatioCli/nativeImage` | sbt task | `workflow/cli` build config | GraalVM native-image build; produces the multicall binary; `-O1` optimization level for the gate latency budget |
| GitHub Actions release workflow | CI workflow | `.github/workflows/release-probatio.yml` (NEW) | matrix on `os`: `ubuntu-latest` (linux-x86_64), `macos-14` (apple-silicon, macos-aarch64), `macos-13` (intel, macos-x86_64); runs `sbt probatioCli/nativeImage` per platform; uploads artifacts + checksums + SBOM + sources |
| `probatio-assembly.jar` | assembly artifact | `workflow/cli` assembly task | fat JAR for JAR-fallback resolution; published to Maven Central and attached to GitHub Release |
| `probatio-sources.jar` | sources artifact | `workflow/cli` sources task | sources JAR for source-availability; attached to release |
| `probatio-sbom.spdx.json` | SBOM artifact | release workflow | SPDX JSON SBOM generated from the dependency tree; attached to release |
| SHA-256 checksum files | checksum artifacts | release workflow | one `.sha256` file per artifact (binary, JAR, sources); generated in CI |
| `probatioInstall` checksum verification | plugin logic | `workflow/plugin` (`sbt-probatio`) | computes SHA-256 of downloaded binary, compares to published checksum, fails on mismatch before first execution; declared in `specs/sbt-plugin/spec.md` (R-S3) |
| `hyperfine` latency evidence | measurement file | release evidence directory | `hyperfine --runs 100 probatio gate --event prompt-submit` output committed as release evidence; warm p50 < 150 ms, cold p50 < 1 s |
| V1 spike (GraalVM native-image of uPickle + scalameta) | MUST-CONFIRM task | `tasks.md` Phase 0 | native-image a module using uPickle read/write of ledger ADTs + scalameta concept extraction; run against fixture corpus; record confirmed/failed |
| V2 spike (gate latency budget) | MUST-CONFIRM task | `tasks.md` Phase 0 | measure cold/warm latency of the `gate` binary on linux-x86_64 with `hyperfine`; confirm R-N1 budget before any porting begins |
| `NativePackagingSpec` | Hedgehog test | `workflow/cli/src/test/scala/org/sinemenda/probatio/packaging/NativePackagingSpec.scala` (NEW) | Ring 3 properties: SHA-256 round-trip, SBOM presence/parseability, platform coverage, checksum-gates-execution; uses explicit `Gen` with `Range`, NO `Arbitrary`, NO ScalaCheck |
| `native-packaging.bats` | bats test | `openspec/schemas/verified-scala3/tests/native-packaging.bats` (NEW) | scenario tests: gate-uses-native-binary, gate-JAR-fallback-on-windows, once-per-ring-one-warning, checksum-mismatch-blocks, all-artifacts-present, windows-no-binary-documented |
| `stryker4s.conf` `mutate` retarget | build config | `stryker4s.conf` | point `mutate` at `**/probatio/packaging/*.scala` for Ring 5 (packaging logic: checksum verification, artifact enumeration) |
| Compile | build step | `workflow/cli` | `sbt probatioCli/compile` |
| Native image build | build step | `workflow/cli` | `sbt probatioCli/nativeImage` (requires GraalVM; dev/opt-in on consumer machines) |
