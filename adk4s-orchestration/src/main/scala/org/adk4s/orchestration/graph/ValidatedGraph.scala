package org.adk4s.orchestration.graph

// spec: add-iron-refined-types/wio-graph — Requirement: ValidatedGraph is proof-carrying
// A proof-carrying graph that has passed `graph.compile`. Can only be
// constructed via `ValidatedGraph.from(g)`, which validates the graph.

import cats.data.{Validated, ValidatedNec}
import org.adk4s.core.error.AdkError

/** Proof-carrying graph that has passed `graph.compile`.
  *
  * Constructed via `ValidatedGraph.from(graph)`, which calls
  * `graph.validateGraph` and returns `Right(ValidatedGraph)` on success
  * or `Left(errors)` on failure. The `executeGraph` method accepts a
  * `ValidatedGraph` and does not re-validate.
  *
  * spec: add-iron-refined-types/wio-graph — Requirement: ValidatedGraph is proof-carrying and cannot be constructed without successful compile
  */
opaque type ValidatedGraph[In, Out] = Graph[In, Out]

object ValidatedGraph:
  /** Construct a ValidatedGraph from a Graph, validating first.
    *
    * Returns `Right(ValidatedGraph)` if the graph is valid,
    * `Left(NonEmptyChain[AdkError])` if validation fails.
    *
    * spec: add-iron-refined-types/wio-graph — Scenario: Valid graph yields ValidatedGraph
    */
  def from[In, Out](graph: Graph[In, Out]): Either[ValidatedNec[AdkError, Unit], ValidatedGraph[In, Out]] =
    graph.validateGraph match
      case Validated.Invalid(errors) => Left(Validated.Invalid(errors))
      case Validated.Valid(_)        => Right(graph)

  /** Extract the underlying Graph from a ValidatedGraph. */
  extension [In, Out] (vg: ValidatedGraph[In, Out])
    def graph: Graph[In, Out] = vg
