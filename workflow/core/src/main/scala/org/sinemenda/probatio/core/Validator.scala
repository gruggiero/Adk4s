package org.sinemenda.probatio.core

/**
 * The 15-clause total validator (R-C1, extended by provenance-validation).
 *
 * `validate` checks the 12 required-field clauses (0–11).
 * `validateFull` checks all 15 clauses (0–14), including the optional-field
 * type checks (12), observer provenance (13), and session provenance (14).
 *
 * Both are total functions — they never throw, return null, or silently
 * accept a record that violates a clause. The result is either the
 * validated record or a contract violation naming exactly one clause.
 *
 * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: probatio-core — Property: Validator conforms to the jq contract in both directions
 * spec: probatio-core — Property: ContractViolation totality — every clause is reachable
 * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
 * spec: provenance-validation — Property: validator-conforms-to-jq-contract-in-both-directions-15-clauses
 * spec: provenance-validation — Property: ContractViolation-totality-15-clauses
 * spec: provenance-validation — Property: adversarial-review-ring-session-presence-is-enforced
 */
object Validator:

  /**
   * Validate a JSON value against the 12-clause ledger record contract.
   *
   * spec: probatio-core — Scenario: a record satisfying all 12 clauses is accepted
   * spec: probatio-core — Scenario: the validator is total — a null input is rejected
   */
  def validate(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    // Clause 0: must be a JSON object
    json match
      case obj: ujson.Obj =>
        validateObject(obj)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.NotAJsonObject())

  /**
   * Validate a JSON value against all 15 contract clauses.
   *
   * Clauses 0–11 are the required-field checks (delegated to `validate`).
   * Clauses 12–14 are the provenance checks:
   *   - 12: optional-field type checks (sha256, digest, wallTime)
   *   - 13: observer provenance (source must be "ambient" when present)
   *   - 14: session provenance (R8 rows MUST carry session; all rows
   *         with session must have a non-empty string)
   *
   * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
   * spec: provenance-validation — Scenario: the validator is total — a null input is rejected, not crashed on (adversarial)
   */
  def validateFull(json: ujson.Value): Either[ContractViolation, ValidatedRecord] =
    json match
      case obj: ujson.Obj =>
        validateFullObject(obj)
      case _ => // danger-scan:allow type-rejection — non-object maps to Left(NotAJsonObject), never a valid value
        Left(ContractViolation.NotAJsonObject())

  /**
   * Validate all 15 clauses over a known JSON object.
   * Clauses 0–11 delegate to `validateObject`; if those pass, clauses
   * 12–14 (provenance) are checked.
   */
  private def validateFullObject(obj: ujson.Obj): Either[ContractViolation, ValidatedRecord] =
    validateObject(obj) match
      case Left(violation) => Left(violation)
      case Right(record)   => validateProvenance(obj.value.toMap, record)

  /**
   * Validate clauses 13–14 (provenance) over a map that has passed
   * clauses 0–11. Clause 12 (optional-field types) is already enforced
   * inside `validateObject` by `LedgerRecordOptional.extract` — the
   * record cannot be constructed without it, so a wrong-typed optional
   * field is rejected before this point and is not re-checked here.
   */
  private def validateProvenance(
    fields: Map[String, ujson.Value],
    record: LedgerRecord
  ): Either[ContractViolation, ValidatedRecord] =
    validateObserverProvenance(fields) match
      case Left(v) => Left(v)
      case Right(_) =>
        validateSessionProvenance(fields, record) match
          case Left(v)  => Left(v)
          case Right(_) => Right(ValidatedRecord(record))

  /**
   * Clause 13: observer provenance — source must be "ambient" when present.
   *
   * spec: provenance-validation — Scenario: a row with source set to a non-ambient value is rejected (adversarial)
   * spec: provenance-validation — Scenario: a row with source set to ambient is accepted
   */
  private def validateObserverProvenance(
    fields: Map[String, ujson.Value]
  ): Either[ContractViolation, Unit] =
    // A non-string source never reaches this clause — extraction enforces
    // the field's type before validateObject returns a record.
    fields.get("source") match
      case Some(ujson.Str("ambient")) => Right(())
      case Some(_) =>
        Left(ContractViolation.ObserverProvenanceInvalid(description = "source must be \"ambient\" when present"))
      case None => Right(())

  /**
   * Clause 14: session provenance.
   * - R8 (adversarial-review) rows MUST carry a non-empty string session.
   * - Non-R8 rows MAY carry session; when present it must be a non-empty string.
   *
   * spec: provenance-validation — Scenario: an adversarial-review ring row missing session is rejected (adversarial)
   * spec: provenance-validation — Scenario: an adversarial-review ring row with an empty session is rejected (adversarial)
   * spec: provenance-validation — Scenario: a non-adversarial-review ring row without session is accepted
   * spec: provenance-validation — Property: adversarial-review-ring-session-presence-is-enforced
   */
  private def validateSessionProvenance(
    fields: Map[String, ujson.Value],
    record: LedgerRecord
  ): Either[ContractViolation, Unit] =
    // A non-string session never reaches this clause — extraction
    // enforces the field's type before validateObject returns a record.
    val sessionOpt: Option[ujson.Value] = fields.get("session")
    if record.ring == Ring.R8 then
      sessionOpt match
        case Some(ujson.Str(s)) if s.nonEmpty =>
          Right(())
        case Some(_) =>
          Left(
            ContractViolation.SessionProvenanceInvalid(description = "session must be a non-empty string for R8 rows")
          )
        case None =>
          Left(
            ContractViolation.SessionProvenanceInvalid(
              description = "session is required for R8 (adversarial-review) rows"
            )
          )
    else
      sessionOpt match
        case Some(ujson.Str(s)) if s.nonEmpty =>
          Right(())
        case Some(_) =>
          Left(
            ContractViolation.SessionProvenanceInvalid(description = "session must be a non-empty string when present")
          )
        case None =>
          Right(())

  /** Validate the 12 clauses over a known JSON object. */
  private def validateObject(obj: ujson.Obj): Either[ContractViolation, LedgerRecord] =
    val fields: Map[String, ujson.Value] = obj.value.toMap

    // Clause 1: all required fields present
    val missing: List[String] = LedgerRecord.requiredFields.filter(f => !fields.contains(f))
    if missing.nonEmpty then Left(ContractViolation.MissingRequiredFields(missing))
    else validateFieldTypes(fields)

  /** Validate clauses 2–11 over a map that has all required fields. */
  private def validateFieldTypes(fields: Map[String, ujson.Value]): Either[ContractViolation, LedgerRecord] =
    // Clause 2: v must be an integer >= 1
    fields("v") match
      case num: ujson.Num if isIntegerValue(num) =>
        val v: BigInt = BigDecimal(num.value).toBigInt
        if v < 1 then Left(ContractViolation.VersionInvalid())
        else validateTimestamp(fields, v)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.VersionInvalid())

  /** Clause 3: ts must be a non-empty ISO-8601 UTC string. */
  private def validateTimestamp(fields: Map[String, ujson.Value], v: BigInt): Either[ContractViolation, LedgerRecord] =
    fields("ts") match
      case tsStr: ujson.Str =>
        val ts: String = tsStr.value
        if ts.isEmpty || !isValidTimestamp(ts) then Left(ContractViolation.TimestampInvalid())
        else validateChange(fields, v, ts)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.TimestampInvalid())

  /** Clause 4: change must be a non-empty string without path separators. */
  private def validateChange(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("change") match
      case changeStr: ujson.Str =>
        val change: String = changeStr.value
        if change.isEmpty || hasPathSeparator(change) then Left(ContractViolation.ChangeInvalid())
        else validateSpec(fields, v, ts, change)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.ChangeInvalid())

  /** Clause 5: spec must be a non-empty string without path separators. */
  private def validateSpec(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("spec") match
      case specStr: ujson.Str =>
        val spec: String = specStr.value
        if spec.isEmpty || hasPathSeparator(spec) then Left(ContractViolation.SpecInvalid())
        else validateRing(fields, v, ts, change, spec)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.SpecInvalid())

  /** Clause 6: ring must be in the closed domain. */
  private def validateRing(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("ring") match
      case ringStr: ujson.Str =>
        Ring.fromString(ringStr.value) match
          case Some(ring) =>
            validateObligation(fields, v, ts, change, spec, ring)
          case None =>
            Left(ContractViolation.RingOutsideDomain())
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.RingOutsideDomain())

  /** Clause 7: obligation must be a non-empty string. */
  private def validateObligation(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String,
    ring: Ring
  ): Either[ContractViolation, LedgerRecord] =
    fields("obligation") match
      case obligationStr: ujson.Str =>
        val obligation: String = obligationStr.value
        if obligation.isEmpty then Left(ContractViolation.ObligationEmpty())
        else validateArtifact(fields, v, ts, change, spec, ring, obligation)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.ObligationEmpty())

  /** Clause 8: artifact must be a non-empty string. */
  private def validateArtifact(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String,
    ring: Ring,
    obligation: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("artifact") match
      case artifactStr: ujson.Str =>
        val artifact: String = artifactStr.value
        if artifact.isEmpty then Left(ContractViolation.ArtifactEmpty())
        else validateCommand(fields, v, ts, change, spec, ring, obligation, artifact)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.ArtifactEmpty())

  /** Clause 9: command must be a non-empty string. */
  private def validateCommand(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String,
    ring: Ring,
    obligation: String,
    artifact: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("command") match
      case commandStr: ujson.Str =>
        val command: String = commandStr.value
        if command.isEmpty then Left(ContractViolation.CommandEmpty())
        else validateExit(fields, v, ts, change, spec, ring, obligation, artifact, command)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.CommandEmpty())

  /** Clause 10: exit must be an integer. */
  private def validateExit(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String,
    ring: Ring,
    obligation: String,
    artifact: String,
    command: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("exit") match
      case exitNum: ujson.Num if isIntegerValue(exitNum) =>
        val exit: BigInt = BigDecimal(exitNum.value).toBigInt
        validateBaseline(fields, v, ts, change, spec, ring, obligation, artifact, command, exit)
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.ExitNotInteger())

  /** Clause 11: baseline must be lowercase hex 7-40 chars. */
  private def validateBaseline(
    fields: Map[String, ujson.Value],
    v: BigInt,
    ts: String,
    change: String,
    spec: String,
    ring: Ring,
    obligation: String,
    artifact: String,
    command: String,
    exit: BigInt
  ): Either[ContractViolation, LedgerRecord] =
    fields("baseline") match
      case baselineStr: ujson.Str =>
        val baseline: String = baselineStr.value
        if !isValidBaseline(baseline) then Left(ContractViolation.BaselineInvalid())
        else
          // The record cannot be constructed without its optional group —
          // extraction enforces the TYPE of each present optional field.
          // A wrong-typed optional field is a violation, never a dropped
          // field.
          LedgerRecordOptional.extract(fields) match
            case Left(violation) => Left(violation)
            case Right(optional) =>
              Right(
                LedgerRecord(
                  v = v,
                  ts = ts,
                  change = change,
                  spec = spec,
                  ring = ring,
                  obligation = obligation,
                  artifact = artifact,
                  command = command,
                  exit = exit,
                  baseline = baseline,
                  optional = optional
                )
              )
      case _ => // danger-scan:allow type-rejection — wrong-typed field maps to Left(violation), never a valid value
        Left(ContractViolation.BaselineInvalid())

  /**
   * Check if a ujson Num is an integer value. The jq contract's
   * `(.x | floor) == .x` accepts every integral JSON number — jq numbers
   * are doubles, so the domain is the whole doubles, NOT the Int32
   * range. `isWhole` is exactly that domain: it excludes NaN and the
   * infinities (which JSON cannot express) and imposes no magnitude
   * bound.
   */
  private def isIntegerValue(n: ujson.Num): Boolean =
    n.value.isWhole

  /** Check if a string contains path separators (/ or \). */
  private def hasPathSeparator(s: String): Boolean =
    s.contains('/') || s.contains('\\')

  /**
   * Check if a string is a valid ISO-8601 UTC timestamp. Accepts exactly
   * the contract's fixed shape YYYY-MM-DDTHH:MM:SSZ — the contract has
   * no fractional-seconds form, so the ported validator must not accept
   * one either.
   */
  private val timestampPattern: String =
    """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"""

  private def isValidTimestamp(s: String): Boolean =
    s.matches(timestampPattern)

  /** Check if a string is a valid lowercase hex revision of 7-40 chars. */
  private val baselinePattern: String =
    """^[0-9a-f]{7,40}$"""

  private def isValidBaseline(s: String): Boolean =
    s.matches(baselinePattern)
