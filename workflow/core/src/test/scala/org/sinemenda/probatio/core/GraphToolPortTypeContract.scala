package org.sinemenda.probatio.core

/**
 * Typed contract for spec: graph-tool-port (spec 5, Step 1).
 *
 * Pins the public shapes the Step-2 test oracle and Step-3
 * implementation must satisfy — compiled under the real probatio-core
 * classpath, `-Werror` active.
 *
 * Pinned decisions for human review:
 *
 *  - `GraphNode` is a sealed hierarchy of exactly the nine predecessor
 *    kinds; node ids are derived (`n.id`), never stored, so an id cannot
 *    disagree with the node it names. There is no `kind: String`
 *    constructor — a free-string kind is unrepresentable.
 *  - `GraphEdge` is a closed enum of the nine rels; the record type is
 *    `Edge(from, rel, to, planned, link)` — `planned` and `link` are
 *    `Option` because the predecessor sets them on only two rels.
 *  - `TraceabilityGraph(nodes, edges, unlinkable)` requires the
 *    unlinkable set as a constructor argument — there is no default, so
 *    a graph assembled without it does not compile.
 *  - `UnlinkableRow` requires `reason: UnlinkableReason`, an opaque
 *    string with no public `apply` — the validating route is
 *    `UnlinkableReason.of`, the total route `UnlinkableReason.stated`.
 *  - `GraphQuery` is a closed enum of the five operations; `Obligations`
 *    and `Export` carry `Option[String]` for the predecessor's optional
 *    arguments.
 *  - `ReachabilityResult` carries the requirement lists — `reaching`,
 *    `unenforcedRequirements`, `artifactlessObligations` — not counts.
 *  - `TraceabilityGraph.build` takes the three parsed source docs plus
 *    `resolveCodePath`/`resolveArtifact` predicates — the core learns
 *    nothing about the filesystem — and returns `GraphBuild` (graph +
 *    warnings + row accounting), never a bare graph.
 *  - `GraphAudit.audit` returns `Option[ReachabilityResult]` — `None`
 *    when the graph carries no spec nodes, so an empty corpus is
 *    could-not-determine rather than all-green.
 *  - `GraphWire.writeExport`/`readExport` pin the round-trip pair;
 *    `writeChangePayload` is the chain-state seam's wire shape.
 *  - `ReachabilityKernel` (verified module) mirrors `reaches` with the
 *    `fuel >= 0`/`fuel >= edges.length` preconditions and the
 *    grounded/complete postconditions; `audit` carries the
 *    requirement-conservation postcondition.
 *
 * spec: graph-tool-port — Step 1: typed contract
 * spec: graph-tool-port — Concepts Introduced (new): GraphNode
 * spec: graph-tool-port — Concepts Introduced (new): GraphEdge
 * spec: graph-tool-port — Concepts Introduced (new): TraceabilityGraph
 * spec: graph-tool-port — Concepts Introduced (new): GraphQuery
 * spec: graph-tool-port — Concepts Introduced (new): ReachabilityResult
 * spec: graph-tool-port — Concepts Introduced (new): ConceptRegistryDoc
 * spec: graph-tool-port — Concepts Introduced (new): InventoryDoc
 * spec: graph-tool-port — Concepts Introduced (new): UnlinkableRow
 */
final class GraphToolPortTypeContract extends ProbatioSuite:

  // ── Source doc parsers — the adapter hands strings, never paths ─────
  val registryParseSig: (String, String) => Either[String, ConceptRegistryDoc] =
    ConceptRegistryDoc.parse

  val inventoryParseSig: (String, String) => InventoryDoc =
    InventoryDoc.parse

  val specParseSig: (String, String, String, String) => SpecGraphDoc =
    SpecGraphDoc.parse

  // ── Graph construction — resolution is an injected predicate ────────
  val buildSig: (
    List[ConceptRegistryDoc],
    InventoryDoc,
    List[SpecGraphDoc],
    String => Boolean,
    String => Boolean
  ) => GraphBuild =
    TraceabilityGraph.build

  // ── The audit — Option, never a bare result ─────────────────────────
  val auditSig: (TraceabilityGraph, Option[String], String => Boolean) => Option[ReachabilityResult] =
    GraphAudit.audit

  val reachesSig: (TraceabilityGraph, String, String => Boolean) => Boolean =
    GraphAudit.reaches

  // ── The wire format — write/read pair plus the chain-state payload ──
  val writeExportSig: GraphBuild => ujson.Value =
    GraphWire.writeExport

  val readExportSig: ujson.Value => Either[String, ExportedGraph] =
    GraphWire.readExport

  val writeChangePayloadSig: (TraceabilityGraph, List[String], String) => ujson.Value =
    GraphWire.writeChangePayload

  // ── Query closures — small total derivations the port reuses ────────
  val nodeByIdSig: TraceabilityGraph => String => Option[GraphNode] =
    (g: TraceabilityGraph) => (id: String) => g.node(id)

  val outgoingSig: TraceabilityGraph => (String, Option[GraphEdge]) => List[Edge] =
    (g: TraceabilityGraph) => (id: String, rel: Option[GraphEdge]) => g.outgoing(id, rel)

  val incomingSig: TraceabilityGraph => (String, Option[GraphEdge]) => List[Edge] =
    (g: TraceabilityGraph) => (id: String, rel: Option[GraphEdge]) => g.incoming(id, rel)

  val requirementsSig: TraceabilityGraph => List[GraphNode.Requirement] =
    (g: TraceabilityGraph) => g.requirements

  // ── Wire names — the predecessor's kind/rel/link vocabulary ─────────
  val kindNameSig: GraphNode => String = GraphNode.kindName
  val relWireNameSig: GraphEdge => String = GraphEdge.wireName
  val linkWireNameSig: ObligationLink => String = ObligationLink.wireName
  val sourceKindWireNameSig: ObligationSourceKind => String = ObligationSourceKind.wireName

  // ── UnlinkableReason — the two construction routes ──────────────────
  val reasonOfSig: String => Either[String, UnlinkableReason] = UnlinkableReason.of
  val reasonStatedSig: String => UnlinkableReason = UnlinkableReason.stated

  // ── The nine node kinds — exhaustive construction pins ──────────────
  val conceptNode: GraphNode = GraphNode.Concept("c", Some("openspec/concepts/c.md"))
  val actionNode: GraphNode = GraphNode.Action("c", "a")
  val syncNode: GraphNode = GraphNode.Sync("c", "S")
  val typeNode: GraphNode = GraphNode.TypeEntry("T", Some("s"), Some("p"))
  val specNode: GraphNode = GraphNode.Spec("ch", "cap", "openspec/changes/ch/specs/cap/spec.md")
  val reqNode: GraphNode = GraphNode.Requirement("ch/cap", 1, "t")
  val obligNode: GraphNode =
    GraphNode.Obligation("ch/cap", 1, "t", "e", Some(ObligationSourceKind.Scenario))
  val artifactNode: GraphNode = GraphNode.Artifact("p/F.scala")
  val codeNode: GraphNode = GraphNode.Code("p/F.scala")

  test("the nine node kinds derive predecessor-shaped ids"):
    assertEquals(conceptNode.id, "concept:c")
    assertEquals(actionNode.id, "action:c/a")
    assertEquals(syncNode.id, "sync:c/S")
    assertEquals(typeNode.id, "type:T")
    assertEquals(specNode.id, "spec:ch/cap")
    assertEquals(reqNode.id, "req:ch/cap#1")
    assertEquals(obligNode.id, "oblig:ch/cap#1")
    assertEquals(artifactNode.id, "artifact:p/F.scala")
    assertEquals(codeNode.id, "code:p/F.scala")

  test("kindName covers all nine cases with predecessor words"):
    val kinds: List[String] =
      List(conceptNode, actionNode, syncNode, typeNode, specNode, reqNode, obligNode, artifactNode, codeNode)
        .map(GraphNode.kindName)
    assertEquals(
      kinds,
      List("concept", "action", "sync", "type", "spec", "req", "oblig", "artifact", "code")
    )

  // ── The nine edge kinds ─────────────────────────────────────────────
  test("the nine edge kinds emit predecessor rel words"):
    val rels: List[String] = List(
      GraphEdge.Declares,
      GraphEdge.DefinesSync,
      GraphEdge.ImplementedBy,
      GraphEdge.Cites,
      GraphEdge.Uses,
      GraphEdge.Introduces,
      GraphEdge.HasRequirement,
      GraphEdge.EnforcedBy,
      GraphEdge.VerifiedBy
    ).map(GraphEdge.wireName)
    assertEquals(
      rels,
      List(
        "declares", "defines-sync", "implemented-by", "cites", "uses",
        "introduces", "has-req", "enforced-by", "verified-by"
      )
    )

  test("the three obligation-link words match the predecessor"):
    assertEquals(ObligationLink.wireName(ObligationLink.Explicit), "explicit")
    assertEquals(ObligationLink.wireName(ObligationLink.Title), "title")
    assertEquals(ObligationLink.wireName(ObligationLink.Inferred), "inferred")

  test("the twelve obligation source kinds emit the predecessor's matched words"):
    val words: List[String] = List(
      ObligationSourceKind.Property,
      ObligationSourceKind.Properties,
      ObligationSourceKind.Scenario,
      ObligationSourceKind.Scenarios,
      ObligationSourceKind.Invariant,
      ObligationSourceKind.CompileNegative,
      ObligationSourceKind.Temporal,
      ObligationSourceKind.Criterion,
      ObligationSourceKind.TypeConstraint,
      ObligationSourceKind.MustConfirm,
      ObligationSourceKind.Design,
      ObligationSourceKind.NonGoal
    ).map(ObligationSourceKind.wireName)
    assertEquals(
      words,
      List(
        "Property", "Properties", "Scenario", "Scenarios", "Invariant",
        "Compile-Negative", "Temporal", "Criterion", "Type-Constraint",
        "MUST-CONFIRM", "Design", "Non-goal"
      )
    )

  // ── The five operations — closed enum pins ──────────────────────────
  test("GraphQuery carries the five operations"):
    val ops: List[GraphQuery] = List(
      GraphQuery.Export(None, None, None),
      GraphQuery.Stats,
      GraphQuery.Impact("concept:x"),
      GraphQuery.Obligations(Some("change")),
      GraphQuery.ConceptCode("concept")
    )
    assertEquals(ops.length, 5)

  // ── UnlinkableReason — the opaque construction contract ─────────────
  test("UnlinkableReason.of rejects empty; stated maps it"):
    assert(UnlinkableReason.of("").isLeft, "empty reason must not validate")
    assertEquals(UnlinkableReason.stated("").text, "the row under inspection")
    assertEquals(UnlinkableReason.stated("no such file").text, "no such file")

  // Compile-negatives live in the PO-table's named artifact,
  // `SpecLintEngineTypeContract` (4 tests, `graph-tool-port:` prefix).
