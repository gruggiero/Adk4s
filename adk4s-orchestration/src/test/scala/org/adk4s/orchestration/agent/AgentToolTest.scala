package org.adk4s.orchestration.agent

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.Agent
import org.adk4s.core.component.AgentTool
import org.adk4s.core.component.AgentToolConfig
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AddressSegment, InterruptSignal}
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Message

class AgentToolTest extends CatsEffectSuite:

  private def mockAgent(response: String): Agent =
    new Agent:
      val name: String = "test-agent"
      val description: String = "A test agent"
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        IO.pure(AssistantMessage(contentOpt = Some(response), toolCalls = Seq.empty))

  private def interruptingAgent(info: String): Agent =
    new Agent:
      val name: String = "interrupting-agent"
      val description: String = "An agent that interrupts"
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        IO.raiseError(AgentInterruptedException(InterruptSignal.simple(info)))

  test("AgentTool returns inner agent result as JSON string") {
    for
      tool <- AgentTool.fromAgent(mockAgent("Hello from inner agent"))
      result <- tool.run(ujson.Obj("request" -> "Hi"))
    yield
      result match
        case ujson.Str(s) => assertEquals(s, "Hello from inner agent")
        case other        => fail(s"Expected ujson.Str, got $other")
  }

  test("AgentTool info uses inner agent name and description") {
    for
      tool <- AgentTool.fromAgent(mockAgent("test"))
    yield
      assertEquals(tool.info.name, "test-agent")
      assertEquals(tool.info.description, "A test agent")
  }

  test("AgentTool propagates interrupt from inner agent") {
    for
      tool <- AgentTool.fromAgent(interruptingAgent("Need approval"))
      result <- tool.run(ujson.Obj("request" -> "Do something")).attempt
    yield
      result match
        case Left(e: AgentInterruptedException) =>
          // AgentTool now wraps as Composite
          e.signal match
            case composite: InterruptSignal.Composite =>
              assertEquals(composite.info, "AgentTool 'interrupting-agent' interrupted")
              assertEquals(composite.address, List(AddressSegment.Agent("interrupting-agent")))
              assertEquals(composite.children.length, 1)
              assertEquals(composite.children.headOption.getOrElse(fail("expected non-empty list")).info, "Need approval")
            case other =>
              fail(s"Expected Composite interrupt, got $other")
        case other =>
          fail(s"Expected AgentInterruptedException, got $other")
  }

  test("AgentTool clears state on normal completion") {
    for
      tool <- AgentTool.fromAgent(mockAgent("done"))
      _ <- tool.run(ujson.Obj("request" -> "first"))
      // Second invocation should also work (no stale state)
      result <- tool.run(ujson.Obj("request" -> "second"))
    yield
      result match
        case ujson.Str(s) => assertEquals(s, "done")
        case other        => fail(s"Expected ujson.Str, got $other")
  }

  test("AgentTool extracts request from string argument") {
    for
      tool <- AgentTool.fromAgent(mockAgent("ok"))
      result <- tool.run(ujson.Str("plain text request"))
    yield
      result match
        case ujson.Str(s) => assertEquals(s, "ok")
        case other        => fail(s"Expected ujson.Str, got $other")
  }
