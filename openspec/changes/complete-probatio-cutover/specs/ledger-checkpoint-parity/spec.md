# Spec: Ledger and Checkpoint Parity

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Schema | The evidence record is where a run becomes a fact; the checkpoint is where those facts are presented for a human decision | [schema.md](../../../../concepts/schema.md) |
| `Conformance` (Conformance Property-Test Contract) | The record format is stated once, by the record contract, and both the tool and the oracle conform to it | [conformance-property-test-contract.md](../../../../concepts/conformance-property-test-contract.md) |

This spec does not alter either concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `LedgerRecord` | final case class | `org.sinemenda.probatio.core` |
| `LedgerRecordOptional` | final case class | `org.sinemenda.probatio.core` |
| `Ledger` / `Ledger.LedgerData` | object / final case class | `org.sinemenda.probatio.core` |
| `Validator` | object (15 clauses) | `org.sinemenda.probatio.core` |
| `ContractViolation` | sealed trait (15 variants) | `org.sinemenda.probatio.core` |
| `Ring` | enum | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |
| `ChainStateReport` | final case class | `org.sinemenda.probatio.core` |
| `PresentationMarker` | object | `org.sinemenda.probatio.core` |
| `SessionId` | opaque type | `org.sinemenda.probatio.core` (introduced by the gate-event-completeness spec) |
| `SubcommandWiring` | object | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RingEvidence` | final case class | One ring's evidence for a spec: the ring, the records found at the current baseline, the resulting verdict, and the session that produced them |
| `CheckpointReport` | final case class | The generated checkpoint: per-ring evidence, the correctness verdict passed in whole, and the overall readiness |
| `ReplayVerdict` | enum (`Matches`, `Diverges`, `Unreplayable`) | The result of re-running a recorded command and comparing its outcome to the record |

## ADDED Requirements

### Requirement: A record written by the observing path carries the fields that make it self-observed

The tool SHALL write, whenever it executes a command itself and records the
outcome, a record carrying the fields that distinguish a self-observed record from
a written assertion.

**Given** the tool is asked to run a command and record its outcome
**When** the record is written
**Then** the record carries the recorder's own observation fields alongside the
required fields, and reading it back yields those fields unchanged

**Rationale**: The self-observation fields are what let the corroboration tool tell
a recorder's own observation from a writer's assertion. The ported tool declares a
companion type for them and never attaches it to the record's encoder, so the
fields are dropped on write and every record reads as a bare assertion.

#### Scenario: Happy path — a self-observed record round-trips its observation fields

**Given** the tool runs a command that succeeds
**When** the record is written and read back
**Then** the observation fields are present and equal to what was written

#### Scenario: Adversarial — the observation fields are not silently dropped

**Given** the tool runs a command
**When** the record is written
**Then** the persisted line contains the observation fields — a record lacking them
is a failure of this requirement, not an acceptable variant

#### Scenario: Happy path — a written assertion carries no observation fields

**Given** the tool is asked to record an outcome the caller supplies
**When** the record is written
**Then** the record carries no observation fields, so it is distinguishable from a
self-observed one

#### Scenario: Edge case — a record set mixing both shapes reads cleanly

**Given** a record set containing both self-observed and written-assertion records
**When** it is read
**Then** every record is returned and none is rejected for the shape it has

### Requirement: A recorded run can be replayed and its outcome compared

The tool SHALL support re-running the command a record names and reporting whether
the observed outcome matches the recorded one.

**Given** a record set
**When** replay is requested
**Then** each record is reported as matching, diverging, or unreplayable, and the
exit status is clean only when no record diverges

**Rationale**: Recording the command is what makes a record re-checkable. Without
the replay operation that property is latent — the record says it is checkable and
nothing checks it. The predecessor has this operation; the port does not.

#### Scenario: Happy path — a matching record reports matching and the run exits clean

**Given** a record whose command re-runs with the recorded outcome
**When** replay is requested
**Then** the record is reported matching and the exit status is clean

#### Scenario: Adversarial — a record whose command now yields a different outcome reports diverging

**Given** a record whose command re-runs with a different outcome
**When** replay is requested
**Then** the record is reported diverging and the exit status is not clean

#### Scenario: Edge case — a record whose discharge is a human judgment is unreplayable

**Given** a record on a ring whose discharge is a human judgment
**When** replay is requested
**Then** the record is reported unreplayable, and it does not cause a non-clean
exit status

#### Scenario: Error path — an unreadable record set is could-not-determine

**Given** a record set that cannot be read
**When** replay is requested
**Then** the result is could-not-determine and the exit status is the
could-not-determine status

### Requirement: The checkpoint is generated from recorded evidence, never authored

The checkpoint SHALL report, for each requested ring, either the records found at
the current baseline or that no evidence was recorded, and SHALL NOT accept a ring
result supplied as free text.

**Given** a set of requested rings, a record set, and a correctness verdict
**When** the checkpoint is generated
**Then** each requested ring is reported with the records that support it or with
the statement that no evidence was recorded for it

**Rationale**: The step this replaces asked the agent to write a per-ring result
list from memory at the single moment a human decides whether to approve. Nothing
compared that against what ran. The ported tool writes a marker and generates no
report at all, which is the same gap with the prose removed.

#### Scenario: Happy path — a ring with records at the current baseline is reported green with them

**Given** a requested ring and a record for it at the current baseline with a
successful outcome
**When** the checkpoint is generated
**Then** that ring is reported with the record, and its verdict is green

#### Scenario: Adversarial — a ring with no records is reported unevidenced, not green

**Given** a requested ring and no record for it at the current baseline
**When** the checkpoint is generated
**Then** that ring is reported as having no recorded evidence, and the exit status
is not clean

#### Scenario: Edge case — a record at a superseded baseline does not evidence the ring

**Given** a requested ring whose only record is at a baseline other than the
current one
**When** the checkpoint is generated
**Then** that ring is reported as having no recorded evidence at the current
baseline

#### Scenario: Error path — a requested ring outside the known set is rejected naming it

**Given** a requested ring name that is not in the known ring set
**When** the checkpoint is generated
**Then** the name is rejected and reported, rather than being reported as a ring
with no evidence

#### Scenario: Adversarial — a review-ring record produced by the implementing session is reported as such

**Given** a requested review ring whose record names the same session as the
implementing session
**When** the checkpoint is generated
**Then** the report states that the review was not produced by an independent
session

### Requirement: The checkpoint does not recompute the correctness verdict

The checkpoint SHALL consume the correctness verdict as supplied and SHALL NOT
compute one itself.

**Given** a correctness verdict supplied to the checkpoint
**When** the checkpoint is generated
**Then** the counts it reports are the supplied counts

**Rationale**: The layering discipline the predecessor states explicitly — record
filtering is always the record tool's own operation, correctness counts are always
the correctness tool's own report. Recomputing either here creates a second parser
free to disagree with the first.

#### Scenario: Happy path — supplied counts appear unchanged

**Given** a supplied correctness verdict with specific counts
**When** the checkpoint is generated
**Then** the reported counts equal the supplied counts

#### Scenario: Error path — a verdict that does not parse is could-not-determine

**Given** a supplied correctness verdict that does not parse
**When** the checkpoint is generated
**Then** the result is could-not-determine and the exit status is the
could-not-determine status

### Requirement: The presentation marker is written only when every requested ring is evidenced and the verdict is fully discharged

The marker recording that a checkpoint was presented SHALL be written only when
every requested ring has recorded evidence at the current baseline and the supplied
correctness verdict reports nothing unresolved.

**Given** a checkpoint generation
**When** the marker is considered
**Then** the marker is written if and only if both conditions hold

**Rationale**: The marker is what the gate's predecessor check and grant waiver
read to decide that a spec is genuinely finished. A marker written on an
undischarged spec makes the whole lock advisory.

#### Scenario: Adversarial — an undischarged verdict writes no marker

**Given** a supplied verdict reporting unresolved requirements
**When** the checkpoint is generated
**Then** no marker is written and the exit status is not clean

#### Scenario: Adversarial — an unevidenced ring writes no marker

**Given** a fully discharged verdict but a requested ring with no recorded evidence
**When** the checkpoint is generated
**Then** no marker is written

#### Scenario: Happy path — a fully evidenced and discharged checkpoint writes the marker

**Given** every requested ring evidenced at the current baseline and a verdict
reporting nothing unresolved
**When** the checkpoint is generated
**Then** the marker is written and the exit status is clean

### Requirement: The record and checkpoint tools accept the predecessor's operation and parameter set

Both tools SHALL accept every operation and parameter the predecessor accepts, and
SHALL produce the predecessor's output for each.

**Given** an invocation the predecessor accepts
**When** the tool runs
**Then** the invocation is accepted and its output matches the predecessor's

**Rationale**: The checkpoint tool currently has no operations at all where the
predecessor has two, and the record tool is missing the replay operation and three
parameters. Any caller written against the workflow's documented surface fails.

#### Scenario: Happy path — the report operation is accepted

**Given** the checkpoint tool invoked with its report operation and the
predecessor's parameters
**When** it runs
**Then** the report is produced

#### Scenario: Happy path — the task-regeneration operation is accepted

**Given** the checkpoint tool invoked with its task-regeneration operation
**When** it runs
**Then** the tasks are regenerated, or, without the writing modifier, the tool
reports whether the existing tasks already match

#### Scenario: Adversarial — a modifying operation on the record set is refused for what it is

**Given** the record tool invoked with an operation that would modify an existing
record, with any parameters
**When** it runs
**Then** the operation is refused naming that the record set is append-only, before
its parameters are examined

#### Scenario: Error path — an unknown operation is rejected naming it

**Given** either tool invoked with an operation name neither accepts
**When** it runs
**Then** the name is reported and the exit status is the finding status

## Properties (Ring 3)

### Property: record-round-trips-all-present-fields

**Invariant**: For every record, writing it and reading it back yields a record
whose every present field, required and optional, is unchanged.

**Generator strategy**: `genLedgerRecord` — constructive: all ten required fields
generated within their contract domains, plus each of the five optional fields
independently present or absent. Hedgehog `cover`: `all-optional-present` ≥ 15%,
`no-optional-present` ≥ 15%, `some-optional-present` ≥ 40%, `review-ring` ≥ 10%.

```
forAll { (r: LedgerRecordWithOptional) =>
  read(write(r)) == Right(r)
}
```

### Property: self-observed-records-are-distinguishable

**Invariant**: For every command execution, the record the observing path writes
carries observation fields, and the record the assertion path writes does not.

**Generator strategy**: `genCommandExecution` — constructive: generates a command
from a pool of deterministic shell commands with known outcomes (true, false,
exit with a generated code), paired with a generated record key. No filtering.
Hedgehog `cover`: `success` ≥ 30%, `failure` ≥ 30%, `nonzero-nonone-code` ≥ 15%.

```
forAll { (ex: CommandExecution) =>
  hasObservationFields(runAndRecord(ex)) &&
  !hasObservationFields(assertAndRecord(ex))
}
```

### Property: replay-verdict-is-total-and-sound

**Invariant**: Every record receives exactly one replay verdict, and a matching
verdict is returned only when the re-run outcome equals the recorded outcome.

**Generator strategy**: `genReplayFixture` — constructive: records whose commands
are drawn from a pool with deterministic outcomes, with the recorded outcome
generated to either agree or disagree with the command's actual outcome, plus
records on judgment rings. Hedgehog `cover`: `agrees` ≥ 30%, `disagrees` ≥ 30%,
`judgment-ring` ≥ 20%.

```
forAll { (fx: ReplayFixture) =>
  val v = replay(fx.record)
  (v == ReplayVerdict.Matches) ==> (rerun(fx.record.command) == fx.record.exit)
}
```

### Property: checkpoint-reports-every-requested-ring

**Invariant**: For every requested ring set and record set, the report names every
requested ring exactly once, each either evidenced or stated unevidenced.

**Generator strategy**: `genCheckpointInputs` — constructive: a requested ring
subset drawn from the known ring set (sizes 1–6), a record set generated over rings
and baselines chosen independently of the request, and a supplied verdict. Hedgehog
`cover`: `all-rings-evidenced` ≥ 15%, `none-evidenced` ≥ 15%,
`evidence-at-wrong-baseline` ≥ 25%, `requested-ring-unknown` ≥ 10%.

```
forAll { (in: CheckpointInputs) =>
  val r = checkpoint(in)
  in.requestedRings.forall(ring => r.namesExactlyOnce(ring))
}
```

### Property: marker-written-iff-evidenced-and-discharged

**Invariant**: The marker is written if and only if every requested ring is
evidenced at the current baseline and the supplied verdict reports nothing
unresolved.

**Generator strategy**: `genCheckpointInputs` as above, with the two conditions
generated independently so all four combinations occur. Hedgehog `cover`: each of
the four combinations ≥ 15%.

```
forAll { (in: CheckpointInputs) =>
  markerWritten(checkpoint(in)) ==
    (in.allRequestedRingsEvidenced && in.verdict.unresolved.isEmpty)
}
```

### Property: parity-with-predecessor

**Invariant**: For every invocation in the fixture corpus, both tools produce the
predecessor's output and exit status.

**Generator strategy**: `genInvocationFixture` — constructive corpus enumerating
each accepted operation × {minimal valid, missing required parameter, unknown
parameter, modifying operation} for both tools. Model-based: the predecessor script
run as a subprocess is the model. Hedgehog `cover`: each operation ≥ 10%,
`invalid` ≥ 40%.

```
forAll { (inv: InvocationFixture) =>
  runPort(inv).exitStatus == runPredecessor(inv).exitStatus &&
  parseOutput(runPort(inv)) == parseOutput(runPredecessor(inv))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A record encoder that serialises only the required fields while an optional-field value is present | The dropped-optional-fields defect must be unrepresentable, not merely tested | `assertDoesNotCompile` on the ten-field encoder; the encoder takes the joined record type | 
| A modification operation name on the record tool's operation type | Append-only is enforced by there being nothing else to name — the existing argument for the absent mutation commands | `assertDoesNotCompile` in `LedgerParitySpec` |
| A `CheckpointReport` constructed with a marker-written flag set while any requested ring is unevidenced | The marker condition is structural, not a runtime check that can be forgotten | `assertDoesNotCompile` on the raw constructor; construction goes through a smart constructor |
| A correctness computation referenced from the checkpoint module | The layering boundary is structural | `assertDoesNotCompile` in `CheckpointParitySpec` |
| `ReplayVerdict` pattern match omitting a case | A replay result must be handled exhaustively | `assertDoesNotCompile` in `LedgerParitySpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. `LedgerValidatorKernel` already exists in
`probatio-verified`; the marker decision is added. The mirror reduces the
checkpoint inputs to `(requestedRings: List[BigInt], evidencedRings: List[BigInt],
unresolvedCount: BigInt)`.

### Contract: markerDecision

**Precondition** (`require`): `unresolvedCount >= 0`.

**Postcondition** (`ensuring`): the marker is granted if and only if every requested
ring appears in the evidenced list and the unresolved count is zero.

```scala
def markerDecision(requested: List[BigInt], evidenced: List[BigInt],
                   unresolvedCount: BigInt): Boolean = {
  require(unresolvedCount >= 0)
  // pure model
}.ensuring { granted =>
  granted == (requested.forall(r => evidenced.contains(r)) && unresolvedCount == 0)
}
```

**Bridge property test**: `CheckpointBridgeSpec` runs the shipped marker decision
and the mirror on the same generated inputs.

**Delegated to Ring 3**: record round-tripping, replay soundness, and predecessor
parity are delegated — all three quantify over strings and process outcomes, which
have no PureScala model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Every present field round-trips | Requirement: A record written by the observing path carries the fields that make it self-observed + Property: record-round-trips-all-present-fields | Ring 4 round-trip property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/LedgerRecordRoundTripSpec.scala` |
| Observation fields are never silently dropped | Scenario: Adversarial — the observation fields are not silently dropped + Compile-Negative: A record encoder that serialises only the required fields | type change (joined record) + compile-negative test + scenario test | `LedgerRecord` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/LedgerRecord.scala`; `LedgerParitySpec` |
| Self-observed and written-assertion records are distinguishable | Property: self-observed-records-are-distinguishable | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerParitySpec.scala` |
| A mixed record set reads cleanly | Scenario: Edge case — a record set mixing both shapes reads cleanly | Ring 4 fixture test against the existing mixed-shape fixture | `openspec/schemas/verified-scala3/tests/fixtures/evidence-ledger-v1.jsonl` via `LedgerRecordRoundTripSpec` |
| Persisted records satisfy the record contract | Requirement: A record written by the observing path carries the fields that make it self-observed | Ring 4 contract-conformance test executing the record contract checker | `openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq` via `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerCmdConformanceSpec.scala` (existing suite, extended) |
| Every record receives exactly one replay verdict, and matching implies agreement | Requirement: A recorded run can be replayed and its outcome compared + Property: replay-verdict-is-total-and-sound | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerParitySpec.scala` |
| A diverging replay does not exit clean | Scenario: Adversarial — a record whose command now yields a different outcome reports diverging | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerParitySpec.scala` |
| A judgment-ring record is unreplayable and does not fail the run | Scenario: Edge case — a record whose discharge is a human judgment is unreplayable | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerParitySpec.scala` |
| A replay verdict is handled exhaustively | Compile-Negative: ReplayVerdict pattern match omitting a case | type system (exhaustiveness escalated to error) + compile-negative test | `ReplayVerdict` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/ReplayVerdict.scala`; `LedgerParitySpec` |
| Every requested ring is named exactly once | Requirement: The checkpoint is generated from recorded evidence, never authored + Property: checkpoint-reports-every-requested-ring | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointParitySpec.scala` |
| A ring with no records is reported unevidenced, not green | Scenario: Adversarial — a ring with no records is reported unevidenced, not green | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointParitySpec.scala` |
| A superseded-baseline record does not evidence a ring | Scenario: Edge case — a record at a superseded baseline does not evidence the ring | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointParitySpec.scala` |
| An unknown ring name is rejected, not reported unevidenced | Scenario: Error path — a requested ring outside the known set is rejected naming it | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/CheckpointCmdSpec.scala` |
| A same-session review record is reported as not independently produced | Scenario: Adversarial — a review-ring record produced by the implementing session is reported as such | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointParitySpec.scala` |
| The supplied verdict is reported unchanged and never recomputed | Requirement: The checkpoint does not recompute the correctness verdict + Compile-Negative: A correctness computation referenced from the checkpoint module | compile-negative test + scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointParitySpec.scala` |
| An unparseable verdict is could-not-determine | Scenario: Error path — a verdict that does not parse is could-not-determine | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/CheckpointCmdSpec.scala` |
| The marker is written exactly when evidenced and discharged | Requirement: The presentation marker is written only when every requested ring is evidenced and the verdict is fully discharged + Property: marker-written-iff-evidenced-and-discharged + Contract: markerDecision | property test + smart constructor + formal contract (Ring 6) + bridge test | `CheckpointParitySpec`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/LedgerValidatorKernel.scala`; `workflow/core/src/test/scala/org/sinemenda/probatio/core/CheckpointBridgeSpec.scala` |
| A marker cannot be flagged written while a ring is unevidenced | Compile-Negative: A CheckpointReport constructed with a marker-written flag set while any requested ring is unevidenced | smart constructor + compile-negative test | `CheckpointReport` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/CheckpointReport.scala`; `CheckpointParitySpec` |
| Both tools accept the predecessor's operations and parameters | Requirement: The record and checkpoint tools accept the predecessor's operation and parameter set + Property: parity-with-predecessor | model-based property test against the predecessor as a subprocess | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerCheckpointParitySpec.scala` |
| A modifying operation is refused for what it is, before parameters are parsed | Scenario: Adversarial — a modifying operation on the record set is refused for what it is + Compile-Negative: A modification operation name on the record tool's operation type | type system (operation absent from the enum) + compile-negative test + scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerParitySpec.scala` |
| An unknown operation is rejected naming it | Scenario: Error path — an unknown operation is rejected naming it | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/LedgerCheckpointParitySpec.scala` |
| Both tools' bats files stay green through the swap | Property: parity-with-predecessor | differential oracle run (see the cutover-gate spec) | `evidence-ledger.bats`, `evidence-capture.bats`, `checkpoint-from-ledger.bats`, `judgment-ring-provenance.bats`, `judgment-ring-integrity.bats` under the differential harness — these are currently at zero failures and must stay there |
| No record field, operation, or verdict was added or altered relative to the predecessor | Property: parity-with-predecessor | adversarial review (Ring 8), fresh context | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `LedgerRecord` | final case class | `workflow/core/src/main/scala/org/sinemenda/probatio/core/LedgerRecord.scala` | joined with `LedgerRecordOptional`; the encoder serialises every present field |
| `LedgerCmd` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | gains the replay operation and the three missing parameters; the observing path stamps the observation fields |
| `CheckpointEngine` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/CheckpointEngine.scala` | pure: takes requested rings, records, and the supplied verdict as values |
| `CheckpointCmd` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | gains the report and task-regeneration operations; delegates record filtering to the record tool's own read path |
| `LedgerValidatorKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/LedgerValidatorKernel.scala` | extended with the marker decision |
| `scanner/ledger.sh`, `scanner/checkpoint.sh` | reference implementations | `openspec/schemas/verified-scala3/scanner/` | still the live tools at the start of this spec; the models for the parity property. Their shims are swapped only under the cutover gate |
| `scanner/ledger-record-contract.jq` | wire contract | `openspec/schemas/verified-scala3/scanner/` | the single statement of the record format; Ring 4 executes it |
| `sbt "probatio-core/test" "probatio-cli/test"` | build step | both modules | Ring 3 and Ring 4 |
