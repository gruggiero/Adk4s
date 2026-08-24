package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The reason a requirement is unresolved, used in `ChainStateReport`.
 *
 * Closed set matching `chain-state-report-contract.jq`'s `valid_reasons`:
 * unbound, unresolved, undischarged, unattributable, failed.
 */
enum UnresolvedReason:
  case Unbound, Unresolved, Undischarged, Unattributable, Failed

object UnresolvedReason:
  def asString(r: UnresolvedReason): String = r match
    case Unbound        => "unbound"
    case Unresolved     => "unresolved"
    case Undischarged   => "undischarged"
    case Unattributable => "unattributable"
    case Failed         => "failed"

  def fromString(s: String): Option[UnresolvedReason] = s match
    case "unbound"        => Some(Unbound)
    case "unresolved"     => Some(Unresolved)
    case "undischarged"   => Some(Undischarged)
    case "unattributable" => Some(Unattributable)
    case "failed"         => Some(Failed)
    case _                => None // danger-scan:allow type-rejection — non-string maps to None, never a valid value

  given ReadWriter[UnresolvedReason] = readwriter[ujson.Value].bimap(
    (r: UnresolvedReason) => ujson.Str(asString(r)),
    {
      case ujson.Str(s) =>
        fromString(s) match
          case Some(r) => r
          case None    => sys.error(s"invalid unresolved reason: $s")
      case other => sys.error(s"expected string, got: $other") // danger-scan:allow type-rejection — non-string crashes, never maps to valid value
    }
  )

/**
 * A named unresolved requirement entry — names WHICH requirement and WHY,
 * not just a count.
 */
final case class UnresolvedEntry(
  spec: String,
  requirement: String,
  reasons: List[UnresolvedReason]
) derives ReadWriter

/**
 * An unmapped obligation — one whose Source is not resolvable to a single
 * requirement but which DOES carry an F9 finding.
 */
final case class UnmappedObligation(
  spec: String,
  line: Int,
  artifact: String
) derives ReadWriter

/**
 * The measured chain-state report (R-C3).
 *
 * Produced by `ChainState.compute` when the inputs are determinable.
 * Satisfies the monotonicity law: discharged ≤ resolved ≤ bound ≤ total.
 * The unresolved list size equals total − discharged.
 *
 * spec: probatio-core — Requirement: Chain-state computation is referentially transparent
 */
final case class ChainStateReport(
  change: String,
  baseline: String,
  total: Int,
  bound: Int,
  resolved: Int,
  discharged: Int,
  unresolved: List[UnresolvedEntry],
  unmappedObligations: List[UnmappedObligation]
) derives ReadWriter

/**
 * The undetermined result — chain state could not be computed.
 *
 * Carries a non-empty reason. The measured fields are absent (the report
 * shape has null counts, not zero counts — undetermined is NEVER collapsed
 * into a clean "0 discharged").
 *
 * spec: probatio-core — Requirement: Undetermined is never collapsed into a finding
 */
final case class ChainStateUndetermined(
  change: String,
  baseline: String,
  reason: String
) derives ReadWriter
