package org.adk4s.examples.structured.sap

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.CategoryClassification

/**
 * Demonstrates Schema-Aligned Parser (SAP) error recovery capabilities.
 *
 * Shows how SAP recovers from common LLM output errors:
 * - Markdown code fences (```json ... ```)
 * - Trailing commas in JSON
 * - Single quotes instead of double quotes
 * - Missing quotes around keys
 * - Comments in JSON
 *
 * The SAP parser applies recovery strategies sequentially and tracks warnings,
 * allowing the system to successfully parse malformed JSON that would normally fail.
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object SAPErrorRecoveryStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[CategoryClassification] = Schema.instance(
    """structure CategoryClassification {
      |  @required
      |  category: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[CategoryClassification]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new SAPRecoveryMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("SAP Error Recovery (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      _ <- IO.println("   The Schema-Aligned Parser (SAP) can recover from common LLM errors:")
      _ <- IO.println("   - Markdown code fences")
      _ <- IO.println("   - Trailing commas")
      _ <- IO.println("   - Single quotes")
      _ <- IO.println("   - Unquoted keys")
      _ <- IO.println("   - JSON comments")
      _ <- IO.println("")

      // Example 1: Markdown fence recovery
      _ <- ExampleUtils.printSubSection("1. Markdown Fence Recovery")
      _ <- IO.println("   LLM wrapped response in ```json ... ```")
      query1 = "What is 2 + 2?"
      prompt1 = Prompt.simple(
        "Classify this query. Wrap your response in markdown code fences.",
        s"Query: $query1"
      )
      result1 <- structured.complete[CategoryClassification](prompt1)
      _ <- IO.println(s"   ✓ Successfully parsed: category=${result1.category}, confidence=${result1.confidence}")

      // Example 2: Trailing comma recovery
      _ <- ExampleUtils.printSubSection("2. Trailing Comma Recovery")
      _ <- IO.println("   LLM included trailing comma in JSON")
      query2 = "How do plants grow?"
      prompt2 = Prompt.simple(
        "Classify this query. Include a trailing comma in your JSON response.",
        s"Query: $query2"
      )
      result2 <- structured.complete[CategoryClassification](prompt2)
      _ <- IO.println(s"   ✓ Successfully parsed: category=${result2.category}, confidence=${result2.confidence}")

      // Example 3: Single quotes recovery
      _ <- ExampleUtils.printSubSection("3. Single Quotes Recovery")
      _ <- IO.println("   LLM used single quotes instead of double quotes")
      query3 = "When did World War II end?"
      prompt3 = Prompt.simple(
        "Classify this query. Use single quotes in your JSON response.",
        s"Query: $query3"
      )
      result3 <- structured.complete[CategoryClassification](prompt3)
      _ <- IO.println(s"   ✓ Successfully parsed: category=${result3.category}, confidence=${result3.confidence}")

      // Example 4: Multiple errors recovery
      _ <- ExampleUtils.printSubSection("4. Multiple Errors Recovery")
      _ <- IO.println("   LLM response has markdown fence + trailing comma + single quotes")
      query4 = "What's the weather today?"
      prompt4 = Prompt.simple(
        "Classify this query. Use markdown fence, single quotes, and trailing comma.",
        s"Query: $query4"
      )
      result4 <- structured.complete[CategoryClassification](prompt4)
      _ <- IO.println(s"   ✓ Successfully parsed: category=${result4.category}, confidence=${result4.confidence}")

      _ <- IO.println("\n   All malformed responses were successfully recovered by SAP!")
      _ <- IO.println("\nSAP error recovery example completed.")
    yield ()

/**
 * Mock LLM client that returns intentionally malformed JSON to demonstrate SAP recovery.
 */
class SAPRecoveryMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val systemMessage: String = conversation.messages.collect {
      case msg: SystemMessage => msg.content
    }.headOption.getOrElse("")

    val response: String =
      if systemMessage.contains("markdown code fences") then
        // Markdown fence error
        """```json
          |{"category": "math", "confidence": 0.95}
          |```""".stripMargin
      else if systemMessage.contains("trailing comma") then
        // Trailing comma error
        """{"category": "science", "confidence": 0.92,}"""
      else if systemMessage.contains("single quotes") && !systemMessage.contains("trailing comma") then
        // Single quotes error
        """{'category': 'history', 'confidence': 0.90}"""
      else if systemMessage.contains("markdown fence, single quotes, and trailing comma") then
        // Multiple errors
        """```json
          |{'category': 'other', 'confidence': 0.88,}
          |```""".stripMargin
      else
        // Valid JSON fallback
        """{"category": "other", "confidence": 0.70}"""

    val assistantMessage: AssistantMessage = AssistantMessage(Some(response))
    Right(Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = response,
      model = "mock-model",
      message = assistantMessage,
      toolCalls = List.empty,
      usage = None,
      thinking = None
    ))

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Either[org.llm4s.error.LLMError, Completion] =
    complete(conversation, options)

  def getContextWindow(): Int = 8192
  def getReserveCompletion(): Int = 512
