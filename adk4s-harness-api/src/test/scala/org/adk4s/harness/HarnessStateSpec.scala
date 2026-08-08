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

/** Hedgehog properties + structural edge-case tests for the harness-state spec
  * (Step 2 — HUMAN GATE 2 — test oracle).
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
    val snap: JsonValue = HarnessState.empty.snapshot
    snap match
      case Document.DObject(fields) => assertEquals(fields.size, 0)
      case _                        => fail("snapshot should be a DObject")

  test("snapshot contains cell ids as keys"):
    given ReadWriter[Int] = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
    val state: HarnessState = HarnessState.initial(List(cell)).set(cell)(42)
    val snap: JsonValue = state.snapshot
    snap match
      case Document.DObject(fields) =>
        assert(fields.contains("m/c"))
      case _ => fail("snapshot should be a DObject")

  test("restore with DArray is Left"):
    given ReadWriter[Int] = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
    val json: JsonValue = Document.DArray(Vector(Document.DString("x")))
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    assert(result.isLeft)

  test("restore with DString is Left"):
    given ReadWriter[Int] = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
    val json: JsonValue = Document.DString("not an object")
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    assert(result.isLeft)

  test("restore with DNull is Left"):
    given ReadWriter[Int] = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
    val json: JsonValue = Document.DNull
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    assert(result.isLeft)

  test("restore of corrupted cell value is Left with cellId"):
    given ReadWriter[Int] = readwriter[Int]
    val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
    // DString where Int expected — should fail to decode
    val json: JsonValue = Document.DObject(Map("m/c" -> Document.DString("not-an-int")))
    val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
    result match
      case Left(err) =>
        assertEquals(err.cellId, "m/c")
      case Right(_) => fail("restore of corrupted cell should fail")

  test("StateDecodeError is an AdkError with cellId, cause, and message"):
    val cause: Throwable = new Exception("bad json")
    val err: StateDecodeError = StateDecodeError("m/c", cause)
    val msg: String = err.message
    assertEquals(err.cellId, "m/c")
    assert(msg.contains("m/c"))
    assert(msg.contains("Failed to decode"))
    // Verify exception chaining (initCause was called)
    assertEquals(err.getCause, cause)

  // ── Hedgehog properties ─────────────────────────────────────────────────

  property("get-set-coherence — get(c)(set(c)(v)(s)) == v"):
    for
      v <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val s0: HarnessState = HarnessState.initial(List(cell))
      val s1: HarnessState = s0.set(cell)(v)
      s1.get(cell) ==== v

  property("set-preserves-other-cells — get(d)(set(c)(v)(s)) == get(d)(s)"):
    for
      v1 <- genInt.forAll
      v2 <- genString.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      given ReadWriter[String] = readwriter[String]
      val c1: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c1", 0)
      val c2: StateCell[String] = StateCell[String](MiddlewareName("m"), "c2", "default")
      val s0: HarnessState = HarnessState.initial(List(c1, c2)).set(c1)(v1).set(c2)(v2)
      val s1: HarnessState = s0.set(c1)(999)
      s1.get(c2) ==== v2

  property("get-absent-reads-initial"):
    for
      initial <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", initial)
      val state: HarnessState = HarnessState.empty
      state.get(cell) ==== initial

  property("update-applies-function — get(c)(update(c)(f)(s)) == f(get(c)(s))"):
    for
      v <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val s0: HarnessState = HarnessState.initial(List(cell)).set(cell)(v)
      val s1: HarnessState = s0.update(cell)(_ + 1)
      s1.get(cell) ==== (v + 1)

  property("set-is-immutable — original state unchanged after set"):
    for
      v0 <- genInt.forAll
      v1 <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val s0: HarnessState = HarnessState.initial(List(cell)).set(cell)(v0)
      val _ : HarnessState = s0.set(cell)(v1)
      s0.get(cell) ==== v0

  property("snapshot-restore-round-trip — restore(declared, snapshot(s)) == Right(s)"):
    for
      v1 <- genInt.forAll
      v2 <- genString.forAll
      v3 <- genBoolean.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      given ReadWriter[String] = readwriter[String]
      given ReadWriter[Boolean] = readwriter[Boolean]
      val c1: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "n1", 0)
      val c2: StateCell[String] = StateCell[String](MiddlewareName("m"), "n2", "default")
      val c3: StateCell[Boolean] = StateCell[Boolean](MiddlewareName("m"), "n3", false)
      val declared: List[StateCell[?]] = List(c1, c2, c3)
      val state: HarnessState = HarnessState.initial(declared)
        .set(c1)(v1)
        .set(c2)(v2)
        .set(c3)(v3)
      val snap: JsonValue = state.snapshot
      val restored: Either[StateDecodeError, HarnessState] = HarnessState.restore(declared, snap)
      restored match
        case Right(s) =>
          val r1: Int = s.get(c1)
          val r2: String = s.get(c2)
          val r3: Boolean = s.get(c3)
          (r1 ==== v1).and(r2 ==== v2).and(r3 ==== v3)
        case Left(err) =>
          Result.failure.log(s"round-trip should succeed: $err")

  property("restore-unknown-fields-ignored"):
    for
      v <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", 0)
      val json: JsonValue = Document.DObject(Map(
        "m/c" -> Document.DNumber(BigDecimal(v.toLong)),
        "unknown/id" -> Document.DString("stranger"),
        "another/unknown" -> Document.DNull
      ))
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      result match
        case Right(s) => s.get(cell) ==== v
        case Left(err) => Result.failure.log(s"restore should succeed: $err")

  property("restore-new-cells-default-initial"):
    for
      initial <- genInt.forAll
    yield
      given ReadWriter[Int] = readwriter[Int]
      val cell: StateCell[Int] = StateCell[Int](MiddlewareName("m"), "c", initial)
      val json: JsonValue = Document.DObject(Map.empty)
      val result: Either[StateDecodeError, HarnessState] = HarnessState.restore(List(cell), json)
      result match
        case Right(s) => s.get(cell) ==== initial
        case Left(err) => Result.failure.log(s"restore should succeed: $err")

  // ── Generators ──────────────────────────────────────────────────────────

  private def genInt: Gen[Int] =
    Gen.int(Range.linear(-1000, 1000))

  private def genString: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 10))

  private def genBoolean: Gen[Boolean] =
    Gen.boolean
