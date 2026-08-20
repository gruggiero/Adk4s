package org.sinemenda.probatio.cli

/**
 * Arg-parse error algebra (R-P4).
 *
 * Every variant carries the offending token — the error message MUST name
 * the missing or invalid flag. A `CliError` variant without an
 * `offendingToken` field does not compile (the sealed abstract class
 * requires it in the constructor).
 *
 * spec: cli-protocol — Requirement: Arg parsing errors name the missing or invalid flag
 * spec: cli-protocol — Compile-Negative: A CliError variant without an offendingToken field
 */
sealed abstract class CliError private[cli] (val offendingToken: String) extends Product with Serializable

object CliError:
  /** An unknown subcommand name (e.g. `update`, `foo`). */
  final case class UnknownSubcommand(token: String) extends CliError(token)

  /**
   * A flag that requires a value but none was provided (e.g. `--baseline`
   * with no following value).
   */
  final case class MissingValue(flag: String) extends CliError(flag)

  /**
   * A flag whose value is not in the expected enum (e.g. `--event invalid`
   * where `--event` expects a known event name).
   */
  final case class InvalidEnum(flag: String, value: String) extends CliError(value)

  /** A flag not recognized by the subcommand (e.g. `--unknown-flag`). */
  final case class UnknownFlag(flag: String) extends CliError(flag)
