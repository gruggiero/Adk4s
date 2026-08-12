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
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, AssistantMessage }
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Hedgehog properties for the middleware-stack monoid laws (L1, L2, L3, L6).
 *
 * These properties verify observational equivalence via trace equality:
 *   - L1 (monoid-identity-any-position): inserting AgentMiddleware.id
 *     anywhere in a stack produces an observationally equivalent stack
 *   - L2 (monoid-associativity): (a ++ b) ++ c == a ++ (b ++ c)
 *   - L3 (hook-distribution): stack.wrapModelCall(base) == m1(m2(base))
 *   - L6 (disjoint-commutativity): disjoint pure middlewares commute
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * spec: middleware-stack — Laws L1, L2, L3, L6
 */
class MiddlewareStackLawsSpec extends HedgehogSuite:

  // ── Test fixtures ───────────────────────────────────────────────────────

  private def testCompletion: Completion =
    Completion("id", 0L, "content", "model", AssistantMessage(Some("content")), Nil, None, None)

  private def testOptions: CompletionOptions =
    CompletionOptions(temperature = 0.7, maxTokens = None, topP = 1.0)

  private def testRequest(state: HarnessState): ModelRequest[IO] =
    ModelRequest[IO](None, Nil, Nil, testOptions, state)

  // ── L1: Monoid identity holds for any insertion position ────────────────

  property("L1 monoid-identity — identity at head is observationally equivalent"):
    for stackSize <- Gen.int(Range.linear(1, 5)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cells: List[StateCell[Int]] =
        (0 until stackSize).toList.map(i => StateCell[Int](MiddlewareName(s"m$i"), "val", 0))
      def tracingMW(i: Int): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName(s"m$i")
        override def stateCells: List[StateCell[?]] = List(cells(i))
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.update(cells(i))(_ + 1))
      val middlewares: List[AgentMiddleware[IO]] =
        (0 until stackSize).toList.map(i => tracingMW(i))
      val baseStack: MiddlewareStack[IO] = validated(middlewares)
      val headStack: MiddlewareStack[IO] = validated(AgentMiddleware.id[IO] :: middlewares)
      val state0: HarnessState           = HarnessState.initial(cells)
      val baseResult: HarnessState       = baseStack.beforeAgent(state0).unsafeRunSync()
      val headResult: HarnessState       = headStack.beforeAgent(state0).unsafeRunSync()
      val allMatch: Boolean              = cells.forall(c => baseResult.get(c) == headResult.get(c))
      allMatch ==== true

  property("L1 monoid-identity — identity at tail is observationally equivalent"):
    for stackSize <- Gen.int(Range.linear(1, 5)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cells: List[StateCell[Int]] =
        (0 until stackSize).toList.map(i => StateCell[Int](MiddlewareName(s"m$i"), "val", 0))
      def tracingMW(i: Int): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName(s"m$i")
        override def stateCells: List[StateCell[?]] = List(cells(i))
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.update(cells(i))(_ + 1))
      val middlewares: List[AgentMiddleware[IO]] =
        (0 until stackSize).toList.map(i => tracingMW(i))
      val baseStack: MiddlewareStack[IO] = validated(middlewares)
      val tailStack: MiddlewareStack[IO] = validated(middlewares :+ AgentMiddleware.id[IO])
      val state0: HarnessState           = HarnessState.initial(cells)
      val baseResult: HarnessState       = baseStack.beforeAgent(state0).unsafeRunSync()
      val tailResult: HarnessState       = tailStack.beforeAgent(state0).unsafeRunSync()
      val allMatch: Boolean              = cells.forall(c => baseResult.get(c) == tailResult.get(c))
      allMatch ==== true

  property("L1 monoid-identity — identity in middle is observationally equivalent"):
    for
      stackSize <- Gen.int(Range.linear(2, 5)).forAll
      insertPos <- Gen.int(Range.linear(1, stackSize - 1)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cells: List[StateCell[Int]] =
        (0 until stackSize).toList.map(i => StateCell[Int](MiddlewareName(s"m$i"), "val", 0))
      def tracingMW(i: Int): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName(s"m$i")
        override def stateCells: List[StateCell[?]] = List(cells(i))
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.update(cells(i))(_ + 1))
      val middlewares: List[AgentMiddleware[IO]] =
        (0 until stackSize).toList.map(i => tracingMW(i))
      val (before, after)                   = middlewares.splitAt(insertPos)
      val withId: List[AgentMiddleware[IO]] = before ++ List(AgentMiddleware.id[IO]) ++ after
      val baseStack: MiddlewareStack[IO]    = validated(middlewares)
      val midStack: MiddlewareStack[IO]     = validated(withId)
      val state0: HarnessState              = HarnessState.initial(cells)
      val baseResult: HarnessState          = baseStack.beforeAgent(state0).unsafeRunSync()
      val midResult: HarnessState           = midStack.beforeAgent(state0).unsafeRunSync()
      val allMatch: Boolean                 = cells.forall(c => baseResult.get(c) == midResult.get(c))
      allMatch ==== true

  // ── L2: Monoid associativity ────────────────────────────────────────────

  property("L2 monoid-associativity — (a ++ b) ++ c == a ++ (b ++ c)"):
    for
      aSize <- Gen.int(Range.linear(0, 3)).forAll
      bSize <- Gen.int(Range.linear(0, 3)).forAll
      cSize <- Gen.int(Range.linear(0, 3)).forAll
    yield
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      def mkMiddleware(prefix: String, i: Int): (StateCell[Int], AgentMiddleware[IO]) =
        val cell: StateCell[Int] = StateCell[Int](MiddlewareName(s"$prefix$i"), "val", 0)
        val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
          val name: MiddlewareName                    = MiddlewareName(s"$prefix$i")
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
      val leftSide: MiddlewareStack[IO]  = (a ++ b) ++ c
      val rightSide: MiddlewareStack[IO] = a ++ (b ++ c)
      val state0: HarnessState           = HarnessState.initial(allCells)
      val leftResult: HarnessState       = leftSide.beforeAgent(state0).unsafeRunSync()
      val rightResult: HarnessState      = rightSide.beforeAgent(state0).unsafeRunSync()
      val allMatch: Boolean              = allCells.forall(c => leftResult.get(c) == rightResult.get(c))
      allMatch ==== true

  // ── L3: Hook distribution for wrapModelCall ─────────────────────────────

  property("L3 hook-distribution — stack.wrapModelCall == m1(m2(base))"):
    for
      tag1 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
      tag2 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
    yield
      def prependMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName(tag)
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val prevBase: Option[String]          = req.systemPrompt.flatMap(_.base)
            val prevSections: List[PromptSection] = req.systemPrompt.map(_.sections).getOrElse(Nil)
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some(tag + prevBase.getOrElse("")), prevSections))
            )
            next.run(rewritten)
          }
      val m1: AgentMiddleware[IO]    = prependMW(tag1)
      val m2: AgentMiddleware[IO]    = prependMW(tag2)
      val stack: MiddlewareStack[IO] = validated(List(m1, m2))
      def traceBase(capture: scala.collection.mutable.ListBuffer[Option[String]]): ModelStep[IO] =
        Kleisli { req =>
          capture += req.systemPrompt.flatMap(_.base)
          IO.pure(ModelResponse(testCompletion, req.state))
        }
      val stackCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val manualCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      stack.wrapModelCall(traceBase(stackCapture)).run(testRequest(HarnessState.empty)).unsafeRunSync()
      m1.wrapModelCall(m2.wrapModelCall(traceBase(manualCapture)))
        .run(testRequest(HarnessState.empty))
        .unsafeRunSync()
      stackCapture ==== manualCapture

  // ── L3: Hook distribution for wrapToolCall ──────────────────────────────

  property("L3 hook-distribution — stack.wrapToolCall == m1(m2(base))"):
    for
      tag1 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
      tag2 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
    yield
      def traceTool(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName(tag)
        override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
          Kleisli { ctx =>
            // Record trace by modifying the output's result string
            next.run(ctx).map(out => out.copy(output = out.output.copy(result = tag + out.output.result)))
          }
      val m1: AgentMiddleware[IO]    = traceTool(tag1)
      val m2: AgentMiddleware[IO]    = traceTool(tag2)
      val stack: MiddlewareStack[IO] = validated(List(m1, m2))
      val input: ToolInput           = ToolInput("test", "{}", "call-1")
      val state: HarnessState        = HarnessState.empty
      val base: ToolStep[IO] = Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("test", "base", "call-1", false), state)))
      val stackResult: ToolCallOut =
        stack.wrapToolCall(base).run(ToolCallCtx(input, state)).unsafeRunSync()
      val manualResult: ToolCallOut =
        m1.wrapToolCall(m2.wrapToolCall(base)).run(ToolCallCtx(input, state)).unsafeRunSync()
      stackResult.output.result ==== manualResult.output.result

  // ── L6: Disjoint commutativity ──────────────────────────────────────────

  property("L6 disjoint-commutativity — disjoint pure middlewares commute"):
    for
      cellName1 <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      cellName2 <- Gen.string(Gen.alpha, Range.linear(3, 10)).forAll
      // Filter for disjointness: different cell names
      isDisjoint = cellName1 != cellName2
    yield
      if !isDisjoint then Result.success
      else
        given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
        val cell1: StateCell[Int]             = StateCell[Int](MiddlewareName("m1"), cellName1, 0)
        val cell2: StateCell[Int]             = StateCell[Int](MiddlewareName("m2"), cellName2, 0)
        val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
          val name: MiddlewareName                    = MiddlewareName("m1")
          override def stateCells: List[StateCell[?]] = List(cell1)
          override def beforeAgent(state: HarnessState): IO[HarnessState] =
            IO.pure(state.set(cell1)(1))
        val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
          val name: MiddlewareName                    = MiddlewareName("m2")
          override def stateCells: List[StateCell[?]] = List(cell2)
          override def beforeAgent(state: HarnessState): IO[HarnessState] =
            IO.pure(state.set(cell2)(2))
        val s12: MiddlewareStack[IO] = validated(List(m1, m2))
        val s21: MiddlewareStack[IO] = validated(List(m2, m1))
        val state0: HarnessState     = HarnessState.initial(List(cell1, cell2))
        val r12: HarnessState        = s12.beforeAgent(state0).unsafeRunSync()
        val r21: HarnessState        = s21.beforeAgent(state0).unsafeRunSync()
        // Both should set both cells to the same values regardless of order
        (r12.get(cell1) ==== r21.get(cell1)).and(r12.get(cell2) ==== r21.get(cell2))

  // ── Forbidden input scenarios (negative tests) ──────────────────────────

  test("L6 forbidden — overlapping tools break commutativity"):
    withMunitAssertions { a =>
      // This is a forbidden input — the law does not apply when tools overlap.
      // We verify that the stacks are NOT equivalent (confirming the law's
      // precondition is necessary).
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell1: StateCell[Int]             = StateCell[Int](MiddlewareName("m1"), "a", 0)
      val cell2: StateCell[Int]             = StateCell[Int](MiddlewareName("m2"), "b", 0)
      // Both middlewares declare the same cell id (overlapping)
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] = List(cell1)
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.set(cell1)(1))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] = List(cell2)
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.set(cell2)(2))
      // These are disjoint, so they SHOULD commute. The forbidden input test
      // would be if they shared a cell — but validated would reject that.
      // Instead, we verify that validated rejects overlapping cells.
      val m1Bad: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("shared"), "cell", 0))
      val m2Bad: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def stateCells: List[StateCell[?]] =
          List(StateCell[Int](MiddlewareName("shared"), "cell", 0))
      val result: Either[cats.data.NonEmptyList[StackError], MiddlewareStack[IO]] =
        MiddlewareStack.validated[IO](List(m1Bad, m2Bad))
      a.assert(result.isLeft)
    }

  test("L6 forbidden — request rewriting breaks commutativity"):
    withMunitAssertions { a =>
      // Request rewriting is inherently order-sensitive. m1 outermost yields
      // "from-m1", m2 outermost yields "from-m2". The law does not apply.
      def rewriteMW(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName(tag)
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some(tag), Nil))
            )
            next.run(rewritten)
          }
      val m1: AgentMiddleware[IO]  = rewriteMW("from-m1")
      val m2: AgentMiddleware[IO]  = rewriteMW("from-m2")
      val s12: MiddlewareStack[IO] = validated(List(m1, m2))
      val s21: MiddlewareStack[IO] = validated(List(m2, m1))
      val capture12: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val capture21: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val base12: ModelStep[IO] = Kleisli { req =>
        capture12 += req.systemPrompt.flatMap(_.base)
        IO.pure(ModelResponse(testCompletion, req.state))
      }
      val base21: ModelStep[IO] = Kleisli { req =>
        capture21 += req.systemPrompt.flatMap(_.base)
        IO.pure(ModelResponse(testCompletion, req.state))
      }
      s12.wrapModelCall(base12).run(testRequest(HarnessState.empty)).unsafeRunSync()
      s21.wrapModelCall(base21).run(testRequest(HarnessState.empty)).unsafeRunSync()
      // [m1, m2]: m1 is outermost (sets base to "from-m1"), then m2 overwrites
      // to "from-m2". Base sees "from-m2" (innermost overwrite wins).
      // [m2, m1]: m2 is outermost (sets base to "from-m2"), then m1 overwrites
      // to "from-m1". Base sees "from-m1".
      // These are NOT equal — request rewriting breaks commutativity.
      a.assertEquals(capture12.headOption.flatten, Some("from-m2"))
      a.assertEquals(capture21.headOption.flatten, Some("from-m1"))
    }

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def validated(ms: List[AgentMiddleware[IO]]): MiddlewareStack[IO] =
    MiddlewareStack.validated[IO](ms) match
      case Right(s) => s
      case Left(e)  => sys.error(s"validation failed: $e")
