package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.DangerScanEngine

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.control.NonFatal // danger-scan:allow fail-open — subprocess/read failure maps to Left/skipped, never thrown

/**
 * The I/O adapter for `danger-scan` (spec 6): resolves the baseline
 * revision, enumerates the production sources changed since it, and
 * reads in-scope files into `DangerScanEngine.ScannedFile` values for
 * the pure engine.
 *
 * Every query is a `git` subprocess under `repo` — the predecessor's
 * `git diff --name-only "$BASELINE" -- '*.scala'` restricted to paths
 * containing `/src/main/` — plus file reads through `java.nio.file`.
 * Nothing here decides what matches a danger pattern; that is
 * `DangerScanEngine`'s job.
 *
 * Failure policy is the spec's, not the predecessor's: the predecessor
 * ran `git diff ... 2>/dev/null` and printed a clean result when the
 * command failed — an unresolvable baseline was reported as "no
 * production files changed". The port answers could-not-determine
 * instead; a silent fallback would claim the diff is empty.
 *
 * spec: danger-reconcile-engines — Requirement: A baseline that cannot be resolved produces a could-not-determine status
 * spec: danger-reconcile-engines — Implementation Anchor: ChangedFilesReader
 */
object ChangedFilesReader:

  /**
   * Verify that `ref` names a resolvable revision under `repo`
   * (`git rev-parse --verify`). `Right` carries the resolved SHA;
   * `Left` carries a reason — could-not-determine at the boundary.
   */
  def resolveBaseline(repo: Path, ref: String): Either[String, String] =
    git(repo, List("rev-parse", "--verify", s"$ref^{commit}")) match
      case Right(sha) if sha.nonEmpty => Right(sha)
      case _ => // danger-scan:allow reject-to-Left — a failed/empty rev-parse means the baseline does not resolve
        Left(s"baseline '$ref' does not resolve to a commit")

  /**
   * `git` under `repo`; `Right` = trimmed stdout on exit 0, `Left` =
   * stderr (or the launch failure). The predecessor discarded stderr —
   * the port keeps it only as the could-not-determine reason.
   */
  private def git(repo: Path, args: List[String]): Either[String, String] =
    try
      val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
      pb.directory(repo.toFile)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      val err: String =
        new String(p.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
      if p.waitFor() == 0 then Right(out.trim)
      else
        val detail: String = err.trim
        Left(if detail.isEmpty then s"git ${args.mkString(" ")} exited ${p.exitValue()}" else detail)
    catch
      case NonFatal(e) => // danger-scan:allow not-swallowed — the error is captured in the Left reason, not discarded
        Left(s"git ${args.mkString(" ")} failed to launch: ${e.getMessage}")

  /**
   * The production sources changed since `baseline`:
   * `git diff --name-only <baseline> -- '*.scala'` filtered to paths
   * containing `/src/main/` — the predecessor's scope, including the
   * rule that a `.scala` file outside `/src/main/` is out of scope
   * unless the caller names it with `--also`.
   *
   * `Left` when the diff itself fails (a resolvable baseline can still
   * fail to diff); the caller maps every `Left` to could-not-determine.
   */
  def changedProductionFiles(repo: Path, baseline: String): Either[String, List[String]] =
    git(repo, List("diff", "--name-only", baseline, "--", "*.scala")).map { out =>
      if out.isEmpty then List.empty[String]
      else
        out.linesIterator.toList
          .map(_.trim)
          .filter(_.nonEmpty)
          .filter(DangerScanEngine.isProductionPath)
    }

  /**
   * Read each path's lines into a `ScannedFile`, `repo`-relative unless
   * the path is absolute. The reported `path` is the literal string the
   * caller supplied — the predecessor prints `$f` verbatim.
   *
   * Mirrors the predecessor's `[ -f "$f" ]` guard: paths that do not
   * name a readable regular file are skipped, not errored — a vanished
   * path contributes no hits rather than failing the whole scan.
   */
  def readFiles(repo: Path, paths: List[String]): List[DangerScanEngine.ScannedFile] =
    paths.flatMap { (path: String) =>
      val p: Path = Paths.get(path) match
        case abs if abs.isAbsolute => abs
        case rel                   => repo.resolve(rel)
      if !Files.isRegularFile(p) || !Files.isReadable(p) then None
      else
        try
          Some(
            DangerScanEngine.ScannedFile(
              path,
              Files.readString(p, StandardCharsets.UTF_8).linesIterator.toList
            )
          )
        catch
          // predecessor parity: the [ -f ] guard plus grep's silent miss — a vanished or unreadable path contributes no hits
          case _: IOException | _: SecurityException => None // danger-scan:allow typed-catch — silent skip
    }
