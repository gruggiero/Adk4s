package org.adk4s.harness.testkit

import hedgehog.Gen
import hedgehog.Property
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import org.adk4s.harness.{ HarnessState, StateCell }
import Generators.*

/**
 * Reusable semilattice laws (L11) for `Shared` `StateCell` merge functions
 * intended for parallel delegation, in the `AgentMemoryLaws` style.
 *
 * A `Shared` cell's `merge` MUST be a semilattice — commutative, associative,
 * and idempotent — so that `mergeBack` over N concurrently-produced child
 * states is order-independent (the CRDT discipline). `SemilatticeLaws` checks
 * the three laws against any shared cell, and checks `mergeBack`
 * order-independence over permutations of children.
 *
 * The `mergeBack` order-independence property is a CONCURRENT behavior
 * property and MUST be driven with `TestControl` for deterministic execution
 * (the spec's L11-mergeBack-order-independence scenario). The pure
 * commutativity / associativity / idempotence laws are deterministic without
 * `TestControl`.
 *
 * spec: middleware-laws — Requirement: L11 Semilattice laws for parallel-shared cells
 */
final class SemilatticeLaws:

  import SemilatticeLaws.*

  // ── L11 commutativity ──────────────────────────────────────────────────────

  def l11Commutativity: Property =
    for c <- commutativityCase.forAll
        .cover(
          30,
          "union",
          (c: CommutativityCase[?]) =>
            c.tc match
              case _: SharedTypedCell.UnionCell => true
              case _                            => false
        )
        .cover(
          30,
          "max",
          (c: CommutativityCase[?]) =>
            c.tc match
              case _: SharedTypedCell.MaxCell => true
              case _                          => false
        )
        .cover(15, "equal-values", (c: CommutativityCase[?]) => c.a == c.b)
    yield c match
      case CommutativityCase(tc, a, b) =>
        val ok: Boolean = tc.merge(a, b) == tc.merge(b, a)
        if ok then Result.success else Result.failure.log("L11-semilattice-commutativity")

  // ── L11 associativity ──────────────────────────────────────────────────────

  def l11Associativity: Property =
    for c <- associativityCase.forAll
        .cover(
          30,
          "union",
          (c: AssociativityCase[?]) =>
            c.tc match
              case _: SharedTypedCell.UnionCell => true
              case _                            => false
        )
        .cover(
          30,
          "max",
          (c: AssociativityCase[?]) =>
            c.tc match
              case _: SharedTypedCell.MaxCell => true
              case _                          => false
        )
        .cover(15, "all-equal", (c: AssociativityCase[?]) => c.a == c.b && c.b == c.c)
    yield c match
      case AssociativityCase(tc, a, b, cc) =>
        val ok: Boolean = tc.merge(a, tc.merge(b, cc)) == tc.merge(tc.merge(a, b), cc)
        if ok then Result.success else Result.failure.log("L11-semilattice-associativity")

  // ── L11 idempotence ────────────────────────────────────────────────────────

  def l11Idempotence: Property =
    for c <- idempotenceCase.forAll
        .cover(
          30,
          "union",
          (c: IdempotenceCase[?]) =>
            c.tc match
              case _: SharedTypedCell.UnionCell => true
              case _                            => false
        )
        .cover(
          30,
          "max",
          (c: IdempotenceCase[?]) =>
            c.tc match
              case _: SharedTypedCell.MaxCell => true
              case _                          => false
        )
        .cover(10, "empty", (c: IdempotenceCase[?]) => c.a == c.tc.cell.initial)
    yield c match
      case IdempotenceCase(tc, a) =>
        val ok: Boolean = tc.merge(a, a) == a
        if ok then Result.success else Result.failure.log("L11-semilattice-idempotence")

  // ── L11 mergeBack order-independence ───────────────────────────────────────

  /**
   * For a parent state, a list of child states, and a `Shared` cell with a
   * semilattice merge, `mergeBack` over any permutation of children produces
   * an equal state. Driven with `TestControl` for deterministic concurrency.
   */
  def l11MergeBackOrderIndependence: Property =
    for c <- mergeBackCase.forAll
        .cover(15, "single-child", (c: MergeBackCase) => c.children.length == 1)
        .cover(15, "all-equal", (c: MergeBackCase) => c.children.map(ch => c.stc.get(ch)).distinct.length <= 1)
        .cover(30, "disjoint-writes", (c: MergeBackCase) => c.children.map(ch => c.stc.get(ch)).distinct.length >= 2)
    yield
      val cell: StateCell[?]           = c.stc.cell
      val original: HarnessState       = HarnessState.mergeBack(c.parent, c.children, List(cell))
      val permuted: List[HarnessState] = c.perm.map(c.children)
      val permutedResult: HarnessState = HarnessState.mergeBack(c.parent, permuted, List(cell))
      val ok: Boolean                  = c.stc.equalsIn(original, permutedResult)
      if ok then Result.success else Result.failure.log("L11-mergeBack-order-independence")

object SemilatticeLaws:

  // ── Parameterized case classes ──────────────────────────────────────────────
  // Pattern matching on these captures the type parameter `A`, allowing
  // typed access to `tc.merge(a, b)` without `Any` or `asInstanceOf`.

  final case class CommutativityCase[A](tc: SharedTypedCell[A], a: A, b: A)
  final case class AssociativityCase[A](tc: SharedTypedCell[A], a: A, b: A, c: A)
  final case class IdempotenceCase[A](tc: SharedTypedCell[A], a: A)
  final case class MergeBackCase(
    stc: SharedTypedCell[?],
    parent: HarnessState,
    children: List[HarnessState],
    perm: List[Int]
  )

  // ── Generators ──────────────────────────────────────────────────────────────
  // Each generator delegates to a method on `SharedTypedCell[A]` that
  // captures the type parameter internally, avoiding existentials.

  val commutativityCase: Gen[CommutativityCase[?]] =
    genSharedTypedCell.flatMap(_.genCommutativityCase)

  val associativityCase: Gen[AssociativityCase[?]] =
    genSharedTypedCell.flatMap(_.genAssociativityCase)

  val idempotenceCase: Gen[IdempotenceCase[?]] =
    genSharedTypedCell.flatMap(_.genIdempotenceCase)

  val mergeBackCase: Gen[MergeBackCase] =
    genSharedTypedCell.flatMap(_.genMergeBackCase)
