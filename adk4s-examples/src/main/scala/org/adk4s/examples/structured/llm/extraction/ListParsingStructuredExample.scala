package org.adk4s.examples.structured.llm.extraction

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{ListParsing, ListItem}

/**
 * Demonstrates StructuredLLM for parsing lists from text.
 *
 * Shows how to:
 * - Parse various list formats (numbered, bulleted, comma-separated)
 * - Extract structured lists from unstructured text
 * - Parse LLM responses into ListParsing with List[ListItem]
 * - Handle different list styles and formats
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object ListParsingStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[ListParsing] = Schema.instance(
    """structure ListParsing {
      |  @required
      |  items: ListItem[]
      |}
      |
      |structure ListItem {
      |  @required
      |  index: Integer
      |  @required
      |  content: String
      |}
      |
      |// "items" is a JSON array: [{"index":1,"content":"..."}, ...]""".stripMargin
  )(using summon[smithy4s.schema.Schema[ListParsing]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new ListParsingMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("List Parsing (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Numbered list
      _ <- ExampleUtils.printSubSection("1. Numbered List Parsing")
      text1 = "Shopping list: 1. Milk, 2. Bread, 3. Eggs, 4. Butter, 5. Cheese"
      prompt1 = Prompt.simple(
        "Parse the list from the text and return each item with its index and content.",
        s"Text: $text1"
      )
      result1 <- structured.complete[ListParsing](prompt1)
      _ <- IO.println(s"   Text: $text1")
      _ <- IO.println(s"   Parsed ${result1.items.size} items:")
      _ <- result1.items.traverse_ { item =>
        IO.println(s"     ${item.index}. ${item.content}")
      }

      // Example 2: Bulleted list
      _ <- ExampleUtils.printSubSection("2. Bulleted List Parsing")
      text2 = "Key features: • Real-time collaboration • End-to-end encryption • Cross-platform support • Offline mode"
      prompt2 = Prompt.simple(
        "Parse the list from the text and return each item with its index and content.",
        s"Text: $text2"
      )
      result2 <- structured.complete[ListParsing](prompt2)
      _ <- IO.println(s"   Text: $text2")
      _ <- IO.println(s"   Parsed ${result2.items.size} items:")
      _ <- result2.items.traverse_ { item =>
        IO.println(s"     ${item.index}. ${item.content}")
      }

      // Example 3: Comma-separated list embedded in sentence
      _ <- ExampleUtils.printSubSection("3. Embedded List Parsing")
      text3 = "The project requires Python, TypeScript, React, and PostgreSQL as core technologies."
      prompt3 = Prompt.simple(
        "Parse the list from the text and return each item with its index and content.",
        s"Text: $text3"
      )
      result3 <- structured.complete[ListParsing](prompt3)
      _ <- IO.println(s"   Text: $text3")
      _ <- IO.println(s"   Parsed ${result3.items.size} items:")
      _ <- result3.items.traverse_ { item =>
        IO.println(s"     ${item.index}. ${item.content}")
      }

      // Example 4: Hyphenated list
      _ <- ExampleUtils.printSubSection("4. Hyphenated List Parsing")
      text4 = "Team members: - Alice (Frontend) - Bob (Backend) - Carol (DevOps) - David (QA)"
      prompt4 = Prompt.simple(
        "Parse the list from the text and return each item with its index and content.",
        s"Text: $text4"
      )
      result4 <- structured.complete[ListParsing](prompt4)
      _ <- IO.println(s"   Text: $text4")
      _ <- IO.println(s"   Parsed ${result4.items.size} items:")
      _ <- result4.items.traverse_ { item =>
        IO.println(s"     ${item.index}. ${item.content}")
      }

      _ <- IO.println("\nList parsing example completed.")
    yield ()

/**
 * Mock LLM client that returns schema-compliant JSON for list parsing.
 */
class ListParsingMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
    }.lastOption.getOrElse("")

    val response: String =
      if lastUserMessage.contains("Shopping list") then
        """{
          |  "items": [
          |    {"index": 1, "content": "Milk"},
          |    {"index": 2, "content": "Bread"},
          |    {"index": 3, "content": "Eggs"},
          |    {"index": 4, "content": "Butter"},
          |    {"index": 5, "content": "Cheese"}
          |  ]
          |}""".stripMargin
      else if lastUserMessage.contains("Key features") then
        """{
          |  "items": [
          |    {"index": 1, "content": "Real-time collaboration"},
          |    {"index": 2, "content": "End-to-end encryption"},
          |    {"index": 3, "content": "Cross-platform support"},
          |    {"index": 4, "content": "Offline mode"}
          |  ]
          |}""".stripMargin
      else if lastUserMessage.contains("core technologies") then
        """{
          |  "items": [
          |    {"index": 1, "content": "Python"},
          |    {"index": 2, "content": "TypeScript"},
          |    {"index": 3, "content": "React"},
          |    {"index": 4, "content": "PostgreSQL"}
          |  ]
          |}""".stripMargin
      else if lastUserMessage.contains("Team members") then
        """{
          |  "items": [
          |    {"index": 1, "content": "Alice (Frontend)"},
          |    {"index": 2, "content": "Bob (Backend)"},
          |    {"index": 3, "content": "Carol (DevOps)"},
          |    {"index": 4, "content": "David (QA)"}
          |  ]
          |}""".stripMargin
      else
        """{
          |  "items": [
          |    {"index": 1, "content": "Item 1"},
          |    {"index": 2, "content": "Item 2"}
          |  ]
          |}""".stripMargin

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
