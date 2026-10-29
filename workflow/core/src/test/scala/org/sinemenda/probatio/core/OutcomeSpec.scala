package org.sinemenda.probatio.core

import hedgehog.*
import hedgehog.core.PropertyConfig
import hedgehog.core.Seed
import hedgehog.core.Status
import hedgehog.runner as hr

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

  // ── spec: hermetic-test-processes ─────────────────────────────────
  // The two coverage-minimum scenarios — written as probes that run a
  // Hedgehog property off to the side and inspect the report, so the
  // suite's own result is never the probe's subject.

  /**
   * Run a probe property under the real Hedgehog runner and return its
   * status plus the rendered report — the mechanism by which the two
   * scenarios observe `cover` behaviour rather than assuming it.
   */
  private def runProbe(name: String, prop: hedgehog.Property): (Status, String) =
    val t: hr.Test        = hedgehog.runner.property(name, prop)
    val report            = hedgehog.Property.check(t.withConfig(PropertyConfig.default), t.result, Seed.fromLong(0xC0FFEE))
    val rendered: String  = hr.Test.renderReport(getClass.getName, t, report, ansiCodesSupported = false)
    (report.status, rendered)

  // spec: hermetic-test-processes — Scenario: Happy path — a met minimum does not fail the property
  test("a met coverage minimum does not fail the property"):
    val prop: hedgehog.Property =
      for
        n <- Gen.int(Range.linear(0, 10)).forAll
          .cover(50, "the labelled case", (x: Int) => x >= 0) // generated at 100%
      yield Result.assert(n >= 0)
    val probe: (Status, String) = runProbe("cover-probe-met", prop)
    assertEquals(
      probe._1,
      Status.ok,
      s"a property whose labelled case is generated above its minimum must pass.\n${probe._2}"
    )

  // spec: hermetic-test-processes — Scenario: Adversarial — a missed minimum fails an otherwise passing property
  test("a missed coverage minimum fails an otherwise passing property"):
    val prop: hedgehog.Property =
      for
        n <- Gen.constant(0).forAll
          .cover(80, "the labelled case", (x: Int) => x == 1) // generated at 0%
      yield Result.assert(n == 0) // never falsified — coverage alone must fail it
    val probe: (Status, String) = runProbe("cover-probe-missed", prop)
    assert(
      probe._1 != Status.ok,
      s"a property whose labelled case is generated below its minimum must fail even with no counterexample.\n${probe._2}"
    )
    assert(
      probe._2.contains("the labelled case"),
      s"the failure must name the missed label.\n${probe._2}"
    )
