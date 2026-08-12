package org.adk4s.harness

import cats.Applicative
import org.adk4s.core.component.InvokableTool

/**
 * Effect-polymorphic agent-loop middleware with four hooks
 * (`beforeAgent`, `afterAgent`, `wrapModelCall`, `wrapToolCall`) plus
 * three contribution members (`stateCells`, `tools`, state-aware
 * `promptSections(state)`).
 *
 * Each hook has a default implementation that is a no-op (returns the
 * input state unchanged for the before/after hooks, returns the next
 * step unchanged for the wrap hooks). The contribution members default
 * to empty.
 *
 * The trait deliberately omits node-level before-model/after-model hooks
 * (LangGraph-graph-shaped constructs the loop does not need) and a
 * separate modify-model-request hook (subsumed by `wrapModelCall`).
 *
 * spec: agent-middleware — Requirement: AgentMiddleware trait provides four hooks with deepagents parity
 */
trait AgentMiddleware[F[_]: Applicative]:
  /** Middleware identity — used for cell-id namespacing and error attribution. */
  def name: MiddlewareName

  /** State cells declared by this middleware. Defaults to empty. */
  def stateCells: List[StateCell[?]] = Nil

  /** Static tools contributed by this middleware. Defaults to empty. */
  def tools: List[InvokableTool[F]] = Nil

  /**
   * State-aware prompt sections. The function takes the current harness
   * state so that section text is folded per-request from live state, not
   * fixed at construction time. Defaults to empty.
   */
  def promptSections(state: HarnessState): List[PromptSection] = Nil

  /**
   * Runs once before the agent loop starts. Returns the (possibly modified)
   * initial harness state. Default: returns the input state unchanged.
   */
  def beforeAgent(state: HarnessState): F[HarnessState] =
    summon[Applicative[F]].pure(state)

  /**
   * Runs once after the agent loop completes (not on interrupt). Returns
   * the (possibly modified) final harness state. Default: returns the
   * input state unchanged.
   */
  def afterAgent(state: HarnessState): F[HarnessState] =
    summon[Applicative[F]].pure(state)

  /**
   * Wraps the model call step. Composes as a Kleisli endomorphism:
   * `m1.wrapModelCall(m2.wrapModelCall(base))` nests naturally.
   * Default: returns the next step unchanged.
   */
  def wrapModelCall(next: ModelStep[F]): ModelStep[F] = next

  /**
   * Wraps the tool call step. Composes as a Kleisli endomorphism.
   * Default: returns the next step unchanged.
   */
  def wrapToolCall(next: ToolStep[F]): ToolStep[F] = next

object AgentMiddleware:
  /**
   * The identity middleware: name is `"identity"`, every hook and
   * contribution is the default no-op/empty value. The identity
   * middleware is the neutral element of middleware composition.
   */
  def id[F[_]: Applicative]: AgentMiddleware[F] = new AgentMiddleware[F]:
    val name: MiddlewareName = MiddlewareName("identity")
