package org.adk4s.orchestration.interrupt

import cats.effect.IO
import org.adk4s.core.runnable.Runnable

/**
 * A node that can be interrupted before execution for human review.
 *
 * Implements the human-in-the-loop pattern from Eino's react_with_interrupt:
 *   1. Before executing the inner action, checks a predicate
 *   2. If the predicate returns true, pauses and stores state in a CheckpointStore
 *   3. Waits for a human to call `resume` with approval/rejection
 *   4. If approved, executes the inner action; if rejected, returns a rejection result
 *
 * This is a standalone component (not a WIONode) that can be used in any IO-based workflow.
 * For WIOGraph integration, use WIOHandleSignalNode directly.
 */
final class InterruptibleNode[I, O] private (
  shouldInterrupt: I => Boolean,
  innerAction: I => IO[O],
  onInterrupt: I => IO[InterruptInfo],
  store: CheckpointStore
):

  def invoke(input: I): IO[InterruptResult[O]] =
    if shouldInterrupt(input) then
      for
        info <- onInterrupt(input)
        _ <- store.set(info.checkpointId, info.serializedState)
      yield InterruptResult.Interrupted(info)
    else
      innerAction(input).map((output: O) => InterruptResult.Completed(output))

  def resume(checkpointId: String, approved: Boolean, input: I): IO[InterruptResult[O]] =
    for
      checkpointOpt <- store.get(checkpointId)
      result <- checkpointOpt match
        case None =>
          IO.raiseError(new RuntimeException(s"Checkpoint not found: $checkpointId"))
        case Some(_) =>
          if approved then
            for
              _ <- store.delete(checkpointId)
              output <- innerAction(input)
            yield InterruptResult.Completed(output)
          else
            for
              _ <- store.delete(checkpointId)
            yield InterruptResult.Rejected(checkpointId)
    yield result

  def toRunnable: Runnable[I, InterruptResult[O]] =
    Runnable.fromInvoke[I, InterruptResult[O]]((input: I) => invoke(input))

object InterruptibleNode:
  def create[I, O](
    shouldInterrupt: I => Boolean,
    innerAction: I => IO[O],
    onInterrupt: I => IO[InterruptInfo],
    store: CheckpointStore
  ): InterruptibleNode[I, O] =
    new InterruptibleNode[I, O](shouldInterrupt, innerAction, onInterrupt, store)

final case class InterruptInfo(
  checkpointId: String,
  description: String,
  serializedState: Array[Byte]
)

sealed trait InterruptResult[+O]

object InterruptResult:
  final case class Completed[O](output: O) extends InterruptResult[O]
  final case class Interrupted(info: InterruptInfo) extends InterruptResult[Nothing]
  final case class Rejected(checkpointId: String) extends InterruptResult[Nothing]
