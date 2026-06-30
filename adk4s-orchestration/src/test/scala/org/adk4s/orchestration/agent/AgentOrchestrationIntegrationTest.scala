package org.adk4s.orchestration.agent

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.{Agent, AgentTool, AgentToolConfig, ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AddressSegment, AgentEvent, AgentEventEmitter, InterruptSignal, RunPath}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, ToolCall, UserMessage}
import fs2.Stream

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AgentOrchestrationIntegrationTest extends CatsEffectSuite:

  private def makeCompletion(content: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = msg
    )

  private def mockChatModel(responses: List[Completion]): ChatModel[IO] =
    val counter: AtomicInteger = new AtomicInteger(0)
    new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val idx: Int = counter.getAndIncrement()
          if idx < responses.length then responses(idx)
          else makeCompletion("fallback")
        }
      def stream(conversation: Conversation): Stream[IO, StreamedChunk] = Stream.empty
      def streamContent(conversation: Conversation): Stream[IO, String] = Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  test("nested agent-tool interrupt propagates address chain") {
    // Inner agent that always interrupts
    val innerAgent: Agent = new Agent:
      val name: String = "database-agent"
      val description: String = "Database query agent"
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        IO.raiseError(AgentInterruptedException(
          InterruptSignal.simple("Approve query?")
            .withAddress(List(AddressSegment.Tool("query")))
        ))

    for
      agentTool <- AgentTool.fromAgent(innerAgent)
      result <- agentTool.run(ujson.Obj("request" -> "SELECT * FROM users")).attempt
    yield
      result match
        case Left(e: AgentInterruptedException) =>
          // AgentTool wraps as Composite with database-agent address
          e.signal match
            case composite: InterruptSignal.Composite =>
              // Composite's address has the AgentTool's agent name
              assertEquals(composite.address, List(AddressSegment.Agent("database-agent")))
              // Child signal has the original Tool address
              assertEquals(composite.children.length, 1)
              val child: InterruptSignal = composite.children.headOption.getOrElse(fail("expected non-empty list"))
              assertEquals(child.address, List(AddressSegment.Tool("query")))
            case other =>
              fail(s"Expected Composite interrupt, got $other")
        case other =>
          fail(s"Expected AgentInterruptedException with address, got $other")
  }

  test("event stream from agent runner captures events with RunPath") {
    val model: ChatModel[IO] = mockChatModel(List(makeCompletion("Done!")))
    val agent: ReactAgent = ReactAgent.create("supervisor", "Supervisor agent", model, List.empty, None, 10)

    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(agent, store, emitter)
      (resultIO, eventStream) = runner.runWithEvents(List(UserMessage("Go")))
      resultFiber <- resultIO.start
      events <- eventStream.compile.toList
      result <- resultFiber.joinWithNever
    yield
      result match
        case RunResult.Completed(output, _) =>
          assertEquals(output, "Done!")
        case other =>
          fail(s"Expected Completed, got $other")

      // Should have at least MessageOutput event
      val messageEvents: List[AgentEvent.MessageOutput] = events.collect {
        case m: AgentEvent.MessageOutput => m
      }
      assert(messageEvents.nonEmpty, s"Expected MessageOutput events, got: $events")
  }

  test("agent-tool invoked by parent agent returns sub-agent result") {
    // Inner agent: responds directly
    val innerAgent: Agent = new Agent:
      val name: String = "calc-agent"
      val description: String = "Calculator agent"
      def generate(messages: List[Message], maxSteps: Int): IO[AssistantMessage] =
        IO.pure(AssistantMessage(contentOpt = Some("42"), toolCalls = Seq.empty))

    for
      agentTool <- AgentTool.fromAgent(innerAgent)
      result <- agentTool.run(ujson.Obj("request" -> "What is 6 * 7?"))
    yield
      result match
        case ujson.Str(s) => assertEquals(s, "42")
        case other        => fail(s"Expected ujson.Str, got $other")
  }
