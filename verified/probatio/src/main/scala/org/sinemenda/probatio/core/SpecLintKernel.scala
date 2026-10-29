package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of `SpecLintEngine.reachabilityFold`.
 *
 * The reachability fold is the decision at the centre of the spec-lint
 * engine: which requirement indices are unenforced (named by no proof
 * obligation) and how many obligation rows resolved to no requirement.
 * The shipped code folds over `Int` targets; the mirror reduces a
 * document to `(numRequirements: BigInt, rowTargets: List[BigInt])`
 * where a target is a requirement index or `-1` for unresolvable.
 *
 * Contract (spec: spec-lint-engine — Contract: reachabilityFold):
 *   - require: every target is `-1` or in `[0, numRequirements)`.
 *   - ensure: the reported unenforced set and the set of requirement
 *     indices appearing in `rowTargets` are exact complements within
 *     `[0, numRequirements)`, and the count of `-1` targets equals the
 *     reported unresolvable count.
 *
 * Exact-complement is established in two pieces, because a single
 * postcondition relating the constructed list to membership inside a
 * different recursion is an induction Z3 cannot discharge (see
 * docs/ring6-stainless-verification-experience.md §4–6):
 *   - Soundness rides `uncoveredFrom`'s own postcondition — the
 *     recursion is same-shaped, so the inductive step is the recursive
 *     call's postcondition.
 *   - Completeness is `uncoveredComplete`, a Unit-lemma whose recursive
 *     call supplies the induction hypothesis.
 *
 * spec: spec-lint-engine — Contract: reachabilityFold
 */
object SpecLintKernel:

  // ---------------------------------------------------------------------------
  // Structural helpers
  // ---------------------------------------------------------------------------

  /** Index `i` appears among `targets`. */
  @pure
  def covered(i: BigInt, targets: List[BigInt]): Boolean =
    decreases(targets.size)
    targets match
      case Nil()         => false
      case Cons(t, rest) => t == i || covered(i, rest)

  /** The count of `-1` (unresolvable) targets. */
  @pure
  def countUnresolvable(targets: List[BigInt]): BigInt =
    decreases(targets.size)
    targets match
      case Nil() => BigInt(0)
      case Cons(t, rest) =>
        val restCount: BigInt = countUnresolvable(rest)
        if t == BigInt(-1) then restCount + BigInt(1) else restCount

  /** The fold's precondition as a boolean — every target is `-1` or in `[0, n)`. */
  @pure
  def allTargetsValid(n: BigInt, targets: List[BigInt]): Boolean =
    decreases(targets.size)
    targets match
      case Nil() => true
      case Cons(t, rest) =>
        (t == BigInt(-1) || (t >= BigInt(0) && t < n)) && allTargetsValid(n, rest)

  /** Every element of `res` is in `[0, n)` and not covered — the soundness clause. */
  @pure
  def allValidUncovered(res: List[BigInt], n: BigInt, targets: List[BigInt]): Boolean =
    decreases(res.size)
    res match
      case Nil() => true
      case Cons(u, rest) =>
        u >= BigInt(0) && u < n && !covered(u, targets) && allValidUncovered(rest, n, targets)

  /**
   * The uncovered requirement indices in `[i, n)`, ascending.
   *
   * The soundness invariant rides this function's own postcondition: at
   * each step the result is either the recursive call's result (whose
   * postcondition is the induction hypothesis) or `Cons(i, …)` where the
   * head's validity is in context.
   */
  @pure
  def uncoveredFrom(i: BigInt, n: BigInt, targets: List[BigInt]): List[BigInt] = {
    require(i >= BigInt(0) && i <= n)
    decreases(n - i)
    if i >= n then Nil()
    else if covered(i, targets) then uncoveredFrom(i + BigInt(1), n, targets)
    else Cons(i, uncoveredFrom(i + BigInt(1), n, targets))
  }.ensuring((res: List[BigInt]) => allValidUncovered(res, n, targets))

  // ---------------------------------------------------------------------------
  // reachabilityFold — the contract
  // ---------------------------------------------------------------------------

  /**
   * The reachability fold: the unenforced requirement indices and the
   * count of unresolvable rows. Soundness of the complement comes from
   * `uncoveredFrom`'s postcondition; completeness is the
   * `uncoveredComplete` lemma below.
   *
   * spec: spec-lint-engine — Contract: reachabilityFold
   */
  @pure
  def reachabilityFold(numRequirements: BigInt, rowTargets: List[BigInt]): (List[BigInt], BigInt) = {
    require(numRequirements >= BigInt(0) && allTargetsValid(numRequirements, rowTargets))
    (uncoveredFrom(BigInt(0), numRequirements, rowTargets), countUnresolvable(rowTargets))
  }.ensuring { case (unenforced, unresolvableCount) =>
    allValidUncovered(unenforced, numRequirements, rowTargets) &&
    unresolvableCount == countUnresolvable(rowTargets)
  }

  /**
   * Law (completeness): an in-range index not covered by `targets` is a
   * member of the report built from `i` upward. The recursive call is the
   * induction hypothesis; the `i == j` base case unfolds `uncoveredFrom`
   * once — `!covered(j, targets)` makes `j` the head.
   */
  @pure
  def uncoveredComplete(i: BigInt, j: BigInt, n: BigInt, targets: List[BigInt]): Unit = {
    require(i >= BigInt(0) && i <= j && j < n && !covered(j, targets))
    decreases(n - i)
    if i < j then uncoveredComplete(i + BigInt(1), j, n, targets)
  }.ensuring((_: Unit) => uncoveredFrom(i, n, targets).contains(j))

  // ---------------------------------------------------------------------------
  // Property lemmas — standalone boolean functions over fixed-size inputs
  // ---------------------------------------------------------------------------

  /**
   * Law: no rows means every requirement is unenforced — the unenforced
   * set is the full `[0, n)` range.
   */
  @pure
  def foldEmptyTargetsAllUnenforced(n: BigInt): Boolean = {
    require(n >= BigInt(0) && n <= BigInt(4))
    val res: (List[BigInt], BigInt) = reachabilityFold(n, Nil())
    res._1 == uncoveredFrom(BigInt(0), n, Nil()) && res._2 == BigInt(0)
  }.ensuring(_ == true)

  /**
   * Law: when every requirement is covered the unenforced set is empty.
   */
  @pure
  def foldFullyCoveredNoneUnenforced: Boolean = {
    val res: (List[BigInt], BigInt) =
      reachabilityFold(BigInt(2), Cons(BigInt(0), Cons(BigInt(1), Nil())))
    res._1.isEmpty && res._2 == BigInt(0)
  }.ensuring(_ == true)

  /**
   * Law: a lone unresolvable target counts as one unresolvable row and
   * covers nothing.
   */
  @pure
  def foldUnresolvableCoversNothing: Boolean = {
    val res: (List[BigInt], BigInt) =
      reachabilityFold(BigInt(2), Cons(BigInt(-1), Nil()))
    res._1 == Cons(BigInt(0), Cons(BigInt(1), Nil())) && res._2 == BigInt(1)
  }.ensuring(_ == true)

  /**
   * Law: a covered index is absent from the unenforced report — the
   * complement's non-membership direction on a concrete witness.
   */
  @pure
  def foldCoveredIndexNotReported: Boolean = {
    val res: (List[BigInt], BigInt) =
      reachabilityFold(BigInt(3), Cons(BigInt(1), Cons(BigInt(-1), Nil())))
    res._1 == Cons(BigInt(0), Cons(BigInt(2), Nil())) && res._2 == BigInt(1)
  }.ensuring(_ == true)

  // ---------------------------------------------------------------------------
  // guardOutcome — the feature-freeze guard's three-way classification
  // (spec: feature-freeze-guard-integrity — Contract: guardOutcome)
  // ---------------------------------------------------------------------------

  /**
   * The guard's report classification, mirrored. The shipped outcome is
   * `Outcome[FeatureFreezeVerdict]`; the kernel reduces it to the three
   * decision classes plus the fixtures a violation names.
   */
  case class GuardResult(
    isUndetermined: Boolean,
    isUpheld: Boolean,
    isViolation: Boolean,
    namedFixtures: List[BigInt]
  )

  /**
   * The guard's three-way report, mirrored.
   *
   * `resolved` is the corpus resolution collapsed to located/not-located —
   * the shipped side's non-empty corpus is a type-level guarantee
   * (`FixtureCorpus`'s private constructor), not a decision input.
   * `disagreements` are the fixture indices whose ported and predecessor
   * verdicts differ.
   *
   * spec: feature-freeze-guard-integrity — Contract: guardOutcome
   */
  @pure
  def guardOutcome(resolved: Boolean, disagreements: List[BigInt]): GuardResult = {
    if !resolved then GuardResult(true, false, false, Nil())
    else if disagreements.isEmpty then GuardResult(false, true, false, Nil())
    else GuardResult(false, false, true, disagreements)
  }.ensuring { (result: GuardResult) =>
    (!resolved ==> result.isUndetermined) &&
    (result.isUndetermined ==> !result.isUpheld) &&
    (result.isUpheld == (resolved && disagreements.isEmpty)) &&
    (result.isViolation ==> result.namedFixtures.nonEmpty)
  }

  // ---------------------------------------------------------------------------
  // sanctionVerdict — the oracle guard's three-way decision
  // (spec: oracle-independence — Contract: sanctionVerdict)
  // ---------------------------------------------------------------------------

  /**
   * The sanction verdict's report classification, mirrored. `mods` are
   * the modification identities since the recorded baseline, `accepted`
   * the modification identities the sanction record accepts, `readable`
   * the three guard inputs (baseline record, history, sanction record)
   * collapsed to readable/not-readable.
   */
  case class VerdictModel(
    isAllSanctioned: Boolean,
    isUnsanctioned: Boolean,
    isUndeterminable: Boolean,
    named: List[BigInt]
  )

  /**
   * Every modification identity is among the accepted identities — the
   * spec's `mods.forall(m => accepted.contains(m))`, structurally
   * unfolded (a stdlib `forall` call leaves an unsolvable VC; see
   * docs/ring6-stainless-verification-experience.md §4–5).
   */
  @pure
  def allAccepted(mods: List[BigInt], accepted: List[BigInt]): Boolean =
    decreases(mods.size)
    mods match
      case Nil()          => true
      case Cons(m, rest)  => accepted.contains(m) && allAccepted(rest, accepted)

  /**
   * Every `res` element is a member of `mods` with no accepted sanction —
   * the spec's `named.forall(m => mods.contains(m) && !accepted.contains(m))`,
   * structurally unfolded for the same reason.
   */
  @pure
  def allUncovered(res: List[BigInt], mods: List[BigInt], accepted: List[BigInt]): Boolean =
    decreases(res.size)
    res match
      case Nil()         => true
      case Cons(m, rest) =>
        mods.contains(m) && !accepted.contains(m) && allUncovered(rest, mods, accepted)

  /**
   * The modification identities with no accepted sanction, in `mods`
   * order — the shipped side's `history.filterNot(accepted)`.
   */
  @pure
  def uncoveredMods(mods: List[BigInt], accepted: List[BigInt]): List[BigInt] =
    decreases(mods.size)
    mods match
      case Nil() => Nil()
      case Cons(m, rest) =>
        if accepted.contains(m) then uncoveredMods(rest, accepted)
        else Cons(m, uncoveredMods(rest, accepted))

  /**
   * Law (weakening): `allUncovered` against `mods` also holds against
   * `Cons(h, mods)` — membership survives a head extension because
   * `contains` is head-or-tail. The recursive call is the induction
   * hypothesis.
   */
  @pure
  def allUncoveredWeaken(res: List[BigInt], h: BigInt, mods: List[BigInt], accepted: List[BigInt]): Unit = {
    require(allUncovered(res, mods, accepted))
    decreases(res.size)
    res match
      case Nil()      => ()
      case Cons(_, t) => allUncoveredWeaken(t, h, mods, accepted)
  }.ensuring { (_: Unit) => allUncovered(res, Cons(h, mods), accepted) }

  /**
   * Law (soundness): `uncoveredMods` reports only uncovered members of
   * `mods`. The `allUncoveredWeaken` call lifts the recursive result's
   * predicate from `rest` to `Cons(m, rest)` — the step Z3 cannot
   * discharge on its own.
   */
  @pure
  def uncoveredSound(mods: List[BigInt], accepted: List[BigInt]): Unit = {
    decreases(mods.size)
    mods match
      case Nil()         => ()
      case Cons(m, rest) =>
        uncoveredSound(rest, accepted)
        allUncoveredWeaken(uncoveredMods(rest, accepted), m, rest, accepted)
  }.ensuring { (_: Unit) => allUncovered(uncoveredMods(mods, accepted), mods, accepted) }

  /**
   * Law: when not every modification is accepted, some modification is
   * uncovered. The recursive call is the induction hypothesis; the
   * `!accepted.contains(m)` head case is immediate.
   */
  @pure
  def uncoveredExists(mods: List[BigInt], accepted: List[BigInt]): Unit = {
    require(!allAccepted(mods, accepted))
    decreases(mods.size)
    mods match
      case Nil()         => ()
      case Cons(m, rest) =>
        if !accepted.contains(m) then () else uncoveredExists(rest, accepted)
  }.ensuring { (_: Unit) => uncoveredMods(mods, accepted).nonEmpty }

  /**
   * The verdict's three-way report, mirrored. An unreadable input is
   * `isUndeterminable`, never a pass; otherwise all-sanctioned passes
   * and any uncovered modification is named.
   *
   * spec: oracle-independence — Contract: sanctionVerdict
   */
  @pure
  def sanctionVerdict(mods: List[BigInt], accepted: List[BigInt], readable: Boolean): VerdictModel = {
    if !readable then VerdictModel(false, false, true, Nil())
    else if allAccepted(mods, accepted) then VerdictModel(true, false, false, Nil())
    else
      uncoveredExists(mods, accepted)
      uncoveredSound(mods, accepted)
      VerdictModel(false, true, false, uncoveredMods(mods, accepted))
  }.ensuring { (result: VerdictModel) =>
    (!readable ==> result.isUndeterminable) &&
    (result.isAllSanctioned == (readable && allAccepted(mods, accepted))) &&
    (result.isUnsanctioned ==> allUncovered(result.named, mods, accepted)) &&
    (result.isUnsanctioned ==> result.named.nonEmpty)
  }

end SpecLintKernel
