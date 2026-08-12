package org.adk4s.examples.eino.agent

import cats.Applicative
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.applicative.catsSyntaxApplicative
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import org.adk4s.core.component.{ AdkToolInfo, ChatModel, ChatModelConfig, InvokableTool }
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.harness.{
  AgentMiddleware,
  CellVisibility,
  HarnessState,
  MiddlewareName,
  MiddlewareStack,
  ModelRequest,
  ModelResponse,
  ModelStep,
  PromptSection,
  StateCell,
  SystemPrompt,
  ToolCallCtx,
  ToolCallOut,
  ToolStep
}
import org.adk4s.orchestration.agent.{ HarnessAgent, HarnessResult }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  Conversation,
  StreamedChunk,
  ToolCall,
  UserMessage
}
import upickle.default.{ ReadWriter, given }

import java.util.UUID

/**
 * Demonstrates the `AgentMiddleware` / `HarnessState` structure with a
 * realistic deep-agent scenario: a research agent that
 *   1. maintains a todo list (planning middleware),
 *   2. tracks an audit log of every tool execution (observability middleware),
 *   3. guards its step budget (resource-control middleware).
 *
 * All three concerns are composable `AgentMiddleware[IO]` values stacked via
 * `MiddlewareStack.validated`. Each declares typed `StateCell`s that flow
 * through the `HarnessAgent` loop, survive checkpoints, and surface in the
 * system prompt via state-aware `promptSections`.
 *
 * This is the pattern that the old `ReactAgent` could not express: cross-
 * cutting agent-loop behavior with typed state, without per-feature surgery
 * on the loop itself.
 *
 * Run: ./adk4s-examples/run-example.sh deepagent
 */
object DeepAgentExample extends IOApp.Simple:

  // ── State types ──────────────────────────────────────────────────────────

  /** Todo list entries — the planning middleware's state. */
  final case class Todos(items: List[String])
  object Todos:
    given ReadWriter[Todos] = upickle.default.readwriter[ujson.Value].bimap(
      (t: Todos) => ujson.Arr(t.items.map(ujson.Str.apply)*),
      (v: ujson.Value) => Todos(v.arr.toList.map((s: ujson.Value) => s.str))
    )

  /** Audit log entries — the observability middleware's state. */
  final case class AuditLog(entries: List[String])
  object AuditLog:
    given ReadWriter[AuditLog] = upickle.default.readwriter[ujson.Value].bimap(
      (a: AuditLog) => ujson.Arr(a.entries.map(ujson.Str.apply)*),
      (v: ujson.Value) => AuditLog(v.arr.toList.map((s: ujson.Value) => s.str))
    )

  // ── Middleware 1: TodoListMiddleware (planning) ──────────────────────────

  /**
   * Maintains a todo list in a `Shared` cell. Contributes a `write_todos`
   * tool the agent can call to update its plan, and renders the current
   * todos into the system prompt per-request via `promptSections(state)`.
   *
   * The `Shared` visibility with union-merge means that if this middleware
   * is used in a sub-agent hierarchy, parallel sub-agents' todo lists
   * combine deterministically (semilattice).
   */
  final class TodoListMiddleware extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("todo-list")

    val todoCell: StateCell[Todos] = StateCell[Todos](
      owner = name,
      name = "todos",
      initial = Todos(List("Analyze the user's request", "Gather information", "Synthesize a response")),
      visibility = CellVisibility.Shared,
      merge = (parent: Todos, child: Todos) =>
        Todos((parent.items ++ child.items).distinct)
    )

    override def stateCells: List[StateCell[?]] = List(todoCell)

    /** The write_todos tool — updates the todo cell via the harness state. */
    val writeTodosTool: InvokableTool[IO] = new InvokableTool[IO]:
      def info: AdkToolInfo = AdkToolInfo(
        name = "write_todos",
        description = "Update the current todo list. Pass the full list of tasks.",
        parameters = ujson.Obj(
          "type" -> "object",
          "properties" -> ujson.Obj(
            "todos" -> ujson.Obj(
              "type" -> "array",
              "items" -> ujson.Obj("type" -> "string"),
              "description" -> "The complete updated todo list"
            )
          ),
          "required" -> ujson.Arr("todos")
        )
      )
      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

      def run(arguments: ujson.Value): IO[ujson.Value] =
        val items: List[String] = arguments.obj.get("todos") match
          case Some(arr: ujson.Arr) => arr.value.toList.map((v: ujson.Value) => v.str)
          case _                    => Nil
        IO.pure(ujson.Str(s"Todo list updated: ${items.size} items"))

    override def tools: List[InvokableTool[IO]] = List(writeTodosTool)

    override def promptSections(state: HarnessState): List[PromptSection] =
      val todos: Todos = state.get(todoCell)
      val body: String =
        if todos.items.isEmpty then "No todos yet."
        else
          todos.items.zipWithIndex
            .map { case (item: String, idx: Int) => s"  ${idx + 1}. $item" }
            .mkString("\n")
      List(PromptSection("Current Plan", s"## Current Todo List\n$body"))
  end TodoListMiddleware

  // ── Middleware 2: AuditLogMiddleware (observability) ─────────────────────

  /**
   * Maintains an audit log in a `Private` cell. Wraps every tool call to
   * record the tool name and result, and renders the audit trail into the
   * system prompt so the agent can see what it has already done.
   *
   * `Private` visibility means the audit log never leaks to sub-agents and
   * sub-agent writes never pollute the parent's log.
   */
  final class AuditLogMiddleware extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("audit-log")

    val logCell: StateCell[AuditLog] = StateCell[AuditLog](
      owner = name,
      name = "log",
      initial = AuditLog(Nil),
      visibility = CellVisibility.Private
    )

    override def stateCells: List[StateCell[?]] = List(logCell)

    override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
      Kleisli { (ctx: ToolCallCtx) =>
        next.run(ctx).flatMap { (out: ToolCallOut) =>
          val entry: String =
            s"[${ctx.input.name}] -> ${out.output.result.take(80)}"
          val updatedLog: AuditLog = ctx.state.get(logCell)
          val newLog: AuditLog = AuditLog(updatedLog.entries :+ entry)
          val newState: HarnessState = out.state.set(logCell)(newLog)
          IO.pure(ToolCallOut(out.output, newState))
        }
      }

    override def promptSections(state: HarnessState): List[PromptSection] =
      val log: AuditLog = state.get(logCell)
      val body: String =
        if log.entries.isEmpty then "No tool calls executed yet."
        else log.entries.zipWithIndex
          .map { case (entry: String, idx: Int) => s"  ${idx + 1}. $entry" }
          .mkString("\n")
      List(PromptSection("Audit Trail", s"## Actions Taken So Far\n$body"))
  end AuditLogMiddleware

  // ── Middleware 3: BudgetGuardMiddleware (resource control) ───────────────

  /**
   * Tracks remaining steps in a `Private` cell. Wraps the model call to
   * inject a budget reminder into the system prompt, so the agent knows
   * how many iterations it has left and can prioritise accordingly.
   *
   * Demonstrates `wrapModelCall` rewriting the request's system prompt
   * by appending a budget section — the per-request prompt assembly that
   * the old `ReactAgent` (which baked the prompt once at construction)
   * could not do.
   */
  final class BudgetGuardMiddleware(maxSteps: Int) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("budget-guard")

    val remainingCell: StateCell[Int] = StateCell[Int](
      owner = name,
      name = "remaining",
      initial = maxSteps,
      visibility = CellVisibility.Private
    )

    override def stateCells: List[StateCell[?]] = List(remainingCell)

    override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
      Kleisli { (req: ModelRequest[IO]) =>
        val remaining: Int = req.state.get(remainingCell)
        val budgetSection: PromptSection = PromptSection(
          "Budget",
          s"## Step Budget\nYou have $remaining steps remaining. Prioritise accordingly."
        )
        val existingPrompt: SystemPrompt = req.systemPrompt.getOrElse(SystemPrompt(None, Nil))
        val enrichedPrompt: SystemPrompt =
          SystemPrompt(existingPrompt.base, existingPrompt.sections :+ budgetSection)
        val enrichedReq: ModelRequest[IO] = req.copy(systemPrompt = Some(enrichedPrompt))
        next.run(enrichedReq).map { (resp: ModelResponse) =>
          val decremented: HarnessState = resp.state.update(remainingCell)(_ - 1)
          ModelResponse(resp.completion, decremented)
        }
      }
  end BudgetGuardMiddleware

  // ── Mock model with tool-calling ─────────────────────────────────────────

  /**
   * A mock model that simulates a two-step research agent:
   *   1. First call: calls `write_todos` to set up a plan.
   *   2. Second call: produces a final response (no tool calls).
   *
   * This lets the example run without an API key while still exercising
   * the full loop (model call → tool execution → state merge → next iteration).
   */
  private def makeCompletion(content: String, toolCalls: Seq[ToolCall] = Seq.empty): Completion =
    val msg: AssistantMessage = AssistantMessage(contentOpt = Some(content), toolCalls = toolCalls)
    Completion(
      id = UUID.randomUUID().toString,
      created = System.currentTimeMillis(),
      content = content,
      model = "mock-model",
      message = msg
    )

  // ── Main ─────────────────────────────────────────────────────────────────

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Deep Agent Example — Middleware Stack + HarnessState")

      _ <- IO.println(
        """Scenario: A research agent with three composable middlewares:
          |  1. TodoListMiddleware  — planning (Shared cell, write_todos tool, state-aware prompt)
          |  2. AuditLogMiddleware  — observability (Private cell, wrapToolCall, audit trail prompt)
          |  3. BudgetGuardMiddleware — resource control (Private cell, wrapModelCall, budget prompt)
          |
          |The old ReactAgent could not express any of these without per-feature
          |surgery on the loop. Here they are three values of AgentMiddleware[IO],
          |stacked via MiddlewareStack.validated.""".stripMargin
      )

      // Build the middleware stack
      todoMw = new TodoListMiddleware
      auditMw = new AuditLogMiddleware
      budgetMw = new BudgetGuardMiddleware(maxSteps = 10)

      stackResult = MiddlewareStack.validated[IO](List(todoMw, auditMw, budgetMw))
      stack <- IO.fromEither(
        stackResult.left.map(errs =>
          new RuntimeException(s"Stack validation failed: ${errs.toList.map(_.toString).mkString(", ")}")
        )
      )

      _ <- ExampleUtils.printSubSection("Stack Construction")
      _ <- IO.println(s"   Middlewares: ${stack.middlewares.map(_.name.value).mkString(", ")}")
      _ <- IO.println(s"   State cells: ${stack.allCells.map(_.id.value).mkString(", ")}")
      _ <- IO.println(s"   Contributed tools: ${stack.allTools.map(_.info.name).mkString(", ")}")

      // Build the mock model
      callCount <- IO.ref(0)
      model = new ChatModel[IO]:
        def generate(conversation: Conversation): IO[Completion] =
          callCount.getAndUpdate(_ + 1).flatMap { (count: Int) =>
            count match
              case 0 =>
                // First iteration: call write_todos to update the plan
                val tc: ToolCall = ToolCall(
                  id = UUID.randomUUID().toString,
                  name = "write_todos",
                  arguments = ujson.Obj(
                    "todos" -> ujson.Arr(
                      ujson.Str("Understand the user's research question"),
                      ujson.Str("Search for relevant information"),
                      ujson.Str("Synthesize findings into a response")
                    )
                  )
                )
                IO.pure(
                  makeCompletion("Let me update my plan first.", Seq(tc))
                )
              case _ =>
                // Second iteration: final response
                IO.pure(
                  makeCompletion(
                    "Based on my research plan, I've analyzed the question and synthesized a response."
                  )
                )
          }

        def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] = fs2.Stream.empty
        def streamContent(conversation: Conversation): fs2.Stream[IO, String] = fs2.Stream.empty
        def withConfig(config: ChatModelConfig): ChatModel[IO] = this

      // Build the HarnessAgent directly (not via ReactAgent.create sugar)
      agent = new HarnessAgent[IO](
        HarnessAgent.Config[IO](
          name = "research-agent",
          description = "Research agent with planning, audit, and budget middlewares",
          model = model,
          stack = stack,
          baseTools = Nil,
          basePrompt = Some("You are a research assistant. Use tools to plan and execute your work."),
          maxSteps = 10
        )
      )

      _ <- ExampleUtils.printSubSection("Running the agent")
      _ <- IO.println("   User: Research the impact of functional programming on agent design.")
      _ <- IO.println("")

      result <- agent.generate(
        List(UserMessage("Research the impact of functional programming on agent design.")),
        maxSteps = 10
      )

      _ <- result match
        case HarnessResult.Completed(assistant, messages, finalState) =>
          IO.println(s"   Agent completed: ${assistant.content}") *>
            IO.println(s"   Total messages: ${messages.size}") *>
            printFinalState(finalState, todoMw, auditMw, budgetMw)

        case HarnessResult.Interrupted(signal, _, _) =>
          IO.println(s"   Agent interrupted: ${signal.info}")

        case HarnessResult.Failed(error, _, _) =>
          IO.println(s"   Agent failed: ${error.message}")

      _ <- IO.println("\n=== Deep Agent Example Completed ===")
    yield ()

  private def printFinalState(
    state: HarnessState,
    todoMw: TodoListMiddleware,
    auditMw: AuditLogMiddleware,
    budgetMw: BudgetGuardMiddleware
  ): IO[Unit] =
    val todos: Todos = state.get(todoMw.todoCell)
    val log: AuditLog = state.get(auditMw.logCell)
    val remaining: Int = state.get(budgetMw.remainingCell)

    ExampleUtils.printSubSection("Final HarnessState") *>
      IO.println(s"   Todos (${todos.items.size}):") *>
      todos.items.zipWithIndex.foldLeft(IO.unit) { case (acc, (item: String, idx: Int)) =>
        acc *> IO.println(s"     ${idx + 1}. $item")
      } *>
      IO.println(s"   Audit log (${log.entries.size}):") *>
      log.entries.zipWithIndex.foldLeft(IO.unit) { case (acc, (entry: String, idx: Int)) =>
        acc *> IO.println(s"     ${idx + 1}. $entry")
      } *>
      IO.println(s"   Remaining steps: $remaining") *>
      IO.println(s"   State snapshot: ${state.snapshot.toString.take(200)}...")
