package org.adk4s.examples.structured.llm.extraction

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{PlanExtraction, PlanStep}

/**
 * Demonstrates StructuredLLM for plan extraction with typed output.
 *
 * Shows how to:
 * - Extract structured plans from natural language
 * - Parse LLM responses into nested structures (PlanExtraction with List[PlanStep])
 * - Work with complex Smithy schemas containing lists
 * - Extract numbered steps with metadata (duration)
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object PlanExecuteStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[PlanExtraction] = Schema.instance(
    """structure PlanExtraction {
      |  @required
      |  steps: PlanStep[]
      |  @required
      |  totalDuration: Integer
      |}
      |
      |structure PlanStep {
      |  @required
      |  index: Integer
      |  @required
      |  description: String
      |  @required
      |  duration: Integer
      |}
      |
      |// "steps" is a JSON array: [{"index":1,"description":"...","duration":30}, ...]""".stripMargin
  )(using summon[smithy4s.schema.Schema[PlanExtraction]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Plan Extraction (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Software development plan
      _ <- ExampleUtils.printSubSection("1. Software Development Plan")
      task1 = "Build a REST API for user authentication"
      prompt1 = Prompt.simple(
        "You are a project planner. Extract a detailed plan with numbered steps and estimated duration in minutes for each step. Calculate total duration.",
        s"Create a plan for: $task1"
      )
      result1 <- structured.complete[PlanExtraction](prompt1)
      _ <- IO.println(s"   Task: $task1")
      _ <- IO.println(s"   Total Duration: ${result1.totalDuration} minutes")
      _ <- IO.println(s"   Steps:")
      _ <- result1.steps.traverse_ { step =>
        IO.println(s"     ${step.index}. ${step.description} (${step.duration} min)")
      }

      // Example 2: Marketing campaign plan
      _ <- ExampleUtils.printSubSection("2. Marketing Campaign Plan")
      task2 = "Launch a social media campaign for a new product"
      prompt2 = Prompt.simple(
        "You are a project planner. Extract a detailed plan with numbered steps and estimated duration in minutes for each step. Calculate total duration.",
        s"Create a plan for: $task2"
      )
      result2 <- structured.complete[PlanExtraction](prompt2)
      _ <- IO.println(s"   Task: $task2")
      _ <- IO.println(s"   Total Duration: ${result2.totalDuration} minutes")
      _ <- IO.println(s"   Steps:")
      _ <- result2.steps.traverse_ { step =>
        IO.println(s"     ${step.index}. ${step.description} (${step.duration} min)")
      }

      // Example 3: Research project plan
      _ <- ExampleUtils.printSubSection("3. Research Project Plan")
      task3 = "Conduct a literature review on machine learning ethics"
      prompt3 = Prompt.simple(
        "You are a project planner. Extract a detailed plan with numbered steps and estimated duration in minutes for each step. Calculate total duration.",
        s"Create a plan for: $task3"
      )
      result3 <- structured.complete[PlanExtraction](prompt3)
      _ <- IO.println(s"   Task: $task3")
      _ <- IO.println(s"   Total Duration: ${result3.totalDuration} minutes")
      _ <- IO.println(s"   Steps:")
      _ <- result3.steps.traverse_ { step =>
        IO.println(s"     ${step.index}. ${step.description} (${step.duration} min)")
      }

      _ <- IO.println("\nPlan extraction example completed.")
    yield ()

