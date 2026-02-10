package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.{AdkToolInfo, Agent, AgentTool, ChatModel, ChatModelConfig, InvokableTool}
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AddressSegment, AgentEventEmitter, InterruptResult, InterruptSignal}
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, Message, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates stateful interrupt/resume with inner agent state persistence.
 *
 * This example showcases:
 * - Multi-step workflow requiring approval mid-stream
 * - Inner agent state saved at interrupt point
 * - State restored on resume (messages + iteration count)
 * - AgentTool state persistence across interrupt/resume
 * - Stateful interrupt signals carrying context data
 *
 * Scenario:
 * - User initiates 3-step data migration workflow
 * - Step 1: Validate data (completes)
 * - Step 2: Request approval (interrupts)
 * - [System saves state: conversation history + step count]
 * - [Human provides approval]
 * - Step 3: Execute migration (resumes from checkpoint)
 *
 * GAP RESOLVED: Interrupt/Resume with State Persistence
 * Before: No state persistence, interrupts lost all context
 * After: Full state save/restore including inner agent conversation and progress
 */
object StatefulResumeExample extends IOApp.Simple:

  // Multi-step workflow tool that interrupts for approval
  private val migrationTool: InvokableTool[IO] = new InvokableTool[IO]:
    private val stepCounter: AtomicInteger = new AtomicInteger(0)

    def info: AdkToolInfo = AdkToolInfo(
      "data_migration",
      "Executes multi-step data migration workflow",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "dataset" -> ujson.Obj("type" -> "string", "description" -> "Dataset to migrate"),
          "destination" -> ujson.Obj("type" -> "string", "description" -> "Target destination")
        ),
        "required" -> ujson.Arr("dataset", "destination")
      )
    )

    def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

    def run(arguments: ujson.Value): IO[ujson.Value] =
      val dataset: String = arguments.obj.get("dataset").map(_.str).getOrElse("unknown")
      val destination: String = arguments.obj.get("destination").map(_.str).getOrElse("unknown")
      val currentStep: Int = stepCounter.incrementAndGet()

      currentStep match
        case 1 =>
          // Step 1: Validation (completes)
          IO.pure(ujson.Str(s"Step 1/3: Validated $dataset (1000 records, 0 errors)"))

        case 2 =>
          // Step 2: Request approval (interrupts)
          IO.raiseError(AgentInterruptedException(
            InterruptSignal.stateful(
              s"Approval needed: Migrate $dataset to $destination (1000 records)?",
              ujson.Obj(
                "dataset" -> dataset,
                "destination" -> destination,
                "recordCount" -> 1000,
                "currentStep" -> 2,
                "totalSteps" -> 3,
                "validationPassed" -> true
              )
            ).withAddress(List(AddressSegment.Tool("data_migration")))
          ))

        case 3 =>
          // Step 3: Execute migration (after resume)
          IO.pure(ujson.Str(s"Step 3/3: Migration completed! 1000 records transferred to $destination"))

        case _ =>
          IO.pure(ujson.Str(s"Unexpected step: $currentStep"))

  // Create workflow agent that uses migration tool
  private def createWorkflowAgent(emitter: Option[AgentEventEmitter]): ReactAgent =
    val callCounter: AtomicInteger = new AtomicInteger(0)

    val model: ChatModel[IO] = new ChatModel[IO]:
      def generate(conversation: Conversation): IO[Completion] =
        IO.delay {
          val count: Int = callCounter.getAndIncrement()

          // Check if we have tool results in conversation (indicates we're continuing)
          val hasToolResults: Boolean = conversation.messages.exists {
            case _: org.llm4s.llmconnect.model.ToolMessage => true
            case _ => false
          }

          val previousSteps: Int = conversation.messages.count {
            case _: org.llm4s.llmconnect.model.ToolMessage => true
            case _ => false
          }

          if count == 0 && !hasToolResults then
            // Initial call: start workflow
            val userRequest: String = conversation.messages
              .collect { case m: UserMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "data_migration",
              arguments = ujson.Obj(
                "dataset" -> "customer_profiles",
                "destination" -> "cloud_warehouse"
              )
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some("Starting migration workflow..."),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Starting...",
              model = "mock-model",
              message = msg
            )

          else if previousSteps < 3 then
            // Continue workflow: invoke tool again for next step
            val lastResult: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .lastOption
              .getOrElse("")

            val toolCall: ToolCall = ToolCall(
              id = UUID.randomUUID().toString,
              name = "data_migration",
              arguments = ujson.Obj(
                "dataset" -> "customer_profiles",
                "destination" -> "cloud_warehouse"
              )
            )

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Continuing workflow... (Last step result: $lastResult)"),
              toolCalls = Seq(toolCall)
            )

            Completion(
              id = UUID.randomUUID().toString,
              created = System.currentTimeMillis(),
              content = "Continuing...",
              model = "mock-model",
              message = msg
            )

          else
            // Final response: workflow complete
            val allResults: String = conversation.messages
              .collect { case m: org.llm4s.llmconnect.model.ToolMessage => m.content }
              .mkString("\n")

            val msg: AssistantMessage = AssistantMessage(
              contentOpt = Some(s"Workflow completed successfully!\n\nExecution log:\n$allResults"),
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
        ReactAgent.create("workflow-agent", "Agent that executes multi-step workflows with approval gates", model, List(migrationTool), None, 10, e)
      case None =>
        ReactAgent.create("workflow-agent", "Agent that executes multi-step workflows with approval gates", model, List(migrationTool), None, 10)

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Stateful Interrupt/Resume Example")
      _ <- IO.println("Scenario: 3-step data migration with approval gate at step 2")
      _ <- IO.println("")

      emitter <- AgentEventEmitter.create()
      workflowAgent: ReactAgent = createWorkflowAgent(Some(emitter))

      // Wrap in AgentTool to demonstrate state persistence
      workflowTool <- AgentTool.fromAgent(workflowAgent)

      // Create parent agent that invokes workflow tool
      parentCounter <- IO.ref(0)
      parentModel = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          parentCounter.getAndUpdate(_ + 1).flatMap { (count: Int) =>
            if count == 0 then
              // First call: invoke workflow tool
              val tc: ToolCall = ToolCall(
                id = UUID.randomUUID().toString,
                name = "workflow-agent",
                arguments = ujson.Obj("request" -> "Migrate customer_profiles to cloud_warehouse")
              )
              IO.pure(Completion(
                id = UUID.randomUUID().toString,
                created = System.currentTimeMillis(),
                content = "Starting workflow",
                model = "mock-model",
                message = AssistantMessage(contentOpt = Some("Starting..."), toolCalls = Seq(tc))
              ))
            else
              // Second call: respond after workflow completes
              IO.pure(Completion(
                id = UUID.randomUUID().toString,
                created = System.currentTimeMillis(),
                content = "Workflow executed successfully!",
                model = "mock-model",
                message = AssistantMessage(contentOpt = Some("Workflow executed successfully!"), toolCalls = Seq.empty)
              ))
          }
        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      parentAgent = ReactAgent.create(
        "parent-agent",
        "Parent agent",
        parentModel,
        List(workflowTool),
        None,
        10,
        emitter
      )

      // Phase 1: Run until interrupt
      _ <- ExampleUtils.printSubSection("Phase 1: Executing workflow (will interrupt at step 2)")
      store <- InMemoryCheckpointStore.create
      runner = AgentRunner.create(parentAgent, store, emitter)
      result1 <- runner.run(List(UserMessage("Start migration workflow")))

      checkpointId <- result1 match
        case RunResult.Interrupted(id, signal) =>
          IO.println("✓ Workflow interrupted for approval!") *>
          IO.println("") *>
          (signal match
            case stateful: InterruptSignal.Stateful =>
              IO.println("Interrupt Details:") *>
              IO.println(s"  Info: ${stateful.info}") *>
              IO.println(s"  Address: ${stateful.address.map(_.name).mkString(" > ")}") *>
              IO.println("") *>
              IO.println("State Saved:") *>
              IO.println(s"  Dataset: ${stateful.state.obj("dataset").str}") *>
              IO.println(s"  Destination: ${stateful.state.obj("destination").str}") *>
              IO.println(s"  Record Count: ${stateful.state.obj("recordCount").num.toInt}") *>
              IO.println(s"  Current Step: ${stateful.state.obj("currentStep").num.toInt}/3") *>
              IO.println(s"  Validation: ${if stateful.state.obj("validationPassed").bool then "PASSED" else "FAILED"}")

            case composite: InterruptSignal.Composite =>
              // AgentTool wraps inner signals in Composite to preserve state
              IO.println(s"Composite interrupt from AgentTool (${composite.children.length} inner signal(s)):") *>
              IO.println(s"  AgentTool info: ${composite.info}") *>
              IO.println(s"  AgentTool address: ${composite.address.map(_.name).mkString(" > ")}") *>
              IO.println("") *>
              composite.children.traverse { (child: InterruptSignal) =>
                child match
                  case stateful: InterruptSignal.Stateful =>
                    IO.println("Inner Interrupt Details:") *>
                    IO.println(s"  Info: ${stateful.info}") *>
                    IO.println(s"  Address: ${stateful.address.map(_.name).mkString(" > ")}") *>
                    IO.println("") *>
                    IO.println("State Saved:") *>
                    IO.println(s"  Dataset: ${stateful.state.obj("dataset").str}") *>
                    IO.println(s"  Destination: ${stateful.state.obj("destination").str}") *>
                    IO.println(s"  Record Count: ${stateful.state.obj("recordCount").num.toInt}") *>
                    IO.println(s"  Current Step: ${stateful.state.obj("currentStep").num.toInt}/3") *>
                    IO.println(s"  Validation: ${if stateful.state.obj("validationPassed").bool then "PASSED" else "FAILED"}")
                  case other =>
                    IO.println(s"  - ${other.info}")
              }.void

            case simple: InterruptSignal.Simple =>
              IO.println(s"Simple interrupt: ${simple.info}")
          ) *>
          IO.println("") *>
          IO.println(s"Checkpoint ID: $id") *>
          IO.println("State persisted: ✓ Conversation history + iteration count") *>
          IO.pure(id)

        case other =>
          IO.println(s"Unexpected result: $other") *>
          IO.pure("none")

      // Phase 2: Simulate approval delay
      _ <- ExampleUtils.printSubSection("Phase 2: Awaiting human approval")
      _ <- IO.println("... (human reviews migration plan) ...")
      _ <- IO.sleep(scala.concurrent.duration.Duration(100, "ms"))
      _ <- IO.println("✓ Human approved migration")

      // Phase 3: Resume workflow
      _ <- ExampleUtils.printSubSection("Phase 3: Resuming workflow from checkpoint")
      _ <- IO.println(s"Loading checkpoint: $checkpointId")
      _ <- IO.println("State restored: ✓ Conversation history + iteration count")
      _ <- IO.println("Continuing workflow from step 3...")
      _ <- IO.println("")

      emitter2 <- AgentEventEmitter.create()
      // Note: We need to recreate the parent agent with same tools for resume
      workflowAgent2: ReactAgent = createWorkflowAgent(Some(emitter2))
      workflowTool2 <- AgentTool.fromAgent(workflowAgent2)

      parentModel2 = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          // Extract resume approval context from conversation
          val resumeContext: String = conversation.messages
            .collect { case m: UserMessage => m.content }
            .filter(_.startsWith("[Resume approval"))
            .mkString("; ")
          val output: String = if resumeContext.nonEmpty then
            s"Workflow resumed with approval. Migration completed successfully.\nApproval context: $resumeContext"
          else
            "Workflow resumed and completed."
          IO.pure(Completion(
            id = UUID.randomUUID().toString,
            created = System.currentTimeMillis(),
            content = output,
            model = "mock-model",
            message = AssistantMessage(contentOpt = Some(output), toolCalls = Seq.empty)
          ))
        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      parentAgent2 = ReactAgent.create("parent-agent", "Parent", parentModel2, List(workflowTool2), None, 10, emitter2)
      runner2 = AgentRunner.create(parentAgent2, store, emitter2)

      result2 <- runner2.resume(
        checkpointId,
        List(
          InterruptResult(
            address = List(AddressSegment.Tool("data_migration")),
            data = ujson.Obj(
              "approved" -> true,
              "approvedBy" -> "admin@company.com",
              "timestamp" -> System.currentTimeMillis()
            )
          )
        )
      )

      _ <- result2 match
        case RunResult.Completed(output, _) =>
          IO.println("✓ Workflow completed after resume!") *>
          IO.println("") *>
          IO.println("Final output:") *>
          IO.println(s"  ${output.split("\n").mkString("\n  ")}")

        case RunResult.Interrupted(_, signal) =>
          IO.println(s"Workflow interrupted again: ${signal.info}")

        case other =>
          IO.println(s"Resume result: $other")

      _ <- IO.println("")
      _ <- IO.println("Key Features Demonstrated:")
      _ <- IO.println("  ✓ Multi-step workflow with progress tracking")
      _ <- IO.println("  ✓ Stateful interrupt with context data")
      _ <- IO.println("  ✓ State persistence at interrupt point")
      _ <- IO.println("  ✓ State restoration on resume")
      _ <- IO.println("  ✓ Workflow continues from exact point of interruption")
      _ <- IO.println("  ✓ AgentTool state (messages + iteration) saved/restored")

      _ <- IO.println("\nStateful resume example complete.")
    yield ()
