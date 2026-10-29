package org.sinemenda.probatio.core

/**
 * The result of building the graph: the `graph` itself, the advisory
 * `warnings` the predecessor prints (typed-source obligations, files
 * declaring no concept, obligations nothing claims), and the row
 * accounting the conservation property quantifies over.
 *
 *   - `rowsRead`  — every table row every source parser produced
 *   - `rowsBound` — rows that contributed at least one node or edge
 *
 * The property `unlinkable-rows-are-conserved` is
 * `rowsRead == rowsBound + graph.unlinkable.length` — accounting, not
 * assertion: the builder is responsible for making it true, the test
 * for checking it.
 *
 * spec: graph-tool-port — Property: unlinkable-rows-are-conserved
 */
final case class GraphBuild(
  graph: TraceabilityGraph,
  warnings: List[String],
  rowsRead: Int,
  rowsBound: Int
)
