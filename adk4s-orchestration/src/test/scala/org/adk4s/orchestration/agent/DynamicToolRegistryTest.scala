package org.adk4s.orchestration.agent

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool

class DynamicToolRegistryTest extends CatsEffectSuite:

  private def makeTool(name: String): InvokableTool[IO] =
    Tool.invokable[IO](name, s"Tool $name", (_: ujson.Value) => Right(ujson.Str(name)))

  test("create with initial tools") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a"), makeTool("b")))
      names <- registry.toolNames
    yield assertEquals(names, List("a", "b"))
  }

  test("empty creates registry with no tools") {
    for
      registry <- DynamicToolRegistry.empty
      names <- registry.toolNames
    yield assertEquals(names, List.empty[String])
  }

  test("addTool appends a tool") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a")))
      _ <- registry.addTool(makeTool("b"))
      names <- registry.toolNames
    yield assertEquals(names, List("a", "b"))
  }

  test("removeTool removes by name") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a"), makeTool("b"), makeTool("c")))
      _ <- registry.removeTool("b")
      names <- registry.toolNames
    yield assertEquals(names, List("a", "c"))
  }

  test("removeTool is no-op for unknown name") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a")))
      _ <- registry.removeTool("z")
      names <- registry.toolNames
    yield assertEquals(names, List("a"))
  }

  test("replaceTool swaps a tool by name") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a"), makeTool("b")))
      replacement = Tool.invokable[IO]("b", "Replaced tool b", (_: ujson.Value) => Right(ujson.Str("replaced")))
      _ <- registry.replaceTool("b", replacement)
      tools <- registry.currentTools
      bTool = tools.find(_.info.name == "b")
    yield {
      assert(bTool.isDefined)
      assertEquals(bTool.get.info.description, "Replaced tool b")
    }
  }

  test("hasTool returns true for existing tool") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a")))
      result <- registry.hasTool("a")
    yield assert(result)
  }

  test("hasTool returns false for missing tool") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a")))
      result <- registry.hasTool("z")
    yield assert(!result)
  }

  test("clear removes all tools") {
    for
      registry <- DynamicToolRegistry.create(List(makeTool("a"), makeTool("b")))
      _ <- registry.clear
      names <- registry.toolNames
    yield assertEquals(names, List.empty[String])
  }

  test("concurrent add/remove is safe") {
    for
      registry <- DynamicToolRegistry.create(List.empty)
      _ <- (1 to 100).toList.map((i: Int) => registry.addTool(makeTool(s"t$i"))).reduce(_ *> _)
      _ <- (1 to 50).toList.map((i: Int) => registry.removeTool(s"t$i")).reduce(_ *> _)
      names <- registry.toolNames
    yield assertEquals(names.length, 50)
  }
