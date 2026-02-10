package org.adk4s.examples.structured.llm.extraction

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{SchemaExtraction, ExtractionMetadata}

/**
 * Demonstrates StructuredLLM for complex schema extraction with nested structures.
 *
 * Shows how to:
 * - Extract complex nested data structures from text
 * - Parse LLM responses with nested objects (SchemaExtraction with ExtractionMetadata)
 * - Handle lists and optional nested fields
 * - Work with rich metadata structures
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object SchemaExtractionStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[SchemaExtraction] = Schema.instance(
    """structure SchemaExtraction {
      |  @required
      |  title: String
      |  @required
      |  author: String
      |  @required
      |  tags: String[]
      |  metadata: ExtractionMetadata
      |}
      |
      |structure ExtractionMetadata {
      |  @required
      |  source: String
      |  @required
      |  date: String
      |  confidence: Double
      |}
      |
      |// "tags" is a JSON array of strings: ["tag1", "tag2", ...]""".stripMargin
  )(using summon[smithy4s.schema.Schema[SchemaExtraction]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new SchemaExtractionMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Complex Schema Extraction (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Article metadata extraction
      _ <- ExampleUtils.printSubSection("1. Article Metadata Extraction")
      article1 = """
        Title: "Introduction to Functional Programming"
        Author: Jane Smith
        Published on Tech Blog on 2024-03-15
        Tags: functional-programming, scala, fp, programming-paradigms
        This comprehensive guide introduces the core concepts of functional programming...
      """
      prompt1 = Prompt.simple(
        "Extract the article metadata including title, author, tags, and metadata (source, date, confidence). Return as structured data.",
        s"Article:\n$article1"
      )
      result1 <- structured.complete[SchemaExtraction](prompt1)
      _ <- IO.println(s"   Title: ${result1.title}")
      _ <- IO.println(s"   Author: ${result1.author}")
      _ <- IO.println(s"   Tags: ${result1.tags.mkString(", ")}")
      _ <- result1.metadata match
        case Some(meta) =>
          IO.println(s"   Source: ${meta.source}") *>
          IO.println(s"   Date: ${meta.date}") *>
          meta.confidence.fold(IO.unit)(conf => IO.println(s"   Confidence: $conf"))
        case None => IO.unit

      // Example 2: Research paper metadata
      _ <- ExampleUtils.printSubSection("2. Research Paper Metadata Extraction")
      paper = """
        "Deep Learning for Natural Language Processing"
        by Dr. Robert Chen
        Published in Journal of AI Research, January 2024
        Keywords: deep-learning, nlp, transformers, attention-mechanisms, bert
      """
      prompt2 = Prompt.simple(
        "Extract the paper metadata including title, author, tags, and metadata (source, date, confidence). Return as structured data.",
        s"Paper:\n$paper"
      )
      result2 <- structured.complete[SchemaExtraction](prompt2)
      _ <- IO.println(s"   Title: ${result2.title}")
      _ <- IO.println(s"   Author: ${result2.author}")
      _ <- IO.println(s"   Tags: ${result2.tags.mkString(", ")}")
      _ <- result2.metadata match
        case Some(meta) =>
          IO.println(s"   Source: ${meta.source}") *>
          IO.println(s"   Date: ${meta.date}") *>
          meta.confidence.fold(IO.unit)(conf => IO.println(s"   Confidence: $conf"))
        case None => IO.unit

      // Example 3: Blog post metadata
      _ <- ExampleUtils.printSubSection("3. Blog Post Metadata Extraction")
      blog = """
        Getting Started with Scala 3
        Written by Alex Johnson
        Posted on DevBlog - 2024-02-20
        Categories: scala, programming, tutorial, functional-programming
      """
      prompt3 = Prompt.simple(
        "Extract the blog post metadata including title, author, tags, and metadata (source, date, confidence). Return as structured data.",
        s"Blog post:\n$blog"
      )
      result3 <- structured.complete[SchemaExtraction](prompt3)
      _ <- IO.println(s"   Title: ${result3.title}")
      _ <- IO.println(s"   Author: ${result3.author}")
      _ <- IO.println(s"   Tags: ${result3.tags.mkString(", ")}")
      _ <- result3.metadata match
        case Some(meta) =>
          IO.println(s"   Source: ${meta.source}") *>
          IO.println(s"   Date: ${meta.date}") *>
          meta.confidence.fold(IO.unit)(conf => IO.println(s"   Confidence: $conf"))
        case None => IO.unit

      _ <- IO.println("\nComplex schema extraction example completed.")
    yield ()

/**
 * Mock LLM client that returns schema-compliant JSON for complex extraction.
 */
class SchemaExtractionMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
    }.lastOption.getOrElse("")

    val response: String =
      if lastUserMessage.contains("Functional Programming") && lastUserMessage.contains("Jane Smith") then
        """{
          |  "title": "Introduction to Functional Programming",
          |  "author": "Jane Smith",
          |  "tags": ["functional-programming", "scala", "fp", "programming-paradigms"],
          |  "metadata": {
          |    "source": "Tech Blog",
          |    "date": "2024-03-15",
          |    "confidence": 0.95
          |  }
          |}""".stripMargin
      else if lastUserMessage.contains("Deep Learning") && lastUserMessage.contains("Robert Chen") then
        """{
          |  "title": "Deep Learning for Natural Language Processing",
          |  "author": "Dr. Robert Chen",
          |  "tags": ["deep-learning", "nlp", "transformers", "attention-mechanisms", "bert"],
          |  "metadata": {
          |    "source": "Journal of AI Research",
          |    "date": "2024-01-01",
          |    "confidence": 0.98
          |  }
          |}""".stripMargin
      else if lastUserMessage.contains("Getting Started with Scala 3") then
        """{
          |  "title": "Getting Started with Scala 3",
          |  "author": "Alex Johnson",
          |  "tags": ["scala", "programming", "tutorial", "functional-programming"],
          |  "metadata": {
          |    "source": "DevBlog",
          |    "date": "2024-02-20",
          |    "confidence": 0.92
          |  }
          |}""".stripMargin
      else
        """{
          |  "title": "Unknown Article",
          |  "author": "Unknown",
          |  "tags": ["general"],
          |  "metadata": {
          |    "source": "unknown",
          |    "date": "2024-01-01"
          |  }
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
