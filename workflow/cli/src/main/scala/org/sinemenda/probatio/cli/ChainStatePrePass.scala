package org.sinemenda.probatio.cli

import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal // danger-scan:allow process-boundary — an unlaunchable or unparseable pre-pass is classified, never propagated

/**
 * The chain-state pre-pass boundary (spec: chain-state-undetermined-fidelity).
 *
 * The mechanical spec-lint run the verdict is computed from is an EXTERNAL
 * process — the same invocation the predecessor makes — whose termination
 * is classified before any lint data may become the verdict's data.
 * `probe` is the execution-boundary witness: `Right(())` is a completed
 * pre-pass; `Left(reason)` is a did-not-run whose reason always names the
 * unavailable input.
 *
 * spec: chain-state-undetermined-fidelity — Requirement: A verdict is produced only from a completed pre-pass
 * spec: chain-state-undetermined-fidelity — Requirement: Every distinct could-not-determine reason is named
 */
private[cli] object ChainStatePrePass:

  /**
   * The degraded-mode numeric summary — the predecessor's completion
   * marker regex, verbatim.
   */
  private val lintSummaryRe: scala.util.matching.Regex =
    "spec-lint: ([0-9]+) spec file\\(s\\), [0-9]+ FAIL, [0-9]+ WARN".r.unanchored

  /**
   * Spawn the resolved spec-lint (`SPEC_LINT_OVERRIDE`, else the schema's
   * `spec-lint.sh` inside the caller's repository — the predecessor's
   * `$SELF_DIR/spec-lint.sh`) with the mode's invocation shape and
   * classify its termination the way `die_undetermined` does.
   */
  def probe(
    changeDir: Path,
    nSpecFiles: Int,
    graphMode: Boolean,
    repo: Path,
    env: Map[String, String]
  ): Either[String, Unit] =
    val tool: Path = GateCmd.scannerTool(repo, env, "SPEC_LINT_OVERRIDE", "spec-lint.sh")
    if !Files.exists(tool) then Left(s"spec-lint not found at $tool; cannot determine bound/resolved")
    else if !Files.isExecutable(tool) then
      Left(s"spec-lint at $tool is not executable; cannot determine bound/resolved")
    else
      // Graph mode consumes `--format json` on stdout only (2>/dev/null);
      // degraded mode consumes human prose on stdout+stderr (2>&1) — the
      // predecessor's two invocation shapes.
      val args: List[String] =
        if graphMode then List("--artifacts", changeDir.toString, "--format", "json")
        else List("--artifacts", changeDir.toString)
      GateCmd.runScanner(repo, tool, args, mergeStderr = !graphMode) match
        case None =>
          Left(s"spec-lint at $tool could not be launched; cannot determine bound/resolved")
        case Some((lintExit, _)) if lintExit != 0 && lintExit != 1 =>
          // 0/1 are a GENUINE run's exits; anything else means the run
          // did not finish — crash, missing dependency, unreadable
          // target. Never "no findings".
          Left(s"spec-lint exited $lintExit (not a lint-finding exit); cannot determine bound/resolved")
        case Some((lintExit, lintOut)) =>
          if graphMode then
            // The JSON-mode completion check: stdout parses as an array.
            val isArray: Boolean =
              try
                ujson.read(lintOut.trim) match
                  case _: ujson.Arr => // danger-scan:allow type-test — every non-array JSON shape fails the marker check
                    true
                  case _ => // danger-scan:allow marker-absent — only a top-level array is the recognised completion marker
                    false
              catch
                case NonFatal(
                      _
                    ) => // danger-scan:allow unparseable-lint — non-JSON output means no recognised completion marker
                  false
            if isArray then Right(())
            else Left(s"spec-lint --format json did not produce a JSON array (exit $lintExit)")
          else classifyDegradedMarker(lintExit, lintOut, nSpecFiles)

  /**
   * The degraded-mode completion markers — the predecessor's three
   * recognised shapes: the numeric summary (whose file count must agree
   * with this script's own enumeration — a disagreement means a
   * permission or discovery fault), spec-lint's legitimate zero message
   * (only when the enumeration is itself empty), and nothing else.
   */
  private def classifyDegradedMarker(
    lintExit: Int,
    lintOut: String,
    nSpecFiles: Int
  ): Either[String, Unit] =
    lintSummaryRe.findFirstMatchIn(lintOut) match
      case Some(m) =>
        val reported: Int = m.group(1).toInt
        if reported != nSpecFiles then
          Left(
            s"spec-lint saw $reported spec file(s) but this script enumerated $nSpecFiles; " +
              "they disagree, likely a permission or discovery fault"
          )
        else Right(())
      case None =>
        if lintOut.contains("no spec files to lint under") then
          if nSpecFiles != 0 then
            Left(
              s"spec-lint reported no specs to lint, but this script enumerated " +
                s"$nSpecFiles spec.md file(s); they disagree"
            )
          else Right(())
        else
          Left(
            s"spec-lint produced no recognised completion message (exit $lintExit); " +
              "it did not finish running"
          )
