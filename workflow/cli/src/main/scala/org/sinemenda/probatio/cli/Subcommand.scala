package org.sinemenda.probatio.cli

/**
 * The exhaustive set of subcommand names, one per predecessor script (R-P1).
 *
 * The port is 1:1 with the predecessor scripts — a port, not a redesign.
 * A subcommand that does not correspond to a predecessor script is a new
 * feature, which the feature freeze (R-X1) forbids. The `metals`
 * subcommand consolidates the two-script `metals-start.sh` /
 * `metals-call.sh` split into sub-subcommands; this is a structural
 * consolidation, not a new capability.
 *
 * No mutation subcommand (`update`, `delete`, `rewrite`, `edit`) exists in
 * this enum — the append-only ledger invariant (§4.2) is enforced by the
 * mutation subcommand not existing (unparseable vs. denylisted — strictly
 * stronger).
 *
 * spec: cli-protocol — Requirement: One subcommand per predecessor script
 * spec: cli-protocol — Requirement: Append-only ledger surface — no mutation subcommands
 */
enum Subcommand:
  case Gate, SpecLint, ChainState, Ledger, Checkpoint, RegistryCheck,
    Reconcile, Scan, RemovalAudit, DangerScan, ImpactScan, Metals,
    ConceptScanner, Graph, InstallSkills, InstallHooks

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
    case Gate           => "gate"
    case SpecLint       => "spec-lint"
    case ChainState     => "chain-state"
    case Ledger         => "ledger"
    case Checkpoint     => "checkpoint"
    case RegistryCheck  => "registry-check"
    case Reconcile      => "reconcile"
    case Scan           => "scan"
    case RemovalAudit   => "removal-audit"
    case DangerScan     => "danger-scan"
    case ImpactScan     => "impact-scan"
    case Metals         => "metals"
    case ConceptScanner => "concept-scanner"
    case Graph          => "graph"
    case InstallSkills  => "install-skills"
    case InstallHooks   => "install-hooks"
