package org.adk4s.harness.testkit

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import hedgehog.Gen
import hedgehog.Property
import hedgehog.Result
import hedgehog.Syntax
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.json.JsonValue
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.harness.{
  AgentMiddleware,
  HarnessState,
  MiddlewareStack,
  ModelRequest,
  ModelResponse,
  ModelStep,
  StateCell,
  SystemPrompt,
  ToolCallCtx,
  ToolCallOut,
  ToolStep
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Message }
import smithy4s.Document
import Generators.*

/**
 * Reusable behavioral laws (L0–L10) for the agent middleware stack, in the
 * `AgentMemoryLaws` style. Each law is a Hedgehog `Property` runnable by any
 * downstream middleware author against their own stack and deterministic
 * model double.
 *
 * The laws are the statement that the middleware monoid structure is
 * observationally real, not just syntactic. Observational equivalence (`≍`)
 * means: driven by the testkit's `DeterministicChatModel` double and a fixed
 * tool set and input, both sides produce equal final `AssistantMessage`,
 * equal final `HarnessState.snapshot`, and equal request traces at the base
 * step.
 *
 * The laws use a `SimpleHarnessLoop` runner (a minimal, deterministic ReAct
 * loop in the testkit) so the testkit does NOT depend on
 * `adk4s-orchestration`. The orchestration-level L0 (HarnessAgent vs
 * ReactAgent sugar) is covered by `HarnessAgentSpec` (spec 5).
 *
 * spec: middleware-laws — Requirements L0–L10
 */
final class AgentMiddlewareLaws(seed: Long, basePrompt: Option[String]):

  import AgentMiddlewareLaws.*

  /**
   * Build a fresh (model, loop, tools) for a tool behavior. Each property
   * side gets its own model so the two sides' request traces do not
   * cross-contaminate.
   */
  def loopFor(
    behavior: ToolBehavior,
    signal: InterruptSignal
  ): IO[(DeterministicChatModel, SimpleHarnessLoop, List[InvokableTool[IO]])] =
    val tools: List[InvokableTool[IO]] = toolsFor(behavior, signal)
    DeterministicChatModel(seed, genScript(seed, behavior)).map((m: DeterministicChatModel) =>
      (m, new SimpleHarnessLoop(m, tools, basePrompt), tools)
    )

  // ── L0 Conservative refactor equivalence (gatekeeper) ─────────────────────

  def l0ConservativeRefactor: Property =
    for c <- l0Case.forAll
        .cover(15, "no-tools", (c: L0Case) => c.behavior == ToolBehavior.NoTool)
        .cover(
          30,
          "with-tools",
          (c: L0Case) => c.behavior == ToolBehavior.SingleTool || c.behavior == ToolBehavior.MultiTool
        )
        .cover(15, "interrupt", (c: L0Case) => c.behavior == ToolBehavior.InterruptOnFirst)
        .cover(15, "step-exhaustion", (c: L0Case) => c.behavior == ToolBehavior.NeverTerminates)
    yield
      val ok: Boolean = l0Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L0-conservative-refactor")

  private def l0Body(c: L0Case): IO[Boolean] =
    val signal: InterruptSignal = InterruptSignal.simple("sig")
    for
      (_, loop1, _) <- loopFor(c.behavior, signal)
      (_, loop2, _) <- loopFor(c.behavior, signal)
      r1            <- loop1.run(MiddlewareStack.empty[IO], c.conversation, c.maxSteps)
      r2            <- loop2.runBaseline(c.conversation, c.maxSteps)
    yield r1.eqStrict(r2)

  // ── L1 Monoid identity ─────────────────────────────────────────────────────

  def l1MonoidIdentity: Property =
    for c <- l1Case.forAll
        .cover(10, "empty-stack", (c: L1Case) => c.stack.middlewares.isEmpty)
        .cover(25, "head", (c: L1Case) => c.pos == Position.Head)
        .cover(25, "middle", (c: L1Case) => c.pos == Position.Middle)
        .cover(25, "tail", (c: L1Case) => c.pos == Position.Tail)
    yield
      val ok: Boolean = l1Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L1-monoid-identity")

  private def l1Body(c: L1Case): IO[Boolean] =
    val signal: InterruptSignal     = InterruptSignal.simple("sig")
    val withId: MiddlewareStack[IO] = insert(c.stack, AgentMiddleware.id[IO], c.pos)
    for
      (_, loop1, _) <- loopFor(ToolBehavior.NoTool, signal)
      (_, loop2, _) <- loopFor(ToolBehavior.NoTool, signal)
      r1            <- loop1.run(c.stack, c.conversation, c.maxSteps)
      r2            <- loop2.run(withId, c.conversation, c.maxSteps)
    yield r1.eqStrict(r2)

  // ── L2 Monoid associativity ────────────────────────────────────────────────

  def l2MonoidAssociativity: Property =
    for c <- l2Case.forAll
        .cover(
          40,
          "all-nonempty",
          (c: L2Case) => c.a.middlewares.nonEmpty && c.b.middlewares.nonEmpty && c.c.middlewares.nonEmpty
        )
        .cover(
          30,
          "one-empty",
          (c: L2Case) => c.a.middlewares.isEmpty || c.b.middlewares.isEmpty || c.c.middlewares.isEmpty
        )
    yield
      val ok: Boolean = l2Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L2-monoid-associativity")

  private def l2Body(c: L2Case): IO[Boolean] =
    val signal: InterruptSignal    = InterruptSignal.simple("sig")
    val left: MiddlewareStack[IO]  = (c.a ++ c.b) ++ c.c
    val right: MiddlewareStack[IO] = c.a ++ (c.b ++ c.c)
    for
      (_, loop1, _) <- loopFor(ToolBehavior.NoTool, signal)
      (_, loop2, _) <- loopFor(ToolBehavior.NoTool, signal)
      r1            <- loop1.run(left, c.conversation, c.maxSteps)
      r2            <- loop2.run(right, c.conversation, c.maxSteps)
    yield r1.eqStrict(r2)

  // ── L3 Hook distribution ───────────────────────────────────────────────────

  def l3HookDistribution: Property =
    for c <- l3Case.forAll
        .cover(40, "both-rewrite", (c: L3Case) => c.m1.rewrites && c.m2.rewrites)
        .cover(30, "one-rewrites", (c: L3Case) => c.m1.rewrites != c.m2.rewrites)
    yield
      val ok: Boolean = l3Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L3-hook-distribution")

  private def l3Body(c: L3Case): IO[Boolean] =
    val signal: InterruptSignal = InterruptSignal.simple("sig")
    val req: ModelRequest[IO]   = testRequest
    for
      (m1, _, _) <- loopFor(ToolBehavior.NoTool, signal)
      (m2, _, _) <- loopFor(ToolBehavior.NoTool, signal)
      base1: ModelStep[IO]       = baseStep(m1)
      base2: ModelStep[IO]       = baseStep(m2)
      stack: MiddlewareStack[IO] = MiddlewareStack.validated(List(c.m1, c.m2)).getOrElse(MiddlewareStack.empty[IO])
      // W2: wrapModelCall distribution — compare full RecordedRequest equality
      _  <- stack.wrapModelCall(base1).run(req)
      _  <- c.m1.wrapModelCall(c.m2.wrapModelCall(base2)).run(req)
      t1 <- m1.capturedRequests.get
      t2 <- m2.capturedRequests.get
      // W1: wrapToolCall distribution — compare tool-call trace order
      toolTraceOk <- l3ToolCallDistribution(c.m1, c.m2)
    yield t1.headOption == t2.headOption && toolTraceOk

  /**
   * W1: Checks that `stack.wrapToolCall(base)` composes in the same order as
   * `m1.wrapToolCall(m2.wrapToolCall(base))` by recording middleware names
   * into trace refs and comparing. Uses separate middleware instances for
   * each side to avoid trace accumulation in the shared ref.
   */
  private def l3ToolCallDistribution(m1: PromptRewriteMiddleware, m2: PromptRewriteMiddleware): IO[Boolean] =
    val baseTool: ToolStep[IO] = Kleisli { (ctx: ToolCallCtx) =>
      IO.pure(ToolCallOut(ToolOutput("test", "ok", "test", isError = false), ctx.state))
    }
    val dummyCtx: ToolCallCtx = ToolCallCtx(
      input = ToolInput("test", "{}", "test"),
      state = HarnessState.empty
    )
    for
      // Side 1: stack-composed [traceMw1a, traceMw2a]
      traceMw1a <- Generators.TraceMiddleware("t1", "", false)
      traceMw2a <- Generators.TraceMiddleware("t2", "", false)
      stackA: MiddlewareStack[IO] =
        MiddlewareStack.validated(List(traceMw1a, traceMw2a)).getOrElse(MiddlewareStack.empty[IO])
      _          <- stackA.wrapToolCall(baseTool).run(dummyCtx)
      stackTrace <- traceMw1a.traceRef.get
      // Side 2: manually-composed traceMw1b.wrapToolCall(traceMw2b.wrapToolCall(baseTool))
      traceMw1b   <- Generators.TraceMiddleware("t1", "", false)
      traceMw2b   <- Generators.TraceMiddleware("t2", "", false)
      _           <- traceMw1b.wrapToolCall(traceMw2b.wrapToolCall(baseTool)).run(dummyCtx)
      manualTrace <- traceMw1b.traceRef.get
    yield stackTrace == manualTrace

  // ── L3 single-element stack identity (W3) ──────────────────────────────────

  def l3SingleElementIdentity: Property =
    for c <- l3SingleCase.forAll
    yield
      val ok: Boolean = l3SingleBody(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L3-single-element-identity")

  private def l3SingleBody(c: L3SingleCase): IO[Boolean] =
    val signal: InterruptSignal = InterruptSignal.simple("sig")
    val req: ModelRequest[IO]   = testRequest
    for
      (m1, _, _) <- loopFor(ToolBehavior.NoTool, signal)
      (m2, _, _) <- loopFor(ToolBehavior.NoTool, signal)
      base1: ModelStep[IO]       = baseStep(m1)
      base2: ModelStep[IO]       = baseStep(m2)
      stack: MiddlewareStack[IO] = MiddlewareStack.validated(List(c.m)).getOrElse(MiddlewareStack.empty[IO])
      _  <- stack.wrapModelCall(base1).run(req)
      _  <- c.m.wrapModelCall(base2).run(req)
      t1 <- m1.capturedRequests.get
      t2 <- m2.capturedRequests.get
    yield t1.headOption == t2.headOption

  // ── L4 Default neutrality ──────────────────────────────────────────────────

  def l4DefaultNeutrality: Property =
    for c <- l4Case.forAll yield
      val ok: Boolean = l4Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L4-default-neutrality")

  private def l4Body(c: L4Case): IO[Boolean] =
    val signal: InterruptSignal = InterruptSignal.simple("sig")
    val stackM: MiddlewareStack[IO] =
      MiddlewareStack.validated(List(c.m)).getOrElse(MiddlewareStack.empty[IO])
    val stackId: MiddlewareStack[IO] =
      MiddlewareStack.validated(List(AgentMiddleware.id[IO])).getOrElse(MiddlewareStack.empty[IO])
    for
      (_, loop1, _) <- loopFor(ToolBehavior.NoTool, signal)
      (_, loop2, _) <- loopFor(ToolBehavior.NoTool, signal)
      r1            <- loop1.run(stackM, c.conversation, c.maxSteps)
      r2            <- loop2.run(stackId, c.conversation, c.maxSteps)
    yield r1.eqStrict(r2)

  // ── L5 Cell frame rule ─────────────────────────────────────────────────────

  def l5CellFrameRule: Property =
    for c <- l5Case.forAll
        .cover(25, "single-cell", (c: L5Case) => c.m.cells.length == 1)
        .cover(40, "multi-cell", (c: L5Case) => c.m.cells.length >= 2)
        .cover(20, "same-type-external", (c: L5Case) => c.m.cells.nonEmpty)
    yield
      val ok: Boolean = l5Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L5-cell-frame-rule")

  private def l5Body(c: L5Case): IO[Boolean] =
    val s: HarnessState = HarnessState.initial(c.m.stateCells).set(c.externalCell)(c.externalValue)
    for
      s1 <- c.m.beforeAgent(s)
      s2 <- c.m.afterAgent(s1)
    yield s1.get(c.externalCell) == c.externalValue && s2.get(c.externalCell) == c.externalValue

  // ── L6 Disjoint commutativity ──────────────────────────────────────────────

  def l6DisjointCommutativity: Property =
    for c <- l6Case.forAll
        .cover(40, "both-with-cells", (c: L6Case) => c.pair._1.cells.nonEmpty && c.pair._2.cells.nonEmpty)
        .cover(30, "one-cellless", (c: L6Case) => c.pair._1.cells.isEmpty || c.pair._2.cells.isEmpty)
    yield
      val ok: Boolean = l6Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L6-disjoint-commutativity")

  private def l6Body(c: L6Case): IO[Boolean] =
    val signal: InterruptSignal = InterruptSignal.simple("sig")
    val (m1, m2)                = c.pair
    val stack12: MiddlewareStack[IO] =
      MiddlewareStack.validated(List(m1, m2)).getOrElse(MiddlewareStack.empty[IO])
    val stack21: MiddlewareStack[IO] =
      MiddlewareStack.validated(List(m2, m1)).getOrElse(MiddlewareStack.empty[IO])
    for
      (_, loop1, _) <- loopFor(ToolBehavior.NoTool, signal)
      (_, loop2, _) <- loopFor(ToolBehavior.NoTool, signal)
      r1            <- loop1.run(stack12, c.conversation, c.maxSteps)
      r2            <- loop2.run(stack21, c.conversation, c.maxSteps)
    yield r1 ≍ r2

  // ── L7 Codec round-trip ────────────────────────────────────────────────────

  def l7CodecRoundTrip: Property =
    for c <- l7Case.forAll
        .cover(
          20,
          "Int",
          (c: L7Case) =>
            c.tcs.exists(tc =>
              tc match
                case _: TypedCell.IntCell => true
                case _                    => false
            )
        )
        .cover(
          20,
          "String",
          (c: L7Case) =>
            c.tcs.exists(tc =>
              tc match
                case _: TypedCell.StringCell => true
                case _                       => false
            )
        )
        .cover(
          20,
          "List",
          (c: L7Case) =>
            c.tcs.exists(tc =>
              tc match
                case _: TypedCell.ListIntCell => true
                case _                        => false
            )
        )
        .cover(15, "at-initial", (c: L7Case) => c.tcs.exists(_.equalsInitial(c.state)))
    yield
      val ok: Boolean = l7Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L7-codec-round-trip")

  private def l7Body(c: L7Case): IO[Boolean] =
    val cells: List[StateCell[?]] = c.tcs.map(_.cell)
    val snap: JsonValue           = c.state.snapshot
    HarnessState.restore(cells, snap) match
      case Right(s2) => IO.pure(c.tcs.forall(_.equalsIn(s2, c.state)))
      case Left(_)   => IO.pure(false)

  // ── L8 Restore leniency ────────────────────────────────────────────────────

  def l8RestoreLeniency: Property =
    for c <- l8Case.forAll
        .cover(30, "with-unknown", (c: L8Case) => c.unknownFields.nonEmpty)
        .cover(30, "with-new-cells", (c: L8Case) => c.newTcs.nonEmpty)
        .cover(20, "both", (c: L8Case) => c.unknownFields.nonEmpty && c.newTcs.nonEmpty)
    yield
      val ok: Boolean = l8Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L8-restore-leniency")

  private def l8Body(c: L8Case): IO[Boolean] =
    val cells: List[StateCell[?]]    = c.tcs.map(_.cell)
    val newCells: List[StateCell[?]] = c.newTcs.map(_.cell)
    val snap: JsonValue              = c.state.snapshot
    val withUnknowns: JsonValue = snap match
      case Document.DObject(fields) => Document.DObject(fields ++ c.unknownFields)
      case other                    => other
    val r1: Either[?, HarnessState] = HarnessState.restore(cells, withUnknowns)
    val r2: Either[?, HarnessState] = HarnessState.restore(cells ++ newCells, snap)
    val ok1: Boolean = r1 match
      case Right(s2) => c.tcs.forall(_.equalsIn(s2, c.state))
      case Left(_)   => false
    val ok2: Boolean = r2 match
      case Right(s2) => c.newTcs.forall(_.equalsInitial(s2))
      case Left(_)   => false
    IO.pure(ok1 && ok2)

  // ── L9 Privacy ─────────────────────────────────────────────────────────────

  def l9Privacy: Property =
    for c <- l9Case.forAll
        .cover(25, "single-child", (c: L9Case[?]) => c.children.length == 1)
        .cover(40, "multi-child", (c: L9Case[?]) => c.children.length >= 2)
        .cover(
          15,
          "writes-initial",
          (c: L9Case[?]) =>
            c match
              case L9Case(tc, _, children) => children.exists(ch => tc.equalsInitial(ch))
        )
    yield
      val ok: Boolean = l9Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L9-privacy")

  private def l9Body(c: L9Case[?]): IO[Boolean] = c match
    case L9Case(tc, parentValue, children) =>
      val cells: List[StateCell[?]] = List(tc.cell)
      val parent: HarnessState      = tc.stateWith(parentValue)
      val projected: HarnessState   = HarnessState.project(parent, cells)
      val merged: HarnessState      = HarnessState.mergeBack(parent, children, cells)
      IO.pure(tc.equalsInitial(projected) && tc.get(merged) == parentValue)

  // ── L10 Merge-back neutrality ──────────────────────────────────────────────

  def l10MergeBackNeutrality: Property =
    for c <- l10Case.forAll
        .cover(
          20,
          "private-only",
          (c: L10Case) =>
            c.tcs.forall(tc =>
              tc match
                case stc: SharedTypedCell[?] => false
                case _                       => true
            )
        )
        .cover(
          25,
          "shared-union",
          (c: L10Case) =>
            c.tcs.exists(tc =>
              tc match
                case _: SharedTypedCell.UnionCell => true
                case _                            => false
            )
        )
        .cover(
          20,
          "shared-max",
          (c: L10Case) =>
            c.tcs.exists(tc =>
              tc match
                case _: SharedTypedCell.MaxCell => true
                case _                          => false
            )
        )
    yield
      val ok: Boolean = l10Body(c).unsafeRunSync()
      if ok then Result.success else Result.failure.log("L10-merge-back-neutrality")

  private def l10Body(c: L10Case): IO[Boolean] =
    val cells: List[StateCell[?]] = c.tcs.map(_.cell)
    val child: HarnessState       = HarnessState.project(c.parent, cells)
    val merged: HarnessState      = HarnessState.mergeBack(c.parent, List(child), cells)
    IO.pure(c.tcs.forall(_.equalsIn(merged, c.parent)))

  // ── Helpers ────────────────────────────────────────────────────────────────

  /** A base model step that delegates to the given deterministic model. */
  private def baseStep(model: DeterministicChatModel): ModelStep[IO] =
    Kleisli { (request: ModelRequest[IO]) =>
      val sysMsgs: List[Message] = request.systemPrompt match
        case Some(sp) if sp.base.isDefined || sp.sections.nonEmpty =>
          List(org.llm4s.llmconnect.model.SystemMessage(sp.render))
        case _ => Nil
      val conv: org.llm4s.llmconnect.model.Conversation =
        org.llm4s.llmconnect.model.Conversation(sysMsgs ++ request.messages)
      model
        .generate(conv, request.options)
        .map((c: org.llm4s.llmconnect.model.Completion) => ModelResponse(c, request.state))
    }

  private def testRequest: ModelRequest[IO] =
    ModelRequest(Some(SystemPrompt(Some("base"), Nil)), Nil, Nil, CompletionOptions(), HarnessState.empty)

object AgentMiddlewareLaws:
  // ── Case classes (bound generated inputs per law) ──────────────────────────
  final case class L0Case(conversation: List[Message], behavior: ToolBehavior, maxSteps: Int)
  final case class L1Case(stack: MiddlewareStack[IO], pos: Position, conversation: List[Message], maxSteps: Int)
  final case class L2Case(
    a: MiddlewareStack[IO],
    b: MiddlewareStack[IO],
    c: MiddlewareStack[IO],
    conversation: List[Message],
    maxSteps: Int
  )
  final case class L3Case(m1: PromptRewriteMiddleware, m2: PromptRewriteMiddleware)
  final case class L3SingleCase(m: PromptRewriteMiddleware)
  final case class L4Case(m: AgentMiddleware[IO], conversation: List[Message], maxSteps: Int)
  final case class L5Case(m: GenPureMiddleware, externalCell: StateCell[Int], externalValue: Int)
  final case class L6Case(pair: (GenPureMiddleware, GenPureMiddleware), conversation: List[Message], maxSteps: Int)
  final case class L7Case(tcs: List[TypedCell[?]], state: HarnessState)
  final case class L8Case(
    tcs: List[TypedCell[?]],
    state: HarnessState,
    unknownFields: Map[String, JsonValue],
    newTcs: List[TypedCell[?]]
  )
  final case class L9Case[A](tc: TypedCell[A], parentValue: A, children: List[HarnessState])
  final case class L10Case(parent: HarnessState, tcs: List[TypedCell[?]])

  // ── Generators for each law ────────────────────────────────────────────────
  val l0Case: Gen[L0Case] =
    for
      conv <- genConversation
      beh  <- genToolBehavior
      ms   <- genMaxSteps
    yield L0Case(conv, beh, ms)

  val l1Case: Gen[L1Case] =
    for
      stack <- genStack
      pos   <- genPosition
      conv  <- genConversation
      ms    <- genMaxSteps
    yield L1Case(stack, pos, conv, ms)

  val l2Case: Gen[L2Case] =
    for
      a    <- genStack
      b    <- genStack
      c    <- genStack
      conv <- genConversation
      ms   <- genMaxSteps
    yield L2Case(a, b, c, conv, ms)

  val l3Case: Gen[L3Case] =
    for
      m1 <- genPromptRewriteMiddleware
      m2 <- genPromptRewriteMiddleware
    yield L3Case(m1, m2)

  val l3SingleCase: Gen[L3SingleCase] =
    genPromptRewriteMiddleware.map(L3SingleCase.apply)

  val l4Case: Gen[L4Case] =
    for
      m    <- genAllDefaultsMiddleware
      conv <- genConversation
      ms   <- genMaxSteps
    yield L4Case(m, conv, ms)

  val l5Case: Gen[L5Case] =
    for
      m       <- genPureMiddleware
      extCell <- genExternalCell
      extVal  <- genInt
    yield L5Case(m, extCell, extVal)

  val l6Case: Gen[L6Case] =
    for
      pair <- genDisjointMiddlewarePair
      conv <- genConversation
      ms   <- genMaxSteps
    yield L6Case(pair, conv, ms)

  val l7Case: Gen[L7Case] =
    genStateWithMutations.map { case (tcs, state) => L7Case(tcs, state) }

  val l8Case: Gen[L8Case] =
    for
      (tcs, state) <- genStateWithMutations
      unknowns     <- genUnknownFields
      existingOwners = tcs.map(_.cell.id).toSet
      newTcs <- genNewTypedCells(existingOwners)
    yield L8Case(tcs, state, unknowns, newTcs)

  val l9Case: Gen[L9Case[?]] =
    genTypedCell.flatMap(_.genL9Case)

  val l10Case: Gen[L10Case] =
    genL10MixedCells.flatMap { case (parent, tcs) =>
      Gen.constant(L10Case(parent, tcs))
    }
