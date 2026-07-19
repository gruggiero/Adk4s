package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import org.adk4s.core.component.{ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.core.interrupt.{AgentEvent, AgentEventEmitter}
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates real-time event streaming from agent execution.
 * Events are printed to the console as they occur.
 */
object EventStreamExample extends IOApp.Simple:

  private def makeCompletion(content: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "mock-model",
      message = msg
    )

  private val echoTool: InvokableTool[IO] = Tool.invokable[IO](
    "echo",
    "Echoes the input back",
    (args: ujson.Value) => Right(ujson.Str(s"Echo: ${args.toString}"))
  )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Event Stream Example")

      // Mock model: makes a tool call, then responds
      counter <- IO.ref(0)
      model = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          counter.getAndUpdate(_ + 1).flatMap { (count: Int) =>
            if count == 0 then
              val tc: ToolCall = ToolCall(
                id = UUID.randomUUID().toString,
                name = "echo",
                arguments = ujson.Obj("message" -> "hello world")
              )
              IO.pure(makeCompletion("", Seq(tc)))
            else
              IO.pure(makeCompletion("I echoed your message successfully!"))
          }
        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      // Create agent with emitter
      emitter <- AgentEventEmitter.create()
      agent = ReactAgent.create("event-agent", "Agent with event streaming", model, List(echoTool), None, 10, emitter)

      // Set up runner
      store <- InMemoryCheckpointStore.create
      runner = AgentRunner.create(agent, store, emitter)
      (resultIO, eventStream) = runner.runWithEvents(List(UserMessage("Echo hello world")))

      _ <- ExampleUtils.printSubSection("Streaming events from agent execution")

      // Run both concurrently: consume events and wait for result
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
          case _: AgentEvent.MemoryRecalled     => "MemoryRecalled"
          case _: AgentEvent.MemoryWritten      => "MemoryWritten"
        IO.println(s"  [EVENT] $eventType @ ${event.runPath.show}")
      }.compile.toList
      result <- resultFiber.joinWithNever

      _ <- ExampleUtils.printSubSection("Result")
      _ <- result match
        case RunResult.Completed(output, _) =>
          IO.println(s"  Output: $output")
        case other =>
          IO.println(s"  Result: $other")
      _ <- IO.println(s"  Total events received: ${events.length}")

      _ <- IO.println("\nEvent stream example complete.")
    yield ()
