package org.adk4s.orchestration.agent

import cats.effect.Async
import cats.data.Kleisli
import cats.effect.IO
import cats.syntax.apply.catsSyntaxApplyOps
import cats.syntax.applicativeError.catsSyntaxApplicativeError
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps
import cats.syntax.foldable.toFoldableOps
import cats.effect.kernel.implicits.parallelForGenSpawn
import cats.syntax.parallel.catsSyntaxParallelTraverse1
import cats.syntax.traverse.toTraverseOps
import fs2.Stream
import io.github.iltotore.iron.refineEither
import io.github.iltotore.iron.constraint.numeric.Positive
import org.adk4s.core.component.{ ChatModel, InvokableTool }
import org.adk4s.core.error.{ AdkError, AgentInterruptedException, ConfigError, MaxStepsExceededError }
import org.adk4s.core.interrupt.{ AgentEvent, InterruptSignal, RunPath }
import org.adk4s.core.tools.{ ToolInput, ToolOutput }
import org.adk4s.harness.{
  CellVisibility,
  HarnessState,
  MiddlewareStack,
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
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message,
  StreamedChunk,
  SystemMessage,
  ToolCall,
  ToolMessage
}
import org.llm4s.toolapi.ToolFunction

/**
 * ReAct loop re-expressed against the middleware stack. The loop:
 *   1. Initializes state from `HarnessState.initial(stack.allCells)` (or
 *      `restore` on resume);
 *   2. Runs `stack.beforeAgent(state)` once;
 *   3. Iterates — builds `ModelRequest` with per-request tools and prompt,
 *      runs `stack.wrapModelCall(baseModelStep)`, executes tool calls through
 *      `stack.wrapToolCall(baseToolStep)`, merges state, appends messages,
 *      recurses until no tool calls or `maxSteps`;
 *   4. Runs `stack.afterAgent(state)` on normal termination.
 *
 * On `AgentInterruptedException`, the loop snapshots state without running
 * `afterAgent`.
 *
 * spec: harness-agent — Requirement: Loop orchestration order
 */
final class HarnessAgent[F[_]: Async](val config: HarnessAgent.Config[F]):

  import HarnessAgent.*

  val name: String        = config.name
  val description: String = config.description

  /** Refine maxSteps to Positive, lifting the error into F via Async.raiseError.
    *
    * spec: add-iron-refined-types/react-agent — Requirement: maxSteps is refined to Positive at the internal boundary
    */
  private def refineMaxSteps(maxSteps: Int): F[Int] =
    maxSteps.refineEither[Positive] match
      case Right(_) => Async[F].pure(maxSteps)
      case Left(msg: String) =>
        Async[F].raiseError(ConfigError("maxSteps", maxSteps.toString, "Positive"))

  def generate(messages: List[Message], maxSteps: Int): F[HarnessResult] =
    refineMaxSteps(maxSteps).flatMap { (_: Int) =>
      val effectiveMaxSteps: Int = math.min(maxSteps, config.maxSteps)
      val state0: HarnessState   = HarnessState.initial(config.stack.allCells)
      config.stack.beforeAgent(state0).flatMap { (state1: HarnessState) =>
        loop(messages, state1, effectiveMaxSteps, config.maxSteps)
      }
    }

  /**
   * Re-enters the loop with a restored `HarnessState` (from
   * `HarnessState.restore` on resume). Unlike `generate`, `beforeAgent` is
   * NOT re-run — it already ran before the interrupt that produced the
   * checkpoint. `afterAgent` runs exactly once, when the resumed run
   * terminates normally.
   *
   * spec: harness-agent — Scenario: Resumed run runs afterAgent once
   */
  def resume(messages: List[Message], restoredState: HarnessState, maxSteps: Int): F[HarnessResult] =
    refineMaxSteps(maxSteps).flatMap { (_: Int) =>
      val effectiveMaxSteps: Int = math.min(maxSteps, config.maxSteps)
      loop(messages, restoredState, effectiveMaxSteps, config.maxSteps)
    }

  def stream(messages: List[Message], maxSteps: Int): Stream[F, StreamedChunk] =
    val effectiveMaxSteps: Int = math.min(maxSteps, config.maxSteps)
    Stream.eval(generate(messages, effectiveMaxSteps)).flatMap {
      case HarnessResult.Completed(assistant, allMessages, _) =>
        val conversation: Conversation = buildConversation(
          SystemPrompt(config.basePrompt, Nil),
          allMessages
        )
        config.model.stream(conversation, buildCompletionOptions(config.baseTools))
      case HarnessResult.Interrupted(_, _, _) =>
        Stream.empty.covary[F]
      case HarnessResult.Failed(_, _, _) =>
        Stream.empty.covary[F]
    }

  // ── Loop ────────────────────────────────────────────────────────────────

  private def loop(
    messages: List[Message],
    state: HarnessState,
    remainingSteps: Int,
    totalSteps: Int
  ): F[HarnessResult] =
    if remainingSteps <= 0 then
      // Step budget exhausted — normal termination, run afterAgent
      config.stack.afterAgent(state).map { (finalState: HarnessState) =>
        HarnessResult.Failed(
          MaxStepsExceededError(totalSteps, totalSteps),
          messages,
          finalState
        )
      }
    else
      buildRequest(messages, state).flatMap { (request: ModelRequest[F]) =>
        val baseModelStep: ModelStep[F]    = baseModelCall
        val wrappedModelStep: ModelStep[F] = config.stack.wrapModelCall(baseModelStep)
        wrappedModelStep.run(request).flatMap { (response: ModelResponse) =>
          val completion: Completion         = response.completion
          val assistantMsg: AssistantMessage = completion.message
          val currentState: HarnessState     = response.state
          val currentIteration: Int          = totalSteps - remainingSteps + 1

          if assistantMsg.toolCalls.isEmpty then
            // Normal termination — emit events, run afterAgent
            emitEvent(
              AgentEvent.MessageOutput(
                runPath = RunPath.empty,
                message = assistantMsg.content,
                role = "assistant"
              )
            ) *>
              emitEvent(
                AgentEvent.IterationCompleted(
                  runPath = RunPath.empty,
                  iteration = currentIteration,
                  remainingSteps = remainingSteps - 1
                )
              ) *>
              config.stack.afterAgent(currentState).map { (finalState: HarnessState) =>
                HarnessResult.Completed(assistantMsg, messages :+ assistantMsg, finalState)
              }
          else
            val toolCalls: List[ToolCall] = assistantMsg.toolCalls.toList
            // Emit ToolCallRequested for each tool call
            toolCalls.traverse_ { (tc: ToolCall) =>
              emitEvent(
                AgentEvent.ToolCallRequested(
                  runPath = RunPath.empty,
                  toolName = tc.name,
                  arguments = tc.arguments.toString,
                  callId = tc.id
                )
              )
            } *>
              executeToolCalls(toolCalls, currentState).flatMap {
                case (toolMessages, newState, Some(signal)) =>
                  // Interrupt — snapshot state without afterAgent
                  emitEvent(AgentEvent.Interrupted(RunPath.empty, signal)).as(
                    HarnessResult.Interrupted(
                      signal,
                      messages :+ assistantMsg,
                      newState
                    )
                  )
                case (toolMessages, newState, None) =>
                  // Emit ToolCallCompleted for each tool result
                  toolCalls.zip(toolMessages).traverse_ { case (tc: ToolCall, tm: ToolMessage) =>
                    emitEvent(
                      AgentEvent.ToolCallCompleted(
                        runPath = RunPath.empty,
                        toolName = tc.name,
                        result = tm.content,
                        callId = tc.id,
                        isError = false
                      )
                    )
                  } *>
                    emitEvent(
                      AgentEvent.IterationCompleted(
                        runPath = RunPath.empty,
                        iteration = currentIteration,
                        remainingSteps = remainingSteps - 1
                      )
                    ) *> {
                      val updatedMessages: List[Message] = messages ++ Seq(assistantMsg) ++ toolMessages
                      loop(updatedMessages, newState, remainingSteps - 1, totalSteps)
                    }
              }
        }
      }

  // ── Per-request building ────────────────────────────────────────────────

  private def buildRequest(messages: List[Message], state: HarnessState): F[ModelRequest[F]] =
    val allTools: List[InvokableTool[F]] = config.stack.allTools ++ config.baseTools
    val systemPrompt: SystemPrompt =
      SystemPrompt(config.basePrompt, config.stack.allSections(state))
    val options: CompletionOptions = buildCompletionOptions(allTools)
    Async[F].pure(
      ModelRequest(
        systemPrompt = Some(systemPrompt),
        messages = messages,
        tools = allTools,
        options = options,
        state = state
      )
    )

  // ── Base model step ─────────────────────────────────────────────────────

  private def baseModelCall: ModelStep[F] =
    Kleisli { (request: ModelRequest[F]) =>
      val conversation: Conversation = buildConversation(
        request.systemPrompt.getOrElse(SystemPrompt(None, Nil)),
        request.messages
      )
      config.model.generate(conversation, request.options).map { (completion: Completion) =>
        ModelResponse(completion, request.state)
      }
    }

  // ── Tool execution ──────────────────────────────────────────────────────

  private def executeToolCalls(
    toolCalls: List[ToolCall],
    state: HarnessState
  ): F[(List[ToolMessage], HarnessState, Option[InterruptSignal])] =
    val baseToolStep: ToolStep[F]    = buildBaseToolStep(toolCalls)
    val wrappedToolStep: ToolStep[F] = config.stack.wrapToolCall(baseToolStep)

    // Execute the tool calls in parallel through the wrapped step
    // (parTraverse preserves the call order of the results regardless of
    // completion order), then merge each tool's resulting state per-cell.
    // `parallelToolExecution = false` runs the degenerate sequential case
    // (parallelism = 1) with identical merge semantics.
    // If one tool raises an interrupt, the run takes the interrupt path:
    // the first AgentInterruptedException failure cancels the sibling
    // fibers and the signal is surfaced.
    val runOne: ToolCall => F[(ToolOutput, HarnessState)] = (tc: ToolCall) =>
      val input: ToolInput = ToolInput.fromToolCall(tc)
      val ctx: ToolCallCtx = ToolCallCtx(input, state)
      wrappedToolStep.run(ctx).map((out: ToolCallOut) => (out.output, out.state))
    val results: F[List[(ToolOutput, HarnessState)]] =
      if config.parallelToolExecution then toolCalls.parTraverse(runOne)
      else toolCalls.traverse(runOne)
    results
      .map { (results: List[(ToolOutput, HarnessState)]) =>
        val toolMessages: List[ToolMessage] = results.map { case (out, _) =>
          out.toLlm4sMessage
        }
        // Merge states per-cell: Shared cells combine via cell.merge,
        // starting from the pre-tool state. For a semilattice merge the
        // fold is order-independent.
        val mergedState: HarnessState = results.foldLeft(state) { (acc, pair) =>
          val (_, toolState) = pair
          mergeStates(acc, toolState)
        }
        (toolMessages, mergedState, None)
      }
      .handleErrorWith {
        case interrupted: AgentInterruptedException =>
          Async[F].pure((Nil, state, Some(interrupted.signal)))
        case other: Throwable =>
          Async[F].raiseError(other)
      }

  private def buildBaseToolStep(allCalls: List[ToolCall]): ToolStep[F] =
    val toolMap: Map[String, InvokableTool[F]] =
      (config.stack.allTools ++ config.baseTools).map(t => t.info.name -> t).toMap
    Kleisli { (ctx: ToolCallCtx) =>
      val input: ToolInput = ctx.input
      toolMap.get(input.name) match
        case Some(tool) =>
          val args: ujson.Value = ujson.read(input.arguments)
          tool
            .run(args)
            .map { (result: ujson.Value) =>
              val resultStr: String = result match
                case ujson.Str(s) => s
                case other        => other.toString
              val output: ToolOutput = ToolOutput(input.name, resultStr, input.callId)
              ToolCallOut(output, ctx.state)
            }
            .handleErrorWith {
              case interrupted: AgentInterruptedException =>
                Async[F].raiseError(interrupted)
              case other: Throwable =>
                val errorOutput: ToolOutput =
                  ToolOutput(input.name, other.getMessage, input.callId, isError = true)
                Async[F].pure(ToolCallOut(errorOutput, ctx.state))
            }
        case None =>
          val errorOutput: ToolOutput =
            ToolOutput(input.name, s"Unknown tool: ${input.name}", input.callId, isError = true)
          Async[F].pure(ToolCallOut(errorOutput, ctx.state))
    }

  /**
   * Merges a tool's resulting state into the accumulated state, per-cell:
   *   - `Shared` cells combine via `cell.merge(accValue, toolValue)`. For a
   *     semilattice `merge` (commutative, associative, idempotent), the
   *     per-iteration fold over parallel tool results is order-independent.
   *     For a non-semilattice `merge` (e.g. last-write-wins), the fold is
   *     list-ordered — deterministic but order-sensitive by design; the
   *     semilattice discipline is the middleware author's obligation.
   *   - `Private`/`Inherited` cells take the tool step's written value.
   *     Cell visibility governs cross-agent projection
   *     (`HarnessState.project`/`mergeBack`), not intra-agent stepping:
   *     within this loop a middleware owns its cells and a write via
   *     `wrapToolCall` is the middleware deliberately mutating its state.
   *     An unwritten cell carries the pre-iteration value forward
   *     unchanged, because every tool step starts from it.
   *
   * For the empty-stack case there are no cells and this fold is the
   * identity.
   *
   * spec: harness-agent — Requirement: Parallel tool-call state merge is order-independent
   */
  private def mergeStates(acc: HarnessState, toolState: HarnessState): HarnessState =
    config.stack.allCells.foldLeft(acc) { (current, cell) =>
      cell match
        case c: StateCell[a] =>
          cell.visibility match
            case CellVisibility.Shared => mergeSharedCell(current, c, toolState)
            case CellVisibility.Private | CellVisibility.Inherited =>
              mergeWrittenCell(current, c, toolState)
    }

  private def mergeSharedCell[A](
    acc: HarnessState,
    cell: StateCell[A],
    toolState: HarnessState
  ): HarnessState =
    acc.set(cell)(cell.merge(acc.get(cell), toolState.get(cell)))

  private def mergeWrittenCell[A](
    acc: HarnessState,
    cell: StateCell[A],
    toolState: HarnessState
  ): HarnessState =
    acc.set(cell)(toolState.get(cell))

  // ── Event emission ──────────────────────────────────────────────────────

  private def emitEvent(event: AgentEvent): F[Unit] =
    config.emitter match
      case None         => Async[F].unit
      case Some(emitFn) => emitFn(event)

object HarnessAgent:

  /**
   * Construction config for `HarnessAgent`.
   *
   * The `emitter` is an `Option[AgentEvent => F[Unit]]` — a function that
   * emits an event in `F`. For `F = IO`, this is constructed from
   * `AgentEventEmitter.emit` via `ReactAgent.create` sugar. Storing the
   * function form (rather than the `AgentEventEmitter` directly) keeps the
   * loop `F`-polymorphic without requiring `LiftIO[F]`.
   */
  final case class Config[F[_]](
    name: String,
    description: String,
    model: ChatModel[F],
    stack: MiddlewareStack[F],
    baseTools: List[InvokableTool[F]],
    basePrompt: Option[String],
    maxSteps: Int,
    emitter: Option[AgentEvent => F[Unit]] = None,
    parallelToolExecution: Boolean = true
  )

  /** Phase 0 runtime instantiation — `HarnessAgent[IO]`. */
  type IOHarnessAgent = HarnessAgent[IO]

  // ── Helpers (shared between HarnessAgent and ReactAgent sugar) ──────────

  /** Builds a `Conversation` from an optional system prompt and messages. */
  def buildConversation(
    systemPrompt: SystemPrompt,
    messages: List[Message]
  ): Conversation =
    val systemMessages: List[Message] =
      if systemPrompt.sections.isEmpty && systemPrompt.base.isEmpty then Nil
      else
        val alreadyHasSystem: Boolean = messages.exists {
          case _: SystemMessage => true
          case _                => false
        }
        if alreadyHasSystem then Nil
        else List(SystemMessage(systemPrompt.render))
    Conversation(systemMessages ++ messages)

  /** Builds `CompletionOptions` from a tool list, deriving per-request. */
  def buildCompletionOptions[F[_]](tools: List[InvokableTool[F]]): CompletionOptions =
    val toolFunctions: Seq[ToolFunction[?, ?]] =
      tools.map((tool: InvokableTool[F]) => tool.asToolFunction.getOrElse(tool.info.toToolFunction))
    CompletionOptions(tools = toolFunctions)
