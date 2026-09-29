package org.sinemenda.probatio.cli

/**
 * Rendering of `CliError` to single-line error messages (R-P4).
 *
 * Lives in a separate file from `CliError` so that the match on the sealed
 * abstract class is checked from outside the defining compilation unit.
 * The exhaustiveness checker for sealed types in the same file as their
 * definition conservatively allows anonymous subclasses; from outside, it
 * knows the closed variant set.
 *
 * spec: cli-protocol — Property: arg-parse-error-attribution
 * spec: cli-protocol — Scenario: Error without offending token is forbidden (adversarial)
 */
object CliErrorRender:

  /**
   * Renders a single-line error message containing the offending token.
   *
   * The message MUST contain the offending token — a generic message like
   * "invalid arguments" without naming the token is a defect. Every variant
   * of `CliError` carries `offendingToken` by construction (the sealed
   * abstract class requires it in the constructor), so even the fallback
   * case includes it.
   */
  def render(error: CliError): String = error match
    case CliError.UnknownSubcommand(token) => s"unknown subcommand: $token"
    case CliError.MissingValue(flag)       => s"missing value for flag: $flag"
    case CliError.InvalidEnum(flag, value) => s"invalid value '$value' for flag: $flag"
    case CliError.UnknownFlag(flag)        => s"unknown flag: $flag"
    case CliError.ForbiddenFlag(flag)      => s"flag not accepted here: $flag"
