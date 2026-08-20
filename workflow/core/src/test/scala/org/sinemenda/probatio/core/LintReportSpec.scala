package org.sinemenda.probatio.core

import hedgehog.*
import upickle.default.*

/**
 * Tests for LintReport — per-requirement verdict attribution.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: spec-lint output carries per-requirement verdict attribution
 * spec: port-scanner-to-probatio/probatio-core — Property: LintReport round-trips through uPickle JSON
 */
final class LintReportSpec extends ProbatioSuite:

  // ── Scenario: a requirement with a bound verdict is attributed
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a requirement with a bound verdict is attributed
  test("a requirement with a bound verdict is attributed"):
    val report: LintReport = LintReport(
      verdicts = List(RequirementVerdict("R1", Verdict.Bound, CheckId.F7)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    assertEquals(report.verdicts.length, 1)
    report.verdicts.headOption match
      case Some(v) =>
        assertEquals(v.requirement, "R1")
        assertEquals(v.verdict, Verdict.Bound)
        assertEquals(v.check, CheckId.F7)
      case None => fail("empty verdicts")

  // ── Scenario: a requirement with an unbound verdict is attributed
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a requirement with an unbound verdict is attributed
  test("a requirement with an unbound verdict is attributed"):
    val report: LintReport = LintReport(
      verdicts = List(RequirementVerdict("R2", Verdict.Unbound, CheckId.F7)),
      warnings = List.empty,
      applicability = Map.empty,
      lintSuccess = true
    )
    report.verdicts.headOption match
      case Some(v) =>
        assertEquals(v.verdict, Verdict.Unbound)
        assertEquals(v.check, CheckId.F7)
      case None => fail("empty verdicts")

  // ── Scenario: the report round-trips through uPickle JSON
  // spec: port-scanner-to-probatio/probatio-core — Scenario: the report round-trips through uPickle JSON
  test("the report round-trips through uPickle JSON"):
    val report: LintReport = LintReport(
      verdicts = List(
        RequirementVerdict("R1", Verdict.Bound, CheckId.F7),
        RequirementVerdict("R2", Verdict.Unbound, CheckId.F7)
      ),
      warnings = List(LintWarning("W3", 42, "negative requirement")),
      applicability = Map("check-17" -> "APPLIES"),
      lintSuccess = true
    )
    val json: String = write(report)
    val decoded: LintReport = read[LintReport](json)
    assertEquals(decoded, report)

  // ── Property: LintReport round-trips through uPickle JSON
  // spec: port-scanner-to-probatio/probatio-core — Property: LintReport round-trips through uPickle JSON
  property("lint report round-trips through uPickle JSON"):
    for
      _ <- Gen.int(Range.linear(0, 20)).forAll
      verdicts  <- Gen.list(genRequirementVerdict, Range.linear(0, 20)).forAll
      nWarnings <- Gen.int(Range.linear(0, 5)).forAll
    yield
      val report: LintReport = LintReport(
        verdicts = verdicts,
        warnings = (1 to nWarnings).toList.map(i =>
          LintWarning(s"W$i", i * 10, s"warning $i")
        ),
        applicability = Map("check-17" -> "APPLIES", "check-18" -> "N/A"),
        lintSuccess = true
      )
      val json: String = write(report)
      val decoded: LintReport = read[LintReport](json)
      Result.assert(decoded == report)

  private def genRequirementVerdict: Gen[RequirementVerdict] =
    for
      req   <- Gen.string(Gen.alphaNum, Range.linear(1, 30))
      v     <- Gen.element1(Verdict.Bound, Verdict.Resolved, Verdict.Unbound)
      check <- Gen.element1(
        CheckId.F1, CheckId.F2, CheckId.F3, CheckId.F4, CheckId.F5,
        CheckId.F6, CheckId.F7, CheckId.F8, CheckId.F9, CheckId.F10
      )
    yield RequirementVerdict(req, v, check)
