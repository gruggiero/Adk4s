package org.adk4s.harness
package typecontract

import org.adk4s.harness.{
  AgentMiddleware,
  HarnessState,
  MiddlewareName,
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
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, AssistantMessage }
import cats.Applicative
import cats.Functor
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * Typed contract for the agent-middleware spec — verifies type-level
 * obligations that the compiler enforces:
 *   - `AgentMiddleware` has exactly four hooks (no beforeModel/afterModel)
 *   - `promptSections` takes a `HarnessState` parameter (no-arg fails)
 *   - `MiddlewareName` is an opaque type (raw String rejected)
 *   - `ModelRequest`/`ModelResponse`/`ToolCallCtx`/`ToolCallOut`/
 *     `SystemPrompt`/`PromptSection` are immutable case classes
 *   - `AgentMiddleware.id` is the identity middleware
 *   - `ToolStep.passthrough` lifts state-oblivious endpoints
 *
 * spec: agent-middleware — Proof Obligations table
 */
class AgentMiddlewareTypeContract extends FunSuite:

  // ── Test fixtures ───────────────────────────────────────────────────────

  private def testCompletion: Completion =
    Completion("id", 0L, "content", "model", AssistantMessage(Some("content")), Nil, None, None)

  private def testOptions: CompletionOptions =
    CompletionOptions(temperature = 0.7, maxTokens = None, topP = 1.0)

  // ── AgentMiddleware trait shape ─────────────────────────────────────────

  test("AgentMiddleware.id has name 'identity' and all defaults"):
    val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
    assertEquals(mw.name.value, "identity")
    assertEquals(mw.stateCells, Nil)
    assertEquals(mw.tools, Nil)
    assertEquals(mw.promptSections(HarnessState.empty), Nil)

  test("AgentMiddleware.id beforeAgent is pure (returns input state)"):
    val mw: AgentMiddleware[IO]  = AgentMiddleware.id[IO]
    val state: HarnessState      = HarnessState.empty
    val result: IO[HarnessState] = mw.beforeAgent(state)
    assertEquals(result.unsafeRunSync(), state)

  test("AgentMiddleware.id afterAgent is pure (returns input state)"):
    val mw: AgentMiddleware[IO]  = AgentMiddleware.id[IO]
    val state: HarnessState      = HarnessState.empty
    val result: IO[HarnessState] = mw.afterAgent(state)
    assertEquals(result.unsafeRunSync(), state)

  test("AgentMiddleware.id wrapModelCall is pass-through (returns same step)"):
    val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
    val base: ModelStep[IO]     = Kleisli(_ => IO.pure(ModelResponse(testCompletion, HarnessState.empty)))
    val wrapped: ModelStep[IO]  = mw.wrapModelCall(base)
    assert(wrapped eq base)

  test("AgentMiddleware.id wrapToolCall is pass-through (returns same step)"):
    val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
    val base: ToolStep[IO] =
      Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("test", "result", "call-1", false), HarnessState.empty)))
    val wrapped: ToolStep[IO] = mw.wrapToolCall(base)
    assert(wrapped eq base)

  // ── ModelRequest / ModelResponse ────────────────────────────────────────

  test("ModelRequest is a case class with correct fields"):
    val req: ModelRequest[IO] = ModelRequest[IO](
      systemPrompt = None,
      messages = Nil,
      tools = Nil,
      options = testOptions,
      state = HarnessState.empty
    )
    assertEquals(req.systemPrompt, None)
    assertEquals(req.messages, Nil)
    assertEquals(req.tools, Nil)
    assertEquals(req.state, HarnessState.empty)

  test("ModelResponse is a case class with completion and state"):
    val resp: ModelResponse = ModelResponse(testCompletion, HarnessState.empty)
    assertEquals(resp.state, HarnessState.empty)

  // ── ToolCallCtx / ToolCallOut ───────────────────────────────────────────

  test("ToolCallCtx is a case class with input and state"):
    val input: ToolInput = ToolInput("test", "{}", "call-1")
    val ctx: ToolCallCtx = ToolCallCtx(input, HarnessState.empty)
    assertEquals(ctx.input, input)
    assertEquals(ctx.state, HarnessState.empty)

  test("ToolCallOut is a case class with output and state"):
    val output: ToolOutput = ToolOutput("test", "result", "call-1", false)
    val out: ToolCallOut   = ToolCallOut(output, HarnessState.empty)
    assertEquals(out.output, output)
    assertEquals(out.state, HarnessState.empty)

  // ── SystemPrompt render ─────────────────────────────────────────────────

  test("SystemPrompt.render concatenates base and section bodies in order"):
    val prompt: SystemPrompt = SystemPrompt(
      Some("You are a helpful assistant."),
      List(PromptSection("tools", "Use these tools..."), PromptSection("skills", "Skills: ..."))
    )
    assertEquals(prompt.render, "You are a helpful assistant.\nUse these tools...\nSkills: ...")

  test("SystemPrompt.render with no base concatenates sections only"):
    val prompt: SystemPrompt = SystemPrompt(
      None,
      List(
        PromptSection("s1", "body1"),
        PromptSection("s2", "body2")
      )
    )
    assertEquals(prompt.render, "body1\nbody2")

  test("SystemPrompt.render with no sections returns base only"):
    val prompt: SystemPrompt = SystemPrompt(Some("base"), Nil)
    assertEquals(prompt.render, "base")

  test("SystemPrompt.render with empty prompt returns empty string"):
    val prompt: SystemPrompt = SystemPrompt(None, Nil)
    assertEquals(prompt.render, "")

  test("PromptSection name is preserved for inspection"):
    val section: PromptSection = PromptSection("filesystem", "...")
    assertEquals(section.name, "filesystem")

  // ── MiddlewareName opaque type ──────────────────────────────────────────

  test("MiddlewareName constructs from String and .value reads it back"):
    val name: MiddlewareName = MiddlewareName("todo-list")
    assertEquals(name.value, "todo-list")

  test("MiddlewareName used in cell-id formation"):
    val owner: MiddlewareName             = MiddlewareName("todo-list")
    given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
    val cell: StateCell[Int]              = StateCell[Int](owner, "items", 0)
    assertEquals(cell.id.value, "todo-list/items")

  // ── ToolStep.passthrough ────────────────────────────────────────────────

  test("ToolStep.passthrough lifts a state-oblivious endpoint"):
    val ep: Kleisli[IO, ToolInput, ToolOutput] = Kleisli { input =>
      IO.pure(ToolOutput(input.name, "ok", input.callId, false))
    }
    val step: ToolStep[IO]  = ToolStep.passthrough[IO](ep)
    val input: ToolInput    = ToolInput("test", "{}", "call-1")
    val state: HarnessState = HarnessState.empty
    val result: ToolCallOut = step.run(ToolCallCtx(input, state)).unsafeRunSync()
    assertEquals(result.output.name, "test")
    assertEquals(result.state, state)

  // ── Compile-negative obligations ────────────────────────────────────────

  test("MiddlewareName is not assignable from a raw String"):
    val errors: String = compileErrors("""
      val name: org.adk4s.harness.MiddlewareName = "todo"
    """)
    assert(errors.nonEmpty, "MiddlewareName must not be assignable from a raw String")

  test("promptSections override without HarnessState parameter does not compile"):
    val errors: String = compileErrors("""
      class M extends org.adk4s.harness.AgentMiddleware[cats.effect.IO]:
        def name: org.adk4s.harness.MiddlewareName =
          org.adk4s.harness.MiddlewareName("m")
        override def promptSections: List[org.adk4s.harness.PromptSection] = Nil
    """)
    assert(
      errors.nonEmpty,
      "override promptSections without HarnessState parameter must not compile — it overrides nothing"
    )

  test("ModelRequest is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val r: org.adk4s.harness.ModelRequest[cats.effect.IO] =
        org.adk4s.harness.ModelRequest[cats.effect.IO](
          None, Nil, Nil,
          org.llm4s.llmconnect.model.CompletionOptions(0.7, None, 1.0),
          org.adk4s.harness.HarnessState.empty)
      r.messages = Nil
    """)
    assert(errors.nonEmpty, "ModelRequest field mutation must not compile")

  test("ToolCallCtx is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val ctx: org.adk4s.harness.ToolCallCtx =
        org.adk4s.harness.ToolCallCtx(
          org.adk4s.core.tools.ToolInput("t", "{}", "c"),
          org.adk4s.harness.HarnessState.empty)
      ctx.state = org.adk4s.harness.HarnessState.empty
    """)
    assert(errors.nonEmpty, "ToolCallCtx field mutation must not compile")

  test("ToolCallOut is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val out: org.adk4s.harness.ToolCallOut =
        org.adk4s.harness.ToolCallOut(
          org.adk4s.core.tools.ToolOutput("t", "r", "c", false),
          org.adk4s.harness.HarnessState.empty)
      out.state = org.adk4s.harness.HarnessState.empty
    """)
    assert(errors.nonEmpty, "ToolCallOut field mutation must not compile")

  test("ModelResponse is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val r: org.adk4s.harness.ModelResponse =
        org.adk4s.harness.ModelResponse(
          org.llm4s.llmconnect.model.Completion("id", 0L, "content", "model",
            org.llm4s.llmconnect.model.AssistantMessage(Some("content")), Nil, None, None),
          org.adk4s.harness.HarnessState.empty)
      r.state = org.adk4s.harness.HarnessState.empty
    """)
    assert(errors.nonEmpty, "ModelResponse field mutation must not compile")

  test("SystemPrompt is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val p: org.adk4s.harness.SystemPrompt =
        org.adk4s.harness.SystemPrompt(Some("base"), Nil)
      p.base = None
    """)
    assert(errors.nonEmpty, "SystemPrompt field mutation must not compile")

  test("PromptSection is immutable — field assignment does not compile"):
    val errors: String = compileErrors("""
      val s: org.adk4s.harness.PromptSection =
        org.adk4s.harness.PromptSection("name", "body")
      s.body = "new"
    """)
    assert(errors.nonEmpty, "PromptSection field mutation must not compile")

  // ── Forbidden hooks compile-negative obligations ────────────────────────
  // The trait deliberately omits beforeModel/afterModel/modifyModelRequest.
  // Adding `override` for any of these fails because they don't exist on the trait.

  test("override beforeModel does not compile — method does not exist on trait"):
    val errors: String = compileErrors("""
      class M extends org.adk4s.harness.AgentMiddleware[cats.effect.IO]:
        def name: org.adk4s.harness.MiddlewareName =
          org.adk4s.harness.MiddlewareName("m")
        override def beforeModel = ???
    """)
    assert(errors.nonEmpty, "override beforeModel must not compile — method does not exist on AgentMiddleware")

  test("override afterModel does not compile — method does not exist on trait"):
    val errors: String = compileErrors("""
      class M extends org.adk4s.harness.AgentMiddleware[cats.effect.IO]:
        def name: org.adk4s.harness.MiddlewareName =
          org.adk4s.harness.MiddlewareName("m")
        override def afterModel = ???
    """)
    assert(errors.nonEmpty, "override afterModel must not compile — method does not exist on AgentMiddleware")

  test("override modifyModelRequest does not compile — method does not exist on trait"):
    val errors: String = compileErrors("""
      class M extends org.adk4s.harness.AgentMiddleware[cats.effect.IO]:
        def name: org.adk4s.harness.MiddlewareName =
          org.adk4s.harness.MiddlewareName("m")
        override def modifyModelRequest = ???
    """)
    assert(errors.nonEmpty, "override modifyModelRequest must not compile — method does not exist on AgentMiddleware")
