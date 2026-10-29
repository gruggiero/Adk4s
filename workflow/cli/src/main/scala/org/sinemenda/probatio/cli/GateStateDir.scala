package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.GateDecisions
import org.sinemenda.probatio.core.HeartbeatRecord
import org.sinemenda.probatio.core.SessionId
import org.sinemenda.probatio.core.SpecPhase

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using
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
   * gate has never run here or the record cannot be read — and also for
   * a `null`/`false` heartbeat: the predecessor's `jq -e .` gate exits 1
   * on both, reporting not-installed. Any OTHER valid JSON — including
   * non-object shapes — is a run record (installed:true), with the
   * fields read as `.ts // empty` / `.event // empty`: absent, `null`,
   * or `false` fields read as `""`; non-string values render with
   * `jq -r` semantics (a field read on a non-object errors to `""`).
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
            case ujson.Null | ujson.Bool(false) => None
            case obj: ujson.Obj =>
              Some(
                HeartbeatRecord(
                  heartbeatField(obj, "ts"),
                  heartbeatField(obj, "event"),
                  heartbeatField(obj, "format")
                )
              )
            case _ => // danger-scan:allow jq-parity — any other valid JSON is a run record with unreadable fields
              Some(HeartbeatRecord("", "", ""))
    catch case NonFatal(_) => None // danger-scan:allow fail-open — unparseable heartbeat reads as "not installed"

  /** `.key // empty` under `jq -r`: null/false/absent → ""; else rendered. */
  private def heartbeatField(obj: ujson.Obj, key: String): String =
    obj.value.get(key) match
      case Some(ujson.Str(s)) => s
      case Some(ujson.Num(n)) =>
        if n == n.toInt.toDouble then n.toInt.toString else n.toString
      // Bound pattern, not two literal cases — the BooleanLiteral mutant
      // `Bool(true)→Bool(false)` would duplicate the other arm and fail
      // to compile (duplicate case under -Werror).
      case Some(ujson.Bool(b))     => if b then "true" else ""
      case Some(ujson.Null) | None => ""
      case Some(other: ujson.Value) => // danger-scan:allow jq-render — arrays/objects render as compact JSON
        ujson.write(other)

  // ── oracle phase (spec 8) ───────────────────────────────────────────

  /** The phase state file for a spec: `phase-<change>-<spec>`. */
  def phaseFile(dir: GateStateDir, change: String, spec: String): Path =
    dir.path.resolve(s"phase-$change-$spec")

  /**
   * Read the recorded phase for a spec. Total: an absent, unreadable,
   * or unrecognised file reads as `Oracle` — the predecessor's
   * `*)` default — never an error and never a fabricated later phase.
   */
  def readPhase(dir: GateStateDir, change: String, spec: String): SpecPhase =
    try
      val f: Path = phaseFile(dir, change, spec)
      if Files.isRegularFile(f) then SpecPhase.fromStateFile(Files.readString(f, StandardCharsets.UTF_8))
      else SpecPhase.Oracle
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — unreadable phase reads as oracle
        SpecPhase.Oracle

  /** Record the phase for a spec. A write failure is swallowed. */
  def writePhase(
    dir: GateStateDir,
    change: String,
    spec: String,
    phase: SpecPhase
  ): Unit =
    try
      Files.createDirectories(dir.path)
      Files.writeString(
        phaseFile(dir, change, spec),
        SpecPhase.asToken(phase),
        StandardCharsets.UTF_8
      )
    catch
      case NonFatal(_) => () // danger-scan:allow fail-open — phase tracking is a degraded capability, never a failure

  // ── presentation markers (spec 8) ───────────────────────────────────

  /** The presentation marker for a spec in a session: `presentation-<change>-<spec>-<encoded>`. */
  def presentationFile(
    dir: GateStateDir,
    change: String,
    spec: String,
    session: SessionId
  ): Path =
    dir.path.resolve(s"presentation-$change-$spec-${session.encoded}")

  /** The presentation marker's recorded hash, if the marker exists. */
  def readPresentationHash(
    dir: GateStateDir,
    change: String,
    spec: String,
    session: SessionId
  ): Option[String] =
    try
      val f: Path = presentationFile(dir, change, spec, session)
      if Files.isRegularFile(f) then Some(Files.readString(f, StandardCharsets.UTF_8))
      else None
    catch case NonFatal(_) => None // danger-scan:allow fail-open — unreadable marker reads as "not presented"

  /**
   * True when ANY session holds a presentation marker for the spec
   * (`presentation-<change>-<spec>-*`) — the completion tier's
   * corroboration check and the tool-call tier's predecessor gate.
   */
  def hasAnySessionPresentation(dir: GateStateDir, change: String, spec: String): Boolean =
    matchingFiles(dir, s"presentation-$change-$spec-").nonEmpty

  /**
   * True when THIS session holds a presentation marker for any spec in
   * any change (`presentation-*-*-<encoded>`) — the completion tier's
   * "a completion claim is being made" signal. The glob requires at
   * least two `-`-segments before the session, matching the
   * predecessor's `presentation-*-*-$SESSION` pattern exactly.
   */
  def hasSessionPresentation(dir: GateStateDir, session: SessionId): Boolean =
    val suffix: String = s"-${session.encoded}"
    matchingFiles(dir, "presentation-").exists { (name: String) =>
      val rest: String = name.stripPrefix("presentation-")
      rest.endsWith(suffix) && rest.dropRight(suffix.length).contains("-")
    }

  /**
   * The `(change, spec)` pairs this session has presented — every file
   * matching the predecessor's `presentation-*-*-<encoded>` glob (the
   * name ends with `-<encoded>` and carries at least one hyphen before
   * it). The file's own name is then parsed right-to-left — the same
   * quirk as the predecessor, which parses `${rest##*-}` even when the
   * encoded session itself contains hyphens.
   */
  def sessionPresentations(dir: GateStateDir, session: SessionId): List[(String, String)] =
    val suffix: String = s"-${session.encoded}"
    matchingFiles(dir, "presentation-").flatMap { (name: String) =>
      val rest: String = name.stripPrefix("presentation-")
      if rest.endsWith(suffix) && rest.dropRight(suffix.length).contains("-") then
        GateDecisions
          .markerTriple("presentation-", name)
          .map((change: String, spec: String, _: String) => (change, spec))
      else None
    }

  /** Record a presentation marker with the content hash. A write failure is swallowed. */
  def writePresentation(
    dir: GateStateDir,
    change: String,
    spec: String,
    session: SessionId,
    hash: String
  ): Unit =
    writeStateFile(dir, s"presentation-$change-$spec-${session.encoded}", hash)

  // ── grant tokens (spec 8) ───────────────────────────────────────────

  /** The grant token for a spec in a session: `grant-<change>-<spec>-<encoded>`. */
  def grantFile(
    dir: GateStateDir,
    change: String,
    spec: String,
    session: SessionId
  ): Path =
    dir.path.resolve(s"grant-$change-$spec-${session.encoded}")

  /** True when this session holds a grant for the spec. */
  def hasGrant(dir: GateStateDir, change: String, spec: String, session: SessionId): Boolean =
    try Files.isRegularFile(grantFile(dir, change, spec, session))
    catch case NonFatal(_) => false // danger-scan:allow fail-open — unreadable grant reads as "not granted"

  /**
   * The first `grant-<change>-<spec>-*` file name, sorted — the
   * predecessor's `ls … | head -1` `any_grant` probe.
   */
  def findAnySessionGrant(dir: GateStateDir, change: String, spec: String): Option[String] =
    matchingFiles(dir, s"grant-$change-$spec-").headOption

  /**
   * True when ANY session holds a grant for the spec
   * (`grant-<change>-<spec>-*`) — the predecessor's `any_grant` probe.
   */
  def hasAnySessionGrant(dir: GateStateDir, change: String, spec: String): Boolean =
    findAnySessionGrant(dir, change, spec).nonEmpty

  /**
   * Write a grant token carrying the presentation hash — only when the
   * grant does not already exist (the predecessor's idempotent write).
   * A write failure is swallowed.
   */
  def writeGrant(
    dir: GateStateDir,
    change: String,
    spec: String,
    session: SessionId,
    hash: String
  ): Unit =
    try
      val f: Path = grantFile(dir, change, spec, session)
      if !Files.isRegularFile(f) then
        Files.createDirectories(dir.path)
        Files.writeString(f, hash, StandardCharsets.UTF_8)
    catch case NonFatal(_) => () // danger-scan:allow fail-open — grants are best-effort

  // ── refusal markers (spec 8) ────────────────────────────────────────

  /** The refusal marker file for a session: `<prefix><encoded>`. */
  def refusalFile(dir: GateStateDir, kind: RefusalKind, session: SessionId): Path =
    dir.path.resolve(s"${RefusalKind.markerPrefix(kind)}${session.encoded}")

  /** True when this session's refusal marker for `kind` exists. */
  def hasRefusal(dir: GateStateDir, kind: RefusalKind, session: SessionId): Boolean =
    try Files.isRegularFile(refusalFile(dir, kind, session))
    catch case NonFatal(_) => false // danger-scan:allow fail-open — unreadable marker reads as "not yet refused"

  /**
   * Write this session's refusal marker for `kind`. Returns `false`
   * when the marker could not be written — the caller fails open
   * rather than blocking without a bound.
   */
  def writeRefusal(dir: GateStateDir, kind: RefusalKind, session: SessionId): Boolean =
    try
      Files.createDirectories(dir.path)
      Files.writeString(refusalFile(dir, kind, session), "", StandardCharsets.UTF_8)
      true
    catch case NonFatal(_) => false // danger-scan:allow fail-open — an unwritable bound means allow, not block

  /**
   * Clear all three refusal markers for the session — the
   * `prompt-submit` tier's turn reset.
   */
  def clearRefusals(dir: GateStateDir, session: SessionId): Unit =
    RefusalKind.values.foreach { (kind: RefusalKind) =>
      try Files.deleteIfExists(refusalFile(dir, kind, session))
      catch case NonFatal(_) => () // danger-scan:allow fail-open — clearing markers is best-effort
    }

  // ── checkpoint-output sweep (spec 8) ────────────────────────────────

  /**
   * The per-event sweep: for each `checkpoint-output-*` file left by
   * `checkpoint.sh report`, hash the content, write the presentation
   * marker named by the file's right-to-left triple, and consume the
   * output file. Skipped entirely when the state directory is absent.
   * Returns the `(change, spec, session)` triples for which a
   * presentation marker was written — the caller traces each (the
   * predecessor's `presentation recorded for …` line).
   */
  def sweepCheckpointOutputs(
    dir: GateStateDir,
    sha256Of: Path => Option[String]
  ): List[(String, String, String)] =
    matchingFiles(dir, "checkpoint-output-").flatMap { (name: String) =>
      val file: Path = dir.path.resolve(name)
      // The predecessor's glob `checkpoint-output-*-*-*` requires at
      // least two hyphens after the prefix; a name with fewer never
      // enters the loop at all (not even consumed).
      val recorded: List[(String, String, String)] =
        if name.stripPrefix("checkpoint-output-").count(_ == '-') >= 2 then
          GateDecisions.markerTriple("checkpoint-output-", name) match
            case Some((change: String, spec: String, sessionRaw: String)) =>
              sha256Of(file).filter(_.nonEmpty) match
                case Some(hash: String) =>
                  // predecessor parity: the session segment is copied into
                  // the marker name verbatim — it is ALREADY the encoded
                  // identity (checkpoint.sh encodes it the same way);
                  // re-encoding would double-encode.
                  writeStateFile(dir, s"presentation-$change-$spec-$sessionRaw", hash)
                  List((change, spec, sessionRaw))
                case None => List.empty[(String, String, String)]
            case None =>
              List.empty[
                (String, String, String)
              ] // danger-scan:allow predecessor-parity — unparseable names are consumed without a marker
        else List.empty[(String, String, String)]
      if name.stripPrefix("checkpoint-output-").count(_ == '-') >= 2 then
        try Files.deleteIfExists(file)
        catch case NonFatal(_) => () // danger-scan:allow fail-open — consuming the output file is best-effort
      recorded
    }

  // ── spec discovery (spec 8) ─────────────────────────────────────────

  /**
   * The spec names under `<changeDir>/specs/`, sorted — the
   * predecessor's directory-listing fallback when
   * `implementation-order.md` names no specs.
   */
  def specDirs(changeDir: Path): List[String] =
    try
      val specsDir: Path = changeDir.resolve("specs")
      if !Files.isDirectory(specsDir) then List.empty[String]
      else
        Using.resource(Files.list(specsDir)) { (stream: java.util.stream.Stream[Path]) =>
          stream
            .iterator()
            .asScala
            .toList
            .filter((p: Path) => Files.isDirectory(p))
            .map((p: Path) => p.getFileName.toString)
            .sorted
        }
    catch case NonFatal(_) => List.empty[String] // danger-scan:allow fail-open — undiscoverable specs mean no ordering

  /**
   * Write a state file by literal name, creating the state directory if
   * needed. A write failure is swallowed.
   */
  private def writeStateFile(dir: GateStateDir, name: String, content: String): Unit =
    try
      Files.createDirectories(dir.path)
      Files.writeString(dir.path.resolve(name), content, StandardCharsets.UTF_8)
    catch case NonFatal(_) => () // danger-scan:allow fail-open — state writes are best-effort

  /**
   * Names in the state directory starting with `prefix`, sorted.
   * `List.empty` when the directory is absent or unreadable.
   */
  private def matchingFiles(dir: GateStateDir, prefix: String): List[String] =
    try
      if !Files.isDirectory(dir.path) then List.empty[String]
      else
        Using.resource(Files.list(dir.path)) { (stream: java.util.stream.Stream[Path]) =>
          stream
            .iterator()
            .asScala
            .toList
            .filter((p: Path) => Files.isRegularFile(p))
            .map((p: Path) => p.getFileName.toString)
            .filter((n: String) => n.startsWith(prefix))
            .sorted
        }
    catch
      case NonFatal(_) => List.empty[String] // danger-scan:allow fail-open — an unreadable state dir is "no markers"

end GateStateDirReader

/**
 * The three refusal-marker kinds — the per-turn bounds for the
 * `tool-call`, grant, and `completion` blocking paths.
 *
 * spec: gate-event-completeness — Requirement: At most one refusal is issued per turn
 */
enum RefusalKind:
  case ToolCall, Grant, Completion

object RefusalKind:

  /** The marker filename prefix: `<prefix><encoded-session>`. */
  def markerPrefix(kind: RefusalKind): String = kind match
    case RefusalKind.ToolCall   => "tool-call-refused-"
    case RefusalKind.Grant      => "grant-refused-"
    case RefusalKind.Completion => "completion-refused-"

end RefusalKind
