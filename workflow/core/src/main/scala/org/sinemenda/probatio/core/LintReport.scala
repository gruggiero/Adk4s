package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The per-requirement verdict (R-C4).
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
      case other => // danger-scan:allow type-rejection — invalid verdict crashes, never maps to valid value
        sys.error(s"invalid verdict: $other")
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
      case other => // danger-scan:allow type-rejection — invalid check id crashes, never maps to valid value
        sys.error(s"invalid check id: $other")
    }
  )

/** A per-requirement verdict entry in the lint report. */
final case class RequirementVerdict(
  requirement: String,
  verdict: Verdict,
  check: CheckId
) derives ReadWriter

/**
 * A spec-lint warning (W1–W7).
 *
 * `line` is `None` for the warnings the predecessor emits without a
 * `line N:` prefix (W2, W4, W6).
 *
 * spec: spec-lint-engine — Requirement: The engine emits exactly the predecessor's failure and warning identifiers for every fixture document
 */
final case class LintWarning(
  code: String,
  line: Option[Int],
  message: String
) derives ReadWriter

/**
 * A typed lint report — the engine's output and chain-state's structured
 * input (R-C4).
 *
 * The primary constructor is private: a `LintReport` is built only by
 * `LintReport.fromRun` (which derives `verdicts` from the parsed
 * document, so a report that claims `lintSuccess` while omitting a
 * requirement present in the `SpecDocument` is unrepresentable) or by
 * the JSON reader (wire data). `lintSuccess` is not a stored field —
 * it is derived from the emitted findings, so it cannot be asserted
 * independently of them.
 *
 * spec: probatio-core — Requirement: spec-lint output carries per-requirement verdict attribution
 * spec: spec-lint-engine — Requirement: Every obligation row is either resolved to a requirement or reported as unresolvable
 * spec: spec-lint-engine — Compile-Negative: LintReport constructed with lintSuccess=true while its verdicts list omits a requirement present in the SpecDocument
 */
final class LintReport private (
  /** One verdict per requirement in the linted document — exactly covers it. */
  val verdicts: List[RequirementVerdict],
  /** The emitted finding stream (Fail/Warn), in predecessor emission order. */
  val findings: List[CheckOutcome],
  /** Context facts the run depended on, rendered as key → state strings. */
  val applicability: Map[String, String],
  /** Evaluated obligation rows that resolved (to requirements or typed sources). */
  val resolvedRows: List[ObligationRow],
  /** Evaluated obligation rows the source check could not resolve — reported, never dropped. */
  val unresolvableRows: List[ObligationRow],
  /** The obligation rows covering each requirement title. */
  val requirementRows: Map[String, List[ObligationRow]]
):

  /** The emitted warnings, projected from the finding stream. */
  def warnings: List[LintWarning] =
    findings.collect { case CheckOutcome.Warn(w) => w }

  /** The emitted failures, projected from the finding stream. */
  def failures: List[CheckOutcome.Fail] =
    findings.collect { case f: CheckOutcome.Fail => f }

  /** The run found no failures — derived from findings, never asserted. */
  def lintSuccess: Boolean =
    findings.forall { outcome =>
      outcome match
        case CheckOutcome.Fail(_, _, _) => false
        case _ => true // danger-scan:allow reject-to-true — Pass and Warn outcomes do not constitute a lint failure
    }

  /** The obligation rows that resolve to the named requirement. */
  def rowsResolvingTo(requirementTitle: String): List[ObligationRow] =
    requirementRows.getOrElse(requirementTitle, Nil)

  override def toString: String =
    s"LintReport(verdicts=$verdicts, findings=$findings, applicability=$applicability, " +
      s"resolvedRows=$resolvedRows, unresolvableRows=$unresolvableRows, requirementRows=$requirementRows)"

object LintReport:

  /**
   * Build the report for one linted document — the only construction
   * route for engine output.
   *
   * `verdicts` are derived, never supplied: a requirement named by no
   * resolved obligation row is `Unbound` (F7); a bound requirement is
   * `Resolved` only when the artifact check ran (`artifactUnresolved`
   * is `Some`) and no covering row's artifact failed (F9); a bound
   * requirement with an unresolved artifact, or one bound when the
   * artifact check did not run, is `Bound`. The verdict's `check` is the
   * last check that vetted it: `F7` for unbound or artifact-unchecked,
   * `F9` for artifact-verdicts.
   */
  def fromRun(
    document: SpecDocument,
    findings: List[CheckOutcome],
    applicability: Map[String, String],
    resolvedRows: List[ObligationRow],
    unresolvableRows: List[ObligationRow],
    requirementRows: Map[String, List[ObligationRow]],
    artifactUnresolved: Option[Set[String]]
  ): LintReport =
    val verdicts: List[RequirementVerdict] = document.requirements.map { req =>
      val covered: Boolean = requirementRows.get(req.title).exists(_.nonEmpty)
      val verdict: Verdict =
        if !covered then Verdict.Unbound
        else
          artifactUnresolved match
            case None         => Verdict.Bound
            case Some(titles) => if titles.contains(req.title) then Verdict.Bound else Verdict.Resolved
      val check: CheckId = verdict match
        case Verdict.Unbound => CheckId.F7
        case Verdict.Bound =>
          artifactUnresolved match
            case None    => CheckId.F7
            case Some(_) => CheckId.F9
        case Verdict.Resolved => CheckId.F9
      RequirementVerdict(req.title, verdict, check)
    }
    new LintReport(verdicts, findings, applicability, resolvedRows, unresolvableRows, requirementRows)

  /** A report over a document with no requirements — nothing linted, nothing found. */
  val empty: LintReport =
    new LintReport(Nil, Nil, Map.empty, Nil, Nil, Map.empty)

  /**
   * Lossless JSON round-trip. The reader reconstructs stored fields as
   * wire data — it is the sole sanctioned construction outside `fromRun`.
   *
   * spec: probatio-core — Property: LintReport round-trips through uPickle JSON
   */
  given ReadWriter[LintReport] = readwriter[ujson.Value].bimap(
    (r: LintReport) =>
      ujson.Obj(
        "verdicts"         -> writeJs(r.verdicts),
        "findings"         -> writeJs(r.findings),
        "applicability"    -> writeJs(r.applicability),
        "resolvedRows"     -> writeJs(r.resolvedRows),
        "unresolvableRows" -> writeJs(r.unresolvableRows),
        "requirementRows"  -> writeJs(r.requirementRows)
      ),
    (json: ujson.Value) =>
      new LintReport(
        read[List[RequirementVerdict]](json("verdicts")),
        read[List[CheckOutcome]](json("findings")),
        read[Map[String, String]](json("applicability")),
        read[List[ObligationRow]](json("resolvedRows")),
        read[List[ObligationRow]](json("unresolvableRows")),
        read[Map[String, List[ObligationRow]]](json("requirementRows"))
      )
  )
