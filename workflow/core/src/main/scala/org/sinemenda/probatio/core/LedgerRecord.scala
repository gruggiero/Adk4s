package org.sinemenda.probatio.core

import upickle.default.*

/** The ported ledger record ADT.
  *
  * All fields are required (no optional fields in the core type). Optional
  * fields from the jq contract (sha256, digest, wallTime, source, session)
  * are modeled on the optional companion type `LedgerRecordOptional`.
  *
  * Validation of every contract clause is a total function returning
  * `Either[ContractViolation, LedgerRecord]` (R-C1). The smart constructor
  * `LedgerRecord.from` validates all 12 clauses; the raw constructor is
  * private.
  *
  * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
  */
final case class LedgerRecord private[core] (
  v: Int,
  ts: String,
  change: String,
  spec: String,
  ring: Ring,
  obligation: String,
  artifact: String,
  command: String,
  exit: Int,
  baseline: String
)

/** Optional fields from the jq contract (D3 evidence-capture + provenance).
  * Present in `run`-mode rows, absent in legacy `append` rows. Both shapes
  * are valid; these are checked only if present. */
final case class LedgerRecordOptional(
  sha256: Option[String] = None,
  digest: Option[String] = None,
  wallTime: Option[Int] = None,
  source: Option[String] = None,
  session: Option[String] = None
)

object LedgerRecord:

  /** Smart constructor — validates all 12 clauses and returns either the
    * validated record or the first contract violation.
    *
    * spec: probatio-core — Scenario: a record satisfying all 12 clauses is accepted
    * spec: probatio-core — Scenario: the validator is total — a null input is rejected
    */
  def from(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    Validator.validate(json)

  /** The list of required field names, in contract order. */
  val requiredFields: List[String] =
    List("v", "ts", "change", "spec", "ring", "obligation", "artifact", "command", "exit", "baseline")

  /** uPickle ReadWriter for JSON round-trip (Ring 4).
    * Manual because the constructor is private — macroRW cannot derive. */
  given ReadWriter[LedgerRecord] = readwriter[ujson.Value].bimap(
    (r: LedgerRecord) => ujson.Obj(
      "v" -> ujson.Num(r.v),
      "ts" -> ujson.Str(r.ts),
      "change" -> ujson.Str(r.change),
      "spec" -> ujson.Str(r.spec),
      "ring" -> ujson.Str(Ring.asString(r.ring)),
      "obligation" -> ujson.Str(r.obligation),
      "artifact" -> ujson.Str(r.artifact),
      "command" -> ujson.Str(r.command),
      "exit" -> ujson.Num(r.exit),
      "baseline" -> ujson.Str(r.baseline)
    ),
    (v: ujson.Value) => Validator.validate(v) match
      case Right(r) => r
      case Left(err) => sys.error(s"invalid ledger record: ${err.description}")
  )

  given ReadWriter[Ring] = readwriter[ujson.Value].bimap(
    (r: Ring) => ujson.Str(Ring.asString(r)),
    {
      case ujson.Str(s) => Ring.fromString(s) match
        case Some(r) => r
        case None    => sys.error(s"invalid ring: $s")
      case other     => sys.error(s"expected ring string, got: $other")
    }
  )
