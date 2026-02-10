package org.adk4s.orchestration.graph

import cats.data.ValidatedNec
import cats.data.Validated.{Valid, Invalid}
import cats.data.NonEmptyChain
import cats.data.NonEmptyList
import cats.syntax.all.*
import org.adk4s.core.error.{AdkError, EdgeValidationError, GraphEndNodesMissingError, GraphEntryMissingError, NodeNotFoundError, FanInError}
import org.adk4s.core.types.NodeKey
import org.adk4s.orchestration.fork.ForkSpec
import workflows4s.wio.WorkflowContext

object GraphValidation:
  /** Validate a graph's structure.
    *
    * Checks:
    * - Entry and end nodes are defined
    * - All referenced nodes exist
    * - No cycles in the graph
    * - Graph is connected from entry
    * - No fan-in except for merge nodes
    */
  def validateGraph[In, Out](
    graph: Graph[In, Out]
  ): ValidatedNec[AdkError, Unit] =
    val nodes: Map[NodeKey, GraphNode[?, ?]] = graph.nodesMap
    val edges: Map[NodeKey, Set[NodeKey]] = graph.edgesMap
    val entryNode: Option[NodeKey] = graph.entry
    val endNodes: Set[NodeKey] = graph.endNodesSet

    (
      validateEntryEndDefined(entryNode, endNodes),
      validateNodesExist(nodes, edges, entryNode, endNodes),
      validateNoCycles(nodes, edges),
      validateConnectedGraph(nodes, edges, entryNode),
      validateNoFanInExceptMerge(nodes, edges, entryNode)
    ).mapN((_, _, _, _, _) => ())

  private def validateEntryEndDefined(
    entryNode: Option[NodeKey],
    endNodes: Set[NodeKey]
  ): ValidatedNec[AdkError, Unit] =
    val entryErrors: List[AdkError] = entryNode match
      case None => List(GraphEntryMissingError())
      case Some(_) => List.empty[AdkError]
    val endErrors: List[AdkError] =
      if endNodes.isEmpty then List(GraphEndNodesMissingError())
      else List.empty[AdkError]
    val allErrors: List[AdkError] = entryErrors ++ endErrors
    NonEmptyList.fromList(allErrors) match
      case None => Valid(())
      case Some(nel) => Invalid(NonEmptyChain.fromNonEmptyList(nel))

  private def validateNodesExist(
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    entryNode: Option[NodeKey],
    endNodes: Set[NodeKey]
  ): ValidatedNec[AdkError, Unit] =
    val edgeErrors: List[AdkError] = edges.toList.flatMap { case (from: NodeKey, tos: Set[NodeKey]) =>
      val missingTargets: Set[NodeKey] = tos.filterNot(nodes.contains)
      missingTargets.toList.map { (to: NodeKey) =>
        NodeNotFoundError(to.value)
      }
    }

    val entryErrors: List[AdkError] = entryNode match
      case Some(nodeKey) if !nodes.contains(nodeKey) =>
        List(NodeNotFoundError(nodeKey.value))
      case _ =>
        List.empty[AdkError]

    val endErrors: List[AdkError] = endNodes.toList.collect {
      case nodeKey: NodeKey if !nodes.contains(nodeKey) =>
        NodeNotFoundError(nodeKey.value)
    }

    val allErrors: List[AdkError] = edgeErrors ++ entryErrors ++ endErrors

    NonEmptyList.fromList(allErrors) match
      case None => Valid(())
      case Some(nel) => Invalid(NonEmptyChain.fromNonEmptyList(nel))

  private def validateNoCycles(
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]]
  ): ValidatedNec[AdkError, Unit] =
    def dfs(
      node: NodeKey,
      visited: Set[NodeKey],
      stack: Set[NodeKey]
    ): (Set[NodeKey], Option[NodeKey]) =
      if !nodes.contains(node) then (visited, None)
      else if stack.contains(node) then (visited, Some(node))
      else if visited.contains(node) then (visited, None)
      else
        val nextVisited: Set[NodeKey] = visited + node
        val nextStack: Set[NodeKey] = stack + node
        val neighbors: Set[NodeKey] = edges.getOrElse(node, Set.empty)
        neighbors.toList.foldLeft((nextVisited, Option.empty[NodeKey])) { case ((accVisited, accCycle), neighbor: NodeKey) =>
          accCycle match
            case Some(_) => (accVisited, accCycle)
            case None =>
              val result: (Set[NodeKey], Option[NodeKey]) = dfs(neighbor, accVisited, nextStack)
              (result._1, result._2)
        }

    val initial: (Set[NodeKey], Option[NodeKey]) =
      nodes.keys.toList.foldLeft((Set.empty[NodeKey], Option.empty[NodeKey])) { case ((visited, cycle), node: NodeKey) =>
        cycle match
          case Some(_) => (visited, cycle)
          case None => dfs(node, visited, Set.empty[NodeKey])
      }

    initial._2 match
      case None => Valid(())
      case Some(node) =>
        Invalid(
          NonEmptyChain.one(EdgeValidationError(node.value, node.value, "Cycle detected in graph"))
        )

  private def validateConnectedGraph(
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    entryNode: Option[NodeKey]
  ): ValidatedNec[AdkError, Unit] =
    def bfs(frontier: List[NodeKey], visited: Set[NodeKey]): Set[NodeKey] =
      frontier match
        case Nil => visited
        case head :: tail =>
          if visited.contains(head) then bfs(tail, visited)
          else
            val neighbors: List[NodeKey] = edges.getOrElse(head, Set.empty).toList
            bfs(tail ++ neighbors, visited + head)

    entryNode match
      case None => Valid(())
      case Some(nodeKey) =>
        val reachable: Set[NodeKey] = bfs(List(nodeKey), Set.empty[NodeKey])
        val unreachable: Set[NodeKey] = nodes.keySet.diff(reachable)

        if unreachable.isEmpty then Valid(())
        else
          val errors: List[AdkError] = unreachable.toList.map { (node: NodeKey) =>
            NodeNotFoundError(node.value)
          }
          NonEmptyList.fromList(errors) match
            case None => Valid(())
            case Some(nel) => Invalid(NonEmptyChain.fromNonEmptyList(nel))

  private def validateNoFanInExceptMerge(
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    entryNode: Option[NodeKey]
  ): ValidatedNec[AdkError, Unit] =
    val incomingEdges: Map[NodeKey, Int] = edges.values.flatten.groupBy(identity).view.mapValues(_.size).toMap
    val fanInErrors: List[AdkError] = incomingEdges.collect {
      case (nodeKey, count) if count > 1 && entryNode.contains(nodeKey) == false =>
        nodes.get(nodeKey) match
          case Some(_: GraphNode.MergeNode[?, ?, ?]) => None
          case _ => Some(FanInError(nodeKey.value))
    }.flatten.toList

    NonEmptyList.fromList(fanInErrors) match
      case None => Valid(())
      case Some(nel) => Invalid(NonEmptyChain.fromNonEmptyList(nel))
