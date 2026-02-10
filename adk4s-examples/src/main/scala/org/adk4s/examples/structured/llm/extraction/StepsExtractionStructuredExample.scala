package org.adk4s.examples.structured.llm.extraction

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{StepList, StepItem}

/**
 * Demonstrates StructuredLLM for steps extraction with typed output.
 *
 * Shows how to:
 * - Extract structured step lists from natural language instructions
 * - Parse LLM responses into nested structures (StepList with List[StepItem])
 * - Handle optional fields (duration is optional in StepItem)
 * - Extract numbered/bulleted lists into structured format
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object StepsExtractionStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[StepList] = Schema.instance(
    """structure StepList {
      |  @required
      |  items: StepItem[]
      |}
      |
      |structure StepItem {
      |  @required
      |  index: Integer
      |  @required
      |  description: String
      |  duration: Integer
      |}
      |
      |// "items" is a JSON array: [{"index":1,"description":"...","duration":30}, ...]""".stripMargin
  )(using summon[smithy4s.schema.Schema[StepList]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new StepsExtractionMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Steps Extraction (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      // Example 1: Recipe steps with durations
      _ <- ExampleUtils.printSubSection("1. Recipe Steps Extraction")
      recipe = "To make pancakes: 1. Mix flour, eggs, and milk (5 min). 2. Heat pan (2 min). 3. Pour batter and cook (3 min). 4. Flip and cook other side (3 min). 5. Serve (1 min)."
      prompt1 = Prompt.simple(
        "Extract the numbered steps from the instructions. Include index, description, and duration if mentioned.",
        s"Instructions: $recipe"
      )
      result1 <- structured.complete[StepList](prompt1)
      _ <- IO.println(s"   Instructions: $recipe")
      _ <- IO.println(s"   Extracted ${result1.items.size} steps:")
      _ <- result1.items.traverse_ { step =>
        val durationStr = step.duration.map(d => s" ($d min)").getOrElse("")
        IO.println(s"     ${step.index}. ${step.description}$durationStr")
      }

      // Example 2: Setup instructions without durations
      _ <- ExampleUtils.printSubSection("2. Setup Instructions Extraction")
      setup = "Installation steps: 1. Download the installer. 2. Run the setup wizard. 3. Accept the license agreement. 4. Choose installation directory. 5. Click Install."
      prompt2 = Prompt.simple(
        "Extract the numbered steps from the instructions. Include index, description, and duration if mentioned.",
        s"Instructions: $setup"
      )
      result2 <- structured.complete[StepList](prompt2)
      _ <- IO.println(s"   Instructions: $setup")
      _ <- IO.println(s"   Extracted ${result2.items.size} steps:")
      _ <- result2.items.traverse_ { step =>
        val durationStr = step.duration.map(d => s" ($d min)").getOrElse("")
        IO.println(s"     ${step.index}. ${step.description}$durationStr")
      }

      // Example 3: Workflow steps with mixed durations
      _ <- ExampleUtils.printSubSection("3. Workflow Steps Extraction")
      workflow = "Deployment workflow: 1. Run tests (10 min). 2. Build Docker image. 3. Push to registry (5 min). 4. Update Kubernetes config. 5. Apply changes (2 min). 6. Verify deployment (3 min)."
      prompt3 = Prompt.simple(
        "Extract the numbered steps from the instructions. Include index, description, and duration if mentioned.",
        s"Instructions: $workflow"
      )
      result3 <- structured.complete[StepList](prompt3)
      _ <- IO.println(s"   Instructions: $workflow")
      _ <- IO.println(s"   Extracted ${result3.items.size} steps:")
      _ <- result3.items.traverse_ { step =>
        val durationStr = step.duration.map(d => s" ($d min)").getOrElse("")
        IO.println(s"     ${step.index}. ${step.description}$durationStr")
      }

      _ <- IO.println("\nSteps extraction example completed.")
    yield ()

/**
 * Mock LLM client that returns schema-compliant JSON for steps extraction.
 */
class StepsExtractionMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
    }.lastOption.getOrElse("")

    val response: String =
      if lastUserMessage.contains("pancakes") then
        """{
          |  "items": [
          |    {"index": 1, "description": "Mix flour, eggs, and milk", "duration": 5},
          |    {"index": 2, "description": "Heat pan", "duration": 2},
          |    {"index": 3, "description": "Pour batter and cook", "duration": 3},
          |    {"index": 4, "description": "Flip and cook other side", "duration": 3},
          |    {"index": 5, "description": "Serve", "duration": 1}
          |  ]
          |}""".stripMargin
      else if lastUserMessage.contains("installer") || lastUserMessage.contains("Installation") then
        """{
          |  "items": [
          |    {"index": 1, "description": "Download the installer"},
          |    {"index": 2, "description": "Run the setup wizard"},
          |    {"index": 3, "description": "Accept the license agreement"},
          |    {"index": 4, "description": "Choose installation directory"},
          |    {"index": 5, "description": "Click Install"}
          |  ]
          |}""".stripMargin
      else if lastUserMessage.contains("Deployment") || lastUserMessage.contains("workflow") then
        """{
          |  "items": [
          |    {"index": 1, "description": "Run tests", "duration": 10},
          |    {"index": 2, "description": "Build Docker image"},
          |    {"index": 3, "description": "Push to registry", "duration": 5},
          |    {"index": 4, "description": "Update Kubernetes config"},
          |    {"index": 5, "description": "Apply changes", "duration": 2},
          |    {"index": 6, "description": "Verify deployment", "duration": 3}
          |  ]
          |}""".stripMargin
      else
        """{
          |  "items": [
          |    {"index": 1, "description": "Step 1"},
          |    {"index": 2, "description": "Step 2"}
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
