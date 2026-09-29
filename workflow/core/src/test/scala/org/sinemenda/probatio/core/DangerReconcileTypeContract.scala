package org.sinemenda.probatio.core

/**
 * Typed contract for the danger-scan and reconcile engines (spec 6,
 * Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3
 * implementation must satisfy — compiled under the real probatio-core
 * classpath, `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `DangerPattern` is a sealed 8-case enum; `label` returns the
 *    predecessor's report token verbatim (`unsafe-get` … `lint-off`).
 *  - `DangerHit` is a plain record: file, 1-based line, pattern, matched
 *    text, and the same-line-justification flag. Justified occurrences
 *    are still constructed — exclusion is the report's job, never the
 *    matcher's.
 *  - `DangerReport` has a private constructor and a sealed `copy`: the
 *    only construction route is `DangerReport.of`, which partitions the
 *    scan's occurrence list on `justified`. `hitCount` is `hits.length`
 *    — a report whose summary disagrees with its contents is
 *    unrepresentable.
 *  - `DangerScanEngine.scan` takes `List[ScannedFile]` (path + lines) —
 *    the engine performs no I/O; `ChangedFilesReader` (cli) owns the
 *    git/file boundary.
 *  - `Corroboration` is a sealed 5-case enum. `Witnessed` and
 *    `Contradicted` carry the observing `ValidatedRecord`s — a witness
 *    verdict without a witness does not compile.
 *  - `ReconcileEngine.classify` takes `List[ValidatedRecord]` + the
 *    predecessor's change/spec/baseline filter, and returns a
 *    `ReconcileReport` covering every in-scope record exactly once.
 *  - `ReconcileReport` has a private constructor; every count and
 *    verdict list is a derived view of `classifications`. The report
 *    carries no discharge verdict — that is chain-state's decision.
 *  - `ReconcileEngine.judgmentRings` is `Set(R2, R8, Manual)` — the
 *    predecessor's `["R2", "R8", "manual"]`.
 *
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerPattern
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerHit
 * spec: danger-reconcile-engines — Concepts Introduced (new): DangerReport
 * spec: danger-reconcile-engines — Concepts Introduced (new): Corroboration
 * spec: danger-reconcile-engines — Concepts Introduced (new): ReconcileReport
 */
final class DangerReconcileTypeContract extends ProbatioSuite:

  // ── DangerPattern — the eight pattern classes ───────────────────────
  val patternLabelSig: DangerPattern => String =
    DangerPattern.label

  // ── DangerHit — one occurrence ──────────────────────────────────────
  val hitSig: (String, Int, DangerPattern, String, Boolean) => DangerHit =
    DangerHit.apply

  val hitFieldsSig: DangerHit => (String, Int, DangerPattern, String, Boolean) =
    (h: DangerHit) => (h.file, h.line, h.pattern, h.text, h.justified)

  // ── DangerReport — smart-constructor surface ────────────────────────
  val reportOfSig: List[DangerHit] => DangerReport =
    DangerReport.of

  val reportViewsSig: DangerReport => (List[DangerHit], Int, Int) =
    (r: DangerReport) => (r.hits, r.justifiedExcluded, r.hitCount)

  // ── DangerScanEngine — the pure scan fold ───────────────────────────
  val scannedFileSig: (String, List[String]) => DangerScanEngine.ScannedFile =
    DangerScanEngine.ScannedFile.apply

  val scanLineSig: (String, Int, String) => List[DangerHit] =
    DangerScanEngine.scanLine

  val scanSig: List[DangerScanEngine.ScannedFile] => DangerReport =
    DangerScanEngine.scan

  // ── Corroboration — the five classification classes ─────────────────
  val selfObservedSig: Corroboration =
    Corroboration.SelfObserved

  val testimonySig: Corroboration =
    Corroboration.Testimony

  val exemptSig: Corroboration =
    Corroboration.Exempt

  val witnessedSig: (ValidatedRecord, List[ValidatedRecord], List[ValidatedRecord]) => Corroboration =
    Corroboration.Witnessed.apply

  val contradictedSig: (ValidatedRecord, List[ValidatedRecord]) => Corroboration =
    Corroboration.Contradicted.apply

  val verdictTokenSig: Corroboration => String =
    Corroboration.verdictToken

  // ── ClaimVerdict — the predecessor's per-claim entry ────────────────
  val claimVerdictSig: (String, Ring, String, String, String, String, List[BigInt]) => ClaimVerdict =
    ClaimVerdict.apply

  // ── ReconcileEngine — the pure classification fold ──────────────────
  val classifiedSig: (ValidatedRecord, Corroboration) => ReconcileEngine.Classified =
    ReconcileEngine.Classified.apply

  val judgmentRingsSig: Set[Ring] =
    ReconcileEngine.judgmentRings

  val classifySig: (
    List[ValidatedRecord],
    String,
    Option[String],
    Option[String]
  ) => ReconcileReport = ReconcileEngine.classify

  // ── ReconcileReport — smart-constructor surface ─────────────────────
  val reconcileOfSig: (String, List[ReconcileEngine.Classified]) => ReconcileReport =
    ReconcileReport.of

  val reconcileViewsSig: ReconcileReport => (Int, Int) =
    (r: ReconcileReport) => (r.rows, r.witnesses)

  val reconcileVerdictsSig
    : ReconcileReport => (List[ClaimVerdict], List[ClaimVerdict], List[ClaimVerdict], List[ClaimVerdict]) =
    (r: ReconcileReport) => (r.claims, r.witnessed, r.testimony, r.contradicted)

  val hasFindingsSig: ReconcileReport => Boolean =
    (r: ReconcileReport) => r.hasFindings

  // ── The pinned surface evaluates ────────────────────────────────────
  test("DangerPattern labels are the predecessor's report tokens"):
    assertEquals(DangerPattern.label(DangerPattern.UnsafeGet), "unsafe-get")
    assertEquals(DangerPattern.label(DangerPattern.UnsafeHead), "unsafe-head")
    assertEquals(DangerPattern.label(DangerPattern.CatchAll), "catch-all")
    assertEquals(DangerPattern.label(DangerPattern.Cast), "cast")
    assertEquals(DangerPattern.label(DangerPattern.Blocking), "blocking")
    assertEquals(DangerPattern.label(DangerPattern.Swallowed), "swallowed")
    assertEquals(DangerPattern.label(DangerPattern.UnreachableClaim), "unreachable-claim")
    assertEquals(DangerPattern.label(DangerPattern.LintOff), "lint-off")
    assertEquals(DangerPattern.values.length, 8)

  test("DangerReport.of partitions occurrences on justified — hitCount derives"):
    val unjustified: DangerHit = DangerHit("a.scala", 3, DangerPattern.CatchAll, "case _ =>", justified = false)
    val justified: DangerHit   = DangerHit("a.scala", 4, DangerPattern.UnsafeGet, "x.get", justified = true)
    val report: DangerReport   = DangerReport.of(List(unjustified, justified))
    assertEquals(report.hits, List(unjustified))
    assertEquals(report.justifiedExcluded, 1)
    assertEquals(report.hitCount, 1)

  test("judgmentRings is the predecessor's set"):
    assertEquals(ReconcileEngine.judgmentRings, Set(Ring.R2, Ring.R8, Ring.Manual))

  test("verdictToken covers all five classes"):
    assertEquals(Corroboration.verdictToken(Corroboration.SelfObserved), "self-observed")
    assertEquals(Corroboration.verdictToken(Corroboration.Testimony), "testimony")
    assertEquals(Corroboration.verdictToken(Corroboration.Exempt), "exempt")

  test("ReconcileReport views derive from classifications"):
    val report: ReconcileReport = ReconcileReport.of("chg", Nil)
    assertEquals(report.rows, 0)
    assertEquals(report.witnesses, 0)
    assertEquals(report.claims, Nil)
    assertEquals(report.witnessed, Nil)
    assertEquals(report.testimony, Nil)
    assertEquals(report.contradicted, Nil)
    assert(!report.hasFindings)
