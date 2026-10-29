package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

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
        toolName match
          case ToolNameSource.Absent =>
            // spec: oracle-fixture-repair — Requirement: An absent tool name keeps parity and is stated
            trace(ctx, env, s"tool-call: file under allow-listed path $prefix, allow — $file — no tool name supplied")
          case ToolNameSource.Supplied(_) =>
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

/** The `spec-lint` subcommand — spec file linting. */
object SpecLintCmd:

  /** The predecessor's parsed invocation shape. */
  final private case class SpecLintArgs(
    artifacts: Boolean,
    contextOnly: Boolean,
    formatJson: Boolean,
    target: String
  )

  /**
   * Parse args exactly as the predecessor's `case` loop does:
   * `--artifacts` and `--context-only` are boolean modifiers, `--format`
   * is consumed without effect (its value arrives as its own token), a
   * bare `json` token selects JSON output, and every other token becomes
   * the target — last one wins, default `.`.
   */
  private def parseArgs(args: Array[String]): SpecLintArgs =
    args.toList.foldLeft(SpecLintArgs(false, false, false, ".")) { (acc, arg) =>
      arg match
        case "--artifacts"    => acc.copy(artifacts = true)
        case "--context-only" => acc.copy(contextOnly = true)
        case "--format"       => acc
        case "json"           => acc.copy(formatJson = true)
        case other => // danger-scan:allow positional-arg — unrecognized args are the target, last one wins
          acc.copy(target = other)
    }

  /** `git <args>` under `dir`; trimmed stdout on exit 0, else None. */
  private[cli] def gitOut(dir: Path, args: List[String]): Option[String] =
    try
      val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
      pb.directory(dir.toFile)
      // predecessor parity: `git ... 2>/dev/null` — stderr is discarded.
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if p.waitFor() == 0 && out.trim.nonEmpty then Some(out.trim) else None
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a non-git cwd falls back to "." like the predecessor
        None

  /**
   * The repository root — the predecessor's
   * `git rev-parse --show-toplevel || echo .` from the caller's cwd, so
   * the applicability facts resolve even when the tool is invoked from a
   * repository subdirectory.
   */
  private[cli] def repoRoot: Path =
    val cwd: Path = Paths.get("").toAbsolutePath.normalize
    gitOut(cwd, List("rev-parse", "--show-toplevel")) match
      case Some(p) => Paths.get(p)
      case None    => cwd

  /** The user-scoped install-root base — `$HOME`, else `user.home`. */
  private[cli] def userHome(env: Map[String, String]): Path =
    env
      .get("HOME")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(
        Paths.get(System.getProperty("user.home"))
      ) // danger-scan:allow home-fallback — user.home is the last resort when HOME is unset

  /**
   * Discover the spec files to lint — the predecessor's two-step probe:
   * `<target>/specs/` first (a change directory), else
   * `<target>/openspec/changes/` (a repository root, `archive/` excluded).
   * `None` means neither shape exists — the predecessor's exit-2 path.
   */
  private def specFiles(target: Path): Option[Either[String, List[Path]]] =
    val specsDir: Path   = target.resolve("specs")
    val changesDir: Path = target.resolve("openspec/changes")
    if Files.isDirectory(specsDir) then Some(findSpecs(specsDir, _ => true))
    else if Files.isDirectory(changesDir) then
      // `-path '*/specs/*'` — a `specs` component anywhere in the path
      // (nested `specs/x/spec.md` is found); `! -path '*/archive/*'` —
      // no `archive` component anywhere.
      Some(
        findSpecs(
          changesDir,
          p =>
            p.iterator().asScala.exists(_.toString == "specs") &&
              !p.iterator().asScala.exists(_.toString == "archive")
        )
      )
    else None

  /**
   * `find <root> -name spec.md` filtered and sorted — as the predecessor
   * pipes it. A failed traversal is `Left`, never an empty list: an
   * existing-but-unlistable directory tree is a discovery fault
   * (could-not-determine), distinguishable from a genuinely empty one.
   */
  private[cli] def findSpecs(root: Path, keep: Path => Boolean): Either[String, List[Path]] =
    try
      Right(
        Using.resource(Files.walk(root)) { walk =>
          walk
            .iterator()
            .asScala
            .toList
            .filter((p: Path) => Files.isRegularFile(p) && p.getFileName.toString == "spec.md" && keep(p))
            .sortBy(_.toString)
        }
      )
    catch
      case NonFatal(e) => // danger-scan:allow degraded-fact — a failed find is could-not-determine, not "no spec files"
        Left(s"could not enumerate spec files under $root: ${e.getMessage}")

  /** A finding as the predecessor's text line: `FAIL F7 line 30: msg`. */
  private def findingText(f: CheckOutcome): String = f match
    case CheckOutcome.Fail(check, line, msg) =>
      s"FAIL ${CheckId.asString(check)}${line.fold("")(l => s" line $l")}: $msg"
    case CheckOutcome.Warn(w) =>
      s"WARN ${w.code}${w.line.fold("")(l => s" line $l")}: ${w.message}"
    case CheckOutcome.Pass(check) =>
      s"PASS ${CheckId.asString(check)}" // danger-scan:allow unreachable-shape — the finding stream carries no Pass

  private val requirementRe: scala.util.matching.Regex =
    "requirement \"([^\"]+)\"".r
  private val artifactRe: scala.util.matching.Regex =
    "artifact '([^']+)'".r

  /**
   * A finding as the predecessor's jq object:
   * `{check, verdict, requirement, reason, line, artifact}` — `requirement`
   * and `artifact` extracted from the reason text, `line` 0 when absent.
   */
  private def findingJson(f: CheckOutcome): ujson.Obj =
    val (verdict: String, check: String, line: Option[Int], reason: String) = f match
      case CheckOutcome.Fail(c, l, msg) => ("FAIL", CheckId.asString(c), l, msg)
      case CheckOutcome.Warn(w)         => ("WARN", w.code, w.line, w.message)
      case CheckOutcome.Pass(c) =>
        (
          "PASS",
          CheckId.asString(c),
          None,
          ""
        ) // danger-scan:allow unreachable-shape — the finding stream carries no Pass
    ujson.Obj(
      "check"   -> ujson.Str(check),
      "verdict" -> ujson.Str(verdict),
      "requirement" -> ujson.Str(
        requirementRe.findFirstMatchIn(reason).map(_.group(1)).getOrElse("")
      ),
      "reason" -> ujson.Str(reason),
      "line"   -> ujson.Num(line.getOrElse(0)),
      "artifact" -> ujson.Str(
        artifactRe.findFirstMatchIn(reason).map(_.group(1)).getOrElse("")
      )
    )

  /**
   * Wire the spec-lint subcommand to the F1–F10 checks and CONTEXT block.
   *
   * The CONTEXT block prints before the lint run in text mode; in
   * `--format json` mode it is suppressed entirely (the predecessor
   * redirects it to /dev/null), so `--context-only --format json` emits
   * nothing and exits clean.
   *
   * spec: cli-wiring — Requirement: The spec-lint subcommand wires to the F1–F10 checks and emits the CONTEXT block
   * spec: spec-lint-engine — Requirement: The lint tool's caller-facing surface accepts the predecessor's invocation forms
   */
  def run(args: Array[String]): Outcome[Int] =
    val parsed: SpecLintArgs     = parseArgs(args)
    val env: Map[String, String] = sys.env // scalafix:ok DisableSyntax.NoSysEnv
    val root: Path               = repoRoot
    val context: LintContext =
      RepositoryFactsReader.readLintContext(root, userHome(env))

    if parsed.contextOnly then
      if !parsed.formatJson then SubcommandWiring.emitStdout(StdoutRenderer[LintContext].render(context) + "\n")
      Outcome.Ran(0)
    else
      val tracked: Set[String] =
        if parsed.artifacts then
          gitOut(root, List("ls-files"))
            .map(_.linesIterator.toSet)
            .getOrElse(Set.empty)
        else Set.empty
      val artifactTracked: String => Boolean =
        (base: String) => tracked.exists(_.contains(base))

      if !parsed.formatJson then SubcommandWiring.emitStdout(StdoutRenderer[LintContext].render(context) + "\n\n")

      specFiles(Paths.get(parsed.target)) match
        case None =>
          val msg: String =
            s"no specs found under ${parsed.target} " +
              "(expected <change>/specs/ or openspec/changes/)"
          SubcommandWiring.emitStderr(s"spec-lint: $msg\n")
          Outcome.Undetermined(msg)
        case Some(Left(reason)) =>
          SubcommandWiring.emitStderr(s"spec-lint: $reason\n")
          Outcome.Undetermined(reason)
        case Some(Right(Nil)) =>
          SubcommandWiring.emitStdout(
            s"spec-lint: no spec files to lint under ${parsed.target}\n"
          )
          Outcome.Ran(0)
        case Some(Right(specs)) => runSpecs(specs, context, parsed, artifactTracked)

  /**
   * Lint every discovered spec and emit findings in the predecessor's
   * shape — per-file headers in text mode, one JSON array in JSON mode —
   * then the summary line and the FAIL/WARN exit mapping.
   */
  private def runSpecs(
    specs: List[Path],
    context: LintContext,
    parsed: SpecLintArgs,
    artifactTracked: String => Boolean
  ): Outcome[Int] =
    val outcomes: List[Either[String, (Path, LintReport)]] = specs.map { spec =>
      try
        val text: String      = Files.readString(spec, StandardCharsets.UTF_8)
        val doc: SpecDocument = SpecDocumentParser.parse(spec.toString, text)
        SpecLintEngine.lint(doc, context, parsed.artifacts, artifactTracked) match
          case Outcome.Ran(report)     => Right(spec -> report)
          case Outcome.Undetermined(r) => Left(r)
          case Outcome.Finding(msg)    => Left(msg)
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — an unreadable spec is could-not-determine, not skipped
          Left(s"could not read $spec: ${e.getMessage}")
    }
    outcomes.collectFirst { case Left(reason) => reason } match
      case Some(reason) =>
        SubcommandWiring.emitStderr(s"spec-lint: UNDETERMINED — $reason\n")
        Outcome.Undetermined(reason)
      case None =>
        val reports: List[(Path, LintReport)] =
          outcomes.collect { case Right(r) => r }
        val fails: Int = reports.map(_._2.failures.length).sum
        val warns: Int = reports.map(_._2.warnings.length).sum
        if parsed.formatJson then
          val findings: ujson.Arr = ujson.Arr(
            reports.flatMap(_._2.findings).map(findingJson)*
          )
          SubcommandWiring.emitStdout(ujson.write(findings) + "\n")
        else
          reports.foreach { case (spec, report) =>
            if report.findings.nonEmpty then
              SubcommandWiring.emitStdout(s"spec-lint: $spec\n")
              report.findings.foreach { f =>
                SubcommandWiring.emitStdout(s"  ${findingText(f)}\n")
                // The generic-F6 hint is part of the predecessor's findings
                // stream (9-space indent + the 2-space findings prefix); it
                // is not a FAIL/WARN line so JSON mode never sees it.
                f match
                  case CheckOutcome.Fail(CheckId.F6, _, msg)
                      if msg.startsWith("Source names no resolvable reference") =>
                    SubcommandWiring.emitStdout(
                      "           (use \"Requirement: <exact title>\", \"Requirement N\", " +
                        "or a typed source like \"Property: <name>\")\n"
                    )
                  case _ => // danger-scan:allow non-F6-shape — the hint trails only the unresolvable-source F6
                    ()
              }
          }
          SubcommandWiring.emitStdout(
            s"spec-lint: ${reports.length} spec file(s), $fails FAIL, $warns WARN\n"
          )
          if fails > 0 then
            SubcommandWiring.emitStdout(
              "spec-lint: FAILED — F-checks are lint failures; fix the specs and re-run.\n"
            )
        if fails > 0 then Outcome.Finding(s"spec-lint: $fails FAIL, $warns WARN")
        else Outcome.Ran(0)

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
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * The env-injecting overload — `OPENSPEC_ROOT` selects the graph-export
   * root (the predecessor's own contract, now consumed by the in-process
   * `GraphCmd` seam); the seam exists so the test oracle can force the
   * graph and degraded paths deterministically.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    parseChainStateArgs(args) match
      case Left(msg) =>
        SubcommandWiring.emitStderr(s"chain-state: $msg\n")
        Outcome.Finding(msg)
      case Right(parsed) =>
        val changeDir: String          = parsed.flags.getOrElse("--change-dir", "")
        val change: String             = parsed.flags.getOrElse("--change", "")
        val baseline: String           = parsed.flags.getOrElse("--baseline", "")
        val ledgerFile: String         = parsed.flags.getOrElse("--ledger-file", s"$changeDir/evidence-ledger.jsonl")
        val specFilter: Option[String] = parsed.flags.get("--spec").filter(_.nonEmpty)

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
          // Fact measurement runs FIRST (the predecessor enumerates specs
          // and runs spec-lint before the ledger read), so the per-spec
          // baseline map is known when the ledger read's failure mode is
          // classified below.
          prepareInputs(
            Paths.get(changeDir),
            change,
            baseline,
            specFilter,
            env,
            ledgerFile,
            emitDiagnostics = true
          ) match
            case Left(reason) =>
              emitUndetermined(change, baseline, UndeterminedReason.stated(reason))
            case Right(inputs) =>
              // Read + validate the ledger file. The predecessor reads it
              // ONCE PER MAPPED SPEC and once unfiltered: with a non-empty
              // baseline map a failed per-spec read is traced and skipped
              // (the spec's requirements report undischarged — absence of
              // evidence), so a missing/corrupt ledger there is tolerated,
              // never silently zero and never undetermined. With an EMPTY
              // map a single failed read IS undetermined.
              val ledgerRead: Either[String, Ledger.LedgerData] =
                SubcommandWiring.readLedgerFile(ledgerFile) match
                  case Outcome.Undetermined(reason) => Left(reason)
                  case Outcome.Finding(msg)         => Left(msg)
                  case Outcome.Ran(rows)            =>
                    // Validate every row — a corrupt ledger is not a
                    // silently truncated ledger with invalid rows dropped.
                    rows
                      .foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                        case (Left(err), _) => Left(err)
                        case (Right(acc), v) =>
                          Validator.validate(v) match
                            case Right(r) => Right(r :: acc)
                            case Left(viol) =>
                              Left(
                                s"ledger contains invalid row: clause ${viol.clauseIndex} — ${viol.description}"
                              )
                      }
                      .map((recordsRev: List[LedgerRecord]) => Ledger.fromRecords(recordsRev.reverse))
              ledgerRead match
                case Left(reason) if inputs.specBaselines.nonEmpty =>
                  SubcommandWiring.emitStderr(
                    s"chain-state: ledger read failed ($reason); per-spec baseline map present — " +
                      "treating the spec's rows as unread (undischarged)\n"
                  )
                  emitReport(
                    ChainState.compute(
                      inputs.prePass,
                      Ledger.fromRecords(Nil),
                      inputs.extracted,
                      inputs.specBaselines,
                      inputs.effectiveBaseline,
                      inputs.resolvedBaseline,
                      change,
                      inputs.artifactUnchanged
                    ),
                    baseline,
                    inputs.extracted.source == FactSource.Degraded
                  )
                case Left(reason) =>
                  emitUndetermined(change, baseline, UndeterminedReason.stated(reason))
                case Right(ledger) =>
                  emitReport(
                    ChainState.compute(
                      inputs.prePass,
                      ledger,
                      inputs.extracted,
                      inputs.specBaselines,
                      inputs.effectiveBaseline,
                      inputs.resolvedBaseline,
                      change,
                      inputs.artifactUnchanged
                    ),
                    baseline,
                    inputs.extracted.source == FactSource.Degraded
                  )

  /**
   * Flags that consume a following value; `--artifacts` and
   * `--forgive-unchanged` are boolean. Both booleans are accepted for
   * predecessor compatibility — the port always runs the artifact check
   * and always applies the forgiveness oracle (the predecessor does too,
   * unconditionally), so they carry no mode switch.
   */
  private val valueFlags: Set[String] = Set(
    "--change-dir",
    "--change",
    "--baseline",
    "--ledger-file",
    "--format",
    "--spec"
  )

  /** Leniently-parsed chain-state arguments. */
  final private case class ChainStateArgs(
    flags: Map[String, String]
  )

  private def parseChainStateArgs(args: Array[String]): Either[String, ChainStateArgs] =
    def loop(rest: List[String], acc: Map[String, String]): Either[String, ChainStateArgs] =
      rest match
        case Nil                           => Right(ChainStateArgs(acc))
        case "--artifacts" :: tail         => loop(tail, acc)
        case "--forgive-unchanged" :: tail => loop(tail, acc)
        case flag :: tail if valueFlags.contains(flag) =>
          tail match
            case value :: remaining => loop(remaining, acc + (flag -> value))
            case Nil                => Left(s"$flag requires a value")
        case unknown :: _ => Left(s"unrecognised argument: $unknown")
    loop(args.toList, Map.empty)

  /**
   * Emit a completed `ChainState.compute` result: the undetermined report
   * on `Left`, else the contract-conformant JSON report — `Ran(0)` only
   * when nothing is unresolved and nothing is unmapped. `degraded` is the
   * extraction's stated fact source (`FactSource.Degraded` iff the
   * traceability graph could not be produced): the report declares it on
   * the wire as `"degraded": bool` — a verdict computed without the graph
   * is marked, never indistinguishable from a graph-backed one.
   *
   * spec: graph-tool-port — Scenario: Adversarial — an unavailable graph produces a stated degradation, not silence
   */
  private def emitReport(
    computed: Either[ChainStateUndetermined, ChainStateReport],
    baseline: String,
    degraded: Boolean
  ): Outcome[Int] =
    computed match
      case Left(u) =>
        emitUndetermined(u.change, baseline, u.reason)
      case Right(report) =>
        val rendered: String = StdoutRenderer[ChainStateReport].render(report)
        val stated: String = ujson.read(rendered) match
          case obj: ujson.Obj =>
            obj.value.put("degraded", ujson.Bool(degraded))
            ujson.write(obj)
          case _ => rendered // danger-scan:allow render-passthrough — StdoutRenderer always emits an object
        SubcommandWiring.emitStdout(stated + "\n")
        if report.unresolved.isEmpty && report.unmappedObligations.isEmpty then Outcome.Ran(0)
        else
          Outcome.Finding(
            s"chain-state: ${report.unresolved.length} unresolved, " +
              s"${report.unmappedObligations.length} unmapped obligation(s)"
          )

  /**
   * Emit the undetermined report on stdout and the diagnostic on stderr —
   * the `UNDETERMINED —` marker is written HERE, exactly once; the reason
   * field carries no marker (it is data, not a diagnostic line). The
   * reason is an `UndeterminedReason` — non-empty and naming the
   * unreadable input by construction.
   *
   * spec: chain-state-attribution — Requirement: The undetermined diagnostic marker is emitted exactly once
   * spec: chain-state-undetermined-fidelity — Requirement: Every distinct could-not-determine reason is named
   */
  private def emitUndetermined(
    change: String,
    baseline: String,
    reason: UndeterminedReason
  ): Outcome[Int] =
    val undetermined: ChainStateUndetermined = ChainStateUndetermined(change, baseline, reason)
    SubcommandWiring.emitStdout(StdoutRenderer[ChainStateUndetermined].render(undetermined) + "\n")
    SubcommandWiring.emitStderr(s"chain-state: UNDETERMINED — ${reason.text}\n")
    Outcome.Undetermined(reason.text)

  /**
   * The bundle of measured facts `ChainState.compute` consumes — the
   * extracted requirement set (with its FactSource), the pre-pass outcome
   * (the spec-lint run's completion boundary; `Completed` carries the
   * per-spec lint outcomes it produced), the per-spec baseline map, the
   * resolved effective baseline, and the forgive-unchanged oracle.
   */
  final private[cli] case class ChainStateInputs(
    extracted: RequirementSet,
    prePass: PrePassOutcome,
    specBaselines: Map[String, List[String]],
    effectiveBaseline: String,
    resolvedBaseline: String,
    artifactUnchanged: (String, String) => Boolean
  )

  /**
   * Discover the change's spec documents, parse them, attempt the graph
   * export, run spec-lint per spec, and resolve the baselines — every
   * fact `compute` needs, measured once. `Left` is a could-not-determine
   * reason (no spec tree, an unreadable document, a `--spec` name that
   * matches nothing); it is never a clean empty result.
   */
  private[cli] def prepareInputs(
    changeDir: Path,
    change: String,
    baselineArg: String,
    specFilter: Option[String],
    env: Map[String, String],
    ledgerFile: String,
    emitDiagnostics: Boolean
  ): Either[String, ChainStateInputs] =
    // The predecessor enumerates `find "$CHANGE_DIR/specs" -name spec.md` —
    // a missing specs/ tree means the spec-lint probe itself could not run.
    val specsDir: Path = changeDir.resolve("specs")
    if !Files.isDirectory(specsDir) then
      Left(s"no spec documents found under $specsDir; cannot determine bound/resolved")
    else
      SpecLintCmd.findSpecs(specsDir, _ => true) match
        case Left(reason) => Left(s"$reason; cannot determine bound/resolved")
        case Right(discovered) =>
          val selected: Either[String, List[Path]] = specFilter match
            case Some(s) =>
              discovered.filter((p: Path) => p.getParent.getFileName.toString == s) match
                case Nil =>
                  Left(s"no spec document named '$s' under $specsDir; cannot determine bound/resolved")
                case keep => Right(keep)
            case None => Right(discovered)
          selected match
            case Left(reason) => Left(reason)
            case Right(paths) =>
              val named: Either[String, List[RequirementExtractor.NamedSpec]] =
                paths.foldLeft[Either[String, List[RequirementExtractor.NamedSpec]]](Right(Nil)) { (acc, p) =>
                  acc.flatMap { (specs: List[RequirementExtractor.NamedSpec]) =>
                    try
                      val text: String = Files.readString(p, StandardCharsets.UTF_8)
                      Right(
                        specs :+ RequirementExtractor.NamedSpec(
                          p.getParent.getFileName.toString,
                          SpecDocumentParser.parse(p.toString, text)
                        )
                      )
                    catch
                      case NonFatal(e) => // danger-scan:allow unreadable-spec-fact — Left(reason), never dropped
                        Left(s"could not read $p: ${e.getMessage}")
                  }
                }
              named match
                case Left(reason)      => Left(reason)
                case Right(namedSpecs) =>
                  // Fact measurement — baseline resolution, graph export,
                  // extraction, spec-lint — is the predecessor's bash
                  // pipeline; a crash there is `die_undetermined` (exit 2),
                  // never a finding. A throw here maps to Left, not a JVM
                  // escape (which would exit 1 — the wrong direction).
                  try
                    val (specBaselines, effectiveBaseline, resolvedBaseline)
                      : (Map[String, List[String]], String, String) =
                      resolveBaselines(changeDir, baselineArg, emitDiagnostics)
                    val (exportJson, diagnostics): (Option[ujson.Value], List[String]) =
                      graphExport(change, env)
                    if emitDiagnostics then diagnostics.foreach((d: String) => SubcommandWiring.emitStderr(d + "\n"))
                    val extracted: RequirementSet =
                      RequirementExtractor.extract(namedSpecs, exportJson)
                    // spec-lint runs exactly as the predecessor invokes it:
                    // `--artifacts` always on, `git ls-files` at the caller's
                    // repo root deciding resolvability.
                    val root: Path = SpecLintCmd.repoRoot
                    val context: LintContext =
                      RepositoryFactsReader.readLintContext(root, SpecLintCmd.userHome(env))
                    val tracked: Set[String] = SpecLintCmd
                      .gitOut(root, List("ls-files"))
                      .map((out: String) => out.linesIterator.toSet)
                      .getOrElse(Set.empty)
                    val artifactTracked: String => Boolean =
                      (base: String) => tracked.exists(_.contains(base))
                    // The pre-pass boundary is the EXTERNAL spec-lint run
                    // — the same invocation the predecessor makes —
                    // classified by its termination, never by hidden
                    // in-process state. Only when it ran to completion do
                    // the in-process per-spec lint results become the
                    // verdict's data; a did-not-run can carry no lint map.
                    val prePass: PrePassOutcome =
                      ChainStatePrePass.probe(
                        changeDir,
                        discovered.length,
                        exportJson.isDefined,
                        root,
                        env
                      ) match
                        case Left(reason) =>
                          PrePassOutcome.DidNotRun(UndeterminedReason.stated(reason))
                        case Right(()) =>
                          PrePassOutcome.Completed(
                            namedSpecs.map { (ns: RequirementExtractor.NamedSpec) =>
                              ns.name -> SpecLintEngine
                                .lint(ns.document, context, checkArtifacts = true, artifactTracked)
                            }.toMap
                          )
                    Right(
                      ChainStateInputs(
                        extracted,
                        prePass,
                        specBaselines,
                        effectiveBaseline,
                        resolvedBaseline,
                        forgivePredicate(ledgerFile)
                      )
                    )
                  catch
                    case NonFatal(e) => // danger-scan:allow crash-is-undetermined — exits 2, never a finding
                      Left(s"could not measure chain-state facts: ${e.getMessage}")

  /**
   * The implementation-progress.md baseline map, ported from the
   * predecessor's awk: `## Spec N[: ]+(name)` blocks carry a
   * `### Baseline` paragraph whose first `` SHA `hex` `` line is that
   * spec's baseline; the effective baseline is the first
   * `**BASELINE SHA**: <sha>`/`SHA \`<sha>\`` match in the file, else the
   * gate's `--baseline`. The returned effective baseline is the RAW value
   * (echoed into the report); the third element is its `git rev-parse`
   * resolution — the staleness filter the ledger read applies (the
   * predecessor's `full_effective`).
   */
  private def resolveBaselines(
    changeDir: Path,
    baselineArg: String,
    emitDiagnostics: Boolean
  ): (Map[String, List[String]], String, String) =
    // REPO is derived from the change dir; the resolution itself runs
    // there or, when the change dir is outside any repository, in the
    // process cwd — the predecessor's `cd ""` is a no-op that keeps cwd.
    val repo: Option[Path] = repoContaining(changeDir)
    val progressFile: Path = changeDir.resolve("implementation-progress.md")
    if !Files.isRegularFile(progressFile) then (Map.empty, baselineArg, resolveSha(repo, baselineArg))
    else
      val lines: List[String] =
        try Files.readString(progressFile, StandardCharsets.UTF_8).linesIterator.toList
        catch case NonFatal(_) => Nil // danger-scan:allow degraded-fact — unreadable progress file means no baselines
      // The awk's two states: the `## Spec N:` block being walked and the
      // `### Baseline` paragraph inside it. The predecessor's rules run IN
      // SEQUENCE (awk semantics — a `## Spec` heading does not itself clear
      // `in_baseline`; `### Baseline`'s `next` skips the remaining rules;
      // a `SHA `-marked line captures the first `` `hex` `` and always ends
      // the paragraph; a blank line ends it; a non-`## Spec` `## ` heading
      // ends it). Ported rule-for-rule.
      val specHeadRe: scala.util.matching.Regex = "^## Spec [0-9]".r
      val specNameRe: scala.util.matching.Regex = "[0-9]+[: ]+\\(?([a-zA-Z0-9_-]+)".r
      val shaRe: scala.util.matching.Regex      = "`([a-f0-9]{7,40})`".r
      final case class BaselineAcc(
        currentSpec: String,
        inBaseline: Boolean,
        map: Map[String, List[String]]
      )
      val parsed: BaselineAcc = lines.foldLeft(BaselineAcc("", false, Map.empty)) { (acc, line) =>
        // /^## Spec [0-9]/ — updates current_spec only when the name match
        // succeeds; in_baseline is untouched (the awk has no clearing here).
        val afterSpecHead: BaselineAcc =
          if specHeadRe.findPrefixOf(line).isDefined then
            acc.copy(
              currentSpec = specNameRe.findFirstMatchIn(line).map(_.group(1)).getOrElse(acc.currentSpec)
            )
          else acc
        if line.startsWith("### Baseline") then afterSpecHead.copy(inBaseline = true)
        else
          // in_baseline && /SHA `/ — first `` `hex` `` wins; the paragraph
          // ends whether or not a hex was found or recorded.
          val afterSha: BaselineAcc =
            if afterSpecHead.inBaseline && line.contains("SHA `") then
              val sha: String =
                shaRe.findFirstMatchIn(line).map(_.group(1)).getOrElse("")
              BaselineAcc(
                afterSpecHead.currentSpec,
                inBaseline = false,
                // The predecessor's TSV appends one line per section, so a
                // spec with several `## Spec` blocks is read under EACH of
                // its baselines — rows qualify under any of them.
                if sha.nonEmpty && afterSpecHead.currentSpec.nonEmpty then
                  afterSpecHead.map.updated(
                    afterSpecHead.currentSpec,
                    afterSpecHead.map.getOrElse(afterSpecHead.currentSpec, Nil) :+ sha
                  )
                else afterSpecHead.map
              )
            else afterSpecHead
          // in_baseline && /^$/ — an EMPTY line ends the paragraph.
          val afterBlank: BaselineAcc =
            if afterSha.inBaseline && line.isEmpty then afterSha.copy(inBaseline = false)
            else afterSha
          // /^## / && !/^## Spec/ — a non-Spec level-2 heading ends the paragraph.
          if line.startsWith("## ") && !line.startsWith("## Spec") then afterBlank.copy(inBaseline = false)
          else afterBlank
      }
      // EFFECTIVE_BASELINE: the first SHA match in the file (the old
      // single-baseline approach), else the gate's --baseline.
      val effectiveShaRe: scala.util.matching.Regex =
        "(\\*\\*BASELINE SHA\\*\\*: `?|SHA `)([a-f0-9]{7,40})`?".r
      // collectFirst on a total lambda stops at line 1 (the lifted
      // PartialFunction is defined everywhere); flatMap scans for the
      // first MATCHING line — the predecessor's `grep | head -1`.
      val rawEffective: String = lines.view
        .flatMap((l: String) => effectiveShaRe.findFirstMatchIn(l).map(_.group(2)))
        .headOption
        .getOrElse(baselineArg)
      if emitDiagnostics then
        if lines.exists((l: String) => effectiveShaRe.findFirstMatchIn(l).isDefined) then
          SubcommandWiring.emitStderr(
            s"chain-state: using per-spec baseline $rawEffective from implementation-progress.md (gate baseline: $baselineArg)\n"
          )
        else
          SubcommandWiring.emitStderr(
            s"chain-state: no per-spec baseline in implementation-progress.md, falling back to gate baseline $baselineArg\n"
          )
      // Resolve every SHA under the change dir's repo — ledger rows record
      // resolved SHAs (the predecessor's `git rev-parse` with the `||`
      // fallback).
      (
        parsed.map.map((spec, shas) => spec -> shas.map((sha: String) => resolveSha(repo, sha))),
        rawEffective,
        resolveSha(repo, rawEffective)
      )

  /**
   * The ported graph seam — `GraphCmd.exportObligations` in-process,
   * replacing the predecessor's `python3 openspec-graph.py export`
   * subprocess (which remains on disk as the revert target). The graph
   * root follows the predecessor's contract: `OPENSPEC_ROOT`, else the
   * process cwd. Returns the export payload plus the diagnostic lines to
   * emit — a fallback is always announced as degraded, never presented
   * as though the extractor ran.
   */
  private def graphExport(
    change: String,
    env: Map[String, String]
  ): (Option[ujson.Value], List[String]) =
    val degraded: String = "using degraded mode (in-process fallback)"
    val repoRoot: Path = env
      .get("OPENSPEC_ROOT")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(Paths.get("").toAbsolutePath.normalize)
    GraphCmd.exportObligations(repoRoot, change) match
      case Left(reason) =>
        (None, List(s"chain-state: graph unavailable ($reason); $degraded"))
      case Right(payload) =>
        // The `.obligations` usability gate runs BEFORE the path
        // diagnostic — a produced but unusable export takes the degraded
        // path and is announced as such, never as graph.
        if RequirementExtractor.usableExport(payload) then
          (
            Some(payload),
            List("chain-state: fact extraction via traceability graph (in-process)")
          )
        else
          (
            None,
            List(s"chain-state: graph unavailable (export produced an unusable payload); $degraded")
          )

  /** The repository containing `dir` — `git -C <dir> rev-parse --show-toplevel`. */
  private def repoContaining(dir: Path): Option[Path] =
    SubcommandWiring.repoContaining(dir)

  /**
   * `$(cd "$REPO" && git rev-parse <sha> 2>/dev/null || echo <sha>)` —
   * when `repo` is `None` the predecessor's `cd ""` is a no-op, so the
   * resolution still runs in the process cwd (every test fixture relies on
   * this: a short SHA inside the real repo resolves even though the change
   * dir sits in /tmp). On failure git echoes the argument to stdout BEFORE
   * the `||` fallback echoes it again, so an unresolvable baseline becomes
   * `"<sha>\n<sha>"` — a value that matches no ledger row, which is the
   * predecessor's observable behaviour and is reproduced exactly.
   */
  private def resolveSha(repo: Option[Path], sha: String): String =
    val dir: Path = repo.getOrElse(Paths.get("").toAbsolutePath.normalize)
    try
      val pb: ProcessBuilder = new ProcessBuilder("git", "rev-parse", sha)
      pb.directory(dir.toFile)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      if p.waitFor() == 0 then out
      else List(out, sha).filter(_.nonEmpty).mkString("\n")
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a non-git cwd passes the literal through
        sha

  /**
   * The forgive-unchanged oracle — delegated to the shared wiring
   * (`SubcommandWiring.forgivePredicate`) so the record tool's own read
   * path and the checkpoint share one discipline.
   */
  private[cli] def forgivePredicate(ledgerFile: String): (String, String) => Boolean =
    SubcommandWiring.forgivePredicate(ledgerFile)

/**
 * The `graph` subcommand — the ported traceability tool.
 *
 * The adapter's role is exactly the predecessor script's outer shell:
 * read the three source trees (`openspec/concepts/`,
 * `openspec/concept-inventory.md`, `openspec/changes/<change>/specs/<cap>/spec.md`),
 * hand their contents to probatio-core as strings, dispatch the parsed
 * `GraphQuery`, and map the result to the three-way exit protocol. All
 * parsing, binding, reachability, and rendering decisions live in
 * `probatio-core`; file existence is supplied to the core as predicates.
 *
 * `exportObligations` is the seam `ChainStateCmd.graphExport` consumes —
 * the in-process replacement for the predecessor's
 * `python3 openspec-graph.py export --change-dir … --change …`
 * subprocess. It returns `Left(reason)` when a source cannot be read or
 * parsed, which the caller maps to the stated degraded path — the
 * ported tool's own unavailability condition (a missing source, not a
 * missing interpreter).
 *
 * spec: graph-tool-port — Requirement: The tool surface names the five operations
 * spec: graph-tool-port — Requirement: The correctness verdict consumes the graph and states when it cannot
 */
object GraphCmd:

  /** Parses args into a `GraphQuery`, builds the graph, dispatches, exits. */
  def run(args: Array[String]): Outcome[Int] =
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

  /** The env-injecting overload — `OPENSPEC_ROOT` is the predecessor's contract. */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    val repoRoot: Path = env
      .get("OPENSPEC_ROOT")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .getOrElse(Paths.get("").toAbsolutePath.normalize)
    if !Files.isDirectory(repoRoot.resolve("openspec")) then
      val msg: String = s"graph: no openspec/ directory under $repoRoot"
      SubcommandWiring.emitStderr(msg + "\n")
      Outcome.Undetermined(msg)
    else
      parseQuery(args.toList) match
        case Left(msg) =>
          SubcommandWiring.emitStderr(s"graph: $msg\n")
          Outcome.Undetermined(msg)
        case Right(query) =>
          buildGraph(repoRoot) match
            case Left(reason) =>
              SubcommandWiring.emitStderr(s"graph: $reason\n")
              Outcome.Undetermined(reason)
            case Right(build) => dispatch(repoRoot, query, build)

  /** Argument parse — the predecessor's positional ops plus export's flags. */
  private def parseQuery(args: List[String]): Either[String, GraphQuery] =
    args match
      case Nil                         => Right(GraphQuery.Stats)
      case "export" :: rest            => Right(parseExportFlags(rest))
      case "stats" :: _                => Right(GraphQuery.Stats)
      case "impact" :: target :: _     => Right(GraphQuery.Impact(target))
      case "impact" :: Nil             => Left("usage: probatio graph impact <Concept[/action]>")
      case "obligations" :: rest       => Right(GraphQuery.Obligations(rest.headOption))
      case "concept-code" :: name :: _ => Right(GraphQuery.ConceptCode(name))
      case "concept-code" :: Nil       => Left("usage: probatio graph concept-code <Concept>")
      case other :: _ => // danger-scan:allow reject-unknown-op — an unrecognized op is an error, never a default dispatch
        Left(s"unknown operation '$other'")

  /** `export`'s flag scan — `--output F`, `--change-dir D`, `--change C`. */
  private def parseExportFlags(args: List[String]): GraphQuery.Export =
    args match
      case "--output" :: value :: rest =>
        parseExportFlags(rest) match
          case GraphQuery.Export(_, cd, ch) => GraphQuery.Export(Some(value), cd, ch)
      case "--change-dir" :: value :: rest =>
        parseExportFlags(rest) match
          case GraphQuery.Export(o, _, ch) => GraphQuery.Export(o, Some(value), ch)
      case "--change" :: value :: rest =>
        parseExportFlags(rest) match
          case GraphQuery.Export(o, cd, _) => GraphQuery.Export(o, cd, Some(value))
      case _ :: rest => // danger-scan:allow skip-unknown-flag — the predecessor's argparse ignores extras
        parseExportFlags(rest)
      case Nil => GraphQuery.Export(None, None, None)

  private def dispatch(repoRoot: Path, query: GraphQuery, build: GraphBuild): Outcome[Int] =
    val graph: TraceabilityGraph = build.graph
    val artifactResolves: String => Boolean =
      (p: String) => Files.isRegularFile(repoRoot.resolve(p))
    query match
      case GraphQuery.Export(output, changeDir, change) =>
        val payload: ujson.Value =
          (changeDir, change) match
            case (Some(_), Some(c)) => GraphWire.writeChangePayload(graph, build.warnings, c)
            case (Some(_), None)    => GraphWire.writeExport(build)
            case (None, _)          => GraphWire.writeExport(build)
        output match
          case Some(file) =>
            try
              Files.writeString(Paths.get(file), ujson.write(payload, 2) + "\n")
              SubcommandWiring.emitStderr(
                s"probatio graph: ${graph.nodes.length} nodes, ${graph.edges.length} edges -> $file\n"
              )
              Outcome.Ran(0)
            catch
              case NonFatal(e) => // danger-scan:allow io-failure-report — a failed write is could-not-determine
                Outcome.Undetermined(s"graph: could not write $file: ${e.getMessage}")
          case None =>
            SubcommandWiring.emitStdout(ujson.write(payload, 2) + "\n")
            Outcome.Ran(0)
      case GraphQuery.Stats =>
        Outcome.Ran(renderStats(build))
      case GraphQuery.Impact(target) =>
        renderImpact(graph, target)
      case GraphQuery.Obligations(change) =>
        renderObligations(graph, build.warnings, change, artifactResolves)
      case GraphQuery.ConceptCode(name) =>
        renderConceptCode(graph, name)

  // ── renderers — the predecessor's text output, ported ───────────────

  private def renderStats(build: GraphBuild): Int =
    val g: TraceabilityGraph = build.graph
    val kinds: List[(String, Int)] =
      g.nodes.map(GraphNode.kindName).groupBy(identity).map((k, v) => k -> v.length).toList.sorted
    val rels: List[(String, Int)] =
      g.edges.map((e: Edge) => GraphEdge.wireName(e.rel)).groupBy(identity).map((k, v) => k -> v.length).toList.sorted
    SubcommandWiring.emitStdout(
      s"nodes: ${kinds.map((k, v) => s"$k=$v").mkString(", ")} (total ${g.nodes.length})\n" +
        s"edges: ${rels.map((k, v) => s"$k=$v").mkString(", ")} (total ${g.edges.length})\n"
    )
    if build.warnings.nonEmpty then
      SubcommandWiring.emitStdout(s"\nunlinked rows (${build.warnings.length}) — reported, not dropped:\n")
      build.warnings.foreach((w: String) => SubcommandWiring.emitStdout(s"   $w\n"))
    if g.unlinkable.nonEmpty then
      SubcommandWiring.emitStdout(s"\nunlinkable rows (${g.unlinkable.length}):\n")
      g.unlinkable.foreach((r: UnlinkableRow) =>
        SubcommandWiring.emitStdout(s"   ${r.source}:${r.line}: ${r.text} — ${r.reason.text}\n")
      )
    0

  private def renderImpact(graph: TraceabilityGraph, target: String): Outcome[Int] =
    val kind: String = if target.contains("/") then "action" else "concept"
    val nid: String  = s"$kind:$target"
    if graph.node(nid).isEmpty then
      val alts: List[String] =
        graph.nodes
          .map(_.id)
          .filter((n: String) => n.startsWith(s"$kind:$target") || n.endsWith(s"/$target"))
          .take(5)
          .toList
      SubcommandWiring.emitStdout(
        s"impact: unknown $kind '$target'" +
          (if alts.nonEmpty then s"; did you mean: ${alts.mkString(", ")}" else "") + "\n"
      )
      Outcome.Finding(s"impact: unknown $kind '$target'")
    else
      SubcommandWiring.emitStdout(s"impact: $target\n")
      val related: Set[String] =
        if kind == "concept" then
          val syncs: List[String] = graph.outgoing(nid, Some(GraphEdge.DefinesSync)).map(_.to)
          val code: List[String]  = graph.outgoing(nid, Some(GraphEdge.ImplementedBy)).map(_.to)
          if syncs.nonEmpty then
            SubcommandWiring.emitStdout(
              s"  syncs defined: ${syncs.map(_.split(":", 2).lift(1).getOrElse("")).mkString(", ")}\n"
            )
          if code.nonEmpty then SubcommandWiring.emitStdout(s"  implementation map binds ${code.length} file(s)\n")
          Set(nid) ++ graph.outgoing(nid, Some(GraphEdge.Declares)).map(_.to).toSet
        else Set(nid)
      val citing: List[String] =
        related.toList.flatMap((r: String) => graph.incoming(r, Some(GraphEdge.Cites)).map(_.from)).distinct.sorted
      if citing.isEmpty then
        SubcommandWiring.emitStdout("  no ACTIVE spec cites this concept — safe from the spec side\n")
        Outcome.Ran(0)
      else
        val perSpec: List[(String, Boolean, List[(String, List[Edge], Set[String])])] =
          citing.map { (sid: String) =>
            val planned: Boolean = related.exists((r: String) =>
              graph.incoming(r, Some(GraphEdge.Cites)).exists((e: Edge) => e.from == sid && e.planned.contains(true))
            )
            val rows: List[(String, List[Edge], Set[String])] =
              graph.outgoing(sid, Some(GraphEdge.HasRequirement)).flatMap { (e: Edge) =>
                graph.node(e.to) match
                  case Some(req: GraphNode.Requirement) =>
                    val obls: List[Edge] = graph.outgoing(e.to, Some(GraphEdge.EnforcedBy))
                    val arts: Set[String] =
                      obls
                        .flatMap((o: Edge) => graph.outgoing(o.to, Some(GraphEdge.VerifiedBy)))
                        .map((a: Edge) => a.to.split(":", 2).lift(1).getOrElse(""))
                        .toSet
                    List((s"R${req.ordinal}: ${req.title.take(72)}", obls, arts))
                  case _ => // danger-scan:allow non-req-target — has-req targets are requirement nodes by construction
                    Nil
              }
            (sid, planned, rows)
          }
        perSpec.foreach { (sid: String, planned: Boolean, rows: List[(String, List[Edge], Set[String])]) =>
          SubcommandWiring.emitStdout(
            s"\n  ${sid.split(":", 2).lift(1).getOrElse(sid)}${if planned then "  [cites as NEW/created]" else ""}\n"
          )
          rows.foreach { (title: String, obls: List[Edge], arts: Set[String]) =>
            SubcommandWiring.emitStdout(s"    ${if obls.nonEmpty then "✓" else "✗"} $title\n")
            if obls.nonEmpty then
              SubcommandWiring.emitStdout(
                s"        ${obls.length} obligation(s) -> ${arts.toList.sorted.take(4).mkString(", ")}\n"
              )
          }
        }
        val allRows: List[(String, String, List[Edge], Set[String])] =
          perSpec.flatMap { (sid: String, _: Boolean, rows: List[(String, List[Edge], Set[String])]) =>
            rows.map { (title: String, obls: List[Edge], arts: Set[String]) =>
              (sid.split(":", 2).lift(1).getOrElse(sid), title, obls, arts)
            }
          }
        val unenforced: List[String] =
          allRows
            .filter((_: String, _: String, obls: List[Edge], _: Set[String]) => obls.isEmpty)
            .map((spec: String, title: String, _: List[Edge], _: Set[String]) =>
              s"$spec ${title.takeWhile((_: Char) != ':')}"
            )
        SubcommandWiring.emitStdout(
          s"\n  reach: ${citing.length} spec(s), ${allRows.length} requirement(s), " +
            s"${allRows.map((_: String, _: String, o: List[Edge], _: Set[String]) => o.length).sum} obligation(s), " +
            s"${allRows.flatMap((_: String, _: String, _: List[Edge], a: Set[String]) => a).toSet.size} enforcing artifact(s)\n"
        )
        if unenforced.nonEmpty then
          SubcommandWiring.emitStdout(
            s"  UNENFORCED requirements (${unenforced.length}): ${unenforced.mkString(", ")}\n"
          )
        Outcome.Ran(0)

  private def renderObligations(
    graph: TraceabilityGraph,
    warnings: List[String],
    change: Option[String],
    artifactResolves: String => Boolean
  ): Outcome[Int] =
    GraphAudit.audit(graph, change, artifactResolves) match
      case None =>
        val msg: String =
          s"obligations: no active spec found${change.fold("")((c: String) => s" for $c")}"
        SubcommandWiring.emitStdout(msg + "\n")
        Outcome.Finding(msg)
      case Some(result) =>
        val specs: List[GraphNode.Spec] =
          graph.nodes
            .collect { case s: GraphNode.Spec => s }
            .filter((s: GraphNode.Spec) => change.forall((c: String) => s.change == c))
            .toList
            .sortBy(_.id)
        specs.foreach { (s: GraphNode.Spec) =>
          val sid: String      = s.id
          val reqs: List[Edge] = graph.outgoing(sid, Some(GraphEdge.HasRequirement))
          val specKey: String  = s"${s.change}/${s.capability}"
          val allObls: List[GraphNode.Obligation] =
            graph.obligations.filter((o: GraphNode.Obligation) => o.spec == specKey)
          val artCount: Int =
            allObls
              .flatMap((o: GraphNode.Obligation) => graph.outgoing(o.id, Some(GraphEdge.VerifiedBy)))
              .map(_.to)
              .distinct
              .length
          SubcommandWiring.emitStdout(s"\n$specKey\n")
          val links: List[(String, Int)] =
            reqs
              .flatMap((e: Edge) => graph.outgoing(e.to, Some(GraphEdge.EnforcedBy)))
              .flatMap(_.link)
              .map(ObligationLink.wireName)
              .groupBy(identity)
              .map((k, v) => k -> v.length)
              .toList
              .sorted
          SubcommandWiring.emitStdout(
            s"  requirements: ${reqs.length}   obligations: ${allObls.length}   artifacts: $artCount\n"
          )
          SubcommandWiring.emitStdout(
            s"  obligation links: ${if links.isEmpty then "(none)" else links.map((k, v) => s"$k=$v").mkString(", ")}\n"
          )
          val kindSourced: List[ObligationSourceKind] = allObls.flatMap(_.sourceKind)
          if kindSourced.nonEmpty then
            val kinds: List[(String, Int)] =
              kindSourced
                .map(ObligationSourceKind.wireName)
                .groupBy(identity)
                .map((k, v) => k -> v.length)
                .toList
                .sorted
            SubcommandWiring.emitStdout(
              s"  non-requirement sources (legitimate): ${kinds.map((k, v) => s"$k=$v").mkString(", ")}\n"
            )
          links.find((k, _) => k == "inferred").foreach { (_, v) =>
            SubcommandWiring.emitStdout(
              s"  ⚠ $v link(s) INFERRED by title overlap — the Source cell names " +
                "no requirement; make it 'Requirement N' or quote the title\n"
            )
          }
        }
        if result.unenforcedRequirements.nonEmpty then
          SubcommandWiring.emitStdout("  ✗ UNENFORCED requirement(s) — no obligation cites them:\n")
          result.unenforcedRequirements.foreach((r: GraphNode.Requirement) =>
            SubcommandWiring.emitStdout(s"      R${r.ordinal}: ${r.title.take(70)}\n")
          )
        if result.artifactlessObligations.nonEmpty then
          SubcommandWiring.emitStdout(
            s"  ⚠ ${result.artifactlessObligations.length} obligation(s) reach no resolving artifact\n"
          )
        if warnings.nonEmpty then
          SubcommandWiring.emitStdout(s"\nunlinked rows (${warnings.length}):\n")
          warnings.foreach((w: String) => SubcommandWiring.emitStdout(s"   $w\n"))
        val verdict: String =
          if result.unenforcedRequirements.nonEmpty then
            s"FAIL — ${result.unenforcedRequirements.length} requirement(s) reach no enforcing artifact"
          else "PASS — every requirement reaches an artifact"
        SubcommandWiring.emitStdout(s"\nverdict: $verdict\n")
        if result.unenforcedRequirements.nonEmpty then Outcome.Finding(verdict)
        else Outcome.Ran(0)

  private def renderConceptCode(graph: TraceabilityGraph, name: String): Outcome[Int] =
    val nid: String = s"concept:$name"
    if graph.node(nid).isEmpty then
      SubcommandWiring.emitStdout(s"concept-code: unknown concept '$name'\n")
      Outcome.Finding(s"concept-code: unknown concept '$name'")
    else
      val files: List[String] =
        graph.outgoing(nid, Some(GraphEdge.ImplementedBy)).map(_.to.split(":", 2).lift(1).getOrElse("")).sorted.distinct
      val actions: List[String] =
        graph.outgoing(nid, Some(GraphEdge.Declares)).map(_.to.split("/", 2).lift(1).getOrElse("")).sorted.distinct
      SubcommandWiring.emitStdout(s"$name: ${actions.length} action(s), ${files.length} bound file(s)\n")
      actions.foreach((a: String) => SubcommandWiring.emitStdout(s"  action $a\n"))
      files.foreach((f: String) => SubcommandWiring.emitStdout(s"  code   $f\n"))
      Outcome.Ran(0)

  // ── source reads — the adapter's half of the build ──────────────────

  /**
   * Builds the graph from the repository rooted at `repoRoot` — reads
   * all three sources, returns `Left(reason)` naming the unreadable
   * source when one cannot be read (the could-not-determine path; no
   * partial graph is emitted).
   */
  private[cli] def buildGraph(repoRoot: Path): Either[String, GraphBuild] =
    val openspec: Path = repoRoot.resolve("openspec")
    for
      concepts  <- readConcepts(openspec.resolve("concepts"))
      inventory <- readInventory(openspec.resolve("concept-inventory.md"))
      specs     <- readSpecs(openspec.resolve("changes"))
    yield
      val (docs: List[ConceptRegistryDoc], parseWarnings: List[String]) = concepts
      val build: GraphBuild = TraceabilityGraph.build(
        docs,
        inventory,
        specs,
        (p: String) => Files.isRegularFile(repoRoot.resolve(p)),
        (p: String) => Files.isRegularFile(repoRoot.resolve(p))
      )
      build.copy(warnings = parseWarnings ++ build.warnings)

  private def readText(path: Path): Either[String, String] =
    try Right(Files.readString(path, StandardCharsets.UTF_8))
    catch
      case NonFatal(e) => // danger-scan:allow unreadable-source — named in the reason
        Left(s"could not read $path: ${e.getMessage}")

  /** `openspec/concepts/` — every `*.md` except README, sorted; parse warnings ride along. */
  private def readConcepts(dir: Path): Either[String, (List[ConceptRegistryDoc], List[String])] =
    if !Files.isDirectory(dir) then Left(s"could not read openspec/concepts (missing or not a directory)")
    else
      try
        val files: List[Path] =
          Using.resource(Files.list(dir)) { stream =>
            stream
              .iterator()
              .asScala
              .toList
              .filter((p: Path) =>
                Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md") &&
                  p.getFileName.toString != "README.md"
              )
              .sortBy(_.getFileName.toString)
          }
        files.foldLeft[Either[String, (List[ConceptRegistryDoc], List[String])]](Right((Nil, Nil))) { (acc, path) =>
          acc.flatMap { case (docs, warns) =>
            readText(path).flatMap { (text: String) =>
              val rel: String = s"openspec/concepts/${path.getFileName}"
              ConceptRegistryDoc.parse(rel, text) match
                case Left(w)    => Right((docs, warns :+ w))
                case Right(doc) => Right((docs :+ doc, warns))
            }
          }
        }
      catch
        case NonFatal(e) => // danger-scan:allow unreadable-source — named in the reason
          Left(
            s"could not list openspec/concepts: ${e.getMessage}"
          )

  /** `openspec/concept-inventory.md` — must exist. */
  private def readInventory(path: Path): Either[String, InventoryDoc] =
    if !Files.isRegularFile(path) then Left("could not read openspec/concept-inventory.md")
    else readText(path).map(InventoryDoc.parse("openspec/concept-inventory.md", _))

  /**
   * `openspec/changes/<change>/specs/<cap>/spec.md`, `archive` excluded —
   * the predecessor's sorted directory walk.
   */
  private def readSpecs(changesDir: Path): Either[String, List[SpecGraphDoc]] =
    if !Files.isDirectory(changesDir) then Left("could not read openspec/changes (missing or not a directory)")
    else
      try
        val changes: List[Path] =
          Using.resource(Files.list(changesDir)) { stream =>
            stream
              .iterator()
              .asScala
              .toList
              .filter((p: Path) => Files.isDirectory(p) && p.getFileName.toString != "archive")
              .sortBy(_.getFileName.toString)
          }
        changes.foldLeft[Either[String, List[SpecGraphDoc]]](Right(Nil)) { (acc, changeDir) =>
          acc.flatMap { (docs: List[SpecGraphDoc]) =>
            val specsDir: Path = changeDir.resolve("specs")
            if !Files.isDirectory(specsDir) then Right(docs)
            else
              val caps: List[Path] =
                Using.resource(Files.list(specsDir)) { stream =>
                  stream
                    .iterator()
                    .asScala
                    .toList
                    .filter(Files.isDirectory(_))
                    .sortBy(_.getFileName.toString)
                }
              caps.foldLeft[Either[String, List[SpecGraphDoc]]](Right(docs)) { (acc2, capDir) =>
                acc2.flatMap { (ds: List[SpecGraphDoc]) =>
                  val specFile: Path = capDir.resolve("spec.md")
                  if !Files.isRegularFile(specFile) then Right(ds)
                  else
                    readText(specFile).map { (text: String) =>
                      val change: String = changeDir.getFileName.toString
                      val cap: String    = capDir.getFileName.toString
                      ds :+ SpecGraphDoc.parse(
                        change,
                        cap,
                        s"openspec/changes/$change/specs/$cap/spec.md",
                        text
                      )
                    }
                }
              }
          }
        }
      catch
        case NonFatal(e) => // danger-scan:allow unreadable-source — named in the reason
          Left(
            s"could not list openspec/changes: ${e.getMessage}"
          )

  /**
   * The per-change obligations payload for the chain-state seam —
   * `GraphWire.writeChangePayload` over the built graph. `Left` names
   * why the graph could not be produced.
   */
  private[cli] def exportObligations(
    repoRoot: Path,
    change: String
  ): Either[String, ujson.Value] =
    buildGraph(repoRoot).map((b: GraphBuild) => GraphWire.writeChangePayload(b.graph, b.warnings, change))

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
          SubcommandWiring.emitStderr(s"ledger: the ledger is append-only; '$actionStr' is not a subcommand.\n")
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

/** The `reconcile` subcommand — obligation reconciliation. */
object ReconcileCmd:

  /**
   * The predecessor's five value-flags: `--file`, `--change`, `--spec`,
   * `--baseline`, `--format`. Any other token is an unrecognised
   * argument — never skipped (a typo'd `--baseline` would silently widen
   * the scope and return a clean result over rows the caller never
   * meant to include).
   */
  private val knownFlags: Set[String] =
    Set("--file", "--change", "--spec", "--baseline", "--format")

  /**
   * Wire the reconcile subcommand to ReconcileEngine.classify.
   *
   * Reads the ledger through `SubcommandWiring.readLedgerFile` +
   * `Ledger.readValidated` (every row validated before any conclusion —
   * a malformed row makes the whole file undetermined, not skipped),
   * classifies the in-scope records, emits the predecessor's text or
   * JSON report, and maps the three-way outcome:
   * 0 = every claim witnessed; 1 = testimony or contradicted;
   * 2 = could not determine.
   *
   * spec: cli-wiring — Requirement: The ledger subcommand wires to the 15-clause validator and emits byte-compatible stdout
   * spec: danger-reconcile-engines — Scenario: Error path — an unreadable record set is could-not-determine
   */
  def run(args: Array[String]): Outcome[Int] =
    SubcommandWiring.parseArgs(args, knownFlags) match
      case Left(err) =>
        finding(errorMessage(err))
      case Right(parsed) =>
        val file: Option[String]   = parsed.get("--file").filter(_.nonEmpty)
        val change: Option[String] = parsed.get("--change").filter(_.nonEmpty)
        val format: Option[String] = parsed.get("--format")
        (file, change, format) match
          case (None, _, _) => finding("--file is required")
          case (_, None, _) => finding("--change is required")
          case (_, _, Some(f)) if f != "json" && f != "text" =>
            finding("--format must be json or text")
          case (Some(f), Some(c), fmt) =>
            reconcile(
              f,
              c,
              parsed.get("--spec").filter(_.nonEmpty),
              parsed.get("--baseline").filter(_.nonEmpty),
              formatJson = fmt.contains("json")
            )

  /** The predecessor's `die_finding`: `reconcile: <msg>` on stderr, exit 1. */
  private def finding(message: String): Outcome[Int] =
    SubcommandWiring.emitStderr(s"reconcile: $message\n")
    Outcome.Finding(message)

  /** The predecessor's `die_undetermined`: `reconcile: UNDETERMINED — <msg>` on stderr, exit 2. */
  private def undetermined(reason: String): Outcome[Int] =
    SubcommandWiring.emitStderr(s"reconcile: UNDETERMINED — $reason\n")
    Outcome.Undetermined(reason)

  /** Map a parse error to the predecessor's message text. */
  private def errorMessage(err: CliError): String = err match
    case CliError.MissingValue(flag)       => s"$flag requires a value"
    case CliError.UnknownFlag(token)       => s"unrecognised argument: $token"
    case CliError.UnknownSubcommand(token) => s"unrecognised argument: $token"
    case CliError.InvalidEnum(flag, value) => s"invalid value '$value' for $flag"
    case CliError.ForbiddenFlag(flag)      => s"flag not accepted here: $flag"

  /** Read, validate, classify, and emit — the predecessor's main body. */
  private def reconcile(
    file: String,
    change: String,
    spec: Option[String],
    baseline: Option[String],
    formatJson: Boolean
  ): Outcome[Int] =
    SubcommandWiring.readLedgerFile(file) match
      case Outcome.Undetermined(reason) => undetermined(reason)
      case Outcome.Finding(msg) =>
        Outcome.Finding(
          msg
        ) // danger-scan:allow unreachable-branch — readLedgerFile never yields Finding; passthrough keeps the direction honest
      case Outcome.Ran(rows) =>
        if rows.isEmpty then undetermined(s"$file holds no records")
        else
          Ledger.readValidated(rows) match
            case Left(err) => undetermined(err.description)
            case Right(records) =>
              val report: ReconcileReport =
                ReconcileEngine.classify(records, change, spec, baseline)
              if formatJson then SubcommandWiring.emitStdout(StdoutRenderer.reconcileJson(report) + "\n")
              else SubcommandWiring.emitStdout(StdoutRenderer[ReconcileReport].render(report) + "\n")
              if report.hasFindings then
                Outcome.Finding(
                  s"${report.testimony.length} testimony, ${report.contradicted.length} contradicted"
                )
              else Outcome.Ran(0)

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

/** The `install-skills` subcommand — skill installation. */
object InstallSkillsCmd:

  /**
   * `command -v <name>` — an executable regular file named `name` under
   * some `PATH` entry (an empty entry means the current directory, as the
   * shell reads it). The adapter's presence check, injected into
   * `probePrerequisites`; the oracle controls `PATH` through the env seam.
   */
  private[cli] def onPath(name: String, pathEnv: String): Boolean =
    pathEnv.split(":", -1).toList.exists { (entry: String) =>
      val dir: String = if entry.isEmpty then "." else entry
      val p: Path     = Paths.get(dir).resolve(name)
      Files.isRegularFile(p) && Files.isExecutable(p)
    }

  /**
   * The prerequisite probe — the predecessor's `--check-installed` mode.
   * Pure over an injected presence check: the adapter supplies
   * `command -v <name>`; a prerequisite is reported present only when the
   * check finds it, and every declared prerequisite is probed, so the
   * report names each one it checked.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer probes the declared prerequisite set
   */
  private[cli] def probePrerequisites(
    present: String => Boolean
  ): PrerequisiteReport =
    PrerequisiteReport(
      InstallSurface.declaredPrerequisites.map((n: String) => PrerequisiteProbe(n, present(n)))
    )

  /**
   * The declared agent directories under `projectRoot` that do NOT hold
   * every skill in `skillNames` — the coverage check that makes a
   * single-directory install report the directories it skipped.
   *
   * spec: install-tool-surface-parity — Scenario: Adversarial — installing into a single directory is not the whole install
   */
  private[cli] def unwrittenAgentDirs(
    projectRoot: Path,
    skillNames: List[String]
  ): List[String] =
    InstallSurface.skillAgentDirs.filter { (d: String) =>
      skillNames.exists((s: String) => !Files.isRegularFile(projectRoot.resolve(d).resolve(s).resolve("SKILL.md")))
    }

  /**
   * The `skills/<name>/` source directories, sorted — the predecessor's
   * glob order. The predecessor's per-child directory glob never matches
   * dot-directories, so a `.git/` under `skills/` is not a skill.
   */
  private def skillDirs(skillsSource: Path): List[Path] =
    Using.resource(Files.list(skillsSource)) { (stream: java.util.stream.Stream[Path]) =>
      stream
        .iterator()
        .asScala
        .toList
        .filter(Files.isDirectory(_))
        .filter((p: Path) => !p.getFileName.toString.startsWith("."))
        .sortBy((p: Path) => p.getFileName.toString)
    }

  /** One agent directory: every skill's `SKILL.md` copied in, each install reported. */
  private def installIntoDir(skills: List[Path], target: Path): Int =
    Files.createDirectories(target)
    skills.foreach { (src: Path) =>
      val name: String = src.getFileName.toString
      val dst: Path    = target.resolve(name)
      Files.createDirectories(dst)
      val file: Path = dst.resolve("SKILL.md")
      Files.copy(src.resolve("SKILL.md"), file, StandardCopyOption.REPLACE_EXISTING)
      SubcommandWiring.emitStdout(s"installed $name -> $file\n")
    }
    skills.length

  /**
   * The full skill install — every skill document under `skillsSource`
   * copied into EVERY declared agent directory under `projectRoot`
   * (`InstallSurface.skillAgentDirs`), never a subset. An agent directory
   * that cannot be written is could-not-determine naming that directory;
   * a missing skills source is the predecessor's `no skills directory`
   * finding. The first failure stops the install — the predecessor's
   * `set -e` never writes a later directory after an earlier one failed.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer writes to every declared agent directory
   */
  private[cli] def installEverywhere(
    skillsSource: Path,
    projectRoot: Path
  ): Outcome[Int] =
    if !Files.isDirectory(skillsSource) then
      SubcommandWiring.emitStderr(s"install-skills: no skills directory at $skillsSource\n")
      Outcome.Finding(s"no skills directory at $skillsSource")
    else
      val skills: List[Path] = skillDirs(skillsSource)
      // Both shapes are the predecessor's exit-1 `cp` failure: an empty
      // skills/ iterates the literal `*/` glob and dies, and a skill
      // directory without SKILL.md dies on the copy — never a clean run,
      // never could-not-determine.
      val blocker: Option[String] =
        if skills.isEmpty then Some(s"no skills under $skillsSource")
        else
          skills
            .find((s: Path) => !Files.isRegularFile(s.resolve("SKILL.md")))
            .map((s: Path) => s"no SKILL.md in $s")
      blocker match
        case Some(reason) =>
          SubcommandWiring.emitStderr(s"install-skills: $reason\n")
          Outcome.Finding(reason)
        case None =>
          InstallSurface.skillAgentDirs
            .foldLeft[Either[String, Int]](Right(0)) { (acc: Either[String, Int], d: String) =>
              acc.flatMap { (n: Int) =>
                try Right(n + installIntoDir(skills, projectRoot.resolve(d)))
                catch
                  case e: java.io.IOException =>
                    Left(s"cannot write $d under $projectRoot: ${e.getMessage}")
              }
            } match
            case Left(reason) => Outcome.Undetermined(reason)
            case Right(count) =>
              SubcommandWiring.emitStdout(s"install-skills: $count skill copy(ies) installed.\n")
              SubcommandWiring.emitStdout(
                "NOTE: if an agent directory (.claude/, .pi/, .devin/) is git-ignored in\n" +
                  "this repo (check 'git check-ignore <dir>/skills' and your user-global\n" +
                  "ignore file), the installed copies are local-only by design — the\n" +
                  "schema's skills/ directory remains the shared source of truth.\n"
              )
              Outcome.Ran(0)

  /** `--check-installed` — probe the declared prerequisite set on `PATH`. */
  private def runProbe(env: Map[String, String]): Outcome[Int] =
    val report: PrerequisiteReport =
      probePrerequisites((n: String) => onPath(n, env.getOrElse("PATH", "")))
    report.probes.foreach { (p: PrerequisiteProbe) =>
      if p.present then SubcommandWiring.emitStdout(s"install-skills: ${p.name} — present\n")
      else SubcommandWiring.emitStderr(s"install-skills: ${p.name} — MISSING\n")
    }
    if report.allPresent then
      SubcommandWiring.emitStdout("install-skills: all prerequisites present\n")
      Outcome.Ran(0)
    else
      SubcommandWiring.emitStderr(s"install-skills: ${report.missingCount} prerequisite(s) missing\n")
      Outcome.Finding(s"${report.missingCount} prerequisite(s) missing")

  /**
   * The full install — the schema's `skills/` resolved like the
   * predecessor's `$SCRIPT_DIR/../skills`: relative to the TARGET
   * project, so `install-skills <root>` works from an unrelated cwd.
   */
  private def runInstall(projectRoot: Path, env: Map[String, String]): Outcome[Int] =
    val schemaDir: Path = SubcommandWiring.schemaDirOf(env, projectRoot)
    installEverywhere(schemaDir.resolve("skills"), projectRoot)

  /**
   * The env-injecting overload — the seam the test oracle drives.
   *
   * `PATH` feeds the prerequisite probe (`command -v` parity);
   * `PROBATIO_SCHEMA_DIR` locates the schema tree the skill documents
   * come from (the binary lives at `<schema>/bin/probatio`, as the
   * predecessor resolves `$SCRIPT_DIR/../skills`); `PWD` supplies the
   * `.` default for the positional project root.
   *
   * The predecessor reads ONLY `$1` — every first token is a project
   * root, extra arguments are ignored, and a dash-token dies inside
   * `mkdir -p` as an invalid option (exit 1), so the ported surface
   * rejects flag-shaped roots as findings rather than inventing flags.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    args.toList match
      case "--check-installed" :: _ => runProbe(env)
      case head :: _ if head.startsWith("-") =>
        SubcommandWiring.emitStderr(s"install-skills: mkdir: invalid option -- '$head'\n")
        Outcome.Finding(s"invalid option: $head")
      case "" :: _ =>
        // `install-skills.sh ""` → `mkdir -p /.claude/skills` fails — exit 1.
        Outcome.Finding("empty project root")
      case root :: _ =>
        runInstall(Path.of(root), env)
      case Nil =>
        runInstall(Path.of(env.getOrElse("PWD", ".")), env)

  /**
   * Wire the install-skills subcommand to the predecessor's surface:
   * `install-skills [project-root]` installs every skill into every
   * declared agent directory; `install-skills --check-installed` probes
   * the declared prerequisite set. The `--dir` surface is removed — the
   * predecessor rejects it, and surface parity is the swap precondition.
   *
   * spec: install-tool-surface-parity — Requirement: The skill installer probes the declared prerequisite set
   * spec: install-tool-surface-parity — Requirement: The skill installer writes to every declared agent directory
   * spec: install-tool-surface-parity — Property: surface-parity-with-the-predecessor
   */
  def run(args: Array[String]): Outcome[Int] =
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

/** The `install-hooks` subcommand — hook installation. */
object InstallHooksCmd:

  /**
   * The hook install — `mode` is a required parameter with NO default,
   * so a write-by-default invocation is unconstructible: `DryRun` reports
   * each file it would write and writes nothing; `Apply` writes and
   * reports each one. `target` selects the harnesses wired — a named
   * harness alone, or every harness already present. An existing
   * configuration the installer did not generate is refused, named with
   * its reason.
   *
   * spec: install-tool-surface-parity — Requirement: The hook installer reports before it writes
   * spec: install-tool-surface-parity — Requirement: The hook installer selects harnesses and refuses to clobber
   * spec: install-tool-surface-parity — Compile-Negative: An install mode defaulting to write
   */
  private[cli] def runInstaller(
    adaptersSource: Path,
    projectRoot: Path,
    target: InstallTarget,
    mode: InstallMode
  ): Outcome[Int] =
    val harnesses: List[String] = target match
      case InstallTarget.AllPresentHarnesses =>
        InstallSurface.harnessMarkerDirs.collect {
          case (h: String, d: String) if Files.isDirectory(projectRoot.resolve(d)) => h
        }
      // The predecessor's `for a in $AGENT` word-splits the value —
      // `--agent "claude pi"` wires both, and an unrecognized WORD is the
      // unknown-agent diagnostic (including "all" in multi-word position).
      case InstallTarget.NamedHarness(name) =>
        name.split("\\s+").toList.filter((w: String) => w.nonEmpty)

    harnesses match
      case Nil =>
        // The predecessor's all-present default on a project with no
        // harness directories: a message, not an error.
        SubcommandWiring.emitStdout(
          s"install-hooks: no .claude/, .pi/ or .devin/ in $projectRoot — pass --agent explicitly\n"
        )
        Outcome.Ran(0)
      case _ => // danger-scan:allow non-empty-list — every remaining shape is a non-empty harness list to wire
        SubcommandWiring.emitStdout(s"install-hooks: project $projectRoot\n")
        SubcommandWiring.emitStdout(s"install-hooks: agents  ${harnesses.mkString(" ")}\n")
        if mode == InstallMode.DryRun then
          SubcommandWiring.emitStdout("install-hooks: DRY RUN — re-run with --apply to write\n")
        val failure: Option[String] =
          harnesses.foldLeft(Option.empty[String]) { (acc: Option[String], h: String) =>
            // `set -e` parity: the first failure ends the run.
            acc.orElse(wireHarness(adaptersSource, projectRoot, h, mode))
          }
        failure match
          case Some(reason) => Outcome.Finding(reason)
          case None =>
            SubcommandWiring.emitStdout("\ninstall-hooks: verify with\n")
            SubcommandWiring.emitStdout(
              s"  bash ${adaptersSource.getParent.resolve("gate.sh")} --event session-start --format text --repo $projectRoot\n"
            )
            SubcommandWiring.emitStdout(
              "install-hooks: disable at any time with  export VERIFIED_SCALA3_HOOKS=off\n"
            )
            Outcome.Ran(0)

  /**
   * One harness's plan-or-write — the failure reason when the wiring
   * could not complete, `None` otherwise. Unknown-agent diagnoses and
   * no-clobber skips are `None` too: the predecessor reports them and
   * still exits 0.
   */
  private def wireHarness(
    adaptersSource: Path,
    projectRoot: Path,
    harness: String,
    mode: InstallMode
  ): Option[String] =
    harness match
      case "pi" =>
        copyAdapter(
          projectRoot.resolve(".pi/extensions/verified-scala3-gate.ts"),
          adaptersSource.resolve("pi/verified-scala3-gate.ts"),
          mode
        )
      case "devin" =>
        val dst: Path = projectRoot.resolve(".devin/hooks.v1.json")
        if Files.isRegularFile(dst) then
          SubcommandWiring.emitStdout(s"  SKIP   $dst already exists — merge the SessionStart entry from\n")
          SubcommandWiring.emitStdout(
            s"         ${adaptersSource.resolve("devin.hooks.v1.json")} by hand (refusing to clobber)\n"
          )
          None
        else copyAdapter(dst, adaptersSource.resolve("devin.hooks.v1.json"), mode)
      case "claude" =>
        val dst: Path = projectRoot.resolve(".claude/settings.json")
        mode match
          case InstallMode.DryRun =>
            SubcommandWiring.emitStdout(s"  would merge   $dst (SessionStart hook)\n")
            None
          case InstallMode.Apply =>
            SubcommandWiring.emitStdout(s"  MERGE  $dst\n")
            mergeClaudeSettings(dst, adaptersSource.resolve("claude.settings.json"))
      case other => // danger-scan:allow unknown-agent-diagnosis — unrecognized harness names are reported and skipped, never mapped to a valid harness
        SubcommandWiring.emitStderr(s"  unknown agent: $other\n")
        None

  /** A copy-write adapter (pi, devin): `would write` under dry-run, `WROTE` under apply. */
  private def copyAdapter(dst: Path, src: Path, mode: InstallMode): Option[String] =
    mode match
      case InstallMode.DryRun =>
        SubcommandWiring.emitStdout(s"  would write  $dst\n")
        None
      case InstallMode.Apply =>
        try
          Option(dst.getParent).foreach((p: Path) => Files.createDirectories(p))
          Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
          SubcommandWiring.emitStdout(s"  WROTE  $dst\n")
          None
        catch
          case e: java.io.IOException =>
            Some(s"could not write $dst: ${e.getMessage}")

  /**
   * The predecessor's python merge, in-process: every event the adapter
   * declares, idempotent per hook `command`, the file rewritten only
   * when something was added. `Some(reason)` when the merge cannot
   * proceed — an existing settings file that does not parse is the
   * predecessor's exit-1 finding, never a silent overwrite.
   */
  private def mergeClaudeSettings(dst: Path, fragPath: Path): Option[String] =
    val parsed: Either[String, ujson.Obj] =
      if !Files.isRegularFile(dst) then Right(ujson.Obj())
      else
        try
          ujson.read(Files.readString(dst, StandardCharsets.UTF_8)) match
            case o: ujson.Obj => Right(o)
            case _ => // danger-scan:allow typed-rejection — a non-object settings file refuses the merge, never overwrites
              Left(s"install-hooks: $dst is not a JSON object — fix it or merge by hand")
        catch
          case NonFatal(_) => // danger-scan:allow typed-catch — bad JSON maps to the predecessor's exit-1 refusal
            Left(s"install-hooks: $dst is not valid JSON — fix it or merge by hand")
    parsed match
      case Left(reason) => Some(reason)
      case Right(cur) =>
        try
          mergeAdapterHooks(cur, fragPath) match
            case Left(reason) => Some(reason)
            case Right((added, skipped)) =>
              if added.nonEmpty then
                Option(dst.getParent).foreach((p: Path) => Files.createDirectories(p))
                Files.writeString(dst, ujson.write(cur, indent = 2) + "\n", StandardCharsets.UTF_8)
              added.foreach((a: String) => SubcommandWiring.emitStdout(s"  + $a\n"))
              skipped.foreach((s: String) => SubcommandWiring.emitStdout(s"  (already present — $s)\n"))
              None
        catch
          case NonFatal(e) => // danger-scan:allow named-failure — merge failure is the exit-1 finding
            Some(s"could not merge $dst: ${e.getMessage}")

  /**
   * Merge `adapters/claude.settings.json`'s `hooks` into `cur` — every
   * adapter-declared event, one entry per hook command, deduplicated on
   * `command` so re-running never appends a duplicate. Returns the
   * (added, skipped) report labels.
   */
  private def mergeAdapterHooks(
    cur: ujson.Obj,
    fragPath: Path
  ): Either[String, (List[String], List[String])] =
    val frag: ujson.Value =
      ujson.read(Files.readString(fragPath, StandardCharsets.UTF_8))
    val hooksE: Either[String, ujson.Obj] = cur.obj.get("hooks") match
      case None =>
        val h: ujson.Obj = ujson.Obj()
        cur("hooks") = h
        Right(h)
      case Some(o: ujson.Obj) => Right(o)
      case Some(_) => // danger-scan:allow non-object-hooks — refuses the merge, never overwrites
        Left("settings.json 'hooks' is not an object — merge by hand")
    val fragHooksE: Either[String, ujson.Obj] = frag.obj.get("hooks") match
      case Some(o: ujson.Obj) => Right(o)
      case _ => // danger-scan:allow typed-rejection — a fragment without a hooks object refuses, never writes
        Left(s"adapter fragment $fragPath has no 'hooks' object")
    for
      hooks     <- hooksE
      fragHooks <- fragHooksE
      result <- fragHooks.obj
        .foldLeft[Either[String, (List[String], List[String])]](Right((Nil, Nil))) {
          (acc: Either[String, (List[String], List[String])], kv: (String, ujson.Value)) =>
            acc.flatMap { case (added: List[String], skipped: List[String]) =>
              mergeEvent(hooks, kv._1, kv._2).map { case (a: List[String], s: List[String]) =>
                (added ++ a, skipped ++ s)
              }
            }
        }
    yield result

  /** One adapter-declared event merged into the settings' `hooks` object. */
  private def mergeEvent(
    hooks: ujson.Obj,
    event: String,
    fragEntries: ujson.Value
  ): Either[String, (List[String], List[String])] =
    val entriesE: Either[String, ujson.Arr] = hooks.obj.get(event) match
      case None =>
        val a: ujson.Arr = ujson.Arr()
        hooks(event) = a
        Right(a)
      case Some(a: ujson.Arr) => Right(a)
      case Some(_) => // danger-scan:allow typed-rejection — a non-array event entry refuses the merge, never overwrites
        Left(s"settings.json 'hooks.$event' is not an array — merge by hand")
    for
      entries <- entriesE
      fragArr <- fragEntries match
        case a: ujson.Arr => Right(a)
        case _ => // danger-scan:allow typed-rejection — a non-array adapter event refuses, never writes
          Left(s"adapter fragment declares '$event' as a non-array")
    yield fragArr.value.foldLeft((List.empty[String], List.empty[String])) {
      case ((added: List[String], skipped: List[String]), fragEntry: ujson.Value) =>
        mergeFragEntry(entries, event, fragEntry, added, skipped)
    }

  /** One `{matcher, hooks:[…]}` fragment entry: each hook deduplicated on its `command`. */
  private def mergeFragEntry(
    entries: ujson.Arr,
    event: String,
    fragEntry: ujson.Value,
    added: List[String],
    skipped: List[String]
  ): (List[String], List[String]) =
    val matcher: String = fragEntry.obj.get("matcher") match
      case Some(ujson.Str(s)) => s
      case _ => // danger-scan:allow jq-empty-parity — absent/non-string matcher reads as "" like `new.get('matcher','')`
        ""
    val label: String = s"$event: $matcher".trim
    fragEntry.obj.get("hooks") match
      case Some(hs: ujson.Arr) =>
        hs.value.foldLeft((added, skipped)) { case ((a: List[String], s: List[String]), h: ujson.Value) =>
          val command: Option[ujson.Value] = h.obj.get("command")
          val duplicate: Boolean = entries.value.exists { (e: ujson.Value) =>
            e.obj.get("hooks") match
              case Some(ehs: ujson.Arr) =>
                ehs.value.exists((x: ujson.Value) => x.obj.get("command") == command)
              case _ => false // danger-scan:allow absent-not-duplicate — no hooks array holds no command to dedupe on
          }
          if duplicate then (a, s :+ label)
          else
            val merged: ujson.Obj = ujson.Obj()
            fragEntry.obj.foreach((kv: (String, ujson.Value)) => if kv._1 != "hooks" then merged(kv._1) = kv._2)
            merged("hooks") = ujson.Arr(h)
            entries.value.append(merged)
            (a :+ label, s)
        }
      case _ => (added, skipped) // danger-scan:allow absent-hooks — contributes nothing to the merge

  /**
   * The parsed hook-installer surface — the predecessor's `while/case`
   * loop as data.
   */
  final private case class HooksArgs(
    agent: Option[String],
    apply: Boolean,
    project: Option[String],
    help: Boolean
  )

  /**
   * The predecessor's sequential `while [ $# -gt 0 ]` scan: `-h`/`--help`
   * exits the moment it is reached (before a bad argument AFTER it would
   * be named), a value flag consumes the next token unconditionally —
   * even another flag, exactly as `AGENT="${2:-}"` does — and a missing
   * value is the `shift 2` failure (exit 1). An unrecognized token is
   * the predecessor's `unknown argument` (exit 2). The last occurrence
   * of a repeated flag wins.
   */
  private def parseHooksArgs(
    args: List[String],
    acc: HooksArgs
  ): Either[CliError, HooksArgs] =
    args match
      case Nil                       => Right(acc)
      case "-h" :: _ | "--help" :: _ => Right(acc.copy(help = true))
      case "--agent" :: value :: rest =>
        parseHooksArgs(rest, acc.copy(agent = Some(value)))
      case "--agent" :: Nil => Left(CliError.MissingValue("--agent"))
      case "--apply" :: rest =>
        parseHooksArgs(rest, acc.copy(apply = true))
      case "--project" :: value :: rest =>
        parseHooksArgs(rest, acc.copy(project = Some(value)))
      case "--project" :: Nil => Left(CliError.MissingValue("--project"))
      case other :: _         => Left(CliError.UnknownFlag(other)) // danger-scan:allow unknown-arg-exit-2

  private val hooksUsage: String =
    "install-hooks [--agent claude|pi|devin|all] [--apply] [--project PATH] [-h|--help]\n" +
      "  --agent     which harness to wire (default: all that are already present)\n" +
      "  --apply     actually write; without it, prints the plan and changes nothing\n" +
      "  --project   project root (default: git toplevel, else cwd)\n" +
      "  -h|--help   this help\n" +
      "exit status: 0 ran clean · 1 finding (missing flag value, refused write) ·\n" +
      "             2 could not determine (unknown argument, no openspec/)\n"

  /**
   * The env-injecting overload — the seam the test oracle drives.
   *
   * `PROBATIO_SCHEMA_DIR` locates the schema tree the harness adapters
   * come from (the binary lives at `<schema>/bin/probatio`, as the
   * predecessor resolves `$SELF_DIR/adapters`); `PWD` supplies the
   * fallback project root when `--project` is absent and `git
   * rev-parse` finds no toplevel.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    parseHooksArgs(
      args.toList,
      HooksArgs(agent = None, apply = false, project = None, help = false)
    ) match
      case Left(CliError.MissingValue(flag)) =>
        SubcommandWiring.emitStderr(s"install-hooks: $flag requires a value\n")
        Outcome.Finding(s"$flag requires a value")
      case Left(err) =>
        SubcommandWiring.emitStderr(s"unknown argument: ${err.offendingToken}\n")
        Outcome.Undetermined(s"unknown argument: ${err.offendingToken}")
      case Right(parsed) if parsed.help =>
        SubcommandWiring.emitStdout(hooksUsage)
        Outcome.Ran(0)
      case Right(parsed) =>
        val cwd: Path = Path.of(env.getOrElse("PWD", "."))
        // The adapters resolve relative to the TARGET project — the
        // predecessor's `$SELF_DIR/adapters` — so `--project` works from
        // an unrelated cwd.
        val project: Path = parsed.project
          .filter(_.nonEmpty)
          .map(Path.of(_))
          .getOrElse(SubcommandWiring.repoRootOf(cwd))
        if !Files.isDirectory(project.resolve("openspec")) then
          SubcommandWiring.emitStderr(
            s"install-hooks: $project has no openspec/ — nothing to wire\n"
          )
          Outcome.Undetermined(s"$project has no openspec/ — nothing to wire")
        else
          val target: InstallTarget = parsed.agent match
            case None | Some("") | Some("all") => InstallTarget.AllPresentHarnesses
            case Some(name)                    => InstallTarget.NamedHarness(name)
          val mode: InstallMode =
            if parsed.apply then InstallMode.Apply else InstallMode.DryRun
          runInstaller(
            SubcommandWiring.schemaDirOf(env, project).resolve("hooks/adapters"),
            project,
            target,
            mode
          )

  /**
   * Wire the install-hooks subcommand to the predecessor's surface:
   * `install-hooks [--agent claude|pi|devin|all] [--apply] [--project
   * PATH] [-h|--help]` — dry-run by default, harnesses selected by name
   * or by presence, an existing foreign configuration refused. The
   * `--dir` surface is removed — the predecessor never had it, and
   * surface parity is the swap precondition.
   *
   * spec: install-tool-surface-parity — Requirement: The hook installer reports before it writes
   * spec: install-tool-surface-parity — Requirement: The hook installer selects harnesses and refuses to clobber
   * spec: install-tool-surface-parity — Property: surface-parity-with-the-predecessor
   */
  def run(args: Array[String]): Outcome[Int] =
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv
