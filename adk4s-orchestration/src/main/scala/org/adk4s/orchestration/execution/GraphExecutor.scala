package org.adk4s.orchestration.execution

import cats.effect.IO
import cats.syntax.all.*
import org.adk4s.core.types.NodeKey
import org.adk4s.orchestration.fork.ForkSpec
import org.adk4s.orchestration.graph.{GCtx, Graph, GraphConfig, GraphNode}

/** GraphExecutor provides graph-to-WIO compilation and execution.
  *
  * The toWIO method compiles a graph into a Workflows4s WIO by:
  * 1. Starting from the typed entry node
  * 2. Walking through edges to build a WIO chain
  * 3. Converting each node to WIO using node.executable.toWIO
  * 4. Composing steps using WIO.AndThen
  * 5. Handling forks using WIO.Fork via ForkSpec
  *
  * All WIO steps preserve full type information throughout compilation.
  */
object GraphExecutor:
  /*
  /** Convert a graph to a typed WIO representation.
    *
    * Each node becomes a WIO step, composed using AndThen.
    * The resulting WIO can be run via a Workflows4s runtime.
    */
  def toWIO[Ctx <: WorkflowContext, In, Err, Out](
    graph: Graph[Ctx, In, Err, Out]
  )(using errorMeta: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] =
    graph.validateGraph match
      case cats.data.Validated.Invalid(errors) =>
        throw new IllegalArgumentException("Graph validation failed: " + errors.toList.map(_.message).mkString(", "))
      case cats.data.Validated.Valid(_) =>
        compileGraph(graph)
  */

  /** Execute a graph with the given input using IO-based traversal.
    * This is an alternative to WIO-based execution for direct IO execution.
    */
  def execute[In, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Out] =
    graph.compile(GraphConfig()).fold(
      errors => IO.raiseError(new Exception("Graph validation failed: " + errors.toList.map(_.message).mkString(", "))),
      _ => executeGraph(graph, input)
    )

  /** Execute a graph with error handling. */
  def executeWithError[In, Err, Out](
    graph: Graph[In, Out],
    input: In
  ): IO[Either[Err, Out]] =
    graph.compile(GraphConfig()).fold(
      errors => IO.raiseError(new Exception("Graph validation failed: " + errors.toList.map(_.message).mkString(", "))),
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

  /*
  /** Helper to create AndThen with proper type casting.
    *
    * Since graph nodes are stored in a Map with existential types,
    * we need to use casts when composing WIOs. This helper encapsulates
    * the unsafe cast in a controlled manner.
    */
  private def chainWIO[Ctx <: WorkflowContext, In, Err, Mid, Out](
    first: WIO[In, Err, Mid, Ctx],
    second: WIO[Mid, Err, Out, Ctx]
  ): WIO[In, Err, Out, Ctx] =
    WIO.AndThen[Ctx, In, Err, Mid, Out](first, second)

  private def compileGraph[Ctx <: WorkflowContext, In, Err, Out](
    graph: Graph[Ctx, In, Err, Out]
  )(using errorMeta: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] =
    val nodes: Map[NodeKey, GraphNode[Ctx, ?, ?, ?]] = graph.nodesMap
    val edges: Map[NodeKey, Set[NodeKey]] = graph.edgesMap
    val forks: Map[NodeKey, ForkSpec[Ctx, ?, ?, ?]] = graph.forksMap
    val endNodes: Set[NodeKey] = graph.endNodesSet

    graph.entry match
      case None =>
        throw new IllegalStateException("Entry node not set")
      case Some(entryNodeKey) =>
        // Start compilation from entry node
        // The types flow: In -> ... -> Out
        compileFromNodeUnsafe[Ctx, Err](entryNodeKey, nodes, edges, forks, endNodes, Set.empty)
          .asInstanceOf[WIO[In, Err, Out, Ctx]]

  /** Compile a subgraph starting from a given node.
    *
    * Returns a WIO with existential input/output types since we can't
    * statically know the types from the map lookup.
    */
  private def compileFromNodeUnsafe[Ctx <: WorkflowContext, Err](
    nodeKey: NodeKey,
    nodes: Map[NodeKey, GraphNode[Ctx, ?, ?, ?]],
    edges: Map[NodeKey, Set[NodeKey]],
    forks: Map[NodeKey, ForkSpec[Ctx, ?, ?, ?]],
    endNodes: Set[NodeKey],
    visited: Set[NodeKey]
  )(using errorMeta: ErrorMeta[Err]): WIO[Any, Err, Any, Ctx] =
    if visited.contains(nodeKey) then
      throw new IllegalStateException(s"Cycle detected at node ${nodeKey.value}")

    nodes.get(nodeKey) match
      case None =>
        throw new IllegalStateException(s"Node ${nodeKey.value} not found")
      case Some(node) =>
        // Get the node's WIO with type erasure
        val nodeWIO: WIO[Any, Err, Any, Ctx] =
          node.executable.toWIO.asInstanceOf[WIO[Any, Err, Any, Ctx]]

        val outgoingEdges: Set[NodeKey] = edges.getOrElse(nodeKey, Set.empty)
        val isEndNode: Boolean = endNodes.contains(nodeKey)
        val maybeFork: Option[ForkSpec[Ctx, ?, ?, ?]] = forks.get(nodeKey)

        (outgoingEdges.isEmpty, isEndNode, maybeFork) match
          // Terminal node - return the node WIO
          case (true, true, _) =>
            nodeWIO

          // End node with no outgoing edges
          case (true, false, _) if endNodes.contains(nodeKey) =>
            nodeWIO

          // Node with fork - the fork handles routing
          case (_, _, Some(forkSpec)) =>
            val forkWIO: WIO[Any, Err, Any, Ctx] =
              forkSpec.asInstanceOf[ForkSpec[Ctx, Any, Err, Any]]
                .toWIO(using errorMeta)
            WIO.AndThen[Ctx, Any, Err, Any, Any](nodeWIO, forkWIO)

          // Linear path - single outgoing edge
          case (false, _, None) if outgoingEdges.size == 1 =>
            val nextNodeKey: NodeKey = outgoingEdges.head
            val nextWIO: WIO[Any, Err, Any, Ctx] =
              compileFromNodeUnsafe[Ctx, Err](nextNodeKey, nodes, edges, forks, endNodes, visited + nodeKey)
            WIO.AndThen[Ctx, Any, Err, Any, Any](nodeWIO, nextWIO)

          // Multiple outgoing edges without fork
          case (false, _, None) =>
            throw new IllegalStateException(
              s"Node ${nodeKey.value} has multiple outgoing edges but no fork defined"
            )

          // Dead end
          case (true, false, _) =>
            throw new IllegalStateException(
              s"Node ${nodeKey.value} has no outgoing edges and is not marked as an end node"
            )
  */

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
          val nextNodeKey = outgoingEdges.head
          traverseGraph[O, Err, O](nextNodeKey, output, nodes, edges, endNodes, visited + currentNodeKey)
        
        // No outgoing edges but not marked as end
        case (true, false) =>
          throw new IllegalStateException(s"Node ${currentNodeKey.value} has no outgoing edges and is not marked as an end node")
        
        // Multiple outgoing edges (not supported in simple execution)
        case (false, _) =>
          throw new IllegalStateException(s"Node ${currentNodeKey.value} has multiple outgoing edges - not supported in simple execution")
    }

  /** Execute a graph with parallel DAG execution. */
  def executeParallel[In, Out](
    graph: Graph[In, Out],
    input: In,
    config: GraphConfig = GraphConfig(),
    callback: GraphCallback = NoOpCallback
  ): IO[Out] =
    graph.compile(config).fold(
      errors => IO.raiseError(new Exception("Graph validation failed: " + errors.toList.map(_.message).mkString(", "))),
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
          if queue.isEmpty then
            currentLevels
          else
            val currentNode = queue.head
            val remainingQueue = queue.tail
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
                  case _ => IO.raiseError(new Exception(s"Type mismatch at end node: " + value.getClass))
              case None => IO.pure(Left("End node not found in results"))
        case layer :: tail =>
          executeLayer(layer, currentResults).flatMap { newResults =>
            executeLayersSequentially(tail, newResults)
          }

    executeLayersSequentially(layers, Map(graph.entry.get -> input)).flatMap {
      case Right(value) => IO.pure(value)
      case Left(error) => IO.raiseError(new Exception(error))
    }

  /** Execute a single node in parallel context. */
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

        val input: Any =
          if incomingEdges.isEmpty then
            results.getOrElse(nodeKey, throw new Exception(s"No input for entry node ${nodeKey.value}"))
          else
            incomingEdges.headOption match
              case Some(sourceKey) => results.getOrElse(sourceKey, throw new Exception(s"No input for node ${nodeKey.value}"))
              case None => throw new Exception(s"No incoming edges for node ${nodeKey.value}")

        for
          _ <- callback.onNodeStart(nodeKey.value)
          start <- IO.monotonic
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
