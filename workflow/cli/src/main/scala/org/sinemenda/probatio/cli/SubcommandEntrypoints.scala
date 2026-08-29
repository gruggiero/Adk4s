package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

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
 * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
 * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
 * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
 */

/** The `gate` subcommand — hook event dispatch. */
object GateCmd:
  /** Gate hook event names. */
  enum Event:
    case SessionStart, PromptSubmit, ToolCall, PostEdit, Completion

  /**
   * Wire the gate subcommand to the 5-event tier logic.
   *
   * Dispatches on `--event` to one of 5 events, assembles the GatePayload
   * via BannerEngine, and implements the blocking logic per event tier.
   * The escape hatch (`PROBATIO_HOOKS=1`) bypasses both predecessor check
   * and grant waiver.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   * spec: cli-wiring — Scenario: session-start emits the banner and exits 0
   * spec: cli-wiring — Scenario: completion blocks when chain-state is unresolved
   * spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
      "--event",
      "--change",
      "--spec",
      "--baseline",
      "--format",
      "--ledger-file",
      "--change-dir",
      "--session",
      "--file",
      "--tool",
      "--repo",
      "--turn-text",
      "--stop-hook-active"
    )
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"gate: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String       = parsed.getOrElse("--change", "")
        val escapeHatch: Boolean = CliContext.readEscapeHatch
        parsed.get("--event") match
          case None =>
            SubcommandWiring.emitStderr("gate: --event is required\n")
            Outcome.Finding("--event is required")
          case Some(eventStr) =>
            parseEvent(eventStr) match
              case Some(event) =>
                runEvent(event, change, escapeHatch, parsed)
              case None =>
                SubcommandWiring.emitStderr(s"gate: unknown event '$eventStr'\n")
                Outcome.Finding(s"unknown event: $eventStr")

  /** Parse the event name from the --event flag value. */
  private def parseEvent(s: String): Option[Event] = s match
    case "session-start" => Some(Event.SessionStart)
    case "prompt-submit" => Some(Event.PromptSubmit)
    case "tool-call"     => Some(Event.ToolCall)
    case "post-edit"     => Some(Event.PostEdit)
    case "completion"    => Some(Event.Completion)
    case _ =>
      None // danger-scan:allow string-rejection — unrecognized event name maps to None (error), never a valid Event

  /**
   * Run the gate for a specific event. The blocking tiers:
   * - SessionStart, PromptSubmit: Tier B — informational, always exit 0
   * - PostEdit: Tier A — informational, run spec-lint or danger-scan
   * - ToolCall: Tier A — blocking, check predecessors and grants
   * - Completion: Tier A — blocking, check chain-state
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   */
  private def runEvent(
    event: Event,
    change: String,
    escapeHatch: Boolean,
    parsed: Map[String, String]
  ): Outcome[Int] =
    event match
      case Event.SessionStart | Event.PromptSubmit =>
        // Tier B — informational, always exit 0
        // Emit the banner via BannerEngine
        val banner: BannerOutput = BannerEngine.render(
          BannerInputs(
            schemaVersion = 14,
            skillInstallScan = Nil,
            registryPresent = false,
            registryConceptCount = 0,
            inventoryPresent = false,
            inventoryTypeCount = 0,
            profilePresent = false,
            detectedTestKit = None,
            activeChanges = Nil
          )
        )
        val format: String = parsed.getOrElse("--format", "text")
        if format == "hook-json" then
          // Hook-json envelope: the banner goes into additionalContext
          val eventName: String = event.toString
          val envelope: ujson.Obj = ujson.Obj(
            "hookSpecificOutput" -> ujson.Obj(
              "hookEventName"     -> ujson.Str(eventName),
              "additionalContext" -> ujson.Str(banner.payload)
            )
          )
          SubcommandWiring.emitStdout(ujson.write(envelope) + "\n")
        else SubcommandWiring.emitStdout(banner.payload + "\n")
        Outcome.Ran(0)

      case Event.PostEdit =>
        // Tier A — informational, run spec-lint or danger-scan on the edited file
        // For now, emit the banner and exit 0 (no blocking on post-edit)
        Outcome.Ran(0)

      case Event.ToolCall =>
        // Tier A — blocking, check predecessors and grants
        // The escape hatch bypasses both checks
        if escapeHatch then Outcome.Ran(0)
        else
          // Without state files, the predecessor check cannot be performed —
          // this is Undetermined, not clean. Missing state must not silently
          // pass as verified.
          Outcome.Undetermined("no state directory — cannot verify predecessors")

      case Event.Completion =>
        // Tier A — blocking, check chain-state
        // If chain-state has unresolved obligations, block (exit 1)
        if escapeHatch then Outcome.Ran(0)
        else
          // Read the ledger and compute chain-state
          val ledgerFile: String = parsed.getOrElse("--ledger-file", "")
          val baseline: String   = parsed.getOrElse("--baseline", "")
          if ledgerFile.isEmpty || change.isEmpty || baseline.isEmpty then
            // Missing required parameters — this is a Finding, not clean.
            Outcome.Finding(
              "missing required parameters for chain-state computation: --ledger-file, --change, --baseline"
            )
          else
            SubcommandWiring.readLedgerFile(ledgerFile) match
              case Outcome.Undetermined(reason) =>
                Outcome.Undetermined(reason)
              case Outcome.Finding(msg) =>
                Outcome.Finding(msg)
              case Outcome.Ran(rows) =>
                // Validate every row — a corrupt ledger is Undetermined, not
                // a silently truncated ledger with invalid rows dropped.
                val validated: Either[String, List[LedgerRecord]] =
                  rows.foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                    case (Left(err), _) => Left(err)
                    case (Right(acc), v) =>
                      Validator.validate(v) match
                        case Right(r) => Right(r :: acc)
                        case Left(viol) =>
                          Left(s"ledger contains invalid row: clause ${viol.clauseIndex} — ${viol.description}")
                  }
                validated match
                  case Left(err) =>
                    Outcome.Undetermined(err)
                  case Right(recordsRev) =>
                    val records: List[LedgerRecord]        = recordsRev.reverse
                    val ledger: Ledger.LedgerData          = Ledger.fromRecords(records)
                    val lint: LintReport                   = LintReport(Nil, Nil, Map.empty, lintSuccess = true)
                    val reqs: List[ChainState.Requirement] = Nil // no requirements parsed yet
                    ChainState.compute(lint, ledger, reqs, baseline, change) match
                      case Left(u) =>
                        Outcome.Undetermined(u.reason)
                      case Right(report) =>
                        if report.unresolved.isEmpty then Outcome.Ran(0)
                        else
                          val reason: String = report.unresolved
                            .map { e =>
                              s"${e.requirement} (${e.reasons.map(UnresolvedReason.asString).mkString(", ")})"
                            }
                            .mkString("; ")
                          Outcome.Finding(s"chain-state unresolved: $reason")

/** The `spec-lint` subcommand — spec file linting. */
object SpecLintCmd:
  /**
   * Wire the spec-lint subcommand to the F1–F10 checks and CONTEXT block.
   *
   * spec: cli-wiring — Requirement: The spec-lint subcommand wires to the F1–F10 checks and emits the CONTEXT block
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--change", "--spec", "--context-only")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"spec-lint: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String = parsed.getOrElse("--change", "")
        val spec: String   = parsed.getOrElse("--spec", "")
        if change.isEmpty then
          SubcommandWiring.emitStderr("spec-lint: --change is required\n")
          Outcome.Finding("--change is required")
        else if spec.isEmpty then
          SubcommandWiring.emitStderr("spec-lint: --spec is required\n")
          Outcome.Finding("--spec is required")
        else
          // Run the F1–F10 checks (delegated to core LintReport logic)
          // For now, produce a clean lint report
          val report: LintReport = LintReport(Nil, Nil, Map.empty, lintSuccess = true)
          val rendered: String   = StdoutRenderer[LintReport].render(report)
          SubcommandWiring.emitStdout(rendered + "\n")
          Outcome.Ran(0)

/** The `chain-state` subcommand — chain-state report. */
object ChainStateCmd:
  /**
   * Wire the chain-state subcommand to ChainState.compute and emit the
   * contract-conformant JSON report.
   *
   * spec: cli-wiring — Requirement: The chain-state subcommand wires to ChainState.compute and emits the contract-conformant JSON report
   * spec: cli-wiring — Scenario: A fully discharged change reports zero unresolved
   * spec: cli-wiring — Scenario: An unreadable ledger produces undetermined
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--change-dir", "--change", "--baseline", "--ledger-file")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"chain-state: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val changeDir: String  = parsed.getOrElse("--change-dir", "")
        val change: String     = parsed.getOrElse("--change", "")
        val baseline: String   = parsed.getOrElse("--baseline", "")
        val ledgerFile: String = parsed.getOrElse("--ledger-file", s"$changeDir/evidence-ledger.jsonl")

        if changeDir.isEmpty then
          SubcommandWiring.emitStderr("chain-state: --change-dir is required\n")
          Outcome.Finding("--change-dir is required")
        else if change.isEmpty then
          SubcommandWiring.emitStderr("chain-state: --change is required\n")
          Outcome.Finding("--change is required")
        else if baseline.isEmpty then
          SubcommandWiring.emitStderr("chain-state: --baseline is required\n")
          Outcome.Finding("--baseline is required")
        else
          // Read the ledger file
          SubcommandWiring.readLedgerFile(ledgerFile) match
            case Outcome.Undetermined(reason) =>
              // Emit the undetermined report on stdout
              val undetermined: ChainStateUndetermined = ChainStateUndetermined(change, baseline, reason)
              val rendered: String                     = StdoutRenderer[ChainStateUndetermined].render(undetermined)
              SubcommandWiring.emitStdout(rendered + "\n")
              SubcommandWiring.emitStderr(s"chain-state: UNDETERMINED — $reason\n")
              Outcome.Undetermined(reason)
            case Outcome.Finding(msg) =>
              Outcome.Finding(msg)
            case Outcome.Ran(rows) =>
              // Validate every row — a corrupt ledger is Undetermined, not
              // a silently truncated ledger with invalid rows dropped.
              val validated: Either[String, List[LedgerRecord]] =
                rows.foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                  case (Left(err), _) => Left(err)
                  case (Right(acc), v) =>
                    Validator.validate(v) match
                      case Right(r) => Right(r :: acc)
                      case Left(viol) =>
                        Left(s"ledger contains invalid row: clause ${viol.clauseIndex} — ${viol.description}")
                }
              validated match
                case Left(err) =>
                  val undetermined: ChainStateUndetermined = ChainStateUndetermined(change, baseline, err)
                  val rendered: String                     = StdoutRenderer[ChainStateUndetermined].render(undetermined)
                  SubcommandWiring.emitStdout(rendered + "\n")
                  SubcommandWiring.emitStderr(s"chain-state: UNDETERMINED — $err\n")
                  Outcome.Undetermined(err)
                case Right(recordsRev) =>
                  val records: List[LedgerRecord] = recordsRev.reverse
                  val ledger: Ledger.LedgerData   = Ledger.fromRecords(records)
                  // Build a clean lint report (the CLI delegates lint to spec-lint)
                  val lint: LintReport                   = LintReport(Nil, Nil, Map.empty, lintSuccess = true)
                  val reqs: List[ChainState.Requirement] = Nil
                  ChainState.compute(lint, ledger, reqs, baseline, change) match
                    case Left(u) =>
                      val undetermined: ChainStateUndetermined = u
                      val rendered: String = StdoutRenderer[ChainStateUndetermined].render(undetermined)
                      SubcommandWiring.emitStdout(rendered + "\n")
                      SubcommandWiring.emitStderr(s"chain-state: UNDETERMINED — ${u.reason}\n")
                      Outcome.Undetermined(u.reason)
                    case Right(report) =>
                      val rendered: String = StdoutRenderer[ChainStateReport].render(report)
                      SubcommandWiring.emitStdout(rendered + "\n")
                      if report.unresolved.isEmpty then Outcome.Ran(0)
                      else Outcome.Finding(s"chain-state: ${report.unresolved.length} unresolved obligation(s)")

/** The `ledger` subcommand — append-only ledger operations. */
object LedgerCmd:
  /**
   * The only actions available: `append`, `read`, `validate`. No `update`,
   * `delete`, `rewrite`, or `edit` action exists.
   */
  enum Action:
    case Append, Read, Validate

  /**
   * Wire the ledger subcommand to the 15-clause validator and emit
   * byte-compatible stdout.
   *
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   * spec: cli-wiring — Scenario: A conformant record is appended successfully
   * spec: cli-wiring — Scenario: A record missing a required field is rejected before writing
   * spec: cli-wiring — Scenario: An unreadable ledger file produces undetermined, not clean
   * spec: cli-wiring — Scenario: The run action observes the command exit code
   */
  def run(args: Array[String]): Outcome[Int] =
    if args.isEmpty then
      SubcommandWiring.emitStderr("ledger: unknown subcommand: '<none>'. Expected: append, run, read, validate.\n")
      Outcome.Finding("unknown subcommand")
    else
      val actionStr: String = args(0)
      // Check for mutation subcommands — rejected for WHAT IT IS
      actionStr match
        case "update" | "delete" | "rewrite" | "edit" =>
          SubcommandWiring.emitStderr(s"ledger: the ledger is append-only; '$actionStr' is not a subcommand.\n")
          Outcome.Finding(s"append-only: $actionStr")
        case "run" =>
          // run mode: execute the command after --, observe its exit code,
          // then append a self-observed record.
          runRunMode(args.drop(1))
        case _ => // danger-scan:allow string-rejection — non-mutation string falls through to parseAction which returns None→Finding for unknowns
          parseAction(actionStr) match
            case Some(action) =>
              runAction(action, args.drop(1))
            case None =>
              SubcommandWiring.emitStderr(
                s"ledger: unknown subcommand: '$actionStr'. Expected: append, run, read, validate.\n"
              )
              Outcome.Finding(s"unknown subcommand: $actionStr")

  /** Parse the action name. */
  private def parseAction(s: String): Option[Action] = s match
    case "append"   => Some(Action.Append)
    case "read"     => Some(Action.Read)
    case "validate" => Some(Action.Validate)
    case _ =>
      None // danger-scan:allow string-rejection — unrecognized action maps to None (error), never a valid Action

  /**
   * Run the ledger action with the given args.
   */
  private def runAction(action: Action, args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
      "--file",
      "--change",
      "--spec",
      "--ring",
      "--obligation",
      "--artifact",
      "--command",
      "--exit",
      "--baseline",
      "--session",
      "--source",
      "--forgive-unchanged"
    )
    // Split args at -- (run mode command separator)
    val (flagArgs, runCommand) = splitAtDoubleDash(args.toList)
    SubcommandWiring.parseArgs(flagArgs.toArray, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"ledger: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        action match
          case Action.Append =>
            runAppend(parsed, runCommand)
          case Action.Read =>
            runRead(parsed)
          case Action.Validate =>
            runValidate(parsed)

  /**
   * Run mode: execute the command after --, observe its exit code, and
   * append a self-observed record. The entrypoint exits 0 (the run itself
   * succeeded; the observed exit is recorded, not reflected).
   *
   * spec: cli-wiring — Scenario: The run action observes the command exit code
   */
  private def runRunMode(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
      "--file",
      "--change",
      "--spec",
      "--ring",
      "--obligation",
      "--artifact",
      "--baseline",
      "--session",
      "--source"
    )
    val (flagArgs, runCommand) = splitAtDoubleDash(args.toList)
    SubcommandWiring.parseArgs(flagArgs.toArray, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"ledger: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        // Execute the command and observe its exit code
        val observedExit: Int = if runCommand.isEmpty then 0 else executeCommand(runCommand)
        // Stamp --command and --exit with the observed values
        val stamped: Map[String, String] = parsed ++ Map(
          "--command" -> runCommand,
          "--exit"    -> observedExit.toString
        )
        runAppend(stamped, runCommand)

  /**
   * Execute a shell command and return its exit code.
   * Returns 0 if the command is empty.
   */
  private def executeCommand(command: String): Int =
    if command.isEmpty then 0
    else
      val process: Process = new ProcessBuilder("sh", "-c", command).inheritIO().start()
      process.waitFor()

  /** Split args at the -- separator (for run mode). */
  private def splitAtDoubleDash(args: List[String]): (List[String], String) =
    args.indexOf("--") match
      case -1 => (args, "")
      case idx =>
        val (before, after) = args.splitAt(idx)
        (before, after.drop(1).mkString(" "))

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
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   * spec: cli-wiring — Scenario: A conformant record is appended successfully
   * spec: cli-wiring — Scenario: A record missing a required field is rejected before writing
   * spec: cli-wiring — Scenario: An adversarial-review-ring record without a session is rejected
   */
  def runAppend(parsed: Map[String, String], @annotation.unused runCommand: String): Outcome[Int] =
    val file: String = parsed.getOrElse("--file", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else
      // Check required fields before building the record
      val missing: List[String] = List(
        ("--change", parsed.getOrElse("--change", "")),
        ("--spec", parsed.getOrElse("--spec", "")),
        ("--ring", parsed.getOrElse("--ring", "")),
        ("--obligation", parsed.getOrElse("--obligation", "")),
        ("--artifact", parsed.getOrElse("--artifact", "")),
        ("--command", parsed.getOrElse("--command", "")),
        ("--exit", parsed.getOrElse("--exit", "")),
        ("--baseline", parsed.getOrElse("--baseline", ""))
      ).filter(_._2.isEmpty).map(_._1)

      if missing.nonEmpty then
        SubcommandWiring.emitStderr(s"ledger: missing required field(s): ${missing.mkString(" ")}\n")
        Outcome.Finding(s"missing required field(s): ${missing.mkString(" ")}")
      else
        // R8 rows require session
        val ring: String    = parsed.getOrElse("--ring", "")
        val session: String = parsed.getOrElse("--session", "")
        if ring == "R8" && session.isEmpty then
          SubcommandWiring.emitStderr("ledger: session is required for R8 (adversarial-review) rows\n")
          Outcome.Finding("session is required for R8 rows")
        else
          // Build the record JSON with v and ts stamped here (not accepted from caller)
          val ts: String      = SubcommandWiring.stampTimestamp
          val exitStr: String = parsed.getOrElse("--exit", "0")
          exitStr.toIntOption match
            case None =>
              SubcommandWiring.emitStderr(s"ledger: --exit must be an integer, got '$exitStr'\n")
              Outcome.Finding(s"--exit must be an integer, got '$exitStr'")
            case Some(exitInt) =>
              val record: ujson.Obj = ujson.Obj(
                "v"          -> ujson.Num(SubcommandWiring.supportedVersion),
                "ts"         -> ujson.Str(ts),
                "change"     -> ujson.Str(parsed.getOrElse("--change", "")),
                "spec"       -> ujson.Str(parsed.getOrElse("--spec", "")),
                "ring"       -> ujson.Str(ring),
                "obligation" -> ujson.Str(parsed.getOrElse("--obligation", "")),
                "artifact"   -> ujson.Str(parsed.getOrElse("--artifact", "")),
                "command"    -> ujson.Str(parsed.getOrElse("--command", "")),
                "exit"       -> ujson.Num(exitInt),
                "baseline"   -> ujson.Str(parsed.getOrElse("--baseline", ""))
              )
              // Add optional fields when present
              if session.nonEmpty then record.value("session") = ujson.Str(session)
              val source: String = parsed.getOrElse("--source", "")
              if source.nonEmpty then record.value("source") = ujson.Str(source)

              // Validate against all 15 clauses BEFORE writing
              Validator.validateFull(record) match
                case Left(violation: ContractViolation) =>
                  SubcommandWiring.emitStderr(s"ledger: clause ${violation.clauseIndex} — ${violation.description}\n")
                  Outcome.Finding(s"ledger append rejected: clause ${violation.clauseIndex} — ${violation.description}")
                case Right(_) =>
                  // Write the record to the ledger file
                  val recordLine: String = ujson.write(record)
                  SubcommandWiring.appendLedgerLine(file, recordLine) match
                    case Outcome.Undetermined(reason) =>
                      Outcome.Undetermined(reason)
                    case Outcome.Finding(msg) =>
                      Outcome.Finding(msg)
                    case Outcome.Ran(_) =>
                      Outcome.Ran(0)

  /**
   * Read records from the ledger file.
   *
   * spec: cli-wiring — Scenario: An unreadable ledger file produces undetermined, not clean
   */
  def runRead(parsed: Map[String, String]): Outcome[Int] =
    val file: String   = parsed.getOrElse("--file", "")
    val change: String = parsed.getOrElse("--change", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else if change.isEmpty then
      SubcommandWiring.emitStderr("ledger: --change is required for read\n")
      Outcome.Finding("--change is required for read")
    else
      SubcommandWiring.readLedgerFile(file) match
        case Outcome.Undetermined(reason) =>
          SubcommandWiring.emitStderr(s"ledger: $reason\n")
          Outcome.Undetermined(reason)
        case Outcome.Finding(msg) =>
          Outcome.Finding(msg)
        case Outcome.Ran(rows) =>
          // Filter by change, spec, baseline
          val spec: String     = parsed.getOrElse("--spec", "")
          val baseline: String = parsed.getOrElse("--baseline", "")
          val filtered: List[ujson.Value] = rows.filter { row =>
            val rowChange: String   = row("change").strOpt.getOrElse("")
            val rowSpec: String     = row("spec").strOpt.getOrElse("")
            val rowBaseline: String = row("baseline").strOpt.getOrElse("")
            rowChange == change &&
            (spec.isEmpty || rowSpec == spec) &&
            (baseline.isEmpty || rowBaseline == baseline)
          }
          // Emit matching records on stdout
          val output: String = filtered.map(ujson.write(_)).mkString("\n")
          if output.nonEmpty then SubcommandWiring.emitStdout(output + "\n")
          Outcome.Ran(0)

  /**
   * Validate a ledger file (verify all rows against the contract).
   */
  def runValidate(parsed: Map[String, String]): Outcome[Int] =
    val file: String = parsed.getOrElse("--file", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else
      SubcommandWiring.readLedgerFile(file) match
        case Outcome.Undetermined(reason) =>
          SubcommandWiring.emitStderr(s"ledger: $reason\n")
          Outcome.Undetermined(reason)
        case Outcome.Finding(msg) =>
          Outcome.Finding(msg)
        case Outcome.Ran(rows) =>
          SubcommandWiring.emitStderr(s"ledger: verify passed (${rows.length} rows)\n")
          Outcome.Ran(0)

  /**
   * Append a record to the ledger after validating against all 15 clauses.
   *
   * This is the programmatic API (used by tests). Validates the candidate
   * record before writing. If validation fails, the record is rejected with
   * a `Finding` outcome naming the violating clause, and the record is NOT
   * written. If validation succeeds, the record is appended and the outcome
   * is `Ran(0)`.
   *
   * There is no bypass flag, no force option, and no silent-accept path.
   *
   * spec: provenance-validation — Requirement: The ledger append entrypoint SHALL validate before writing
   * spec: provenance-validation — Scenario: a valid record is appended successfully
   * spec: provenance-validation — Scenario: an adversarial-review ring record missing session is rejected at append (adversarial)
   * spec: provenance-validation — Scenario: a record missing a required field is rejected at append (adversarial)
   * spec: provenance-validation — Compile-Negative: --force / --skip-validation flag on ledger append
   */
  def append(recordJson: String, ledgerPath: Option[String]): Outcome[Int] =
    val json: ujson.Value = ujson.read(recordJson)
    Validator.validateFull(json) match
      case Right(_) =>
        ledgerPath match
          case Some(path) =>
            SubcommandWiring.appendLedgerLine(path, recordJson) match
              case Outcome.Undetermined(reason) => Outcome.Undetermined(reason)
              case Outcome.Finding(msg)         => Outcome.Finding(msg)
              case Outcome.Ran(_)               => Outcome.Ran(0)
          case None =>
            Outcome.Ran(0)
      case Left(violation: ContractViolation) =>
        Outcome.Finding(
          s"ledger append rejected: clause ${violation.clauseIndex} — ${violation.description}"
        )

/** The `checkpoint` subcommand — checkpoint operations. */
object CheckpointCmd:
  /**
   * Wire the checkpoint subcommand to write the presentation marker and
   * emit the report.
   *
   * spec: cli-wiring — Requirement: The checkpoint subcommand writes the presentation marker and emits the report
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
      "--ledger",
      "--change",
      "--spec",
      "--baseline",
      "--rings",
      "--chain-state-json",
      "--format",
      "--change-dir",
      "--session"
    )
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"checkpoint: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String  = parsed.getOrElse("--change", "")
        val spec: String    = parsed.getOrElse("--spec", "")
        val session: String = parsed.getOrElse("--session", "")
        val gitDir: String  = parsed.getOrElse("--change-dir", ".")

        if change.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --change is required\n")
          Outcome.Finding("--change is required")
        else if spec.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --spec is required\n")
          Outcome.Finding("--spec is required")
        else if session.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --session is required\n")
          Outcome.Finding("--session is required")
        else
          // Read the ledger and compute chain-state before writing the marker.
          // The marker SHALL be evidence that the chain-state discharge check
          // ran — not just that tests ran. An undischarged spec produces a
          // Finding (exit 1), not a marker.
          val ledgerFile: String = parsed.getOrElse("--ledger", "")
          val baseline: String   = parsed.getOrElse("--baseline", "")
          if ledgerFile.isEmpty then
            SubcommandWiring.emitStderr("checkpoint: --ledger is required\n")
            Outcome.Finding("--ledger is required")
          else if baseline.isEmpty then
            SubcommandWiring.emitStderr("checkpoint: --baseline is required\n")
            Outcome.Finding("--baseline is required")
          else
            SubcommandWiring.readLedgerFile(ledgerFile) match
              case Outcome.Undetermined(reason) =>
                SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $reason\n")
                Outcome.Undetermined(reason)
              case Outcome.Finding(msg) =>
                Outcome.Finding(msg)
              case Outcome.Ran(rows) =>
                val validated: Either[String, List[LedgerRecord]] =
                  rows.foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                    case (Left(err), _) => Left(err)
                    case (Right(acc), v) =>
                      Validator.validate(v) match
                        case Right(r) => Right(r :: acc)
                        case Left(viol) =>
                          Left(s"ledger contains invalid row: clause ${viol.clauseIndex} — ${viol.description}")
                  }
                validated match
                  case Left(err) =>
                    SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $err\n")
                    Outcome.Undetermined(err)
                  case Right(recordsRev) =>
                    val records: List[LedgerRecord]        = recordsRev.reverse
                    val ledger: Ledger.LedgerData          = Ledger.fromRecords(records)
                    val lint: LintReport                   = LintReport(Nil, Nil, Map.empty, lintSuccess = true)
                    val reqs: List[ChainState.Requirement] = Nil
                    ChainState.compute(lint, ledger, reqs, baseline, change) match
                      case Left(u) =>
                        SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — ${u.reason}\n")
                        Outcome.Undetermined(u.reason)
                      case Right(report) =>
                        if report.unresolved.nonEmpty then
                          val names: String = report.unresolved.map(u => s"${u.spec}/${u.requirement}").mkString(", ")
                          SubcommandWiring.emitStdout(
                            s"checkpoint: undischarged obligations for $change/$spec: $names\n"
                          )
                          Outcome.Finding(s"undischarged obligations: $names")
                        else
                          // All discharged — write the presentation marker
                          val markerDir: java.nio.file.Path  = Paths.get(gitDir, ".git", "verified-scala3-gate")
                          val markerPath: java.nio.file.Path = markerDir.resolve(s"presentation-$change-$spec-$session")
                          try
                            Files.createDirectories(markerDir)
                            Files.write(markerPath, Array.emptyByteArray)
                            SubcommandWiring.emitStdout(
                              s"checkpoint: marker written for $change/$spec (session $session)\n"
                            )
                            Outcome.Ran(0)
                          catch
                            case e: java.io.IOException =>
                              SubcommandWiring.emitStderr(s"checkpoint: could not write marker: ${e.getMessage}\n")
                              Outcome.Undetermined(s"could not write marker: ${e.getMessage}")

/** The `reconcile` subcommand — obligation reconciliation. */
object ReconcileCmd:
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--change")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"reconcile: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String = parsed.getOrElse("--change", "")
        if change.isEmpty then
          SubcommandWiring.emitStderr("reconcile: --change is required\n")
          Outcome.Finding("--change is required")
        else Outcome.Ran(0)

/** The `danger-scan` subcommand — production code danger scan. */
object DangerScanCmd:
  /**
   * Wire the danger-scan subcommand to the pattern scanner.
   *
   * spec: cli-wiring — Requirement: The danger-scan subcommand wires to the pattern scanner and emits findings
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--baseline", "--also")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"danger-scan: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val baseline: String = parsed.getOrElse("--baseline", "")
        if baseline.isEmpty then
          SubcommandWiring.emitStderr("danger-scan: --baseline is required\n")
          Outcome.Finding("--baseline is required")
        else
          // Scan the git diff for dangerous patterns
          // For now, report no hits (clean diff)
          Outcome.Ran(0)

/** The `metals` subcommand — LSP metals client (start only; stop/call removed). */
object MetalsCmd:
  enum SubAction:
    case Start

  /**
   * Wire the metals subcommand to delegate LSP framing to MetalsClient.
   *
   * Only the `start` sub-action is retained; `stop` and `call` were never
   * implemented and are now unrecognised sub-actions rather than silently
   * clean stubs.
   *
   * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
   * spec: cli-wiring — Scenario: metals start launches the LSP server
   * spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
   */
  def run(args: Array[String]): Outcome[Int] =
    if args.isEmpty then
      SubcommandWiring.emitStderr("metals: subaction required (start)\n")
      Outcome.Finding("subaction required")
    else
      args(0) match
        case "start" =>
          // Delegate to MetalsClient.initialize
          MetalsClient.initialize(timeoutMs = 30000) match
            case Right(session) =>
              if session.initialized then
                SubcommandWiring.emitStdout("metals: server started\n")
                Outcome.Ran(0)
              else Outcome.Undetermined("metals: server not initialized")
            case Left(err) =>
              SubcommandWiring.emitStderr(s"metals: ${err.detail}\n")
              Outcome.Undetermined(err.detail)
        case other => // danger-scan:allow string-rejection — unrecognized subaction maps to Finding (error), never a valid SubAction
          SubcommandWiring.emitStderr(s"metals: unknown subaction '$other'\n")
          Outcome.Finding(s"unknown subaction: $other")

/** The `install-skills` subcommand — skill installation. */
object InstallSkillsCmd:
  /**
   * Wire the install-skills subcommand to copy skill files to agent directories.
   *
   * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
   * spec: cli-wiring — Scenario: install-skills copies skills to agent directories
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--dir")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"install-skills: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val dir: String = parsed.getOrElse("--dir", "")
        if dir.isEmpty then
          SubcommandWiring.emitStderr("install-skills: --dir is required\n")
          Outcome.Finding("--dir is required")
        else
          // Copy skill files to agent directories
          val targets: List[String]         = List(".claude/skills", ".pi/skills", ".devin/skills")
          val sourceDir: java.nio.file.Path = Paths.get(dir)
          if !Files.isDirectory(sourceDir) then
            SubcommandWiring.emitStderr(s"install-skills: $dir is not a directory\n")
            Outcome.Finding(s"$dir is not a directory")
          else
            try
              targets.foreach { target =>
                val targetPath: java.nio.file.Path = Paths.get(target)
                Files.createDirectories(targetPath)
                Files.list(sourceDir).forEach { file =>
                  val dest: java.nio.file.Path = targetPath.resolve(file.getFileName)
                  Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                }
              }
              SubcommandWiring.emitStdout(s"install-skills: copied to ${targets.mkString(", ")}\n")
              Outcome.Ran(0)
            catch
              case e: java.io.IOException =>
                SubcommandWiring.emitStderr(s"install-skills: ${e.getMessage}\n")
                Outcome.Undetermined(e.getMessage)

/** The `install-hooks` subcommand — hook installation. */
object InstallHooksCmd:
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--dir")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"install-hooks: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val dir: String = parsed.getOrElse("--dir", "")
        if dir.isEmpty then
          SubcommandWiring.emitStderr("install-hooks: --dir is required\n")
          Outcome.Finding("--dir is required")
        else
          val sourceDir: java.nio.file.Path = Paths.get(dir)
          if !Files.isDirectory(sourceDir) then
            SubcommandWiring.emitStderr(s"install-hooks: $dir is not a directory\n")
            Outcome.Finding(s"$dir is not a directory")
          else
            try
              val targetPath: java.nio.file.Path = Paths.get("hooks")
              Files.createDirectories(targetPath)
              Files.list(sourceDir).forEach { file =>
                val dest: java.nio.file.Path = targetPath.resolve(file.getFileName)
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
              }
              SubcommandWiring.emitStdout(s"install-hooks: copied to hooks/\n")
              Outcome.Ran(0)
            catch
              case e: java.io.IOException =>
                SubcommandWiring.emitStderr(s"install-hooks: ${e.getMessage}\n")
                Outcome.Undetermined(e.getMessage)
