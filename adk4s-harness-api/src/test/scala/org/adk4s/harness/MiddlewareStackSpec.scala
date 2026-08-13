package org.adk4s.harness

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
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Scenario tests and Hedgehog properties for the middleware-stack spec.
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * spec: middleware-stack — Proof Obligations table
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class MiddlewareStackSpec extends HedgehogSuite:

  // ── Test fixtures ───────────────────────────────────────────────────────

  private def testCompletion: Completion =
    Completion("id", 0L, "content", "model", AssistantMessage(Some("content")), Nil, None, None)

  private def testOptions: CompletionOptions =
    CompletionOptions(temperature = 0.7, maxTokens = None, topP = 1.0)

  private def testRequest(state: HarnessState): ModelRequest[IO] =
    ModelRequest[IO](None, Nil, Nil, testOptions, state)

  // ── beforeAgent runs in stack order ─────────────────────────────────────

  test("beforeAgent runs in stack order (m1 → m2 → m3)"):
    withMunitAssertions { a =>
      val traceRef: scala.collection.mutable.ListBuffer[String] =
        scala.collection.mutable.ListBuffer.empty
      def tracingMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName.refineEither(tag).fold(err => throw err, identity)
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure {
            traceRef += tag
            state
          }
      val m1: AgentMiddleware[IO] = tracingMW("m1")
      val m2: AgentMiddleware[IO] = tracingMW("m2")
      val m3: AgentMiddleware[IO] = tracingMW("m3")
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2, m3)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val state: HarnessState = HarnessState.empty
      stack.beforeAgent(state).unsafeRunSync()
      a.assertEquals(traceRef.toList, List("m1", "m2", "m3"))
    }

  // ── afterAgent runs in reverse stack order ──────────────────────────────

  test("afterAgent runs in reverse stack order (m3 → m2 → m1)"):
    withMunitAssertions { a =>
      val traceRef: scala.collection.mutable.ListBuffer[String] =
        scala.collection.mutable.ListBuffer.empty
      def tracingMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName.refineEither(tag).fold(err => throw err, identity)
        override def afterAgent(state: HarnessState): IO[HarnessState] =
          IO.pure {
            traceRef += tag
            state
          }
      val m1: AgentMiddleware[IO] = tracingMW("m1")
      val m2: AgentMiddleware[IO] = tracingMW("m2")
      val m3: AgentMiddleware[IO] = tracingMW("m3")
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2, m3)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val state: HarnessState = HarnessState.empty
      stack.afterAgent(state).unsafeRunSync()
      a.assertEquals(traceRef.toList, List("m3", "m2", "m1"))
    }

  // ── wrapModelCall nests m1 outermost ────────────────────────────────────

  test("wrapModelCall nests m1 outermost"):
    withMunitAssertions { a =>
      def prependMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName.refineEither(tag).fold(err => throw err, identity)
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val prevBase: Option[String]          = req.systemPrompt.flatMap(_.base)
            val prevSections: List[PromptSection] = req.systemPrompt.map(_.sections).getOrElse(Nil)
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some(tag + prevBase.getOrElse("")), prevSections))
            )
            next.run(rewritten)
          }
      val m1: AgentMiddleware[IO] = prependMW("m1:")
      val m2: AgentMiddleware[IO] = prependMW("m2:")
      val m3: AgentMiddleware[IO] = prependMW("m3:")
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2, m3)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val capturedRef: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val base: ModelStep[IO] = Kleisli { req =>
        capturedRef += req.systemPrompt.flatMap(_.base)
        IO.pure(ModelResponse(testCompletion, req.state))
      }
      stack.wrapModelCall(base).run(testRequest(HarnessState.empty)).unsafeRunSync()
      // m1 outermost: m1 prepends first, then m2, then m3 → base sees "m3:m2:m1:"
      a.assertEquals(capturedRef.headOption.flatten, Some("m3:m2:m1:"))
    }

  // ── Empty stack is the identity ─────────────────────────────────────────

  test("Empty stack is the identity for all hooks"):
    withMunitAssertions { a =>
      val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
      val state: HarnessState        = HarnessState.empty
      a.assertEquals(stack.beforeAgent(state).unsafeRunSync(), state)
      a.assertEquals(stack.afterAgent(state).unsafeRunSync(), state)
      val base: ModelStep[IO] = Kleisli(_ => IO.pure(ModelResponse(testCompletion, state)))
      a.assert(stack.wrapModelCall(base) eq base)
      val toolBase: ToolStep[IO] = Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("t", "r", "c", false), state)))
      a.assert(stack.wrapToolCall(toolBase) eq toolBase)
      a.assertEquals(stack.allCells, Nil)
      a.assertEquals(stack.allTools, Nil)
      a.assertEquals(stack.allSections(state), Nil)
    }

  // ── allSections folds per-request from current state ────────────────────

  test("allSections folds per-request from current state"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[String] = upickle.default.readwriter[String]
      val cellA: StateCell[String]             = StateCell[String](MiddlewareName("m1"), "data", "")
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] = List(cellA)
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("m1", state.get(cellA)))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("m2", "static"))
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val stateA: HarnessState           = HarnessState.initial(List(cellA)).set(cellA)("hello")
      val stateB: HarnessState           = HarnessState.initial(List(cellA)).set(cellA)("world")
      val sectionsA: List[PromptSection] = stack.allSections(stateA)
      val sectionsB: List[PromptSection] = stack.allSections(stateB)
      a.assertEquals(sectionsA.map(_.name), List("m1", "m2"))
      a.assertEquals(sectionsA.map(_.body), List("hello", "static"))
      a.assertEquals(sectionsB.map(_.body), List("world", "static"))
    }

  // ── Aggregation methods concatenate in stack order ──────────────────────

  test("allCells concatenates in stack order"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cA: StateCell[Int]                = StateCell[Int](MiddlewareName("m1"), "a", 0)
      val cB: StateCell[Int]                = StateCell[Int](MiddlewareName("m2"), "b", 0)
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] = List(cA)
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] = List(cB)
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      a.assertEquals(stack.allCells.map(_.id.value), List("m1/a", "m2/b"))
    }

  test("allTools concatenates in stack order"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def tools: List[InvokableTool[IO]] = List(stubTool("t1"))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m2")
        override def tools: List[InvokableTool[IO]] = List(stubTool("t2"))
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      a.assertEquals(stack.allTools.map(_.info.name), List("t1", "t2"))
    }

  test("Empty stack aggregates to empty"):
    withMunitAssertions { a =>
      val stack: MiddlewareStack[IO] = MiddlewareStack.empty[IO]
      a.assertEquals(stack.allCells, Nil)
      a.assertEquals(stack.allTools, Nil)
      a.assertEquals(stack.allSections(HarnessState.empty), Nil)
    }

  test("Middleware with default empty contributions aggregates to empty"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      a.assertEquals(stack.allCells, Nil)
      a.assertEquals(stack.allTools, Nil)
      a.assertEquals(stack.allSections(HarnessState.empty), Nil)
    }

  // ── Validated construction ──────────────────────────────────────────────

  test("validated rejects duplicate cell ids"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      a.assert(result.isLeft)
      result match
        case Left(errors) =>
          val hasCellErr: Boolean = errors.exists {
            case StackError.DuplicateCellId(_, _)   => true
            case StackError.DuplicateToolName(_, _) => false
          }
          a.assert(hasCellErr)
        case Right(_) => a.fail("Expected Left")
    }

  test("validated DuplicateCellId carries exact id and owners in stack order"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      result match
        case Left(errors) =>
          errors.toList match
            case (cellErr: StackError.DuplicateCellId) :: _ =>
              a.assertEquals(cellErr.id, StateCell.CellId(MiddlewareName("owner"), "count"))
              a.assertEquals(cellErr.owners, List(MiddlewareName("m1"), MiddlewareName("m2")))
            case _ => a.fail("Expected DuplicateCellId")
        case Right(_) => a.fail("Expected Left")
    }

  test("validated DuplicateToolName carries exact name and owners in stack order"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def tools: List[InvokableTool[IO]] = List(stubTool("search"))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m2")
        override def tools: List[InvokableTool[IO]] = List(stubTool("search"))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      result match
        case Left(errors) =>
          errors.toList match
            case (toolErr: StackError.DuplicateToolName) :: _ =>
              a.assertEquals(toolErr.name, "search")
              a.assertEquals(toolErr.owners, List(MiddlewareName("m1"), MiddlewareName("m2")))
            case _ => a.fail("Expected DuplicateToolName")
        case Right(_) => a.fail("Expected Left")
    }

  test("validated rejects duplicate tool names"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def tools: List[InvokableTool[IO]] =
          List(stubTool("search"))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def tools: List[InvokableTool[IO]] =
          List(stubTool("search"))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      a.assert(result.isLeft)
      result match
        case Left(errors) =>
          val hasToolErr: Boolean = errors.exists {
            case StackError.DuplicateCellId(_, _)   => false
            case StackError.DuplicateToolName(_, _) => true
          }
          a.assert(hasToolErr)
        case Right(_) => a.fail("Expected Left")
    }

  test("validated accumulates all errors"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
        override def tools: List[InvokableTool[IO]] =
          List(stubTool("search"))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), "count", 0))
        override def tools: List[InvokableTool[IO]] =
          List(stubTool("search"))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      a.assert(result.isLeft)
      result match
        case Left(errors) =>
          val hasCellErr: Boolean = errors.exists {
            case StackError.DuplicateCellId(_, _)   => true
            case StackError.DuplicateToolName(_, _) => false
          }
          val hasToolErr: Boolean = errors.exists {
            case StackError.DuplicateCellId(_, _)   => false
            case StackError.DuplicateToolName(_, _) => true
          }
          a.assert(hasCellErr)
          a.assert(hasToolErr)
          a.assert(errors.length > 1)
        case Right(_) => a.fail("Expected Left")
    }

  test("validated returns Right for valid stacks"):
    withMunitAssertions { a =>
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
      a.assert(result.isRight)
    }

  test("validated(Nil) returns Right(empty)"):
    withMunitAssertions { a =>
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](Nil)
      a.assert(result.isRight)
      result match
        case Right(stack) =>
          a.assertEquals(stack.allCells, Nil)
          a.assertEquals(stack.allTools, Nil)
        case Left(_) => a.fail("Expected Right")
    }

  // ── StackError variant fields ───────────────────────────────────────────

  test("DuplicateCellId carries id and owners"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val id: StateCell.CellId              = StateCell.CellId(MiddlewareName("m"), "count")
      val owners: List[MiddlewareName]      = List(MiddlewareName("m1"), MiddlewareName("m2"))
      val err: StackError                   = StackError.DuplicateCellId(id, owners)
      err match
        case StackError.DuplicateCellId(errId, errOwners) =>
          a.assertEquals(errId, id)
          a.assertEquals(errOwners, owners)
        case StackError.DuplicateToolName(_, _) =>
          a.fail("Expected DuplicateCellId")
    }

  test("DuplicateToolName carries name and owners"):
    withMunitAssertions { a =>
      val name: String                 = "search"
      val owners: List[MiddlewareName] = List(MiddlewareName("m1"), MiddlewareName("m2"))
      val err: StackError              = StackError.DuplicateToolName(name, owners)
      err match
        case StackError.DuplicateToolName(errName, errOwners) =>
          a.assertEquals(errName, name)
          a.assertEquals(errOwners, owners)
        case StackError.DuplicateCellId(_, _) =>
          a.fail("Expected DuplicateToolName")
    }

  // ── ++ concatenation ────────────────────────────────────────────────────

  test("++ concatenates middlewares in order"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cA: StateCell[Int]                = StateCell[Int](MiddlewareName("m1"), "a", 0)
      val cB: StateCell[Int]                = StateCell[Int](MiddlewareName("m2"), "b", 0)
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] = List(cA)
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] = List(cB)
      val s1: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val s2: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m2)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      val combined: MiddlewareStack[IO] = s1 ++ s2
      a.assertEquals(combined.allCells.map(_.id.value), List("m1/a", "m2/b"))
    }

  // ── Hedgehog property: validated-duplicate-detection ────────────────────

  property("validated-duplicate-detection — duplicate cell ids produce Left"):
    for cellName <- Gen.string(Gen.alpha, Range.linear(1, 10)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), cellName, 0))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("owner"), cellName, 0))
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1, m2))
      result match
        case Left(errors) =>
          val hasCellErr: Boolean = errors.exists {
            case StackError.DuplicateCellId(_, _)   => true
            case StackError.DuplicateToolName(_, _) => false
          }
          hasCellErr ==== true
        case Right(_) => Result.failure.log("Expected Left for duplicate cell ids")

  property("validated-duplicate-detection — valid stack produces Right"):
    for nMiddlewares <- Gen.int(Range.linear(0, 5)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val middlewares: List[AgentMiddleware[IO]] =
        (0 until nMiddlewares).toList.map { i =>
          new AgentMiddleware[IO]:
            val name: MiddlewareName = MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity)
            override def stateCells: List[StateCell[?]] =
              List(StateCell[Int](MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity), "cell", 0))
        }
      val result: Either[NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](middlewares)
      result.isRight ==== true

  // ── Hedgehog property: monoid-identity-any-position ─────────────────────

  property("monoid-identity-any-position — inserting id is observationally equivalent"):
    for stackSize <- Gen.int(Range.linear(1, 5)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      // Each middleware has its own unique cell (no duplicates)
      val cells: List[StateCell[Int]] =
        (0 until stackSize).toList.map(i => StateCell[Int](MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity), "val", 0))
      def tracingMW(i: Int): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity)
        override def stateCells: List[StateCell[?]] = List(cells(i))
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.update(cells(i))(_ + 1))
      val middlewares: List[AgentMiddleware[IO]] =
        (0 until stackSize).toList.map(i => tracingMW(i))
      val baseStack: MiddlewareStack[IO] = validated(middlewares)
      val state0: HarnessState           = HarnessState.initial(cells)
      val baseResult: HarnessState       = baseStack.beforeAgent(state0).unsafeRunSync()
      // Insert id at head
      val headStack: MiddlewareStack[IO] = validated(AgentMiddleware.id[IO] :: middlewares)
      val headResult: HarnessState       = headStack.beforeAgent(state0).unsafeRunSync()
      // Insert id at tail
      val tailStack: MiddlewareStack[IO] = validated(middlewares :+ AgentMiddleware.id[IO])
      val tailResult: HarnessState       = tailStack.beforeAgent(state0).unsafeRunSync()
      // Each cell should have value 1 in all three cases (id contributes nothing)
      val allMatch: Boolean = cells.forall { c =>
        baseResult.get(c) == headResult.get(c) && baseResult.get(c) == tailResult.get(c)
      }
      allMatch ==== true

  // ── Hedgehog property: monoid-associativity ─────────────────────────────

  property("monoid-associativity — (a ++ b) ++ c == a ++ (b ++ c)"):
    for
      aSize <- Gen.int(Range.linear(0, 3)).forAll
      bSize <- Gen.int(Range.linear(0, 3)).forAll
      cSize <- Gen.int(Range.linear(0, 3)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      // Each middleware has its own unique cell
      def mkMiddleware(prefix: String, i: Int): (StateCell[Int], AgentMiddleware[IO]) =
        val cell: StateCell[Int] = StateCell[Int](MiddlewareName.refineEither(s"$prefix$i").fold(err => throw err, identity), "val", 0)
        val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
          val name: MiddlewareName                    = MiddlewareName.refineEither(s"$prefix$i").fold(err => throw err, identity)
          override def stateCells: List[StateCell[?]] = List(cell)
          override def beforeAgent(state: HarnessState): IO[HarnessState] =
            IO.pure(state.update(cell)(_ + 1))
        (cell, mw)
      val aData: List[(StateCell[Int], AgentMiddleware[IO])] =
        (0 until aSize).toList.map(i => mkMiddleware("a", i))
      val bData: List[(StateCell[Int], AgentMiddleware[IO])] =
        (0 until bSize).toList.map(i => mkMiddleware("b", i))
      val cData: List[(StateCell[Int], AgentMiddleware[IO])] =
        (0 until cSize).toList.map(i => mkMiddleware("c", i))
      val allCells: List[StateCell[Int]] = aData.map(_._1) ++ bData.map(_._1) ++ cData.map(_._1)
      val a: MiddlewareStack[IO]         = validated(aData.map(_._2))
      val b: MiddlewareStack[IO]         = validated(bData.map(_._2))
      val c: MiddlewareStack[IO]         = validated(cData.map(_._2))
      val left: MiddlewareStack[IO]      = (a ++ b) ++ c
      val right: MiddlewareStack[IO]     = a ++ (b ++ c)
      val state0: HarnessState           = HarnessState.initial(allCells)
      val leftResult: HarnessState       = left.beforeAgent(state0).unsafeRunSync()
      val rightResult: HarnessState      = right.beforeAgent(state0).unsafeRunSync()
      // Each cell should have value 1 in both cases
      val allMatch: Boolean = allCells.forall(c => leftResult.get(c) == rightResult.get(c))
      allMatch ==== true

  // ── Hedgehog property: hook-distribution-wrapModelCall ──────────────────

  property("hook-distribution-wrapModelCall — stack composition matches manual nesting"):
    for
      tag1 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
      tag2 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
    yield
      def prependMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName.refineEither(tag).fold(err => throw err, identity)
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val prevBase: Option[String]          = req.systemPrompt.flatMap(_.base)
            val prevSections: List[PromptSection] = req.systemPrompt.map(_.sections).getOrElse(Nil)
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some(tag + prevBase.getOrElse("")), prevSections))
            )
            next.run(rewritten)
          }
      val m1: AgentMiddleware[IO] = prependMW(tag1)
      val m2: AgentMiddleware[IO] = prependMW(tag2)
      val stack: MiddlewareStack[IO] = MiddlewareStack.validated[IO](List(m1, m2)) match
        case Right(s) => s
        case Left(e)  => sys.error(s"validation failed: $e")
      def traceBase(capture: scala.collection.mutable.ListBuffer[Option[String]]): ModelStep[IO] =
        Kleisli { req =>
          capture += req.systemPrompt.flatMap(_.base)
          IO.pure(ModelResponse(testCompletion, req.state))
        }
      val stackCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val manualCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val stackComposed: ModelStep[IO]  = stack.wrapModelCall(traceBase(stackCapture))
      val manualComposed: ModelStep[IO] = m1.wrapModelCall(m2.wrapModelCall(traceBase(manualCapture)))
      val testReq: ModelRequest[IO]     = testRequest(HarnessState.empty)
      stackComposed.run(testReq).unsafeRunSync()
      manualComposed.run(testReq).unsafeRunSync()
      stackCapture ==== manualCapture

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def validated(ms: List[AgentMiddleware[IO]]): MiddlewareStack[IO] =
    MiddlewareStack.validated[IO](ms) match
      case Right(s) => s
      case Left(e)  => sys.error(s"validation failed: $e")

  private def stubTool(name: String): InvokableTool[IO] =
    new InvokableTool[IO]:
      def info: org.adk4s.core.component.AdkToolInfo =
        org.adk4s.core.component.AdkToolInfo(name, "stub", ujson.Null)
      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None
      def run(input: ujson.Value): IO[ujson.Value]                         = IO.pure(ujson.Null)
