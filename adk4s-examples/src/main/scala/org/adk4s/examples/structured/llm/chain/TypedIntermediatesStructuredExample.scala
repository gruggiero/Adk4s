package org.adk4s.examples.structured.llm.chain

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.TypedIntermediate

/**
 * Demonstrates chaining StructuredLLM calls with typed intermediates.
 *
 * Shows how to:
 * - Chain multiple StructuredLLM calls where output of one feeds into the next
 * - Use typed intermediate results (TypedIntermediate)
 * - Build multi-stage processing pipelines with type safety
 * - Track progress through processing stages
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object TypedIntermediatesStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[TypedIntermediate] = Schema.instance(
    """structure TypedIntermediate {
      |  @required
      |  stage: String
      |  @required
      |  result: String
      |  @required
      |  nextAction: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[TypedIntermediate]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Typed Intermediates Chain (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example: Multi-stage content processing pipeline
      _ <- ExampleUtils.printSubSection("Content Processing Pipeline")
      originalText = "Artificial intelligence is revolutionizing software development by enabling automated code generation, intelligent debugging, and predictive analytics."

      // Stage 1: Analysis
      _ <- IO.println(s"\n   Original text: $originalText\n")
      _ <- IO.println("   Stage 1: Analyzing content...")
      prompt1 = Prompt.simple(
        "You are at the 'analysis' stage. Analyze the input text and identify key themes. Return the stage name, analysis result, and what should happen next.",
        s"Analyze: $originalText"
      )
      stage1 <- structured.complete[TypedIntermediate](prompt1)
      _ <- IO.println(s"   → Stage: ${stage1.stage}")
      _ <- IO.println(s"   → Result: ${stage1.result}")
      _ <- IO.println(s"   → Next: ${stage1.nextAction}")

      // Stage 2: Summarization (using stage1 result)
      _ <- IO.println("\n   Stage 2: Summarizing analysis...")
      prompt2 = Prompt.simple(
        "You are at the 'summarization' stage. Create a concise summary based on the previous analysis. Return the stage name, summary result, and what should happen next.",
        s"Previous analysis: ${stage1.result}\nOriginal text: $originalText"
      )
      stage2 <- structured.complete[TypedIntermediate](prompt2)
      _ <- IO.println(s"   → Stage: ${stage2.stage}")
      _ <- IO.println(s"   → Result: ${stage2.result}")
      _ <- IO.println(s"   → Next: ${stage2.nextAction}")

      // Stage 3: Refinement (using stage2 result)
      _ <- IO.println("\n   Stage 3: Refining summary...")
      prompt3 = Prompt.simple(
        "You are at the 'refinement' stage. Refine the summary to make it more concise and impactful. Return the stage name, refined result, and what should happen next.",
        s"Previous summary: ${stage2.result}"
      )
      stage3 <- structured.complete[TypedIntermediate](prompt3)
      _ <- IO.println(s"   → Stage: ${stage3.stage}")
      _ <- IO.println(s"   → Result: ${stage3.result}")
      _ <- IO.println(s"   → Next: ${stage3.nextAction}")

      // Final output
      _ <- IO.println("\n   Pipeline complete!")
      _ <- IO.println(s"   Final result: ${stage3.result}")

      _ <- IO.println("\nTyped intermediates chain example completed.")
    yield ()
