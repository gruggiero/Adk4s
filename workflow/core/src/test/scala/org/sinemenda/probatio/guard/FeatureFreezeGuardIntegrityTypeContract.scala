package org.sinemenda.probatio.guard

import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Typed contract for spec: feature-freeze-guard-integrity
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references; any later signature drift
 * breaks `probatio-core/Test/compile`.
 *
 * Pinned decisions for human review:
 *
 *  - `FixtureCorpus` is a `final class` with a `private` constructor — NOT
 *    the spec table's literal "final case class": a private case-class
 *    constructor still emits a public `copy`, and `corpus.copy(specs = Nil)`
 *    would rebuild the empty corpus this type exists to make
 *    unrepresentable. `specs` are fixture paths relative to `origin` so an
 *    active and an archived placement of the same corpus compare equal.
 *  - `CorpusResolution.NotFound` carries the searched locations as a
 *    required field — a not-found that cannot say where it looked is
 *    unconstructible.
 *  - `FeatureFreezeVerdict.Accepted` takes a `FixtureCorpus` — a
 *    freeze-upheld verdict is unreachable without a resolved corpus.
 *  - `reviewFeatureFreeze` takes a mandatory `FeatureFreezeViolation` and
 *    always rejects: acceptance is only produced by `guardOutcome`, which
 *    owns the corpus run.
 *  - `guardOutcome` returns `Outcome[FeatureFreezeVerdict]` — the existing
 *    three-way algebra, no parallel hierarchy. `disagreements` refines the
 *    contract's `List[FixtureVerdict]` to `List[VerdictAlteration]`: the
 *    differing-verdict report must name the fixture and BOTH verdicts,
 *    which is what `VerdictAlteration` carries.
 *  - `resolve`/`guardOutcome` were `???` at the Step-1/2 polarity gates;
 *    Step 3 implemented them — this file remains the signature pin.
 *
 * spec: feature-freeze-guard-integrity — Step 1: typed contract (minimal — test-corpus resolution only)
 * spec: feature-freeze-guard-integrity — Concepts Introduced (new): FixtureCorpus
 * spec: feature-freeze-guard-integrity — Concepts Introduced (new): CorpusResolution
 */
final class FeatureFreezeGuardIntegrityTypeContract extends ProbatioSuite:

  import CorpusResolution.*
  import FeatureFreezeVerdict.*

  // ── FixtureCorpus — private-constructor corpus ──────────────────────
  // resolve: (String, os.Path) => CorpusResolution
  val resolveSig: (String, os.Path) => CorpusResolution =
    FixtureCorpus.resolve

  // searchedLocations: (String, os.Path) => List[os.Path]
  val searchedLocationsSig: (String, os.Path) => List[os.Path] =
    FixtureCorpus.searchedLocations

  // specs / origin projections
  val corpusSpecsSig: FixtureCorpus => List[String] =
    (c: FixtureCorpus) => c.specs

  val corpusOriginSig: FixtureCorpus => os.Path =
    (c: FixtureCorpus) => c.origin

  val corpusFixturePathSig: (FixtureCorpus, String) => os.Path =
    (c: FixtureCorpus, spec: String) => c.fixturePath(spec)

  // ── CorpusResolution — two-variant resolution ───────────────────────
  val resolvedSig: FixtureCorpus => CorpusResolution =
    CorpusResolution.Resolved.apply

  val notFoundSig: List[os.Path] => CorpusResolution =
    CorpusResolution.NotFound.apply

  val isResolvedSig: CorpusResolution => Boolean =
    (r: CorpusResolution) => r.isResolved

  val isNotFoundSig: CorpusResolution => Boolean =
    (r: CorpusResolution) => r.isNotFound

  val resolutionCorpusSig: CorpusResolution => Option[FixtureCorpus] =
    (r: CorpusResolution) => r.corpusOption

  // ── FeatureFreezeVerdict — the accepted variant takes a corpus ──────
  val acceptedSig: FixtureCorpus => FeatureFreezeVerdict =
    FeatureFreezeVerdict.Accepted.apply

  val rejectedSig: (FeatureFreezeViolation, String) => FeatureFreezeVerdict =
    FeatureFreezeVerdict.Rejected.apply

  // ── FeatureFreezeGuard — the two decisions ──────────────────────────
  val reviewSig: FeatureFreezeViolation => FeatureFreezeVerdict =
    FeatureFreezeGuard.reviewFeatureFreeze

  val guardOutcomeSig: (
    CorpusResolution,
    List[FeatureFreezeViolation.VerdictAlteration]
  ) => Outcome[FeatureFreezeVerdict] =
    FeatureFreezeGuard.guardOutcome

  // FeatureFreezeGuard.unknownCheckIds: Set[String] => Set[String]
  val unknownCheckIdsSig: Set[String] => Set[String] =
    FeatureFreezeGuard.unknownCheckIds

  // ── KnownCheckId — the closed identifier set, unchanged ─────────────
  val knownIdsSig: Set[String] = KnownCheckId.allIds

  // ── The pinned surface evaluates (no resolve — bodies are ???) ──────

  test("NotFound carries the locations searched and is not resolved"):
    val searched: List[os.Path] = List(os.pwd / "a", os.pwd / "b")
    val resolution: CorpusResolution = NotFound(searched)
    assert(resolution.isNotFound)
    assert(!resolution.isResolved)
    assertEquals(resolution.corpusOption, None)
    resolution match
      case NotFound(found) => assertEquals(found, searched)
      case Resolved(_)     => fail("NotFound must not match Resolved")

  test("every violation class is rejected with a reason"):
    val violations: List[FeatureFreezeViolation] = List(
      FeatureFreezeViolation.NewLintCheck("F11"),
      FeatureFreezeViolation.VerdictAlteration("f", "clean", "findings"),
      FeatureFreezeViolation.NewWorkflowFeature("new tier")
    )
    violations.foreach { (v: FeatureFreezeViolation) =>
      FeatureFreezeGuard.reviewFeatureFreeze(v) match
        case Rejected(_, reason) => assert(reason.nonEmpty, "rejection must carry a reason")
        case Accepted(_)         => fail("a constructed violation must never be accepted")
    }

  test("the closed check-identifier set is F1–F10 and nothing else"):
    assertEquals(KnownCheckId.allIds, (1 to 10).map(i => s"F$i").toSet)
    assert(!KnownCheckId.isKnown("F11"))
    assert(!KnownCheckId.isKnown("W3"))

  // ── Compile-Negative: A sanction with no cited requirement
  // spec: oracle-independence — Compile-Negative: A sanction with no cited requirement
  // The snippet is same-package-resolvable so a passing compile could only
  // mean the constructor dropped its mandatory fields; the assertion pins
  // the missing-argument diagnostic on `spec` specifically, so a failure
  // that went away cannot surface as a silent unrelated error.
  test("compile-negative: a sanction without spec and requirement does not compile"):
    val err: String =
      compileErrors("""OracleSanction("suite.bats", "deadbeef")""")
    assert(
      err.nonEmpty && err.contains("spec"),
      s"OracleSanction(file, commit) must not compile — spec and requirement are required; got: $err"
    )

  // ── Compile-Negative: A guard verdict built without the baseline
  // spec: oracle-independence — Compile-Negative: A guard verdict built without the baseline
  // `AllSanctioned` bare eta-expands to a FUNCTION, so the failing shape is
  // assigning it where a `SanctionVerdict` is required — the verdict must
  // carry the baseline it was earned against.
  test("compile-negative: a passing verdict without a baseline does not compile"):
    val err: String =
      compileErrors("val v: SanctionVerdict = SanctionVerdict.AllSanctioned")
    assert(
      err.nonEmpty && err.contains("AllSanctioned"),
      s"SanctionVerdict.AllSanctioned without a baseline must not compile; got: $err"
    )

  // ── Compile-Negative: An absent location that names no searched place
  // spec: archive-safe-fixtures — Compile-Negative: An absent location that names no searched place
  // `ChangeLocation` lives in `org.sinemenda.probatio.migration` — the
  // snippet is fully qualified so the failure, if it ever went away,
  // cannot be a silent unresolved-name error: the assertion pins the
  // missing-parameter diagnostic specifically.
  test("compile-negative: an absent location without searched places does not compile"):
    val err: String =
      compileErrors("org.sinemenda.probatio.migration.ChangeLocation.Absent()")
    assert(
      err.nonEmpty && err.contains("searched"),
      s"Absent() must not compile — the variant requires the searched list; got: $err"
    )
