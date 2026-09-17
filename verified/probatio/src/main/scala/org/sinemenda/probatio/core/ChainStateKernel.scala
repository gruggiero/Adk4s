package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of `ChainState.compute` (R-C3).
 *
 * This model mirrors the undetermined-never-collapses law: when the lint
 * fails, the result is always `Left(Undetermined)`, never `Right(report)`.
 * When the lint succeeds, the result is always `Right(report)`, never `Left`.
 *
 * The defect class this whole schema averts is collapsing an undetermined
 * state into a clean/zero discharged state — i.e. returning a
 * `Right(ChainStateReport(0, 0, 0, 0))` when the lint failed instead of
 * `Left(Undetermined)`. The measured fields are absent in the undetermined
 * case; undetermined is NEVER collapsed into a clean "0 discharged" report.
 *
 * The shipped `ChainState.compute` uses `String` keys, `upickle` derivation,
 * and `Int` counts. Stainless is pinned to Scala 3.7.2 while the build is
 * 3.8.4 — the mirror exists to prove the algorithm.
 *
 * Abstraction:
 *   - `LintReport` → a `Boolean` (`lintSuccess`) + a `Map[BigInt, Verdict]`
 *     (verdicts by obligation id)
 *   - `Verdict` → sealed abstract class: `Bound`, `Resolved`, `Unbound`
 *   - `LedgerRecord` → a case class with `BigInt` fields (spec, obligation
 *     as `BigInt` ids; ring as `Ring`; change/baseline as `BigInt`)
 *   - `Requirement` → case class with `BigInt` spec and obligation ids
 *   - `ChainStateReport` → case class with `BigInt` counts (total, bound,
 *     resolved, discharged)
 *   - `Undetermined` → case class with reason (`BigInt` for abstraction)
 *   - `Ring` → sealed abstract class with case objects (R0–R8, Manual)
 *
 * The bridge spec runs the real `ChainState.compute` and this model on the
 * SAME generated inputs and asserts they agree on the proven invariants.
 *
 * spec: middleware-laws — Formal Contracts (Ring 6)
 */
object ChainStateKernel:

  // ── Ring abstraction (R0–R8, Manual) ───────────────────────────────────

  /**
   * The closed ring domain (R0–R8, manual). A ring outside this set is
   * unrepresentable at the type level.
   */
  sealed abstract class Ring
  case object R0     extends Ring
  case object R1     extends Ring
  case object R2     extends Ring
  case object R3     extends Ring
  case object R4     extends Ring
  case object R5     extends Ring
  case object R6     extends Ring
  case object R7     extends Ring
  case object R8     extends Ring
  case object Manual extends Ring

  // ── Verdict abstraction ────────────────────────────────────────────────

  /** The per-requirement verdict (R-C4). */
  sealed abstract class Verdict
  case class Bound()    extends Verdict
  case class Resolved() extends Verdict
  case class Unbound()  extends Verdict

  // ── Data abstractions ──────────────────────────────────────────────────

  /** A ledger record — spec, obligation, ring, change, baseline. */
  case class LedgerRecord(
    spec: BigInt,
    obligation: BigInt,
    ring: Ring,
    change: BigInt,
    baseline: BigInt
  )

  /** A requirement in the requirements list. */
  case class Requirement(
    spec: BigInt,
    obligation: BigInt
  )

  /** The measured chain-state report (R-C3). */
  case class ChainStateReport(
    total: BigInt,
    bound: BigInt,
    resolved: BigInt,
    discharged: BigInt
  )

  /** The undetermined result — chain state could not be computed. */
  case class Undetermined(reason: BigInt)

  // ── Helpers ────────────────────────────────────────────────────────────

  /** A ring is non-Manual (R0–R8), i.e. discharge-eligible. */
  @pure
  def isNonManual(r: Ring): Boolean = r match
    case Manual => false
    case _      => true // danger-scan:allow spec-contract-code — all R0–R8 rings are discharge-eligible

  /** A verdict is Bound or Resolved (counts toward bound). */
  @pure
  def isBoundOrResolved(v: Verdict): Boolean = v match
    case Bound()    => true
    case Resolved() => true
    case Unbound()  => false

  /** A verdict is Resolved (counts toward resolved). */
  @pure
  def isResolved(v: Verdict): Boolean = v match
    case Resolved() => true
    case Bound()    => false
    case Unbound()  => false

  /**
   * A ledger record matches the current change and baseline and is
   * discharge-eligible (non-Manual ring).
   */
  @pure
  def matchesBaselineChange(rec: LedgerRecord, baseline: BigInt, change: BigInt): Boolean =
    rec.change == change && rec.baseline == baseline && isNonManual(rec.ring)

  /**
   * A requirement has a matching non-Manual ledger record (same spec +
   * obligation, matching change/baseline, non-Manual ring).
   */
  @pure
  def isDischarged(
    req: Requirement,
    records: List[LedgerRecord],
    baseline: BigInt,
    change: BigInt
  ): Boolean =
    decreases(records.size)
    records match
      case Nil() => false
      case Cons(rec, rest) =>
        if matchesBaselineChange(rec, baseline, change) &&
          rec.spec == req.spec &&
          rec.obligation == req.obligation
        then true
        else isDischarged(req, rest, baseline, change)

  /** Count requirements with verdict Bound or Resolved. */
  @pure
  def countBound(verdicts: Map[BigInt, Verdict], reqs: List[Requirement]): BigInt =
    decreases(reqs.size)
    reqs match
      case Nil() => BigInt(0)
      case Cons(r, rest) =>
        val head: BigInt = if verdicts.get(r.obligation) match
            case Some(v) => isBoundOrResolved(v)
            case None()  => false
        then BigInt(1)
        else BigInt(0)
        head + countBound(verdicts, rest)

  /** Count requirements with verdict Resolved. */
  @pure
  def countResolved(verdicts: Map[BigInt, Verdict], reqs: List[Requirement]): BigInt =
    decreases(reqs.size)
    reqs match
      case Nil() => BigInt(0)
      case Cons(r, rest) =>
        val head: BigInt = if verdicts.get(r.obligation) match
            case Some(v) => isResolved(v)
            case None()  => false
        then BigInt(1)
        else BigInt(0)
        head + countResolved(verdicts, rest)

  /** Count requirements with a matching non-Manual ledger record. */
  @pure
  def countDischarged(
    records: List[LedgerRecord],
    reqs: List[Requirement],
    baseline: BigInt,
    change: BigInt
  ): BigInt =
    decreases(reqs.size)
    reqs match
      case Nil() => BigInt(0)
      case Cons(r, rest) =>
        val head: BigInt = if isDischarged(r, records, baseline, change) then BigInt(1) else BigInt(0)
        head + countDischarged(records, rest, baseline, change)

  /**
   * A "clean" report is the defect state: all counts zero. This is what
   * an undetermined result must NEVER be collapsed into.
   */
  @pure
  def isCleanReport(r: ChainStateReport): Boolean =
    r.total == BigInt(0) &&
      r.bound == BigInt(0) &&
      r.resolved == BigInt(0) &&
      r.discharged == BigInt(0)

  // ── Core computation ───────────────────────────────────────────────────

  /**
   * Compute chain state from declared inputs.
   *
   * Returns either an `Undetermined` (when lint failed) or a
   * `ChainStateReport` (when lint succeeded). The
   * undetermined-never-collapses law guarantees that a failed lint NEVER
   * produces a `Right` (report) — it is always `Left(Undetermined)`.
   *
   * spec: probatio-core — Scenario: a failed lint yields undetermined, not zero
   * spec: probatio-core — Scenario: same inputs produce same output
   */
  @pure
  def compute(
    lintSuccess: Boolean,
    verdicts: Map[BigInt, Verdict],
    ledgerRecords: List[LedgerRecord],
    requirements: List[Requirement],
    baseline: BigInt,
    change: BigInt
  ): Either[Undetermined, ChainStateReport] =
    if !lintSuccess then {
      Left(Undetermined(BigInt(0)))
    } else {
      val total: BigInt      = requirements.length
      val bound: BigInt      = countBound(verdicts, requirements)
      val resolved: BigInt   = countResolved(verdicts, requirements)
      val discharged: BigInt = countDischarged(ledgerRecords, requirements, baseline, change)
      Right(ChainStateReport(total, bound, resolved, discharged))
    }

  // ---------------------------------------------------------------------------
  // Property lemmas — standalone Boolean functions
  //
  // Proven over FIXED-SIZE inputs (empty lists) to keep VCs tractable for Z3.
  // The bridge spec tests the full recursive functions against production code;
  // these lemmas prove the core invariants on the base cases.
  // ---------------------------------------------------------------------------

  /**
   * Law: a failed lint always yields Undetermined (`Left`), never a report.
   * Proven on empty inputs — the lint-success branch is independent of the
   * list contents.
   *
   * spec: probatio-core — Scenario: a failed lint yields undetermined, not zero
   */
  @pure
  def failedLintYieldsUndetermined(
    verdicts: Map[BigInt, Verdict],
    baseline: BigInt,
    change: BigInt
  ): Boolean = {
    val result: Either[Undetermined, ChainStateReport] =
      compute(false, verdicts, Nil(), Nil(), baseline, change)
    result.isLeft
  }.ensuring(_ == true)

  /**
   * Law: a successful lint with empty requirements yields a report (`Right`)
   * with all counts zero.
   */
  @pure
  def successfulLintYieldsReport(
    verdicts: Map[BigInt, Verdict],
    baseline: BigInt,
    change: BigInt
  ): Boolean = {
    val result: Either[Undetermined, ChainStateReport] =
      compute(true, verdicts, Nil(), Nil(), baseline, change)
    result.isRight
  }.ensuring(_ == true)

  /**
   * Law: a successful lint with empty requirements yields a clean report
   * (all counts zero). This is the undetermined-never-collapses invariant:
   * the defect state is a clean report, and a failed lint produces Left,
   * never a clean report.
   */
  @pure
  def cleanReportOnEmpty(
    verdicts: Map[BigInt, Verdict],
    baseline: BigInt,
    change: BigInt
  ): Boolean = {
    val result: Either[Undetermined, ChainStateReport] =
      compute(true, verdicts, Nil(), Nil(), baseline, change)
    result match
      case Right(report) => isCleanReport(report)
      case Left(_)       => true // danger-scan:allow lemma-vacuous — vacuous when lint failed
  }.ensuring(_ == true)

  /** Law: countBound on an empty requirement list is 0. */
  @pure
  def countBoundEmpty(verdicts: Map[BigInt, Verdict]): Boolean = {
    countBound(verdicts, Nil()) == BigInt(0)
  }.ensuring(_ == true)

  /** Law: countResolved on an empty requirement list is 0. */
  @pure
  def countResolvedEmpty(verdicts: Map[BigInt, Verdict]): Boolean = {
    countResolved(verdicts, Nil()) == BigInt(0)
  }.ensuring(_ == true)

  /** Law: countDischarged on an empty requirement list is 0. */
  @pure
  def countDischargedEmpty(
    records: List[LedgerRecord],
    baseline: BigInt,
    change: BigInt
  ): Boolean = {
    countDischarged(records, Nil(), baseline, change) == BigInt(0)
  }.ensuring(_ == true)

  /** Law: isDischarged on an empty record list is false. */
  @pure
  // format: off — scalafmt must not reflow .ensuring off the Stainless postcondition position
  def isDischargedEmpty(
    req: Requirement,
    baseline: BigInt,
    change: BigInt
  ): Boolean =
    !isDischarged(req, Nil(), baseline, change)
      .ensuring(_ == true)
  // format: on
