package org.sinemenda.probatio.migration

import upickle.default.*

/** Types for the conformance property test (R-M2).
  *
  * The conformance relation between a ported Scala validator and its
  * corresponding executable `.jq` contract is a property test over a
  * generated corpus, not a spot check. For every clause of every contract,
  * the system generates records that satisfy the clause and records that
  * violate it, and asserts that the validator accepts a record if and only
  * if the executable contract accepts the same record.
  *
  * spec: migration-protocol — Requirement: Conformance between validators and executable contracts is a property test
  * spec: migration-protocol — Property: conformance-validator-contract-equivalence
  */
object ConformanceTypes:

  /** The three executable contract files (R-M2).
    *
    * Each maps to a `.jq` file under `openspec/schemas/verified-scala3/scanner/`
    * and a ported Scala validator in `probatio-core`.
    */
  enum ContractId:
    case LedgerRecord
    case ChainStateReport
    case GateHookJson

  object ContractId:
    given ReadWriter[ContractId] = readwriter[ujson.Value].bimap(
      (c: ContractId) => ujson.Str(c.toString),
      (v: ujson.Value) => v match
        case ujson.Str("LedgerRecord")       => ContractId.LedgerRecord
        case ujson.Str("ChainStateReport")   => ContractId.ChainStateReport
        case ujson.Str("GateHookJson")       => ContractId.GateHookJson
        case other                           => sys.error(s"invalid ContractId: $other")
    )

    /** The relative path to the `.jq` contract file. */
    def contractPath(id: ContractId): String = id match
      case ContractId.LedgerRecord       => "openspec/schemas/verified-scala3/scanner/ledger-record-contract.jq"
      case ContractId.ChainStateReport   => "openspec/schemas/verified-scala3/scanner/chain-state-report-contract.jq"
      case ContractId.GateHookJson       => "openspec/schemas/verified-scala3/scanner/gate-hookjson-contract.jq"

  /** Judgment from running an executable `.jq` contract on a record.
    *
    * `Accept` means `jq -e -f` exited zero (the record conforms).
    * `Reject` means it exited non-zero with a clause-named error.
    */
  enum ContractJudgment:
    case Accept
    case Reject(clause: String)

  /** Judgment from running the ported Scala validator on a record.
    *
    * `Accept` means `Validator.validate` returned `Right`.
    * `Reject` means it returned `Left` with the violated clause name.
    */
  enum ValidatorJudgment:
    case Accept
    case Reject(clause: String)

  /** A generated record paired with metadata about which contract it
    * targets and which clause (if any) it violates.
    *
    * `violatedClause = None` means the record satisfies all clauses.
    * `violatedClause = Some(name)` means the record violates exactly that
    * clause (the generator constructed it to exercise that clause's boundary).
    */
  final case class ContractRecord(
    contractId: ContractId,
    json: ujson.Value,
    violatedClause: Option[String]
  )

  /** The result of evaluating a record through both the contract and the
    * validator. Conformance holds when both agree.
    */
  final case class ConformanceResult(
    contractJudgment: ContractJudgment,
    validatorJudgment: ValidatorJudgment
  ):
    /** True iff both judgments agree (both accept or both reject). */
    def isConformant: Boolean = (contractJudgment, validatorJudgment) match
      case (ContractJudgment.Accept, ValidatorJudgment.Accept)           => true
      case (ContractJudgment.Reject(_), ValidatorJudgment.Reject(_))     => true
      case _                                                             => false

    /** True iff the validator accepts but the contract rejects (false positive). */
    def isFalsePositive: Boolean = (contractJudgment, validatorJudgment) match
      case (ContractJudgment.Reject(_), ValidatorJudgment.Accept)        => true
      case _                                                             => false

    /** True iff the contract accepts but the validator rejects (false negative). */
    def isFalseNegative: Boolean = (contractJudgment, validatorJudgment) match
      case (ContractJudgment.Accept, ValidatorJudgment.Reject(_))        => true
      case _                                                             => false

  /** The 12 ledger-record clauses (from `ledger-record-contract.jq`).
    *
    * Used by the generator to construct records that violate exactly one
    * clause at a time.
    */
  val ledgerClauses: List[String] = List(
    "not-a-json-object",
    "missing-required-field",
    "v-must-be-integer-gte-1",
    "ts-must-be-iso-8601-utc",
    "change-must-be-non-empty-no-separator",
    "spec-must-be-non-empty-no-separator",
    "ring-must-be-in-closed-domain",
    "obligation-must-be-non-empty",
    "artifact-must-be-non-empty",
    "command-must-be-non-empty",
    "exit-must-be-integer",
    "baseline-must-be-lowercase-hex-7-40"
  )

  /** The chain-state report clauses (from `chain-state-report-contract.jq`).
    *
    * Includes both the measured-shape and undetermined-shape clauses.
    */
  val chainStateClauses: List[String] = List(
    "must-be-json-object",
    "missing-required-field",
    "change-must-be-non-empty",
    "baseline-must-be-non-empty",
    "counts-must-be-non-negative-integer",
    "discharged-must-not-exceed-resolved",
    "resolved-must-not-exceed-bound",
    "bound-must-not-exceed-total",
    "unresolved-must-be-array",
    "unresolved-length-equals-total-minus-discharged",
    "unresolved-entry-must-name-spec-requirement-reasons",
    "reasons-must-not-repeat",
    "no-duplicate-spec-requirement-pair",
    "cross-consistency-total-minus-bound",
    "cross-consistency-bound-minus-resolved",
    "cross-consistency-resolved-minus-discharged",
    "unmapped-obligations-must-be-array",
    "unmapped-obligations-entry-shape"
  )

  /** The gate hook-json clauses (from `gate-hookjson-contract.jq`). */
  val gateHookJsonClauses: List[String] = List(
    "must-be-json-object",
    "must-have-hookSpecificOutput-object",
    "hookEventName-must-be-non-empty-string",
    "hookEventName-must-be-SessionStart-or-UserPromptSubmit",
    "additionalContext-must-be-string-when-present"
  )

  /** Helper: construct a ujson.Obj from a Map[String, ujson.Value].
    * Needed because ujson.Obj's varargs constructor doesn't accept
    * `Map.toSeq*` due to overloaded apply type inference issues. */
  def objFromMap(m: Map[String, ujson.Value]): ujson.Obj =
    val obj: ujson.Obj = ujson.Obj()
    m.foreach { case (k: String, v: ujson.Value) => obj(k) = v }
    obj
