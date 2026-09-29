package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `danger-scan` subcommand — production code danger scan. */
object DangerScanCmd:

  /** The predecessor's `BASELINE="HEAD"` default — the working tree. */
  private[cli] val defaultBaseline: String = "HEAD"

  /** The predecessor's parsed invocation shape. */
  final private[cli] case class DangerScanArgs(
    baseline: String,
    alsoFiles: List[String]
  )

  /**
   * Parse args the way the predecessor's `for arg` loop does: a token
   * equal to `--also` switches the rest of the invocation to additional
   * files (even a second `--also` is consumed as the flag); every other
   * token is the positional baseline — last wins, default HEAD.
   *
   * Spec divergence: a `-`-led token before `--also` is a named
   * parameter the scan does not accept — rejected, naming the token.
   * The predecessor would have taken `--frobnicate` as the baseline and
   * reported an empty scope when the diff failed.
   *
   * spec: danger-reconcile-engines — Requirement: The scan's baseline is optional and defaults to the working tree
   * spec: danger-reconcile-engines — Scenario: Error path — an unknown named parameter is rejected naming the token
   */
  private[cli] def parseArgs(args: List[String]): Either[String, DangerScanArgs] =
    def loop(
      remaining: List[String],
      also: List[String],
      parsingAlso: Boolean,
      baseline: String
    ): Either[String, DangerScanArgs] =
      remaining match
        case Nil => Right(DangerScanArgs(baseline, also.reverse))
        case "--also" :: rest =>
          loop(rest, also, parsingAlso = true, baseline)
        case token :: rest if parsingAlso =>
          loop(rest, token :: also, parsingAlso = true, baseline)
        case token :: _ if token.startsWith("-") =>
          Left(token)
        case token :: rest =>
          loop(rest, also, parsingAlso = false, baseline = token)
    loop(args, List.empty, parsingAlso = false, defaultBaseline)

  /**
   * Wire the danger-scan subcommand to the pattern scanner.
   *
   * Resolves the baseline, enumerates the changed production `.scala`
   * files plus `--also` extras, scans them through
   * `DangerScanEngine.scan`, and emits the predecessor's report.
   *
   * spec: cli-wiring — Requirement: The danger-scan subcommand wires to the pattern scanner and emits findings
   * spec: danger-reconcile-engines — Scenario: Error path — an unresolvable baseline is could-not-determine
   * spec: danger-reconcile-engines — Scenario: Edge case — no changed production files reports clean with a stated scope
   */
  def run(args: Array[String]): Outcome[Int] =
    run(args, Paths.get("").toAbsolutePath.normalize)

  private[cli] def run(args: Array[String], cwd: Path): Outcome[Int] =
    parseArgs(args.toList) match
      case Left(token) =>
        SubcommandWiring.emitStderr(s"danger-scan: unrecognised argument: $token\n")
        Outcome.Finding(s"unrecognised argument: $token")
      case Right(parsed) =>
        ChangedFilesReader.resolveBaseline(cwd, parsed.baseline) match
          case Left(reason) =>
            undetermined(reason)
          case Right(_) =>
            ChangedFilesReader.changedProductionFiles(cwd, parsed.baseline) match
              case Left(reason) =>
                undetermined(reason)
              case Right(changed) =>
                val scope: List[String] = (changed ++ parsed.alsoFiles).distinct.sorted
                val files: List[DangerScanEngine.ScannedFile] =
                  ChangedFilesReader.readFiles(cwd, scope)
                if files.isEmpty then
                  SubcommandWiring.emitStdout(
                    s"danger-scan: no production .scala files changed since ${parsed.baseline} (and no --also files).\n"
                  )
                  Outcome.Ran(0)
                else emitReport(DangerScanEngine.scan(files), parsed.baseline)

  /** The predecessor's `die_undetermined` equivalent: stderr + exit 2. */
  private def undetermined(reason: String): Outcome[Int] =
    SubcommandWiring.emitStderr(s"danger-scan: UNDETERMINED — $reason\n")
    Outcome.Undetermined(reason)

  /**
   * Emit the predecessor's report: one `danger-scan: <file>` header per
   * file with hits, each hit as `  [<label>] <line>:<text>`; then the
   * OK line or the candidate-lines summary.
   */
  private def emitReport(report: DangerReport, baseline: String): Outcome[Int] =
    if report.hits.isEmpty then
      SubcommandWiring.emitStdout(
        s"danger-scan: OK — no unjustified dangerous patterns since $baseline.\n"
      )
      Outcome.Ran(0)
    else
      report.hits.map(_.file).distinct.foreach { file =>
        SubcommandWiring.emitStdout(s"danger-scan: $file\n")
        report.hits
          .filter((h: DangerHit) => h.file == file)
          .foreach { (h: DangerHit) =>
            SubcommandWiring.emitStdout(
              s"  [${DangerPattern.label(h.pattern)}] ${h.line}:${h.text}\n"
            )
          }
      }
      SubcommandWiring.emitStdout(
        "\n" +
          s"danger-scan: ${report.hitCount} candidate line(s). Remove each, or justify it with a\n" +
          "same-line '// danger-scan:allow <reason>' comment. A catch-all that maps\n" +
          "an unrecognized variant to a VALID domain value is the bug class this\n" +
          "scan exists for — that one is never justifiable.\n"
      )
      Outcome.Finding(s"danger-scan: ${report.hitCount} candidate line(s)")
