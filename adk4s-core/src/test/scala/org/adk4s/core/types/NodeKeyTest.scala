package org.adk4s.core.types

import munit.CatsEffectSuite
import cats.Eq
import cats.Order
import cats.implicits.catsKernelOrderingForOrder

class NodeKeyTest extends CatsEffectSuite:

  test("create valid node key") {
    assertEquals(NodeKey("agent_1"), Right(NodeKey.unsafeApply("agent_1")))
  }

  test("create valid node key with underscores") {
    assertEquals(NodeKey("agent_node_1"), Right(NodeKey.unsafeApply("agent_node_1")))
  }

  test("create valid node key with numbers") {
    assertEquals(NodeKey("node123"), Right(NodeKey.unsafeApply("node123")))
  }

  test("reject empty node key") {
    assertEquals(NodeKey(""), Left("Node key cannot be empty"))
  }

  test("reject reserved start key") {
    assertEquals(NodeKey("__start__"), Left("Node key '__start__' is reserved"))
  }

  test("reject reserved end key") {
    assertEquals(NodeKey("__end__"), Left("Node key '__end__' is reserved"))
  }

  test("use unsafeApply for trusted keys") {
    val key = NodeKey.unsafeApply("model_node")
    assertEquals(key.value, "model_node")
  }

  test("from rejects invalid key") {
    assertEquals(NodeKey.from(""), Left(org.adk4s.core.error.NodeKeyError("")))
  }

  test("from rejects reserved key") {
    assertEquals(NodeKey.from("__start__"), Left(org.adk4s.core.error.NodeKeyError("__start__")))
  }

  test("check if node key is start") {
    val key = NodeKey.START
    assert(key.isStart)
  }

  test("check if node key is not start") {
    val key = NodeKey.unsafeApply("agent_1")
    assert(!key.isStart)
  }

  test("check if node key is end") {
    val key = NodeKey.END
    assert(key.isEnd)
  }

  test("check if node key is not end") {
    val key = NodeKey.unsafeApply("agent_1")
    assert(!key.isEnd)
  }

  test("check if node key is reserved - start") {
    val key = NodeKey.START
    assert(key.isReserved)
  }

  test("check if node key is reserved - end") {
    val key = NodeKey.END
    assert(key.isReserved)
  }

  test("check if node key is not reserved") {
    val key = NodeKey.unsafeApply("agent_1")
    assert(!key.isReserved)
  }

  test("Eq instance compares by value") {
    val key1 = NodeKey.unsafeApply("node")
    val key2 = NodeKey.unsafeApply("node")
    val key3 = NodeKey.unsafeApply("different")
    assert(Eq[NodeKey].eqv(key1, key2))
    assert(!Eq[NodeKey].eqv(key1, key3))
  }

  test("Order instance sorts alphabetically") {
    val key1 = NodeKey.unsafeApply("agent_1")
    val key2 = NodeKey.unsafeApply("agent_2")
    val key3 = NodeKey.unsafeApply("agent_10")
    val keys = List(key3, key2, key1).sorted(using Order[NodeKey].toOrdering)
    assertEquals(keys.map(_.value), List("agent_1", "agent_10", "agent_2"))
  }

  test("Show instance formats to value") {
    val key = NodeKey.unsafeApply("model_node")
    assertEquals(cats.Show[NodeKey].show(key), "model_node")
  }

  test("value method returns key string") {
    val key = NodeKey.unsafeApply("test_node")
    assertEquals(key.value, "test_node")
  }

  test("START reserved key has correct value") {
    assertEquals(NodeKey.START.value, "__start__")
  }

  test("END reserved key has correct value") {
    assertEquals(NodeKey.END.value, "__end__")
  }
