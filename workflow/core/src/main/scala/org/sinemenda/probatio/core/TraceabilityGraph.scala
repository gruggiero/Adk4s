package org.sinemenda.probatio.core

/**
 * The traceability graph: the nodes every source contributed, the edges
 * between them, and the rows that were read but could not be bound.
 *
 * `unlinkable` is a required field, not an Option — a graph that cannot
 * name its dropped rows reports reachability optimistically, which is
 * exactly the confidently-wrong failure mode this tool exists to detect.
 * There is no `apply` that defaults it to empty: a graph without its
 * unlinkable rows does not compile.
 *
 * Node order is the predecessor's: all nodes of each builder in source
 * order (concepts first, then inventory, then specs), de-duplicated on
 * first occurrence — later re-declarations merge attributes via
 * `GraphNode.merge` and keep the first position. Edge order is emission
 * order.
 *
 * spec: graph-tool-port — Concepts Introduced (new): TraceabilityGraph
 * spec: graph-tool-port — Compile-Negative: A graph that forgets unlinkable rows
 */
final case class TraceabilityGraph(
  nodes: Vector[GraphNode],
  edges: List[Edge],
  unlinkable: List[UnlinkableRow]
):

  /** The node carrying `id`, if it was contributed by any source. */
  def node(id: String): Option[GraphNode] =
    nodes.find((n: GraphNode) => n.id == id)

  /** Edges leaving `id`, optionally restricted to `rel`. */
  def outgoing(id: String, rel: Option[GraphEdge]): List[Edge] =
    edges.filter((e: Edge) => e.from == id && rel.forall((r: GraphEdge) => e.rel == r))

  /** Edges entering `id`, optionally restricted to `rel`. */
  def incoming(id: String, rel: Option[GraphEdge]): List[Edge] =
    edges.filter((e: Edge) => e.to == id && rel.forall((r: GraphEdge) => e.rel == r))

  /** All requirement nodes, in contribution order. */
  def requirements: List[GraphNode.Requirement] =
    nodes.collect { case r: GraphNode.Requirement => r }.toList

  /** All obligation nodes, in contribution order. */
  def obligations: List[GraphNode.Obligation] =
    nodes.collect { case o: GraphNode.Obligation => o }.toList

object TraceabilityGraph:

  /**
   * Builds the graph from the three declared sources plus the
   * resolution predicate the adapter supplies for path existence.
   *
   * `resolveCodePath` decides whether a path-shaped implementation-map
   * token names a code file; `resolveArtifact` decides whether a
   * proof-obligation artifact token resolves. Both are supplied by the
   * adapter — the core learns nothing about the filesystem, so
   * conformance on generated corpora is decidable in-process.
   *
   * A row that cannot be bound contributes an `UnlinkableRow` with a
   * stated reason instead of a node or edge — the conservation property
   * `unlinkable-rows-are-conserved` is over this set.
   *
   * spec: graph-tool-port — Requirement: The graph is built from the three declared sources
   * spec: graph-tool-port — Property: unlinkable-rows-are-conserved
   */
  def build(
    registry: List[ConceptRegistryDoc],
    inventory: InventoryDoc,
    specs: List[SpecGraphDoc],
    resolveCodePath: String => Boolean,
    resolveArtifact: String => Boolean
  ): GraphBuild =
    GraphBuilder(registry, inventory, specs, resolveCodePath, resolveArtifact).run()

  /**
   * The build loop — a fold over the three sources accumulating the
   * node index, edge list, unlinkable rows, warnings, and row
   * accounting. Nodes de-duplicate on id at first position; a later
   * re-declaration merges attributes (the predecessor's dict-update
   * semantics) without moving the node.
   */
  private final case class Acc(
    index: Map[String, Int],
    nodes: Vector[GraphNode],
    edges: List[Edge],
    unlinkable: List[UnlinkableRow],
    warnings: List[String],
    rowsRead: Int,
    rowsBound: Int
  ):
    def node(n: GraphNode): Acc =
      index.get(n.id) match
        case Some(i) => copy(nodes = nodes.updated(i, GraphNode.merge(nodes(i), n)))
        case None    =>
          copy(index = index + (n.id -> nodes.length), nodes = nodes :+ n)
    def edge(e: Edge): Acc           = copy(edges = edges :+ e)
    def unl(r: UnlinkableRow): Acc   = copy(unlinkable = unlinkable :+ r)
    def warn(w: String): Acc         = copy(warnings = warnings :+ w)
    def read(n: Int): Acc            = copy(rowsRead = rowsRead + n)
    def bound(n: Int): Acc           = copy(rowsBound = rowsBound + n)

  private object Acc:
    val empty: Acc = Acc(Map.empty, Vector.empty, Nil, Nil, Nil, 0, 0)

  private final class GraphBuilder(
    registry: List[ConceptRegistryDoc],
    inventory: InventoryDoc,
    specs: List[SpecGraphDoc],
    resolveCodePath: String => Boolean,
    resolveArtifact: String => Boolean
  ):

    def run(): GraphBuild =
      val a1: Acc = registry.foldLeft(Acc.empty)(buildConcept)
      val a2: Acc = inventory.rows.foldLeft(a1)(buildInventoryRow)
        .copy(unlinkable = a1.unlinkable ++ inventory.unlinkable)
        .read(inventory.unlinkable.length + inventory.rows.length)
        .bound(inventory.rows.length)
      val a3: Acc = specs.foldLeft(a2)(buildSpec)
      GraphBuild(
        TraceabilityGraph(a3.nodes, a3.edges, a3.unlinkable),
        a3.warnings,
        a3.rowsRead,
        a3.rowsBound
      )

    // ── concepts ──────────────────────────────────────────────────────

    private def buildConcept(acc: Acc, doc: ConceptRegistryDoc): Acc =
      val cid: String = s"concept:${doc.concept}"
      val a0: Acc = acc.node(GraphNode.Concept(doc.concept, Some(doc.file)))
      val a1: Acc = doc.actions.foldLeft(a0) { (a, name) =>
        val action: GraphNode = GraphNode.Action(doc.concept, name)
        a.node(action).edge(Edge.plain(cid, GraphEdge.Declares, action.id))
      }
      val a2: Acc = doc.syncs.foldLeft(a1) { (a, name) =>
        val sync: GraphNode = GraphNode.Sync(doc.concept, name)
        a.node(sync).edge(Edge.plain(cid, GraphEdge.DefinesSync, sync.id))
      }
      // impl-map rows: a row binds iff at least one citation resolves;
      // citations resolving nowhere in a partially-bound row are named
      // in warnings, a fully-unresolving row goes to unlinkable.
      val a3: Acc = doc.implMapRows.foldLeft(a2.read(doc.implMapRows.length)) { (a, row) =>
        val (resolved: List[String], dead: List[String]) =
          row.citations.partition(resolveCodePath)
        if resolved.nonEmpty then
          val bound: Acc = resolved.foldLeft(a) { (aa, tok) =>
            val code: GraphNode = GraphNode.Code(tok)
            aa.node(code).edge(Edge.plain(cid, GraphEdge.ImplementedBy, code.id))
          }
          val warned: Acc = dead.foldLeft(bound) { (aa, tok) =>
            aa.warn(s"${doc.file}:${row.line}: citation `$tok` resolves nowhere (row partially bound)")
          }
          warned.bound(1)
        else
          a.unl(UnlinkableRow(
            doc.file, row.line, row.text,
            UnlinkableReason.stated(
              s"citation(s) resolve nowhere: ${row.citations.mkString(", ")}"
            )
          ))
      }
      a3.copy(unlinkable = a3.unlinkable ++ doc.unlinkable)
        .read(doc.unlinkable.length)

    // ── inventory ─────────────────────────────────────────────────────

    private def buildInventoryRow(acc: Acc, row: InventoryRow): Acc =
      val t: GraphNode =
        GraphNode.TypeEntry(row.name, Some(row.section), row.cells.lastOption)
      val a0: Acc = acc.node(t)
      if row.section.startsWith("Service Traits") && row.cells.length >= 4 then
        row.cells.lift(3).getOrElse("").split(",").toList
          .map((x: String) => x.trim.replaceAll("^`+|`+$", "").trim)
          .foldLeft(a0) { (a, impl) =>
            if impl.isEmpty || impl == "—" then a
            else
              val t2: GraphNode = GraphNode.TypeEntry(impl, Some("implementation"), None)
              a.node(t2).edge(Edge.plain(t.id, GraphEdge.ImplementedBy, t2.id))
          }
      else a0

    // ── specs ─────────────────────────────────────────────────────────

    private def buildSpec(acc: Acc, doc: SpecGraphDoc): Acc =
      val sid: String = s"spec:${doc.change}/${doc.capability}"
      val a0: Acc = acc.node(GraphNode.Spec(doc.change, doc.capability, doc.file))
      val a1: Acc = doc.conceptsUsed.foldLeft(a0) { (a, row) =>
        val first: String = row.cells.headOption.getOrElse("")
        GraphParse.firstSymbol(first) match
          case None => a // parse-time filter already reported it
          case Some(ref) =>
            val planned: Boolean = GraphParse.PlannedMarker.findFirstIn(first).nonEmpty
            val cited: GraphNode =
              if ref.contains("/") then
                val cname: String = ref.split("/", 2)(0)
                GraphNode.Action(cname, ref.substring(cname.length + 1))
              else GraphNode.Concept(ref, None)
            a.node(cited).edge(Edge(sid, GraphEdge.Cites, cited.id, Some(planned), None))
      }
      val a2: Acc = doc.typesUsed.foldLeft(a1) { (a, row) =>
        bindTypeRow(a, sid, row, GraphEdge.Uses)
      }
      val a3: Acc = doc.typesIntroduced.foldLeft(a2) { (a, row) =>
        bindTypeRow(a, sid, row, GraphEdge.Introduces)
      }
      val a4: Acc = requirements(a3, sid, doc)
      val a5: Acc = obligations(a4, doc)
      a5.copy(unlinkable = a5.unlinkable ++ doc.unlinkable)
        .read(
          doc.conceptsUsed.length + doc.typesUsed.length + doc.typesIntroduced.length +
            doc.obligations.length + doc.unlinkable.length
        )
        .bound(doc.conceptsUsed.length + doc.typesUsed.length + doc.typesIntroduced.length)

    private def bindTypeRow(acc: Acc, sid: String, row: SpecTableRow, rel: GraphEdge): Acc =
      GraphParse.firstSymbol(row.cells.headOption.getOrElse("")) match
        case None => acc // reported at parse time
        case Some(name) =>
          acc.node(GraphNode.TypeEntry(name, None, None))
            .edge(Edge.plain(sid, rel, s"type:$name"))

    private def requirements(acc: Acc, sid: String, doc: SpecGraphDoc): Acc =
      doc.document.requirements.zipWithIndex.foldLeft(acc) { (a, pair) =>
        val (req, idx) = pair
        val r: GraphNode = GraphNode.Requirement(s"${doc.change}/${doc.capability}", idx + 1, req.title)
        a.node(r).edge(Edge.plain(sid, GraphEdge.HasRequirement, r.id))
      }

    private def obligations(acc: Acc, doc: SpecGraphDoc): Acc =
      val reqTitles: List[String] = doc.document.requirements.map(_.title)
      doc.obligations.zipWithIndex.foldLeft(acc) { (a, pair) =>
        val (row, idx) = pair
        val n: Int = idx + 1
        val first: String = row.cells.headOption.getOrElse("")
        if row.cells.length < 4 || first.contains("<!--") then
          a.unl(UnlinkableRow(
            doc.file, row.line, row.text,
            UnlinkableReason.stated(
              s"obligation row cannot bind: ${if row.cells.length < 4 then s"${row.cells.length} cells" else "comment row"}"
            )
          ))
        else
          bindObligation(a, doc, row, n, reqTitles)
      }

    private def bindObligation(
      acc: Acc,
      doc: SpecGraphDoc,
      row: SpecTableRow,
      n: Int,
      reqTitles: List[String]
    ): Acc =
      val spec: String = s"${doc.change}/${doc.capability}"
      val oid: String = s"oblig:$spec#$n"
      val source: String = row.cells.lift(1).getOrElse("")
      val obl: GraphNode.Obligation = GraphNode.Obligation(
        spec, n,
        row.cells.lift(0).getOrElse("").replaceAll("^`+|`+$", ""),
        row.cells.lift(2).getOrElse(""),
        typedSourceKind(source)
      )
      val a0: Acc = acc.node(obl)

      // (1) explicit ordinal: "Requirement N" / "RN" — every in-range match links
      val explicit: List[Int] =
        GraphParse.ExplicitOrdinal.findAllMatchIn(source).toList
          .map((m: scala.util.matching.Regex.Match) => m.group(1).toInt)
          .filter((i: Int) => i >= 1 && i <= reqTitles.length)
      val a1: Acc = explicit.foldLeft(a0) { (a, i) =>
        a.edge(Edge(s"req:$spec#$i", GraphEdge.EnforcedBy, oid, None, Some(ObligationLink.Explicit)))
      }

      // (2) requirement title (first 40 chars) quoted in the Source cell
      val (a2: Acc, linked2: Boolean) =
        if explicit.nonEmpty then (a1, true)
        else
          reqTitles.zipWithIndex.foldLeft((a1, false)) { (accPair, pair) =>
            val (a, found) = accPair
            val (title, idx) = pair
            val key: String = title.toLowerCase.take(40)
            if !found && key.nonEmpty && source.toLowerCase.contains(key) then
              (a.edge(Edge(s"req:$spec#${idx + 1}", GraphEdge.EnforcedBy, oid, None, Some(ObligationLink.Title))), true)
            else (a, found)
          }

      // (3) inferred: distinctive-token overlap ≥ 2 with a requirement title
      val (a3: Acc, linked3: Boolean) =
        if linked2 then (a2, true)
        else
          val otoks: Set[String] = GraphParse.tokens(row.cells(0))
          val scored: List[(Int, Int)] =
            reqTitles.zipWithIndex.map { (title, idx) =>
              (idx + 1, otoks.intersect(GraphParse.tokens(title)).size)
            }
          val best: Option[(Int, Int)] =
            scored.filter((_, score) => score >= 2).sortBy(-_._2).headOption
          best match
            case Some((i, _)) =>
              (a2.edge(Edge(s"req:$spec#$i", GraphEdge.EnforcedBy, oid, None, Some(ObligationLink.Inferred)))
                .warn(s"$spec: obligation #$n Source='${source.take(44)}' names no requirement — INFERRED -> R$i by title overlap"),
               true)
            case None => (a2, false)

      // untyped Source cells name nothing — warn (the predecessor's message)
      val a4: Acc =
        if !linked3 && obl.sourceKind.isEmpty then
          a3.warn(s"$spec: obligation #$n names NOTHING resolvable (Source='${source.take(60)}')")
        else a3

      // artifacts — every non-empty non-dash token of the cell binds
      val artifactCell: String = row.cells.lift(3).getOrElse("")
      val artifactTokens: List[String] =
        GraphParse.backticks(artifactCell) match
          case Nil  => List(artifactCell)
          case toks => toks
      artifactTokens.map(_.trim)
        .filter((art: String) => art.nonEmpty && art != "—" && art != "-")
        .foldLeft(a4.bound(1)) { (a, art) =>
          val withEdge: Acc =
            a.node(GraphNode.Artifact(art)).edge(Edge.plain(oid, GraphEdge.VerifiedBy, s"artifact:$art"))
          if resolveArtifact(art) then withEdge
          else withEdge.warn(s"$spec: obligation #$n artifact `$art` names no tracked file")
        }

    private def typedSourceKind(source: String): Option[ObligationSourceKind] =
      GraphParse.TypedSource.findFirstMatchIn(source).map((m: scala.util.matching.Regex.Match) =>
        m.group(2) match
          case "Property"        => ObligationSourceKind.Property
          case "Properties"      => ObligationSourceKind.Properties
          case "Scenario"        => ObligationSourceKind.Scenario
          case "Scenarios"       => ObligationSourceKind.Scenarios
          case "Invariant"       => ObligationSourceKind.Invariant
          case "Compile-Negative" => ObligationSourceKind.CompileNegative
          case "Temporal"        => ObligationSourceKind.Temporal
          case "Criterion"       => ObligationSourceKind.Criterion
          case "Type-Constraint" => ObligationSourceKind.TypeConstraint
          case "MUST-CONFIRM"    => ObligationSourceKind.MustConfirm
          case "Design"          => ObligationSourceKind.Design
          case _                 => ObligationSourceKind.NonGoal // danger-scan:allow closed domain — TypedSource's alternation pins group 2 to the listed words, so only "Non-goal" reaches this arm
      )
