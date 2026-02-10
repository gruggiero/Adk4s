package org.adk4s.orchestration.branch

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.types.NodeKey

case class Router[I](branches: Map[NodeKey, Branch[I]]):
  def route(fromNode: NodeKey, input: I): IO[NodeKey] =
    branches.get(fromNode) match
      case Some(InvokeBranch(condition, _)) =>
        condition(input)
      case Some(StreamBranch(_, _)) =>
        IO.raiseError(new IllegalStateException("Cannot use invoke routing with stream branch"))
      case None =>
        IO.raiseError(new IllegalStateException(s"No branch defined for node ${fromNode.value}"))

  def routeStream(fromNode: NodeKey, input: Stream[IO, I]): IO[NodeKey] =
    branches.get(fromNode) match
      case Some(StreamBranch(condition, _)) =>
        condition(input)
      case Some(InvokeBranch(condition, _)) =>
        input.compile.lastOrError.flatMap(condition)
      case None =>
        IO.raiseError(new IllegalStateException(s"No branch defined for node ${fromNode.value}"))

  def addBranch(fromNode: NodeKey, branch: Branch[I]): Router[I] =
    copy(branches = branches + (fromNode -> branch))

object Router:
  def empty[I]: Router[I] = Router(Map.empty)
