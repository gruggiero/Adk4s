# Spec: Danger and Reconcile Engines

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| Schema | The dangerous-pattern scan is the mechanical half of the schema's first verification ring; corroboration is how a recorded run stops being testimony | [schema.md](../../../../concepts/schema.md) |
| `Strangler` (Strangler Migration Protocol) | Both tools are swapped seams; their parity is the swap's acceptance criterion | [strangler-migration-protocol.md](../../../../concepts/strangler-migration-protocol.md) |

This spec does not alter either concept's actions, state, or synchronizations. No
concept file update is required.

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Outcome[+A]` | enum | `org.sinemenda.probatio.core` |
| `LedgerRecord` | final case class | `org.sinemenda.probatio.core` |
| `Ledger.LedgerData` | final case class | `org.sinemenda.probatio.core` |
| `Ring` | enum | `org.sinemenda.probatio.core` |
| `Validator` | object | `org.sinemenda.probatio.core` |
| `ContractViolation` | sealed trait | `org.sinemenda.probatio.core` |
| `SubcommandWiring` | object | `org.sinemenda.probatio.cli` |
| `StdoutRenderer[A]` | trait | `org.sinemenda.probatio.cli` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `DangerPattern` | enum (8 cases) | The named classes of dangerous construct the scan reports: unchecked extraction, unchecked collection access, catch-all match arm, cast or unchecked annotation, blocking call, swallowed error, unreachability claim, lint suppression |
| `DangerHit` | final case class | One reported occurrence: file, line, pattern, the matched text, and whether it carries a justification |
| `DangerReport` | final case class | The scan result: the hits reported and the count of occurrences excluded by justification |
| `Corroboration` | enum (`SelfObserved`, `Witnessed`, `Testimony`, `Contradicted`, `Exempt`) | How a recorded run's outcome is supported: observed by the recorder itself, confirmed by an independent observer, asserted by the writer alone, contradicted by an observer, or exempt from corroboration |
| `ReconcileReport` | final case class | The per-row corroboration classification for a change |

## ADDED Requirements

### Requirement: The dangerous-pattern scan examines the production files changed since the baseline

The scan SHALL examine every production source file changed since the given
baseline, plus any additional files named by the caller, and SHALL report every
occurrence of a dangerous pattern in them.

**Given** a baseline and a working tree with changed production files
**When** the scan runs
**Then** every dangerous-pattern occurrence in those files is reported with its
file, line, and pattern name

**Rationale**: The ported tool returns clean without opening a file. The first
verification ring's mechanical half is therefore currently unenforceable: a
change can contain any dangerous construct and the ring still reports green.

#### Scenario: Happy path — an unjustified occurrence is reported

**Given** a production file changed since the baseline containing a catch-all match
arm with no justification comment
**When** the scan runs
**Then** the occurrence is reported with its file, line, and the catch-all pattern
name, and the exit status is the finding status

#### Scenario: Happy path — a justified occurrence is excluded

**Given** the same file where the catch-all arm carries a same-line justification
comment
**When** the scan runs
**Then** the occurrence is not reported, and the count of justified exclusions
includes it

#### Scenario: Adversarial — a clean result is not produced without examining files

**Given** a baseline and a changed production file containing an unjustified
dangerous construct
**When** the scan runs
**Then** the exit status is the finding status — a clean status is not produced

#### Scenario: Error path — an unresolvable baseline is could-not-determine

**Given** a baseline reference that does not resolve in the repository
**When** the scan runs
**Then** the result is could-not-determine naming the unresolved reference, and the
exit status is the could-not-determine status

#### Scenario: Edge case — no changed production files reports clean with a stated scope

**Given** a baseline against which no production file has changed
**When** the scan runs
**Then** the exit status is clean and the output states that no production file was
in scope

#### Scenario: Edge case — test files are not in scope unless named by the caller

**Given** a baseline against which only test files have changed, each containing
unjustified dangerous constructs
**When** the scan runs with no additional files named
**Then** no occurrence is reported

### Requirement: The scan's baseline is optional and defaults to the working tree

The scan SHALL accept the baseline as an optional positional argument and SHALL
default to the current working tree when none is given.

**Given** an invocation with no baseline argument
**When** the scan runs
**Then** the baseline is the current working tree and the scan proceeds

**Rationale**: The predecessor is invoked positionally with a default; the ported
tool requires a named parameter and rejects the invocation the workflow's own
documentation and hooks use.

#### Scenario: Happy path — no argument scans the working tree

**Given** an invocation with no arguments and an uncommitted change containing an
unjustified dangerous construct
**When** the scan runs
**Then** the occurrence is reported

#### Scenario: Happy path — a positional baseline is accepted

**Given** an invocation whose only argument is a revision
**When** the scan runs
**Then** the scan is taken against that revision

#### Scenario: Error path — an unknown named parameter is rejected naming the token

**Given** an invocation carrying a parameter name the scan does not accept
**When** the scan runs
**Then** the offending token is named and the exit status is the finding status

### Requirement: A recorded run whose outcome only its writer asserted is reported as uncorroborated

Every recorded green run on a ring that has a deterministic command SHALL be
classified by how its outcome was observed, and a run with no independent observer
SHALL be reported as testimony.

**Given** a set of recorded runs for a change
**When** corroboration is computed
**Then** each run is classified as self-observed, witnessed, testimony,
contradicted, or exempt

**Rationale**: A record asserting a green run and a record of one are byte-for-byte
identical unless something else saw it. The ported tool reports nothing at all, so
every backfilled record currently reads as corroborated.

#### Scenario: Happy path — a self-observed run needs no witness

**Given** a recorded run carrying the recorder's own observation of the command's
outcome
**When** corroboration is computed
**Then** it is classified self-observed and does not appear as testimony

#### Scenario: Happy path — a written record with a matching independent observation is witnessed

**Given** a written record of a green run, and an independently observed record for
the same change, spec, ring, and baseline carrying the same outcome
**When** corroboration is computed
**Then** the written record is classified witnessed

#### Scenario: Adversarial — a written green record with no observer is testimony

**Given** a written record of a green run on a ring with a deterministic command,
and no independently observed record at that key
**When** corroboration is computed
**Then** the record is classified testimony and the exit status is the finding
status

#### Scenario: Adversarial — an observer disagreeing with the written outcome is contradicted, reported separately

**Given** a written record of a green run, and an independently observed record at
the same key carrying a different outcome
**When** corroboration is computed
**Then** the record is classified contradicted and reported separately from
testimony

#### Scenario: Edge case — a judgment ring is exempt

**Given** a written record on a ring whose discharge is a human judgment rather
than a deterministic command
**When** corroboration is computed
**Then** the record is classified exempt

#### Scenario: Edge case — a record of a failing run needs no witness

**Given** a written record whose outcome is a failure
**When** corroboration is computed
**Then** the record is not classified testimony, because it discharges nothing

#### Scenario: Error path — an unreadable record set is could-not-determine

**Given** a record set that cannot be read or does not parse
**When** corroboration is computed
**Then** the result is could-not-determine, and the exit status is the
could-not-determine status

### Requirement: Corroboration does not decide whether an obligation is discharged

The corroboration tool SHALL report only whether a record's outcome has support
beyond its writer's assertion, and SHALL NOT report a discharge verdict.

**Given** any record set
**When** corroboration is computed
**Then** the output contains no discharge verdict for any requirement

**Rationale**: Discharge is the correctness computation's decision. Two tools
answering the same question is how the predecessor's three parallel parsers came to
disagree; the boundary is stated so the port does not reintroduce it.

#### Scenario: Adversarial — the output carries no discharge verdict

**Given** a record set in which every record is witnessed
**When** corroboration is computed
**Then** the output reports the classifications and does not state that any
requirement is discharged

## Properties (Ring 3)

### Property: danger-parity-with-predecessor

**Invariant**: For every fixture tree, the set of reported occurrences (file, line,
pattern) equals the predecessor's.

**Generator strategy**: `genDangerFixture` — constructive: materialises a small
source tree from a pool of snippets, one per pattern class, each independently
justified or not, placed in production or test paths, with a generated baseline.
Model-based: the predecessor script run as a subprocess is the model. Hedgehog
`cover`: each pattern class ≥ 8%, `justified` ≥ 30%, `test-path-only` ≥ 10%,
`no-changes` ≥ 5%.

```
forAll { (fx: DangerFixture) =>
  engine.scan(fx).hits.map(h => (h.file, h.line, h.pattern)).toSet ==
    runPredecessor(fx).hits.map(h => (h.file, h.line, h.pattern)).toSet
}
```

### Property: justification-excludes-exactly-its-own-occurrence

**Invariant**: Adding a justification comment to one occurrence removes exactly
that occurrence from the report and changes no other.

**Generator strategy**: `genDangerFixturePair` — constructive: generates a fixture
with at least two unjustified occurrences (by construction, not by filtering), then
produces a second fixture identical except that one chosen occurrence carries a
justification. Hedgehog `cover`: `same-file-both-occurrences` ≥ 30%,
`different-files` ≥ 30%.

```
forAll { (pair: (DangerFixture, DangerFixture)) =>
  val before = engine.scan(pair._1).hits.toSet
  val after  = engine.scan(pair._2).hits.toSet
  before.diff(after).size == 1 && after.subsetOf(before)
}
```

### Property: corroboration-is-total-and-exclusive

**Invariant**: Every record receives exactly one classification, and the classes
partition the record set.

**Generator strategy**: `genRecordSet` — constructive: generates records over
independently chosen change, spec, ring, baseline, outcome, and observation kind
(written / self-observed / independently-observed), with observed records generated
to match or mismatch a chosen subset of written ones. Hedgehog `cover`:
`has-testimony` ≥ 25%, `has-contradiction` ≥ 15%, `has-witnessed` ≥ 25%,
`has-exempt` ≥ 15%, `has-self-observed` ≥ 20%.

```
forAll { (rs: RecordSet) =>
  val r = reconcile(rs)
  r.classifications.size == rs.size &&
  rs.forall(rec => r.classOf(rec).isDefined)
}
```

### Property: witness-requires-key-agreement

**Invariant**: A written record is classified witnessed only when an independently
observed record exists at the same change, spec, ring, and baseline, carrying the
same outcome.

**Generator strategy**: `genRecordSet` as above, with the key-mismatch case
generated directly (an observed record at a deliberately perturbed key). Hedgehog
`cover`: `observer-at-wrong-key` ≥ 25%, `observer-at-right-key-same-outcome` ≥ 25%,
`observer-at-right-key-different-outcome` ≥ 20%.

```
forAll { (rs: RecordSet) =>
  reconcile(rs).witnessed.forall { w =>
    rs.observed.exists(o => o.key == w.key && o.outcome == w.outcome)
  }
}
```

### Property: no-discharge-verdict-in-output

**Invariant**: For every record set, the rendered output contains no discharge
verdict token.

**Generator strategy**: `genRecordSet` as above. Hedgehog `cover`:
`all-witnessed` ≥ 15% — the case where a naive implementation is most tempted to
conclude discharge.

```
forAll { (rs: RecordSet) =>
  !containsDischargeVerdict(render(reconcile(rs)))
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| A `DangerReport` constructed with an empty hit list and a hit count greater than zero | A report whose summary and contents disagree is the shape a stubbed scan produces | `assertDoesNotCompile` on the raw constructor; construction goes through a smart constructor | 
| `Corroboration.Witnessed` constructed without the observing record | A witness classification with no witness is testimony wearing the wrong label | `assertDoesNotCompile` in `ReconcileEngineSpec` |
| A discharge verdict type referenced from the corroboration module | The boundary between corroboration and discharge is structural, not conventional | `assertDoesNotCompile` in `ReconcileEngineSpec` |
| `DangerPattern` pattern match omitting a case | A new pattern class must force every match to be updated | `assertDoesNotCompile` in `DangerScanEngineSpec` |

## Formal Contracts (Ring 6)

Route: **verified mirror**. The corroboration classification is a decision fold and
is modelled. The mirror reduces a record to
`(keyIndex, outcomeCode, observationKind)` and returns a classification code.

### Contract: corroborationFold

**Precondition** (`require`): every observation kind is in `[0, 2]`; every outcome
code is `>= 0`.

**Postcondition** (`ensuring`): every record receives exactly one classification;
a written green record is classified witnessed only if an observed record shares its
key and outcome; a written green record with no observed record at its key is
classified testimony.

```scala
def corroborationFold(records: List[(BigInt, BigInt, BigInt)]): List[BigInt] = {
  require(records.forall { case (_, o, k) => o >= 0 && k >= 0 && k <= 2 })
  // pure model: classification code per record
}.ensuring { cls =>
  cls.length == records.length &&
  records.zip(cls).forall { case ((key, out, kind), c) =>
    (c == WITNESSED) ==> records.exists { case (k2, o2, kind2) =>
      kind2 == OBSERVED && k2 == key && o2 == out } &&
    ((kind == WRITTEN && out == 0 &&
      !records.exists { case (k2, _, kind2) => kind2 == OBSERVED && k2 == key })
       ==> c == TESTIMONY)
  }
}
```

**Bridge property test**: `ReconcileBridgeSpec` runs the shipped classifier and the
mirror on the same generated record sets.

**Delegated to Ring 3**: the dangerous-pattern scan is delegated entirely — it is
text matching over source files, with no PureScala model;
`danger-parity-with-predecessor` covers it.

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Reported occurrences match the predecessor on every fixture | Requirement: The dangerous-pattern scan examines the production files changed since the baseline + Property: danger-parity-with-predecessor | model-based property test against the predecessor as a subprocess | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DangerScanParitySpec.scala` |
| An unjustified occurrence is reported and yields the finding status | Scenario: Happy path — an unjustified occurrence is reported | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DangerScanEngineSpec.scala` |
| A clean status is not produced while an unjustified occurrence exists | Scenario: Adversarial — a clean result is not produced without examining files | scenario test executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/DangerScanCmdSpec.scala` |
| A justification excludes exactly its own occurrence | Scenario: Happy path — a justified occurrence is excluded + Property: justification-excludes-exactly-its-own-occurrence | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DangerScanEngineSpec.scala` |
| An unresolvable baseline is could-not-determine | Scenario: Error path — an unresolvable baseline is could-not-determine | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/DangerScanCmdSpec.scala` |
| An empty scope reports clean and states its scope | Scenario: Edge case — no changed production files reports clean with a stated scope | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/DangerScanCmdSpec.scala` |
| Test files are out of scope unless named | Scenario: Edge case — test files are not in scope unless named by the caller | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/DangerScanEngineSpec.scala` |
| A report whose summary contradicts its contents is unrepresentable | Compile-Negative: A DangerReport constructed with an empty hit list and a hit count greater than zero | smart constructor + compile-negative test | `DangerReport` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/DangerScanEngine.scala`; `DangerScanEngineSpec` |
| A new pattern class forces every match to be updated | Compile-Negative: DangerPattern pattern match omitting a case | type system (exhaustiveness escalated to error) + compile-negative test | `DangerPattern` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/DangerScanEngine.scala`; `DangerScanEngineSpec` |
| The predecessor's positional invocation is accepted | Requirement: The scan's baseline is optional and defaults to the working tree + Scenario: Happy path — a positional baseline is accepted | scenario tests executing the built artifact | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/DangerScanCmdSpec.scala` |
| An unknown parameter is rejected naming the token | Scenario: Error path — an unknown named parameter is rejected naming the token | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/DangerScanCmdSpec.scala` |
| Every record is classified exactly once | Requirement: A recorded run whose outcome only its writer asserted is reported as uncorroborated + Property: corroboration-is-total-and-exclusive | property test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ReconcileEngineSpec.scala` |
| A written green record with no observer is testimony | Scenario: Adversarial — a written green record with no observer is testimony + Contract: corroborationFold | property test + formal contract (Ring 6) + bridge test | `ReconcileEngineSpec`; `verified/probatio/src/main/scala/org/sinemenda/probatio/core/ReconcileKernel.scala`; `workflow/core/src/test/scala/org/sinemenda/probatio/core/ReconcileBridgeSpec.scala` |
| A disagreeing observer yields contradicted, reported separately | Scenario: Adversarial — an observer disagreeing with the written outcome is contradicted, reported separately | scenario test | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ReconcileEngineSpec.scala` |
| A witness classification requires an observer at the same key with the same outcome | Property: witness-requires-key-agreement + Compile-Negative: Corroboration.Witnessed constructed without the observing record | property test + smart constructor + compile-negative test | `Corroboration` in `workflow/core/src/main/scala/org/sinemenda/probatio/core/ReconcileEngine.scala`; `ReconcileEngineSpec` |
| A judgment ring and a failing run are exempt from corroboration | Scenario: Edge case — a judgment ring is exempt + Scenario: Edge case — a record of a failing run needs no witness | scenario tests | `workflow/core/src/test/scala/org/sinemenda/probatio/core/ReconcileEngineSpec.scala` |
| An unreadable record set is could-not-determine | Scenario: Error path — an unreadable record set is could-not-determine | scenario test | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ReconcileCmdSpec.scala` |
| No discharge verdict appears in the corroboration output | Requirement: Corroboration does not decide whether an obligation is discharged + Scenario: Adversarial — the output carries no discharge verdict + Property: no-discharge-verdict-in-output + Compile-Negative: A discharge verdict type referenced from the corroboration module | property test + compile-negative test | `ReconcileEngineSpec` |
| The corroboration tool accepts the predecessor's parameter set | Requirement: A recorded run whose outcome only its writer asserted is reported as uncorroborated | scenario test executing the built artifact with the predecessor's parameters | `workflow/cli/src/test/scala/org/sinemenda/probatio/cli/ReconcileCmdSpec.scala` |
| Both tools' bats files reach parity with the predecessor control | Requirement: The dangerous-pattern scan examines the production files changed since the baseline | differential oracle run (see the cutover-gate spec) | `ambient-capture-wiring.bats` and `discharge-fidelity.bats` under the differential harness |
| No pattern class was added, removed, or re-scoped relative to the predecessor | Property: danger-parity-with-predecessor | adversarial review (Ring 8), fresh context, comparing the pattern set against the predecessor's documented list | Ring 8 review record in `implementation-progress.md` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `DangerScanEngine` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/DangerScanEngine.scala` | pure: takes file contents and paths as values, returns a `DangerReport` |
| `ReconcileEngine` | object (new) | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ReconcileEngine.scala` | pure: takes a record set as values, returns a `ReconcileReport` |
| `DangerScanCmd`, `ReconcileCmd` | objects | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/SubcommandEntrypoints.scala` | replace the `Ran(0)` stubs; both gain the predecessor's parameter surfaces |
| `ChangedFilesReader` | object (new) | `workflow/cli/src/main/scala/org/sinemenda/probatio/cli/ChangedFilesReader.scala` | resolves the baseline and enumerates changed production files; the only file-reading component for the scan |
| `ReconcileKernel` | Stainless object (new) | `verified/probatio/src/main/scala/org/sinemenda/probatio/core/ReconcileKernel.scala` | corroboration fold mirror |
| `scanner/danger-scan.sh.predecessor.bak`, `scanner/reconcile.sh.predecessor.bak` | reference implementations | `openspec/schemas/verified-scala3/scanner/` | the models for the parity properties |
| `stryker4s.conf` | tool config | repository root | must be retargeted to these two engines for Ring 5; `break = 0` means the score is read, not inferred from the exit code |
| `sbt "probatio-core/test" "probatio-cli/test"` | build step | both modules | Ring 3 |
