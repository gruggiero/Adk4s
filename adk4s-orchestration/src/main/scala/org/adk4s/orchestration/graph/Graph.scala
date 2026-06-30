package org.adk4s.orchestration.graph

import cats.effect.IO
import cats.data.{ValidatedNec, NonEmptyChain}
import cats.data.Validated.{Valid, Invalid}
import org.adk4s.core.types.{NodeKey, PromptTemplate, Schema}
import org.adk4s.core.runnable.{Runnable, Lambda}
import org.adk4s.core.component.ChatModel
import org.adk4s.core.tools.{StructuredToolCall, ToolSchema, ToolsNode}
import org.adk4s.core.error.{AdkError, GraphCompiledError, NodeAlreadyExistsError, SourceNodeNotFoundError, NodeDoesNotExistError, FanInError}
import org.adk4s.orchestration.execution.GraphWorkflowContext
import org.adk4s.orchestration.fork.ForkSpec
import org.llm4s.llmconnect.model.{Conversation, Completion, ToolCall, ToolMessage}
import org.llm4s.agent.orchestration.TypedAgent
import org.adk4s.core.runnable.TypedAgentBridge
import workflows4s.wio.{ErrorMeta, WCState, WIO, WorkflowContext}

import scala.reflect.ClassTag

case class GraphConfig(
  maxRunSteps: Int = 100,
  graphName: Option[String] = None,
  maxParallelism: Int = 10
)

/** Type alias for GraphWorkflowContext.type for cleaner syntax. */
type GCtx = GraphWorkflowContext.type

/** Graph represents a typed workflow graph.
  *
  * Type parameters:
  * - In: Input type to the graph
  * - Out: Output type
  */
case class Graph[In, Out] private (
  private val nodes: Map[NodeKey, GraphNode[?, ?]],
  private val edges: Map[NodeKey, Set[NodeKey]],
  private val entryNode: Option[NodeKey],
  private val endNodes: Set[NodeKey],
  private val compiled: Boolean = false
):
  /** Add a lambda node to the graph. */
  def addLambdaNode[A, B](
    key: String,
    lambda: Lambda[A, B]
  )(using classTag: ClassTag[B]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.LambdaNode[A, B](lambda)
    addNodeWithRef(nodeKey, node)

  /** Add a chat model node to the graph. */
  def addChatModelNode(
    key: String,
    model: ChatModel[IO]
  )(using classTag: ClassTag[Completion]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, Conversation, Completion]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[Conversation, Completion] = GraphNode.ChatModelNode(model)
    addNodeWithRef(nodeKey, node)

  /** Add a tools node to the graph. */
  def addToolsNode(
    key: String,
    toolsNode: ToolsNode
  )(using classTag: ClassTag[List[ToolMessage]]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, List[ToolCall], List[ToolMessage]]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[List[ToolCall], List[ToolMessage]] = GraphNode.ToolsNode(toolsNode)
    addNodeWithRef(nodeKey, node)

  /** Add a sub-graph node. */
  def addSubGraphNode[A, B](
    key: String,
    subGraph: Graph[A, B]
  )(using classTag: ClassTag[B]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.SubGraphNode[A, B](subGraph)
    addNodeWithRef(nodeKey, node)

  /** Add a structured model node. */
  def addStructuredModelNode[A, B](
    key: String,
    model: org.adk4s.structured.core.StructuredLLM[IO],
    template: PromptTemplate[A]
  )(using schema: Schema[B], classTag: ClassTag[B]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.StructuredModelNode[A, B](model, template)
    addNodeWithRef(nodeKey, node)

  /** Add a structured tool node. */
  def addStructuredToolNode[ToolIn, ToolOut](
    key: String,
    structuredToolCall: StructuredToolCall[IO],
    toolName: String
  )(using
    inputSchema: ToolSchema[ToolIn],
    outputSchema: ToolSchema[ToolOut],
    classTag: ClassTag[ToolOut]
  ): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, ToolCall, ToolOut]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[ToolCall, ToolOut] =
      GraphNode.StructuredToolNode[ToolIn, ToolOut](structuredToolCall, toolName)
    addNodeWithRef(nodeKey, node)

  /** Add a merge node that combines two inputs. */
  def addMergeNode[A, B, C](
    key: String,
    combine: (A, B) => C
  )(using classTag: ClassTag[C]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, (A, B), C]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[(A, B), C] = GraphNode.MergeNode[A, B, C](combine)
    addNodeWithRef(nodeKey, node)

  /** Add a fork node with a ForkSpec. */
  def addForkNode[A, B](
    key: String,
    forkSpec: ForkSpec[A, B]
  ): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.ForkNode[A, B](forkSpec)
    addNodeWithRef(nodeKey, node)

  /** Add a TypedAgent node via the Runnable bridge. */
  def addTypedAgentNode[A, B](
    key: String,
    agent: TypedAgent[A, B]
  )(using ec: scala.concurrent.ExecutionContext, classTag: ClassTag[B]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    import scala.language.implicitConversions
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val runnable = TypedAgentBridge.toRunnable(agent)(using ec)
    val lambda: Lambda[A, B] = Lambda((input: A) => runnable.invoke(input))
    val node: GraphNode[A, B] = GraphNode.LambdaNode[A, B](lambda)
    addNodeWithRef(nodeKey, node)

  /** Add a pure transformation node. */
  def addPureNode[A, B](
    key: String,
    transform: A => Either[Throwable, B]
  ): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.PureNode[A, B](transform)
    addNodeWithRef(nodeKey, node)

  /** Add a RunIO node with explicit event handling. */
  def addRunIONode[A, Evt, B](
    key: String,
    runIO: A => IO[Evt],
    handleEvent: (A, Evt) => Either[Throwable, B]
  )(using eventClassTag: ClassTag[Evt]): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val nodeKey: NodeKey = NodeKey.unsafeApply(key)
    val node: GraphNode[A, B] = GraphNode.RunIONode[A, Evt, B](runIO, handleEvent)
    addNodeWithRef(nodeKey, node)

  /** Add an edge between two nodes. Type-safe: output of 'from' must match input of 'to'. */
  def addEdge[A, B, C](
    from: Graph.NodeRef[A, B],
    to: Graph.NodeRef[B, C]
  ): ValidatedNec[AdkError, Graph[In, Out]] =
    addEdgeInternal(from, to)

  /** Add a fork specification at a node. */
  def addFork[A, B](
    from: Graph.NodeRef[A, B],
    forkSpec: ForkSpec[A, B]
  ): ValidatedNec[AdkError, Graph[In, Out]] =
    addForkInternal(from, forkSpec)

  /** Set the entry node for the graph. */
  def setEntry[A](entry: Graph.NodeRef[In, A]): ValidatedNec[AdkError, Graph[In, Out]] =
    setEntryInternal(entry)

  /** Add an end node to the graph. */
  def addEndNode[A](endNode: Graph.NodeRef[A, Out]): ValidatedNec[AdkError, Graph[In, Out]] =
    addEndNodeInternal(endNode)

  /** Get the nodes map. */
  def nodesMap: Map[NodeKey, GraphNode[?, ?]] = nodes

  /** Get the edges map. */
  def edgesMap: Map[NodeKey, Set[NodeKey]] = edges


  

  /** Get the entry node key. */
  def entry: Option[NodeKey] = entryNode


  /** Get the end node keys. */
  def endNodesSet: Set[NodeKey] = endNodes


  /** Compile the graph to a Runnable. */
  def compile(config: GraphConfig = GraphConfig()): ValidatedNec[AdkError, IO[Runnable[In, Out]]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else
      validateGraph.map { _ =>
        IO.delay(buildRunnable(config))
      }

  /** Validate the graph structure. */
  def validateGraph: ValidatedNec[AdkError, Unit] =
    GraphValidation.validateGraph(this)

  private def buildRunnable(config: GraphConfig): Runnable[In, Out] =
    Runnable.fromInvoke { (input: In) =>
      executeGraph(input, config)
    }

  private def executeGraph(input: In, config: GraphConfig): IO[Out] =
    IO.raiseError(org.adk4s.core.error.GenericError("Graph execution not yet fully implemented - use GraphExecutor instead"))

  private def addNode[A, B](
    key: NodeKey,
    node: GraphNode[A, B]
  ): ValidatedNec[AdkError, Graph[In, Out]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if nodes.contains(key) then
      Invalid(NonEmptyChain.one(NodeAlreadyExistsError(key.value)))
    else
      Valid(copy(
        nodes = nodes + (key -> node),
        edges = edges + (key -> Set.empty)
      ))

  private def addNodeWithRef[A, B](
    key: NodeKey,
    node: GraphNode[A, B]
  ): ValidatedNec[AdkError, Graph.NodeAddition[In, Out, A, B]] =
    val buildAddition: Graph[In, Out] => Graph.NodeAddition[In, Out, A, B] =
      (graph: Graph[In, Out]) =>
        val ref: Graph.NodeRef[A, B] = Graph.NodeRef[A, B](key)
        Graph.NodeAddition[In, Out, A, B](graph, ref)
    addNode(key, node).map(buildAddition)

  private def addEdgeInternal[A, B, C](
    from: Graph.NodeRef[A, B],
    to: Graph.NodeRef[B, C]
  ): ValidatedNec[AdkError, Graph[In, Out]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(from.key) then
      Invalid(NonEmptyChain.one(SourceNodeNotFoundError(from.key.value)))
    else
      val currentEdges: Set[NodeKey] = edges.getOrElse(from.key, Set.empty)
      val fanInCheck: ValidatedNec[AdkError, Unit] = checkFanIn(to.key, from.key)
      fanInCheck.map(_ => copy(
        edges = edges + (from.key -> (currentEdges + to.key))
      ))

  private def checkFanIn(targetNode: NodeKey, newSource: NodeKey): ValidatedNec[AdkError, Unit] =
    if entryNode.contains(targetNode) then
      Valid(())
    else
      val existingIncoming: Set[NodeKey] = edges.values.flatten.filter(_ == targetNode).toSet
      if existingIncoming.nonEmpty then
        Invalid(NonEmptyChain.one(FanInError(targetNode.value)))
      else
        Valid(())

  private def addForkInternal[A, B](
    from: Graph.NodeRef[A, B],
    forkSpec: ForkSpec[A, B]
  ): ValidatedNec[AdkError, Graph[In, Out]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(from.key) then
      Invalid(NonEmptyChain.one(NodeDoesNotExistError(from.key.value)))
    else
      Valid(copy()) // Forks not supported in simplified Graph

  private def setEntryInternal[A](entry: Graph.NodeRef[In, A]): ValidatedNec[AdkError, Graph[In, Out]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(entry.key) then
      Invalid(NonEmptyChain.one(NodeDoesNotExistError(entry.key.value)))
    else
      Valid(copy(entryNode = Some(entry.key)))

  private def addEndNodeInternal[A](endNode: Graph.NodeRef[A, Out]): ValidatedNec[AdkError, Graph[In, Out]] =
    if compiled then
      Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(endNode.key) then
      Invalid(NonEmptyChain.one(NodeDoesNotExistError(endNode.key.value)))
    else
      Valid(copy(
        endNodes = endNodes + endNode.key
      ))

  /** Validate that an edge can be added (without modifying graph). */
  def validateEdge(from: NodeKey, to: NodeKey): ValidatedNec[AdkError, Unit] =
    if compiled then Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(from) then Invalid(NonEmptyChain.one(SourceNodeNotFoundError(from.value)))
    else if !nodes.contains(to) then Invalid(NonEmptyChain.one(NodeDoesNotExistError(to.value)))
    else checkFanIn(to, from)

  /** Validate that a node exists. */
  def validateNodeExists(key: NodeKey): ValidatedNec[AdkError, Unit] =
    if compiled then Invalid(NonEmptyChain.one(GraphCompiledError()))
    else if !nodes.contains(key) then Invalid(NonEmptyChain.one(NodeDoesNotExistError(key.value)))
    else Valid(())

  /** Add edge without validation (package-private, use after validation). */
  private[graph] def addEdgeUnchecked(from: NodeKey, to: NodeKey): Graph[In, Out] =
    val currentEdges: Set[NodeKey] = edges.getOrElse(from, Set.empty)
    copy(edges = edges + (from -> (currentEdges + to)))

  /** Set entry without validation (package-private, use after validation). */
  private[graph] def setEntryUnchecked(key: NodeKey): Graph[In, Out] =
    copy(entryNode = Some(key))

  /** Add end node without validation (package-private, use after validation). */
  private[graph] def addEndNodeUnchecked(key: NodeKey): Graph[In, Out] =
    copy(endNodes = endNodes + key)

object Graph:
  /** Typed reference to a node in the graph. */
  case class NodeRef[I, O](key: NodeKey)

  /** Result of adding a node to a graph. */
  case class NodeAddition[In, Out, A, B](
    graph: Graph[In, Out],
    ref: NodeRef[A, B]
  )

  /** Typed edge preserving input/output type relationships.
    * Ensures that the output type of 'from' (B) matches the input type of 'to' (B2).
    */
  case class Edge[A, B, B2, C](
    from: NodeRef[A, B],
    to: NodeRef[B2, C]
  )

  /** Typed fork binding preserving the relationship between node output and fork input. */
  case class ForkBinding[A, B](
    from: NodeRef[A, B],
    forkSpec: ForkSpec[A, B]
  )

  /** Typed entry reference ensuring the entry node accepts the graph input type. */
  case class EntryRef[In, A](ref: NodeRef[In, A])

  /** Typed end reference ensuring the end node produces the graph output type. */
  case class EndRef[A, Out](ref: NodeRef[A, Out])

  /** Create an empty graph. */
  def apply[In, Out]: Graph[In, Out] =
    Graph[In, Out](
      Map.empty[NodeKey, GraphNode[?, ?]],
      Map.empty[NodeKey, Set[NodeKey]],
      None,
      Set.empty[NodeKey],
      false
    )

  /** Create an empty graph with custom error type. */
  def withError[In, Out]: Graph[In, Out] =
    Graph[In, Out](
      Map.empty[NodeKey, GraphNode[?, ?]],
      Map.empty[NodeKey, Set[NodeKey]],
      None,
      Set.empty[NodeKey],
      false
    )

  /** Extension for Graph to provide configure method. */
  extension [In, Out](graph: Graph[In, Out])
    /** Configure graph structure with accumulated error reporting. */
    def configure(f: GraphStructure[In, Out] => GraphStructure[In, Out]): ValidatedNec[AdkError, Graph[In, Out]] =
      f(GraphStructure[In, Out]).applyTo(graph)
