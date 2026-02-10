package org.adk4s.core.types

import munit.CatsEffectSuite
import java.time.Instant

class RunInfoTest extends CatsEffectSuite:

  test("create basic RunInfo") {
    val key  = NodeKey.unsafeApply("model")
    val info = RunInfo.forNode(key, "LLM")
    assertEquals(info.nodeKey, key)
    assertEquals(info.componentType, "LLM")
    assertEquals(info.nodeName, None)
    assertEquals(info.startTime, None)
    assertEquals(info.parentPath, Nil)
  }

  test("create RunInfo with name") {
    val key  = NodeKey.unsafeApply("model")
    val info = RunInfo.forNode(key, "LLM", "Primary Model")
    assertEquals(info.nodeName, Some("Primary Model"))
  }

  test("create RunInfo with all fields") {
    val key    = NodeKey.unsafeApply("inner")
    val parent = NodeKey.unsafeApply("outer")
    val time   = Instant.now()
    val info = RunInfo(
      nodeKey = key,
      componentType = "LLM",
      nodeName = Some("Child Model"),
      startTime = Some(time),
      parentPath = List(parent)
    )
    assertEquals(info.nodeKey, key)
    assertEquals(info.componentType, "LLM")
    assertEquals(info.nodeName, Some("Child Model"))
    assertEquals(info.startTime, Some(time))
    assertEquals(info.parentPath, List(parent))
  }

  test("calculate full path from parent path") {
    val inner = NodeKey.unsafeApply("inner")
    val outer = NodeKey.unsafeApply("outer")
    val info = RunInfo(
      nodeKey = inner,
      componentType = "LLM",
      parentPath = List(outer)
    )
    assertEquals(info.fullPath, List(outer, inner))
  }

  test("full path with multiple parents") {
    val child   = NodeKey.unsafeApply("child")
    val parent1 = NodeKey.unsafeApply("parent1")
    val parent2 = NodeKey.unsafeApply("parent2")
    val info = RunInfo(
      nodeKey = child,
      componentType = "Tool",
      parentPath = List(parent1, parent2)
    )
    assertEquals(info.fullPath, List(parent1, parent2, child))
  }

  test("full path is empty for top-level node") {
    val key  = NodeKey.unsafeApply("root")
    val info = RunInfo.forNode(key, "LLM")
    assertEquals(info.fullPath, List(key))
  }

  test("Show formats RunInfo with name") {
    val key  = NodeKey.unsafeApply("model")
    val info = RunInfo(key, "LLM", Some("GPT-4"))
    val show = cats.Show[RunInfo].show(info)
    assert(show.contains("model"))
    assert(show.contains("(GPT-4)"))
    assert(show.contains("LLM"))
  }

  test("Show formats RunInfo without name") {
    val key  = NodeKey.unsafeApply("tool")
    val info = RunInfo(key, "Function", None)
    val show = cats.Show[RunInfo].show(info)
    assert(show.contains("tool"))
    assert(show.contains("Function"))
    assert(!show.contains("("))
  }

  test("Show formats RunInfo with parent path") {
    val inner = NodeKey.unsafeApply("inner")
    val outer = NodeKey.unsafeApply("outer")
    val info = RunInfo(
      nodeKey = inner,
      componentType = "LLM",
      nodeName = None,
      parentPath = List(outer)
    )
    val show = cats.Show[RunInfo].show(info)
    assert(show.contains("["))
    assert(show.contains("->"))
    assert(show.contains("outer"))
    assert(show.contains("inner"))
  }

  test("Show formats RunInfo with name and parent path") {
    val inner = NodeKey.unsafeApply("inner")
    val outer = NodeKey.unsafeApply("outer")
    val info = RunInfo(
      nodeKey = inner,
      componentType = "LLM",
      nodeName = Some("Child"),
      parentPath = List(outer)
    )
    val show = cats.Show[RunInfo].show(info)
    assert(show.contains("inner"))
    assert(show.contains("(Child)"))
    assert(show.contains("LLM"))
    assert(show.contains("["))
    assert(show.contains("outer -> inner"))
  }

  test("Show formats RunInfo without parent path") {
    val key  = NodeKey.unsafeApply("root")
    val info = RunInfo(key, "LLM", None)
    val show = cats.Show[RunInfo].show(info)
    assert(!show.contains("["))
  }

  test("RunInfo factory without name") {
    val key  = NodeKey.unsafeApply("test")
    val info = RunInfo.forNode(key, "TestType")
    assertEquals(info.nodeKey, key)
    assertEquals(info.componentType, "TestType")
    assertEquals(info.nodeName, None)
  }

  test("RunInfo factory with name") {
    val key  = NodeKey.unsafeApply("test")
    val info = RunInfo.forNode(key, "TestType", "Test Name")
    assertEquals(info.nodeKey, key)
    assertEquals(info.componentType, "TestType")
    assertEquals(info.nodeName, Some("Test Name"))
  }
