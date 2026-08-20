package org.sinemenda.probatio.cli

import hedgehog.*

/**
 * Tests for arg-parse error attribution (R-P4).
 *
 * spec: port-scanner-to-probatio/cli-protocol — Requirement: Arg parsing errors name the missing or invalid flag
 * spec: port-scanner-to-probatio/cli-protocol — Property: arg-parse-error-attribution
 */
final class CliParseErrorSpec extends ProbatioCliSuite:

  // ── Scenario: Missing required value names the flag
  // spec: cli-protocol — Scenario: Missing required value names the flag
  test("MissingValue error names the flag 'baseline'"):
    val err: CliError = CliError.MissingValue("baseline")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("baseline"), s"message '$msg' does not name 'baseline'")

  // ── Scenario: Unknown subcommand names the token
  // spec: cli-protocol — Scenario: Unknown subcommand names the token
  test("UnknownSubcommand error names the token 'foo'"):
    val err: CliError = CliError.UnknownSubcommand("foo")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("foo"), s"message '$msg' does not name 'foo'")

  // ── Scenario: Invalid enum value names the flag and value
  // spec: cli-protocol — Scenario: Invalid enum value names the flag and value
  test("InvalidEnum error names both flag 'event' and value 'invalid'"):
    val err: CliError = CliError.InvalidEnum("event", "invalid")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("event"), s"message '$msg' does not name flag 'event'")
    assert(msg.contains("invalid"), s"message '$msg' does not name value 'invalid'")

  test("UnknownFlag error names the flag 'unknown-flag'"):
    val err: CliError = CliError.UnknownFlag("unknown-flag")
    val msg: String   = CliErrorRender.render(err)
    assert(msg.contains("unknown-flag"), s"message '$msg' does not name 'unknown-flag'")

  // ── Scenario: Error without offending token is forbidden (adversarial)
  // spec: cli-protocol — Scenario: Error without offending token is forbidden (adversarial)
  test("every CliError variant's render output contains its offendingToken"):
    val errors: List[CliError] = List(
      CliError.UnknownSubcommand("foo"),
      CliError.MissingValue("baseline"),
      CliError.InvalidEnum("event", "invalid"),
      CliError.UnknownFlag("unknown-flag")
    )
    errors.foreach { err =>
      val msg: String = CliErrorRender.render(err)
      assert(
        msg.contains(err.offendingToken),
        s"message '$msg' does not contain offendingToken '${err.offendingToken}'"
      )
    }

  test("every CliError variant has a non-empty offendingToken"):
    val errors: List[CliError] = List(
      CliError.UnknownSubcommand("foo"),
      CliError.MissingValue("baseline"),
      CliError.InvalidEnum("event", "invalid"),
      CliError.UnknownFlag("unknown-flag")
    )
    errors.foreach { err =>
      assert(
        err.offendingToken.nonEmpty,
        s"variant ${err.getClass.getSimpleName} has empty offendingToken"
      )
    }

  // ── Property: arg-parse-error-attribution
  // spec: cli-protocol — Property: arg-parse-error-attribution
  property("arg-parse-error-attribution"):
    for
      kind  <- Gen.element1("unknown_subcommand", "missing_value", "invalid_enum", "unknown_flag").forAll
      token <- Gen.string(Gen.alphaNum, Range.linear(1, 30)).forAll
      flag  <- Gen.string(Gen.alphaNum, Range.linear(1, 20)).forAll
    yield
      val error: CliError = kind match
        case "unknown_subcommand" => CliError.UnknownSubcommand(token)
        case "missing_value"      => CliError.MissingValue(flag)
        case "invalid_enum"       => CliError.InvalidEnum(flag, token)
        case "unknown_flag"       => CliError.UnknownFlag(flag)
      val msg: String           = CliErrorRender.render(error)
      val expectedToken: String = error.offendingToken
      Result
        .assert(msg.contains(expectedToken))
        .and(Result.assert(msg.nonEmpty))
