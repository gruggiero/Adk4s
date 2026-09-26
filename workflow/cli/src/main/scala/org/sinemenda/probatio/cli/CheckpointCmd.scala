package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `checkpoint` subcommand — checkpoint operations. */
object CheckpointCmd:
  /**
   * Wire the checkpoint subcommand to the predecessor's two operations:
   * `report` (generate the per-ring checkpoint from recorded evidence,
   * consuming the supplied correctness verdict verbatim) and
   * `regenerate-tasks` (rewrite tasks.md checkbox state from the
   * progress tracker). Unknown operations are rejected by name.
   *
   * spec: cli-wiring — Requirement: The checkpoint subcommand writes the presentation marker and emits the report
   * spec: ledger-checkpoint-parity — Requirement: The checkpoint SHALL be generated from recorded evidence, never authored
   */
  def run(args: Array[String]): Outcome[Int] =
    if args.isEmpty then
      SubcommandWiring.emitStderr("checkpoint: unknown subcommand: '<none>'. Expected: report, regenerate-tasks.\n")
      Outcome.Finding("unknown subcommand")
    else
      val sub: String = args(0)
      sub match
        case "report" =>
          runReport(
            args.drop(1),
            ChainStateCmd.forgivePredicate
          )
        case "regenerate-tasks" =>
          runRegenerateTasks(
            args.drop(1),
            SubcommandWiring.readTextFile,
            SubcommandWiring.writeTextFile
          )
        case _ => // danger-scan:allow string-rejection — unknown op maps to Finding, never a valid action
          SubcommandWiring.emitStderr(
            s"checkpoint: unknown subcommand: '$sub'. Expected: report, regenerate-tasks.\n"
          )
          Outcome.Finding(s"unknown subcommand: $sub")

  /**
   * Generate the checkpoint report: read the supplied verdict verbatim,
   * delegate row filtering to the record tool's own read path, and
   * emit the generated report — never a recomputed correctness verdict.
   * The presentation marker is written iff the report's evidence
   * permits it (`report.markerWritten`) AND a `--session` was supplied
   * to key it.
   *
   * spec: ledger-checkpoint-parity — Requirement: The checkpoint SHALL be generated from recorded evidence, never authored
   * spec: ledger-checkpoint-parity — Compile-Negative: A correctness computation referenced from the checkpoint module
   */
  private[cli] def runReport(
    args: Array[String],
    forgivePredicateFor: String => (String, String) => Boolean
  ): Outcome[Int] =
    val valueFlags: Set[String] = Set(
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
    SubcommandWiring.parseArgs(args, valueFlags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"checkpoint: ${SubcommandWiring.argErrorMessage(err)}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val required: List[String] =
          List("--ledger", "--change", "--spec", "--baseline", "--rings", "--chain-state-json")
            .filter((k: String) => parsed.getOrElse(k, "").isEmpty)
        if required.nonEmpty then
          // The predecessor names the FIRST missing flag, in this order.
          val flag: String = required.headOption.getOrElse("--ledger")
          SubcommandWiring.emitStderr(s"checkpoint: $flag is required\n")
          Outcome.Finding(s"$flag is required")
        else
          val format: String = parsed.getOrElse("--format", "json")
          if format != "json" && format != "text" then
            SubcommandWiring.emitStderr("checkpoint: --format must be json or text\n")
            Outcome.Finding("--format must be json or text")
          else
            // --rings is comma-separated; every name must be in the closed
            // domain, rejected by name — never reported unevidenced. The
            // -1 limit keeps empty elements (a bare "," or a trailing
            // comma names an EMPTY ring, which the closed domain rejects)
            // — Java's default split drops them, which would let a
            // delimiter-only value parse as an empty ring list and grant
            // a vacuous all-green marker.
            val ringNames: List[String] = parsed.getOrElse("--rings", "").split(",", -1).toList
            val unknown: Option[String] =
              ringNames.find((n: String) => Ring.fromString(n).isEmpty)
            unknown match
              case Some(bad) =>
                SubcommandWiring.emitStderr(
                  s"checkpoint: unrecognised ring in --rings: $bad (known: R0 R1 R2 R3 R4 R5 R6 R7 R8 R9 manual)\n"
                )
                Outcome.Finding(s"unrecognised ring in --rings: $bad")
              case None =>
                // Every name already validated — flatMap is exact, never
                // a silent fallback to a real ring.
                val rings: List[Ring] =
                  ringNames.flatMap((n: String) => Ring.fromString(n))
                runReportBody(parsed, format, rings, forgivePredicateFor)

  /**
   * The report body once args are validated: consume the supplied verdict
   * verbatim, delegate row filtering to the record tool's read path,
   * generate the checkpoint, write the session-keyed marker when the
   * evidence permits, and emit.
   */
  private def runReportBody(
    parsed: Map[String, String],
    format: String,
    rings: List[Ring],
    forgivePredicateFor: String => (String, String) => Boolean
  ): Outcome[Int] =
    val ledgerFile: String = parsed.getOrElse("--ledger", "")
    val change: String     = parsed.getOrElse("--change", "")
    val spec: String       = parsed.getOrElse("--spec", "")
    val baseline: String   = parsed.getOrElse("--baseline", "")
    val csPath: String     = parsed.getOrElse("--chain-state-json", "")
    val changeDir: String  = parsed.getOrElse("--change-dir", "")
    val sessionArg: String = parsed.getOrElse("--session", "")
    // The supplied verdict is READ, never recomputed: a file that does not
    // parse, lacks a numeric `total`, or is itself undetermined makes the
    // whole checkpoint undetermined — never a clean zero.
    val csFile: Path = Paths.get(csPath)
    if !Files.isReadable(csFile) then
      SubcommandWiring.emitStderr(
        s"checkpoint: UNDETERMINED — chain-state JSON not found or not readable at $csPath\n"
      )
      Outcome.Undetermined(s"chain-state JSON not found or not readable at $csPath")
    else
      val csParsed: Either[String, ujson.Value] =
        try Right(ujson.read(Files.readString(csFile, StandardCharsets.UTF_8)))
        catch
          case NonFatal(_) => // danger-scan:allow typed-catch — bad JSON maps to a named Left
            Left("does not parse")
      csParsed match
        case Left(_) =>
          SubcommandWiring.emitStderr(
            s"checkpoint: UNDETERMINED — chain-state JSON at $csPath does not parse, has no numeric total, or is itself undetermined\n"
          )
          Outcome.Undetermined(s"chain-state JSON at $csPath does not parse or is undetermined")
        case Right(csJson) =>
          val totalNumeric: Boolean = csJson.objOpt.flatMap(_.get("total")).flatMap(_.numOpt).isDefined
          val selfUndetermined: Boolean =
            csJson.objOpt.flatMap(_.get("undetermined")).exists((v: ujson.Value) => v != ujson.Null && v != ujson.False)
          if !totalNumeric || selfUndetermined then
            SubcommandWiring.emitStderr(
              s"checkpoint: UNDETERMINED — chain-state JSON at $csPath does not parse, has no numeric total, or is itself undetermined\n"
            )
            Outcome.Undetermined(s"chain-state JSON at $csPath does not parse or is undetermined")
          else
            // Per-spec baseline: when --change-dir names the change's
            // directory, the spec's own BASELINE SHA from
            // implementation-progress.md supersedes the gate baseline;
            // absent one, the fallback is traced.
            val effectiveBaseline: String =
              if changeDir.isEmpty then baseline
              else
                val progressFile: Path =
                  Paths.get(changeDir).resolve("implementation-progress.md")
                if !Files.isRegularFile(progressFile) then baseline
                else
                  SubcommandWiring.readTextFile(progressFile) match
                    case Left(_) =>
                      SubcommandWiring.emitStderr(
                        s"checkpoint: no per-spec baseline for spec $spec in implementation-progress.md, falling back to gate baseline $baseline\n"
                      )
                      baseline
                    case Right(progressText) =>
                      CheckpointEngine.specBaseline(progressText, spec) match
                        case Some(sha) =>
                          SubcommandWiring.emitStderr(
                            s"checkpoint: using per-spec baseline $sha for spec $spec from implementation-progress.md (gate baseline: $baseline)\n"
                          )
                          sha
                        case None =>
                          SubcommandWiring.emitStderr(
                            s"checkpoint: no per-spec baseline for spec $spec in implementation-progress.md, falling back to gate baseline $baseline\n"
                          )
                          baseline
            LedgerCmd.readRowsFiltered(
              ledgerFile,
              change,
              spec,
              effectiveBaseline,
              forgivePredicateFor(ledgerFile)
            ) match
              case Outcome.Undetermined(reason) =>
                SubcommandWiring.emitStderr(
                  s"checkpoint: UNDETERMINED — ledger read failed: $reason\n"
                )
                Outcome.Undetermined(s"ledger read failed: $reason")
              case Outcome.Finding(msg) =>
                Outcome.Finding(msg)
              case Outcome.Ran(rows) =>
                val records: Either[String, List[LedgerRecord]] =
                  rows.foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                    (acc: Either[String, List[LedgerRecord]], row: ujson.Value) =>
                      acc.flatMap { (rs: List[LedgerRecord]) =>
                        LedgerRecord.from(row) match
                          case Right(r) => Right(r :: rs)
                          case Left(v) =>
                            Left(s"ledger row failed validation: clause ${v.clauseIndex} — ${v.description}")
                      }
                  }
                records match
                  case Left(reason) =>
                    SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $reason\n")
                    Outcome.Undetermined(reason)
                  case Right(reversed) =>
                    val implementingSession: Option[SessionId] =
                      Option(sessionArg).filter(_.nonEmpty).map(SessionId.fromRaw)
                    val report: CheckpointReport = CheckpointEngine.report(
                      change,
                      spec,
                      effectiveBaseline,
                      rings,
                      reversed.reverse,
                      csJson,
                      implementingSession
                    )
                    val output: String =
                      if format == "text" then CheckpointReport.toText(report)
                      else ujson.write(CheckpointReport.toJson(report))
                    // The session-keyed presentation marker: written only
                    // when the report's evidence permits it AND a session
                    // keys it — a side effect, never part of the report.
                    if report.markerWritten && implementingSession.isDefined && changeDir.nonEmpty then
                      writeCheckpointMarker(changeDir, change, spec, sessionArg, output)
                    SubcommandWiring.emitStdout(output + "\n")
                    if report.markerWritten then Outcome.Ran(0)
                    else
                      Outcome.Finding(
                        "checkpoint not clean: not every requested ring is green or the verdict has unresolved obligations"
                      )

  /**
   * The predecessor's checkpoint-presentation side effect: the report
   * output teed to `<git-dir>/verified-scala3-gate/checkpoint-output-
   * <change>-<spec>-<session>`, resolved from --change-dir's repository.
   * Fail-open — an unresolvable state dir or a failed write skips the
   * marker silently, exactly as the predecessor's `tee` does.
   */
  private def writeCheckpointMarker(
    changeDir: String,
    change: String,
    spec: String,
    session: String,
    output: String
  ): Unit =
    val stateDir: Option[GateStateDir] =
      SubcommandWiring.repoContaining(Paths.get(changeDir)).flatMap(GateStateDirReader.resolve)
    stateDir.foreach { (d: GateStateDir) =>
      try
        Files.createDirectories(d.path)
        Files.writeString(
          d.path.resolve(s"checkpoint-output-$change-$spec-$session"),
          output + "\n",
          StandardCharsets.UTF_8
        )
      catch
        case NonFatal(_) => // danger-scan:allow fail-open — a failed marker write degrades, never fails the checkpoint
          ()
    }

  /**
   * Regenerate tasks.md checkbox state from the progress tracker —
   * text-preserving; only the checkbox marker changes. With `--write`
   * the tasks file is rewritten; without it the regenerated content is
   * printed and the exit reports staleness (0 = already matches,
   * 1 = would change, 2 = tracker unreadable).
   */
  private[cli] def runRegenerateTasks(
    args: Array[String],
    readFile: java.nio.file.Path => Either[String, String],
    writeFile: (java.nio.file.Path, String) => Either[String, Unit]
  ): Outcome[Int] =
    val valueFlags: Set[String]   = Set("--progress", "--tasks")
    val booleanFlags: Set[String] = Set("--write")
    SubcommandWiring.parseArgs(args, valueFlags, booleanFlags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"checkpoint: ${SubcommandWiring.argErrorMessage(err)}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val progress: String = parsed.getOrElse("--progress", "")
        val tasks: String    = parsed.getOrElse("--tasks", "")
        if progress.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --progress is required\n")
          Outcome.Finding("--progress is required")
        else if tasks.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --tasks is required\n")
          Outcome.Finding("--tasks is required")
        else
          val progressPath: Path = Paths.get(progress)
          val tasksPath: Path    = Paths.get(tasks)
          readFile(progressPath) match
            case Left(reason) =>
              SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — progress tracker $reason\n")
              Outcome.Undetermined(s"progress tracker $reason")
            case Right(progressText) =>
              readFile(tasksPath) match
                case Left(reason) =>
                  SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — tasks file $reason\n")
                  Outcome.Undetermined(s"tasks file $reason")
                case Right(tasksText) =>
                  val regenerated: String =
                    CheckpointEngine.regenerateTasks(progressText, tasksText)
                  if parsed.contains("--write") then
                    writeFile(tasksPath, regenerated) match
                      case Left(reason) =>
                        SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — could not write $tasks: $reason\n")
                        Outcome.Undetermined(s"could not write $tasks")
                      case Right(_) => Outcome.Ran(0)
                  else
                    // Dry run: the regenerated content is printed and the
                    // exit reports staleness — 0 when already matching,
                    // 1 when the write would change the file.
                    SubcommandWiring.emitStdout(regenerated)
                    if regenerated == tasksText then Outcome.Ran(0)
                    else Outcome.Finding("tasks.md is stale — regenerate with --write")
