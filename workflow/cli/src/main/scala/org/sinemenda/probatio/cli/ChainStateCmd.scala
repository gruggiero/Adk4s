package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.util.control.NonFatal // danger-scan:allow fail-open — the gate hook degrades silently, it never fails a session

/**
 * Subcommand entrypoint — moved verbatim out of `SubcommandEntrypoints.scala`
 * by `entrypoint-split` (spec: finish-probatio-replacement/entrypoint-split).
 * The body is byte-identical to its origin; the only additions are the
 * package clause and the imports a separate file requires.
 */

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
