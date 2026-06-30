package org.adk4s.orchestration.interrupt

import cats.effect.IO
import munit.CatsEffectSuite

class CheckpointStoreTest extends CatsEffectSuite:

  test("get returns None for missing key") {
    for
      store <- InMemoryCheckpointStore.create
      result <- store.get("missing")
    yield assertEquals(result, None)
  }

  test("set and get round-trip") {
    val data: Array[Byte] = "hello".getBytes
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.set("key1", data)
      result <- store.get("key1")
    yield {
      assert(result.isDefined)
      assertEquals(new String(result.getOrElse(fail("expected checkpoint to be defined"))), "hello")
    }
  }

  test("set overwrites existing value") {
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.set("key1", "first".getBytes)
      _ <- store.set("key1", "second".getBytes)
      result <- store.get("key1")
    yield assertEquals(new String(result.getOrElse(fail("expected checkpoint to be defined"))), "second")
  }

  test("delete removes a key") {
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.set("key1", "data".getBytes)
      _ <- store.delete("key1")
      result <- store.get("key1")
    yield assertEquals(result, None)
  }

  test("delete is no-op for missing key") {
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.delete("missing")
      result <- store.get("missing")
    yield assertEquals(result, None)
  }

  test("keys returns all stored keys") {
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.set("a", "1".getBytes)
      _ <- store.set("b", "2".getBytes)
      _ <- store.set("c", "3".getBytes)
      result <- store.keys
    yield assertEquals(result.sorted, List("a", "b", "c"))
  }

  test("keys returns empty list when store is empty") {
    for
      store <- InMemoryCheckpointStore.create
      result <- store.keys
    yield assertEquals(result, List.empty[String])
  }
