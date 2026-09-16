package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import org.sinemenda.probatio.verified.BannerEngineKernel

import scala.collection.immutable.List as ScalaList

/**
 * Ring 6 bridge for `BannerEngineKernel.bannerClaims` — the emitted-claim
 * contract of spec `live-fact-banner`.
 *
 * The fact-code vector abstracts the facts record: `-1` unreadable,
 * `0` absent, `n > 0` present with count `n`. The shipped renderer's claim
 * extraction reads the emitted banner text back into the same code space:
 * the five always-emitted scalar fact claims (schema, registry, inventory,
 * profile, active-changes count) in emission order. The property runs the
 * kernel and the shipped renderer on the SAME generated fact vectors and
 * asserts the emitted claims equal the read fact codes elementwise — an
 * unreadable fact is never claimed absent.
 *
 * spec: live-fact-banner — Formal Contract: bannerClaims (Ring 6 bridge)
 */
final class BannerBridgeSpec extends ProbatioSuite:

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  // ── Scala ↔ Stainless list conversion (same as VerifiedKernelBridgeSpec) ──

  private def scalaToStainlessList[A](xs: ScalaList[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  private def stainlessListToScala[A](xs: stainless.collection.List[A]): ScalaList[A] =
    xs match
      case stainless.collection.Nil()      => ScalaList.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  // ── Generators ─────────────────────────────────────────────────────────

  /** A fact code: `-1` unreadable, `0` absent, `n > 0` present-with-count. */
  private val genFactCode: Gen[BigInt] =
    Gen.frequency1(
      15 -> Gen.constant(BigInt(-1)),
      30 -> Gen.constant(BigInt(0)),
      55 -> Gen.int(Range.linear(1, 40)).map((i: Int) => BigInt(i))
    )

  /** A well-formed fact-code vector of arbitrary length (kernel-side). */
  private val genFactVector: Gen[ScalaList[BigInt]] =
    genFactCode.list(Range.linear(0, 12))

  /** A single-valued fact code — present is count-less, so n is 0 or 1. */
  private val genBooleanFactCode: Gen[BigInt] =
    Gen.frequency1(
      15 -> Gen.constant(BigInt(-1)),
      40 -> Gen.constant(BigInt(0)),
      45 -> Gen.constant(BigInt(1))
    )

  /**
   * The scalar fact vector — one code per always-emitted scalar claim:
   * `[schema, registry, inventory, profile, activeChanges]`. The profile
   * claim is count-less, so its code is drawn from the boolean domain.
   */
  private val genScalarVector: Gen[ScalaList[BigInt]] =
    for
      schema    <- genFactCode
      registry  <- genFactCode
      inventory <- genFactCode
      profile   <- genBooleanFactCode
      changes   <- genFactCode
    yield ScalaList(schema, registry, inventory, profile, changes)

  // ── fact-code vector → production RepositoryFacts ───────────────────────
  //
  // Each code maps to the three `FactRead` states; `n > 0` carries a count.

  private def codeToCountFact(code: BigInt): FactRead[Int] =
    if code == BigInt(-1) then FactRead.Unreadable("bridge-unreadable")
    else if code == BigInt(0) then FactRead.Absent
    else FactRead.Present(code.toInt)

  private def codeToProfileFact(code: BigInt): FactRead[Option[String]] =
    if code == BigInt(-1) then FactRead.Unreadable("bridge-unreadable")
    else if code == BigInt(0) then FactRead.Absent
    else FactRead.Present(Option("munit"))

  private def codeToChangesFact(code: BigInt): FactRead[List[ActiveChangeWithChainState]] =
    if code == BigInt(-1) then FactRead.Unreadable("bridge-unreadable")
    else if code == BigInt(0) then FactRead.Absent
    else
      FactRead.Present(
        List.tabulate(code.toInt) { (i: Int) =>
          ActiveChangeWithChainState(
            name = s"bridge-change-$i",
            artifacts = FactRead.Absent,
            chainState = Left(ChainStateUndetermined("bridge-change", "b", "bridge"))
          )
        }
      )

  private def factsOf(vector: ScalaList[BigInt]): RepositoryFacts =
    RepositoryFacts(
      schemaVersion = codeToCountFact(vector(0)),
      registry = codeToCountFact(vector(1)),
      inventory = codeToCountFact(vector(2)),
      profile = codeToProfileFact(vector(3)),
      installRoots = ScalaList.empty,
      activeChanges = codeToChangesFact(vector(4))
    )

  // ── emitted banner → claim-code vector ──────────────────────────────────
  //
  // Claim extraction: for each scalar fact line the banner always emits,
  // UNREADABLE → -1, ABSENT → 0, PRESENT → the stated count (or 1 for the
  // count-less profile claim). The active-changes claim is the number of
  // `  active change <name>` lines emitted (-1 on the UNREADABLE line).

  private def scalarClaim(
    text: String,
    marker: String,
    countOf: String => Option[BigInt]
  ): Option[BigInt] =
    text.linesIterator.find(_.contains(marker)).flatMap { (l: String) =>
      if l.contains("UNREADABLE") then Option(BigInt(-1))
      else if l.contains("ABSENT") then Option(BigInt(0))
      else countOf(l)
    }

  private def countIn(line: String, pattern: String): Option[BigInt] =
    pattern.r
      .findFirstMatchIn(line)
      .map((m: scala.util.matching.Regex.Match) => BigInt(m.group(1)))

  /** The claim vector the emitted banner text carries, in emission order. */
  private def extractClaimVector(text: String): Option[ScalaList[BigInt]] =
    val schemaClaim: Option[BigInt] = scalarClaim(
      text,
      "openspec/schemas/verified-scala3",
      (l: String) => countIn(l, "v(\\d+)")
    )
    val registryClaim: Option[BigInt] = scalarClaim(
      text,
      "openspec/concepts/",
      (l: String) => countIn(l, "PRESENT \\((\\d+) concepts\\)")
    )
    val inventoryClaim: Option[BigInt] = scalarClaim(
      text,
      "openspec/concept-inventory.md",
      (l: String) => countIn(l, "PRESENT \\((\\d+) typed rows\\)")
    )
    val profileClaim: Option[BigInt] = scalarClaim(
      text,
      "openspec/capability-profile.md",
      (l: String) => if l.contains("PRESENT") then Option(BigInt(1)) else Option.empty[BigInt]
    )
    val changesClaim: BigInt =
      if text.linesIterator.exists(l => l.startsWith("  active changes") && l.contains("UNREADABLE"))
      then BigInt(-1)
      else BigInt(text.linesIterator.count(_.startsWith("  active change ")))
    for
      s <- schemaClaim
      r <- registryClaim
      i <- inventoryClaim
      p <- profileClaim
    yield ScalaList(s, r, i, p, changesClaim)

  // ── the bridge property ─────────────────────────────────────────────────

  // spec: live-fact-banner — Formal Contract: bannerClaims (bridge)
  property("bridge-bannerClaims — emitted claims equal the read fact codes", coverConfig):
    for vector <- genScalarVector.forAll yield
      val facts: RepositoryFacts             = factsOf(vector)
      val text: String                       = BannerEngine.render(BannerInputs.from(facts)).payload
      val emitted: Option[ScalaList[BigInt]] = extractClaimVector(text)
      val kernel: ScalaList[BigInt] =
        stainlessListToScala(BannerEngineKernel.bannerClaims(scalaToStainlessList(vector)))
      Result
        .assert(kernel == vector)
        .log(s"kernel claims $kernel != facts $vector")
        .and(
          Result
            .assert(emitted.contains(vector))
            .log(s"emitted claims $emitted != facts $vector\n$text")
        )

  // The kernel contract on arbitrary-length well-formed fact vectors —
  // exercises the recursion the fixed-arity scalar property cannot.
  property("bridge-bannerClaims — the kernel preserves any well-formed fact vector"):
    for vector <- genFactVector.forAll yield
      val claims: ScalaList[BigInt] =
        stainlessListToScala(BannerEngineKernel.bannerClaims(scalaToStainlessList(vector)))
      Result
        .assert(claims == vector)
        .log(s"kernel claims $claims != facts $vector")

end BannerBridgeSpec
