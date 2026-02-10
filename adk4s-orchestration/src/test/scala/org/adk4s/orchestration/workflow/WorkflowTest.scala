package org.adk4s.orchestration.workflow

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.runnable.Lambda
import org.adk4s.core.types.NodeKey
import org.adk4s.orchestration.workflow.FieldMapping

class WorkflowTest extends CatsEffectSuite:
  test("create empty workflow") {
    val workflow = Workflow[Int, String]
    assertEquals(workflow, Workflow[Int, String])
  }

  test("add lambda node with default input mapping") {
    val workflow = Workflow[Int, String]
      .addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .addInput("start")
      .done

    assert(workflow != null, "workflow should not be null")
  }

  test("add lambda node with custom field mapping") {
    val mapping = FieldMapping("output.data", "input")

    val workflow = Workflow[Int, String]
      .addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .addInput("start", mapping)
      .done

    assert(workflow != null, "workflow should not be null")
  }

  test("set end node for workflow") {
    val workflow = Workflow[Int, String]
      .addLambdaNode("node1", Lambda[Int, String]((x: Int) => IO.pure(x.toString)))
      .done
      .end
      .at("node1")

    assert(workflow != null, "workflow should not be null")
  }

  test("compile workflow raises UnsupportedOperationException (not yet implemented)") {
    val workflow: Workflow[Int, String] = Workflow[Int, String]
      .addLambdaNode("node1", Lambda[Int, Int]((x: Int) => IO.pure(x * 2)))
      .addInput("start")
      .addLambdaNode("node2", Lambda[Int, String]((x: Int) => IO.pure(x.toString)))
      .addInput("node1", FieldMapping.rootRoot)
      .done
      .end
      .at("node2")

    interceptIO[UnsupportedOperationException](workflow.compile).map { error =>
      assert(error.getMessage.contains("not yet implemented"))
    }
  }

  test("create field mapping with string paths") {
    val mapping = FieldMapping("source.path", "dest.path")
    assertEquals(mapping.from.render, "source.path")
    assertEquals(mapping.to.render, "dest.path")
  }

  test("create field mapping with fromNode") {
    val nodeKey = NodeKey.unsafeApply("node1")
    val mapping = FieldMapping.withNode("source", "dest", nodeKey)
    assertEquals(mapping.fromNode, Some(nodeKey))
  }
