# Spec: Feature Freeze Guard Integrity

The guard that proves the port added no check and altered no verdict reads its corpus from
a fixed location. Archiving the change that produced that corpus moved it, so the guard
now finds nothing and fails. It went red in the same commit that declared the port
complete, and nothing re-ran it.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The feature freeze is the protocol's constraint on what a swap may change. This spec repairs its enforcing mechanism; the constraint itself is unchanged. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `FeatureFreezeViolation` | enum (NewLintCheck, VerdictAlteration, NewWorkflowFeature) | `org.sinemenda.probatio.guard` |
| `FeatureFreezeVerdict` | enum (Accepted, Rejected) | `org.sinemenda.probatio.guard` |
| `KnownCheckId` | enum (closed set of check identifiers) | `org.sinemenda.probatio.guard` |
| `FixtureVerdict` | final case class | `org.sinemenda.probatio.guard` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `FixtureCorpus` | final case class, private constructor (specs, origin) | The set of specification fixtures the guard checks verdict stability against. Constructible only from a non-empty resolution, so an empty corpus cannot be silently accepted. |
| `CorpusResolution` | enum (`Resolved(corpus)`, `NotFound(searched)`) | The outcome of locating the corpus, carrying the locations searched when it is not found. |

### Type-Widening Impact

No public type is widened. Both new types are closed and every match over them is written
in this change.

## ADDED Requirements

### Requirement: The guard locates its corpus wherever the change resides

The guard SHALL locate its specification corpus whether the change is active or archived,
and it MUST NOT depend on a single fixed location.

**Given** a change carrying specification fixtures
**When** the guard resolves its corpus
**Then** the corpus is found whether the change is in the active area or the archived area

**Rationale**: archiving is a normal, expected step in the workflow's own lifecycle. A
guard that a normal lifecycle step breaks is a guard that will be broken most of the time.

#### Scenario: Happy path — an active change's corpus resolves

**Given** a change in the active area carrying specification fixtures
**When** the guard resolves its corpus
**Then** the corpus is found

#### Scenario: Happy path — an archived change's corpus resolves

**Given** the same change moved to the archived area
**When** the guard resolves its corpus
**Then** the corpus is found and contains the same fixtures

#### Scenario: Adversarial — a corpus present in neither location is not silently accepted

**Given** a change name that exists in neither the active nor the archived area
**When** the guard resolves its corpus
**Then** the resolution reports not-found naming every location searched

### Requirement: An empty corpus is could-not-determine, never a pass

When the guard's corpus cannot be resolved or resolves empty, the guard SHALL report
could-not-determine, and it MUST NOT report the freeze as upheld.

**Given** a guard run whose corpus is unresolvable or empty
**When** the guard reports
**Then** the outcome is could-not-determine naming the corpus, and the outcome is not
freeze-upheld

**Rationale**: this is the same boundary the correctness verdict enforces, applied to the
guard itself. A guard that passes when it has nothing to check is indistinguishable from
a guard that checked and found nothing wrong — and the second is a claim the first has not
earned.

#### Scenario: Adversarial — an unresolvable corpus does not report the freeze upheld

**Given** a guard run whose corpus resolves to nothing
**When** the guard reports
**Then** the outcome is could-not-determine, and not freeze-upheld

#### Scenario: Adversarial — a resolved but empty corpus does not report the freeze upheld

**Given** a guard run whose corpus resolves to a location containing no fixtures
**When** the guard reports
**Then** the outcome is could-not-determine naming the empty corpus

#### Scenario: Adversarial — an empty corpus cannot be constructed

**Given** a caller building a corpus from an empty fixture list
**When** the code is compiled
**Then** compilation fails: the corpus type has no constructor accepting an empty list

### Requirement: The check-identifier set stays closed across the port

The guard SHALL reject any check identifier outside the recorded closed set, and a new
identifier MUST NOT be accepted as a pass.

**Given** the recorded closed set of check identifiers
**When** the ported implementation's emitted identifiers are compared against it
**Then** an identifier outside the set is a freeze violation

#### Scenario: Happy path — the ported implementation emits only known identifiers

**Given** the ported implementation run over the corpus
**When** its emitted identifiers are collected
**Then** every identifier is in the recorded closed set

#### Scenario: Adversarial — an unknown identifier is a violation

**Given** an implementation emitting an identifier outside the recorded set
**When** the guard runs
**Then** the guard reports a freeze violation naming that identifier

### Requirement: Verdict stability is measured against the predecessor

For every fixture in the corpus, the ported implementation's verdict SHALL equal the
predecessor implementation's verdict for that fixture.

**Given** a fixture in the corpus
**When** both implementations produce a verdict for it
**Then** the two verdicts are equal

#### Scenario: Happy path — every fixture's verdict agrees

**Given** the full corpus
**When** both implementations run
**Then** every fixture's verdicts agree

#### Scenario: Adversarial — a differing verdict is reported with both values

**Given** a fixture on which the two implementations disagree
**When** the guard runs
**Then** the guard reports a violation naming the fixture, the predecessor's verdict and
the ported verdict

## Properties (Ring 3)

### Property: corpus-resolution-is-location-independent

**Invariant**: for every corpus and every location it is placed in — active or archived —
the resolved fixture set is the same.

**Generator strategy**: `genCorpus` — constructive over fixture lists of size 1–8, each
fixture drawn from a closed alphabet of specification shapes. Each generated corpus is
materialised into a temporary repository twice, once in each location. Edge cases: a
single fixture, the maximum size, fixtures with identical names in different
subdirectories.

```
property("corpus resolution is location independent") {
  for {
    corpus <- genCorpus.forAll
    active   = resolve(placeActive(corpus))
    archived = resolve(placeArchived(corpus))
  } yield Result.assert(active.fixtures == archived.fixtures)
}
```

### Property: empty-corpus-never-passes

**Invariant**: for every guard run whose corpus is unresolvable or empty, the outcome is
could-not-determine — never freeze-upheld and never a violation.

**Generator strategy**: `genEmptyCorpusCondition` — constructive over the closed set of
ways a corpus can be absent: change name not present, location present but empty, location
present but unreadable. No filtering.

```
property("an empty corpus never passes") {
  for {
    condition <- genEmptyCorpusCondition.forAll
    outcome    = runGuard(condition)
  } yield Result.assert(outcome.isUndetermined && !outcome.isUpheld)
}
```

### Property: verdict-stability-across-the-port

**Invariant**: for every fixture, the ported implementation's verdict equals the
predecessor's. The predecessor is executed as the model.

**Generator strategy**: `genFixture` — constructive over specification documents built
from a closed alphabet of clause shapes covering each check the closed identifier set
names, both satisfying and violating. Model-based: the predecessor runs as a subprocess.
Determinism: only verdicts are compared; no timing is observed.

```
property("verdict stability across the port") {
  for {
    fixture <- genFixture.forAll
    ported   = verdictPorted(fixture)
    model    = verdictPredecessor(fixture)
  } yield Result.assert(ported == model)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A fixture corpus built from an empty list | An empty corpus is how the guard came to pass vacuously | `assertDoesNotCompile("FixtureCorpus(Nil, origin)")` — the constructor is private; the factory returns a resolution |
| A not-found resolution without the locations searched | A not-found that does not say where it looked cannot be diagnosed | `assertDoesNotCompile("CorpusResolution.NotFound()")` — the variant requires the searched list |
| A freeze verdict constructed for an unresolved corpus | The verdict must be unreachable without a corpus to have produced it | `assertDoesNotCompile("FeatureFreezeVerdict.Accepted(CorpusResolution.NotFound(Nil))")` — the accepted variant takes a resolved corpus |

## Formal Contracts (Ring 6)

The guard's outcome classification is a three-way decision with the same shape as the
correctness verdict's. It is small and total; it is mirrored alongside the specification
lint kernel rather than in a kernel of its own.

### Contract: guardOutcome

```
def guardOutcome(resolution: CorpusResolution,
                 disagreements: List[FixtureVerdict]): GuardResult = {
  ...
} ensuring { result =>
  // an unresolved or empty corpus is always undetermined
  (resolution.isNotFound ==> result.isUndetermined) &&
  (result.isUndetermined ==> !result.isUpheld) &&
  // upheld iff resolved and no disagreement
  (result.isUpheld == (resolution.isResolved && disagreements.isEmpty)) &&
  // a violation always names at least one fixture
  (result.isViolation ==> result.namedFixtures.nonEmpty)
}
```

A bridge property binds the shipped guard to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| An active change's corpus resolves | Requirement: The guard locates its corpus wherever the change resides + Scenario: Happy path — an active change's corpus resolves | scenario test | `NonGoalsGuardSpec` |
| An archived change's corpus resolves | Requirement: The guard locates its corpus wherever the change resides + Scenario: Happy path — an archived change's corpus resolves | scenario test | `NonGoalsGuardSpec` |
| A corpus in neither location reports not-found with locations | Requirement: The guard locates its corpus wherever the change resides + Scenario: Adversarial — a corpus present in neither location is not silently accepted | scenario test | `NonGoalsGuardSpec` |
| Resolution is location independent | Property: corpus-resolution-is-location-independent | Hedgehog property | `NonGoalsGuardSpec` |
| An unresolvable corpus does not report the freeze upheld | Requirement: An empty corpus is could-not-determine, never a pass + Scenario: Adversarial — an unresolvable corpus does not report the freeze upheld | scenario test | `NonGoalsGuardSpec` |
| A resolved but empty corpus does not report the freeze upheld | Requirement: An empty corpus is could-not-determine, never a pass + Scenario: Adversarial — a resolved but empty corpus does not report the freeze upheld | scenario test | `NonGoalsGuardSpec` |
| An empty corpus never passes, for all absence conditions | Property: empty-corpus-never-passes | Hedgehog property | `NonGoalsGuardSpec` |
| An empty corpus is unconstructible | Compile-Negative: A fixture corpus built from an empty list | compile-negative test | `NonGoalsGuardSpec` |
| A not-found without searched locations is unconstructible | Compile-Negative: A not-found resolution without the locations searched | compile-negative test | `NonGoalsGuardSpec` |
| A verdict for an unresolved corpus is unconstructible | Compile-Negative: A freeze verdict constructed for an unresolved corpus | compile-negative test | `NonGoalsGuardSpec` |
| Only known check identifiers are emitted | Requirement: The check-identifier set stays closed across the port + Scenario: Happy path — the ported implementation emits only known identifiers | scenario test | `NonGoalsGuardSpec` |
| An unknown identifier is a violation | Requirement: The check-identifier set stays closed across the port + Scenario: Adversarial — an unknown identifier is a violation | scenario test | `NonGoalsGuardSpec` |
| Every fixture's verdict agrees with the predecessor | Requirement: Verdict stability is measured against the predecessor + Property: verdict-stability-across-the-port | Hedgehog model-based property (predecessor run as a subprocess) | `NonGoalsGuardSpec` |
| A differing verdict is reported with both values | Requirement: Verdict stability is measured against the predecessor + Scenario: Adversarial — a differing verdict is reported with both values | scenario test | `NonGoalsGuardSpec` |
| The guard's outcome classification is verified | Invariant: an unresolved corpus is never upheld | Stainless verification + bridge property test | `SpecLintKernel` (extended) + `SpecLintBridgeSpec` |
| The guard suite is green | Criterion: this spec's exit criterion | direct suite run, recorded in the evidence ledger | `NonGoalsGuardSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `NonGoalsGuardSpec` | munit suite | `workflow/core/src/test/scala/org/sinemenda/probatio/guard/NonGoalsGuardSpec.scala` | The only suite in its package. Line 350 hardcodes the active-area path for a change that is now archived; line 273 is the failure that results. |
| `FixtureCorpus`, `CorpusResolution` | new types | `.../guard/` | New |
| `KnownCheckId` | enum | `.../guard/` | Reused as the closed identifier set |
| `FeatureFreezeVerdict`, `FeatureFreezeViolation`, `FixtureVerdict` | enums / case class | `.../guard/` | Reused; the accepted variant narrows to take a resolved corpus |
| `spec-lint.sh.predecessor.bak` | predecessor implementation | `openspec/schemas/verified-scala3/scanner/` | The verdict model |
| `SpecLintKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/SpecLintKernel.scala` | Extended with the outcome contract |
| Ring 5 note | — | `stryker4s.conf` | This spec's implementation lives in **test** sources; the move-to-main-and-back procedure applies |
