package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

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
