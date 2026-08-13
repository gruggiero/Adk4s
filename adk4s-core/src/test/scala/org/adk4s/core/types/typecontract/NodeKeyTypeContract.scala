package org.adk4s.core.types.typecontract

import munit.FunSuite
import org.adk4s.core.types.{NodeKey, ReservedNodeKey, Positive, NonNegative, given}
import io.github.iltotore.iron.{autoRefine, refineEither}
import io.github.iltotore.iron.constraint.numeric

// spec: add-iron-refined-types/core-types — Typed Contract (Step 1)
// Verifies type conformance and compile-time behavior of the migrated
// NodeKey refined opaque type, ReservedNodeKey enum, and Positive/
// NonNegative constraint aliases.

class NodeKeyTypeContract extends FunSuite:

  // ── NodeKey type conformance ───────────────────────────────────

  test("NodeKey is constructible from a valid inline literal via RefinedType.apply"):
    // RefinedType.apply checks the constraint at compile time
    val k: NodeKey = NodeKey("fetch")
    assertEquals(k.value, "fetch")

  test("NodeKey.either returns Right for valid runtime string"):
    val result: Either[String, NodeKey] = NodeKey.either("myNode")
    assert(result.isRight, s"Expected Right, got $result")
    result match
      case Right(nk) => assertEquals(nk.value, "myNode")
      case Left(err) => fail(s"Expected Right, got Left($err)")

  test("NodeKey.either returns Left for empty string"):
    val result: Either[String, NodeKey] = NodeKey.either("")
    assert(result.isLeft, s"Expected Left, got $result")

  test("NodeKey.either returns Left for __start__"):
    val result: Either[String, NodeKey] = NodeKey.either("__start__")
    assert(result.isLeft, s"Expected Left, got $result")

  test("NodeKey.either returns Left for __end__"):
    val result: Either[String, NodeKey] = NodeKey.either("__end__")
    assert(result.isLeft, s"Expected Left, got $result")

  test("NodeKey.from returns Left(NodeKeyError) for empty string"):
    val result = NodeKey.from("")
    assert(result.isLeft, s"Expected Left, got $result")
    result.left.toOption.foreach: err =>
      assertEquals(err.invalidKey, "")

  test("NodeKey.apply constructs via compile-time refinement"):
    val k: NodeKey = NodeKey("hardcoded")
    assertEquals(k.value, "hardcoded")

  test("NodeKey.value returns underlying string"):
    val k: NodeKey = NodeKey("testNode")
    assertEquals(k.value, "testNode")

  // ── ReservedNodeKey enum ───────────────────────────────────────

  test("ReservedNodeKey.Start has value __start__"):
    assertEquals(ReservedNodeKey.Start.value, "__start__")

  test("ReservedNodeKey.End has value __end__"):
    assertEquals(ReservedNodeKey.End.value, "__end__")

  test("ReservedNodeKey is not a NodeKey"):
    // This is a compile-time check: ReservedNodeKey and NodeKey are
    // distinct types. If this compiles, the types are correctly separate.
    val r: ReservedNodeKey = ReservedNodeKey.Start
    val k: NodeKey = NodeKey("node")
    assertEquals(r.value, "__start__")
    assertEquals(k.value, "node")

  // ── Positive / NonNegative constraint aliases ──────────────────

  test("Positive accepts positive values at compile time"):
    val x: Positive = 42
    assertEquals(x, 42)

  test("NonNegative accepts zero at compile time"):
    val x: NonNegative = 0
    assertEquals(x, 0)

  test("NonNegative accepts positive values at compile time"):
    val x: NonNegative = 10
    assertEquals(x, 10)

  test("Positive.refineEither rejects zero"):
    val result = (0).refineEither[numeric.Positive]
    assert(result.isLeft, s"Expected Left for 0, got $result")

  test("Positive.refineEither rejects negative"):
    val result = (-1).refineEither[numeric.Positive]
    assert(result.isLeft, s"Expected Left for -1, got $result")

  test("NonNegative.refineEither rejects negative"):
    val result = (-1).refineEither[numeric.Positive0]
    assert(result.isLeft, s"Expected Left for -1, got $result")

  test("NonNegative.refineEither accepts zero"):
    val result = (0).refineEither[numeric.Positive0]
    assert(result.isRight, s"Expected Right for 0, got $result")

  // ── Compile-negative obligations ───────────────────────────────
  // spec: add-iron-refined-types/core-types — Compile-Negative Obligations
  // The `compileErrors` macro returns the compiler error string for the
  // given snippet; we assert it is non-empty.

  test("NodeKey(\"\") as inline literal does not compile"):
    val errors: String = compileErrors("""val k: NodeKey = NodeKey("")""")
    assert(errors.nonEmpty, "NodeKey(\"\") must not compile — empty string violates NonEmpty")

  test("NodeKey(\"__start__\") as inline literal does not compile"):
    val errors: String = compileErrors("""val k: NodeKey = NodeKey("__start__")""")
    assert(errors.nonEmpty, "NodeKey(\"__start__\") must not compile — reserved string violates Not[Reserved]")

  test("ReservedNodeKey is not assignable to NodeKey"):
    val errors: String = compileErrors("""val k: NodeKey = ReservedNodeKey.Start""")
    assert(errors.nonEmpty, "ReservedNodeKey must not be assignable to NodeKey")
