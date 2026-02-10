package org.adk4s.core.component

import cats.effect.*
import cats.effect.unsafe.implicits.global
import munit.*
import org.llm4s.llmconnect.*
import org.llm4s.llmconnect.model.*

class ToolCallingChatModelSpec extends CatsEffectSuite:

  test("Get tools list") {
    val mockClient = new ComponentMockLLMClient()
    val model = ToolCallingChatModel.fromLlm4s[IO](mockClient, Seq.empty)

    val tools = model.tools

    assertEquals(tools.length, 0, "Should have 0 tools")
  }

  test("Replace tools immutably") {
    val mockClient = new ComponentMockLLMClient()
    val model = ToolCallingChatModel.fromLlm4s[IO](mockClient, Seq.empty)

    val toolsBefore = model.tools
    val newModel = model.withTools(Seq.empty)

    val toolsAfterOriginal = model.tools
    val toolsAfterNew = newModel.tools

    assertEquals(toolsBefore.length, 0, "Original should have 0 tools")
    assertEquals(toolsAfterOriginal.length, 0, "Original should still have 0 tools")
    assertEquals(toolsAfterNew.length, 0, "New model should have 0 tools")
  }

  test("Add tools to existing model") {
    val mockClient = new ComponentMockLLMClient()
    val model = ToolCallingChatModel.fromLlm4s[IO](mockClient, Seq.empty)

    val newModel = model.addTools(Seq.empty: _*)

    assertEquals(model.tools.length, 0, "Original should have 0 tools")
    assertEquals(newModel.tools.length, 0, "New model should have 0 tools")
  }

  test("Generate without tools (base method)") {
    val mockClient = new ComponentMockLLMClient()
    val model = ToolCallingChatModel.fromLlm4s[IO](mockClient, Seq.empty)

    val conv = Conversation(List(UserMessage("Just say hello")))
    val result = model.generate(conv).attempt.unsafeRunSync()

    assert(result.isRight, "Should complete without tools")
  }

  test("Create ToolCallingChatModel from LLM4S client") {
    val mockClient = new ComponentMockLLMClient()
    val model = ToolCallingChatModel.fromLlm4s[IO](mockClient, Seq.empty)

    val conv = Conversation(List(UserMessage("Test")))
    val result = model.generate(conv).attempt.unsafeRunSync()

    assert(result.isRight, "Should wrap client successfully")
  }

class ComponentMockLLMClient extends LLMClient:
  var lastOptions: Option[CompletionOptions] = None

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    lastOptions = Some(options)
    Right(Completion(
      id = "mock-id",
      content = "Mock completion",
      created = System.currentTimeMillis(),
      model = "test-model",
      message = AssistantMessage(Some("Mock completion"))
    ))

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    lastOptions = Some(options)
    Right(Completion(
      id = "mock-id",
      content = "Mock chunk1",
      created = System.currentTimeMillis(),
      model = "test-model",
      message = AssistantMessage(Some("Mock chunk1"))
    ))
