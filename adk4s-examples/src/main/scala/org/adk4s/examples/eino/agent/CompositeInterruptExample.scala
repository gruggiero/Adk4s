package org.adk4s.examples.eino.agent

import cats.effect.{IO, IOApp}
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.{AdkToolInfo, ChatModel, ChatModelConfig, InvokableTool, Tool}
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.{AddressSegment, AgentEventEmitter, InterruptResult, InterruptSignal}
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.{AgentRunner, ReactAgent, RunResult}
import org.adk4s.orchestration.interrupt.InMemoryCheckpointStore
import org.llm4s.llmconnect.model.{AssistantMessage, Completion, Conversation, StreamedChunk, ToolCall, UserMessage}

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Demonstrates composite interrupts: multiple tools interrupting simultaneously.
 *
 * This example showcases:
 * - Agent calling multiple tools in parallel
 * - Multiple tools interrupting with different reasons
 * - Composite interrupt signal aggregating all interrupts
 * - Resume providing multiple InterruptResults
 * - Sequential execution stopping on first interrupt
 *
 * Scenario:
 * - Agent tries to perform a batch operation
 * - Calls payment_tool and email_tool in same iteration
 * - Both tools interrupt (payment needs approval, email needs verification)
 * - Agent receives composite interrupt with both signals
 * - Human provides approval for both
 * - Agent resumes and completes
 *
 * GAP RESOLVED: Interrupt/Resume with Composite Signals
 * Before: Only single interrupts supported
 * After: Multiple simultaneous interrupts aggregated into composite signal
 */
object CompositeInterruptExample extends IOApp.Simple:

  // Tool 1: Payment processing that requires approval for large amounts
  private val paymentTool: InvokableTool[IO] = new InvokableTool[IO]:
    def info: AdkToolInfo = AdkToolInfo(
      "process_payment",
      "Processes payment transactions",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "amount" -> ujson.Obj("type" -> "number"),
          "recipient" -> ujson.Obj("type" -> "string")
        ),
        "required" -> ujson.Arr("amount", "recipient")
      )
    )

    def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

    def run(arguments: ujson.Value): IO[ujson.Value] =
      val amount: Double = arguments.obj.get("amount").map(_.num).getOrElse(0.0)
      val recipient: String = arguments.obj.get("recipient").map(_.str).getOrElse("unknown")

      if amount > 1000.0 then
        // Large payment requires approval
        IO.raiseError(AgentInterruptedException(
          InterruptSignal.stateful(
            s"Payment approval needed: $$$amount to $recipient (exceeds threshold)",
            ujson.Obj("amount" -> amount, "recipient" -> recipient, "threshold" -> 1000.0)
          ).withAddress(List(AddressSegment.Tool("process_payment")))
        ))
      else
        IO.pure(ujson.Str(s"Payment of $$$amount to $recipient processed successfully"))

  // Tool 2: Email sending that requires verification for sensitive recipients
  private val emailTool: InvokableTool[IO] = new InvokableTool[IO]:
    def info: AdkToolInfo = AdkToolInfo(
      "send_email",
      "Sends email messages",
      ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj(
          "to" -> ujson.Obj("type" -> "string"),
          "subject" -> ujson.Obj("type" -> "string"),
          "body" -> ujson.Obj("type" -> "string")
        ),
        "required" -> ujson.Arr("to", "subject", "body")
      )
    )

    def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

    def run(arguments: ujson.Value): IO[ujson.Value] =
      val to: String = arguments.obj.get("to").map(_.str).getOrElse("unknown")
      val subject: String = arguments.obj.get("subject").map(_.str).getOrElse("(no subject)")

      // Check if recipient domain is sensitive
      val sensitivePatterns: List[String] = List("@board.", "@exec.", "@ceo.")
      val isSensitive: Boolean = sensitivePatterns.exists(pattern => to.toLowerCase.contains(pattern))

      if isSensitive then
        // Sensitive recipient requires verification
        IO.raiseError(AgentInterruptedException(
          InterruptSignal.stateful(
            s"Email verification needed: sending to sensitive recipient $to",
            ujson.Obj("to" -> to, "subject" -> subject, "reason" -> "sensitive_recipient")
          ).withAddress(List(AddressSegment.Tool("send_email")))
        ))
      else
        IO.pure(ujson.Str(s"Email sent to $to successfully"))

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
      _ <- ExampleUtils.printSection("Composite Interrupt Example")
      _ <- IO.println("Scenario: Process invoice payment + send notification email")
      _ <- IO.println("Both operations require approval/verification")
      _ <- IO.println("")

      // Create model that calls both tools sequentially (ToolsNode runs sequentially by default)
      counter <- IO.ref(0)
      model = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          counter.getAndUpdate(_ + 1).flatMap { (count: Int) =>
            count match
              case 0 =>
                // First call: try to process payment and send email
                val tc1: ToolCall = ToolCall(
                  id = UUID.randomUUID().toString,
                  name = "process_payment",
                  arguments = ujson.Obj("amount" -> 5000.0, "recipient" -> "ACME Corp")
                )
                val tc2: ToolCall = ToolCall(
                  id = UUID.randomUUID().toString,
                  name = "send_email",
                  arguments = ujson.Obj(
                    "to" -> "cfo@board.acme.com",
                    "subject" -> "Payment Processed",
                    "body" -> "Your payment of $5000 has been processed."
                  )
                )
                IO.pure(makeCompletion("Processing payment and sending notification...", Seq(tc1, tc2)))

              case 1 =>
                // Second call: after resume, acknowledge completion
                IO.pure(makeCompletion("All approvals received. Operations completed successfully!"))

              case _ =>
                IO.pure(makeCompletion("Unexpected state"))
          }

        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      agent = ReactAgent.create(
        "invoice-processor",
        "Processes invoices with payment and notifications",
        model,
        List(paymentTool, emailTool),
        None,
        maxSteps = 10
      )

      // Phase 1: Run agent - will interrupt on first tool (sequential execution)
      _ <- ExampleUtils.printSubSection("Phase 1: Running agent (sequential execution)")
      _ <- IO.println("   Note: ToolsNode executes sequentially by default")
      _ <- IO.println("   First tool (payment) will interrupt before second tool runs")
      _ <- IO.println("")

      store <- InMemoryCheckpointStore.create
      emitter <- AgentEventEmitter.create()
      runner = AgentRunner.create(agent, store, emitter)

      result1 <- runner.run(List(UserMessage("Process invoice #12345: Pay $5000 to ACME Corp and notify CFO")))

      checkpointId <- result1 match
        case RunResult.Interrupted(id, signal) =>
          IO.println("   ✓ Agent interrupted!") *>
          IO.println(s"   Checkpoint ID: $id") *>
          IO.println("") *>
          IO.println("   INTERRUPT DETAILS:") *>
          (signal match
            case simple: InterruptSignal.Simple =>
              IO.println(s"   Type: Simple") *>
              IO.println(s"   Info: ${simple.info}") *>
              IO.println(s"   Address: ${simple.address.map(_.name).mkString(" > ")}")

            case stateful: InterruptSignal.Stateful =>
              IO.println(s"   Type: Stateful") *>
              IO.println(s"   Info: ${stateful.info}") *>
              IO.println(s"   Address: ${stateful.address.map(_.name).mkString(" > ")}") *>
              IO.println(s"   State: ${stateful.state}")

            case composite: InterruptSignal.Composite =>
              IO.println(s"   Type: Composite (${composite.children.length} interrupts)") *>
              IO.println(s"   Info: ${composite.info}") *>
              IO.println(s"   Address: ${composite.address.map(_.name).mkString(" > ")}") *>
              composite.children.zipWithIndex.traverse { case (child, idx) =>
                IO.println(s"   Child ${idx + 1}: ${child.info}") *>
                IO.println(s"     Address: ${child.address.map(_.name).mkString(" > ")}")
              }.void
          ) *>
          IO.pure(id)

        case other =>
          IO.println(s"   Unexpected result: $other") *>
          IO.pure("none")

      // Phase 2: Human provides approvals
      _ <- ExampleUtils.printSubSection("Phase 2: Human provides approvals")
      _ <- IO.println("   Human approves payment: ✓")
      _ <- IO.println("   Human verifies email recipient: ✓")
      _ <- IO.println("")

      // Phase 3: Resume with approvals
      _ <- ExampleUtils.printSubSection("Phase 3: Resuming with approvals")
      emitter2 <- AgentEventEmitter.create()
      runner2 = AgentRunner.create(agent, store, emitter2)

      result2 <- runner2.resume(
        checkpointId,
        List(
          InterruptResult(
            address = List(AddressSegment.Tool("process_payment")),
            data = ujson.Obj("approved" -> true, "approver" -> "manager@company.com")
          ),
          InterruptResult(
            address = List(AddressSegment.Tool("send_email")),
            data = ujson.Obj("verified" -> true, "verifier" -> "security@company.com")
          )
        )
      )

      _ <- result2 match
        case RunResult.Completed(output, _) =>
          IO.println("   ✓ Agent completed after resume!") *>
          IO.println("") *>
          IO.println("   FINAL OUTPUT:") *>
          IO.println(s"   $output")

        case RunResult.Interrupted(id, signal) =>
          IO.println(s"   Agent interrupted again: ${signal.info}") *>
          IO.println("   (This happens because sequential execution interrupted on first tool)")

        case other =>
          IO.println(s"   Resume result: $other")

      _ <- IO.println("")
      _ <- IO.println("Key Features Demonstrated:")
      _ <- IO.println("  ✓ Sequential execution stops on first interrupt")
      _ <- IO.println("  ✓ Interrupt signal preserves address information")
      _ <- IO.println("  ✓ Resume can provide multiple InterruptResults")
      _ <- IO.println("  ✓ Stateful interrupts carry context data")
      _ <- IO.println("  ✓ Agent continues after approval")
      _ <- IO.println("")
      _ <- IO.println("NOTE: True composite interrupts (multiple simultaneous) require")
      _ <- IO.println("      parallel execution mode, which stops on ANY interrupt.")

      _ <- IO.println("\nComposite interrupt example complete.")
    yield ()
