package org.adk4s.core.streaming

import munit.CatsEffectSuite
import cats.effect.IO
import fs2.Stream
import org.llm4s.llmconnect.{LLMClient}
import org.llm4s.llmconnect.model.{Conversation, CompletionOptions, Completion, StreamedChunk, AssistantMessage}
import org.llm4s.error.NetworkError
import org.llm4s.error.LLMError
import org.adk4s.core.error.LlmCallError
import org.adk4s.core.types.ConversationConverter
import org.adk4s.structured.core.{Prompt, Message, Role}

class StreamingLLMClientTest extends CatsEffectSuite:

  def mockStreamingClient(
    chunks: List[StreamedChunk],
    completionResult: Either[LLMError, Completion]
  ): LLMClient =
    new LLMClient:
      override def complete(conversation: Conversation, options: CompletionOptions): Either[LLMError, Completion] =
        completionResult

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Either[LLMError, Completion] =
        chunks.foreach(onChunk)
        completionResult

      override def getContextWindow(): Int = 8192

      override def getReserveCompletion(): Int = 512

  def mockNonStreamingClient(completionResult: Either[LLMError, Completion]): LLMClient =
    new LLMClient:
      override def complete(conversation: Conversation, options: CompletionOptions): Either[LLMError, Completion] =
        completionResult

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Either[LLMError, Completion] =
        completionResult

      override def getContextWindow(): Int = 8192

      override def getReserveCompletion(): Int = 512

  test("Stream completion with chunks") {
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", Some(" World"), None, None)
    val completion = Completion("id", 0L, "Hello World", "model", AssistantMessage(Some("Hello World")), List.empty, None, None)
    val client = mockStreamingClient(List(chunk1, chunk2), Right(completion))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.stream(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 2)
    assertIO(result.map(_.head.content), Some("Hello"))
  }

  test("Stream content with real-time output") {
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", Some("World"), None, None)
    val completion = Completion("id", 0L, "Hello World", "model", AssistantMessage(Some("Hello World")), List.empty, None, None)
    val client = mockStreamingClient(List(chunk1, chunk2), Right(completion))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.streamContent(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 2)
    assertIO(result.map(_ == List("Hello", "World")), true)
  }

  test("Stream content filters empty strings") {
    val chunk1 = StreamedChunk("id1", Some("Hello"), None, None)
    val chunk2 = StreamedChunk("id2", None, None, None)
    val chunk3 = StreamedChunk("id3", Some("World"), None, None)
    val completion = Completion("id", 0L, "HelloWorld", "model", AssistantMessage(Some("HelloWorld")), List.empty, None, None)
    val client = mockStreamingClient(List(chunk1, chunk2, chunk3), Right(completion))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.streamContent(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 2)
    assertIO(result.map(_ == List("Hello", "World")), true)
  }

  test("Complete with non-streaming API") {
    val completion = Completion("id", 0L, "Response", "model", AssistantMessage(Some("Response")), List.empty, None, None)
    val client = mockStreamingClient(List(), Right(completion))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.complete(conv, CompletionOptions())
    assertIO(result.map(_.content), "Response")
  }

  test("Handle errors in streaming context") {
    val error = NetworkError("Connection failed", None, "test")
    val client = mockStreamingClient(List(), Left(error))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.stream(conv, CompletionOptions()).compile.toList.attempt
    assertIO(result.map(_.isLeft), true)
  }

  test("Use non-streaming API for fallback - stream") {
    val completion = Completion("id", 0L, "Response", "model", AssistantMessage(Some("Response")), List.empty, None, None)
    val client = mockNonStreamingClient(Right(completion))
    val streaming = StreamingLLMClient.fromNonStreaming(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.stream(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.head.content), Some("Response"))
  }

  test("Use non-streaming API for fallback - streamContent") {
    val completion = Completion("id", 0L, "Hello", "model", AssistantMessage(Some("Hello")), List.empty, None, None)
    val client = mockNonStreamingClient(Right(completion))
    val streaming = StreamingLLMClient.fromNonStreaming(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.streamContent(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_ == List("Hello")), true)
  }

  test("Use non-streaming API for fallback - complete") {
    val completion = Completion("id", 0L, "Response", "model", AssistantMessage(Some("Response")), List.empty, None, None)
    val client = mockNonStreamingClient(Right(completion))
    val streaming = StreamingLLMClient.fromNonStreaming(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.complete(conv, CompletionOptions())
    assertIO(result.map(_.content), "Response")
  }

  test("Non-streaming API handles errors in stream") {
    val error = NetworkError("Connection failed", None, "test")
    val client = mockNonStreamingClient(Left(error))
    val streaming = StreamingLLMClient.fromNonStreaming(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.stream(conv, CompletionOptions()).compile.toList.attempt
    assertIO(result.map(_.isLeft), true)
  }

  test("Non-streaming API handles errors in complete") {
    val error = NetworkError("Connection failed", None, "test")
    val client = mockNonStreamingClient(Left(error))
    val streaming = StreamingLLMClient.fromNonStreaming(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.complete(conv, CompletionOptions()).attempt
    assertIO(result.map(_.isLeft), true)
  }

  test("Stream completion with tool calls in chunks") {
    import org.llm4s.llmconnect.model.ToolCall
    import ujson.Obj
    val tool = ToolCall("call1", "func", Obj("arg" -> "val"))
    val chunk = StreamedChunk("id1", Some("text"), Some(tool), None)
    val completion = Completion(
      "id", 0L, "text", "model",
      AssistantMessage(Some("text")),
      List(tool),
      None, None
    )
    val client = mockStreamingClient(List(chunk), Right(completion))
    val streaming = StreamingLLMClient.fromClient(client)
    val prompt = Prompt(Vector(Message(Role.User, "Test")))
    val conv = ConversationConverter.toConversation(prompt)
    val result = streaming.stream(conv, CompletionOptions()).compile.toList
    assertIO(result.map(_.size), 1)
    assertIO(result.map(_.head.toolCall), Some(tool))
  }
