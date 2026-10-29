package org.sinemenda.probatio.core

/**
 * The obligations audit — the reachability decision the graph exists to
 * answer. Ring-6-mirrored: `ReachabilityKernel` in the verified module
 * states the contract on `BigInt` node ids; this object is the shipped
 * decision over `GraphNode` ids, and `VerifiedKernelBridgeSpec` runs both
 * on the same inputs.
 *
 * Termination is by fuel bounded by edge count — `reaches` mirrors the
 * kernel's `fuel >= edges.length` precondition, so a cycle cannot make
 * the audit diverge.
 *
 * spec: graph-tool-port — Requirement: The obligations audit reports every requirement that reaches no artifact
 * spec: graph-tool-port — Ring 6 Cross-Reference: ReachabilityKernel
 */
object GraphAudit:

  /**
   * Audits `graph`, restricted to `change` when given (`Some(change)`
   * scopes to that change's specs — the predecessor's
   * optional positional argument).
   *
   * `artifactResolves` decides whether an `artifact:` node's path names
   * a tracked file; the adapter supplies it. A requirement linked only
   * through obligations whose artifacts do not resolve is unenforced —
   * an artifactless obligation does not enforce.
   *
   * Returns `None` when the graph contains no spec nodes at all — the
   * empty-corpus case is could-not-determine, not an all-green result.
   *
   * Conservation contract (Ring 6):
   *   `result.reaching.length + result.unenforcedRequirements.length`
   *   equals the number of in-scope requirement nodes, and the two lists
   *   are disjoint.
   *
   * spec: graph-tool-port — Property: reachability-is-transitive-and-grounded
   */
  def audit(
    graph: TraceabilityGraph,
    change: Option[String],
    artifactResolves: String => Boolean
  ): Option[ReachabilityResult] =
    val specs: List[GraphNode.Spec] =
      graph.nodes.collect { case s: GraphNode.Spec => s }
        .filter((s: GraphNode.Spec) => change.forall((c: String) => s.change == c))
        .toList
    if specs.isEmpty then None
    else
      // in-scope requirements: the has-req targets of the in-scope spec
      // nodes, in edge order — the predecessor's `g.out(sid, "has-req")`
      val reqs: List[GraphNode.Requirement] =
        specs.flatMap((s: GraphNode.Spec) =>
          graph.outgoing(s.id, Some(GraphEdge.HasRequirement)).map((e: Edge) => e.to)
        ).flatMap((id: String) =>
          graph.node(id) match
            case Some(r: GraphNode.Requirement) => List(r)
            case _                              => Nil // danger-scan:allow transparent — a has-req edge to a non-requirement contributes no requirement
        )
      val (reaching: List[GraphNode.Requirement], unenforced: List[GraphNode.Requirement]) =
        reqs.partition((r: GraphNode.Requirement) => reaches(graph, r.id, artifactResolves))
      val specKeys: Set[String] =
        specs.map((s: GraphNode.Spec) => s"${s.change}/${s.capability}").toSet
      // an obligation with no resolving artifact in its closure does not
      // enforce — "artifactless" is the reachability reading, not merely
      // "no verified-by edge": a verified-by to a path that resolves
      // nowhere is the same verdict a missing cell earns
      val artifactless: List[GraphNode.Obligation] =
        graph.obligations.filter((o: GraphNode.Obligation) =>
          specKeys.contains(o.spec) && !reaches(graph, o.id, artifactResolves)
        )
      Some(ReachabilityResult(reaching, unenforced, artifactless))

  /**
   * Whether `from` reaches any artifact node by following `enforced-by`
   * / `verified-by` edges — the transitive closure, fuel-bounded by the
   * edge count. `artifactResolves` additionally requires the reached
   * artifact's path to name a tracked file.
   */
  def reaches(
    graph: TraceabilityGraph,
    from: String,
    artifactResolves: String => Boolean
  ): Boolean =
    val follow: Set[GraphEdge] = Set(GraphEdge.EnforcedBy, GraphEdge.VerifiedBy)
    def loop(todo: List[String], seen: Set[String], fuel: Int): Boolean =
      todo match
        case Nil => false
        case id :: rest =>
          graph.node(id) match
            case Some(a: GraphNode.Artifact) if artifactResolves(a.path) => true
            case _ => // danger-scan:allow transparent — non-artifact or non-resolving nodes never terminate the search
              // an artifact whose path resolves nowhere is not a
              // terminus — the closure continues through it
              if fuel <= 0 then
                // fuel bounds edge-follows, not inspection — ids already
                // enqueued are already reached, so a resolving artifact
                // among them still counts (mirrors the kernel, which
                // checks `artifacts.contains` before the fuel test)
                rest.exists((t: String) =>
                  graph.node(t) match
                    case Some(b: GraphNode.Artifact) => artifactResolves(b.path)
                    case _ => false // danger-scan:allow transparent — only a resolving artifact terminates the search
                )
              else
                val next: List[String] =
                  graph.outgoing(id, None)
                    .filter((e: Edge) => follow.contains(e.rel))
                    .map((e: Edge) => e.to)
                    .filter((t: String) => !seen.contains(t))
                loop(rest ++ next, seen ++ next.toSet, fuel - next.length)
    loop(List(from), Set(from), graph.edges.length)
