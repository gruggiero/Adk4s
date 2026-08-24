package org.sinemenda.probatio.core

/**
 * The optional provenance fields from the jq contract (clauses 12–14).
 *
 * Carried alongside `LedgerRecord` as a companion value, not embedded in
 * the core record type (which remains 10 required fields only). These
 * fields are checked only if present — except `session`, which is
 * mandatory for adversarial-review (R8) ring rows.
 *
 * spec: provenance-validation — Requirement: The validator SHALL check all 15 contract clauses, not 12
 * spec: provenance-validation — Concepts Introduced: ProvenanceFields
 */
final case class ProvenanceFields(
  sha256: Option[String] = None,
  digest: Option[String] = None,
  wallTime: Option[Int] = None,
  source: Option[String] = None,
  session: Option[String] = None
)

object ProvenanceFields:

  /**
   * Extract provenance fields from a JSON object map, if present.
   * Does NOT validate — extraction only. Validation is done by the
   * 15-clause validator.
   */
  def extract(fields: Map[String, ujson.Value]): ProvenanceFields =
    val sha256: Option[String] = fields
      .get("sha256")
      .flatMap:
        case ujson.Str(s) => Some(s)
        case _            => None
    val digest: Option[String] = fields
      .get("digest")
      .flatMap:
        case ujson.Str(s) => Some(s)
        case _            => None
    val wallTime: Option[Int] = fields
      .get("wallTime")
      .flatMap:
        case num: ujson.Num if isIntegerValue(num) => Some(num.value.toInt)
        case _                                     => None
    val source: Option[String] = fields
      .get("source")
      .flatMap:
        case ujson.Str(s) => Some(s)
        case _            => None
    val session: Option[String] = fields
      .get("session")
      .flatMap:
        case ujson.Str(s) => Some(s)
        case _            => None
    ProvenanceFields(sha256, digest, wallTime, source, session)

  /** Check if a ujson Num is an integer value. */
  private def isIntegerValue(n: ujson.Num): Boolean =
    n.value == n.value.floor && n.value.isValidInt
