package org.adk4s.orchestration.execution

import cats.effect.IO
import cats.syntax.all.*
import org.adk4s.core.error.{GenericError, GraphCompilationError}
import org.adk4s.core.types.NodeKey
import org.adk4s.orchestration.fork.ForkSpec
import org.adk4s.orchestration.graph.{GCtx, Graph, GraphConfig, GraphNode}

/** GraphExecutor provides graph compilation and execution.
  *
  * spec: add-iron-refined-types/wio-graph — Requirement: GraphExecutor raises GraphCompilationError instead of generic Exception
  */
object GraphExecutor:

  /** Execute a graph with the given input using IO-based traversal.
    * This is an alternative to WIO-based execution for direct IO execution.
    *
    * spec: add-iron-refined-types/wio-graph — Scenario: Failed compile raises GraphCompilationError
    */
  def execute[In, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Out] =
    graph.compile(GraphConfig()).fold(
      errors => IO.raiseError(GraphCompilationError(errors.toList)),
      _ => executeGraph(graph, input)
    )

  /** Execute a graph with error handling. */
  def executeWithError[In, Err, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Either[Err, Out]] =
    graph.compile(GraphConfig()).fold(
      errors => IO.raiseError(GraphCompilationError(errors.toList)),
      _ => executeGraphWithError(graph, input)
    )

  /** Internal: Execute a graph and return the output directly. */
  private[orchestration] def executeGraph[In, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Out] =
    executeGraphWithError(graph, input).flatMap {
      case Right(result) => IO.pure(result)
      case Left(err) => IO.raiseError(new Exception(s"Unexpected error: $err"))
    }

  private def executeGraphWithError[In, Err, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Either[Err, Out]] =
    val nodes: Map[NodeKey, GraphNode[?, ?]] = graph.nodesMap
    val edges: Map[NodeKey, Set[NodeKey]] = graph.edgesMap
    val endNodes: Set[NodeKey] = graph.endNodesSet

    graph.entry match
      case None =>
        IO.raiseError(new Exception("Entry node not set"))
      case Some(entryNode) =>
        traverseGraph[In, Err, Out](entryNode, input, nodes, edges, endNodes, Set.empty)

  private def traverseGraph[I, Err, O](
    currentNodeKey: NodeKey,
    currentInput: I,
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    endNodes: Set[NodeKey],
    visited: Set[NodeKey]
  ): IO[Either[Err, O]] =
    if visited.contains(currentNodeKey) then
      IO.raiseError(new Exception(s"Cycle detected at node ${currentNodeKey.value}"))
    else
      nodes.get(currentNodeKey) match
        case None =>
          IO.raiseError(new Exception(s"Node ${currentNodeKey.value} not found"))
        case Some(node: GraphNode[I, O]) =>
          executeNode(
            currentNodeKey,
            currentInput,
            node,
            nodes,
            edges,
            endNodes,
            visited + currentNodeKey
          )

  private def executeNode[I, Err, O](
    currentNodeKey: NodeKey,
    currentInput: I,
    node: GraphNode[I, O],
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    endNodes: Set[NodeKey],
    visited: Set[NodeKey]
  ): IO[Either[Err, O]] =
    // Execute the current node using its executable
    val nodeExecutable: NodeExecutable[I, O] = node.executable
    val nodeOutputIO: IO[O] = nodeExecutable.invoke(currentInput)

    nodeOutputIO.flatMap { output =>
      val outgoingEdges: Set[NodeKey] = edges.getOrElse(currentNodeKey, Set.empty)
      val isEndNode: Boolean = endNodes.contains(currentNodeKey)

      (outgoingEdges.isEmpty, isEndNode) match
        // Terminal node
        case (true, true) =>
          IO.pure(Right(output))
        
        // Single outgoing edge
        case (false, _) if outgoingEdges.size == 1 =>
          outgoingEdges.headOption.fold(
            IO.raiseError(GenericError("outgoingEdges unexpectedly empty despite size == 1")): IO[Either[Err, O]]
          )(nextNodeKey =>
            traverseGraph[O, Err, O](nextNodeKey, output, nodes, edges, endNodes, visited + currentNodeKey)
          )

        // No outgoing edges but not marked as end
        case (true, false) =>
          IO.raiseError(GenericError(s"Node ${currentNodeKey.value} has no outgoing edges and is not marked as an end node"))

        // Multiple outgoing edges (not supported in simple execution)
        case (false, _) =>
          IO.raiseError(GenericError(s"Node ${currentNodeKey.value} has multiple outgoing edges - not supported in simple execution"))
    }

  /** Execute a graph with parallel DAG execution. */
  def executeParallel[In, Out](
    graph: Graph[In, Out],
    input: In,
    config: GraphConfig = GraphConfig(),
    callback: GraphCallback = NoOpCallback
  ): IO[Out] =
    graph.compile(config).fold(
      errors => IO.raiseError(GraphCompilationError(errors.toList)),
      _ => executeGraphParallel(graph, input, config, callback)
    )

  /** Execute a graph with parallel DAG execution using Kahn's algorithm. */
  private def executeGraphParallel[In, Out](
    graph: Graph[In, Out],
    input: In,
    config: GraphConfig,
    callback: GraphCallback
  ): IO[Out] =
    calculateDAGLayers(graph) match
      case Left(error) => IO.raiseError(new Exception(error))
      case Right(layers) =>
        executeLayers(layers, graph, input, config, callback)

  /** Calculate DAG layers using Kahn's algorithm. */
  private def calculateDAGLayers[In, Out](
    graph: Graph[In, Out]
  ): Either[String, List[List[NodeKey]]] =
    val nodes: Map[NodeKey, GraphNode[?, ?]] = graph.nodesMap
    val edges: Map[NodeKey, Set[NodeKey]] = graph.edgesMap
    val entry: Option[NodeKey] = graph.entry

    entry match
      case None => Left("Entry node not set")
      case Some(entryNode) =>
        val incomingEdges: Map[NodeKey, List[NodeKey]] =
          edges.toList.flatMap { case (from, tos) => tos.map(to => to -> from) }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

        def calculateLevels(
          currentLevels: Map[NodeKey, Int],
          queue: List[NodeKey]
        ): Map[NodeKey, Int] =
          queue match
            case Nil => currentLevels
            case currentNode :: remainingQueue =>
              val outgoing = edges.getOrElse(currentNode, Set.empty).toList

              val (updatedLevels, newQueue) = outgoing.foldLeft((currentLevels, remainingQueue)) { case ((levels, q), target) =>
                val dependencies = incomingEdges.getOrElse(target, Nil)

                if dependencies.forall(levels.contains) then
                  val maxDepLevel = dependencies.map(levels).maxOption.getOrElse(-1)
                  val targetLevel = maxDepLevel + 1

                  if !levels.contains(target) then
                    (levels + (target -> targetLevel), if !q.contains(target) then q :+ target else q)
                  else
                    (levels, q)
                else
                  (levels, q)
              }

              calculateLevels(updatedLevels, newQueue)

        val levels = calculateLevels(Map(entryNode -> 0), List(entryNode))

        if levels.size != nodes.size then
          Left("Failed to assign levels - graph may contain cycles")
        else
          val layers = levels
            .groupBy(_._2)
            .toSeq
            .sortBy(_._1)
            .map { case (_, nodeEntries) => nodeEntries.keys.toList }
            .toList

          Right(layers)

  /** Execute DAG layers sequentially, nodes within layers in parallel. */
  private def executeLayers[In, Out](
    layers: List[List[NodeKey]],
    graph: Graph[In, Out],
    input: In,
    config: GraphConfig,
    callback: GraphCallback
  ): IO[Out] =
    val nodes: Map[NodeKey, GraphNode[?, ?]] = graph.nodesMap
    val edges: Map[NodeKey, Set[NodeKey]] = graph.edgesMap

    def executeLayer(
      layer: List[NodeKey],
      results: Map[NodeKey, Any]
    ): IO[Map[NodeKey, Any]] =
      layer match
        case Nil => IO.pure(results)
        case nodeKeys =>
          def executeWithLimit(
            remaining: List[NodeKey],
            acc: Map[NodeKey, Any]
          ): IO[Map[NodeKey, Any]] =
            if remaining.isEmpty then
              IO.pure(acc)
            else
              val (batch, rest) = remaining.splitAt(config.maxParallelism)
              val batchResults = batch.traverse { nodeKey =>
                executeNodeParallel(nodeKey, results, graph, nodes, edges, callback)
              }
              batchResults.flatMap { newResults =>
                val updated = acc ++ newResults
                executeWithLimit(rest, updated)
              }

          executeWithLimit(nodeKeys, Map.empty).map(_ ++ results)

    def executeLayersSequentially(
      remainingLayers: List[List[NodeKey]],
      currentResults: Map[NodeKey, Any]
    ): IO[Either[String, Out]] =
      remainingLayers match
        case Nil =>
          graph.endNodesSet.toList.headOption match
            case None => IO.pure(Left("No end nodes"))
            case Some(endKey) => currentResults.get(endKey) match
              case Some(value) => 
                value match
                  case out: Out => IO.pure(Right(out))
                  case _ => IO.raiseError(new Exception(s"Type mismatch at end node: ${value.getClass}"))
              case None => IO.pure(Left("End node not found in results"))
        case layer :: tail =>
          executeLayer(layer, currentResults).flatMap { newResults =>
            executeLayersSequentially(tail, newResults)
          }

    graph.entry.fold(
      IO.raiseError(GenericError("Graph has no entry node")): IO[Out]
    )(entryKey =>
      executeLayersSequentially(layers, Map(entryKey -> input)).flatMap {
        case Right(value) => IO.pure(value)
        case Left(error) => IO.raiseError(new Exception(error))
      }
    )

  /** Execute a single node in parallel context. */
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def executeNodeParallel(
    nodeKey: NodeKey,
    results: Map[NodeKey, Any],
    graph: Graph[?, ?],
    nodes: Map[NodeKey, GraphNode[?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    callback: GraphCallback
  ): IO[(NodeKey, Any)] =
    nodes.get(nodeKey) match
      case None => IO.raiseError(new Exception(s"Node ${nodeKey.value} not found"))
      case Some(node) =>
        val nodeExecutable: NodeExecutable[Any, Any] = node.executable.asInstanceOf[NodeExecutable[Any, Any]]
        val incomingEdges: Set[NodeKey] = edges.filter { case (_, tos) => tos.contains(nodeKey) }.keySet

        val inputIO: IO[Any] =
          if incomingEdges.isEmpty then
            results.get(nodeKey).fold(IO.raiseError(GenericError(s"No input for entry node ${nodeKey.value}")): IO[Any])(IO.pure)
          else
            incomingEdges.headOption match
              case Some(sourceKey) => results.get(sourceKey).fold(IO.raiseError(GenericError(s"No input for node ${nodeKey.value}")): IO[Any])(IO.pure)
              case None => IO.raiseError(GenericError(s"No incoming edges for node ${nodeKey.value}"))

        for
          _ <- callback.onNodeStart(nodeKey.value)
          start <- IO.monotonic
          input <- inputIO
          result <- nodeExecutable.invoke(input).attempt
          end <- IO.monotonic
          duration = end - start
          output <- result match
            case Right(value) =>
              callback.onNodeSuccess(nodeKey.value, duration).as(value)
            case Left(error) =>
              callback.onNodeFailure(nodeKey.value, error, duration).flatMap { _ =>
                IO.raiseError(error)
              }
        yield nodeKey -> output
