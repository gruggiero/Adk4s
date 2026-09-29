package org.sinemenda.probatio.cli

import hedgehog.*
import org.sinemenda.probatio.core.Outcome

/**
 * Tests for the ExitCode sealed enum and the exit-code mapping.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Three-way exit protocol for every subcommand
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Undetermined is never collapsed into a finding
 * spec: port-scanner-to-probatio/cli-protocol — Property: exit-code-mapping-is-total-and-disjoint
 * spec: port-scanner-to-probatio/cli-protocol — Property: undetermined-never-collapses
 */
final class ExitCodeSpec extends ProbatioCliSuite:

  // ── Scenario: Clean run exits 0
  // spec: cli-protocol — Scenario: Clean run exits 0
  test("clean run maps to exit code 0"):
    val outcome: Outcome[Int] = Outcome.Ran(0)
    assertEquals(ExitCode.toInt(ExitCode.from(outcome)), 0)

  // ── Scenario: Finding exits 1
  // spec: cli-protocol — Scenario: Finding exits 1
  test("finding maps to exit code 1"):
    val outcome: Outcome[Int] = Outcome.Finding("spec-lint violation")
    assertEquals(ExitCode.toInt(ExitCode.from(outcome)), 1)

  // ── Scenario: Undetermined exits 2
  // spec: cli-protocol — Scenario: Undetermined exits 2
  test("undetermined maps to exit code 2"):
    val outcome: Outcome[Int] = Outcome.Undetermined("ledger unreadable")
    assertEquals(ExitCode.toInt(ExitCode.from(outcome)), 2)

  // ── Scenario: no exit code outside {0,1,2} is producible
  test("exit code enum has exactly three cases"):
    assertEquals(ExitCode.values.length, 3)

  test("ExitCode.toInt maps Clean→0, Finding→1, Undetermined→2"):
    assertEquals(ExitCode.toInt(ExitCode.Clean), 0)
    assertEquals(ExitCode.toInt(ExitCode.Finding), 1)
    assertEquals(ExitCode.toInt(ExitCode.Undetermined), 2)

  // ── Scenario: Undetermined condition input is never collapsed (adversarial)
  // spec: cli-protocol — Scenario: Undetermined condition input is never collapsed (adversarial)
  test("undetermined with any reason maps to 2, never 1 or 0"):
    val reasons: List[String] = List(
      "unreadable ledger",
      "unknown record version",
      "missing prerequisite",
      "spec-lint non-lint failure",
      ""
    )
    reasons.foreach { reason =>
      val outcome: Outcome[Int] = Outcome.Undetermined(reason)
      val code: Int             = ExitCode.toInt(ExitCode.from(outcome))
      assert(code == 2, s"expected 2 for reason '$reason', got $code")
      assert(code != 1, s"undetermined collapsed to 1 for reason '$reason'")
      assert(code != 0, s"undetermined collapsed to 0 for reason '$reason'")
    }

  // ── Property: exit-code-mapping-is-total-and-disjoint
  // spec: cli-protocol — Property: exit-code-mapping-is-total-and-disjoint
  property("exit-code-mapping-is-total-and-disjoint"):
    for
      tag <- Gen.element1("clean", "finding", "undetermined").forAll
      v   <- Gen.int(Range.linear(0, 100)).forAll
      msg <- Gen.string(Gen.alphaNum, Range.linear(1, 50)).forAll
    yield
      val outcome: Outcome[Int] = tag match
        case "clean"        => Outcome.Ran(v)
        case "finding"      => Outcome.Finding(msg)
        case "undetermined" => Outcome.Undetermined(msg)
      val code: Int = ExitCode.toInt(ExitCode.from(outcome))
      val expected: Int = tag match
        case "clean"        => 0
        case "finding"      => 1
        case "undetermined" => 2
      Result
        .assert(code == expected)
        .and(Result.assert(code >= 0))
        .and(Result.assert(code <= 2))
        .and(
          Result.assert(
            outcome match
              case Outcome.Ran(_)          => code == 0
              case Outcome.Finding(_)      => code == 1
              case Outcome.Undetermined(_) => code == 2
          )
        )

  // ── Property: undetermined-never-collapses
  // spec: cli-protocol — Property: undetermined-never-collapses
  property("undetermined-never-collapses"):
    for reason <- Gen.string(Gen.alphaNum, Range.linear(0, 100)).forAll
    yield
      val outcome: Outcome[Int] = Outcome.Undetermined(reason)
      val code: Int             = ExitCode.toInt(ExitCode.from(outcome))
      Result
        .assert(code == 2)
        .and(Result.assert(code != 1))
        .and(Result.assert(code != 0))
