package org.adk4s.core.component

import org.llm4s.llmconnect.*
import org.llm4s.llmconnect.model.*
import org.llm4s.types.*

class ComponentMockLLMClient extends LLMClient:
  var lastOptions: Option[CompletionOptions] = None

  def getContextWindow(): Int = 4096

  def getReserveCompletion(): Int = 1000

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
    val chunk = StreamedChunk(
      id = "mock-id",
      content = Some("Mock chunk1"),
      finishReason = None
    )
    onChunk(chunk)
    Right(Completion(
      id = "mock-id",
      content = "Mock chunk1",
      created = System.currentTimeMillis(),
      model = "test-model",
      message = AssistantMessage(Some("Mock chunk1"))
    ))
