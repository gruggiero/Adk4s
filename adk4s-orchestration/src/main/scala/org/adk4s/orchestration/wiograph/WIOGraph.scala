package org.adk4s.orchestration.wiograph

import cats.data.NonEmptyChain
import cats.effect.IO
import fs2.Stream
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import workflows4s.wio.{ErrorMeta, WCEvent, WCState, WIO, WorkflowContext}
import org.adk4s.core.types.NodeKey

import java.time.Instant
import scala.reflect.ClassTag

final case class WIOGraph[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]] private (
  private val nodes: List[WIOGraph.NodeEntry[Ctx, Err, ?, ? <: Out]],
  private val edges: List[WIOGraph.Edge[Ctx, ?, ? <: Out, ? <: Out]],
  private val entryNode: Option[WIONodeRef[Ctx, In, ? <: Out]],
  private val endNodes: Set[NodeKey]
):

  def addNode[I, O <: Out](
    key: String,
    node: WIONode[Ctx, I, Err, O]
  ): WIOGraph[Ctx, In, Err, Out] =
    val nodeKey = NodeKey.unsafeApply(key)
    if nodes.exists(entry => entry.ref.key == nodeKey) then
      throw new IllegalArgumentException(s"Node '$key' already exists")
    else
      val entry: WIOGraph.NodeEntry[Ctx, Err, I, O] = WIOGraph.NodeEntry[Ctx, Err, I, O](WIONodeRef[Ctx, I, O](nodeKey), node, List.empty[WIONodeModifier[Ctx, I, Err, O]])
      copy(nodes = nodes :+ entry)

  def addRunnableNode[I, Evt <: WCEvent[Ctx], RawOut, O <: Out](
    key: String,
    runnable: Runnable[I, RawOut],
    toEvent: RawOut => Evt,
    handleEvent: (I, Evt) => Either[Err, O]
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOGraph[Ctx, In, Err, Out] =
    val node: WIORunnableNode[Ctx, I, Err, Evt, RawOut, O] =
      WIORunnableNode[Ctx, I, Err, Evt, RawOut, O](runnable, toEvent, handleEvent)
    addNode(key, node)

  def addLambdaNode[I, Evt <: WCEvent[Ctx], RawOut, O <: Out](
    key: String,
    lambda: Lambda[I, RawOut],
    toEvent: RawOut => Evt,
    handleEvent: (I, Evt) => Either[Err, O]
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOGraph[Ctx, In, Err, Out] =
    addRunnableNode(key, lambda.toRunnable, toEvent, handleEvent)

  def setEntry[A <: Out](entry: WIONodeRef[Ctx, In, A]): WIOGraph[Ctx, In, Err, Out] =
    if !nodes.exists(existing => existing.ref.key == entry.key) then
      throw new IllegalArgumentException(s"Entry node '${entry.key}' does not exist")
    else
      copy(entryNode = Some(entry))

  def addEndNode[O2 <: Out](endNode: WIONodeRef[Ctx, O2, Out]): WIOGraph[Ctx, In, Err, Out] =
    if !nodes.exists(existing => existing.ref.key == endNode.key) then
      throw new IllegalArgumentException(s"End node '${endNode.key}' does not exist")
    else
      copy(endNodes = endNodes + endNode.key)

  def withCheckpoint[I, O <: Out, Evt <: WCEvent[Ctx]](
    nodeRef: WIONodeRef[Ctx, I, O],
    genEvent: (I, O) => Evt,
    handleEvent: (I, Evt) => O
  )(using evtCt: ClassTag[Evt]): WIOGraph[Ctx, In, Err, Out] =
    val modifier: CheckpointModifier[Ctx, I, Err, O, Evt] =
      CheckpointModifier[Ctx, I, Err, O, Evt](genEvent, handleEvent)
    addModifier(nodeRef, modifier)

  def withRetry[I, O <: Out](
    nodeRef: WIONodeRef[Ctx, I, O],
    onError: (Throwable, WCState[Ctx], Instant) => IO[Option[Instant]]
  ): WIOGraph[Ctx, In, Err, Out] =
    val modifier: RetryModifier[Ctx, I, Err, O] =
      RetryModifier[Ctx, I, Err, O](onError)
    addModifier(nodeRef, modifier)

  def withInterruption[I, O <: Out](
    nodeRef: WIONodeRef[Ctx, I, O],
    interruption: WIO.Interruption[Ctx, Err, O]
  ): WIOGraph[Ctx, In, Err, Out] =
    val modifier: InterruptionModifier[Ctx, I, Err, O] =
      InterruptionModifier[Ctx, I, Err, O](interruption)
    addModifier(nodeRef, modifier)

  private def addModifier[I, O <: Out](
    nodeRef: WIONodeRef[Ctx, I, O],
    modifier: WIONodeModifier[Ctx, I, Err, O]
  ): WIOGraph[Ctx, In, Err, Out] =
    val updatedNodes: List[WIOGraph.NodeEntry[Ctx, Err, ?, ? <: Out]] = nodes.map {
      case entry @ WIOGraph.NodeEntry(`nodeRef`, node, modifiers) =>
        WIOGraph.NodeEntry[Ctx, Err, I, O](nodeRef, node, modifiers :+ modifier)
      case other => other
    }
    if !nodes.exists(entry => entry.ref.key == nodeRef.key) then
      throw new IllegalArgumentException(s"Node '${nodeRef.key}' does not exist")
    else
      copy(nodes = updatedNodes)

  def addEdge[A, B <: Out, C <: Out](
    from: WIONodeRef[Ctx, A, B],
    to: WIONodeRef[Ctx, B, C]
  ): WIOGraph[Ctx, In, Err, Out] =
    if !nodes.exists(existing => existing.ref.key == from.key) then
      throw new IllegalArgumentException(s"Source node '${from.key}' does not exist")
    if !nodes.exists(existing => existing.ref.key == to.key) then
      throw new IllegalArgumentException(s"Target node '${to.key}' does not exist")
    else
      copy(edges = edges :+ WIOGraph.Edge(from, to))

  def toWIO(using errorMeta: ErrorMeta[Err]): Either[NonEmptyChain[WIOGraphError], WIO[In, Err, Out, Ctx]] =
    for
      _ <- validateNoCycles
      _ <- validateEntrySet
      _ <- validateEndsReachable
      entry <- entryNode.toRight(NonEmptyChain.one(WIOGraphError.MissingEntry))
    yield compileFromEntry(entry)

  /** Compile this graph into a Runnable that supports invoke, stream, collect, and transform modes.
    * Each node is converted to a Runnable and chained sequentially.
    * WIORunnableNode uses its stored Runnable directly; other node types use invoke-based wrapping.
    */
  def toRunnable: Either[NonEmptyChain[WIOGraphError], Runnable[In, Out]] =
    for
      _ <- validateNoCycles
      _ <- validateEntrySet
      _ <- validateEndsReachable
      entry <- entryNode.toRight(NonEmptyChain.one(WIOGraphError.MissingEntry))
    yield compileRunnableFromEntry(entry)

  private def compileRunnableFromEntry[A <: Out](ref: WIONodeRef[Ctx, In, A]): Runnable[In, Out] =
    compileRunnableFromNode(ref)

  private def compileRunnableFromNode[A, B <: Out](ref: WIONodeRef[Ctx, A, B]): Runnable[A, Out] =
    val entry: WIOGraph.NodeEntry[Ctx, Err, A, B] = entryFor(ref)
    val node: WIONode[Ctx, A, Err, B] = entry.node
    val outgoingEdges: List[WIOGraph.Edge[Ctx, A, B, ? <: Out]] =
      edges.collect { case edge @ WIOGraph.Edge(`ref`, _) => edge }

    node match
      case forkNode: WIOForkNode[Ctx, A, Err, B] =>
        compileRunnableFork(forkNode, outgoingEdges)
      case _ =>
        val nodeRunnable: Runnable[A, B] = nodeToRunnable(node)
        outgoingEdges match
          case Nil =>
            nodeRunnable.asInstanceOf[Runnable[A, Out]]
          case edge :: Nil =>
            val next: Runnable[B, Out] = compileRunnableFromNode(edge.to)
            chainRunnables(nodeRunnable, next)
          case _ =>
            throw new IllegalStateException(
              s"Node '${ref.key}' has multiple outgoing edges, which is not supported for linear Runnable compilation"
            )

  private def nodeToRunnable[A, B <: Out](node: WIONode[Ctx, A, Err, B]): Runnable[A, B] =
    node match
      case runnableNode: WIORunnableNode[Ctx, A, Err, _, _, B] =>
        runnableNode.toRunnable
      case pureNode: WIOPureNode[Ctx, A, Err, B] =>
        Runnable.fromInvoke[A, B]((input: A) =>
          pureNode.transform(input) match
            case Right(output) => IO.pure(output)
            case Left(err) => IO.raiseError(new RuntimeException(s"Pure node error: $err"))
        )
      case runIONode: WIORunIONode[Ctx, A, Err, _, B] =>
        runIONode.toRunnable
      case subGraphNode: WIOSubGraphNode[Ctx, A, Err, B] =>
        subGraphNode.subGraph.toRunnable match
          case Right(runnable) => runnable.asInstanceOf[Runnable[A, B]]
          case Left(errors) =>
            val message: String = errors.toNonEmptyList.toList.mkString("Sub-graph Runnable compilation failed: ", ", ", "")
            throw new IllegalStateException(message)
      case _ =>
        throw new IllegalStateException(s"Node type ${node.getClass.getSimpleName} is not supported for Runnable compilation")

  private def chainRunnables[A, B, C](first: Runnable[A, B], second: Runnable[B, C]): Runnable[A, C] =
    Runnable.full[A, C](
      invokeFn = (input: A) => first.invoke(input).flatMap(second.invoke),
      streamFn = (input: A) => first.stream(input).flatMap(second.stream),
      collectFn = (inputStream: Stream[IO, A]) => first.collect(inputStream).flatMap(second.invoke),
      transformFn = (inputStream: Stream[IO, A]) => second.transform(first.transform(inputStream))
    )

  private def compileRunnableFork[A, B <: Out](
    forkNode: WIOForkNode[Ctx, A, Err, B],
    outgoingEdges: List[WIOGraph.Edge[Ctx, A, B, ? <: Out]]
  ): Runnable[A, Out] =
    val branches: List[WIOForkNode.Branch[Ctx, A, Err, B]] = forkNode.branches
    val edgeCount: Int = outgoingEdges.length
    val branchesWithEdges: List[(WIOForkNode.Branch[Ctx, A, Err, B], WIOGraph.Edge[Ctx, A, B, ? <: Out])] =
      branches.take(edgeCount).zip(outgoingEdges)
    val branchesWithoutEdges: List[WIOForkNode.Branch[Ctx, A, Err, B]] =
      branches.drop(edgeCount)

    Runnable.fromInvoke[A, Out] { (input: A) =>
      val matchedBranch: Option[(WIOForkNode.Branch[Ctx, A, Err, B], Option[WIOGraph.Edge[Ctx, A, B, ? <: Out]])] =
        branchesWithEdges.collectFirst {
          case (branch, edge) if branch.predicate(input).isDefined => (branch, Some(edge))
        }.orElse(
          branchesWithoutEdges.collectFirst {
            case branch if branch.predicate(input).isDefined => (branch, None)
          }
        )

      matchedBranch match
        case None =>
          IO.raiseError(new RuntimeException("No branch matched for fork node"))
        case Some((branch, edgeOpt)) =>
          // Execute the branch WIO through the workflow runtime
          executeBranchWio(branch.workflow, input).flatMap { (branchResult: B) =>
            edgeOpt match
              case Some(edge) =>
                val next: Runnable[B, Out] = compileRunnableFromNode(edge.to)
                next.invoke(branchResult)
              case None =>
                IO.pure(branchResult.asInstanceOf[Out])
          }
    }

  private def executeBranchWio[A, B <: WCState[Ctx]](
    wio: WIO[A, Err, B, Ctx],
    input: A
  ): IO[B] =
    import cats.data.Ior
    import workflows4s.runtime.WorkflowInstanceId
    import workflows4s.wio.ActiveWorkflow
    import workflows4s.wio.internal.WakeupResult
    val workflowId: WorkflowInstanceId = WorkflowInstanceId("fork-branch", "fork-branch")
    val initialWio: WIO.Initial[Ctx] = wio.provideInput(input).asInstanceOf[WIO.Initial[Ctx]]
    val start: ActiveWorkflow[Ctx] =
      ActiveWorkflow[Ctx](workflowId, initialWio, input.asInstanceOf[WCState[Ctx]])

    def proceedOnce(
      workflow: ActiveWorkflow[Ctx]
    ): IO[(ActiveWorkflow[Ctx], Boolean)] =
      val wakeup: WakeupResult[WCEvent[Ctx]] = workflow.proceed(java.time.Instant.EPOCH)
      wakeup.toRaw match
        case None => IO.pure((workflow, false))
        case Some(io: IO[Ior[java.time.Instant, WCEvent[Ctx]]]) =>
          io.map { (result: Ior[java.time.Instant, WCEvent[Ctx]]) =>
            val eventOpt: Option[WCEvent[Ctx]] = result match
              case Ior.Right(event) => Some(event)
              case Ior.Both(_, event) => Some(event)
              case Ior.Left(_) => None
            eventOpt match
              case Some(event) =>
                (workflow.handleEvent(event).getOrElse(workflow), true)
              case None => (workflow, false)
          }

    def loop(workflow: ActiveWorkflow[Ctx]): IO[ActiveWorkflow[Ctx]] =
      proceedOnce(workflow).flatMap { case (next, continued) =>
        if continued then loop(next) else IO.pure(next)
      }

    loop(start).map(_.liveState.asInstanceOf[B])

  private def validateNoCycles: Either[NonEmptyChain[WIOGraphError], Unit] =
    val allNodes: Set[NodeKey] = edges.flatMap(edge => List(edge.from.key, edge.to.key)).toSet

    def dfs(nodeKey: NodeKey, visited: Set[NodeKey], stack: List[NodeKey]): Either[NonEmptyChain[WIOGraphError], Set[NodeKey]] =
      if stack.contains(nodeKey) then
        Left(NonEmptyChain.one(WIOGraphError.CycleDetected((nodeKey :: stack).reverse)))
      else if visited.contains(nodeKey) then
        Right(visited)
      else
        val newStack: List[NodeKey] = nodeKey :: stack
        val outgoingEdges: List[WIOGraph.Edge[Ctx, ?, ? <: Out, ? <: Out]] =
          edges.filter(edge => edge.from.key == nodeKey)
        outgoingEdges.foldLeft[Either[NonEmptyChain[WIOGraphError], Set[NodeKey]]](Right(visited + nodeKey)) {
          case (Right(current), edge) => dfs(edge.to.key, current, newStack)
          case (error, _) => error
        }

    allNodes.foldLeft[Either[NonEmptyChain[WIOGraphError], Set[NodeKey]]](Right(Set.empty)) {
      case (Right(visited), nodeKey) => dfs(nodeKey, visited, List.empty)
      case (error, _) => error
    }.map(_ => ())

  private def validateEntrySet: Either[NonEmptyChain[WIOGraphError], Unit] =
    entryNode match
      case None => Left(NonEmptyChain.one(WIOGraphError.MissingEntry))
      case Some(_) => Right(())

  private def validateEndsReachable: Either[NonEmptyChain[WIOGraphError], Unit] =
    entryNode match
      case None => Right(())
      case Some(entry) =>
        val reachable = findAllReachable(entry.key)
        val unreachable = endNodes.filterNot(reachable.contains)
        if unreachable.nonEmpty then
          Left(NonEmptyChain.one(WIOGraphError.UnreachableEnd(unreachable)))
        else
          Right(())

  private def findAllReachable(startNode: NodeKey): Set[NodeKey] =
    def loop(frontier: List[NodeKey], visited: Set[NodeKey]): Set[NodeKey] =
      frontier match
        case Nil => visited
        case current :: rest =>
          if visited.contains(current) then
            loop(rest, visited)
          else
            val outgoing = edges.filter(_.from.key == current).map(_.to.key)
            loop(outgoing ::: rest, visited + current)

    loop(List(startNode), Set.empty)

  private def compileFromEntry[A <: Out](ref: WIONodeRef[Ctx, In, A])(using errorMeta: ErrorMeta[Err]): WIO[In, Err, Out, Ctx] =
    compileFromNode(ref)

  private def compileFromNode[A, B <: Out](ref: WIONodeRef[Ctx, A, B])(using errorMeta: ErrorMeta[Err]): WIO[A, Err, Out, Ctx] =
    val entry: WIOGraph.NodeEntry[Ctx, Err, A, B] = entryFor(ref)
    val node: WIONode[Ctx, A, Err, B] = entry.node
    val modifiers: List[WIONodeModifier[Ctx, A, Err, B]] = entry.modifiers
    val outgoingEdges: List[WIOGraph.Edge[Ctx, A, B, ? <: Out]] =
      edges.collect { case edge @ WIOGraph.Edge(`ref`, _) => edge }

    node match
      case forkNode: WIOForkNode[Ctx, A, Err, B] =>
        compileFork(forkNode, outgoingEdges, modifiers)
      case _ =>
        val baseWIO: WIO[A, Err, B, Ctx] = applyModifiers(node.toWIO, modifiers)
        outgoingEdges match
          case Nil =>
            baseWIO
          case edge :: Nil =>
            val next: WIO[B, Err, Out, Ctx] = compileFromNode(edge.to)
            WIO.AndThen[Ctx, A, Err, B, Out](baseWIO, next)
          case _ =>
            throw new IllegalStateException(
              s"Node '${ref.key}' has multiple outgoing edges, which is not supported for linear compilation"
            )

  private def applyModifiers[A, B <: WCState[Ctx]](
    base: WIO[A, Err, B, Ctx],
    modifiers: List[WIONodeModifier[Ctx, A, Err, B]]
  )(using errorMeta: ErrorMeta[Err]): WIO[A, Err, B, Ctx] =
    modifiers.foldLeft(base) { (wio: WIO[A, Err, B, Ctx], modifier: WIONodeModifier[Ctx, A, Err, B]) =>
      modifier.apply(wio)
    }

  private def compileFork[A, B <: Out](
    forkNode: WIOForkNode[Ctx, A, Err, B],
    outgoingEdges: List[WIOGraph.Edge[Ctx, A, B, ? <: Out]],
    modifiers: List[WIONodeModifier[Ctx, A, Err, B]]
  )(using errorMeta: ErrorMeta[Err]): WIO[A, Err, Out, Ctx] =
    val branches: List[WIOForkNode.Branch[Ctx, A, Err, B]] = forkNode.branches
    if outgoingEdges.length > branches.length then
      throw new IllegalStateException(
        s"Fork node has ${outgoingEdges.length} edges but only ${branches.length} branches"
      )
    val edgeCount: Int = outgoingEdges.length
    val branchesWithEdges: List[(WIOForkNode.Branch[Ctx, A, Err, B], WIOGraph.Edge[Ctx, A, B, ? <: Out])] =
      branches.take(edgeCount).zip(outgoingEdges)
    val branchesWithoutEdges: List[WIOForkNode.Branch[Ctx, A, Err, B]] =
      branches.drop(edgeCount)

    val compiledWithEdges: List[WIOForkNode.Branch[Ctx, A, Err, Out]] =
      branchesWithEdges.map { case (branch, edge) =>
        val baseBranch: WIO[A, Err, B, Ctx] = applyModifiers(branch.workflow, modifiers)
        val next: WIO[B, Err, Out, Ctx] = compileFromNode(edge.to)
        val chained: WIO[A, Err, Out, Ctx] = WIO.AndThen[Ctx, A, Err, B, Out](baseBranch, next)
        WIOForkNode.Branch[Ctx, A, Err, Out](branch.predicate, chained, branch.branchName)
      }

    val compiledWithoutEdges: List[WIOForkNode.Branch[Ctx, A, Err, Out]] =
      branchesWithoutEdges.map { (branch: WIOForkNode.Branch[Ctx, A, Err, B]) =>
        val baseBranch: WIO[A, Err, B, Ctx] = applyModifiers(branch.workflow, modifiers)
        WIOForkNode.Branch[Ctx, A, Err, Out](branch.predicate, baseBranch, branch.branchName)
      }

    val allBranches: List[WIOForkNode.Branch[Ctx, A, Err, Out]] = compiledWithEdges ++ compiledWithoutEdges
    WIOForkNode[Ctx, A, Err, Out](allBranches, forkNode.name).toWIO

  private def entryFor[A, B <: Out](ref: WIONodeRef[Ctx, A, B]): WIOGraph.NodeEntry[Ctx, Err, A, B] =
    nodes.collectFirst { case entry @ WIOGraph.NodeEntry(`ref`, _, _) => entry } match
      case Some(entry) => entry
      case None =>
        throw new IllegalStateException(s"Node '${ref.key}' not found in graph")

object WIOGraph:
  final case class NodeEntry[Ctx <: WorkflowContext, Err, I, O <: WCState[Ctx]](
    ref: WIONodeRef[Ctx, I, O],
    node: WIONode[Ctx, I, Err, O],
    modifiers: List[WIONodeModifier[Ctx, I, Err, O]] = List.empty
  )

  final case class Edge[Ctx <: WorkflowContext, A, B, C](
    from: WIONodeRef[Ctx, A, B],
    to: WIONodeRef[Ctx, B, C]
  )

  def apply[Ctx <: WorkflowContext, In, Out <: WCState[Ctx]]: WIOGraph[Ctx, In, Nothing, Out] =
    WIOGraph[Ctx, In, Nothing, Out](
      nodes = List.empty,
      edges = List.empty,
      entryNode = None,
      endNodes = Set.empty
    )

  def withError[Ctx <: WorkflowContext, In, Err, Out <: WCState[Ctx]]: WIOGraph[Ctx, In, Err, Out] =
    WIOGraph[Ctx, In, Err, Out](
      nodes = List.empty,
      edges = List.empty,
      entryNode = None,
      endNodes = Set.empty
    )
