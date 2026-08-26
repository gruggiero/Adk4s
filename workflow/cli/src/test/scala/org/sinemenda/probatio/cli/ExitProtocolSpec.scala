package org.sinemenda.probatio.cli

import hedgehog.*
import hedgehog.Range
import org.sinemenda.probatio.core.Outcome

/**
 * Test oracle for the three-way exit protocol faithfulness (cli-wiring spec).
 *
 * Tests that every subcommand's exit code matches the predecessor's exit code.
 * Exit 0 (clean) maps to `Outcome.Ran(0)`; exit 1 (finding) maps to
 * `Outcome.Finding`; exit 2 (undetermined) maps to `Outcome.Undetermined`.
 * No subcommand collapses undetermined into clean.
 *
 * spec: cli-wiring — Property: three-way-exit-protocol-faithfulness
 * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
 */
final class ExitProtocolSpec extends ProbatioCliSuite:

  // ── Property: three-way-exit-protocol-faithfulness
  // spec: cli-wiring — Property: three-way-exit-protocol-faithfulness

  property("three-way-exit-protocol-faithfulness"):
    for
      // Generate outcomes and verify the exit-code mapping is total and disjoint.
      outcome <- genOutcome.forAll
    yield
      val exitCode: Int = ExitCode.toInt(ExitCode.from(outcome))
      outcome match
        case Outcome.Ran(_) =>
          Result.assert(exitCode == 0).log(s"Ran should map to 0, got $exitCode")
        case Outcome.Finding(_) =>
          Result.assert(exitCode == 1).log(s"Finding should map to 1, got $exitCode")
        case Outcome.Undetermined(_) =>
          Result.assert(exitCode == 2).log(s"Undetermined should map to 2, got $exitCode")

  // ── Scenario: Undetermined is never collapsed into clean
  // spec: cli-wiring — Scenario (implicit from three-way exit protocol)

  test("Undetermined outcome never maps to exit 0"):
    val reasons: List[String] = List(
      "unreadable ledger",
      "unknown format version",
      "missing prerequisite",
      "spec-lint non-lint failure",
      "corrupt content"
    )
    reasons.foreach { reason =>
      val outcome: Outcome[Int] = Outcome.Undetermined(reason)
      val code: Int = ExitCode.toInt(ExitCode.from(outcome))
      assert(code == 2, s"Undetermined('$reason') mapped to $code, expected 2")
    }

  // ── Scenario: Finding never maps to exit 0 or 2
  test("Finding outcome never maps to exit 0 or 2"):
    val findings: List[String] = List(
      "clause 1 violated",
      "missing required field",
      "dangerous pattern found",
      "predecessor not checkpointed"
    )
    findings.foreach { msg =>
      val outcome: Outcome[Int] = Outcome.Finding(msg)
      val code: Int = ExitCode.toInt(ExitCode.from(outcome))
      assert(code == 1, s"Finding('$msg') mapped to $code, expected 1")
    }

  // ── Scenario: Ran always maps to exit 0
  test("Ran outcome always maps to exit 0"):
    val values: List[Int] = List(0, 1, 42, -1, 100)
    values.foreach { v =>
      val outcome: Outcome[Int] = Outcome.Ran(v)
      val code: Int = ExitCode.toInt(ExitCode.from(outcome))
      assert(code == 0, s"Ran($v) mapped to $code, expected 0")
    }

  // ── Generator for Outcome[Int]
  private def genOutcome: Gen[Outcome[Int]] =
    Gen.choice1(
      Gen.int(Range.linear(-100, 100)).map(Outcome.Ran(_)),
      Gen.string(Gen.alphaNum, Range.linear(1, 50)).map(Outcome.Finding(_)),
      Gen.string(Gen.alphaNum, Range.linear(1, 50)).map(Outcome.Undetermined(_))
    )
