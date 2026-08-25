# Spec: Non-Goals Guard

<!-- Delta spec for the `non-goals-guard` capability of the
     `port-scanner-to-probatio` change. This is the FEATURE-FREEZE CONTRACT:
     it defines what this change refuses, keeping the migration interpretable
     (a behavior delta breaks the oracle in a way indistinguishable from a
     port bug). Covers R-X1…R-X3 and the cross-cutting R-ARCH1 boundary
     enforcement. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| (none) | This spec is a guard contract, not a behavioral concept alteration; no concept's actions, state, or synchronizations are changed | — |

This spec does not alter any concept's actions, state, or synchronizations.
No concept file updates are required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

R-ARCH1 isolates probatio from adk4s: the tooling subprojects depend on
NOTHING adk4s-side. The "Concepts Used (from inventory)" table is
intentionally empty — this spec guards that boundary rather than reusing
adk4s types.

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| Feature-freeze contract | behavioral concept (guard) | The contract that the port does not extend checks, alter verdicts, or add workflow features; keeps the oracle interpretable as a port-acceptance suite rather than a behavior-delta detector |
| Allowed-dependency set | build-level check | The closed set of dependencies `probatio-core`/`probatio-cli` may declare: scalafmt (build-only), munit + Hedgehog (test), os-lib, uPickle/ujson, mainargs, scalameta (concept-scanner only); enforced by the dependency-lint rule |
| `dependency-lint rule` (R-ARCH1) | build-level check | Fails if any `workflow/*` project's classpath reaches an adk4s module OR a forbidden dependency (cats, cats-effect, fs2, llm4s, workflows4s); enforces the extraction-ready boundary |

## ADDED Requirements

### Requirement: The port does not extend checks, alter verdicts, or add workflow features

The port SHALL NOT extend the F1–F10 lint checks, alter any verdict a
predecessor tool emits on any fixture, or add new workflow features. Any
such change SHALL be filed as a separate change. This requirement is what
keeps the migration acceptance suite interpretable: a behavior delta breaks
the oracle in a way indistinguishable from a port bug.

**Given** the ported tooling running against the existing fixture corpus
**When** a contributor adds a new lint check (e.g. an F11), alters a verdict
on an existing fixture, or introduces a new workflow feature
**Then** the port rejects the change — it is out of scope and must be filed
as its own change

**Rationale**: The whole migration is a port, not a redesign (§4 of the
requirements doc). Mixing a behavior change with a port makes the oracle
uninterpretable: a red oracle could mean either a port bug or an intentional
behavior change, and there is no way to tell which. R-X1 keeps these two
concerns separated by forcing behavior changes into their own changes.

#### Scenario: New lint check rejected as out of scope

**Given** a contributor adds an F11 check to the ported spec-lint
**When** the change is reviewed
**Then** the review rejects it — F11 is a workflow feature, not a port, and
must be filed as a separate change

#### Scenario: Verdict alteration on a fixture rejected

**Given** a contributor modifies the ported chain-state so that a fixture
which previously produced a "bound" verdict now produces "discharged"
**When** the bats oracle runs against the ported tool
**Then** the oracle fails on that fixture — the verdict changed, which is a
behavior delta, not a port bug; the change is rejected until the verdict is
restored or the behavior change is filed separately

#### Scenario: New workflow feature rejected

**Given** a contributor adds a new gate tier (e.g. a post-completion
blocking tier) to the ported gate
**When** the change is reviewed
**Then** the review rejects it — a new gate tier is a workflow feature,
not a port, and must be filed as a separate change

### Requirement: The port does not change the schema for copy-based consumers beyond the rename

The port SHALL NOT change the schema for projects consuming
verified-scala3 via copy, beyond the rename in R-V2. Consumers who do not
use sbt are unaffected except that their hook installation now installs
`probatio` binaries instead of scripts; the hook payload their harness
sees is unchanged.

**Given** a consumer project that copied the verified-scala3 schema and
does not use sbt
**When** the consumer upgrades to the renamed schema (v14)
**Then** the only change the consumer observes is the rename
(verified-scala3 → probatio) and the binary installation replacing the
script installation; the hook payload their harness receives is
byte-stable

**Rationale**: Copy-based consumers (who do not use sbt) depend on the
hook payload contract, not the implementation language. R-X2 ensures the
port does not break them by changing the payload shape under cover of the
rename.

#### Scenario: Copy-based consumer payload unchanged after rename

**Given** a consumer project that copied the schema and runs the gate hook
**When** the consumer upgrades to v14 (probatio rename) and reinstalls hooks
**Then** the hook payload the consumer's harness receives is byte-stable
relative to v13 — the `hookSpecificOutput.additionalContext` shape and
decision/payload JSON are unchanged

#### Scenario: Schema template or ring definition change rejected

**Given** a contributor modifies the schema's spec templates or ring
definitions as part of the port
**When** the change is reviewed
**Then** the review rejects it — schema templates and ring definitions are
workflow semantics, which do not change in a port; the change is out of scope

#### Scenario: sbt 1.x to 2.x build migration rejected

**Given** a contributor migrates this repo's build from sbt 1.x to 2.x as
part of the port
**When** the change is reviewed
**Then** the review rejects it — the sbt 1.x to 2.x migration is an
independent change; the plugin must work on sbt 1.x because this repo is
sbt 1.x

### Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect

The allowed dependencies of `probatio-core` and `probatio-cli` SHALL be
the closed set: scalafmt (build-only), munit + Hedgehog (test), os-lib,
uPickle/ujson, mainargs, and scalameta (concept-scanner only).
`cats`, `cats-effect`, `fs2`, `llm4s`, `workflows4s`, and all `org.adk4s`
modules SHALL NOT appear on the classpath of any `workflow/*` subproject.
The AGENTS.md functional-programming mandate applies to the product code
under validation, not to the validating tool — this is a recorded
one-line deviation cited explicitly here.

**Given** the `probatio-core` and `probatio-cli` subprojects' build
definitions
**When** a contributor adds a dependency
**Then** the dependency-lint rule accepts only dependencies in the closed
allowed set and rejects any dependency on cats, cats-effect, fs2, llm4s,
workflows4s, or any org.adk4s module

**Rationale**: The workflow's shipped artifacts stay dependency-minimal.
R-X3 records the deviation from the repo's global FP rule so the openspec
proposal can cite it explicitly. The AGENTS.md mandate (Cats for data
structures, Cats Effect for effects) applies to adk4s product code under
validation; probatio is the validating tool, not the product.

**NOTE on ScalaCheck vs Hedgehog**: The source requirements doc (R-X3)
names "ScalaCheck" as an allowed dependency. The detected capability
profile (`openspec/capability-profile.md`) establishes that the project's
property framework is Hedgehog 0.13.1, NOT ScalaCheck. This spec corrects
R-X3 to use Hedgehog, per the profile's "profile wins" rule. Adding
ScalaCheck would contradict the detected stack and introduce a dependency
not present elsewhere in the build.

#### Scenario: Allowed dependency accepted

**Given** the `probatio-core` build definition declares `os-lib` and
`uPickle` as dependencies
**When** the dependency-lint rule runs
**Then** the rule accepts the dependencies — they are in the closed allowed
set

#### Scenario: cats dependency rejected (adversarial)

**Given** a contributor adds `libraryDependencies += "org.typelevel" %% "cats-core" % "2.x"` to the `probatio-core` build definition
**When** the dependency-lint rule runs
**Then** the rule fails the build — cats is explicitly excluded by R-X3;
the error message names the forbidden dependency and cites R-X3

#### Scenario: cats-effect dependency rejected (adversarial)

**Given** a contributor adds `libraryDependencies += "org.typelevel" %% "cats-effect" % "3.x"` to the `probatio-cli` build definition
**When** the dependency-lint rule runs
**Then** the rule fails the build — cats-effect is explicitly excluded by
R-X3; the error message names the forbidden dependency

#### Scenario: adk4s module dependency rejected (adversarial)

**Given** a contributor adds `libraryDependencies += "org.adk4s" %% "adk4s-core" % "0.1.0-SNAPSHOT"` to any `workflow/*` subproject
**When** the dependency-lint rule runs
**Then** the rule fails the build — any org.adk4s module on a workflow/*
classpath violates R-ARCH1; the error message names the forbidden module
and the violating subproject

#### Scenario: ScalaCheck dependency rejected (adversarial)

**Given** a contributor adds `libraryDependencies += "org.scalacheck" %% "scalacheck" % "1.x"` to the `probatio-core` test dependencies
**When** the dependency-lint rule runs
**Then** the rule fails the build — ScalaCheck is not in the allowed set;
the detected property framework is Hedgehog (per capability-profile.md),
and adding ScalaCheck would contradict the detected stack

#### Scenario: fs2 dependency rejected (adversarial)

**Given** a contributor adds `libraryDependencies += "co.fs2" %% "fs2-core" % "3.x"` to the `probatio-cli` build definition
**When** the dependency-lint rule runs
**Then** the rule fails the build — fs2 is excluded; probatio-cli uses
blocking I/O (mainargs + os-lib), not streaming

## Properties (Ring 3)

### Property: F1–F10 verdict stability across the port

**Invariant**: For every fixture in the existing corpus, the ported
spec-lint produces the same verdict and warning set as the predecessor
spec-lint. No fixture's verdict changes between the bash implementation
and the Scala implementation.

**Generator strategy**: constructive over the fixture corpus
(`tests/fixtures/` enumerated) — no random generation; each fixture is a
known input with a known expected verdict. Edge cases: fixtures that
trigger each of F1–F10, fixtures that trigger the numbered conditional
checks (3, 6, 17 ALTITUDE, 18 CONCURRENCY), fixtures with APPLIES and
N/A verdicts.

```
property("F1–F10 verdict stability across the port") {
  for fixture <- Gen.from(allFixtures) do
    val expected = bashSpecLint(fixture)
    val actual   = probatioSpecLint(fixture)
    actual.verdict ==== expected.verdict
    actual.warnings.toSet ==== expected.warnings.toSet
}
```

### Property: dependency boundary is closed

**Invariant**: The classpath of every `workflow/*` subproject contains
only dependencies in the allowed set. No forbidden dependency (cats,
cats-effect, fs2, llm4s, workflows4s, any org.adk4s module) appears on
any workflow/* classpath.

**Generator strategy**: constructive over the workflow/* subprojects
(enumerated: probatio-core, probatio-cli, sbt-probatio) × the forbidden
dependency set (enumerated: cats-core, cats-effect, cats-effect-std,
fs2-core, fs2-io, llm4s-core, workflows4s-core, adk4s-core,
adk4s-orchestration, structured-llm, etc.) — no random generation; each
(subproject, forbidden-dep) pair is a known adversarial input.

```
property("dependency boundary is closed") {
  for subproject <- Gen.from(allWorkflowSubprojects) do
    for forbidden <- Gen.from(forbiddenDependencies) do
      !classpath(subproject).contains(forbidden)
}
```

### Property: oracle immutability at every migration step

**Invariant**: The bats oracle (17 files under `tests/*.bats`) is not
modified at any commit on main during the migration. The oracle is the
acceptance suite, not a moving target.

**Generator strategy**: constructive over the migration commits
(enumerated from git history of the migration branch) — no random
generation; each commit is a known checkpoint. Edge cases: the first
porting commit, the last shim-swap commit, intermediate commits where
subcommands are ported one at a time.

```
property("oracle immutability at every migration step") {
  for commit <- Gen.from(migrationCommitsOnMain) do
    val oracleAtCommit = git.show(commit, "tests/*.bats")
    val oracleAtStart  = git.show(migrationStart, "tests/*.bats")
    oracleAtCommit ==== oracleAtStart
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `import cats.*` or `import cats.effect.*` in probatio-core/cli source | cats and cats-effect are explicitly excluded by R-X3; the validating tool stays dependency-minimal | source lint (grep for `import cats` in `workflow/{core,cli}/src`) + dependency-lint rule |
| `import fs2.*` in probatio-core/cli source | fs2 is excluded; probatio-cli uses blocking I/O, not streaming | source lint (grep for `import fs2` in `workflow/{core,cli}/src`) + dependency-lint rule |
| `import org.adk4s.*` in any workflow/* source | R-ARCH1: tooling subprojects reference zero adk4s code | source lint (grep for `import org.adk4s` in `workflow/*/src`) + dependency-lint rule |
| `libraryDependencies += "org.typelevel" %% "cats-core"` in workflow/* build def | cats is explicitly excluded by R-X3 | dependency-lint rule — fails if cats-core appears on any workflow/* classpath |
| `libraryDependencies += "org.typelevel" %% "cats-effect"` in workflow/* build def | cats-effect is explicitly excluded by R-X3 | dependency-lint rule — fails if cats-effect appears on any workflow/* classpath |
| `libraryDependencies += "org.scalacheck" %% "scalacheck"` in workflow/* build def | ScalaCheck is not the detected property framework (Hedgehog is); adding it contradicts the capability profile | dependency-lint rule — fails if scalacheck appears on any workflow/* classpath |
| A new F11 check in the ported spec-lint | R-X1: the port does not extend F1–F10 | scenario test (F11 check rejected as out of scope) + review gate |
| A verdict change on any existing fixture | R-X1: the port does not alter verdicts | property test (F1–F10 verdict stability) — oracle fails if a verdict changes |
| A new gate tier in the ported gate | R-X1: the port does not add workflow features | scenario test (new tier rejected as out of scope) + review gate |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Port does not extend F1–F10 | Requirement: The port does not extend checks, alter verdicts, or add workflow features | scenario test (F11 rejected) + review gate | NonGoalsGuardSpec |
| Port does not alter verdicts | Requirement: The port does not extend checks, alter verdicts, or add workflow features | property test (F1–F10 verdict stability) + oracle (bats fails on verdict change) | NonGoalsGuardSpec, bats oracle |
| Port does not add workflow features | Requirement: The port does not extend checks, alter verdicts, or add workflow features | scenario test (new gate tier rejected) + review gate | NonGoalsGuardSpec |
| Verdict alteration rejected on fixture (adversarial) | Requirement: The port does not extend checks, alter verdicts, or add workflow features | scenario test (oracle fails on verdict change) | NonGoalsGuardSpec |
| New workflow feature rejected (adversarial) | Requirement: The port does not extend checks, alter verdicts, or add workflow features | scenario test (new tier rejected) | NonGoalsGuardSpec |
| Copy-based consumer payload unchanged | Requirement: The port does not change the schema for copy-based consumers beyond the rename | scenario test (payload byte-stable across rename) + Ring 4 wire compatibility | NonGoalsGuardSpec |
| Schema template change rejected (adversarial) | Requirement: The port does not change the schema for copy-based consumers beyond the rename | scenario test (template change rejected) + review gate | NonGoalsGuardSpec |
| sbt 1.x to 2.x migration rejected (adversarial) | Requirement: The port does not change the schema for copy-based consumers beyond the rename | scenario test (build migration rejected) + review gate | NonGoalsGuardSpec |
| Allowed dependency set is closed | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule + property test (dependency boundary is closed) | build.sbt, dependency-lint task, NonGoalsGuardSpec |
| cats dependency rejected (adversarial) | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule — fails if cats appears on workflow/* classpath | build.sbt, dependency-lint task |
| cats-effect dependency rejected (adversarial) | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule — fails if cats-effect appears | build.sbt, dependency-lint task |
| adk4s module dependency rejected (adversarial) | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule (R-ARCH1) — fails if any org.adk4s module appears | build.sbt, dependency-lint task |
| ScalaCheck dependency rejected (adversarial) | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule — fails if scalacheck appears (Hedgehog is the detected framework) | build.sbt, dependency-lint task |
| fs2 dependency rejected (adversarial) | Requirement: The allowed dependencies of probatio-core and probatio-cli are a closed set excluding cats and cats-effect | dependency-lint rule — fails if fs2 appears | build.sbt, dependency-lint task |
| Oracle immutability at every migration step | Property: oracle immutability at every migration step | property test (oracle immutability) — git history check | NonGoalsGuardSpec |
| F1–F10 verdict stability | Property: F1–F10 verdict stability across the port | property test (verdict stability) + bats oracle | NonGoalsGuardSpec, bats oracle |
| Dependency boundary closed | Property: dependency boundary is closed | property test (dependency boundary) + dependency-lint rule | NonGoalsGuardSpec, build.sbt |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `dependency-lint rule` | build-level check | `build.sbt` (or `project/` plugin) | Fails if any `workflow/*` project's classpath reaches an adk4s module OR a forbidden dependency (cats, cats-effect, fs2, llm4s, workflows4s, scalacheck); enforced as a CI step |
| Allowed-dependency set | closed set | `build.sbt` workflow/* build defs | scalafmt (build-only), munit + Hedgehog (test), os-lib, uPickle/ujson, mainargs, scalameta (concept-scanner only) |
| `NonGoalsGuardSpec` | test suite (Hedgehog) | `workflow/core/src/test/scala/...` | Runs F1–F10 verdict stability, dependency boundary, oracle immutability properties + all scenario tests |
| F1–F10 checks | lint checks | `openspec/schemas/verified-scala3/scanner/spec-lint.sh` (predecessor) → `probatio-core` (ported) | The 10 mechanical lint checks whose verdicts must not change; cross-referenced to `specs/probatio-core/spec.md` R-C5 |
| bats oracle (17 files) | test suite | `openspec/schemas/verified-scala3/tests/*.bats` | The acceptance suite that must pass unmodified at every migration step; cross-referenced to `specs/migration-protocol/spec.md` R-M1 |
| `tests/fixtures/` | fixture corpus | `openspec/schemas/verified-scala3/tests/fixtures/` | The known inputs with known expected verdicts used by the F1–F10 verdict stability property |
| `hedgehog-munit` | test dependency | `workflow/{core,cli}` build defs | `qa.hedgehog %% hedgehog-munit % 0.13.1 % Test` — Hedgehog 0.13.1 (NOT ScalaCheck, per `capability-profile.md`) |
| Review gate | manual review | PR review process | The human gate that rejects out-of-scope changes (F11, verdict alterations, new workflow features, schema template changes, sbt 2.x migration); mandatory per R-X1/R-X2 |
