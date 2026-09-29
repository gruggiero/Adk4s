package org.sinemenda.probatio.cli

/**
 * A flag's help entry: name, description, and default (or "required" / "none").
 *
 * spec: cli-protocol — Requirement: Help lists every flag with its default
 */
final case class FlagHelp(
  name: String,
  description: String,
  default: String // "required" for mandatory flags, "none" for no-default, or the default value
)

/**
 * An exit-code documentation entry for `--help` output.
 *
 * spec: cli-protocol — Scenario: Help documents exit codes
 */
final case class ExitCodeDoc(
  code: Int,
  label: String,
  condition: String
)

/**
 * The `--help` output for a subcommand (R-P5).
 *
 * Lists every flag accepted by the subcommand with its default value (or
 * "required" for mandatory flags, or "none" for flags with no default).
 * The help output MUST NOT omit any flag. Each subcommand's `--help` also
 * documents which exit codes it can produce (0, 1, 2) and the conditions
 * under which each is emitted.
 *
 * A consumer SHALL never need to open the source to discover the
 * subcommand's interface.
 *
 * spec: cli-protocol — Requirement: Help lists every flag with its default
 * spec: cli-protocol — Property: help-lists-every-flag
 */
final case class HelpOutput(
  subcommand: Subcommand,
  flags: List[FlagHelp],
  exitCodes: List[ExitCodeDoc]
):

  /** Renders the help output to a string for stdout. */
  def render: String =
    val subLine: String   = s"probatio ${Subcommand.cliName(subcommand)} [options]"
    val flagLines: String = flags.map(f => s"  ${f.name}  ${f.description} (default: ${f.default})").mkString("\n")
    val exitLines: String = exitCodes.map(e => s"  exit ${e.code} — ${e.label}: ${e.condition}").mkString("\n")
    s"$subLine\n\nOptions:\n$flagLines\n\nExit codes:\n$exitLines\n"

object HelpOutput:

  /**
   * The three-way exit protocol documentation, shared by all subcommands
   * that can produce all three exit codes.
   */
  val threeWayExit: List[ExitCodeDoc] = List(
    ExitCodeDoc(0, "clean", "ran and found nothing wrong"),
    ExitCodeDoc(1, "finding", "ran and found something"),
    ExitCodeDoc(2, "undetermined", "could not determine")
  )

  /** Exit codes for subcommands that cannot produce an undetermined outcome. */
  val twoWayExit: List[ExitCodeDoc] = List(
    ExitCodeDoc(0, "clean", "ran and found nothing wrong"),
    ExitCodeDoc(1, "finding", "ran and found something")
  )
