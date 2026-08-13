package org.adk4s.orchestration.workflow

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.types.{NodeKey, Reserved}
import org.adk4s.core.types.given
import org.adk4s.orchestration.workflow.FieldMapping

class WorkflowTest extends CatsEffectSuite:
  test("create empty workflow") {
    val workflow = Workflow[Int, String]
    assertEquals(workflow, Workflow[Int, String])
  }

  test("add lambda node with default input mapping") {
    val result: Either[org.adk4s.core.error.NodeKeyError, Workflow[Int, String]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
        b2 <- b1.addInput("start")
      yield b2.done

    assert(result.isRight, s"Expected Right, got $result")
  }

  test("add lambda node with custom field mapping") {
    val mapping = FieldMapping("output.data", "input")

    val result: Either[org.adk4s.core.error.NodeKeyError, Workflow[Int, String]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
        b2 <- b1.addInput("start", mapping)
      yield b2.done

    assert(result.isRight, s"Expected Right, got $result")
  }

  test("set end node for workflow") {
    val result: Either[org.adk4s.core.error.NodeKeyError, Workflow[Int, String]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, String]((x: Int) => IO.pure(x.toString)))
        w  <- b1.done.end.at("node1")
      yield w

    assert(result.isRight, s"Expected Right, got $result")
  }

  test("compile workflow raises UnsupportedOperationException (not yet implemented)") {
    val result: Either[org.adk4s.core.error.NodeKeyError, Workflow[Int, String]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
        b2 <- b1.addInput("start")
        b3 <- b2.addLambdaNode("node2", Lambda[Int, String]((x: Int) => IO.pure(x.toString)))
        b4 <- b3.addInput("node1", FieldMapping.rootRoot)
        w  <- b4.done.end.at("node2")
      yield w

    val workflow: Workflow[Int, String] = result.fold(
      err => fail(s"Unexpected error: $err"),
      identity
    )

    interceptIO[UnsupportedOperationException](workflow.compile).map { error =>
      assert(error.getMessage.contains("not yet implemented"))
    }
  }

  test("addLambdaNode rejects invalid key") {
    val result = Workflow[Int, String].addLambdaNode("", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
    assert(result.isLeft, s"Expected Left for empty key, got $result")
  }

  test("addLambdaNode rejects reserved key") {
    val result = Workflow[Int, String].addLambdaNode("__start__", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
    assert(result.isLeft, s"Expected Left for reserved key, got $result")
  }

  test("addInput rejects invalid key") {
    val result: Either[org.adk4s.core.error.NodeKeyError, WorkflowNodeBuilder[Int, String, Int, Int]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
        b2 <- b1.addInput("")
      yield b2

    assert(result.isLeft, s"Expected Left for empty key, got $result")
  }

  test("end.at rejects invalid key") {
    val result: Either[org.adk4s.core.error.NodeKeyError, Workflow[Int, String]] =
      for
        b1 <- Workflow[Int, String].addLambdaNode("node1", Lambda[Int, String]((x: Int) => IO.pure(x.toString)))
        w  <- b1.done.end.at("")
      yield w

    assert(result.isLeft, s"Expected Left for empty key, got $result")
  }

  test("create field mapping with string paths") {
    val mapping = FieldMapping("source.path", "dest.path")
    assertEquals(mapping.from.render, "source.path")
    assertEquals(mapping.to.render, "dest.path")
  }

  test("create field mapping with fromNode") {
    val nodeKey = NodeKey("node1")
    val mapping = FieldMapping.withNode("source", "dest", nodeKey)
    assertEquals(mapping.fromNode, Some(nodeKey))
  }
