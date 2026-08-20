package org.sinemenda.probatio.core

import upickle.default.*

/** The per-requirement verdict (R-C4).
  *
  * spec: probatio-core — Requirement: spec-lint output carries per-requirement verdict attribution
  */
enum Verdict:
  case Bound, Resolved, Unbound

object Verdict:
  def asString(v: Verdict): String = v match
    case Bound    => "bound"
    case Resolved => "resolved"
    case Unbound  => "unbound"

  given ReadWriter[Verdict] = readwriter[ujson.Value].bimap(
    (v: Verdict) => ujson.Str(asString(v)),
    {
      case ujson.Str("bound")    => Verdict.Bound
      case ujson.Str("resolved") => Verdict.Resolved
      case ujson.Str("unbound")  => Verdict.Unbound
      case other                 => sys.error(s"invalid verdict: $other")
    }
  )

/** The F1–F10 check identifiers. */
enum CheckId:
  case F1, F2, F3, F4, F5, F6, F7, F8, F9, F10

object CheckId:
  def asString(c: CheckId): String = c match
    case F1  => "F1"
    case F2  => "F2"
    case F3  => "F3"
    case F4  => "F4"
    case F5  => "F5"
    case F6  => "F6"
    case F7  => "F7"
    case F8  => "F8"
    case F9  => "F9"
    case F10 => "F10"

  given ReadWriter[CheckId] = readwriter[ujson.Value].bimap(
    (c: CheckId) => ujson.Str(asString(c)),
    {
      case ujson.Str("F1")  => CheckId.F1
      case ujson.Str("F2")  => CheckId.F2
      case ujson.Str("F3")  => CheckId.F3
      case ujson.Str("F4")  => CheckId.F4
      case ujson.Str("F5")  => CheckId.F5
      case ujson.Str("F6")  => CheckId.F6
      case ujson.Str("F7")  => CheckId.F7
      case ujson.Str("F8")  => CheckId.F8
      case ujson.Str("F9")  => CheckId.F9
      case ujson.Str("F10") => CheckId.F10
      case other            => sys.error(s"invalid check id: $other")
    }
  )

/** A per-requirement verdict entry in the lint report. */
final case class RequirementVerdict(
  requirement: String,
  verdict: Verdict,
  check: CheckId
) derives ReadWriter

/** A spec-lint warning (W3, W7, etc.). */
final case class LintWarning(
  code: String,
  line: Int,
  message: String
) derives ReadWriter

/** A typed lint report carrying per-requirement verdict attribution (R-C4).
  *
  * Replaces the table-structure re-parsing that today's chain-state script
  * does. Consumed by chain-state as structured input. Serializable as
  * uPickle JSON and round-trips losslessly.
  *
  * spec: probatio-core — Requirement: spec-lint output carries per-requirement verdict attribution
  * spec: probatio-core — Property: LintReport round-trips through uPickle JSON
  */
final case class LintReport(
  verdicts: List[RequirementVerdict],
  warnings: List[LintWarning],
  applicability: Map[String, String],
  lintSuccess: Boolean
) derives ReadWriter
