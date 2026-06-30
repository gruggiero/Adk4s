package org.adk4s.orchestration.interrupt

import cats.effect.IO
import munit.CatsEffectSuite
import munit.FailException

// spec: wartremover-option-partial — Scenario tests (Step 2)
// Tests written from the spec BEFORE implementation.
// They verify behavior preservation after the refactor in Step 3.

class OptionPartialScenarioSpec extends CatsEffectSuite:

  // spec: wartremover-option-partial — Scenario: .get on a possibly-absent Option replaced by .getOrElse
  test("Missing checkpoint returns None (not throw) — behavior preserved") {
    for
      store <- InMemoryCheckpointStore.create
      result <- store.get("missing")
    yield assertEquals(result, None)
  }

  // spec: wartremover-option-partial — Scenario: .get on a known-present Option replaced by pattern match
  test("Present checkpoint returns Some(data) — behavior preserved") {
    val data: Array[Byte] = "hello".getBytes
    for
      store <- InMemoryCheckpointStore.create
      _ <- store.set("key1", data)
      result <- store.get("key1")
    yield
      assert(result.isDefined)
      assertEquals(result.getOrElse(fail("expected checkpoint to be defined")), data)
  }

  // spec: wartremover-option-partial — Scenario: Test-source .get on assertions replaced by .getOrElse with munit fail
  test("getOrElse with fail preserves Some value") {
    val opt: Option[Int] = Some(42)
    assertEquals(opt.getOrElse(fail("expected Some")), 42)
  }

  test("getOrElse with fail fails on None") {
    val opt: Option[Int] = None
    val ex: FailException = intercept[FailException] {
      opt.getOrElse(fail("expected Some"))
    }
    assert(ex.getMessage.contains("expected Some"), s"message should contain 'expected Some': ${ex.getMessage}")
  }

  // spec: wartremover-option-partial — Scenario: Edge case — .get on a Map (not Option) is NOT touched
  test("Map#get returns Option and is not flagged by OptionPartial") {
    val map: Map[String, Int] = Map("a" -> 1, "b" -> 2)
    // Map#get returns Option, which is fine — it's Option#get that's flagged
    assertEquals(map.get("a"), Some(1))
    assertEquals(map.get("missing"), None)
  }
