package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `reconcile` subcommand — obligation reconciliation. */
object ReconcileCmd:

  /**
   * The predecessor's five value-flags: `--file`, `--change`, `--spec`,
   * `--baseline`, `--format`. Any other token is an unrecognised
   * argument — never skipped (a typo'd `--baseline` would silently widen
   * the scope and return a clean result over rows the caller never
   * meant to include).
   */
  private val knownFlags: Set[String] =
    Set("--file", "--change", "--spec", "--baseline", "--format")

  /**
   * Wire the reconcile subcommand to ReconcileEngine.classify.
   *
   * Reads the ledger through `SubcommandWiring.readLedgerFile` +
   * `Ledger.readValidated` (every row validated before any conclusion —
   * a malformed row makes the whole file undetermined, not skipped),
   * classifies the in-scope records, emits the predecessor's text or
   * JSON report, and maps the three-way outcome:
   * 0 = every claim witnessed; 1 = testimony or contradicted;
   * 2 = could not determine.
   *
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   * spec: danger-reconcile-engines — Scenario: Error path — an unreadable record set is could-not-determine
   */
  def run(args: Array[String]): Outcome[Int] =
    SubcommandWiring.parseArgs(args, knownFlags) match
      case Left(err) =>
        finding(errorMessage(err))
      case Right(parsed) =>
        val file: Option[String]   = parsed.get("--file").filter(_.nonEmpty)
        val change: Option[String] = parsed.get("--change").filter(_.nonEmpty)
        val format: Option[String] = parsed.get("--format")
        (file, change, format) match
          case (None, _, _) => finding("--file is required")
          case (_, None, _) => finding("--change is required")
          case (_, _, Some(f)) if f != "json" && f != "text" =>
            finding("--format must be json or text")
          case (Some(f), Some(c), fmt) =>
            reconcile(
              f,
              c,
              parsed.get("--spec").filter(_.nonEmpty),
              parsed.get("--baseline").filter(_.nonEmpty),
              formatJson = fmt.contains("json")
            )

  /** The predecessor's `die_finding`: `reconcile: <msg>` on stderr, exit 1. */
  private def finding(message: String): Outcome[Int] =
    SubcommandWiring.emitStderr(s"reconcile: $message\n")
    Outcome.Finding(message)

  /** The predecessor's `die_undetermined`: `reconcile: UNDETERMINED — <msg>` on stderr, exit 2. */
  private def undetermined(reason: String): Outcome[Int] =
    SubcommandWiring.emitStderr(s"reconcile: UNDETERMINED — $reason\n")
    Outcome.Undetermined(reason)

  /** Map a parse error to the predecessor's message text. */
  private def errorMessage(err: CliError): String = err match
    case CliError.MissingValue(flag)       => s"$flag requires a value"
    case CliError.UnknownFlag(token)       => s"unrecognised argument: $token"
    case CliError.UnknownSubcommand(token) => s"unrecognised argument: $token"
    case CliError.InvalidEnum(flag, value) => s"invalid value '$value' for $flag"
    case CliError.ForbiddenFlag(flag)      => s"flag not accepted here: $flag"

  /** Read, validate, classify, and emit — the predecessor's main body. */
  private def reconcile(
    file: String,
    change: String,
    spec: Option[String],
    baseline: Option[String],
    formatJson: Boolean
  ): Outcome[Int] =
    SubcommandWiring.readLedgerFile(file) match
      case Outcome.Undetermined(reason) => undetermined(reason)
      case Outcome.Finding(msg) =>
        Outcome.Finding(
          msg
        ) // danger-scan:allow unreachable-branch — readLedgerFile never yields Finding; passthrough keeps the direction honest
      case Outcome.Ran(rows) =>
        if rows.isEmpty then undetermined(s"$file holds no records")
        else
          Ledger.readValidated(rows) match
            case Left(err) => undetermined(err.description)
            case Right(records) =>
              val report: ReconcileReport =
                ReconcileEngine.classify(records, change, spec, baseline)
              if formatJson then SubcommandWiring.emitStdout(StdoutRenderer.reconcileJson(report) + "\n")
              else SubcommandWiring.emitStdout(StdoutRenderer[ReconcileReport].render(report) + "\n")
              if report.hasFindings then
                Outcome.Finding(
                  s"${report.testimony.length} testimony, ${report.contradicted.length} contradicted"
                )
              else Outcome.Ran(0)
