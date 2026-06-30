package org.adk4s.orchestration.agent

import cats.effect.IO
import munit.CatsEffectSuite
import org.adk4s.core.component.Agent
import org.adk4s.core.component.ChatModel
import org.adk4s.core.component.ChatModelConfig
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.core.error.{AgentInterruptedException, CheckpointNotFoundError}
import org.adk4s.core.interrupt.{AgentEventEmitter, InterruptResult, InterruptSignal, AddressSegment}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, UserMessage}
import fs2.Stream

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AgentRunnerTest extends CatsEffectSuite:

  private def makeCompletion(content: String): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
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

      def stream(conversation: Conversation): Stream[IO, StreamedChunk] =
        Stream.empty

      def streamContent(conversation: Conversation): Stream[IO, String] =
        Stream.empty

      def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  private def simpleAgent(response: String): ReactAgent =
    val model: ChatModel[IO] = mockChatModel(List(makeCompletion(response)))
    ReactAgent.create("test-agent", "Test agent", model, List.empty, None, 10)

  private def interruptingAgent(info: String): ReactAgent =
    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.raiseError(AgentInterruptedException(InterruptSignal.simple(info)))
      def stream(conversation: Conversation): Stream[IO, StreamedChunk] = Stream.empty
      def streamContent(conversation: Conversation): Stream[IO, String] = Stream.empty
      def withConfig(config: ChatModelConfig): ChatModel[IO] = this
    ReactAgent.create("interrupting-agent", "Agent that interrupts", model, List.empty, None, 10)

  test("run returns Completed when agent finishes normally") {
    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(simpleAgent("Hello!"), store, emitter)
      result <- runner.run(List(UserMessage("Hi")))
    yield
      result match
        case RunResult.Completed(output, _) =>
          assertEquals(output, "Hello!")
        case other =>
          fail(s"Expected Completed, got $other")
  }

  test("run returns Interrupted and saves checkpoint when agent interrupts") {
    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(interruptingAgent("Need approval"), store, emitter)
      result <- runner.run(List(UserMessage("Do something")))
    yield
      result match
        case RunResult.Interrupted(checkpointId, signal) =>
          assertEquals(signal.info, "Need approval")
          assert(checkpointId.nonEmpty)
        case other =>
          fail(s"Expected Interrupted, got $other")
  }

  test("checkpoint is stored on interrupt") {
    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(interruptingAgent("Approve?"), store, emitter)
      result <- runner.run(List(UserMessage("query")))
      checkpointId = result match
        case RunResult.Interrupted(id, _) => id
        case _ => fail("Expected Interrupted")
      checkpoint <- store.get(checkpointId)
    yield assert(checkpoint.isDefined)
  }

  test("resume with invalid checkpoint returns Failed") {
    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(simpleAgent("ok"), store, emitter)
      result <- runner.resume("nonexistent-id", List.empty)
    yield
      result match
        case RunResult.Failed(error) =>
          assert(error.message.contains("nonexistent-id"))
        case other =>
          fail(s"Expected Failed, got $other")
  }

  test("runWithEvents returns event stream") {
    for
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(simpleAgent("Result"), store, emitter)
      (resultIO, eventStream) = runner.runWithEvents(List(UserMessage("Go")))
      // Start both concurrently
      resultFiber <- resultIO.start
      events <- eventStream.compile.toList
      result <- resultFiber.joinWithNever
    yield
      result match
        case RunResult.Completed(output, _) =>
          assertEquals(output, "Result")
        case other =>
          fail(s"Expected Completed, got $other")
      assert(events.nonEmpty)
  }
