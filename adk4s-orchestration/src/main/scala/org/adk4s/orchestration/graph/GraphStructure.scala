package org.adk4s.orchestration.graph

import cats.data.{ValidatedNec, NonEmptyChain}
import cats.data.Validated.{Valid, Invalid}
import cats.syntax.all.*
import org.adk4s.core.error.AdkError
import org.adk4s.core.types.NodeKey

/** Represents a typed edge between nodes. */
case class GraphEdge[A, B, C](
  from: Graph.NodeRef[A, B],
  to: Graph.NodeRef[B, C]
)

/** Declarative structure for graph configuration.
  * Collects edges, entry, and end nodes, then validates and applies in one step.
  */
case class GraphStructure[In, Out] private (
  private val edges: List[GraphEdge[?, ?, ?]],
  private val entry: Option[Graph.NodeRef[In, ?]],
  private val endNodes: List[Graph.NodeRef[?, Out]]
):
  def withEdge[A, B, C](from: Graph.NodeRef[A, B], to: Graph.NodeRef[B, C]): GraphStructure[In, Out] =
    copy(edges = edges :+ GraphEdge(from, to))

  def withEntry[A](entry: Graph.NodeRef[In, A]): GraphStructure[In, Out] =
    copy(entry = Some(entry))

  def withEndNode[A](endNode: Graph.NodeRef[A, Out]): GraphStructure[In, Out] =
    copy(endNodes = endNodes :+ endNode)

  /** Validate all operations and apply to graph if valid.
    * Returns ALL accumulated errors, not just the first.
    */
  def applyTo(graph: Graph[In, Out]): ValidatedNec[AdkError, Graph[In, Out]] =
    // Phase 1: Validate all operations independently (accumulates ALL errors)
    val edgeValidations: ValidatedNec[AdkError, Unit] =
      edges.traverse_(e => graph.validateEdge(e.from.key, e.to.key))

    val entryValidation: ValidatedNec[AdkError, Unit] =
      entry.traverse_(e => graph.validateNodeExists(e.key))

    val endValidations: ValidatedNec[AdkError, Unit] =
      endNodes.traverse_(e => graph.validateNodeExists(e.key))

    // Combine all validations using mapN (accumulates errors)
    (edgeValidations, entryValidation, endValidations).mapN { (_, _, _) =>
      // Phase 2: Apply all operations (only runs if ALL validations pass)
      val g1: Graph[In, Out] = edges.foldLeft(graph) { (g, e) =>
        g.addEdgeUnchecked(e.from.key, e.to.key)
      }
      val g2: Graph[In, Out] = entry.fold(g1)(e => g1.setEntryUnchecked(e.key))
      endNodes.foldLeft(g2)((g, e) => g.addEndNodeUnchecked(e.key))
    }

object GraphStructure:
  def apply[In, Out]: GraphStructure[In, Out] =
    GraphStructure[In, Out](Nil, None, Nil)
