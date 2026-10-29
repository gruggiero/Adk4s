package org.sinemenda.probatio.core

/**
 * The reachability audit's decision value — the requirement lists, not
 * the counts the predecessor prints.
 *
 *   - `reaching`                — requirements whose `verified-by`
 *                                 closure reaches an artifact node
 *   - `unenforcedRequirements`  — requirements whose closure does not
 *                                 (unlinked, linked to nothing, or
 *                                 linked only to artifactless
 *                                 obligations)
 *   - `artifactlessObligations` — obligations with no `enforced-by`
 *                                 edge; they are reported and do NOT
 *                                 count as enforcement for any
 *                                 requirement that links to them
 *
 * The lists are the conservation invariant the Ring-6 contract states:
 * `reaching.length + unenforcedRequirements.length` equals the number of
 * requirement nodes in the audited scope, and the two lists are
 * disjoint. A count-only result cannot express that invariant — which
 * is why `ReachabilityResult` has no count constructor.
 *
 * spec: graph-tool-port — Concepts Introduced (new): ReachabilityResult
 * spec: graph-tool-port — Compile-Negative: A reachability result that is only a count
 * spec: graph-tool-port — Property: reachability-is-transitive-and-grounded
 */
final case class ReachabilityResult(
  reaching: List[GraphNode.Requirement],
  unenforcedRequirements: List[GraphNode.Requirement],
  artifactlessObligations: List[GraphNode.Obligation]
)
