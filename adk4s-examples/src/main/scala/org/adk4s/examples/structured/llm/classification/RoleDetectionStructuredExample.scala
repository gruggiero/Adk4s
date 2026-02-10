package org.adk4s.examples.structured.llm.classification

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.RoleDetection

/**
 * Demonstrates StructuredLLM for role detection with typed output.
 *
 * Shows how to:
 * - Parse LLM responses into typed RoleDetection case class
 * - Detect user roles (customer/support/manager) from messages
 * - Return confidence scores alongside role classifications
 * - Handle customer service / support ticket scenarios
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object RoleDetectionStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[RoleDetection] = Schema.instance(
    """structure RoleDetection {
      |  @required
      |  role: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[RoleDetection]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Role Detection (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Customer role
      _ <- ExampleUtils.printSubSection("1. Customer Role Detection")
      message1 = "I have a problem with my recent order #12345. Can you help me track it?"
      prompt1 = Prompt.simple(
        "You are a role detector for a customer service system. Classify the speaker's role as one of: customer, support_agent, manager. Return confidence as a number between 0 and 1.",
        s"Detect the role of this speaker: \"$message1\""
      )
      result1 <- structured.complete[RoleDetection](prompt1)
      _ <- IO.println(s"   Message: $message1")
      _ <- IO.println(s"   Role: ${result1.role}")
      _ <- IO.println(s"   Confidence: ${result1.confidence}")

      // Example 2: Support agent role
      _ <- ExampleUtils.printSubSection("2. Support Agent Role Detection")
      message2 = "I've reviewed ticket #12345 and escalated it to our shipping department. I'll follow up with the customer within 24 hours."
      prompt2 = Prompt.simple(
        "You are a role detector for a customer service system. Classify the speaker's role as one of: customer, support_agent, manager. Return confidence as a number between 0 and 1.",
        s"Detect the role of this speaker: \"$message2\""
      )
      result2 <- structured.complete[RoleDetection](prompt2)
      _ <- IO.println(s"   Message: $message2")
      _ <- IO.println(s"   Role: ${result2.role}")
      _ <- IO.println(s"   Confidence: ${result2.confidence}")

      // Example 3: Manager role
      _ <- ExampleUtils.printSubSection("3. Manager Role Detection")
      message3 = "Team, I need everyone to review our Q4 support metrics. Let's discuss strategies to improve response times in tomorrow's meeting."
      prompt3 = Prompt.simple(
        "You are a role detector for a customer service system. Classify the speaker's role as one of: customer, support_agent, manager. Return confidence as a number between 0 and 1.",
        s"Detect the role of this speaker: \"$message3\""
      )
      result3 <- structured.complete[RoleDetection](prompt3)
      _ <- IO.println(s"   Message: $message3")
      _ <- IO.println(s"   Role: ${result3.role}")
      _ <- IO.println(s"   Confidence: ${result3.confidence}")

      // Example 4: Ambiguous case (customer asking technical question)
      _ <- ExampleUtils.printSubSection("4. Technical Customer Detection")
      message4 = "I'm implementing your API and getting a 401 error. What are the correct authentication headers?"
      prompt4 = Prompt.simple(
        "You are a role detector for a customer service system. Classify the speaker's role as one of: customer, support_agent, manager. Return confidence as a number between 0 and 1.",
        s"Detect the role of this speaker: \"$message4\""
      )
      result4 <- structured.complete[RoleDetection](prompt4)
      _ <- IO.println(s"   Message: $message4")
      _ <- IO.println(s"   Role: ${result4.role}")
      _ <- IO.println(s"   Confidence: ${result4.confidence}")

      _ <- IO.println("\nRole detection example completed.")
    yield ()
