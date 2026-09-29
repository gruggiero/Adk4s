package org.sinemenda.probatio.core

import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.verified.ReconcileKernel

import scala.collection.immutable.List as ScalaList

import ReconcileFixtures.genRecordSet

/**
 * Ring 6 bridge for `ReconcileKernel.corroborationFold` — the
 * corroboration contract of spec `danger-reconcile-engines`.
 *
 * A generated record set is reduced to the kernel's input space:
 * `(keyIndex, outcomeCode, observationKind)` per record. The key index
 * collapses (spec, ring, baseline, command) — equality, not structure,
 * is what classification reads. The outcome code collapses exit codes
 * to zero/nonzero — the only distinction the corroboration decision
 * uses (a claim's exit is 0; a witness corroborates iff its exit is 0).
 * A judgment-ring or failing written row is encoded WRITTEN with a
 * nonzero outcome — it is not a corroboratable claim, so both the
 * kernel and the engine classify it exempt.
 *
 * The property runs the shipped `ReconcileEngine.classify` and the
 * Stainless mirror on the same vectors and asserts the classification
 * codes agree per record, in record order.
 *
 * spec: danger-reconcile-engines — Contract: corroborationFold (Ring 6 bridge)
 */
final class ReconcileBridgeSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  private def scalaToStainlessList[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  private def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  /** The engine's classification as a kernel code. */
  private def codeOf(c: Corroboration): BigInt = c match
    case Corroboration.SelfObserved       => ReconcileKernel.CLS_SELF_OBSERVED
    case Corroboration.Witnessed(_, _, _) => ReconcileKernel.CLS_WITNESSED
    case Corroboration.Testimony          => ReconcileKernel.CLS_TESTIMONY
    case Corroboration.Contradicted(_, _) => ReconcileKernel.CLS_CONTRADICTED
    case Corroboration.Exempt             => ReconcileKernel.CLS_EXEMPT

  /**
   * Encode a record as `(keyIndex, outcomeCode, kind)`.
   *
   * `kind` is the observation kind — digest rows are SELF_OBSERVED,
   * ambient rows OBSERVED, bare written rows WRITTEN. The outcome code
   * is 0 only where the record is a green claim: a deterministic-ring
   * written row with exit 0. Every other record encodes its
   * zero/nonzero exit distinction (nonzero collapses to 1) — enough for
   * the key-agreement check, which only ever compares to the claim's 0.
   */
  private def encode(records: ScalaList[ValidatedRecord]): ScalaList[(BigInt, BigInt, BigInt)] =
    val keys: ScalaList[(String, String, String, String)] =
      records
        .map(r =>
          (
            r.record.spec,
            Ring.asString(r.record.ring),
            r.record.baseline,
            r.record.command
          )
        )
        .distinct
    records.map { (r: ValidatedRecord) =>
      val keyIdx: BigInt = BigInt(
        keys.indexOf(
          (
            r.record.spec,
            Ring.asString(r.record.ring),
            r.record.baseline,
            r.record.command
          )
        )
      )
      val kind: BigInt =
        if r.provenance.digest.nonEmpty then ReconcileKernel.SELF_OBSERVED
        else if r.provenance.source.contains("ambient") then ReconcileKernel.OBSERVED
        else ReconcileKernel.WRITTEN
      val out: BigInt =
        if r.record.exit != 0 ||
          (kind == ReconcileKernel.WRITTEN &&
            ReconcileEngine.judgmentRings.contains(r.record.ring))
        then BigInt(1)
        else BigInt(0)
      (keyIdx, out, kind)
    }

  // spec: danger-reconcile-engines — Contract: corroborationFold (bridge)
  property("bridge-corroborationFold — mirror equals the shipped classifier on generated record sets", coverConfig):
    for pair <- genRecordSet.forAll
        .cover(
          25,
          "has-testimony",
          (p: (String, ScalaList[ValidatedRecord])) =>
            ReconcileEngine.classify(p._2, p._1, None, None).testimony.nonEmpty
        )
        .cover(
          25,
          "has-witnessed",
          (p: (String, ScalaList[ValidatedRecord])) =>
            ReconcileEngine.classify(p._2, p._1, None, None).witnessed.nonEmpty
        )
        .cover(
          15,
          "has-contradicted",
          (p: (String, ScalaList[ValidatedRecord])) =>
            ReconcileEngine.classify(p._2, p._1, None, None).contradicted.nonEmpty
        )
        .cover(
          15,
          "has-exempt",
          (p: (String, ScalaList[ValidatedRecord])) =>
            ReconcileEngine
              .classify(p._2, p._1, None, None)
              .classifications
              .exists(_.corroboration == Corroboration.Exempt)
        )
        .cover(
          20,
          "has-self-observed",
          (p: (String, ScalaList[ValidatedRecord])) =>
            ReconcileEngine
              .classify(p._2, p._1, None, None)
              .classifications
              .exists(_.corroboration == Corroboration.SelfObserved)
        )
    yield
      val (change: String, records: ScalaList[ValidatedRecord]) = pair
      val shipped: ScalaList[BigInt] =
        ReconcileEngine
          .classify(records, change, None, None)
          .classifications
          .map(c => codeOf(c.corroboration))
      val kern: ScalaList[BigInt] =
        stainlessListToScala(
          ReconcileKernel.corroborationFold(scalaToStainlessList(encode(records)))
        )
      Result
        .assert(shipped == kern)
        .log(s"shipped $shipped != kernel $kern")

end ReconcileBridgeSpec
