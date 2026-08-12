package org.adk4s.harness

import org.adk4s.core.component.InvokableTool
import cats.Applicative
import cats.Monad
import cats.data.Kleisli
import cats.data.NonEmptyList
import cats.syntax.flatMap.toFlatMapOps
import cats.syntax.functor.toFunctorOps

/**
 * Monoid of `AgentMiddleware[F]` values with stack-order semantics per hook.
 *
 * A stack `[m1, m2, m3]` composes hooks with the following stack-order
 * semantics:
 *   - `beforeAgent` sequences m1 → m2 → m3 (Kleisli left-to-right fold)
 *   - `afterAgent` sequences m3 → m2 → m1 (reverse — teardown mirrors setup)
 *   - `wrapModelCall` and `wrapToolCall` compose m1 outermost —
 *     `m1(m2(m3(base)))` — via a right fold
 *   - `tools` and `stateCells` concatenate in stack order
 *   - `promptSections(state)` concatenates in stack order, folded per-request
 *     from the current `HarnessState`
 *
 * `MiddlewareStack.empty` is the identity element (empty middleware list).
 * Construction is via `validated` (which checks for duplicate cell ids and
 * duplicate tool names) or `empty` — the constructor is private.
 *
 * spec: middleware-stack — Requirement: MiddlewareStack is a monoid under list concatenation with stack-order semantics
 */
final case class MiddlewareStack[F[_]: Monad] private (middlewares: List[AgentMiddleware[F]]):

  /** All state cells declared by middlewares in stack order. */
  def allCells: List[StateCell[?]] =
    middlewares.flatMap(_.stateCells)

  /** All tools contributed by middlewares in stack order. */
  def allTools: List[InvokableTool[F]] =
    middlewares.flatMap(_.tools)

  /**
   * All prompt sections folded per-request from the current harness state.
   * Concatenates each middleware's `promptSections(state)` in stack order.
   */
  def allSections(state: HarnessState): List[PromptSection] =
    middlewares.flatMap(_.promptSections(state))

  /**
   * Runs `beforeAgent` on each middleware in stack order (m1 → m2 → m3).
   * Each middleware receives the state produced by the previous one.
   */
  def beforeAgent(state: HarnessState): F[HarnessState] =
    middlewares.foldLeft(summon[Monad[F]].pure(state))((acc, mw) => summon[Monad[F]].flatMap(acc)(mw.beforeAgent))

  /**
   * Runs `afterAgent` on each middleware in reverse stack order (m3 → m2 → m1).
   * Each middleware receives the state produced by the previous one.
   */
  def afterAgent(state: HarnessState): F[HarnessState] =
    middlewares.reverse.foldLeft(summon[Monad[F]].pure(state)) { (acc, mw) =>
      summon[Monad[F]].flatMap(acc)(mw.afterAgent)
    }

  /**
   * Wraps the base model step with each middleware's `wrapModelCall` in
   * outermost-first order: `m1(m2(m3(base)))`.
   */
  def wrapModelCall(base: ModelStep[F]): ModelStep[F] =
    middlewares.foldRight(base)((mw, step) => mw.wrapModelCall(step))

  /**
   * Wraps the base tool step with each middleware's `wrapToolCall` in
   * outermost-first order: `m1(m2(m3(base)))`.
   */
  def wrapToolCall(base: ToolStep[F]): ToolStep[F] =
    middlewares.foldRight(base)((mw, step) => mw.wrapToolCall(step))

  /**
   * Concatenates two stacks: `this ++ that` produces a stack whose
   * middlewares are `this.middlewares ++ that.middlewares`.
   */
  def ++(that: MiddlewareStack[F]): MiddlewareStack[F] =
    MiddlewareStack(this.middlewares ++ that.middlewares)

object MiddlewareStack:

  /**
   * The identity element: an empty middleware list. All hooks are no-ops,
   * all aggregations are empty.
   */
  def empty[F[_]: Monad]: MiddlewareStack[F] =
    MiddlewareStack(Nil)

  /**
   * Validates a list of middlewares for duplicate cell ids and duplicate
   * tool names. All detected errors are accumulated into a
   * `NonEmptyList[StackError]` (not short-circuited to the first). A valid
   * stack returns `Right(MiddlewareStack(ms))`.
   */
  def validated[F[_]: Monad](
    ms: List[AgentMiddleware[F]]
  ): Either[NonEmptyList[StackError], MiddlewareStack[F]] =
    val cellErrors: List[StackError] = checkDuplicateCellIds(ms)
    val toolErrors: List[StackError] = checkDuplicateToolNames(ms)
    val allErrors: List[StackError]  = cellErrors ++ toolErrors
    allErrors match
      case Nil          => Right(MiddlewareStack(ms))
      case head :: tail => Left(NonEmptyList(head, tail))

  // ── Validation helpers ──────────────────────────────────────────────────

  /**
   * Checks for duplicate cell ids across all middlewares' `stateCells`.
   * Returns a `StackError.DuplicateCellId` for each colliding id, listing
   * every owner that declares it.
   */
  private def checkDuplicateCellIds[F[_]](
    ms: List[AgentMiddleware[F]]
  ): List[StackError] =
    val idToOwners: Map[StateCell.CellId, List[MiddlewareName]] =
      ms.flatMap(mw => mw.stateCells.map(cell => (cell.id, mw.name))).groupBy(_._1).map { (id, pairs) =>
        id -> pairs.map(_._2)
      }
    idToOwners.toList.collect {
      case (id, owners) if owners.length > 1 =>
        StackError.DuplicateCellId(id, owners)
    }

  /**
   * Checks for duplicate tool names across all middlewares' `tools`.
   * Returns a `StackError.DuplicateToolName` for each colliding name.
   */
  private def checkDuplicateToolNames[F[_]](
    ms: List[AgentMiddleware[F]]
  ): List[StackError] =
    val nameToOwners: Map[String, List[MiddlewareName]] =
      ms.flatMap(mw => mw.tools.map(tool => (tool.info.name, mw.name))).groupBy(_._1).map { (name, pairs) =>
        name -> pairs.map(_._2)
      }
    nameToOwners.toList.collect {
      case (name, owners) if owners.length > 1 =>
        StackError.DuplicateToolName(name, owners)
    }
