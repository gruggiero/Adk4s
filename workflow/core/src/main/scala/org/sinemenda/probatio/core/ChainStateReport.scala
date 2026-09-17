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
      case other => // danger-scan:allow type-rejection — non-string crashes, never maps to valid value
        sys.error(s"expected string, got: $other")
    }
  )

/**
 * A named unresolved requirement entry — names WHICH requirement and WHY,
 * not just a count.
 *
 * The primary constructor is private and `copy` is sealed: an unresolved
 * entry is built only by `UnresolvedEntry.of`, which requires a non-empty
 * spec, a non-empty requirement, and at least one reason with no repeats —
 * the contract's per-entry clauses made unrepresentable rather than merely
 * untested. An unresolved requirement with no stated reason is a claim
 * without evidence.
 *
 * spec: chain-state-attribution — Compile-Negative: UnresolvedEntry with an empty reasons list
 */
final case class UnresolvedEntry private (
  spec: String,
  requirement: String,
  reasons: List[UnresolvedReason]
):
  // A public `copy` would re-open the constructor's invariant — a valid
  // entry could be copied with `reasons = Nil`. Sealed shut so `of`
  // remains the only construction path. Declared solely to suppress the
  // compiler-generated public `copy`; it is intentionally never invoked.
  @scala.annotation.nowarn("msg=unused private member")
  private def copy(
    spec: String = spec,
    requirement: String = requirement,
    reasons: List[UnresolvedReason] = reasons
  ): UnresolvedEntry = new UnresolvedEntry(spec, requirement, reasons)

object UnresolvedEntry:

  /**
   * The only construction route. Returns `None` when the entry would
   * violate the contract: empty spec or requirement, no reasons, or a
   * repeated reason (the contract requires `reasons | unique == reasons`).
   */
  def of(
    spec: String,
    requirement: String,
    reasons: List[UnresolvedReason]
  ): Option[UnresolvedEntry] =
    if spec.isEmpty || requirement.isEmpty || reasons.isEmpty ||
      reasons.distinct.length != reasons.length
    then None
    else Some(new UnresolvedEntry(spec, requirement, reasons))

  /**
   * Wire codec: the write side emits the contract's `{spec, requirement,
   * reasons}` object; the read side routes through `of`, so a
   * contract-violating entry on the wire is rejected, never admitted.
   */
  given ReadWriter[UnresolvedEntry] = readwriter[ujson.Value].bimap(
    (e: UnresolvedEntry) =>
      ujson.Obj(
        "spec"        -> ujson.Str(e.spec),
        "requirement" -> ujson.Str(e.requirement),
        "reasons"     -> ujson.Arr(e.reasons.map(r => ujson.Str(UnresolvedReason.asString(r)))*)
      ),
    {
      case obj: ujson.Obj =>
        val parsed: Option[UnresolvedEntry] = for
          sp <- obj.obj.get("spec").collect { case ujson.Str(s) => s }
          rq <- obj.obj.get("requirement").collect { case ujson.Str(s) => s }
          rs <- obj.obj.get("reasons").collect { case ujson.Arr(items) => items.toList }
          reasons <- rs.foldLeft[Option[List[UnresolvedReason]]](Some(List.empty)) {
            case (Some(acc), ujson.Str(s)) =>
              UnresolvedReason.fromString(s).map(r => acc :+ r)
            case _ => None // danger-scan:allow type-rejection — a non-string reason rejects the entry, never maps to valid
          }
          e <- of(sp, rq, reasons)
        yield e
        parsed match
          case Some(e) => e
          case None    => sys.error(s"invalid unresolved entry on the wire: $obj")
      case other => // danger-scan:allow type-rejection — non-object crashes, never maps to valid value
        sys.error(s"expected object, got: $other")
    }
  )

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
 * The primary constructor is private and `copy` is sealed: a report is
 * built only by `ChainStateReport.fromCounts`, which enforces every clause
 * of `chain-state-report-contract.jq` that is checkable on values —
 * non-negative monotone counts, the list-length law, unique (spec,
 * requirement) pairs, and the cross-consistency of the counts with the
 * reasons actually carried in `unresolved`. An impossible count is
 * unrepresentable, not merely untested.
 *
 * spec: probatio-core — Requirement: Chain-state computation is referentially transparent
 * spec: chain-state-attribution — Compile-Negative: ChainStateReport with discharged exceeding total
 */
final case class ChainStateReport private (
  change: String,
  baseline: String,
  total: Int,
  bound: Int,
  resolved: Int,
  discharged: Int,
  unresolved: List[UnresolvedEntry],
  unmappedObligations: List[UnmappedObligation]
):
  // Sealed like UnresolvedEntry's: `copy(discharged = total + 1)` would
  // bypass `fromCounts`' monotonicity check. Declared solely to suppress
  // the compiler-generated public `copy`; intentionally never invoked.
  @scala.annotation.nowarn("msg=unused private member")
  private def copy(
    change: String = change,
    baseline: String = baseline,
    total: Int = total,
    bound: Int = bound,
    resolved: Int = resolved,
    discharged: Int = discharged,
    unresolved: List[UnresolvedEntry] = unresolved,
    unmappedObligations: List[UnmappedObligation] = unmappedObligations
  ): ChainStateReport =
    new ChainStateReport(
      change,
      baseline,
      total,
      bound,
      resolved,
      discharged,
      unresolved,
      unmappedObligations
    )

object ChainStateReport:

  /**
   * The only construction route — the port of the predecessor's
   * self-check, where a report that violates its own contract is a bug in
   * the tool, never a fact about the change. `Left` names the violated
   * clause (mirroring the jq checker's `fail` messages); `compute` maps a
   * `Left` to `ChainStateUndetermined` with an internal-error reason, and
   * wire readers map it to rejection.
   */
  def fromCounts(
    change: String,
    baseline: String,
    total: Int,
    bound: Int,
    resolved: Int,
    discharged: Int,
    unresolved: List[UnresolvedEntry],
    unmappedObligations: List[UnmappedObligation]
  ): Either[String, ChainStateReport] =
    if change.isEmpty then Left("change must be a non-empty string")
    else if baseline.isEmpty then Left("baseline must be a non-empty string")
    else if List(total, bound, resolved, discharged).exists(_ < 0) then
      Left("total, bound, resolved and discharged must each be a non-negative integer")
    else if discharged > resolved then Left(s"discharged ($discharged) must not exceed resolved ($resolved)")
    else if resolved > bound then Left(s"resolved ($resolved) must not exceed bound ($bound)")
    else if bound > total then Left(s"bound ($bound) must not exceed total ($total)")
    else if unresolved.length != total - discharged then
      Left(
        s"unresolved list has ${unresolved.length} entries; total - discharged = ${total - discharged}"
      )
    else if unresolved.map(e => (e.spec, e.requirement)).distinct.length != unresolved.length then
      Left("the same (spec, requirement) pair appears more than once in unresolved")
    else if (total - bound) != unresolved.count(_.reasons.contains(UnresolvedReason.Unbound)) then
      Left("total - bound must equal the number of unresolved entries reasoned unbound")
    else if (bound - resolved) != unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Unresolved) || e.reasons.contains(UnresolvedReason.Unattributable)
      )
    then
      Left(
        "bound - resolved must equal the number of unresolved entries reasoned unresolved or unattributable"
      )
    else if (resolved - discharged) != unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Undischarged) || e.reasons.contains(UnresolvedReason.Failed)
      )
    then
      Left(
        "resolved - discharged must equal the number of unresolved entries reasoned undischarged or failed"
      )
    else if unmappedObligations.exists(u => u.spec.isEmpty || u.artifact.isEmpty || u.line < 1) then
      Left(
        "every unmapped_obligations entry must name a non-empty spec, a positive integer line, and a non-empty artifact"
      )
    else
      Right(
        new ChainStateReport(
          change,
          baseline,
          total,
          bound,
          resolved,
          discharged,
          unresolved,
          unmappedObligations
        )
      )

  /**
   * Wire codec: the write side emits the report-contract field names
   * (`unmapped_obligations` snake-case); the read side routes through
   * `fromCounts`, so a contract-violating report on the wire is rejected.
   */
  given ReadWriter[ChainStateReport] = readwriter[ujson.Value].bimap(
    (r: ChainStateReport) =>
      ujson.Obj(
        "change"               -> ujson.Str(r.change),
        "baseline"             -> ujson.Str(r.baseline),
        "total"                -> ujson.Num(r.total),
        "bound"                -> ujson.Num(r.bound),
        "resolved"             -> ujson.Num(r.resolved),
        "discharged"           -> ujson.Num(r.discharged),
        "unresolved"           -> ujson.Arr(r.unresolved.map(e => ujson.read(write(e)))*),
        "unmapped_obligations" -> ujson.Arr(r.unmappedObligations.map(u => ujson.read(write(u)))*)
      ),
    {
      case obj: ujson.Obj =>
        def str(k: String): Option[String] =
          obj.obj.get(k).collect { case ujson.Str(s) => s }
        def num(k: String): Option[Int] =
          obj.obj.get(k).collect { case ujson.Num(n) if n == n.floor => n.toInt }
        def entries(k: String): Option[List[UnresolvedEntry]] =
          obj.obj.get(k) match
            case None => Some(List.empty) // danger-scan:allow absent-key — absent key means empty (jq parity)
            case Some(ujson.Arr(items)) =>
              items.toList.foldLeft[Option[List[UnresolvedEntry]]](Some(List.empty)) {
                case (Some(acc), item: ujson.Obj) =>
                  val one: Option[UnresolvedEntry] = for
                    sp <- item.obj.get("spec").collect { case ujson.Str(s) => s }
                    rq <- item.obj.get("requirement").collect { case ujson.Str(s) => s }
                    rs <- item.obj.get("reasons").collect { case ujson.Arr(its) => its.toList }
                    reasons <- rs.foldLeft[Option[List[UnresolvedReason]]](Some(List.empty)) {
                      case (Some(a), ujson.Str(s)) =>
                        UnresolvedReason.fromString(s).map(r => a :+ r)
                      case _ => None // danger-scan:allow type-rejection — a non-string reason rejects the entry
                    }
                    e <- UnresolvedEntry.of(sp, rq, reasons)
                  yield e
                  one.map(e => acc :+ e)
                case _ => None // danger-scan:allow type-rejection — a non-object entry rejects the report
              }
            case Some(_) => None // danger-scan:allow type-rejection — a non-array unresolved key rejects the report
        def unmapped(k: String): Option[List[UnmappedObligation]] =
          obj.obj.get(k) match
            case None => Some(List.empty) // danger-scan:allow absent-key — absent key means empty (jq parity)
            case Some(ujson.Arr(items)) =>
              items.toList.foldLeft[Option[List[UnmappedObligation]]](Some(List.empty)) {
                case (Some(acc), item: ujson.Obj) =>
                  val one: Option[UnmappedObligation] = for
                    sp <- item.obj.get("spec").collect { case ujson.Str(s) => s }
                    ln <- item.obj.get("line").collect { case ujson.Num(n) if n == n.floor => n.toInt }
                    ar <- item.obj.get("artifact").collect { case ujson.Str(s) => s }
                  yield UnmappedObligation(sp, ln, ar)
                  one.map(u => acc :+ u)
                case _ => None // danger-scan:allow type-rejection — an invalid entry rejects the report
              }
            case Some(_) =>
              None // danger-scan:allow type-rejection — a non-array unmapped_obligations key rejects the report
        val parsed: Option[ChainStateReport] = for
          c  <- str("change")
          b  <- str("baseline")
          t  <- num("total")
          bd <- num("bound")
          r  <- num("resolved")
          d  <- num("discharged")
          u  <- entries("unresolved")
          um <- unmapped("unmapped_obligations")
          ok <- fromCounts(c, b, t, bd, r, d, u, um).toOption
        yield ok
        parsed match
          case Some(r) => r
          case None    => sys.error(s"invalid chain-state report on the wire: $obj")
      case other => // danger-scan:allow type-rejection — non-object crashes, never maps to valid value
        sys.error(s"expected object, got: $other")
    }
  )

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
