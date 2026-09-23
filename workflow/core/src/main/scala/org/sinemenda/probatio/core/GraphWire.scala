package org.sinemenda.probatio.core

/**
 * A graph decoded back from the wire — `readExport`'s result. Warnings
 * ride the export payload (the predecessor emits them; `stats` counts
 * them); row accounting is build-internal and not serialized.
 *
 * spec: graph-tool-port — Property: export-round-trips
 */
final case class ExportedGraph(
  graph: TraceabilityGraph,
  warnings: List[String]
)

/**
 * The export wire format — the predecessor's JSON object, extended with
 * the `unlinkable` array the predecessor lacks:
 *
 *   `{"nodes": [{id, kind, ...attrs}], "edges": [{from, rel, to,
 *   ...attrs}], "warnings": [...], "unlinkable": [{source, line, text,
 *   reason}]}`
 *
 * The added field is why a predecessor consumer still reads the export
 * (it ignores unknown keys), while the port's own `readExport`
 * round-trips all three sets — the `export-round-trips` property is over
 * node set, edge set AND unlinkable set.
 *
 * `writeChangePayload` emits the predecessor's `--change-dir/--change`
 * restricted payload — `{change, obligations, warnings}` — which the
 * chain-state seam consumes in place of the subprocess it replaces.
 *
 * spec: graph-tool-port — Requirement: The exported graph agrees with the predecessor on the repository corpus
 * spec: graph-tool-port — Property: export-agrees-with-the-predecessor
 */
object GraphWire:

  /** Serializes the full graph plus build warnings to the wire object. */
  def writeExport(build: GraphBuild): ujson.Value =
    ujson.Obj(
      "nodes"      -> ujson.Arr(build.graph.nodes.map(nodeJson)*),
      "edges"      -> ujson.Arr(build.graph.edges.map(edgeJson)*),
      "warnings"   -> ujson.Arr(build.warnings.map(ujson.Str(_))*),
      "unlinkable" -> ujson.Arr(build.graph.unlinkable.map(unlinkableJson)*)
    )

  private def nodeJson(n: GraphNode): ujson.Value =
    val base: ujson.Obj = ujson.Obj("id" -> n.id, "kind" -> GraphNode.kindName(n))
    n match
      case c: GraphNode.Concept =>
        c.file.foreach((f: String) => base("file") = f)
      case a: GraphNode.Action =>
        base("concept") = a.concept
      case s: GraphNode.Sync =>
        base("concept") = s.concept
      case t: GraphNode.TypeEntry =>
        t.section.foreach((s: String) => base("section") = s)
        t.provenance.foreach((p: String) => base("provenance") = p)
      case s: GraphNode.Spec =>
        base("change") = s.change
        base("file") = s.file
      case r: GraphNode.Requirement =>
        base("title") = r.title
        base("ordinal") = r.ordinal
        base("spec") = r.spec
      case o: GraphNode.Obligation =>
        base("title") = o.title
        base("enforcement") = o.enforcement
        base("spec") = o.spec
        o.sourceKind.foreach((k: ObligationSourceKind) =>
          base("source_kind") = ObligationSourceKind.wireName(k)
        )
      case GraphNode.Artifact(_) => ()
      case GraphNode.Code(_)     => ()
    base

  private def edgeJson(e: Edge): ujson.Value =
    val base: ujson.Obj = ujson.Obj(
      "from" -> e.from, "rel" -> GraphEdge.wireName(e.rel), "to" -> e.to
    )
    e.planned.foreach((p: Boolean) => base("planned") = p)
    e.link.foreach((l: ObligationLink) => base("link") = ObligationLink.wireName(l))
    base

  private def unlinkableJson(r: UnlinkableRow): ujson.Value =
    ujson.Obj(
      "source" -> r.source,
      "line"   -> r.line,
      "text"   -> r.text,
      "reason" -> r.reason.text
    )

  /**
   * Reads an export payload back. `Left` names the field whose shape
   * rejected — a malformed export is a parse failure, not a partial
   * graph.
   */
  def readExport(value: ujson.Value): Either[String, ExportedGraph] =
    value.objOpt match
      case None    => Left("export: not a JSON object")
      case Some(o) =>
        for
          nodes <- arrayOf(o, "nodes").flatMap(readNodes)
          edges <- arrayOf(o, "edges").flatMap(readEdges)
          warnings <- arrayOf(o, "warnings").flatMap(readStrings)
          unlinkable <-
            o.get("unlinkable") match
              case None    => Right(Nil) // a predecessor export lacks the field
              case Some(v) => v.arrOpt match
                case None    => Left("unlinkable: not an array")
                case Some(a) => readUnlinkable(a.toList)
        yield ExportedGraph(TraceabilityGraph(nodes.toVector, edges, unlinkable), warnings)

  private def arrayOf(
    o: scala.collection.mutable.Map[String, ujson.Value],
    key: String
  ): Either[String, List[ujson.Value]] =
    o.get(key) match
      case None    => Left(s"$key: missing")
      case Some(a) => a.arrOpt match
        case None    => Left(s"$key: not an array")
        case Some(x) => Right(x.toList)

  private def readStrings(arr: List[ujson.Value]): Either[String, List[String]] =
    arr.foldLeft[Either[String, List[String]]](Right(Nil)) { (acc, v) =>
      acc.flatMap((ss: List[String]) =>
        v.strOpt match
          case Some(s) => Right(ss :+ s)
          case None    => Left("warnings: element is not a string")
      )
    }

  private def readNodes(arr: List[ujson.Value]): Either[String, List[GraphNode]] =
    arr.foldLeft[Either[String, List[GraphNode]]](Right(Nil)) { (acc, v) =>
      acc.flatMap((ns: List[GraphNode]) => readNode(v).map(ns :+ _))
    }

  private def readEdges(arr: List[ujson.Value]): Either[String, List[Edge]] =
    arr.foldLeft[Either[String, List[Edge]]](Right(Nil)) { (acc, v) =>
      acc.flatMap { (es: List[Edge]) =>
        objOf(v, "edge").flatMap { (o: scala.collection.mutable.Map[String, ujson.Value]) =>
          for
            from <- strField(o, "from")
            relW <- strField(o, "rel")
            to   <- strField(o, "to")
            rel  <- relFromWire(relW)
            link <- o.get("link") match
              case None    => Right(Option.empty[ObligationLink])
              case Some(l) =>
                l.strOpt match
                  case None    => Left("edge link: not a string")
                  case Some(w) => linkFromWire(w).map(Option(_))
          yield es :+ Edge(from, rel, to, o.get("planned").flatMap(_.boolOpt), link)
        }
      }
    }

  private def readUnlinkable(arr: List[ujson.Value]): Either[String, List[UnlinkableRow]] =
    arr.foldLeft[Either[String, List[UnlinkableRow]]](Right(Nil)) { (acc, v) =>
      acc.flatMap { (rs: List[UnlinkableRow]) =>
        objOf(v, "unlinkable").flatMap { (o: scala.collection.mutable.Map[String, ujson.Value]) =>
        for
          source <- strField(o, "source")
          text   <- strField(o, "text")
          reason <- strField(o, "reason")
          line   <- intField(o, "line")
        yield rs :+ UnlinkableRow(source, line, text, UnlinkableReason.stated(reason))
        }
      }
    }

  private def objOf(
    v: ujson.Value,
    what: String
  ): Either[String, scala.collection.mutable.Map[String, ujson.Value]] =
    v.objOpt match
      case Some(o) => Right(o)
      case None    => Left(s"$what: not a JSON object")

  private def strField(
    o: scala.collection.mutable.Map[String, ujson.Value],
    key: String
  ): Either[String, String] =
    o.get(key).flatMap(_.strOpt) match
      case Some(s) => Right(s)
      case None    => Left(s"node/edge field '$key': missing or not a string")

  private def intField(
    o: scala.collection.mutable.Map[String, ujson.Value],
    key: String
  ): Either[String, Int] =
    o.get(key).flatMap(_.numOpt) match
      case Some(d) => Right(d.toInt)
      case None    => Left(s"field '$key': missing or not a number")

  private def readNode(v: ujson.Value): Either[String, GraphNode] =
    objOf(v, "node").flatMap { (o: scala.collection.mutable.Map[String, ujson.Value]) =>
    for
      id   <- strField(o, "id")
      kind <- strField(o, "kind")
      node <- kind match
        case "concept"  => Right(GraphNode.Concept(id.stripPrefix("concept:"), o.get("file").flatMap(_.strOpt)))
        case "action"   =>
          strField(o, "concept").map { (c: String) =>
            GraphNode.Action(c, id.stripPrefix(s"action:$c/"))
          }
        case "sync"     =>
          strField(o, "concept").map { (c: String) =>
            GraphNode.Sync(c, id.stripPrefix(s"sync:$c/"))
          }
        case "type"     =>
          Right(GraphNode.TypeEntry(
            id.stripPrefix("type:"),
            o.get("section").flatMap(_.strOpt),
            o.get("provenance").flatMap(_.strOpt)
          ))
        case "spec"     =>
          val rest: String = id.stripPrefix("spec:")
          val idx: Int = rest.indexOf('/')
          if idx < 0 then Left(s"spec node id has no change/capability split: $id")
          else
            strField(o, "file").map { (f: String) =>
              GraphNode.Spec(rest.substring(0, idx), rest.substring(idx + 1), f)
            }
        case "req"      => readKeyed(id, "req:") { (spec: String, ord: Int) =>
          strField(o, "title").map((t: String) => GraphNode.Requirement(spec, ord, t))
        }
        case "oblig"    => readKeyed(id, "oblig:") { (spec: String, ord: Int) =>
          for
            title <- strField(o, "title")
            enfo  <- strField(o, "enforcement")
          yield GraphNode.Obligation(
            spec, ord, title, enfo,
            o.get("source_kind").flatMap(_.strOpt).flatMap(sourceKindFromWire)
          )
        }
        case "artifact" => Right(GraphNode.Artifact(id.stripPrefix("artifact:")))
        case "code"     => Right(GraphNode.Code(id.stripPrefix("code:")))
        case other      => Left(s"node kind '$other': unknown") // danger-scan:allow rejection — unknown kinds are named Lefts, not mapped
    yield node
    }

  /** `req:`/`oblig:` ids are `<prefix><spec>#<ordinal>` — split on the last `#`. */
  private def readKeyed(
    id: String,
    prefix: String
  )(mk: (String, Int) => Either[String, GraphNode]): Either[String, GraphNode] =
    val rest: String = id.stripPrefix(prefix)
    val idx: Int = rest.lastIndexOf('#')
    if idx < 0 then Left(s"node id has no #ordinal: $id")
    else
      val ord: Either[String, Int] =
        scala.util.Try(rest.substring(idx + 1).toInt).toEither.left.map(_ => s"non-numeric ordinal in $id")
      ord.flatMap((n: Int) => mk(rest.substring(0, idx), n))

  private def relFromWire(w: String): Either[String, GraphEdge] = w match
    case "declares"        => Right(GraphEdge.Declares)
    case "defines-sync"    => Right(GraphEdge.DefinesSync)
    case "implemented-by"  => Right(GraphEdge.ImplementedBy)
    case "cites"           => Right(GraphEdge.Cites)
    case "uses"            => Right(GraphEdge.Uses)
    case "introduces"      => Right(GraphEdge.Introduces)
    case "has-req"         => Right(GraphEdge.HasRequirement)
    case "enforced-by"     => Right(GraphEdge.EnforcedBy)
    case "verified-by"     => Right(GraphEdge.VerifiedBy)
    case other             => Left(s"edge rel '$other': unknown") // danger-scan:allow rejection — unknown rels are named Lefts, not mapped

  private def linkFromWire(w: String): Either[String, ObligationLink] = w match
    case "explicit" => Right(ObligationLink.Explicit)
    case "title"    => Right(ObligationLink.Title)
    case "inferred" => Right(ObligationLink.Inferred)
    case other      => Left(s"obligation link '$other': unknown") // danger-scan:allow rejection — unknown links are named Lefts, not mapped

  private def sourceKindFromWire(w: String): Option[ObligationSourceKind] =
    ObligationSourceKind.values.find((k: ObligationSourceKind) => ObligationSourceKind.wireName(k) == w)

  /** The per-change obligations payload the chain-state seam consumes. */
  def writeChangePayload(
    graph: TraceabilityGraph,
    warnings: List[String],
    change: String
  ): ujson.Value =
    val obligations: List[ujson.Value] =
      graph.obligations
        .filter((o: GraphNode.Obligation) => o.spec.split("/")(0) == change)
        .map { (o: GraphNode.Obligation) =>
          val artifacts: List[String] =
            graph.outgoing(o.id, Some(GraphEdge.VerifiedBy))
              .map((e: Edge) => e.to.replace("artifact:", ""))
          val sources: List[ujson.Value] =
            graph.incoming(o.id, Some(GraphEdge.EnforcedBy)).flatMap { (e: Edge) =>
              graph.node(e.from) match
                case Some(r: GraphNode.Requirement) =>
                  List(ujson.Obj(
                    "requirement" -> r.title,
                    "ordinal"     -> r.ordinal,
                    "link"        -> e.link.map(ObligationLink.wireName).getOrElse("")
                  ))
                case _ => Nil // danger-scan:allow transparent — a non-requirement enforced-by source contributes no source entry
            }
          ujson.Obj(
            "spec"        -> o.spec.split("/").lift(1).getOrElse(""),
            "obligation"  -> o.title,
            "artifact"    -> artifacts.headOption.getOrElse(""),
            "artifacts"   -> ujson.Arr(artifacts.map(ujson.Str(_))*),
            "enforcement" -> o.enforcement,
            "sources"     -> ujson.Arr(sources*)
          )
        }
    ujson.Obj(
      "change"      -> change,
      "obligations" -> ujson.Arr(obligations*),
      "warnings"    -> ujson.Arr(warnings.map(ujson.Str(_))*)
    )
