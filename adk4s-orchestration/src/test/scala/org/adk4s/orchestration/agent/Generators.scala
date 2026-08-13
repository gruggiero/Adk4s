package org.adk4s.orchestration.agent

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.Ref
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import hedgehog.Gen
import hedgehog.Range
import org.adk4s.core.component.{ AdkToolInfo, ChatModel, ChatModelConfig, InvokableTool, Tool }
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.json.JsonValue
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.harness.{
  AgentMiddleware,
  CellVisibility,
  HarnessState,
  MiddlewareName,
  ModelStep,
  PromptSection,
  StateCell,
  ToolCallCtx,
  ToolCallOut,
  ToolStep
}
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  StreamedChunk,
  SystemMessage,
  ToolCall,
  UserMessage
}
import smithy4s.Document
import java.util.UUID
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

/**
 * Hedgehog generators and test doubles for the harness-agent spec. All
 * generators are constructive (explicit `Gen` + `Range`), no `Arbitrary`.
 *
 * spec: harness-agent — Generators
 */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object Generators:

  // ── Content generators ──────────────────────────────────────────────────

  val genContent: Gen[String] =
    Gen.string(Gen.alphaNum, hedgehog.Range.linear(1, 20))

  val genToolName: Gen[String] =
    Gen.string(Gen.alphaNum, hedgehog.Range.linear(3, 12))

  // ── Conversation generator ──────────────────────────────────────────────

  val genConversation: Gen[List[Message]] =
    for
      n    <- Gen.int(hedgehog.Range.linear(1, 6))
      msgs <- Gen.list(genContent.map(UserMessage.apply), hedgehog.Range.singleton(n))
    yield msgs

  // ── Max steps generator ─────────────────────────────────────────────────

  val genMaxSteps: Gen[Int] =
    Gen.int(hedgehog.Range.linear(1, 5))

  // ── Tool behavior ADT ───────────────────────────────────────────────────

  sealed trait ToolBehavior extends Product with Serializable
  object ToolBehavior:
    case object NoTool           extends ToolBehavior
    case object SingleTool       extends ToolBehavior
    case object MultiTool        extends ToolBehavior
    case object InterruptOnFirst extends ToolBehavior

  val genToolBehavior: Gen[ToolBehavior] =
    Gen.element1(
      ToolBehavior.NoTool,
      ToolBehavior.SingleTool,
      ToolBehavior.MultiTool,
      ToolBehavior.InterruptOnFirst
    )

  // ── L0 case generator (bucket-weighted for cover-label stability) ───────
  //
  // The spec's cover labels (no-tool ≥20%, single-tool ≥20%, multi-tool
  // ≥20%, interrupt ≥20%, maxSteps-exhausted ≥10%) are near-tight for a
  // uniform sampling of (behavior × maxSteps) — e.g. natural exhaustion
  // hits only ~10% of the time exactly at the threshold. Bucket-weighted
  // sampling keeps every label comfortably above its threshold so the
  // gatekeeper is not flaky.

  sealed trait L0Bucket extends Product with Serializable
  object L0Bucket:
    case object NoTool       extends L0Bucket // idea: answer without tools; needs 1 iteration
    case object SingleToolOk extends L0Bucket // single tool call, then terminates; needs 2 iterations
    case object MultiToolOk  extends L0Bucket // two tool calls, then terminates; needs 2 iterations
    case object Interrupt    extends L0Bucket // interrupt on the only tool call, iteration 1
    case object Exhausted    extends L0Bucket // tool work present but maxSteps=1 → budget exhausted

  final case class L0Case(
    conversation: List[Message],
    behavior: ToolBehavior,
    maxSteps: Int
  ):
    /**
     * Iterations the behavior needs to complete (NoTool=1, Single/Multi=2;
     * InterruptOnFirst interrupts during iteration 1, so it never
     * exhausts the budget).
     */
    def requiredIterations: Int =
      behavior match
        case ToolBehavior.NoTool           => 1
        case ToolBehavior.SingleTool       => 2
        case ToolBehavior.MultiTool        => 2
        case ToolBehavior.InterruptOnFirst => 1

    def isExhausted: Boolean =
      maxSteps < requiredIterations

  val genL0Case: Gen[L0Case] =
    for
      bucket <- Gen.frequency1(
        22 -> Gen.constant(L0Bucket.NoTool),
        22 -> Gen.constant(L0Bucket.SingleToolOk),
        22 -> Gen.constant(L0Bucket.MultiToolOk),
        22 -> Gen.constant(L0Bucket.Interrupt),
        12 -> Gen.constant(L0Bucket.Exhausted)
      )
      conv <- genConversation
      caze <- bucket match
        case L0Bucket.NoTool =>
          genMaxSteps.map((ms: Int) => L0Case(conv, ToolBehavior.NoTool, ms))
        case L0Bucket.SingleToolOk =>
          Gen.int(hedgehog.Range.linear(2, 5)).map((ms: Int) => L0Case(conv, ToolBehavior.SingleTool, ms))
        case L0Bucket.MultiToolOk =>
          Gen.int(hedgehog.Range.linear(2, 5)).map((ms: Int) => L0Case(conv, ToolBehavior.MultiTool, ms))
        case L0Bucket.Interrupt =>
          genMaxSteps.map((ms: Int) => L0Case(conv, ToolBehavior.InterruptOnFirst, ms))
        case L0Bucket.Exhausted =>
          Gen
            .element1(ToolBehavior.SingleTool, ToolBehavior.MultiTool)
            .map((b: ToolBehavior) => L0Case(conv, b, 1))
    yield caze

  // ── Interrupt signal generator (all three variants) ─────────────────────

  val genJsonValue: Gen[JsonValue] =
    genContent.map((s: String) => Document.DString(s): JsonValue)

  val genInterruptSignal: Gen[InterruptSignal] =
    Gen.frequency1(
      32 -> genContent.map((s: String) => InterruptSignal.simple(s): InterruptSignal),
      36 ->
        (for
          info  <- genContent
          state <- genJsonValue
        yield InterruptSignal.stateful(info, state): InterruptSignal),
      32 ->
        (for
          info  <- genContent
          state <- genJsonValue
          children <- Gen.list(
            genContent.map((s: String) => InterruptSignal.simple(s): InterruptSignal),
            hedgehog.Range.linear(1, 2)
          )
        yield InterruptSignal.composite(info, state, children): InterruptSignal)
    )

  // ── Interrupt scenario case ─────────────────────────────────────────────

  final case class InterruptCase(stackSize: Int, signal: InterruptSignal)

  val genInterruptCase: Gen[InterruptCase] =
    for
      stackSize <- Gen.frequency1(
        36 -> Gen.constant(1),
        32 -> Gen.constant(2),
        32 -> Gen.constant(3)
      )
      signal <- genInterruptSignal
    yield InterruptCase(stackSize, signal)

  // ── Prompt-folding case ─────────────────────────────────────────────────
  //
  // `toolVal == beforeVal` is forced 40% of the time so the
  // `prompt-unchanged` cover label (≥30%) is hit deterministically — for
  // independently generated 1–20-char alphanumerics, equality is
  // astronomically rare.

  final case class PromptCase(beforeVal: String, toolVal: String)

  val genPromptCase: Gen[PromptCase] =
    for
      before <- genContent
      tool <- Gen.frequency1(
        4 -> Gen.constant(before),
        6 -> genContent
      )
    yield PromptCase(before, tool)

  // ── Parallel tool-write case ────────────────────────────────────────────

  val genSetElement: Gen[String] =
    Gen.string(Gen.alpha, hedgehog.Range.linear(1, 5))

  val genParallelToolWrites: Gen[List[Set[String]]] =
    for
      n <- Gen.frequency1(
        30 -> Gen.constant(1),
        35 -> Gen.constant(2),
        35 -> Gen.constant(3)
      )
      writes <- Gen.list(
        Gen.list(genSetElement, hedgehog.Range.linear(0, 4)).map(items => Set.from(items)),
        hedgehog.Range.singleton(n)
      )
    yield writes

  // ── Deterministic ChatModel double with request recording ───────────────

  /** What the model double observed at the base model step, per request. */
  final case class RecordedRequest(
    renderedSystemPrompt: Option[String],
    messages: List[Message],
    toolNames: List[String]
  )

  /**
   * A deterministic ChatModel double that returns Completions from a
   * script in order, and records every request it receives (system prompt
   * render, message list, tool names from `CompletionOptions`) for
   * per-request verification.
   */
  class ScriptedChatModel(
    script: List[Completion],
    val capturedRequests: Ref[IO, List[RecordedRequest]]
  ) extends ChatModel[IO]:
    private val counter: AtomicInteger = new AtomicInteger(0)

    private def completionFor(idx: Int): Completion =
      if idx < script.length then script(idx)
      else
        Completion(
          id = UUID.randomUUID().toString,
          created = 0L,
          content = "fallback",
          model = "test-model",
          message = AssistantMessage(Some("fallback"))
        )

    override def generate(conversation: Conversation, options: CompletionOptions): IO[Completion] =
      val record: RecordedRequest = RecordedRequest(
        renderedSystemPrompt = conversation.messages.collectFirst { case m: SystemMessage => m.content },
        messages = conversation.messages.toList,
        toolNames = options.tools.map((tf: org.llm4s.toolapi.ToolFunction[?, ?]) => tf.name).toList
      )
      capturedRequests.update((acc: List[RecordedRequest]) => acc :+ record) *> IO.delay(
        completionFor(counter.getAndIncrement())
      )

    def generate(conversation: Conversation): IO[Completion] =
      generate(conversation, CompletionOptions())

    def stream(conversation: Conversation): fs2.Stream[IO, StreamedChunk] =
      fs2.Stream.empty

    def streamContent(conversation: Conversation): fs2.Stream[IO, String] =
      fs2.Stream.empty

    def withConfig(config: ChatModelConfig): ChatModel[IO] = this

  object ScriptedChatModel:
    def apply(script: List[Completion]): IO[ScriptedChatModel] =
      Ref
        .of[IO, List[RecordedRequest]](Nil)
        .map((ref: Ref[IO, List[RecordedRequest]]) => new ScriptedChatModel(script, ref))

  // ── Completion builders ─────────────────────────────────────────────────

  def makeCompletion(content: String): Completion =
    Completion(
      id = UUID.randomUUID().toString,
      created = 0L,
      content = content,
      model = "test-model",
      message = AssistantMessage(Some(content))
    )

  def makeToolCallCompletion(toolName: String, args: String): Completion =
    val callId: String = UUID.randomUUID().toString
    val tc: ToolCall   = ToolCall(id = callId, name = toolName, arguments = ujson.read(args))
    Completion(
      id = UUID.randomUUID().toString,
      created = 0L,
      content = "",
      model = "test-model",
      message = AssistantMessage(None, Seq(tc))
    )

  def makeMultiToolCallCompletion(toolNames: List[String]): Completion =
    val calls: Seq[ToolCall] = toolNames.map { (name: String) =>
      ToolCall(id = UUID.randomUUID().toString, name = name, arguments = ujson.Obj())
    }
    Completion(
      id = UUID.randomUUID().toString,
      created = 0L,
      content = "",
      model = "test-model",
      message = AssistantMessage(None, calls)
    )

  // ── Script generation from tool behavior ────────────────────────────────

  /** Generates a script (list of Completions) for a given tool behavior. */
  def genScript(behavior: ToolBehavior, maxSteps: Int): List[Completion] =
    behavior match
      case ToolBehavior.NoTool =>
        List(makeCompletion("done"))
      case ToolBehavior.SingleTool =>
        List(makeToolCallCompletion("echo", "{}"), makeCompletion("done after echo"))
      case ToolBehavior.MultiTool =>
        List(
          makeMultiToolCallCompletion(List("echo", "echo2")),
          makeCompletion("done after multi")
        )
      case ToolBehavior.InterruptOnFirst =>
        List(makeToolCallCompletion("interrupting", "{}"))

  /** Tools required by each behavior's script. */
  def toolsFor(behavior: ToolBehavior, signal: InterruptSignal): List[InvokableTool[IO]] =
    behavior match
      case ToolBehavior.NoTool           => List.empty
      case ToolBehavior.SingleTool       => List(echoTool)
      case ToolBehavior.MultiTool        => List(echoTool, echo2Tool)
      case ToolBehavior.InterruptOnFirst => List(interruptingTool(signal))

  // ── Test tools ──────────────────────────────────────────────────────────

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

  /**
   * An interrupting tool that raises only on its first invocation;
   * subsequent invocations succeed. Used by resume scenarios.
   */
  def onceInterruptingTool(signal: InterruptSignal, fired: AtomicReference[Boolean]): InvokableTool[IO] =
    new InvokableTool[IO]:
      def info: AdkToolInfo =
        AdkToolInfo("interrupting", "Raises interrupt once", ujson.Obj())
      def run(args: ujson.Value): IO[ujson.Value] =
        IO.delay(fired.get()).flatMap { (already: Boolean) =>
          if already then IO.pure(ujson.Str("approved"))
          else IO.delay(fired.set(true)) *> IO.raiseError(AgentInterruptedException(signal))
        }
      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

  // ── Counting middleware ─────────────────────────────────────────────────

  /** A middleware that counts beforeAgent/afterAgent invocations. */
  class CountingMiddleware(
    val mwName: String,
    val beforeCountRef: Ref[IO, Int],
    val afterCountRef: Ref[IO, Int]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName.refineEither(mwName).fold(err => throw err, identity)

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      beforeCountRef.update(_ + 1).as(state)

    override def afterAgent(state: HarnessState): IO[HarnessState] =
      afterCountRef.update(_ + 1).as(state)

  object CountingMiddleware:
    def apply(name: String): IO[CountingMiddleware] =
      for
        before <- Ref.of[IO, Int](0)
        after  <- Ref.of[IO, Int](0)
      yield new CountingMiddleware(name, before, after)

  // ── Order-recording middleware ──────────────────────────────────────────

  /**
   * Records every hook invocation into a shared trace ref, so tests can
   * assert the exact hook ordering. Entries are `"<mwName>.<hook>"`.
   */
  class OrderRecordingMiddleware(
    val mwName: String,
    traceRef: Ref[IO, List[String]]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName.refineEither(mwName).fold(err => throw err, identity)

    private def record(hook: String): IO[Unit] =
      traceRef.update((acc: List[String]) => acc :+ s"$mwName.$hook")

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      record("beforeAgent").as(state)

    override def afterAgent(state: HarnessState): IO[HarnessState] =
      record("afterAgent").as(state)

    override def wrapModelCall(next: ModelStep[IO]): ModelStep[IO] =
      Kleisli((req: org.adk4s.harness.ModelRequest[IO]) => record("wrapModelCall") *> next.run(req))

    override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
      Kleisli((ctx: ToolCallCtx) => record("wrapToolCall") *> next.run(ctx))

  object OrderRecordingMiddleware:
    def apply(name: String): IO[OrderRecordingMiddleware] =
      Ref
        .of[IO, List[String]](Nil)
        .map((ref: Ref[IO, List[String]]) => new OrderRecordingMiddleware(name, ref))

  // ── Short-circuiting middleware ─────────────────────────────────────────

  /**
   * `wrapToolCall` returns a synthetic output WITHOUT calling `next` —
   * the base tool step (and underlying tool) never runs. Used by the
   * adversarial scenario proving the loop emits `ToolCallRequested` before
   * delegating to the wrap.
   */
  class ShortCircuitMiddleware(synthetic: String) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("short-circuit")

    override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
      Kleisli { (ctx: ToolCallCtx) =>
        IO.pure(
          ToolCallOut(
            ToolOutput(ctx.input.name, synthetic, ctx.input.callId),
            ctx.state
          )
        )
      }

  // ── Stateful middleware (per-request prompt folding) ────────────────────

  /**
   * A middleware with a Private cell that `beforeAgent` sets to
   * `beforeVal`, and whose `wrapToolCall` sets it to `toolVal` after the
   * base tool runs — simulating a tool that updates the cell mid-run, so
   * the second request's folded prompt reflects the post-tool state.
   */
  class StatefulPromptMiddleware(
    val cell: StateCell[String],
    beforeVal: String,
    toolVal: String
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("stateful-prompt")

    override def stateCells: List[StateCell[?]] = List(cell)

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      IO.pure(state.set(cell)(beforeVal))

    override def promptSections(state: HarnessState): List[PromptSection] =
      List(PromptSection("note", state.get(cell)))

    override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
      Kleisli { (ctx: ToolCallCtx) =>
        next.run(ctx).map((out: ToolCallOut) => ToolCallOut(out.output, out.state.set(cell)(toolVal)))
      }

  object StatefulPromptMiddleware:
    def apply(beforeVal: String, toolVal: String): IO[StatefulPromptMiddleware] =
      IO.pure {
        val cell: StateCell[String] = StateCell[String](
          owner = MiddlewareName("stateful-prompt"),
          name = "note",
          initial = "",
          visibility = CellVisibility.Private
        )
        new StatefulPromptMiddleware(cell, beforeVal, toolVal)
      }

  // ── Late-tool middleware (per-request tool list) ────────────────────────

  /**
   * A middleware whose `tools` contribution is read from a mutable source
   * at request-build time, not fixed at construction. `beforeAgent` flips
   * the source on, so the FIRST request already reflects the
   * post-`beforeAgent` contribution — proving the tool list is derived per
   * request, not snapshotted at construction.
   */
  class LateToolMiddleware(
    source: AtomicReference[List[InvokableTool[IO]]],
    lateTools: List[InvokableTool[IO]]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("late-tool")

    override def tools: List[InvokableTool[IO]] =
      source.get()

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      IO.delay(source.set(lateTools)).as(state)

  object LateToolMiddleware:
    def apply(lateTools: List[InvokableTool[IO]]): IO[LateToolMiddleware] =
      IO.delay(new AtomicReference[List[InvokableTool[IO]]](Nil)).map {
        (ref: AtomicReference[List[InvokableTool[IO]]]) => new LateToolMiddleware(ref, lateTools)
      }

  /**
   * A middleware contributing a fixed set of tools at construction (for
   * the stack-tool-before-base-tool scenario).
   */
  class StaticToolMiddleware(stackTools: List[InvokableTool[IO]]) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("static-tool")

    override def tools: List[InvokableTool[IO]] = stackTools

  // ── Cell-writing middleware (parallel merge) ────────────────────────────

  /**
   * A middleware whose `wrapToolCall` writes a per-tool `Set[String]`
   * delta into a `Shared` cell AFTER the base tool runs (via
   * `ToolCallOut(out, newState)`). The cell's `merge` function (chosen at
   * declaration) controls how parallel writes are combined by the loop:
   * `_ union _` (semilattice — order-independent) or last-write-wins
   * (order-sensitive by design).
   */
  class CellWritingMiddleware(
    val cell: StateCell[Set[String]],
    writes: Map[String, Set[String]]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("cell-writer")

    override def stateCells: List[StateCell[?]] = List(cell)

    override def wrapToolCall(next: ToolStep[IO]): ToolStep[IO] =
      Kleisli { (ctx: ToolCallCtx) =>
        next.run(ctx).map { (out: ToolCallOut) =>
          val delta: Set[String]  = writes.getOrElse(ctx.input.name, Set.empty)
          val merged: Set[String] = out.state.get(cell) ++ delta
          ToolCallOut(out.output, out.state.set(cell)(merged))
        }
      }

  // ── Shared-cell probe middleware (interrupt snapshot) ───────────────────

  /**
   * A middleware with a `Shared` cell set to `v1` by `beforeAgent`, so an
   * interrupt raised mid-iteration carries `v1` in the interrupted
   * outcome's state.
   */
  class SharedCellProbeMiddleware(
    val cell: StateCell[String],
    v1: String
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("shared-cell-probe")

    override def stateCells: List[StateCell[?]] = List(cell)

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      IO.pure(state.set(cell)(v1))

  object SharedCellProbeMiddleware:
    def apply(v1: String): SharedCellProbeMiddleware =
      val cell: StateCell[String] = StateCell[String](
        owner = MiddlewareName("shared-cell-probe"),
        name = "probe",
        initial = "",
        visibility = CellVisibility.Shared
      )
      new SharedCellProbeMiddleware(cell, v1)

  // ── Resume-probe middleware (afterAgent-once-on-resume) ─────────────────

  /**
   * A middleware that sets a cell to `setTo` in `beforeAgent` and records
   * every `state.get(cell)` value observed by `afterAgent` — used to prove
   * `afterAgent` runs exactly once across an interrupt/resume cycle, and on
   * the RESUMED run's (restored) state, not the interrupted one.
   */
  class ResumeProbeMiddleware(
    val cell: StateCell[String],
    setTo: String,
    val afterObserved: Ref[IO, List[String]]
  ) extends AgentMiddleware[IO]:
    val name: MiddlewareName = MiddlewareName("resume-probe")

    override def stateCells: List[StateCell[?]] = List(cell)

    override def beforeAgent(state: HarnessState): IO[HarnessState] =
      IO.pure(state.set(cell)(setTo))

    override def afterAgent(state: HarnessState): IO[HarnessState] =
      afterObserved.update((acc: List[String]) => acc :+ state.get(cell)).as(state)

  object ResumeProbeMiddleware:
    def apply(setTo: String): IO[ResumeProbeMiddleware] =
      Ref
        .of[IO, List[String]](Nil)
        .map { (ref: Ref[IO, List[String]]) =>
          val cell: StateCell[String] = StateCell[String](
            owner = MiddlewareName("resume-probe"),
            name = "note",
            initial = "",
            visibility = CellVisibility.Shared
          )
          new ResumeProbeMiddleware(cell, setTo, ref)
        }
