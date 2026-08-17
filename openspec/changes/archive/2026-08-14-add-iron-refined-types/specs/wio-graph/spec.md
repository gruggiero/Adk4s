# Spec: wio-graph (Iron refined types)

<!-- Delta spec for the add-iron-refined-types change. Introduces
     ValidatedGraph (proof-carrying refined type), replaces the generic
     Exception in GraphExecutor with GraphCompilationError, and deletes
     the dead commented-out throw blocks. -->

## Concepts Used (behavioral)

| Concept | Role here | File |
|---------|-----------|------|
| [WIOGraph](../../../../concepts/wio-graph.md) | GraphExecutor error path typed; ValidatedGraph introduced; dead code deleted | `openspec/concepts/wio-graph.md` |
| [Graph](../../../../concepts/graph.md) | Graph.compile feeds ValidatedGraph | `openspec/concepts/graph.md` |

Updating the `wio-graph` and `graph` concept files' descriptions is PART of
implementing this spec (apply Step 12).

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `GraphExecutor` | object | `org.adk4s.orchestration.execution` |
| `Graph` | case class | `org.adk4s.orchestration.graph` |
| `AdkError` (validation errors) | sealed trait | `org.adk4s.core.error` |
| `GraphCompiledError` | case class (AdkError) | `org.adk4s.core.error` |
| `GraphEntryMissingError` | case class (AdkError) | `org.adk4s.core.error` |
| `GraphEndNodesMissingError` | case class (AdkError) | `org.adk4s.core.error` |
| `GraphCompilationError` | case class (AdkError) | `org.adk4s.core.error` (introduced by error-hierarchy-dedup spec) |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `ValidatedGraph` | refined type alias `Graph :| GraphValidated` | Proof-carrying graph that has passed `graph.compile`; consumed by `executeGraph`/`executeGraphParallel` so they receive a graph already proven valid. |

## ADDED Requirements

### Requirement: GraphExecutor raises GraphCompilationError instead of generic Exception

The system SHALL replace the `IO.raiseError(new Exception("Graph validation
failed: …"))` in `GraphExecutor.execute` and `GraphExecutor.executeWithError`
with `IO.raiseError(GraphCompilationError(errors))`, so that graph-compilation
failures surface as a typed `AdkError` variant that callers can match
explicitly.

**Given** a graph that fails `graph.compile`
**When** `GraphExecutor.execute` or `executeWithError` handles the failure
**Then** the raised error is a `GraphCompilationError` whose `errors` field
contains every `AdkError` validation error from compilation

**Rationale**: The generic `Exception` is unmatchable as an `AdkError`; the
typed variant integrates with the project's sealed error hierarchy.

#### Scenario: Failed compile raises GraphCompilationError

**Given** a graph with a missing entry node
**When** `GraphExecutor.execute(graph, input)` is called
**Then** the resulting `IO` fails with `GraphCompilationError` (not
`Exception`) and the error's `errors` list contains the entry-missing
validation error

#### Scenario: Successful compile does not raise

**Given** a valid graph that passes `graph.compile`
**When** `GraphExecutor.execute(graph, input)` is called
**Then** no `GraphCompilationError` is raised and execution proceeds

#### Scenario: Caller can pattern-match GraphCompilationError as AdkError

**Given** an `IO` that fails with `GraphCompilationError`
**When** the error is matched against `AdkError`
**Then** it matches the `GraphCompilationError` variant and its `errors` list
is accessible

### Requirement: ValidatedGraph is proof-carrying and cannot be constructed without successful compile

The system SHALL introduce a `ValidatedGraph` refined type
(`Graph :| GraphValidated`) that can only be constructed from a `Graph` that
has passed `graph.compile`, so that `executeGraph` and `executeGraphParallel`
receive a graph already proven valid and do not re-validate.

**Given** a `Graph` that fails `graph.compile`
**When** an attempt is made to construct a `ValidatedGraph` from it
**Then** the construction fails (returns `Left` with the validation errors)

**Given** a `Graph` that passes `graph.compile`
**When** a `ValidatedGraph` is constructed from it
**Then** the `ValidatedGraph` carries the proven-valid graph and can be passed
to `executeGraph` without re-validation

**Rationale**: Eliminates redundant re-validation in the execution path and
makes the "graph is valid" invariant a type.

#### Scenario: Valid graph yields ValidatedGraph

**Given** a graph with a valid entry node, edges, and end nodes
**When** `graph.validate` is called and succeeds
**Then** a `ValidatedGraph` is constructed and `executeGraph` accepts it

#### Scenario: Invalid graph cannot yield ValidatedGraph

**Given** a graph with a cycle
**When** `graph.validate` is called
**Then** it fails and no `ValidatedGraph` can be constructed

#### Scenario: executeGraph does not re-validate a ValidatedGraph

**Given** a `ValidatedGraph`
**When** `executeGraph` is called with it
**Then** no second `graph.compile` call is made (the proof is trusted)

### Requirement: Dead commented-out throw blocks are deleted

The system SHALL delete the commented-out `toWIO` and
`compileFromNodeUnsafe` blocks in `GraphExecutor.scala` (the code between
the `/*` … `*/` comment delimiters containing `throw new
IllegalStateException` and `throw new IllegalArgumentException` calls), as
they are dead code that contradicts the no-raw-throw discipline.

**Given** the graph executor source file after migration
**When** it is inspected
**Then** no commented-out code blocks containing `throw new` remain

**Rationale**: Dead code containing raw throws is a liability; it is not part
of any active path and its presence contradicts the project's
no-raw-throw convention enforced by WartRemover and Scalafix.

#### Scenario: No commented-out throw blocks remain

**Given** the migrated graph executor source
**When** the file is searched for `throw new` inside comments
**Then** zero matches are found

## Properties (Ring 3)

### Property: ValidatedGraph construction matches graph.compile success

**Invariant**: For every `Graph` `g`, `ValidatedGraph.from(g).isDefined ==
g.compile(GraphConfig()).isValid`.

**Generator strategy**: `GraphGen` (constructive — builds graphs from node/edge lists; covers valid graphs, missing-entry graphs, dead-end graphs, cycle graphs) — uses existing test graph builders if present, else constructs from `GraphNode`/edge lists.

```
forAll { (g: Graph) =>
  ValidatedGraph.from(g).isDefined == g.compile(GraphConfig()).isValid
}
```

### Property: GraphCompilationError carries all validation errors

**Invariant**: For every graph `g` that fails `graph.compile` with errors
`es`, `GraphExecutor.execute(g, input)` fails with a
`GraphCompilationError` whose `errors == es`.

**Generator strategy**: `GraphGen.filter(_.compile(GraphConfig()).isInvalid)` — filtered (invalid graphs only); covers each validation-error class.

```
forAll { (g: Graph) =>
  g.compile(GraphConfig()) match
    case Invalid(es) =>
      val err: GraphCompilationError = GraphExecutor.execute(g, input).attempt.unsafeRunSync().left.get
      err.errors == es.toList
    case Valid(_) => true
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `ValidatedGraph` constructed directly from a raw `Graph` without `validate` | The proof must come from a successful compile | `assertDoesNotCompile("val v: ValidatedGraph = myGraph")` (opaque/refined boundary) |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| GraphCompilationError raised on failed compile | Requirement: GraphExecutor raises GraphCompilationError instead of generic Exception + Scenario: Failed compile raises GraphCompilationError | scenario test + property test | `GraphExecutorSpec` (adk4s-orchestration/test) |
| Successful compile does not raise | Requirement: GraphExecutor raises GraphCompilationError instead of generic Exception + Scenario: Successful compile does not raise | scenario test | `GraphExecutorSpec` |
| GraphCompilationError matchable as AdkError | Requirement: GraphExecutor raises GraphCompilationError instead of generic Exception + Scenario: Caller can pattern-match GraphCompilationError as AdkError | type system + scenario test | `GraphExecutorSpec` |
| ValidatedGraph requires successful compile | Requirement: ValidatedGraph is proof-carrying and cannot be constructed without successful compile + Scenario: Invalid graph cannot yield ValidatedGraph + Compile-Negative | type system + compile-negative test + property test | `GraphExecutorTypeContract` (adk4s-orchestration/test) |
| executeGraph does not re-validate | Requirement: ValidatedGraph is proof-carrying and cannot be constructed without successful compile + Scenario: executeGraph does not re-validate a ValidatedGraph | scenario test (observe no second compile call) | `GraphExecutorSpec` |
| Dead throw blocks deleted | Requirement: Dead commented-out throw blocks are deleted + Scenario: No commented-out throw blocks remain | static check (grep/scan) | `GraphExecutorSpec` or `danger-scan.sh` |
| ValidatedGraph matches compile success | Property: ValidatedGraph construction matches graph.compile success | Hedgehog property | `GraphExecutorSpec` |
| GraphCompilationError carries all errors | Property: GraphCompilationError carries all validation errors | Hedgehog property | `GraphExecutorSpec` |
| No silent fallback in ValidatedGraph construction | Requirement: ValidatedGraph is proof-carrying and cannot be constructed without successful compile (adversarial: cannot be constructed without successful compile) | Ring 8 adversarial review | review + `GraphExecutorTypeContract` |

## Implementation Anchors

| Anchor | Kind | Where | Note |
|--------|------|-------|------|
| `GraphExecutor.execute` / `executeWithError` | methods | `org.adk4s.orchestration.execution` (adk4s-orchestration) | `IO.raiseError(new Exception(…))` → `IO.raiseError(GraphCompilationError(errors))` |
| `GraphExecutor.executeGraph` / `executeGraphParallel` | private methods | `org.adk4s.orchestration.execution` (adk4s-orchestration) | accept `ValidatedGraph`; no re-validation |
| `ValidatedGraph` | refined type alias | `org.adk4s.orchestration.graph` or `.execution` (adk4s-orchestration) | new; `Graph :| GraphValidated`; constructed via `ValidatedGraph.from(g)` after `g.compile` succeeds |
| `Graph.compile` | method | `org.adk4s.orchestration.graph` (adk4s-orchestration) | existing; feeds `ValidatedGraph` |
| `GraphCompilationError` | case class (AdkError) | `org.adk4s.core.error` (adk4s-core) | introduced by error-hierarchy-dedup spec |
| Dead commented blocks (lines 22-36, 70-162) | dead code | `GraphExecutor.scala` (adk4s-orchestration) | deleted |
| `sbt adk4s-orchestration/test` | build step | adk4s-orchestration | `GraphExecutorSpec` runs |
