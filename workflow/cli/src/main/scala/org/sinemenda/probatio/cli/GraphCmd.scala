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
