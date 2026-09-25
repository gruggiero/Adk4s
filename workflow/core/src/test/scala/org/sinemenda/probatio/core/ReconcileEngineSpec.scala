package org.sinemenda.probatio.core

import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import ReconcileFixtures.ClaimKey
import ReconcileFixtures.RecKind
import ReconcileFixtures.genRecordSet
import ReconcileFixtures.record

/**
 * Test oracle for the reconcile engine (spec 6).
 *
 * Step 1 scope: the compile-negative obligations. The Step-2 oracle —
 * the named scenarios, the totality/key-agreement/no-discharge-verdict
 * properties — lands in this file in the next phase.
 *
 * spec: danger-reconcile-engines — Compile-Negative: Corroboration.Witnessed constructed without the observing record
 * spec: danger-reconcile-engines — Compile-Negative: A discharge verdict type referenced from the corroboration module
 */
final class ReconcileEngineSpec extends ProbatioSuite:

  // ── Compile-Negative: a witness verdict without a witness ──────────
  // spec: danger-reconcile-engines — Compile-Negative: Corroboration.Witnessed constructed without the observing record
  test("Corroboration.Witnessed cannot be constructed without the observing record"):
    val err: String = compileErrors(
      "val c: Corroboration = Corroboration.Witnessed"
    )
    assert(
      err.nonEmpty,
      "a bare Witnessed must not compile — the verdict carries the " +
        "observing ValidatedRecord; a witness without a witness is " +
        "testimony wearing the wrong label"
    )

  // ── Compile-Negative: no discharge verdict in this module ───────────
  // spec: danger-reconcile-engines — Compile-Negative: A discharge verdict type referenced from the corroboration module
  test("no discharge verdict type is reachable from the corroboration module"):
    val errDischarged: String = compileErrors(
      "ReconcileReport.of(\"c\", Nil).discharged"
    )
    assert(
      errDischarged.nonEmpty,
      "report.discharged must not compile — discharge is chain-state's " +
        "decision, not corroboration's"
    )
    val errCase: String = compileErrors(
      "Corroboration.Discharged"
    )
    assert(
      errCase.nonEmpty,
      "Corroboration.Discharged must not compile — no discharge verdict " +
        "exists in the corroboration algebra"
    )

  // ── Compile-Negative: the engine takes values, not a path ───────────
  test("the reconcile engine accepts records, not a filesystem path"):
    val err: String = compileErrors(
      "ReconcileEngine.classify(java.nio.file.Paths.get(\"ledger.jsonl\"), \"c\", None, None)"
    )
    assert(
      err.nonEmpty,
      "classify(Path, ...) must not compile — the engine takes " +
        "List[ValidatedRecord]; the CLI adapter owns the file"
    )

  test("the reconcile engine sources perform no file I/O and no environment reads"):
    val moduleDir: Path = reconcileModuleDir
    val engineSources: List[Path] = List(
      "ReconcileEngine.scala"
    ).map((name: String) => moduleDir.resolve(name))
    val forbidden: List[String] = List(
      "java.nio.file",
      "java.io.File",
      "System.getenv", // scalafix:ok DisableSyntax.NoSystemGetenv
      "scala.io.",
      "sys.process", // scalafix:ok DisableSyntax.NoScalaSysProcess
      "ProcessBuilder"
    )
    engineSources.foreach { path =>
      assert(Files.isRegularFile(path), s"engine source missing: $path")
      val text: String = Files.readString(path)
      forbidden.foreach { token =>
        assert(
          !text.contains(token),
          s"${path.getFileName} contains forbidden I/O token '$token'"
        )
      }
    }

  // ════════════════════════════════════════════════════════════════════
  // Step 2 oracle — scenarios and properties (expected RED until Step 3)
  // ════════════════════════════════════════════════════════════════════

  private val coverConfig: PropertyConfig => PropertyConfig =
    (c: PropertyConfig) => c.copy(testLimit = SuccessCount(200))

  private def key(
    spec: String = "s1",
    ring: Ring = Ring.R3,
    baseline: String = "base1",
    command: String = "sbt test"
  ): ClaimKey =
    ClaimKey(spec, ring, baseline, command)

  private def classOf(report: ReconcileReport, r: ValidatedRecord): Corroboration =
    report.classifications.find(_.record eq r) match
      case Some(c) => c.corroboration
      case None    => fail("record missing from classifications — totality broken")

  // ── Scenario: a self-observed run needs no witness ──────────────────
  // spec: danger-reconcile-engines — Scenario: Happy path — a self-observed run needs no witness
  test("a digest-carrying run row is self-observed, not testimony"):
    val k: ClaimKey                = key()
    val digestRow: ValidatedRecord = record(k, "chg", exit = 0, RecKind.DigestRow)
    val report: ReconcileReport    = ReconcileEngine.classify(List(digestRow), "chg", None, None)
    assertEquals(classOf(report, digestRow), Corroboration.SelfObserved)
    assertEquals(report.testimony, Nil)

  test("an ambient row corroborates itself"):
    val k: ClaimKey              = key()
    val ambient: ValidatedRecord = record(k, "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(ambient), "chg", None, None)
    assertEquals(classOf(report, ambient), Corroboration.SelfObserved)
    assertEquals(report.witnesses, 1)

  // ── Scenario: matching independent observation is witnessed ─────────
  // spec: danger-reconcile-engines — Scenario: Happy path — a written record with a matching independent observation is witnessed
  test("a written green record with a matching ambient observer is witnessed"):
    val k: ClaimKey              = key()
    val claim: ValidatedRecord   = record(k, "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(k, "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    classOf(report, claim) match
      case Corroboration.Witnessed(observer, preceding, following) =>
        assert(observer eq witness, "the verdict must carry the observing record")
        assert(
          (preceding ++ (observer :: following)).map(_ eq witness) == List(true),
          "the ambient set in ledger order must be exactly the witness here"
        )
      case other => fail(s"expected Witnessed, got $other")
    assertEquals(report.witnessed.length, 1)
    assertEquals(report.testimony, Nil)
    assert(!report.hasFindings)

  // The predecessor's `observed` is `$w | map(.exit)` — the FULL ambient
  // set at the key in ledger order, not observer-first. A dissenting
  // ambient row recorded before the witness stays first.
  test("a witnessed claim's observed exits keep ledger order"):
    val k: ClaimKey              = key()
    val claim: ValidatedRecord   = record(k, "chg", exit = 0, RecKind.Written)
    val dissent: ValidatedRecord = record(k, "chg", exit = 1, RecKind.AmbientRow)
    val witness: ValidatedRecord = record(k, "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, dissent, witness), "chg", None, None)
    classOf(report, claim) match
      case Corroboration.Witnessed(observer, preceding, following) =>
        assert(observer eq witness)
        assert(preceding.exists(_ eq dissent) && following.isEmpty)
      case other => fail(s"expected Witnessed, got $other")
    assertEquals(report.witnessed.map(_.observed), List(List(BigInt(1), BigInt(0))))

  test("a witness at the same key but a different command does not corroborate"):
    val claim: ValidatedRecord   = record(key(command = "sbt test"), "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(key(command = "make check"), "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Testimony)

  test("a witness at a different baseline does not corroborate"):
    val claim: ValidatedRecord   = record(key(baseline = "b1"), "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(key(baseline = "b2"), "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Testimony)

  test("a witness at a different spec does not corroborate"):
    val claim: ValidatedRecord   = record(key(spec = "s1"), "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(key(spec = "s2"), "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Testimony)

  test("a witness at a different ring does not corroborate"):
    val claim: ValidatedRecord   = record(key(ring = Ring.R0), "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(key(ring = Ring.R1), "chg", exit = 0, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Testimony)

  // ── Scenario: written green record with no observer is testimony ────
  // spec: danger-reconcile-engines — Scenario: Adversarial — a written green record with no observer is testimony
  test("a written green record with no observer is testimony"):
    val k: ClaimKey             = key()
    val claim: ValidatedRecord  = record(k, "chg", exit = 0, RecKind.Written)
    val report: ReconcileReport = ReconcileEngine.classify(List(claim), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Testimony)
    assertEquals(report.testimony.map(_.spec), List(k.spec))
    assert(report.hasFindings)

  // ── Scenario: a disagreeing observer is contradicted, separately ────
  // spec: danger-reconcile-engines — Scenario: Adversarial — an observer disagreeing with the written outcome is contradicted, reported separately
  test("an observer disagreeing with the written outcome is contradicted"):
    val k: ClaimKey              = key()
    val claim: ValidatedRecord   = record(k, "chg", exit = 0, RecKind.Written)
    val witness: ValidatedRecord = record(k, "chg", exit = 1, RecKind.AmbientRow)
    val report: ReconcileReport  = ReconcileEngine.classify(List(claim, witness), "chg", None, None)
    classOf(report, claim) match
      case Corroboration.Contradicted(observer, others) =>
        assert(observer eq witness)
        assertEquals((observer :: others).map(_.record.exit), List(BigInt(1)))
      case other => fail(s"expected Contradicted, got $other")
    assertEquals(report.contradicted.length, 1)
    assertEquals(report.testimony, Nil)
    assertEquals(report.contradicted.map(_.observed), List(List(BigInt(1))))

  // ── Scenario: a judgment ring is exempt ─────────────────────────────
  // spec: danger-reconcile-engines — Scenario: Edge case — a judgment ring is exempt
  test("written records on judgment rings are exempt"):
    ReconcileEngine.judgmentRings.foreach { ring =>
      val claim: ValidatedRecord  = record(key(), "chg", exit = 0, RecKind.Written, ring = Some(ring))
      val report: ReconcileReport = ReconcileEngine.classify(List(claim), "chg", None, None)
      assertEquals(classOf(report, claim), Corroboration.Exempt)
    }

  // ── Scenario: a failing run needs no witness ────────────────────────
  // spec: danger-reconcile-engines — Scenario: Edge case — a record of a failing run needs no witness
  test("a written record of a failing run is exempt, not testimony"):
    val claim: ValidatedRecord  = record(key(), "chg", exit = 1, RecKind.Written)
    val report: ReconcileReport = ReconcileEngine.classify(List(claim), "chg", None, None)
    assertEquals(classOf(report, claim), Corroboration.Exempt)
    assertEquals(report.testimony, Nil)

  // ── Scenario: rows outside the change are not classified ────────────
  test("records for other changes are out of scope"):
    val claim: ValidatedRecord  = record(key(), "other-change", exit = 0, RecKind.Written)
    val report: ReconcileReport = ReconcileEngine.classify(List(claim), "chg", None, None)
    assertEquals(report.rows, 0)
    assertEquals(report.claims, Nil)

  test("--spec narrows the record set before classification"):
    val inSpec: ValidatedRecord  = record(key(spec = "s1"), "chg", exit = 0, RecKind.Written)
    val outSpec: ValidatedRecord = record(key(spec = "s2"), "chg", exit = 0, RecKind.Written)
    val report: ReconcileReport  = ReconcileEngine.classify(List(inSpec, outSpec), "chg", Some("s1"), None)
    assertEquals(report.rows, 1)
    assertEquals(report.testimony.map(_.spec), List("s1"))

  test("--baseline narrows the record set before classification"):
    val inBaseline: ValidatedRecord  = record(key(baseline = "b1"), "chg", exit = 0, RecKind.Written)
    val outBaseline: ValidatedRecord = record(key(baseline = "b2"), "chg", exit = 0, RecKind.Written)
    val report: ReconcileReport =
      ReconcileEngine.classify(List(inBaseline, outBaseline), "chg", None, Some("b1"))
    assertEquals(report.rows, 1)
    assertEquals(report.testimony.map(_.baseline), List("b1"))

  // ── Property: corroboration-is-total-and-exclusive ──────────────────
  // spec: danger-reconcile-engines — Property: corroboration-is-total-and-exclusive
  property("corroboration-is-total-and-exclusive", coverConfig):
    for pair <- genRecordSet.forAll
        .cover(
          25,
          "has-testimony",
          (p: (String, List[ValidatedRecord])) => classified(p).exists(_.corroboration == Corroboration.Testimony)
        )
        .cover(
          15,
          "has-contradiction",
          (p: (String, List[ValidatedRecord])) =>
            classified(p).exists(c =>
              c.corroboration match
                case Corroboration.Contradicted(_, _) => true
                case _                                => false
            ) // danger-scan:allow cover-predicate — partition check, not a claim match
        )
        .cover(
          25,
          "has-witnessed",
          (p: (String, List[ValidatedRecord])) =>
            classified(p).exists(c =>
              c.corroboration match
                case Corroboration.Witnessed(_, _, _) => true
                case _                                => false
            ) // danger-scan:allow cover-predicate — partition check, not a claim match
        )
        .cover(
          15,
          "has-exempt",
          (p: (String, List[ValidatedRecord])) => classified(p).exists(_.corroboration == Corroboration.Exempt)
        )
        .cover(
          20,
          "has-self-observed",
          (p: (String, List[ValidatedRecord])) => classified(p).exists(_.corroboration == Corroboration.SelfObserved)
        )
    yield
      val (change: String, records: List[ValidatedRecord]) = pair
      val report: ReconcileReport                          = ReconcileEngine.classify(records, change, None, None)
      Result
        .assert(report.classifications.length == records.length)
        .log("every record classified exactly once")
        .and(
          Result
            .assert(
              records.forall(r => report.classifications.exists(_.record eq r))
            )
            .log("the classes partition the record set")
        )

  // ── Property: witness-requires-key-agreement ────────────────────────
  // spec: danger-reconcile-engines — Property: witness-requires-key-agreement
  property("witness-requires-key-agreement", coverConfig):
    for pair <- genRecordSet.forAll
        .cover(
          25,
          "observer-at-right-key-same-outcome",
          (p: (String, List[ValidatedRecord])) =>
            classified(p).exists(c =>
              c.corroboration match
                case Corroboration.Witnessed(_, _, _) => true
                case _                                => false
            ) // danger-scan:allow cover-predicate — witnessed presence, not a claim match
        )
        .cover(
          25,
          "observer-at-wrong-key",
          (p: (String, List[ValidatedRecord])) =>
            // a claim classified Testimony while an ambient row exists
            // at a DIFFERENT (spec, ring, baseline, command) key — the
            // observer that must not corroborate
            val (change: String, records: List[ValidatedRecord]) = p
            val report: ReconcileReport                          = ReconcileEngine.classify(records, change, None, None)
            val ambient: List[ValidatedRecord] =
              records.filter(_.provenance.source.contains("ambient"))
            report.classifications.exists { (c: ReconcileEngine.Classified) =>
              c.corroboration == Corroboration.Testimony &&
              ambient.exists { (o: ValidatedRecord) =>
                !(o.record.spec == c.record.record.spec &&
                  o.record.ring == c.record.record.ring &&
                  o.record.baseline == c.record.record.baseline &&
                  o.record.command == c.record.record.command)
              }
            }
        )
        .cover(
          20,
          "observer-at-right-key-different-outcome",
          (p: (String, List[ValidatedRecord])) =>
            classified(p).exists(c =>
              c.corroboration match
                case Corroboration.Contradicted(_, _) => true
                case _                                => false
            ) // danger-scan:allow cover-predicate — contradicted presence
        )
    yield
      val (change: String, records: List[ValidatedRecord]) = pair
      val report: ReconcileReport                          = ReconcileEngine.classify(records, change, None, None)
      val observed: List[ValidatedRecord] =
        records.filter(_.provenance.source.contains("ambient"))
      Result
        .assert(
          report.witnessed.forall { (v: ClaimVerdict) =>
            observed.exists { o =>
              o.record.spec == v.spec &&
              Ring.asString(o.record.ring) == Ring.asString(v.ring) &&
              o.record.baseline == v.baseline &&
              o.record.command == v.command &&
              o.record.exit == 0
            }
          }
        )
        .log("every witnessed claim has an ambient record at its key with the claimed outcome")

  // ── Property: no discharge verdict in the corroboration vocabulary ──
  // spec: danger-reconcile-engines — Property: no-discharge-verdict-in-output
  property("no-discharge-verdict-in-output", coverConfig):
    for pair <- genRecordSet.forAll
        .cover(
          15,
          "all-witnessed",
          (p: (String, List[ValidatedRecord])) =>
            val report: ReconcileReport = ReconcileEngine.classify(p._2, p._1, None, None)
            report.claims.nonEmpty && report.testimony.isEmpty && report.contradicted.isEmpty
        )
    yield
      val (change: String, records: List[ValidatedRecord]) = pair
      val report: ReconcileReport                          = ReconcileEngine.classify(records, change, None, None)
      val verdictTokens: List[String] =
        report.classifications.map(c => Corroboration.verdictToken(c.corroboration)) ++
          report.claims.map(_.verdict)
      Result
        .assert(
          verdictTokens.forall(t => !t.contains("discharg"))
        )
        .log("no discharge verdict token in the corroboration vocabulary")

  /** Run classify and return the classifications (oracle-internal). */
  private def classified(pair: (String, List[ValidatedRecord])): List[ReconcileEngine.Classified] =
    ReconcileEngine.classify(pair._2, pair._1, None, None).classifications

  // ── helpers ─────────────────────────────────────────────────────────

  /** Locate `probatio-core`'s main source directory. */
  private def reconcileModuleDir: Path =
    val candidates: List[Path] = List(
      Paths.get("workflow/core/src/main/scala/org/sinemenda/probatio/core"),
      Paths.get("src/main/scala/org/sinemenda/probatio/core")
    )
    candidates.find((p: Path) => Files.isDirectory(p)) match
      case Some(dir) => dir
      case None =>
        fail(s"could not locate probatio-core sources from ${Paths.get("").toAbsolutePath}")
