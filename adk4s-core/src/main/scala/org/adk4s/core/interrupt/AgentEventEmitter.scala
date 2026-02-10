package org.adk4s.core.interrupt

import cats.effect.IO
import cats.effect.std.Queue
import fs2.Stream

/** Publishes agent execution events for consumption by subscribers. */
final class AgentEventEmitter private (
  queue: Queue[IO, Option[AgentEvent]],
  scopeStep: Option[RunStep]
):
  /** Enqueue an event. If this emitter is scoped, the RunStep is prepended to the event's RunPath. */
  def emit(event: AgentEvent): IO[Unit] =
    val scopedEvent: AgentEvent = scopeStep match
      case Some(step) => event.withPrependedStep(step)
      case None       => event
    queue.offer(Some(scopedEvent))

  /** Subscribe to the event stream. Terminates when `complete` is called. */
  def subscribe: Stream[IO, AgentEvent] =
    Stream.fromQueueNoneTerminated(queue)

  /** Signal that no more events will be emitted — terminates all subscribers. */
  def complete: IO[Unit] =
    queue.offer(None)

  /** Create a child emitter that prepends the given RunStep to all emitted events. */
  def scoped(step: RunStep): AgentEventEmitter =
    new AgentEventEmitter(queue, Some(step))

object AgentEventEmitter:
  /** Create a new emitter backed by a bounded queue. */
  def create(capacity: Int = 256): IO[AgentEventEmitter] =
    Queue.bounded[IO, Option[AgentEvent]](capacity).map { (q: Queue[IO, Option[AgentEvent]]) =>
      new AgentEventEmitter(q, scopeStep = None)
    }
