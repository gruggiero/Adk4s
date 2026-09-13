package org.sinemenda.probatio.core

import hedgehog.*

/**
 * Tests for the Outcome sealed enum.
 *
 * spec: port-scanner-to-probatio/probatio-core — Requirement: The three-way exit protocol is a sealed enum
 * spec: port-scanner-to-probatio/probatio-core — Requirement: Undetermined is never collapsed into a finding
 */
final class OutcomeSpec extends ProbatioSuite:

  // ── Scenario: a clean run carries its value
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a clean run carries its value
  test("a clean run carries its value and maps to exit code 0"):
    val outcome: Outcome[Int] = Outcome.Ran(42)
    assertEquals(outcome, Outcome.Ran(42))
    assertEquals(Outcome.toExitCode(outcome), 0)

  // ── Scenario: a finding carries its description
  // spec: port-scanner-to-probatio/probatio-core — Scenario: a finding carries its description
  test("a finding carries a non-empty description and maps to exit code 1"):
    val outcome: Outcome[Int] = Outcome.Finding("missing required field: exit")
    assertEquals(outcome, Outcome.Finding("missing required field: exit"))
    assertEquals(Outcome.toExitCode(outcome), 1)

  // ── Scenario: an undetermined result carries its reason
  // spec: port-scanner-to-probatio/probatio-core — Scenario: an undetermined result carries its reason
  test("an undetermined result carries a non-empty reason and maps to exit code 2"):
    val outcome: Outcome[Int] = Outcome.Undetermined("ledger unreadable: truncated")
    assertEquals(outcome, Outcome.Undetermined("ledger unreadable: truncated"))
    assertEquals(Outcome.toExitCode(outcome), 2)

  // ── Scenario: no fourth case is constructible (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: no fourth case is constructible (adversarial)
  test("no fourth case is constructible — the enum is sealed"):
    val allCases: List[Outcome[Int]] = List(
      Outcome.Ran(1),
      Outcome.Finding("x"),
      Outcome.Undetermined("y")
    )
    // A match on Outcome is exhaustive without a catch-all — the compiler
    // enforces this. The test below would not compile if a fourth case existed
    // and was not handled.
    def classify(o: Outcome[Int]): String = o match
      case Outcome.Ran(_)          => "ran"
      case Outcome.Finding(_)      => "finding"
      case Outcome.Undetermined(_) => "undetermined"
    assertEquals(allCases.length, 3)
    assertEquals(classify(Outcome.Ran(1)), "ran")
    assertEquals(classify(Outcome.Finding("x")), "finding")
    assertEquals(classify(Outcome.Undetermined("y")), "undetermined")

  // ── Scenario: undetermined is not collapsed into finding (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: undetermined is not collapsed into finding (adversarial)
  test("undetermined maps to exit code 2, never 1"):
    val outcome: Outcome[Int] = Outcome.Undetermined("any reason")
    assert(Outcome.toExitCode(outcome) != 1)
    assertEquals(Outcome.toExitCode(outcome), 2)

  // ── Scenario: undetermined is not collapsed into clean (adversarial)
  // spec: port-scanner-to-probatio/probatio-core — Scenario: undetermined is not collapsed into clean (adversarial)
  test("undetermined maps to exit code 2, never 0"):
    val outcome: Outcome[Int] = Outcome.Undetermined("any reason")
    assert(Outcome.toExitCode(outcome) != 0)
    assertEquals(Outcome.toExitCode(outcome), 2)

  // ── Property: Outcome totality — every case is reachable and the enum is exhaustive
  // spec: port-scanner-to-probatio/probatio-core — Property: Outcome totality — every case is reachable and the enum is exhaustive
  property("outcome totality — every case is reachable and the enum is exhaustive"):
    for
      tag <- Gen.element1("clean", "finding", "undetermined").forAll
      v   <- Gen.int(Range.linear(0, 100)).forAll
      msg <- Gen.string(Gen.alphaNum, Range.linear(1, 50)).forAll
    yield
      val outcome: Outcome[Int] = tag match
        case "clean"        => Outcome.Ran(v)
        case "finding"      => Outcome.Finding(msg)
        case "undetermined" => Outcome.Undetermined(msg)
      val exitCode: Int = Outcome.toExitCode(outcome)
      outcome match
        case Outcome.Ran(value) =>
          Result.assert(value == v).and(Result.assert(exitCode == 0))
        case Outcome.Finding(desc) =>
          Result.assert(desc == msg).and(Result.assert(exitCode == 1))
        case Outcome.Undetermined(reason) =>
          Result.assert(reason == msg).and(Result.assert(exitCode == 2))
