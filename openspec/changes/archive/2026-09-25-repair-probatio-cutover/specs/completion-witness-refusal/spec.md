# Spec: Completion Witness Refusal

The completion tier must refuse to let a turn end when the evidence record contains a
green result that nothing corroborates. Today it allows the turn: the refusal predicate
returns allow where the predecessor refuses.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `Strangler` (Strangler Migration Protocol) | The completion tier sits at the gate seam, the last tool swapped. This spec restores the predecessor's refusal at that seam. No protocol action changes. | `openspec/concepts/strangler-migration-protocol.md` |

This spec does not alter any concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `GateEvent` | enum | `org.sinemenda.probatio.core` |
| `GateDecision` | enum | `org.sinemenda.probatio.core` |
| `GateDecisions` | object | `org.sinemenda.probatio.core` |
| `BlockReason` | enum | `org.sinemenda.probatio.core` |
| `RefusalBudget` | object | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class, private constructor | `org.sinemenda.probatio.core` |
| `Ledger` | object | `org.sinemenda.probatio.core` |
| `GateStateDir` | final case class | `org.sinemenda.probatio.cli` |
| `GateKernel` | Stainless object | `org.sinemenda.probatio.verified` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `WitnessVerdict` | enum (`Witnessed`, `Unwitnessed(row)`, `Undeterminable(reason)`) | Whether a green evidence row is corroborated by an independent observation. The third variant keeps "the corroboration could not be read" distinct from "there is no corroboration" — collapsing them would turn an unreadable record into a refusal or an allow, both of which are claims. |

### Type-Widening Impact

No public type is widened. `WitnessVerdict` is a new closed type; every match over it is
written in this change. `BlockReason` gains no variant — the existing
unwitnessed-claim reason is reused.

## ADDED Requirements

### Requirement: A turn is refused when a green result has no corroboration

The completion tier SHALL refuse turn completion when the change's evidence record
contains a result recorded as green for which no independent corroborating observation
exists, and it MUST NOT allow completion in that state.

**Given** a change whose evidence record contains a green result at the current baseline
**When** turn completion is attempted and no corroborating observation for that result
exists in the record
**Then** completion is refused, and the refusal names the uncorroborated result

**Applicability** (recorded at apply Ring 8): the refusal is scoped to a turn that
*presents* a completion claim — the per-session checkpoint-presentation marker the
harness writes before the Stop event. A session with no presentation marker makes no
completion claim, so mid-work stops pass silently; this precondition is predecessor
behaviour, not a relaxation of the refusal. Likewise the requirement quantifies over
*enumerable* changes: a changes directory that cannot be enumerated yields no scan
subjects, the same empty iteration the predecessor's `changes/*/` glob produces.

**Rationale**: a green result recorded by the party that produced it is a self-report. The
corroboration requirement is what makes the record evidence rather than testimony. The
predecessor refuses here; the port allows, which removes the tier's only teeth.

#### Scenario: Happy path — every green result is corroborated and completion proceeds

**Given** a change whose every green result carries a corroborating observation
**When** turn completion is attempted
**Then** completion proceeds

#### Scenario: Adversarial — a single uncorroborated green result refuses the turn

**Given** a change whose evidence record contains one green result with no corroborating
observation, alongside several corroborated ones
**When** turn completion is attempted
**Then** completion is refused and the refusal names that one result

#### Scenario: Edge case — a non-green result needs no corroboration

**Given** a change whose evidence record contains a red result with no corroborating
observation
**When** turn completion is attempted
**Then** completion proceeds — the corroboration requirement binds green results only

#### Scenario: Error path — an unreadable evidence record allows completion with a stated reason

**Given** a change whose evidence record cannot be read
**When** turn completion is attempted
**Then** completion proceeds and the tier states that the corroboration check could not be
performed, naming the unreadable record

### Requirement: The corroboration check fails open and says so

When the state the refusal depends on cannot be read, the tier SHALL allow completion and
state the reason, and it MUST NOT refuse on unread state.

**Given** a completion attempt whose corroboration state is unavailable — the record, the
baseline, or the per-session state area cannot be read
**When** the tier decides
**Then** completion is allowed, and the output names which input could not be read

**Rationale**: a hook that refuses on state it could not read converts an environment
fault into a blocked session. Failing open with a stated reason keeps the failure visible
without making it fatal — the same discipline every other tier already follows.

#### Scenario: Happy path — an unavailable state area allows with a named reason

**Given** a completion attempt in a repository with no resolvable per-session state area
**When** the tier decides
**Then** completion is allowed and the output names the state area as unavailable

#### Scenario: Adversarial — an unreadable record does not produce a refusal

**Given** a completion attempt whose evidence record exists but cannot be parsed
**When** the tier decides
**Then** completion is allowed, and the outcome is not a refusal

### Requirement: At most one refusal is issued per turn

The tier SHALL issue no more than one refusal within a single turn, and a second
completion attempt in the same turn MUST NOT produce a second refusal.

**Given** a turn in which the tier has already refused completion once
**When** completion is attempted again within that same turn
**Then** completion proceeds

**Rationale**: an unbounded refusal loop is indistinguishable from a hung session. The
bound already exists for the other tiers; the restored refusal must respect it.

#### Scenario: Happy path — the first refusal in a turn is issued

**Given** a turn with an uncorroborated green result and no prior refusal
**When** completion is attempted
**Then** completion is refused

#### Scenario: Adversarial — a second attempt in the same turn is not refused

**Given** a turn in which a refusal has already been issued
**When** completion is attempted a second time with the same uncorroborated result
**Then** completion proceeds

#### Scenario: Edge case — a new turn refuses again

**Given** a new turn with the same uncorroborated green result
**When** completion is attempted
**Then** completion is refused — the bound is per turn, not per change

## Properties (Ring 3)

### Property: refusal-iff-an-uncorroborated-green-result-exists

**Invariant**: for every readable evidence record and every turn with no prior refusal,
the tier refuses completion if and only if the record contains at least one green result
at the current baseline with no corroborating observation.

**Generator strategy**: `genEvidenceRecord` — constructive over lists of 0–8 rows, each
row drawn as (result ∈ {green, red}, corroborated ∈ {yes, no}, baseline ∈ {current,
other}) from closed sets rather than filtered. Edge cases: the empty record, all-green
all-corroborated, all-green none-corroborated, green rows at a non-current baseline (which
must not trigger a refusal), and a single uncorroborated row among many corroborated ones.

```
property("refuses iff an uncorroborated green result exists") {
  for {
    record <- genEvidenceRecord.forAll
    verdict = decideCompletion(record, baseline, noPriorRefusal)
  } yield Result.assert(
    verdict.isRefusal ==
      record.rows.exists(r => r.isGreen && r.atBaseline && !r.corroborated)
  )
}
```

### Property: unreadable-state-never-refuses

**Invariant**: for every unreadable-state condition, the decision is allow — never a
refusal — and the output names the unreadable input.

**Generator strategy**: `genUnreadableState` — constructive over the closed set of inputs
the decision depends on (record absent, record unparseable, baseline unresolvable, state
area unresolvable), crossed with an evidence record drawn from `genEvidenceRecord` so the
property covers the case where the record *would* have refused had it been readable.

```
property("unreadable state never refuses") {
  for {
    state  <- genUnreadableState.forAll
    record <- genEvidenceRecord.forAll
    verdict = decideCompletion(record, state)
  } yield Result.assert(!verdict.isRefusal && verdict.reason.namesInput)
}
```

### Property: refusal-budget-is-bounded-and-nonzero

**Invariant**: across any sequence of completion attempts within one turn, the number of
refusals issued is at most one, and for a turn whose first attempt warrants a refusal the
number is exactly one.

**Generator strategy**: `genAttemptSequence` — constructive over sequences of 1–6 attempts
within a single turn, each attempt carrying a record from `genEvidenceRecord`. Edge cases:
a single attempt, six identical warranting attempts, and a sequence whose first attempt
does not warrant a refusal but whose later ones do. Determinism: the sequence is driven by
an injected turn identity and an injected clock seam, not by wall-clock time.

```
property("at most one refusal per turn") {
  for {
    attempts <- genAttemptSequence.forAll
    refusals = attempts.scanDecisions.count(_.isRefusal)
  } yield Result.assert(refusals <= 1)
}
```

### Property: parity-with-predecessor-on-the-completion-tier

**Invariant**: for every generated change fixture, the ported tier's exit status equals
the predecessor's for the same fixture, with the predecessor run as the model — **except
on the declared divergence**: the predecessor's corroboration check is invoked without a
baseline filter and refuses on an uncorroborated green row at ANY baseline, while this
spec scopes the refusal to the current baseline (`decideCompletion`'s
`r.baseline == baseline`). A fixture whose only refusal warrant is an uncorroborated
green row at a non-current baseline therefore diverges BY DESIGN — the predecessor
refuses, the port allows. The property does not exclude that shape from the corpus; it
asserts the divergence is confined to it.

**Declared divergence**: `model` refuses ∧ `ported` allows is permitted **iff** the
record contains an uncorroborated green row at a non-current baseline AND no
uncorroborated green row at the current baseline. Every other fixture is unconditional
parity. (Recorded at apply Step 0 — the current-baseline scope was confirmed as the
intended semantics against the measured predecessor behaviour.)

**Generator strategy**: `genCompletionFixture` — constructive over change directories
carrying an evidence record from `genEvidenceRecord`, a baseline, and a per-session state
area in one of {absent, empty, carrying a prior refusal}. Model-based: the predecessor
script runs as a subprocess on the same fixture. Determinism: recorded process outcomes
and an injected clock seam; no sleeps. Coverage: the divergent shape MUST be exercised —
a parity property whose corpus never produces a stale-baseline claim is vacuous on
exactly the case it declares.

```
property("completion tier agrees with the predecessor modulo the declared divergence") {
  for {
    fixture <- genCompletionFixture.forAll
    ported   = runPorted(fixture)
    model    = runPredecessor(fixture)
  } yield Result.assert(
    ported.exitStatus == model.exitStatus ||
      (model.isRefusal && ported.isAllow &&
        fixture.record.hasStaleUncorroboratedGreen &&
        !fixture.record.hasCurrentUncorroboratedGreen)
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A witness verdict with only two variants | Collapsing "could not read the corroboration" into either "witnessed" or "unwitnessed" turns an environment fault into a verdict | `assertDoesNotCompile("val v: WitnessVerdict = WitnessVerdict.Unknown")` — the type's third variant carries a reason and cannot be built empty |
| A refusal constructed without the offending row | A refusal that does not name what it refuses over cannot be acted on | `assertDoesNotCompile("WitnessVerdict.Unwitnessed()")` — the variant requires the row |

## Formal Contracts (Ring 6)

The refusal predicate and the per-turn bound are decisions at the centre of this spec. The
bound is already mirrored; the predicate is added.

### Contract: decideCompletion

```
def decideCompletion(rows: List[EvidenceRow], baseline: Baseline,
                     priorRefusals: BigInt): CompletionDecision = {
  require(priorRefusals >= 0)
  ...
} ensuring { result =>
  // refusal iff an uncorroborated green row exists at this baseline, and none yet issued
  result.isRefusal ==
    (priorRefusals == 0 &&
     rows.exists(r => r.isGreen && r.baseline == baseline && !r.corroborated)) &&
  // a refusal always names a row that justifies it
  (result.isRefusal ==> result.namedRow.isDefined) &&
  // the bound holds
  (priorRefusals > 0 ==> !result.isRefusal)
}
```

A bridge property binds the shipped predicate to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| An uncorroborated green result refuses the turn | Requirement: A turn is refused when a green result has no corroboration | scenario test + bats oracle | `GateEventSpec`, `ambient-capture-wiring.bats` |
| Every corroborated record proceeds | Requirement: A turn is refused when a green result has no corroboration + Scenario: Happy path — every green result is corroborated and completion proceeds | bats oracle | `ambient-capture-wiring.bats` |
| One uncorroborated result among many refuses | Requirement: A turn is refused when a green result has no corroboration + Scenario: Adversarial — a single uncorroborated green result refuses the turn | scenario test | `GateEventSpec` |
| A red result needs no corroboration | Requirement: A turn is refused when a green result has no corroboration + Scenario: Edge case — a non-green result needs no corroboration | scenario test | `GateEventSpec` |
| Refusal holds exactly when an uncorroborated green result exists | Property: refusal-iff-an-uncorroborated-green-result-exists | Hedgehog property | `GateDecisionSpec` |
| An unreadable record allows with a stated reason | Requirement: The corroboration check fails open and says so + Scenario: Error path — an unreadable evidence record allows completion with a stated reason | scenario test | `GateEventSpec` |
| An unavailable state area allows with a named reason | Requirement: The corroboration check fails open and says so + Scenario: Happy path — an unavailable state area allows with a named reason | scenario test | `GateStateDirSpec` |
| Unreadable state never refuses | Requirement: The corroboration check fails open and says so + Scenario: Adversarial — an unreadable record does not produce a refusal + Property: unreadable-state-never-refuses | Hedgehog property | `GateDecisionSpec` |
| The first refusal in a turn is issued | Requirement: At most one refusal is issued per turn + Scenario: Happy path — the first refusal in a turn is issued | bats oracle | `hook-tiers.bats` |
| A second attempt in the same turn is not refused | Requirement: At most one refusal is issued per turn + Scenario: Adversarial — a second attempt in the same turn is not refused | bats oracle | `hook-tiers.bats` |
| A new turn refuses again | Requirement: At most one refusal is issued per turn + Scenario: Edge case — a new turn refuses again | scenario test | `GateEventSpec` |
| The per-turn bound holds over any attempt sequence | Property: refusal-budget-is-bounded-and-nonzero | Hedgehog property (injected turn identity and clock seam — no wall-clock) | `GateDecisionSpec` |
| A two-variant witness verdict is unconstructible | Compile-Negative: A witness verdict with only two variants | compile-negative test | `CompletionWitnessRefusalCompileNegative` |
| A refusal without its offending row is unconstructible | Compile-Negative: A refusal constructed without the offending row | compile-negative test | `CompletionWitnessRefusalCompileNegative` |
| The tier agrees with the predecessor | Property: parity-with-predecessor-on-the-completion-tier | Hedgehog model-based property (predecessor run as a subprocess) | `GateBannerCompatSpec` |
| The refusal predicate and bound are formally verified | Invariant: refusal iff uncorroborated-green-and-unbounded | Stainless verification + bridge property test | `GateKernel` (extended) + `GateBridgeSpec` |
| The suite file reaches control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `ambient-capture-wiring.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `GateDecisions` | object | `workflow/core/src/main/scala/org/sinemenda/probatio/core/GateDecisions.scala` | The pure decision functions; gains the corroboration predicate |
| `WitnessVerdict` | new enum | `.../core/` | New |
| `RefusalBudget` | object | `.../core/RefusalBudget.scala` | Reused unchanged; the restored refusal is bounded by it |
| `GateCmd` completion tier | object method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala:34` | The adapter: reads the record and the state area, calls the predicate, maps to an exit status |
| `GateStateDirReader` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/GateStateDir.scala` | Supplies the per-turn refusal state; already fails open |
| `ambient-capture-wiring.bats` | bats suite | `openspec/schemas/verified-scala3/tests/` | Test 29 is the regression; the other 31 pass in both arms |
| `GateKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/GateKernel.scala` | Extended with the corroboration contract |
| Ring 5 note | — | `stryker4s.conf` | Implementation is in main sources; no move procedure needed |
