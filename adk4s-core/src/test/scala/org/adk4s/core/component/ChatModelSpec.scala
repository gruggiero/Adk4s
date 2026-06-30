package org.adk4s.core.component

import cats.effect.*
import cats.effect.unsafe.implicits.global
import munit.*
import org.llm4s.llmconnect.*
import org.llm4s.llmconnect.model.*
import org.llm4s.types.*

class ChatModelSpec extends CatsEffectSuite:

  test("Generate completion from conversation") {
    val mockClient = new ComponentMockLLMClient()
    val model = ChatModel.fromLlm4s[IO](mockClient)

    val conversation = Conversation(List(
      SystemMessage("You are a helpful assistant"),
      UserMessage("Hello")
    ))

    val result = model.generate(conversation).attempt.unsafeRunSync()

    assert(result.isRight, "Should complete successfully")
  }

  test("Stream completion chunks") {
    val mockClient = new ComponentMockLLMClient()
    val model = ChatModel.fromLlm4s[IO](mockClient)

    val conversation = Conversation(List(UserMessage("Tell me a story")))

    val chunks = model.stream(conversation).compile.toList.unsafeRunSync()

    assert(chunks.nonEmpty, "Should have chunks")
  }

  test("Stream content only") {
    val mockClient = new ComponentMockLLMClient()
    val model = ChatModel.fromLlm4s[IO](mockClient)

    val conversation = Conversation(List(UserMessage("Stream content")))

    val content = model.streamContent(conversation).compile.toList.unsafeRunSync()

    assert(content.nonEmpty, "Should have content")
    }

  test("Apply custom configuration") {
    val config = ChatModelConfig(temperature = Some(0.7), maxTokens = Some(1000))
    val mockClient = new ComponentMockLLMClient()
    val model = ChatModel.fromLlm4s[IO](mockClient, config)

    val conversation = Conversation(List(UserMessage("Test")))

    val _ = model.generate(conversation).attempt.unsafeRunSync()

    assert(mockClient.lastOptions.get.exists(_.temperature == 0.7), "Should use temperature")
    assert(mockClient.lastOptions.get.exists(_.maxTokens == Some(1000)), "Should use maxTokens")
  }

  test("Create ChatModel from LLM4S client") {
    val mockClient = new ComponentMockLLMClient()
    val model = ChatModel.fromLlm4s[IO](mockClient)

    val conversation = Conversation(List(UserMessage("Test")))

    val result = model.generate(conversation).attempt.unsafeRunSync()

    assert(result.isRight, "Should wrap client successfully")
  }

  test("WithConfig returns new model") {
    val baseConfig = ChatModelConfig(temperature = Some(0.5))
    val newConfig = ChatModelConfig(temperature = Some(0.9))
    val mockClient = new ComponentMockLLMClient()

    val baseModel = ChatModel.fromLlm4s[IO](mockClient, baseConfig)
    val newModel = baseModel.withConfig(newConfig)

    val conversation = Conversation(List(UserMessage("Test")))

    val _ = newModel.generate(conversation).attempt.unsafeRunSync()

    assert(mockClient.lastOptions.get.exists(_.temperature == 0.9), "Should use new config")
  }
