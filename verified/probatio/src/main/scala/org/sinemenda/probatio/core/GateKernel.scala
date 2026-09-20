package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of the gate's two spec-8 decisions:
 * the refusal budget and the outcome classification.
 *
 * Abstraction:
 *   - `ToolOutcome` → `Option[BigInt]`: `Some(code)` is an `Exit`,
 *     `None()` is a `Skip` (the reason strings cannot be modelled;
 *     the decision is whether a code was reported at all).
 *   - The harness response shape → `shape: BigInt`:
 *       0 = object without `interrupted`   (the success report)
 *       1 = object with `interrupted`      (cut short — not an outcome)
 *       2 = `"Error: Exit code N"`         (the command's own code)
 *       3 = any other string               (a refusal — not an outcome)
 *       4 = any other shape                (unrecognised)
 *   - `carriedCode` is the code the error string carries (uninterpreted).
 *
 * The shipped `ToolOutcome.classify` and `RefusalBudget.apply` perform
 * the same case analysis over richer types; the bridge spec
 * (`GateBridgeSpec` in probatio-cli) runs both on the same inputs and
 * asserts they agree.
 *
 * The `refusalBudget` postcondition quantifies over recursive results on
 * an unbounded list — the solver cannot discharge that by unrolling, so
 * the law is carried by four inductive lemmas (Unit functions whose
 * recursive call is the induction hypothesis, the same idiom as
 * `ChainStateKernel.absentIsUnresolved`), instantiated in the body.
 * `countTrue` is the structural form of the contract's `count(d => d)`;
 * `firstTrueIndex` is `indexWhere` restricted to the first-hit index.
 *
 * spec: gate-event-completeness — Contract: refusalBudget
 * spec: gate-event-completeness — Contract: classifyOutcome
 */
object GateKernel:

  // ── refusalBudget ────────────────────────────────────────────────────

  /** Number of `true` elements — the contract's `count(d => d)`. */
  @pure
  private def countTrue(l: List[Boolean]): BigInt =
    l match
      case Nil()      => BigInt(0)
      case Cons(h, t) => (if h then BigInt(1) else BigInt(0)) + countTrue(t)

  /** The element at `idx`, or `false` when out of range. */
  @pure
  private def elemAt(l: List[Boolean], idx: BigInt): Boolean =
    l match
      case Cons(h, t) => if idx == BigInt(0) then h else elemAt(t, idx - BigInt(1))
      case Nil()      => false

  /** Index of the first `true`, or `l.size` when none. Structural recursion. */
  @pure
  private def firstTrueIndex(l: List[Boolean], i: BigInt): BigInt =
    l match
      case Nil()      => i
      case Cons(h, t) => if h then i else firstTrueIndex(t, i + BigInt(1))

  /** Mark `h && (position == target)` elementwise. Structural recursion. */
  @pure
  private def markAt(l: List[Boolean], target: BigInt, i: BigInt): List[Boolean] =
    l match
      case Nil()      => Nil()
      case Cons(h, t) => Cons(h && i == target, markAt(t, target, i + BigInt(1)))

  /**
   * Lemma: `markAt` preserves length. The recursive call is the
   * induction hypothesis.
   */
  @pure
  private def markAtLength(l: List[Boolean], target: BigInt, i: BigInt): Unit = {
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(_, t) => markAtLength(t, target, i + BigInt(1))
  }.ensuring((_: Unit) => markAt(l, target, i).length == l.length)

  /**
   * Lemma: `markAt(l, k, i)` marks exactly position `k` when it is in
   * range and `l(k - i)` holds; otherwise it marks nothing.
   */
  @pure
  private def markAtCount(l: List[Boolean], k: BigInt, i: BigInt): Unit = {
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(_, t) => markAtCount(t, k, i + BigInt(1))
  }.ensuring((_: Unit) =>
    countTrue(markAt(l, k, i)) ==
      (if i <= k && k < i + l.size && elemAt(l, k - i) then BigInt(1) else BigInt(0))
  )

  /**
   * Lemma: a list containing `true` has its first-true index in range,
   * and the element there is `true`.
   */
  @pure
  private def firstTrueInRange(l: List[Boolean], i: BigInt): Unit = {
    require(l.contains(true))
    decreases(l.size)
    l match
      case Nil()      => ()
      case Cons(h, t) => if !h then firstTrueInRange(t, i + BigInt(1))
  }.ensuring { (_: Unit) =>
    val k: BigInt = firstTrueIndex(l, i)
    i <= k && k < i + l.size && elemAt(l, k - i)
  }

  /**
   * Lemma: the first `true` of `markAt(l, k, i)` sits at offset `k - i`
   * — the marked position is the first hit.
   */
  @pure
  private def markAtFti(l: List[Boolean], k: BigInt, i: BigInt, j: BigInt): Unit = {
    require(i <= k && k < i + l.size && elemAt(l, k - i))
    decreases(l.size)
    l match
      case Nil() => ()
      case Cons(_, t) =>
        if i != k then markAtFti(t, k, i + BigInt(1), j + BigInt(1))
  }.ensuring((_: Unit) => firstTrueIndex(markAt(l, k, i), j) == j + (k - i))

  /**
   * The per-turn refusal fold: the refusal decision per action.
   * Exactly one refusal is produced — at the first blockable action.
   *
   * spec: gate-event-completeness — Contract: refusalBudget
   */
  @pure
  def refusalBudget(blockable: List[Boolean]): List[Boolean] = {
    require(blockable.nonEmpty && blockable.contains(true))
    val k: BigInt = firstTrueIndex(blockable, BigInt(0))
    firstTrueInRange(blockable, BigInt(0))
    markAtLength(blockable, k, BigInt(0))
    markAtCount(blockable, k, BigInt(0))
    markAtFti(blockable, k, BigInt(0), BigInt(0))
    markAt(blockable, k, BigInt(0))
  }.ensuring { (decisions: List[Boolean]) =>
    decisions.length == blockable.length &&
    countTrue(decisions) == BigInt(1) &&
    firstTrueIndex(decisions, BigInt(0)) == firstTrueIndex(blockable, BigInt(0))
  }

  // ── classifyOutcome ──────────────────────────────────────────────────

  /**
   * The outcome predicate: an exit code is produced ONLY for the object
   * shape (code 0 — the shape itself is the success report) and the
   * `"Error: Exit code N"` string shape (the carried code). Every other
   * shape — interruption, refusal string, unrecognised — yields
   * `None()`: the Skip that records nothing.
   *
   * spec: gate-event-completeness — Contract: classifyOutcome
   * spec: gate-event-completeness — Property: outcome-classification-is-total-and-conservative
   */
  @pure
  def classifyOutcome(shape: BigInt, carriedCode: BigInt): Option[BigInt] = {
    require(shape >= BigInt(0) && shape <= BigInt(4))
    if shape == BigInt(0) then Some(BigInt(0))
    else if shape == BigInt(2) then Some(carriedCode)
    else None[BigInt]()
  }.ensuring { (res: Option[BigInt]) =>
    (res.isDefined ==> (shape == BigInt(0) || shape == BigInt(2))) &&
    (shape == BigInt(0) ==> (res == Some(BigInt(0)))) &&
    (shape == BigInt(2) ==> (res == Some(carriedCode))) &&
    ((shape == BigInt(1) || shape == BigInt(3) || shape == BigInt(4)) ==> (res == None[BigInt]()))
  }

end GateKernel
