package org.adk4s.core.interrupt

import cats.effect.IO
import munit.CatsEffectSuite

class AgentEventEmitterTest extends CatsEffectSuite:

  test("emit and subscribe delivers events") {
    for
      emitter <- AgentEventEmitter.create()
      event = AgentEvent.MessageOutput(RunPath.of("agent"), "hello", "assistant")
      _ <- emitter.emit(event)
      _ <- emitter.complete
      events <- emitter.subscribe.compile.toList
    yield
      assertEquals(events.length, 1)
      events.headOption.getOrElse(fail("expected non-empty list")) match
        case msg: AgentEvent.MessageOutput =>
          assertEquals(msg.message, "hello")
        case other =>
          fail(s"Expected MessageOutput, got $other")
  }

  test("scoped emitter prepends RunStep to events") {
    for
      emitter <- AgentEventEmitter.create()
      scoped = emitter.scoped(RunStep("parent"))
      event = AgentEvent.MessageOutput(RunPath.of("child"), "msg", "assistant")
      _ <- scoped.emit(event)
      _ <- emitter.complete
      events <- emitter.subscribe.compile.toList
    yield
      assertEquals(events.length, 1)
      val resultPath: String = events.headOption.getOrElse(fail("expected non-empty list")).runPath.show
      assertEquals(resultPath, "parent > child")
  }

  test("multiple events are delivered in order") {
    for
      emitter <- AgentEventEmitter.create()
      _ <- emitter.emit(AgentEvent.TokenDelta(RunPath.empty, "a"))
      _ <- emitter.emit(AgentEvent.TokenDelta(RunPath.empty, "b"))
      _ <- emitter.emit(AgentEvent.TokenDelta(RunPath.empty, "c"))
      _ <- emitter.complete
      events <- emitter.subscribe.compile.toList
    yield
      assertEquals(events.length, 3)
      val deltas: List[String] = events.collect { case td: AgentEvent.TokenDelta => td.delta }
      assertEquals(deltas, List("a", "b", "c"))
  }

  test("complete terminates the stream") {
    for
      emitter <- AgentEventEmitter.create()
      _ <- emitter.emit(AgentEvent.MessageOutput(RunPath.empty, "one", "user"))
      _ <- emitter.complete
      events <- emitter.subscribe.compile.toList
    yield assertEquals(events.length, 1)
  }
