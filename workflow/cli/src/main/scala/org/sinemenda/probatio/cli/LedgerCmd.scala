package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `ledger` subcommand — append-only ledger operations. */
object LedgerCmd:
  /**
   * The predecessor's operation set: `append`, `run`, `read`, `verify`.
   * No `update`, `delete`, `rewrite`, or `edit` action exists — a
   * modification operation on this type does not compile.
   *
   * spec: ledger-checkpoint-parity — Compile-Negative: A record operation type with an update, delete, or rewrite operation
   */
  enum Action:
    case Append, Run, Read, Verify

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
      SubcommandWiring.emitStderr("ledger: unknown subcommand: '<none>'. Expected: append, run, read, verify.\n")
      Outcome.Finding("unknown subcommand")
    else
      val actionStr: String = args(0)
      // Check for mutation subcommands — rejected for WHAT IT IS, before
      // any parameter parsing (the predecessor refuses them by name).
      actionStr match
        case "update" | "delete" | "rewrite" | "edit" =>
          SubcommandWiring.emitStderr(s"ledger: '$actionStr' is not a subcommand.\n")
          Outcome.Finding(s"append-only: $actionStr")
        case _ => // danger-scan:allow string-rejection — non-mutation string falls through to parseAction which returns None→Finding for unknowns
          parseAction(actionStr) match
            case Some(action) =>
              runAction(action, args.drop(1))
            case None =>
              SubcommandWiring.emitStderr(
                s"ledger: unknown subcommand: '$actionStr'. Expected: append, run, read, verify.\n"
              )
              Outcome.Finding(s"unknown subcommand: $actionStr")

  /** Parse the action name. */
  private def parseAction(s: String): Option[Action] = s match
    case "append" => Some(Action.Append)
    case "run"    => Some(Action.Run)
    case "read"   => Some(Action.Read)
    case "verify" => Some(Action.Verify)
    case _ => // danger-scan:allow string-rejection — unrecognized action maps to None (error), never a valid Action
      None

  /**
   * Run the ledger action with the given args.
   */
  private def runAction(action: Action, args: Array[String]): Outcome[Int] =
    val valueFlags: Set[String] = Set(
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
      "--source"
    )
    val booleanFlags: Set[String] = Set("--forgive-unchanged")
    // Split args at -- (run mode command separator) — a value-taking
    // flag consumes its next token, so `--command --` is data, not the
    // separator.
    val (flagArgs, runCommand) = splitAtDoubleDash(args.toList, valueFlags)
    // The predecessor rejects --exit/--source during the arg loop for
    // `run` — at the position they appear, before any other check.
    val forbidden: Set[String] =
      if action == Action.Run then Set("--exit", "--source") else Set.empty[String]
    SubcommandWiring.parseArgs(flagArgs.toArray, valueFlags, booleanFlags, forbidden) match
      case Left(err) =>
        err match
          case CliError.ForbiddenFlag("--exit") =>
            SubcommandWiring.emitStderr("ledger: run mode does not accept --exit; the script observes the exit code\n")
          case CliError.ForbiddenFlag("--source") =>
            SubcommandWiring.emitStderr("ledger: run mode does not accept --source; a run row is self-observed\n")
          case _ => // danger-scan:allow fallback-render — non-forbidden parse errors render through the shared mapper
            SubcommandWiring.emitStderr(s"ledger: ${SubcommandWiring.argErrorMessage(err)}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        action match
          case Action.Append =>
            runAppend(parsed, runCommand)
          case Action.Run =>
            runRunMode(parsed, runCommand)
          case Action.Read =>
            runRead(parsed)
          case Action.Verify =>
            runVerify(
              parsed,
              SubcommandWiring.repoRootOf,
              SubcommandWiring.sha256OfFile,
              SubcommandWiring.shellParses,
              SubcommandWiring.replayCommand
            )

  /**
   * Run mode: execute the command after --, observe its exit code, and
   * append a self-observed record. The entrypoint exits 0 (the run itself
   * succeeded; the observed exit is recorded, not reflected).
   *
   * spec: cli-wiring — Scenario: The run action observes the command exit code
   */
  private def runRunMode(parsed: Map[String, String], runCommand: String): Outcome[Int] =
    // --exit/--source are already rejected at parse position for `run`
    // (the predecessor dies in the arg loop, before any other check).
    val file: String = parsed.getOrElse("--file", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else if runCommand.isEmpty then
      SubcommandWiring.emitStderr("ledger: run mode requires a command after --\n")
      Outcome.Finding("run mode requires a command after --")
    else
      // Missing REQUIRED inputs are named individually — bare names, as
      // the predecessor's `missing="$missing change"` accumulates them.
      val missing: List[String] = List(
        ("change", parsed.getOrElse("--change", "")),
        ("spec", parsed.getOrElse("--spec", "")),
        ("ring", parsed.getOrElse("--ring", "")),
        ("obligation", parsed.getOrElse("--obligation", "")),
        ("artifact", parsed.getOrElse("--artifact", "")),
        ("baseline", parsed.getOrElse("--baseline", ""))
      ).filter(_._2.isEmpty).map(_._1)
      if missing.nonEmpty then
        SubcommandWiring.emitStderr(s"ledger: missing required field(s): ${missing.mkString(" ")}\n")
        Outcome.Finding(s"missing required field(s): ${missing.mkString(" ")}")
      else
        // Resolve the artifact's pre-run sha256 relative to the ledger's
        // repo root (the command may CREATE the artifact — an
        // unresolvable artifact is a warning, never a fake hash). The
        // predecessor concatenates `$repo_root/$ARTIFACT` — an absolute
        // artifact lands UNDER the repo, it does not escape it.
        val ledgerDir: Path =
          Option(Paths.get(file).toAbsolutePath.normalize.getParent)
            .getOrElse(Paths.get("").toAbsolutePath)
        val repo: Path       = SubcommandWiring.repoRootOf(ledgerDir)
        val artifact: String = parsed.getOrElse("--artifact", "")
        val artifactSha256: String =
          SubcommandWiring.sha256OfFile(Paths.get(s"$repo/$artifact")) match
            case Some(sha) => sha
            case None =>
              SubcommandWiring.emitStderr(
                s"ledger: WARNING — artifact $artifact does not resolve at run time (may be created by the command)\n"
              )
              ""
        // Execute, observing exit + merged output + wall time.
        val (observedExit: Int, output: Array[Byte], wallMs: Long) =
          SubcommandWiring.executeCaptured(repo, runCommand)
        val runDigest: String = SubcommandWiring.sha256Hex(output)
        // R8 rows require the producing session (checked at record-build
        // time, as the predecessor does — after the run has observed).
        val ring: String    = parsed.getOrElse("--ring", "")
        val session: String = parsed.getOrElse("--session", "")
        if ring == "R8" && session.isEmpty then
          SubcommandWiring.emitStderr(
            "ledger: session is required for R8 (adversarial-review) rows — pass --session with the producing session's identity\n"
          )
          Outcome.Finding("session is required for R8 rows")
        else
          val record: ujson.Obj = ujson.Obj(
            "v"          -> ujson.Num(SubcommandWiring.supportedVersion),
            "ts"         -> ujson.Str(SubcommandWiring.stampTimestamp),
            "change"     -> ujson.Str(parsed.getOrElse("--change", "")),
            "spec"       -> ujson.Str(parsed.getOrElse("--spec", "")),
            "ring"       -> ujson.Str(ring),
            "obligation" -> ujson.Str(parsed.getOrElse("--obligation", "")),
            "artifact"   -> ujson.Str(artifact),
            "command"    -> ujson.Str(runCommand),
            "exit"       -> ujson.Num(observedExit),
            "baseline"   -> ujson.Str(parsed.getOrElse("--baseline", "")),
            "sha256"     -> ujson.Str(artifactSha256),
            "digest"     -> ujson.Str(runDigest),
            "wallTime"   -> ujson.Num(wallMs.toDouble)
          )
          // Non-R8 run rows never carry `session` — the predecessor
          // emits it only for the adversarial-review ring.
          if ring == "R8" then record.value("session") = ujson.Str(session)
          Validator.validateFull(record) match
            case Left(violation: ContractViolation) =>
              SubcommandWiring.emitStderr(s"ledger: clause ${violation.clauseIndex} — ${violation.description}\n")
              Outcome.Finding(s"ledger run rejected: clause ${violation.clauseIndex} — ${violation.description}")
            case Right(_) =>
              SubcommandWiring.appendLedgerLine(file, ujson.write(record)) match
                case Outcome.Undetermined(reason) =>
                  SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
                  Outcome.Undetermined(reason)
                case Outcome.Finding(msg) => Outcome.Finding(msg)
                case Outcome.Ran(_) =>
                  SubcommandWiring.emitStderr(
                    s"ledger: run recorded exit=$observedExit wallTime=${wallMs}ms\n"
                  )
                  Outcome.Ran(0)

  /**
   * Split args at the `--` separator (for run mode), walking the arg
   * stream the way the predecessor's case loop does: a value-taking
   * flag consumes its next token VERBATIM — `--command --` stores `--`
   * as the command, it is not the separator. Only a `--` in flag
   * position ends the flags.
   */
  private def splitAtDoubleDash(args: List[String], valueFlags: Set[String]): (List[String], String) =
    @annotation.tailrec
    def loop(rest: List[String], acc: List[String]): (List[String], String) =
      rest match
        case Nil          => (acc.reverse, "")
        case "--" :: tail => (acc.reverse, tail.mkString(" "))
        case flag :: value :: tail if valueFlags.contains(flag) =>
          loop(tail, value :: flag :: acc)
        case head :: tail => loop(tail, head :: acc)
    loop(args, List.empty[String])

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
      // Check required fields before building the record — bare names,
      // as the predecessor's `missing="$missing change"` accumulates them.
      val missing: List[String] = List(
        ("change", parsed.getOrElse("--change", "")),
        ("spec", parsed.getOrElse("--spec", "")),
        ("ring", parsed.getOrElse("--ring", "")),
        ("obligation", parsed.getOrElse("--obligation", "")),
        ("artifact", parsed.getOrElse("--artifact", "")),
        ("command", parsed.getOrElse("--command", "")),
        ("exit", parsed.getOrElse("--exit", "")),
        ("baseline", parsed.getOrElse("--baseline", ""))
      ).filter(_._2.isEmpty).map(_._1)

      if missing.nonEmpty then
        SubcommandWiring.emitStderr(s"ledger: missing required field(s): ${missing.mkString(" ")}\n")
        Outcome.Finding(s"missing required field(s): ${missing.mkString(" ")}")
      else
        // Strict exit grammar, checked before the session rule exactly
        // as the predecessor does: `0 | -?[1-9][0-9]{0,2}` — the value AS
        // WRITTEN (-999..999, no leading zeros, no sign-normalisation):
        // `007` must not silently store 7 in the field that makes a row
        // falsifiable.
        val ring: String    = parsed.getOrElse("--ring", "")
        val session: String = parsed.getOrElse("--session", "")
        val ts: String      = SubcommandWiring.stampTimestamp
        val exitStr: String = parsed.getOrElse("--exit", "0")
        val exitCanonical: Boolean =
          exitStr == "0" || exitStr.matches("-?[1-9][0-9]{0,2}")
        if !exitCanonical then
          SubcommandWiring.emitStderr(s"ledger: exit must be an integer in -999..999 as written, got: $exitStr\n")
          Outcome.Finding(s"exit must be an integer in -999..999 as written, got: $exitStr")
        else if ring == "R8" && session.isEmpty then
          SubcommandWiring.emitStderr(
            "ledger: session is required for R8 (adversarial-review) rows — pass --session with the producing session's identity\n"
          )
          Outcome.Finding("session is required for R8 rows")
        else
          // Build the record JSON with v and ts stamped here (not accepted from caller)
          val exitInt: Int = exitStr.toInt // canonical grammar ⊆ parseable
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
                  SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
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
      val filePath: Path   = Paths.get(file)
      val spec: String     = parsed.getOrElse("--spec", "")
      val baseline: String = parsed.getOrElse("--baseline", "")
      // --forgive-unchanged: a stale row is kept iff the artifact it ran
      // has not changed since the row's baseline — the shared wiring
      // predicate. Absent the flag, nothing forgives.
      val predicate: (String, String) => Boolean =
        if parsed.contains("--forgive-unchanged") then SubcommandWiring.forgivePredicate(file)
        else (_: String, _: String) => false
      if !SubcommandWiring.fileExists(filePath) then
        // The predecessor reports the absent ledger on stdout, exit 2 —
        // "no ledger" is could-not-determine, never a measurement of zero.
        SubcommandWiring.emitStdout(s"ledger: no ledger at $file\n")
        Outcome.Undetermined(s"no ledger at $file")
      else if !Files.isRegularFile(filePath) then
        SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $file is not a regular file\n")
        Outcome.Undetermined(s"$file is not a regular file")
      else if !Files.isReadable(filePath) then
        SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $file is not readable\n")
        Outcome.Undetermined(s"$file is not readable")
      else
        readRowsFiltered(file, change, spec, baseline, predicate) match
          case Outcome.Undetermined(reason) =>
            SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
            Outcome.Undetermined(reason)
          case Outcome.Finding(msg) =>
            Outcome.Finding(msg)
          case Outcome.Ran(rows) =>
            // Emit matching records on stdout
            val output: String = rows.map(ujson.write(_)).mkString("\n")
            if output.nonEmpty then SubcommandWiring.emitStdout(output + "\n")
            Outcome.Ran(0)

  /**
   * Verify a ledger file: every row is contract-checked, judgment-ring
   * (manual/R8) rows must name a resolvable artifact (sha256 traced),
   * and runnable rows are replayed in the repo root — their observed
   * exit must agree with the recorded exit. Any disagreement is a
   * failure; a malformed row makes the whole file undetermined.
   *
   * The seams are the only effectful boundary: `repoRootOf` resolves the
   * ledger file's repository root, `sha256Of` hashes a file,
   * `shellParses` is the predecessor's `bash -n` syntax check, and
   * `replayExit` re-runs the command under a directory returning its
   * observed exit.
   *
   * spec: ledger-checkpoint-parity — Requirement: Replay SHALL assign exactly one verdict to every record
   */
  private[cli] def runVerify(
    parsed: Map[String, String],
    repoRootOf: java.nio.file.Path => java.nio.file.Path,
    sha256Of: java.nio.file.Path => Option[String],
    shellParses: String => Boolean,
    replayExit: (java.nio.file.Path, String) => Int
  ): Outcome[Int] =
    val file: String = parsed.getOrElse("--file", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else
      val filePath: Path = Paths.get(file)
      if !SubcommandWiring.fileExists(filePath) then
        // The predecessor reports the absent ledger on stdout, exit 2 —
        // "no ledger" is could-not-determine, never a measurement of zero.
        SubcommandWiring.emitStdout(s"ledger: no ledger at $file\n")
        Outcome.Undetermined(s"no ledger at $file")
      else if !Files.isRegularFile(filePath) then
        SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $file is not a regular file\n")
        Outcome.Undetermined(s"$file is not a regular file")
      else if !Files.isReadable(filePath) then
        SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $file is not readable\n")
        Outcome.Undetermined(s"$file is not readable")
      else
        SubcommandWiring.readLedgerFile(file) match
          case Outcome.Undetermined(reason) =>
            SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
            Outcome.Undetermined(reason)
          case Outcome.Finding(msg) =>
            Outcome.Finding(msg)
          case Outcome.Ran(rows) =>
            val ledgerDir: Path =
              Option(filePath.toAbsolutePath.normalize.getParent)
                .getOrElse(Paths.get("").toAbsolutePath)
            val repo: Path = repoRootOf(ledgerDir)
            final case class Acc(lineNo: Int, failures: Int)
            val result: Acc = rows.foldLeft(Acc(lineNo = 0, failures = 0)) { (acc: Acc, row: ujson.Value) =>
              val n: Int           = acc.lineNo + 1
              val ring: String     = row("ring").strOpt.getOrElse("")
              val artifact: String = row("artifact").strOpt.getOrElse("")
              val command: String  = row("command").strOpt.getOrElse("")
              // The recorded exit is the contract's integral-JSON-number
              // domain (BigInt), not Int32 — the row passed validation,
              // so the value is whole; a raw `toInt` would silently
              // saturate beyond Int.MaxValue.
              val recorded: BigInt = row("exit").numOpt.map((d: Double) => BigDecimal(d).toBigInt).getOrElse(BigInt(0))
              // The judgment-ring set is the typed model's own
              // (`ReplayVerdict.unreplayableRings`), not a string literal
              // — the code that ships is the code the properties test.
              if Ring.fromString(ring).exists(ReplayVerdict.unreplayableRings.contains) then
                // Judgment rows are not replayed — their evidence is a
                // report artifact that must resolve and hash. The
                // predecessor concatenates `$repo/$artifact` — an
                // absolute artifact lands UNDER the repo.
                if artifact.isEmpty then
                  SubcommandWiring.emitStderr(
                    s"ledger: line $n: manual/R8 rows must name a report artifact\n"
                  )
                  Acc(n, acc.failures + 1)
                else
                  sha256Of(Paths.get(s"$repo/$artifact")) match
                    case None =>
                      SubcommandWiring.emitStderr(
                        s"ledger: line $n: manual row artifact does not resolve: $artifact\n"
                      )
                      Acc(n, acc.failures + 1)
                    case Some(sha) =>
                      SubcommandWiring.emitStderr(
                        s"ledger: line $n: artifact $artifact sha256=$sha\n"
                      )
                      SubcommandWiring.emitStderr(
                        s"ledger: line $n: manual/unreplayable (ring=$ring, artifact=$artifact)\n"
                      )
                      Acc(n, acc.failures)
              else
                // Runnable row — replay when the command parses as shell,
                // then let the typed verdict classify the outcome.
                val replayExitOption: Option[Int] =
                  if shellParses(command) then Some(replayExit(repo, command)) else None
                replayExitOption match
                  case None =>
                    // Not valid shell — skipped replay is named, never
                    // silently passed.
                    SubcommandWiring.emitStderr(
                      s"ledger: line $n: non-manual row command does not parse as shell (skipped replay): $command\n"
                    )
                    Acc(n, acc.failures)
                  case Some(observed) =>
                    ReplayVerdict.classifyName(ring, Some(observed), recorded) match
                      case ReplayVerdict.Matches => Acc(n, acc.failures)
                      case ReplayVerdict.Diverges =>
                        SubcommandWiring.emitStderr(
                          s"ledger: line $n: command/exit disagreement (recorded $recorded, observed $observed): $command\n"
                        )
                        Acc(n, acc.failures + 1)
                      case ReplayVerdict.Unreplayable =>
                        // classifyName yields Matches or Diverges on a
                        // defined replay for a non-judgment ring — this
                        // arm is dead by construction.
                        Acc(
                          n,
                          acc.failures
                        ) // danger-scan:allow dead-arm — judgment rings handled above; replay defined
            }
            if result.failures > 0 then
              SubcommandWiring.emitStderr(
                s"ledger: verify found ${result.failures} failure(s) in ${result.lineNo} row(s)\n"
              )
              Outcome.Finding(s"verify found ${result.failures} failure(s) in ${result.lineNo} row(s)")
            else
              SubcommandWiring.emitStderr(s"ledger: verify passed (${result.lineNo} rows)\n")
              Outcome.Ran(0)

  /**
   * The record tool's own read path: read + contract-validate the file,
   * then keep the rows matching change/spec/baseline — a stale row is
   * forgiven iff `artifactUnchanged` (the predecessor's
   * `--forgive-unchanged` discipline: `git diff --quiet <row.baseline>
   * HEAD -- <artifact>` in the ledger's repo root) says the artifact it
   * ran has not changed.
   *
   * The checkpoint delegates row filtering to THIS path — it never
   * re-filters raw ledger lines itself.
   *
   * spec: ledger-checkpoint-parity — Requirement: Record filtering SHALL delegate to the record tool's own read path
   */
  private[cli] def readRowsFiltered(
    file: String,
    change: String,
    spec: String,
    baseline: String,
    artifactUnchanged: (String, String) => Boolean
  ): Outcome[List[ujson.Value]] =
    SubcommandWiring.readLedgerFile(file) match
      case Outcome.Undetermined(reason) => Outcome.Undetermined(reason)
      case Outcome.Finding(msg)         => Outcome.Finding(msg)
      case Outcome.Ran(rows) =>
        val kept: List[ujson.Value] = rows.filter { (row: ujson.Value) =>
          val rowChange: String   = row("change").strOpt.getOrElse("")
          val rowSpec: String     = row("spec").strOpt.getOrElse("")
          val rowBaseline: String = row("baseline").strOpt.getOrElse("")
          val rowArtifact: String = row("artifact").strOpt.getOrElse("")
          if rowChange != change then false                   // out-of-scope — never forgiven
          else if spec.nonEmpty && rowSpec != spec then false // out-of-scope
          else if baseline.nonEmpty && rowBaseline != baseline then
            // Stale — forgiven iff the artifact the row ran is unchanged
            // since the row's own baseline. artifactUnchanged answers
            // `git diff --quiet <rowBaseline> HEAD -- <artifact>`.
            rowArtifact.nonEmpty && rowBaseline.nonEmpty &&
            artifactUnchanged(rowBaseline, rowArtifact)
          else true
        }
        Outcome.Ran(kept)

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
              case Outcome.Undetermined(reason) =>
                SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
                Outcome.Undetermined(reason)
              case Outcome.Finding(msg) => Outcome.Finding(msg)
              case Outcome.Ran(_)       => Outcome.Ran(0)
          case None =>
            Outcome.Ran(0)
      case Left(violation: ContractViolation) =>
        Outcome.Finding(
          s"ledger append rejected: clause ${violation.clauseIndex} — ${violation.description}"
        )
