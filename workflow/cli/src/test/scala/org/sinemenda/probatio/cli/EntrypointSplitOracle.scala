package org.sinemenda.probatio.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Test support for spec: entrypoint-split
 *
 * The mechanical machinery the Ring 3 oracle shares: the corpus snapshot
 * recorded before the split, the entrypoint-file layout check, and the
 * byte-for-byte moved-body comparison. Pure over its inputs; the only IO is
 * resolving and reading files under the repository root.
 *
 * spec: entrypoint-split — Requirement: Each subcommand's entrypoint resides in its own source file
 * spec: entrypoint-split — Requirement: Moved code is moved, not edited
 * spec: entrypoint-split — Property: split-preserves-every-observable
 */
object EntrypointSplitOracle:

  /** The eleven entrypoint objects, in file order. */
  val entrypointObjects: List[String] = List(
    "GateCmd",
    "SpecLintCmd",
    "ChainStateCmd",
    "GraphCmd",
    "LedgerCmd",
    "CheckpointCmd",
    "ReconcileCmd",
    "DangerScanCmd",
    "MetalsCmd",
    "InstallSkillsCmd",
    "InstallHooksCmd"
  )

  /** One recorded invocation: which artifact, the argv, and the observables. */
  final case class CorpusRow(
      artifact: String,
      argv: List[String],
      exit: Int,
      out: String,
      err: String
  )

  /** The repository root, walked up from the forked test runner's cwd. */
  def repoRoot: Path =
    val start: Path = Path.of("").toAbsolutePath.normalize
    Iterator
      .unfold(start)((p: Path) => Option(p.getParent).map((par: Path) => p -> par))
      .find((p: Path) => Files.isDirectory(p.resolve("openspec/schemas/verified-scala3")))
      .getOrElse(sys.error(s"could not locate the repository root from $start"))

  /** The recorded before-split fixtures. */
  def fixturesDir: Path =
    repoRoot.resolve("workflow/cli/src/test/fixtures/entrypoint-split")

  /** The cli source directory the layout check scans. */
  def cliSourceDir: Path =
    repoRoot.resolve("workflow/cli/src/main/scala/org/sinemenda/probatio/cli")

  /**
   * Parse the recorded corpus: `artifact \t argv \t exit \t b64(stdout) \t
   * b64(stderr)` per line.
   */
  def parseCorpus(lines: List[String]): List[CorpusRow] =
    lines.collect { case line if line.nonEmpty =>
      line.split("\t", -1).toList match
        case List(artifact, argv, exit, out, err) =>
          CorpusRow(
            artifact,
            if argv.isEmpty then List.empty else argv.split(" ").toList,
            exit.toInt,
            new String(Base64.getDecoder.decode(out), java.nio.charset.StandardCharsets.UTF_8),
            new String(Base64.getDecoder.decode(err), java.nio.charset.StandardCharsets.UTF_8)
          )
        case other => sys.error(s"corpus row with ${other.length} fields (expected 5): $line")
    }

  /** The recorded corpus. */
  def loadCorpus: List[CorpusRow] =
    parseCorpus(Files.readAllLines(fixturesDir.resolve("corpus-before.tsv")).toArray(Array.ofDim[String](_)).toList)

  /**
   * The recorded before-split entrypoint source — the comparison baseline
   * for the moved-body check.
   */
  def loadBeforeSource: Vector[String] =
    Files
      .readAllLines(fixturesDir.resolve("SubcommandEntrypoints.before.scala"))
      .toArray(Array.ofDim[String](_))
      .toVector

  // ── Layout check ────────────────────────────────────────────────────────
  //
  // spec: entrypoint-split — Requirement: Each subcommand's entrypoint resides in its own source file

  /**
   * The entrypoint objects each source declares, as `(fileName, objects)`.
   * Only top-level `object <Name>:` declarations count — an entrypoint is a
   * top-level object.
   */
  def entrypointDeclarations(decls: List[(String, List[String])]): List[(String, List[String])] =
    decls.map((f: (String, List[String])) => (f._1, f._2.filter(entrypointObjects.contains)))

  /** Scan a directory's `.scala` files for top-level object declarations. */
  def scanDir(dir: Path): List[(String, List[String])] =
    val files: List[Path] =
      if Files.isDirectory(dir) then
        scala.util
          .Using(Files.list(dir))(
            _.filter((p: Path) => p.getFileName.toString.endsWith(".scala"))
              .sorted(java.util.Comparator.comparing((p: Path) => p.getFileName.toString))
              .toArray(Array.ofDim[Path](_))
              .toList
          )
          .fold((e: Throwable) => sys.error(s"could not list $dir: ${e.getMessage}"), (l: List[Path]) => l)
      else List.empty
    files.map { (p: Path) =>
      val objects: List[String] =
        Files
          .readAllLines(p)
          .toArray(Array.ofDim[String](_))
          .toList
          .collect { case line if line.matches("^object [A-Za-z0-9_]+:.*") =>
            line.trim.stripPrefix("object ").takeWhile(_ != ':')
          }
      (p.getFileName.toString, objects)
    }

  /**
   * Layout violations: an entrypoint missing a file, an entrypoint declared
   * in two files, or a file holding the entrypoints of two subcommands (the
   * report names the file and the entrypoints).
   *
   * spec: entrypoint-split — Scenario: Adversarial — a file holding two entrypoints is reported
   */
  def layoutViolations(decls: List[(String, List[String])]): List[String] =
    val held: List[(String, List[String])] = entrypointDeclarations(decls).filter((d: (String, List[String])) => d._2.nonEmpty)
    val crowded: List[String] = held.collect { case (file, objects) if objects.length > 1 =>
      s"$file holds ${objects.length} entrypoints: ${objects.mkString(", ")}"
    }
    val byObject: Map[String, List[String]] =
      held.flatMap((d: (String, List[String])) => d._2.map((o: String) => o -> d._1)).groupMap(_._1)(_._2)
    val missing: List[String] =
      entrypointObjects.filterNot(byObject.contains).map((o: String) => s"$o has no entrypoint file")
    val splitBrained: List[String] =
      byObject.toList.collect { case (o, files) if files.length > 1 => s"$o is declared in ${files.length} files: ${files.mkString(", ")}" }
    crowded ++ missing ++ splitBrained

  // ── Moved-body comparison ───────────────────────────────────────────────
  //
  // spec: entrypoint-split — Requirement: Moved code is moved, not edited

  private def commentLine(line: String): Boolean =
    val t: String = line.trim
    t.startsWith("/**") || t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")

  /**
   * The span `[start, end)` of `object <name>` including its immediately
   * preceding contiguous comment block. The end is the start of the next
   * top-level object's span, or the last non-blank line of the source.
   */
  def objectSpan(lines: Vector[String], name: String): Option[(Int, Int)] =
    val decls: Vector[Int] = lines.indices.filter((i: Int) => lines(i).matches(s"^object [A-Za-z0-9_]+:.*")).toVector
    def spanStart(decl: Int): Int =
      // The smallest index of the contiguous comment run immediately above
      // the decl (the object's doc block); `decl` itself when there is none.
      (decl - 1 to 0 by -1).takeWhile((i: Int) => commentLine(lines(i))).lastOption.getOrElse(decl)
    decls.find((i: Int) => lines(i).matches(s"^object $name:.*")) match
      case None => None
      case Some(decl) =>
        val start: Int = spanStart(decl)
        val rawEnd: Int = decls.find((d: Int) => d > decl).map(spanStart).getOrElse(lines.length)
        val trailingBlanks: Int =
          (rawEnd - 1 to start by -1).takeWhile((i: Int) => lines(i).trim.isEmpty).size
        Some((start, rawEnd - trailingBlanks))

  /**
   * Compare `object <name>`'s span in `before` against its span in `after`
   * (ignoring package, import and file-header lines — only the object's own
   * span is compared). Empty on identity; otherwise the differing lines.
   *
   * spec: entrypoint-split — Scenario: Adversarial — an edit hidden in the move is detected
   */
  def movedBodyDiffs(before: Vector[String], after: Vector[String], name: String): List[String] =
    (objectSpan(before, name), objectSpan(after, name)) match
      case (None, _)                    => List(s"$name not found in the before source")
      case (_, None)                    => List(s"$name not found in the after source")
      case (Some((bs, be)), Some((as, ae))) =>
        val b: Vector[String] = before.slice(bs, be)
        val a: Vector[String] = after.slice(as, ae)
        if a == b then List.empty
        else
          val firstDiff: Int =
            (0 until math.min(a.length, b.length)).find((k: Int) => a(k) != b(k))
              .getOrElse(math.min(a.length, b.length))
          val width: Int    = math.max(a.length, b.length)
          val diffs: List[String] = (firstDiff until width).toList.flatMap { (k: Int) =>
            val aa: String = if k < a.length then a(k) else "<absent>"
            val bb: String = if k < b.length then b(k) else "<absent>"
            if aa != bb then List(s"line ${k + 1} of $name's body: before='$bb' after='$aa'") else List.empty
          }
          if diffs.nonEmpty then diffs else List(s"$name's body length differs (before ${b.length}, after ${a.length})")
