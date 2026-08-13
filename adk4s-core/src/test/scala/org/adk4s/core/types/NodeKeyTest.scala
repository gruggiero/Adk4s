package org.adk4s.core.types

import munit.CatsEffectSuite
import cats.Eq
import cats.Order
import cats.implicits.catsKernelOrderingForOrder

class NodeKeyTest extends CatsEffectSuite:

  test("create valid node key") {
    val result: Either[String, NodeKey] = NodeKey.either("agent_1")
    assertEquals(result, Right(NodeKey("agent_1")))
  }

  test("create valid node key with underscores") {
    val result: Either[String, NodeKey] = NodeKey.either("agent_node_1")
    assertEquals(result, Right(NodeKey("agent_node_1")))
  }

  test("create valid node key with numbers") {
    val result: Either[String, NodeKey] = NodeKey.either("node123")
    assertEquals(result, Right(NodeKey("node123")))
  }

  test("reject empty node key") {
    val result: Either[String, NodeKey] = NodeKey.either("")
    assert(result.isLeft, s"Expected Left, got $result")
    assert(result.left.toOption.exists(_.contains("whitespace")), s"Error should mention whitespace: $result")
  }

  test("reject reserved start key") {
    val result: Either[String, NodeKey] = NodeKey.either("__start__")
    assert(result.isLeft, s"Expected Left, got $result")
  }

  test("reject reserved end key") {
    val result: Either[String, NodeKey] = NodeKey.either("__end__")
    assert(result.isLeft, s"Expected Left, got $result")
  }

  test("use compile-time refinement for trusted keys") {
    val key: NodeKey = NodeKey("model_node")
    assertEquals(key.value, "model_node")
  }

  test("from rejects invalid key") {
    assertEquals(NodeKey.from(""), Left(org.adk4s.core.error.NodeKeyError("")))
  }

  test("from rejects reserved key") {
    assertEquals(NodeKey.from("__start__"), Left(org.adk4s.core.error.NodeKeyError("__start__")))
  }

  test("Eq instance compares by value") {
    val key1: NodeKey = NodeKey("node")
    val key2: NodeKey = NodeKey("node")
    val key3: NodeKey = NodeKey("different")
    assert(Eq[NodeKey].eqv(key1, key2))
    assert(!Eq[NodeKey].eqv(key1, key3))
  }

  test("Order instance sorts alphabetically") {
    val key1: NodeKey = NodeKey("agent_1")
    val key2: NodeKey = NodeKey("agent_2")
    val key3: NodeKey = NodeKey("agent_10")
    val keys: List[NodeKey] = List(key3, key2, key1).sorted(using Order[NodeKey].toOrdering)
    assertEquals(keys.map(_.value), List("agent_1", "agent_10", "agent_2"))
  }

  test("Show instance formats to value") {
    val key: NodeKey = NodeKey("model_node")
    assertEquals(cats.Show[NodeKey].show(key), "model_node")
  }

  test("value method returns key string") {
    val key: NodeKey = NodeKey("test_node")
    assertEquals(key.value, "test_node")
  }

  test("START reserved key has correct value") {
    assertEquals(ReservedNodeKey.Start.value, "__start__")
  }

  test("END reserved key has correct value") {
    assertEquals(ReservedNodeKey.End.value, "__end__")
  }
