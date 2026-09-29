package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * Test oracle for LedgerCmd.append write-time validation.
 *
 * spec: port-scanner-to-probatio/provenance-validation — Requirement: The ledger append entrypoint SHALL validate before writing
 * spec: port-scanner-to-probatio/provenance-validation — Compile-Negative: --force / --skip-validation flag on ledger append
 */
final class LedgerCmdProvenanceSpec extends ProbatioCliSuite:

  // A valid JSON record string with all 10 required fields + R8 session.
  private val validR8Record: String =
    """{"v":1,"ts":"2026-08-08T12:34:56Z","change":"c","spec":"s","ring":"R8","obligation":"o","artifact":"a","command":"cmd","exit":0,"baseline":"abc1234","session":"devin-cli-session-x"}"""

  // A valid JSON record string with all 10 required fields (R3, no provenance).
  private val validR3Record: String =
    """{"v":1,"ts":"2026-08-08T12:34:56Z","change":"c","spec":"s","ring":"R3","obligation":"o","artifact":"a","command":"cmd","exit":0,"baseline":"abc1234"}"""

  // An R8 record missing session.
  private val r8MissingSession: String =
    """{"v":1,"ts":"2026-08-08T12:34:56Z","change":"c","spec":"s","ring":"R8","obligation":"o","artifact":"a","command":"cmd","exit":0,"baseline":"abc1234"}"""

  // A record missing the exit field.
  private val missingExit: String =
    """{"v":1,"ts":"2026-08-08T12:34:56Z","change":"c","spec":"s","ring":"R3","obligation":"o","artifact":"a","command":"cmd","baseline":"abc1234"}"""

  // ── Scenario: a valid record is appended successfully
  // spec: provenance-validation — Scenario: a valid record is appended successfully
  test("a valid R8 record with session is appended successfully"):
    val result: Outcome[Int] = LedgerCmd.append(validR8Record, ledgerPath = None)
    result match
      case Outcome.Ran(code) => assertEquals(code, 0)
      case other             => fail(s"Expected Ran(0), got $other")

  // ── Scenario: an adversarial-review ring record missing session is rejected at append (adversarial)
  // spec: provenance-validation — Scenario: an adversarial-review ring record missing session is rejected at append (adversarial)
  test("an R8 record missing session is rejected at append with Finding"):
    val result: Outcome[Int] = LedgerCmd.append(r8MissingSession, ledgerPath = None)
    result match
      case Outcome.Finding(desc) =>
        assert(
          desc.contains("session") || desc.contains("14"),
          s"Expected finding to mention session or clause 14, got: $desc"
        )
      case other => fail(s"Expected Finding, got $other")

  // ── Scenario: a record missing a required field is rejected at append (adversarial)
  // spec: provenance-validation — Scenario: a record missing a required field is rejected at append (adversarial)
  test("a record missing exit is rejected at append with Finding"):
    val result: Outcome[Int] = LedgerCmd.append(missingExit, ledgerPath = None)
    result match
      case Outcome.Finding(desc) =>
        assert(desc.contains("exit") || desc.contains("1"), s"Expected finding to mention exit or clause 1, got: $desc")
      case other => fail(s"Expected Finding, got $other")

  // ── Scenario: no force flag exists (adversarial)
  // spec: provenance-validation — Scenario: no force flag exists (adversarial)
  test("LedgerCmd.append has no force/skip-validation/allow-invalid flag"):
    val err: String = compileErrors(
      "LedgerCmd.append(validR8Record, ledgerPath = None, force = true)"
    )
    assert(err.nonEmpty, "LedgerCmd.append should not accept a force flag")

  // ── Scenario: a valid R3 record without provenance is appended successfully
  test("a valid R3 record without provenance is appended successfully"):
    val result: Outcome[Int] = LedgerCmd.append(validR3Record, ledgerPath = None)
    result match
      case Outcome.Ran(code) => assertEquals(code, 0)
      case other             => fail(s"Expected Ran(0), got $other")
