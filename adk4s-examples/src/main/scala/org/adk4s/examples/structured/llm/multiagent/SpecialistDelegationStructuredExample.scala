package org.adk4s.examples.structured.llm.multiagent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.structured.core.{Prompt, Schema, StructuredLLM}
import org.adk4s.structured.test.SpecialistDelegation

/**
 * Demonstrates StructuredLLM for multi-agent specialist delegation.
 *
 * Shows how to:
 * - Route tasks to specialized agents based on domain expertise
 * - Parse LLM responses into SpecialistDelegation with rationale
 * - Implement a host agent that delegates to specialists
 * - Build multi-agent systems with type-safe delegation
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object SpecialistDelegationStructuredExample extends IOApp.Simple:

  // Schema[A] instance wrapping Smithy-generated schema
  given Schema[SpecialistDelegation] = Schema.instance(
    """structure SpecialistDelegation {
      |  @required
      |  specialist: String
      |  @required
      |  rationale: String
      |}""".stripMargin
  )(using summon[smithy4s.schema.Schema[SpecialistDelegation]])

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new SpecialistDelegationMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Specialist Delegation (Structured)")
      llmClient <- createLLMClient
      structured = StructuredLLM.fromClient[IO](llmClient)

      _ <- IO.println("   Available specialists:")
      _ <- IO.println("     - code_specialist: Reviews and writes code")
      _ <- IO.println("     - data_specialist: Analyzes data and creates visualizations")
      _ <- IO.println("     - security_specialist: Reviews security vulnerabilities")
      _ <- IO.println("     - performance_specialist: Optimizes performance")
      _ <- IO.println("")

      // Example 1: Code review task
      _ <- ExampleUtils.printSubSection("1. Code Review Delegation")
      task1 = "Review this pull request for a new authentication feature"
      prompt1 = Prompt.simple(
        "You are a host agent coordinating specialists. Given a task, select the most appropriate specialist and explain why. Available specialists: code_specialist, data_specialist, security_specialist, performance_specialist. Use exact names.",
        s"Task: $task1"
      )
      result1 <- structured.complete[SpecialistDelegation](prompt1)
      _ <- IO.println(s"   Task: $task1")
      _ <- IO.println(s"   → Delegated to: ${result1.specialist}")
      _ <- IO.println(s"   → Rationale: ${result1.rationale}")

      // Example 2: Data analysis task
      _ <- ExampleUtils.printSubSection("2. Data Analysis Delegation")
      task2 = "Analyze user engagement metrics and create a dashboard"
      prompt2 = Prompt.simple(
        "You are a host agent coordinating specialists. Given a task, select the most appropriate specialist and explain why. Available specialists: code_specialist, data_specialist, security_specialist, performance_specialist. Use exact names.",
        s"Task: $task2"
      )
      result2 <- structured.complete[SpecialistDelegation](prompt2)
      _ <- IO.println(s"   Task: $task2")
      _ <- IO.println(s"   → Delegated to: ${result2.specialist}")
      _ <- IO.println(s"   → Rationale: ${result2.rationale}")

      // Example 3: Security audit task
      _ <- ExampleUtils.printSubSection("3. Security Audit Delegation")
      task3 = "Conduct a security audit of our API endpoints"
      prompt3 = Prompt.simple(
        "You are a host agent coordinating specialists. Given a task, select the most appropriate specialist and explain why. Available specialists: code_specialist, data_specialist, security_specialist, performance_specialist. Use exact names.",
        s"Task: $task3"
      )
      result3 <- structured.complete[SpecialistDelegation](prompt3)
      _ <- IO.println(s"   Task: $task3")
      _ <- IO.println(s"   → Delegated to: ${result3.specialist}")
      _ <- IO.println(s"   → Rationale: ${result3.rationale}")

      // Example 4: Performance optimization task
      _ <- ExampleUtils.printSubSection("4. Performance Optimization Delegation")
      task4 = "Optimize database queries that are causing slow page loads"
      prompt4 = Prompt.simple(
        "You are a host agent coordinating specialists. Given a task, select the most appropriate specialist and explain why. Available specialists: code_specialist, data_specialist, security_specialist, performance_specialist. Use exact names.",
        s"Task: $task4"
      )
      result4 <- structured.complete[SpecialistDelegation](prompt4)
      _ <- IO.println(s"   Task: $task4")
      _ <- IO.println(s"   → Delegated to: ${result4.specialist}")
      _ <- IO.println(s"   → Rationale: ${result4.rationale}")

      _ <- IO.println("\nSpecialist delegation example completed.")
    yield ()

/**
 * Mock LLM client that returns schema-compliant JSON for specialist delegation.
 */
class SpecialistDelegationMockLLMClient extends org.llm4s.llmconnect.LLMClient:
  import org.llm4s.llmconnect.model.*
  import java.util.UUID

  def complete(conversation: Conversation, options: CompletionOptions): Either[org.llm4s.error.LLMError, Completion] =
    val lastUserMessage: String = conversation.messages.collect {
      case msg: UserMessage => msg.content
    }.lastOption.getOrElse("")

    val response: String =
      if lastUserMessage.contains("pull request") || lastUserMessage.contains("authentication feature") then
        """{"specialist": "code_specialist", "rationale": "Authentication features require careful code review to ensure correctness and maintainability"}"""
      else if lastUserMessage.contains("engagement metrics") || lastUserMessage.contains("dashboard") then
        """{"specialist": "data_specialist", "rationale": "Creating dashboards and analyzing metrics requires data analysis expertise and visualization skills"}"""
      else if lastUserMessage.contains("security audit") || lastUserMessage.contains("API endpoints") then
        """{"specialist": "security_specialist", "rationale": "API security audits require specialized knowledge of vulnerabilities and attack vectors"}"""
      else if lastUserMessage.contains("optimize") || lastUserMessage.contains("slow page loads") then
        """{"specialist": "performance_specialist", "rationale": "Database query optimization and performance tuning requires specialized performance analysis skills"}"""
      else
        """{"specialist": "code_specialist", "rationale": "Default to code specialist for general tasks"}"""

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
