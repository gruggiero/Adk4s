package org.sinemenda.probatio.core

import upickle.default.*

/** The append-only ledger module (R-C2).
  *
  * Exposes only `read`, `append`, and `validate` — no `update`, `delete`, or
  * `rewrite` function is defined. The forbidden operations are unconstructible,
  * not denylisted.
  *
  * spec: probatio-core — Requirement: The ledger is append-only at the type level
  * spec: probatio-core — Compile-Negative: no update/delete/rewrite
  */
object Ledger:

  /** An immutable, append-only ledger of records. */
  final case class LedgerData(records: List[LedgerRecord] = List.empty):
    /** The number of records in the ledger. */
    def length: Int = records.length

  /** Read all records from the ledger in append order.
    *
    * spec: probatio-core — Scenario: read returns records in append order
    */
  def read(ledger: LedgerData): List[LedgerRecord] =
    ledger.records

  /** Append a record to the end of the ledger without altering any prior
    * record. Returns a new ledger (immutable).
    *
    * spec: probatio-core — Scenario: appending preserves all prior records
    * spec: probatio-core — Property: Append-only ledger round-trips every record
    */
  def append(ledger: LedgerData, record: LedgerRecord): LedgerData =
    LedgerData(ledger.records :+ record)

  /** Validate a JSON value against the 12-clause contract before appending.
    * Delegates to `LedgerRecord.from`.
    *
    * spec: probatio-core — Requirement: The ledger is append-only at the type level
    */
  def validate(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    Validator.validate(json)

  /** Construct a ledger from an existing list of records (for testing). */
  def fromRecords(records: List[LedgerRecord]): LedgerData =
    LedgerData(records)

  /** uPickle ReadWriter for LedgerData (Ring 4 wire compatibility). */
  given ReadWriter[LedgerData] = readwriter[ujson.Value].bimap(
    (l: LedgerData) => ujson.Arr(l.records.map(r => writeJs(r))*),
    {
      case ujson.Arr(arr) =>
        LedgerData(arr.toList.map(v => upickle.default.read[LedgerRecord](v)))
      case other =>
        sys.error(s"expected JSON array for ledger, got: $other")
    }
  )
