package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The append-only ledger module (R-C2).
 *
 * Exposes only `read`, `append`, and `validate` — no `update`, `delete`, or
 * `rewrite` function is defined. The forbidden operations are unconstructible,
 * not denylisted.
 *
 * spec: probatio-core — Requirement: The ledger is append-only at the type level
 * spec: probatio-core — Compile-Negative: no update/delete/rewrite
 * spec: provenance-validation — Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined
 */
object Ledger:

  /** An immutable, append-only ledger of records. */
  final case class LedgerData(records: List[LedgerRecord] = List.empty):
    /** The number of records in the ledger. */
    def length: Int = records.length

  /**
   * Read all records from the ledger in append order.
   *
   * spec: probatio-core — Scenario: read returns records in append order
   */
  def read(ledger: LedgerData): List[LedgerRecord] =
    ledger.records

  /**
   * Read and validate every row against all 15 contract clauses.
   * If any row fails validation, the read produces a `LedgerReadError`
   * naming the violating row and clause — not a clean report with the
   * valid rows, and not a silent skip of the invalid row.
   *
   * spec: provenance-validation — Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined
   * spec: provenance-validation — Scenario: a ledger with all valid rows is read successfully
   * spec: provenance-validation — Scenario: a ledger with a malformed adversarial-review ring row is rejected as undetermined (adversarial)
   * spec: provenance-validation — Scenario: an empty ledger is read as zero records, not undetermined
   */
  def readValidated(rows: List[ujson.Value]): Either[LedgerReadError, List[ValidatedRecord]] =
    readValidatedLoop(rows, index = 0, acc = List.empty)

  /** Tail-recursive loop for readValidated — validates each row in order. */
  private def readValidatedLoop(
    rows: List[ujson.Value],
    index: Int,
    acc: List[ValidatedRecord]
  ): Either[LedgerReadError, List[ValidatedRecord]] =
    rows match
      case Nil => Right(acc.reverse)
      case head :: tail =>
        Validator.validateFull(head) match
          case Left(violation) =>
            Left(LedgerReadError.MalformedRow(rowIndex = index, violation = violation))
          case Right(validated) =>
            readValidatedLoop(tail, index + 1, validated :: acc)

  /**
   * Append a record to the end of the ledger without altering any prior
   * record. Returns a new ledger (immutable).
   *
   * spec: probatio-core — Scenario: appending preserves all prior records
   * spec: probatio-core — Property: Append-only ledger round-trips every record
   */
  def append(ledger: LedgerData, record: LedgerRecord): LedgerData =
    LedgerData(ledger.records :+ record)

  /**
   * Validate a JSON value against the 12-clause contract before appending.
   * Delegates to `LedgerRecord.from`.
   *
   * spec: probatio-core — Requirement: The ledger is append-only at the type level
   */
  def validate(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    Validator.validate(json)

  /**
   * Validate a JSON value against all 15 contract clauses.
   *
   * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
   */
  def validateFull(json: ujson.Value): Either[ContractViolation, ValidatedRecord] =
    Validator.validateFull(json)

  /** Construct a ledger from an existing list of records (for testing). */
  def fromRecords(records: List[LedgerRecord]): LedgerData =
    LedgerData(records)

  /** uPickle ReadWriter for LedgerData (Ring 4 wire compatibility). */
  given ReadWriter[LedgerData] = readwriter[ujson.Value].bimap(
    (l: LedgerData) => ujson.Arr(l.records.map(r => writeJs(r))*),
    {
      case ujson.Arr(arr) =>
        LedgerData(arr.toList.map(v => upickle.default.read[LedgerRecord](v)))
      case other => // danger-scan:allow type-rejection — non-array maps to sys.error, never a valid value
        sys.error(s"expected JSON array for ledger, got: $other")
    }
  )

/**
 * Error from reading a ledger with malformed rows.
 *
 * A ledger with even one malformed row is undetermined: the chain-state
 * computation cannot trust a ledger that contains a record the contract
 * rejects.
 *
 * spec: provenance-validation — Requirement: Ledger read SHALL validate every row and reject malformed ledgers as undetermined
 */
sealed trait LedgerReadError:
  /** Human-readable description of the error. */
  def description: String

object LedgerReadError:
  /** A row that failed validation against the 15-clause contract. */
  final case class MalformedRow(rowIndex: Int, violation: ContractViolation) extends LedgerReadError:
    val description: String =
      s"row $rowIndex failed contract clause ${violation.clauseIndex}: ${violation.description}"
