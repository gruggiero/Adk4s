package org.adk4s.orchestration.agent

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.{Agent, AgentTool, AgentToolConfig}
import org.llm4s.llmconnect.model.{AssistantMessage, Message, UserMessage}

class AgentToolEnhancedTest extends CatsEffectSuite:

  private def mockAgent(response: String): Agent =
    new Agent:
      val name: String = "test-agent"
      val description: String = "A test agent"
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        IO.pure(AssistantMessage(contentOpt = Some(response), toolCalls = Seq.empty))

  test("fromReactAgent alias works") {
    for
      tool <- AgentTool.fromReactAgent(mockAgent("Hello"))
      result <- tool.run(ujson.Obj("request" -> "Hi"))
    yield
      result match
        case ujson.Str(s) => assertEquals(s, "Hello")
        case other        => fail(s"Expected ujson.Str, got $other")
  }

  test("fromFunction creates working agent tool") {
    val fn: List[Message] => IO[String] = { messages =>
      val userMsg: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")
      IO.pure(s"Processed: $userMsg")
    }

    for
      tool <- AgentTool.fromFunction("processor", "Processes messages", fn)
      result <- tool.run(ujson.Obj("request" -> "test input"))
    yield
      assertEquals(tool.info.name, "processor")
      assertEquals(tool.info.description, "Processes messages")
      result match
        case ujson.Str(s) =>
          assert(s.contains("Processed:"))
          assert(s.contains("test input"))
        case other => fail(s"Expected ujson.Str, got $other")
  }

  test("custom input schema via config") {
    val customSchema: ujson.Value = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "query" -> ujson.Obj("type" -> "string"),
        "limit" -> ujson.Obj("type" -> "integer")
      ),
      "required" -> ujson.Arr("query")
    )
    val config: AgentToolConfig = AgentToolConfig.withInputSchema(customSchema)

    for
      tool <- AgentTool.fromAgent(mockAgent("result"), config)
    yield
      val schema: ujson.Value = tool.info.parameters
      assertEquals(schema("type").str, "object")
      assert(schema("properties").obj.contains("query"))
      assert(schema("properties").obj.contains("limit"))
  }

  test("default input schema has request field") {
    for
      tool <- AgentTool.fromAgent(mockAgent("test"))
    yield
      val schema: ujson.Value = tool.info.parameters
      assertEquals(schema("type").str, "object")
      assert(schema("properties").obj.contains("request"))
      assertEquals(schema("required").arr.headOption.getOrElse(fail("expected non-empty list")).str, "request")
  }
