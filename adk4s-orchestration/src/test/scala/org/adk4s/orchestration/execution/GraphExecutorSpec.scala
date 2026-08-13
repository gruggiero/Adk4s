package org.adk4s.orchestration.execution

// spec: add-iron-refined-types/wio-graph — Test oracle (Step 2)
// Scenario tests for GraphCompilationError and ValidatedGraph.

import cats.effect.IO
import cats.data.Validated
import munit.CatsEffectSuite
import org.adk4s.core.error.{AdkError, GraphCompilationError, GraphEntryMissingError}
import org.adk4s.orchestration.graph.{Graph, ValidatedGraph}

class GraphExecutorSpec extends CatsEffectSuite:

  // ── Scenario tests ───────────────────────────────────────────────────────

  test("Failed compile raises GraphCompilationError"):
    // spec: add-iron-refined-types/wio-graph — Scenario: Failed compile raises GraphCompilationError
    // An empty graph has no entry node, so compile fails
    val graph: Graph[String, String] = Graph[String, String]
    val result: IO[String] = GraphExecutor.execute(graph, "input")
    result.attempt.map {
      case Left(err: GraphCompilationError) =>
        err.errors.nonEmpty
      case _ => false
    }.assert

  test("GraphCompilationError is matchable as AdkError"):
    // spec: add-iron-refined-types/wio-graph — Scenario: Caller can pattern-match GraphCompilationError as AdkError
    val graph: Graph[String, String] = Graph[String, String]
    val result: IO[String] = GraphExecutor.execute(graph, "input")
    result.attempt.map {
      case Left(_: GraphCompilationError) => true
      case _ => false
    }.assert

  test("GraphCompilationError carries validation errors"):
    // spec: add-iron-refined-types/wio-graph — Property: GraphCompilationError carries all validation errors
    val graph: Graph[String, String] = Graph[String, String]
    val result: IO[String] = GraphExecutor.execute(graph, "input")
    result.attempt.map {
      case Left(err: GraphCompilationError) =>
        // Empty graph should have at least GraphEntryMissingError
        err.errors.exists { e => e match { case _: GraphEntryMissingError => true; case _ => false } }
      case _ => false
    }.assert

  test("ValidatedGraph.from rejects invalid graph"):
    // spec: add-iron-refined-types/wio-graph — Scenario: Invalid graph cannot yield ValidatedGraph
    val graph: Graph[String, String] = Graph[String, String]
    val result: Either[cats.data.ValidatedNec[AdkError, Unit], ValidatedGraph[String, String]] =
      ValidatedGraph.from(graph)
    assert(result.isLeft, "Expected Left for invalid graph")

  test("No commented-out throw blocks remain in GraphExecutor"):
    // spec: add-iron-refined-types/wio-graph — Scenario: No commented-out throw blocks remain
    val source: String = scala.io.Source.fromFile(
      "adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/GraphExecutor.scala"
    ).mkString
    val throwInComment: Boolean = source.contains("throw new IllegalStateException") ||
      source.contains("throw new IllegalArgumentException")
    assert(!throwInComment, "No commented-out throw blocks should remain")
