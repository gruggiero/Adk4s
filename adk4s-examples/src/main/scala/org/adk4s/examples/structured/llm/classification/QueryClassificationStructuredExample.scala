package org.adk4s.examples.structured.llm.classification

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.QueryClassification

/**
 * Demonstrates StructuredLLM for query type classification with typed output.
 *
 * Shows how to:
 * - Classify user queries into types (question/command/statement)
 * - Extract query intent alongside classification
 * - Parse LLM responses into QueryClassification case class
 * - Handle natural language understanding tasks
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object QueryClassificationStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
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
      _ <- ExampleUtils.printSection("Query Classification (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Question
      _ <- ExampleUtils.printSubSection("1. Question Classification")
      query1 = "What is the capital of France?"
      prompt1 = Prompt.simple(
        "You are a query classifier. Classify the user's query as one of: question, command, statement. Also identify the intent (what the user wants to achieve).",
        s"Classify this query: \"$query1\""
      )
      result1 <- structured.complete[QueryClassification](prompt1)
      _ <- IO.println(s"   Query: $query1")
      _ <- IO.println(s"   Type: ${result1.queryType}")
      _ <- IO.println(s"   Intent: ${result1.intent}")

      // Example 2: Command
      _ <- ExampleUtils.printSubSection("2. Command Classification")
      query2 = "Please schedule a meeting for tomorrow at 3pm"
      prompt2 = Prompt.simple(
        "You are a query classifier. Classify the user's query as one of: question, command, statement. Also identify the intent (what the user wants to achieve).",
        s"Classify this query: \"$query2\""
      )
      result2 <- structured.complete[QueryClassification](prompt2)
      _ <- IO.println(s"   Query: $query2")
      _ <- IO.println(s"   Type: ${result2.queryType}")
      _ <- IO.println(s"   Intent: ${result2.intent}")

      // Example 3: Statement
      _ <- ExampleUtils.printSubSection("3. Statement Classification")
      query3 = "The weather is really nice today"
      prompt3 = Prompt.simple(
        "You are a query classifier. Classify the user's query as one of: question, command, statement. Also identify the intent (what the user wants to achieve).",
        s"Classify this query: \"$query3\""
      )
      result3 <- structured.complete[QueryClassification](prompt3)
      _ <- IO.println(s"   Query: $query3")
      _ <- IO.println(s"   Type: ${result3.queryType}")
      _ <- IO.println(s"   Intent: ${result3.intent}")

      // Example 4: Implicit command (question that implies action)
      _ <- ExampleUtils.printSubSection("4. Implicit Command Classification")
      query4 = "Can you turn on the lights?"
      prompt4 = Prompt.simple(
        "You are a query classifier. Classify the user's query as one of: question, command, statement. Also identify the intent (what the user wants to achieve).",
        s"Classify this query: \"$query4\""
      )
      result4 <- structured.complete[QueryClassification](prompt4)
      _ <- IO.println(s"   Query: $query4")
      _ <- IO.println(s"   Type: ${result4.queryType}")
      _ <- IO.println(s"   Intent: ${result4.intent}")

      _ <- IO.println("\nQuery classification example completed.")
    yield ()
