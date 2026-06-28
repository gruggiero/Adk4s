package org.adk4s.orchestration.wiograph

import cats.Applicative
import cats.effect.IO
import workflows4s.wio.{ErrorMeta, WCEffect, WCEvent, WCState, WIO, WIOContext, WorkflowContext}
import workflows4s.wio.internal.EventHandler

import java.time.Instant
import scala.reflect.ClassTag

sealed trait WIONodeModifier[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]]:
  def apply(base: WIO[I, Err, O, Ctx])(using ErrorMeta[Err]): WIO[I, Err, O, Ctx]

final case class CheckpointModifier[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx], Evt <: WCEvent[Ctx]](
  genEvent: (I, O) => Evt,
  handleEvent: (I, Evt) => O
)(using evtCt: ClassTag[Evt]) extends WIONodeModifier[Ctx, I, Err, O]:
  def apply(base: WIO[I, Err, O, Ctx])(using ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    val genEventIO: (I, O) => IO[Evt] = (in: I, out: O) => IO.pure(genEvent(in, out))
    val detectEvent: WCEvent[Ctx] => Option[Evt] = (evt: WCEvent[Ctx]) => evtCt.unapply(evt)
    val convertEvent: Evt => WCEvent[Ctx] = (evt: Evt) => evt
    val handleDetected: (I, Evt) => O = (in: I, evt: Evt) => handleEvent(in, evt)
    val eventHandler: EventHandler[I, O, WCEvent[Ctx], Evt] =
      EventHandler[WCEvent[Ctx], I, O, Evt](
        detect0 = detectEvent,
        convert0 = convertEvent,
        handle0 = handleDetected
      )
    WIO.Checkpoint[Ctx, I, Err, O, Evt](base, _ => (in: I, out: O) => genEventIO(in, out).asInstanceOf[WCEffect[Ctx][Evt]], eventHandler)

final case class RetryModifier[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  onError: (Throwable, WCState[Ctx], Instant) => IO[Option[Instant]]
) extends WIONodeModifier[Ctx, I, Err, O]:
  def apply(base: WIO[I, Err, O, Ctx])(using ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    given Applicative[WCEffect[Ctx]] = cats.Applicative[IO].asInstanceOf[Applicative[WCEffect[Ctx]]]
    base.retry.statelessly.wakeupAt { (in, err, state) =>
      onError(err, state, Instant.EPOCH).asInstanceOf[WCEffect[Ctx][Option[Instant]]]
    }

final case class InterruptionModifier[Ctx <: WorkflowContext, I, Err, O <: WCState[Ctx]](
  interruption: WIO.Interruption[Ctx, Err, O]
) extends WIONodeModifier[Ctx, I, Err, O]:
  def apply(base: WIO[I, Err, O, Ctx])(using ErrorMeta[Err]): WIO[I, Err, O, Ctx] =
    WIO.HandleInterruption[Ctx, I, Err, O](
      base,
      interruption.handler,
      WIO.HandleInterruption.InterruptionStatus.Pending,
      interruption.tpe
    )
