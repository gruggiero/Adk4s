package org.adk4s.core.interrupt

import munit.FunSuite

class InterruptSignalTest extends FunSuite:

  test("Simple interrupt has empty address by default") {
    val signal: InterruptSignal.Simple = InterruptSignal.simple("Need approval")
    assertEquals(signal.address, List.empty[AddressSegment])
    assertEquals(signal.info, "Need approval")
  }

  test("Stateful interrupt carries state") {
    val state: ujson.Value = ujson.Obj("key" -> "value")
    val signal: InterruptSignal.Stateful = InterruptSignal.stateful("Confirm?", state)
    assertEquals(signal.state, state)
    assertEquals(signal.info, "Confirm?")
  }

  test("Composite interrupt wraps children") {
    val child1: InterruptSignal.Simple = InterruptSignal.simple("child1")
    val child2: InterruptSignal.Simple = InterruptSignal.simple("child2")
    val state: ujson.Value = ujson.Obj("parent" -> "state")
    val composite: InterruptSignal.Composite = InterruptSignal.composite("parent", state, List(child1, child2))
    assertEquals(composite.children.length, 2)
    assertEquals(composite.info, "parent")
  }

  test("withAddress updates address on Simple") {
    val signal: InterruptSignal.Simple = InterruptSignal.simple("test")
    val updated: InterruptSignal = signal.withAddress(List(AddressSegment.Agent("supervisor")))
    assertEquals(updated.address, List(AddressSegment.Agent("supervisor")))
  }

  test("withAddress updates address on Stateful") {
    val signal: InterruptSignal.Stateful = InterruptSignal.stateful("test", ujson.Null)
    val addr: List[AddressSegment] = List(AddressSegment.Agent("agent"), AddressSegment.Tool("query"))
    val updated: InterruptSignal = signal.withAddress(addr)
    assertEquals(updated.address, addr)
  }

  test("AddressSegment.Agent has correct name") {
    val segment: AddressSegment = AddressSegment.Agent("supervisor")
    assertEquals(segment.name, "supervisor")
  }

  test("AddressSegment.Tool has correct name") {
    val segment: AddressSegment = AddressSegment.Tool("query")
    assertEquals(segment.name, "query")
  }

  test("InterruptSignal serialization roundtrip via upickle") {
    val signal: InterruptSignal = InterruptSignal.simple("test")
      .withAddress(List(AddressSegment.Agent("a"), AddressSegment.Tool("t")))
    val json: String = upickle.default.write(signal)
    val restored: InterruptSignal = upickle.default.read[InterruptSignal](json)
    assertEquals(restored.info, "test")
    assertEquals(restored.address.length, 2)
  }
