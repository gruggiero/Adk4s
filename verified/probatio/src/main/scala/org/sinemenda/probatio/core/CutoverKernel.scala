package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of the cutover gate decision.
 *
 * The gate decision is a comparison fold over two count vectors: the
 * predecessor failure counts and the ported failure counts, one per
 * file. The gate proceeds if and only if every ported count is at most
 * its paired predecessor count.
 *
 * The defect class this model averts is an absolute-threshold gate
 * (proceed when total failures == 0) that is unsatisfiable when the
 * predecessor itself fails tests, leading to the gate being bypassed
 * rather than enforced. The comparison-based predicate is both
 * satisfiable and strictly stronger than "no new failures overall",
 * because it forbids trading a regression in one file for an
 * improvement in another.
 *
 * The shipped `CutoverGate.decide` uses `DifferentialResult` with
 * `String` file names, `Int` counts, and `Boolean` completeness flags.
 * This mirror uses `BigInt` count vectors and a `Boolean` completeness
 * flag, proving the core decision law.
 *
 * spec: cutover-gate — Formal Contracts (Ring 6)
 * spec: cutover-gate — Contract: cutoverDecision
 */
object CutoverKernel:

  /** The gate's decision: proceed or revert. */
  sealed abstract class Decision
  case object Proceed                     extends Decision
  case class Revert(witnessIndex: BigInt) extends Decision

  /**
   * Decide whether to proceed based on two count vectors.
   *
   * Precondition: the two lists have equal length, and every count is
   * non-negative.
   *
   * Postcondition: the decision is proceed if and only if every ported
   * count is at most its paired predecessor count.
   *
   * Uses structural recursion (not .forall/.exists) to keep VCs
   * tractable for Z3 — see docs/ring6-stainless-verification-experience.md §4.
   *
   * spec: cutover-gate — Contract: cutoverDecision
   */
  @pure
  def cutoverDecision(predecessor: List[BigInt], ported: List[BigInt]): Boolean = {
    require(
      predecessor.length == ported.length &&
        allNonNeg(predecessor) && allNonNeg(ported)
    )
    noWorse(predecessor, ported)
  }.ensuring((proceed: Boolean) => proceed == noWorse(predecessor, ported))

  /**
   * Structural recursion: true iff every ported count <= its paired
   * predecessor count. Replaces `zipAll(xs, ys).forall(...)` which
   * generated unprovable VCs (see docs/ring6-stainless-verification-experience.md §4).
   */
  @pure
  def noWorse(predecessor: List[BigInt], ported: List[BigInt]): Boolean = {
    decreases(predecessor.size)
    (predecessor, ported) match
      case (Nil(), Nil())                   => true
      case (Cons(p, restP), Cons(q, restQ)) => q <= p && noWorse(restP, restQ)
      case _                                => true
  }

  /**
   * Structural recursion: true iff every count is non-negative.
   * Replaces `xs.forall(_ >= BigInt(0))` which generated unprovable VCs.
   */
  @pure
  def allNonNeg(xs: List[BigInt]): Boolean = {
    decreases(xs.size)
    xs match
      case Nil()      => true
      case Cons(h, t) => h >= BigInt(0) && allNonNeg(t)
  }

  /**
   * Decide with a witness: returns `Revert(witnessIndex)` when any file
   * is worse, naming the first such index. Returns `Proceed` when no
   * file is worse.
   */
  @pure
  def decideWithWitness(predecessor: List[BigInt], ported: List[BigInt]): Decision = {
    require(
      predecessor.length == ported.length &&
        allNonNeg(predecessor) && allNonNeg(ported)
    )
    findWorse(predecessor, ported, BigInt(0)) match
      case Some(idx) => Revert(idx)
      case None()    => Proceed
  }

  /**
   * Find the first index where the ported count exceeds the predecessor
   * count. Returns `Some(index)` if found, `None` if no file is worse.
   */
  @pure
  def findWorse(predecessor: List[BigInt], ported: List[BigInt], idx: BigInt): Option[BigInt] = {
    decreases(predecessor.size)
    (predecessor, ported) match
      case (Nil(), Nil()) => None()
      case (Cons(p, restP), Cons(q, restQ)) =>
        if q > p then Some(idx)
        else findWorse(restP, restQ, idx + BigInt(1))
      case _ => None()
  }

  // ---------------------------------------------------------------------------
  // Property lemmas — proven over FIXED-SIZE inputs to keep VCs tractable.
  // ---------------------------------------------------------------------------

  /**
   * Law: empty count vectors yield proceed (vacuously, no file is worse).
   * spec: cutover-gate — Property: proceed-iff-no-file-worse
   */
  @pure
  def emptyVectorsProceed: Boolean =
    cutoverDecision(Nil(), Nil())
      .ensuring(_ == true)

  /**
   * Law: a single file where ported <= predecessor yields proceed.
   * spec: cutover-gate — Property: proceed-iff-no-file-worse
   */
  @pure
  def singleFileProceed(pred: BigInt, ported: BigInt): Boolean = {
    require(pred >= BigInt(0) && ported >= BigInt(0) && ported <= pred)
    cutoverDecision(Cons(pred, Nil()), Cons(ported, Nil()))
  }.ensuring(_ == true)

  /**
   * Law: a single file where ported > predecessor yields revert (not
   * proceed).
   * spec: cutover-gate — Property: total-improvement-does-not-excuse-a-regression
   */
  @pure
  def singleFileRevert(pred: BigInt, ported: BigInt): Boolean = {
    require(pred >= BigInt(0) && ported >= BigInt(0) && ported > pred)
    cutoverDecision(Cons(pred, Nil()), Cons(ported, Nil()))
  }.ensuring(_ == false)

  /**
   * Law: a regression in one file blocks even when the total improves.
   * File 0: pred=10, ported=12 (regression, +2).
   * File 1: pred=10, ported=5  (improvement, -5).
   * Total: pred=20, ported=17 (improvement), but the gate reverts.
   * spec: cutover-gate — Property: total-improvement-does-not-excuse-a-regression
   */
  @pure
  def regressionBlocksDespiteTotalImprovement: Boolean = {
    val pred: List[BigInt]   = Cons(BigInt(10), Cons(BigInt(10), Nil()))
    val ported: List[BigInt] = Cons(BigInt(12), Cons(BigInt(5), Nil()))
    cutoverDecision(pred, ported)
  }.ensuring(_ == false)

  /**
   * Law: all files equal yields proceed.
   * spec: cutover-gate — Property: proceed-iff-no-file-worse
   */
  @pure
  def allFilesEqualProceed(a: BigInt, b: BigInt): Boolean = {
    require(a >= BigInt(0) && b >= BigInt(0))
    cutoverDecision(Cons(a, Cons(b, Nil())), Cons(a, Cons(b, Nil())))
  }.ensuring(_ == true)

  /** Law: decideWithWitness returns Proceed when no file is worse. */
  @pure
  def witnessProceedWhenNoWorse(a: BigInt, b: BigInt): Boolean = {
    require(a >= BigInt(0) && b >= BigInt(0) && b <= a)
    decideWithWitness(Cons(a, Nil()), Cons(b, Nil())) match
      case Proceed   => true
      case Revert(_) => false
  }.ensuring(_ == true)

  /**
   * Law: decideWithWitness returns Revert with a valid index when a
   * file is worse.
   */
  @pure
  def witnessRevertWhenWorse(a: BigInt, b: BigInt): Boolean = {
    require(a >= BigInt(0) && b >= BigInt(0) && b > a)
    decideWithWitness(Cons(a, Nil()), Cons(b, Nil())) match
      case Proceed     => false
      case Revert(idx) => idx == BigInt(0)
  }.ensuring(_ == true)
