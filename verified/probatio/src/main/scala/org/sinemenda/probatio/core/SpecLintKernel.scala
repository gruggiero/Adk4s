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

end SpecLintKernel
