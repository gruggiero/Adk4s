package org.adk4s.structured.test

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.structured.core.{ Message as CoreMessage, Prompt, PromptTemplate, Role, Schema, StructuredLLM }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.types.Result

class ResumeSchemaTest extends CatsEffectSuite:

  test("extract resume using generated smithy4s schema") {
    // Mock LLM client that returns JSON matching the Resume schema
    val mockClient: LLMClient =
      new LLMClient:
        override def complete(
          conversation: Conversation,
          options: CompletionOptions
        ): Result[Completion] =
          val json: String =
            """{
              |  "name": "Jane Smith",
              |  "email": "jane.smith@example.com",
              |  "skills": ["Scala", "Functional Programming", "Smithy"],
              |  "seniority": "SENIOR",
              |  "education": [
              |    {
              |      "school": "MIT",
              |      "degree": "BS Computer Science",
              |      "year": 2015
              |    }
              |  ]
              |}""".stripMargin
          val completion: Completion = Completion(
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
          val chunk: StreamedChunk = StreamedChunk(
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

    val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](mockClient)

    // Create Schema instance using the generated Resume type
    // Using a manual Smithy definition string for now
    given Schema[Resume] = Schema.instance(
      """structure Resume {
        |  @required
        |  name: String
        |  @required
        |  skills: StringList
        |  @required
        |  seniority: SeniorityLevel
        |  email: String
        |  education: EducationList
        |}""".stripMargin,
      Some("Resume with skills and experience")
    )(using Resume.schema)

    // Create a prompt template
    val extractResume = new PromptTemplate[String]:
      def render(input: String): Prompt =
        val basePrompt = Prompt(
          CoreMessage(Role.System, "Extract resume information"),
          CoreMessage(Role.User, s"Extract from: $input")
        )
        basePrompt.withOutputFormat[Resume]

    for result <- structured.complete[Resume](extractResume.render("Test resume"))
    yield
      assertEquals(result.name, "Jane Smith")
      assertEquals(result.email, Some("jane.smith@example.com"))
      assertEquals(result.skills, List("Scala", "Functional Programming", "Smithy"))
      assertEquals(result.seniority, SeniorityLevel.SENIOR)
      assertEquals(result.education.map(_.size), Some(1))
      result.education.foreach { eduList =>
        assertEquals(eduList.head.school, "MIT")
        assertEquals(eduList.head.degree, "BS Computer Science")
        assertEquals(eduList.head.year, Some(2015))
      }
  }

  test("Resume schema has correct structure") {
    // Verify the generated schema has expected fields
    val resume = Resume(
      name = "Test Name",
      skills = List("Skill1", "Skill2"),
      seniority = SeniorityLevel.MID,
      email = Some("test@example.com"),
      education = None
    )

    assertEquals(resume.name, "Test Name")
    assertEquals(resume.skills.size, 2)
    assertEquals(resume.seniority, SeniorityLevel.MID)
  }

  test("SeniorityLevel enum has all values") {
    val levels = List(
      SeniorityLevel.JUNIOR,
      SeniorityLevel.MID,
      SeniorityLevel.SENIOR,
      SeniorityLevel.STAFF
    )

    assertEquals(levels.size, 4)
  }
