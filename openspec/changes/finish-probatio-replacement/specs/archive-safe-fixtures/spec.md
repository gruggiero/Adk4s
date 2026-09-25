# Spec: Archive-Safe Fixtures

Archiving a change is a routine step in the workflow's lifecycle, and it moves the change's
directory. A test that names the change's directory by its active-area path breaks the
moment the change is archived. It has happened twice: the feature-freeze guard in one
change, the predecessor-control fixture in the next. The first fix repaired one test and
left the pattern in place.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The protocol's **Gate** action depends on recorded fixtures — the predecessor control, the guard's corpus — that live in a change's directory. This spec makes those fixtures reachable after archiving. The protocol's actions and state are unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `FixtureCorpus` | final case class, private constructor | `org.sinemenda.probatio.guard` |
| `CorpusResolution` | enum (`Resolved`, `NotFound(searched)`) | `org.sinemenda.probatio.guard` |
| `DifferentialHarness` | object | `org.sinemenda.probatio.migration` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ChangeLocation` | enum (`Active(dir)`, `Archived(dir, date)`, `Absent(searched)`) | Where a named change's directory is. The only way test code locates a change's artifacts. `Absent` carries every location searched. |

`CorpusResolution` is **generalised**: the feature-freeze guard's resolver becomes the
shared one, returning `ChangeLocation`. The guard keeps its behaviour, and every other
fixture reader gains it.

### Type-Widening Impact

No public type gains a variant. `ChangeLocation` is new and closed. The guard's existing
matches over `CorpusResolution` move to `ChangeLocation`, whose `Absent` variant carries
the searched list that `NotFound` already carries. The mapping is one-to-one.

## ADDED Requirements

### Requirement: Test code locates a change through the archive-aware resolver

Test code SHALL locate a change's directory only through the shared resolver, which finds
it in the active area or the archive, and it MUST NOT depend on a single fixed location.

**Given** a change carrying fixtures
**When** test code resolves the change by name
**Then** the change is found whether it is active or archived

#### Scenario: Happy path — an active change resolves

**Given** a change in the active area
**When** it is resolved by name
**Then** the result is the active location

#### Scenario: Happy path — an archived change resolves to the same fixtures

**Given** the same change after archiving
**When** it is resolved by name
**Then** the result is the archived location, and it holds the same fixtures

#### Scenario: Adversarial — a change in neither place is not silently accepted

**Given** a change name present in neither the active area nor the archive
**When** it is resolved
**Then** the result is absent, naming every location searched

#### Scenario: Edge case — a change archived more than once resolves to the latest

**Given** a change name that appears under two archive dates
**When** it is resolved
**Then** the result is the most recent archived location, and the resolution names both

### Requirement: The predecessor-control fixture is read after archiving

The differential comparison's recorded predecessor control SHALL be readable after the
change that recorded it is archived.

**Given** the recorded predecessor control of an archived change
**When** the control's well-formedness check runs
**Then** it reads the control from the archive and passes

**Rationale**: on 2026-09-25 this check failed with "control fixture missing" because it
named the change's active-area path; the fixture was present under the archive.

#### Scenario: Happy path — the archived control is well-formed

**Given** the archived predecessor control
**When** its check runs
**Then** it passes

#### Scenario: Adversarial — a missing control is a failure, not a pass

**Given** a change with no recorded control in either place
**When** the check runs
**Then** it fails naming every location searched

### Requirement: A literal active-change path in test code is rejected

The lint SHALL reject any literal path into a named change's active-area directory in test
code, and such a path MUST NOT pass review.

**Given** the test sources of the workflow modules and the acceptance suites
**When** the lint runs
**Then** no literal path into a named change's active-area directory remains

#### Scenario: Happy path — resolver-based test code is clean

**Given** test code that locates changes only through the resolver
**When** the lint runs
**Then** it reports nothing

#### Scenario: Adversarial — a literal active-change path is rejected

**Given** a test naming a change's fixture by its active-area path
**When** the lint runs
**Then** it reports that path with its file and line

#### Scenario: Edge case — a generic fixture path for a synthetic change is allowed

**Given** a test that builds a synthetic change inside a temporary directory
**When** the lint runs
**Then** the synthetic path is not reported — the rule targets the repository's own
change directories, not paths a test builds for itself

## Properties (Ring 3)

### Property: resolution-is-location-independent

**Invariant**: for every change and every place it is put — active, or archived under any
date — the resolved fixture set is the same.

**Generator strategy**: `genChangePlacement` — constructive over a fixture list of size 1–6
and a placement drawn from {active, archived once, archived twice under different dates}.
Each placement is materialised in a temporary repository. Edge cases: a single fixture, the
double-archive case, fixtures with the same name in different subdirectories.

```
property("resolution is location independent") {
  for {
    placement <- genChangePlacement.forAll
    resolved   = ChangeLocation.resolve(placement.repo, placement.name)
  } yield Result.assert(fixturesAt(resolved) == placement.fixtures)
}
```

### Property: absent-names-every-searched-location

**Invariant**: for every change name absent from both areas, resolution is `Absent` and its
searched list includes both the active area and the archive.

**Generator strategy**: `genAbsentName` — constructive over names built from a small
alphabet, placed in repositories that hold other changes but not this one. No filtering.

```
property("absent names every searched location") {
  for {
    (repo, name) <- genAbsentName.forAll
  } yield Result.assert(ChangeLocation.resolve(repo, name) match {
    case Absent(searched) => searched.contains(activeArea) && searched.contains(archiveArea)
    case _                => false
  })
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| An absent location that names no searched place | An absence that does not say where it looked cannot be diagnosed | `assertDoesNotCompile("ChangeLocation.Absent()")` — the variant requires the searched list |

## Formal Contracts (Ring 6)

No formal contracts — stated skip: a lookup over at most two directory areas, with no fold
or law at its centre; pinned by the two properties above.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| An active change resolves | Requirement: Test code locates a change through the archive-aware resolver + Scenario: Happy path — an active change resolves | scenario test | `NonGoalsGuardSpec` |
| An archived change resolves to the same fixtures | Requirement: Test code locates a change through the archive-aware resolver + Scenario: Happy path — an archived change resolves to the same fixtures + Property: resolution-is-location-independent | Hedgehog property | `NonGoalsGuardSpec` |
| A change in neither place is reported absent with locations | Requirement: Test code locates a change through the archive-aware resolver + Scenario: Adversarial — a change in neither place is not silently accepted + Property: absent-names-every-searched-location | Hedgehog property | `NonGoalsGuardSpec` |
| A double-archived change resolves to the latest | Requirement: Test code locates a change through the archive-aware resolver + Scenario: Edge case — a change archived more than once resolves to the latest | scenario test | `NonGoalsGuardSpec` |
| An absent location cannot omit its search list | Compile-Negative: An absent location that names no searched place | compile-negative test | `FeatureFreezeGuardIntegrityTypeContract` |
| The archived control is well-formed | Requirement: The predecessor-control fixture is read after archiving + Scenario: Happy path — the archived control is well-formed | scenario test | `DifferentialHarnessSpec` |
| A missing control fails naming locations | Requirement: The predecessor-control fixture is read after archiving + Scenario: Adversarial — a missing control is a failure, not a pass | scenario test | `DifferentialHarnessSpec` |
| Resolver-based code is lint-clean | Requirement: A literal active-change path in test code is rejected + Scenario: Happy path — resolver-based test code is clean | static rule (scalafix), plus a bats-side check for the suites | `.scalafix.conf` |
| A literal active-change path is rejected | Requirement: A literal active-change path in test code is rejected + Scenario: Adversarial — a literal active-change path is rejected | static rule with a negative fixture | `.scalafix.conf` |
| A synthetic temporary path is allowed | Requirement: A literal active-change path in test code is rejected + Scenario: Edge case — a generic fixture path for a synthetic change is allowed | static rule with a positive fixture | `.scalafix.conf` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The defect site | munit suite | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/DifferentialHarnessSpec.scala:458–459` | Names `openspec/changes/repair-probatio-cutover/fixtures/predecessor-control.json`; the fixture is under `openspec/changes/archive/2026-09-25-repair-probatio-cutover/fixtures/` |
| The first fix, to generalise | test source | `workflow/core/src/test/scala/org/sinemenda/probatio/guard/GuardCorpus.scala` | The feature-freeze guard's resolver; becomes the shared one |
| The lint | scalafix `DisableSyntax` block | `.scalafix.conf` | Pattern over `openspec/changes/<name>` literals that are not under `archive/`, scoped to `workflow/*/src/test/scala/**` |
| `ChangeLocation` | new type | shared test-support source | New |
| Ring 5 note | — | `stryker4s.conf` | Test infrastructure; the move-to-main-and-back procedure applies |
