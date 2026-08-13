package org.adk4s.core.error

// spec: add-iron-refined-types/error-hierarchy-dedup — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// Tests ConfigError and GraphCompilationError as AdkError variants.

import cats.Show
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

class IronErrorHierarchySpec extends HedgehogSuite:

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ConfigError variant for refinement-boundary failures
  // Scenario: Invalid input produces ConfigError with field and constraint
  // ═══════════════════════════════════════════════════════════════

  test("ConfigError carries field, invalidValue, and constraint"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: Invalid input produces ConfigError with field and constraint
    val e: ConfigError = ConfigError("recallK", "-1", "NonNegative")
    assertEquals(e.field, "recallK")
    assertEquals(e.invalidValue, "-1")
    assertEquals(e.constraint, "NonNegative")

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ConfigError variant for refinement-boundary failures
  // Scenario: ConfigError is an AdkError variant
  // ═══════════════════════════════════════════════════════════════

  test("ConfigError is recognized as AdkError variant with message containing all fields"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: ConfigError is an AdkError variant
    val e: AdkError = ConfigError("maxSteps", "0", "Positive")
    e match
      case ce: ConfigError =>
        val msg: String = ce.message
        assert(msg.contains("maxSteps"), s"message should contain field: $msg")
        assert(msg.contains("0"), s"message should contain invalidValue: $msg")
        assert(msg.contains("Positive"), s"message should contain constraint: $msg")
      case other =>
        fail(s"Expected ConfigError, got ${other.getClass.getName}")

  // ═══════════════════════════════════════════════════════════════
  // Requirement: ConfigError variant for refinement-boundary failures
  // Scenario: Valid input produces no ConfigError
  // ═══════════════════════════════════════════════════════════════

  test("ConfigError message format is stable and parseable"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: Valid input produces no ConfigError
    // This test verifies the ConfigError message format so that callers can
    // reliably extract information. The "valid input produces no ConfigError"
    // behavior is tested in the specs that implement refinement boundaries
    // (memory-orchestration-hook, tools-node, etc.).
    val e: ConfigError = ConfigError("recallK", "-1", "NonNegative")
    val expected: String = "Invalid recallK: '-1' violates NonNegative"
    assertEquals(e.message, expected)

  // ═══════════════════════════════════════════════════════════════
  // Requirement: GraphCompilationError variant for graph-compile failures
  // Scenario: Failed compile produces GraphCompilationError with all errors
  // ═══════════════════════════════════════════════════════════════

  test("GraphCompilationError carries all validation errors"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: Failed compile produces GraphCompilationError with all errors
    val errors: List[AdkError] = List(
      NodeNotFoundError("missingEntry"),
      NodeNotFoundError("deadEnd")
    )
    val e: GraphCompilationError = GraphCompilationError(errors)
    assertEquals(e.errors.length, 2)
    assertEquals(e.errors, errors)

  // ═══════════════════════════════════════════════════════════════
  // Requirement: GraphCompilationError variant for graph-compile failures
  // Scenario: GraphCompilationError is an AdkError variant
  // ═══════════════════════════════════════════════════════════════

  test("GraphCompilationError is recognized as AdkError variant with message containing errors"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: GraphCompilationError is an AdkError variant
    val errors: List[AdkError] = List(NodeNotFoundError("foo"), EdgeValidationError("a", "b", "missing"))
    val e: AdkError = GraphCompilationError(errors)
    e match
      case gce: GraphCompilationError =>
        val msg: String = gce.message
        assert(msg.contains("Graph compilation failed"), s"message should describe compilation failure: $msg")
        assert(msg.contains("foo"), s"message should contain error details: $msg")
      case other =>
        fail(s"Expected GraphCompilationError, got ${other.getClass.getName}")

  // ═══════════════════════════════════════════════════════════════
  // Requirement: GraphCompilationError variant for graph-compile failures
  // Scenario: Compiled graph produces no GraphCompilationError
  // ═══════════════════════════════════════════════════════════════

  test("GraphCompilationError with empty errors list has valid message"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: Compiled graph produces no GraphCompilationError
    // The "compiled graph produces no error" behavior is tested in the
    // wio-graph spec. Here we verify the edge case of an empty error list.
    val e: GraphCompilationError = GraphCompilationError(List.empty)
    assertEquals(e.errors, List.empty)
    assert(e.message.contains("Graph compilation failed"), s"message should still describe failure: ${e.message}")
    assert(e.message.contains("0 error(s)"), s"message should reflect empty error count: ${e.message}")

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Existing AdkError pattern matches handle new variants
  // Scenario: Match with catch-all continues to compile
  // ═══════════════════════════════════════════════════════════════

  test("ConfigError and GraphCompilationError are handled by catch-all AdkError match"):
    // spec: add-iron-refined-types/error-hierarchy-dedup — Scenario: Match with catch-all continues to compile
    // This test verifies that existing catch-all matches (case _: AdkError =>)
    // correctly receive the new variants. The exhaustiveness escalation is
    // verified via Ring 0 (sbt compile).
    val configErr: AdkError = ConfigError("f", "v", "c")
    val graphErr: AdkError = GraphCompilationError(List(NodeNotFoundError("x")))
    val configResult: String = configErr match
      case _: AgentInterruptedException => "interrupt"
      case _: ConfigError               => "config"
      case other: AdkError              => other.message
    val graphResult: String = graphErr match
      case _: AgentInterruptedException => "interrupt"
      case _: GraphCompilationError     => "graph"
      case other: AdkError              => other.message
    assertEquals(configResult, "config")
    assertEquals(graphResult, "graph")

  // ═══════════════════════════════════════════════════════════════
  // Property: ConfigError round-trips through AdkError Show
  // spec: add-iron-refined-types/error-hierarchy-dedup — Property: ConfigError round-trips through AdkError Show
  // ═══════════════════════════════════════════════════════════════

  property("ConfigError round-trips through AdkError Show"):
    // Generator strategy: Gen.string(Gen.alpha, Range.linear(1, 20)) for
    // field/constraint; Gen.string(Gen.alphaNum, Range.linear(1, 20)) for
    // invalidValue — constructive, covers single-char and multi-char names.
    val fieldGen: Gen[String] = Gen.string(Gen.alpha, Range.linear(1, 20))
    val invalidValueGen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 20))
    val constraintGen: Gen[String] = Gen.string(Gen.alpha, Range.linear(1, 20))
    (for
      field <- fieldGen
      invalidValue <- invalidValueGen
      constraint <- constraintGen
    yield
      val e: ConfigError = ConfigError(field, invalidValue, constraint)
      val shown: String = summon[Show[AdkError]].show(e)
      Result.assert(shown.contains(field))
        .and(Result.assert(shown.contains(invalidValue)))
        .and(Result.assert(shown.contains(constraint)))
    ).forAll

  // ═══════════════════════════════════════════════════════════════
  // Property: GraphCompilationError preserves error list
  // spec: add-iron-refined-types/error-hierarchy-dedup — Property: GraphCompilationError preserves error list
  // ═══════════════════════════════════════════════════════════════

  property("GraphCompilationError preserves error list and message reflects size"):
    // Generator strategy: Gen.list(Gen.const(NodeNotFoundError("node")),
    // Range.linear(0, 5)) — constructive, covers empty and non-empty error lists.
    val errorsGen: Gen[List[AdkError]] = Gen.list(Gen.constant(NodeNotFoundError("node")), Range.linear(0, 5))
    errorsGen.forAll.map { (errors: List[AdkError]) =>
      val e: GraphCompilationError = GraphCompilationError(errors)
      (e.errors ==== errors)
        .and(Result.assert(e.message.contains(errors.length.toString)))
    }
