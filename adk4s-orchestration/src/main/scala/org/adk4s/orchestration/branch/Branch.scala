package org.adk4s.orchestration.branch

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.types.{NodeKey, ReservedNodeKey}

// RouteTarget: a routing destination that can be either a regular
// NodeKey or a ReservedNodeKey (e.g. End). Replaces the old pattern
// of using NodeKey.END as a NodeKey value, which is no longer valid
// since NodeKey now rejects reserved strings at the type level.
sealed trait RouteTarget extends Product with Serializable:
  def value: String

object RouteTarget:
  case class ToNode(key: NodeKey) extends RouteTarget:
    def value: String = key.value
  case class ToReserved(reserved: ReservedNodeKey) extends RouteTarget:
    def value: String = reserved.value

sealed trait Branch[I]:
  def endNodes: Set[RouteTarget]

case class InvokeBranch[I](
  condition: I => IO[RouteTarget],
  endNodes: Set[RouteTarget]
) extends Branch[I]

case class StreamBranch[I](
  condition: Stream[IO, I] => IO[RouteTarget],
  endNodes: Set[RouteTarget]
) extends Branch[I]

object Branch:
  def apply[I](condition: I => IO[RouteTarget], targets: Set[RouteTarget]): Branch[I] =
    InvokeBranch(condition, targets)

  def pure[I](condition: I => RouteTarget, targets: Set[RouteTarget]): Branch[I] =
    InvokeBranch(i => IO.pure(condition(i)), targets)

  def stream[I](condition: Stream[IO, I] => IO[RouteTarget], targets: Set[RouteTarget]): Branch[I] =
    StreamBranch(condition, targets)

  def binary[I](
    predicate: I => IO[Boolean],
    ifTrue: NodeKey,
    ifFalse: NodeKey
  ): Branch[I] =
    InvokeBranch(
      i => predicate(i).map(if _ then RouteTarget.ToNode(ifTrue) else RouteTarget.ToNode(ifFalse)),
      Set(RouteTarget.ToNode(ifTrue), RouteTarget.ToNode(ifFalse))
    )

  def endIf[I](predicate: I => IO[Boolean], otherwise: NodeKey): Branch[I] =
    InvokeBranch(
      i => predicate(i).map(if _ then RouteTarget.ToReserved(ReservedNodeKey.End) else RouteTarget.ToNode(otherwise)),
      Set(RouteTarget.ToReserved(ReservedNodeKey.End), RouteTarget.ToNode(otherwise))
    )
