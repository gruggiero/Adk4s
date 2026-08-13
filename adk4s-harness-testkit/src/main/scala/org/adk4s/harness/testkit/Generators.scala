package org.adk4s.harness.testkit

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.functor.toFunctorOps
import hedgehog.Gen
import hedgehog.Range
import org.adk4s.core.component.{ AdkToolInfo, InvokableTool, Tool }
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.json.JsonValue
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.harness.{
  AgentMiddleware,
  CellVisibility,
  HarnessState,
  MiddlewareName,
  MiddlewareStack,
  ModelStep,
  PromptSection,
  StateCell,
  ToolCallCtx,
  ToolCallOut,
  ToolStep
}
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, UserMessage }
import smithy4s.Document

/**
 * Hedgehog generators and test doubles for the middleware-laws testkit.
 *
 * All generators are constructive (explicit `Gen` + `Range`), no `Arbitrary`,
 * per the spec's Compile-Negative obligations. Generator strategies are
 * derived from the spec's Properties section, NOT from the implementation.
 *
 * spec: middleware-laws — Properties (Ring 3) generator strategies
 */
object Generators:

  // ── Primitive generators ──────────────────────────────────────────────────

  val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(1, 20))

  val genToolName: Gen[String] =
    Gen.string(Gen.alphaNum, Range.linear(3, 12))

  val genInt: Gen[Int] =
    Gen.int(Range.linear(-1000, 1000))

  val genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(0, 20))

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  val genOwner: Gen[MiddlewareName] =
    Gen.string(Gen.alpha, Range.linear(1, 6)).map(s => MiddlewareName.refineEither(s).fold(err => throw err, identity))

  val genCellName: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 5))

  // ── Conversation / steps / tool behavior ──────────────────────────────────

  /**
   * Constructive: a non-empty list of `UserMessage` with generated text,
   * length `Range.linear 1 6`.
   */
  val genConversation: Gen[List[Message]] =
    for
      n    <- Gen.int(Range.linear(1, 6))
      msgs <- Gen.list(genContent.map(UserMessage.apply), Range.singleton(n))
    yield msgs

  /** Constructive: `Range.linear 1 20` per the L0 strategy. */
  val genMaxSteps: Gen[Int] =
    Gen.int(Range.linear(1, 20))

  /**
   * Tool behavior ADT — one of: fixed-output, raises a stateful interrupt,
   * raises a generic error, never terminates (always tool calls). Used to
   * script the deterministic double.
   */
  sealed trait ToolBehavior extends Product with Serializable
  object ToolBehavior:
    case object NoTool           extends ToolBehavior
    case object SingleTool       extends ToolBehavior
    case object MultiTool        extends ToolBehavior
    case object InterruptOnFirst extends ToolBehavior
    case object NeverTerminates  extends ToolBehavior

  val genToolBehavior: Gen[ToolBehavior] =
    Gen.element1(
      ToolBehavior.NoTool,
      ToolBehavior.SingleTool,
      ToolBehavior.MultiTool,
      ToolBehavior.InterruptOnFirst,
      ToolBehavior.NeverTerminates
    )

  // ── Insertion position (L1) ───────────────────────────────────────────────

  sealed trait Position extends Product with Serializable
  object Position:
    case object Head   extends Position
    case object Middle extends Position
    case object Tail   extends Position

  val genPosition: Gen[Position] =
    Gen.element1(Position.Head, Position.Middle, Position.Tail)

  /** Insert `mw` into `stack` at position `pos`. */
  def insert[F[_]: cats.Monad](
    stack: MiddlewareStack[F],
    mw: AgentMiddleware[F],
    pos: Position
  ): MiddlewareStack[F] =
    val ms: List[AgentMiddleware[F]] = stack.middlewares
    val updated: List[AgentMiddleware[F]] = pos match
      case Position.Head => mw :: ms
      case Position.Tail => ms :+ mw
      case Position.Middle =>
        val (before, after) = ms.splitAt(ms.length / 2)
        before ++ (mw :: after)
    MiddlewareStack.validated(updated).getOrElse(stack)

  // ── Test tools ─────────────────────────────────────────────────────────────

  def echoTool: InvokableTool[IO] = Tool.invokable[IO](
    "echo",
    "Echoes input",
    (args: ujson.Value) => Right(ujson.Str(s"echo: ${args.toString}"))
  )

  def echo2Tool: InvokableTool[IO] = Tool.invokable[IO](
    "echo2",
    "Echoes input twice",
    (args: ujson.Value) => Right(ujson.Str(s"echo2: ${args.toString}"))
  )

  def namedTool(name: String): InvokableTool[IO] = Tool.invokable[IO](
    name,
    s"Tool $name",
    (args: ujson.Value) => Right(ujson.Str("ok"))
  )

  def interruptingTool(signal: InterruptSignal): InvokableTool[IO] =
    new InvokableTool[IO]:
      def info: AdkToolInfo =
        AdkToolInfo("interrupting", "Raises interrupt", ujson.Obj())
      def run(args: ujson.Value): IO[ujson.Value] =
        IO.raiseError(AgentInterruptedException(signal))
      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

  /** Tools required by each behavior's script. */
  def toolsFor(behavior: ToolBehavior, signal: InterruptSignal): List[InvokableTool[IO]] =
    behavior match
      case ToolBehavior.NoTool           => List.empty
      case ToolBehavior.SingleTool       => List(echoTool)
      case ToolBehavior.MultiTool        => List(echoTool, echo2Tool)
      case ToolBehavior.InterruptOnFirst => List(interruptingTool(signal))
      case ToolBehavior.NeverTerminates  => List(echoTool)

  // ── Script generation from tool behavior ──────────────────────────────────

  /**
   * Generates a script (list of Completions) for a given tool behavior, using
   * deterministic ids derived from `seed` (no UUID / wall-clock).
   */
  def genScript(seed: Long, behavior: ToolBehavior): List[org.llm4s.llmconnect.model.Completion] =
    import org.llm4s.llmconnect.model.Completion
    behavior match
      case ToolBehavior.NoTool =>
        List(DeterministicChatModel.textCompletion(seed, 0, "done"))
      case ToolBehavior.SingleTool =>
        List(
          DeterministicChatModel.toolCallCompletion(seed, 0, "echo", "{}"),
          DeterministicChatModel.textCompletion(seed, 1, "done after echo")
        )
      case ToolBehavior.MultiTool =>
        List(
          DeterministicChatModel.multiToolCallCompletion(seed, 0, List("echo", "echo2")),
          DeterministicChatModel.textCompletion(seed, 1, "done after multi")
        )
      case ToolBehavior.InterruptOnFirst =>
        List(DeterministicChatModel.toolCallCompletion(seed, 0, "interrupting", "{}"))
      case ToolBehavior.NeverTerminates =>
        // Repeating tool-call completions — the loop will exhaust maxSteps
        // because the model never returns a final text response.
        (0 to 20).toList.map(i => DeterministicChatModel.toolCallCompletion(seed, i, "echo", "{}"))

  // ── Generated middleware: pure cell-touching hooks ────────────────────────

  /**
   * A generated middleware with 0–2 `Private` Int cells, 0–1 tools, 0–1
   * prompt sections, and pure `beforeAgent`/`afterAgent` hooks that touch
   * ONLY declared cells (increment each declared Int cell in `beforeAgent`).
   * `wrapModelCall` / `wrapToolCall` are the identity (no request rewriting)
   * — so the middleware is a pure state-transition contributor.
   *
   * Used by `genMiddleware` / `genStack` (L1, L2) and `genPureMiddleware`
   * (L5).
   */
  final class GenPureMiddleware(
    val mwName: MiddlewareName,
    val cells: List[StateCell[Int]],
    val mwTools: List[InvokableTool[IO]],
    val sections: List[PromptSection]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = mwName

    override def stateCells: List[StateCell[?]] = cells

    override def tools: List[InvokableTool[IO]] = mwTools

    override def promptSections(state: HarnessState): List[PromptSection] = sections

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      IO.pure(cells.foldLeft(state)((s, c) => s.update(c)(_ + 1)))

    override def afterAgent(state: HarnessState): IO[HarnessState] =
      IO.pure(state)

  /**
   * Constructive: generates a middleware with 0–2 cells, 0–1 tools, 0–1
   * prompt sections, pure hooks. Cell ids are unique by generated owner.
   */
  val genMiddleware: Gen[GenPureMiddleware] =
    for
      owner  <- genOwner
      nCells <- Gen.int(Range.linear(0, 2))
      cellNs <- Gen.list(genCellName, Range.singleton(nCells))
      nTools <- Gen.int(Range.linear(0, 1))
      toolNs <- Gen.list(genToolName, Range.singleton(nTools))
      nSecs  <- Gen.int(Range.linear(0, 1))
      secBs  <- Gen.list(genContent, Range.singleton(nSecs))
    yield
      val cells: List[StateCell[Int]]    = cellNs.map(n => StateCell[Int](owner, n, 0))
      val tools: List[InvokableTool[IO]] = toolNs.map(namedTool)
      val sections: List[PromptSection]  = secBs.map(b => PromptSection("s", b))
      new GenPureMiddleware(owner, cells, tools, sections)

  /**
   * Constructive: `Gen.list genMiddleware Range.linear 0 5`, then
   * `MiddlewareStack.validated`. Returns a (stack, validated?) pair so the
   * property can skip invalid stacks.
   */
  val genStack: Gen[MiddlewareStack[IO]] =
    for
      n  <- Gen.frequency1(20 -> Gen.constant(0), 30 -> Gen.constant(1), 30 -> Gen.constant(2), 20 -> Gen.constant(3))
      ms <- Gen.list(genMiddleware, Range.singleton(n))
    yield MiddlewareStack.validated(ms).getOrElse(MiddlewareStack.empty[IO])

  // ── L3: trace-recording middleware ─────────────────────────────────────────

  /**
   * A middleware that records its name into a shared trace ref when wrapping
   * the model call (outermost-first order observable). Optionally rewrites
   * the system prompt by prepending `prefix`.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  final class TraceMiddleware(
    val mwName: String,
    prefix: String,
    rewrites: Boolean,
    val traceRef: cats.effect.Ref[IO, List[String]]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName.refineEither(mwName).fold(err => throw err, identity)

    override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
      Kleisli { (req: org.adk4s.harness.ModelRequest[IO]) =>
        traceRef.update((acc: List[String]) => acc :+ mwName) *>
          (if rewrites then
             next.run(
               req.copy(systemPrompt = req.systemPrompt.map(sp => org.adk4s.harness.SystemPrompt(sp.base, sp.sections)))
             )
           else next.run(req))
      }

    override def wrapToolCall(next: org.adk4s.harness.ToolStep[IO]): org.adk4s.harness.ToolStep[IO] =
      Kleisli { (ctx: org.adk4s.harness.ToolCallCtx) =>
        traceRef.update((acc: List[String]) => acc :+ s"tool-$mwName") *> next.run(ctx)
      }

  object TraceMiddleware:
    def apply(name: String, prefix: String, rewrites: Boolean): IO[TraceMiddleware] =
      cats.effect.Ref
        .of[IO, List[String]](Nil)
        .map((ref: cats.effect.Ref[IO, List[String]]) => new TraceMiddleware(name, prefix, rewrites, ref))

  // ── L3: prompt-rewriting middleware ────────────────────────────────────────

  /**
   * A middleware that prepends `prefix` to the rendered system prompt in
   * `wrapModelCall` (m1 outermost → its prefix is applied last, so it ends up
   * leftmost in the final string). Used by L3 to make wrapping order
   * observable through the deterministic model's recorded system prompt.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  final class PromptRewriteMiddleware(val mwName: String, prefix: String, val rewrites: Boolean)
      extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName.refineEither(mwName).fold(err => throw err, identity)

    override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
      Kleisli { (req: org.adk4s.harness.ModelRequest[IO]) =>
        if rewrites then
          val newPrompt: org.adk4s.harness.SystemPrompt = req.systemPrompt match
            case Some(sp) =>
              org.adk4s.harness.SystemPrompt(sp.base.map(b => prefix + b).orElse(Some(prefix)), sp.sections)
            case None => org.adk4s.harness.SystemPrompt(Some(prefix), Nil)
          next.run(req.copy(systemPrompt = Some(newPrompt)))
        else next.run(req)
      }

  /**
   * Constructive: generates a `PromptRewriteMiddleware` with a short alpha
   * prefix.
   */
  val genPromptRewriteMiddleware: Gen[PromptRewriteMiddleware] =
    for
      p       <- Gen.string(Gen.alpha, Range.linear(1, 3))
      rewrite <- Gen.frequency1(65 -> Gen.constant(true), 35 -> Gen.constant(false))
    yield new PromptRewriteMiddleware(s"rw-$p", p, rewrite)

  // ── L4: all-defaults middleware ────────────────────────────────────────────

  /**
   * Constructive: generates a middleware with a random `name` but all other
   * members at defaults (`stateCells = Nil`, `tools = Nil`,
   * `promptSections = _ => Nil`, hooks return `state.pure`, wraps are
   * identity).
   */
  val genAllDefaultsMiddleware: Gen[AgentMiddleware[IO]] =
    genOwner.map((owner: MiddlewareName) =>
      new AgentMiddleware[IO]:
        val name: MiddlewareName = owner
    )

  // ── L5: pure middleware + external cell ────────────────────────────────────

  /**
   * Constructive: generates a middleware with 1–3 cells in
   * `Range.linear 1 3`, `beforeAgent`/`afterAgent` that read/write only
   * declared cells via pure state transitions.
   */
  val genPureMiddleware: Gen[GenPureMiddleware] =
    for
      owner  <- genOwner
      nCells <- Gen.frequency1(35 -> Gen.constant(1), 35 -> Gen.constant(2), 30 -> Gen.constant(3))
      cellNs <- Gen.list(genCellName, Range.singleton(nCells))
    yield
      val cells: List[StateCell[Int]] = cellNs.map(n => StateCell[Int](owner, n, 0))
      new GenPureMiddleware(owner, cells, Nil, Nil)

  /**
   * Constructive: a `StateCell[Int]` with a different owner than a generated
   * middleware, not in `m.stateCells`.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  val genExternalCell: Gen[StateCell[Int]] =
    for
      owner <- genOwner.map(n => MiddlewareName.refineEither(s"ext-${n.value}").fold(err => throw err, identity))
      name  <- genCellName
    yield StateCell[Int](owner, name, 0)

  // ── L6: disjoint middleware pair ───────────────────────────────────────────

  /**
   * Constructive: generates two middlewares with guaranteed-disjoint cell ids
   * (distinct owners), disjoint tool names, disjoint section names, pure
   * `beforeAgent`/`afterAgent`, and no `wrapModelCall` override.
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  val genDisjointMiddlewarePair: Gen[(GenPureMiddleware, GenPureMiddleware)] =
    for
      owner1 <- genOwner
      owner2 <- genOwner.map(n => MiddlewareName.refineEither(s"other-${n.value}").fold(err => throw err, identity))
      n1     <- Gen.frequency1(70 -> Gen.int(Range.linear(1, 2)), 30 -> Gen.constant(0))
      n2     <- Gen.frequency1(70 -> Gen.int(Range.linear(1, 2)), 30 -> Gen.constant(0))
      cs1    <- Gen.list(genCellName, Range.singleton(n1))
      cs2    <- Gen.list(genCellName, Range.singleton(n2))
      t1     <- genToolName
      t2     <- genToolName.map(s => s"other-$s")
    yield
      val m1: GenPureMiddleware = new GenPureMiddleware(
        owner1,
        cs1.map(n => StateCell[Int](owner1, n, 0)),
        List(namedTool(t1)),
        Nil
      )
      val m2: GenPureMiddleware = new GenPureMiddleware(
        owner2,
        cs2.map(n => StateCell[Int](owner2, n, 0)),
        List(namedTool(t2)),
        Nil
      )
      (m1, m2)

  // ── L7: typed cell ADT ──────────────────────────────────────────────────────

  val genListInt: Gen[List[Int]] =
    Gen.int(Range.linear(-100, 100)).list(Range.linear(0, 10))

  val genSetInt: Gen[Set[Int]] =
    Gen.int(Range.linear(-50, 50)).list(Range.linear(0, 8)).map(_.toSet)

  /**
   * A typed cell wrapper that encapsulates type-specific operations (get,
   * set, equality, value generation) without exposing `Any`. Pattern-match
   * on the variant to access the underlying `StateCell[A]` and `Gen[A]`.
   *
   * spec: middleware-laws — L7–L10 typed cell comparison without Any
   */
  sealed trait TypedCell[A]:
    def cell: StateCell[A]
    def genValue: Gen[A]

    /** Get this cell's value from a state. */
    def get(s: HarnessState): A = s.get(cell)

    /** Set this cell's value in a state. */
    def set(s: HarnessState, v: A): HarnessState = s.set(cell)(v)

    /** Initialize a state with this cell at the given value. */
    def stateWith(v: A): HarnessState = HarnessState.initial(List(cell)).set(cell)(v)

    /** Compare this cell's value across two states. */
    def equalsIn(s1: HarnessState, s2: HarnessState): Boolean = s1.get(cell) == s2.get(cell)

    /** Check if this cell's value in the state equals its initial value. */
    def equalsInitial(s: HarnessState): Boolean = s.get(cell) == cell.initial

    /**
     * Generate a state with this cell set to a random value, starting from
     * the given base state.
     */
    def genSetIn(base: HarnessState): Gen[HarnessState] =
      genValue.map((v: A) => base.set(cell)(v))

    /** Generate a fresh state with only this cell set to a random value. */
    def genStateWithValue: Gen[HarnessState] =
      genValue.map((v: A) => stateWith(v))

    /**
     * Generate an `L9Case[?]` with a parent value and 1–4 children, each
     * having written an arbitrary value to this cell. The type parameter
     * `A` is captured internally — returns `Gen[L9Case[?]]` to avoid Gen
     * invariance issues.
     */
    def genL9Case: Gen[AgentMiddlewareLaws.L9Case[?]] =
      for
        parentValue    <- genValue
        n              <- Gen.frequency1(30 -> Gen.constant(1), 40 -> Gen.constant(2), 30 -> Gen.constant(4))
        childVals      <- genValue.list(Range.singleton(n))
        includeInitial <- Gen.boolean
        vals =
          if includeInitial && childVals.nonEmpty then childVals.updated(0, cell.initial)
          else childVals
      yield
        val children: List[HarnessState] = vals.map(v => stateWith(v))
        AgentMiddlewareLaws.L9Case(this, parentValue, children): AgentMiddlewareLaws.L9Case[?]

  object TypedCell:
    case class IntCell(cell: StateCell[Int]) extends TypedCell[Int]:
      def genValue: Gen[Int] = genInt
    case class StringCell(cell: StateCell[String]) extends TypedCell[String]:
      def genValue: Gen[String] = genString
    case class BoolCell(cell: StateCell[Boolean]) extends TypedCell[Boolean]:
      def genValue: Gen[Boolean] = Gen.boolean
    case class ListIntCell(cell: StateCell[List[Int]]) extends TypedCell[List[Int]]:
      def genValue: Gen[List[Int]] = genListInt

  /**
   * Constructive: generates a `TypedCell[?]` for `A` in
   * {Int, String, Boolean, List[Int]} with the built-in `ReadWriter` and a
   * unique cell name (generated, not fixed) to avoid id collisions when
   * multiple cells are generated for the same state.
   */
  val genTypedCell: Gen[TypedCell[?]] =
    for
      owner <- genOwner
      name  <- genCellName
      tc <- Gen.element1(
        TypedCell.IntCell(StateCell[Int](owner, name, 0)),
        TypedCell.StringCell(StateCell[String](owner, name, "")),
        TypedCell.BoolCell(StateCell[Boolean](owner, name, false)),
        TypedCell.ListIntCell(StateCell[List[Int]](owner, name, Nil))
      )
    yield tc

  /**
   * Recursively generate `n` typed cells and a state with each cell set to
   * a random value.
   */
  def genTypedCellsAndState(
    n: Int,
    acc: List[TypedCell[?]],
    state: HarnessState
  ): Gen[(List[TypedCell[?]], HarnessState)] =
    if n <= 0 then Gen.constant((acc.reverse, state))
    else
      genTypedCell.flatMap { (tc: TypedCell[?]) =>
        tc.genSetIn(state).flatMap((newState: HarnessState) => genTypedCellsAndState(n - 1, tc :: acc, newState))
      }

  /**
   * Constructive: generates a list of typed cells and a state with each cell
   * set to a random value.
   */
  val genStateWithMutations: Gen[(List[TypedCell[?]], HarnessState)] =
    for
      n      <- Gen.int(Range.linear(1, 4))
      result <- genTypedCellsAndState(n, Nil, HarnessState.empty)
    yield result

  // ── L8: unknown fields + new cells ─────────────────────────────────────────

  val genJsonValue: Gen[JsonValue] =
    Gen.frequency1(
      50 -> genInt.map((i: Int) => Document.DNumber(BigDecimal(i)): JsonValue),
      50 -> genString.map((s: String) => Document.DString(s): JsonValue)
    )

  /**
   * Constructive: a map of unknown field names to arbitrary `JsonValue`s,
   * `Range.linear 0 5` entries.
   */
  val genUnknownFields: Gen[Map[String, JsonValue]] =
    (for
      name <- Gen.string(Gen.alpha, Range.linear(1, 8))
      v    <- genJsonValue
    yield (name, v))
      .list(Range.linear(0, 5))
      .map(_.toMap)

  /**
   * Constructive: `Gen.list genTypedCell Range.linear 1 3` with owners not
   * in a given set. Returns typed cells so callers can compare values
   * without `Any`.
   */
  def genNewTypedCells(existingOwners: Set[StateCell.CellId]): Gen[List[TypedCell[?]]] =
    for
      n      <- Gen.int(Range.linear(1, 3))
      result <- genTypedCellsAndState(n, Nil, HarnessState.empty)
    yield result._1.filter(tc => !existingOwners.contains(tc.cell.id))

  // ── L9: private cell with value + children ─────────────────────────────────

  /**
   * Constructive: a `Private` `StateCell[Int]` with `initial` and a non-initial
   * value.
   */
  val genPrivateCellWithValue: Gen[(StateCell[Int], Int)] =
    for
      owner <- genOwner
      name  <- genCellName
      v     <- genInt
    yield (StateCell[Int](owner, name, 0, visibility = CellVisibility.Private), v)

  /**
   * Constructive: `Gen.list genChildState Range.linear 1 4` where each child
   * has written an arbitrary value to the given cell.
   */
  def genChildren(cell: StateCell[Int]): Gen[List[HarnessState]] =
    genInt
      .list(Range.linear(1, 4))
      .map((vals: List[Int]) => vals.map(v => HarnessState.initial(List(cell)).set(cell)(v)))

  // ── L10/L11: shared semilattice cells ──────────────────────────────────────

  /**
   * A shared cell with a semilattice merge function. Extends `TypedCell[A]`
   * with `merge: (A, A) => A`. The merge MUST be commutative, associative,
   * and idempotent (the CRDT discipline) so that `mergeBack` over N
   * concurrently-produced child states is order-independent.
   *
   * spec: middleware-laws — L11 Semilattice laws for parallel-shared cells
   */
  sealed trait SharedTypedCell[A] extends TypedCell[A]:
    def merge: (A, A) => A

    /**
     * Generate a `CommutativityCase[?]` with two random values. The type
     * parameter `A` is captured internally — the return type is
     * `Gen[CommutativityCase[?]]` so it can be called on a
     * `SharedTypedCell[?]` without existential issues (Gen is invariant).
     */
    def genCommutativityCase: Gen[SemilatticeLaws.CommutativityCase[?]] =
      for
        a      <- genValue
        equalB <- Gen.boolean
        b      <- if equalB then Gen.constant(a) else genValue
      yield SemilatticeLaws.CommutativityCase(this, a, b): SemilatticeLaws.CommutativityCase[?]

    /**
     * Generate an `AssociativityCase[?]` with three random values. Sometimes
     * all three are equal to exercise the `all-equal` cover label.
     */
    def genAssociativityCase: Gen[SemilatticeLaws.AssociativityCase[?]] =
      for
        a        <- genValue
        allEqual <- Gen.boolean
        b        <- if allEqual then Gen.constant(a) else genValue
        c        <- if allEqual then Gen.constant(a) else genValue
      yield SemilatticeLaws.AssociativityCase(this, a, b, c): SemilatticeLaws.AssociativityCase[?]

    /**
     * Generate an `IdempotenceCase[?]` with one random value. Sometimes the
     * value is the cell's initial to exercise the `empty` cover label.
     */
    def genIdempotenceCase: Gen[SemilatticeLaws.IdempotenceCase[?]] =
      for
        useInitial <- Gen.boolean
        a          <- if useInitial then Gen.constant(cell.initial) else genValue
      yield SemilatticeLaws.IdempotenceCase(this, a): SemilatticeLaws.IdempotenceCase[?]

    /**
     * Generate a `MergeBackCase` with a parent state, N child states, and a
     * permutation. Not parameterized — returns `Gen[MergeBackCase]` so it
     * can be called on `SharedTypedCell[?]` directly. Sometimes all children
     * write the same value to exercise the `all-equal` cover label.
     */
    def genMergeBackCase: Gen[SemilatticeLaws.MergeBackCase] =
      for
        parentVal <- genValue
        n         <- Gen.int(Range.linear(1, 6))
        allEqual  <- Gen.frequency1(30 -> Gen.constant(true), 70 -> Gen.constant(false))
        singleVal <- genValue
        childVals <-
          if allEqual then Gen.list(Gen.constant(singleVal), Range.singleton(n)) else genValue.list(Range.singleton(n))
        perm <- genPermutation(n)
      yield
        val parent: HarnessState         = stateWith(parentVal)
        val children: List[HarnessState] = childVals.map(v => stateWith(v))
        SemilatticeLaws.MergeBackCase(this, parent, children, perm)

  object SharedTypedCell:
    case class MaxCell(cell: StateCell[Int]) extends SharedTypedCell[Int]:
      def genValue: Gen[Int]       = genInt
      def merge: (Int, Int) => Int = (a, b) => math.max(a, b)
    case class MinCell(cell: StateCell[Int]) extends SharedTypedCell[Int]:
      def genValue: Gen[Int]       = genInt
      def merge: (Int, Int) => Int = (a, b) => math.min(a, b)
    case class UnionCell(cell: StateCell[Set[Int]]) extends SharedTypedCell[Set[Int]]:
      def genValue: Gen[Set[Int]]                 = genSetInt
      def merge: (Set[Int], Set[Int]) => Set[Int] = (a, b) => a.union(b)

  /**
   * Constructive: a `SharedTypedCell[?]` — one of `MaxCell`, `MinCell`,
   * `UnionCell` — with a semilattice merge.
   */
  val genSharedTypedCell: Gen[SharedTypedCell[?]] =
    Gen.frequency1(
      40 -> genOwner.map(o =>
        SharedTypedCell.MaxCell(
          StateCell[Int](o, "max", 0, visibility = CellVisibility.Shared, merge = (a: Int, b: Int) => math.max(a, b))
        )
      ),
      20 -> genOwner.map(o =>
        SharedTypedCell.MinCell(
          StateCell[Int](o, "min", 0, visibility = CellVisibility.Shared, merge = (a: Int, b: Int) => math.min(a, b))
        )
      ),
      40 -> genOwner.map(o =>
        SharedTypedCell.UnionCell(
          StateCell[Set[Int]](
            o,
            "set",
            Set.empty,
            visibility = CellVisibility.Shared,
            merge = (a: Set[Int], b: Set[Int]) => a.union(b)
          )
        )
      )
    )

  /**
   * Constructive: a permutation of `n` indices, generated via random sort
   * keys (deterministic given the generated keys — no `Gen.shuffle`, which
   * is not in hedgehog 0.13.1).
   */
  def genPermutation(n: Int): Gen[List[Int]] =
    if n <= 0 then Gen.constant(Nil)
    else
      Gen
        .double(Range.linearFrac(0.0, 1.0))
        .list(Range.singleton(n))
        .map((keys: List[Double]) => keys.zipWithIndex.sortBy(_._1).map(_._2))

  // ── L10: mixed Shared+Private cell lists ───────────────────────────────────

  /**
   * Constructive: generates a parent state with 0–2 Private `TypedCell`s
   * and optionally 1 `SharedTypedCell`, all set to random values. Returns
   * the state and the full typed cell list (private + shared). Used by L10
   * to exercise mixed-cell merge-back neutrality.
   *
   * spec: middleware-laws — L10 cover labels: private-only, shared-union, shared-max
   */
  val genL10MixedCells: Gen[(HarnessState, List[TypedCell[?]])] =
    for
      nPrivate  <- Gen.int(Range.linear(0, 2))
      privates  <- genTypedCell.list(Range.singleton(nPrivate))
      hasShared <- Gen.frequency1(75 -> Gen.constant(true), 25 -> Gen.constant(false))
      sharedOpt <- if hasShared then genSharedTypedCell.map(Some(_)) else Gen.constant(None)
      allTcs: List[TypedCell[?]]   = privates ++ sharedOpt.toList
      allCells: List[StateCell[?]] = allTcs.map(_.cell)
      base: HarnessState           = HarnessState.initial(allCells)
      parent <- setAllTcs(base, allTcs)
    yield (parent, allTcs)

  /** Recursively set each `TypedCell[?]` to a random value in the state. */
  private def setAllTcs(state: HarnessState, tcs: List[TypedCell[?]]): Gen[HarnessState] =
    tcs match
      case Nil        => Gen.constant(state)
      case tc :: rest => tc.genSetIn(state).flatMap(newState => setAllTcs(newState, rest))
