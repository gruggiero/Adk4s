package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

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
