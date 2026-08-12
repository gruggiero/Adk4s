package org.adk4s.harness

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import upickle.default.*

/**
 * Hedgehog properties + structural edge-case tests for HarnessState.project /
 * mergeBack (Step 2 — HUMAN GATE 2 — test oracle).
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * Properties derived from the spec's sub-agent boundary requirements:
 *  1. project-private-initial — Private cells in child read as `initial`
 *  2. project-inherited-shared-parent — Inherited/Shared cells in child read parent's value
 *  3. mergeBack-shared-folds — Shared cells fold `merge` over children
 *  4. mergeBack-private-inherited-unchanged — Private/Inherited cells unchanged in parent
 *  5. mergeBack-order-independence-semilattice — for semilattice merge, any permutation yields equal result
 *
 * Structural edge-case scenarios (not expressible as properties — they test
 * specific boundary conditions that don't benefit from generated inputs):
 *  - project — absent parent cell defaults to initial in child for Inherited
 *  - mergeBack — no children returns parent unchanged
 *  - privacy — Private parent value unobservable by child (both directions)
 */
class HarnessStateBoundarySpec extends HedgehogSuite:

  // ── Structural edge-case scenarios ──────────────────────────────────────

  test("project — absent parent cell defaults to initial in child for Inherited"):
    withMunitAssertions { a =>
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "inh", 42, visibility = CellVisibility.Inherited)
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.empty // parent doesn't have the cell
      val child: HarnessState          = HarnessState.project(parent, declared)
      a.assertEquals(child.get(cell), 42)
    }

  test("mergeBack — no children returns parent unchanged"):
    withMunitAssertions { a =>
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "shar",
        0,
        visibility = CellVisibility.Shared,
        merge = (a: Int, b: Int) => a + b
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(100)
      val merged: HarnessState         = HarnessState.mergeBack(parent, Nil, declared)
      a.assertEquals(merged.get(cell), 100)
    }

  test("project — Shared cell when parent doesn't have it defaults to initial"):
    withMunitAssertions { a =>
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "shar",
        42,
        visibility = CellVisibility.Shared,
        merge = (a: Int, b: Int) => a + b
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.empty // parent doesn't have the cell
      val child: HarnessState          = HarnessState.project(parent, declared)
      a.assertEquals(child.get(cell), 42) // Shared + absent parent → initial
    }

  // ── Hedgehog properties ─────────────────────────────────────────────────

  property("project-private-initial — Private cells in child read as initial"):
    for
      parentVal <- genInt.forAll
      initial   <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "priv", initial, visibility = CellVisibility.Private)
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(parentVal)
      val child: HarnessState          = HarnessState.project(parent, declared)
      child.get(cell) ==== initial

  property("project-inherited-shared-parent — Inherited/Shared cells read parent value"):
    for
      parentVal <- genInt.forAll
      initial   <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cInh: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "inh", initial, visibility = CellVisibility.Inherited)
      val cShar: StateCell[Int] =
        StateCell[Int](MiddlewareName("m"), "shar", initial, visibility = CellVisibility.Shared)
      val declared: List[StateCell[?]] = List(cInh, cShar)
      val parent: HarnessState         = HarnessState.initial(declared).set(cInh)(parentVal).set(cShar)(parentVal)
      val child: HarnessState          = HarnessState.project(parent, declared)
      (child.get(cInh) ==== parentVal).and(child.get(cShar) ==== parentVal)

  property("mergeBack-shared-folds — Shared cell folds merge over children"):
    for
      parentVal <- genInt.forAll
      c1Val     <- genInt.forAll
      c2Val     <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "shar",
        0,
        visibility = CellVisibility.Shared,
        merge = (a: Int, b: Int) => a + b
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(parentVal)
      val child1: HarnessState         = HarnessState.project(parent, declared).set(cell)(c1Val)
      val child2: HarnessState         = HarnessState.project(parent, declared).set(cell)(c2Val)
      val merged: HarnessState         = HarnessState.mergeBack(parent, List(child1, child2), declared)
      merged.get(cell) ==== (parentVal + c1Val + c2Val)

  property("mergeBack-private-inherited-unchanged — Private/Inherited unchanged in parent"):
    for
      parentVal <- genInt.forAll
      childVal  <- genInt.forAll
    yield
      given ReadWriter[Int]     = readwriter[Int]
      val cPriv: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "priv", 0, visibility = CellVisibility.Private)
      val cInh: StateCell[Int]  = StateCell[Int](MiddlewareName("m"), "inh", 0, visibility = CellVisibility.Inherited)
      val declared: List[StateCell[?]] = List(cPriv, cInh)
      val parent: HarnessState         = HarnessState.initial(declared).set(cPriv)(parentVal).set(cInh)(parentVal)
      val child: HarnessState          = HarnessState.project(parent, declared).set(cPriv)(childVal).set(cInh)(childVal)
      val merged: HarnessState         = HarnessState.mergeBack(parent, List(child), declared)
      (merged.get(cPriv) ==== parentVal).and(merged.get(cInh) ==== parentVal)

  property("mergeBack-order-independence-semilattice — set-union merge is order-independent"):
    for
      parentSet <- genSet.forAll
      c1Set     <- genSet.forAll
      c2Set     <- genSet.forAll
      c3Set     <- genSet.forAll
    yield
      given ReadWriter[Set[String]] = readwriter[Set[String]]
      val cell: StateCell[Set[String]] = StateCell[Set[String]](
        MiddlewareName("m"),
        "shar",
        Set.empty[String],
        visibility = CellVisibility.Shared,
        merge = (a: Set[String], b: Set[String]) => a.union(b)
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(parentSet)
      val children: List[HarnessState] = List(c1Set, c2Set, c3Set).map { s =>
        HarnessState.project(parent, declared).set(cell)(s)
      }
      // All permutations of children should yield the same merged result
      val permutations: List[List[HarnessState]] = children.permutations.toList
      val results: List[Set[String]] = permutations.map { perm =>
        HarnessState.mergeBack(parent, perm, declared).get(cell)
      }
      results match
        case Nil       => true ==== true
        case head :: _ => results.forall(_ == head) ==== true

  // ── L10: Merge-back neutrality for untouched children ───────────────────
  // For any parent and Shared cell with idempotent merge, a child that was
  // projected from the parent and did NOT write to the cell should not change
  // the parent's value: mergeBack(parent, List(project(parent, [s])), [s]).get(s)
  // == parent.get(s). This holds when merge is idempotent.

  property("merge-back-neutrality — untouched child with set-union merge preserves parent"):
    for parentSet <- genSet.forAll
    yield
      given ReadWriter[Set[String]] = readwriter[Set[String]]
      val cell: StateCell[Set[String]] = StateCell[Set[String]](
        MiddlewareName("m"),
        "shar",
        Set.empty[String],
        visibility = CellVisibility.Shared,
        merge = (a: Set[String], b: Set[String]) => a.union(b)
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(parentSet)
      // Project child but do NOT write to the Shared cell
      val child: HarnessState  = HarnessState.project(parent, declared)
      val merged: HarnessState = HarnessState.mergeBack(parent, List(child), declared)
      merged.get(cell) ==== parentSet // idempotence: union(parentSet, parentSet) == parentSet

  property("merge-back-neutrality — untouched child with max merge preserves parent"):
    for parentVal <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](
        MiddlewareName("m"),
        "shar",
        0,
        visibility = CellVisibility.Shared,
        merge = (a: Int, b: Int) => math.max(a, b)
      )
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(parentVal)
      // Project child but do NOT write to the Shared cell
      val child: HarnessState  = HarnessState.project(parent, declared)
      val merged: HarnessState = HarnessState.mergeBack(parent, List(child), declared)
      merged.get(cell) ==== parentVal // idempotence: max(parentVal, parentVal) == parentVal

  // ── L10 counterexample: non-idempotent merge violates neutrality ─────────
  // A non-idempotent merge (e.g. concatenation) causes an untouched child to
  // corrupt the parent's value. This demonstrates why non-idempotent merge
  // violates L10.

  test("non-idempotent merge violates L10 — untouched child corrupts parent"):
    withMunitAssertions { a =>
      given ReadWriter[List[String]] = readwriter[List[String]]
      val cell: StateCell[List[String]] = StateCell[List[String]](
        MiddlewareName("m"),
        "shar",
        List.empty[String],
        visibility = CellVisibility.Shared,
        merge = (a: List[String], b: List[String]) => a ++ b
      ) // concatenation — NOT idempotent
      val declared: List[StateCell[?]] = List(cell)
      val parent: HarnessState         = HarnessState.initial(declared).set(cell)(List("a"))
      // Project child but do NOT write to the Shared cell
      val child: HarnessState  = HarnessState.project(parent, declared)
      val merged: HarnessState = HarnessState.mergeBack(parent, List(child), declared)
      // merge(List("a"), List("a")) == List("a", "a") — corrupted!
      a.assertEquals(merged.get(cell), List("a", "a"))
    }

  // ── Generators ──────────────────────────────────────────────────────────

  private def genInt: Gen[Int] =
    Gen.int(Range.linear(-1000, 1000))

  private def genSet: Gen[Set[String]] =
    Gen
      .string(Gen.alpha, Range.linear(1, 5))
      .list(Range.linear(0, 5))
      .map(_.toSet)
