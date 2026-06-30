package org.adk4s.orchestration.wiograph

import cats.effect.IO
import fs2.Stream
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.runnable.Runnable
import workflows4s.wio.{ErrorMeta, SignalDef, SignalRouter, WCEffect, WCEvent, WCState, WIO, WIOContext, WorkflowContext}
import workflows4s.wio.internal.{EventHandler, SignalHandler, WorkflowEmbedding}
import workflows4s.wio.model.WIOMeta
import workflows4s.wio.builders.AllBuilders

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

sealed trait WIONode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx]

object WIONode:
  given [Ctx <: WorkflowContext] => AllBuilders[Ctx] = WIO.build[Ctx]
  
  given nothingErrorMeta: ErrorMeta[Nothing] = ErrorMeta.noError

  def pure[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](f: I => O)(using errorMeta: ErrorMeta[Nothing]): WIOPureNode[Ctx, I, Nothing, O] =
    WIOPureNode((i: I) => Right(f(i)))

  def pureEither[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](f: I => Either[Err, O])(using errorMeta: ErrorMeta[Err]): WIOPureNode[Ctx, I, Err, O] =
    WIOPureNode(f)

  def error[Ctx <: WorkflowContext, I, Err](value: Err)(using errorMeta: ErrorMeta[Err]): WIOPureNode[Ctx, I, Err, Nothing] =
    WIOPureNode((_: I) => Left(value))

  def runIO[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
    runIO: I => IO[Evt],
    handleEvent: (I, Evt) => O
  )(using errorMeta: ErrorMeta[Nothing], evtCt: ClassTag[Evt]): WIORunIONode[Ctx, I, Nothing, Evt, O] =
    val handler: (I, Evt) => Either[Nothing, O] = (i: I, evt: Evt) => Right(handleEvent(i, evt))
    WIORunIONode[Ctx, I, Nothing, Evt, O](runIO, handler)

  def runIOWithError[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
    runIO: I => IO[Evt],
    handleEvent: (I, Evt) => Either[Err, O]
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIORunIONode[Ctx, I, Err, Evt, O] =
    WIORunIONode[Ctx, I, Err, Evt, O](runIO, handleEvent)

  def loop[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
    body: WIO[I, Err, O, Ctx],
    stopCondition: O => Boolean,
    restart: WIO[I, Err, I, Ctx]
  )(using errorMeta: ErrorMeta[Err], ev: O <:< I): WIOLoopNode[Ctx, I, Err, O] =
    val stopWhen: O => Either[I, O] = (out: O) => if stopCondition(out) then Right(out) else Left(ev(out))
    WIOLoopNode[Ctx, I, Err, O](body, stopWhen, restart)

  def loopEither[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
    body: WIO[I, Err, O, Ctx],
    stopWhen: O => Either[I, O],
    restart: WIO[I, Err, I, Ctx]
  )(using errorMeta: ErrorMeta[Err]): WIOLoopNode[Ctx, I, Err, O] =
    WIOLoopNode[Ctx, I, Err, O](body, stopWhen, restart)

  def await[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
    duration: FiniteDuration,
    handleEvent: (I, WIO.Timer.Released) => O,
    name: Option[String] = None
  )(using
    errorMeta: ErrorMeta[Nothing],
    releasedEvidence: WIO.Timer.Released <:< WCEvent[Ctx],
    startedEvidence: WIO.Timer.Started <:< WCEvent[Ctx]
  ): WIOAwaitNode[Ctx, I, Nothing, O] =
    val durationSource: WIO.Timer.DurationSource[I] =
      WIO.Timer.DurationSource.Static(java.time.Duration.ofNanos(duration.toNanos))
    val handler: (I, WIO.Timer.Released) => Either[Nothing, O] =
      (in: I, evt: WIO.Timer.Released) => Right(handleEvent(in, evt))
    WIOAwaitNode[Ctx, I, Nothing, O](durationSource, handler, name)

  def awaitDynamic[Ctx <: WorkflowContext, I, O <: WCState[Ctx]](
    getDuration: I => FiniteDuration,
    handleEvent: (I, WIO.Timer.Released) => O,
    name: Option[String] = None
  )(using
    errorMeta: ErrorMeta[Nothing],
    releasedEvidence: WIO.Timer.Released <:< WCEvent[Ctx],
    startedEvidence: WIO.Timer.Started <:< WCEvent[Ctx]
  ): WIOAwaitNode[Ctx, I, Nothing, O] =
    val durationSource: WIO.Timer.DurationSource[I] =
      WIO.Timer.DurationSource.Dynamic((in: I) => java.time.Duration.ofNanos(getDuration(in).toNanos))
    val handler: (I, WIO.Timer.Released) => Either[Nothing, O] =
      (in: I, evt: WIO.Timer.Released) => Right(handleEvent(in, evt))
    WIOAwaitNode[Ctx, I, Nothing, O](durationSource, handler, name)

  def parallel[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
    workflows: List[WIO[I, Err, WCState[Ctx], Ctx]],
    collectResults: List[WCState[Ctx]] => O
  )(using errorMeta: ErrorMeta[Err]): WIOParallelNode[Ctx, I, Err, O, I] =
    val elements: List[WIOParallelNode.Element[Ctx, I, Err, I, WCState[Ctx]]] =
      workflows.map((workflow: WIO[I, Err, WCState[Ctx], Ctx]) =>
        val incorporate: (I, WCState[Ctx]) => I = (interim: I, _: WCState[Ctx]) => interim
        WIOParallelNode.Element[Ctx, I, Err, I, WCState[Ctx]](workflow, incorporate)
      )
    val formResult: Seq[WCState[Ctx]] => O = (results: Seq[WCState[Ctx]]) => collectResults(results.toList)
    val initialInterimState: I => I = (in: I) => in
    WIOParallelNode[Ctx, I, Err, O, I](elements, formResult, initialInterimState)

  def parallelWithState[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], InterimState <: WCState[Ctx]](
    elements: List[WIOParallelNode.Element[Ctx, I, Err, InterimState, ? <: WCState[Ctx]]],
    formResult: Seq[WCState[Ctx]] => O,
    initialInterimState: I => InterimState
  )(using errorMeta: ErrorMeta[Err]): WIOParallelNode[Ctx, I, Err, O, InterimState] =
    WIOParallelNode[Ctx, I, Err, O, InterimState](elements, formResult, initialInterimState)

  def fromRunnable[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], RawOut, O <: WCState[Ctx]](
    runnable: Runnable[I, RawOut],
    toEvent: RawOut => Evt,
    handleEvent: (I, Evt) => Either[Err, O]
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Err, Evt, RawOut, O] =
    WIORunnableNode[Ctx, I, Err, Evt, RawOut, O](runnable, toEvent, handleEvent)

  def fromRunnableSimple[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], RawOut, O <: WCState[Ctx]](
    runnable: Runnable[I, RawOut],
    toEvent: RawOut => Evt,
    toState: (I, Evt) => O
  )(using errorMeta: ErrorMeta[Nothing], evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Nothing, Evt, RawOut, O] =
    val handler: (I, Evt) => Either[Nothing, O] = (i: I, evt: Evt) => Right(toState(i, evt))
    WIORunnableNode[Ctx, I, Nothing, Evt, RawOut, O](runnable, toEvent, handler)

  def fromLambda[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], RawOut, O <: WCState[Ctx]](
    lambda: Lambda[I, RawOut],
    toEvent: RawOut => Evt,
    handleEvent: (I, Evt) => Either[Err, O]
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Err, Evt, RawOut, O] =
    WIORunnableNode[Ctx, I, Err, Evt, RawOut, O](lambda.toRunnable, toEvent, handleEvent)

  def fromLambdaSimple[Ctx <: WorkflowContext, I, Evt <: WCEvent[Ctx], RawOut, O <: WCState[Ctx]](
    lambda: Lambda[I, RawOut],
    toEvent: RawOut => Evt,
    toState: (I, Evt) => O
  )(using errorMeta: ErrorMeta[Nothing], evtCt: ClassTag[Evt]): WIORunnableNode[Ctx, I, Nothing, Evt, RawOut, O] =
    val handler: (I, Evt) => Either[Nothing, O] = (i: I, evt: Evt) => Right(toState(i, evt))
    WIORunnableNode[Ctx, I, Nothing, Evt, RawOut, O](lambda.toRunnable, toEvent, handler)

  def subGraph[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    graph: WIOGraph[Ctx, I, Err, O]
  ): WIOSubGraphNode[Ctx, I, Err, O] =
    WIOSubGraphNode[Ctx, I, Err, O](graph)

  def forEach[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
    getElements: I => Set[Elem],
    elemWorkflow: WIO[Elem, Err, ElemOut, InnerCtx],
    initialElemState: () => WCState[InnerCtx],
    eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]],
    interimStateBuilder: (I, Map[Elem, WCState[InnerCtx]]) => InterimState,
    buildOutput: (I, Map[Elem, ElemOut]) => O,
    signalRouter: SignalRouter.Receiver[Elem, InterimState],
    name: Option[String] = None
  )(using errorMeta: ErrorMeta[Err]): WIOForEachNode[Ctx, I, Err, O, Elem, InnerCtx, ElemOut, InterimState] =
    WIOForEachNode[Ctx, I, Err, O, Elem, InnerCtx, ElemOut, InterimState](
      getElements, elemWorkflow, initialElemState, eventEmbedding, interimStateBuilder, buildOutput, signalRouter, name
    )

  def handleSignal[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Req, Resp, Evt <: WCEvent[Ctx]](
    signalDef: SignalDef[Req, Resp],
    signalHandler: (I, Req) => IO[Evt],
    eventHandler: (I, Evt) => Either[Err, O],
    responseHandler: (I, Evt) => Resp,
    operationName: Option[String] = None
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOHandleSignalNode[Ctx, I, Err, O, Req, Resp, Evt] =
    WIOHandleSignalNode(signalDef, signalHandler, eventHandler, responseHandler, operationName)

  def handleSignalPurely[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Req, Resp, Evt <: WCEvent[Ctx]](
    signalDef: SignalDef[Req, Resp],
    signalHandler: (I, Req) => Evt,
    eventHandler: (I, Evt) => Either[Err, O],
    responseHandler: (I, Evt) => Resp,
    operationName: Option[String] = None
  )(using errorMeta: ErrorMeta[Err], evtCt: ClassTag[Evt]): WIOHandleSignalNode[Ctx, I, Err, O, Req, Resp, Evt] =
    WIOHandleSignalNode(
      signalDef,
      (input: I, req: Req) => IO.pure(signalHandler(input, req)),
      eventHandler,
      responseHandler,
      operationName
    )

final case class WIOPureNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  transform: I => Either[Err, O]
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    WIO.Pure[Ctx, I, Err, O](_ => transform, WIO.Pure.Meta(errorMeta, None))

final case class WIORunIONode[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], O <: WCState[Ctx]](
  runIO: I => IO[Evt],
  handleEvent: (I, Evt) => Either[Err, O]
)(using evtCt: ClassTag[Evt]) extends WIONode[Ctx, I, Err, O]:
  def toRunnable: Runnable[I, O] =
    Runnable.fromInvoke[I, O]((input: I) =>
      runIO(input).flatMap { (evt: Evt) =>
        handleEvent(input, evt) match
          case Right(output) => IO.pure(output)
          case Left(err) => IO.raiseError(new RuntimeException(s"RunIO node error: $err"))
      }
    )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val detectEvent: WCEvent[Ctx] => Option[Evt] = (evt: WCEvent[Ctx]) => evtCt.unapply(evt)
    val convertEvent: Evt => WCEvent[Ctx] = (evt: Evt) => evt
    val handleDetected: (I, Evt) => Either[Err, O] = (i: I, evt: Evt) => handleEvent(i, evt)
    val eventHandler: EventHandler[I, Either[Err, O], WCEvent[Ctx], Evt] =
      EventHandler[WCEvent[Ctx], I, Either[Err, O], Evt](
        detect0 = detectEvent,
        convert0 = convertEvent,
        handle0 = handleDetected
      )
    WIO.RunIO[Ctx, I, Err, O, Evt](_ => (in: I) => runIO(in).asInstanceOf[WCEffect[Ctx][Evt]], eventHandler, WIO.RunIO.Meta(errorMeta, None, None))

final case class WIOForkNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  branches: List[WIOForkNode.Branch[Ctx, I, Err, O]],
  name: Option[String]
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val wioBranches = branches.map(_.toWIOBranch(using errorMeta)).toVector
    WIO.Fork[Ctx, I, Err, O](wioBranches, name, None)

object WIOForkNode:
  case class Branch[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    predicate: I => Option[I],
    workflow: WIO[I, Err, O, Ctx],
    branchName: Option[String] = None
  ):
    def toWIOBranch(using errorMeta: ErrorMeta[Err]): WIO.Branch[I, Err, O, Ctx, I] =
      WIO.Branch[I, Err, O, Ctx, I](predicate, workflow, branchName)

  def binaryFork[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    condition: I => Boolean,
    ifTrue: WIO[I, Err, O, Ctx],
    ifFalse: WIO[I, Err, O, Ctx],
    name: Option[String] = None
  )(using errorMeta: ErrorMeta[Err]): WIOForkNode[Ctx, I, Err, O] =
    val trueBranch: Branch[Ctx, I, Err, O] = Branch[Ctx, I, Err, O](
      (i: I) => if condition(i) then Some(i) else None,
      ifTrue
    )
    val falseBranch: Branch[Ctx, I, Err, O] = Branch[Ctx, I, Err, O](
      (i: I) => if !condition(i) then Some(i) else None,
      ifFalse
    )
    WIOForkNode(List(trueBranch, falseBranch), name)

  def withOtherwise[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    branches: List[Branch[Ctx, I, Err, O]],
    otherwise: WIO[I, Err, O, Ctx],
    name: Option[String] = None
  )(using errorMeta: ErrorMeta[Err]): WIOForkNode[Ctx, I, Err, O] =
    val otherwiseBranch: Branch[Ctx, I, Err, O] = Branch[Ctx, I, Err, O](
      (i: I) => Some(i),
      otherwise,
      Some("otherwise")
    )
    WIOForkNode(branches :+ otherwiseBranch, name)

  /** A stream-aware branch that inspects a stream to decide routing.
    * The classifier consumes the stream and returns a (branchName, materializedValue) pair.
    * Used for Eino-style stream branching (e.g., checking tool calls in streamed model output).
    */
  final case class StreamBranch[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    classifier: Stream[IO, I] => IO[(String, I)],
    namedBranches: Map[String, Runnable[I, O]],
    endBranchName: Option[String] = None
  )

  /** Create a stream-aware fork that classifies input via a stream and routes to named branches.
    * The classifier reads from a stream of I and returns (branchName, materializedValue).
    * If branchName matches endBranchName, the materializedValue is returned as the output directly.
    * Otherwise, the corresponding named branch Runnable is invoked.
    */
  def streamFork[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
    classifier: Stream[IO, I] => IO[(String, I)],
    namedBranches: Map[String, Runnable[I, O]],
    endBranchName: Option[String] = None
  ): StreamBranch[Ctx, I, Err, O] =
    StreamBranch[Ctx, I, Err, O](classifier, namedBranches, endBranchName)

final case class WIOLoopNode[Ctx <: WorkflowContext, I <: WCState[Ctx], Err, O <: WCState[Ctx]](
  body: WIO[I, Err, O, Ctx],
  stopWhen: O => Either[I, O],
  restart: WIO[I, Err, I, Ctx]
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val meta: WIO.Loop.Meta = WIO.Loop.Meta(None, None, None)
    WIO.Loop[Ctx, I, Err, O, I, O, I](
      body,
      stopWhen,
      restart,
      WIO.Loop.State.Forward[Ctx, I, Err, I, O](body),
      meta,
      Vector.empty
    )

final case class WIOAwaitNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  duration: WIO.Timer.DurationSource[I],
  handleEvent: (I, WIO.Timer.Released) => Either[Err, O],
  name: Option[String]
)(using
  releasedEvidence: WIO.Timer.Released <:< WCEvent[Ctx],
  startedEvidence: WIO.Timer.Started <:< WCEvent[Ctx]
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val startedClassTag: ClassTag[WIO.Timer.Started] = summon[ClassTag[WIO.Timer.Started]]
    val releasedClassTag: ClassTag[WIO.Timer.Released] = summon[ClassTag[WIO.Timer.Released]]
    val detectStarted: WCEvent[Ctx] => Option[WIO.Timer.Started] = (evt: WCEvent[Ctx]) => startedClassTag.unapply(evt)
    val convertStarted: WIO.Timer.Started => WCEvent[Ctx] = (evt: WIO.Timer.Started) => startedEvidence(evt)
    val handleStarted: (I, WIO.Timer.Started) => Unit = (_: I, _: WIO.Timer.Started) => ()
    val startedHandler: EventHandler[I, Unit, WCEvent[Ctx], WIO.Timer.Started] = EventHandler[
      WCEvent[Ctx],
      I,
      Unit,
      WIO.Timer.Started
    ](
      detect0 = detectStarted,
      convert0 = convertStarted,
      handle0 = handleStarted
    )
    val detectReleased: WCEvent[Ctx] => Option[WIO.Timer.Released] = (evt: WCEvent[Ctx]) => releasedClassTag.unapply(evt)
    val convertReleased: WIO.Timer.Released => WCEvent[Ctx] = (evt: WIO.Timer.Released) => releasedEvidence(evt)
    val handleReleased: (I, WIO.Timer.Released) => Either[Err, O] = (in: I, evt: WIO.Timer.Released) => handleEvent(in, evt)
    val releasedHandler: EventHandler[I, Either[Err, O], WCEvent[Ctx], WIO.Timer.Released] = EventHandler[
      WCEvent[Ctx],
      I,
      Either[Err, O],
      WIO.Timer.Released
    ](
      detect0 = detectReleased,
      convert0 = convertReleased,
      handle0 = handleReleased
    )
    WIO.Timer[Ctx, I, Err, O](duration, startedHandler, name, releasedHandler)

final case class WIOSubGraphNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  subGraph: WIOGraph[Ctx, I, Err, O]
) extends WIONode[Ctx, I, Err, O]:
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    subGraph.toWIO match
      case Right(wio) => wio
      case Left(errors) =>
        val message: String = errors.toNonEmptyList.toList.mkString("Sub-graph compilation failed: ", ", ", "")
        val failEffect: I => WCEffect[Ctx][Nothing] = (_: I) =>
          IO.raiseError(org.adk4s.core.error.GenericError(message)).asInstanceOf[WCEffect[Ctx][Nothing]]
        val detectNothing: WCEvent[Ctx] => Option[Nothing] = (_: WCEvent[Ctx]) => None
        val convertNothing: Nothing => WCEvent[Ctx] = (n: Nothing) => n
        val handleNothing: (I, Nothing) => Either[Err, O] = (_: I, n: Nothing) => n
        val eventHandler: EventHandler[I, Either[Err, O], WCEvent[Ctx], Nothing] =
          EventHandler[WCEvent[Ctx], I, Either[Err, O], Nothing](
            detect0 = detectNothing,
            convert0 = convertNothing,
            handle0 = handleNothing
          )
        WIO.RunIO[Ctx, I, Err, O, Nothing](
          _ => failEffect,
          eventHandler,
          WIO.RunIO.Meta(errorMeta, None, None)
        )

final case class WIOHandleSignalNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Req, Resp, Evt <: WCEvent[Ctx]](
  signalDef: SignalDef[Req, Resp],
  signalHandler: (I, Req) => IO[Evt],
  eventHandler: (I, Evt) => Either[Err, O],
  responseHandler: (I, Evt) => Resp,
  operationName: Option[String]
)(using evtCt: ClassTag[Evt]) extends WIONode[Ctx, I, Err, O]:
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val sigHandler: SignalHandler[WCEffect[Ctx], Req, Evt, I, WCState[Ctx]] =
      SignalHandler[WCEffect[Ctx], Req, Evt, I, WCState[Ctx]](_ => (in: I, req: Req) => signalHandler(in, req).asInstanceOf[WCEffect[Ctx][Evt]])
    val evtHandler: EventHandler[I, Either[Err, O], WCEvent[Ctx], Evt] =
      EventHandler[WCEvent[Ctx], I, Either[Err, O], Evt](evtCt.unapply, (evt: Evt) => evt, eventHandler)
    val responseProducer: (I, Evt, Req) => Resp = (in: I, evt: Evt, _: Req) => responseHandler(in, evt)
    val meta: WIO.HandleSignal.Meta = WIO.HandleSignal.Meta(errorMeta, signalDef.name, operationName)
    WIO.HandleSignal[Ctx, I, O, Err, Req, Resp, Evt](signalDef, sigHandler, evtHandler, responseProducer, meta)

final case class WIOParallelNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], InterimState <: WCState[Ctx]](
  elements: List[WIOParallelNode.Element[Ctx, I, Err, InterimState, ? <: WCState[Ctx]]],
  formResult: Seq[WCState[Ctx]] => O,
  initialInterimState: I => InterimState
) extends WIONode[Ctx, I, Err, O]:
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val parallelElements: List[WIO.Parallel.Element[Ctx, I, Err, WCState[Ctx], InterimState]] =
      elements.map((elem: WIOParallelNode.Element[Ctx, I, Err, InterimState, ? <: WCState[Ctx]]) => elem.toParallelElement)
    WIO.Parallel[Ctx, I, Err, O, InterimState](parallelElements, formResult, initialInterimState)

object WIOParallelNode:
  final case class Element[Ctx <: WorkflowContext, I, Err, InterimState <: WCState[Ctx], Out <: WCState[Ctx]](
    workflow: WIO[I, Err, Out, Ctx],
    incorporateState: (InterimState, WCState[Ctx]) => InterimState
  ):
    def toParallelElement: WIO.Parallel.Element[Ctx, I, Err, WCState[Ctx], InterimState] =
      WIO.Parallel.Element[Ctx, I, Err, WCState[Ctx], InterimState](workflow, incorporateState)

final case class WIORunnableNode[Ctx <: WorkflowContext, I, Err, Evt <: WCEvent[Ctx], RawOut, O <: WCState[Ctx]](
  runnable: Runnable[I, RawOut],
  toEvent: RawOut => Evt,
  handleEvent: (I, Evt) => Either[Err, O]
)(using evtCt: ClassTag[Evt]) extends WIONode[Ctx, I, Err, O]:
  private def applyHandler(input: I, raw: RawOut): IO[O] =
    handleEvent(input, toEvent(raw)) match
      case Right(output) => IO.pure(output)
      case Left(err) => IO.raiseError(new RuntimeException(s"Runnable node error: $err"))

  def toRunnable: Runnable[I, O] =
    Runnable.full[I, O](
      invokeFn = (input: I) => runnable.invoke(input).flatMap(applyHandler(input, _)),
      streamFn = (input: I) => runnable.stream(input).evalMap(applyHandler(input, _)),
      collectFn = (inputStream: Stream[IO, I]) =>
        inputStream.compile.lastOrError.flatMap { (input: I) =>
          runnable.invoke(input).flatMap(applyHandler(input, _))
        },
      transformFn = (inputStream: Stream[IO, I]) =>
        inputStream.evalMap { (input: I) =>
          runnable.invoke(input).flatMap(applyHandler(input, _))
        }
    )

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val runIOFn: I => IO[Evt] = (i: I) => runnable.invoke(i).map(toEvent)
    val detectEvent: WCEvent[Ctx] => Option[Evt] = (evt: WCEvent[Ctx]) => evtCt.unapply(evt)
    val convertEvent: Evt => WCEvent[Ctx] = (evt: Evt) => evt
    val handleDetected: (I, Evt) => Either[Err, O] = (i: I, evt: Evt) => handleEvent(i, evt)
    val eventHandler: EventHandler[I, Either[Err, O], WCEvent[Ctx], Evt] =
      EventHandler[WCEvent[Ctx], I, Either[Err, O], Evt](
        detect0 = detectEvent,
        convert0 = convertEvent,
        handle0 = handleDetected
      )
    WIO.RunIO[Ctx, I, Err, O, Evt](_ => (in: I) => runIOFn(in).asInstanceOf[WCEffect[Ctx][Evt]], eventHandler, WIO.RunIO.Meta(errorMeta, None, None))

final case class WIOForEachNode[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Elem, InnerCtx <: WorkflowContext, ElemOut <: WCState[InnerCtx], InterimState <: WCState[Ctx]](
  getElements: I => Set[Elem],
  elemWorkflow: WIO[Elem, Err, ElemOut, InnerCtx],
  initialElemState: () => WCState[InnerCtx],
  eventEmbedding: WorkflowEmbedding.Event[(Elem, WCEvent[InnerCtx]), WCEvent[Ctx]],
  interimStateBuilder: (I, Map[Elem, WCState[InnerCtx]]) => InterimState,
  buildOutput: (I, Map[Elem, ElemOut]) => O,
  signalRouter: SignalRouter.Receiver[Elem, InterimState],
  name: Option[String]
) extends WIONode[Ctx, I, Err, O]:
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def toWIO(using errorMeta: ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val meta: WIOMeta.ForEach = WIOMeta.ForEach(name)
    WIO.ForEach[Ctx, I, Err, O, Elem, InnerCtx, ElemOut, InterimState](
      getElements,
      elemWorkflow,
      initialElemState,
      eventEmbedding,
      interimStateBuilder,
      buildOutput,
      None,
      signalRouter,
      meta,
      [A] => (fa: WCEffect[InnerCtx][A]) => fa.asInstanceOf[WCEffect[Ctx][A]]
    )
