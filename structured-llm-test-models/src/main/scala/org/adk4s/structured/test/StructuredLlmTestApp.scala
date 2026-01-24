package org.adk4s.structured.test

import cats.effect.IO
import cats.effect.IOApp
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import smithy4s.Schema as Smithy4sSchema
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message as LLM4sMessage
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.types.Result
import org.llm4s.error.LLMError

/** Minimal runnable example showing how to call Structured LLM using the generated
  * smithy4s Resume model from this module.
  */
object StructuredLlmTestApp extends IOApp.Simple:

  private val smithyFilePath: String =
    "structured-llm-test-models/src/main/smithy/resume.smithy"

  private val sampleResumeText: String =
    """John Doe
      |john.doe@example.com
      |
      |Summary:
      |Senior software engineer with 8 years of experience building backend systems.
      |
      |Skills:
      |- Scala, Kotlin, Java
      |- Functional Programming
      |- AWS, Docker, Kubernetes
      |
      |Education:
      |- MS Computer Science, MIT, 2016
      |- BS Computer Engineering, UCLA, 2014
      |
      |Experience:
      |- Staff Engineer at Acme Corp (2020-present)
      |- Senior Engineer at Globex (2016-2020)
      |""".stripMargin

  private val mockCompletionJson: String =
    """{
      |  "name": "John Doe",
      |  "email": "john.doe@example.com",
      |  "skills": ["Scala", "Functional Programming", "AWS", "Kubernetes"],
      |  "education": [
      |    {
      |      "school": "MIT",
      |      "degree": "MS Computer Science",
      |      "year": 2016
      |    }
      |  ],
      |  "seniority": "SENIOR"
      |}""".stripMargin

  private def loadDefinition(path: String): IO[String] =
    val targetPath: Path = Path.of(path)
    IO.blocking(Files.readString(targetPath, StandardCharsets.UTF_8))

  private def mkSchema[A](definition: String)(using smithy: Smithy4sSchema[A]): Schema[A] =
    Schema.instance[A](definition)(using smithy)

  private val mockClient: LLMClient =
    new LLMClient:
      override def complete(
        conversation: Conversation,
        options: CompletionOptions
      ): Result[Completion] =
        val completion: Completion = Completion(
          id = "mock-completion-id",
          created = 0L,
          content = mockCompletionJson,
          model = "mock-model",
          message = AssistantMessage(Some(mockCompletionJson)),
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

  private def resumeTemplate(using schema: Schema[Resume]): PromptTemplate[String] =
    PromptTemplate
      .withSystem("You are an expert resume parser. Return strictly valid JSON.")
      .expecting[Resume]

  private def logResult(resume: Resume): IO[Unit] =
    val summary: String =
      s"""Parsed resume:
         |Name: ${resume.name}
         |Email: ${resume.email.getOrElse("Not provided")}
         |Skills: ${resume.skills.mkString(", ")}
         |Seniority: ${resume.seniority.toString}
         |Education entries: ${resume.education.map(_.size).getOrElse(0)}""".stripMargin
    IO.println(summary)

  private def runExample(using schema: Schema[Resume]): IO[Unit] =
    val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](mockClient)
    val template: PromptTemplate[String] = resumeTemplate(using schema)
    val parsedResume: IO[Resume] =
      structured.completeTemplate[String, Resume](template, sampleResumeText)

    val outputFormat: IO[Unit] = IO.println(Schema[Resume].outputFormatBlock)
    val execution: IO[Unit] = parsedResume.flatMap(logResult)

    outputFormat *> execution

  override def run: IO[Unit] =
    val program: IO[Unit] =
      loadDefinition(smithyFilePath).flatMap { (definition: String) =>
        given Schema[Resume] = mkSchema[Resume](definition)(using Resume.schema)
        val header: IO[Unit] = IO.println("=== Structured LLM resume extraction example ===")
        val body: IO[Unit] = runExample(using summon[Schema[Resume]])
        header *> body
      }

    program
