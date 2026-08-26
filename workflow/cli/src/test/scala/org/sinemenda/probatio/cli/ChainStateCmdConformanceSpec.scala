package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.*
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
    val req: ChainState.Requirement = ChainState.Requirement("test-spec", "test requirement")
    val reqs: List[ChainState.Requirement] = List(req)
    val lint: LintReport = LintReport(
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
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(lint, ledger, reqs, "abc1234", "test-change")
    result match
      case Right(report) =>
        assertEquals(report.discharged, 1)
        assertEquals(report.unresolved, Nil)
      case Left(u) => fail(s"expected Right, got Left($u)")

  // ── Scenario: An undischarged obligation appears in the unresolved list
  // spec: cli-wiring — Scenario: An undischarged obligation appears in the unresolved list

  test("chain-state with an undischarged obligation reports it in unresolved"):
    val req: ChainState.Requirement = ChainState.Requirement("test-spec", "undischarged req")
    val reqs: List[ChainState.Requirement] = List(req)
    val lint: LintReport = LintReport(
      verdicts = List(RequirementVerdict("undischarged req", Verdict.Resolved, CheckId.F9)),
      warnings = Nil,
      applicability = Map.empty,
      lintSuccess = true
    )
    val ledger: Ledger.LedgerData = Ledger.fromRecords(Nil) // no records → nothing discharged
    val result: Either[ChainStateUndetermined, ChainStateReport] =
      ChainState.compute(lint, ledger, reqs, "abc1234", "test-change")
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
      Array("--change-dir", "/nonexistent", "--change", "test", "--baseline", "abc1234", "--ledger-file", "/nonexistent/ledger.jsonl")
    )
    outcome match
      case Outcome.Undetermined(reason) => assert(reason.nonEmpty)
      case Outcome.Ran(_)               => fail("chain-state on nonexistent file returned Ran — should be Undetermined")
      case Outcome.Finding(_)           => fail("chain-state on nonexistent file returned Finding — should be Undetermined")

  // ── Property: chain-state-report-contract-conformance
  // spec: cli-wiring — Property: chain-state-report-contract-conformance

  property("chain-state-report-contract-conformance"):
    for
      total      <- Gen.int(Range.linear(0, 50)).forAll
      bound      <- Gen.int(Range.linear(0, total)).forAll
      resolved   <- Gen.int(Range.linear(0, bound)).forAll
      discharged <- Gen.int(Range.linear(0, resolved)).forAll
      change     <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      val unresolvedCount: Int = total - discharged
      val unresolved: List[UnresolvedEntry] = (0 until unresolvedCount).map { i =>
        UnresolvedEntry("spec", s"req-$i", List(UnresolvedReason.Undischarged))
      }.toList
      val report: ChainStateReport = ChainStateReport(
        change = change,
        baseline = "abc1234",
        total = total,
        bound = bound,
        resolved = resolved,
        discharged = discharged,
        unresolved = unresolved,
        unmappedObligations = Nil
      )
      val json: String = write(report)
      // The chain-state report contract requires these fields.
      Result.assert(json.contains("total"))
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
