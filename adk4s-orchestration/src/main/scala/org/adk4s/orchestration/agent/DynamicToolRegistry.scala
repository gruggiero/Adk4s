package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.effect.Ref
import org.adk4s.core.component.InvokableTool

/**
 * A thread-safe, mutable registry of tools that can be modified at runtime.
 *
 * Uses cats-effect Ref for lock-free concurrent access. Designed to be used
 * with ReactAgent.createWithToolProvider to allow adding/removing tools
 * between agent invocations.
 */
final class DynamicToolRegistry private (
  toolsRef: Ref[IO, List[InvokableTool[IO]]]
):
  def addTool(tool: InvokableTool[IO]): IO[Unit] =
    toolsRef.update((tools: List[InvokableTool[IO]]) => tools :+ tool)

  def removeTool(name: String): IO[Unit] =
    toolsRef.update((tools: List[InvokableTool[IO]]) =>
      tools.filterNot((t: InvokableTool[IO]) => t.info.name == name)
    )

  def replaceTool(name: String, tool: InvokableTool[IO]): IO[Unit] =
    toolsRef.update((tools: List[InvokableTool[IO]]) =>
      tools.map((t: InvokableTool[IO]) =>
        if t.info.name == name then tool else t
      )
    )

  def currentTools: IO[List[InvokableTool[IO]]] =
    toolsRef.get

  def toolNames: IO[List[String]] =
    toolsRef.get.map((tools: List[InvokableTool[IO]]) =>
      tools.map((t: InvokableTool[IO]) => t.info.name)
    )

  def hasTool(name: String): IO[Boolean] =
    toolsRef.get.map((tools: List[InvokableTool[IO]]) =>
      tools.exists((t: InvokableTool[IO]) => t.info.name == name)
    )

  def clear: IO[Unit] =
    toolsRef.set(List.empty)

object DynamicToolRegistry:
  def create(initial: List[InvokableTool[IO]]): IO[DynamicToolRegistry] =
    Ref.of[IO, List[InvokableTool[IO]]](initial).map((ref: Ref[IO, List[InvokableTool[IO]]]) =>
      new DynamicToolRegistry(ref)
    )

  def empty: IO[DynamicToolRegistry] =
    create(List.empty)
