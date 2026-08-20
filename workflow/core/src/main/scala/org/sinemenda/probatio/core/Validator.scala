package org.sinemenda.probatio.core

/** The 12-clause total validator (R-C1).
  *
  * `validate` is a total function — it never throws, returns null, or
  * silently accepts a record that violates a clause. The result is either
  * the validated record or a contract violation naming exactly one of the
  * 12 clauses.
  *
  * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
  * spec: probatio-core — Property: Validator conforms to the jq contract in both directions
  * spec: probatio-core — Property: ContractViolation totality — every clause is reachable
  */
object Validator:

  /** Validate a JSON value against the 12-clause ledger record contract.
    *
    * spec: probatio-core — Scenario: a record satisfying all 12 clauses is accepted
    * spec: probatio-core — Scenario: the validator is total — a null input is rejected
    */
  def validate(json: ujson.Value): Either[ContractViolation, LedgerRecord] =
    // Clause 0: must be a JSON object
    json match
      case obj: ujson.Obj =>
        validateObject(obj)
      case _ =>
        Left(ContractViolation.NotAJsonObject())

  /** Validate the 12 clauses over a known JSON object. */
  private def validateObject(obj: ujson.Obj): Either[ContractViolation, LedgerRecord] =
    val fields: Map[String, ujson.Value] = obj.value.toMap

    // Clause 1: all required fields present
    val missing: List[String] = LedgerRecord.requiredFields.filter(f => !fields.contains(f))
    if missing.nonEmpty then
      Left(ContractViolation.MissingRequiredFields(missing))
    else
      validateFieldTypes(fields)

  /** Validate clauses 2–11 over a map that has all required fields. */
  private def validateFieldTypes(fields: Map[String, ujson.Value]): Either[ContractViolation, LedgerRecord] =
    // Clause 2: v must be an integer >= 1
    fields("v") match
      case num: ujson.Num if isIntegerValue(num) =>
        val v: Int = num.value.toInt
        if v < 1 then
          Left(ContractViolation.VersionInvalid())
        else
          validateTimestamp(fields, v)
      case _ =>
        Left(ContractViolation.VersionInvalid())

  /** Clause 3: ts must be a non-empty ISO-8601 UTC string. */
  private def validateTimestamp(fields: Map[String, ujson.Value], v: Int): Either[ContractViolation, LedgerRecord] =
    fields("ts") match
      case tsStr: ujson.Str =>
        val ts: String = tsStr.value
        if ts.isEmpty || !isValidTimestamp(ts) then
          Left(ContractViolation.TimestampInvalid())
        else
          validateChange(fields, v, ts)
      case _ =>
        Left(ContractViolation.TimestampInvalid())

  /** Clause 4: change must be a non-empty string without path separators. */
  private def validateChange(
    fields: Map[String, ujson.Value], v: Int, ts: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("change") match
      case changeStr: ujson.Str =>
        val change: String = changeStr.value
        if change.isEmpty || hasPathSeparator(change) then
          Left(ContractViolation.ChangeInvalid())
        else
          validateSpec(fields, v, ts, change)
      case _ =>
        Left(ContractViolation.ChangeInvalid())

  /** Clause 5: spec must be a non-empty string without path separators. */
  private def validateSpec(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("spec") match
      case specStr: ujson.Str =>
        val spec: String = specStr.value
        if spec.isEmpty || hasPathSeparator(spec) then
          Left(ContractViolation.SpecInvalid())
        else
          validateRing(fields, v, ts, change, spec)
      case _ =>
        Left(ContractViolation.SpecInvalid())

  /** Clause 6: ring must be in the closed domain. */
  private def validateRing(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("ring") match
      case ringStr: ujson.Str =>
        Ring.fromString(ringStr.value) match
          case Some(ring) =>
            validateObligation(fields, v, ts, change, spec, ring)
          case None =>
            Left(ContractViolation.RingOutsideDomain())
      case _ =>
        Left(ContractViolation.RingOutsideDomain())

  /** Clause 7: obligation must be a non-empty string. */
  private def validateObligation(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String, ring: Ring
  ): Either[ContractViolation, LedgerRecord] =
    fields("obligation") match
      case obligationStr: ujson.Str =>
        val obligation: String = obligationStr.value
        if obligation.isEmpty then
          Left(ContractViolation.ObligationEmpty())
        else
          validateArtifact(fields, v, ts, change, spec, ring, obligation)
      case _ =>
        Left(ContractViolation.ObligationEmpty())

  /** Clause 8: artifact must be a non-empty string. */
  private def validateArtifact(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String,
    ring: Ring, obligation: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("artifact") match
      case artifactStr: ujson.Str =>
        val artifact: String = artifactStr.value
        if artifact.isEmpty then
          Left(ContractViolation.ArtifactEmpty())
        else
          validateCommand(fields, v, ts, change, spec, ring, obligation, artifact)
      case _ =>
        Left(ContractViolation.ArtifactEmpty())

  /** Clause 9: command must be a non-empty string. */
  private def validateCommand(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String,
    ring: Ring, obligation: String, artifact: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("command") match
      case commandStr: ujson.Str =>
        val command: String = commandStr.value
        if command.isEmpty then
          Left(ContractViolation.CommandEmpty())
        else
          validateExit(fields, v, ts, change, spec, ring, obligation, artifact, command)
      case _ =>
        Left(ContractViolation.CommandEmpty())

  /** Clause 10: exit must be an integer. */
  private def validateExit(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String,
    ring: Ring, obligation: String, artifact: String, command: String
  ): Either[ContractViolation, LedgerRecord] =
    fields("exit") match
      case exitNum: ujson.Num if isIntegerValue(exitNum) =>
        val exit: Int = exitNum.value.toInt
        validateBaseline(fields, v, ts, change, spec, ring, obligation, artifact, command, exit)
      case _ =>
        Left(ContractViolation.ExitNotInteger())

  /** Clause 11: baseline must be lowercase hex 7-40 chars. */
  private def validateBaseline(
    fields: Map[String, ujson.Value], v: Int, ts: String, change: String, spec: String,
    ring: Ring, obligation: String, artifact: String, command: String, exit: Int
  ): Either[ContractViolation, LedgerRecord] =
    fields("baseline") match
      case baselineStr: ujson.Str =>
        val baseline: String = baselineStr.value
        if !isValidBaseline(baseline) then
          Left(ContractViolation.BaselineInvalid())
        else
          Right(LedgerRecord(
            v = v, ts = ts, change = change, spec = spec, ring = ring,
            obligation = obligation, artifact = artifact, command = command,
            exit = exit, baseline = baseline
          ))
      case _ =>
        Left(ContractViolation.BaselineInvalid())

  /** Check if a ujson Num is an integer value. */
  private def isIntegerValue(n: ujson.Num): Boolean =
    n.value == n.value.floor && n.value.isValidInt

  /** Check if a string contains path separators (/ or \). */
  private def hasPathSeparator(s: String): Boolean =
    s.contains('/') || s.contains('\\')

  /** Check if a string is a valid ISO-8601 UTC timestamp.
    * Accepts the pattern YYYY-MM-DDTHH:MM:SSZ (with optional fractional seconds). */
  private val timestampPattern: String =
    """^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$"""

  private def isValidTimestamp(s: String): Boolean =
    s.matches(timestampPattern)

  /** Check if a string is a valid lowercase hex revision of 7-40 chars. */
  private val baselinePattern: String =
    """^[0-9a-f]{7,40}$"""

  private def isValidBaseline(s: String): Boolean =
    s.matches(baselinePattern)
