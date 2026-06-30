package org.adk4s.structured.sap

import org.adk4s.structured.sap.CompletionState

/**
 * Per-field streaming behavior configuration.
 *
 * @param done If true, the field is only shown when complete (null until then)
 * @param needed If true, the container won't stream until this field is non-null
 * @param withState If true, the field is wrapped in StreamState for completion tracking
 */
final case class StreamingBehavior(
  done: Boolean = false,
  needed: Boolean = false,
  withState: Boolean = false
)

object StreamingBehavior:
  /** Default behavior — stream partial values as they arrive. */
  val default: StreamingBehavior = StreamingBehavior()

  /** @stream.done — only show when complete. */
  val done: StreamingBehavior = StreamingBehavior(done = true)

  /** @stream.not_null — container won't stream until non-null. */
  val needed: StreamingBehavior = StreamingBehavior(needed = true)

  /** @stream.with_state — wrap in StreamState. */
  val withState: StreamingBehavior = StreamingBehavior(withState = true)

/**
 * Wrapper for streamed values with completion state.
 *
 * Used by @stream.with_state to track whether a value is fully received.
 */
final case class StreamState[A](
  value: Option[A],
  state: CompletionState
)

object StreamState:
  /** Create a complete StreamState with a value. */
  def complete[A](value: A): StreamState[A] = StreamState(Some(value), CompletionState.Complete)

  /** Create an incomplete StreamState with a partial value. */
  def incomplete[A](value: Option[A]): StreamState[A] = StreamState(value, CompletionState.Incomplete)

  /** Create a pending StreamState (no value yet). */
  def pending[A]: StreamState[A] = StreamState(None, CompletionState.Pending)
