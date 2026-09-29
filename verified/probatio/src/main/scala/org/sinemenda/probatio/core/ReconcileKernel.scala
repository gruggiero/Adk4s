package org.sinemenda.probatio.verified

import stainless.lang._
import stainless.collection._
import stainless.annotation._

/**
 * Ring 6 — PureScala model of `ReconcileEngine.classify`.
 *
 * The corroboration fold is the decision at the centre of the reconcile
 * engine: whether a written green record's outcome has support beyond
 * its writer's assertion. The mirror reduces a record to
 * `(keyIndex, outcomeCode, observationKind)` — the (spec, ring,
 * baseline, command) key is collapsed to an index because equality, not
 * structure, is what the classification reads — and returns a
 * classification code per record.
 *
 * Contract (spec: danger-reconcile-engines — Contract: corroborationFold):
 *   - require: every observation kind is in [0, 2]; every outcome code
 *     is >= 0.
 *   - ensure: every record receives exactly one classification; a
 *     written green record is classified witnessed only if an observed
 *     record shares its key and outcome; a written green record with no
 *     observed record at its key is classified testimony.
 *
 * Bridge encoding (ReconcileBridgeSpec): a judgment-ring or failing
 * written row is encoded WRITTEN with a nonzero outcome — it is not a
 * corroboratable green claim, so the fold classifies it EXEMPT, as the
 * engine does. Ambient rows are OBSERVED; digest rows are
 * SELF_OBSERVED.
 *
 * spec: danger-reconcile-engines — Contract: corroborationFold
 */
object ReconcileKernel:

  // ---------------------------------------------------------------------------
  // Observation kinds — who saw the exit
  // ---------------------------------------------------------------------------

  /** A bare `append` row — the writer typed the exit code. */
  val WRITTEN: BigInt = BigInt(0)

  /** A `run` row — carries a digest; the recorder observed its own run. */
  val SELF_OBSERVED: BigInt = BigInt(1)

  /** An ambient row — the gate's own observation; the witness kind. */
  val OBSERVED: BigInt = BigInt(2)

  // ---------------------------------------------------------------------------
  // Classification codes — the corroboration verdict
  // ---------------------------------------------------------------------------

  val CLS_SELF_OBSERVED: BigInt = BigInt(0)
  val CLS_WITNESSED: BigInt     = BigInt(1)
  val CLS_TESTIMONY: BigInt     = BigInt(2)
  val CLS_CONTRADICTED: BigInt  = BigInt(3)
  val CLS_EXEMPT: BigInt        = BigInt(4)

  /** An OBSERVED record exists at `key`. */
  @pure
  def observedAt(key: BigInt, records: List[(BigInt, BigInt, BigInt)]): Boolean = {
    decreases(records.size)
    records match
      case Nil() => false
      case Cons((k2, _, kind2), rest) =>
        (kind2 == OBSERVED && k2 == key) || observedAt(key, rest)
  }

  /** An OBSERVED record exists at `key` carrying `out`. */
  @pure
  def observedOutcomeAt(key: BigInt, out: BigInt, records: List[(BigInt, BigInt, BigInt)]): Boolean = {
    decreases(records.size)
    records match
      case Nil() => false
      case Cons((k2, o2, kind2), rest) =>
        (kind2 == OBSERVED && k2 == key && o2 == out) ||
        observedOutcomeAt(key, out, rest)
  }

  /**
   * The spec's `require` as structural recursion — `List.forall`
   * generates an unprovable VC (see
   * docs/ring6-stainless-verification-experience.md §4).
   */
  @pure
  def validRecords(records: List[(BigInt, BigInt, BigInt)]): Boolean = {
    decreases(records.size)
    records match
      case Nil() => true
      case Cons((_, out, kind), rest) =>
        out >= BigInt(0) && kind >= BigInt(0) && kind <= BigInt(2) &&
        validRecords(rest)
  }

  /** One record's classification — the engine's if-chain verbatim. */
  @pure
  def classifyRow(
    key: BigInt,
    out: BigInt,
    kind: BigInt,
    records: List[(BigInt, BigInt, BigInt)]
  ): BigInt =
    if kind != WRITTEN then CLS_SELF_OBSERVED
    else if out != BigInt(0) then CLS_EXEMPT
    else if !observedAt(key, records) then CLS_TESTIMONY
    else if observedOutcomeAt(key, out, records) then CLS_WITNESSED
    else CLS_CONTRADICTED

  /**
   * The spec's `zip(...).forall` postcondition as structural recursion —
   * same clauses, provable inductively (the library `zip`/`forall`
   * generate unprovable VCs; §4 of the experience doc). `all` is the
   * full record set — each element's clause quantifies over the whole
   * input, as the spec's `records.exists` does.
   */
  @pure
  def postOk(
    all: List[(BigInt, BigInt, BigInt)],
    records: List[(BigInt, BigInt, BigInt)],
    cls: List[BigInt]
  ): Boolean = {
    decreases(records.size)
    (records, cls) match
      case (Nil(), Nil()) => true
      case (Cons((key, out, kind), rs), Cons(c, cs)) =>
        ((c == CLS_WITNESSED) ==> observedOutcomeAt(key, out, all)) &&
        (((kind == WRITTEN) && (out == BigInt(0)) && !observedAt(key, all))
          ==> (c == CLS_TESTIMONY)) &&
        postOk(all, rs, cs)
      case _ => false // danger-scan:allow shape-mismatch — lengths equal by fold construction
  }

  /**
   * The corroboration fold: one classification code per record, in
   * record order.
   *
   * A written record (kind == WRITTEN) with a green outcome (code == 0)
   * is a claim; a claim with no OBSERVED record at its key is testimony,
   * a claim whose observed records at its key include the claim's
   * outcome is witnessed, and a claim all of whose observed records at
   * its key disagree is contradicted. Self-observed and observed records
   * corroborate themselves; written records with a nonzero outcome are
   * exempt — they discharge nothing.
   */
  @pure
  // format: off — scalafmt must not reflow .ensuring off the Stainless postcondition position
  def corroborationFold(records: List[(BigInt, BigInt, BigInt)]): List[BigInt] = {
    require(validRecords(records))
    foldGo(records, records)
  }.ensuring((cls: List[BigInt]) => cls.length == records.length && postOk(records, records, cls))
  // format: on

  /**
   * The fold itself: `all` stays the full record set through the
   * recursion (each row's observers are sought across ALL records, not
   * the remaining tail) while `records` shrinks. The postcondition is
   * inductive: the recursive call delivers `postOk(all, rest, tail)`
   * and the head clause is decided by `classifyRow`'s own branch
   * conditions — no monotonicity lemma needed.
   */
  @pure
  // format: off — scalafmt must not reflow .ensuring off the Stainless postcondition position
  private def foldGo(
    all: List[(BigInt, BigInt, BigInt)],
    records: List[(BigInt, BigInt, BigInt)]
  ): List[BigInt] = {
    decreases(records.size)
    require(validRecords(all))
    records match
      case Nil() => Nil()
      case Cons((key, out, kind), rest) =>
        Cons(classifyRow(key, out, kind, all), foldGo(all, rest))
  }.ensuring((cls: List[BigInt]) => cls.length == records.length && postOk(all, records, cls))
  // format: on

end ReconcileKernel
