package org.adk4s.examples.structured.llm.classification

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.ChainRoute

/**
 * Demonstrates StructuredLLM for chain routing decisions.
 *
 * Shows how to:
 * - Route tasks to appropriate processing chains
 * - Parse LLM responses into ChainRoute with reasoning
 * - Make chain selection decisions with explanations
 * - Build intelligent routing systems with type safety
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object ChainRouteStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[ChainRoute] = Schema.instance(
    """structure ChainRoute {
      |  @required
      |  chainName: String
      |  @required
      |  reason: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[ChainRoute]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Chain Route Selection (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      _ <- IO.println("   Available chains:")
      _ <- IO.println("     - summarization_chain: Summarizes long text")
      _ <- IO.println("     - qa_chain: Answers questions")
      _ <- IO.println("     - translation_chain: Translates between languages")
      _ <- IO.println("     - classification_chain: Classifies content")
      _ <- IO.println("")

      // Example 1: Summarization task
      _ <- ExampleUtils.printSubSection("1. Summarization Task Routing")
      task1 = "Please provide a summary of this 10-page research paper on quantum computing."
      prompt1 = Prompt.simple(
        "You are a routing agent. Given a task, select the most appropriate chain and explain why. Available chains: summarization_chain, qa_chain, translation_chain, classification_chain.",
        s"Task: $task1"
      )
      result1 <- structured.complete[ChainRoute](prompt1)
      _ <- IO.println(s"   Task: $task1")
      _ <- IO.println(s"   → Route to: ${result1.chainName}")
      _ <- IO.println(s"   → Reason: ${result1.reason}")

      // Example 2: Q&A task
      _ <- ExampleUtils.printSubSection("2. Question Answering Task Routing")
      task2 = "What are the main differences between supervised and unsupervised learning?"
      prompt2 = Prompt.simple(
        "You are a routing agent. Given a task, select the most appropriate chain and explain why. Available chains: summarization_chain, qa_chain, translation_chain, classification_chain.",
        s"Task: $task2"
      )
      result2 <- structured.complete[ChainRoute](prompt2)
      _ <- IO.println(s"   Task: $task2")
      _ <- IO.println(s"   → Route to: ${result2.chainName}")
      _ <- IO.println(s"   → Reason: ${result2.reason}")

      // Example 3: Translation task
      _ <- ExampleUtils.printSubSection("3. Translation Task Routing")
      task3 = "Translate this document from English to Spanish."
      prompt3 = Prompt.simple(
        "You are a routing agent. Given a task, select the most appropriate chain and explain why. Available chains: summarization_chain, qa_chain, translation_chain, classification_chain.",
        s"Task: $task3"
      )
      result3 <- structured.complete[ChainRoute](prompt3)
      _ <- IO.println(s"   Task: $task3")
      _ <- IO.println(s"   → Route to: ${result3.chainName}")
      _ <- IO.println(s"   → Reason: ${result3.reason}")

      // Example 4: Classification task
      _ <- ExampleUtils.printSubSection("4. Classification Task Routing")
      task4 = "Categorize these customer reviews into positive, negative, or neutral sentiment."
      prompt4 = Prompt.simple(
        "You are a routing agent. Given a task, select the most appropriate chain and explain why. Available chains: summarization_chain, qa_chain, translation_chain, classification_chain.",
        s"Task: $task4"
      )
      result4 <- structured.complete[ChainRoute](prompt4)
      _ <- IO.println(s"   Task: $task4")
      _ <- IO.println(s"   → Route to: ${result4.chainName}")
      _ <- IO.println(s"   → Reason: ${result4.reason}")

      _ <- IO.println("\nChain route selection example completed.")
    yield ()
