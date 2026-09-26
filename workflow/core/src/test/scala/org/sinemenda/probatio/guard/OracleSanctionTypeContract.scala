package org.sinemenda.probatio.guard

import org.sinemenda.probatio.core.ProbatioSuite

/**
 * Typed contract for spec: oracle-independence (spec 5 of
 * `finish-probatio-replacement`).
 *
 * This is a COMPILE-CHECKED TYPE-LEVEL CONTRACT. It pins the approved
 * public signatures via eta-expanded references; any later signature
 * drift breaks `probatio-core/Test/compile`.
 *
 * Pinned decisions for human review:
 *
 *  - `OracleSanction` requires `spec` and `requirement` — a citation-free
 *    sanction is the rubber stamp this spec prevents, and is
 *    unrepresentable (compile-negative in
 *    `FeatureFreezeGuardIntegrityTypeContract`).
 *  - `SanctionVerdict.AllSanctioned` carries the `OracleBaseline` the
 *    verdict was earned against — a passing verdict whose baseline is
 *    implicit cannot be reproduced (compile-negative).
 *  - `SanctionVerdict` has three variants: the spec's stated widening of
 *    the two-variant `OracleImmutabilityResult` it supersedes.
 *    `Unsanctioned` keeps the codebase's `List` convention (same shape
 *    as `ArmDivergence.Diverged`); the guard produces it only non-empty,
 *    which the kernel contract asserts (`isUnsanctioned ==> named.nonEmpty`).
 *  - `OracleBaseline` is a plain case class carrying the recorded commit —
 *    the spec table's "final case class (commit)". The design's "opaque
 *    over String, checked at construction by the adapter" is honoured in
 *    spirit: resolution to a real commit happens where the record is
 *    READ (the adapter), and failure is `Undeterminable`, never a
 *    defaulted pass.
 *  - `OracleModification` — a supporting carrier not in the spec's
 *    Concepts Introduced table — keeps `Unsanctioned`'s named
 *    modifications typed rather than `(String, String)` pairs.
 *  - `sanctionVerdict` takes its inputs as `Option`: `None` is the
 *    adapter's could-not-read marker and maps to `Undeterminable` —
 *    the kernel's `readable: Boolean` input, distributed over the three
 *    inputs so the verdict can name WHICH one failed.
 *  - `requirementText` and `testTitles` are injected lookups: the pure
 *    decision never reads the spec tree or the bats files itself (the
 *    same seam the hedgehog property's synthetic inputs feed).
 *  - The guard's decision functions are `???` at this step: the contract
 *    pins signatures only; bodies land at Step 3.
 *
 * spec: oracle-independence — Step 1: typed contract
 * spec: oracle-independence — Concepts Introduced (new): OracleTestKind
 * spec: oracle-independence — Concepts Introduced (new): OracleSanction
 * spec: oracle-independence — Concepts Introduced (new): SanctionVerdict
 * spec: oracle-independence — Concepts Introduced (new): OracleBaseline
 */
final class OracleSanctionTypeContract extends ProbatioSuite:

  // ── OracleTestKind — the two-variant classification ─────────────────
  val testKindBehavioural: OracleTestKind = OracleTestKind.Behavioural
  val testKindStructural: OracleTestKind  = OracleTestKind.Structural

  // ── OracleModification — one (file, commit) pair of history ─────────
  val modificationSig: (String, String) => OracleModification =
    OracleModification.apply

  // ── OracleSanction — spec and requirement are mandatory ─────────────
  val sanctionSig: (String, String, String, String) => OracleSanction =
    OracleSanction.apply

  // ── OracleBaseline — the recorded commit ────────────────────────────
  val baselineSig: String => OracleBaseline =
    OracleBaseline.apply

  // ── HistoryEntry — one commit of git history (message never read) ───
  val historyEntrySig: (String, String, List[String]) => HistoryEntry =
    HistoryEntry.apply

  // ── SanctionCheck — the per-sanction decision ───────────────────────
  val checkAcceptedSig: OracleSanction => SanctionCheck =
    SanctionCheck.Accepted.apply

  val checkRejectedUnrelatedSig: OracleSanction => SanctionCheck =
    SanctionCheck.RejectedUnrelated.apply

  val checkRejectedUnresolvableSig: OracleSanction => SanctionCheck =
    SanctionCheck.RejectedUnresolvable.apply

  // ── StructuralTest — the finding naming file and test ───────────────
  val structuralTestSig: (String, String) => StructuralTest =
    StructuralTest.apply

  // ── SanctionVerdict — the three-variant decision ────────────────────
  val allSanctionedSig: OracleBaseline => SanctionVerdict =
    SanctionVerdict.AllSanctioned.apply

  val unsanctionedSig: List[OracleModification] => SanctionVerdict =
    SanctionVerdict.Unsanctioned.apply

  val undeterminableSig: String => SanctionVerdict =
    SanctionVerdict.Undeterminable.apply

  // verdict projections
  val isAllSanctionedSig: SanctionVerdict => Boolean =
    (v: SanctionVerdict) => v.isAllSanctioned

  val isUnsanctionedSig: SanctionVerdict => Boolean =
    (v: SanctionVerdict) => v.isUnsanctioned

  val isUndeterminableSig: SanctionVerdict => Boolean =
    (v: SanctionVerdict) => v.isUndeterminable

  val namedModificationsSig: SanctionVerdict => List[OracleModification] =
    (v: SanctionVerdict) => v.namedModifications

  // ── OracleSanctionGuard — the pure decisions (bodies ??? at Step 1) ─
  val classifyTestSig: (String, String) => OracleTestKind =
    OracleSanctionGuard.classifyTest

  val batsTestBlocksSig: String => List[(String, String)] =
    OracleSanctionGuard.batsTestBlocks

  val requirementNamesSig: (String, String, List[String]) => Boolean =
    OracleSanctionGuard.requirementNames

  val checkSanctionSig: (
    OracleSanction,
    (String, String) => Option[String],
    String => List[String]
  ) => SanctionCheck =
    OracleSanctionGuard.checkSanction

  val acceptedModificationsSig: (
    List[OracleModification],
    List[OracleSanction],
    (String, String) => Option[String],
    String => List[String]
  ) => Set[OracleModification] =
    OracleSanctionGuard.acceptedModifications

  val sanctionVerdictSig: (
    Option[OracleBaseline],
    Option[List[OracleModification]],
    Option[List[OracleSanction]],
    (String, String) => Option[String],
    String => List[String]
  ) => SanctionVerdict =
    OracleSanctionGuard.sanctionVerdict

  val modificationsSinceSig: (
    OracleBaseline,
    List[HistoryEntry],
    String => Boolean
  ) => Option[List[OracleModification]] =
    OracleSanctionGuard.modificationsSince

  val structuralInSig: (String, String) => List[StructuralTest] =
    OracleSanctionGuard.structuralIn

  val writeBaselineSig: OracleBaseline => String =
    OracleSanctionGuard.writeBaseline

  val readBaselineSig: String => Either[String, OracleBaseline] =
    OracleSanctionGuard.readBaseline

  val writeSanctionsSig: List[OracleSanction] => String =
    OracleSanctionGuard.writeSanctions

  val readSanctionsSig: String => Either[String, List[OracleSanction]] =
    OracleSanctionGuard.readSanctions

  // ── The pinned surface evaluates (no verdict produced — bodies ???) ──

  test("the verdict variants carry their declared payloads"):
    val baseline: OracleBaseline = OracleBaseline("a" * 40)
    val mod: OracleModification  = OracleModification("suite.bats", "b" * 40)
    val verdicts: List[SanctionVerdict] = List(
      SanctionVerdict.AllSanctioned(baseline),
      SanctionVerdict.Unsanctioned(List(mod)),
      SanctionVerdict.Undeterminable("unreadable")
    )
    verdicts.foreach { (v: SanctionVerdict) =>
      val kinds: Int =
        List(v.isAllSanctioned, v.isUnsanctioned, v.isUndeterminable)
          .count((b: Boolean) => b)
      assertEquals(kinds, 1, "exactly one classification per verdict")
    }
    assertEquals(
      SanctionVerdict.Unsanctioned(List(mod)).namedModifications,
      List(mod)
    )

  test("a sanction carries the file, the commit, and BOTH citations"):
    val sanction: OracleSanction =
      OracleSanction("suite.bats", "c" * 40, "the-spec", "the requirement")
    assertEquals(sanction.spec, "the-spec")
    assertEquals(sanction.requirement, "the requirement")
