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
   * A ledger record matches the current change and baseline. `ledger.sh
   * read` has no ring filter — Manual-ring rows are legitimate discharge
   * evidence, matching the predecessor.
   */
  @pure
  def matchesBaselineChange(rec: LedgerRecord, baseline: BigInt, change: BigInt): Boolean =
    rec.change == change && rec.baseline == baseline

  /**
   * A requirement has a matching ledger record (same spec + obligation,
   * matching change/baseline — any ring, including Manual).
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

  /** Count requirements with a matching ledger record (any ring). */
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
  def isDischargedEmpty(
    req: Requirement,
    baseline: BigInt,
    change: BigInt
  ): Boolean =
    (!isDischarged(req, Nil(), baseline, change)).ensuring(_ == true)

  // ---------------------------------------------------------------------------
  // chainStateFold — the spec-5 formal contract
  //
  // The fold reduces a requirement to an index, a verdict to 0/1/2
  // (unbound / bound / resolved), a ledger to the set of discharged
  // indices, and the unreachable-obligation boundary to the set of // danger-scan:allow domain-term — the boundary's name, not a reachability claim
  // reachable-but-unattributable indices. `total` is the number of
  // requirements; `verdicts` is indexed by position — hence
  // `verdicts.size == total` (implied by the spec's bound <= total
  // postcondition).
  //
  // spec: chain-state-attribution — Formal Contracts (Ring 6): chainStateFold
  // ---------------------------------------------------------------------------

  /** The fold's accumulator: running counts plus the unresolved index list. */
  case class FoldResult(
    bound: BigInt,
    resolved: BigInt,
    dis: BigInt,
    unresolved: List[BigInt]
  )

  /** Every verdict code is in `[0, 2]`. */
  @pure
  def verdictCodesValid(vs: List[BigInt]): Boolean =
    decreases(vs.size)
    vs match
      case Nil() => true
      case Cons(v, rest) =>
        v >= BigInt(0) && v <= BigInt(2) && verdictCodesValid(rest)

  /** Every index in `is` lies in `[0, n)`. */
  @pure
  def indicesInRange(is: List[BigInt], n: BigInt): Boolean =
    decreases(is.size)
    is match
      case Nil() => true
      case Cons(i, rest) =>
        i >= BigInt(0) && i < n && indicesInRange(rest, n)

  /** Every element of `is` is a valid index into `[0, hi)`. */
  @pure
  def allValidIndex(is: List[BigInt], hi: BigInt): Boolean =
    decreases(is.size)
    is match
      case Nil() => true
      case Cons(u, rest) =>
        u >= BigInt(0) && u < hi && allValidIndex(rest, hi)

  /**
   * `filterOut(l, banned)` drops every `banned` element from `l`. The
   * unattributable exclusion is applied HERE, before the fold: an index
   * absent from the effective discharged set can never be discharged —
   * that is the unreachable-obligation boundary. // danger-scan:allow domain-term — the boundary's name, not a reachability claim
   */
  @pure
  def filterOut(l: List[BigInt], banned: List[BigInt]): List[BigInt] =
    decreases(l.size)
    l match
      case Nil() => Nil()
      case Cons(x, rest) =>
        if banned.contains(x) then filterOut(rest, banned)
        else Cons(x, filterOut(rest, banned))

  /**
   * A banned element never survives `filterOut` — the recursive call is
   * the induction hypothesis.
   */
  @pure
  def filteredNotBanned(x: BigInt, l: List[BigInt], banned: List[BigInt]): Unit = {
    require(banned.contains(x))
    decreases(l.size)
    l match
      case Nil()         => ()
      case Cons(_, rest) => filteredNotBanned(x, rest, banned)
  }.ensuring((_: Unit) => !filterOut(l, banned).contains(x))

  /**
   * The spec's unattributable clause over an index range: every `i` in
   * `[k, hi)` that is both unattributable and ledger-discharged appears
   * in `unresolved`. Iterating the range (not the unattributable list)
   * keeps the induction in the `uncoveredFrom` shape — the recursive
   * call's postcondition is the hypothesis for the tail.
   */
  @pure
  def rangeClause(
    k: BigInt,
    hi: BigInt,
    discharged: List[BigInt],
    unattributable: List[BigInt],
    unresolved: List[BigInt]
  ): Boolean = {
    require(k <= hi)
    decreases(hi - k)
    if k >= hi then true
    else
      (!(unattributable.contains(k) && discharged.contains(k)) ||
        unresolved.contains(k)) &&
      rangeClause(k + BigInt(1), hi, discharged, unattributable, unresolved)
  }

  /**
   * The fold over verdicts `vs` starting at index `idx`, where
   * `dischargedEff` is already free of unattributable indices. Each index
   * is either effectively discharged (ledger-discharged with a resolved
   * verdict — counted, absent from `unresolved`) or unresolved
   * (prepended) — so `unresolved` is the exact complement of the
   * effective discharged set within the visited range, and the counts
   * obey `dis <= resolved <= bound <= |vs|` because an effective
   * discharge implies a resolved verdict implies a bound verdict.
   */
  @pure
  def foldFrom(
    idx: BigInt,
    vs: List[BigInt],
    dischargedEff: List[BigInt]
  ): FoldResult = {
    require(idx >= BigInt(0))
    decreases(vs.size)
    vs match
      case Nil() => FoldResult(BigInt(0), BigInt(0), BigInt(0), Nil())
      case Cons(v, rest) =>
        val rec: FoldResult     = foldFrom(idx + BigInt(1), rest, dischargedEff)
        val boundInc: BigInt    = if v >= BigInt(1) then BigInt(1) else BigInt(0)
        val resolvedInc: BigInt = if v == BigInt(2) then BigInt(1) else BigInt(0)
        if dischargedEff.contains(idx) && v == BigInt(2) then
          FoldResult(
            rec.bound + boundInc,
            rec.resolved + resolvedInc,
            rec.dis + BigInt(1),
            rec.unresolved
          )
        else
          FoldResult(
            rec.bound + boundInc,
            rec.resolved + resolvedInc,
            rec.dis,
            Cons(idx, rec.unresolved)
          )
  }.ensuring { (res: FoldResult) =>
    res.dis <= res.resolved &&
    res.resolved <= res.bound &&
    res.bound <= vs.size &&
    res.unresolved.size == vs.size - res.dis &&
    allValidIndex(res.unresolved, idx + vs.size)
  }

  /**
   * Law (completeness): an in-range index absent from the effective
   * discharged set is consed onto `unresolved` at its step — regardless
   * of its verdict, since absence falsifies the discharge condition.
   * The recursive call is the induction hypothesis; the `idx == j` base
   * case unfolds `foldFrom` once.
   */
  @pure
  def absentIsUnresolved(
    idx: BigInt,
    j: BigInt,
    vs: List[BigInt],
    dischargedEff: List[BigInt]
  ): Unit = {
    require(
      idx >= BigInt(0) && j >= idx && j < idx + vs.size && !dischargedEff.contains(j)
    )
    decreases(vs.size)
    vs match
      case Nil() => ()
      case Cons(_, rest) =>
        if idx < j then absentIsUnresolved(idx + BigInt(1), j, rest, dischargedEff)
  }.ensuring((_: Unit) => foldFrom(idx, vs, dischargedEff).unresolved.contains(j))

  /**
   * The range-clause lift: every `k` in `[k, hi)` that is unattributable
   * and discharged is in the fold's unresolved set, because filtering
   * removes it from `dischargedEff` and `absentIsUnresolved` conses it.
   * The recursive call is the induction hypothesis for `k + 1`.
   */
  @pure
  def clauseFrom(
    k: BigInt,
    hi: BigInt,
    vs: List[BigInt],
    discharged: List[BigInt],
    unattributable: List[BigInt]
  ): Unit = {
    require(k >= BigInt(0) && k <= hi && vs.size == hi)
    decreases(hi - k)
    if k < hi then {
      if unattributable.contains(k) && discharged.contains(k) then {
        filteredNotBanned(k, discharged, unattributable)
        absentIsUnresolved(BigInt(0), k, vs, filterOut(discharged, unattributable))
      }
      clauseFrom(k + BigInt(1), hi, vs, discharged, unattributable)
    }
  }.ensuring { (_: Unit) =>
    rangeClause(
      k,
      hi,
      discharged,
      unattributable,
      foldFrom(BigInt(0), vs, filterOut(discharged, unattributable)).unresolved
    )
  }

  /**
   * The spec-5 fold contract. `verdicts` is indexed by requirement
   * position, so `verdicts.size == total` is required for the
   * `bound <= total` postcondition to be meaningful. The effective
   * discharged set filters unattributable indices out BEFORE the fold —
   * so no reachable-but-unattributable index can ever be counted
   * discharged, by construction.
   *
   * spec: chain-state-attribution — Formal Contracts (Ring 6): chainStateFold
   */
  @pure
  def chainStateFold(
    total: BigInt,
    verdicts: List[BigInt],
    discharged: List[BigInt],
    unattributable: List[BigInt]
  ): (BigInt, BigInt, BigInt, List[BigInt]) = {
    require(
      total >= BigInt(0) &&
        verdicts.size == total &&
        verdictCodesValid(verdicts) &&
        indicesInRange(discharged, total) &&
        indicesInRange(unattributable, total)
    )
    val disEff: List[BigInt] = filterOut(discharged, unattributable)
    val res: FoldResult      = foldFrom(BigInt(0), verdicts, disEff)
    clauseFrom(BigInt(0), total, verdicts, discharged, unattributable)
    (res.bound, res.resolved, res.dis, res.unresolved)
  }.ensuring { case (bound, resolved, dis, unresolved) =>
    dis <= resolved &&
    resolved <= bound &&
    bound <= total &&
    unresolved.size == total - dis &&
    rangeClause(BigInt(0), total, discharged, unattributable, unresolved)
  }

  /** Law: empty inputs fold to all-zero counts and no unresolved indices. */
  @pure
  def foldEmptyInputs: Boolean = {
    val res: (BigInt, BigInt, BigInt, List[BigInt]) =
      chainStateFold(BigInt(0), Nil(), Nil(), Nil())
    res._1 == BigInt(0) && res._2 == BigInt(0) && res._3 == BigInt(0) && res._4.isEmpty
  }.ensuring(_ == true)

  /**
   * Law: an unattributable index admitted to `discharged` is not counted
   * and lands in `unresolved` — the unattributable-never-discharged
   * invariant on a concrete witness.
   */
  @pure
  def foldUnattributableNotDischarged: Boolean = {
    val res: (BigInt, BigInt, BigInt, List[BigInt]) = chainStateFold(
      BigInt(2),
      Cons(BigInt(1), Cons(BigInt(2), Nil())),
      Cons(BigInt(0), Cons(BigInt(1), Nil())),
      Cons(BigInt(0), Nil())
    )
    res._1 == BigInt(2) && res._2 == BigInt(1) && res._3 == BigInt(1) &&
    res._4 == Cons(BigInt(0), Nil())
  }.ensuring(_ == true)

  /**
   * Law: when every resolved index is discharged and none is
   * unattributable, `dis == resolved` and `unresolved` is the complement
   * of the resolved set.
   */
  @pure
  def foldAllResolvedDischarged: Boolean = {
    val res: (BigInt, BigInt, BigInt, List[BigInt]) = chainStateFold(
      BigInt(3),
      Cons(BigInt(0), Cons(BigInt(2), Cons(BigInt(2), Nil()))),
      Cons(BigInt(1), Cons(BigInt(2), Nil())),
      Nil()
    )
    res._1 == BigInt(2) && res._2 == BigInt(2) && res._3 == BigInt(2) &&
    res._4 == Cons(BigInt(0), Nil())
  }.ensuring(_ == true)

  // ---------------------------------------------------------------------------
  // chain-state-undetermined-fidelity — the pre-pass boundary
  //
  // The verdict's data exists only when the mechanical pre-pass ran to
  // completion. `PrePassOutcome` mirrors the shipped enum: `Completed`
  // carries the run's observable result (reduced to its success bit —
  // the kernel's lint abstraction); `DidNotRun` carries only a stated
  // reason and can produce NO lint data at all — there is no field in
  // which it could live.
  //
  // spec: chain-state-undetermined-fidelity — Formal Contracts (Ring 6)
  // ---------------------------------------------------------------------------

  /**
   * The typed pre-pass boundary. `DidNotRun` cannot carry lint data — a
   * did-not-run is a fact about the run, not a run.
   */
  sealed abstract class PrePassOutcome
  case class PrePassCompleted(lintSuccess: Boolean) extends PrePassOutcome
  case class PrePassDidNotRun(reason: BigInt)       extends PrePassOutcome

  /**
   * `compute` behind the typed boundary. `DidNotRun` short-circuits to
   * `Left(Undetermined(reason))` — the verdicts, records and requirements
   * are never consulted, so a populated evidence set can never be
   * presented as a measured report. `Completed` is the only arm that
   * reaches the fold.
   *
   * spec: chain-state-undetermined-fidelity — Requirement: A verdict is produced only from a completed pre-pass
   */
  @pure
  def computeOutcome(
    prePass: PrePassOutcome,
    verdicts: Map[BigInt, Verdict],
    ledgerRecords: List[LedgerRecord],
    requirements: List[Requirement],
    baseline: BigInt,
    change: BigInt
  ): Either[Undetermined, ChainStateReport] =
    prePass match
      case PrePassDidNotRun(reason) => Left(Undetermined(reason))
      case PrePassCompleted(ran) =>
        compute(ran, verdicts, ledgerRecords, requirements, baseline, change)

  /**
   * Law (boundary): a did-not-run yields `Left` even when every other
   * input is populated — the deliberately-loaded witness proves the
   * evidence is never consulted.
   */
  @pure
  def didNotRunIgnoresPopulatedEvidence: Boolean = {
    val verdicts: Map[BigInt, Verdict] =
      stainless.lang.Map(BigInt(1) -> Resolved())
    val records: List[LedgerRecord] =
      Cons(LedgerRecord(BigInt(1), BigInt(1), Manual, BigInt(1), BigInt(1)), Nil())
    val reqs: List[Requirement] =
      Cons(Requirement(BigInt(1), BigInt(1)), Nil())
    computeOutcome(
      PrePassDidNotRun(BigInt(7)),
      verdicts,
      records,
      reqs,
      BigInt(1),
      BigInt(1)
    ).isLeft
  }.ensuring(_ == true)

  /**
   * Law (named reason): a did-not-run carries its stated reason through
   * — it is not replaced by a generic message and not dropped.
   *
   * spec: chain-state-undetermined-fidelity — Requirement: Every distinct could-not-determine reason is named
   */
  @pure
  def didNotRunCarriesStatedReason: Boolean = {
    computeOutcome(
      PrePassDidNotRun(BigInt(7)),
      stainless.lang.Map.empty[BigInt, Verdict],
      Nil(),
      Nil(),
      BigInt(1),
      BigInt(1)
    ) match
      case Left(u)  => u.reason == BigInt(7)
      case Right(_) => false // danger-scan:allow boundary-witness — unreachable; the Left arm is the law
  }.ensuring(_ == true)

  /**
   * Law (boundary completion): a completed pre-pass delegates to the
   * measured fold — `Completed(ran)` agrees with `compute(ran, ...)`
   * on the same inputs.
   */
  @pure
  def completedOutcomeMatchesCompute: Boolean = {
    val verdicts: Map[BigInt, Verdict] =
      stainless.lang.Map(BigInt(1) -> Resolved())
    val records: List[LedgerRecord] =
      Cons(LedgerRecord(BigInt(1), BigInt(1), Manual, BigInt(1), BigInt(1)), Nil())
    val reqs: List[Requirement] =
      Cons(Requirement(BigInt(1), BigInt(1)), Nil())
    computeOutcome(
      PrePassCompleted(true),
      verdicts,
      records,
      reqs,
      BigInt(1),
      BigInt(1)
    ) == compute(true, verdicts, records, reqs, BigInt(1), BigInt(1))
  }.ensuring(_ == true)

  /**
   * Law (count monotonicity): the shipped fold derives each count by
   * subtracting a disjoint subset of the previous one —
   * `bound = total − unbound`, `resolved = bound − unresolved`,
   * `discharged = resolved − undischarged` — so the inequality
   * `0 <= discharged <= resolved <= bound <= total` holds on every
   * reachable report, structurally.
   *
   * spec: chain-state-undetermined-fidelity — Property: counts are monotone and bounded
   */
  @pure
  def derivedCountsMonotone(
    total: BigInt,
    unbound: BigInt,
    unresolved: BigInt,
    undischarged: BigInt
  ): Boolean = {
    require(
      total >= BigInt(0) &&
        unbound >= BigInt(0) && unbound <= total &&
        unresolved >= BigInt(0) && unresolved <= total - unbound &&
        undischarged >= BigInt(0) && undischarged <= total - unbound - unresolved
    )
    val bound: BigInt      = total - unbound
    val resolved: BigInt   = bound - unresolved
    val discharged: BigInt = resolved - undischarged
    discharged >= BigInt(0) && discharged <= resolved && resolved <= bound && bound <= total
  }.ensuring(_ == true)
