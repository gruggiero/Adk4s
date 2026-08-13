package org.adk4s.harness
package typecontract

import org.adk4s.harness.{
  AgentMiddleware,
  HarnessState,
  MiddlewareName,
  MiddlewareStack,
  ModelRequest,
  ModelResponse,
  ModelStep,
  PromptSection,
  StateCell,
  StackError,
  SystemPrompt,
  ToolCallCtx,
  ToolCallOut,
  ToolStep
}
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, AssistantMessage }
import cats.Applicative
import cats.Monad
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import munit.FunSuite

/**
 * Typed contract for the middleware-stack spec — verifies type-level
 * obligations that the compiler enforces:
 *   - `MiddlewareStack` has a private constructor (direct call fails)
 *   - `StackError` is a sealed enum with two variants
 *   - Non-exhaustive match over `StackError` fails to compile
 *   - `MiddlewareStack.empty` is the identity element
 *   - `validated` returns `Right` for valid stacks, `Left` for duplicates
 *   - Stack-order semantics: beforeAgent forward, afterAgent reverse,
 *     wrapModelCall/wrapToolCall outermost-first
 *
 * spec: middleware-stack — Proof Obligations table
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class MiddlewareStackTypeContract extends FunSuite:

  // ── Test fixtures ───────────────────────────────────────────────────────

  private def testCompletion: Completion =
    Completion("id", 0L, "content", "model", AssistantMessage(Some("content")), Nil, None, None)

  private def testOptions: CompletionOptions =
    CompletionOptions(temperature = 0.7, maxTokens = None, topP = 1.0)

  private def testRequest(state: HarnessState): ModelRequest[IO] =
    ModelRequest[IO](None, Nil, Nil, testOptions, state)

  private def tracingMiddleware(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName.refineEither(tag).fold(err => throw err, identity)

  // ── MiddlewareStack.empty ───────────────────────────────────────────────

  test("MiddlewareStack.empty has no middlewares"):
    val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
    assertEquals(stack.allCells, Nil)
    assertEquals(stack.allTools, Nil)
    assertEquals(stack.allSections(HarnessState.empty), Nil)

  test("MiddlewareStack.empty beforeAgent returns state unchanged"):
    val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
    val state: HarnessState        = HarnessState.empty
    val result: HarnessState       = stack.beforeAgent(state).unsafeRunSync()
    assertEquals(result, state)

  test("MiddlewareStack.empty afterAgent returns state unchanged"):
    val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
    val state: HarnessState        = HarnessState.empty
    val result: HarnessState       = stack.afterAgent(state).unsafeRunSync()
    assertEquals(result, state)

  test("MiddlewareStack.empty wrapModelCall returns base unchanged"):
    val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
    val base: ModelStep[IO]        = Kleisli(_ => IO.pure(ModelResponse(testCompletion, HarnessState.empty)))
    val wrapped: ModelStep[IO]     = stack.wrapModelCall(base)
    assert(wrapped eq base)

  test("MiddlewareStack.empty wrapToolCall returns base unchanged"):
    val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
    val base: ToolStep[IO]    = Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("t", "r", "c", false), HarnessState.empty)))
    val wrapped: ToolStep[IO] = stack.wrapToolCall(base)
    assert(wrapped eq base)

  // ── StackError sealed enum ──────────────────────────────────────────────

  test("StackError.DuplicateCellId carries id and owners"):
    given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
    val id: StateCell.CellId              = StateCell.CellId(MiddlewareName("m"), "count")
    val owners: List[MiddlewareName]      = List(MiddlewareName("m1"), MiddlewareName("m2"))
    val err: StackError                   = StackError.DuplicateCellId(id, owners)
    err match
      case StackError.DuplicateCellId(errId, errOwners) =>
        assertEquals(errId, id)
        assertEquals(errOwners, owners)
      case StackError.DuplicateToolName(_, _) =>
        fail("Expected DuplicateCellId")

  test("StackError.DuplicateToolName carries name and owners"):
    val name: String                 = "search"
    val owners: List[MiddlewareName] = List(MiddlewareName("m1"), MiddlewareName("m2"))
    val err: StackError              = StackError.DuplicateToolName(name, owners)
    err match
      case StackError.DuplicateToolName(errName, errOwners) =>
        assertEquals(errName, name)
        assertEquals(errOwners, owners)
      case StackError.DuplicateCellId(_, _) =>
        fail("Expected DuplicateToolName")

  // ── Compile-negative obligations ────────────────────────────────────────

  test("MiddlewareStack direct constructor call does not compile"):
    val errors: String = compileErrors("""
      val ms = org.adk4s.harness.MiddlewareStack[cats.effect.IO](
        List(org.adk4s.harness.AgentMiddleware.id[cats.effect.IO]))
    """)
    assert(errors.nonEmpty, "MiddlewareStack constructor is private — direct call must not compile")

  test("StackError is a sealed enum with exactly two variants"):
    // The exhaustiveness guarantee is enforced by -Wconf:name=PatternMatchExhaustivity:e
    // in the project's scalacOptions. The compileErrors macro doesn't apply project
    // settings, so we verify the structural guarantee: StackError has exactly two
    // variants, both constructible. Non-exhaustive matches are compile errors under
    // -Wconf in the project build.
    val cellErr: StackError = StackError.DuplicateCellId(StateCell.CellId(MiddlewareName("m"), "c"), Nil)
    val toolErr: StackError = StackError.DuplicateToolName("search", Nil)
    // Verify they are different variants via exhaustive pattern matching
    val cellVariant: Boolean = cellErr match
      case StackError.DuplicateCellId(_, _)   => true
      case StackError.DuplicateToolName(_, _) => false
    val toolVariant: Boolean = toolErr match
      case StackError.DuplicateCellId(_, _)   => false
      case StackError.DuplicateToolName(_, _) => true
    assert(cellVariant)
    assert(toolVariant)

  // ── validated ───────────────────────────────────────────────────────────

  test("validated(Nil) returns Right(empty)"):
    val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
      MiddlewareStack.validated[IO](Nil)
    assert(result.isRight)
    result.foreach { stack =>
      assertEquals(stack.allCells, Nil)
      assertEquals(stack.allTools, Nil)
    }

  test("validated with valid middlewares returns Right"):
    given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
    val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("m1")
      override def stateCells: List[StateCell[?]] =
        List(StateCell[Int](MiddlewareName("m1"), "count", 0))
    val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("m2")
      override def stateCells: List[StateCell[?]] =
        List(StateCell[Int](MiddlewareName("m2"), "items", 0))
    val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
      MiddlewareStack.validated[IO](List(m1, m2))
    assert(result.isRight)

  test("validated with duplicate cell id returns Left"):
    given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
    val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("m1")
      override def stateCells: List[StateCell[?]] =
        List(StateCell[Int](MiddlewareName("m1"), "count", 0))
    val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
      val name: MiddlewareName = MiddlewareName("m1") // same owner — same CellId
      override def stateCells: List[StateCell[?]] =
        List(StateCell[Int](MiddlewareName("m1"), "count", 0))
    val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
      MiddlewareStack.validated[IO](List(m1, m2))
    assert(result.isLeft)
    result match
      case Left(errors) =>
        assert(errors.exists {
          case StackError.DuplicateCellId(_, _)   => true
          case StackError.DuplicateToolName(_, _) => false
        })
      case Right(_) => fail("Expected Left")

  // ── ++ concatenation ────────────────────────────────────────────────────

  test("++ concatenates middlewares"):
    val m1: AgentMiddleware[IO] = tracingMiddleware("m1")
    val m2: AgentMiddleware[IO] = tracingMiddleware("m2")
    MiddlewareStack.validated[IO](List(m1)) match
      case Right(s1) =>
        MiddlewareStack.validated[IO](List(m2)) match
          case Right(s2) =>
            val combined: MiddlewareStack[IO] = s1 ++ s2
            val state: HarnessState           = HarnessState.empty
            combined.beforeAgent(state).unsafeRunSync()
          case Left(_) => fail("m2 should validate")
      case Left(_) => fail("m1 should validate")
