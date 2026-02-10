package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import org.adk4s.core.component.{AdkToolInfo, Agent, ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AddressSegment, AgentEventEmitter, InterruptResult, InterruptSignal}
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates interrupt/resume: a tool requests human approval,
 * the agent interrupts, state is persisted, then resumed with approval.
 */
object InterruptResumeExample extends IOApp.Simple:

  /** A tool that always interrupts to request approval. */
  private val approvalTool: InvokableTool[IO] = new InvokableTool[IO]:
    def info: AdkToolInfo = AdkToolInfo(
      "request_approval",
      "Requests human approval before proceeding",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "action" -> ujson.Obj("type" -> "string", "description" -> "The action to approve")
        ),
        "required" -> ujson.Arr("action")
      )
    )
    def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None
    def run(arguments: ujson.Value): IO[ujson.Value] =
      val action: String = arguments.obj.get("action").map(_.str).getOrElse("unknown action")
      IO.raiseError(AgentInterruptedException(
        InterruptSignal.simple(s"Approval needed for: $action")
          .withAddress(List(AddressSegment.Tool("request_approval")))
      ))

  private def makeCompletion(content: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "mock-model",
      message = msg
    )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Interrupt/Resume Example")

      // Create agent with the approval tool
      // Mock model: first call makes a tool call, second call returns final answer
      callCount <- IO.ref(0)
      model = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          callCount.getAndUpdate(_ + 1).flatMap { (count: Int) =>
            if count == 0 then
              // First call: request the approval tool
              val tc: ToolCall = ToolCall(
                id = UUID.randomUUID().toString,
                name = "request_approval",
                arguments = ujson.Obj("action" -> "delete user 42")
              )
              IO.pure(makeCompletion("", Seq(tc)))
            else
              IO.pure(makeCompletion("Action completed after approval."))
          }
        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      agent = ReactAgent.create("approval-agent", "Agent with approval workflow", model, List(approvalTool), None, 10)

      // Set up runner with checkpoint store
      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(agent, store, emitter)

      // Phase 1: Run agent — it will interrupt
      _ <- ExampleUtils.printSubSection("Phase 1: Running agent (will interrupt)")
      result1 <- runner.run(List(UserMessage("Delete user 42")))
      _ <- result1 match
        case RunResult.Interrupted(checkpointId, signal) =>
          IO.println(s"Agent interrupted!") *>
          IO.println(s"  Checkpoint ID: $checkpointId") *>
          IO.println(s"  Signal: ${signal.info}") *>
          IO.println(s"  Address: ${signal.address.map(_.name).mkString(" > ")}")
        case other =>
          IO.println(s"Unexpected result: $other")

      // Phase 2: Simulate human approval and resume
      _ <- ExampleUtils.printSubSection("Phase 2: Resuming with approval")
      checkpointId = result1 match
        case RunResult.Interrupted(id, _) => id
        case _ => "none"
      emitter2 <- AgentEventEmitter.create()
      runner2 = AgentRunner.create(agent, store, emitter2)
      result2 <- runner2.resume(checkpointId, List(
        InterruptResult(
          address = List(AddressSegment.Tool("request_approval")),
          data = ujson.Obj("approved" -> true)
        )
      ))
      _ <- result2 match
        case RunResult.Completed(output, _) =>
          IO.println(s"Agent completed after resume!") *>
          IO.println(s"  Output: $output")
        case other =>
          IO.println(s"Resume result: $other")

      _ <- IO.println("\nInterrupt/Resume example complete.")
    yield ()
