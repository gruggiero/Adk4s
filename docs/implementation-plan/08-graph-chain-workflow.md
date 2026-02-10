# Feature 08: Graph/Chain/Workflow Builders

## Overview

This document details the implementation of graph, chain, and workflow builders for ADK4S, providing three levels of abstraction for composing nodes into executable pipelines.

## Prerequisites

- **Feature 01-07**: All previous features

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Workflows4s | WIO for execution | Local |
| Cats Effect | Effects | 3.6.3 |

## Design Philosophy

Three builder types serve different use cases:
- **Chain**: Linear pipelines (A -> B -> C)
- **Graph**: Complex DAGs with branching
- **Workflow**: Field-level data mapping between nodes

## Implementation Tasks

### Task 1: Create Chain Builder

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/chain/Chain.scala`

**API Design**:
```scala
package org.adk4s.orchestration.chain

import cats.effect.IO
import org.adk4s.core.runnable.{Runnable, Lambda}
import org.adk4s.core.component.ChatModel
import org.adk4s.orchestration.branch.Branch

/**
 * Chain builds linear pipelines of operations.
 */
case class Chain[I, O] private (
  private val steps: Vector[ChainStep[?, ?]]
):
  /**
   * Append a lambda to the chain.
   */
  def appendLambda[O2](lambda: Lambda[O, O2]): Chain[I, O2] =
    Chain(steps :+ ChainStep.LambdaStep(lambda))

  /**
   * Append a chat model to the chain.
   */
  def appendChatModel(model: ChatModel[IO]): Chain[I, Completion] =
    Chain(steps :+ ChainStep.ChatModelStep(model))

  /**
   * Append a branch for conditional routing.
   */
  def appendBranch[O2](branch: ChainBranch[O, O2]): Chain[I, O2] =
    Chain(steps :+ ChainStep.BranchStep(branch))

  /**
   * Pass through input unchanged.
   */
  def appendPassthrough: Chain[I, O] = this

  /**
   * Compile to Runnable.
   */
  def compile: IO[Runnable[I, O]] = ???

object Chain:
  def apply[I, O]: Chain[I, O] = Chain(Vector.empty)

sealed trait ChainStep[I, O]
object ChainStep:
  case class LambdaStep[I, O](lambda: Lambda[I, O]) extends ChainStep[I, O]
  case class ChatModelStep(model: ChatModel[IO]) extends ChainStep[Any, Completion]
  case class BranchStep[I, O](branch: ChainBranch[I, O]) extends ChainStep[I, O]
```

---

### Task 2: Create Graph Builder

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/graph/Graph.scala`

**API Design**:
```scala
package org.adk4s.orchestration.graph

import cats.effect.IO
import cats.data.ValidatedNec
import org.adk4s.core.types.NodeKey
import org.adk4s.core.runnable.{Runnable, Lambda}
import org.adk4s.orchestration.state.StateRef
import org.adk4s.orchestration.branch.Branch
import org.adk4s.core.error.{AdkError, GraphCompiledError}

/**
 * Graph configuration.
 */
case class GraphConfig(
  maxRunSteps: Int = 100,
  graphName: Option[String] = None
)

/**
 * Graph builds DAG-based pipelines with branching.
 */
case class Graph[I, O, S] private (
  private val nodes: Map[NodeKey, GraphNode[?, ?]],
  private val edges: Map[NodeKey, Set[NodeKey]],
  private val branches: Map[NodeKey, Branch[?]],
  private val stateGen: Option[IO[StateRef[IO, S]]],
  private val compiled: Boolean = false
):
  /**
   * Add a lambda node.
   */
  def addLambdaNode[A, B](key: String, lambda: Lambda[A, B]): ValidatedNec[AdkError, Graph[I, O, S]] =
    addNode(NodeKey.unsafeApply(key), GraphNode.LambdaNode(lambda))

  /**
   * Add an edge between nodes.
   */
  def addEdge(from: String, to: String): ValidatedNec[AdkError, Graph[I, O, S]] =
    addEdge(NodeKey.unsafeApply(from), NodeKey.unsafeApply(to))

  /**
   * Add a branch after a node.
   */
  def addBranch[A](from: String, branch: Branch[A]): ValidatedNec[AdkError, Graph[I, O, S]] =
    addBranchInternal(NodeKey.unsafeApply(from), branch)

  /**
   * Compile the graph for execution.
   */
  def compile(config: GraphConfig = GraphConfig()): ValidatedNec[AdkError, IO[Runnable[I, O]]] =
    validate.map(_ => buildRunnable(config))

  private def validate: ValidatedNec[AdkError, Unit] = ???
  private def buildRunnable(config: GraphConfig): IO[Runnable[I, O]] = ???
  private def addNode[A, B](key: NodeKey, node: GraphNode[A, B]): ValidatedNec[AdkError, Graph[I, O, S]] = ???
  private def addEdge(from: NodeKey, to: NodeKey): ValidatedNec[AdkError, Graph[I, O, S]] = ???
  private def addBranchInternal[A](from: NodeKey, branch: Branch[A]): ValidatedNec[AdkError, Graph[I, O, S]] = ???

object Graph:
  def apply[I, O]: Graph[I, O, Unit] =
    Graph(Map.empty, Map.empty, Map.empty, None, false)

  def withState[I, O, S](stateGen: IO[StateRef[IO, S]]): Graph[I, O, S] =
    Graph(Map.empty, Map.empty, Map.empty, Some(stateGen), false)

sealed trait GraphNode[I, O]
object GraphNode:
  case class LambdaNode[I, O](lambda: Lambda[I, O]) extends GraphNode[I, O]
  case class ChatModelNode(model: ChatModel[IO]) extends GraphNode[Conversation, Completion]
  case class ToolsNode(tools: org.adk4s.agent.tools.ToolsNode) extends GraphNode[List[ToolCall], List[ToolMessage]]
  case class SubGraphNode[I, O, S](graph: Graph[I, O, S]) extends GraphNode[I, O]
```

---

### Task 3: Create Workflow Builder (with Field Mapping)

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/workflow/Workflow.scala`

**API Design**:
```scala
package org.adk4s.orchestration.workflow

import cats.effect.IO
import org.adk4s.core.types.{NodeKey, FieldPath}
import org.adk4s.core.runnable.Lambda

/**
 * Field mapping between nodes.
 */
case class FieldMapping(
  from: FieldPath,
  to: FieldPath,
  fromNode: Option[NodeKey] = None
)

object FieldMapping:
  def apply(from: String, to: String): FieldMapping =
    FieldMapping(FieldPath(from), FieldPath(to))

/**
 * Workflow with field-level data mapping.
 */
case class Workflow[I, O] private (
  private val nodes: Map[NodeKey, WorkflowNode[?, ?]],
  private val inputs: Map[NodeKey, List[(NodeKey, FieldMapping)]],
  private val endNode: Option[NodeKey]
):
  /**
   * Add a lambda node.
   */
  def addLambdaNode[A, B](key: String, lambda: Lambda[A, B]): WorkflowNodeBuilder[I, O, A, B] =
    WorkflowNodeBuilder(this, NodeKey.unsafeApply(key), WorkflowNode.Lambda(lambda))

  /**
   * Set end node.
   */
  def end: WorkflowEndBuilder[I, O] =
    WorkflowEndBuilder(this)

  /**
   * Compile to Runnable.
   */
  def compile: IO[Runnable[I, O]] = ???

object Workflow:
  def apply[I, O]: Workflow[I, O] = Workflow(Map.empty, Map.empty, None)

/**
 * Builder for adding inputs to a node.
 */
case class WorkflowNodeBuilder[I, O, A, B](
  workflow: Workflow[I, O],
  key: NodeKey,
  node: WorkflowNode[A, B]
):
  def addInput(from: String): WorkflowNodeBuilder[I, O, A, B] =
    addInput(NodeKey.unsafeApply(from), FieldMapping(FieldPath.Root, FieldPath.Root))

  def addInput(from: String, mapping: FieldMapping): WorkflowNodeBuilder[I, O, A, B] =
    addInput(NodeKey.unsafeApply(from), mapping)

  private def addInput(from: NodeKey, mapping: FieldMapping): WorkflowNodeBuilder[I, O, A, B] = ???

sealed trait WorkflowNode[I, O]
object WorkflowNode:
  case class Lambda[I, O](lambda: org.adk4s.core.runnable.Lambda[I, O]) extends WorkflowNode[I, O]
  case class ChatModel(model: org.adk4s.core.component.ChatModel[IO]) extends WorkflowNode[Conversation, Completion]
```

---

### Task 4: Create Workflows4s WIO-based Execution

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/execution/WIOExecutor.scala`

**API Design**:
```scala
package org.adk4s.orchestration.execution

import cats.effect.IO
import workflows4s.wio.{WIO, WorkflowContext}
import org.adk4s.orchestration.graph.Graph

/**
 * Execute Graph using Workflows4s WIO.
 */
object WIOExecutor:
  /**
   * Convert Graph to WIO for execution.
   */
  def toWIO[I, O, Ctx <: WorkflowContext](
    graph: Graph[I, O, ?]
  ): WIO[I, Nothing, O, Ctx] = ???

  /**
   * Execute graph with Workflows4s runtime.
   */
  def execute[I, O](graph: Graph[I, O, ?], input: I): IO[O] = ???
```

---

## File Structure

```
adk4s-orchestration/
└── src/main/scala/org/adk4s/orchestration/
    ├── chain/
    │   ├── Chain.scala
    │   └── ChainBranch.scala
    ├── graph/
    │   ├── Graph.scala
    │   ├── GraphNode.scala
    │   └── GraphValidation.scala
    ├── workflow/
    │   ├── Workflow.scala
    │   ├── FieldMapping.scala
    │   └── WorkflowNode.scala
    └── execution/
        └── WIOExecutor.scala
```

## Testing Plan

1. Test Chain linear composition
2. Test Graph DAG validation
3. Test Graph branching execution
4. Test Workflow field mapping
5. Test WIO integration

## Completion Criteria

- [ ] Chain builder complete
- [ ] Graph builder with validation
- [ ] Workflow with field mapping
- [ ] Workflows4s execution integration
- [ ] Unit tests passing
