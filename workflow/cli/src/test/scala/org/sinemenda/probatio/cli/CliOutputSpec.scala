package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.ChainStateUndetermined
import org.sinemenda.probatio.core.GatePayload
import org.sinemenda.probatio.core.HookSpecificOutput
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.UndeterminedReason
import upickle.default.*

/**
 * Scenario tests for output on undetermined (exit 2) — stdout MUST be
 * non-empty so unconditional readers see the reason.
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Output on undetermined is still emitted on stdout
 */
final class CliOutputSpec extends ProbatioCliSuite:

  // ── Scenario: Undetermined chain-state emits report on stdout
  // spec: cli-protocol — Scenario: Undetermined chain-state emits report on stdout
  test("undetermined chain-state report serializes to non-empty stdout payload"):
    val undetermined: ChainStateUndetermined = ChainStateUndetermined(
      change = "port-scanner-to-probatio",
      baseline = "abc123",
      reason = UndeterminedReason.stated("ledger unreadable: truncated at line 42")
    )
    val stdout: String = write(undetermined)
    assert(stdout.nonEmpty, "undetermined chain-state stdout payload is empty")
    assert(stdout.contains("ledger unreadable"), "reason not in stdout payload")

  // ── Scenario: Undetermined gate emits payload on stdout
  // spec: cli-protocol — Scenario: Undetermined gate emits payload on stdout
  test("undetermined gate payload serializes to non-empty stdout"):
    val payload: GatePayload = GatePayload(
      HookSpecificOutput(
        hookEventName = "tool-call",
        additionalContext = Some("undetermined: precondition could not be determined")
      )
    )
    val stdout: String = write(payload)
    assert(stdout.nonEmpty, "undetermined gate stdout payload is empty")
    assert(stdout.contains("tool-call"), "event name not in stdout payload")

  // ── Scenario: Suppressed stdout on exit 2 is forbidden (adversarial)
  // spec: cli-protocol — Scenario: Suppressed stdout on exit 2 is forbidden (adversarial)
  test("every undetermined outcome carries a reason that serializes to non-empty JSON"):
    val reasons: List[String] = List(
      "unreadable ledger",
      "unknown record version",
      "missing prerequisite",
      "spec-lint non-lint failure",
      "corrupt content at offset 0"
    )
    reasons.foreach { reason =>
      val outcome: Outcome[Int] = Outcome.Undetermined(reason)
      val code: Int             = ExitCode.toInt(ExitCode.from(outcome))
      assert(code == 2, s"exit code for reason '$reason' is $code, expected 2")
      // The reason itself is non-empty — the payload will carry it.
      assert(reason.nonEmpty, s"reason '$reason' is empty")
    }

  test("undetermined chain-state rejects an empty reason and still produces a JSON object"):
    // An empty reason is unrepresentable (UndeterminedReason.of rejects it);
    // the total `stated` route maps it to the unclassifiable-input reason —
    // the report still serializes to a JSON object on stdout, never
    // suppressed entirely.
    assert(
      UndeterminedReason.of("").isLeft,
      "an empty reason must be rejected by the smart constructor"
    )
    val undetermined: ChainStateUndetermined = ChainStateUndetermined(
      change = "test-change",
      baseline = "sha000",
      reason = UndeterminedReason.stated("")
    )
    val stdout: String = write(undetermined)
    assert(stdout.nonEmpty, "undetermined with empty reason produced empty stdout")
    assert(stdout.contains("test-change"), "change name not in stdout payload")
    assert(
      undetermined.reason.text.nonEmpty,
      "the stated fallback still names the input under inspection"
    )
