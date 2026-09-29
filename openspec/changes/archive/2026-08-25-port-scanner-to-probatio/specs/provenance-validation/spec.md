# Spec: Provenance Validation

<!-- DELTA spec for the `provenance-validation` capability of the
     `port-scanner-to-probatio` change.

     This spec closes a gap discovered during the port-scanner-to-probatio
     change: the ported `Validator` (probatio-core) checks only the 12
     required-field clauses of the ledger record contract, but the jq
     contract has 15 enforcement clauses. The 3 omitted clauses are:

       Clause 12: optional-field type checks (sha256, digest, wallTime)
       Clause 13: observer provenance (source must be "ambient" when present)
       Clause 14: session provenance (adversarial-review ring rows MUST carry session; non-R8
                  rows MAY carry it; when present it must be a non-empty
                  string)

     The probatio-core spec classified all three as "optional-fields
     validation ... NOT modeled — it is Ring 3" (spec line 1056). That
     classification is correct for clauses 12–13's type checks (truly
     optional fields checked only if present), but WRONG for the R8
     session requirement: it is a mandatory conditional, not an optional
     field. An adversarial-review ring row missing `session` is rejected by the jq contract
     with the same force as a missing required field — it is never
     accepted silently.

     The defect manifested in production: five rows in the
     port-scanner-to-probatio evidence-ledger.jsonl were hand-written
     without `session` (adversarial-review ring rows) or without `v`/`obligation`/`artifact`/
     `baseline` (R3 rows), causing chain-state.sh to exit 2 (UNDETERMINED).
     The bash scanner's `ledger.sh read` caught them on read because it
     runs the full jq contract. The ported `Validator.validate` would NOT
     have caught the R8-session-missing rows, because it does not check
     that clause.

     This spec adds the 3 provenance clauses to the ported validator,
     lifts the 12-variant cap on ContractViolation to 15, and requires
     the CLI `ledger append` entrypoint to validate before writing.

     WRITING RULES (enforced by spec-lint):
     - Every requirement opens with a normative statement containing SHALL or
       MUST (required by `openspec validate --strict`), followed by Given/When/Then
     - Every Then must be observable; every scenario testable
     - Every error path specified
     - No vague words without a concrete definition next to them
     - ADVERSARIAL RULE: every requirement containing "only", "never", or
       "must not" needs at least one scenario whose INPUT the requirement forbids
     - Properties use Hedgehog (NOT ScalaCheck) per the detected stack
       (capability-profile.md: hedgehog 0.13.1, "NOT ScalaCheck") -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| `LedgerRecord` | The record being validated; extended to carry optional provenance fields | `workflow/core/src/main/scala/org/sinemenda/probatio/core/LedgerRecord.scala` |
| `ContractViolation` | The disjoint sum of clause failures; extended from 12 to 15 variants | `workflow/core/src/main/scala/org/sinemenda/probatio/core/ContractViolation.scala` |
| `Validator` | The total validation function; extended to check clauses 12–14 | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Validator.scala` |
| `Ring` | The closed ring domain; R8 triggers the session requirement | `workflow/core/src/main/scala/org/sinemenda/probatio/core/Ring.scala` |

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| (none) | — | — |

> R-ARCH1: probatio is a leaf by construction; this spec introduces no
> new adk4s-side dependencies.

## Concepts Introduced (new)

> **COMMITMENT**: the following concepts SHALL be introduced by this spec and
> registered in `openspec/concept-inventory.md` during apply Step 12.

| Concept | Kind | Description |
|---------|------|-------------|
| `ProvenanceFields` | immutable case class | The optional provenance fields from the jq contract: `sha256: Option[String]`, `digest: Option[String]`, `wallTime: Option[Int]`, `source: Option[String]`, `session: Option[String]`. Carried alongside `LedgerRecord` as a companion value, not embedded in the core record type (which remains 10 required fields only). |
| `ValidatedRecord` | immutable case class | A `LedgerRecord` paired with its `ProvenanceFields`, produced by the extended validator when all 15 clauses pass. The core `LedgerRecord` is unchanged; provenance is layered on top. |

## ADDED Requirements

### Requirement: The validator SHALL check all 15 contract clauses, not 12

The system SHALL validate ledger records against all 15 enforcement clauses of the ledger record contract, not only the 12 required-field clauses. Clauses 12–14 SHALL be: (12) optional-field type checks — `sha256` must be a string when present, `digest` must be a string when present, `wallTime` must be an integer when present; (13) observer provenance — `source` must be the string `"ambient"` when present, and no other value is accepted; (14) session provenance — an adversarial-review ring row MUST carry a `session` field that is a non-empty string, a non-adversarial-review ring row MAY carry `session` as a non-empty string, and a `session` that is present but not a non-empty string is rejected. The validation result SHALL be either a `ValidatedRecord` (all 15 clauses pass) or a `ContractViolation` naming exactly one of the 15 clauses as the first failure. The validator SHALL remain total — it never throws, returns null, or silently accepts a record that violates a clause.

**Given** a candidate ledger record as a JSON value, with or without optional provenance fields
**When** the validator is applied
**Then** the result is either a `ValidatedRecord` (carrying the `LedgerRecord` and `ProvenanceFields`) or a `ContractViolation` naming exactly one of the 15 clauses, and the validator never throws, returns null, or silently accepts a record that violates a clause

**Rationale**: The jq contract has 15 enforcement clauses, not 12. The
original probatio-core spec classified clauses 12–14 as "optional-fields
validation ... NOT modeled" and capped `ContractViolation` at 12 variants.
That classification conflated truly optional type checks (sha256, digest,
wallTime — checked only if present) with a mandatory conditional (adversarial-review ring rows
MUST carry session — rejected if absent). The defect manifested in
production: adversarial-review ring rows hand-written without `session` caused chain-state to
exit UNDETERMINED. The bash scanner caught them because `ledger.sh read`
runs the full jq contract; the ported validator did not because it skipped
clauses 12–14 entirely. A validator that silently accepts a record the
contract rejects is the exact "corrupt ledger reads as clean" defect class
the whole schema exists to avert.

#### Scenario: an adversarial-review ring row with a valid session is accepted

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R8"`, and a `session` field set to `"devin-cli-session-r8"`
**When** the validator is applied
**Then** the result is a `ValidatedRecord` whose `ProvenanceFields.session` is `Some("devin-cli-session-r8")`

#### Scenario: an adversarial-review ring row missing session is rejected (adversarial)

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R8"`, and no `session` field
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the session-provenance clause with a description containing "session is required for R8"

#### Scenario: an adversarial-review ring row with an empty session is rejected (adversarial)

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R8"`, and a `session` field set to `""` (empty string)
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the session-provenance clause with a description containing "session must be a non-empty string"

#### Scenario: a non-adversarial-review ring row without session is accepted

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R3"`, and no `session` field
**When** the validator is applied
**Then** the result is a `ValidatedRecord` whose `ProvenanceFields.session` is `None`

#### Scenario: a non-adversarial-review ring row with a valid session is accepted

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R3"`, and a `session` field set to `"devin-cli-session-abc"`
**When** the validator is applied
**Then** the result is a `ValidatedRecord` whose `ProvenanceFields.session` is `Some("devin-cli-session-abc")`

#### Scenario: a row with source set to a non-ambient value is rejected (adversarial)

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, and a `source` field set to `"manual"` (not `"ambient"`)
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the observer-provenance clause with a description containing "source must be \"ambient\""

#### Scenario: a row with source set to ambient is accepted

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, and a `source` field set to `"ambient"`
**When** the validator is applied
**Then** the result is a `ValidatedRecord` whose `ProvenanceFields.source` is `Some("ambient")`

#### Scenario: a row with a non-string sha256 is rejected (adversarial)

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, and a `sha256` field set to `42` (a number, not a string)
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the optional-field-type clause with a description containing "sha256 must be a string"

#### Scenario: a row with a non-integer wallTime is rejected (adversarial)

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, and a `wallTime` field set to `1.5` (not an integer)
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the optional-field-type clause with a description containing "wallTime must be an integer"

#### Scenario: a row with all optional fields valid is accepted

**Given** a JSON object with all 10 required fields conforming to clauses 0–11, `ring` set to `"R3"`, `sha256` set to a hex string, `digest` set to a hex string, `wallTime` set to `15000`, `source` set to `"ambient"`, `session` set to `"devin-cli-session-xyz"`
**When** the validator is applied
**Then** the result is a `ValidatedRecord` whose `ProvenanceFields` carries all five optional fields as `Some`

#### Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)

**Given** a JSON null value
**When** the validator is applied
**Then** the result is a `ContractViolation` naming the not-a-json-object clause, and no exception is thrown

### Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause

The `ContractViolation` sealed trait SHALL have exactly 15 variants: the existing 12 (clauses 0–11) plus 3 new variants for clauses 12–14. The 3 new variants SHALL be: `OptionalFieldTypeInvalid` (clause 12 — an optional field present with a wrong type), `ObserverProvenanceInvalid` (clause 13 — `source` present but not `"ambient"`), `SessionProvenanceInvalid` (clause 14 — adversarial-review ring row missing `session`, or any row with `session` that is not a non-empty string). Each variant SHALL carry a `clauseIndex` (12, 13, or 14 respectively) and a `description`. A 16th variant SHALL be unconstructible — the trait is sealed.

**Given** the `ContractViolation` sealed trait
**When** its variants are enumerated
**Then** exactly 15 variants exist, one per contract clause (0–14), and a 16th variant is unconstructible

**Rationale**: The original spec capped `ContractViolation` at 12 variants
with a compile-negative test for a 13th. That cap was correct for the 12
required-field clauses but excluded the 3 provenance clauses the jq
contract enforces. A validator with 12 variants cannot represent a
session-provenance failure — the failure is unrepresentable, which means
it is silently accepted. Lifting the cap to 15 makes every contract
clause representable as an explicit value.

#### Scenario: all 15 variants are constructible

**Given** the `ContractViolation` sealed trait
**When** each variant is constructed with its clause index
**Then** 15 distinct variants exist with clause indices 0 through 14

#### Scenario: a 16th variant is unconstructible (adversarial)

**Given** an attempt to construct a `ContractViolation` outside the 15 variants
**When** the code is compiled
**Then** compilation fails — the trait is sealed and no 16th variant is defined

### Requirement: The ledger append entrypoint SHALL validate before writing

The CLI `ledger append` entrypoint SHALL validate the candidate record against all 15 contract clauses before writing it to the ledger file. If validation fails, the entrypoint SHALL reject the record with a `Finding` outcome (exit 1) naming the violating clause, and SHALL NOT write the record to the file. If validation succeeds, the entrypoint SHALL append the record and return `Ran(0)` (exit 0). The entrypoint SHALL NOT write a record that fails validation under any circumstance — there is no bypass flag, no force option, and no silent-accept path.

**Given** a `ledger append` invocation with a candidate record as JSON
**When** the entrypoint processes the append
**Then** the record is validated against all 15 clauses; if any clause fails, the record is not written and the outcome is `Finding` naming the clause; if all pass, the record is appended and the outcome is `Ran(0)`

**Rationale**: The original defect occurred because rows were hand-written
directly to the JSONL file, bypassing `ledger.sh run`'s write-time
validation. The bash scanner mitigated this by running the full jq
contract on read (`ledger.sh read`). The ported CLI must enforce
validation at write time — the entrypoint is the gate. But write-time
validation alone does not prevent direct file append; the validator must
ALSO catch malformed rows on read, so that rows written by any path
(hand-written, legacy `append`, or a future buggy writer) are caught
when chain-state reads the ledger. This requirement covers write-time;
the read-time coverage is the next requirement.

#### Scenario: a valid record is appended successfully

**Given** a `ledger append` invocation with a JSON record conforming to all 15 clauses
**When** the entrypoint processes the append
**Then** the record is appended to the ledger file and the outcome is `Ran(0)`

#### Scenario: an adversarial-review ring record missing session is rejected at append (adversarial)

**Given** a `ledger append` invocation with a JSON record whose `ring` is `"R8"` and no `session` field
**When** the entrypoint processes the append
**Then** the record is NOT written to the ledger file, and the outcome is `Finding` naming the session-provenance clause

#### Scenario: a record missing a required field is rejected at append (adversarial)

**Given** a `ledger append` invocation with a JSON record missing the `exit` field
**When** the entrypoint processes the append
**Then** the record is NOT written to the ledger file, and the outcome is `Finding` naming the missing-required-fields clause

#### Scenario: no force flag exists (adversarial)

**Given** the `ledger append` subcommand's flag set
**When** the flags are inspected for a bypass or force option
**Then** no `--force`, `--skip-validation`, or `--allow-invalid` flag exists in the flag set

### Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined

The `Ledger.read` operation (or the CLI layer that reads the ledger file for chain-state) SHALL validate every row against all 15 contract clauses. If any row fails validation, the read SHALL produce an undetermined result naming the violating row and clause — not a clean report with the valid rows, and not a silent skip of the invalid row. A ledger with even one malformed row is undetermined: the chain-state computation cannot trust a ledger that contains a record the contract rejects, because the missing or malformed field may be the one that carries the evidence (e.g., `session` for an adversarial-review ring row).

**Given** a ledger file containing one or more rows
**When** the ledger is read for chain-state computation
**Then** every row is validated against all 15 clauses; if any row fails, the result is undetermined naming the row number and violating clause; if all rows pass, the records are returned in append order

**Rationale**: The bash scanner's `ledger.sh read` runs the full jq
contract on every row and fails the read if any row is malformed. This
is what caught the five malformed rows in the port-scanner-to-probatio
evidence ledger. The ported `Ledger.read` currently uses uPickle's
`ReadWriter[LedgerRecord]`, which calls `Validator.validate` — but only
on the 12 required-field clauses. A row missing `session` (R8) passes
uPickle parsing silently. This requirement closes the read-time gap:
the ported reader must validate all 15 clauses, matching the bash
scanner's behavior. Without this, a malformed row written by any path
(hand-written, legacy append, or a future buggy writer) would be
silently accepted on read, and chain-state would compute a verdict
from a ledger it cannot trust.

#### Scenario: a ledger with all valid rows is read successfully

**Given** a ledger file with 3 rows, all conforming to all 15 clauses
**When** the ledger is read
**Then** 3 records are returned in append order, each carrying its `ProvenanceFields`

#### Scenario: a ledger with a malformed adversarial-review ring row is rejected as undetermined (adversarial)

**Given** a ledger file with 3 rows where row 2 has `ring` set to `"R8"` and no `session` field
**When** the ledger is read
**Then** the result is undetermined with a reason naming row 2 and the session-provenance clause, and the valid rows are NOT returned as a partial result

#### Scenario: a ledger with a malformed optional field is rejected as undetermined (adversarial)

**Given** a ledger file with 3 rows where row 1 has `sha256` set to `42` (a number, not a string)
**When** the ledger is read
**Then** the result is undetermined with a reason naming row 1 and the optional-field-type clause

#### Scenario: an empty ledger is read as zero records, not undetermined

**Given** a ledger file with 0 rows
**When** the ledger is read
**Then** the result is an empty list of records (not undetermined) — an empty ledger is valid, distinct from a malformed one

## Properties (Ring 3)

### Property: validator-conforms-to-jq-contract-in-both-directions-15-clauses

The ported validator SHALL agree with the jq contract (`ledger-record-contract.jq`) on accept/reject and on the first-failing clause index, for all 15 clauses. For any JSON value: if the jq contract accepts it, the ported validator accepts it; if the jq contract rejects it, the ported validator rejects it with the same clause index. This property generalizes the existing 12-clause conformance property to 15 clauses.

**Generator strategy**: `genLedgerRecordJsonWithProvenance` (constructive — extends `genLedgerRecordJson` from probatio-core with optional provenance fields: `sha256` from `Gen.option(Gen.string(Gen.char('0' to 'f'), Range.linear 0 64))` plus non-string values, `digest` from `Gen.option(Gen.string(Gen.char('0' to 'f'), Range.linear 0 64))` plus non-string values, `wallTime` from `Gen.option(Gen.int(Range.linear 0 60000))` plus non-integer values, `source` from `Gen.option(Gen.element1("ambient", "manual", ""))`, `session` from `Gen.option(Gen.string(Gen.alphaNum, Range.linear 0 30))`). Covers each provenance-clause-violating edge independently (R8 missing session, R8 empty session, non-ambient source, non-string sha256, non-integer wallTime) and the all-valid-provenance edge. Classification labels: `valid`, `clause-12-optional-type`, `clause-13-source`, `clause-14-session-r8-missing`, `clause-14-session-r8-empty`, `clause-14-session-nonempty-nonstring`.

```
property("validator conforms to jq contract — all 15 clauses, both directions") {
  for json <- genLedgerRecordJsonWithProvenance.forAll
  yield
    val jqResult     = runJqContract(json)
    val scalaResult  = Validator.validateFull(json)
    (jqResult, scalaResult) match
      case (Right(_), Right(_))   => Result.success
      case (Left(jqIdx), Left(sv)) => Result.assert(sv.clauseIndex == jqIdx)
      case _                       => Result.failure("validator and jq contract disagree")
}
```

### Property: ContractViolation-totality-15-clauses

Every clause index 0–14 is reachable: for each clause, there exists a JSON value that the validator rejects with that clause index and no other. This generalizes the existing 12-clause totality property to 15 clauses.

**Generator strategy**: Enumerated over 15 clause-violating generators, one per clause (constructive — each generator produces a JSON value that violates exactly one clause and satisfies all prior clauses in the elif chain). Clauses 0–11 reuse the existing `genRecordFailingClause` from probatio-core. Clauses 12–14 add three new generators: `genClause12OptionalType` (sets `sha256` to a number, `digest` to a number, or `wallTime` to a non-integer), `genClause13Source` (sets `source` to a string other than `"ambient"`), `genClause14Session` (for adversarial-review ring rows: omits `session` or sets it to empty string; for non-adversarial-review ring rows: sets `session` to a non-string). Covers each clause independently.

```
property("ContractViolation totality — every clause 0-14 is reachable") {
  for clauseIdx <- Gen.int(Range.linear(0, 14)).forAll
  yield
    val json = genRecordFailingClause(clauseIdx)
    val result = Validator.validateFull(json)
    result match
      case Left(v) => Result.assert(v.clauseIndex == clauseIdx)
      case Right(_) => Result.failure(s"clause $clauseIdx was not reached")
}
```

### Property: adversarial-review-ring-session-presence-is-enforced

For any JSON value with `ring` set to `"R8"`: if `session` is absent or empty, the validator rejects it; if `session` is a non-empty string, the validator accepts it (assuming all other clauses pass). This property is the direct encoding of the defect that manifested in production.

**Generator strategy**: `genSessionForR8` (constructive — generates `session` via `Gen.option(Gen.string(Gen.alphaNum, Range.linear 0 20))`, covering three cases: `None` (absent), `Some("")` (empty), `Some("devin-cli-session-xyz")` (valid non-empty). The base record is a valid record with `ring` set to `"R8"` and all other clauses passing. Classification labels: `r8-session-absent`, `r8-session-empty`, `r8-session-valid`.

```
property("adversarial-review ring rows require a non-empty session") {
  for session <- Gen.option(Gen.string(Gen.alphaNum, Range.linear(0, 20))).forAll
  yield
    val json = baseValidRecord(ring = "R8").withSession(session)
    val result = Validator.validateFull(json)
    session match
      case Some(s) if s.nonEmpty => Result.assert(result.isRight)
      case _ => Result.assert(result.isLeft && result.left.get.clauseIndex == 14)
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ContractViolation` with a 16th variant | The 15 clauses are a closed set; a 16th variant would mean the contract and the validator disagree | `assertDoesNotCompile("val v: ContractViolation = new ContractViolation {}")` — the trait is sealed |
| `Ledger.append` that does not call `Validator.validateFull` | Write-time validation is mandatory; an append path that skips validation is the defect class this spec exists to close | code-review gate (Scalafix rule or WartRemover custom wart requiring `validateFull` call in `LedgerCmd.append`) |
| `--force` / `--skip-validation` flag on `ledger append` | There is no bypass for validation; a force flag would reintroduce the silent-accept path | `assertDoesNotCompile("LedgerCmd.append(--force)")` or flag-set enumeration test asserting no bypass flag exists |
| `Ledger.read` that skips validation on any row | Read-time validation is mandatory; skipping a malformed row is the "corrupt ledger reads as clean" defect | code-review gate (Scalafix rule requiring `validateFull` call in the read path) |
| `session` field on `LedgerRecord` (the core type) | Session is a provenance field, not a required field; it lives on `ProvenanceFields`, not on the core record | `assertDoesNotCompile("LedgerRecord(v=1, ..., session=\"x\")")` — the field does not exist on the case class |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| The validator checks all 15 contract clauses | Requirement: The validator SHALL check all 15 contract clauses, not 12 | type system (sealed ContractViolation, 15 variants) + munit tests | ProvenanceValidatorSpec, probatio-core |
| An adversarial-review ring row with a valid session is accepted | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: an adversarial-review ring row with a valid session is accepted | munit test | ProvenanceValidatorSpec |
| An adversarial-review ring row missing session is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: an adversarial-review ring row missing session is rejected (adversarial) | munit test | ProvenanceValidatorSpec |
| An adversarial-review ring row with an empty session is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: an adversarial-review ring row with an empty session is rejected (adversarial) | munit test | ProvenanceValidatorSpec |
| A non-adversarial-review ring row without session is accepted | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a non-adversarial-review ring row without session is accepted | munit test | ProvenanceValidatorSpec |
| A non-adversarial-review ring row with a valid session is accepted | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a non-adversarial-review ring row with a valid session is accepted | munit test | ProvenanceValidatorSpec |
| A row with source set to a non-ambient value is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a row with source set to a non-ambient value is rejected (adversarial) | munit test | ProvenanceValidatorSpec |
| A row with source set to ambient is accepted | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a row with source set to ambient is accepted | munit test | ProvenanceValidatorSpec |
| A row with a non-string sha256 is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a row with a non-string sha256 is rejected (adversarial) | munit test | ProvenanceValidatorSpec |
| A row with a non-integer wallTime is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a row with a non-integer wallTime is rejected (adversarial) | munit test | ProvenanceValidatorSpec |
| A row with all optional fields valid is accepted | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: a row with all optional fields valid is accepted | munit test | ProvenanceValidatorSpec |
| The validator is total — null input is rejected | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Scenario: the validator is total — a null input is rejected, not crashed on (adversarial) | munit test (assertThrows negative — no exception) | ProvenanceValidatorSpec |
| ContractViolation has exactly 15 variants | Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause | type system (sealed trait, 15 variants) + compile-negative test | ContractViolationSpec, probatio-core |
| All 15 variants are constructible | Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause + Scenario: all 15 variants are constructible | munit test | ContractViolationSpec |
| A 16th variant is unconstructible | Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause + Scenario: a 16th variant is unconstructible (adversarial) | compile-negative test (`assertDoesNotCompile`) | ContractViolationSpec |
| The ledger append entrypoint validates before writing | Requirement: The ledger append entrypoint SHALL validate before writing | munit test (assert validateFull called, assert no write on failure) | LedgerCmdSpec, probatio-cli |
| A valid record is appended successfully | Requirement: The ledger append entrypoint SHALL validate before writing + Scenario: a valid record is appended successfully | munit test | LedgerCmdSpec |
| An adversarial-review ring record missing session is rejected at append | Requirement: The ledger append entrypoint SHALL validate before writing + Scenario: an adversarial-review ring record missing session is rejected at append (adversarial) | munit test (assert no file write, assert Finding outcome) | LedgerCmdSpec |
| A record missing a required field is rejected at append | Requirement: The ledger append entrypoint SHALL validate before writing + Scenario: a record missing a required field is rejected at append (adversarial) | munit test (assert no file write, assert Finding outcome) | LedgerCmdSpec |
| No force flag exists | Requirement: The ledger append entrypoint SHALL validate before writing + Scenario: no force flag exists (adversarial) | flag-set enumeration test | LedgerCmdSpec |
| Ledger read validates every row | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined | munit test (assert all rows validated, assert undetermined on malformed) | LedgerReadSpec, probatio-core |
| A ledger with all valid rows is read successfully | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined + Scenario: a ledger with all valid rows is read successfully | munit test | LedgerReadSpec |
| A ledger with a malformed adversarial-review ring row is rejected as undetermined | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined + Scenario: a ledger with a malformed adversarial-review ring row is rejected as undetermined (adversarial) | munit test (assert undetermined, assert no partial result) | LedgerReadSpec |
| A ledger with a malformed optional field is rejected as undetermined | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined + Scenario: a ledger with a malformed optional field is rejected as undetermined (adversarial) | munit test (assert undetermined, assert no partial result) | LedgerReadSpec |
| An empty ledger is read as zero records, not undetermined | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined + Scenario: an empty ledger is read as zero records, not undetermined | munit test | LedgerReadSpec |
| Validator conforms to jq contract — all 15 clauses, both directions | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Property: validator-conforms-to-jq-contract-in-both-directions-15-clauses | Hedgehog property (R-M2 conformance) | ProvenanceConformanceSpec |
| ContractViolation totality — every clause 0-14 is reachable | Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause + Property: ContractViolation-totality-15-clauses | Hedgehog property | ProvenanceValidatorSpec |
| adversarial-review ring rows require a non-empty session | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Property: adversarial-review-ring-session-presence-is-enforced | Hedgehog property | ProvenanceValidatorSpec |
| No 16th ContractViolation variant | Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause + Compile-Negative: ContractViolation with a 16th variant | compile-negative test (`assertDoesNotCompile`) | ContractViolationSpec |
| No append path that skips validation | Requirement: The ledger append entrypoint SHALL validate before writing + Compile-Negative: Ledger.append that does not call Validator.validateFull | code-review gate (Scalafix rule or WartRemover custom wart) | build, adversarial review |
| No force/skip-validation flag | Requirement: The ledger append entrypoint SHALL validate before writing + Compile-Negative: --force / --skip-validation flag on ledger append | flag-set enumeration test | LedgerCmdSpec |
| No read path that skips validation | Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined + Compile-Negative: Ledger.read that skips validation on any row | code-review gate (Scalafix rule) | build, adversarial review |
| No session field on LedgerRecord core type | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Compile-Negative: session field on LedgerRecord (the core type) | compile-negative test (`assertDoesNotCompile`) | LedgerRecordSpec |
| LedgerValidatorKernel totality — 15 clauses (returns a result for every input) | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Formal Contract: LedgerValidatorKernel — extended to 15 clauses | formal contract (Ring 6, Stainless) | LedgerValidatorKernel (verified mirror) |
| LedgerValidatorKernel first-failure ordering — 15 clauses (first failing clause returned) | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Formal Contract: LedgerValidatorKernel — extended to 15 clauses | formal contract (Ring 6, Stainless) | LedgerValidatorKernel (verified mirror) |
| Shipped validator conforms to the verified model — 15 clauses | Requirement: The validator SHALL check all 15 contract clauses, not 12 + Formal Contract: LedgerValidatorKernel — extended to 15 clauses | bridge property (Ring 3 + Ring 6) | LedgerValidatorModelBridgeTests |

## Formal Contracts (Ring 6)

<!-- The existing LedgerValidatorKernel models clauses 0–11 in PureScala.
     This spec extends the validator to 15 clauses. The Ring 6 kernel SHOULD
     be extended to model clauses 12–14 as well, but the session-provenance
     clause (14) involves a conditional (ring == R8) that is already modeled
     in the kernel's Ring enum. The extension is straightforward: add 3 new
     clause indices to the kernel's validation function and 3 new cases to
     the bridge property test.

     If extending the kernel is deferred, the scope note SHALL be updated to
     state which clauses are modeled (0–11 or 0–14) and which are Ring 3 only.
     The bridge property test SHALL cover whichever clauses are modeled. -->

### Contract: LedgerValidatorKernel — extended to 15 clauses

**Mirror name**: `LedgerValidatorKernel`
**Mirror location**: `verified/probatio` leaf (Scala 3.7.2, Stainless)
**Abstraction**: The kernel is extended to model clauses 12–14 in addition to 0–11. The `RecordModel` is extended with optional provenance fields modeled as `Option[String]` / `Option[BigInt]`. The kernel's `validate` function checks all 15 clauses in order, returning the first failing clause index.

**Precondition** (`require`): the input is a `RecordModel` with finite fields.
**Postcondition** (`ensuring`): the result is `Either[BigInt, RecordModel]` — `Left(clauseIndex)` for the first failing clause (0–14), or `Right(record)` if all pass. The function is total.

**Scope note**: If Stainless modeling of the optional-field type checks (clause 12) proves impractical (ujson type discrimination is not in PureScala), clauses 12–13 MAY remain Ring 3 only, but clause 14 (session provenance) SHALL be modeled because it is a mandatory conditional, not an optional type check. The scope note SHALL state which clauses are Ring 6 vs Ring 3.

**Bridge property test**: `LedgerValidatorModelBridgeTests` — extended to run the shipped `Validator.validateFull` and the `LedgerValidatorKernel.validate` on the same generated JSON values (reduced to the model's abstraction) and assert they agree on accept/reject and on the first-failing clause index, for all modeled clauses.
