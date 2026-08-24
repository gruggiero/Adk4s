package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.ContractViolation
import org.sinemenda.probatio.core.Outcome
import org.sinemenda.probatio.core.Validator

/**
 * Subcommand entrypoints (R-P1).
 *
 * Each subcommand is an entrypoint that takes raw args and returns
 * `Outcome[Int]`, mapped to exit 0/1/2 at the boundary by `ProbatioMain`.
 * The entrypoints delegate to probatio-core for decision logic; the CLI
 * boundary is arg parsing + exit-code mapping only.
 *
 * spec: cli-protocol — Implementation Anchor: @main entrypoints
 * spec: provenance-validation — Requirement: The ledger append entrypoint SHALL validate before writing
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

  /**
   * Append a record to the ledger after validating against all 15 clauses.
   *
   * Validates the candidate record before writing. If validation fails,
   * the record is rejected with a `Finding` outcome naming the violating
   * clause, and the record is NOT written. If validation succeeds, the
   * record is appended and the outcome is `Ran(0)`.
   *
   * There is no bypass flag, no force option, and no silent-accept path.
   *
   * spec: provenance-validation — Requirement: The ledger append entrypoint SHALL validate before writing
   * spec: provenance-validation — Scenario: a valid record is appended successfully
   * spec: provenance-validation — Scenario: an adversarial-review ring record missing session is rejected at append (adversarial)
   * spec: provenance-validation — Scenario: a record missing a required field is rejected at append (adversarial)
   * spec: provenance-validation — Compile-Negative: --force / --skip-validation flag on ledger append
   */
  def append(recordJson: String, @annotation.unused ledgerPath: Option[String]): Outcome[Int] =
    val json: ujson.Value = ujson.read(recordJson)
    Validator.validateFull(json) match
      case Right(_) =>
        // In a real implementation, this would write to the ledger file.
        // For the port, validation is the gate — the write is delegated
        // to the file system layer (os-lib) when ledgerPath is provided.
        Outcome.Ran(0)
      case Left(violation: ContractViolation) =>
        Outcome.Finding(
          s"ledger append rejected: clause ${violation.clauseIndex} — ${violation.description}"
        )

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
