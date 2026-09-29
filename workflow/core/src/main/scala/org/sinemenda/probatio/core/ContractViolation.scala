package org.sinemenda.probatio.core

/**
 * Disjoint sum of the 15 clause failures from the ledger record contract.
 *
 * One variant per clause. The `clauseIndex` field gives the 0-based index
 * of the clause in the validation order (0–14), for conformance testing
 * against the jq contract. The first 12 clauses group the jq contract's
 * required-field elif branches by field: each field's type+value checks
 * form one clause. Clauses 12–14 cover the optional-field and provenance
 * checks added by the provenance-validation spec.
 *
 * spec: probatio-core — Requirement: LedgerRecord is an immutable product type with total clause validation
 * spec: probatio-core — Compile-Negative: ContractViolation with a 13th variant
 * spec: provenance-validation — Requirement: ContractViolation SHALL have exactly 15 variants, one per contract clause
 * spec: provenance-validation — Compile-Negative: ContractViolation with a 16th variant
 */
sealed trait ContractViolation:
  /** 0-based index of the failing clause (0–14). */
  def clauseIndex: Int

  /** Human-readable description of the violation. */
  def description: String

object ContractViolation:

  /** Clause 0: the input is not a JSON object (null, array, string, number, bool). */
  final case class NotAJsonObject(clauseIndex: Int = 0, description: String = "a record must be a JSON object")
      extends ContractViolation

  /** Clause 1: one or more required fields are missing. */
  final case class MissingRequiredFields(missingFields: List[String], clauseIndex: Int = 1) extends ContractViolation:
    val description: String = s"missing required field(s): ${missingFields.mkString(", ")}"

  /** Clause 2: version is not an integer or is < 1. */
  final case class VersionInvalid(clauseIndex: Int = 2, description: String = "v must be an integer >= 1")
      extends ContractViolation

  /** Clause 3: timestamp is not a string or not ISO-8601 UTC. */
  final case class TimestampInvalid(
    clauseIndex: Int = 3,
    description: String = "ts must be ISO-8601 UTC, e.g. 2026-08-08T12:34:56Z"
  ) extends ContractViolation

  /** Clause 4: change is not a non-empty string without path separators. */
  final case class ChangeInvalid(
    clauseIndex: Int = 4,
    description: String = "change must be a non-empty string without path separators"
  ) extends ContractViolation

  /** Clause 5: spec is not a non-empty string without path separators. */
  final case class SpecInvalid(
    clauseIndex: Int = 5,
    description: String = "spec must be a non-empty string without path separators"
  ) extends ContractViolation

  /** Clause 6: ring is not a string or not in the closed domain R0–R9/manual. */
  final case class RingOutsideDomain(
    clauseIndex: Int = 6,
    description: String = "ring must be one of: R0, R1, R2, R3, R4, R5, R6, R7, R8, R9, manual"
  ) extends ContractViolation

  /** Clause 7: obligation is not a non-empty string. */
  final case class ObligationEmpty(clauseIndex: Int = 7, description: String = "obligation must be a non-empty string")
      extends ContractViolation

  /** Clause 8: artifact is not a non-empty string. */
  final case class ArtifactEmpty(clauseIndex: Int = 8, description: String = "artifact must be a non-empty string")
      extends ContractViolation

  /** Clause 9: command is not a non-empty string. */
  final case class CommandEmpty(clauseIndex: Int = 9, description: String = "command must be a non-empty string")
      extends ContractViolation

  /** Clause 10: exit is not an integer. */
  final case class ExitNotInteger(clauseIndex: Int = 10, description: String = "exit must be an integer")
      extends ContractViolation

  /** Clause 11: baseline is not a lowercase hex string of 7–40 chars. */
  final case class BaselineInvalid(
    clauseIndex: Int = 11,
    description: String = "baseline must be a lowercase hex revision of 7-40 chars"
  ) extends ContractViolation

  /**
   * Clause 12: an optional field is present with a wrong type
   * (sha256/digest not a string, wallTime not an integer).
   *
   * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
   */
  final case class OptionalFieldTypeInvalid(
    clauseIndex: Int = 12,
    description: String = "optional field has an invalid type"
  ) extends ContractViolation

  /**
   * Clause 13: observer provenance — `source` present but not `"ambient"`.
   *
   * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
   */
  final case class ObserverProvenanceInvalid(
    clauseIndex: Int = 13,
    description: String = "source must be \"ambient\" when present"
  ) extends ContractViolation

  /**
   * Clause 14: session provenance — adversarial-review ring row missing
   * `session`, or any row with `session` that is not a non-empty string.
   *
   * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
   */
  final case class SessionProvenanceInvalid(
    clauseIndex: Int = 14,
    description: String = "session provenance is invalid"
  ) extends ContractViolation
