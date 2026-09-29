package org.sinemenda.probatio.migration

import org.sinemenda.probatio.core.*

import scala.util.control.NonFatal // danger-scan:allow subprocess-fixture — predecessor agreement runs the model as a subprocess; failures map to Left
import scala.util.matching.Regex

/**
 * Conformance fixtures for spec: graph-tool-port — materialising a
 * `SourceCorpus` into a temporary repository, reading a repository back
 * into a corpus, running the predecessor export as the executable model,
 * and the per-element diff the disagreement scenario requires.
 *
 * File I/O lives here (test sources) — the shipped core stays pure.
 */
object GraphConformance:

  /** The predecessor script, relative to the repository root. */
  private val predecessorPath: String =
    "openspec/schemas/verified-scala3/scanner/openspec-graph.py"

  /** A maximal run of path characters — candidates for citation tokens. */
  private val PathToken: Regex = "[A-Za-z0-9_./-]+".r

  /**
   * Writes the corpus as a repository tree under `dir`:
   * `openspec/concepts/`, `openspec/concept-inventory.md`, active specs
   * under `openspec/changes/<change>/specs/<cap>/`, archived specs under
   * `openspec/changes/archive/`, plus an empty file at every path in
   * `existingPaths` so filesystem resolution equals the corpus's intent.
   */
  def materialise(corpus: GraphFixtures.SourceCorpus, dir: os.Path): Unit =
    corpus.registry.foreach { case (name: String, text: String) =>
      os.write(dir / "openspec" / "concepts" / name, text, createFolders = true)
    }
    corpus.inventory.foreach { (text: String) =>
      os.write(dir / "openspec" / "concept-inventory.md", text, createFolders = true)
    }
    corpus.activeSpecs.foreach { (s: GraphFixtures.SpecText) =>
      os.write(dir / "openspec" / "changes" / s.change / "specs" / s.capability / "spec.md", s.text, createFolders = true)
    }
    corpus.archivedSpecs.foreach { (s: GraphFixtures.SpecText) =>
      os.write(dir / "openspec" / "changes" / "archive" / s.change / "specs" / s.capability / "spec.md", s.text, createFolders = true)
    }
    corpus.existingPaths.foreach { (p: String) =>
      os.write(dir / os.SubPath(p), "", createFolders = true)
    }

  /**
   * Reads `dir` back into a `SourceCorpus` — the same shapes the adapter
   * reads, as texts. `existingPaths` = every file under `dir` relative
   * to it (excluding `.git`), so resolution is filesystem truth.
   */
  def readRepo(dir: os.Path): Either[String, GraphFixtures.SourceCorpus] =
    try
      val registry: Map[String, String] =
        if os.isDir(dir / "openspec" / "concepts") then
          os.list(dir / "openspec" / "concepts")
            .filter((p: os.Path) => p.last.endsWith(".md") && p.last != "README.md")
            .map((p: os.Path) => p.last -> os.read(p))
            .toMap
        else Map.empty
      val inventory: Option[String] =
        if os.isFile(dir / "openspec" / "concept-inventory.md") then
          Some(os.read(dir / "openspec" / "concept-inventory.md"))
        else None
      val changesDir: os.Path = dir / "openspec" / "changes"
      val specAt: (os.Path, os.Path) => List[GraphFixtures.SpecText] =
        (changeDir: os.Path, capDir: os.Path) =>
          val specFile: os.Path = capDir / "spec.md"
          if os.isFile(specFile) then
            List(GraphFixtures.SpecText(changeDir.last, capDir.last, os.read(specFile)))
          else Nil
      val active: List[GraphFixtures.SpecText] =
        if os.isDir(changesDir) then
          os.list(changesDir)
            .filter((p: os.Path) => os.isDir(p) && p.last != "archive")
            .flatMap((changeDir: os.Path) =>
              if os.isDir(changeDir / "specs") then
                os.list(changeDir / "specs").flatMap((capDir: os.Path) => specAt(changeDir, capDir))
              else Nil
            )
            .toList
        else Nil
      val existing: Set[String] =
        os.walk(dir)
          .filter(os.isFile)
          .filter((p: os.Path) => !p.segments.toList.contains(".git"))
          .map((p: os.Path) => p.relativeTo(dir).toString)
          .toSet
      Right(GraphFixtures.SourceCorpus(registry, inventory, active, Nil, existing))
    catch
      case NonFatal(e) => Left(s"could not read corpus under $dir: ${e.getMessage}")

  /** Runs the ported pipeline (parse + build + export) on `dir`. */
  def exportPorted(dir: os.Path): Either[String, ujson.Value] =
    readRepo(dir).flatMap { (corpus: GraphFixtures.SourceCorpus) =>
      GraphFixtures.buildFrom(corpus).map { (b: GraphBuild) => GraphWire.writeExport(b) }
    }

  /** Runs the predecessor export on `dir` — the executable model. */
  def exportPredecessor(dir: os.Path): Either[String, ujson.Value] =
    val script: os.Path = os.pwd / os.SubPath(predecessorPath)
    if !os.exists(script) then Left(s"predecessor not found at $script")
    else
      try
        // spec: hermetic-test-processes — OPENSPEC_ROOT is the one declared
        // controlled variable; the rest of the invoking environment is
        // filtered out.
        val env: HermeticEnv =
          HermeticEnv.build(Map(ControlledVariable.OpenspecRoot -> dir.toString))
        val r: HermeticResult =
          HermeticEnv.capture(List("python3", script.toString, "export"), env, cwd = Some(dir.toIO))
        if r.exitCode != 0 then Left(s"predecessor export exited ${r.exitCode}: ${r.err.take(200)}")
        else Right(ujson.read(r.out))
      catch
        case NonFatal(e) => Left(s"predecessor export failed: ${e.getMessage}")

  /**
   * The per-element diff between two exports — the disagreement scenario
   * requires naming each differing node and edge, never a summary count.
   *
   * Two sanctioned divergence classes are filtered, both consequences of
   * the gate-approved strict-binding decision:
   *
   *  - `unlinkable only in ported`: the predecessor has no unlinkable
   *    vocabulary — it silently drops rows the port reports. These are
   *    never a divergence.
   *  - predecessor-only `code:P`/`artifact:P` nodes and
   *    `implemented-by`/`verified-by` edges to them: the predecessor
   *    binds path-shaped citations unconditionally; the port binds only
   *    what resolves. Such an element is EXPLAINED — not a divergence —
   *    only when a ported unlinkable row's text or a ported warning
   *    names `P` (the port reported the row rather than dropping it).
   *    An unexplained predecessor-only element means the port lost a
   *    binding without a trace — the defect this test exists to catch.
   */
  def diffExports(ported: ujson.Value, model: ujson.Value): List[String] =
    def nodeIds(v: ujson.Value): Set[String] =
      v.obj.get("nodes").map(_.arr.map((n: ujson.Value) => n("id").str).toSet).getOrElse(Set.empty)
    def edgeKeys(v: ujson.Value): Set[String] =
      v.obj.get("edges").map { arr =>
        arr.arr.map { (e: ujson.Value) =>
          val link: String    = e.obj.get("link").map((x: ujson.Value) => x.str).getOrElse("")
          val planned: String = e.obj.get("planned").map((x: ujson.Value) => x.bool.toString).getOrElse("")
          s"${e("from").str}|${e("rel").str}|${e("to").str}|$link|$planned"
        }.toSet
      }.getOrElse(Set.empty)
    def unlinkableKeys(v: ujson.Value): Set[String] =
      v.obj.get("unlinkable").map { arr =>
        arr.arr.map((r: ujson.Value) => s"${r("source").str}:${r("line").num.toInt}").toSet
      }.getOrElse(Set.empty)
    // Every place the port reports an unbound path — unlinkable row
    // texts plus warnings — used to explain strict-binding divergences.
    val reportedTexts: List[String] =
      ported.obj.get("unlinkable").map(_.arr.toList.map((r: ujson.Value) => r("text").str)).getOrElse(Nil) ++
        ported.obj.get("warnings").map(_.arr.toList.map((w: ujson.Value) => w.str)).getOrElse(Nil)
    // The path-shaped tokens those texts name — the same shape test the
    // impl-map parser applies (`/` and `.`, no space). EXACT membership
    // is required: substring matching would let an unrelated reported
    // path mask a genuinely dropped binding.
    val reportedPaths: Set[String] =
      reportedTexts
        .flatMap((t: String) => PathToken.findAllIn(t).toList)
        .filter((tok: String) => tok.contains("/") && tok.contains(".") && !tok.contains(" "))
        .toSet
    def strictBindingExplained(id: String): Boolean =
      id.stripPrefix("code:").stripPrefix("artifact:") match
        case path if path != id => reportedPaths.contains(path)
        case _                  => false
    val (pn: Set[String], mn: Set[String]) = (nodeIds(ported), nodeIds(model))
    val (pe: Set[String], me: Set[String]) = (edgeKeys(ported), edgeKeys(model))
    val (pu: Set[String], mu: Set[String]) = (unlinkableKeys(ported), unlinkableKeys(model))
    (pn -- mn).toList.sorted.map((id: String) => s"node only in ported: $id") ++
      (mn -- pn).toList.sorted.filterNot(strictBindingExplained).map((id: String) => s"node only in predecessor: $id") ++
      (pe -- me).toList.sorted.map((k: String) => s"edge only in ported: $k") ++
      (me -- pe).toList.sorted.filterNot { (k: String) =>
        val parts: Array[String] = k.split("\\|", -1)
        parts.length >= 3 && strictBindingExplained(parts(2))
      }.map((k: String) => s"edge only in predecessor: $k") ++
      (mu -- pu).toList.sorted.map((k: String) => s"unlinkable only in predecessor: $k")
