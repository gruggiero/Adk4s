package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.*
import org.sinemenda.probatio.migration.HermeticEnv
import upickle.default.*

/**
 * Test oracle for the `chain-state` subcommand wiring (cli-wiring spec).
 *
 * Tests the ChainState.compute wiring, the contract-conformant JSON report,
 * and the three-way exit protocol. Derived from the spec's requirements and
 * scenarios — NOT from the implementation.
 *
 * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
 * spec: cli-wiring — Property: chain-state-report-contract-conformance
 */
final class ChainStateCmdConformanceSpec extends ProbatioCliSuite:

  // ── Scenario: A fully discharged change reports zero unresolved
  // spec: cli-wiring — Scenario: A fully discharged change reports zero unresolved

  test("chain-state with all requirements discharged reports zero unresolved"):
    val req: ChainState.Requirement        = ChainState.Requirement("test-spec", "test requirement")
    val reqs: List[ChainState.Requirement] = List(req)
    val lint: LintReport = LiveFactFixtures.lintReport(
      verdicts = List(RequirementVerdict("test requirement", Verdict.Resolved, CheckId.F9)),
      warnings = Nil,
      applicability = Map.empty,
      lintSuccess = true
    )
    val record: LedgerRecord = LedgerRecord.from(
      ujson.Obj(
        "v"          -> ujson.Num(1),
        "ts"         -> ujson.Str("2026-08-26T12:00:00Z"),
        "change"     -> ujson.Str("test-change"),
        "spec"       -> ujson.Str("test-spec"),
        "ring"       -> ujson.Str("R0"),
        "obligation" -> ujson.Str("test requirement"),
        "artifact"   -> ujson.Str("Test.scala"),
        "command"    -> ujson.Str("sbt test"),
        "exit"       -> ujson.Num(0),
        "baseline"   -> ujson.Str("abc1234")
      )
    ) match
      case Right(r) => r
      case Left(e)  => fail(s"valid record rejected: ${e.description}")
    val ledger: Ledger.LedgerData = Ledger.fromRecords(List(record))
    // The obligation claiming `Requirement: test requirement` — the row the
    // green ledger record discharges.
    val obligation: ExtractedObligation = ExtractedObligation(
      spec = "test-spec",
      line = 20,
      obligation = "test requirement",
      artifact = "Test.scala",
      artifacts = List("Test.scala"),
      requirementClaims = List("test requirement"),
      unmappable = false
    )
    val extracted: RequirementSet =
      RequirementSet(List("test-spec"), reqs, List(obligation), FactSource.Degraded)
    val lints: Map[String, Outcome[LintReport]] = Map("test-spec" -> Outcome.Ran(lint))
    val noForgive: (String, String) => Boolean  = (_, _) => false
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(lints),
        ledger,
        extracted,
        Map.empty,
        "abc1234",
        "abc1234",
        "test-change",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"expected Right, got Left($u)")

  // ── Scenario: An undischarged obligation appears in the unresolved list
  // spec: cli-wiring — Scenario: An undischarged obligation appears in the unresolved list

  test("chain-state with an undischarged obligation reports it in unresolved"):
    val req: ChainState.Requirement        = ChainState.Requirement("test-spec", "undischarged req")
    val reqs: List[ChainState.Requirement] = List(req)
    val lint: LintReport = LiveFactFixtures.lintReport(
      verdicts = List(RequirementVerdict("undischarged req", Verdict.Resolved, CheckId.F9)),
      warnings = Nil,
      applicability = Map.empty,
      lintSuccess = true
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(Nil) // no records → nothing discharged
    // A mapped obligation with no ledger rows — the undischarged arm (an
    // empty obligations list would be unattributable, a different reason).
    val obligation: ExtractedObligation = ExtractedObligation(
      spec = "test-spec",
      line = 20,
      obligation = "undischarged obl",
      artifact = "Test.scala",
      artifacts = List("Test.scala"),
      requirementClaims = List("undischarged req"),
      unmappable = false
    )
    val extracted: RequirementSet =
      RequirementSet(List("test-spec"), reqs, List(obligation), FactSource.Degraded)
    val lints: Map[String, Outcome[LintReport]] = Map("test-spec" -> Outcome.Ran(lint))
    val noForgive: (String, String) => Boolean  = (_, _) => false
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(
        PrePassOutcome.Completed(lints),
        ledger,
        extracted,
        Map.empty,
        "abc1234",
        "abc1234",
        "test-change",
        noForgive
      )
    result match
      case Right(report) =>
        assertEquals(report.discharged, 0)
        assertEquals(report.unresolved.length, 1)
        assertEquals(report.unresolved.headOption.map(_.requirement), Some("undischarged req"))
      case Left(u) => fail(s"expected Right, got Left($u)")

  // ── Scenario: An unreadable ledger produces undetermined
  // spec: cli-wiring — Scenario: An unreadable ledger produces undetermined

  test("chain-state on nonexistent ledger file produces Undetermined, not Ran"):
    // The wired entrypoint should return Undetermined for a nonexistent file.
    // The stub currently returns Ran(0) — this test is RED until wired.
    val outcome: Outcome[Int] = ChainStateCmd.run(
      Array(
        "--change-dir",
        "/nonexistent",
        "--change",
        "test",
        "--baseline",
        "abc1234",
        "--ledger-file",
        "/nonexistent/ledger.jsonl"
      )
    )
    outcome match
      case Outcome.Undetermined(reason) => assert(reason.nonEmpty)
      case Outcome.Ran(_)               => fail("chain-state on nonexistent file returned Ran — should be Undetermined")
      case Outcome.Finding(_) => fail("chain-state on nonexistent file returned Finding — should be Undetermined")

  // ── Property: chain-state-report-contract-conformance
  // spec: cli-wiring — Property: chain-state-report-contract-conformance

  /**
   * An unresolved entry through the smart constructor — the test corpus is
   * contract-valid by construction.
   */
  private def entry(spec: String, req: String, reason: UnresolvedReason): UnresolvedEntry =
    UnresolvedEntry.of(spec, req, List(reason)).getOrElse(fail(s"unrepresentable entry: $spec/$req"))

  property("chain-state-report-contract-conformance"):
    for
      nEntries <- Gen.int(Range.linear(0, 50)).forAll
      okCount  <- Gen.int(Range.linear(0, 10)).forAll
      reasons <- Gen
        .element1(
          UnresolvedReason.Unbound,
          UnresolvedReason.Unresolved,
          UnresolvedReason.Undischarged,
          UnresolvedReason.Unattributable,
          UnresolvedReason.Failed
        )
        .list(Range.singleton(nEntries))
        .forAll
      change <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      // Counts are derived from the reason mix, as the contract's
      // cross-consistency clauses require: each entry carries exactly one
      // reason, so bound/resolved/discharged fall out of the reason counts.
      val unresolved: List[UnresolvedEntry] =
        reasons.zipWithIndex.map { case (r, i) => entry("spec", s"req-$i", r) }
      val total: Int = unresolved.length + okCount
      val bound: Int = total - unresolved.count(_.reasons.contains(UnresolvedReason.Unbound))
      val resolved: Int = bound - unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Unresolved) || e.reasons.contains(UnresolvedReason.Unattributable)
      )
      val discharged: Int = resolved - unresolved.count(e =>
        e.reasons.contains(UnresolvedReason.Undischarged) || e.reasons.contains(UnresolvedReason.Failed)
      )
      val report: ChainStateReport = ChainStateReport
        .fromCounts(
          change = change,
          baseline = "abc1234",
          total = total,
          bound = bound,
          resolved = resolved,
          discharged = discharged,
          unresolved = unresolved,
          unmappedObligations = Nil
        )
        .getOrElse(fail("generated report violates the report contract"))
      val json: String = write(report)
      // The chain-state report contract requires these fields.
      Result
        .assert(json.contains("total"))
        .and(Result.assert(json.contains("bound")))
        .and(Result.assert(json.contains("resolved")))
        .and(Result.assert(json.contains("discharged")))
        .and(Result.assert(json.contains("unresolved")))
        // Internal consistency: bound <= total, resolved <= bound, discharged <= resolved
        .and(Result.assert(report.bound <= report.total))
        .and(Result.assert(report.resolved <= report.bound))
        .and(Result.assert(report.discharged <= report.resolved))
        // Round-trip
        .and(Result.assert(read[ChainStateReport](json) == report))

  // ── Scenario: the unmapped-obligations list is present and empty when nothing is unmappable
  // spec: chain-state-attribution — Scenario: Edge case — the unmapped-obligations list is present and empty when nothing is unmappable

  test("the unmapped_obligations field is present even when empty"):
    val report: ChainStateReport = ChainStateReport
      .fromCounts("c", "b", 1, 1, 1, 1, Nil, Nil)
      .getOrElse(fail("fixture violates the report contract"))
    val rendered: String    = StdoutRenderer[ChainStateReport].render(report)
    val parsed: ujson.Value = ujson.read(rendered)
    assert(
      parsed.obj.contains("unmapped_obligations"),
      s"the field must not be omitted, got: $rendered"
    )
    assertEquals(
      parsed("unmapped_obligations").arr.toList,
      Nil,
      "an empty unmapped set serialises as an empty array"
    )

  // ── Obligation: the emitted report conforms to the report contract byte-wise
  // spec: chain-state-attribution — Property: verdict-parity-with-predecessor (Ring 4 contract-conformance)

  /**
   * `jq -e -f chain-state-report-contract.jq` over a rendered report — the
   * contract file is the single statement of the report shape; the emitted
   * bytes must satisfy it, not merely look right.
   */
  private def contractCheck(rendered: String): (Int, String) =
    val contract: java.nio.file.Path =
      repoRootPath.resolve("openspec/schemas/verified-scala3/scanner/chain-state-report-contract.jq")
    // spec: hermetic-test-processes — via the shared helper; streams merged
    // as before, under the fixed-base environment.
    val r: org.sinemenda.probatio.migration.HermeticResult = HermeticEnv.captureMerged(
      List("jq", "-e", "-f", contract.toString),
      HermeticEnv.empty,
      stdin = Some(rendered.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )
    (r.exitCode, r.out.trim)

  private def repoRootPath: java.nio.file.Path =
    val start: java.nio.file.Path = java.nio.file.Path.of("").toAbsolutePath.normalize
    LazyList
      .unfold(start)((p: java.nio.file.Path) => Option(p.getParent).map((par: java.nio.file.Path) => p -> par))
      .find(p => java.nio.file.Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(fail(s"could not locate the repository root from $start"))

  test("a rendered report satisfies chain-state-report-contract.jq"):
    val report: ChainStateReport = ChainStateReport
      .fromCounts(
        "c",
        "b",
        3,
        2,
        2,
        1,
        List(
          entry("spec", "req-unbound", UnresolvedReason.Unbound),
          entry("spec", "req-failed", UnresolvedReason.Failed)
        ),
        List(UnmappedObligation("spec", 42, "tests/fake.scala"))
      )
      .getOrElse(fail("fixture violates the report contract"))
    val rendered: String = StdoutRenderer[ChainStateReport].render(report)
    val (exit, out)      = contractCheck(rendered)
    assertEquals(exit, 0, s"the rendered report must satisfy the jq contract: $out")

  test("a rendered undetermined report satisfies chain-state-report-contract.jq"):
    val undetermined: ChainStateUndetermined =
      ChainStateUndetermined("c", "b", UndeterminedReason.stated("no ledger at /x"))
    val rendered: String = StdoutRenderer[ChainStateUndetermined].render(undetermined)
    val (exit, out)      = contractCheck(rendered)
    assertEquals(exit, 0, s"the undetermined report must satisfy the jq contract: $out")
