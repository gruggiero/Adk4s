package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.HeartbeatRecord
import org.sinemenda.probatio.core.SessionId

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.control.NonFatal // danger-scan:allow fail-open — gate state IO degrades, never fails a session

/**
 * The resolved per-repository gate state directory:
 * `<absolute-git-dir>/verified-scala3-gate`.
 *
 * Resolved via `git rev-parse --absolute-git-dir`, which answers correctly
 * for a plain checkout, a linked worktree (where `.git` is a `gitdir:`
 * pointer file, not a directory), and a submodule. Unresolvable when the
 * repository is not a git repository — in which case the gate still runs
 * but no state is persisted (fail-open, matching the predecessor).
 *
 * Forward reference (recorded at spec 3 Step 0): `GateStateDir` is a spec-8
 * concept ("heartbeat, per-session suppression fingerprint, grant tokens,
 * presentation markers, oracle phase"). Spec 3 introduces the minimal form —
 * resolution, suppression fingerprints, and the heartbeat — because
 * `gate-payload.bats` exercises all three and is spec 3's parity obligation.
 * Spec 8 extends it with grant tokens, presentation markers, and oracle
 * phase.
 *
 * spec: gate-event-completeness — Concepts Introduced: GateStateDir
 * spec: live-fact-banner — Requirement: Repeated injections within one session are suppressed only when the underlying facts are unchanged
 */
final case class GateStateDir(path: Path)

/**
 * The only file-reading component for gate state (R-P1 adapter).
 */
object GateStateDirReader:

  private val heartbeatFileName: String = "heartbeat"

  /**
   * Resolve the state directory for a repository: run
   * `git rev-parse --absolute-git-dir` under `repoRoot` and return
   * `<git-dir>/verified-scala3-gate`. `None` when the repository is not a
   * git repository or git is unavailable.
   */
  def resolve(repoRoot: Path): Option[GateStateDir] =
    try
      val pb: ProcessBuilder =
        new ProcessBuilder("git", "rev-parse", "--absolute-git-dir")
      pb.directory(repoRoot.toFile)
      // predecessor parity: `git ... 2>/dev/null` — stderr is discarded.
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if p.waitFor() == 0 && out.trim.nonEmpty then
        Some(GateStateDir(Path.of(out.trim).resolve("verified-scala3-gate")))
      else None
    catch case NonFatal(_) => None // danger-scan:allow fail-open — unresolvable git-dir means no state, not an error

  /** The suppression-fingerprint file for a session: `fp-<encoded>`. */
  def fingerprintFile(dir: GateStateDir, session: SessionId): Path =
    dir.path.resolve(s"fp-${session.encoded}")

  /**
   * Read the recorded facts fingerprint for a session, if any. `None` when
   * the state directory does not exist or the file cannot be read.
   */
  def readFingerprint(dir: GateStateDir, session: SessionId): Option[String] =
    try
      val f: Path = fingerprintFile(dir, session)
      if Files.isRegularFile(f) then Some(Files.readString(f, StandardCharsets.UTF_8))
      else None
    catch
      case NonFatal(_) => None // danger-scan:allow fail-open — a missing/unreadable fingerprint means "not suppressed"

  /**
   * Record the facts fingerprint for a session. Creates the state directory
   * if needed; a write failure is swallowed (suppression is a degraded
   * capability, never a reason to fail a session).
   */
  def writeFingerprint(
    dir: GateStateDir,
    session: SessionId,
    fingerprint: String
  ): Unit =
    try
      Files.createDirectories(dir.path)
      Files.writeString(
        fingerprintFile(dir, session),
        fingerprint,
        StandardCharsets.UTF_8
      )
    catch case NonFatal(_) => () // danger-scan:allow fail-open — suppression is a degraded capability, never a failure

  /**
   * Write the heartbeat record (`{ts, event, format}`) for this invocation.
   * Creates the state directory if needed; a write failure is swallowed.
   */
  def writeHeartbeat(dir: GateStateDir, record: HeartbeatRecord): Unit =
    try
      Files.createDirectories(dir.path)
      val json: String = ujson.write(
        ujson.Obj(
          "ts"     -> ujson.Str(record.ts),
          "event"  -> ujson.Str(record.event),
          "format" -> ujson.Str(record.format)
        )
      )
      Files.writeString(
        dir.path.resolve(heartbeatFileName),
        json + "\n",
        StandardCharsets.UTF_8
      )
    catch case NonFatal(_) => () // danger-scan:allow fail-open — the heartbeat is best-effort

  /**
   * Read the heartbeat record, if present and parseable. `None` when the
   * gate has never run here or the record cannot be read.
   */
  def readHeartbeat(dir: GateStateDir): Option[HeartbeatRecord] =
    try
      val f: Path = dir.path.resolve(heartbeatFileName)
      if !Files.isRegularFile(f) then None
      else
        val content: String = Files.readString(f, StandardCharsets.UTF_8)
        if content.isEmpty then None
        else
          ujson.read(content) match
            case obj: ujson.Obj =>
              // predecessor parity: `.ts // empty` / `.event // empty` —
              // a present-but-non-string field reads as "", not as absent
              def str(k: String): String = obj.value.get(k) match
                case Some(ujson.Str(s)) => s
                case _                  => "" // danger-scan:allow jq-empty-parity — predecessor's `// empty` yields ""
              Some(HeartbeatRecord(str("ts"), str("event"), str("format")))
            case _ => // danger-scan:allow jq-parity — the predecessor treats ANY valid JSON heartbeat as a run record
              Some(HeartbeatRecord("", "", ""))
    catch case NonFatal(_) => None // danger-scan:allow fail-open — unparseable heartbeat reads as "not installed"

end GateStateDirReader
