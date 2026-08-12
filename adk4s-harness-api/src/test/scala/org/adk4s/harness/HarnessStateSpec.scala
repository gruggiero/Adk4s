package org.adk4s.harness

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import upickle.default.*
import org.adk4s.core.json.JsonValue
import org.adk4s.core.error.StateDecodeError
import smithy4s.Document

/**
 * Hedgehog properties + structural edge-case tests for the harness-state spec
 * (Step 2 — HUMAN GATE 2 — test oracle).
 *
 * NOTE: `HedgehogSuite` extends `HedgehogAssertions` which overrides
 * `assertEquals`/`assert`/`fail` to return `hedgehog.Result` instead of
 * throwing. In `test(...)` blocks (non-property tests), these return values
 * are silently discarded — the assertions do NOT fire. Scenario tests MUST
 * use `withMunitAssertions { a => a.assertEquals(...) }` to get real munit
 * assertions that throw on failure. Hedgehog `property(...)` blocks use
 * `====` and `and` which return `Result` checked by the property harness.
 *
 * Properties derived from the spec's Proof Obligations table, NOT from the
 * implementation:
 *  1. get-set-coherence — `get(c)(set(c)(v)(s)) == v`
 *  2. set-preserves-other-cells — `get(d)(set(c)(v)(s)) == get(d)(s)` for `c.id != d.id`
 *  3. snapshot-restore-round-trip — `restore(declared, snapshot(s)) == Right(s)` (up to absent-equals-initial)
 *  4. restore-unknown-fields-ignored — unknown ids in json are silently dropped
 *  5. restore-new-cells-default-initial — declared-but-absent cells read as `initial`
 *  6. get-absent-reads-initial — `get(c)(empty) == c.initial`
 *  7. update-applies-function — `get(c)(update(c)(f)(s)) == f(get(c)(s))`
 *  8. set-is-immutable — `get(c)(s) == get(c)(s)` after `set(c)(v)(s)` (s unchanged)
 *
 * Structural edge-case scenarios (not expressible as properties — they test
 * error shapes and structural invariants, not value equalities):
 *  - snapshot of empty state is an empty DObject
 *  - snapshot contains cell ids as keys
 *  - restore with DArray / DString / DNull is Left (non-DObject error)
 *  - restore of corrupted cell value is Left with cellId
 *  - StateDecodeError extends AdkError with cellId + cause + message
 */
class HarnessStateSpec extends HedgehogSuite:

  // ── Structural edge-case scenarios ──────────────────────────────────────

  test("snapshot of empty state is an empty DObject"):
    withMunitAssertions { a =>
      val snap: JsonValue = HarnessState.empty.snapshot
      snap match
        case Document.DObject(fields) => a.assertEquals(fields.size, 0)
        case _                        => a.fail("snapshot should be a DObject")
    }

  test("snapshot contains cell ids as keys"):
    withMunitAssertions { a =>
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val state: HarnessState  = HarnessState.initial(List(cell)).set(cell)(42)
      val snap: JsonValue      = state.snapshot
      snap match
        case Document.DObject(fields) =>
          a.assert(fields.contains("m/c"))
        case _ => a.fail("snapshot should be a DObject")
    }

  test("restore with DArray is Left"):
    withMunitAssertions { a =>
      given ReadWriter[Int]                              = readwriter[Int]
      val cell: StateCell[Int]                           = StateCell[Int](MiddlewareName("m"), "c", 0)
      val json: JsonValue                                = Document.DArray(Vector(Document.DString("x")))
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      a.assert(result.isLeft)
    }

  test("restore with DString is Left"):
    withMunitAssertions { a =>
      given ReadWriter[Int]                              = readwriter[Int]
      val cell: StateCell[Int]                           = StateCell[Int](MiddlewareName("m"), "c", 0)
      val json: JsonValue                                = Document.DString("not an object")
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      a.assert(result.isLeft)
    }

  test("restore with DNull is Left"):
    withMunitAssertions { a =>
      given ReadWriter[Int]                              = readwriter[Int]
      val cell: StateCell[Int]                           = StateCell[Int](MiddlewareName("m"), "c", 0)
      val json: JsonValue                                = Document.DNull
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      a.assert(result.isLeft)
    }

  test("restore of corrupted cell value is Left with cellId"):
    withMunitAssertions { a =>
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      // DString where Int expected — should fail to decode
      val json: JsonValue = Document.DObject(Map("m/c" -> Document.DString("not-an-int")))
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      result match
        case Left(err) =>
          a.assertEquals(err.cellId, "m/c")
        case Right(_) => a.fail("restore of corrupted cell should fail")
    }

  test("StateDecodeError is an AdkError with cellId, cause, and message"):
    withMunitAssertions { a =>
      val cause: Throwable      = new Exception("bad json")
      val err: StateDecodeError = StateDecodeError("m/c", cause)
      val msg: String           = err.message
      a.assertEquals(err.cellId, "m/c")
      a.assert(msg.contains("m/c"))
      a.assert(msg.contains("Failed to decode"))
      // Verify exception chaining (initCause was called)
      a.assertEquals(err.getCause, cause)
    }

  // ── Hedgehog properties ─────────────────────────────────────────────────

  property("get-set-coherence — get(c)(set(c)(v)(s)) == v"):
    for
      stateAndCells <- Generators.genHarnessState.forAll
      v             <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val (cells, s0)          = stateAndCells
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("target"), "c", 0)
      val s1: HarnessState     = s0.set(cell)(v)
      s1.get(cell) ==== v

  property("set-preserves-other-cells — get(d)(set(c)(v)(s)) == get(d)(s)"):
    for
      stateAndCells <- Generators.genHarnessState.forAll
      v             <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val (cells, s0)       = stateAndCells
      // Use a cell from the generated state as d, and a fresh cell as c
      cells.headOption match
        case Some(d) =>
          val c: StateCell[Int] = StateCell[Int](MiddlewareName("other"), "c", 0)
          val beforeD: Int      = s0.get(d)
          val s1: HarnessState  = s0.set(c)(v)
          s1.get(d) ==== beforeD
        case None =>
          // Empty-state edge: no cells in state, set a fresh cell, get on another fresh cell
          val c: StateCell[Int] = StateCell[Int](MiddlewareName("other"), "c", 0)
          val d: StateCell[Int] = StateCell[Int](MiddlewareName("another"), "d", 42)
          val s1: HarnessState  = HarnessState.empty.set(c)(v)
          s1.get(d) ==== 42 // d.initial, unaffected by write to c

  property("get-absent-reads-initial"):
    for initial <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", initial)
      val state: HarnessState  = HarnessState.empty
      state.get(cell) ==== initial

  property("update-applies-function — get(c)(update(c)(f)(s)) == f(get(c)(s))"):
    for v <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val s0: HarnessState     = HarnessState.initial(List(cell)).set(cell)(v)
      val s1: HarnessState     = s0.update(cell)(_ + 1)
      s1.get(cell) ==== (v + 1)

  property("set-is-immutable — original state unchanged after set"):
    for
      v0 <- genInt.forAll
      v1 <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val s0: HarnessState     = HarnessState.initial(List(cell)).set(cell)(v0)
      val _: HarnessState      = s0.set(cell)(v1)
      s0.get(cell) ==== v0

  property("snapshot-restore-round-trip — restore(declared, snapshot(s)) == Right(s)"):
    for
      v1 <- genInt.forAll
      v2 <- genString.forAll
      v3 <- genBoolean.forAll
    yield
      given ReadWriter[Int]            = readwriter[Int]
      given ReadWriter[String]         = readwriter[String]
      given ReadWriter[Boolean]        = readwriter[Boolean]
      val c1: StateCell[Int]           = StateCell[Int](MiddlewareName("m"), "n1", 0)
      val c2: StateCell[String]        = StateCell[String](MiddlewareName("m"), "n2", "default")
      val c3: StateCell[Boolean]       = StateCell[Boolean](MiddlewareName("m"), "n3", false)
      val declared: List[StateCell[?]] = List(c1, c2, c3)
      val state: HarnessState = HarnessState
        .initial(declared)
        .set(c1)(v1)
        .set(c2)(v2)
        .set(c3)(v3)
      val snap: JsonValue                                  = state.snapshot
      val restored: Either[StateDecodeError, HarnessState] = HarnessState.restore(declared, snap)
      restored match
        case Right(s) =>
          val r1: Int     = s.get(c1)
          val r2: String  = s.get(c2)
          val r3: Boolean = s.get(c3)
          (r1 ==== v1).and(r2 ==== v2).and(r3 ==== v3)
        case Left(err) =>
          Result.failure.log(s"round-trip should succeed: $err")

  property("restore-unknown-fields-ignored"):
    for v <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val json: JsonValue = Document.DObject(
        Map(
          "m/c"             -> Document.DNumber(BigDecimal(v.toLong)),
          "unknown/id"      -> Document.DString("stranger"),
          "another/unknown" -> Document.DNull
        )
      )
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      result match
        case Right(s)  => s.get(cell) ==== v
        case Left(err) => Result.failure.log(s"restore should succeed: $err")

  property("restore-new-cells-default-initial"):
    for initial <- genInt.forAll
    yield
      given ReadWriter[Int]                              = readwriter[Int]
      val cell: StateCell[Int]                           = StateCell[Int](MiddlewareName("m"), "c", initial)
      val json: JsonValue                                = Document.DObject(Map.empty)
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      result match
        case Right(s)  => s.get(cell) ==== initial
        case Left(err) => Result.failure.log(s"restore should succeed: $err")

  // ── L7a: Codec round-trip per cell ──────────────────────────────────────
  // For every declared cell and generated value, read(rw)(write(rw)(a)) == a.
  // This isolates the codec layer from the full snapshot/restore cycle (L7b).

  property("codec-round-trip-per-cell — Int"):
    for a <- genInt.forAll
    yield
      given ReadWriter[Int]    = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val written: ujson.Value = writeJs(a)(using cell.rw)
      val decoded: Int         = upickle.default.read[Int](written)(using cell.rw)
      decoded ==== a

  property("codec-round-trip-per-cell — String"):
    for a <- genString.forAll
    yield
      given ReadWriter[String]    = readwriter[String]
      val cell: StateCell[String] = StateCell[String](MiddlewareName("m"), "c", "default")
      val written: ujson.Value    = writeJs(a)(using cell.rw)
      val decoded: String         = upickle.default.read[String](written)(using cell.rw)
      decoded ==== a

  property("codec-round-trip-per-cell — Set[String]"):
    for a <- genSetString.forAll
    yield
      given ReadWriter[Set[String]]    = readwriter[Set[String]]
      val cell: StateCell[Set[String]] = StateCell[Set[String]](MiddlewareName("m"), "c", Set.empty[String])
      val written: ujson.Value         = writeJs(a)(using cell.rw)
      val decoded: Set[String]         = upickle.default.read[Set[String]](written)(using cell.rw)
      decoded ==== a

  property("codec-round-trip-per-cell — List[Int]"):
    for a <- genListInt.forAll
    yield
      given ReadWriter[List[Int]]    = readwriter[List[Int]]
      val cell: StateCell[List[Int]] = StateCell[List[Int]](MiddlewareName("m"), "c", List.empty[Int])
      val written: ujson.Value       = writeJs(a)(using cell.rw)
      val decoded: List[Int]         = upickle.default.read[List[Int]](written)(using cell.rw)
      decoded ==== a

  // ── Generators ──────────────────────────────────────────────────────────

  private def genInt: Gen[Int] =
    Gen.int(Range.linear(-1000, 1000))

  private def genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(0, 20))

  private def genBoolean: Gen[Boolean] =
    Gen.boolean

  private def genSetString: Gen[Set[String]] =
    Gen
      .string(Gen.alpha, Range.linear(1, 10))
      .list(Range.linear(0, 10))
      .map(_.toSet)

  private def genListInt: Gen[List[Int]] =
    Gen
      .int(Range.linear(-100, 100))
      .list(Range.linear(0, 20))
