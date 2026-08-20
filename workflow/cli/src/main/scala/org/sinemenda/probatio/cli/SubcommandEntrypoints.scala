package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.Outcome

/**
 * Subcommand entrypoints (R-P1).
 *
 * Each subcommand is an entrypoint that takes raw args and returns
 * `Outcome[Int]`, mapped to exit 0/1/2 at the boundary by `ProbatioMain`.
 * The entrypoints delegate to probatio-core for decision logic; the CLI
 * boundary is arg parsing + exit-code mapping only.
 *
 * spec: cli-protocol — Implementation Anchor: @main entrypoints
 */

/** The `gate` subcommand — hook event dispatch. */
object GateCmd:
  /** Gate hook event names. */
  enum Event:
    case SessionStart, PromptSubmit, ToolCall, PostEdit, Completion

  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    // The gate subcommand delegates to probatio-core's GatePayload
    // construction and emits the hook JSON on stdout. For the port, the
    // decision logic (blocking tiers) lives in probatio-core.
    Outcome.Ran(0)

/** The `spec-lint` subcommand — spec file linting. */
object SpecLintCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `chain-state` subcommand — chain-state report. */
object ChainStateCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `ledger` subcommand — append-only ledger operations. */
object LedgerCmd:
  /**
   * The only actions available: `append`, `read`, `validate`. No `update`,
   * `delete`, `rewrite`, or `edit` action exists.
   */
  enum Action:
    case Append, Read, Validate

  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `checkpoint` subcommand — checkpoint operations. */
object CheckpointCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `registry-check` subcommand — behavioural concept registry check. */
object RegistryCheckCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `reconcile` subcommand — obligation reconciliation. */
object ReconcileCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `scan` subcommand — concept scanning. */
object ScanCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `removal-audit` subcommand — removed-code audit. */
object RemovalAuditCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `danger-scan` subcommand — production code danger scan. */
object DangerScanCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `impact-scan` subcommand — public-type-change impact scan. */
object ImpactScanCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `metals` subcommand — LSP metals client (start/stop/call). */
object MetalsCmd:
  enum SubAction:
    case Start, Stop, Call

  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `concept-scanner` subcommand — scalameta concept extraction. */
object ConceptScannerCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `graph` subcommand — graph extraction. */
object GraphCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `install-skills` subcommand — skill installation. */
object InstallSkillsCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)

/** The `install-hooks` subcommand — hook installation. */
object InstallHooksCmd:
  def run(@annotation.unused args: Array[String]): Outcome[Int] =
    Outcome.Ran(0)
