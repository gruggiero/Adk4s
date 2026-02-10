package org.adk4s.orchestration.execution

import cats.effect.IO
import scala.concurrent.duration.FiniteDuration

/** Lifecycle callbacks for graph execution observability. */
trait GraphCallback:
  /** Called when a node starts execution. */
  def onNodeStart(nodeKey: String): IO[Unit] = IO.pure(())

  /** Called when a node completes successfully. */
  def onNodeSuccess(nodeKey: String, duration: FiniteDuration): IO[Unit] = IO.pure(())

  /** Called when a node fails. */
  def onNodeFailure(nodeKey: String, error: Throwable, duration: FiniteDuration): IO[Unit] = IO.pure(())

/** No-op callback implementation for use when callbacks are not needed. */
object NoOpCallback extends GraphCallback
