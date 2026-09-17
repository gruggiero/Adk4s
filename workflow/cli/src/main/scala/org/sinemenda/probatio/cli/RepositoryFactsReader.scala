package org.sinemenda.probatio.cli

import org.sinemenda.probatio.core.ActiveChangeWithChainState
import org.sinemenda.probatio.core.ArtifactRef
import org.sinemenda.probatio.core.ArtifactScan
import org.sinemenda.probatio.core.ChainStateReport
import org.sinemenda.probatio.core.ChainStateUndetermined
import org.sinemenda.probatio.core.DriftScan
import org.sinemenda.probatio.core.FactRead
import org.sinemenda.probatio.core.InstallRootScan
import org.sinemenda.probatio.core.InstallRootState
import org.sinemenda.probatio.core.LintContext
import org.sinemenda.probatio.core.RepositoryFacts
import org.sinemenda.probatio.core.RootBase
import org.sinemenda.probatio.core.StampFormat
import org.sinemenda.probatio.core.UnmappedObligation
import org.sinemenda.probatio.core.UnresolvedEntry
import org.sinemenda.probatio.core.UnresolvedReason

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using
import scala.util.control.NonFatal // danger-scan:allow degraded-fact — a failed read becomes FactRead.Unreadable data, or the fact it feeds is reported not-computed

/**
 * The repository-facts reader (R-P1 adapter).
 *
 * Reads, during one invocation, every fact the banner states:
 * the schema version and artifact DAG from
 * `openspec/schemas/verified-scala3/schema.yaml`, the concept-registry
 * presence and document count, the type-inventory presence and typed-row
 * count, the capability-profile presence and detected test kit, the
 * instruction stamp at each of the six searched install roots (three
 * repository-local, three user-scoped), and each active change's artifact
 * state plus its live chain-state result (obtained through the chain-state
 * tool — `CHAIN_STATE_OVERRIDE` or `scanner/chain-state.sh` — executed as a
 * subprocess, matching the predecessor's seam).
 *
 * A fact that cannot be read is `FactRead.Unreadable`, never `Absent`.
 * Total: `read` always returns a `RepositoryFacts`; failures are data.
 *
 * spec: live-fact-banner — Requirement: Every fact the banner states is read during the run that states it
 * spec: live-fact-banner — Property: facts-reflect-repository
 */
object RepositoryFactsReader:

  private val schemaRel: String =
    "openspec/schemas/verified-scala3/schema.yaml"
  private val chainStateRel: String =
    "openspec/schemas/verified-scala3/scanner/chain-state.sh"
  private val skillDocRel: String = "openspec-spec-lint/SKILL.md"
  private val kitPattern =
    "(?i)(TestControl|TestKit|VirtualTime|TestScheduler)".r
  private val newStamp    = "probatio-schema/(\\d+)".r
  private val legacyStamp = "verified-scala3-schema/(\\d+)".r

  /**
   * Read the repository facts for the gate's banner.
   *
   * @param repoRoot the repository root (resolved from `--repo` or the git
   *                 toplevel by the caller)
   * @param userHome the user-scoped base for the three user install roots
   * @param env      the process environment, read once by the caller
   *                 (`CHAIN_STATE_OVERRIDE` resolves the chain-state tool)
   */
  def read(
    repoRoot: Path,
    userHome: Path,
    env: Map[String, String]
  ): RepositoryFacts =
    val schemaContent: FactRead[String] =
      readText(repoRoot.resolve(schemaRel))
    val schemaVersion: FactRead[Int] = schemaContent match
      case FactRead.Present(text) => schemaVersionOf(text)
      case FactRead.Absent        => FactRead.Absent
      case FactRead.Unreadable(r) => FactRead.Unreadable(r)
    val dag: FactRead[List[ArtifactRef]] = schemaContent match
      case FactRead.Present(text) =>
        parseArtifactDag(text) match
          case Nil  => FactRead.Absent // no `artifacts:` section — no DAG fact
          case refs => FactRead.Present(refs)
      case FactRead.Absent        => FactRead.Absent
      case FactRead.Unreadable(r) => FactRead.Unreadable(r)

    val baseline: String = gitHead(repoRoot).getOrElse("unknown")

    RepositoryFacts(
      schemaVersion = schemaVersion,
      registry = readRegistry(repoRoot),
      inventory = readInventory(repoRoot),
      profile = readProfile(repoRoot),
      installRoots = scanInstallRoots(repoRoot, userHome),
      activeChanges = readActiveChanges(repoRoot, dag, env, baseline)
    )

  /**
   * Read the spec-lint applicability context — the same facts the banner
   * reads (schema version, registry, inventory, profile, install roots)
   * plus the registry `# Concept:` headings and inventory type names the
   * W7 type-scan needs. Every fact is a `FactRead`; `Unreadable`
   * propagates so the engine can report could-not-determine rather than
   * silently linting on fabricated absence.
   *
   * spec: spec-lint-engine — Requirement: Applicability facts are read from the repository at the I/O boundary and passed to the engine as data
   */
  def readLintContext(repoRoot: Path, userHome: Path): LintContext =
    val schemaContent: FactRead[String] =
      readText(repoRoot.resolve(schemaRel))
    val schemaVersion: FactRead[Int] = schemaContent match
      case FactRead.Present(text) => schemaVersionOf(text)
      case FactRead.Absent        => FactRead.Absent
      case FactRead.Unreadable(r) => FactRead.Unreadable(r)
    LintContext(
      schemaVersion = schemaVersion,
      registry = readRegistry(repoRoot),
      registryConcepts = readRegistryConcepts(repoRoot),
      inventoryTypes = readInventoryTypes(repoRoot),
      profile = readProfile(repoRoot),
      installRoots = scanInstallRoots(repoRoot, userHome)
    )

  /**
   * The registry's `# Concept:` headings — the predecessor's
   * `grep -h '^# Concept:'` over the top-level markdown documents in
   * `openspec/concepts/`, each stripped to its title text, sorted and
   * deduplicated. A directory that exists but cannot be scanned is
   * `Unreadable`, never `Absent`.
   */
  private def readRegistryConcepts(repoRoot: Path): FactRead[List[String]] =
    val dir: Path = repoRoot.resolve("openspec/concepts")
    if !Files.isDirectory(dir) then FactRead.Absent
    else
      try
        val docs: List[Path] = Using.resource(Files.list(dir)) { stream =>
          stream
            .iterator()
            .asScala
            .toList
            .filter((p: Path) => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".md"))
        }
        val headings: List[String] = docs
          .flatMap(p => Files.readString(p, StandardCharsets.UTF_8).linesIterator)
          // `grep -h '^# Concept: '` — the space after the colon is required;
          // `# Concept:Foo` is not a concept heading.
          .filter(_.startsWith("# Concept: "))
          .map(_.replaceFirst("^# Concept: *", "").replaceAll("[ \t]+$", ""))
          .sorted
          .distinct
        FactRead.Present(headings)
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — the scan failure IS the fact: Unreadable, not swallowed
          FactRead.Unreadable(s"could not scan $dir: ${e.getMessage}")

  // ── file primitives ───────────────────────────────────────────────────

  /** Read a regular file; absent file → Absent, read failure → Unreadable. */
  private def readText(path: Path): FactRead[String] =
    if !Files.isRegularFile(path) then FactRead.Absent
    else
      try FactRead.Present(Files.readString(path, StandardCharsets.UTF_8))
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — the read failure IS the fact: Unreadable, not swallowed
          FactRead.Unreadable(s"could not read $path: ${e.getMessage}")

  /** Run `git` under `dir`; trimmed stdout on exit 0, else None. Never throws. */
  private def gitOut(dir: Path, args: List[String]): Option[String] =
    try
      val pb: ProcessBuilder = new ProcessBuilder(("git" +: args)*)
      pb.directory(dir.toFile)
      // predecessor parity: `git ... 2>/dev/null` — stderr is discarded, not
      // merged into the parsed stdout value.
      pb.redirectError(ProcessBuilder.Redirect.DISCARD)
      val p: Process = pb.start()
      val out: String =
        new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if p.waitFor() == 0 && out.trim.nonEmpty then Some(out.trim) else None
    catch
      case NonFatal(_) => // danger-scan:allow fail-open — a non-git repo has no HEAD baseline; unknown is honest
        None

  private def gitHead(repoRoot: Path): Option[String] =
    gitOut(repoRoot, List("rev-parse", "HEAD"))

  /**
   * `List[Option[A]]` → `Option[List[A]]` without cats (R-ARCH1: cats is
   * forbidden on this classpath).
   */
  private def sequence[A](opts: List[Option[A]]): Option[List[A]] =
    opts.foldRight(Option(List.empty[A])) { (o, acc) =>
      for
        x  <- o
        xs <- acc
      yield x :: xs
    }

  // ── schema.yaml ───────────────────────────────────────────────────────

  /**
   * The `version:` field — a present file with no parseable version is
   * Unreadable (the version is the fact that could not be read).
   */
  private def schemaVersionOf(text: String): FactRead[Int] =
    text.linesIterator.find(_.startsWith("version:")) match
      case None =>
        FactRead.Unreadable("schema.yaml has no version field")
      case Some(line) =>
        val raw: String = line.substring(line.indexOf(':') + 1).trim
        raw.toIntOption match
          case Some(v) => FactRead.Present(v)
          case None =>
            FactRead.Unreadable(s"schema version is not a number: $raw")

  /**
   * Parse the artifact DAG — the predecessor's awk: inside the
   * `artifacts:` block, `  - id: X` names an artifact and
   * `    generates: Y` gives the file it generates; a new top-level key
   * ends the block. Surrounding quotes on `generates` are stripped.
   */
  private def parseArtifactDag(text: String): List[ArtifactRef] =
    text.linesIterator
      .foldLeft[(Boolean, String, List[ArtifactRef])]((false, "", Nil)) { case ((inA, id, acc), line) =>
        if line.startsWith("artifacts:") then (true, "", acc)
        else if inA && line.matches("[a-z_-]+:.*") then (false, id, acc)
        else if inA && line.startsWith("  - id: ") then (true, line.stripPrefix("  - id: ").trim, acc)
        else if inA && line.startsWith("    generates: ") then
          val gen: String =
            line.stripPrefix("    generates: ").trim.replaceAll("^\"|\"$", "")
          (true, id, acc :+ ArtifactRef(id, gen))
        else (inA, id, acc)
      }
      ._3

  // ── registry / inventory / profile ────────────────────────────────────

  /** `openspec/concepts/` — recursive `*.md` count excluding `README.md`. */
  private def readRegistry(repoRoot: Path): FactRead[Int] =
    val dir: Path = repoRoot.resolve("openspec/concepts")
    if !Files.isDirectory(dir) then FactRead.Absent
    else
      try
        val n: Long = Using.resource(Files.walk(dir)) { stream =>
          stream
            .filter { (p: Path) =>
              Files.isRegularFile(p) &&
              p.getFileName.toString.endsWith(".md") &&
              p.getFileName.toString != "README.md"
            }
            .count()
        }
        FactRead.Present(n.toInt)
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — the scan failure IS the fact: Unreadable, not swallowed
          FactRead.Unreadable(s"could not scan $dir: ${e.getMessage}")

  /**
   * `openspec/concept-inventory.md` — the predecessor's awk rule: first
   * data cell of every `|`-row, backticks stripped, `Foo[A]` → `Foo`,
   * capitalised identifiers only, the header cell `Type` excluded,
   * deduplicated.
   */
  private def readInventory(repoRoot: Path): FactRead[Int] =
    readInventoryTypes(repoRoot) match
      case FactRead.Present(types) => FactRead.Present(types.length)
      case FactRead.Absent         => FactRead.Absent
      case FactRead.Unreadable(r)  => FactRead.Unreadable(r)

  /**
   * The inventory's type names — the same extraction as `readInventory`
   * but returning the deduplicated, sorted name list the W7 type scan
   * needs (the predecessor's `sort -u` side of the `comm -23`).
   */
  private def readInventoryTypes(repoRoot: Path): FactRead[List[String]] =
    readText(repoRoot.resolve("openspec/concept-inventory.md")) match
      case FactRead.Present(text) =>
        val cells: List[String] = text.linesIterator
          .filter(_.startsWith("|"))
          .flatMap { (line: String) =>
            val fields: Array[String] = line.split("\\|", -1)
            if fields.length > 1 then Some(fields(1)) else None
          }
          .toList
        val types: List[String] = cells
          .map(c => c.replace("`", "").trim.replaceAll("\\[.*", ""))
          .filter(c => c.matches("[A-Z][A-Za-z0-9_]*") && c != "Type")
          .distinct
          .sorted
        FactRead.Present(types)
      case FactRead.Absent        => FactRead.Absent
      case FactRead.Unreadable(r) => FactRead.Unreadable(r)

  /**
   * `openspec/capability-profile.md` — the deterministic test kit named
   * in the file (deduped, sorted, space-joined), or None when the file
   * names no kit.
   */
  private def readProfile(repoRoot: Path): FactRead[Option[String]] =
    readText(repoRoot.resolve("openspec/capability-profile.md")) match
      case FactRead.Present(text) =>
        val found: List[String] =
          kitPattern.findAllIn(text).toList.distinct.sorted
        FactRead.Present(
          if found.isEmpty then None else Some(found.mkString(" "))
        )
      case FactRead.Absent        => FactRead.Absent
      case FactRead.Unreadable(r) => FactRead.Unreadable(r)

  // ── install roots ─────────────────────────────────────────────────────

  /** Scan every searched install root — all six, never narrowed. */
  private def scanInstallRoots(
    repoRoot: Path,
    userHome: Path
  ): List[InstallRootScan] =
    DriftScan.installRoots.all.map { ref =>
      val base: Path = ref.base match
        case RootBase.RepoRoot => repoRoot
        case RootBase.UserHome => userHome
      val root: Path = base.resolve(ref.relativePath)
      val doc: Path  = root.resolve(skillDocRel)
      val state: InstallRootState =
        if !Files.isRegularFile(doc) then InstallRootState.Absent
        else readStamp(doc)
      InstallRootScan(root.toString, state)
    }

  /**
   * The `generatedBy:` stamp: `probatio-schema/<N>` (New) or the
   * pre-rename `verified-scala3-schema/<N>` (Legacy); major version only.
   * A `generatedBy:` line with neither stamp is `PresentNoStamp`.
   */
  private def readStamp(doc: Path): InstallRootState =
    try
      val text: String = Files.readString(doc, StandardCharsets.UTF_8)
      text.linesIterator.find(_.contains("generatedBy:")) match
        case None => InstallRootState.PresentNoStamp
        case Some(l) =>
          (newStamp.findFirstMatchIn(l), legacyStamp.findFirstMatchIn(l)) match
            case (Some(m), _) =>
              InstallRootState.Stamped(m.group(1).toInt, StampFormat.New)
            case (_, Some(m)) =>
              InstallRootState.Stamped(m.group(1).toInt, StampFormat.Legacy)
            case _ => // danger-scan:allow honest-default — a generatedBy line with no recognised stamp is PresentNoStamp
              InstallRootState.PresentNoStamp
    catch
      case NonFatal(e) => // danger-scan:allow degraded-fact — read failure IS the fact: Unreadable, not swallowed
        InstallRootState.Unreadable(s"could not read $doc: ${e.getMessage}")

  // ── active changes + chain state ──────────────────────────────────────

  /**
   * Every directory under `openspec/changes/` except `archive/`, in
   * lexical order — the predecessor's glob. A missing changes dir is
   * `Absent` (the repository genuinely has no changes); a changes dir that
   * exists but cannot be listed is `Unreadable` — the spec forbids
   * reporting an empty active-change list when the listing itself failed.
   */
  private def readActiveChanges(
    repoRoot: Path,
    dag: FactRead[List[ArtifactRef]],
    env: Map[String, String],
    baseline: String
  ): FactRead[List[ActiveChangeWithChainState]] =
    val changesDir: Path = repoRoot.resolve("openspec/changes")
    if !Files.exists(changesDir) then FactRead.Absent
    else
      try
        val listed: List[Path] =
          Using.resource(Files.list(changesDir)) { (stream: java.util.stream.Stream[Path]) =>
            stream.iterator().asScala.toList
          }
        val names: List[String] = listed
          .filter(Files.isDirectory(_))
          .map(_.getFileName.toString)
          .filter(_ != "archive")
          .sorted
        FactRead.Present(names.map { (name: String) =>
          val chg: Path = changesDir.resolve(name)
          val artifacts: FactRead[ArtifactScan] = dag match
            case FactRead.Present(refs) =>
              FactRead.Present(scanChangeArtifacts(chg, refs))
            case FactRead.Absent        => FactRead.Absent
            case FactRead.Unreadable(r) => FactRead.Unreadable(r)
          ActiveChangeWithChainState(
            name = name,
            artifacts = artifacts,
            chainState = computeChainState(repoRoot, chg, name, baseline, env)
          )
        })
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — a failed listing IS the fact
          FactRead.Unreadable(s"could not list $changesDir: ${e.getMessage}")

  /**
   * Which DAG artifacts exist in the change dir, and the first that does
   * not. A `generates` whose value is a glob under a directory (the
   * specs-glob row) checks that directory; anything else checks the
   * file — the predecessor's rule.
   */
  private def scanChangeArtifacts(
    chg: Path,
    refs: List[ArtifactRef]
  ): ArtifactScan =
    val present: List[String] =
      refs.filter(a => artifactExists(chg, a.generates)).map(_.id)
    val next: Option[ArtifactRef] =
      refs.find(a => !artifactExists(chg, a.generates))
    ArtifactScan(present, next)

  private def artifactExists(chg: Path, generates: String): Boolean =
    if generates.contains("/*") then
      Files.isDirectory(
        chg.resolve(generates.substring(0, generates.indexOf('/')))
      )
    else Files.isRegularFile(chg.resolve(generates))

  /**
   * Invoke the chain-state tool exactly as the predecessor does:
   * `bash <tool> --change-dir <chg> --change <name> --baseline <base>` with
   * cwd=repo. `CHAIN_STATE_OVERRIDE` selects an alternate tool; otherwise
   * the repo's own `scanner/chain-state.sh`. A measured report requires
   * exit 0 or 1 AND a numeric `total` — a null-total undetermined report
   * is never rendered as a clean zero.
   */
  private def computeChainState(
    repoRoot: Path,
    chg: Path,
    name: String,
    baseline: String,
    env: Map[String, String]
  ): Either[ChainStateUndetermined, ChainStateReport] =
    val tool: Path = env.get("CHAIN_STATE_OVERRIDE").filter(_.nonEmpty) match
      case Some(p) => Path.of(p)
      case None    => repoRoot.resolve(chainStateRel)
    if !Files.exists(tool) then
      Left(
        ChainStateUndetermined(
          name,
          baseline,
          "chain state could not be computed (chain-state.sh exit 127)"
        )
      )
    else
      try
        val pb: ProcessBuilder = new ProcessBuilder(
          "bash",
          tool.toString,
          "--change-dir",
          chg.toString,
          "--change",
          name,
          "--baseline",
          baseline
        )
        pb.directory(repoRoot.toFile)
        // predecessor parity: `bash tool ... 2>/dev/null` — stderr is
        // discarded; merged noise would corrupt the report's JSON parse.
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        val p: Process = pb.start()
        val out: String =
          new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
        val code: Int = p.waitFor()
        if (code == 0 || code == 1) && totalIsNumber(out) then
          parseReport(name, out) match
            case Some(report) => Right(report)
            case None =>
              Left(
                ChainStateUndetermined(
                  name,
                  baseline,
                  "chain state could not be computed " +
                    "(report did not satisfy the contract)"
                )
              )
        else
          Left(
            ChainStateUndetermined(
              name,
              baseline,
              s"chain state could not be computed (chain-state.sh exit $code)"
            )
          )
      catch
        case NonFatal(e) => // danger-scan:allow degraded-fact — a failed spawn is undetermined evidence
          Left(
            ChainStateUndetermined(
              name,
              baseline,
              s"chain state could not be computed (spawn failed: ${e.getMessage})"
            )
          )

  /** The predecessor's jq guard: `(.total | type) == "number"`. */
  private def totalIsNumber(out: String): Boolean =
    try
      ujson.read(out).obj.get("total") match
        case Some(ujson.Num(_)) => true
        case _ => false // danger-scan:allow type-rejection — a non-numeric total is not a measurement, never true
    catch case NonFatal(_) => false // danger-scan:allow degraded-fact — unparseable output is not a measurement

  /**
   * Parse a measured report into the contract shape; unparseable fields
   * (unknown reason strings, non-string keys) make the report
   * undetermined rather than fabricated.
   */
  private def parseReport(
    name: String,
    out: String
  ): Option[ChainStateReport] =
    try
      val obj: scala.collection.mutable.Map[String, ujson.Value] =
        ujson.read(out).obj
      def num(k: String): Option[Int] = obj.get(k) match
        case Some(ujson.Num(n)) => Some(n.toInt)
        case _ => None // danger-scan:allow type-rejection — non-number maps to None, never a valid count
      def str(k: String): Option[String] = obj.get(k) match
        case Some(ujson.Str(s)) => Some(s)
        case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid field
      val unresolved: Option[List[UnresolvedEntry]] =
        obj.get("unresolved") match
          case Some(ujson.Arr(items)) =>
            sequence(items.toList.map { (v: ujson.Value) =>
              for
                req <- v.obj.get("requirement") match
                  case Some(ujson.Str(s)) => Some(s)
                  case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid entry
                sp <- v.obj.get("spec") match
                  case Some(ujson.Str(s)) => Some(s)
                  case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid entry
                rss <- v.obj.get("reasons") match
                  case Some(ujson.Arr(rs)) =>
                    sequence(rs.toList.map {
                      case ujson.Str(s) => UnresolvedReason.fromString(s)
                      case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid reason
                    })
                  case _ => None // danger-scan:allow type-rejection — non-array maps to None, never a valid entry
                entry <- UnresolvedEntry.of(sp, req, rss)
              yield entry
            })
          case _ => Some(Nil) // danger-scan:allow absent-key — no unresolved key means empty (jq parity)
      val unmapped: Option[List[UnmappedObligation]] =
        obj.get("unmapped_obligations") match
          case Some(ujson.Arr(items)) =>
            sequence(items.toList.map { (v: ujson.Value) =>
              for
                sp <- v.obj.get("spec") match
                  case Some(ujson.Str(s)) => Some(s)
                  case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid entry
                ln <- v.obj.get("line") match
                  case Some(ujson.Num(n)) => Some(n.toInt)
                  case _ => None // danger-scan:allow type-rejection — non-number maps to None, never a valid entry
                art <- v.obj.get("artifact") match
                  case Some(ujson.Str(s)) => Some(s)
                  case _ => None // danger-scan:allow type-rejection — non-string maps to None, never a valid entry
              yield UnmappedObligation(sp, ln, art)
            })
          case _ => Some(Nil) // danger-scan:allow absent-key — no unmapped_obligations key means empty (jq parity)
      for
        t  <- num("total")
        b  <- num("bound")
        r  <- num("resolved")
        d  <- num("discharged")
        u  <- unresolved
        um <- unmapped
        report <- ChainStateReport
          .fromCounts(
            str("change").getOrElse(name),
            str("baseline").getOrElse(
              "unknown"
            ), // danger-scan:allow jq-parity — a report lacking baseline displays "unknown" (predecessor `// "unknown"`)
            t,
            b,
            r,
            d,
            u,
            um
          )
          .toOption
      yield report
    catch
      case NonFatal(_) => // danger-scan:allow degraded-fact — a contract-violating report is undetermined
        None

end RepositoryFactsReader
