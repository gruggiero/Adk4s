# Spec: Oracle Independence

The acceptance oracle is worth something only because it was written from the specs, not
from the implementation. The migration protocol protects that by requiring the oracle to
pass unmodified, and a guard checks it. The guard is red: the oracle was edited in five of
the last change's eleven specs, and the red was accepted as pre-existing. Most of those
edits were not careless. Some tests assert over a tool's *source text*; when a tool becomes
a forwarding script those tests cannot pass, so they were rewritten to follow the new
implementation. Every future swap will force the same. The oracle is being co-evolved with
the code it is meant to judge.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | **Modified.** The protocol's **Gate** action currently requires the oracle to pass *unmodified*. This spec gives that rule a checkable meaning: the oracle holds only behavioural tests, and every modification since the recorded baseline cites the requirement that made it necessary. The concept file's Gate action and State sections are updated as part of this spec. | `openspec/concepts/strangler-migration-protocol.md` |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `OracleImmutabilityResult` | enum (`Immutable(commit)`, `Modified(commit, file)`) | `org.sinemenda.probatio.guard` |
| `FeatureFreezeViolation` | enum | `org.sinemenda.probatio.guard` |
| `FeatureFreezeVerdict` | enum | `org.sinemenda.probatio.guard` |
| `SpecLintKernel` (via `GuardResult`) | Stainless object | `org.sinemenda.probatio.verified` |
| `Outcome[+A]` | enum (Ran, Finding, Undetermined) | `org.sinemenda.probatio.core` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `OracleTestKind` | enum (`Behavioural`, `Structural`) | Whether a test observes a tool's behaviour (runs it, reads its output and status) or asserts over its source text. Only behavioural tests belong to the acceptance oracle. |
| `OracleSanction` | final case class (file, commit, spec, requirement) | A persisted record that one modification of an oracle file was required by a named requirement of a named spec. |
| `SanctionVerdict` | enum (`AllSanctioned`, `Unsanctioned(modifications)`, `Undeterminable(reason)`) | The immutability guard's decision against the sanction record. |
| `OracleBaseline` | final case class (commit) | The commit from which modifications are counted, recorded explicitly rather than discovered by searching commit messages. |

`OracleImmutabilityResult` is **superseded** by `SanctionVerdict`: "modified" stops being a
verdict on its own. A modification is either sanctioned or it is a finding.

### Type-Widening Impact

`SanctionVerdict` replaces `OracleImmutabilityResult` in the guard's one property. It has
three variants against the old two; the third, `Undeterminable`, is new reachable
behaviour. The guard's single match must handle it as could-not-determine, never as a
pass. There are no other matches over the old type.

## ADDED Requirements

### Requirement: The acceptance oracle holds only behavioural tests

The acceptance oracle SHALL contain only tests that observe a tool's behaviour, and a test
that asserts over a tool's source text MUST reside in the separate implementation-shape
suite.

**Given** the acceptance oracle's test files
**When** each test is classified
**Then** every test is behavioural, and every structural test is in the implementation-shape
suite

**Rationale**: a structural test is a statement about *how* a tool is written. It is
legitimate, and it is exactly the kind of test a port must change. Kept in the oracle, it
forces an oracle edit on every swap. Moved out, the oracle can stay fixed while the shape
suite follows the implementation openly.

#### Scenario: Happy path — a behavioural test stays in the oracle

**Given** a test that runs the gate and asserts its exit status and output
**When** the tests are classified
**Then** it is behavioural and stays in the oracle

#### Scenario: Happy path — a source-inspecting test moves to the shape suite

**Given** the test asserting that the gate reads the payload's working directory with the
structured parser rather than a pattern substitution — a statement about the source
**When** the tests are classified
**Then** it is structural and resides in the implementation-shape suite

#### Scenario: Adversarial — a structural test left in the oracle is reported

**Given** a test in the oracle that reads a tool's source file
**When** the classification check runs
**Then** it reports that test as structural, naming its file

### Requirement: Every oracle modification cites the requirement that made it necessary

Each modification of an acceptance-oracle file since the recorded baseline SHALL have a
sanction record naming the spec and requirement that required it, and a sanction MUST NOT
cite a requirement whose text does not name the modified oracle file or test.

**Given** a modification to an oracle file after the baseline
**When** the sanction record is checked
**Then** the modification has a sanction whose cited requirement names that file or test

**Rationale**: a sanction list anyone can append to is a rubber stamp. Requiring the cited
requirement to *name* the oracle change forces the decision to edit the oracle into a spec,
where it is reviewed, instead of into a commit, where it is not.

#### Scenario: Happy path — a requirement that names the test sanctions its edit

**Given** a modification to the schema-version assertion, and the requirement *"The
acceptance suite asserts the schema's actual version"*, which names it
**When** the sanction is checked
**Then** the sanction is accepted

#### Scenario: Adversarial — a sanction citing an unrelated requirement is rejected

**Given** a sanction for a modification to one test that cites a requirement whose text
does not mention that test or its file
**When** the sanction is checked
**Then** it is rejected, naming the modification and the cited requirement

#### Scenario: Adversarial — a sanction citing a requirement that does not exist is rejected

**Given** a sanction naming a requirement title absent from the cited spec
**When** the sanction is checked
**Then** it is rejected as unresolvable

### Requirement: The guard passes exactly when every modification is sanctioned

The immutability guard SHALL pass if and only if every oracle modification since the
recorded baseline has an accepted sanction, and it MUST NOT pass when it cannot read the
baseline, the history, or the record.

**Given** the recorded baseline, the oracle's history since it, and the sanction record
**When** the guard runs
**Then** it passes when every modification is sanctioned, and otherwise fails naming each
unsanctioned modification by file and commit

#### Scenario: Happy path — all modifications sanctioned passes

**Given** a history in which every oracle modification has an accepted sanction
**When** the guard runs
**Then** it passes

#### Scenario: Adversarial — one unsanctioned modification fails the guard

**Given** a history with one oracle modification that has no sanction
**When** the guard runs
**Then** it fails naming that file and commit

#### Scenario: Error path — an unreadable baseline is could-not-determine, not a pass

**Given** a recorded baseline that does not resolve to a commit
**When** the guard runs
**Then** the outcome is could-not-determine naming the baseline, and it is not a pass

### Requirement: The baseline is recorded, not discovered

The guard's baseline SHALL be a commit recorded explicitly with the oracle, and it MUST NOT
be located by searching commit messages.

**Given** the guard
**When** it determines where to count modifications from
**Then** it reads the recorded baseline commit

**Rationale**: the guard currently finds its baseline by searching history for a commit
message containing a fixed phrase. A reworded message, a squash, or a rebase silently
moves the baseline, and with it every verdict.

#### Scenario: Happy path — the recorded baseline is used

**Given** a recorded baseline commit
**When** the guard runs
**Then** it counts modifications from that commit

#### Scenario: Adversarial — a commit message containing the old search phrase does not move the baseline

**Given** a later commit whose message contains the phrase the guard used to search for
**When** the guard runs
**Then** the baseline is still the recorded commit

### Requirement: The existing modifications are resolved, not waved through

Every oracle modification made since the migration began SHALL be either moved to the
implementation-shape suite (if structural), sanctioned by a requirement that names it (if
behavioural), or reverted — and none MAY remain unaccounted for.

**Given** the oracle modifications made by the previous change — to the schema-hygiene,
fact-extraction, schema-version and install-verification suite files
**When** this spec is complete
**Then** each is in the shape suite, sanctioned, or reverted, and the guard passes

#### Scenario: Happy path — the retargeted source tests land in the shape suite

**Given** the tests that the previous change retargeted after their tools became forwarding
scripts
**When** this spec is complete
**Then** they reside in the implementation-shape suite, not in the oracle

#### Scenario: Adversarial — a behavioural modification with no naming requirement is reverted or sanctioned here

**Given** a behavioural oracle modification whose originating spec named no oracle test
**When** this spec is complete
**Then** it is either reverted or sanctioned by a requirement of *this* spec that names it
— it is not left in place unsanctioned

## Properties (Ring 3)

### Property: guard-passes-iff-all-sanctioned

**Invariant**: for every set of modifications and every sanction record, the guard passes
if and only if every modification has an accepted sanction; and an unreadable input never
yields a pass.

**Generator strategy**: `genModificationHistory` × `genSanctionRecord` — constructive.
Modifications drawn as (file, commit) from small closed alphabets. Each modification's
sanction is drawn from {absent, citing a requirement that names it, citing one that does
not, citing a nonexistent one}, so every sanction outcome arises by construction. Crossed
with an input-readability flag. Edge cases: no modifications, all sanctioned, exactly one
unsanctioned.

```
property("guard passes iff every modification is sanctioned") {
  for {
    mods     <- genModificationHistory.forAll
    record   <- genSanctionRecord(mods).forAll
    readable <- Gen.boolean.forAll
    verdict   = guard(mods, record, readable)
  } yield Result.assert(
    (verdict == AllSanctioned) == (readable && mods.forall(m => record.accepts(m)))
  )
}
```

### Property: sanction-record-round-trips

**Invariant**: for every sanction record, writing it and reading it back yields an equal
record.

**Generator strategy**: `genSanctionRecord` as above, with requirement titles containing
quotes, em-dashes and non-ASCII characters — the characters real requirement titles use.

```
property("the sanction record round-trips") {
  for {
    record <- genSanctionRecord.forAll
  } yield Result.assert(read(write(record)) == record)
}
```

### Property: oracle-contains-no-structural-test

**Invariant**: every test in the acceptance oracle is classified behavioural.

**Generator strategy**: enumerated, not sampled — the domain is the oracle's test list,
discovered at test time so a new test is covered automatically. The finite-domain limit is
stated.

```
property("the oracle contains no structural test") {
  for {
    _ <- Gen.constant(()).forAll
  } yield Result.assert(oracleTests.forall(_.kind == Behavioural))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A sanction with no cited requirement | A sanction that names no requirement is the rubber stamp this spec prevents | `assertDoesNotCompile("OracleSanction(file, commit)")` — spec and requirement are required |
| A guard verdict built without the baseline | A verdict whose baseline is implicit cannot be reproduced | `assertDoesNotCompile("SanctionVerdict.AllSanctioned")` — the passing variant carries the baseline it was earned against |

## Formal Contracts (Ring 6)

The guard's decision is a set-inclusion law over finite sets — every modification is in the
accepted-sanction set — and it sits at the centre of this spec. It is mirrored alongside the
existing guard outcome in the spec-lint kernel.

### Contract: sanctionVerdict

```
def sanctionVerdict(mods: List[BigInt], accepted: List[BigInt], readable: Boolean): VerdictModel = {
  ...
} ensuring { result =>
  (!readable ==> result.isUndeterminable) &&
  (result.isAllSanctioned == (readable && mods.forall(m => accepted.contains(m)))) &&
  (result.isUnsanctioned ==> result.named.forall(m => mods.contains(m) && !accepted.contains(m))) &&
  (result.isUnsanctioned ==> result.named.nonEmpty)
}
```

A bridge property binds the shipped guard to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A behavioural test stays in the oracle | Requirement: The acceptance oracle holds only behavioural tests + Scenario: Happy path — a behavioural test stays in the oracle | scenario test | `NonGoalsGuardSpec` |
| A source-inspecting test moves to the shape suite | Requirement: The acceptance oracle holds only behavioural tests + Scenario: Happy path — a source-inspecting test moves to the shape suite | scenario test | `NonGoalsGuardSpec` |
| A structural test left in the oracle is reported | Requirement: The acceptance oracle holds only behavioural tests + Scenario: Adversarial — a structural test left in the oracle is reported + Property: oracle-contains-no-structural-test | Hedgehog property (enumerated) | `NonGoalsGuardSpec` |
| A naming requirement sanctions its edit | Requirement: Every oracle modification cites the requirement that made it necessary + Scenario: Happy path — a requirement that names the test sanctions its edit | scenario test | `NonGoalsGuardSpec` |
| A sanction citing an unrelated requirement is rejected | Requirement: Every oracle modification cites the requirement that made it necessary + Scenario: Adversarial — a sanction citing an unrelated requirement is rejected | scenario test | `NonGoalsGuardSpec` |
| A sanction citing a nonexistent requirement is rejected | Requirement: Every oracle modification cites the requirement that made it necessary + Scenario: Adversarial — a sanction citing a requirement that does not exist is rejected | scenario test | `NonGoalsGuardSpec` |
| A sanction cannot omit its requirement | Compile-Negative: A sanction with no cited requirement | compile-negative test | `FeatureFreezeGuardIntegrityTypeContract` |
| The guard passes iff all modifications are sanctioned | Requirement: The guard passes exactly when every modification is sanctioned + Property: guard-passes-iff-all-sanctioned | Hedgehog property | `NonGoalsGuardSpec` |
| All sanctioned passes | Requirement: The guard passes exactly when every modification is sanctioned + Scenario: Happy path — all modifications sanctioned passes | scenario test | `NonGoalsGuardSpec` |
| One unsanctioned modification fails | Requirement: The guard passes exactly when every modification is sanctioned + Scenario: Adversarial — one unsanctioned modification fails the guard | scenario test | `NonGoalsGuardSpec` |
| An unreadable baseline is could-not-determine | Requirement: The guard passes exactly when every modification is sanctioned + Scenario: Error path — an unreadable baseline is could-not-determine, not a pass | scenario test | `NonGoalsGuardSpec` |
| A passing verdict cannot omit its baseline | Compile-Negative: A guard verdict built without the baseline | compile-negative test | `FeatureFreezeGuardIntegrityTypeContract` |
| The recorded baseline is used | Requirement: The baseline is recorded, not discovered + Scenario: Happy path — the recorded baseline is used | scenario test | `NonGoalsGuardSpec` |
| A commit message cannot move the baseline | Requirement: The baseline is recorded, not discovered + Scenario: Adversarial — a commit message containing the old search phrase does not move the baseline | scenario test | `NonGoalsGuardSpec` |
| The sanction record round-trips | Property: sanction-record-round-trips | Hedgehog property (Ring 4) | `NonGoalsGuardSpec` |
| The retargeted source tests are in the shape suite | Requirement: The existing modifications are resolved, not waved through + Scenario: Happy path — the retargeted source tests land in the shape suite | scenario test | `workflow-hygiene.bats` |
| An unnamed behavioural modification is resolved here | Requirement: The existing modifications are resolved, not waved through + Scenario: Adversarial — a behavioural modification with no naming requirement is reverted or sanctioned here | manual review at the checkpoint, each disposition recorded in the sanction record | `NonGoalsGuardSpec` |
| The guard decision is verified | Invariant: the guard passes iff every modification is sanctioned and inputs are readable | Stainless verification + bridge property test | `SpecLintKernel` (extended) + `SpecLintBridgeSpec` |
| The guard is green at the change's end | Criterion: this spec's exit criterion | suite run, recorded in the evidence ledger | `NonGoalsGuardSpec` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| The guard | munit suite | `workflow/core/src/test/scala/org/sinemenda/probatio/guard/NonGoalsGuardSpec.scala:650–658` | *oracle immutability at every migration step* — red at `55837a0` |
| Baseline discovery | test method | same file, `:817` (`migrationCommitsOnMain`) | `git rev-list --grep="created change"` — replaced by the recorded baseline |
| Immutability check | test method | same file, `:865` (`checkOracleImmutability`) | Replaced by the sanction check |
| Modifications to resolve | bats suites | `openspec/schemas/verified-scala3/tests/{workflow-hygiene,fact-extraction,correctness-invariant,harness-install-verification}.bats` | Edited in commits `c59a2aa`, `c3e3d54`, `9eda369`, `8d7e848`, `657823d` (+248 / −88) |
| Confirmed structural tests | bats tests | `workflow-hygiene.bats` — the ledger-mutation test (reads the subcommand source), the working-directory-parse test (reads the gate source), the drift-message test; `fact-extraction.bats` — the forwarding-script follower | Move to the shape suite. The full classification is done per test during implementation; a heuristic scan over-matches, so no count is asserted here |
| The shape suite | new bats directory | `openspec/schemas/verified-scala3/tests/shape/` (proposed) | Outside the oracle; not guarded for immutability |
| The sanction record | new persisted file | alongside the oracle | Ring 4 subject |
| The concept file | behavioural concept | `openspec/concepts/strangler-migration-protocol.md` | Gate action and State updated |
| `SpecLintKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/SpecLintKernel.scala` | Extended with the sanction contract |
| Ring 5 note | — | `stryker4s.conf` | The guard lives in test sources; the move-to-main-and-back procedure applies |
