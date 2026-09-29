package org.sinemenda.probatio.core

import hedgehog.Gen
import hedgehog.Range

/**
 * Test fixtures for spec: graph-tool-port — the constructive source
 * corpus and graph generators the four properties quantify over.
 *
 * `SourceCorpus` models the three sources AS TEXTS — the same shape the
 * adapter hands the parsers — plus `existingPaths`, the set of
 * repository-relative paths that resolve (the predicates
 * `TraceabilityGraph.build` injects). `archivedSpecs` are part of the
 * fixture precisely so tests can assert they are never parsed.
 *
 * spec: graph-tool-port — Property: unlinkable-rows-are-conserved
 * spec: graph-tool-port — Property: reachability-is-transitive-and-grounded
 * spec: graph-tool-port — Property: export-round-trips
 */
object GraphFixtures:

  // ── Kernel-encoding helpers (Ring 6 bridge) ─────────────────────────────

  /** Convert a Scala List to a Stainless List. */
  def scalaToStainlessList[A](xs: List[A]): stainless.collection.List[A] =
    stainless.collection.List.fromScala(xs)

  /** Convert a Stainless List to a Scala List. */
  def stainlessListToScala[A](xs: stainless.collection.List[A]): List[A] =
    xs match
      case stainless.collection.Nil()      => List.empty
      case stainless.collection.Cons(h, t) => h :: stainlessListToScala(t)

  /**
   * Encode a graph's follow-edge set (`enforced-by`/`verified-by`) as
   * BigInt pairs over a fresh id map, with the requirement and artifact
   * id lists the kernel's `audit` consumes. `resolving` names the
   * artifact paths the adapter would resolve — the kernel's artifact
   * list is the RESOLVED artifact set, mirroring the shipped audit's
   * `artifactResolves` predicate (an unresolving artifact is not a
   * terminus in either implementation).
   */
  def encodeForKernel(
    graph: TraceabilityGraph,
    resolving: String => Boolean
  ): (stainless.collection.List[BigInt],
      stainless.collection.List[(BigInt, BigInt)],
      stainless.collection.List[BigInt]) =
    val ids: Map[String, BigInt] =
      graph.nodes.zipWithIndex.map { case (n: GraphNode, i: Int) => n.id -> BigInt(i + 1) }.toMap
    val edges: List[(BigInt, BigInt)] =
      graph.edges
        .filter((e: Edge) => e.rel == GraphEdge.EnforcedBy || e.rel == GraphEdge.VerifiedBy)
        .flatMap { (e: Edge) =>
          for f <- ids.get(e.from); t <- ids.get(e.to) yield (f, t)
        }
    val reqs: List[BigInt] =
      graph.requirements.flatMap((r: GraphNode.Requirement) => ids.get(r.id))
    val artifacts: List[BigInt] =
      graph.nodes.collect { case a: GraphNode.Artifact if resolving(a.path) => a }
        .flatMap((a: GraphNode.Artifact) => ids.get(a.id))
        .toList
    (scalaToStainlessList(reqs), scalaToStainlessList(edges), scalaToStainlessList(artifacts))


  /** One spec document: change name, capability name, spec.md text. */
  final case class SpecText(change: String, capability: String, text: String)

  /**
   * A source corpus as texts. `inventory = None` models the unreadable
   * source — `buildFrom` reports it as could-not-determine, never as a
   * silently-empty graph.
   */
  final case class SourceCorpus(
    registry: Map[String, String],
    inventory: Option[String],
    activeSpecs: List[SpecText],
    archivedSpecs: List[SpecText],
    existingPaths: Set[String]
  ):

    /**
     * Every table row the parser's read-set contains — the conservation
     * denominator, counted INDEPENDENTLY of the parsers (mirrors, not
     * calls, `tableRows`): archived specs are never parsed so they
     * contribute nothing.
     */
    def rowCount: Int =
      registry.values.toList.map(countImplMapRows).sum +
        inventory.map(countTableRows).getOrElse(0) +
        activeSpecs.map((s: SpecText) => countSpecRows(s.text)).sum

  /** Cells of a `|` row — mirrors `cellsOf` (strip edge bars, split, trim). */
  private def rowCells(line: String): List[String] =
    line.trim.replaceAll("^\\|+", "").replaceAll("\\|+$", "")
      .split("\\|", -1).toList.map((c: String) => c.trim)

  /** Every cell `:?-{2,}:?` or empty — the markdown rule row. */
  private def isSepRow(line: String): Boolean =
    rowCells(line).forall { (c: String) =>
      val noLead: String = if c.startsWith(":") then c.substring(1) else c
      val core: String   = if noLead.endsWith(":") then noLead.substring(0, noLead.length - 1) else noLead
      c.isEmpty || (core.length >= 2 && core.forall((ch: Char) => ch == '-'))
    }

  /** The marker first-cells — "nothing here"/comment rows are not read. */
  private def isMarkerFirst(cells: List[String]): Boolean =
    val c: String = cells.headOption.getOrElse("").trim
    c.isEmpty || c == "(none)" || c == "—" || c == "-" || c.contains("<!--")

  /**
   * The contiguous `|` block at/after `start` — the `tableRows` scan
   * shape: non-`|` lines skipped until the first row, the first non-`|`
   * line after that ends the table.
   */
  private def tableBlock(lines: List[String], start: Int): List[String] =
    lines.drop(start + 1).map((l: String) => l.trim)
      .dropWhile((l: String) => !l.startsWith("|"))
      .takeWhile((l: String) => l.startsWith("|"))

  /** Data rows of the table under `heading` — block minus separators minus header. */
  private def tableDataRows(lines: List[String], heading: String): List[List[String]] =
    lines.indexWhere((l: String) => l.startsWith(heading)) match
      case -1    => Nil
      case start => tableBlock(lines, start).filterNot(isSepRow).drop(1).map(rowCells)

  /** impl-map rows in a concept file — every data row is read (no marker exemption). */
  def countImplMapRows(text: String): Int =
    tableDataRows(text.split("\n", -1).toList, "## Implementation map").length

  /**
   * `|` rows inside `## ` sections of an inventory file — the
   * predecessor's section scan, so header rows count and separators
   * don't. `|` rows before any section are not read.
   */
  def countTableRows(text: String): Int =
    text.split("\n", -1).toList.foldLeft((false, 0)) {
      case ((inSection: Boolean, n: Int), line: String) =>
        if line.startsWith("## ") then (true, n)
        else if inSection && line.trim.startsWith("|") && !isSepRow(line) then (inSection, n + 1)
        else (inSection, n)
    }._2

  /**
   * Graph-table rows in a spec text — non-marker data rows in the three
   * concept tables plus every data row in Proof Obligations.
   */
  def countSpecRows(text: String): Int =
    val lines: List[String] = text.split("\n", -1).toList
    val conceptRows: Int =
      List(
        "## Concepts Used (behavioral)",
        "## Concepts Used (from inventory)",
        "## Concepts Introduced"
      ).map((h: String) =>
        tableDataRows(lines, h).count((cells: List[String]) => !isMarkerFirst(cells))
      ).sum
    conceptRows + tableDataRows(lines, "## Proof Obligations").length

  /**
   * Parses the corpus and builds the graph — the pure half of
   * `GraphCmd.buildGraph`. `None` inventory is the unreadable source:
   * `Left` naming it, no partial graph.
   */
  def buildFrom(corpus: SourceCorpus): Either[String, GraphBuild] =
    corpus.inventory match
      case None => Left("could not read openspec/concept-inventory.md")
      case Some(invText) =>
        val parsed: Either[String, List[ConceptRegistryDoc]] =
          corpus.registry.toList.sortBy(_._1).foldLeft[Either[String, List[ConceptRegistryDoc]]](Right(Nil)) {
            (acc: Either[String, List[ConceptRegistryDoc]], kv: (String, String)) =>
              acc.flatMap { (docs: List[ConceptRegistryDoc]) =>
                ConceptRegistryDoc.parse(s"openspec/concepts/${kv._1}", kv._2) match
                  case Left(_)     => Right(docs) // warning path — predecessor skips the file
                  case Right(doc)  => Right(docs :+ doc)
              }
          }
        parsed.map { (regDocs: List[ConceptRegistryDoc]) =>
          TraceabilityGraph.build(
            regDocs,
            InventoryDoc.parse("openspec/concept-inventory.md", invText),
            corpus.activeSpecs.map { (s: SpecText) =>
              SpecGraphDoc.parse(s.change, s.capability, s"openspec/changes/${s.change}/specs/${s.capability}/spec.md", s.text)
            },
            (p: String) => corpus.existingPaths.contains(p),
            (p: String) => corpus.existingPaths.contains(p)
          )
        }

  // ── Minimal corpus constructors ─────────────────────────────────────

  def registryText(concept: String, implCitations: List[String]): String =
    val citations: String =
      if implCitations.isEmpty then "| element | (none) |"
      else
        implCitations.map((p: String) => s"| element `$concept.x` | (`$p`) |").mkString("\n       |")
    s"""# Concept: $concept
       |
       |```
       |concept $concept
       |purpose
       |    fixture concept
       |```
       |
       |## Implementation map
       |
       || Element | Code |
       ||---|---|
       |$citations
       |""".stripMargin

  def inventoryText(names: List[String]): String =
    val rows: String =
      names.map((n: String) => s"| `$n` | `String` | `pkg` | pre-existing |").mkString("\n       |")
    s"""## Refined / Opaque Types
       |
       || Type | Underlying | Package | Introduced By |
       ||---|---|---|---|
       |$rows
       |""".stripMargin

  /**
   * A spec text with `reqCount` requirements and `obligations` rows of
   * `(source, artifact)` — artifact "" means the cell names nothing
   * (artifactless), `Requirement N` sources link explicitly.
   */
  def specText(reqCount: Int, obligations: List[(String, String)]): String =
    val reqs: String = (1 to reqCount).map { (n: Int) =>
      s"""### Requirement: Req $n
         |
         |The system SHALL satisfy requirement $n.
         |
         |#### Scenario: covers $n
         |
         |**Given** input
         |**When** it runs
         |**Then** it holds
         |""".stripMargin
    }.mkString("\n")
    val rows: String =
      if obligations.isEmpty then ""
      else
        obligations.map { case (src: String, art: String) =>
          s"| oblig | $src | test | $art |"
        }.mkString("\n       |")
    s"""# Spec: fixture
       |
       |## Concepts Used (behavioral)
       |
       || Concept | Role here | File |
       ||---------|-----------|------|
       || (none) | Workflow tooling only | — |
       |
       |$reqs
       |## Proof Obligations
       |
       || Obligation | Source | Enforcement | Artifact |
       ||------------|--------|-------------|----------|
       |$rows
       |""".stripMargin

  /** A corpus that binds everything — `existingPaths` covers every citation. */
  def resolvableCorpus: SourceCorpus =
    val codePath: String = "workflow/core/src/main/scala/org/sinemenda/probatio/core/GraphNode.scala"
    SourceCorpus(
      registry = Map("fixture-concept.md" -> registryText("FixtureConcept", List(codePath))),
      inventory = Some(inventoryText(List("FixtureType"))),
      activeSpecs = List(SpecText("fix-change", "cap", specText(1, List("Requirement 1" -> "tests/t.bats")))),
      archivedSpecs = Nil,
      existingPaths = Set(codePath, "tests/t.bats")
    )

  // ── Generators ──────────────────────────────────────────────────────

  /** Lowercase identifier-ish names for generated rows. */
  private def genName(prefix: String): Gen[String] =
    Gen.int(Range.linear(1, 999)).map((n: Int) => s"$prefix$n")

  /**
   * A path-shaped token — deliberately including edge-case characters
   * the spec calls out: quotes are avoided inside the token itself (the
   * predecessor's backtick extraction can't see them), but the LABELS
   * around rows exercise quotes/newlines/non-ASCII in spec titles.
   */
  private def genPath(existing: Set[String]): Gen[String] =
    val missing: Gen[String] = genName("missing/path/File").map((s: String) => s + ".scala")
    existing.toList match
      case h :: t => Gen.choice1(Gen.element(h, t), missing)
      case Nil    => missing

  /**
   * `genSourceCorpus` — constructive: registry, inventory and spec texts
   * built from a closed alphabet of row shapes (resolvable citations,
   * citations to missing paths, marker rows). Sizes 0–4 rows per source.
   */
  def genSourceCorpus: Gen[SourceCorpus] =
    val knownPaths: Set[String] = Set("a/B.scala", "c/D.scala", "e/F.scala")
    for
      regRows     <- Gen.int(Range.linear(0, 4))
      invRows     <- Gen.int(Range.linear(0, 4))
      reqCount    <- Gen.int(Range.linear(0, 4))
      obligCount  <- Gen.int(Range.linear(0, 4))
      citations   <- Gen.list(genPath(knownPaths), Range.linear(math.min(regRows, 1), regRows))
      invNames    <- Gen.list(genName("Type"), Range.linear(0, invRows))
      obligs      <- Gen.list(
                       Gen.choice1(
                         Gen.int(Range.linear(1, math.max(1, reqCount))).map((n: Int) => s"Requirement $n"),
                         Gen.constant("Requirement 99"),
                         Gen.constant("see above")
                       ).flatMap { (src: String) =>
                         Gen.choice1(
                           knownPaths.toList match
                             case h :: t => Gen.element(h, t)
                             case Nil    => Gen.constant("a/B.scala"),
                           Gen.constant("")
                         ).map((art: String) => (src, art))
                       },
                       Range.linear(0, obligCount)
                     )
    yield SourceCorpus(
      registry =
        if citations.isEmpty then Map.empty
        else Map("gen-concept.md" -> registryText("GenConcept", citations)),
      inventory = Some(inventoryText(invNames)),
      activeSpecs = List(SpecText("gen-change", "cap", specText(reqCount, obligs))),
      archivedSpecs = List(SpecText("archived-change", "cap", specText(1, List("Requirement 1" -> "a/B.scala")))),
      existingPaths = knownPaths + "tests/t.bats"
    )

  // ── genGraph — constructive over chain shapes ───────────────────────

  /** The obligation chain shape one requirement gets. */
  enum ChainShape:
    case NoObligation
    case ObligationNoArtifact
    case ObligationWithArtifact(resolving: Boolean)
    case TwoObligations
    case Cycle

  /** A generated graph plus which artifact paths resolve. */
  final case class GeneratedGraph(graph: TraceabilityGraph, resolving: Set[String])

  private def genShape: Gen[ChainShape] =
    Gen.choice1(
      Gen.constant(ChainShape.NoObligation),
      Gen.constant(ChainShape.ObligationNoArtifact),
      Gen.constant(ChainShape.ObligationWithArtifact(true)),
      Gen.constant(ChainShape.ObligationWithArtifact(false)),
      Gen.constant(ChainShape.TwoObligations),
      Gen.constant(ChainShape.Cycle)
    )

  def genGraph: Gen[GeneratedGraph] =
    for
      reqCount <- Gen.int(Range.linear(0, 10))
      shapes   <- Gen.list(genShape, Range.constant(reqCount, reqCount))
      unlCount <- Gen.int(Range.linear(0, 3))
    yield
      val specId: String = "ch/cap"
      val specNode: GraphNode = GraphNode.Spec("ch", "cap", "openspec/changes/ch/specs/cap/spec.md")
      val acc: (Vector[GraphNode], List[Edge], Set[String], Int) =
        shapes.zipWithIndex.foldLeft((Vector(specNode), List.empty[Edge], Set.empty[String], 0)) {
          case ((ns: Vector[GraphNode], es: List[Edge], res: Set[String], oi: Int), (shape: ChainShape, i: Int)) =>
            val n: Int = i + 1
            val req: GraphNode.Requirement = GraphNode.Requirement(specId, n, s"req $n")
            val reqEdge: Edge = Edge.plain(specNode.id, GraphEdge.HasRequirement, req.id)
            shape match
              case ChainShape.NoObligation =>
                (ns :+ req, es :+ reqEdge, res, oi)
              case ChainShape.ObligationNoArtifact =>
                val ob: GraphNode.Obligation =
                  GraphNode.Obligation(specId, oi + 1, s"ob$oi", "test", None)
                (ns ++ Vector(req, ob), es ++ List(reqEdge, Edge(req.id, GraphEdge.EnforcedBy, ob.id, None, Some(ObligationLink.Explicit))), res, oi + 1)
              case ChainShape.ObligationWithArtifact(resolving: Boolean) =>
                val ob: GraphNode.Obligation =
                  GraphNode.Obligation(specId, oi + 1, s"ob$oi", "test", None)
                val artPath: String = s"art/$oi.scala"
                val art: GraphNode.Artifact = GraphNode.Artifact(artPath)
                (ns ++ Vector(req, ob, art),
                 es ++ List(
                   reqEdge,
                   Edge(req.id, GraphEdge.EnforcedBy, ob.id, None, Some(ObligationLink.Explicit)),
                   Edge.plain(ob.id, GraphEdge.VerifiedBy, art.id)
                 ),
                 if resolving then res + artPath else res,
                 oi + 1)
              case ChainShape.TwoObligations =>
                val ob1: GraphNode.Obligation =
                  GraphNode.Obligation(specId, oi + 1, s"ob$oi", "test", None)
                val ob2: GraphNode.Obligation =
                  GraphNode.Obligation(specId, oi + 2, s"ob${oi + 1}", "test", None)
                val artPath: String = s"art/$oi.scala"
                val art: GraphNode.Artifact = GraphNode.Artifact(artPath)
                (ns ++ Vector(req, ob1, ob2, art),
                 es ++ List(
                   reqEdge,
                   Edge(req.id, GraphEdge.EnforcedBy, ob1.id, None, Some(ObligationLink.Inferred)),
                   Edge(req.id, GraphEdge.EnforcedBy, ob2.id, None, Some(ObligationLink.Explicit)),
                   Edge.plain(ob2.id, GraphEdge.VerifiedBy, art.id)
                 ),
                 res + artPath,
                 oi + 2)
              case ChainShape.Cycle =>
                val ob: GraphNode.Obligation =
                  GraphNode.Obligation(specId, oi + 1, s"ob$oi", "test", None)
                (ns ++ Vector(req, ob),
                 es ++ List(
                   reqEdge,
                   Edge(req.id, GraphEdge.EnforcedBy, ob.id, None, Some(ObligationLink.Title)),
                   Edge(ob.id, GraphEdge.VerifiedBy, req.id, None, Some(ObligationLink.Inferred))
                 ),
                 res,
                 oi + 1)
        }
      val (nodes: Vector[GraphNode], edges: List[Edge], resolving: Set[String], _: Int) = acc
      val unlinkable: List[UnlinkableRow] = (1 to unlCount).toList.map { (i: Int) =>
        UnlinkableRow("gen.md", i, s"row $i", UnlinkableReason.stated(s"reason $i"))
      }
      GeneratedGraph(TraceabilityGraph(nodes, edges, unlinkable), resolving)

  /**
   * The independent reachability model the property asserts against —
   * plain BFS over `verified-by`/`enforced-by` edges to an artifact node
   * whose path resolves, deliberately NOT the shipped `GraphAudit`
   * implementation.
   */
  def reachesArtifact(graph: TraceabilityGraph, from: String, resolves: String => Boolean): Boolean =
    val follow: Set[GraphEdge] = Set(GraphEdge.EnforcedBy, GraphEdge.VerifiedBy)
    def step(frontier: List[String], seen: Set[String]): Boolean =
      frontier match
        case Nil => false
        case id :: rest =>
          graph.node(id) match
            case Some(a: GraphNode.Artifact) if resolves(a.path) => true
            case _ =>
              val next: List[String] =
                graph.outgoing(id, None).filter((e: Edge) => follow.contains(e.rel)).map(_.to)
                  .filter((t: String) => !seen.contains(t))
              step(rest ++ next, seen ++ next.toSet)
    step(List(from), Set(from))

  /**
   * `genUnlinkableRow` — labels carrying the edge cases the spec calls
   * out: quotes, newlines, non-ASCII characters.
   */
  def genUnlinkableRow: Gen[UnlinkableRow] =
    for
      src    <- Gen.choice1(
                  genName("src/file").map((s: String) => s + ".md"),
                  Gen.constant("with \"quote\".md"),
                  Gen.constant("ünïcode-文件.md")
                )
      line   <- Gen.int(Range.linear(1, 500))
      text   <- Gen.choice1(
                  genName("row"),
                  Gen.constant("a \"quoted\" row"),
                  Gen.constant("a row\nwith newline"),
                  Gen.constant("ünïcode røw 文字")
                )
      reason <- genName("reason ")
    yield UnlinkableRow(src, line, text, UnlinkableReason.stated(reason))
