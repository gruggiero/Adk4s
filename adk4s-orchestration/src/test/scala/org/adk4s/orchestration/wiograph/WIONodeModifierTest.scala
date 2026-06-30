package org.adk4s.orchestration.wiograph

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite
import workflows4s.runtime.WorkflowInstanceId
import workflows4s.wio.{ActiveWorkflow, ErrorMeta, SignalDef, SignalRouter, WCEffect, WCEffectLift, WCEvent, WCState, WIO, WIOContext}
import workflows4s.wio.internal.{WakeupResult, WorkflowEmbedding}

import java.time.Instant
import scala.reflect.ClassTag

import TestContext.{Ctx, ForEachWrappedEvent, TestEvent, TestState, TestStateBase, ValueAdded}

class WIONodeModifierTest extends FunSuite:

  private given ErrorMeta[Nothing] = ErrorMeta.noError

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

  test("CheckpointModifier wraps a pure node with checkpoint behavior") {
    val pureNode: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i * 2))

    val modifier: CheckpointModifier[TestContext.Ctx, Int, Nothing, TestState, ValueAdded] =
      CheckpointModifier[TestContext.Ctx, Int, Nothing, TestState, ValueAdded](
        genEvent = (_: Int, out: TestState) => ValueAdded(out.value),
        handleEvent = (_: Int, evt: ValueAdded) => TestState(evt.delta)
      )

    val baseWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = pureNode.toWIO
    val modifiedWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = modifier.apply(baseWIO)

    modifiedWIO match
      case _: WIO.Checkpoint[TestContext.Ctx, Int, Nothing, TestState, ValueAdded] =>
        assert(true, "WIO is wrapped in Checkpoint")
      case other =>
        fail(s"Expected Checkpoint, got ${other.getClass.getSimpleName}")
  }

  test("RetryModifier wraps a RunIO node with retry behavior") {
    val runIONode: WIORunIONode[TestContext.Ctx, Int, Nothing, ValueAdded, TestState] =
      WIONode.runIO[TestContext.Ctx, Int, ValueAdded, TestState](
        runIO = (i: Int) => IO.pure(ValueAdded(i)),
        handleEvent = (i: Int, evt: ValueAdded) => TestState(i + evt.delta)
      )

    val modifier: RetryModifier[TestContext.Ctx, Int, Nothing, TestState] =
      RetryModifier[TestContext.Ctx, Int, Nothing, TestState](
        onError = (_: Throwable, _: WCState[TestContext.Ctx], _: Instant) => IO.pure(None)
      )

    val baseWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = runIONode.toWIO
    val modifiedWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = modifier.apply(baseWIO)

    modifiedWIO match
      case _: WIO.Retry[TestContext.Ctx, Int, Nothing, TestState] =>
        assert(true, "WIO is wrapped in Retry")
      case other =>
        fail(s"Expected Retry, got ${other.getClass.getSimpleName}")
  }

  test("Multiple modifiers compose correctly (checkpoint + retry on same node)") {
    val pureNode: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val checkpointMod: CheckpointModifier[TestContext.Ctx, Int, Nothing, TestState, ValueAdded] =
      CheckpointModifier[TestContext.Ctx, Int, Nothing, TestState, ValueAdded](
        genEvent = (_: Int, out: TestState) => ValueAdded(out.value),
        handleEvent = (_: Int, evt: ValueAdded) => TestState(evt.delta)
      )

    val retryMod: RetryModifier[TestContext.Ctx, Int, Nothing, TestState] =
      RetryModifier[TestContext.Ctx, Int, Nothing, TestState](
        onError = (_: Throwable, _: WCState[TestContext.Ctx], _: Instant) => IO.pure(None)
      )

    val baseWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = pureNode.toWIO
    val afterCheckpoint: WIO[Int, Nothing, TestState, TestContext.Ctx] = checkpointMod.apply(baseWIO)
    val afterRetry: WIO[Int, Nothing, TestState, TestContext.Ctx] = retryMod.apply(afterCheckpoint)

    afterRetry match
      case _: WIO.Retry[TestContext.Ctx, Int, Nothing, TestState] =>
        assert(true, "Outer wrapper is Retry")
      case other =>
        fail(s"Expected Retry as outer wrapper, got ${other.getClass.getSimpleName}")
  }

  test("Graph with checkpoint modifier on a node compiles and executes") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val node1Ref: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("node1"))
    val node2Ref: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("node2"))

    val node1: WIORunIONode[TestContext.Ctx, Int, Nothing, ValueAdded, TestState] =
      WIONode.runIO[TestContext.Ctx, Int, ValueAdded, TestState](
        runIO = (i: Int) => IO.pure(ValueAdded(i * 3)),
        handleEvent = (i: Int, evt: ValueAdded) => TestState(evt.delta)
      )

    val node2: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => s)

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("node1", node1)
      g2 <- g1.addNode("node2", node2)
      g3 <- g2.addEdge(node1Ref, node2Ref)
      g4 <- g3.setEntry(node1Ref)
      g5 <- g4.addEndNode(node2Ref)
      g6 <- g5.withCheckpoint[Int, TestState, ValueAdded](
        node1Ref,
        genEvent = (_: Int, out: TestState) => ValueAdded(out.value),
        handleEvent = (_: Int, evt: ValueAdded) => TestState(evt.delta)
      )
    yield g6

    val graphWithModifier: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithModifier.toWIO
    assert(wioResult.isRight, s"expected Right WIO, got $wioResult")

    val wio: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))
    val result: TestStateBase = executeWio[Int, TestState](wio, 5, TestState(0))

    assertEquals(result, TestState(15))
  }

  test("Fork with otherwise branch compiles and executes") {
    val highBranch: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 1000)).toWIO
    val otherwiseBranch: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 1)).toWIO

    val forkNode: WIOForkNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIOForkNode.withOtherwise[TestContext.Ctx, Int, Nothing, TestState](
        branches = List(
          WIOForkNode.Branch[TestContext.Ctx, Int, Nothing, TestState](
            predicate = (i: Int) => if i > 100 then Some(i) else None,
            workflow = highBranch,
            branchName = Some("high")
          )
        ),
        otherwise = otherwiseBranch
      )

    val wio: WIO[Int, Nothing, TestState, TestContext.Ctx] = forkNode.toWIO

    val resultHigh: TestStateBase = executeWio[Int, TestState](wio, 200, TestState(0))
    assertEquals(resultHigh, TestState(1200))

    val resultOtherwise: TestStateBase = executeWio[Int, TestState](wio, 50, TestState(0))
    assertEquals(resultOtherwise, TestState(51))
  }

  test("Fork with otherwise and edges compiles correctly") {
    val graph: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      WIOGraph[TestContext.Ctx, Int, TestState]

    val forkRef: WIONodeRef[TestContext.Ctx, Int, TestState] =
      WIONodeRef[TestContext.Ctx, Int, TestState](NodeKey.unsafeApply("fork"))
    val afterHighRef: WIONodeRef[TestContext.Ctx, TestState, TestState] =
      WIONodeRef[TestContext.Ctx, TestState, TestState](NodeKey.unsafeApply("afterHigh"))

    val highBranch: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 1000)).toWIO
    val otherwiseBranch: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i + 1)).toWIO

    val forkNode: WIOForkNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIOForkNode.withOtherwise[TestContext.Ctx, Int, Nothing, TestState](
        branches = List(
          WIOForkNode.Branch[TestContext.Ctx, Int, Nothing, TestState](
            predicate = (i: Int) => if i > 100 then Some(i) else None,
            workflow = highBranch,
            branchName = Some("high")
          )
        ),
        otherwise = otherwiseBranch
      )

    val afterHighNode: WIOPureNode[TestContext.Ctx, TestState, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, TestState, TestState]((s: TestState) => TestState(s.value + 7))

    val graphResult: Either[WIOGraphError, WIOGraph[TestContext.Ctx, Int, Nothing, TestState]] = for
      g1 <- graph.addNode("fork", forkNode)
      g2 <- g1.addNode("afterHigh", afterHighNode)
      g3 <- g2.addEdge(forkRef, afterHighRef)
      g4 <- g3.setEntry(forkRef)
      g5 <- g4.addEndNode(afterHighRef)
    yield g5

    val graphWithFork: WIOGraph[TestContext.Ctx, Int, Nothing, TestState] =
      graphResult.getOrElse(fail("Should build graph"))

    val wioResult: Either[NonEmptyChain[WIOGraphError], WIO[Int, Nothing, TestState, TestContext.Ctx]] =
      graphWithFork.toWIO
    assert(wioResult.isRight, s"expected Right WIO, got $wioResult")

    val wio: WIO[Int, Nothing, TestState, TestContext.Ctx] =
      wioResult.getOrElse(fail("Should have WIO"))

    val resultHigh: TestStateBase = executeWio[Int, TestState](wio, 200, TestState(0))
    assertEquals(resultHigh, TestState(1207))

    val resultOtherwise: TestStateBase = executeWio[Int, TestState](wio, 50, TestState(0))
    assertEquals(resultOtherwise, TestState(51))
  }

  test("InterruptionModifier wraps a node with HandleInterruption") {
    val pureNode: WIOPureNode[TestContext.Ctx, Int, Nothing, TestState] =
      WIONode.pure[TestContext.Ctx, Int, TestState]((i: Int) => TestState(i))

    val interruptionHandler: WIO[TestStateBase, Nothing, TestState, TestContext.Ctx] =
      WIO.Pure[TestContext.Ctx, TestStateBase, Nothing, TestState](
        (_: WIOContext[WCState[TestContext.Ctx]]) => (in: TestStateBase) => Right(TestState(999)),
        WIO.Pure.Meta(ErrorMeta.noError, None)
      )

    val interruption: WIO.Interruption[TestContext.Ctx, Nothing, TestState] =
      WIO.Interruption[TestContext.Ctx, Nothing, TestState](
        interruptionHandler,
        WIO.HandleInterruption.InterruptionType.Signal
      )

    val modifier: InterruptionModifier[TestContext.Ctx, Int, Nothing, TestState] =
      InterruptionModifier[TestContext.Ctx, Int, Nothing, TestState](interruption)

    val baseWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = pureNode.toWIO
    val modifiedWIO: WIO[Int, Nothing, TestState, TestContext.Ctx] = modifier.apply(baseWIO)

    modifiedWIO match
      case _: WIO.HandleInterruption[TestContext.Ctx, Int, Nothing, TestState] =>
        assert(true, "WIO is wrapped in HandleInterruption")
      case other =>
        fail(s"Expected HandleInterruption, got ${other.getClass.getSimpleName}")
  }

  test("WIOForEachNode compiles to WIO.ForEach") {
    val eventEmbedding: WorkflowEmbedding.Event[(String, TestEvent), TestEvent] =
      new WorkflowEmbedding.Event[(String, TestEvent), TestEvent]:
        override def convertEvent(e: (String, TestEvent)): TestEvent =
          ForEachWrappedEvent(e._1, e._2)
        override def unconvertEvent(e: TestEvent): Option[(String, TestEvent)] =
          e match
            case ForEachWrappedEvent(elem, inner) => Some((elem, inner))
            case _ => None

    val signalRouter: SignalRouter.Receiver[String, TestState] =
      new SignalRouter.Receiver[String, TestState]:
        override def outerSignalDef[InnerReq, Resp](innerDef: SignalDef[InnerReq, Resp]): SignalDef[InnerReq, Resp] =
          innerDef
        override def unwrap[OuterReq, InnerReq, Resp](
          signalDef: SignalDef[OuterReq, Resp],
          outerReq: OuterReq,
          input: TestState
        ): Option[SignalRouter.Unwrapped[String, InnerReq, Resp]] =
          None

    val elemWorkflow: WIO[String, Nothing, TestState, TestContext.Ctx] =
      WIONode.pure[TestContext.Ctx, String, TestState]((s: String) => TestState(s.length)).toWIO

    val forEachNode: WIOForEachNode[TestContext.Ctx, Int, Nothing, TestState, String, TestContext.Ctx, TestState, TestState] =
      WIONode.forEach[TestContext.Ctx, Int, Nothing, TestState, String, TestContext.Ctx, TestState, TestState](
        getElements = (i: Int) => Set("a", "bb", "ccc"),
        elemWorkflow = elemWorkflow,
        initialElemState = () => TestState(0),
        eventEmbedding = eventEmbedding,
        interimStateBuilder = (_: Int, _: Map[String, WCState[TestContext.Ctx]]) => TestState(0),
        buildOutput = (_: Int, results: Map[String, TestState]) => TestState(results.values.map(_.value).sum),
        signalRouter = signalRouter,
        name = Some("test-forEach")
      )

    val wio: WIO[Int, Nothing, TestState, TestContext.Ctx] = forEachNode.toWIO

    wio match
      case _: WIO.ForEach[TestContext.Ctx, Int, Nothing, TestState, String, TestContext.Ctx, TestState, TestState] =>
        assert(true, "WIO is ForEach")
      case other =>
        fail(s"Expected ForEach, got ${other.getClass.getSimpleName}")
  }
