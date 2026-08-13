package org.adk4s.orchestration.agent

import cats.effect.IO
import cats.effect.Ref
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.traverse.toTraverseOps
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.core.PropertyConfig
import hedgehog.core.SuccessCount
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.error.{ AgentInterruptedException, MaxStepsExceededError }
import org.adk4s.core.interrupt.{ AgentEvent, AgentEventEmitter, InterruptSignal }
import org.adk4s.harness.{ AgentMiddleware, CellVisibility, HarnessState, MiddlewareName, MiddlewareStack, StateCell }
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, Message, UserMessage }
import upickle.default.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Test oracle for the harness-agent spec — L0 gatekeeper + per-request +
 * interrupt + parallel-merge properties.
 *
 * The L0 gatekeeper is a DIFFERENTIAL property: `HarnessAgent` with
 * `MiddlewareStack.empty` is driven by the same deterministic model double as
 * the pre-refactor loop ([[LegacyReactAgent]]) and both must produce equal
 * outcomes, equal message lists, equal request traces, and equal event
 * sequences.
 *
 * Cover-label sizing: each property run executes a small BATCH of generated
 * cases and labels classify the batch (`exists`). Per-run label rates land
 * far above the spec minimums, so the coverage check is reliable at the
 * default 100 runs — the near-tight per-CASE rates (~12–36 % against 10–30 %
 * thresholds) would be statistically flaky at that run count.
 *
 * spec: harness-agent — Properties (Ring 3) + Proof Obligations
 */
class HarnessAgentSpec extends HedgehogSuite:

  import Generators.*

  // ── Helpers ─────────────────────────────────────────────────────────────

  private def stackOf(mws: List[AgentMiddleware[IO]]): MiddlewareStack[IO] =
    MiddlewareStack.validated[IO](mws).toOption match
      case Some(stack) => stack
      case None        => scala.sys.error("expected valid middleware stack")

  private def harnessOf(
    model: ScriptedChatModel,
    stack: MiddlewareStack[IO],
    baseTools: List[InvokableTool[IO]],
    maxSteps: Int,
    basePrompt: Option[String] = None,
    emitter: Option[AgentEvent => IO[Unit]] = None,
    parallelToolExecution: Boolean = true
  ): HarnessAgent[IO] =
    new HarnessAgent[IO](
      HarnessAgent.Config[IO](
        name = "test-harness",
        description = "test",
        model = model,
        stack = stack,
        baseTools = baseTools,
        basePrompt = basePrompt,
        maxSteps = maxSteps,
        emitter = emitter,
        parallelToolExecution = parallelToolExecution
      )
    )

  /** Runs `program`, closes the emitter queue, drains every buffered event. */
  private def collectEvents[A](emitter: AgentEventEmitter)(program: IO[A]): IO[(A, List[AgentEvent])] =
    program.flatMap { (a: A) =>
      emitter.complete *>
        emitter.subscribe.compile.toList.map((events: List[AgentEvent]) => (a, events))
    }

  /** Variant tag for ordering assertions. */
  private def tagOf(event: AgentEvent): String = event match
    case _: AgentEvent.ToolCallRequested  => "ToolCallRequested"
    case _: AgentEvent.ToolCallCompleted  => "ToolCallCompleted"
    case _: AgentEvent.IterationCompleted => "IterationCompleted"
    case _: AgentEvent.MessageOutput      => "MessageOutput"
    case _: AgentEvent.Interrupted        => "Interrupted"
    case _: AgentEvent.ErrorOccurred      => "ErrorOccurred"
    case _: AgentEvent.TokenDelta         => "TokenDelta"
    case _: AgentEvent.MemoryRecalled     => "MemoryRecalled"
    case _: AgentEvent.MemoryWritten      => "MemoryWritten"

  // ── L0 Gatekeeper: differential equivalence vs the pre-refactor loop ────

  /**
   * Runs one generated L0 case against both the empty-stack harness and the
   * pre-refactor loop and asserts observational equivalence:
   *   - equal request traces observed at the base model step
   *   - equal outcomes (Completed message + message list / same interrupt
   *     signal / both terminate by step-budget exhaustion)
   *   - equal loop-level event sequences (loop-level means `ToolsNode`
   *     emission disabled on the legacy side; the harness emits only at the
   *     loop level, per the event-emission requirement)
   */
  private def runDifferential(caze: L0Case, signal: InterruptSignal): IO[Result] =
    val script: List[Completion]       = genScript(caze.behavior, caze.maxSteps)
    val tools: List[InvokableTool[IO]] = toolsFor(caze.behavior, signal)
    for
      harnessModel   <- ScriptedChatModel(script)
      legacyModel    <- ScriptedChatModel(script)
      harnessEmitter <- AgentEventEmitter.create()
      legacyEmitter  <- AgentEventEmitter.create()
      harness = harnessOf(
        harnessModel,
        MiddlewareStack.empty[IO],
        tools,
        caze.maxSteps,
        emitter = Some((e: AgentEvent) => harnessEmitter.emit(e))
      )
      legacy = new LegacyReactAgent(
        "l0-legacy",
        "l0 differential target",
        legacyModel,
        tools,
        None,
        caze.maxSteps,
        Some(legacyEmitter),
        emitFromToolsNode = false
      )
      hResult <- harness.generate(caze.conversation, caze.maxSteps)
      lResult <- legacy.generate(caze.conversation, caze.maxSteps).attempt
      hEvents <- harnessEmitter.complete *> harnessEmitter.subscribe.compile.toList
      lEvents <- legacyEmitter.complete *> legacyEmitter.subscribe.compile.toList
      hTrace  <- harnessModel.capturedRequests.get
      lTrace  <- legacyModel.capturedRequests.get
    yield Result.all(
      List(
        Result.assert(hTrace == lTrace),
        outcomesMatch(hResult, lResult),
        eventsMatch(hEvents, lEvents, caze.behavior == ToolBehavior.InterruptOnFirst)
      )
    )

  private def outcomesMatch(
    harness: HarnessResult,
    legacy: Either[Throwable, (AssistantMessage, List[Message])]
  ): Result =
    (harness, legacy) match
      case (
            HarnessResult.Completed(assistant, messages, _),
            Right((legacyAssistant, legacyMessages))
          ) =>
        Result.all(
          List(
            Result.assert(assistant == legacyAssistant),
            Result.assert(messages == legacyMessages)
          )
        )
      case (HarnessResult.Interrupted(signal, _, _), Left(error)) =>
        error match
          case interrupt: AgentInterruptedException =>
            Result.assert(interrupt.signal == signal)
          case _ =>
            Result.assert(false)
      case (HarnessResult.Failed(error, _, _), Left(legacyError)) =>
        val isMaxSteps: Boolean = error match
          case _: MaxStepsExceededError => true
          case _                        => false
        Result.all(
          List(
            Result.assert(isMaxSteps),
            Result.assert(Option(legacyError.getMessage).exists(_.contains("max steps")))
          )
        )
      case _ =>
        Result.assert(false)

  /**
   * Loop-level event sequence equality. The legacy loop did not emit an
   * `Interrupted` event (the interrupt surfaced only via the raised error
   * and an `AgentRunner`-level event), while the harness loop does, so for
   * interrupt cases the harness's loop-level `Interrupted` is excluded from
   * the comparison.
   */
  private def eventsMatch(
    harnessEvents: List[AgentEvent],
    legacyEvents: List[AgentEvent],
    isInterruptCase: Boolean
  ): Result =
    val normalized: List[AgentEvent] =
      if isInterruptCase then
        harnessEvents.filter {
          case _: AgentEvent.Interrupted => false
          case _                         => true
        }
      else harnessEvents
    Result.assert(normalized == legacyEvents)

  property("L0 empty-stack-equivalence — differential vs LegacyReactAgent") {
    for
      cases <- Gen
        .list(genL0Case, Range.singleton(5))
        .forAll
        .cover(
          20,
          "no-tool",
          (cs: List[L0Case]) => cs.exists((c: L0Case) => !c.isExhausted && c.behavior == ToolBehavior.NoTool)
        )
        .cover(
          20,
          "single-tool",
          (cs: List[L0Case]) => cs.exists((c: L0Case) => !c.isExhausted && c.behavior == ToolBehavior.SingleTool)
        )
        .cover(
          20,
          "multi-tool",
          (cs: List[L0Case]) => cs.exists((c: L0Case) => !c.isExhausted && c.behavior == ToolBehavior.MultiTool)
        )
        .cover(
          20,
          "interrupt",
          (cs: List[L0Case]) => cs.exists((c: L0Case) => c.behavior == ToolBehavior.InterruptOnFirst)
        )
        .cover(10, "maxSteps-exhausted", (cs: List[L0Case]) => cs.exists((c: L0Case) => c.isExhausted))
      signal <- genInterruptSignal.forAll
    yield
      val combined: IO[Result] =
        cases.traverse((caze: L0Case) => runDifferential(caze, signal)).map(Result.all)
      combined.unsafeRunSync()
  }

  test("L0 — legacy ToolsNode double-emission quirk is documented and absent from the harness loop") {
    // The pre-refactor loop emitted tool events twice: once at the loop level
    // and once from inside ToolsNode. The harness emits each event exactly
    // once — the loop owns the observability channel.
    val script: List[Completion] =
      List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
    val program: IO[(List[AgentEvent], List[AgentEvent])] = for
      harnessModel   <- ScriptedChatModel(script)
      legacyModel    <- ScriptedChatModel(script)
      harnessEmitter <- AgentEventEmitter.create()
      legacyEmitter  <- AgentEventEmitter.create()
      harness = harnessOf(
        harnessModel,
        MiddlewareStack.empty[IO],
        List(echoTool),
        5,
        emitter = Some((e: AgentEvent) => harnessEmitter.emit(e))
      )
      legacy = new LegacyReactAgent(
        "l0-legacy",
        "quirk probe",
        legacyModel,
        List(echoTool),
        None,
        5,
        Some(legacyEmitter),
        emitFromToolsNode = true
      )
      _       <- harness.generate(List(UserMessage("go")), 5)
      hEvents <- harnessEmitter.complete *> harnessEmitter.subscribe.compile.toList
      _       <- legacy.generate(List(UserMessage("go")), 5)
      lEvents <- legacyEmitter.complete *> legacyEmitter.subscribe.compile.toList
    yield (hEvents, lEvents)

    val (hEvents, lEvents): (List[AgentEvent], List[AgentEvent]) = program.unsafeRunSync()
    assertEquals(hEvents.map(tagOf).count(_ == "ToolCallRequested"), 1)
    assert(lEvents.map(tagOf).count(_ == "ToolCallRequested") > 1 || lEvents.length > hEvents.length)
  }

  // ── Per-request prompt folding ──────────────────────────────────────────

  property(
    "per-request-prompt-folding — state-aware sections in prompt",
    withConfig = _.copy(testLimit = SuccessCount(500))
  ) {
    // spec: harness-agent — Property: per-request-prompt-folding
    for pc <- genPromptCase.forAll
        .cover(50, "prompt-changed", (c: PromptCase) => c.beforeVal != c.toolVal)
        .cover(30, "prompt-unchanged", (c: PromptCase) => c.beforeVal == c.toolVal)
    yield
      val script: List[Completion] =
        List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
      val program: IO[Result] = for
        model <- ScriptedChatModel(script)
        mw    <- StatefulPromptMiddleware(pc.beforeVal, pc.toolVal)
        harness = harnessOf(model, stackOf(List(mw)), List(echoTool), 5)
        _    <- harness.generate(List(UserMessage("go")), 5)
        reqs <- model.capturedRequests.get
      yield
        val prompt0: String = reqs.headOption.flatMap(_.renderedSystemPrompt).getOrElse("")
        val prompt1: String = reqs.lift(1).flatMap(_.renderedSystemPrompt).getOrElse("")
        Result.all(
          List(
            Result.assert(reqs.length == 2),
            Result.assert(prompt0.contains(pc.beforeVal)),
            Result.assert(prompt1.contains(pc.toolVal)),
            Result.assert(pc.beforeVal == pc.toolVal || prompt0 != prompt1)
          )
        )
      program.unsafeRunSync()
  }

  property("per-request-prompt-folding — empty stack yields only the base prompt") {
    // spec: harness-agent — Scenario: Empty stack yields only the base prompt
    for basePrompt <- genContent.forAll
    yield
      val script: List[Completion] = List(makeCompletion("done"))
      val program: IO[Result] = for
        model <- ScriptedChatModel(script)
        harness = harnessOf(model, MiddlewareStack.empty[IO], List.empty, 5, basePrompt = Some(basePrompt))
        _    <- harness.generate(List(UserMessage("go")), 5)
        reqs <- model.capturedRequests.get
      yield Result.all(
        List(
          Result.assert(reqs.length == 1),
          Result.assert(reqs.headOption.flatMap(_.renderedSystemPrompt) == Some(basePrompt))
        )
      )
      program.unsafeRunSync()
  }

  // ── Per-request tool list ───────────────────────────────────────────────

  property("per-request-tool-list — late tools appear in the first request after beforeAgent") {
    // spec: harness-agent — Property: per-request-tool-list
    for
      baseNames <- Gen.list(genToolName, Range.linear(0, 3)).forAll
      lateNames <- Gen.list(genToolName, Range.linear(0, 3)).forAll
    yield
      val uniqBase: List[String]   = baseNames.zipWithIndex.map { case (n: String, i: Int) => s"base-$i-$n" }
      val uniqLate: List[String]   = lateNames.zipWithIndex.map { case (n: String, i: Int) => s"late-$i-$n" }
      val script: List[Completion] = List(makeCompletion("done"))
      val program: IO[Result] = for
        model <- ScriptedChatModel(script)
        mw    <- LateToolMiddleware(uniqLate.map(namedTool))
        harness = harnessOf(model, stackOf(List(mw)), uniqBase.map(namedTool), 5)
        _    <- harness.generate(List(UserMessage("go")), 5)
        reqs <- model.capturedRequests.get
      yield
        val firstTools: List[String] = reqs.headOption.map(_.toolNames).getOrElse(Nil)
        Result.all(
          List(
            Result.assert(reqs.length == 1),
            // stack order (late tools) then base order — derived at request-build
            // time, AFTER beforeAgent flipped the late-tool source on
            Result.assert(firstTools == uniqLate ++ uniqBase),
            Result.assert(firstTools.length == uniqLate.length + uniqBase.length)
          )
        )
      program.unsafeRunSync()
  }

  test("per-request-tool-list — stack tool appears before base tool") {
    // spec: harness-agent — Scenario: Stack tool appears before base tool
    val script: List[Completion] = List(makeCompletion("done"))
    val program: IO[List[String]] = for
      model <- ScriptedChatModel(script)
      mw      = new StaticToolMiddleware(List(namedTool("t-stack")))
      harness = harnessOf(model, stackOf(List(mw)), List(namedTool("t-base")), 5)
      _    <- harness.generate(List(UserMessage("go")), 5)
      reqs <- model.capturedRequests.get
    yield reqs.headOption.map(_.toolNames).getOrElse(Nil)
    assertEquals(program.unsafeRunSync(), List("t-stack", "t-base"))
  }

  test("per-request-tool-list — empty stack yields only base tools") {
    // spec: harness-agent — Scenario: Empty stack yields only base tools
    val script: List[Completion] = List(makeCompletion("done"))
    val program: IO[List[String]] = for
      model <- ScriptedChatModel(script)
      harness = harnessOf(model, MiddlewareStack.empty[IO], List(namedTool("t-base")), 5)
      _    <- harness.generate(List(UserMessage("go")), 5)
      reqs <- model.capturedRequests.get
    yield reqs.headOption.map(_.toolNames).getOrElse(Nil)
    assertEquals(program.unsafeRunSync(), List("t-base"))
  }

  // ── Interrupt: afterAgent skipped, state snapshotted ────────────────────

  property("afterAgent-skipped-on-interrupt — zero afterAgent calls, signal round-trips") {
    // spec: harness-agent — Property: afterAgent-skipped-on-interrupt
    for batch <- Gen
        .list(genInterruptCase, Range.singleton(4))
        .forAll
        .cover(30, "stack-size-1", (bs: List[InterruptCase]) => bs.exists(_.stackSize == 1))
        .cover(20, "stack-size-3", (bs: List[InterruptCase]) => bs.exists(_.stackSize == 3))
        .cover(
          30,
          "stateful-signal",
          (bs: List[InterruptCase]) =>
            bs.exists((b: InterruptCase) =>
              b.signal match
                case _: InterruptSignal.Stateful => true
                case _                           => false
            )
        )
    yield
      val script: List[Completion] = List(makeToolCallCompletion("interrupting", "{}"))
      val runOne: InterruptCase => IO[Result] = (ic: InterruptCase) =>
        for
          model <- ScriptedChatModel(script)
          mws   <- (1 to ic.stackSize).toList.traverse((i: Int) => CountingMiddleware(s"M$i"))
          harness = harnessOf(model, stackOf(mws), List(interruptingTool(ic.signal)), 5)
          res         <- harness.generate(List(UserMessage("go")), 5)
          afterCounts <- mws.traverse((mw: CountingMiddleware) => mw.afterCountRef.get)
        yield res match
          case HarnessResult.Interrupted(sig, _, _) =>
            Result.all(
              List(
                Result.assert(sig == ic.signal),
                Result.assert(afterCounts.forall(_ == 0))
              )
            )
          case _ => Result.assert(false)
      batch.traverse(runOne).map(Result.all).unsafeRunSync()
  }

  test("interrupt mid-iteration snapshots partial Shared-cell state") {
    // spec: harness-agent — Scenario: Interrupt mid-iteration snapshots partial state
    val script: List[Completion] = List(makeToolCallCompletion("interrupting", "{}"))
    val signal: InterruptSignal  = InterruptSignal.simple("snap")
    val program: IO[Boolean] = for
      model <- ScriptedChatModel(script)
      mw      = SharedCellProbeMiddleware("v1-value")
      harness = harnessOf(model, stackOf(List(mw)), List(interruptingTool(signal)), 5)
      res <- harness.generate(List(UserMessage("go")), 5)
    yield res match
      case HarnessResult.Interrupted(_, _, state) =>
        val restored: Option[HarnessState] =
          HarnessState.restore(List(mw.cell: StateCell[?]), state.snapshot).toOption
        state.get(mw.cell) == "v1-value" &&
        restored.exists(_.get(mw.cell) == "v1-value")
      case _ => false
    assert(program.unsafeRunSync())
  }

  test("loop-order — beforeAgent runs exactly once in a multi-iteration run") {
    // spec: harness-agent — Scenario: beforeAgent runs exactly once
    val script: List[Completion] =
      List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
    val program: IO[Int] = for
      model <- ScriptedChatModel(script)
      mw    <- CountingMiddleware("M1")
      harness = harnessOf(model, stackOf(List(mw)), List(echoTool), 5)
      _           <- harness.generate(List(UserMessage("go")), 5)
      beforeCount <- mw.beforeCountRef.get
    yield beforeCount
    assertEquals(program.unsafeRunSync(), 1)
  }

  property("loop-order — afterAgent runs on step-budget exhaustion") {
    // spec: harness-agent — Scenario: maxSteps exhausted still runs afterAgent
    for maxSteps <- Gen.int(Range.linear(1, 2)).forAll
    yield
      val script: List[Completion] =
        List.fill(maxSteps + 1)(makeToolCallCompletion("echo", "{}"))
      val program: IO[Result] = for
        model <- ScriptedChatModel(script)
        mw    <- CountingMiddleware("M1")
        harness = harnessOf(model, stackOf(List(mw)), List(echoTool), maxSteps)
        res        <- harness.generate(List(UserMessage("loop")), maxSteps)
        afterCount <- mw.afterCountRef.get
      yield res match
        case HarnessResult.Failed(error, _, _) =>
          val isMaxSteps: Boolean = error match
            case _: MaxStepsExceededError => true
            case _                        => false
          Result.all(
            List(
              Result.assert(afterCount == 1),
              Result.assert(isMaxSteps)
            )
          )
        case _ => Result.assert(false)
      program.unsafeRunSync()
  }

  test("loop-order — full hook trace: beforeAgent in stack order, afterAgent in reverse, M1 outermost") {
    // spec: harness-agent — Requirement: Loop orchestration order
    val script: List[Completion] =
      List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
    val program: IO[List[String]] = for
      model    <- ScriptedChatModel(script)
      traceRef <- Ref.of[IO, List[String]](Nil)
      m1      = new OrderRecordingMiddleware("M1", traceRef)
      m2      = new OrderRecordingMiddleware("M2", traceRef)
      harness = harnessOf(model, stackOf(List(m1, m2)), List(echoTool), 5)
      _     <- harness.generate(List(UserMessage("go")), 5)
      trace <- traceRef.get
    yield trace

    assertEquals(
      program.unsafeRunSync(),
      List(
        "M1.beforeAgent",
        "M2.beforeAgent",
        "M1.wrapModelCall",
        "M2.wrapModelCall",
        "M1.wrapToolCall",
        "M2.wrapToolCall",
        "M1.wrapModelCall",
        "M2.wrapModelCall",
        "M2.afterAgent",
        "M1.afterAgent"
      )
    )
  }

  // ── Event emission ──────────────────────────────────────────────────────

  test("event-emission — exact loop-level event sequence for a one-tool run") {
    // spec: harness-agent — Scenario: Tool-call events emitted in order
    val script: List[Completion] =
      List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
    val program: IO[List[AgentEvent]] = for
      model   <- ScriptedChatModel(script)
      emitter <- AgentEventEmitter.create()
      harness = harnessOf(
        model,
        MiddlewareStack.empty[IO],
        List(echoTool),
        5,
        emitter = Some((e: AgentEvent) => emitter.emit(e))
      )
      (_, events) <- collectEvents(emitter)(harness.generate(List(UserMessage("go")), 5))
    yield events

    assertEquals(
      program.unsafeRunSync().map(tagOf),
      List(
        "ToolCallRequested",
        "ToolCallCompleted",
        "IterationCompleted",
        "MessageOutput",
        "IterationCompleted"
      )
    )
  }

  test("event-emission — short-circuiting middleware cannot suppress ToolCallRequested") {
    // spec: harness-agent — Scenario: Middleware cannot suppress a ToolCallRequested event
    val script: List[Completion] =
      List(makeToolCallCompletion("echo", "{}"), makeCompletion("done"))
    val program: IO[List[AgentEvent]] = for
      model   <- ScriptedChatModel(script)
      emitter <- AgentEventEmitter.create()
      harness = harnessOf(
        model,
        stackOf(List(new ShortCircuitMiddleware("synthetic-result"))),
        List(echoTool),
        5,
        emitter = Some((e: AgentEvent) => emitter.emit(e))
      )
      (_, events) <- collectEvents(emitter)(harness.generate(List(UserMessage("go")), 5))
    yield events

    val events: List[AgentEvent] = program.unsafeRunSync()
    assertEquals(
      events.map(tagOf),
      List(
        "ToolCallRequested",
        "ToolCallCompleted",
        "IterationCompleted",
        "MessageOutput",
        "IterationCompleted"
      )
    )
    // ToolCallCompleted reflects the middleware's synthetic output (the base
    // tool never ran), proving the wrap short-circuited while the loop still
    // owned the event sequence.
    events.collectFirst { case c: AgentEvent.ToolCallCompleted => c } match
      case Some(completed) => assertEquals(completed.result, "synthetic-result")
      case None            => fail("expected a ToolCallCompleted event")
  }

  // ── Parallel tool-call state merge ──────────────────────────────────────

  private def mergeCell(mergeFn: (Set[String], Set[String]) => Set[String]): StateCell[Set[String]] =
    StateCell[Set[String]](
      owner = MiddlewareName("parallel-merge"),
      name = "shared-set",
      initial = Set.empty[String],
      visibility = CellVisibility.Shared,
      merge = mergeFn
    )

  /**
   * Runs one parallel-merge case three times and asserts the merged `Shared`
   * cell equals the union of all writes and that repeated runs produce
   * identical state snapshots (order-independence for the semilattice
   * merge).
   */
  private def runMergeCase(writes: List[Set[String]], parallel: Boolean): IO[Result] =
    val toolNames: List[String] = writes.indices.map((i: Int) => s"T$i").toList
    val expected: Set[String] =
      writes.foldLeft(Set.empty[String])((acc: Set[String], w: Set[String]) => acc.union(w))
    val cell: StateCell[Set[String]] = mergeCell((a: Set[String], b: Set[String]) => a.union(b))
    val oneRun: IO[Option[HarnessState]] = for
      model <- ScriptedChatModel(List(makeMultiToolCallCompletion(toolNames), makeCompletion("done")))
      mw      = new CellWritingMiddleware(cell, toolNames.zip(writes).toMap)
      harness = harnessOf(model, stackOf(List(mw)), toolNames.map(namedTool), 5, parallelToolExecution = parallel)
      res <- harness.generate(List(UserMessage("go")), 5)
    yield res match
      case HarnessResult.Completed(_, _, state) => Some(state)
      case _                                    => None
    for
      s1 <- oneRun
      s2 <- oneRun
      s3 <- oneRun
    yield
      val snapshotsEqual: Boolean = (s1, s2, s3) match
        case (Some(a), Some(b), Some(c)) => a.snapshot == b.snapshot && b.snapshot == c.snapshot
        case _                           => false
      Result.all(
        List(
          Result.assert(s1.exists(_.get(cell) == expected)),
          Result.assert(s2.exists(_.get(cell) == expected)),
          Result.assert(snapshotsEqual)
        )
      )

  property("parallel-tool-merge-order-independence — union cell under TestControl") {
    // spec: harness-agent — Property: parallel-tool-merge-order-independence
    for batch <- Gen
        .list(genParallelToolWrites, Range.singleton(3))
        .forAll
        .cover(30, "2-tools", (bs: List[List[Set[String]]]) => bs.exists(_.length == 2))
        .cover(20, "3-tools", (bs: List[List[Set[String]]]) => bs.exists(_.length == 3))
    yield
      val cases: IO[List[Result]] = batch.traverse((writes: List[Set[String]]) => runMergeCase(writes, parallel = true))
      TestControl.executeEmbed(cases.map(Result.all)).unsafeRunSync()
  }

  test("parallel-tool-merge — non-semilattice (last-write-wins) merge is fold-order deterministic") {
    // spec: harness-agent — Scenario: Non-semilattice merge is order-sensitive (honest edge)
    // parTraverse preserves call-list order in the results regardless of
    // completion order, so a non-semilattice merge deterministically reflects
    // the LAST call's write — documented order-sensitivity by design, not a
    // bug; the semilattice discipline is the middleware author's obligation.
    val cell: StateCell[Set[String]] = mergeCell((_: Set[String], last: Set[String]) => last)
    val script: List[Completion] =
      List(makeMultiToolCallCompletion(List("T0", "T1")), makeCompletion("done"))
    val program: IO[Set[String]] = for
      model <- ScriptedChatModel(script)
      mw      = new CellWritingMiddleware(cell, Map("T0" -> Set("a"), "T1" -> Set("b")))
      harness = harnessOf(model, stackOf(List(mw)), List(namedTool("T0"), namedTool("T1")), 5)
      res <- harness.generate(List(UserMessage("go")), 5)
    yield res match
      case HarnessResult.Completed(_, _, state) => state.get(cell)
      case _                                    => Set.empty
    assertEquals(program.unsafeRunSync(), Set("b"))
  }

  test("parallel-tool-merge — sequential execution (parallelism = 1) is the degenerate deterministic case") {
    // spec: harness-agent — Scenario: Sequential execution (parallelism = 1)
    // is trivially deterministic
    val cell: StateCell[Set[String]] = mergeCell((a: Set[String], b: Set[String]) => a.union(b))
    val script: List[Completion] =
      List(makeMultiToolCallCompletion(List("T0", "T1")), makeCompletion("done"))
    val program: IO[Set[String]] = for
      model <- ScriptedChatModel(script)
      mw = new CellWritingMiddleware(cell, Map("T0" -> Set("a"), "T1" -> Set("b")))
      harness = harnessOf(
        model,
        stackOf(List(mw)),
        List(namedTool("T0"), namedTool("T1")),
        5,
        parallelToolExecution = false
      )
      res <- harness.generate(List(UserMessage("go")), 5)
    yield res match
      case HarnessResult.Completed(_, _, state) => state.get(cell)
      case _                                    => Set.empty
    assertEquals(program.unsafeRunSync(), Set("a", "b"))
  }

  // ── Interrupt / resume ──────────────────────────────────────────────────

  test("resumed run runs afterAgent exactly once, on the restored state") {
    // spec: harness-agent — Scenario: Resumed run runs afterAgent once
    val signal: InterruptSignal = InterruptSignal.simple("resume-me")
    val script: List[Completion] = List(
      makeToolCallCompletion("interrupting", "{}"),
      makeToolCallCompletion("interrupting", "{}"),
      makeCompletion("done")
    )
    val program: IO[(Option[HarnessResult], HarnessResult, List[String], String)] = for
      model <- ScriptedChatModel(script)
      fired <- IO.delay(new AtomicReference[Boolean](false))
      mw    <- ResumeProbeMiddleware("restored-cell-value")
      stack   = stackOf(List(mw))
      harness = harnessOf(model, stack, List(onceInterruptingTool(signal, fired)), 5)
      interrupted          <- harness.generate(List(UserMessage("go")), 5)
      observedBeforeResume <- mw.afterObserved.get
      resumed <- interrupted match
        case HarnessResult.Interrupted(_, messages, state) =>
          IO.fromEither(HarnessState.restore(stack.allCells, state.snapshot)).flatMap { (restored: HarnessState) =>
            harness.resume(messages, restored, 5).map((r: HarnessResult) => Some(r))
          }
        case _ => IO.pure(Option.empty[HarnessResult])
      observedAfterResume <- mw.afterObserved.get
    yield
      val interruptedStateValue: String = interrupted match
        case HarnessResult.Interrupted(_, _, state) => state.get(mw.cell)
        case _                                      => "<not interrupted>"
      assertEquals(observedBeforeResume, List.empty[String]) // afterAgent NOT run on interrupt
      (resumed, interrupted, observedAfterResume, interruptedStateValue)

    val (resumed, interrupted, observed, stateValue): (Option[HarnessResult], HarnessResult, List[String], String) =
      program.unsafeRunSync()
    assert(
      interrupted match
        case _: HarnessResult.Interrupted => true
        case _                            => false
    )
    assertEquals(stateValue, "restored-cell-value")
    resumed match
      case Some(HarnessResult.Completed(assistant, _, _)) => assertEquals(assistant.content, "done")
      case _                                              => fail("expected the resumed run to complete")
    // exactly one afterAgent invocation, on the RESUMED run's restored state
    assertEquals(observed, List("restored-cell-value"))
  }

  // ── ReactAgent.create source compatibility ──────────────────────────────

  test("ReactAgent.create source-compatible — 3-arg sugar runs through the empty stack") {
    // spec: harness-agent — Scenario: Existing example compiles unchanged
    val script: List[Completion] = List(makeCompletion("done"))
    val program: IO[Boolean] = for
      model <- ScriptedChatModel(script)
      agent = ReactAgent.create(model, List.empty)
      res <- agent.generate(List(UserMessage("hi")), 5)
    yield res.content == "done"
    assert(program.unsafeRunSync())
  }

  test("ReactAgent.create source-compatible — 6-arg sugar runs through the empty stack") {
    // spec: harness-agent — Scenario: Existing example compiles unchanged
    val script: List[Completion] = List(makeCompletion("done"))
    val program: IO[Boolean] = for
      model <- ScriptedChatModel(script)
      agent = ReactAgent.create("name", "desc", model, List.empty, None, 10)
      res <- agent.generate(List(UserMessage("hi")), 5)
    yield res.content == "done"
    assert(program.unsafeRunSync())
  }
