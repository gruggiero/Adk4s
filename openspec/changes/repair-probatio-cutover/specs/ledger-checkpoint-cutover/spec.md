# Spec: Ledger and Checkpoint Cutover

The evidence ledger and the checkpoint generator have ported implementations that the
acceptance oracle has never executed. Six oracle files covering them were reported at
parity throughout the migration while both arms ran the predecessor. This spec swaps them
under a comparison that can see the difference.

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Strangler migration protocol | The protocol's **Swap** and **Gate** actions applied to the two seams the migration declared but never measured. The protocol's declared swap order already places the ledger first. | `openspec/concepts/strangler-migration-protocol.md` |
| Conformance property-test contract | The ledger record format has an executable contract; the ported validator must agree with it in both directions. | `openspec/concepts/conformance-property-test-contract.md` |

This spec does not alter either concept's purpose, actions, state, or synchronizations.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Ledger` | object | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class, private constructor | `org.sinemenda.probatio.core` |
| `Validator` | object (closed clause set) | `org.sinemenda.probatio.core` |
| `ContractViolation` | sealed hierarchy | `org.sinemenda.probatio.core` |
| `CheckpointEngine` | object | `org.sinemenda.probatio.core` |
| `CheckpointReport` | final case class, smart constructor | `org.sinemenda.probatio.core` |
| `RingEvidence` | final case class | `org.sinemenda.probatio.core` |
| `ReplayVerdict` | enum | `org.sinemenda.probatio.core` |
| `SessionId` | opaque type | `org.sinemenda.probatio.core` |
| `ToolId` (migration) | enum | `org.sinemenda.probatio.migration` |
| `SwapOrder` | enum | `org.sinemenda.probatio.migration` |
| `ShimSwap` | final case class | `org.sinemenda.probatio.migration` |
| `CutoverGate` | object | `org.sinemenda.probatio.migration` |

## Concepts Introduced (new)

None. This spec swaps two seams whose implementations already exist and adds no type. The
two seams it needs — the ledger seam and the checkpoint seam — are introduced by
`specs/differential-harness-integrity/spec.md`, which this spec depends on.

### Type-Widening Impact

None. No type gains a variant in this spec.

## ADDED Requirements

### Requirement: Each seam is swapped only after its oracle files reach control parity

A seam SHALL be swapped only when every oracle file exercising it fails no more tests
under the ported implementation than under the predecessor, and a seam MUST NOT be swapped
on an unmeasured file.

**Given** a seam and the set of oracle files that exercise it
**When** the differential comparison runs with that seam ported and the rest on the
predecessor
**Then** the swap proceeds only if no such file is worse, and otherwise the seam is left
on the predecessor

**Rationale**: these two seams were reported at parity for the whole migration without ever
being measured. The requirement is that the measurement precede the swap, not that the
measurement be favourable — an unfavourable measurement is a result, an absent one is not.

#### Scenario: Happy path — a seam whose files are at parity is swapped

**Given** a seam whose every exercising oracle file is at or below the predecessor's
failure count
**When** the swap decision is taken
**Then** the seam is swapped and the decision records the comparison it rested on

#### Scenario: Adversarial — a seam with one worse file is not swapped

**Given** a seam whose exercising files are all at parity except one, which is worse
**When** the swap decision is taken
**Then** the seam is left on the predecessor and the decision names the worse file

#### Scenario: Adversarial — a seam with an unmeasured file is not swapped

**Given** a seam one of whose exercising oracle files produced no result in one arm
**When** the swap decision is taken
**Then** the seam is left on the predecessor and the decision names the unmeasured file

### Requirement: The evidence record format stays byte-compatible across the swap

The ported evidence tool SHALL accept every record the executable record contract accepts
and reject every record it rejects, and it MUST NOT accept a record the contract rejects.

**Given** a record and the executable record contract
**When** both the ported validator and the contract judge it
**Then** the two judgments agree

#### Scenario: Happy path — a conforming record is accepted by both

**Given** a record satisfying every contract clause
**When** both judge it
**Then** both accept

#### Scenario: Adversarial — a record violating one clause is rejected by both

**Given** a record violating exactly one contract clause, for each clause in turn
**When** both judge it
**Then** both reject

#### Scenario: Edge case — a record carrying only the optional fields round-trips

**Given** a record carrying every optional field
**When** it is written and read back
**Then** the read record equals the written one

#### Scenario: Error path — a record at an unrecognised format version is could-not-determine

**Given** a record declaring a format version the tool does not recognise
**When** the tool reads it
**Then** the outcome is could-not-determine naming the version, and the record is not
skipped silently

### Requirement: The checkpoint reports every requested ring from recorded evidence

The checkpoint SHALL report an outcome for every ring it was asked about, drawn from
recorded evidence at the current baseline, and it MUST NOT report a ring outcome for which
no evidence was recorded.

**Given** a checkpoint invocation naming a set of rings
**When** the checkpoint is generated
**Then** every named ring appears, each either carrying its recorded evidence or marked as
having none

#### Scenario: Happy path — a fully evidenced ring set reports every ring

**Given** a ring set for which every ring has recorded evidence at the current baseline
**When** the checkpoint is generated
**Then** every ring appears with its evidence

#### Scenario: Adversarial — a ring with no recorded evidence is marked, not inferred

**Given** a ring set containing one ring with no recorded evidence
**When** the checkpoint is generated
**Then** that ring appears marked as having no recorded evidence, and no outcome is
asserted for it

#### Scenario: Adversarial — evidence recorded at another baseline does not count

**Given** a ring whose only evidence was recorded at a different baseline
**When** the checkpoint is generated at the current baseline
**Then** that ring is marked as having no recorded evidence at this baseline

### Requirement: The predecessor implementations remain the revert target

Each swapped predecessor implementation SHALL remain on disk until the comparison has held
at parity across a full change cycle, and a predecessor implementation MUST NOT be removed
by this change.

**Given** a swapped seam
**When** the tree is inspected
**Then** the predecessor implementation for that seam is present

#### Scenario: Happy path — every swapped seam has its predecessor on disk

**Given** the set of swapped seams after this change
**When** the tree is inspected
**Then** each has its predecessor implementation present

#### Scenario: Adversarial — a swap whose predecessor is absent is refused

**Given** a seam whose predecessor implementation is not present
**When** the swap decision is taken
**Then** the swap is refused naming the absent predecessor

## Properties (Ring 3)

### Property: record-validator-agrees-with-the-contract

**Invariant**: for every generated record, the ported validator's judgment equals the
executable contract's judgment — in both directions.

**Generator strategy**: `genLedgerRecord` — constructive, covering each contract clause
independently: for each clause, generate a record satisfying it and a record violating it
alone, then combine. Augmented with the committed fixture corpus. No filtering. Edge
cases: the minimal record, a record with every optional field, records with empty and
non-ASCII field values.

```
property("validator agrees with the contract") {
  for {
    record <- genLedgerRecord.forAll
    ported  = validate(record)
    model   = contractJudge(record)
  } yield Result.assert(ported.accepts == model.accepts)
}
```

### Property: record-round-trips-all-present-fields

**Invariant**: for every record, writing it and reading it back yields an equal record,
including every optional field that was present.

**Generator strategy**: `genLedgerRecord` as above, with the optional-field subset drawn
constructively over the full power set rather than sampled, so every combination of
present and absent optional fields is covered.

```
property("records round-trip every present field") {
  for {
    record <- genLedgerRecord.forAll
    back    = read(write(record))
  } yield Result.assert(back == record)
}
```

### Property: checkpoint-reports-every-requested-ring

**Invariant**: for every requested ring set and every evidence state, the generated
checkpoint contains exactly one entry per requested ring, and an entry carries an outcome
only when evidence for that ring exists at the current baseline.

**Generator strategy**: `genRingRequest` × `genEvidenceState` — constructive over ring
subsets of the closed ring set, crossed with evidence states drawn from {none, at this
baseline, at another baseline, both}. Edge cases: the empty ring set, the full ring set,
a ring evidenced only at another baseline.

```
property("checkpoint reports every requested ring") {
  for {
    rings    <- genRingRequest.forAll
    evidence <- genEvidenceState.forAll
    report    = checkpoint(rings, evidence)
  } yield Result.assert(
    report.entries.map(_.ring).toSet == rings.toSet &&
    report.entries.forall(e => e.hasOutcome == evidence.hasAt(e.ring, currentBaseline))
  )
}
```

### Property: swap-decision-requires-a-complete-comparison

**Invariant**: for every comparison result, a swap is authorised only when every oracle
file exercising the seam produced a result in both arms and none is worse.

**Generator strategy**: `genComparisonResult` — constructive over per-file triples
(predecessor result present, ported result present, failure counts), with each presence
flag drawn independently so incomplete comparisons arise by construction. Edge cases: all
files complete and equal, one file missing from one arm, one file worse, both.

```
property("a swap requires a complete comparison") {
  for {
    result <- genComparisonResult.forAll
    decision = authoriseSwap(result)
  } yield Result.assert(
    decision.authorised == (result.isComplete && !result.hasRegression)
  )
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A swap record constructed without its comparison evidence | A swap whose justification is not attached cannot be audited or reverted with reason | `assertDoesNotCompile("ShimSwap(tool, pred, shim, bin)")` — the record requires the comparison outcome |
| A checkpoint entry asserting an outcome with no evidence | This is the defect the checkpoint exists to prevent: a ring outcome written from memory | `assertDoesNotCompile("RingEvidence(ring, outcome = Green)")` — evidence is required to construct an outcome-bearing entry |
| A mutation operation on the evidence record | The record is append-only; a mutation operation would make the audit trail rewritable | `assertDoesNotCompile("Subcommand.fromString(\"update\")")` — no mutation case exists in the subcommand enum |

## Formal Contracts (Ring 6)

The record validator and the checkpoint marker decision are already mirrored. This spec
extends the mirror with the swap-authorisation decision, which is the new decision it
introduces.

### Contract: authoriseSwap

```
def authoriseSwap(files: List[FileComparison]): SwapDecision = {
  ...
} ensuring { result =>
  // authorised iff complete and no regression
  result.authorised ==
    (files.forall(f => f.predecessorPresent && f.portedPresent) &&
     files.forall(f => f.portedFailures <= f.predecessorFailures)) &&
  // a refusal always names at least one file that justifies it
  (!result.authorised ==> result.namedFiles.nonEmpty)
}
```

A bridge property binds the shipped decision to this model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| A seam at parity is swapped and records its comparison | Requirement: Each seam is swapped only after its oracle files reach control parity + Scenario: Happy path — a seam whose files are at parity is swapped | scenario test | `CutoverGateSpec` |
| A seam with one worse file is not swapped | Requirement: Each seam is swapped only after its oracle files reach control parity + Scenario: Adversarial — a seam with one worse file is not swapped | scenario test | `CutoverGateSpec` |
| A seam with an unmeasured file is not swapped | Requirement: Each seam is swapped only after its oracle files reach control parity + Scenario: Adversarial — a seam with an unmeasured file is not swapped | scenario test | `CutoverGateSpec` |
| A swap requires a complete comparison | Property: swap-decision-requires-a-complete-comparison | Hedgehog property | `CutoverGateSpec` |
| A swap record without its comparison is unconstructible | Compile-Negative: A swap record constructed without its comparison evidence | compile-negative test | `CliWiringCompileNegativeSpec` |
| A conforming record is accepted by both | Requirement: The evidence record format stays byte-compatible across the swap + Scenario: Happy path — a conforming record is accepted by both | scenario test | `LedgerCmdConformanceSpec` |
| A clause-violating record is rejected by both | Requirement: The evidence record format stays byte-compatible across the swap + Scenario: Adversarial — a record violating one clause is rejected by both + Property: record-validator-agrees-with-the-contract | Hedgehog property against the executable contract | `LedgerCmdConformanceSpec` |
| A record with every optional field round-trips | Requirement: The evidence record format stays byte-compatible across the swap + Scenario: Edge case — a record carrying only the optional fields round-trips + Property: record-round-trips-all-present-fields | Hedgehog property (Ring 4) | `LedgerRecordRoundTripSpec` |
| An unrecognised format version is could-not-determine | Requirement: The evidence record format stays byte-compatible across the swap + Scenario: Error path — a record at an unrecognised format version is could-not-determine | scenario test + bats oracle | `LedgerReadSpec`, `evidence-ledger.bats` |
| A mutation operation is unconstructible | Compile-Negative: A mutation operation on the evidence record | compile-negative test | `SubcommandTypeContract` |
| A fully evidenced ring set reports every ring | Requirement: The checkpoint reports every requested ring from recorded evidence + Scenario: Happy path — a fully evidenced ring set reports every ring | scenario test + bats oracle | `CheckpointCmdSpec`, `checkpoint-from-ledger.bats` |
| A ring with no evidence is marked, not inferred | Requirement: The checkpoint reports every requested ring from recorded evidence + Scenario: Adversarial — a ring with no recorded evidence is marked, not inferred | scenario test + bats oracle | `CheckpointCmdSpec`, `checkpoint-from-ledger.bats` |
| Evidence at another baseline does not count | Requirement: The checkpoint reports every requested ring from recorded evidence + Scenario: Adversarial — evidence recorded at another baseline does not count | scenario test | `CheckpointParitySpec` |
| Every requested ring appears exactly once | Property: checkpoint-reports-every-requested-ring | Hedgehog property | `CheckpointParitySpec` |
| An outcome-bearing entry without evidence is unconstructible | Compile-Negative: A checkpoint entry asserting an outcome with no evidence | compile-negative test | `LedgerCheckpointParityTypeContract` |
| Every swapped seam has its predecessor on disk | Requirement: The predecessor implementations remain the revert target + Scenario: Happy path — every swapped seam has its predecessor on disk | scenario test | `HookCutoverShimSpec` |
| A swap whose predecessor is absent is refused | Requirement: The predecessor implementations remain the revert target + Scenario: Adversarial — a swap whose predecessor is absent is refused | scenario test | `CutoverRevertSpec` |
| The swap-authorisation decision is verified | Invariant: a swap is authorised iff the comparison is complete and regression-free | Stainless verification + bridge property test | `LedgerValidatorKernel` (extended) + `CheckpointBridgeSpec` |
| The six exercising suite files reach control parity | Criterion: this spec's exit criterion | bats oracle compared against the repaired differential control | `evidence-ledger.bats`, `evidence-capture.bats`, `discharge-fidelity.bats`, `checkpoint-from-ledger.bats`, `judgment-ring-integrity.bats`, `judgment-ring-provenance.bats` via `probatioOracleDiff` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ledger.sh` | predecessor implementation | `openspec/schemas/verified-scala3/scanner/ledger.sh` | 616 lines, four operations, eleven flags. Becomes a forwarding shim; the original is retained as the revert target. |
| `checkpoint.sh` | predecessor implementation | `openspec/schemas/verified-scala3/scanner/checkpoint.sh` | 514 lines. Same treatment. |
| `LedgerCmd`, `CheckpointCmd` | objects | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala:2315` and following | The ported implementations; unchanged by this spec except where parity measurement reveals a defect |
| `ledger-record-contract.jq` | executable contract | `openspec/schemas/verified-scala3/scanner/` | The Ring 4 wire oracle; unchanged |
| `SwapOrder` | enum | `workflow/core/src/test/scala/org/sinemenda/probatio/migration/SwapOrder.scala:25` | Already places the ledger first; this spec follows that order |
| `ShimSwap` | final case class | `.../migration/ShimSwap.scala` | Gains the comparison outcome as a required field |
| The six exercising oracle files | bats suites | `openspec/schemas/verified-scala3/tests/` | All six currently pass in both arms **because both arms run the predecessor**; their first real measurement happens here |
| `LedgerValidatorKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/LedgerValidatorKernel.scala` | Extended with the swap-authorisation contract |
| Ring 5 note | — | `stryker4s.conf` | The swap-decision code lives in **test** sources; the move-to-main-and-back procedure applies to that part |
