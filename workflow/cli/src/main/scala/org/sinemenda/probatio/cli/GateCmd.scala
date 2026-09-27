package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

/** The `gate` subcommand — hook event dispatch. */
object GateCmd:
  /**
   * The gate's event domain IS the core `GateEvent` enum — one source of
   * truth, so the sixth case (`PostBash`) is exhaustiveness-escalated
   * through every match here. `Event.X` resolves to `GateEvent.X`.
   */
  type Event = GateEvent
  val Event: GateEvent.type = GateEvent

  /**
   * Wire the gate subcommand to the 6-event tier logic.
   *
   * Dispatches on `--event` to one of 6 events, assembles the GatePayload
   * via BannerEngine, and implements the blocking logic per event tier.
   * The escape hatch (`PROBATIO_HOOKS=off`, legacy alias
   * `VERIFIED_SCALA3_HOOKS`) bypasses both predecessor check
   * and grant waiver.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   * spec: cli-wiring — Scenario: session-start emits the banner and exits 0
   * spec: cli-wiring — Scenario: completion blocks when chain-state is unresolved
   * spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock
   */
  /**
   * Flags that consume a following value — the predecessor's set plus the
   * port's own (`--change`, `--spec`, `--baseline`, `--ledger-file`,
   * `--change-dir`). Unknown tokens are skipped; `--check-installed` is a
   * boolean flag.
   */
  private val valueFlags: Set[String] = Set(
    "--event",
    "--format",
    "--repo",
    "--session",
    "--file",
    "--turn-text",
    "--stop-hook-active",
    "--tool",
    "--command",
    "--exit",
    "--change",
    "--spec",
    "--baseline",
    "--ledger-file",
    "--change-dir"
  )

  /** Leniently-parsed gate arguments (predecessor-compatible). */
  final private case class GateArgs(
    flags: Map[String, String],
    checkInstalled: Boolean
  )

  /**
   * Parse args the way the predecessor does: a `case` loop where each
   * known flag consumes a value, `--check-installed` is boolean, and any
   * unrecognized token is skipped (`*) shift`). A trailing value-flag
   * with no value binds the empty string. The predecessor's loop is a
   * plain `VAR="$2"` assignment — a repeated flag overwrites, so the
   * LAST occurrence wins (parsing tail-first, an earlier binding must
   * not overwrite the tail's).
   */
  private def parseGateArgs(args: List[String]): GateArgs = args match
    case Nil => GateArgs(Map.empty, checkInstalled = false)
    case "--check-installed" :: rest =>
      val tail: GateArgs = parseGateArgs(rest)
      tail.copy(checkInstalled = true)
    case flag :: rest if valueFlags.contains(flag) =>
      rest match
        case value :: after =>
          val tail: GateArgs = parseGateArgs(after)
          if tail.flags.contains(flag) then tail
          else tail.copy(flags = tail.flags + (flag -> value))
        case Nil =>
          val tail: GateArgs = parseGateArgs(Nil)
          tail.copy(flags = tail.flags + (flag -> ""))
    case _ :: rest => // danger-scan:allow lenient-parse — unknown tokens skipped, never mapped (predecessor `*) shift`)
      parseGateArgs(rest)

  /**
   * Wire the gate subcommand to the 6-event tier logic.
   *
   * Dispatches on `--event` to one of 6 events, assembles the GatePayload
   * via BannerEngine, and implements the blocking logic per event tier.
   * The escape hatch (`PROBATIO_HOOKS=off`) bypasses both predecessor check
   * and grant waiver.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   * spec: cli-wiring — Scenario: session-start emits the banner and exits 0
   * spec: cli-wiring — Scenario: completion blocks when chain-state is unresolved
   * spec: cli-wiring — Scenario: The escape hatch bypasses the tool-call lock
   * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
   */
  def run(args: Array[String]): Outcome[Int] =
    // The hook boundary must read the process env (CLAUDE_CODE_SESSION_ID,
    // PROBATIO_HOOKS); Adk4sConfig is not on this classpath (R-ARCH1).
    // The input channel is read AT MOST ONCE — here, at the top level,
    // and only when the event consumes a payload or --repo is absent
    // (the payload's .cwd is the repo fallback). The predecessor gates
    // the read on `[ ! -t 0 ]`; the ported gate adds the buffered-bytes
    // check — a console() null under a silent open pipe (bats, sbt,
    // CI) would otherwise block on readAllBytes forever.
    run(
      args,
      sys.env, // scalafix:ok DisableSyntax.NoSysEnv
      () => HarnessPayloadReader.readChannel(inputPending = stdinHasInput())
    )

  /**
   * Whether stdin carries buffered input — the non-blocking stand-in
   * for the predecessor's `-t 0` check. A real harness pipes the
   * payload before the gate starts, so the bytes are already in the
   * buffer; an open silent pipe (bats, sbt, an IDE) reports nothing
   * and the gate must not block waiting for an EOF that never comes.
   */
  private def stdinHasInput(): Boolean =
    try System.console() == null && System.in.available() > 0
    catch case NonFatal(_) => false // danger-scan:allow fail-open — an unreadable channel probe means "no payload"

  /**
   * The gate run with an explicit environment — the test seam. `run(args)`
   * reads the process environment once and delegates; the env is threaded
   * through every downstream decision (repo resolution, hook control,
   * session, escape hatch) so tests exercise the identical logic.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    run(args, env, () => None)

  /**
   * The gate run with explicit environment and input channel. The
   * channel is invoked at most once — the read-once discipline the
   * predecessor documents at length (a second read returns EOF).
   */
  private[cli] def run(
    args: Array[String],
    env: Map[String, String],
    channel: () => Option[String]
  ): Outcome[Int] =
    val parsed: GateArgs = parseGateArgs(args.toList)
    if parsed.checkInstalled then
      // The predecessor reads stdin when --repo is absent even for the
      // probe — the payload's `.cwd` is the repo fallback. (Ring 8:
      // the ported probe skipped the channel, so .cwd never applied.)
      val payloadCwd: Option[String] =
        if parsed.flags.get("--repo").forall(_.isEmpty)
        then channel().flatMap(HarnessPayloadReader.parse).map(_.cwd)
        else None
      runCheckInstalled(parsed, env, payloadCwd)
    else
      parsed.flags.get("--event") match
        case None =>
          SubcommandWiring.emitStderr("gate: --event is required\n")
          Outcome.Finding("--event is required")
        case Some(eventStr) =>
          val dispatch: EventDispatch = parseEvent(eventStr)
          val consumes: Boolean = dispatch match
            case EventDispatch.Tier(event)  => HarnessPayloadReader.consumesPayload(event)
            case EventDispatch.Injection(_) => false
          val payload: Option[HarnessPayload] =
            if consumes || parsed.flags.get("--repo").forall(_.isEmpty)
            then channel().flatMap(HarnessPayloadReader.parse)
            else None
          runEvent(dispatch, parsed, env, payload)

  /**
   * `--check-installed`: a pure read of the heartbeat — no state is ever
   * created, and it works in any repository (the relevance guard does not
   * apply). Reports `{installed, last_run, event}`.
   *
   * spec: gate-event-completeness — Requirement: The installation probe reports whether the gate has run
   */
  private def runCheckInstalled(
    parsed: GateArgs,
    env: Map[String, String],
    payloadCwd: Option[String]
  ): Outcome[Int] =
    val repo: Path = resolveRepo(parsed.flags.get("--repo"), env, payloadCwd)
    val record: Option[HeartbeatRecord] =
      GateStateDirReader.resolve(repo).flatMap(GateStateDirReader.readHeartbeat)
    val json: String = record match
      case Some(hb) =>
        ujson.write(
          ujson.Obj(
            "installed" -> ujson.Bool(true),
            "last_run"  -> ujson.Str(hb.ts),
            "event"     -> ujson.Str(hb.event)
          )
        )
      case None =>
        ujson.write(
          ujson.Obj(
            "installed" -> ujson.Bool(false),
            "last_run"  -> ujson.Null,
            "event"     -> ujson.Null
          )
        )
    SubcommandWiring.emitStdout(json + "\n")
    Outcome.Ran(0)

  /**
   * Resolve the repository root from the strongest available signal,
   * the predecessor's order: explicit `--repo`, then the payload's
   * `.cwd`, then `CLAUDE_PROJECT_DIR`, then
   * `git rev-parse --show-toplevel` from the process cwd, then the cwd
   * itself.
   */
  private def resolveRepo(
    explicit: Option[String],
    env: Map[String, String],
    payloadCwd: Option[String]
  ): Path =
    val cwd: Path =
      Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
    explicit
      .filter(_.nonEmpty)
      .map(Path.of(_))
      .orElse(payloadCwd.filter(_.nonEmpty).map(Path.of(_)))
      .orElse(env.get("CLAUDE_PROJECT_DIR").filter(_.nonEmpty).map(Path.of(_)))
      .orElse(gitToplevel(cwd))
      .getOrElse(cwd)

  /** `git rev-parse --show-toplevel` under `cwd`; None on any failure. */
  private def gitToplevel(cwd: Path): Option[Path] =
    try
      val pb: ProcessBuilder =
        new ProcessBuilder("git", "rev-parse", "--show-toplevel")
      pb.directory(cwd.toFile)
      // predecessor parity: `git ... 2>/dev/null` — stderr is discarded.
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      if p.waitFor() == 0 && out.trim.nonEmpty then Some(Path.of(out.trim))
      else None
    catch
      case NonFatal(_) => None // danger-scan:allow fail-open — a failed git probe means "repo = cwd", never an error

  /**
   * The hook-control env var, honouring the one-major-version deprecated
   * alias `VERIFIED_SCALA3_HOOKS` (renamed to `PROBATIO_HOOKS` at schema
   * v14). The deprecation window itself is a core decision —
   * `SchemaPolicy.resolveHookEnv`; an unknown schema version honours the
   * alias (fail safe, matching the predecessor). Deprecation warnings
   * surface on stderr, as the predecessor does.
   */
  private def hooksControlValue(repo: Path, env: Map[String, String]): String =
    val resolution: EnvResolution = CliContext.hooksControl(
      env,
      repoSchemaVersion(repo).getOrElse(SchemaPolicy.renameVersion)
    )
    resolution.warnings.foreach { (w: DeprecationWarning) =>
      SubcommandWiring.emitStderr(
        s"probatio: ${w.oldName} is deprecated — renamed to " +
          s"${w.newName} at schema v${SchemaPolicy.renameVersion}. The old name is read as an " +
          "alias for one major version. Update your shell config to " +
          s"use ${w.newName}.\n"
      )
    }
    resolution.resolved match
      case ResolvedValue.Value(v) => v
      case ResolvedValue.Default =>
        "on" // danger-scan:allow control-default — no hook-control env var means on (predecessor default)

  /** The repo's schema version for the env-alias expiry window. */
  private def repoSchemaVersion(repo: Path): Option[Int] =
    try
      val p: Path =
        repo.resolve("openspec/schemas/verified-scala3/schema.yaml")
      if !Files.isRegularFile(p) then None
      else
        Files
          .readString(p)
          .linesIterator
          .find(_.startsWith("version:"))
          .flatMap(l => l.substring(l.indexOf(':') + 1).trim.toIntOption)
    catch
      case NonFatal(_) => None // danger-scan:allow fail-open — an unreadable schema.yaml keeps the alias window open

  /** The parent process id for the `ppid-<n>` session fallback. */
  private def parentPid: Long =
    try
      ProcessHandle
        .current()
        .parent()
        .map[Long]((h: ProcessHandle) => h.pid())
        .orElse(0L)
    catch
      case NonFatal(_) => 0L // danger-scan:allow fail-open — an unknown parent pid degrades to the ppid-0 session key

  /**
   * Parse the event name from the --event flag value. Total into
   * `EventDispatch` — the parse can no longer fail: a recognised name
   * yields `Tier`, every other name yields `Injection` carrying the
   * supplied name (spec 4, the predecessor's permissive fallback made
   * visible).
   *
   * spec: gate-event-compatibility — Requirement: An unrecognised event name routes to the injection tier
   */
  private[cli] def parseEvent(s: String): EventDispatch =
    EventDispatch.classify(s)

  /**
   * The gate's per-invocation context, resolved once in the prologue:
   * repository, session, and state directory (absent when unresolvable
   * — the tiers fail open from `None`). `dispatch` is the classified
   * `--event` decision — the heartbeat and trace record the SUPPLIED
   * name for an `Injection` (predecessor `$EVENT` verbatim), while the
   * hook-json envelope follows the predecessor's `*)` mapping.
   */
  final private case class GateContext(
    repo: Path,
    session: SessionId,
    stateDir: Option[GateStateDir],
    format: String,
    dispatch: EventDispatch
  )

  /**
   * Run the gate for a classified `--event` dispatch. The shared
   * prologue is the predecessor's own order, applied to EVERY supplied
   * name — recognised or not:
   *   relevance guard → hook control → state directory → heartbeat →
   *   checkpoint-output sweep → per-dispatch tier.
   *
   * The event tiers:
   * - SessionStart, PromptSubmit: Tier B — informational banner; the
   *   prompt-submit path additionally clears refusal markers and writes
   *   grant tokens.
   * - PostEdit: Tier A′ — informational findings, never blocks.
   * - PostBash: Tier A′ — ambient evidence writer, never blocks.
   * - ToolCall: Tier A — the pre-execution blocking tier.
   * - Completion: Tier A — the completion blocking tier.
   * - Injection: the predecessor's fallthrough — the context-injection
   *   tier (banner path), reached by every name outside the recognised
   *   set. NEVER an error status.
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   * spec: gate-event-completeness — Requirement: The gate handles every event its installed adapters emit
   * spec: gate-event-compatibility — Requirement: An unrecognised event name routes to the injection tier
   */
  private def runEvent(
    dispatch: EventDispatch,
    parsed: GateArgs,
    env: Map[String, String],
    payload: Option[HarnessPayload]
  ): Outcome[Int] =
    val repo: Path = resolveRepo(
      parsed.flags.get("--repo"),
      env,
      payload.map(_.cwd)
    )
    val format: String = parsed.flags.getOrElse("--format", "hook-json")
    // Relevance guard: a repository that does not use this workflow gets
    // NOTHING — no output, and no state directory created.
    if !Files.isDirectory(repo.resolve("openspec")) then
      trace(env, dispatchToken(dispatch), format, repo, "skip: no openspec/ here")
      Outcome.Ran(0)
    else if hooksControlValue(repo, env) == "off" then
      trace(env, dispatchToken(dispatch), format, repo, "skip: hook control env var=off")
      Outcome.Ran(0)
    else
      val session: SessionId = SessionId.resolve(
        parsed.flags.get("--session"),
        env.get("CLAUDE_CODE_SESSION_ID"),
        env.get("VERIFIED_SCALA3_SESSION_ID"),
        parentPid
      )
      // State directory — created only after the relevance guard (D8),
      // fail-open when unresolvable or unwritable.
      val stateDir: Option[GateStateDir] =
        GateStateDirReader.resolve(repo).flatMap { (d: GateStateDir) =>
          try
            Files.createDirectories(d.path)
            Some(d)
          catch case NonFatal(_) => None // danger-scan:allow fail-open — unwritable state dir means no persistence
        }
      val ctx: GateContext = GateContext(repo, session, stateDir, format, dispatch)
      stateDir.foreach { (d: GateStateDir) =>
        GateStateDirReader.writeHeartbeat(
          d,
          HeartbeatRecord(SubcommandWiring.stampTimestamp, dispatchToken(dispatch), format)
        )
        GateStateDirReader
          .sweepCheckpointOutputs(d, SubcommandWiring.sha256OfFile)
          .foreach { (chg: String, spec: String, sess: String) =>
            trace(ctx, env, s"presentation recorded for $chg/$spec (session $sess)")
          }
      }
      dispatch match
        case EventDispatch.Tier(event) =>
          event match
            case Event.SessionStart =>
              runBanner(ctx, env)
            case Event.PromptSubmit =>
              // New turn: clear this session's refusal markers and write
              // grant tokens for presented-but-ungranted specs — the only
              // event that writes grants (predecessor parity).
              ctx.stateDir.foreach { (d: GateStateDir) =>
                GateStateDirReader.clearRefusals(d, ctx.session)
                writeSessionGrants(d, ctx, env)
              }
              runBanner(ctx, env)
            case Event.PostEdit =>
              // Tier A′ — informational findings only, NEVER blocks — same
              // boundary guard as post-bash: a defect inside the tier
              // degrades to no findings, never a blocked turn.
              try runPostEdit(ctx, parsed, env, payload)
              catch
                case NonFatal(_) => // danger-scan:allow informational tier — a crash must not block
                  Outcome.Ran(0)
            case Event.ToolCall =>
              // Tier A — the pre-execution blocking tier.
              runToolCall(ctx, parsed, env, payload)
            case Event.PostBash =>
              // Tier A′ — the ambient evidence writer, NEVER blocks. The
              // boundary guard is the predecessor's `_post_bash_exit0`
              // wrapper: post-bash exits 0 under ANY payload, so a defect
              // inside the tier degrades to a skipped observation, never a
              // blocked turn. (Ring 8: a classifier throw could escape.)
              try runPostBash(ctx, parsed, env, payload)
              catch
                case NonFatal(_) => // danger-scan:allow observation-only — a crash skips a row, never blocks
                  Outcome.Ran(0)
            case Event.Completion =>
              // Tier A — the completion blocking tier.
              runCompletion(ctx, parsed, env, payload)
        case EventDispatch.Injection(supplied) =>
          runInjection(ctx, env, supplied)

  /**
   * The context-injection tier — the predecessor's fallthrough: every
   * supplied name outside the recognised set lands here and terminates
   * with the clean status, never an error status. The fallback is NOT
   * silent: the diagnostic output names the supplied value (the
   * predecessor's fallback was silent — this spec adds the line), and
   * the heartbeat/trace record it verbatim (predecessor `$EVENT`).
   * The tier itself is the banner path — the same path `session-start`
   * runs.
   *
   * spec: gate-event-compatibility — Requirement: An unrecognised event name routes to the injection tier
   * spec: gate-event-compatibility — Requirement: The supplied name survives into the diagnostic output
   */
  private def runInjection(
    ctx: GateContext,
    env: Map[String, String],
    supplied: String
  ): Outcome[Int] =
    SubcommandWiring.emitStderr(
      s"gate: unrecognised event '$supplied' — running the context-injection tier\n"
    )
    trace(ctx, env, s"injection: unrecognised event '$supplied'")
    runBanner(ctx, env)

  /** The predecessor's `--event` token for a gate event (heartbeat field). */
  private def eventToken(event: Event): String = event match
    case Event.SessionStart => "session-start"
    case Event.PromptSubmit => "prompt-submit"
    case Event.ToolCall     => "tool-call"
    case Event.PostEdit     => "post-edit"
    case Event.PostBash     => "post-bash"
    case Event.Completion   => "completion"

  /**
   * The name the heartbeat and trace record — the predecessor's
   * `$EVENT` verbatim: the canonical token for a tier dispatch, the
   * RAW supplied name for an injection.
   */
  private def dispatchToken(dispatch: EventDispatch): String = dispatch match
    case EventDispatch.Tier(event)         => eventToken(event)
    case EventDispatch.Injection(supplied) => supplied

  /**
   * The predecessor's `trace()`: opt-in per-invocation diagnostics —
   * `PROBATIO_HOOKS_TRACE` (or the deprecated `VERIFIED_SCALA3_HOOKS_TRACE`
   * alias) names a file that gets one appended line per call, including
   * the silent ones ("fired and stayed silent" vs "never fired" is how an
   * install is verified). Write failures are swallowed (`|| true`). This
   * is the diagnostic channel the spec's fail-open requirement invokes —
   * an allow caused by unreadable state says why HERE, keeping normal
   * hook output silent.
   *
   * spec: gate-event-completeness — Scenario: an unreadable state directory allows rather than refusing
   */
  private def trace(
    env: Map[String, String],
    event: String,
    format: String,
    repo: Path,
    msg: String
  ): Unit =
    env
      .get("PROBATIO_HOOKS_TRACE")
      .filter(_.nonEmpty)
      .orElse(env.get("VERIFIED_SCALA3_HOOKS_TRACE").filter(_.nonEmpty))
      .foreach { (target: String) =>
        try
          val ts: String =
            Instant.now().truncatedTo(ChronoUnit.SECONDS).toString
          val line: String =
            f"$ts  event=$event%-14s format=$format%-9s repo=${repo.toString}%-42s $msg\n"
          Files.writeString(
            Path.of(target),
            line,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
          )
        catch case NonFatal(_) => () // danger-scan:allow predecessor `|| true` — trace must never affect the decision
      }

  /** Trace with the resolved context's dispatch/format/repo. */
  private def trace(ctx: GateContext, env: Map[String, String], msg: String): Unit =
    trace(env, dispatchToken(ctx.dispatch), ctx.format, ctx.repo, msg)

  // ── shared tier helpers ────────────────────────────────────────────

  /**
   * `jq -r` rendering: strings verbatim, numbers/booleans rendered,
   * objects/arrays as compact JSON, `null` as the literal "null" (the
   * `// empty` tail removes it upstream, so callers never see it).
   */
  private def jqRender(v: ujson.Value): String = v match
    case ujson.Str(s) => s
    case ujson.Num(n) =>
      if n == n.toInt.toDouble then n.toInt.toString else n.toString
    case ujson.Bool(b) => if b then "true" else "false"
    case ujson.Null    => "null"
    case other => // danger-scan:allow pass-through — arrays/objects render as JSON, matching jq raw output
      ujson.write(other)

  /** `jq -r '<expr> // empty'` — `null` and `false` read as empty. */
  private def jqField(v: ujson.Value): String = v match
    case ujson.Null | ujson.Bool(false) => ""
    case other => // danger-scan:allow pass-through — every non-null/false value renders (jq semantics)
      jqRender(other)

  /** `.a // .b // empty` — the predecessor's two-operand field read. */
  private def jqAlternative(first: ujson.Value, second: ujson.Value): String =
    first match
      case ujson.Null | ujson.Bool(false) => jqField(second)
      case other => // danger-scan:allow pass-through — jq alternative: non-null/false takes the first arm
        jqRender(other)

  /** `.tool_input.<key>` — `Null` when tool_input is not an object. */
  private def toolInputField(p: HarnessPayload, key: String): ujson.Value =
    p.toolInput match
      case obj: ujson.Obj => obj.value.getOrElse(key, ujson.Null)
      case _              => ujson.Null // danger-scan:allow jq semantics — a field read on a non-object yields null

  /** The predecessor's `.tool_input.file_path // .tool_input.path`. */
  private def payloadFilePath(payload: Option[HarnessPayload]): String =
    payload match
      case None => ""
      case Some(p: HarnessPayload) =>
        jqAlternative(toolInputField(p, "file_path"), toolInputField(p, "path"))

  /**
   * The predecessor's relative-path normalisation: a non-absolute
   * `FILE_PATH` becomes `$REPO/<path>` so the leading-star globs can
   * match. Empty and absolute paths pass through unchanged.
   */
  private def normalizeFilePath(repo: Path, file: String): String =
    if file.isEmpty || file.startsWith("/") then file
    else repo.toString + "/" + file

  /**
   * The non-archive change directories under `<repo>/openspec/changes`,
   * sorted — the predecessor's `changes/` directory glob order.
   */
  private def activeChangeDirs(repo: Path): List[Path] =
    try
      val changesDir: Path = repo.resolve("openspec/changes")
      if !Files.isDirectory(changesDir) then List.empty[Path]
      else
        Using.resource(Files.list(changesDir)) { (stream: java.util.stream.Stream[Path]) =>
          // Materialise to a List before filtering — java.util.stream has
          // no `filterNot`/`forall`, so mutants on a raw Stream never
          // compile; on List they do and face the oracle.
          stream
            .iterator()
            .asScala
            .toList
            .filter((p: Path) => Files.isDirectory(p))
            .filter((p: Path) => p.getFileName.toString != "archive")
            .sorted
        }
    catch
      case NonFatal(_) => List.empty[Path] // danger-scan:allow fail-open — undiscoverable changes mean no gate state

  /**
   * The first non-archive change name — the predecessor's
   * `active_change`/`grant_change`/`postbash_change` discovery.
   */
  private def firstActiveChange(repo: Path): Option[String] =
    activeChangeDirs(repo).headOption.map((p: Path) => p.getFileName.toString)

  /** The change's `implementation-order.md` text; "" when absent. */
  private def implOrderText(changeDir: Path): String =
    val f: Path = changeDir.resolve("implementation-order.md")
    try
      if Files.isRegularFile(f) then Files.readString(f, StandardCharsets.UTF_8)
      else ""
    catch case NonFatal(_) => "" // danger-scan:allow fail-open — an unreadable order file is "no order", never an error

  /**
   * The ordered spec list: `implementation-order.md`'s `specs/<n>/spec.md`
   * tokens in order, else the sorted `specs/` directory listing — the
   * predecessor's two-source fallback.
   */
  private def orderedSpecs(changeDir: Path): List[String] =
    val parsed: List[String] = GateDecisions.specOrder(implOrderText(changeDir))
    if parsed.nonEmpty then parsed else GateStateDirReader.specDirs(changeDir)

  /**
   * Ledger rows parsed per-line tolerantly — a malformed or non-JSON
   * line contributes nothing (the predecessor's per-line `jq` failure).
   */
  private def readLedgerRows(file: Path): List[ujson.Value] =
    try
      if !Files.isRegularFile(file) then List.empty[ujson.Value]
      else
        Files
          .readAllLines(file, StandardCharsets.UTF_8)
          .asScala
          .toList
          .flatMap { (line: String) =>
            val t: String = line.trim
            if t.isEmpty then None
            else
              try Some(ujson.read(t))
              catch case NonFatal(_) => None // danger-scan:allow per-line-skip — a malformed row reads as no fields
          }
    catch case NonFatal(_) => List.empty[ujson.Value] // danger-scan:allow fail-open — an unreadable ledger is "no rows"

  /**
   * A scanner tool's path — the `*_OVERRIDE` env seam first, else the
   * schema's scanner directory inside the repo (`<repo>/openspec/
   * schemas/verified-scala3/scanner/<name>` — the predecessor's
   * `$SELF_DIR/../scanner`).
   */
  private[cli] def scannerTool(
    repo: Path,
    env: Map[String, String],
    overrideVar: String,
    name: String
  ): Path =
    env
      .get(overrideVar)
      .filter(_.nonEmpty)
      .map(Path.of(_))
      .getOrElse(repo.resolve("openspec/schemas/verified-scala3/scanner").resolve(name))

  /**
   * `bash <script> <args>` under `repo`, capturing stdout; stderr is
   * merged (the predecessor's `2>&1`) or discarded (`2>/dev/null`).
   * `None` when the process cannot be launched — a failed check is an
   * empty report, never a gate failure.
   */
  private[cli] def runScanner(
    repo: Path,
    script: Path,
    args: List[String],
    mergeStderr: Boolean
  ): Option[(Int, String)] =
    try
      val pb: ProcessBuilder =
        new ProcessBuilder((List("bash", script.toString) ++ args)*)
      pb.directory(repo.toFile)
      if mergeStderr then pb.redirectErrorStream(true)
      else pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      Some((p.waitFor(), out))
    catch case NonFatal(_) => None // danger-scan:allow fail-open — an unlaunchable check reports nothing

  /** The predecessor's `-x || -f` existence check for a scanner tool. */
  private def toolExists(script: Path): Boolean =
    Files.isRegularFile(script) || Files.isExecutable(script)

  /** `{decision:"block",reason:…}` on stdout — the harness-honoured refusal. */
  private def emitBlockJson(reason: String): Unit =
    SubcommandWiring.emitStdout(
      ujson.write(
        ujson.Obj(
          "decision" -> ujson.Str("block"),
          "reason"   -> ujson.Str(reason)
        )
      ) + "\n"
    )

  /**
   * The tool-call tier's refusal emission: `text` prints the reason on
   * stderr and exits 2 (`Undetermined`); every other format emits the
   * `decision:block` envelope on stdout and exits 0 — the predecessor's
   * harness contract.
   */
  private def toolCallRefusal(format: String, reason: String): Outcome[Int] =
    if format == "text" then
      SubcommandWiring.emitStderr(reason + "\n")
      Outcome.Undetermined(reason)
    else
      emitBlockJson(reason)
      Outcome.Ran(0)

  /**
   * The completion tier's refusal emission: `text` prints the reason on
   * STDOUT (the predecessor's completion refusals do not redirect to
   * stderr) and returns the given outcome (exit 1 for `Finding`, 2 for
   * `Undetermined`); other formats emit the `decision:block` envelope
   * and exit 0.
   */
  private def completionRefusal(
    format: String,
    reason: String,
    outcome: String => Outcome[Int]
  ): Outcome[Int] =
    if format == "text" then
      SubcommandWiring.emitStdout(reason + "\n")
      outcome(reason)
    else
      emitBlockJson(reason)
      Outcome.Ran(0)

  /**
   * The post-edit tier (Tier A′): run the relevant existing check on
   * the just-edited file and emit the findings — spec-lint for a spec
   * file of an active change, danger-scan for a production `.scala`
   * file. Informational only; never blocks.
   *
   * spec: gate-event-completeness — Requirement: The post-edit tier is informational and never blocks
   */
  private def runPostEdit(
    ctx: GateContext,
    parsed: GateArgs,
    env: Map[String, String],
    payload: Option[HarnessPayload]
  ): Outcome[Int] =
    val flagFile: String = parsed.flags.getOrElse("--file", "")
    val rawFile: String =
      if flagFile.nonEmpty then flagFile else payloadFilePath(payload)
    val file: String = normalizeFilePath(ctx.repo, rawFile)
    val findings: List[String] =
      List(
        specEditFinding(ctx.repo, file, env),
        prodEditFinding(ctx.repo, file, env)
      ).flatMap((o: Option[String]) => o.toList)
    // The predecessor's `sed '/^$/d'` — empty lines are stripped from
    // the concatenated report.
    val text: String =
      findings
        .flatMap((f: String) => f.split("\n", -1).toList)
        .filter(_.nonEmpty)
        .mkString("\n")
    trace(ctx, env, s"post-edit: file=$file findings=${text.length} chars")
    if text.isEmpty then Outcome.Ran(0)
    else if ctx.format == "text" then
      SubcommandWiring.emitStdout(text + "\n")
      Outcome.Ran(0)
    else
      // `hookSpecificOutput` envelope — the PostToolUse injection shape.
      SubcommandWiring.emitStdout(
        ujson.write(
          ujson.Obj(
            "hookSpecificOutput" -> ujson.Obj(
              "hookEventName"     -> ujson.Str("PostToolUse"),
              "additionalContext" -> ujson.Str(text)
            )
          )
        ) + "\n"
      )
      Outcome.Ran(0)

  /**
   * The spec-edit finding block: when the edited file is an active
   * change's `specs/<spec>/spec.md`, run spec-lint over the change's
   * artifact dir; an unavailable check reports as a finding line —
   * never as a failure.
   */
  private def specEditFinding(
    repo: Path,
    file: String,
    env: Map[String, String]
  ): Option[String] =
    if !GateDecisions.isSpecEdit(file) then None
    else
      // The predecessor's greedy `sed` extraction — the LAST marker
      // followed by `<name>/specs/` names the change.
      val changeName: String = GateDecisions.specEditChangeName(file)
      val tool: Path         = scannerTool(repo, env, "SPEC_LINT_OVERRIDE", "spec-lint.sh")
      if changeName.nonEmpty && toolExists(tool) then
        val chgDir: Path = repo.resolve("openspec/changes").resolve(changeName)
        val out: String =
          runScanner(repo, tool, List("--artifacts", chgDir.toString), mergeStderr = true)
            .map((r: (Int, String)) => r._2)
            .getOrElse("")
        Some(s"spec-lint ($changeName):\n$out")
      else Some("spec-lint: check could not run (script not found or change name unresolved)")

  /**
   * The production-edit finding block: `.scala` under `/src/main/` runs
   * danger-scan bare (default baseline HEAD — the uncommitted edit is
   * already in its `git diff HEAD`).
   */
  private def prodEditFinding(
    repo: Path,
    file: String,
    env: Map[String, String]
  ): Option[String] =
    if !GateDecisions.isProductionEdit(file) then None
    else
      val tool: Path = scannerTool(repo, env, "DANGER_SCAN_OVERRIDE", "danger-scan.sh")
      if toolExists(tool) then
        val out: String =
          runScanner(repo, tool, List.empty[String], mergeStderr = true)
            .map((r: (Int, String)) => r._2)
            .getOrElse("")
        Some(s"danger-scan:\n$out")
      else Some("danger-scan: check could not run (script not found)")

  /**
   * The pre-execution blocking tier (Tier A): the oracle ordering lock
   * on production edits, the grant lock on Step-0 signatures, and the
   * predecessor gate — wired to the actual gate state. Fails open with
   * a stated reason when the bounding state is unavailable; at most one
   * refusal per turn.
   *
   * spec: gate-event-completeness — Requirement: The blocking tiers consult repository state and fail open when it is unavailable
   */
  private def runToolCall(
    ctx: GateContext,
    parsed: GateArgs,
    env: Map[String, String],
    payload: Option[HarnessPayload]
  ): Outcome[Int] =
    val flagFile: String = parsed.flags.getOrElse("--file", "")
    val flagTool: String = parsed.flags.getOrElse("--tool", "")
    // The predecessor reads the payload fields only inside the
    // `--file`-absent branch — a supplied --file also suppresses the
    // payload's tool_name. An empty flag or payload value is absence,
    // not a supplied empty name (spec 6, oracle-fixture-repair).
    val rawFile: String =
      if flagFile.nonEmpty then flagFile else payloadFilePath(payload)
    val toolName: ToolNameSource =
      if flagTool.nonEmpty then ToolNameSource.Supplied(flagTool)
      else if flagFile.isEmpty then
        payload
          .map(_.toolName)
          .filter(_.nonEmpty)
          .fold[ToolNameSource](ToolNameSource.Absent)((n: String) => ToolNameSource.Supplied(n))
      else ToolNameSource.Absent
    val file: String    = normalizeFilePath(ctx.repo, rawFile)
    val isProd: Boolean = GateDecisions.isProductionEdit(file)
    // VERIFIED_SCALA3_ALLOW_PATHS — a colon-separated prefix allowlist,
    // production paths only.
    val allowPrefix: Option[String] =
      if !isProd then None
      else
        env
          .get("VERIFIED_SCALA3_ALLOW_PATHS")
          .filter(_.nonEmpty)
          .flatMap((v: String) => v.split(":").find((p: String) => file.startsWith(p)))
    allowPrefix match
      case Some(prefix: String) =>
        trace(ctx, env, s"tool-call: file under allow-listed path $prefix, allow — $file")
        Outcome.Ran(0)
      case None =>
        if GateDecisions.preExecution(toolName) then
          toolName match
            case ToolNameSource.Absent =>
              // spec: oracle-fixture-repair — Requirement: An absent tool name keeps parity and is stated
              if isProd then trace(ctx, env, s"tool-call: no tool name supplied on production path, allow — $file")
              else trace(ctx, env, s"tool-call: no tool name supplied, allow — $file")
            case ToolNameSource.Supplied(name: String) =>
              if isProd then trace(ctx, env, s"tool-call: read-only tool '$name' on production path, allow — $file")
              else trace(ctx, env, s"tool-call: read-only tool '$name', allow — $file")
          Outcome.Ran(0)
        else
          ctx.stateDir match
            case None =>
              // fail open — no bound, no block
              if isProd then
                trace(ctx, env, "tool-call: allow — bounded-refusal state unavailable (STATE_DIR unset), failing open")
              else trace(ctx, env, s"tool-call: non-production path, allow — $file (STATE_DIR unavailable)")
              Outcome.Ran(0)
            case Some(dir: GateStateDir) =>
              if isProd then oracleLock(ctx, dir, file, env)
              else nonProdLock(ctx, dir, file, env)

  /**
   * The non-production side of the tool-call tier: only the grant lock
   * on Step-0 signatures (`implementation-progress.md`, a spec dir) —
   * every other non-production file allows unconditionally. The
   * predecessor's Expected-Files ownership mapping lives in the
   * production branch only (`oracleLock`).
   */
  private def nonProdLock(
    ctx: GateContext,
    dir: GateStateDir,
    file: String,
    env: Map[String, String]
  ): Outcome[Int] =
    firstActiveChange(ctx.repo) match
      case None =>
        trace(ctx, env, s"tool-call: non-production path, allow — $file")
        Outcome.Ran(0)
      case Some(chg: String) =>
        val chgDir: Path = ctx.repo.resolve("openspec/changes").resolve(chg)
        GateDecisions.step0Target(file, chg) match
          case Some(target: GateDecisions.Step0Target) =>
            grantLock(ctx, dir, chg, chgDir, file, env, target)
          case None =>
            // A file that is neither production-shaped nor a Step-0
            // signature is always allowed — the predecessor's Expected
            // Files ownership mapping lives in the PRODUCTION branch
            // only; gating declared non-production paths (build.sbt,
            // tools/*.sh) would be blocking the predecessor never had.
            trace(ctx, env, s"tool-call: non-production path, allow — $file")
            Outcome.Ran(0)

  /**
   * The human-grant lock: a Step-0 signature edit requires a grant for
   * the required spec — satisfied by this session's grant, a grant from
   * ANY session, or the waiver (required spec is `verified` AND carries
   * a presentation marker). Bounded to one refusal per turn; fails open
   * when the marker cannot be written.
   *
   * spec: gate-event-completeness — Requirement: The grant lock requires a human grant or the verified-plus-presentation waiver
   */
  private def grantLock(
    ctx: GateContext,
    dir: GateStateDir,
    chg: String,
    chgDir: Path,
    file: String,
    env: Map[String, String],
    target: GateDecisions.Step0Target
  ): Outcome[Int] =
    // Spec-dir targets are exempt when the spec is untracked (no phase
    // file — authoring a new spec's docs is planning, not starting) or
    // already past oracle (the grant lock prevents STARTING, not
    // revising).
    val applies: Boolean = target match
      case GateDecisions.Step0Target.ProgressFile => true
      case GateDecisions.Step0Target.SpecDir(spec: String) =>
        if !Files.isRegularFile(GateStateDirReader.phaseFile(dir, chg, spec)) then
          trace(ctx, env, s"tool-call: target spec $spec has no phase file (new/untracked), allow spec-dir edit")
          false
        else
          val phase: SpecPhase = GateStateDirReader.readPhase(dir, chg, spec)
          if phase != SpecPhase.Oracle then
            trace(
              ctx,
              env,
              s"tool-call: target spec $spec is ${SpecPhase.asToken(phase)} (past oracle), no grant needed"
            )
            false
          else true
    if !applies then
      trace(ctx, env, s"tool-call: non-production path, allow — $file")
      Outcome.Ran(0)
    else
      val specs: List[String] = orderedSpecs(chgDir)
      val needsGrant: String => Boolean = (s: String) =>
        Files.isRegularFile(GateStateDirReader.presentationFile(dir, chg, s, ctx.session)) &&
          !GateStateDirReader.hasGrant(dir, chg, s, ctx.session)
      val required: Option[String] = target match
        // A spec-dir target needs the grant for the spec BEFORE it —
        // or for the first presented-but-ungranted prior, whichever the
        // scan reaches first (the predecessor's loop order).
        case GateDecisions.Step0Target.SpecDir(t: String) if specs.contains(t) =>
          val priors: List[String] = specs.takeWhile((s: String) => s != t)
          priors.find(needsGrant).orElse(priors.lastOption)
        case _ => // danger-scan:allow ImplProgress/absent-target arm — the predecessor falls back to the first ungranted spec
          specs.find(needsGrant)
      required match
        case None =>
          trace(ctx, env, s"tool-call: non-production path, allow — $file")
          Outcome.Ran(0)
        case Some(req: String) =>
          // The predecessor's two-stage grant resolution: this session's
          // grant; else a grant from ANY session (two trace lines); else
          // the verified+presented waiver.
          val sessionGrant: Boolean =
            GateStateDirReader.hasGrant(dir, chg, req, ctx.session)
          val anyGrant: Option[String] =
            if sessionGrant then None
            else GateStateDirReader.findAnySessionGrant(dir, chg, req)
          val waived: Boolean =
            !sessionGrant && anyGrant.isEmpty &&
              GateStateDirReader.readPhase(dir, chg, req) == SpecPhase.Verified &&
              GateStateDirReader.hasAnySessionPresentation(dir, chg, req)
          anyGrant.foreach { (name: String) =>
            trace(ctx, env, s"tool-call: grant for $req found from a prior session: $name")
          }
          if sessionGrant || anyGrant.nonEmpty then
            trace(ctx, env, s"tool-call: grant satisfied for $req")
            Outcome.Ran(0)
          else if waived then
            trace(
              ctx,
              env,
              s"tool-call: required-grant spec $req is verified and checkpointed (approved in a prior session), waive grant"
            )
            Outcome.Ran(0)
          else if GateStateDirReader.hasRefusal(dir, RefusalKind.Grant, ctx.session) then
            trace(ctx, env, "tool-call: already refused grant once this turn, allow")
            Outcome.Ran(0)
          else if !GateStateDirReader.writeRefusal(dir, RefusalKind.Grant, ctx.session) then
            trace(ctx, env, "tool-call: failed to write grant-refusal marker, failing open")
            Outcome.Ran(0) // fail open
          else
            trace(ctx, env, s"tool-call: refuse (missing grant for $req)")
            val reason: String =
              s"$req grant: next-spec Step-0 blocked — no human grant for spec $req. " +
                "A user prompt must arrive after the checkpoint presentation (prompt-submit event) to write the grant."
            toolCallRefusal(ctx.format, reason)

  /**
   * The production-edit oracle lock: the active spec's phase must be
   * past `oracle` (a RED R3 row advanced it) and every spec before it
   * must be verified AND checkpointed. Phase advancement happens here,
   * driven by the ledger's R3 rows at ancestor baselines.
   */
  private def oracleLock(
    ctx: GateContext,
    dir: GateStateDir,
    file: String,
    env: Map[String, String]
  ): Outcome[Int] =
    if GateStateDirReader.hasRefusal(dir, RefusalKind.ToolCall, ctx.session) then
      trace(ctx, env, "tool-call: already refused once this turn, allow")
      Outcome.Ran(0)
    else
      firstActiveChange(ctx.repo) match
        case None =>
          trace(ctx, env, "tool-call: no active change, allow")
          Outcome.Ran(0)
        case Some(chg: String) =>
          val chgDir: Path      = ctx.repo.resolve("openspec/changes").resolve(chg)
          val orderText: String = implOrderText(chgDir)
          val specs: List[String] =
            val parsed: List[String] = GateDecisions.specOrder(orderText)
            if parsed.nonEmpty then parsed else GateStateDirReader.specDirs(chgDir)
          // Active spec: manual override → Expected-Files ownership →
          // first non-verified spec. The predecessor traces which rule
          // resolved the spec.
          val activeSpec: Option[String] =
            env.get("VERIFIED_SCALA3_ACTIVE_SPEC").filter(_.nonEmpty) match
              case Some(s: String) =>
                trace(ctx, env, s"tool-call: active spec overridden via env var: $s")
                Some(s)
              case None =>
                GateDecisions.owningSpec(orderText, ctx.repo.toString, file) match
                  case Some(s: String) =>
                    trace(ctx, env, s"tool-call: file mapped to spec $s via Expected Files table")
                    Some(s)
                  case None =>
                    specs
                      .find((s: String) => GateStateDirReader.readPhase(dir, chg, s) != SpecPhase.Verified)
                      .map { (s: String) =>
                        trace(
                          ctx,
                          env,
                          s"tool-call: file not in Expected Files table, fallback to first non-verified: $s"
                        )
                        s
                      }
          activeSpec match
            case None =>
              trace(ctx, env, s"tool-call: all specs verified for $chg, allow")
              Outcome.Ran(0)
            case Some(spec: String) =>
              val currentPhase: SpecPhase = GateStateDirReader.readPhase(dir, chg, spec)
              val ledgerFile: Path        = chgDir.resolve("evidence-ledger.jsonl")
              // The polarity scan runs only when HEAD resolves and the
              // ledger exists — the predecessor's precondition.
              val rows: List[ujson.Value] =
                SpecLintCmd.gitOut(ctx.repo, List("rev-parse", "HEAD")) match
                  case Some(_) => readLedgerRows(ledgerFile)
                  case None    => List.empty[ujson.Value]
              val ancestorOfHead: String => Boolean = (b: String) =>
                SubcommandWiring.gitExit(ctx.repo, List("merge-base", "--is-ancestor", b, "HEAD")) == 0
              val ancestorPair: (String, String) => Boolean = (a: String, b: String) =>
                SubcommandWiring.gitExit(ctx.repo, List("merge-base", "--is-ancestor", a, b)) == 0
              val redBaseline: Option[String] =
                GateDecisions.firstRing3Baseline(rows, chg, spec, GateDecisions.Polarity.Red, ancestorOfHead)
              val greenExists: Boolean =
                GateDecisions.hasGreenAfterRed(rows, chg, spec, redBaseline, ancestorOfHead, ancestorPair)
              val newPhase: SpecPhase =
                GateDecisions.advancePhase(currentPhase, redBaseline.isDefined, greenExists)
              if newPhase != currentPhase then
                GateStateDirReader.writePhase(dir, chg, spec, newPhase)
                trace(
                  ctx,
                  env,
                  s"tool-call: phase ${SpecPhase.asToken(currentPhase)} → ${SpecPhase.asToken(newPhase)} ($chg/$spec)"
                )
              newPhase match
                case SpecPhase.Oracle =>
                  if !GateStateDirReader.writeRefusal(dir, RefusalKind.ToolCall, ctx.session) then
                    trace(ctx, env, "tool-call: failed to write refusal marker, failing open to avoid deadlock")
                    Outcome.Ran(0) // fail open
                  else
                    val reason: String =
                      s"$spec oracle: production edit blocked — the oracle phase has not advanced. " +
                        "Run the test oracle first (ledger.sh run -- … -- sbt <module>/test) to record a RED run and advance to implementation."
                    trace(ctx, env, s"tool-call: refuse (oracle phase, $chg/$spec)")
                    toolCallRefusal(ctx.format, reason)
                case _ => // danger-scan:allow phases past the oracle lock reach the predecessor check (the predecessor's fallthrough)
                  if env.get("VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK").exists(_.nonEmpty) then
                    trace(ctx, env, "tool-call: predecessor check skipped (VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK set)")
                    trace(ctx, env, s"tool-call: allow (phase ${SpecPhase.asToken(newPhase)}, $chg/$spec)")
                    Outcome.Ran(0)
                  else
                    // Depth-first discipline: every spec before the
                    // active one must be verified AND checkpointed.
                    val priors: List[String] = specs.takeWhile((s: String) => s != spec)
                    val firstBad: Option[(String, SpecPhase)] =
                      priors
                        .find { (s: String) =>
                          GateStateDirReader.readPhase(dir, chg, s) != SpecPhase.Verified ||
                          !GateStateDirReader.hasAnySessionPresentation(dir, chg, s)
                        }
                        .map((s: String) => (s, GateStateDirReader.readPhase(dir, chg, s)))
                    firstBad match
                      case None =>
                        trace(ctx, env, s"tool-call: allow (phase ${SpecPhase.asToken(newPhase)}, $chg/$spec)")
                        Outcome.Ran(0)
                      case Some((s: String, phase: SpecPhase)) =>
                        if !GateStateDirReader.writeRefusal(dir, RefusalKind.ToolCall, ctx.session) then
                          trace(ctx, env, "tool-call: failed to write refusal marker, failing open to avoid deadlock")
                          Outcome.Ran(0) // fail open
                        else
                          val phaseDesc: String =
                            if phase == SpecPhase.Verified then "verified (not checkpointed)"
                            else SpecPhase.asToken(phase)
                          val reason: String =
                            s"$spec blocked — predecessor spec $s is $phaseDesc. " +
                              "All prior specs must be verified AND checkpointed (run checkpoint.sh) " +
                              "before editing this spec's production code. " +
                              "Set VERIFIED_SCALA3_SKIP_PREDECESSOR_CHECK=1 to override."
                          trace(ctx, env, s"tool-call: refuse (predecessor $s $phaseDesc, $chg/$spec)")
                          toolCallRefusal(ctx.format, reason)

  /**
   * The ambient evidence writer (Tier A′): classify the harness's tool
   * response (`ToolOutcome.classify`), match the command against the
   * ring-shape table, and append a ledger row through the record tool.
   * NEVER blocks — every path exits 0.
   *
   * spec: gate-event-completeness — Property: post-tool-observation-never-blocks
   */
  private def runPostBash(
    ctx: GateContext,
    parsed: GateArgs,
    env: Map[String, String],
    payload: Option[HarnessPayload]
  ): Outcome[Int] =
    // Flag path: --command supplied → the exit comes from --exit, no
    // payload or tool classification at all (the predecessor's test
    // seam). Payload path: Bash-tool only, command from tool_input, exit
    // from ToolOutcome.classify.
    val flagCommand: String = parsed.flags.getOrElse("--command", "")
    val commandAndExit: Option[(String, String)] =
      if flagCommand.nonEmpty then
        val exitFlag: String = parsed.flags.getOrElse("--exit", "")
        if exitFlag.isEmpty then None else Some((flagCommand, exitFlag))
      else
        payload match
          case None => None
          case Some(p: HarnessPayload) =>
            if p.toolName.nonEmpty && p.toolName != "Bash" then
              trace(ctx, env, s"post-bash: tool is ${p.toolName}, not Bash — not recorded")
              None
            else
              val command: String = jqField(toolInputField(p, "command"))
              if command.isEmpty then None
              else
                ToolOutcome.classify(p.toolResponse) match
                  case ToolOutcome.Exit(code: Int) => Some((command, code.toString))
                  case ToolOutcome.Skip(reason: String) =>
                    trace(ctx, env, s"post-bash: $reason — not recorded")
                    None
    // The predecessor traces the resolved pair unconditionally (empty
    // fields included) before the silent "nothing to record" exits.
    trace(
      ctx,
      env,
      s"post-bash: command='${commandAndExit.map((p: (String, String)) => p._1).getOrElse("")}' " +
        s"exit=${commandAndExit.map((p: (String, String)) => p._2).getOrElse("")}"
    )
    commandAndExit match
      case None => Outcome.Ran(0)
      case Some((command: String, exitStr: String)) =>
        GateDecisions.ambientVerdict(command) match
          case Left(reason: String) =>
            trace(ctx, env, s"post-bash: $reason")
            Outcome.Ran(0)
          case Right(matched: GateDecisions.AmbientMatch) =>
            ctx.stateDir match
              case None =>
                trace(ctx, env, "post-bash: STATE_DIR unavailable, skipping record")
                Outcome.Ran(0)
              case Some(dir: GateStateDir) =>
                appendAmbientRow(ctx, dir, command, exitStr, matched, env)

  /**
   * Append the ambient ledger row for a matched ring command — the
   * predecessor's `ledger.sh append --source ambient` through the
   * in-process `LedgerCmd.runAppend` (the record validates identically
   * and no subprocess dependency is needed). `spec` is derived as the
   * first spec holding this session's presentation without a grant,
   * else the first spec dir, else "unknown". NEVER blocks — the
   * append's own outcome is discarded.
   */
  private def appendAmbientRow(
    ctx: GateContext,
    dir: GateStateDir,
    command: String,
    exitStr: String,
    matched: GateDecisions.AmbientMatch,
    env: Map[String, String]
  ): Outcome[Int] =
    firstActiveChange(ctx.repo) match
      case None =>
        trace(ctx, env, "post-bash: missing required fields, skipping record")
        Outcome.Ran(0)
      case Some(chg: String) =>
        val chgDir: Path        = ctx.repo.resolve("openspec/changes").resolve(chg)
        val specs: List[String] = GateStateDirReader.specDirs(chgDir)
        val spec: String =
          specs
            .find { (s: String) =>
              Files.isRegularFile(GateStateDirReader.presentationFile(dir, chg, s, ctx.session)) &&
              !GateStateDirReader.hasGrant(dir, chg, s, ctx.session)
            }
            .orElse(specs.headOption)
            .getOrElse("unknown")
        val baseline: String =
          SpecLintCmd
            .gitOut(ctx.repo, List("rev-parse", "--short", "HEAD"))
            .getOrElse("0000000")
        val fields: Map[String, String] = Map(
          "--file"       -> chgDir.resolve("evidence-ledger.jsonl").toString,
          "--change"     -> chg,
          "--spec"       -> spec,
          "--ring"       -> Ring.asString(matched.ring),
          "--obligation" -> matched.obligation,
          "--artifact"   -> matched.artifact,
          "--command"    -> command,
          "--exit"       -> exitStr,
          "--baseline"   -> baseline,
          "--source"     -> "ambient"
        )
        // R8 rows carry the session (judgment-ring-provenance) — the
        // ambient table never produces one, but the shape is preserved.
        val withSession: Map[String, String] =
          if matched.ring == Ring.R8 then fields + ("--session" -> ctx.session.encoded)
          else fields
        val _ = LedgerCmd.runAppend(withSession, "") // observation only — the outcome never propagates
        trace(ctx, env, s"post-bash: row appended (ring=${Ring.asString(matched.ring)}, exit=$exitStr)")
        Outcome.Ran(0)

  /**
   * The completion blocking tier (Tier A): refuse when a checkpoint
   * presentation exists for this session and the reconcile or chain
   * state reports uncorroborated/unresolved/undetermined evidence.
   * Bounded by the session's completion-refusal marker; fails open when
   * the bounding state is unavailable.
   *
   * spec: gate-event-completeness — Requirement: The completion tier refuses only on corroborated-uncorroborated, unresolved, or undetermined chain state
   */
  private def runCompletion(
    ctx: GateContext,
    parsed: GateArgs,
    env: Map[String, String],
    payload: Option[HarnessPayload]
  ): Outcome[Int] =
    val stopActive: Boolean = parsed.flags.get("--stop-hook-active") match
      case Some(v: String) => v == "true"
      case None            =>
        // The payload read is skipped when --turn-text was supplied —
        // the predecessor's STOP_HOOK_ACTIVE_GIVEN gate.
        if parsed.flags.get("--turn-text").exists(_.nonEmpty) then false
        else payload.exists((p: HarnessPayload) => jqField(p.stopHookActive) == "true")
    if stopActive then
      trace(ctx, env, "completion: stop_hook_active=true, allow")
      Outcome.Ran(0)
    else
      ctx.stateDir match
        case None =>
          trace(ctx, env, "completion: no STATE_DIR, no marker check possible, allow")
          Outcome.Ran(0)
        case Some(dir: GateStateDir) =>
          // No presentation marker for this session → no completion
          // claim is being made → mid-work stops pass silently.
          if !GateStateDirReader.hasSessionPresentation(dir, ctx.session) then
            trace(ctx, env, "completion: no checkpoint presentation marker for this session, allow")
            Outcome.Ran(0)
          else if GateStateDirReader.hasRefusal(dir, RefusalKind.Completion, ctx.session) then
            trace(ctx, env, "completion: already refused once this turn, allow")
            Outcome.Ran(0)
          else
            trace(ctx, env, "completion: checkpoint presentation marker found, running chain-state")
            val gateBaseline: String =
              SpecLintCmd.gitOut(ctx.repo, List("rev-parse", "HEAD")).getOrElse("unknown")
            val chgDirs: List[Path] = activeChangeDirs(ctx.repo)
            // The corroboration scope compares against the baseline the
            // ledger RECORDED — the post-bash writer's `--short` form,
            // not the long sha chain-state receives. Resolved only when
            // a ledger exists to compare against — the subprocess is
            // skipped on the ledger-less path.
            val ledgerBaseline: Option[String] =
              if chgDirs.exists((d: Path) => Files.isRegularFile(d.resolve("evidence-ledger.jsonl")))
              then SpecLintCmd.gitOut(ctx.repo, List("rev-parse", "--short", "HEAD"))
              else None
            val scan: CompletionScan =
              chgDirs.foldLeft(CompletionScan.Empty) { (acc: CompletionScan, chgDir: Path) =>
                completionScanChange(ctx, env, chgDir, gateBaseline, ledgerBaseline, acc)
              }
            val decision: CompletionDecision =
              GateDecisions.decideCompletion(scan.verdict, RefusalBudget.full)
            // Decision order: undetermined → uncorroborated →
            // unresolved (the predecessor's order — an unwitnessed
            // ledger poisons everything downstream of it).
            if scan.undetermined then
              if !GateStateDirReader.writeRefusal(dir, RefusalKind.Completion, ctx.session) then
                trace(ctx, env, "completion: failed to write refusal marker, failing open")
                Outcome.Ran(0)
              else
                val reason: String = BlockReason.ChainStateUndetermined.render
                trace(ctx, env, "completion: refuse (undetermined)")
                completionRefusal(ctx.format, reason, Outcome.Undetermined(_))
            else if decision.isRefusal then
              if !GateStateDirReader.writeRefusal(dir, RefusalKind.Completion, ctx.session) then
                trace(ctx, env, "completion: failed to write refusal marker, failing open")
                Outcome.Ran(0)
              else
                val reason: String = BlockReason.Uncorroborated(scan.uncorroborated).render
                trace(ctx, env, "completion: refuse (uncorroborated)")
                completionRefusal(ctx.format, reason, Outcome.Finding(_))
            else
              decision match
                case CompletionDecision.AllowUndetermined(r: UndeterminedReason) =>
                  trace(ctx, env, s"completion: corroboration undeterminable — ${r.text}, allow")
                case _ => () // danger-scan:allow decision-shape — Allow carries nothing to state
              if scan.unresolved.nonEmpty then
                if !GateStateDirReader.writeRefusal(dir, RefusalKind.Completion, ctx.session) then
                  trace(ctx, env, "completion: failed to write refusal marker, failing open")
                  Outcome.Ran(0)
                else
                  val reason: String = BlockReason.CompletionUnresolved(scan.unresolved).render
                  trace(ctx, env, "completion: refuse (unresolved)")
                  completionRefusal(ctx.format, reason, Outcome.Finding(_))
              else
                trace(ctx, env, "completion: allow (fully discharged)")
                Outcome.Ran(0)

  /** The per-change accumulators of the completion scan. */
  final private case class CompletionScan(
    undetermined: Boolean,
    unresolved: String,
    verdict: WitnessVerdict,
    uncorroborated: String
  )

  private object CompletionScan:
    val Empty: CompletionScan =
      CompletionScan(undetermined = false, "", WitnessVerdict.Witnessed, "")

  /**
   * Fold two change-dirs' corroboration verdicts: the first
   * `Unwitnessed` wins (the refusal names its row); otherwise the first
   * `Undeterminable`; `Witnessed` only when every change is witnessed.
   */
  private def mergeVerdict(a: WitnessVerdict, b: WitnessVerdict): WitnessVerdict =
    (a, b) match
      case (WitnessVerdict.Unwitnessed(_), _)    => a
      case (_, WitnessVerdict.Unwitnessed(_))    => b
      case (WitnessVerdict.Undeterminable(_), _) => a
      case _                                     => b // danger-scan:allow verdict-fold — only Witnessed remains

  /**
   * Scan one active change dir for the completion decision: the
   * corroboration verdict over the change's evidence ledger — computed
   * IN-CORE (`readLedgerFile` + `Ledger.readValidated` +
   * `ReconcileEngine.classify` + `GateDecisions.corroborationVerdict`),
   * no reconcile subprocess — and the chain state tool's
   * unresolved-requirements list.
   *
   * The in-core read is the defect repair: the ported tool resolution
   * anchored `reconcile.sh` at the repo under test, where fixtures
   * never install it, silently skipping the corroboration check. An
   * absent ledger, an unresolvable baseline, or a present-but-unreadable
   * record is `Undeterminable` — fail-open with a stated reason naming
   * the unreadable input, never a silent clean allow and never a
   * refusal on unread state.
   *
   * spec: completion-witness-refusal — Requirement: A turn is refused when a green result has no corroboration
   */
  private def completionScanChange(
    ctx: GateContext,
    env: Map[String, String],
    chgDir: Path,
    gateBaseline: String,
    ledgerBaseline: Option[String],
    acc: CompletionScan
  ): CompletionScan =
    val name: String = chgDir.getFileName.toString
    val ledger: Path = chgDir.resolve("evidence-ledger.jsonl")
    val corr: (WitnessVerdict, String) = // verdict + refusal detail text
      if !Files.isRegularFile(ledger) then
        (
          WitnessVerdict.Undeterminable(
            UndeterminedReason.stated(s"$ledger: no evidence record")
          ),
          ""
        )
      else
        ledgerBaseline match
          case None =>
            (
              WitnessVerdict.Undeterminable(
                UndeterminedReason.stated(
                  s"baseline unresolvable — git rev-parse --short HEAD failed under ${ctx.repo}"
                )
              ),
              ""
            )
          case Some(base: String) =>
            SubcommandWiring.readLedgerFile(ledger.toString) match
              case Outcome.Undetermined(reason: String) =>
                (
                  WitnessVerdict.Undeterminable(
                    UndeterminedReason.stated(s"$ledger: $reason")
                  ),
                  ""
                )
              case Outcome.Finding(msg: String) =>
                // readLedgerFile never yields Finding — direction-honest
                // passthrough, still undeterminable rather than a warrant.
                (
                  WitnessVerdict.Undeterminable(
                    UndeterminedReason.stated(s"$ledger: $msg")
                  ),
                  ""
                ) // danger-scan:allow unreachable-branch — readLedgerFile's contract is Ran|Undetermined
              case Outcome.Ran(rows: List[ujson.Value]) =>
                Ledger.readValidated(rows) match
                  case Left(err: LedgerReadError) =>
                    (
                      WitnessVerdict.Undeterminable(
                        UndeterminedReason.stated(s"$ledger: ${err.description}")
                      ),
                      ""
                    )
                  case Right(records: List[ValidatedRecord]) =>
                    val report: ReconcileReport =
                      ReconcileEngine.classify(records, name, None, None)
                    GateDecisions.corroborationVerdict(report, base) match
                      case v @ WitnessVerdict.Unwitnessed(_) =>
                        (v, "\n  " + StdoutRenderer[ReconcileReport].render(report))
                      case v =>
                        (v, "") // danger-scan:allow verdict-shape — Witnessed/Undeterminable carry no detail text
    val chainState: Path = scannerTool(ctx.repo, env, "CHAIN_STATE_OVERRIDE", "chain-state.sh")
    val csResult: Option[(Int, String)] =
      if toolExists(chainState) then
        runScanner(
          ctx.repo,
          chainState,
          List("--change-dir", chgDir.toString, "--change", name, "--baseline", gateBaseline),
          mergeStderr = false
        )
      else Some((127, "")) // the predecessor's missing-tool exit
    val verdict: WitnessVerdict = mergeVerdict(acc.verdict, corr._1)
    val uncorroborated: String  = acc.uncorroborated + corr._2
    csResult match
      case Some((code: Int, out: String)) if code == 0 || code == 1 =>
        parseChainStateTotal(out) match
          case None =>
            // Not a JSON object with a numeric .total — undetermined.
            acc.copy(undetermined = true, verdict = verdict, uncorroborated = uncorroborated)
          case Some(obj: ujson.Obj) =>
            val unresolvedCount: Int = chainUnresolvedCount(obj)
            if unresolvedCount > 0 then
              acc.copy(
                unresolved = acc.unresolved + s"\n  $name:\n" + chainUnresolvedNames(obj),
                verdict = verdict,
                uncorroborated = uncorroborated
              )
            else acc.copy(verdict = verdict, uncorroborated = uncorroborated)
      case _ => // danger-scan:allow fail-closed — a non-{0,1} scanner exit or absent result is undetermined, never clean
        acc.copy(undetermined = true, verdict = verdict, uncorroborated = uncorroborated)

  /**
   * The chain-state parse gate: a JSON object carrying a numeric
   * `.total` — anything else (bad exit, non-JSON, missing/ill-typed
   * total) is undetermined.
   */
  private def parseChainStateTotal(out: String): Option[ujson.Obj] =
    try
      ujson.read(out) match
        case obj: ujson.Obj =>
          obj.value.get("total") match
            case Some(_: ujson.Num) => Some(obj)
            case _                  => None // danger-scan:allow fail-closed — a non-numeric .total is undetermined
        case _ => None // danger-scan:allow fail-closed — non-object chain state is undetermined
    catch case NonFatal(_) => None // danger-scan:allow undetermined — unparseable chain state is not a clean bill

  /**
   * `.unresolved | length` — jq length semantics: arrays and objects
   * count elements/keys, strings count characters, numbers count
   * magnitude; `null` and booleans are 0.
   */
  private def chainUnresolvedCount(obj: ujson.Obj): Int =
    obj.value.get("unresolved") match
      case Some(ujson.Arr(items)) => items.length
      case Some(ujson.Obj(m))     => m.size
      case Some(ujson.Str(s))     => s.length
      case Some(ujson.Num(n))     => math.abs(n.toInt)
      case _                      => 0 // danger-scan:allow jq `length` semantics — null/boolean/missing read as 0

  /**
   * The unresolved-names block — the predecessor's jq map over
   * `.unresolved[]` with `.requirement` and `.reasons`. Extraction is
   * all-or-nothing (jq's `map` fails atomically on a non-conforming
   * entry, yielding an empty block — the refusal still fires on the
   * count).
   */
  private def chainUnresolvedNames(obj: ujson.Obj): String =
    obj.value.get("unresolved") match
      case Some(ujson.Arr(items)) =>
        val entries: List[Option[(String, List[String])]] =
          items.toList.map {
            case o: ujson.Obj =>
              for
                req <- o.value.get("requirement").collect { case ujson.Str(s: String) => s }
                reasons <- o.value.get("reasons").collect { case ujson.Arr(rs) =>
                  rs.toList.collect { case ujson.Str(s: String) => s }
                }
              yield (req, reasons)
            case _ => None // danger-scan:allow jq `map` is atomic — a non-conforming entry fails the whole map
          }
        if entries.forall(_.isDefined) then GateDecisions.unresolvedBlock(entries.flatMap(_.toList))
        else ""
      case _ => // danger-scan:allow jq `map` on a non-array fails — the names block is empty; the refusal still fires on the count
        ""

  /**
   * Write this session's grant tokens: for every spec with a
   * presentation marker but no grant yet, copy the presentation hash
   * into `grant-<change>-<spec>-<session>` — the only event that
   * writes grants (predecessor's prompt-submit block). The write is
   * guarded on a non-empty hash and an absent grant — the same
   * conditions the predecessor traces under `grant written for …`.
   */
  private def writeSessionGrants(
    dir: GateStateDir,
    ctx: GateContext,
    env: Map[String, String]
  ): Unit =
    GateStateDirReader
      .sessionPresentations(dir, ctx.session)
      .foreach { (change: String, spec: String) =>
        if !GateStateDirReader.hasGrant(dir, change, spec, ctx.session) then
          GateStateDirReader
            .readPresentationHash(dir, change, spec, ctx.session)
            .filter(_.nonEmpty)
            .foreach { (hash: String) =>
              GateStateDirReader.writeGrant(dir, change, spec, ctx.session, hash)
              trace(ctx, env, s"grant written for $change/$spec (session ${ctx.session.encoded})")
            }
      }

  /**
   * The Tier-B banner path — the spec-3 live-fact wiring:
   * facts read → render → per-session suppression → emit. Always
   * exit 0; a failure at any step degrades (no output), never fails a
   * session. The prologue already applied the relevance guard, hook
   * control, state directory, and heartbeat.
   *
   * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
   * spec: live-fact-banner — Requirement: Repeated injections within one session are suppressed only when the underlying facts are unchanged
   * spec: live-fact-banner — Requirement: The hook emits the invariant and live chain state on session-start and prompt-submit
   */
  private def runBanner(
    ctx: GateContext,
    env: Map[String, String]
  ): Outcome[Int] =
    try
      // The predecessor scans `$HOME/…`; the JVM's user.home comes from
      // the passwd entry, not $HOME — under an overridden HOME the
      // user-scoped roots must still resolve where the predecessor looked.
      val userHome: Path =
        env
          .get("HOME")
          .filter(_.nonEmpty)
          .map(Path.of(_))
          .getOrElse(
            Path.of(System.getProperty("user.home"))
          ) // danger-scan:allow home-fallback — user.home is the last resort when HOME is unset
      val facts: RepositoryFacts =
        RepositoryFactsReader.read(ctx.repo, userHome, env)
      val banner: BannerOutput =
        BannerEngine.render(BannerInputs.from(facts))
      val suppressed: Boolean = ctx.stateDir.exists { (d: GateStateDir) =>
        GateStateDirReader.readFingerprint(d, ctx.session).contains(facts.fingerprint)
      }
      if banner.payload.isEmpty then
        trace(ctx, env, "skip: nothing to report (no facts, no active change)")
        Outcome.Ran(0)
      else if suppressed then
        trace(ctx, env, s"skip: unchanged since last injection this session (${ctx.session.encoded})")
        Outcome.Ran(0)
      else
        ctx.stateDir.foreach((d: GateStateDir) =>
          GateStateDirReader.writeFingerprint(d, ctx.session, facts.fingerprint)
        )
        trace(ctx, env, s"emit: ${banner.payload.length} chars")
        emitBanner(ctx.dispatch, ctx.format, banner.payload)
        Outcome.Ran(0)
    catch case NonFatal(_) => Outcome.Ran(0) // danger-scan:allow fail-open — the gate never fails a session

  /**
   * Emit the banner payload: `hook-json` wraps it in the shared
   * `hookSpecificOutput` envelope. The `hookEventName` is the harness's
   * own event name — for a tier dispatch, the event's harness name
   * (prompt-submit maps to `UserPromptSubmit`, matching the
   * predecessor); for an injection, the predecessor's `*)` arm maps
   * every unrecognised name to `SessionStart`. Every other format
   * value — including `text` — prints the payload itself.
   *
   * spec: gate-event-compatibility — Requirement: The payload envelope reports the harness event name
   */
  private def emitBanner(dispatch: EventDispatch, format: String, payload: String): Unit =
    format match
      case "hook-json" =>
        val hookEventName: String = dispatch match
          case EventDispatch.Tier(event) => GateEvent.harnessName(event)
          case EventDispatch.Injection(_) =>
            "SessionStart" // danger-scan:allow predecessor `*)` arm — every unrecognised name envelopes as SessionStart
        val envelope: ujson.Obj = ujson.Obj(
          "hookSpecificOutput" -> ujson.Obj(
            "hookEventName"     -> ujson.Str(hookEventName),
            "additionalContext" -> ujson.Str(payload)
          )
        )
        SubcommandWiring.emitStdout(ujson.write(envelope) + "\n")
      case _ => // danger-scan:allow format-default — any non-hook-json format emits the text payload (predecessor `*)`)
        SubcommandWiring.emitStdout(payload + "\n")
