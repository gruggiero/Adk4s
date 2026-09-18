package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The ported ledger record ADT.
 *
 * The record is the ten required contract fields joined with the optional
 * observation/provenance group (`LedgerRecordOptional`). The join is a
 * REQUIRED field — a record that cannot state what it observed is
 * unrepresentable, so an encoder that emits only the ten required fields
 * does not compile.
 *
 * Validation of every contract clause is a total function returning
 * `Either[ContractViolation, LedgerRecord]` (R-C1). The smart constructor
 * `LedgerRecord.from` validates the required-field clauses plus the
 * optional-field TYPE checks needed to populate the group; the raw
 * constructor is private.
 *
 * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: ledger-checkpoint-parity — Requirement: The record type SHALL carry the recorder's observation fields as first-class data
 * spec: ledger-checkpoint-parity — Compile-Negative: The encoder writes the ten required fields without joining the optional group
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
  baseline: String,
  optional: LedgerRecordOptional
)

/**
 * Optional fields from the jq contract (D3 evidence-capture + provenance).
 * Present in `run`-mode rows, absent in legacy `append` rows. Both shapes
 * are valid; these are checked only if present.
 */
final case class LedgerRecordOptional(
  sha256: Option[String] = None,
  digest: Option[String] = None,
  wallTime: Option[Int] = None,
  source: Option[String] = None,
  session: Option[String] = None
)

object LedgerRecordOptional:

  /** The empty group — a legacy `append` row observes nothing extra. */
  val empty: LedgerRecordOptional = LedgerRecordOptional()

  /**
   * Extract the optional group from a record's fields, enforcing the
   * TYPE of each present field (the jq contract's checked-if-present
   * rules). A present field of the wrong type is
   * `Left(OptionalFieldTypeInvalid)` — it can never be silently dropped
   * into a record that claims not to carry it. Value-level provenance
   * (`source` must be "ambient", `session` must be non-empty) is checked
   * by the 15-clause validator's clauses 13/14, not here — extraction
   * preserves the value so the full validator can judge it.
   */
  def extract(fields: Map[String, ujson.Value]): Either[ContractViolation, LedgerRecordOptional] =
    stringField(fields, "sha256") match
      case Left(v) => Left(v)
      case Right(sha256) =>
        stringField(fields, "digest") match
          case Left(v) => Left(v)
          case Right(digest) =>
            intField(fields, "wallTime") match
              case Left(v) => Left(v)
              case Right(wallTime) =>
                stringField(fields, "source") match
                  case Left(v) => Left(v)
                  case Right(source) =>
                    stringField(fields, "session") match
                      case Left(v) => Left(v)
                      case Right(session) =>
                        Right(LedgerRecordOptional(sha256, digest, wallTime, source, session))

  private def stringField(
    fields: Map[String, ujson.Value],
    name: String
  ): Either[ContractViolation, Option[String]] =
    fields.get(name) match
      case Some(ujson.Str(s)) => Right(Some(s))
      case Some(_) =>
        Left(
          ContractViolation.OptionalFieldTypeInvalid(description = s"$name must be a string when present")
        )
      case None => Right(None)

  private def intField(
    fields: Map[String, ujson.Value],
    name: String
  ): Either[ContractViolation, Option[Int]] =
    fields.get(name) match
      case Some(num: ujson.Num) if num.value == num.value.floor && num.value.isValidInt =>
        Right(Some(num.value.toInt))
      case Some(_) =>
        Left(
          ContractViolation.OptionalFieldTypeInvalid(description = s"$name must be an integer when present")
        )
      case None => Right(None)

end LedgerRecordOptional

object LedgerRecord:

  /**
   * Smart constructor — validates the required-field clauses plus the
   * optional-field type checks and returns either the validated record
   * or the first contract violation.
   *
   * spec: probatio-core — Scenario: a record satisfying all 12 clauses is accepted
   * spec: probatio-core — Scenario: the validator is total — a null input is rejected
   */
  def from(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    Validator.validate(json)

  /** The list of required field names, in contract order. */
  val requiredFields: List[String] =
    List("v", "ts", "change", "spec", "ring", "obligation", "artifact", "command", "exit", "baseline")

  /**
   * uPickle ReadWriter for JSON round-trip (Ring 4).
   * Manual because the constructor is private — macroRW cannot derive.
   * The encoder serialises every PRESENT optional field; reading is the
   * total validator, so a written row reads back with every field intact.
   *
   * spec: ledger-checkpoint-parity — Requirement: Reading a record SHALL preserve every present field unchanged
   */
  given ReadWriter[LedgerRecord] = readwriter[ujson.Value].bimap(
    (r: LedgerRecord) =>
      val obj: ujson.Obj = ujson.Obj(
        "v"          -> ujson.Num(r.v),
        "ts"         -> ujson.Str(r.ts),
        "change"     -> ujson.Str(r.change),
        "spec"       -> ujson.Str(r.spec),
        "ring"       -> ujson.Str(Ring.asString(r.ring)),
        "obligation" -> ujson.Str(r.obligation),
        "artifact"   -> ujson.Str(r.artifact),
        "command"    -> ujson.Str(r.command),
        "exit"       -> ujson.Num(r.exit),
        "baseline"   -> ujson.Str(r.baseline)
      )
      r.optional.sha256.foreach((s: String) => obj.value("sha256") = ujson.Str(s))
      r.optional.digest.foreach((s: String) => obj.value("digest") = ujson.Str(s))
      r.optional.wallTime.foreach((n: Int) => obj.value("wallTime") = ujson.Num(n.toDouble))
      r.optional.source.foreach((s: String) => obj.value("source") = ujson.Str(s))
      r.optional.session.foreach((s: String) => obj.value("session") = ujson.Str(s))
      obj
    ,
    (v: ujson.Value) =>
      Validator.validate(v) match
        case Right(r)  => r
        case Left(err) => sys.error(s"invalid ledger record: ${err.description}")
  )

  given ReadWriter[Ring] = readwriter[ujson.Value].bimap(
    (r: Ring) => ujson.Str(Ring.asString(r)),
    {
      case ujson.Str(s) =>
        Ring.fromString(s) match
          case Some(r) => r
          case None    => sys.error(s"invalid ring: $s")
      case other => // danger-scan:allow type-rejection — invalid ring crashes, never maps to valid value
        sys.error(s"expected ring string, got: $other")
    }
  )
