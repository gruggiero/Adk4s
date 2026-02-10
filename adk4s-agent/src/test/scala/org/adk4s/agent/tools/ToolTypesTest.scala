package org.adk4s.agent.tools

import munit.CatsEffectSuite
import cats.effect.IO
import org.llm4s.llmconnect.model.ToolCall
import org.adk4s.core.tools.{ToolInput, ToolOutput, ToolExecutionResult, ToolExecutionFailure}

class ToolTypesTest extends CatsEffectSuite:

  test("ToolInput.fromToolCall converts correctly") {
    val toolCall = ToolCall(
      "call_123",
      "get_weather",
      ujson.Obj("location" -> ujson.Str("NYC"))
    )
    val input = ToolInput.fromToolCall(toolCall)

    assertEquals(input.name, "get_weather")
    assert(input.arguments.contains("NYC"))
    assertEquals(input.callId, "call_123")
  }

  test("ToolInput.fromToolCalls converts multiple calls") {
    val toolCalls = List(
      ToolCall("call_1", "get_weather", ujson.Obj("location" -> ujson.Str("NYC"))),
      ToolCall("call_2", "calculate", ujson.Obj("expression" -> ujson.Str("1+1")))
    )
    val inputs = ToolInput.fromToolCalls(toolCalls)

    assertEquals(inputs.length, 2)
    assertEquals(inputs.head.name, "get_weather")
    assertEquals(inputs(1).name, "calculate")
  }

  test("ToolOutput.toLlm4sMessage creates correct message") {
    val output = ToolOutput("get_weather", "Sunny, 25C", "call_123")
    val message = output.toLlm4sMessage

    assertEquals(message.content, "Sunny, 25C")
    assertEquals(message.toolCallId, "call_123")
  }

  test("ToolExecutionResult.toLlm4sMessages converts all outputs") {
    val result = ToolExecutionResult(
      outputs = List(
        ToolOutput("tool1", "result1", "call_1"),
        ToolOutput("tool2", "result2", "call_2")
      )
    )
    val messages = result.toLlm4sMessages(includeErrors = true)

    assertEquals(messages.length, 2)
    assertEquals(messages.head.content, "result1")
    assertEquals(messages(1).content, "result2")
  }

  test("ToolExecutionResult.allSucceeded returns true when no failures") {
    val result = ToolExecutionResult(
      outputs = List(ToolOutput("tool1", "result1", "call_1"))
    )

    assert(result.allSucceeded)
  }

  test("ToolExecutionResult.allSucceeded returns false when failures exist") {
    val result = ToolExecutionResult(
      outputs = List(ToolOutput("tool1", "result1", "call_1")),
      failedTools = List(ToolExecutionFailure(
        ToolInput("tool2", "{}", "call_2"),
        new RuntimeException("error")
      ))
    )

    assert(!result.allSucceeded)
  }
