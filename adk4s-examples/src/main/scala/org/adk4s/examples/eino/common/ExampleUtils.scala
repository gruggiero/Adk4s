package org.adk4s.examples.eino.common

import cats.effect.IO
import org.adk4s.core.component.ChatModel
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.model.ModelRegistryService

import java.util.UUID

object ExampleUtils:

  def printSection(title: String): IO[Unit] =
    IO.println(s"\n${"=" * 60}") *>
      IO.println(s"  $title") *>
      IO.println(s"${"=" * 60}")

  def printSubSection(title: String): IO[Unit] =
    IO.println(s"\n--- $title ---")

  /**
   * Creates an LLMClient for structured examples.
   * Uses real LLM if OPENAI_API_KEY is set, otherwise returns a mock client
   * that should be overridden by the example's own mock implementation.
   */
  def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)
    apiKey match
      case Some(key) =>
        val model: String = Option(System.getenv("LLM_MODEL")).filter(_.nonEmpty).getOrElse("gpt-4o-mini")
        val baseUrl: String = Option(System.getenv("OPENAI_BASE_URL")).filter(_.nonEmpty).getOrElse("https://api.openai.com/v1")
        IO.println(s"[Using real LLM: $model]") *>
          IO.fromEither(
            ModelRegistryService
              .default()
              .left
              .map(err => new RuntimeException(s"Failed to load model registry: ${err.message}"))
          ).flatMap { (registry: ModelRegistryService) =>
            given ContextWindowResolver = ContextWindowResolver(registry)
            val config: OpenAIConfig = OpenAIConfig.fromValues(model, key, None, baseUrl)
            IO.fromEither(
              LLMConnect
                .getClient(config)(using registry)
                .left
                .map(err => new RuntimeException(s"Failed to create LLM client: ${err.message}"))
            )
          }
      case None =>
        IO.println("[Using MockLLMClient — set OPENAI_API_KEY for real LLM]") *>
          IO.raiseError(new UnsupportedOperationException(
            "No API key provided and no mock client available. Each example should handle the None case."
          ))

  def createChatModel: IO[ChatModel[IO]] =
    val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)
    apiKey match
      case Some(key) =>
        val model: String = Option(System.getenv("LLM_MODEL")).filter(_.nonEmpty).getOrElse("gpt-4o-mini")
        val baseUrl: String = Option(System.getenv("OPENAI_BASE_URL")).filter(_.nonEmpty).getOrElse("https://api.openai.com/v1")
        IO.println(s"[Using real LLM: $model via $baseUrl]") *>
          IO.fromEither(
            ModelRegistryService
              .default()
              .left
              .map(err => new RuntimeException(s"Failed to load model registry: ${err.message}"))
          ).flatMap { (registry: ModelRegistryService) =>
            given ContextWindowResolver = ContextWindowResolver(registry)
            val config: OpenAIConfig = OpenAIConfig.fromValues(model, key, None, baseUrl)
            IO.fromEither(
              LLMConnect
                .getClient(config)(using registry)
                .left
                .map(err => new RuntimeException(s"Failed to create LLM client: ${err.message}"))
            ).map((client: org.llm4s.llmconnect.LLMClient) => ChatModel.fromLlm4s[IO](client))
          }
      case None =>
        IO.println("[Using MockChatModel — set OPENAI_API_KEY for real LLM]") *>
          IO.pure(new MockChatModel())

class MockChatModel extends ChatModel[IO]:
  def generate(conversation: Conversation): IO[Completion] =
    IO.delay {
      val lastUserMessage: String = conversation.messages.collect {
        case msg: UserMessage => msg.content
      }.lastOption.getOrElse("")

      val systemContent: String = conversation.messages.collect {
        case msg: SystemMessage => msg.content
      }.headOption.getOrElse("")

      val response: String =
        if systemContent.contains("cat") then
          "Meow! Cats purr when they are content and relaxed. It's a self-soothing behavior."
        else if systemContent.contains("dog") then
          "Woof! Dogs bark to communicate — excitement, alerting, or seeking attention."
        else if systemContent.contains("critic") then
          "The writing is good but could use more specific examples and a stronger conclusion."
        else if systemContent.contains("writer") then
          "Here is a revised draft incorporating the feedback with concrete examples."
        else if lastUserMessage.contains("weather") then
          "The weather today is sunny with a high of 72°F."
        else if lastUserMessage.contains("plan") then
          "Step 1: Research the topic. Step 2: Create an outline. Step 3: Write the draft."
        else
          s"I received your message: ${lastUserMessage.take(50)}"

      val assistantMessage: AssistantMessage = AssistantMessage(response)
      Completion(
        id = UUID.randomUUID().toString,
        created = System.currentTimeMillis(),
        content = response,
        model = "mock-model",
        message = assistantMessage
      )
    }

  def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] =
    fs2.Stream.eval(generate(conversation)).flatMap { (completion: Completion) =>
      val words: List[String] = completion.content.split(" ").toList
      fs2.Stream.emits(words).map { (word: String) =>
        StreamedChunk(
          id = completion.id,
          content = Some(word + " "),
          toolCall = None,
          finishReason = None,
          thinkingDelta = None
        )
      } ++ fs2.Stream.emit(StreamedChunk(
        id = completion.id,
        content = None,
        toolCall = None,
        finishReason = Some("stop"),
        thinkingDelta = None
      ))
    }

  def streamContent(conversation: Conversation): fs2.Stream[IO, String] =
    stream(conversation)
      .evalMap((chunk: StreamedChunk) => IO.pure(chunk.content.getOrElse("")))
      .filter(_.nonEmpty)

  def withConfig(config: org.adk4s.core.component.ChatModelConfig): ChatModel[IO] = this
