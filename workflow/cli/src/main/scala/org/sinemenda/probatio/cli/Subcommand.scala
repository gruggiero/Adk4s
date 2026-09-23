package org.sinemenda.probatio.cli

/**
 * The exhaustive set of subcommand names — only the tools that perform their
 * described work.
 *
 * A subcommand whose behaviour has not been ported is NOT in this enum — it
 * is unparseable rather than recognised-and-silent. The removed names
 * (`registry-check`, `scan`, `removal-audit`, `impact-scan`,
 * `concept-scanner`, plus the mutation commands `update`/`delete`/
 * `rewrite`/`edit`) all produce `UnknownSubcommand` when supplied as a
 * token. Their predecessor implementations remain live and are invoked
 * directly.
 *
 * `graph` IS in the enum: `graph-tool-port` supplies its implementation, so
 * the entrypoint contract's rule — a tool with no implementation is not
 * nameable — is satisfied rather than violated.
 *
 * No mutation subcommand (`update`, `delete`, `rewrite`, `edit`) exists in
 * this enum — the append-only ledger invariant (§4.2) is enforced by the
 * mutation subcommand not existing (unparseable vs. denylisted — strictly
 * stronger).
 *
 * spec: cli-entrypoint-contract — Requirement: A tool that has no implementation is not nameable on the tool surface
 * spec: cli-protocol — Requirement: Append-only ledger surface — no mutation subcommands
 * spec: graph-tool-port — Requirement: The tool surface names the five operations
 */
enum Subcommand:
  case Gate, SpecLint, ChainState, Ledger, Checkpoint, Reconcile,
    DangerScan, Metals, InstallSkills, InstallHooks, Graph

object Subcommand:

  /**
   * Parses a subcommand name from a string token.
   *
   * Returns `Right(sub)` if the token names a valid subcommand, or
   * `Left(error)` with the offending token if it does not. The mutation
   * subcommand names (`update`, `delete`, `rewrite`, `edit`) are not in
   * the enum and therefore produce `UnknownSubcommand` — they are
   * unparseable, not merely denylisted.
   *
   * spec: cli-protocol — Scenario: Unknown subcommand is rejected
   * spec: cli-protocol — Scenario: Mutation subcommand does not exist (adversarial)
   */
  def fromString(token: String): Either[CliError, Subcommand] =
    values.find(sub => cliName(sub) == token) match
      case Some(sub) => Right(sub)
      case None      => Left(CliError.UnknownSubcommand(token))

  /** The string form used on the command line (kebab-case). */
  def cliName(sub: Subcommand): String = sub match
    case Gate          => "gate"
    case SpecLint      => "spec-lint"
    case ChainState    => "chain-state"
    case Ledger        => "ledger"
    case Checkpoint    => "checkpoint"
    case Reconcile     => "reconcile"
    case DangerScan    => "danger-scan"
    case Metals        => "metals"
    case InstallSkills => "install-skills"
    case InstallHooks  => "install-hooks"
    case Graph         => "graph"
