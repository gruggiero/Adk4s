package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.guard.CorpusResolution
import org.sinemenda.probatio.guard.FeatureFreezeGuard
import org.sinemenda.probatio.guard.FeatureFreezeVerdict
import org.sinemenda.probatio.guard.FeatureFreezeViolation
import org.sinemenda.probatio.guard.FixtureCorpus
import org.sinemenda.probatio.guard.GuardCorpusFixtures
import org.sinemenda.probatio.guard.OracleBaseline
import org.sinemenda.probatio.guard.OracleModification
import org.sinemenda.probatio.guard.OracleSanction
import org.sinemenda.probatio.guard.OracleSanctionGuard
import org.sinemenda.probatio.guard.SanctionVerdict
import org.sinemenda.probatio.verified.SpecLintKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge for `SpecLintKernel.reachabilityFold` — the exact-
 * complement contract of spec `spec-lint-engine`.
 *
 * A generated `SpecDocument` is reduced to the kernel's input space:
 * `(numRequirements, rowTargets)` where each evaluated obligation row
 * contributes the requirement indices its source resolved to, or `-1`
 * for a row that resolved to none. The property runs the shipped
 * `SpecLintEngine.reachabilityFold` and the Stainless mirror on the same
 * vectors and asserts the `(unenforced, unresolvableCount)` pairs agree —
 * and that the shipped unenforced set is exactly the requirement indices
 * that would draw F7.
 *
 * spec: spec-lint-engine — Contract: reachabilityFold (Ring 6 bridge)
 */
final class SpecLintBridgeSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  private def scalaToStainlessList[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  private def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  /**
   * The document → `(count, rowTargets)` reduction: per evaluated row,
   * the requirement indices its source resolves to (`ByTitle` /
   * `ByOrdinal`), or a single `-1` when it resolves to none.
   */
  private def rowTargetsOf(doc: SpecDocument): ScalaList[Int] =
    doc.obligationRows.flatMap { (row: ObligationRow) =>
      val covered: ScalaList[Int] = SpecLintEngine.obligationSources(doc, row).collect {
        case ObligationSource.ByTitle(i)   => i
        case ObligationSource.ByOrdinal(i) => i
      }
      if covered.isEmpty then ScalaList(-1) else covered
    }

  // spec: spec-lint-engine — Contract: reachabilityFold (bridge)
  property("bridge-reachabilityFold — mirror equals the shipped fold on generated documents", coverConfig):
    for doc <- SpecLintFixtures.genSpecDocument.forAll
        .cover(
          30,
          "some-unenforced",
          (d: SpecDocument) =>
            val targets: ScalaList[Int] = rowTargetsOf(d).filter(_ >= 0).distinct
            d.requirements.indices.exists(i => !targets.contains(i))
        )
        .cover(20, "has-unresolvable", (d: SpecDocument) => rowTargetsOf(d).contains(-1))
        // The generator gives nReqs=0 a 10% weight — a 10% label would be
        // a coin-flip at 200 samples, so the threshold sits below the mean.
        .cover(5, "no-requirements", (d: SpecDocument) => d.requirements.isEmpty)
    yield
      val nReqs: Int                 = doc.requirements.length
      val rowTargets: ScalaList[Int] = rowTargetsOf(doc)

      val (shipUnenforced, shipUnresolvable): (ScalaList[Int], Int) =
        SpecLintEngine.reachabilityFold(nReqs, rowTargets)

      val kernPair: (stainless.collection.List[BigInt], BigInt) =
        SpecLintKernel.reachabilityFold(
          BigInt(nReqs),
          scalaToStainlessList(rowTargets.map(BigInt.apply))
        )
      val kernUnenforced: ScalaList[Int] = stainlessListToScala(kernPair._1).map(_.toInt)
      val kernUnresolvable: Int          = kernPair._2.toInt

      // The shipped unenforced set is exactly the indices that draw F7.
      val coveredIdx: Set[Int] = rowTargets.filter(_ >= 0).toSet
      val expectedUnenforced: ScalaList[Int] =
        doc.requirements.indices.filterNot(coveredIdx.contains).toList

      Result
        .assert(shipUnenforced == kernUnenforced && shipUnresolvable == kernUnresolvable)
        .log(s"shipped ($shipUnenforced, $shipUnresolvable) != kernel ($kernUnenforced, $kernUnresolvable)")
        .and(
          Result
            .assert(shipUnenforced == expectedUnenforced)
            .log(s"unenforced $shipUnenforced != F7 set $expectedUnenforced")
        )

  /**
   * The fold-level bridge on arbitrary precondition-satisfying vectors —
   * reaches target shapes a generated document may not produce.
   */
  property("bridge-reachabilityFold — mirror equals the shipped fold on arbitrary target vectors"):
    val genVector: Gen[(Int, ScalaList[Int])] =
      for
        n <- Gen.int(Range.linear(0, 8))
        t <-
          // With zero requirements no in-range target exists — only -1.
          if n == 0 then Gen.constant(-1).list(Range.linear(0, 12))
          else
            Gen
              .frequency1(
                60 -> Gen.int(Range.linear(0, n - 1)),
                40 -> Gen.constant(-1)
              )
              .list(Range.linear(0, 12))
      yield (n, t)
    for (n, targets) <- genVector.forAll
        .cover(30, "has-unresolvable", (p: (Int, ScalaList[Int])) => p._2.contains(-1))
        .cover(30, "has-covered", (p: (Int, ScalaList[Int])) => p._2.exists(_ >= 0))
    yield
      val shipped: (ScalaList[Int], Int) = SpecLintEngine.reachabilityFold(n, targets)
      val kern: (stainless.collection.List[BigInt], BigInt) =
        SpecLintKernel.reachabilityFold(BigInt(n), scalaToStainlessList(targets.map(BigInt.apply)))
      Result
        .assert(
          shipped._1 == stainlessListToScala(kern._1).map(_.toInt) && shipped._2 == kern._2.toInt
        )
        .log(s"shipped $shipped != kernel (${kern._1}, ${kern._2}) on n=$n targets=$targets")

  // ---------------------------------------------------------------------------
  // guardOutcome bridge (spec: feature-freeze-guard-integrity — Contract: guardOutcome)
  // ---------------------------------------------------------------------------

  private val genVerdict: Gen[String] =
    Gen.element("clean", ScalaList("findings", "undetermined"))

  private val genAlteration: Gen[FeatureFreezeViolation.VerdictAlteration] =
    for
      fixture  <- Gen.string(Gen.alphaNum, Range.linear(3, 12)).map(n => s"$n/spec.md")
      expected <- genVerdict
      actual   <- genVerdict
    yield FeatureFreezeViolation.VerdictAlteration(fixture, expected, actual)

  /**
   * The guard classification a shipped `Outcome` reports: undetermined /
   * upheld / violation. `Ran` carrying a non-`Accepted` verdict is not a
   * guard outcome — `guardOutcome` never produces it.
   */
  private def shippedClasses(
    outcome: Outcome[FeatureFreezeVerdict]
  ): (Boolean, Boolean, Boolean) =
    outcome match
      case Outcome.Undetermined(_)                       => (true, false, false)
      case Outcome.Ran(FeatureFreezeVerdict.Accepted(_)) => (false, true, false)
      case Outcome.Finding(_)                           => (false, false, true)
      case Outcome.Ran(_)                               => (false, false, false)

  // spec: feature-freeze-guard-integrity — Contract: guardOutcome (bridge)
  property("bridge-guardOutcome — mirror equals the shipped classification on generated inputs"):
    val genInput: Gen[(Boolean, ScalaList[FeatureFreezeViolation.VerdictAlteration])] =
      for
        resolved <- Gen.boolean
        ds       <- genAlteration.list(Range.linear(0, 5))
      yield (resolved, ds)
    for (resolved, disagreements) <- genInput.forAll
        .cover(40, "resolved", (p: (Boolean, ScalaList[FeatureFreezeViolation.VerdictAlteration])) => p._1)
        .cover(40, "not-resolved", (p: (Boolean, ScalaList[FeatureFreezeViolation.VerdictAlteration])) => !p._1)
        .cover(30, "has-disagreements", (p: (Boolean, ScalaList[FeatureFreezeViolation.VerdictAlteration])) => p._2.nonEmpty)
        .cover(
          20,
          "resolved-with-disagreements",
          (p: (Boolean, ScalaList[FeatureFreezeViolation.VerdictAlteration])) => p._1 && p._2.nonEmpty
        )
    yield
      // `resolved` collapses the corpus resolution to located/not-located —
      // a located corpus is materialised through the real resolver.
      val resolution: CorpusResolution =
        GuardCorpusFixtures.withTempOpenspec { (openspec: os.Path) =>
          if resolved then
            GuardCorpusFixtures.placeActive(
              openspec,
              "change-x",
              ScalaList("cap/spec.md" -> "# Spec: X\n")
            )
          FixtureCorpus.resolve("change-x", openspec)
        }
      val shipped: Outcome[FeatureFreezeVerdict] =
        FeatureFreezeGuard.guardOutcome(resolution, disagreements)
      val (shUnd, shUp, shViol): (Boolean, Boolean, Boolean) = shippedClasses(shipped)

      val fixtureIdxs: ScalaList[BigInt] =
        disagreements.zipWithIndex.map((_, i) => BigInt(i))
      val kern: SpecLintKernel.GuardResult =
        SpecLintKernel.guardOutcome(resolved, scalaToStainlessList(fixtureIdxs))

      Result
        .assert(
          shUnd == kern.isUndetermined && shUp == kern.isUpheld && shViol == kern.isViolation
        )
        .log(
          s"shipped ($shUnd,$shUp,$shViol) != kernel (${kern.isUndetermined},${kern.isUpheld},${kern.isViolation}) " +
            s"on resolved=$resolved disagreements=${disagreements.length}"
        )
        .and(
          Result
            .assert(!shViol || disagreements.nonEmpty)
            .log("a violation must name at least one fixture")
        )
        .and(
          Result
            .assert(!shUnd || !shUp)
            .log("could-not-determine must never be upheld")
        )

  // ---------------------------------------------------------------------------
  // sanctionVerdict bridge (spec: oracle-independence — Contract: sanctionVerdict)
  // ---------------------------------------------------------------------------

  /**
   * The verdict's three classes, mirrored. `named` is recovered from the
   * shipped `Unsanctioned` payload by parsing the generated file names.
   */
  private def verdictClasses(
    v: SanctionVerdict
  ): (Boolean, Boolean, Boolean, ScalaList[BigInt]) =
    v match
      case SanctionVerdict.AllSanctioned(_)  => (true, false, false, ScalaList.empty)
      case SanctionVerdict.Undeterminable(_) => (false, false, true, ScalaList.empty)
      case SanctionVerdict.Unsanctioned(ms) =>
        (
          false,
          true,
          false,
          ms.map((m: OracleModification) =>
            BigInt(m.file.stripPrefix("mod-").stripSuffix(".bats"))
          )
        )

  // spec: oracle-independence — Contract: sanctionVerdict (bridge)
  property("bridge-sanctionVerdict — mirror equals the shipped verdict on generated inputs"):
    val genInput
      : Gen[(ScalaList[BigInt], ScalaList[BigInt], ScalaList[BigInt], Boolean, Int)] =
      for
        shape <- Gen.frequency1(
          30 -> Gen.constant(0), // all covered
          50 -> Gen.constant(1), // at least one uncovered
          20 -> Gen.constant(2)  // unconstrained mix
        )
        n    <- Gen.int(Range.linear(if shape == 1 then 1 else 0, 6))
        mods <- Gen
          .int(Range.linear(0, 30))
          .map(BigInt.apply)
          .list(Range.constant(n, n))
          .map((ms: ScalaList[BigInt]) => ms.distinct)
        mask <- shape match
          case 0 => Gen.constant(ScalaList.fill(mods.length)(true))
          case 1 =>
            Gen.boolean
              .list(Range.constant(mods.length, mods.length))
              .map((ms: ScalaList[Boolean]) =>
                if mods.nonEmpty && ms.forall((b: Boolean) => b) then ms.updated(0, false)
                else ms
              )
          case _ => Gen.boolean.list(Range.constant(mods.length, mods.length))
        foreign  <- Gen.int(Range.linear(31, 60)).map(BigInt.apply).list(Range.linear(0, 2))
        ghost    <- Gen.int(Range.linear(61, 90)).map(BigInt.apply).list(Range.linear(0, 2))
        readable <- Gen.frequency1(70 -> Gen.constant(true), 30 -> Gen.constant(false))
        failed   <- Gen.int(Range.linear(0, 2))
        accepted: ScalaList[BigInt] =
          mods.zip(mask).collect { case (m, true) => m } ++ foreign
      yield (mods, accepted, ghost, readable, failed)
    for (mods, accepted, ghost, readable, failed) <- genInput.forAll
        .cover(60, "readable", (p: (ScalaList[BigInt], ScalaList[BigInt], ScalaList[BigInt], Boolean, Int)) => p._4)
        .cover(25, "not-readable", (p: (ScalaList[BigInt], ScalaList[BigInt], ScalaList[BigInt], Boolean, Int)) => !p._4)
        .cover(
          40,
          "has-uncovered",
          (p: (ScalaList[BigInt], ScalaList[BigInt], ScalaList[BigInt], Boolean, Int)) =>
            p._1.exists(m => !p._2.contains(m))
        )
        .cover(
          30,
          "all-covered",
          (p: (ScalaList[BigInt], ScalaList[BigInt], ScalaList[BigInt], Boolean, Int)) =>
            p._1.forall(m => p._2.contains(m))
        )
    yield
      // Accepted ids get a sanction citing a requirement whose text names
      // the file's basename; ghost ids get a sanction whose requirement
      // resolves to nothing — rejected, so they never cover.
      val reqText: Map[(String, String), String] =
        accepted
          .map(id => (s"spec-$id", s"req-$id") -> s"the requirement names mod-$id.bats")
          .toMap
      val requirementText: (String, String) => Option[String] =
        (spec: String, req: String) => reqText.get((spec, req))
      val testTitles: String => ScalaList[String] =
        (_: String) => ScalaList.empty[String]
      val record: ScalaList[OracleSanction] =
        accepted.map(id =>
          OracleSanction(s"mod-$id.bats", s"c$id", s"spec-$id", s"req-$id")
        ) ++ ghost.map(id =>
          OracleSanction(s"mod-$id.bats", s"c$id", "ghost-spec", "ghost-req")
        )
      val history: ScalaList[OracleModification] =
        mods.map(id => OracleModification(s"mod-$id.bats", s"c$id"))
      val base: Option[OracleBaseline]         = Option(OracleBaseline("a" * 40))
      val hist: Option[ScalaList[OracleModification]] = Option(history)
      val rec: Option[ScalaList[OracleSanction]]      = Option(record)
      val shipped: SanctionVerdict =
        if readable then
          OracleSanctionGuard.sanctionVerdict(base, hist, rec, requirementText, testTitles)
        else
          failed match
            case 0 =>
              OracleSanctionGuard.sanctionVerdict(None, hist, rec, requirementText, testTitles)
            case 1 =>
              OracleSanctionGuard.sanctionVerdict(base, None, rec, requirementText, testTitles)
            case _ =>
              OracleSanctionGuard.sanctionVerdict(base, hist, None, requirementText, testTitles)

      val kern: SpecLintKernel.VerdictModel =
        SpecLintKernel.sanctionVerdict(
          scalaToStainlessList(mods),
          scalaToStainlessList(accepted),
          readable
        )
      val (shAll, shUns, shUnd, shNamed): (Boolean, Boolean, Boolean, ScalaList[BigInt]) =
        verdictClasses(shipped)
      val kernNamed: ScalaList[BigInt] = stainlessListToScala(kern.named)

      Result
        .assert(
          shAll == kern.isAllSanctioned && shUns == kern.isUnsanctioned &&
            shUnd == kern.isUndeterminable
        )
        .log(
          s"shipped ($shAll,$shUns,$shUnd) != kernel " +
            s"(${kern.isAllSanctioned},${kern.isUnsanctioned},${kern.isUndeterminable}) " +
            s"on mods=$mods accepted=$accepted readable=$readable"
        )
        .and(
          Result
            .assert(shNamed == kernNamed)
            .log(s"shipped named $shNamed != kernel named $kernNamed")
        )
        .and(
          Result
            .assert(!shUnd || !shAll)
            .log("could-not-determine must never be all-sanctioned")
        )

end SpecLintBridgeSpec
