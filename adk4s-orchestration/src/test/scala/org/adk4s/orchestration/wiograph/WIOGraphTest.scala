package org.adk4s.orchestration.wiograph

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.{ActiveWorkflow, SignalDef, WCEffect, WCEffectLift, WCEvent, WCState, WIO}
import workflows4s.wio.internal.{SignalResult, WakeupResult}

import java.time.Instant
import scala.reflect.ClassTag

import TestContext.{Ctx, TestEvent, TestState, TestStateBase, ValueAdded, SignalReceived}

sealed trait MySignal
final case class IncrementSignal(amount: Int) extends MySignal

val IncrementSignalDef = SignalDef[MySignal, Unit](id = "increment-signal", name = "IncrementSignal")

class WIOGraphTest extends FunSuite:

  private val workflowInstanceId: WorkflowInstanceId = WorkflowInstanceId("test", "test")

  private def executeWio[In, Out <: WCState[TestContext.Ctx]](
    wio: WIO[In, Nothing, Out, TestContext.Ctx],
    input: In,
    initialState: TestStateBase
  ): TestStateBase =
    val liftEffect: WCEffectLift[TestContext.Ctx, IO] = [A] => (fa: WCEffect[TestContext.Ctx][A]) => fa.asInstanceOf[IO[A]]
    val workflow: ActiveWorkflow[TestContext.Ctx] =
      ActiveWorkflow[TestContext.Ctx](workflowInstanceId, wio.provideInput(input), initialState)
    val wakeup: WakeupResult[IO, WCEvent[TestContext.Ctx]] =
      workflow.proceed(Instant.EPOCH, liftEffect)
    val eventOpt: Option[WCEvent[TestContext.Ctx]] =
      wakeup match
        case WakeupResult.Noop() => None
        case WakeupResult.Processed(io) =>
          io.asInstanceOf[IO[WakeupResult.ProcessingResult[WCEvent[TestContext.Ctx]]]].unsafeRunSync() match
            case WakeupResult.ProcessingResult.Proceeded(event) => Some(event)
            case WakeupResult.ProcessingResult.Failed(_, Some(event)) => Some(event)
            case WakeupResult.ProcessingResult.Failed(_, None) => None
    val finalWorkflow: ActiveWorkflow[TestContext.Ctx] =
      eventOpt match
        case Some(event) =>
          workflow.handleEvent(event) match
            case Some(updated) => updated
            case None => throw new AssertionError("Expected workflow to handle event")
        case None => workflow
    finalWorkflow.liveState

  test("6.2 Test WIOPureNode creation and WIO compilation") {
    val pureNode: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] = pureNode.toWIO
    val result: TestStateBase = executeWio[Int, TestState](wio, 42, TestState(0))

    assertEquals(result, TestState(42))
  }

  test("6.3 Test WIORunIONode with event type enforcement") {
    val runIONode: WIORunIONode[TestContext.Ctx, Int, Nothing, ValueAdded, TestState] =
      WIORunIONode[TestContext.Ctx, Int, Nothing, ValueAdded, TestState](
        runIO = (i: Int) => cats.effect.IO.pure(ValueAdded(i)),
        handleEvent = (i: Int, evt: ValueAdded) => Right(TestState(i + evt.delta))
      )

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] = runIONode.toWIO
    val result: TestStateBase = executeWio[Int, TestState](wio, 10, TestState(0))

    assertEquals(result, TestState(20))
  }

  test("6.4 Test WIOForkNode branching logic") {
    val trueBranch: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 100))
    val falseBranch: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 200))

    val forkNode: WIOForkNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIOForkNode.binaryFork[TestContext.Ctx, Int, Nothing, TestState](
        condition = (i: Int) => i > 50,
        ifTrue = trueBranch.toWIO,
        ifFalse = falseBranch.toWIO
      )

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] = forkNode.toWIO

    val result1: TestStateBase = executeWio[Int, TestState](wio, 60, TestState(0))
    assertEquals(result1, TestState(160))

    val result2: TestStateBase = executeWio[Int, TestState](wio, 40, TestState(0))
    assertEquals(result2, TestState(240))
  }

  test("6.5 Test WIOGraph builder with type-safe edge validation") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val node1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))
    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 10))

    val graphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graph
        .addNode("node1", node1)
        .addNode("node2", node2)

    val graphWithEdge: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNodes.addEdge(node1Ref, node2Ref)
    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEdge.setEntry(node1Ref)
    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEntry.addEndNode(node2Ref)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(throw new AssertionError("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 5, TestState(0))

    assertEquals(result, TestState(15))
  }

  test("6.6 Test eager validation (duplicate keys)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] = graph.addNode("node1", node)

    intercept[IllegalArgumentException] {
      graphWithNode.addNode("node1", node)
    }
  }

  test("6.6 Test eager validation (missing edge targets)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val missingRef: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("missing"))

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] = graph.addNode("node1", node)

    intercept[IllegalArgumentException] {
      graphWithNode.addEdge(node1Ref, missingRef)
    }
  }

  test("6.7 Test lazy validation (cycles)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val node1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))
    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 10))

    val graphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graph
        .addNode("node1", node1)
        .addNode("node2", node2)

    val graphWithEdges: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNodes
        .addEdge(node1Ref, node2Ref)
        .addEdge(node2Ref, node2Ref)

    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEdges.setEntry(node1Ref)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEntry.toWIO
    assert(wioResult.isLeft, "expected Left errors")

    wioResult.left.toOption match
      case Some(errors: NonEmptyChain[WIOGraphError]) =>
        assert(errors.exists {
          case WIOGraphError.CycleDetected(_) => true
          case _ => false
        }, "expected cycle error")
      case None =>
        throw new AssertionError("Should have cycle error")
  }

  test("6.7 Test lazy validation (missing entry node)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val nodeRef: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] = graph.addNode("node1", node)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithNode.toWIO
    assert(wioResult.isLeft, "expected Left errors")

    wioResult.left.toOption match
      case Some(errors: NonEmptyChain[WIOGraphError]) =>
        assert(errors.exists {
          case WIOGraphError.MissingEntry => true
          case _ => false
        }, "expected missing entry error")
      case None =>
        throw new AssertionError("Should have missing entry error")
  }

  test("6.7 Test lazy validation (unreachable end nodes)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))
    val node3Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node3"))

    val node1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))
    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 10))
    val node3: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 20))

    val graphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graph
        .addNode("node1", node1)
        .addNode("node2", node2)
        .addNode("node3", node3)

    val graphWithEdges: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNodes.addEdge(node1Ref, node2Ref)

    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEdges.setEntry(node1Ref)
    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEntry.addEndNode(node3Ref)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnd.toWIO
    assert(wioResult.isLeft, "expected Left errors")

    wioResult.left.toOption match
      case Some(errors: NonEmptyChain[WIOGraphError]) =>
        assert(errors.exists {
          case WIOGraphError.UnreachableEnd(_) => true
          case _ => false
        }, "expected unreachable end error")
      case None =>
        throw new AssertionError("Should have unreachable end error")
  }

  test("6.8 Test linear graph compilation (A -> B -> C)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))
    val node3Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node3"))

    val node1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))
    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 10))
    val node3: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value * 2))

    val graphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graph
        .addNode("node1", node1)
        .addNode("node2", node2)
        .addNode("node3", node3)

    val graphWithEdges: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNodes
        .addEdge(node1Ref, node2Ref)
        .addEdge(node2Ref, node3Ref)

    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEdges.setEntry(node1Ref)
    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEntry.addEndNode(node3Ref)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(throw new AssertionError("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 5, TestState(0))

    assertEquals(result, TestState(30))
  }

  test("6.9 Test branching graph compilation with fork") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))
    val node3Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node3"))

    val trueBranch: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 100))
    val falseBranch: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 200))

    val forkNode: WIOForkNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIOForkNode.binaryFork[TestContext.Ctx, Int, Nothing, TestState](
        condition = (i: Int) => i > 50,
        ifTrue = trueBranch.toWIO,
        ifFalse = falseBranch.toWIO
      )

    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 10))
    val node3: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 20))

    val graphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graph
        .addNode("node1", forkNode)
        .addNode("node2", node2)
        .addNode("node3", node3)

    val graphWithEdges: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNodes.addEdge(node1Ref, node2Ref)
      .addEdge(node1Ref, node3Ref)

    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEdges.setEntry(node1Ref)
    val graphWithEnds: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithEntry.addEndNode(node2Ref).addEndNode(node3Ref)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnds.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(throw new AssertionError("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 60, TestState(0))

    assertEquals(result, TestState(170))
  }

  test("6.10 Test nested sub-graph compilation") {
    val subGraph: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, TestState, TestState]

    val subNode1Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("sub_node1"))
    val subNode2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("sub_node2"))

    val subNode1: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)
    val subNode2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 5))

    val subGraphWithNodes: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      subGraph
        .addNode("sub_node1", subNode1)
        .addNode("sub_node2", subNode2)

    val subGraphWithEdges: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      subGraphWithNodes.addEdge(subNode1Ref, subNode2Ref)

    val subGraphWithEntry: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      subGraphWithEdges.setEntry(subNode1Ref)
    val subGraphWithEnd: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      subGraphWithEntry.addEndNode(subNode2Ref)

    val parentGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val parentNode1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("parent_node1"))
    val parentSubRef: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("parent_sub"))
    val parentEndRef: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("parent_end"))

    val parentNode1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i * 2))
    val parentSubNode: WIOSubGraphNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIOSubGraphNode[TestContext.Ctx, TestState, Nothing, TestState](subGraphWithEnd)
    val parentEndNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 100))

    val parentGraphWithNodes: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      parentGraph
        .addNode("parent_node1", parentNode1)
        .addNode("parent_sub", parentSubNode)
        .addNode("parent_end", parentEndNode)

    val parentGraphWithEdges: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      parentGraphWithNodes
        .addEdge(parentNode1Ref, parentSubRef)
        .addEdge(parentSubRef, parentEndRef)

    val parentGraphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      parentGraphWithEdges.setEntry(parentNode1Ref)
    val parentGraphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      parentGraphWithEntry.addEndNode(parentEndRef)

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      parentGraphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(throw new AssertionError("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 10, TestState(0))

    assertEquals(result, TestState(125))
  }

  test("Test WIOHandleSignalNode with signal handling") {
    val handleSignalNode: WIOHandleSignalNode[TestContext.Ctx, TestState, Nothing, TestState, MySignal, Unit, SignalReceived] =
      WIONode.handleSignal[TestContext.Ctx, TestState, Nothing, TestState, MySignal, Unit, SignalReceived](
        signalDef = IncrementSignalDef,
        signalHandler = (state: TestState, req: MySignal) =>
          req match
            case inc: IncrementSignal => cats.effect.IO.pure(SignalReceived(inc.amount)),
        eventHandler = (state: TestState, evt: SignalReceived) => Right(TestState(state.value + evt.amount)),
        responseHandler = (_, _) => ()
      )

    val wio: workflows4s.wio.WIO[TestState, Nothing, TestState, TestContext.Ctx] = handleSignalNode.toWIO

    val initialState: TestState = TestState(10)
    val workflow: ActiveWorkflow[TestContext.Ctx] =
      ActiveWorkflow[TestContext.Ctx](workflowInstanceId, wio.provideInput(initialState), initialState)

    val signal: MySignal = IncrementSignal(5)

    val liftEffect: WCEffectLift[TestContext.Ctx, IO] = [A] => (fa: WCEffect[TestContext.Ctx][A]) => fa.asInstanceOf[IO[A]]
    val signalResult: SignalResult[IO, WCEvent[TestContext.Ctx], Unit] = 
      workflow.handleSignal(IncrementSignalDef, signal, liftEffect)

    assert(signalResult.hasEffect, "signal result should have effect")
    
    signalResult match {
      case SignalResult.Processed(resultIO) =>
        val processingResult: SignalResult.ProcessingResult[WCEvent[TestContext.Ctx], Unit] = resultIO.unsafeRunSync()
        assertEquals(processingResult.event, SignalReceived(5))
      case _ => 
        throw new AssertionError("Expected Processed signal result")
    }
  }

  test("Test WIOHandleSignalNode purely") {
    val handleSignalNode: WIOHandleSignalNode[TestContext.Ctx, TestState, Nothing, TestState, MySignal, Unit, SignalReceived] =
      WIONode.handleSignalPurely[TestContext.Ctx, TestState, Nothing, TestState, MySignal, Unit, SignalReceived](
        signalDef = IncrementSignalDef,
        signalHandler = (state: TestState, req: MySignal) =>
          req match
            case inc: IncrementSignal => SignalReceived(inc.amount + state.value),
        eventHandler = (state: TestState, evt: SignalReceived) => Right(TestState(evt.amount)),
        responseHandler = (_, _) => ()
      )

    val wio: workflows4s.wio.WIO[TestState, Nothing, TestState, TestContext.Ctx] = handleSignalNode.toWIO

    val initialState: TestState = TestState(10)
    val workflow: ActiveWorkflow[TestContext.Ctx] =
      ActiveWorkflow[TestContext.Ctx](workflowInstanceId, wio.provideInput(initialState), initialState)

    val signal: MySignal = IncrementSignal(50)

    val liftEffect2: WCEffectLift[TestContext.Ctx, IO] = [A] => (fa: WCEffect[TestContext.Ctx][A]) => fa.asInstanceOf[IO[A]]
    val signalResult2: SignalResult[IO, WCEvent[TestContext.Ctx], Unit] = 
      workflow.handleSignal(IncrementSignalDef, signal, liftEffect2)

    assert(signalResult2.hasEffect, "signal result should have effect")

    signalResult2 match {
      case SignalResult.Processed(resultIO) =>
        val processingResult: SignalResult.ProcessingResult[WCEvent[TestContext.Ctx], Unit] = resultIO.unsafeRunSync()
        assertEquals(processingResult.event, SignalReceived(60))
      case _ => 
        throw new AssertionError("Expected Processed signal result")
    }
  }
