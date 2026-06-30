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

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  private def ioLiftEffect: WCEffectLift[TestContext.Ctx, IO] =
    [A] => (fa: WCEffect[TestContext.Ctx][A]) => fa.asInstanceOf[IO[A]]

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
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
            case None => fail("Expected workflow to handle event")
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

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 5, TestState(0))

    assertEquals(result, TestState(15))
  }

  test("6.6 Test eager validation (duplicate keys)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graph.addNode("node1", node)

    val duplicateResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graphWithNode.flatMap(_.addNode("node1", node))
    assert(duplicateResult.isLeft, "expected Left for duplicate key")
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

    val graphWithNode: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graph.addNode("node1", node)

    val edgeResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graphWithNode.flatMap(_.addEdge(node1Ref, missingRef))
    assert(edgeResult.isLeft, "expected Left for missing edge target")
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

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.addEdge(node2Ref, node2Ref)
      g5 <- g4.setEntry(node1Ref)
    yield g5

    val graphWithEntry: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

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
        fail("Should have cycle error")
  }

  test("6.7 Test lazy validation (missing entry node)") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val nodeRef: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graph.addNode("node1", node)

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNode.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      builtGraph.toWIO
    assert(wioResult.isLeft, "expected Left errors")

    wioResult.left.toOption match
      case Some(errors: NonEmptyChain[WIOGraphError]) =>
        assert(errors.exists {
          case WIOGraphError.MissingEntry => true
          case _ => false
        }, "expected missing entry error")
      case None =>
        fail("Should have missing entry error")
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

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addNode("node3", node3)
      g4 <- g3.addEdge(node1Ref, node2Ref)
      g5 <- g4.setEntry(node1Ref)
      g6 <- g5.addEndNode(node3Ref)
    yield g6

    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

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
        fail("Should have unreachable end error")
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

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addNode("node3", node3)
      g4 <- g3.addEdge(node1Ref, node2Ref)
      g5 <- g4.addEdge(node2Ref, node3Ref)
      g6 <- g5.setEntry(node1Ref)
      g7 <- g6.addEndNode(node3Ref)
    yield g7

    val graphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))
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

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", forkNode)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addNode("node3", node3)
      g4 <- g3.addEdge(node1Ref, node2Ref)
      g5 <- g4.addEdge(node1Ref, node3Ref)
      g6 <- g5.setEntry(node1Ref)
      g7 <- g6.addEndNode(node2Ref)
      g8 <- g7.addEndNode(node3Ref)
    yield g8

    val graphWithEnds: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithEnds.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))
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

    val subGraphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, TestState, Nothing, TestState]] = for
      sg1 <- subGraph.addNode("sub_node1", subNode1)
      sg2 <- sg1.addNode("sub_node2", subNode2)
      sg3 <- sg2.addEdge(subNode1Ref, subNode2Ref)
      sg4 <- sg3.setEntry(subNode1Ref)
      sg5 <- sg4.addEndNode(subNode2Ref)
    yield sg5

    val subGraphWithEnd: WIOGraph[TestContext.Ctx, TestState, Nothing, TestState] =
      subGraphResult.getOrElse(fail("Should build sub-graph"))

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

    val parentGraphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      pg1 <- parentGraph.addNode("parent_node1", parentNode1)
      pg2 <- pg1.addNode("parent_sub", parentSubNode)
      pg3 <- pg2.addNode("parent_end", parentEndNode)
      pg4 <- pg3.addEdge(parentNode1Ref, parentSubRef)
      pg5 <- pg4.addEdge(parentSubRef, parentEndRef)
      pg6 <- pg5.setEntry(parentNode1Ref)
      pg7 <- pg6.addEndNode(parentEndRef)
    yield pg7

    val parentGraphWithEnd: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      parentGraphResult.getOrElse(fail("Should build parent graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      parentGraphWithEnd.toWIO
    assert(wioResult.isRight, "expected Right WIO")

    val wio: workflows4s.wio.WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))
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

    val liftEffect: WCEffectLift[TestContext.Ctx, IO] = ioLiftEffect
    val signalResult: SignalResult[IO, WCEvent[TestContext.Ctx], Unit] = 
      workflow.handleSignal(IncrementSignalDef, signal, liftEffect)

    assert(signalResult.hasEffect, "signal result should have effect")
    
    signalResult match {
      case SignalResult.Processed(resultIO) =>
        val processingResult: SignalResult.ProcessingResult[WCEvent[TestContext.Ctx], Unit] = resultIO.unsafeRunSync()
        assertEquals(processingResult.event, SignalReceived(5))
      case _ =>
        fail("Expected Processed signal result")
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

    val liftEffect2: WCEffectLift[TestContext.Ctx, IO] = ioLiftEffect
    val signalResult2: SignalResult[IO, WCEvent[TestContext.Ctx], Unit] = 
      workflow.handleSignal(IncrementSignalDef, signal, liftEffect2)

    assert(signalResult2.hasEffect, "signal result should have effect")

    signalResult2 match {
      case SignalResult.Processed(resultIO) =>
        val processingResult: SignalResult.ProcessingResult[WCEvent[TestContext.Ctx], Unit] = resultIO.unsafeRunSync()
        assertEquals(processingResult.event, SignalReceived(60))
      case _ =>
        fail("Expected Processed signal result")
    }
  }
