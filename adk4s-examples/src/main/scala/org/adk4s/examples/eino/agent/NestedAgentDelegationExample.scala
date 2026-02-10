package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import org.adk4s.core.component.{Agent, AgentTool, ChatModel, ChatModelConfig, InvokableTool}
import org.adk4s.core.interrupt.AgentEventEmitter
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates nested agent delegation: Supervisor → Specialist → Sub-specialist
 *
 * This example showcases:
 * - Multi-level agent hierarchy using AgentTool
 * - Hierarchical delegation pattern
 * - Each level of nesting working independently
 * - Address tracking through nested agents
 *
 * Architecture:
 * - Supervisor Agent: Top-level coordinator
 * - Data Specialist Agent: Delegates to sub-specialists for different data types
 * - Query Sub-specialist: Executes actual queries
 *
 * GAP RESOLVED: Agent-as-Tool (AgentTool)
 * Before: Agents couldn't delegate to other agents as tools
 * After: Full hierarchical agent delegation with address tracking
 */
object NestedAgentDelegationExample extends IOApp.Simple:

  // Level 3: Query sub-specialist (leaf agent)
  private val queryAgent: Agent = new Agent:
    val name: String = "query-specialist"
    val description: String = "Executes database queries and returns results"

    def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
      val request: String = messages.collect { case m: UserMessage => m.content }.lastOption.getOrElse("")
      val result: String =
        if request.toLowerCase.contains("user") then
          """{"query": "SELECT * FROM users", "results": [{"id": 1, "name": "Alice", "role": "admin"}]}"""
        else if request.toLowerCase.contains("order") then
          """{"query": "SELECT * FROM orders", "results": [{"id": 101, "amount": 49.99, "status": "completed"}]}"""
        else if request.toLowerCase.contains("product") then
          """{"query": "SELECT * FROM products", "results": [{"id": 5, "name": "Widget", "price": 29.99}]}"""
        else
          s"""{"query": "UNKNOWN", "results": [], "note": "Query type not recognized"}"""

      IO.pure(AssistantMessage(
        contentOpt = Some(s"Query executed successfully:\n$result"),
        toolCalls = Seq.empty
      ))

  // Level 2: Data specialist that delegates to query sub-specialist
  private def createDataSpecialist(queryTool: InvokableTool[IO]): ReactAgent =
    val counter: AtomicInteger = new AtomicInteger(0)

    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val count: Int = counter.getAndIncrement()
          if count == 0 then
            // First call: delegate to query specialist
            val lastUserMsg: String = conversation.messages
              .collect { case m: UserMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "query-specialist",
              arguments = ujson.Obj("request" -> lastUserMsg)
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some("I'll delegate this to our query specialist..."),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Delegating to query specialist",
              model = "mock-model",
              message = msg
            )
          else
            // Second call: return final response after tool execution
            val toolResults: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .lastOption
              .getOrElse("No results")

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Data specialist processed the query. Here are the results:\n$toolResults"),
              toolCalls = Seq.empty
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = msg.contentOpt.getOrElse(""),
              model = "mock-model",
              message = msg
            )
        }

      def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
      def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO] = this

    ReactAgent.create(
      "data-specialist",
      "Specialist for data queries - delegates to query sub-specialists",
      model,
      List(queryTool),
      None,
      maxSteps = 5
    )

  // Level 1: Supervisor agent that delegates to data specialist
  private def createSupervisor(dataSpecialistTool: InvokableTool[IO]): ReactAgent =
    val counter: AtomicInteger = new AtomicInteger(0)

    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val count: Int = counter.getAndIncrement()
          if count == 0 then
            // First call: delegate to data specialist
            val userRequest: String = conversation.messages
              .collect { case m: UserMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "data-specialist",
              arguments = ujson.Obj("request" -> userRequest)
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some("I'll route this to our data specialist team..."),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Routing to data specialist",
              model = "mock-model",
              message = msg
            )
          else
            // Second call: synthesize final response
            val specialistResults: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .lastOption
              .getOrElse("No results")

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Request processed through our specialist teams:\n\n$specialistResults\n\nIs there anything else you'd like to know?"),
              toolCalls = Seq.empty
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = msg.contentOpt.getOrElse(""),
              model = "mock-model",
              message = msg
            )
        }

      def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
      def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO] = this

    ReactAgent.create(
      "supervisor",
      "Supervisor agent that coordinates specialist teams",
      model,
      List(dataSpecialistTool),
      None,
      maxSteps = 5
    )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Nested Agent Delegation Example")
      _ <- IO.println("Architecture: Supervisor → Data Specialist → Query Specialist")
      _ <- IO.println("")

      // Build hierarchy from bottom up
      _ <- ExampleUtils.printSubSection("1. Creating Query Specialist (Level 3)")
      queryTool <- AgentTool.fromAgent(queryAgent)
      _ <- IO.println(s"   Created: ${queryTool.info.name}")

      _ <- ExampleUtils.printSubSection("2. Creating Data Specialist (Level 2)")
      dataSpecialistAgent: ReactAgent = createDataSpecialist(queryTool)
      dataSpecialistTool <- AgentTool.fromAgent(dataSpecialistAgent)
      _ <- IO.println(s"   Created: ${dataSpecialistTool.info.name}")
      _ <- IO.println(s"   Has access to: ${queryTool.info.name}")

      _ <- ExampleUtils.printSubSection("3. Creating Supervisor (Level 1)")
      supervisorAgent: ReactAgent = createSupervisor(dataSpecialistTool)
      _ <- IO.println(s"   Created: supervisor")
      _ <- IO.println(s"   Has access to: ${dataSpecialistTool.info.name}")

      // Execute nested delegation
      _ <- ExampleUtils.printSubSection("4. Executing Nested Delegation")
      _ <- IO.println("   User request: Find all active users")
      _ <- IO.println("")

      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(supervisorAgent, store, emitter)

      result <- runner.run(List(UserMessage("Find all active users in the system")))

      _ <- result match
        case RunResult.Completed(output, _) =>
          IO.println("   FLOW:") *>
          IO.println("   1. Supervisor received request") *>
          IO.println("   2. Supervisor → delegated to Data Specialist") *>
          IO.println("   3. Data Specialist → delegated to Query Specialist") *>
          IO.println("   4. Query Specialist → executed query") *>
          IO.println("   5. Results bubbled back through hierarchy") *>
          IO.println("") *>
          IO.println("   FINAL OUTPUT:") *>
          IO.println(s"   ${output.split("\n").mkString("\n   ")}")
        case other =>
          IO.println(s"   Unexpected result: $other")

      _ <- IO.println("")
      _ <- IO.println("Key Benefits Demonstrated:")
      _ <- IO.println("  ✓ Each agent layer adds value (routing, processing, execution)")
      _ <- IO.println("  ✓ Clean separation of concerns")
      _ <- IO.println("  ✓ Address tracking through nested calls")
      _ <- IO.println("  ✓ Independent agent teams can be composed")

      _ <- IO.println("\nNested delegation example complete.")
    yield ()
