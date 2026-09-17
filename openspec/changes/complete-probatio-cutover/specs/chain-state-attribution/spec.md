# Spec: Chain-State Attribution

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Schema | The correctness verdict — bound, resolved, discharged — is the schema's central definition; this spec makes the ported computation able to reach it | [schema.md](../../../../concepts/schema.md) |
| `Strangler` (Strangler Migration Protocol) | Chain-state is the first swapped seam; its parity is the swap's acceptance criterion | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |

This spec does not alter either concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `ChainState` | object | `org.sinemenda.probatio.core` |
| `ChainState.Requirement` | final case class | `org.sinemenda.probatio.core` |
| `ChainStateReport` | final case class | `org.sinemenda.probatio.core` |
| `ChainStateUndetermined` | final case class | `org.sinemenda.probatio.core` |
| `UnresolvedEntry` | final case class | `org.sinemenda.probatio.core` |
| `UnresolvedReason` | enum (Unbound, Unresolved, Undischarged, Unattributable, Failed) | `org.sinemenda.probatio.core` |
| `UnmappedObligation` | final case class | `org.sinemenda.probatio.core` |
| `LintReport` | final case class | `org.sinemenda.probatio.core` |
| `Ledger.LedgerData` | final case class | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class | `org.sinemenda.probatio.core` |
| `Ring` | enum | `org.sinemenda.probatio.core` |
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |
| `SpecDocument` | final case class | `org.sinemenda.probatio.core` (introduced by the spec-lint-engine spec) |
| `ObligationRow` | final case class | `org.sinemenda.probatio.core` (introduced by the spec-lint-engine spec) |
| `ObligationSource` | enum | `org.sinemenda.probatio.core` (introduced by the spec-lint-engine spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `RequirementSet` | final case class | The requirements of a change, extracted once from its spec documents, each carrying its owning spec name and title |
| `FactSource` | enum (`Graph`, `Degraded`) | Which extraction path produced the requirement set — the transitive graph extractor, or the in-process fallback. Reported, never inferred |

## ADDED Requirements

### Requirement: The correctness verdict is computed over the change's actual requirements

The computation SHALL derive its requirement set from the change's spec documents,
and SHALL NOT report a verdict computed over an empty requirement set as though
the change had no requirements.

**Given** a change whose spec documents declare one or more requirements
**When** the verdict is computed
**Then** the reported total equals the number of requirements declared, and the
unresolved list is computed against those requirements

**Rationale**: The ported computation passes an empty requirement list, so its
total is always zero, its unresolved list is always empty, and every change reports
fully discharged. A verdict structurally incapable of finding anything is the
purest form of a claim outrunning its evidence.

#### Scenario: Happy path — a change with declared requirements reports a nonzero total

**Given** a change with two spec documents declaring three requirements between them
**When** the verdict is computed
**Then** the reported total is three

#### Scenario: Error path — a change directory with no readable spec documents is could-not-determine

**Given** a change directory containing no spec document
**When** the verdict is computed
**Then** the result is could-not-determine, naming that no spec document was found,
and the exit status is the could-not-determine status — not a clean zero

#### Scenario: Adversarial — a genuinely empty requirement set is distinguishable from an unread one

**Given** a change with one spec document that declares no requirements
**When** the verdict is computed
**Then** the reported total is zero AND the result is clean, which is
distinguishable from the could-not-determine result the unreadable case produces

### Requirement: A requirement whose obligations cannot be attributed is never counted as discharged

The computation SHALL report as unresolved, with the unattributable reason, any
requirement that the lint pass reports reachable but for which no obligation row
resolves by exact title, and SHALL NOT count such a requirement as resolved or
discharged.

**Given** a requirement the lint pass reports reachable, and an obligation table in
which no row's source resolves to that requirement's exact title
**When** the verdict is computed
**Then** the requirement appears in the unresolved list with the unattributable
reason

**Rationale**: The predecessor records this as the exact defect it discovered
inside the tool built to prevent it: an empty attribution loop never lowers a
flag that defaults to set, so a requirement with zero attributable rows reports as
fully discharged. The ported computation has the reason code but never emits it.

#### Scenario: Adversarial — a reachable-but-unattributable requirement is not discharged

**Given** a requirement reported reachable by an ordinal obligation source, and no
row naming it by exact title
**When** the verdict is computed
**Then** that requirement is in the unresolved list with the unattributable reason,
and the discharged count does not include it

#### Scenario: Happy path — a requirement named by exact title and backed by a recorded run is discharged

**Given** a requirement named by exact title in one obligation row, and a ledger
row at the current baseline whose obligation text matches
**When** the verdict is computed
**Then** that requirement is not in the unresolved list and the discharged count
includes it

#### Scenario: Edge case — a ledger row at a superseded baseline does not discharge

**Given** a requirement named by exact title, and a ledger row whose baseline
differs from the current baseline
**When** the verdict is computed
**Then** that requirement is in the unresolved list with the undischarged reason

### Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed

The computation SHALL report in the unmapped-obligations list every obligation row
that carries a finding and whose source resolves to no requirement title, and SHALL
NOT attribute such a row to any requirement.

**Given** an obligation row whose source is an ordinal, a non-requirement typed
reference, or a dangling reference, and which carries a finding
**When** the verdict is computed
**Then** the row appears in the unmapped-obligations list with its source text, and
no requirement's counts change because of it

**Rationale**: Silently dropping an unmappable row makes a change look cleaner
than the evidence supports; silently attributing it to the nearest requirement
makes a different requirement look worse than the evidence supports. Both are
fabrications; the predecessor reports the row separately and this spec preserves
that.

#### Scenario: Adversarial — an ordinal-sourced finding is not attributed to the requirement at that ordinal

**Given** an obligation row whose source is the ordinal for the second requirement,
carrying an artifact-resolution finding
**When** the verdict is computed
**Then** the row appears in the unmapped-obligations list, and the second
requirement's unresolved reasons do not include that finding

#### Scenario: Happy path — a mappable finding is attributed to its requirement

**Given** an obligation row naming a requirement by exact title and carrying an
artifact-resolution finding
**When** the verdict is computed
**Then** that requirement appears unresolved with the unresolved reason, and the
unmapped-obligations list is empty

#### Scenario: Edge case — the unmapped-obligations list is present and empty when nothing is unmappable

**Given** a change in which every obligation row resolves by exact title
**When** the verdict is computed
**Then** the report carries an unmapped-obligations field holding an empty list —
the field is not omitted

### Requirement: The extraction path used for the requirement set is reported

The computation SHALL report which extraction path produced its requirement set,
and when the transitive extractor is unavailable SHALL state that the fallback was
used rather than reporting the fallback's result as though the extractor had run.

**Given** a run of the computation
**When** it reports its result
**Then** the report or the diagnostic channel names the extraction path used

**Rationale**: The predecessor's fact extraction was unified onto a single
extractor precisely so that three parallel parsers could not disagree; its degraded
mode is announced so a reader knows which parser produced the numbers. A silent
fallback reintroduces the disagreement it removed.

#### Scenario: Happy path — the transitive extractor is available and is named

**Given** the transitive extractor is present and runnable
**When** the computation runs
**Then** the diagnostic channel names the transitive extractor as the source

#### Scenario: Error path — the extractor is unavailable and the fallback is announced

**Given** the transitive extractor is not runnable
**When** the computation runs
**Then** the diagnostic channel states that the fallback path was used, and the
result is still produced

#### Scenario: Adversarial — a fallback result is never reported as an extractor result

**Given** the transitive extractor is not runnable
**When** the computation runs
**Then** the diagnostic channel does not name the transitive extractor as the source

### Requirement: A could-not-determine result states its reason exactly once

The computation SHALL emit the reason text exactly once in the report and exactly
once in the diagnostic line, with no repeated prefix, when it cannot determine a
verdict.

**Given** a condition under which the verdict cannot be determined
**When** the computation reports it
**Then** the report's reason field and the diagnostic line each contain the
could-not-determine marker at most once

**Rationale**: The ported tool currently emits the marker twice — once from the
file-reading layer and once from the reporting layer — producing a diagnostic line
that does not match the predecessor's byte for byte. Observed 2026-08-29 by
invoking the built binary against a missing ledger.

#### Scenario: Error path — a missing ledger produces a single-marker diagnostic

**Given** a ledger path that does not exist
**When** the computation runs
**Then** the diagnostic line names the missing path with the could-not-determine
marker appearing exactly once

#### Scenario: Happy path — the report is still emitted on the output channel when undetermined

**Given** any could-not-determine condition
**When** the computation runs
**Then** a report is emitted on the output channel carrying the change, the
baseline, the could-not-determine flag, the reason, and null counts

## Properties (Ring 3)

### Property: verdict-parity-with-predecessor

**Invariant**: For every change fixture, the emitted report equals the
predecessor's emitted report after parsing both as structured data.

**Generator strategy**: `genChangeFixture` — constructive, drawn from a fixed
corpus of change directories built from combinations of {0,1,3} spec documents ×
{0,1,5} requirements × {empty, matching, stale-baseline, corrupt} ledgers. Model-based:
the predecessor script is the model, run as a subprocess. Hedgehog `cover`:
`clean` ≥ 20%, `has-unresolved` ≥ 30%, `undetermined` ≥ 20%, `has-unmapped` ≥ 10%.

```
forAll { (fx: ChangeFixture) =>
  parseReport(engine.run(fx)) == parseReport(runPredecessor(fx))
}
```

### Property: counts-are-consistent

**Invariant**: For every input, `discharged <= resolved <= bound <= total`, and the
unresolved list's length equals `total - discharged`.

**Generator strategy**: `genComputeInputs` — constructive: generates a requirement
list (0–10), a lint report whose verdicts are generated independently per
requirement (including requirements with no verdict at all), and a ledger whose
rows are generated to match a chosen subset of requirements at a chosen subset of
baselines. No filtering. Hedgehog `cover`: `all-discharged` ≥ 10%,
`none-discharged` ≥ 15%, `mixed` ≥ 40%, `requirement-with-no-verdict` ≥ 20%.

```
forAll { (lint: LintReport, ledger: LedgerData, reqs: List[Requirement], b: String, c: String) =>
  ChainState.compute(lint, ledger, reqs, b, c) match
    case Right(r) =>
      r.discharged <= r.resolved && r.resolved <= r.bound && r.bound <= r.total &&
      r.unresolved.length == r.total - r.discharged
    case Left(_) => true
}
```

### Property: unattributable-is-reachable-and-never-discharged

**Invariant**: For every input containing at least one requirement that the lint
report marks reachable and for which no obligation row resolves by exact title,
that requirement appears unresolved with the unattributable reason.

**Generator strategy**: `genComputeInputs` restricted constructively — the
generator builds the unattributable case directly (choose a requirement, mark it
reachable in the lint report, emit obligation rows whose sources are only ordinals
or dangling typed references) rather than filtering for it. Hedgehog `cover`:
`has-unattributable` ≥ 60% (this generator is built to produce it).

```
forAll { (in: ComputeInputs) =>
  val r = ChainState.compute(in.lint, in.ledger, in.reqs, in.baseline, in.change)
  in.reachableButUnattributable.forall { req =>
    r.exists(_.unresolved.exists(u => u.requirement == req.requirement &&
                                      u.reasons.contains(UnresolvedReason.Unattributable)))
  }
}
```

### Property: obligation-rows-are-conserved

**Invariant**: Every obligation row carrying a finding is either attributed to
exactly one requirement or listed once in the unmapped-obligations report. No row
is dropped and none is counted twice.

**Generator strategy**: `genObligationRows` — constructive: rows whose sources are
drawn from `{exact title, ordinal, typed-existing, typed-dangling, bare}`, each
independently carrying or not carrying a finding. Hedgehog `cover`: each source
kind ≥ 12%.

```
forAll { (rows: List[ObligationRow], reqs: List[Requirement]) =>
  val r = attribute(rows, reqs)
  rows.filter(_.hasFinding).size == r.attributedCount + r.unmapped.size
}
```

### Property: empty-is-not-unreadable

**Invariant**: A genuinely empty input and an unreadable input never produce the
same result.

**Generator strategy**: `genEmptyVsUnreadable` — constructive pair generator
producing, for each seed, one change with zero requirements and a readable empty
ledger, and one change with the same shape but an unreadable ledger. Hedgehog
`cover`: both arms 50% by construction.

```
forAll { (pair: (ChangeFixture, ChangeFixture)) =>
  engine.run(pair._1).exitStatus != engine.run(pair._2).exitStatus
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ChainState.compute(lint, ledger, Nil, baseline, change)` where the requirement list is a literal `Nil` supplied by a CLI entrypoint | The empty-requirements call is the defect; the entrypoint must pass an extracted `RequirementSet`, whose emptiness is a fact rather than a placeholder | `assertDoesNotCompile` in `ChainStateAttributionSpec` (the parameter type changes from `List[Requirement]` to `RequirementSet`) |
| A `ChainStateReport` constructed with a `discharged` count exceeding its `total` | An impossible count must be unrepresentable, not merely untested | `assertDoesNotCompile` on the raw constructor; construction goes through a smart constructor |
| `UnresolvedEntry` with an empty `reasons` list | An unresolved requirement with no stated reason is a claim without evidence | `assertDoesNotCompile` in `ChainStateAttributionSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. `ChainStateKernel` already exists in
`probatio-verified` and is extended here. The mirror reduces a requirement to an
index and a verdict to `0 = unbound, 1 = bound, 2 = resolved`, and a ledger to the
set of discharged indices.

### Contract: chainStateFold

**Precondition** (`require`): every verdict code is in `[0, 2]`; every discharged
index is in `[0, total)`.

**Postcondition** (`ensuring`): the counts are monotone
(`discharged <= resolved <= bound <= total`); the unresolved index list and the
discharged index set are exact complements within `[0, total)`; and no index that
is reachable-but-unattributable appears in the discharged set.

```scala
def chainStateFold(total: BigInt, verdicts: List[BigInt],
                   discharged: List[BigInt], unattributable: List[BigInt]):
    (BigInt, BigInt, BigInt, List[BigInt]) = {
  require(verdicts.forall(v => v >= 0 && v <= 2) &&
          discharged.forall(i => i >= 0 && i < total) &&
          unattributable.forall(i => i >= 0 && i < total))
  // pure model
}.ensuring { case (bound, resolved, dis, unresolved) =>
  dis <= resolved && resolved <= bound && bound <= total &&
  unresolved.length == total - dis &&
  unattributable.forall(i => !discharged.contains(i) || unresolved.contains(i))
}
```

**Bridge property test**: `ChainStateBridgeSpec` (extended) runs the shipped
`ChainState.compute` and the kernel on the same generated inputs.

**Delegated to Ring 3**: predecessor parity and the extraction-path reporting are
delegated — neither has a PureScala model.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The verdict is computed over extracted requirements, not an empty list | Requirement: The correctness verdict is computed over the change's actual requirements + Compile-Negative: ChainState.compute with a literal Nil requirement list | type change (`RequirementSet` parameter) + compile-negative test | `ChainState` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainState.scala`; `ChainStateAttributionSpec` |
| A change with unreadable specs is could-not-determine, not clean zero | Scenario: Error path — a change directory with no readable spec documents is could-not-determine + Property: empty-is-not-unreadable | property test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateCmdSpec.scala` |
| A genuinely empty requirement set is distinguishable from an unread one | Scenario: Adversarial — a genuinely empty requirement set is distinguishable from an unread one | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateCmdSpec.scala` |
| A reachable-but-unattributable requirement is never discharged | Requirement: A requirement whose obligations cannot be attributed is never counted as discharged + Property: unattributable-is-reachable-and-never-discharged | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ChainStateAttributionSpec.scala` |
| A stale-baseline ledger row does not discharge | Scenario: Edge case — a ledger row at a superseded baseline does not discharge | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ChainStateAttributionSpec.scala` |
| Every finding-carrying obligation row is attributed exactly once or reported unmapped | Requirement: An obligation that maps to no known requirement is reported separately, never dropped and never misattributed + Property: obligation-rows-are-conserved | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ChainStateAttributionSpec.scala` |
| An ordinal-sourced finding is not attributed to the requirement at that ordinal | Scenario: Adversarial — an ordinal-sourced finding is not attributed to the requirement at that ordinal | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ChainStateAttributionSpec.scala` |
| The unmapped-obligations field is present even when empty | Scenario: Edge case — the unmapped-obligations list is present and empty when nothing is unmappable | contract-conformance test against the report contract | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateCmdConformanceSpec.scala` (existing suite, extended) |
| Counts are monotone and the unresolved list is the exact complement | Property: counts-are-consistent + Contract: chainStateFold | property test + formal contract (Ring 6) + bridge test | `ChainStateAttributionSpec`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/ChainStateKernel.scala`; `ChainStateBridgeSpec` |
| Impossible counts and reasonless unresolved entries are unrepresentable | Compile-Negative: ChainStateReport with discharged exceeding total + Compile-Negative: UnresolvedEntry with an empty reasons list | smart constructor + compile-negative tests | `ChainStateReport`, `UnresolvedEntry` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainStateReport.scala`; `ChainStateAttributionSpec` |
| The extraction path is reported and a fallback is never presented as the extractor | Requirement: The extraction path used for the requirement set is reported + Scenario: Adversarial — a fallback result is never reported as an extractor result | scenario tests | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateCmdSpec.scala` |
| The could-not-determine marker appears exactly once | Requirement: A could-not-determine result states its reason exactly once + Scenario: Error path — a missing ledger produces a single-marker diagnostic | scenario test executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ChainStateCmdSpec.scala` |
| The emitted report conforms to the report contract byte-wise | Property: verdict-parity-with-predecessor | Ring 4 contract-conformance test executing the report contract checker | `openspec/schemas/verified-scala3/scanner/chain-state-report-contract.jq` via `ChainStateCmdConformanceSpec` |
| The chain-state bats file reaches parity with the predecessor control | Requirement: The correctness verdict is computed over the change's actual requirements | differential oracle run (see the cutover-gate spec) | `openspec/schemas/verified-scala3/tests/chain-state.bats` and `discharge-fidelity.bats` under the differential harness |
| No verdict was altered relative to the predecessor | Property: verdict-parity-with-predecessor | adversarial review (Ring 8), fresh context | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `ChainState.compute` | method | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ChainState.scala` | requirement parameter changes to `RequirementSet`; gains attribution and unmapped-obligation logic |
| `RequirementExtractor` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/RequirementExtractor.scala` | builds a `RequirementSet` from parsed `SpecDocument`s (from the spec-lint-engine spec) |
| `ChainStateCmd` | object | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | reads spec documents, calls the extractor, stops passing `Nil`; gains `--format`, `--spec`, `--artifacts`, `--forgive-unchanged` |
| `SubcommandWiring.readLedgerFile` | method | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandWiring.scala` | stops embedding the could-not-determine marker in its reason string (the caller adds it) |
| `StdoutRenderer[ChainStateReport]` | given instance | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/StdoutRenderer.scala` | snake-case field names already correct; extended for the new fields |
| `ChainStateKernel` | Stainless object | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/ChainStateKernel.scala` | extended with the unattributable clause |
| `scanner/openspec-graph.py` | reference extractor | `openspec/schemas/verified-scala3/scanner/` | remains python3; invoked as a subprocess for the `Graph` extraction path (out of scope to port — see the proposal) |
| `scanner/chain-state.sh.predecessor.bak` | reference implementation | `openspec/schemas/verified-scala3/scanner/` | the model for the parity property |
| `sbt "probatio-core/test" "probatio-cli/test"` | build step | both modules | Ring 3 |
