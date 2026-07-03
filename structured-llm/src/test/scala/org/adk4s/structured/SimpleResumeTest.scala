package org.adk4s.structured

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.structured.core.*
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.types.Result
import smithy4s.schema.Schema as Smithy4sSchema

class SimpleResumeTest extends CatsEffectSuite:

  // Simple case class for testing (without smithy4s generation)
  case class TestResume(
    name: String,
    email: Option[String],
    skills: List[String],
    seniority: String
  )

  // Schema using smithy4s constant schema for demonstration
  given Smithy4sSchema[TestResume] = Smithy4sSchema.constant(
    TestResume("John Doe", Some("john.doe@example.com"), List("Python", "Machine Learning", "React"), "Mid")
  )

  // Our Schema wrapper
  given Schema[TestResume] = Schema.instance(
    """structure TestResume {
      |  @required
      |  name: String
      |  
      |  email: String
      |  
      |  @required
      |  skills: List<String>
      |  
      |  @required
      |  seniority: String
      |}""".stripMargin
  )(using summon[Smithy4sSchema[TestResume]])

  test("extract resume using smithy4s decoder") {
    val mockClient = new LLMClient:
      override def complete(
        conversation: Conversation,
        options: CompletionOptions
      ): Result[Completion] =
        val json: String =
          """{
            |  "name": "John Doe",
            |  "email": "john.doe@example.com",
            |  "skills": ["Python", "Machine Learning", "React"],
            |  "seniority": "Mid"
            |}""".stripMargin
        val completion = Completion(
          id = "mock-id",
          created = 0L,
          content = json,
          model = "mock-model",
          message = AssistantMessage(Some(json)),
          toolCalls = List.empty,
          usage = None,
          thinking = None
        )
        Right(completion)

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] =
        val chunk = StreamedChunk(
          id = "mock-chunk-id",
          content = Some("mock-chunk"),
          toolCall = None,
          finishReason = Some("stop"),
          thinkingDelta = None
        )
        onChunk(chunk)
        complete(conversation, options)

      override def getContextWindow(): Int = 8192

      override def getReserveCompletion(): Int = 512

    val structured = StructuredLLM.fromClient[IO](mockClient)

    // Create a prompt template that expects TestResume output
    val extractResume = new PromptTemplate[String]:
      def render(input: String): Prompt =
        val basePrompt = Prompt(
          SystemMessage("Extract resume information"),
          UserMessage(s"Extract from: $input")
        )
        // Add output format to the last message
        basePrompt.withOutputFormat[TestResume]

    for result <- structured.complete[TestResume](extractResume.render("Test resume"))
    yield
      assertEquals(result.name, "John Doe")
      assertEquals(result.email, Some("john.doe@example.com"))
      assertEquals(result.skills, List("Python", "Machine Learning", "React"))
      assertEquals(result.seniority, "Mid")
  }
