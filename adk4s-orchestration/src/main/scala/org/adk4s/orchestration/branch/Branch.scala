package org.adk4s.orchestration.branch

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.types.NodeKey

sealed trait Branch[I]:
  def endNodes: Set[NodeKey]

case class InvokeBranch[I](
  condition: I => IO[NodeKey],
  endNodes: Set[NodeKey]
) extends Branch[I]

case class StreamBranch[I](
  condition: Stream[IO, I] => IO[NodeKey],
  endNodes: Set[NodeKey]
) extends Branch[I]

object Branch:
  def apply[I](condition: I => IO[NodeKey], targets: Set[NodeKey]): Branch[I] =
    InvokeBranch(condition, targets)

  def pure[I](condition: I => NodeKey, targets: Set[NodeKey]): Branch[I] =
    InvokeBranch(i => IO.pure(condition(i)), targets)

  def stream[I](condition: Stream[IO, I] => IO[NodeKey], targets: Set[NodeKey]): Branch[I] =
    StreamBranch(condition, targets)

  def binary[I](
    predicate: I => IO[Boolean],
    ifTrue: NodeKey,
    ifFalse: NodeKey
  ): Branch[I] =
    InvokeBranch(
      i => predicate(i).map(if _ then ifTrue else ifFalse),
      Set(ifTrue, ifFalse)
    )

  def endIf[I](predicate: I => IO[Boolean], otherwise: NodeKey): Branch[I] =
    binary(predicate, NodeKey.END, otherwise)
