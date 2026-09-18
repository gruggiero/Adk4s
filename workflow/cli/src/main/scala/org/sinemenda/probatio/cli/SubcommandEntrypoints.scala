package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import scala.jdk.CollectionConverters.IteratorHasAsScala
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
  /** Gate hook event names. */
  enum Event:
    case SessionStart, PromptSubmit, ToolCall, PostEdit, Completion

  /**
   * Wire the gate subcommand to the 5-event tier logic.
   *
   * Dispatches on `--event` to one of 5 events, assembles the GatePayload
   * via BannerEngine, and implements the blocking logic per event tier.
   * The escape hatch (`PROBATIO_HOOKS=1`) bypasses both predecessor check
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
   * with no value binds the empty string.
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
          tail.copy(flags = tail.flags + (flag -> value))
        case Nil =>
          val tail: GateArgs = parseGateArgs(Nil)
          tail.copy(flags = tail.flags + (flag -> ""))
    case _ :: rest => // danger-scan:allow lenient-parse — unknown tokens skipped, never mapped (predecessor `*) shift`)
      parseGateArgs(rest)

  /**
   * Wire the gate subcommand to the 5-event tier logic.
   *
   * Dispatches on `--event` to one of 5 events, assembles the GatePayload
   * via BannerEngine, and implements the blocking logic per event tier.
   * The escape hatch (`PROBATIO_HOOKS=1`) bypasses both predecessor check
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
    run(args, sys.env) // scalafix:ok DisableSyntax.NoSysEnv

  /**
   * The gate run with an explicit environment — the test seam. `run(args)`
   * reads the process environment once and delegates; the env is threaded
   * through every downstream decision (repo resolution, hook control,
   * session, escape hatch) so tests exercise the identical logic.
   */
  private[cli] def run(args: Array[String], env: Map[String, String]): Outcome[Int] =
    val parsed: GateArgs = parseGateArgs(args.toList)
    if parsed.checkInstalled then runCheckInstalled(parsed, env)
    else
      parsed.flags.get("--event") match
        case None =>
          SubcommandWiring.emitStderr("gate: --event is required\n")
          Outcome.Finding("--event is required")
        case Some(eventStr) =>
          parseEvent(eventStr) match
            case Some(event) =>
              runEvent(event, parsed, env)
            case None =>
              SubcommandWiring.emitStderr(s"gate: unknown event '$eventStr'\n")
              Outcome.Finding(s"unknown event: $eventStr")

  /**
   * `--check-installed`: a pure read of the heartbeat — no state is ever
   * created, and it works in any repository (the relevance guard does not
   * apply). Reports `{installed, last_run, event}`.
   *
   * spec: gate-event-completeness — Requirement: The installation probe reports whether the gate has run
   */
  private def runCheckInstalled(
    parsed: GateArgs,
    env: Map[String, String]
  ): Outcome[Int] =
    val repo: Path = resolveRepo(parsed.flags.get("--repo"), env)
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
   * Resolve the repository root from the strongest available signal:
   * explicit `--repo`, then `CLAUDE_PROJECT_DIR`, then
   * `git rev-parse --show-toplevel` from the process cwd, then the cwd
   * itself. (The stdin-payload `.cwd` signal is spec-8 scope; adapters
   * pass `--repo`.)
   */
  private def resolveRepo(
    explicit: Option[String],
    env: Map[String, String]
  ): Path =
    val cwd: Path =
      Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize()
    explicit
      .filter(_.nonEmpty)
      .map(Path.of(_))
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
   * v14; alias expires when the repo's schema version exceeds 15, unknown
   * version honours the alias — fail safe, matching the predecessor).
   */
  private def hooksControlValue(repo: Path, env: Map[String, String]): String =
    val aliasExpired: Boolean = repoSchemaVersion(repo).exists(_ > 15)
    env.get("PROBATIO_HOOKS").filter(_.nonEmpty) match
      case Some(v) => v
      case None =>
        env.get("VERIFIED_SCALA3_HOOKS").filter(_.nonEmpty) match
          case Some(v) if !aliasExpired =>
            SubcommandWiring.emitStderr(
              "probatio: VERIFIED_SCALA3_HOOKS is deprecated — renamed to " +
                "PROBATIO_HOOKS at schema v14. The old name is read as an " +
                "alias for one major version. Update your shell config to " +
                "use PROBATIO_HOOKS.\n"
            )
            v
          case _ => "on" // danger-scan:allow control-default — no hook-control env var means on (predecessor default)

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

  /** Parse the event name from the --event flag value. */
  private def parseEvent(s: String): Option[Event] = s match
    case "session-start" => Some(Event.SessionStart)
    case "prompt-submit" => Some(Event.PromptSubmit)
    case "tool-call"     => Some(Event.ToolCall)
    case "post-edit"     => Some(Event.PostEdit)
    case "completion"    => Some(Event.Completion)
    case _ => // danger-scan:allow string-rejection — unrecognized event name maps to None (error), never a valid Event
      None

  /**
   * Run the gate for a specific event. The blocking tiers:
   * - SessionStart, PromptSubmit: Tier B — informational, always exit 0
   * - PostEdit: Tier A — informational, run spec-lint or danger-scan
   * - ToolCall: Tier A — blocking, check predecessors and grants
   * - Completion: Tier A — blocking, check chain-state
   *
   * spec: cli-wiring — Requirement: The gate subcommand wires to the 5-event tier logic and emits the hook banner
   */
  private def runEvent(
    event: Event,
    parsed: GateArgs,
    env: Map[String, String]
  ): Outcome[Int] =
    val change: String = parsed.flags.getOrElse("--change", "")
    // The escape hatch reads the same env the caller resolved — under
    // `run(args)` this is exactly the process environment.
    val escapeHatch: Boolean = CliContext.readEscapeHatch(env)
    event match
      case Event.SessionStart | Event.PromptSubmit =>
        runBanner(event, parsed, env)

      case Event.PostEdit =>
        // Tier A — informational, run spec-lint or danger-scan on the edited file
        // For now, emit the banner and exit 0 (no blocking on post-edit)
        Outcome.Ran(0)

      case Event.ToolCall =>
        // Tier A — blocking, check predecessors and grants
        // The escape hatch bypasses both checks
        if escapeHatch then Outcome.Ran(0)
        else
          // Without state files, the predecessor check cannot be performed —
          // this is Undetermined, not clean. Missing state must not silently
          // pass as verified.
          Outcome.Undetermined("no state directory — cannot verify predecessors")

      case Event.Completion =>
        // Tier A — blocking, check chain-state
        // If chain-state has unresolved obligations, block (exit 1)
        if escapeHatch then Outcome.Ran(0)
        else
          // Read the ledger and compute chain-state
          val ledgerFile: String = parsed.flags.getOrElse("--ledger-file", "")
          val baseline: String   = parsed.flags.getOrElse("--baseline", "")
          if ledgerFile.isEmpty || change.isEmpty || baseline.isEmpty then
            // Missing required parameters — this is a Finding, not clean.
            Outcome.Finding(
              "missing required parameters for chain-state computation: --ledger-file, --change, --baseline"
            )
          else
            SubcommandWiring.readLedgerFile(ledgerFile) match
              case Outcome.Undetermined(reason) =>
                Outcome.Undetermined(reason)
              case Outcome.Finding(msg) =>
                Outcome.Finding(msg)
              case Outcome.Ran(rows) =>
                // Validate every row — a corrupt ledger is Undetermined, not
                // a silently truncated ledger with invalid rows dropped.
                val validated: Either[String, List[LedgerRecord]] =
                  rows.foldLeft[Either[String, List[LedgerRecord]]](Right(Nil)) {
                    case (Left(err), _) => Left(err)
                    case (Right(acc), v) =>
                      Validator.validate(v) match
                        case Right(r) => Right(r :: acc)
                        case Left(viol) =>
                          Left(s"ledger contains invalid row: clause ${viol.clauseIndex} — ${viol.description}")
                  }
                validated match
                  case Left(err) =>
                    Outcome.Undetermined(err)
                  case Right(recordsRev) =>
                    val records: List[LedgerRecord] = recordsRev.reverse
                    val ledger: Ledger.LedgerData   = Ledger.fromRecords(records)
                    // The gate's change dir: `--change-dir` when the hook
                    // passes it, else <repo>/openspec/changes/<change>.
                    val changeDir: Path = parsed.flags
                      .get("--change-dir")
                      .filter(_.nonEmpty)
                      .map(Paths.get(_))
                      .getOrElse(
                        resolveRepo(parsed.flags.get("--repo"), env)
                          .resolve("openspec/changes")
                          .resolve(change)
                      )
                    ChainStateCmd.prepareInputs(
                      changeDir,
                      change,
                      baseline,
                      specFilter = None,
                      env,
                      ledgerFile,
                      emitDiagnostics = false
                    ) match
                      case Left(reason) =>
                        Outcome.Undetermined(reason)
                      case Right(inputs) =>
                        ChainState.compute(
                          inputs.lints,
                          ledger,
                          inputs.extracted,
                          inputs.specBaselines,
                          inputs.effectiveBaseline,
                          inputs.resolvedBaseline,
                          change,
                          inputs.artifactUnchanged
                        ) match
                          case Left(u) =>
                            Outcome.Undetermined(u.reason)
                          case Right(report) =>
                            if report.unresolved.isEmpty then Outcome.Ran(0)
                            else
                              val reason: String = report.unresolved
                                .map { e =>
                                  s"${e.requirement} (${e.reasons.map(UnresolvedReason.asString).mkString(", ")})"
                                }
                                .mkString("; ")
                              Outcome.Finding(s"chain-state unresolved: $reason")

  /**
   * The Tier-B banner path — the spec-3 live-fact wiring:
   * relevance guard → env control → state dir → heartbeat → facts read →
   * render → per-session suppression → emit. Always exit 0; a failure at
   * any step degrades (no output), never fails a session.
   *
   * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
   * spec: live-fact-banner — Requirement: Repeated injections within one session are suppressed only when the underlying facts are unchanged
   * spec: live-fact-banner — Requirement: The hook emits the invariant and live chain state on session-start and prompt-submit
   */
  private def runBanner(
    event: Event,
    parsed: GateArgs,
    env: Map[String, String]
  ): Outcome[Int] =
    val repo: Path = resolveRepo(parsed.flags.get("--repo"), env)
    // Relevance guard: a repository that does not use this workflow gets
    // NOTHING — no output, and no state directory created.
    if !Files.isDirectory(repo.resolve("openspec")) then Outcome.Ran(0)
    else if hooksControlValue(repo, env) == "off" then Outcome.Ran(0)
    else
      val format: String = parsed.flags.getOrElse("--format", "hook-json")
      val eventName: String = event match
        case Event.SessionStart => "session-start"
        case Event.PromptSubmit => "prompt-submit"
        case _ => "session-start" // danger-scan:allow predecessor-default — only banner events reach this path
      // State directory — created only after the relevance guard (D8), and
      // fail-open when unresolvable or unwritable.
      val stateDir: Option[GateStateDir] =
        GateStateDirReader.resolve(repo).flatMap { (d: GateStateDir) =>
          try
            Files.createDirectories(d.path)
            Some(d)
          catch case NonFatal(_) => None // danger-scan:allow fail-open — unwritable state dir means no persistence
        }
      stateDir.foreach { (d: GateStateDir) =>
        GateStateDirReader.writeHeartbeat(
          d,
          HeartbeatRecord(SubcommandWiring.stampTimestamp, eventName, format)
        )
      }
      val session: SessionId = SessionId.resolve(
        parsed.flags.get("--session"),
        env.get("CLAUDE_CODE_SESSION_ID"),
        env.get("VERIFIED_SCALA3_SESSION_ID"),
        parentPid
      )
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
          RepositoryFactsReader.read(repo, userHome, env)
        val banner: BannerOutput =
          BannerEngine.render(BannerInputs.from(facts))
        val suppressed: Boolean = stateDir.exists { (d: GateStateDir) =>
          GateStateDirReader.readFingerprint(d, session).contains(facts.fingerprint)
        }
        if suppressed then Outcome.Ran(0)
        else
          stateDir.foreach((d: GateStateDir) => GateStateDirReader.writeFingerprint(d, session, facts.fingerprint))
          emitBanner(event, format, banner.payload)
          Outcome.Ran(0)
      catch case NonFatal(_) => Outcome.Ran(0) // danger-scan:allow fail-open — the gate never fails a session

  /**
   * Emit the banner payload: `hook-json` wraps it in the shared
   * `hookSpecificOutput` envelope (prompt-submit maps to
   * `UserPromptSubmit`, matching the predecessor); every other format
   * value — including `text` — prints the payload itself.
   */
  private def emitBanner(event: Event, format: String, payload: String): Unit =
    format match
      case "hook-json" =>
        val hookEventName: String = event match
          case Event.SessionStart => "SessionStart"
          case Event.PromptSubmit => "UserPromptSubmit"
          case _ => "SessionStart" // danger-scan:allow predecessor-default — only banner events reach this path
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
   * root (the predecessor's own contract) and `PROBATIO_SCANNER_DIR` names
   * the directory holding `openspec-graph.py`; the seam exists so the test
   * oracle can force the graph and degraded paths deterministically.
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
              emitUndetermined(change, baseline, reason)
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
                      inputs.lints,
                      Ledger.fromRecords(Nil),
                      inputs.extracted,
                      inputs.specBaselines,
                      inputs.effectiveBaseline,
                      inputs.resolvedBaseline,
                      change,
                      inputs.artifactUnchanged
                    ),
                    baseline
                  )
                case Left(reason) =>
                  emitUndetermined(change, baseline, reason)
                case Right(ledger) =>
                  emitReport(
                    ChainState.compute(
                      inputs.lints,
                      ledger,
                      inputs.extracted,
                      inputs.specBaselines,
                      inputs.effectiveBaseline,
                      inputs.resolvedBaseline,
                      change,
                      inputs.artifactUnchanged
                    ),
                    baseline
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
   * when nothing is unresolved and nothing is unmapped.
   */
  private def emitReport(
    computed: Either[ChainStateUndetermined, ChainStateReport],
    baseline: String
  ): Outcome[Int] =
    computed match
      case Left(u) =>
        emitUndetermined(u.change, baseline, u.reason)
      case Right(report) =>
        val rendered: String = StdoutRenderer[ChainStateReport].render(report)
        SubcommandWiring.emitStdout(rendered + "\n")
        if report.unresolved.isEmpty && report.unmappedObligations.isEmpty then Outcome.Ran(0)
        else
          Outcome.Finding(
            s"chain-state: ${report.unresolved.length} unresolved, " +
              s"${report.unmappedObligations.length} unmapped obligation(s)"
          )

  /**
   * Emit the undetermined report on stdout and the diagnostic on stderr —
   * the `UNDETERMINED —` marker is written HERE, exactly once; the reason
   * field carries no marker (it is data, not a diagnostic line).
   *
   * spec: chain-state-attribution — Requirement: The undetermined diagnostic marker is emitted exactly once
   */
  private def emitUndetermined(
    change: String,
    baseline: String,
    reason: String
  ): Outcome[Int] =
    val undetermined: ChainStateUndetermined = ChainStateUndetermined(change, baseline, reason)
    SubcommandWiring.emitStdout(StdoutRenderer[ChainStateUndetermined].render(undetermined) + "\n")
    SubcommandWiring.emitStderr(s"chain-state: UNDETERMINED — $reason\n")
    Outcome.Undetermined(reason)

  /**
   * The bundle of measured facts `ChainState.compute` consumes — the
   * extracted requirement set (with its FactSource), the per-spec lint
   * outcomes, the per-spec baseline map, the resolved effective baseline,
   * and the forgive-unchanged oracle.
   */
  final private[cli] case class ChainStateInputs(
    extracted: RequirementSet,
    lints: Map[String, Outcome[LintReport]],
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
                      graphExport(changeDir, change, env)
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
                    val lints: Map[String, Outcome[LintReport]] = namedSpecs.map { ns =>
                      ns.name -> SpecLintEngine.lint(ns.document, context, checkArtifacts = true, artifactTracked)
                    }.toMap
                    Right(
                      ChainStateInputs(
                        extracted,
                        lints,
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
      val rawEffective: String = lines
        .collectFirst((l: String) => effectiveShaRe.findFirstMatchIn(l).map(_.group(2)))
        .flatten
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
   * Attempt `openspec-graph.py export` — the predecessor's D5 mechanism:
   * `python3 <script> export --change-dir <dir> --change <name>` with
   * `OPENSPEC_ROOT` on the subprocess environment. Returns the parsed
   * export (if it ran and produced JSON) plus the diagnostic lines to
   * emit — the FactSource is stated, never inferred: a fallback is always
   * announced as degraded, never presented as though the extractor ran.
   */
  private def graphExport(
    changeDir: Path,
    change: String,
    env: Map[String, String]
  ): (Option[ujson.Value], List[String]) =
    val degraded: String = "using degraded mode (in-process fallback)"
    env.get("PROBATIO_SCANNER_DIR").map(Paths.get(_)) match
      case None =>
        (
          None,
          List(s"chain-state: openspec-graph.py unavailable (PROBATIO_SCANNER_DIR not set); $degraded")
        )
      case Some(scannerDir) =>
        val script: Path = scannerDir.resolve("openspec-graph.py")
        if !Files.isRegularFile(script) then
          (None, List(s"chain-state: openspec-graph.py not found at $script; $degraded"))
        else
          try
            val pb: ProcessBuilder = new ProcessBuilder(
              "python3",
              script.toString,
              "export",
              "--change-dir",
              changeDir.toString,
              "--change",
              change
            )
            env.get("OPENSPEC_ROOT").foreach((r: String) => pb.environment().put("OPENSPEC_ROOT", r))
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            val p: Process = pb.start()
            val out: String =
              new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
            val code: Int = p.waitFor()
            if code != 0 || out.isBlank then
              (None, List(s"chain-state: openspec-graph.py export failed (exit $code); $degraded"))
            else
              try
                val parsed: ujson.Value = ujson.read(out)
                // The `.obligations` usability gate runs BEFORE the path
                // diagnostic — a parseable but unusable export takes the
                // degraded path and is announced as such, never as graph.
                if RequirementExtractor.usableExport(parsed) then
                  (
                    Some(parsed),
                    List("chain-state: fact extraction via openspec-graph.py export (graph)")
                  )
                else
                  (
                    None,
                    List(s"chain-state: openspec-graph.py export produced invalid JSON; $degraded")
                  )
              catch
                case NonFatal(_) => // danger-scan:allow typed-catch — unparseable export output falls back
                  (
                    None,
                    List(s"chain-state: openspec-graph.py export produced invalid JSON; $degraded")
                  )
          catch
            case NonFatal(_) => // danger-scan:allow degraded-fact — no python3 is the predecessor's degraded trigger
              (None, List(s"chain-state: python3 unavailable; $degraded"))

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
      if !Files.exists(filePath) then
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
      if !Files.exists(filePath) then
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
              val recorded: Int    = row("exit").numOpt.map(_.toInt).getOrElse(0)
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

/** The `metals` subcommand — LSP metals client (start only; stop/call removed). */
object MetalsCmd:
  enum SubAction:
    case Start

  /**
   * Wire the metals subcommand to delegate LSP framing to MetalsClient.
   *
   * Only the `start` sub-action is retained; `stop` and `call` were never
   * implemented and are now unrecognised sub-actions rather than silently
   * clean stubs.
   *
   * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
   * spec: cli-wiring — Scenario: metals start launches the LSP server
   * spec: cli-entrypoint-contract — Scenario: Edge case — the retained sub-action of a partially-ported tool still resolves
   */
  def run(args: Array[String]): Outcome[Int] =
    if args.isEmpty then
      SubcommandWiring.emitStderr("metals: subaction required (start)\n")
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
        case other => // danger-scan:allow string-rejection — unrecognized subaction maps to Finding (error), never a valid SubAction
          SubcommandWiring.emitStderr(s"metals: unknown subaction '$other'\n")
          Outcome.Finding(s"unknown subaction: $other")

/** The `install-skills` subcommand — skill installation. */
object InstallSkillsCmd:
  /**
   * Wire the install-skills subcommand to copy skill files to agent directories.
   *
   * spec: cli-wiring — Requirement: The remaining subcommands wire to their core logic and emit byte-compatible stdout
   * spec: cli-wiring — Scenario: install-skills copies skills to agent directories
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--dir")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"install-skills: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val dir: String = parsed.getOrElse("--dir", "")
        if dir.isEmpty then
          SubcommandWiring.emitStderr("install-skills: --dir is required\n")
          Outcome.Finding("--dir is required")
        else
          // Copy skill files to agent directories
          val targets: List[String]         = List(".claude/skills", ".pi/skills", ".devin/skills")
          val sourceDir: java.nio.file.Path = Paths.get(dir)
          if !Files.isDirectory(sourceDir) then
            SubcommandWiring.emitStderr(s"install-skills: $dir is not a directory\n")
            Outcome.Finding(s"$dir is not a directory")
          else
            try
              targets.foreach { target =>
                val targetPath: java.nio.file.Path = Paths.get(target)
                Files.createDirectories(targetPath)
                Files.list(sourceDir).forEach { file =>
                  val dest: java.nio.file.Path = targetPath.resolve(file.getFileName)
                  Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                }
              }
              SubcommandWiring.emitStdout(s"install-skills: copied to ${targets.mkString(", ")}\n")
              Outcome.Ran(0)
            catch
              case e: java.io.IOException =>
                SubcommandWiring.emitStderr(s"install-skills: ${e.getMessage}\n")
                Outcome.Undetermined(e.getMessage)

/** The `install-hooks` subcommand — hook installation. */
object InstallHooksCmd:
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--dir")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"install-hooks: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val dir: String = parsed.getOrElse("--dir", "")
        if dir.isEmpty then
          SubcommandWiring.emitStderr("install-hooks: --dir is required\n")
          Outcome.Finding("--dir is required")
        else
          val sourceDir: java.nio.file.Path = Paths.get(dir)
          if !Files.isDirectory(sourceDir) then
            SubcommandWiring.emitStderr(s"install-hooks: $dir is not a directory\n")
            Outcome.Finding(s"$dir is not a directory")
          else
            try
              val targetPath: java.nio.file.Path = Paths.get("hooks")
              Files.createDirectories(targetPath)
              Files.list(sourceDir).forEach { file =>
                val dest: java.nio.file.Path = targetPath.resolve(file.getFileName)
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
              }
              SubcommandWiring.emitStdout(s"install-hooks: copied to hooks/\n")
              Outcome.Ran(0)
            catch
              case e: java.io.IOException =>
                SubcommandWiring.emitStderr(s"install-hooks: ${e.getMessage}\n")
                Outcome.Undetermined(e.getMessage)
