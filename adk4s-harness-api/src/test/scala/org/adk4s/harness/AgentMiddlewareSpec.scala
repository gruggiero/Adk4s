package org.adk4s.harness

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
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, AssistantMessage, Message, UserMessage }
import cats.Applicative
import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

/**
 * Scenario tests and Hedgehog properties for the agent-middleware spec.
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * spec: agent-middleware — Proof Obligations table
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
class AgentMiddlewareSpec extends HedgehogSuite:

  // ── Test fixtures ───────────────────────────────────────────────────────

  private def testCompletion: Completion =
    Completion("id", 0L, "content", "model", AssistantMessage(Some("content")), Nil, None, None)

  private def testOptions: CompletionOptions =
    CompletionOptions(temperature = 0.7, maxTokens = None, topP = 1.0)

  private def testRequest(state: HarnessState): ModelRequest[IO] =
    ModelRequest[IO](None, Nil, Nil, testOptions, state)

  // ── Default hooks are no-ops ────────────────────────────────────────────

  test("Default beforeAgent returns input state unchanged"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      val state: HarnessState     = HarnessState.empty
      val result: HarnessState    = mw.beforeAgent(state).unsafeRunSync()
      a.assertEquals(result, state)
    }

  test("Default afterAgent returns input state unchanged"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      val state: HarnessState     = HarnessState.empty
      val result: HarnessState    = mw.afterAgent(state).unsafeRunSync()
      a.assertEquals(result, state)
    }

  test("Default wrapModelCall returns the same step reference"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      val base: ModelStep[IO]     = Kleisli(_ => IO.pure(ModelResponse(testCompletion, HarnessState.empty)))
      val wrapped: ModelStep[IO]  = mw.wrapModelCall(base)
      a.assert(wrapped eq base)
    }

  test("Default wrapToolCall returns the same step reference"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      val base: ToolStep[IO] = Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("t", "r", "c", false), HarnessState.empty)))
      val wrapped: ToolStep[IO] = mw.wrapToolCall(base)
      a.assert(wrapped eq base)
    }

  // ── Default contributions are all empty ─────────────────────────────────

  test("Default stateCells, tools, and promptSections are all empty"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      a.assertEquals(mw.stateCells, Nil)
      a.assertEquals(mw.tools, Nil)
      a.assertEquals(mw.promptSections(HarnessState.empty), Nil)
    }

  // ── Middleware declares state cells, tools, and state-aware prompt sections ──

  test("Middleware with state cell, tools, and state-aware promptSections"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell: StateCell[Int]              = StateCell[Int](MiddlewareName("m"), "counter", 0)
      val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                                             = MiddlewareName("m")
        override def stateCells: List[StateCell[?]]                          = List(cell)
        override def tools: List[org.adk4s.core.component.InvokableTool[IO]] = Nil
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("counter", s"Counter: ${state.get(cell)}"))

      a.assertEquals(mw.stateCells.length, 1)
      a.assertEquals(mw.tools.length, 0)
      val sections: List[PromptSection] = mw.promptSections(HarnessState.initial(List(cell)).set(cell)(42))
      a.assertEquals(sections.length, 1)
      a.assertEquals(sections.headOption.map(_.name), Some("counter"))
      a.assert(sections.headOption.exists(_.body.contains("42")))
    }

  test("Static-section middleware ignores the state parameter"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("fs")
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("tools", "Use these tools..."))

      val s1: List[PromptSection] = mw.promptSections(HarnessState.empty)
      val s2: List[PromptSection] = mw.promptSections(HarnessState.empty)
      a.assertEquals(s1, s2)
    }

  test("Static-section middleware unaffected by re-folding on different states"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("fs")
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("tools", "Use these tools..."))

      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell: StateCell[Int]              = StateCell[Int](MiddlewareName("x"), "n", 0)
      val s0: HarnessState                  = HarnessState.initial(List(cell))
      val s1: HarnessState                  = s0.set(cell)(999)
      val sections0: List[PromptSection]    = mw.promptSections(s0)
      val sections1: List[PromptSection]    = mw.promptSections(s1)
      a.assertEquals(sections0, sections1)
    }

  test("Sections are in stack order (list flatMap preserves order)"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("first", "m1 body"))
      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("second", "m2 body"))

      val stack: List[AgentMiddleware[IO]] = List(m1, m2)
      val folded: List[PromptSection]      = stack.flatMap(_.promptSections(HarnessState.empty))
      a.assertEquals(folded.map(_.name), List("first", "second"))
    }

  test("State-aware middleware reflects recalled content"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[String] = upickle.default.readwriter[String]
      val cell: StateCell[String]              = StateCell[String](MiddlewareName("skills"), "doc", "")
      val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName                    = MiddlewareName("skills")
        override def stateCells: List[StateCell[?]] = List(cell)
        override def beforeAgent(state: HarnessState): IO[HarnessState] =
          IO.pure(state.set(cell)("Loaded skills document"))
        override def promptSections(state: HarnessState): List[PromptSection] =
          List(PromptSection("skills", state.get(cell)))

      val s0: HarnessState              = HarnessState.initial(List(cell))
      val s1: HarnessState              = mw.beforeAgent(s0).unsafeRunSync()
      val sections: List[PromptSection] = mw.promptSections(s1)
      a.assertEquals(sections.headOption.map(_.body), Some("Loaded skills document"))
    }

  // ── AgentMiddleware.id is the identity middleware ───────────────────────

  test("AgentMiddleware.id name is 'identity'"):
    withMunitAssertions { a =>
      val mw: AgentMiddleware[IO] = AgentMiddleware.id[IO]
      a.assertEquals(mw.name.value, "identity")
    }

  // ── wrap-model-call composes as outermost-first nesting ─────────────────

  test("Two-middleware nesting: m1.wrapModelCall(m2.wrapModelCall(base))"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val prevBase: Option[String]          = req.systemPrompt.flatMap(_.base)
            val prevSections: List[PromptSection] = req.systemPrompt.map(_.sections).getOrElse(Nil)
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some("m1-" + prevBase.getOrElse("")), prevSections))
            )
            next.run(rewritten)
          }

      val m2: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m2")
        override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
          Kleisli { req =>
            val prevBase: Option[String]          = req.systemPrompt.flatMap(_.base)
            val prevSections: List[PromptSection] = req.systemPrompt.map(_.sections).getOrElse(Nil)
            val rewritten: ModelRequest[IO] = req.copy(
              systemPrompt = Some(SystemPrompt(Some("m2-" + prevBase.getOrElse("")), prevSections))
            )
            next.run(rewritten)
          }

      val capturedRef: scala.collection.mutable.ListBuffer[Option[SystemPrompt]] =
        scala.collection.mutable.ListBuffer.empty
      val base: ModelStep[IO] = Kleisli { req =>
        capturedRef += req.systemPrompt
        IO.pure(ModelResponse(testCompletion, req.state))
      }

      val composed: ModelStep[IO] = m1.wrapModelCall(m2.wrapModelCall(base))
      composed.run(testRequest(HarnessState.empty)).unsafeRunSync()
      // m1 is outermost (sees request first, prepends "m1-"), m2 is inner
      // (sees m1's rewrite, prepends "m2-" → "m2-m1-"). The base sees the
      // composition: "m2-m1-". The spec's example text says "m1-m2-" but with
      // prepend semantics, m1's tag is applied first and then covered by m2's
      // prepend, so the base sees "m2-m1-". The nesting ORDER (m1 outermost)
      // is what the spec mandates; the string ordering is a consequence of
      // prepend direction.
      a.assertEquals(capturedRef.headOption.flatMap(_.flatMap(_.base)), Some("m2-m1-"))
    }

  test("Empty stack is the base step (single middleware is trivially nested)"):
    withMunitAssertions { a =>
      val m1: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName("m1")
      val base: ModelStep[IO]    = Kleisli(_ => IO.pure(ModelResponse(testCompletion, HarnessState.empty)))
      val wrapped: ModelStep[IO] = m1.wrapModelCall(base)
      // Single middleware: wrapped should delegate to base
      val result: ModelResponse = wrapped.run(testRequest(HarnessState.empty)).unsafeRunSync()
      a.assertEquals(result.completion, testCompletion)
    }

  // ── ModelRequest / ModelResponse scenarios ──────────────────────────────

  test("ModelRequest with no system prompt has None"):
    withMunitAssertions { a =>
      val req: ModelRequest[IO] = ModelRequest[IO](None, Nil, Nil, testOptions, HarnessState.empty)
      a.assertEquals(req.systemPrompt, None)
    }

  test("ModelRequest with empty tool list is valid"):
    withMunitAssertions { a =>
      val req: ModelRequest[IO] = ModelRequest[IO](None, Nil, Nil, testOptions, HarnessState.empty)
      a.assertEquals(req.tools, Nil)
    }

  test("ModelResponse state flows forward"):
    withMunitAssertions { a =>
      val state1: HarnessState = HarnessState.empty
      val resp: ModelResponse  = ModelResponse(testCompletion, state1)
      a.assertEquals(resp.state, state1)
    }

  // ── ToolCallCtx / ToolCallOut scenarios ─────────────────────────────────

  test("ToolCallCtx carries state into a stateful tool"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell: StateCell[Int]              = StateCell[Int](MiddlewareName("todo"), "count", 0)
      val s0: HarnessState                  = HarnessState.initial(List(cell))
      val input: ToolInput                  = ToolInput("write-todos", "{}", "call-1")
      val ctx: ToolCallCtx                  = ToolCallCtx(input, s0)
      a.assertEquals(ctx.input, input)
      a.assertEquals(ctx.state, s0)
    }

  test("ToolCallOut state is the input to the next tool call"):
    withMunitAssertions { a =>
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell: StateCell[Int]              = StateCell[Int](MiddlewareName("todo"), "count", 0)
      val s0: HarnessState                  = HarnessState.initial(List(cell))
      val s1: HarnessState                  = s0.set(cell)(5)
      val output: ToolOutput                = ToolOutput("write-todos", "ok", "call-1", false)
      val out: ToolCallOut                  = ToolCallOut(output, s1)
      // Next call's context uses s1
      val nextCtx: ToolCallCtx = ToolCallCtx(ToolInput("read", "{}", "call-2"), out.state)
      a.assertEquals(nextCtx.state.get(cell), 5)
    }

  // ── MiddlewareName used in stack-error attribution ──────────────────────

  test("MiddlewareName used in cell-id formation"):
    withMunitAssertions { a =>
      val owner: MiddlewareName             = MiddlewareName("todo-list")
      given upickle.default.ReadWriter[Int] = upickle.default.readwriter[Int]
      val cell: StateCell[Int]              = StateCell[Int](owner, "items", 0)
      a.assertEquals(cell.id.value, "todo-list/items")
    }

  // ── Hedgehog property: default-neutrality ───────────────────────────────
  // For any all-defaults middleware, beforeAgent/afterAgent are pure and
  // wrapModelCall/wrapToolCall are pass-through.

  property("default-neutrality — all-defaults middleware hooks are no-ops"):
    for mwName <- Gen.string(Gen.alpha, Range.linear(3, 20)).forAll
    yield
      val mw: AgentMiddleware[IO] = new AgentMiddleware[IO]:
        val name: MiddlewareName = MiddlewareName.refineEither(mwName).fold(err => throw err, identity)
      val state: HarnessState         = HarnessState.empty
      val beforeResult: HarnessState  = mw.beforeAgent(state).unsafeRunSync()
      val afterResult: HarnessState   = mw.afterAgent(state).unsafeRunSync()
      val baseModel: ModelStep[IO]    = Kleisli(_ => IO.pure(ModelResponse(testCompletion, state)))
      val wrappedModel: ModelStep[IO] = mw.wrapModelCall(baseModel)
      val baseTool: ToolStep[IO]      = Kleisli(_ => IO.pure(ToolCallOut(ToolOutput("t", "r", "c", false), state)))
      val wrappedTool: ToolStep[IO]   = mw.wrapToolCall(baseTool)
      (beforeResult ==== state)
        .and(afterResult ==== state)
        .and((wrappedModel eq baseModel) ==== true)
        .and((wrappedTool eq baseTool) ==== true)

  // ── Hedgehog property: hook-distribution-wrap-model-call ────────────────
  // For a stack [m1, m2], stack.wrapModelCall(base) composes as
  // m1.wrapModelCall(m2.wrapModelCall(base)) — outermost-first nesting.
  // Tested by trace equality: the system prompt seen by the base step must
  // match between manual and stack composition. Comparing completions would
  // be tautological (base always returns the same completion regardless of
  // the request), so we capture the request trace instead.

  property("hook-distribution-wrap-model-call — outermost-first nesting by trace equality"):
    for
      tag1 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
      tag2 <- Gen.string(Gen.alpha, Range.linear(1, 5)).forAll
    yield
      def traceMiddleware(tag: String): AgentMiddleware[IO] = new AgentMiddleware[IO]:
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

      val m1: AgentMiddleware[IO]          = traceMiddleware(tag1)
      val m2: AgentMiddleware[IO]          = traceMiddleware(tag2)
      val stack: List[AgentMiddleware[IO]] = List(m1, m2)

      // A base step that records the system prompt base it receives.
      def traceCapturingBase(capture: scala.collection.mutable.ListBuffer[Option[String]]): ModelStep[IO] =
        Kleisli { req =>
          capture += req.systemPrompt.flatMap(_.base)
          IO.pure(ModelResponse(testCompletion, req.state))
        }

      // Manual composition: m1(m2(base))
      val manualCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val manual: ModelStep[IO] =
        m1.wrapModelCall(m2.wrapModelCall(traceCapturingBase(manualCapture)))

      // Stack composition via foldRight — same as MiddlewareStack will do
      val composedCapture: scala.collection.mutable.ListBuffer[Option[String]] =
        scala.collection.mutable.ListBuffer.empty
      val composed: ModelStep[IO] =
        stack.foldRight(traceCapturingBase(composedCapture))((mw, step) => mw.wrapModelCall(step))

      val testReq: ModelRequest[IO] = testRequest(HarnessState.empty)
      manual.run(testReq).unsafeRunSync()
      composed.run(testReq).unsafeRunSync()

      // The base step must see the same system prompt base in both compositions.
      // With prepend semantics and m1 outermost: m1 prepends tag1 first, then
      // m2 prepends tag2, so the base sees "tag2+tag1" (inner tag appears first
      // in the string). A wrong fold order (e.g. foldLeft) would produce
      // "tag1+tag2" — the reversed order.
      (manualCapture ==== composedCapture).and(manualCapture.headOption.flatten ==== Some(tag2 + tag1))

  // ── Hedgehog property: prompt-sections-state-awareness ──────────────────
  // For any stack and any harness state, the folded sections are the
  // concatenation of each middleware's promptSections(state) in stack order,
  // and folding on two different states produces different sections whenever
  // at least one middleware's sections depend on state.

  property("prompt-sections-state-awareness — folded sections match stack order and reflect state"):
    for
      stackSize <- Gen.int(Range.linear(1, 5)).forAll
      cellValues <- Gen
        .string(Gen.alpha, Range.linear(1, 20))
        .list(Range.linear(stackSize, stackSize))
        .forAll
      cellValues2 <- Gen
        .string(Gen.alpha, Range.linear(1, 20))
        .list(Range.linear(stackSize, stackSize))
        .forAll
    yield
      given upickle.default.ReadWriter[String] = upickle.default.readwriter[String]

      // Each middleware has a private cell whose value drives promptSections.
      // The cell value is generated, so the section body reflects live state.
      val middlewares: List[AgentMiddleware[IO]] =
        cellValues.zipWithIndex.map { (cellValue, i) =>
          val cell: StateCell[String] =
            StateCell[String](MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity), "data", "")
          new AgentMiddleware[IO]:
            val name: MiddlewareName                    = MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity)
            override def stateCells: List[StateCell[?]] = List(cell)
            override def promptSections(state: HarnessState): List[PromptSection] =
              List(PromptSection(s"section-$i", state.get(cell)))
        }

      // Build two states with different cell values.
      val cells: List[StateCell[String]] = middlewares.zipWithIndex.map { (mw, i) =>
        StateCell[String](MiddlewareName.refineEither(s"m$i").fold(err => throw err, identity), "data", "")
      }
      def buildState(values: List[String]): HarnessState =
        values.zip(cells).foldLeft(HarnessState.initial(cells)) { case (acc, (v, c)) =>
          acc.set(c)(v)
        }
      val state0: HarnessState = buildState(cellValues)
      val state1: HarnessState = buildState(cellValues2)

      // Folded sections = flatMap in stack order (the loop's fold semantics).
      val folded0: List[PromptSection] = middlewares.flatMap(_.promptSections(state0))
      val folded1: List[PromptSection] = middlewares.flatMap(_.promptSections(state1))

      // Invariant 1: the fold is the concatenation of each middleware's
      // promptSections(state) in stack order.
      val expected0: List[PromptSection] =
        middlewares.zipWithIndex.flatMap((mw, i) => mw.promptSections(state0))
      val orderMatches: Boolean = folded0.map(_.name) == expected0.map(_.name)

      // Invariant 2: when the generated cell values differ between state0 and
      // state1, the section bodies must differ (state-awareness). When all
      // values happen to be equal, the sections are equal (harmless for
      // state-aware middlewares — the invariant is one-directional).
      val valuesDiffer: Boolean   = cellValues != cellValues2
      val sectionsDiffer: Boolean = folded0.map(_.body) != folded1.map(_.body)
      val stateAwarenessHolds: Boolean =
        (valuesDiffer && sectionsDiffer) || (!valuesDiffer && !sectionsDiffer)

      (orderMatches ==== true).and(stateAwarenessHolds ==== true)
