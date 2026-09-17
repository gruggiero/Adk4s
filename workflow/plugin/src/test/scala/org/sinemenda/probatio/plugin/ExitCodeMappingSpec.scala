package org.sinemenda.probatio.plugin

import hedgehog._
import hedgehog.Range._

/**
 * Tests for ExitCodeMapping — the pure function that maps the binary's exit
 * code to a task outcome with distinguishable failure messages.
 *
 * spec: port-scanner-to-probatio/sbt-plugin — Requirement: Task exit-code mapping distinguishes finding from undetermined
 * spec: port-scanner-to-probatio/sbt-plugin — Property: exit-code-mapping-distinct
 */
final class ExitCodeMappingSpec extends ProbatioPluginSuite {

  // ── Property: exit-code-mapping-distinct ────────────────────────────────
  // spec: sbt-plugin — Property: exit-code-mapping-distinct
  property("exit-code-mapping-distinct: exit 1 and 2 produce distinguishable messages") {
    for {
      tool     <- Gen.element1("spec-lint", "chain-state", "checkpoint", "ledger").forAll
      findings <- Gen.int(linear(0, 100)).forAll
      reason   <- Gen.string(Gen.alphaNum, linear(1, 80)).forAll
    } yield {
      val msg1: String = ExitCodeMapping.findingMessage(tool, findings, s"$findings findings")
      val msg2: String = ExitCodeMapping.undeterminedMessage(tool, reason)
      Result
        .assert(msg1.contains("reported"))
        .log(s"exit-1 message missing 'reported': $msg1")
        .and(
          Result
            .assert(msg1.contains("finding(s)"))
            .log(s"exit-1 message missing 'finding(s)': $msg1")
        )
        .and(
          Result
            .assert(msg1.contains(s"reported $findings"))
            .log(s"exit-1 message missing count N='reported $findings': $msg1")
        )
        .and(
          Result
            .assert(msg2.contains("could not determine"))
            .log(s"exit-2 message missing 'could not determine': $msg2")
        )
        .and(
          Result
            .assert(msg1 != msg2)
            .log(s"messages should be distinguishable:\n  msg1=$msg1\n  msg2=$msg2")
        )
    }
  }

  // ── Scenario: exit 0 maps to task success ───────────────────────────────
  // spec: sbt-plugin — Scenario: exit 0 maps to task success
  test("exit 0 maps to Right(()) — task success") {
    val result: Either[String, Unit] = ExitCodeMapping.mapExitCode("spec-lint", 0, "")
    assert(result.isRight, s"exit 0 should map to success, got $result")
  }

  // ── Scenario: exit 1 maps to finding failure ────────────────────────────
  // spec: sbt-plugin — Scenario: exit 1 maps to finding failure
  test("exit 1 maps to finding failure with 'reported N finding(s)' and tool name") {
    val result: Either[String, Unit] = ExitCodeMapping.mapExitCode("spec-lint", 1, "3 findings")
    assert(result.isLeft, s"exit 1 should map to failure, got $result")
    val msg: String = result match {
      case Left(m)  => m
      case Right(_) => fail("expected Left, got Right")
    }
    assert(msg.contains("probatio spec-lint"), s"message should name the tool: $msg")
    assert(msg.contains("reported"), s"message should contain 'reported': $msg")
    assert(msg.contains("finding(s)"), s"message should contain 'finding(s)': $msg")
    assert(msg.contains("reported 3"), s"message should contain count N='reported 3': $msg")
  }

  // ── Scenario: exit 2 maps to undetermined failure ───────────────────────
  // spec: sbt-plugin — Scenario: exit 2 maps to undetermined failure
  test("exit 2 maps to undetermined failure with 'could not determine'") {
    val result: Either[String, Unit] = ExitCodeMapping.mapExitCode("chain-state", 2, "ledger unreadable")
    assert(result.isLeft, s"exit 2 should map to failure, got $result")
    val msg: String = result match {
      case Left(m)  => m
      case Right(_) => fail("expected Left, got Right")
    }
    assert(msg.contains("probatio chain-state"), s"message should name the tool: $msg")
    assert(msg.contains("could not determine"), s"message should contain 'could not determine': $msg")
  }

  // ── Scenario: exit 1 and exit 2 produce distinguishable messages ────────
  // spec: sbt-plugin — Scenario: exit 1 and exit 2 produce distinguishable messages (adversarial)
  test("exit 1 and exit 2 messages are distinguishable without inspecting exit code") {
    val msg1: String = ExitCodeMapping.findingMessage("spec-lint", "3 findings")
    val msg2: String = ExitCodeMapping.undeterminedMessage("spec-lint", "ledger unreadable")
    assertNotEquals(msg1, msg2, "messages must be distinguishable")
    assert(msg1.contains("reported"), s"exit-1 message should contain 'reported': $msg1")
    assert(msg1.contains("finding(s)"), s"exit-1 message should contain 'finding(s)': $msg1")
    assert(msg2.contains("could not determine"), s"exit-2 message should contain 'could not determine': $msg2")
  }

  // ── Scenario: both exit 1 and exit 2 fail the build ─────────────────────
  // spec: sbt-plugin — Scenario: both exit 1 and exit 2 fail the build
  test("both exit 1 and exit 2 produce Left (build failure)") {
    val r1: Either[String, Unit] = ExitCodeMapping.mapExitCode("spec-lint", 1, "1 finding")
    val r2: Either[String, Unit] = ExitCodeMapping.mapExitCode("chain-state", 2, "timeout")
    assert(r1.isLeft, "exit 1 must fail the build")
    assert(r2.isLeft, "exit 2 must fail the build")
  }

  // ── Scenario: exit 1 with zero findings still says "reported" ───────────
  // Edge case from spec: exit code 1 with zero findings
  test("exit 1 with zero findings still says 'reported 0 finding(s)'") {
    val msg: String = ExitCodeMapping.findingMessage("spec-lint", 0, "0 findings")
    assert(msg.contains("reported"), s"exit-1 with 0 findings should still say 'reported': $msg")
    assert(msg.contains("finding(s)"), s"exit-1 with 0 findings should still say 'finding(s)': $msg")
    assert(msg.contains("reported 0"), s"exit-1 with 0 findings should say 'reported 0': $msg")
  }

  // ── Scenario: exit 2 with empty reason still says "could not determine" ─
  // Edge case from spec: exit code 2 with empty reason
  test("exit 2 with empty reason still says 'could not determine'") {
    val msg: String = ExitCodeMapping.undeterminedMessage("chain-state", "")
    assert(
      msg.contains("could not determine"),
      s"exit-2 with empty reason should still say 'could not determine': $msg"
    )
  }
}
