package org.adk4s.orchestration.wiograph

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import munit.FunSuite
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.{ActiveWorkflow, ErrorMeta, WCEffect, WCEffectLift, WCEvent, WCState, WIO}
import workflows4s.wio.internal.WakeupResult

import scala.reflect.ClassTag

import TestContext.{Ctx, TestEvent, TestState, TestStateBase, RunnableResult, StringResult, StringState}

class WIORunnableNodeTest extends FunSuite:

  private val workflowInstanceId: WorkflowInstanceId = WorkflowInstanceId("test", "test")

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
      workflow.proceed(java.time.Instant.EPOCH, liftEffect)
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

  // --- WIORunnableNode tests ---

  test("WIORunnableNode: toWIO compiles and executes correctly") {
    val runnable: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i * 3))

    val node: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )

    val wio: WIO[Int, Nothing, TestState, TestContext.Ctx] = node.toWIO
    val result: TestStateBase = executeWio[Int, TestState](wio, 7, TestState(0))

    assertEquals(result, TestState(21))
  }

  test("WIORunnableNode: toRunnable invoke mode") {
    val runnable: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i + 10))

    val node: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )

    val result: TestState = node.toRunnable.invoke(5).unsafeRunSync()
    assertEquals(result, TestState(15))
  }

  test("WIORunnableNode: toRunnable stream mode") {
    val runnable: Runnable[Int, Int] = Runnable.fromStream[Int, Int]((i: Int) =>
      Stream.emits(List(i, i + 1, i + 2))
    )

    val node: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )

    val results: List[TestState] = node.toRunnable.stream(10).compile.toList.unsafeRunSync()
    assertEquals(results, List(TestState(10), TestState(11), TestState(12)))
  }

  test("WIORunnableNode: fromLambdaSimple factory") {
    val lambda: Lambda[Int, Int] = Lambda.pure[Int, Int]((i: Int) => i * 2)

    val node: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromLambdaSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        lambda = lambda,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )

    val result: TestState = node.toRunnable.invoke(8).unsafeRunSync()
    assertEquals(result, TestState(16))
  }

  test("WIORunnableNode: error handling via toRunnable") {
    val runnable: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i))

    val node: WIORunnableNode[TestContext.Ctx, Int, String, RunnableResult, Int, TestState] =
      WIORunnableNode[TestContext.Ctx, Int, String, RunnableResult, Int, TestState](
        runnable = runnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        handleEvent = (_: Int, evt: RunnableResult) =>
          if evt.value > 0 then Right(TestState(evt.value))
          else Left("Value must be positive")
      )

    // Positive value succeeds
    val successResult: TestState = node.toRunnable.invoke(5).unsafeRunSync()
    assertEquals(successResult, TestState(5))

    // Zero value fails
    val failResult: Either[Throwable, TestState] = node.toRunnable.invoke(0).attempt.unsafeRunSync()
    assert(failResult.isLeft)
  }

  // --- WIOGraph.toRunnable tests ---

  test("WIOGraph.toRunnable: linear graph with pure nodes") {
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

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnableResult: Either[NonEmptyChain[WIOGraphError], Runnable[Int, TestState]] =
      builtGraph.toRunnable
    assert(runnableResult.isRight, "expected Right Runnable")

    val runnable: Runnable[Int, TestState] =
      runnableResult.getOrElse(fail("Should have Runnable"))
    val result: TestState = runnable.invoke(5).unsafeRunSync()

    assertEquals(result, TestState(15))
  }

  test("WIOGraph.toRunnable: graph with WIORunnableNode invoke") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val runnableImpl: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i * 2))

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val runnableNode: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnableImpl,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )
    val pureNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 100))

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", runnableNode)
      g2 <- g1.addNode("node2", pureNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      builtGraph.toRunnable.getOrElse(fail("Should compile"))
    val result: TestState = runnable.invoke(7).unsafeRunSync()

    assertEquals(result, TestState(114)) // 7*2=14, 14+100=114
  }

  test("WIOGraph.toRunnable: graph with WIORunnableNode stream mode") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val streamRunnable: Runnable[Int, Int] = Runnable.fromStream[Int, Int]((i: Int) =>
      Stream.emits(List(i, i * 2, i * 3))
    )

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val runnableNode: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = streamRunnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )
    val identityNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", runnableNode)
      g2 <- g1.addNode("node2", identityNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      builtGraph.toRunnable.getOrElse(fail("Should compile"))

    // Stream mode — chained through identity, so invoke-based chaining gives single result
    val result: TestState = runnable.invoke(5).unsafeRunSync()
    assertEquals(result, TestState(15)) // stream last = 5*3=15
  }

  test("WIOGraph.toRunnable: validation errors propagate") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val graphWithNode: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] =
      graph.addNode("node1", node)

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphWithNode.getOrElse(fail("Should build graph"))

    // No entry set — should fail validation
    val result: Either[NonEmptyChain[WIOGraphError], Runnable[Int, TestState]] =
      builtGraph.toRunnable
    assert(result.isLeft, "expected Left errors")
  }

  // --- WIOGraphStreamExecutor tests ---

  test("WIOGraphStreamExecutor.invoke: executes graph") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val node1: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i * 5))
    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val result: TestState =
      WIOGraphStreamExecutor.invoke(builtGraph, 4).unsafeRunSync()
    assertEquals(result, TestState(20))
  }

  test("WIOGraphStreamExecutor.stream: streams from graph") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val streamRunnable: Runnable[Int, Int] = Runnable.fromStream[Int, Int]((i: Int) =>
      Stream.emits(List(i, i + 1))
    )

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val runnableNode: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = streamRunnable,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )
    val identityNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", runnableNode)
      g2 <- g1.addNode("node2", identityNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    // Stream mode chains through identity via stream->stream
    val results: List[TestState] =
      WIOGraphStreamExecutor.stream(builtGraph, 10).compile.toList.unsafeRunSync()
    assertEquals(results, List(TestState(10), TestState(11)))
  }

  test("WIOGraphStreamExecutor.transform: transforms stream through graph") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val runnableImpl: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i * 10))

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val runnableNode: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnableImpl,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )
    val identityNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", runnableNode)
      g2 <- g1.addNode("node2", identityNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val inputStream: Stream[IO, Int] = Stream.emits(List(1, 2, 3))
    val results: List[TestState] =
      WIOGraphStreamExecutor.transform(builtGraph, inputStream).compile.toList.unsafeRunSync()
    assertEquals(results, List(TestState(10), TestState(20), TestState(30)))
  }

  test("WIOGraph.addRunnableNode convenience method") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val runnableImpl: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i + 5))

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val identityNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addRunnableNode[Int, RunnableResult, Int, TestState](
        "node1",
        runnableImpl,
        (raw: Int) => RunnableResult(raw),
        (_: Int, evt: RunnableResult) => Right(TestState(evt.value))
      )
      g2 <- g1.addNode("node2", identityNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val finalGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      finalGraph.toRunnable.getOrElse(fail("Should compile"))
    val result: TestState = runnable.invoke(3).unsafeRunSync()

    assertEquals(result, TestState(8))
  }

  test("WIOGraph.addLambdaNode convenience method") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val lambda: Lambda[Int, Int] = Lambda.pure[Int, Int]((i: Int) => i * 3)

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val identityNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addLambdaNode[Int, RunnableResult, Int, TestState](
        "node1",
        lambda,
        (raw: Int) => RunnableResult(raw),
        (_: Int, evt: RunnableResult) => Right(TestState(evt.value))
      )
      g2 <- g1.addNode("node2", identityNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val finalGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      finalGraph.toRunnable.getOrElse(fail("Should compile"))
    val result: TestState = runnable.invoke(4).unsafeRunSync()

    assertEquals(result, TestState(12))
  }

  test("WIOGraph.toRunnable: three-node linear chain") {
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

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      builtGraph.toRunnable.getOrElse(fail("Should compile"))
    val result: TestState = runnable.invoke(5).unsafeRunSync()

    // 5 -> TestState(5) -> TestState(15) -> TestState(30)
    assertEquals(result, TestState(30))
  }

  test("WIOGraph.toRunnable: mixed pure and runnable nodes") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val runnableImpl: Runnable[Int, Int] = Runnable.fromInvoke[Int, Int]((i: Int) => IO.pure(i * 2))

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("runnable"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("pure"))

    val runnableNode: WIORunnableNode[TestContext.Ctx, Int, Nothing, RunnableResult, Int, TestState] =
      WIONode.fromRunnableSimple[TestContext.Ctx, Int, RunnableResult, Int, TestState](
        runnable = runnableImpl,
        toEvent = (raw: Int) => RunnableResult(raw),
        toState = (_: Int, evt: RunnableResult) => TestState(evt.value)
      )
    val pureNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 1))

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("runnable", runnableNode)
      g2 <- g1.addNode("pure", pureNode)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
    yield g5

    val builtGraph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val runnable: Runnable[Int, TestState] =
      builtGraph.toRunnable.getOrElse(fail("Should compile"))
    val result: TestState = runnable.invoke(10).unsafeRunSync()

    // 10*2=20, 20+1=21
    assertEquals(result, TestState(21))
  }
