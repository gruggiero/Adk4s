package org.adk4s.examples.structured.llm.chain

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{CategoryClassification, QueryClassification}

/**
 * Demonstrates composing multiple StructuredLLM parsers in sequence.
 *
 * Shows how to:
 * - Chain different parser types together
 * - Use output from one parser as context for the next
 * - Build multi-stage analysis pipelines with different schemas
 * - Compose classification and extraction stages
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object ChainCompositionStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[CategoryClassification] = Schema.instance(
    """structure CategoryClassification {
      |  @required
      |  category: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[CategoryClassification]])

  given Schema[QueryClassification] = Schema.instance(
    """structure QueryClassification {
      |  @required
      |  queryType: String
      |  @required
      |  intent: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[QueryClassification]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Chain Composition (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Two-stage classification
      _ <- ExampleUtils.printSubSection("1. Two-Stage Query Analysis")
      query1 = "What is the square root of 144?"

      // Stage 1: Classify category
      _ <- IO.println(s"   Query: $query1\n")
      _ <- IO.println("   Stage 1: Category classification...")
      categoryPrompt = Prompt.simple(
        "Classify the query category (math/science/history/other) with confidence.",
        s"Query: $query1"
      )
      categoryResult <- structured.complete[CategoryClassification](categoryPrompt)
      _ <- IO.println(s"   → Category: ${categoryResult.category} (confidence: ${categoryResult.confidence})")

      // Stage 2: Classify query type (using category result as context)
      _ <- IO.println("\n   Stage 2: Query type classification...")
      typePrompt = Prompt.simple(
        "Classify the query type (question/command/statement) and identify intent.",
        s"Query: $query1\nCategory: ${categoryResult.category}"
      )
      typeResult <- structured.complete[QueryClassification](typePrompt)
      _ <- IO.println(s"   → Type: ${typeResult.queryType}")
      _ <- IO.println(s"   → Intent: ${typeResult.intent}")

      // Example 2: Three-stage classification
      _ <- ExampleUtils.printSubSection("2. Science Question Analysis")
      query2 = "How do plants convert sunlight into energy?"

      _ <- IO.println(s"   Query: $query2\n")
      _ <- IO.println("   Stage 1: Category classification...")
      categoryPrompt2 = Prompt.simple(
        "Classify the query category (math/science/history/other) with confidence.",
        s"Query: $query2"
      )
      categoryResult2 <- structured.complete[CategoryClassification](categoryPrompt2)
      _ <- IO.println(s"   → Category: ${categoryResult2.category} (confidence: ${categoryResult2.confidence})")

      _ <- IO.println("\n   Stage 2: Query type classification...")
      typePrompt2 = Prompt.simple(
        "Classify the query type (question/command/statement) and identify intent.",
        s"Query: $query2\nCategory: ${categoryResult2.category}"
      )
      typeResult2 <- structured.complete[QueryClassification](typePrompt2)
      _ <- IO.println(s"   → Type: ${typeResult2.queryType}")
      _ <- IO.println(s"   → Intent: ${typeResult2.intent}")

      // Example 3: Command analysis
      _ <- ExampleUtils.printSubSection("3. Command Analysis")
      query3 = "Schedule a meeting for tomorrow at 3pm"

      _ <- IO.println(s"   Query: $query3\n")
      _ <- IO.println("   Stage 1: Category classification...")
      categoryPrompt3 = Prompt.simple(
        "Classify the query category (math/science/history/other) with confidence.",
        s"Query: $query3"
      )
      categoryResult3 <- structured.complete[CategoryClassification](categoryPrompt3)
      _ <- IO.println(s"   → Category: ${categoryResult3.category} (confidence: ${categoryResult3.confidence})")

      _ <- IO.println("\n   Stage 2: Query type classification...")
      typePrompt3 = Prompt.simple(
        "Classify the query type (question/command/statement) and identify intent.",
        s"Query: $query3\nCategory: ${categoryResult3.category}"
      )
      typeResult3 <- structured.complete[QueryClassification](typePrompt3)
      _ <- IO.println(s"   → Type: ${typeResult3.queryType}")
      _ <- IO.println(s"   → Intent: ${typeResult3.intent}")

      _ <- IO.println("\nChain composition example completed.")
    yield ()
