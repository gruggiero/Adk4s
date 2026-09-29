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

  // ── decideCompletion (spec: completion-witness-refusal) ──────────────

  /**
   * An evidence-record row, abstracted to the three facts the refusal
   * decision reads: is the claim green, does its baseline equal the gate
   * baseline (a real `BigInt` equality — the current-baseline scope the
   * spec declares), and does an independent observation corroborate it.
   */
  case class EvidenceRow(isGreen: Boolean, baseline: BigInt, corroborated: Boolean)

  /**
   * The completion decision, abstracted: `CompletionAllow` is the clean
   * proceed; `CompletionRefuse` carries the INDEX of a row that
   * justifies the refusal — the model's `namedRow`. The fail-open
   * `AllowUndetermined` arm is unmodelled: the kernel quantifies over
   * already-read rows only, and an unreadable input can never reach the
   * decision (the adapter abstains at the read boundary).
   */
  sealed abstract class CompletionDecision
  case class CompletionAllow()                  extends CompletionDecision
  case class CompletionRefuse(rowIndex: BigInt) extends CompletionDecision

  /** True when the decision is a refusal. */
  @pure
  private def isCompletionRefusal(d: CompletionDecision): Boolean = d match
    case CompletionRefuse(_) => true
    case CompletionAllow()   => false

  /** The row predicate a refusal is warranted by. */
  @pure
  private def uncorroboratedAtBaseline(r: EvidenceRow, baseline: BigInt): Boolean =
    r.isGreen && r.baseline == baseline && !r.corroborated

  /** Whether any row warrants a refusal. */
  @pure
  private def hasUncorroboratedAtBaseline(rows: List[EvidenceRow], baseline: BigInt): Boolean =
    rows.exists((r: EvidenceRow) => uncorroboratedAtBaseline(r, baseline))

  /** The index of the first refusal-warranting row, if any. */
  @pure
  private def firstUncorroborated(rows: List[EvidenceRow], baseline: BigInt, i: BigInt): Option[BigInt] =
    rows match
      case Nil() => None[BigInt]()
      case Cons(h, t) =>
        if uncorroboratedAtBaseline(h, baseline) then Some(i)
        else firstUncorroborated(t, baseline, i + BigInt(1))

  /**
   * Soundness lemma: when `firstUncorroborated` returns `Some(k)`, `k`
   * is in range and the row it names satisfies the warrant predicate —
   * the refusal's `namedRow` justification.
   */
  @pure
  private def fuSound(rows: List[EvidenceRow], baseline: BigInt, i: BigInt, k: BigInt): Unit = {
    require(firstUncorroborated(rows, baseline, i) == Some(k))
    decreases(rows.size)
    rows match
      case Nil() => ()
      case Cons(h, t) =>
        if uncorroboratedAtBaseline(h, baseline) then ()
        else fuSound(t, baseline, i + BigInt(1), k)
  }.ensuring { (_: Unit) =>
    i <= k && k < i + rows.size &&
    uncorroboratedAtBaseline(rows(k - i), baseline)
  }

  /**
   * A satisfied predicate at an in-range index implies `exists` — the
   * `Some` arm's contribution to the refusal-iff postcondition.
   */
  @pure
  private def uncAtIndexImpliesExists(rows: List[EvidenceRow], baseline: BigInt, idx: BigInt): Unit = {
    require(
      BigInt(0) <= idx && idx < rows.size &&
      uncorroboratedAtBaseline(rows(idx), baseline)
    )
    decreases(rows.size)
    rows match
      case Nil() => ()
      case Cons(_, t) =>
        if idx == BigInt(0) then ()
        else uncAtIndexImpliesExists(t, baseline, idx - BigInt(1))
  }.ensuring { (_: Unit) => hasUncorroboratedAtBaseline(rows, baseline) }

  /**
   * Completeness lemma: when `firstUncorroborated` finds nothing, no row
   * warrants a refusal — the `None` arm's contribution to the
   * refusal-iff postcondition.
   */
  @pure
  private def fuComplete(rows: List[EvidenceRow], baseline: BigInt, i: BigInt): Unit = {
    require(firstUncorroborated(rows, baseline, i) == None[BigInt]())
    decreases(rows.size)
    rows match
      case Nil() => ()
      case Cons(h, t) =>
        if uncorroboratedAtBaseline(h, baseline) then ()
        else fuComplete(t, baseline, i + BigInt(1))
  }.ensuring { (_: Unit) => !hasUncorroboratedAtBaseline(rows, baseline) }

  /**
   * The completion decision: refuse — naming the first justifying row —
   * exactly when an uncorroborated green row exists at the current
   * baseline and the turn's refusal budget is unspent.
   *
   * Postcondition (the spec's `decideCompletion` contract):
   *  - refusal iff a warrant exists and no prior refusal was issued;
   *  - a refusal always names a row that justifies it;
   *  - a spent budget never refuses.
   *
   * spec: completion-witness-refusal — Contract: decideCompletion
   * spec: completion-witness-refusal — Property: refusal-iff-an-uncorroborated-green-result-exists
   * spec: completion-witness-refusal — Requirement: At most one refusal is issued per turn
   */
  @pure
  def decideCompletion(
    rows: List[EvidenceRow],
    baseline: BigInt,
    priorRefusals: BigInt
  ): CompletionDecision = {
    require(priorRefusals >= 0)
    if priorRefusals > 0 then CompletionAllow()
    else
      firstUncorroborated(rows, baseline, BigInt(0)) match
        case Some(i) =>
          fuSound(rows, baseline, BigInt(0), i)
          uncAtIndexImpliesExists(rows, baseline, i)
          CompletionRefuse(i)
        case None() =>
          fuComplete(rows, baseline, BigInt(0))
          CompletionAllow()
  }.ensuring { (res: CompletionDecision) =>
    val warranted: Boolean = hasUncorroboratedAtBaseline(rows, baseline)
    isCompletionRefusal(res) == (priorRefusals == 0 && warranted) &&
    (res match
      case CompletionRefuse(i) =>
        i >= BigInt(0) && i < rows.size &&
        uncorroboratedAtBaseline(rows(i), baseline)
      case CompletionAllow() => true
    ) &&
    (priorRefusals > 0 ==> !isCompletionRefusal(res))
  }

end GateKernel
