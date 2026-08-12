package org.adk4s.harness.testkit

import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.traverse.toTraverseOps
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.error.AgentInterruptedException
import org.adk4s.core.interrupt.InterruptSignal
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.harness.{
  CellVisibility,
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
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  SystemMessage,
  ToolCall,
  ToolMessage
}
import org.llm4s.toolapi.ToolFunction

/**
 * The observable outcome of driving a stack on the deterministic model:
 * the final `AssistantMessage`, the final `HarnessState`, and the request
 * traces recorded at the base model step.
 *
 * Two observations are equal (`≍`) iff all components are equal — this is
 * the observational equivalence relation used by L0–L6.
 *
 * spec: middleware-laws — L0–L10 observational equivalence (`≍`)
 */
final case class Observation(
  finalAssistant: Option[AssistantMessage],
  finalState: HarnessState,
  requestTraces: List[RecordedRequest],
  outcome: Observation.Outcome
):
  /**
   * Observational equivalence with tool-order insensitivity: equal outcome
   * variant, equal final assistant message, equal final state snapshot, equal
   * request traces (tool names compared as SETS — tool contribution order is
   * not observable). Used by L6 (disjoint commutativity) where tool order
   * must not be observable.
   */
  def ≍(that: Observation): Boolean =
    this.outcome == that.outcome &&
      this.finalAssistant == that.finalAssistant &&
      this.finalState.snapshot == that.finalState.snapshot &&
      this.requestTraces.length == that.requestTraces.length &&
      this.requestTraces.zip(that.requestTraces).forall { case (a, b) =>
        a.renderedSystemPrompt == b.renderedSystemPrompt &&
        a.messages == b.messages &&
        a.toolNames.toSet == b.toolNames.toSet
      }

  /**
   * Strict observational equivalence: equal outcome variant, equal final
   * assistant message, equal final state snapshot, equal request traces
   * (tool names compared as ORDERED LISTS — tool order IS observable). Used
   * by L0–L5 where the spec says "equal request traces" element-for-element.
   */
  def eqStrict(that: Observation): Boolean =
    this.outcome == that.outcome &&
      this.finalAssistant == that.finalAssistant &&
      this.finalState.snapshot == that.finalState.snapshot &&
      this.requestTraces == that.requestTraces

object Observation:
  enum Outcome:
    case Completed, Interrupted, StepBudgetExhausted

/**
 * A minimal, deterministic ReAct loop that drives a `MiddlewareStack` on a
 * `DeterministicChatModel` using only `adk4s-harness-api` + `adk4s-core`
 * types. It does NOT depend on `adk4s-orchestration` (the testkit's Ring 2
 * boundary forbids it), so it is intentionally simpler than
 * `HarnessAgent.loop`: no event emission, no checkpoint/resume, no parallel
 * tool execution — sequential tool calls with state threaded through.
 *
 * The loop is the runner for L0–L6 observational-equivalence properties:
 * both sides of a law are driven by `run` and compared via `Observation.≍`.
 *
 * L0 note: the spec's L0 compares `HarnessAgent(MiddlewareStack.empty)` vs
 * `ReactAgentImpl`. Spec 5 refactored `ReactAgent.create` to delegate to
 * `HarnessAgent` with the empty stack, so at the orchestration level the
 * two share a code path and that comparison is covered by `HarnessAgentSpec`
 * (spec 5). The testkit's L0 compares the empty-stack loop vs a no-stack
 * baseline (the model + tools with no `MiddlewareStack` machinery) — a
 * meaningful regression property realizable within the testkit's layering.
 *
 * spec: middleware-laws — L0–L6 runner
 */
final class SimpleHarnessLoop(
  model: DeterministicChatModel,
  baseTools: List[InvokableTool[IO]],
  basePrompt: Option[String]
):

  def run(
    stack: MiddlewareStack[IO],
    messages: List[Message],
    maxSteps: Int
  ): IO[Observation] =
    val state0: HarnessState = HarnessState.initial(stack.allCells)
    stack.beforeAgent(state0).flatMap((state1: HarnessState) => loop(stack, messages, state1, maxSteps, maxSteps))

  /**
   * A no-stack baseline run: the model + tools with no `MiddlewareStack`
   * machinery (no before/afterAgent, no wrapModelCall/wrapToolCall, no
   * sections). Used as the L0 reference.
   */
  def runBaseline(messages: List[Message], maxSteps: Int): IO[Observation] =
    loopBaseline(messages, HarnessState.empty, maxSteps, maxSteps)

  // ── Stack-driven loop ──────────────────────────────────────────────────────

  private def loop(
    stack: MiddlewareStack[IO],
    messages: List[Message],
    state: HarnessState,
    remaining: Int,
    total: Int
  ): IO[Observation] =
    if remaining <= 0 then
      stack
        .afterAgent(state)
        .flatMap((finalState: HarnessState) => finish(Observation.Outcome.StepBudgetExhausted, None, finalState))
    else
      buildRequest(stack, messages, state).flatMap { (request: ModelRequest[IO]) =>
        val wrapped: ModelStep[IO] = stack.wrapModelCall(baseModelStep)
        wrapped.run(request).flatMap { (resp: ModelResponse) =>
          val assistant: AssistantMessage = resp.completion.message
          val curState: HarnessState      = resp.state
          if assistant.toolCalls.isEmpty then
            stack
              .afterAgent(curState)
              .flatMap((finalState: HarnessState) => finish(Observation.Outcome.Completed, Some(assistant), finalState))
          else
            executeToolCalls(stack, assistant.toolCalls.toList, curState).flatMap {
              case (toolMsgs, newState, Some(_)) =>
                finish(Observation.Outcome.Interrupted, None, newState)
              case (toolMsgs, newState, None) =>
                val updated: List[Message] = messages ++ Seq(assistant) ++ toolMsgs
                loop(stack, updated, newState, remaining - 1, total)
            }
        }
      }

  private def loopBaseline(
    messages: List[Message],
    state: HarnessState,
    remaining: Int,
    total: Int
  ): IO[Observation] =
    if remaining <= 0 then finish(Observation.Outcome.StepBudgetExhausted, None, state)
    else
      val request: ModelRequest[IO] = buildBaselineRequest(messages, state)
      baseModelStep.run(request).flatMap { (resp: ModelResponse) =>
        val assistant: AssistantMessage = resp.completion.message
        if assistant.toolCalls.isEmpty then finish(Observation.Outcome.Completed, Some(assistant), state)
        else
          executeToolCallsBaseline(assistant.toolCalls.toList, state).flatMap {
            case (toolMsgs, newState, Some(_)) =>
              finish(Observation.Outcome.Interrupted, None, newState)
            case (toolMsgs, newState, None) =>
              val updated: List[Message] = messages ++ Seq(assistant) ++ toolMsgs
              loopBaseline(updated, newState, remaining - 1, total)
          }
      }

  private def finish(
    outcome: Observation.Outcome,
    assistant: Option[AssistantMessage],
    state: HarnessState
  ): IO[Observation] =
    model.capturedRequests.get.map((traces: List[RecordedRequest]) => Observation(assistant, state, traces, outcome))

  // ── Request building ──────────────────────────────────────────────────────

  private def buildRequest(
    stack: MiddlewareStack[IO],
    messages: List[Message],
    state: HarnessState
  ): IO[ModelRequest[IO]] =
    val allTools: List[InvokableTool[IO]] = stack.allTools ++ baseTools
    val systemPrompt: SystemPrompt        = SystemPrompt(basePrompt, stack.allSections(state))
    IO.pure(ModelRequest(Some(systemPrompt), messages, allTools, buildCompletionOptions(allTools), state))

  private def buildBaselineRequest(messages: List[Message], state: HarnessState): ModelRequest[IO] =
    ModelRequest(Some(SystemPrompt(basePrompt, Nil)), messages, baseTools, buildCompletionOptions(baseTools), state)

  private def buildCompletionOptions(tools: List[InvokableTool[IO]]): CompletionOptions =
    val fns: Seq[ToolFunction[?, ?]] =
      tools.map((t: InvokableTool[IO]) => t.asToolFunction.getOrElse(t.info.toToolFunction))
    CompletionOptions(tools = fns)

  // ── Base model step ────────────────────────────────────────────────────────

  private def baseModelStep: ModelStep[IO] =
    Kleisli { (request: ModelRequest[IO]) =>
      val conversation: Conversation = buildConversation(request.systemPrompt, request.messages)
      model.generate(conversation, request.options).map((c: Completion) => ModelResponse(c, request.state))
    }

  private def buildConversation(systemPrompt: Option[SystemPrompt], messages: List[Message]): Conversation =
    val sysMsgs: List[Message] = systemPrompt match
      case Some(sp) if sp.base.isDefined || sp.sections.nonEmpty =>
        val hasSystem: Boolean = messages.exists { case _: SystemMessage => true; case _ => false }
        if hasSystem then Nil
        else List(SystemMessage(sp.render))
      case _ => Nil
    Conversation(sysMsgs ++ messages)

  // ── Tool execution ─────────────────────────────────────────────────────────

  private def executeToolCalls(
    stack: MiddlewareStack[IO],
    toolCalls: List[ToolCall],
    state: HarnessState
  ): IO[(List[ToolMessage], HarnessState, Option[InterruptSignal])] =
    val wrapped: ToolStep[IO] = stack.wrapToolCall(buildBaseToolStep(stack))
    val runOne: ToolCall => IO[(ToolOutput, HarnessState)] = (tc: ToolCall) =>
      val ctx: ToolCallCtx = ToolCallCtx(ToolInput.fromToolCall(tc), state)
      wrapped.run(ctx).map((out: ToolCallOut) => (out.output, out.state))
    toolCalls
      .traverse(runOne)
      .map { (results: List[(ToolOutput, HarnessState)]) =>
        val toolMsgs: List[ToolMessage] = results.map(_._1.toLlm4sMessage)
        val merged: HarnessState        = results.foldLeft(state)((acc, pair) => mergeStates(stack, acc, pair._2))
        (toolMsgs, merged, None)
      }
      .handleErrorWith {
        case interrupted: AgentInterruptedException => IO.pure((Nil, state, Some(interrupted.signal)))
        case other: Throwable                       => IO.raiseError(other)
      }

  private def executeToolCallsBaseline(
    toolCalls: List[ToolCall],
    state: HarnessState
  ): IO[(List[ToolMessage], HarnessState, Option[InterruptSignal])] =
    val toolMap: Map[String, InvokableTool[IO]] = baseTools.map(t => t.info.name -> t).toMap
    val runOne: ToolCall => IO[ToolMessage] = (tc: ToolCall) =>
      val input: ToolInput = ToolInput.fromToolCall(tc)
      toolMap.get(input.name) match
        case Some(tool) =>
          tool
            .run(ujson.read(input.arguments))
            .map((r: ujson.Value) => ToolOutput(input.name, r.toString, input.callId).toLlm4sMessage)
            .handleErrorWith {
              case interrupted: AgentInterruptedException => IO.raiseError(interrupted)
              case _: Throwable =>
                IO.pure(ToolMessage(s"Error", input.callId))
            }
        case None => IO.pure(ToolMessage(s"Unknown tool: ${input.name}", input.callId))
    toolCalls
      .traverse(runOne)
      .map((msgs: List[ToolMessage]) => (msgs, state, None))
      .handleErrorWith {
        case interrupted: AgentInterruptedException => IO.pure((Nil, state, Some(interrupted.signal)))
        case other: Throwable                       => IO.raiseError(other)
      }

  private def buildBaseToolStep(stack: MiddlewareStack[IO]): ToolStep[IO] =
    val toolMap: Map[String, InvokableTool[IO]] =
      (stack.allTools ++ baseTools).map(t => t.info.name -> t).toMap
    Kleisli { (ctx: ToolCallCtx) =>
      val input: ToolInput = ctx.input
      toolMap.get(input.name) match
        case Some(tool) =>
          tool
            .run(ujson.read(input.arguments))
            .map((r: ujson.Value) => ToolCallOut(ToolOutput(input.name, r.toString, input.callId), ctx.state))
            .handleErrorWith {
              case interrupted: AgentInterruptedException => IO.raiseError(interrupted)
              case other: Throwable =>
                IO.pure(ToolCallOut(ToolOutput(input.name, other.getMessage, input.callId, isError = true), ctx.state))
            }
        case None =>
          IO.pure(
            ToolCallOut(ToolOutput(input.name, s"Unknown tool: ${input.name}", input.callId, isError = true), ctx.state)
          )
    }

  /**
   * Merge a tool step's state into the accumulated state per-cell (Shared
   * cells combine via `cell.merge`; Private/Inherited take the tool step's
   * written value). Mirrors `HarnessAgent.mergeStates` semantics.
   */
  private def mergeStates(stack: MiddlewareStack[IO], acc: HarnessState, toolState: HarnessState): HarnessState =
    stack.allCells.foldLeft(acc) { (current, cell) =>
      cell match
        case c: StateCell[a] =>
          c.visibility match
            case CellVisibility.Shared => current.set(c)(c.merge(current.get(c), toolState.get(c)))
            case CellVisibility.Private | CellVisibility.Inherited =>
              current.set(c)(toolState.get(c))
    }
