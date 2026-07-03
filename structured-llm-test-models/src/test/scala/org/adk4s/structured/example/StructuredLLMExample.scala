package org.adk4s.structured.example

import cats.effect.IO
import cats.effect.IOApp
import io.circe.generic.auto.*
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import org.adk4s.structured.template.syntax.*
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.types.Result
import smithy4s.schema.Schema as Smithy4sSchema
import smithy4s.{ ShapeId, Hints }

/**
 * Example demonstrating the BAML-inspired structured LLM wrapper.
 *
 * This shows:
 * 1. Defining output types with Smithy schemas
 * 2. Creating prompt templates
 * 3. Making type-safe LLM calls
 * 4. Composing multiple calls
 */
object StructuredLLMExample extends IOApp.Simple:

  // ============================================================
  // 1. DEFINE OUTPUT TYPES
  // ============================================================

  /**
   * Simple resume data structure.
   */
  case class Resume(
    name: String,
    email: Option[String],
    skills: List[String],
    education: Option[List[Education]],
    seniority: SeniorityLevel
  )

  case class Education(
    school: String,
    degree: String,
    year: Option[Int]
  )

  enum SeniorityLevel:
    case Junior, Mid, Senior, Staff

  // Simple string-based schema for demonstration
  // In practice, you would use proper smithy4s generated schemas
  given Smithy4sSchema[Resume] = Smithy4sSchema.constant(Resume("", None, Nil, None, SeniorityLevel.Junior))
  given Schema[Resume] = Schema.instance[Resume](
    """structure Resume {
      |  @required
      |  name: String
      |  
      |  email: String
      |  
      |  @required
      |  skills: StringList
      |  
      |  education: EducationList
      |  
      |  @required
      |  seniority: SeniorityLevel
      |}
      |
      |list StringList {
      |  member: String
      |}
      |
      |list EducationList {
      |  member: Education
      |}
      |
      |structure Education {
      |  @required
      |  school: String
      |  
      |  @required
      |  degree: String
      |  
      |  year: Integer
      |}
      |
      |enum SeniorityLevel {
      |  @documentation("0-2 years of experience")
      |  JUNIOR
      |  
      |  @documentation("2-5 years of experience")
      |  MID
      |  
      |  @documentation("5-10 years of experience")
      |  SENIOR
      |  
      |  @documentation("10+ years, technical leadership")
      |  STAFF
      |}""".stripMargin
  )(using summon[Smithy4sSchema[Resume]])

  // ============================================================
  // 2. CREATE PROMPT TEMPLATES
  // ============================================================

  /**
   * Input for resume extraction.
   */
  case class ResumeInput(resumeText: String, context: Option[String] = None)

  /**
   * Template for extracting structured resume data.
   */
  val extractResumeTemplate: PromptTemplate[ResumeInput] =
    new PromptTemplate[ResumeInput]:
      def render(input: ResumeInput): Prompt =
        val content =
          s"""You are an expert resume parser. Extract information accurately and preserve the exact wording.
                         |
                         |Please extract the following information from this resume:
                         |${input.resumeText}
                         |
                         |Focus on:
                         |- Full name
                         |- Contact information (email if available)
                         |- Technical and soft skills
                         |- Educational background
                         |- Overall seniority level based on experience""".stripMargin

        Prompt(SystemMessage(content))
    .expecting[Resume]

  // ============================================================
  // 3. BASIC USAGE EXAMPLES
  // ============================================================

  /**
   * Example 1: Simple extraction
   */
  def extractResumeExample(structured: StructuredLLM[IO]): IO[Resume] =
    val resumeText = """
      |John Doe
      |john.doe@example.com
      |
      |Skills: Python, Machine Learning, React, TypeScript, AWS
      |
      |Education:
      |- BS Computer Science, Stanford University (2018)
      |- MS AI, MIT (2020)
      |
      |Experience:
      |- Software Engineer at TechCorp (2020-2022)
      |- Senior Engineer at StartupXYZ (2022-present)
      |""".stripMargin

    for
      prompt <- IO.pure(extractResumeTemplate.render(ResumeInput(resumeText)))
      _      <- IO.println(s"\nPrompt being sent:\n${prompt.conversation.messages.map(_.content).mkString("\n")}\n")
      result <- structured.complete[Resume](prompt)
    yield result

  /**
   * Example 2: Using the output format directly
   */
  def showOutputFormat(structured: StructuredLLM[IO]): IO[Unit] =
    IO.println(Schema[Resume].outputFormatBlock) *>
      IO.println("\n---\n")

  // ============================================================
  // 4. MAIN PROGRAM
  // ============================================================

  /**
   * Run the example.
   */
  def run: IO[Unit] =
    val mockClient: LLMClient         = new MockLLMClient
    val structured: StructuredLLM[IO] = StructuredLLM.fromClient(mockClient)

    for
      _ <- IO.println("=== Structured LLM Resume Extraction Example ===")

      // Show the output format that will be sent to the LLM
      _ <- showOutputFormat(structured)

      // Extract resume data
      resume <- extractResumeExample(structured)

      _ <- IO.println("\nExtracted Resume:")
      _ <- IO.println(s"Name: ${resume.name}")
      _ <- IO.println(s"Email: ${resume.email.getOrElse("Not provided")}")
      _ <- IO.println(s"Skills: ${resume.skills.mkString(", ")}")
      _ <- IO.println(s"Seniority: ${resume.seniority}")

      _ <- IO.println("\nDone!")
    yield ()

  // ============================================================
  // 5. MOCK LLM CLIENT
  // ============================================================

  /**
   * Mock LLM client for demonstration.
   * In practice, this would connect to a real LLM service.
   */
  class MockLLMClient extends LLMClient:
    def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      val jsonResponse: String =
        """{
          |  "name": "John Doe",
          |  "email": "john.doe@example.com",
          |  "skills": ["Python", "Machine Learning", "React", "TypeScript", "AWS"],
          |  "education": [
          |    {
          |      "school": "Stanford University",
          |      "degree": "BS Computer Science",
          |      "year": 2018
          |    },
          |    {
          |      "school": "MIT",
          |      "degree": "MS AI",
          |      "year": 2020
          |    }
          |  ],
          |  "seniority": "MID"
          |}""".stripMargin
      val completion: Completion = Completion(
        id = "mock-completion-id",
        created = 0L,
        content = jsonResponse,
        model = "mock-model",
        message = AssistantMessage(Some(jsonResponse)),
        toolCalls = List.empty,
        usage = None,
        thinking = None
      )
      println(s"\n[MockLLM] Returning JSON:\n$jsonResponse\n")
      Right(completion)

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      // Emit a single chunk with full content, then return same completion as complete
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
