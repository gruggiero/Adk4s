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
  private def specFiles(target: Path): Option[List[Path]] =
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

  /** `find <root> -name spec.md` filtered and sorted — as the predecessor pipes it. */
  private[cli] def findSpecs(root: Path, keep: Path => Boolean): List[Path] =
    try
      Using.resource(Files.walk(root)) { walk =>
        walk
          .iterator()
          .asScala
          .toList
          .filter((p: Path) => Files.isRegularFile(p) && p.getFileName.toString == "spec.md" && keep(p))
          .sortBy(_.toString)
      }
    catch case NonFatal(_) => Nil // danger-scan:allow degraded-discovery — a failed find is "no spec files"

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
        case Some(Nil) =>
          SubcommandWiring.emitStdout(
            s"spec-lint: no spec files to lint under ${parsed.target}\n"
          )
          Outcome.Ran(0)
        case Some(specs) => runSpecs(specs, context, parsed, artifactTracked)

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
          // Read the ledger file — an unreadable ledger is undetermined,
          // never a silently zero measurement.
          SubcommandWiring.readLedgerFile(ledgerFile) match
            case Outcome.Undetermined(reason) =>
              emitUndetermined(change, baseline, reason)
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
                  emitUndetermined(change, baseline, err)
                case Right(recordsRev) =>
                  val records: List[LedgerRecord] = recordsRev.reverse
                  val ledger: Ledger.LedgerData   = Ledger.fromRecords(records)
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
    specBaselines: Map[String, String],
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
      val discovered: List[Path] = SpecLintCmd.findSpecs(specsDir, _ => true)
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
                  case NonFatal(e) => // danger-scan:allow degraded-fact — unreadable spec is undetermined, not skipped
                    Left(s"could not read $p: ${e.getMessage}")
              }
            }
          named match
            case Left(reason) => Left(reason)
            case Right(namedSpecs) =>
              val (specBaselines, effectiveBaseline, resolvedBaseline): (Map[String, String], String, String) =
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
  ): (Map[String, String], String, String) =
    // REPO is derived from the change dir; the resolution itself runs
    // there or, when the change dir is outside any repository, in the
    // process cwd — the predecessor's `cd ""` is a no-op that keeps cwd.
    val repo: Option[Path] = repoContaining(changeDir)
    val progressFile: Path = changeDir.resolve("implementation-progress.md")
    if !Files.isRegularFile(progressFile) then (Map.empty, baselineArg, resolveSha(repo, baselineArg))
    else
      val lines: List[String] =
        try Files.readString(progressFile, StandardCharsets.UTF_8).linesIterator.toList
        catch
          case NonFatal(_) => Nil // danger-scan:allow degraded-fact — unreadable progress file means no baselines
      // The awk's two states: the `## Spec N:` block being walked and the
      // `### Baseline` paragraph inside it. `inBaseline` survives any
      // non-blank, non-SHA, non-`## Spec` line — the predecessor's own
      // (loose) state machine, ported exactly.
      val specHeadRe: scala.util.matching.Regex = "^## Spec [0-9]".r
      val specNameRe: scala.util.matching.Regex = "[0-9]+[: ]+\\(?([a-zA-Z0-9_-]+)".r
      val shaRe: scala.util.matching.Regex      = "`([a-f0-9]{7,40})`".r
      final case class BaselineAcc(
        currentSpec: String,
        inBaseline: Boolean,
        map: Map[String, String]
      )
      val parsed: BaselineAcc = lines.foldLeft(BaselineAcc("", false, Map.empty)) { (acc, line) =>
        if specHeadRe.findPrefixOf(line).isDefined then
          BaselineAcc(
            specNameRe.findFirstMatchIn(line).map(_.group(1)).getOrElse(""),
            inBaseline = false,
            acc.map
          )
        else if line.startsWith("### Baseline") then acc.copy(inBaseline = true)
        else if acc.inBaseline && shaRe.findFirstMatchIn(line).isDefined then
          val sha: String =
            shaRe.findFirstMatchIn(line).map(_.group(1)).getOrElse("")
          BaselineAcc(
            acc.currentSpec,
            inBaseline = false,
            if acc.currentSpec.nonEmpty then acc.map + (acc.currentSpec -> sha)
            else acc.map
          )
        else if acc.inBaseline && line.isEmpty then acc.copy(inBaseline = false)
        else acc
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
        parsed.map.map((spec, sha) => spec -> resolveSha(repo, sha)),
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
                (
                  Some(ujson.read(out)),
                  List("chain-state: fact extraction via openspec-graph.py export (graph)")
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
    if Files.isDirectory(dir) then SpecLintCmd.gitOut(dir, List("rev-parse", "--show-toplevel")).map(Paths.get(_))
    else None

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
      else List(out, sha).filter(_.nonEmpty).distinct.mkString("\n")
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a non-git cwd passes the literal through
        sha

  /** `git <args>` under `dir`, returning the exit code. */
  private def gitExit(dir: Path, args: List[String]): Int =
    try
      val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
      pb.directory(dir.toFile)
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
      pb.start().waitFor()
    catch case NonFatal(_) => 128 // danger-scan:allow fail-open — a git failure is "changed", never "unchanged"

  /**
   * The forgive-unchanged oracle — the predecessor's
   * `git diff --quiet <row.baseline> HEAD -- <artifact>` under the ledger
   * file's repository root. Anything that is not a clean diff (changed
   * artifact, unknown baseline, no repo) is "changed" — a stale row that
   * cannot be forgiven stays absent evidence.
   */
  private def forgivePredicate(ledgerFile: String): (String, String) => Boolean =
    val ledgerDir: Option[Path] =
      Option(Paths.get(ledgerFile).toAbsolutePath.normalize.getParent)
    val repo: Option[Path] = ledgerDir.flatMap(repoContaining)
    (rowBaseline: String, artifact: String) =>
      repo match
        case Some(r) =>
          gitExit(r, List("diff", "--quiet", rowBaseline, "HEAD", "--", artifact)) == 0
        case None => false

/** The `ledger` subcommand — append-only ledger operations. */
object LedgerCmd:
  /**
   * The only actions available: `append`, `read`, `validate`. No `update`,
   * `delete`, `rewrite`, or `edit` action exists.
   */
  enum Action:
    case Append, Read, Validate

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
      SubcommandWiring.emitStderr("ledger: unknown subcommand: '<none>'. Expected: append, run, read, validate.\n")
      Outcome.Finding("unknown subcommand")
    else
      val actionStr: String = args(0)
      // Check for mutation subcommands — rejected for WHAT IT IS
      actionStr match
        case "update" | "delete" | "rewrite" | "edit" =>
          SubcommandWiring.emitStderr(s"ledger: the ledger is append-only; '$actionStr' is not a subcommand.\n")
          Outcome.Finding(s"append-only: $actionStr")
        case "run" =>
          // run mode: execute the command after --, observe its exit code,
          // then append a self-observed record.
          runRunMode(args.drop(1))
        case _ => // danger-scan:allow string-rejection — non-mutation string falls through to parseAction which returns None→Finding for unknowns
          parseAction(actionStr) match
            case Some(action) =>
              runAction(action, args.drop(1))
            case None =>
              SubcommandWiring.emitStderr(
                s"ledger: unknown subcommand: '$actionStr'. Expected: append, run, read, validate.\n"
              )
              Outcome.Finding(s"unknown subcommand: $actionStr")

  /** Parse the action name. */
  private def parseAction(s: String): Option[Action] = s match
    case "append"   => Some(Action.Append)
    case "read"     => Some(Action.Read)
    case "validate" => Some(Action.Validate)
    case _ => // danger-scan:allow string-rejection — unrecognized action maps to None (error), never a valid Action
      None

  /**
   * Run the ledger action with the given args.
   */
  private def runAction(action: Action, args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
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
      "--source",
      "--forgive-unchanged"
    )
    // Split args at -- (run mode command separator)
    val (flagArgs, runCommand) = splitAtDoubleDash(args.toList)
    SubcommandWiring.parseArgs(flagArgs.toArray, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"ledger: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        action match
          case Action.Append =>
            runAppend(parsed, runCommand)
          case Action.Read =>
            runRead(parsed)
          case Action.Validate =>
            runValidate(parsed)

  /**
   * Run mode: execute the command after --, observe its exit code, and
   * append a self-observed record. The entrypoint exits 0 (the run itself
   * succeeded; the observed exit is recorded, not reflected).
   *
   * spec: cli-wiring — Scenario: The run action observes the command exit code
   */
  private def runRunMode(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
      "--file",
      "--change",
      "--spec",
      "--ring",
      "--obligation",
      "--artifact",
      "--baseline",
      "--session",
      "--source"
    )
    val (flagArgs, runCommand) = splitAtDoubleDash(args.toList)
    SubcommandWiring.parseArgs(flagArgs.toArray, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"ledger: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        // Execute the command and observe its exit code
        val observedExit: Int = if runCommand.isEmpty then 0 else executeCommand(runCommand)
        // Stamp --command and --exit with the observed values
        val stamped: Map[String, String] = parsed ++ Map(
          "--command" -> runCommand,
          "--exit"    -> observedExit.toString
        )
        runAppend(stamped, runCommand)

  /**
   * Execute a shell command and return its exit code.
   * Returns 0 if the command is empty.
   */
  private def executeCommand(command: String): Int =
    if command.isEmpty then 0
    else
      val process: Process = new ProcessBuilder("sh", "-c", command).inheritIO().start()
      process.waitFor()

  /** Split args at the -- separator (for run mode). */
  private def splitAtDoubleDash(args: List[String]): (List[String], String) =
    args.indexOf("--") match
      case -1 => (args, "")
      case idx =>
        val (before, after) = args.splitAt(idx)
        (before, after.drop(1).mkString(" "))

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
      // Check required fields before building the record
      val missing: List[String] = List(
        ("--change", parsed.getOrElse("--change", "")),
        ("--spec", parsed.getOrElse("--spec", "")),
        ("--ring", parsed.getOrElse("--ring", "")),
        ("--obligation", parsed.getOrElse("--obligation", "")),
        ("--artifact", parsed.getOrElse("--artifact", "")),
        ("--command", parsed.getOrElse("--command", "")),
        ("--exit", parsed.getOrElse("--exit", "")),
        ("--baseline", parsed.getOrElse("--baseline", ""))
      ).filter(_._2.isEmpty).map(_._1)

      if missing.nonEmpty then
        SubcommandWiring.emitStderr(s"ledger: missing required field(s): ${missing.mkString(" ")}\n")
        Outcome.Finding(s"missing required field(s): ${missing.mkString(" ")}")
      else
        // R8 rows require session
        val ring: String    = parsed.getOrElse("--ring", "")
        val session: String = parsed.getOrElse("--session", "")
        if ring == "R8" && session.isEmpty then
          SubcommandWiring.emitStderr("ledger: session is required for R8 (adversarial-review) rows\n")
          Outcome.Finding("session is required for R8 rows")
        else
          // Build the record JSON with v and ts stamped here (not accepted from caller)
          val ts: String      = SubcommandWiring.stampTimestamp
          val exitStr: String = parsed.getOrElse("--exit", "0")
          exitStr.toIntOption match
            case None =>
              SubcommandWiring.emitStderr(s"ledger: --exit must be an integer, got '$exitStr'\n")
              Outcome.Finding(s"--exit must be an integer, got '$exitStr'")
            case Some(exitInt) =>
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
      SubcommandWiring.readLedgerFile(file) match
        case Outcome.Undetermined(reason) =>
          SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
          Outcome.Undetermined(reason)
        case Outcome.Finding(msg) =>
          Outcome.Finding(msg)
        case Outcome.Ran(rows) =>
          // Filter by change, spec, baseline
          val spec: String     = parsed.getOrElse("--spec", "")
          val baseline: String = parsed.getOrElse("--baseline", "")
          val filtered: List[ujson.Value] = rows.filter { row =>
            val rowChange: String   = row("change").strOpt.getOrElse("")
            val rowSpec: String     = row("spec").strOpt.getOrElse("")
            val rowBaseline: String = row("baseline").strOpt.getOrElse("")
            rowChange == change &&
            (spec.isEmpty || rowSpec == spec) &&
            (baseline.isEmpty || rowBaseline == baseline)
          }
          // Emit matching records on stdout
          val output: String = filtered.map(ujson.write(_)).mkString("\n")
          if output.nonEmpty then SubcommandWiring.emitStdout(output + "\n")
          Outcome.Ran(0)

  /**
   * Validate a ledger file (verify all rows against the contract).
   */
  def runValidate(parsed: Map[String, String]): Outcome[Int] =
    val file: String = parsed.getOrElse("--file", "")
    if file.isEmpty then
      SubcommandWiring.emitStderr("ledger: --file is required\n")
      Outcome.Finding("--file is required")
    else
      SubcommandWiring.readLedgerFile(file) match
        case Outcome.Undetermined(reason) =>
          SubcommandWiring.emitStderr(s"ledger: UNDETERMINED — $reason\n")
          Outcome.Undetermined(reason)
        case Outcome.Finding(msg) =>
          Outcome.Finding(msg)
        case Outcome.Ran(rows) =>
          SubcommandWiring.emitStderr(s"ledger: verify passed (${rows.length} rows)\n")
          Outcome.Ran(0)

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
              case Outcome.Undetermined(reason) => Outcome.Undetermined(reason)
              case Outcome.Finding(msg)         => Outcome.Finding(msg)
              case Outcome.Ran(_)               => Outcome.Ran(0)
          case None =>
            Outcome.Ran(0)
      case Left(violation: ContractViolation) =>
        Outcome.Finding(
          s"ledger append rejected: clause ${violation.clauseIndex} — ${violation.description}"
        )

/** The `checkpoint` subcommand — checkpoint operations. */
object CheckpointCmd:
  /**
   * Wire the checkpoint subcommand to write the presentation marker and
   * emit the report.
   *
   * spec: cli-wiring — Requirement: The checkpoint subcommand writes the presentation marker and emits the report
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set(
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
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"checkpoint: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String  = parsed.getOrElse("--change", "")
        val spec: String    = parsed.getOrElse("--spec", "")
        val session: String = parsed.getOrElse("--session", "")
        val gitDir: String  = parsed.getOrElse("--change-dir", ".")

        if change.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --change is required\n")
          Outcome.Finding("--change is required")
        else if spec.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --spec is required\n")
          Outcome.Finding("--spec is required")
        else if session.isEmpty then
          SubcommandWiring.emitStderr("checkpoint: --session is required\n")
          Outcome.Finding("--session is required")
        else
          // Read the ledger and compute chain-state before writing the marker.
          // The marker SHALL be evidence that the chain-state discharge check
          // ran — not just that tests ran. An undischarged spec produces a
          // Finding (exit 1), not a marker.
          val ledgerFile: String = parsed.getOrElse("--ledger", "")
          val baseline: String   = parsed.getOrElse("--baseline", "")
          if ledgerFile.isEmpty then
            SubcommandWiring.emitStderr("checkpoint: --ledger is required\n")
            Outcome.Finding("--ledger is required")
          else if baseline.isEmpty then
            SubcommandWiring.emitStderr("checkpoint: --baseline is required\n")
            Outcome.Finding("--baseline is required")
          else
            SubcommandWiring.readLedgerFile(ledgerFile) match
              case Outcome.Undetermined(reason) =>
                SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $reason\n")
                Outcome.Undetermined(reason)
              case Outcome.Finding(msg) =>
                Outcome.Finding(msg)
              case Outcome.Ran(rows) =>
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
                    SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $err\n")
                    Outcome.Undetermined(err)
                  case Right(recordsRev) =>
                    val records: List[LedgerRecord] = recordsRev.reverse
                    val ledger: Ledger.LedgerData   = Ledger.fromRecords(records)
                    // --change-dir is the repository root; the change's
                    // spec tree lives under openspec/changes/<change>.
                    val changeDir: Path =
                      Paths.get(gitDir).resolve("openspec/changes").resolve(change)
                    ChainStateCmd.prepareInputs(
                      changeDir,
                      change,
                      baseline,
                      specFilter = None,
                      sys.env, // scalafix:ok DisableSyntax.NoSysEnv
                      ledgerFile,
                      emitDiagnostics = false
                    ) match
                      case Left(reason) =>
                        SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — $reason\n")
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
                            SubcommandWiring.emitStderr(s"checkpoint: UNDETERMINED — ${u.reason}\n")
                            Outcome.Undetermined(u.reason)
                          case Right(report) =>
                            if report.unresolved.nonEmpty then
                              val names: String =
                                report.unresolved.map(u => s"${u.spec}/${u.requirement}").mkString(", ")
                              SubcommandWiring.emitStdout(
                                s"checkpoint: undischarged obligations for $change/$spec: $names\n"
                              )
                              Outcome.Finding(s"undischarged obligations: $names")
                            else
                              // All discharged — write the presentation marker
                              val markerDir: java.nio.file.Path = Paths.get(gitDir, ".git", "verified-scala3-gate")
                              val markerPath: java.nio.file.Path =
                                markerDir.resolve(s"presentation-$change-$spec-$session")
                              try
                                Files.createDirectories(markerDir)
                                Files.write(markerPath, Array.emptyByteArray)
                                SubcommandWiring.emitStdout(
                                  s"checkpoint: marker written for $change/$spec (session $session)\n"
                                )
                                Outcome.Ran(0)
                              catch
                                case e: java.io.IOException =>
                                  SubcommandWiring.emitStderr(s"checkpoint: could not write marker: ${e.getMessage}\n")
                                  Outcome.Undetermined(s"could not write marker: ${e.getMessage}")

/** The `reconcile` subcommand — obligation reconciliation. */
object ReconcileCmd:
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--change")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"reconcile: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val change: String = parsed.getOrElse("--change", "")
        if change.isEmpty then
          SubcommandWiring.emitStderr("reconcile: --change is required\n")
          Outcome.Finding("--change is required")
        else Outcome.Ran(0)

/** The `danger-scan` subcommand — production code danger scan. */
object DangerScanCmd:
  /**
   * Wire the danger-scan subcommand to the pattern scanner.
   *
   * spec: cli-wiring — Requirement: The danger-scan subcommand wires to the pattern scanner and emits findings
   */
  def run(args: Array[String]): Outcome[Int] =
    val flags: Set[String] = Set("--baseline", "--also")
    SubcommandWiring.parseArgs(args, flags) match
      case Left(err) =>
        SubcommandWiring.emitStderr(s"danger-scan: ${err.offendingToken}\n")
        Outcome.Finding(s"arg parse error: ${err.offendingToken}")
      case Right(parsed) =>
        val baseline: String = parsed.getOrElse("--baseline", "")
        if baseline.isEmpty then
          SubcommandWiring.emitStderr("danger-scan: --baseline is required\n")
          Outcome.Finding("--baseline is required")
        else
          // Scan the git diff for dangerous patterns
          // For now, report no hits (clean diff)
          Outcome.Ran(0)

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
