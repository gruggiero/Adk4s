package org.sinemenda.probatio.cli

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.core.EventDispatch
import org.sinemenda.probatio.core.RefusalBudget
import org.sinemenda.probatio.core.ToolOutcome
import org.sinemenda.probatio.verified.DispatchKernel
import org.sinemenda.probatio.verified.GateKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge for `GateKernel` — the spec-8 verified mirror.
 *
 * Runs the shipped `RefusalBudget.apply` / `ToolOutcome.classify` against
 * the Stainless `GateKernel.refusalBudget` / `classifyOutcome` on the same
 * generated inputs and asserts they agree.
 *
 * Abstraction (mirroring `GateKernel`'s own doc):
 *   - `ToolOutcome` → `Option[BigInt]`: `Exit(code)` is `Some(code)`,
 *     any `Skip` is `None`.
 *   - The harness response → `shape`: 0 = object without `interrupted`,
 *     1 = object with `interrupted`, 2 = `"Error: Exit code N"`,
 *     3 = any other string, 4 = any other shape.
 *   - `carriedCode` is bounded to `Int` — the shipped classifier can only
 *     carry an `Int`; the kernel's unbounded `BigInt` code is the model
 *     of the digit string, and an out-of-range string legitimately
 *     classifies as `Skip` (unrecordable) rather than `Exit`.
 *
 * spec: gate-event-completeness — Contract: refusalBudget (Ring 6 bridge)
 * spec: gate-event-completeness — Contract: classifyOutcome (Ring 6 bridge)
 */
final class GateBridgeSpec extends ProbatioCliSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  private def scalaToStainless[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  private def stainlessToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessToScala(t)

  /** The shipped classification reduced to the kernel's `Option[BigInt]`. */
  private def shippedOutcome(response: ujson.Value): Option[BigInt] =
    ToolOutcome.classify(response) match
      case ToolOutcome.Exit(code: Int) => Some(BigInt(code))
      case ToolOutcome.Skip(_: String) => None

  /** A `ujson` response shaped as the kernel's `shape` code describes. */
  private def responseOfShape(shape: Int, code: Int): ujson.Value =
    shape match
      case 0 => ujson.Obj("output" -> ujson.Str("ok"))
      case 1 => ujson.Obj("interrupted" -> ujson.Bool(true))
      case 2 => ujson.Str(s"Error: Exit code $code")
      case 3 => ujson.Str("Error: This command requires approval")
      case _ => // danger-scan:allow shape-4 — any non-object non-string value qualifies
        ujson.Arr(ujson.Str("x"))

  // spec: gate-event-completeness — Contract: refusalBudget (bridge)
  property("bridge-refusalBudget — mirror equals the shipped budget on generated turns", coverConfig):
    for blockable <- Gen
        .list(Gen.boolean, Range.linear(1, 12))
        .filter((bs: ScalaList[Boolean]) => bs.contains(true))
        .forAll
        .cover(30, "multiple-blockable", (bs: ScalaList[Boolean]) => bs.count(identity) >= 2)
        .cover(20, "first-not-blockable", (bs: ScalaList[Boolean]) => !bs.headOption.getOrElse(true))
    yield
      val shipped: ScalaList[Boolean] = RefusalBudget(blockable)
      val kernel: ScalaList[Boolean] =
        stainlessToScala(GateKernel.refusalBudget(scalaToStainless(blockable)))
      if shipped == kernel then Result.success
      else Result.failure.log(s"divergence on $blockable: shipped=$shipped kernel=$kernel")

  // spec: gate-event-completeness — Contract: classifyOutcome (bridge)
  property("bridge-classifyOutcome — mirror equals the shipped classifier on generated shapes", coverConfig):
    for
      shape <- Gen.element1(0, 1, 2, 3, 4).forAll
      code  <- Gen.int(Range.linear(0, Int.MaxValue)).forAll
    yield
      val shipped: Option[BigInt] = shippedOutcome(responseOfShape(shape, code))
      val kernel: Option[BigInt] =
        GateKernel.classifyOutcome(BigInt(shape), BigInt(code)) match
          case stainless.lang.Some(v) => Some(v)
          case _                      => None // danger-scan:allow stainless None() → scala None
      if shipped == kernel then Result.success
      else
        Result.failure.log(
          s"divergence on shape=$shape code=$code: shipped=$shipped kernel=$kernel"
        )

  // spec: gate-event-compatibility — Contract: classify (bridge)
  //
  // The shipped `EventDispatch.classify` run against the Stainless
  // `DispatchKernel.classifyEvent` on the same generated names. The
  // abstraction (shared with `DispatchKernel`'s own doc): names reduce
  // to codes — `1..6` for the recognised names (position in
  // `EventDispatch.recognisedNames`), `0` for every other string. The
  // tier arm additionally re-checks the shipped event's own token, so a
  // name→event mapping drift inside the shipped table is caught here
  // and not only by the scenario pins.
  property("bridge-classifyEvent — mirror equals the shipped event classification", coverConfig):
    for name <- EventDispatchFixtures.genEventName.forAll
    yield
      val code: BigInt                              = EventDispatchFixtures.eventNameCode(name)
      val shipped: EventDispatch                    = EventDispatch.classify(name)
      val kernel: DispatchKernel.EventDispatchModel = DispatchKernel.classifyEvent(code)
      (shipped, kernel) match
        case (EventDispatch.Tier(event), DispatchKernel.EventTier(tierCode)) =>
          Result
            .assert(
              EventDispatchFixtures.eventNameCode(EventDispatchFixtures.tokenOf(event)) == tierCode
            )
            .log(s"tier-code divergence for '$name': event=$event kernel=$tierCode")
        case (EventDispatch.Injection(supplied), DispatchKernel.EventInjection(suppliedCode)) =>
          Result.all(
            List(
              Result
                .assert(supplied == name)
                .log(s"the injection lost the supplied name: '$supplied' vs '$name'"),
              Result
                .assert(suppliedCode == code)
                .log(s"kernel injection code drift: $suppliedCode vs $code")
            )
          )
        case _ => // danger-scan:allow bridge assertion — a shape divergence fails the property
          Result.failure.log(
            s"dispatch shape divergence for '$name': shipped=$shipped kernel=$kernel"
          )
