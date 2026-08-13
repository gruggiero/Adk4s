package org.adk4s.core.error.typecontract

import org.adk4s.core.error.{AdkError, ConfigError, GraphCompilationError, NodeNotFoundError}
import cats.Show

/** Typed contract for spec: add-iron-refined-types/error-hierarchy-dedup
  *
  * Verifies that ConfigError and GraphCompilationError are proper AdkError
  * variants with the correct fields and message formatting.
  *
  * spec: add-iron-refined-types/error-hierarchy-dedup
  */
final class IronErrorHierarchyTypeContract extends munit.FunSuite:

  // — Type conformance: ConfigError is an AdkError —
  test("ConfigError is an AdkError subtype"):
    val e: AdkError = ConfigError("recallK", "-1", "NonNegative")
    e match
      case _: ConfigError => ()  // expected
      case _              => fail("expected ConfigError")

  // — Type conformance: GraphCompilationError is an AdkError —
  test("GraphCompilationError is an AdkError subtype"):
    val e: AdkError = GraphCompilationError(List(NodeNotFoundError("foo")))
    e match
      case _: GraphCompilationError => ()  // expected
      case _                        => fail("expected GraphCompilationError")

  // — ConfigError carries field, invalidValue, constraint —
  test("ConfigError carries field, invalidValue, and constraint"):
    val e: ConfigError = ConfigError("recallK", "-1", "NonNegative")
    assertEquals(e.field, "recallK")
    assertEquals(e.invalidValue, "-1")
    assertEquals(e.constraint, "NonNegative")

  // — GraphCompilationError carries errors list —
  test("GraphCompilationError carries errors list"):
    val errors: List[AdkError] = List(NodeNotFoundError("a"), NodeNotFoundError("b"))
    val e: GraphCompilationError = GraphCompilationError(errors)
    assertEquals(e.errors, errors)

  // — ConfigError message contains all fields —
  test("ConfigError message contains field, invalidValue, and constraint"):
    val e: ConfigError = ConfigError("maxSteps", "0", "Positive")
    val msg: String = summon[Show[AdkError]].show(e)
    assert(msg.contains("maxSteps"), s"message should contain field name: $msg")
    assert(msg.contains("0"), s"message should contain invalid value: $msg")
    assert(msg.contains("Positive"), s"message should contain constraint: $msg")

  // — GraphCompilationError message reflects error count —
  test("GraphCompilationError message reflects errors"):
    val errors: List[AdkError] = List(NodeNotFoundError("a"), NodeNotFoundError("b"))
    val e: GraphCompilationError = GraphCompilationError(errors)
    val msg: String = summon[Show[AdkError]].show(e)
    assert(msg.contains("Graph compilation failed"), s"message should describe compilation failure: $msg")
    assert(msg.contains("2 error(s)"), s"message should contain error count: $msg")
    assert(msg.contains("a"), s"message should contain error details: $msg")

  // — ConfigError is throwable (AdkError extends Throwable) —
  test("ConfigError is throwable"):
    val e: ConfigError = ConfigError("f", "v", "c")
    val _: Throwable = e  // type conformance — compiles only if ConfigError <: Throwable

  // — GraphCompilationError is throwable —
  test("GraphCompilationError is throwable"):
    val e: GraphCompilationError = GraphCompilationError(Nil)
    val _: Throwable = e  // type conformance — compiles only if GraphCompilationError <: Throwable

  // — ConfigError getMessage returns message —
  test("ConfigError getMessage returns message"):
    val e: ConfigError = ConfigError("f", "v", "c")
    assertEquals(e.getMessage, e.message)

  // — GraphCompilationError getMessage returns message —
  test("GraphCompilationError getMessage returns message"):
    val e: GraphCompilationError = GraphCompilationError(List(NodeNotFoundError("x")))
    assertEquals(e.getMessage, e.message)
