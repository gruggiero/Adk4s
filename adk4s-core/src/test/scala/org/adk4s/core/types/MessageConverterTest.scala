package org.adk4s.core.types

import munit.CatsEffectSuite
import org.adk4s.structured.core.{ Message as AdkMessage, Role as AdkRole }
import org.llm4s.llmconnect.model.{ SystemMessage, UserMessage, AssistantMessage, ToolMessage, ToolCall }
import org.adk4s.core.types.MessageConverter.asLlm4s
import org.adk4s.core.types.MessageConverter.asAdk
import ujson.Obj

class MessageConverterTest extends CatsEffectSuite:

  test("convert System message from ADK to LLM4S") {
    val adkMsg   = AdkMessage(AdkRole.System, "You are a helpful assistant")
    val llm4sMsg = adkMsg.asLlm4s
    assertEquals(llm4sMsg, SystemMessage("You are a helpful assistant"))
  }

  test("convert User message from ADK to LLM4S") {
    val adkMsg   = AdkMessage(AdkRole.User, "Hello, AI!")
    val llm4sMsg = adkMsg.asLlm4s
    assertEquals(llm4sMsg, UserMessage("Hello, AI!"))
  }

  test("convert Assistant message from ADK to LLM4S") {
    val adkMsg   = AdkMessage(AdkRole.Assistant, "I can help you")
    val llm4sMsg = adkMsg.asLlm4s
    assertEquals(llm4sMsg, AssistantMessage(contentOpt = Some("I can help you"), toolCalls = Seq.empty))
  }

  test("convert Assistant message with tool calls from LLM4S to ADK") {
    val toolCall = ToolCall("call_123", "search", Obj("query" -> "test"))
    val llm4sMsg = AssistantMessage(contentOpt = Some("Let me use a tool"), toolCalls = Seq(toolCall))
    val adkMsg   = llm4sMsg.asAdk
    assertEquals(adkMsg.role, AdkRole.Assistant)
    assertEquals(adkMsg.content, "Let me use a tool")
  }

  test("convert Tool message from ADK to LLM4S") {
    val adkMsg   = AdkMessage(AdkRole.Tool, "Result")
    val llm4sMsg = adkMsg.asLlm4s
    assertEquals(llm4sMsg, ToolMessage("Result", toolCallId = "unknown"))
  }

  test("convert Tool message from LLM4S to ADK") {
    val llm4sMsg = ToolMessage("Result", toolCallId = "call_123")
    val adkMsg   = llm4sMsg.asAdk
    assertEquals(adkMsg.role, AdkRole.Tool)
    assertEquals(adkMsg.content, "Result")
  }

  test("round-trip conversion preserves data for System message") {
    val original  = AdkMessage(AdkRole.System, "System prompt")
    val converted = original.asLlm4s.asAdk
    assertEquals(converted.role, original.role)
    assertEquals(converted.content, original.content)
  }

  test("round-trip conversion preserves data for User message") {
    val original  = AdkMessage(AdkRole.User, "User input")
    val converted = original.asLlm4s.asAdk
    assertEquals(converted.role, original.role)
    assertEquals(converted.content, original.content)
  }

  test("round-trip conversion preserves data for Assistant message") {
    val original  = AdkMessage(AdkRole.Assistant, "Assistant response")
    val converted = original.asLlm4s.asAdk
    assertEquals(converted.role, original.role)
    assertEquals(converted.content, original.content)
  }

  test("round-trip conversion preserves data for Tool message") {
    val original  = AdkMessage(AdkRole.Tool, "Tool result")
    val converted = original.asLlm4s.asAdk
    assertEquals(converted.role, original.role)
    assertEquals(converted.content, original.content)
  }

  test("convert empty content Assistant message from LLM4S to ADK") {
    val llm4sMsg = AssistantMessage(contentOpt = None, toolCalls = Seq.empty)
    val adkMsg   = llm4sMsg.asAdk
    assertEquals(adkMsg.role, AdkRole.Assistant)
    assertEquals(adkMsg.content, "")
  }

  test("convert Assistant message from LLM4S to ADK") {
    val llm4sMsg = AssistantMessage(contentOpt = Some("Hello"), toolCalls = Seq.empty)
    val adkMsg   = llm4sMsg.asAdk
    assertEquals(adkMsg, AdkMessage(AdkRole.Assistant, "Hello"))
  }

  test("convert Assistant message with only tool calls from LLM4S to ADK") {
    val toolCall = ToolCall("call_123", "function", Obj("arg" -> "value"))
    val llm4sMsg = AssistantMessage(contentOpt = None, toolCalls = Seq(toolCall))
    val adkMsg   = llm4sMsg.asAdk
    assertEquals(adkMsg.role, AdkRole.Assistant)
    assertEquals(adkMsg.content, "")
  }
