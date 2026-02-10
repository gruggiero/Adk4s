package org.adk4s.examples.structured.llm.classification

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.CategoryClassification

/**
 * Demonstrates StructuredLLM for category classification with typed output.
 *
 * Shows how to:
 * - Parse LLM responses into typed CategoryClassification case class
 * - Use Schema[A] typeclass wrapping Smithy schema
 * - Classify queries into categories (math/science/history/other)
 * - Return confidence scores alongside classifications
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object CategoryClassificationStructuredExample extends IOApp.Simple:

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
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Category Classification (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Math question
      _ <- ExampleUtils.printSubSection("1. Math Question Classification")
      query1 = "What is the square root of 144?"
      prompt1 = Prompt.simple(
        "You are a query classifier. Classify the user's query into one of these categories: math, science, history, or other. Return confidence as a number between 0 and 1.",
        s"Classify this query: $query1"
      )
      result1 <- structured.complete[CategoryClassification](prompt1)
      _ <- IO.println(s"   Query: $query1")
      _ <- IO.println(s"   Category: ${result1.category}")
      _ <- IO.println(s"   Confidence: ${result1.confidence}")

      // Example 2: Science question
      _ <- ExampleUtils.printSubSection("2. Science Question Classification")
      query2 = "How do plants perform photosynthesis?"
      prompt2 = Prompt.simple(
        "You are a query classifier. Classify the user's query into one of these categories: math, science, history, or other. Return confidence as a number between 0 and 1.",
        s"Classify this query: $query2"
      )
      result2 <- structured.complete[CategoryClassification](prompt2)
      _ <- IO.println(s"   Query: $query2")
      _ <- IO.println(s"   Category: ${result2.category}")
      _ <- IO.println(s"   Confidence: ${result2.confidence}")

      // Example 3: History question
      _ <- ExampleUtils.printSubSection("3. History Question Classification")
      query3 = "When did World War II end?"
      prompt3 = Prompt.simple(
        "You are a query classifier. Classify the user's query into one of these categories: math, science, history, or other. Return confidence as a number between 0 and 1.",
        s"Classify this query: $query3"
      )
      result3 <- structured.complete[CategoryClassification](prompt3)
      _ <- IO.println(s"   Query: $query3")
      _ <- IO.println(s"   Category: ${result3.category}")
      _ <- IO.println(s"   Confidence: ${result3.confidence}")

      // Example 4: Other category
      _ <- ExampleUtils.printSubSection("4. Other Category Classification")
      query4 = "What's the weather like today?"
      prompt4 = Prompt.simple(
        "You are a query classifier. Classify the user's query into one of these categories: math, science, history, or other. Return confidence as a number between 0 and 1.",
        s"Classify this query: $query4"
      )
      result4 <- structured.complete[CategoryClassification](prompt4)
      _ <- IO.println(s"   Query: $query4")
      _ <- IO.println(s"   Category: ${result4.category}")
      _ <- IO.println(s"   Confidence: ${result4.confidence}")

      _ <- IO.println("\nCategory classification example completed.")
    yield ()
