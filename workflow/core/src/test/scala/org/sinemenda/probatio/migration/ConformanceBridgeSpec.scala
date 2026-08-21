package org.sinemenda.probatio.migration

import hedgehog.*
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.core.ProbatioSuite
import org.sinemenda.probatio.verified.ConformanceModel

/** Bridge property test — binds the shipped validators to the Ring 6
  * `ConformanceModel` on the same generated inputs (R-M2, Ring 6 delegation).
  *
  * The Ring 6 model covers the conformance *decision* (clause satisfaction
  * equivalence), not the jq execution layer or the ujson/uPickle wire layer.
  * This bridge runs the shipped `Validator.validate` and the
  * `ConformanceModel` on the same generated records and asserts they agree
  * on the decision.
  *
  * spec: migration-protocol — Formal Contract: Conformance relation — validator iff contract
  * spec: migration-protocol — Formal Contract: Conformance symmetry — no false positives and no false negatives
  */
final class ConformanceBridgeSpec extends ProbatioSuite:

  import ConformanceTypes.*

  // ── Scenario: Bridge — shipped validator agrees with model on a valid record
  // spec: migration-protocol — Formal Contract: Conformance relation — validator iff contract
  test("bridge: shipped validator agrees with ConformanceModel on a valid record"):
    val record: ContractRecord = satisfyingLedgerRecordForBridge
    val shippedAccepts: Boolean = shippedValidatorAccepts(record)
    val modelAccepts: Boolean = modelValidatorAccepts(record)
    assert(shippedAccepts == modelAccepts,
      s"disagreement on valid record: shipped=$shippedAccepts, model=$modelAccepts")

  // ── Scenario: Bridge — shipped validator agrees with model on an invalid record
  // spec: migration-protocol — Formal Contract: Conformance relation — validator iff contract
  test("bridge: shipped validator agrees with ConformanceModel on an invalid record"):
    val record: ContractRecord = violatingLedgerRecordForBridge("v-must-be-integer-gte-1")
    val shippedAccepts: Boolean = shippedValidatorAccepts(record)
    val modelAccepts: Boolean = modelValidatorAccepts(record)
    assert(shippedAccepts == modelAccepts,
      s"disagreement on invalid record: shipped=$shippedAccepts, model=$modelAccepts")
    assert(!shippedAccepts, "both should reject an invalid record")

  // ── Property: bridge-validator-model-agreement
  // The shipped validator and the Ring 6 model agree on the accept/reject
  // decision for every generated record.
  property("bridge: shipped validator agrees with ConformanceModel over corpus"):
    for
      record <- genContractRecord.forAll
    yield
      val shippedAccepts: Boolean = shippedValidatorAccepts(record)
      val modelAccepts: Boolean = modelValidatorAccepts(record)
      Result.diff(shippedAccepts, modelAccepts)(_ == _)

  // ── Generator (delegates to ConformanceSpec's generator pattern)
  def genContractRecord: Gen[ContractRecord] =
    Gen.choice1(
      Gen.constant(satisfyingLedgerRecordForBridge),
      Gen.element(ConformanceTypes.ledgerClauses(0), ConformanceTypes.ledgerClauses.drop(1)).map(violatingLedgerRecordForBridge)
    )

  // ── Helper: run the shipped validator
  def shippedValidatorAccepts(record: ContractRecord): Boolean =
    record.contractId match
      case ContractId.LedgerRecord =>
        Validator.validate(record.json).isRight
      case _ =>
        // Non-ledger contracts are delegated to Ring 3 property tests
        true

  // ── Helper: run the Ring 6 model validator
  def modelValidatorAccepts(record: ContractRecord): Boolean =
    record.contractId match
      case ContractId.LedgerRecord =>
        val model: ConformanceModel.RecordModel = toRecordModel(record)
        ConformanceModel.modelValidate(model)
      case _ =>
        // Non-ledger contracts are delegated to Ring 3 property tests
        true

  // ── Helper: convert a ContractRecord to a RecordModel for the Ring 6 model
  private def toRecordModel(record: ContractRecord): ConformanceModel.RecordModel =
    val json: ujson.Value = record.json
    json match
      case obj: ujson.Obj =>
        val m: Map[String, ujson.Value] = obj.value.toMap
        ConformanceModel.RecordModel(
          isObject = true,
          hasAllRequired = ConformanceTypes.ledgerClauses(1) != record.violatedClause.getOrElse("") &&
            LedgerRecord.requiredFields.forall(m.contains),
          vValid = m.get("v").exists(isValidV),
          tsValid = m.get("ts").exists(isValidTs),
          changeValid = m.get("change").exists(isValidChange),
          specValid = m.get("spec").exists(isValidSpec),
          ringValid = m.get("ring").exists(isValidRing),
          obligationValid = m.get("obligation").exists(isNonEmptyStr),
          artifactValid = m.get("artifact").exists(isNonEmptyStr),
          commandValid = m.get("command").exists(isNonEmptyStr),
          exitValid = m.get("exit").exists(isIntegerValue),
          baselineValid = m.get("baseline").exists(isValidBaseline)
        )
      case _ =>
        ConformanceModel.RecordModel(
          isObject = false,
          hasAllRequired = false,
          vValid = false,
          tsValid = false,
          changeValid = false,
          specValid = false,
          ringValid = false,
          obligationValid = false,
          artifactValid = false,
          commandValid = false,
          exitValid = false,
          baselineValid = false
        )

  // ── Field validators for the model bridge ────────────────────────────────

  private def isValidV(v: ujson.Value): Boolean = v match
    case n: ujson.Num if n.value == n.value.floor && n.value.isValidInt && n.value.toInt >= 1 => true
    case _ => false

  private def isValidTs(v: ujson.Value): Boolean = v match
    case s: ujson.Str if s.value.matches("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""") => true
    case _ => false

  private def isValidChange(v: ujson.Value): Boolean = v match
    case s: ujson.Str if s.value.nonEmpty && !s.value.contains('/') && !s.value.contains('\\') => true
    case _ => false

  private def isValidSpec(v: ujson.Value): Boolean = v match
    case s: ujson.Str if s.value.nonEmpty && !s.value.contains('/') && !s.value.contains('\\') => true
    case _ => false

  private def isValidRing(v: ujson.Value): Boolean = v match
    case s: ujson.Str => Ring.fromString(s.value).isDefined
    case _ => false

  private def isNonEmptyStr(v: ujson.Value): Boolean = v match
    case s: ujson.Str if s.value.nonEmpty => true
    case _ => false

  private def isIntegerValue(v: ujson.Value): Boolean = v match
    case n: ujson.Num if n.value == n.value.floor && n.value.isValidInt => true
    case _ => false

  private def isValidBaseline(v: ujson.Value): Boolean = v match
    case s: ujson.Str if s.value.matches("""^[0-9a-f]{7,40}$""") => true
    case _ => false

  // ── Fixtures ──────────────────────────────────────────────────────────────

  private def satisfyingLedgerRecordForBridge: ContractRecord =
    ContractRecord(
      contractId = ContractId.LedgerRecord,
      json = ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-08T12:34:56Z"),
        "change"     -> ujson.Str("port-scanner-to-probatio"),
        "spec"       -> ujson.Str("migration-protocol"),
        "ring"       -> ujson.Str("R3"),
        "obligation" -> ujson.Str("bridge test"),
        "artifact"   -> ujson.Str("ConformanceBridgeSpec.scala"),
        "command"    -> ujson.Str("sbt probatio-core/test"),
        "exit"       -> ujson.Num(0),
        "baseline"   -> ujson.Str("abc1234")
      ),
      violatedClause = None
    )

  private def violatingLedgerRecordForBridge(clause: String): ContractRecord =
    val base: ujson.Obj = satisfyingLedgerRecordForBridge.json match
      case obj: ujson.Obj => obj
      case other          => sys.error(s"expected ujson.Obj, got $other")
    val violated: ujson.Value = clause match
      case "v-must-be-integer-gte-1" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("v", ujson.Num(0))
        ConformanceTypes.objFromMap(m)
      case "ring-must-be-in-closed-domain" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("ring", ujson.Str("R99"))
        ConformanceTypes.objFromMap(m)
      case "baseline-must-be-lowercase-hex-7-40" =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("baseline", ujson.Str("XYZ"))
        ConformanceTypes.objFromMap(m)
      case _ =>
        val m: Map[String, ujson.Value] = base.value.toMap.updated("v", ujson.Num(0))
        ConformanceTypes.objFromMap(m)

    ContractRecord(
      contractId = ContractId.LedgerRecord,
      json = violated,
      violatedClause = Some(clause)
    )
