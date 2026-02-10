package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.{Agent, AgentTool, ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter}
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates hierarchical event streaming with RunPath tracking.
 *
 * This example showcases:
 * - Event stream from nested agents (parent → AgentTool → inner agent)
 * - RunPath hierarchy showing execution context
 * - Event types at different nesting levels
 * - Real-time observability into agent execution flow
 * - Event forwarding through AgentTool boundaries
 *
 * Architecture:
 * - Parent Agent: Orchestrator
 *   └─ Research Agent (via AgentTool): Performs research
 *      └─ Search Tool: Executes searches
 *
 * Events flow:
 * - Parent: ToolCallRequested[research-agent]
 * - Inner: ToolCallRequested[search] @ research-agent
 * - Inner: ToolCallCompleted[search] @ research-agent
 * - Inner: IterationCompleted @ research-agent
 * - Inner: MessageOutput @ research-agent
 * - Parent: ToolCallCompleted[research-agent]
 * - Parent: MessageOutput
 *
 * GAP RESOLVED: Event Streaming with Hierarchical RunPath
 * Before: No visibility into nested agent execution
 * After: Full event stream with hierarchical RunPath tracking
 */
object HierarchicalEventStreamExample extends IOApp.Simple:

  // Inner tool: Search tool used by research agent
  private val searchTool: InvokableTool[IO] = Tool.invokable[IO](
    "search",
    "Searches knowledge base for information",
    (args: ujson.Value) => {
      val query: String = args.obj.get("query").map(_.str).getOrElse("")
      val results: String = s"Search results for '$query':\n1. Article A\n2. Article B\n3. Article C"
      Right(ujson.Str(results))
    }
  )

  // Create research agent that uses search tool
  private def createResearchAgent(emitter: Option[AgentEventEmitter]): ReactAgent =
    val counter: AtomicInteger = new AtomicInteger(0)

    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val count: Int = counter.getAndIncrement()
          if count == 0 then
            // First call: use search tool
            val request: String = conversation.messages
              .collect { case m: UserMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "search",
              arguments = ujson.Obj("query" -> request)
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some("Let me search for that..."),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Searching...",
              model = "mock-model",
              message = msg
            )
          else
            // Second call: synthesize results
            val searchResults: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .lastOption
              .getOrElse("No results")

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Based on my research:\n$searchResults\n\nThis covers the main points."),
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

    emitter match
      case Some(e) =>
        ReactAgent.create("research-agent", "Agent that performs research using search tools", model, List(searchTool), None, 5, e)
      case None =>
        ReactAgent.create("research-agent", "Agent that performs research using search tools", model, List(searchTool), None, 5)

  // Create parent orchestrator agent
  private def createOrchestratorAgent(researchTool: InvokableTool[IO], emitter: Option[AgentEventEmitter]): ReactAgent =
    val counter: AtomicInteger = new AtomicInteger(0)

    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val count: Int = counter.getAndIncrement()
          if count == 0 then
            // First call: delegate to research agent
            val request: String = conversation.messages
              .collect { case m: UserMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "research-agent",
              arguments = ujson.Obj("request" -> request)
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some("I'll delegate this to our research team..."),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Delegating...",
              model = "mock-model",
              message = msg
            )
          else
            // Second call: synthesize final response
            val researchResults: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .lastOption
              .getOrElse("No results")

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Here's what our research team found:\n\n$researchResults\n\nLet me know if you need more details!"),
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

    emitter match
      case Some(e) =>
        ReactAgent.create("orchestrator", "Orchestrator agent that delegates to specialist agents", model, List(researchTool), None, 5, e)
      case None =>
        ReactAgent.create("orchestrator", "Orchestrator agent that delegates to specialist agents", model, List(researchTool), None, 5)

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Hierarchical Event Stream Example")
      _ <- IO.println("Architecture: Orchestrator → Research Agent → Search Tool")
      _ <- IO.println("Watch how events flow through nested agent calls with RunPath tracking")
      _ <- IO.println("")

      // Create emitter that will be shared across hierarchy
      emitter <- AgentEventEmitter.create()

      // Build agent hierarchy
      researchAgent: ReactAgent = createResearchAgent(Some(emitter))
      researchTool <- AgentTool.fromAgent(researchAgent)
      orchestratorAgent: ReactAgent = createOrchestratorAgent(researchTool, Some(emitter))

      // Set up runner
      store <- InMemoryCheckpointStore.create
      runner = AgentRunner.create(orchestratorAgent, store, emitter)
      (resultIO, eventStream) = runner.runWithEvents(List(UserMessage("Research quantum computing applications")))

      _ <- ExampleUtils.printSubSection("Event Stream (real-time)")
      _ <- IO.println("")

      // Track event counts and hierarchy
      eventCounts <- IO.ref(Map.empty[String, Int])

      // Run concurrently: consume events and wait for result
      resultFiber <- resultIO.start

      events <- eventStream.evalTap { (event: AgentEvent) =>
        val eventType: String = event match
          case _: AgentEvent.MessageOutput     => "MessageOutput"
          case _: AgentEvent.ToolCallRequested  => "ToolCallRequested"
          case _: AgentEvent.ToolCallCompleted  => "ToolCallCompleted"
          case _: AgentEvent.IterationCompleted => "IterationCompleted"
          case _: AgentEvent.Interrupted        => "Interrupted"
          case _: AgentEvent.ErrorOccurred      => "ErrorOccurred"
          case _: AgentEvent.TokenDelta         => "TokenDelta"

        for
          _ <- eventCounts.update { (counts: Map[String, Int]) =>
            counts.updated(eventType, counts.getOrElse(eventType, 0) + 1)
          }
          line <- IO.delay {
            val path: String = event.runPath.show
            val depth: Int = if path.isEmpty then 0 else path.count(_ == '/')
            val indent: String = "  " * depth
            val pathDisplay: String = if path.isEmpty then "(root)" else path

            val details: String = event match
              case e: AgentEvent.ToolCallRequested =>
                s"tool=${e.toolName}"
              case e: AgentEvent.ToolCallCompleted =>
                val status: String = if e.isError then "ERROR" else "OK"
                s"tool=${e.toolName} status=$status"
              case e: AgentEvent.MessageOutput =>
                val preview: String = e.message.take(50)
                s"msg='${preview}${if e.message.length > 50 then "..." else ""}'"
              case _ => ""

            s"$indent[$eventType] @ $pathDisplay${if details.nonEmpty then s" ($details)" else ""}"
          }
          _ <- IO.println(line)
        yield ()
      }.compile.toList

      result <- resultFiber.joinWithNever

      // Print summary
      _ <- ExampleUtils.printSubSection("Summary")
      _ <- IO.println("")
      _ <- result match
        case RunResult.Completed(output, _) =>
          IO.println("✓ Execution completed successfully") *>
          IO.println("") *>
          IO.println("Final output (truncated):") *>
          IO.println(s"  ${output.take(100)}...")

        case other =>
          IO.println(s"Result: $other")

      _ <- IO.println("")
      _ <- IO.println("Event Statistics:")
      counts <- eventCounts.get
      _ <- counts.toList.sortBy(_._1).traverse { case (eventType, count) =>
        IO.println(s"  $eventType: $count")
      }
      _ <- IO.println(s"  TOTAL: ${events.length}")

      _ <- IO.println("")
      _ <- IO.println("Hierarchy Analysis:")
      _ <- IO.delay {
        val byPath: Map[String, Int] = events.groupBy(_.runPath.show).view.mapValues(_.length).toMap
        byPath.toList.sortBy(_._1).foreach { case (path, count) =>
          val display: String = if path.isEmpty then "(root)" else path
          println(s"  $display: $count events")
        }
      }

      _ <- IO.println("")
      _ <- IO.println("Key Features Demonstrated:")
      _ <- IO.println("  ✓ Events flow through nested agent boundaries")
      _ <- IO.println("  ✓ RunPath shows execution context hierarchy")
      _ <- IO.println("  ✓ Real-time observability into agent internals")
      _ <- IO.println("  ✓ Event forwarding from AgentTool to parent emitter")
      _ <- IO.println("  ✓ Complete visibility: outer + inner agent events")

      _ <- IO.println("\nHierarchical event stream example complete.")
    yield ()
