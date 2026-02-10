package org.adk4s.examples.structured.llm.multiagent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.{SpecialistDelegation, CategoryClassification}

/**
 * Demonstrates multi-agent host pattern with StructuredLLM.
 *
 * Shows how to:
 * - Implement a host agent that coordinates specialist agents
 * - Route tasks to specialists using StructuredLLM
 * - Execute specialist tasks with different schemas
 * - Build hierarchical multi-agent systems with type safety
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object MultiAgentHostStructuredExample extends IOApp.Simple:

  // Schema[A] instances wrapping Smithy-generated schemas
  given Schema[SpecialistDelegation] = Schema.instance(
    """structure SpecialistDelegation {
      |  @required
      |  specialist: String
      |  @required
      |  rationale: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[SpecialistDelegation]])

  given Schema[CategoryClassification] = Schema.instance(
    """structure CategoryClassification {
      |  @required
      |  category: String
      |  @required
      |  confidence: Double
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[CategoryClassification]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new MultiAgentHostMockLLMClient())
    }

  // Simulate specialist agent execution
  private def executeCodeSpecialist(task: String, llmClient: org.llm4s.llmconnect.LLMClient): IO[String] =
    val structured = StructuredLLM.fromClient[IO](llmClient)
    val prompt = Prompt.simple(
      "You are a code specialist. Classify the code-related task.",
      s"Task: $task"
    )
    structured.complete[CategoryClassification](prompt).map { result =>
      s"Code review completed: ${result.category} (confidence: ${result.confidence})"
    }

  private def executeDataSpecialist(task: String, llmClient: org.llm4s.llmconnect.LLMClient): IO[String] =
    val structured = StructuredLLM.fromClient[IO](llmClient)
    val prompt = Prompt.simple(
      "You are a data specialist. Classify the data-related task.",
      s"Task: $task"
    )
    structured.complete[CategoryClassification](prompt).map { result =>
      s"Data analysis completed: ${result.category} (confidence: ${result.confidence})"
    }

  private def executeSecuritySpecialist(task: String, llmClient: org.llm4s.llmconnect.LLMClient): IO[String] =
    val structured = StructuredLLM.fromClient[IO](llmClient)
    val prompt = Prompt.simple(
      "You are a security specialist. Classify the security-related task.",
      s"Task: $task"
    )
    structured.complete[CategoryClassification](prompt).map { result =>
      s"Security audit completed: ${result.category} (confidence: ${result.confidence})"
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Multi-Agent Host (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      _ <- IO.println("   Host Agent coordinates specialists:")
      _ <- IO.println("     - code_specialist")
      _ <- IO.println("     - data_specialist")
      _ <- IO.println("     - security_specialist")
      _ <- IO.println("")

      // Example 1: Code task with host routing
      _ <- ExampleUtils.printSubSection("1. Code Review Task")
      task1 = "Review the authentication implementation in the API"
      _ <- IO.println(s"   Task: $task1\n")

      // Host decides which specialist to use
      _ <- IO.println("   [Host] Routing to appropriate specialist...")
      hostPrompt1 = Prompt.simple(
        "You are a host agent. Select the appropriate specialist for this task. Valid specialists: code_specialist, data_specialist, security_specialist. Use exact names.",
        s"Task: $task1"
      )
      routing1 <- structured.complete[SpecialistDelegation](hostPrompt1)
      _ <- IO.println(s"   [Host] → Delegating to: ${routing1.specialist}")
      _ <- IO.println(s"   [Host] → Rationale: ${routing1.rationale}\n")

      // Execute specialist
      result1 <- routing1.specialist match
        case "code_specialist" => executeCodeSpecialist(task1, llmClient)
        case "data_specialist" => executeDataSpecialist(task1, llmClient)
        case "security_specialist" => executeSecuritySpecialist(task1, llmClient)
        case _ => IO.pure("Unknown specialist")
      _ <- IO.println(s"   [${routing1.specialist}] $result1")

      // Example 2: Data task
      _ <- ExampleUtils.printSubSection("2. Data Analysis Task")
      task2 = "Analyze user engagement trends over the last quarter"
      _ <- IO.println(s"   Task: $task2\n")

      _ <- IO.println("   [Host] Routing to appropriate specialist...")
      hostPrompt2 = Prompt.simple(
        "You are a host agent. Select the appropriate specialist for this task. Valid specialists: code_specialist, data_specialist, security_specialist. Use exact names.",
        s"Task: $task2"
      )
      routing2 <- structured.complete[SpecialistDelegation](hostPrompt2)
      _ <- IO.println(s"   [Host] → Delegating to: ${routing2.specialist}")
      _ <- IO.println(s"   [Host] → Rationale: ${routing2.rationale}\n")

      result2 <- routing2.specialist match
        case "code_specialist" => executeCodeSpecialist(task2, llmClient)
        case "data_specialist" => executeDataSpecialist(task2, llmClient)
        case "security_specialist" => executeSecuritySpecialist(task2, llmClient)
        case _ => IO.pure("Unknown specialist")
      _ <- IO.println(s"   [${routing2.specialist}] $result2")

      // Example 3: Security task
      _ <- ExampleUtils.printSubSection("3. Security Audit Task")
      task3 = "Conduct penetration testing on the user authentication endpoints"
      _ <- IO.println(s"   Task: $task3\n")

      _ <- IO.println("   [Host] Routing to appropriate specialist...")
      hostPrompt3 = Prompt.simple(
        "You are a host agent. Select the appropriate specialist for this task. Valid specialists: code_specialist, data_specialist, security_specialist. Use exact names.",
        s"Task: $task3"
      )
      routing3 <- structured.complete[SpecialistDelegation](hostPrompt3)
      _ <- IO.println(s"   [Host] → Delegating to: ${routing3.specialist}")
      _ <- IO.println(s"   [Host] → Rationale: ${routing3.rationale}\n")

      result3 <- routing3.specialist match
        case "code_specialist" => executeCodeSpecialist(task3, llmClient)
        case "data_specialist" => executeDataSpecialist(task3, llmClient)
        case "security_specialist" => executeSecuritySpecialist(task3, llmClient)
        case _ => IO.pure("Unknown specialist")
      _ <- IO.println(s"   [${routing3.specialist}] $result3")

      _ <- IO.println("\nMulti-agent host example completed.")
    yield ()

/**
 * Mock LLM client for multi-agent host examples.
 */
class MultiAgentHostMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val systemMessage: String = conversation.messages.collect {
      case msg: SystemMessage => msg.content
    }.headOption.getOrElse("")

    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
    }.lastOption.getOrElse("")

    val response: String =
      // Host routing decisions
      if systemMessage.contains("host agent") then
        if lastUserMessage.contains("authentication implementation") then
          """{"specialist": "code_specialist", "rationale": "Authentication implementation requires code review expertise"}"""
        else if lastUserMessage.contains("engagement trends") then
          """{"specialist": "data_specialist", "rationale": "Analyzing trends requires data analysis expertise"}"""
        else if lastUserMessage.contains("penetration testing") then
          """{"specialist": "security_specialist", "rationale": "Penetration testing requires security expertise"}"""
        else
          """{"specialist": "code_specialist", "rationale": "Default to code specialist"}"""
      // Specialist execution responses
      else if systemMessage.contains("code specialist") then
        """{"category": "code-review", "confidence": 0.92}"""
      else if systemMessage.contains("data specialist") then
        """{"category": "data-analysis", "confidence": 0.90}"""
      else if systemMessage.contains("security specialist") then
        """{"category": "security-audit", "confidence": 0.95}"""
      else
        """{"category": "general", "confidence": 0.70}"""

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
