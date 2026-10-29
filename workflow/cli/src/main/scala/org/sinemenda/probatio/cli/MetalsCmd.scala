package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `metals` subcommand — LSP metals client (start + stop; `call` remains unported — see the unported-tool register). */
object MetalsCmd:
  enum SubAction:
    case Start, Stop

  /**
   * Wire the metals subcommand to delegate LSP framing to MetalsClient.
   *
   * `start` and `stop` are ported; `call` was never implemented and is
   * an unrecognised sub-action — `metals-call.sh` is recorded in
   * `openspec/schemas/verified-scala3/unported-tools.md`.
   *
   * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
   * spec: cli-wiring — Scenario: metals start launches the LSP server
   * spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
   * spec: unported-tool-register — Requirement: A tool that becomes ported leaves the register
   */
  def run(args: Array[String]): Outcome[Int] =
    if args.isEmpty then
      SubcommandWiring.emitStderr("metals: subaction required (start|stop)\n")
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
        case "stop" =>
          // metals-start.sh stop: ROOT = arg, else git toplevel, else cwd;
          // `ROOT="$(cd "$ROOT" && pwd)"` under `set -e` — a nonexistent
          // or non-directory root exits non-zero.
          val root: Path = args
            .lift(1)
            .map((a: String) => Path.of(a).toAbsolutePath.normalize())
            .getOrElse(SpecLintCmd.repoRoot)
          if !Files.isDirectory(root) then
            SubcommandWiring.emitStderr(s"metals: cannot cd to $root\n")
            Outcome.Finding(s"metals: cannot cd to $root")
          else stopInstance(root)
        case other => // danger-scan:allow string-rejection — unrecognized subaction maps to Finding (error), never a valid SubAction
          SubcommandWiring.emitStderr(s"metals: unknown subaction '$other'\n")
          Outcome.Finding(s"unknown subaction: $other")

  /**
   * `metals-start.sh stop`: kill the recorded per-project instance and
   * remove the discovery files. Exit 0 both ways — a missing or dead
   * instance is a clean no-op. A pid that is alive but cannot be
   * signalled is a finding (the predecessor's `kill` failure exits
   * non-zero before the cleanup runs).
   */
  private def stopInstance(root: Path): Outcome[Int] =
    val meta: Path    = root.resolve(".metals")
    val pidFile: Path = meta.resolve("mcp.pid")
    val urlFile: Path = meta.resolve("mcp.url")
    readRecordedPid(pidFile).filter(canSignal) match
      case Some(pid) =>
        if terminate(pid) then
          SubcommandWiring.emitStdout(s"metals-start: stopped instance for $root (pid $pid)\n")
          Files.deleteIfExists(pidFile)
          Files.deleteIfExists(urlFile)
          Outcome.Ran(0)
        else
          SubcommandWiring.emitStderr(s"metals: could not stop pid $pid for $root\n")
          Outcome.Finding(s"metals: could not stop pid $pid")
      case None =>
        SubcommandWiring.emitStdout(s"metals-start: no running instance recorded for $root\n")
        Files.deleteIfExists(pidFile)
        Files.deleteIfExists(urlFile)
        Outcome.Ran(0)

  /** The recorded pid — `cat mcp.pid`; None when absent, unreadable or unparseable (the predecessor's failed `kill -0` branch). */
  private def readRecordedPid(pidFile: Path): Option[Long] =
    try
      if Files.isRegularFile(pidFile) then Files.readString(pidFile).trim.toLongOption
      else None
    catch case NonFatal(_) => None // danger-scan:allow fail-open — an unreadable pid file is the predecessor's "no running instance" branch

  /**
   * `kill -0` — the pid exists AND this process may signal it. The
   * predecessor's probe, not an existence check: a foreign-owned pid
   * (EPERM) fails `kill -0` and reads as "no running instance", so the
   * same subprocess is run here rather than `ProcessHandle.of`, which
   * reports foreign pids as alive.
   */
  private def canSignal(pid: Long): Boolean =
    try new ProcessBuilder("kill", "-0", pid.toString).start().waitFor() == 0
    catch case NonFatal(_) => false // danger-scan:allow fail-open — an unprobeable pid reads as the predecessor's failed `kill -0`

  /** `kill` — SIGTERM via the same syscall as the predecessor. False when the signal could not be delivered. */
  private def terminate(pid: Long): Boolean =
    try new ProcessBuilder("kill", pid.toString).start().waitFor() == 0
    catch case NonFatal(_) => false // danger-scan:allow fail-open — a failed signal is reported as a finding, not an exception
