# Feature 07: Branching & Routing

## Overview

This document details the implementation of branching and routing patterns for ADK4S, enabling conditional execution paths in graphs based on runtime conditions.

## Prerequisites

- **Feature 01**: Core Types & Schema System
- **Feature 02**: Streaming Integration
- **Feature 06**: State Management

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Cats Effect | IO | 3.6.3 |
| Workflows4s | WIO.fork | Local |

## Implementation Tasks

### Task 1: Create Branch Types

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Branch.scala`

**API Design**:
```scala
package org.adk4s.orchestration.branch

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.types.NodeKey

/**
 * Branch determines which node to route to based on input.
 */
sealed trait Branch[I]:
  def endNodes: Set[NodeKey]

/**
 * Invoke-based branch condition.
 */
case class InvokeBranch[I](
  condition: I => IO[NodeKey],
  endNodes: Set[NodeKey]
) extends Branch[I]

/**
 * Stream-based branch condition.
 */
case class StreamBranch[I](
  condition: Stream[IO, I] => IO[NodeKey],
  endNodes: Set[NodeKey]
) extends Branch[I]

object Branch:
  /**
   * Create invoke branch.
   */
  def apply[I](condition: I => IO[NodeKey], targets: Set[NodeKey]): Branch[I] =
    InvokeBranch(condition, targets)

  /**
   * Create branch from pure function.
   */
  def pure[I](condition: I => NodeKey, targets: Set[NodeKey]): Branch[I] =
    InvokeBranch(i => IO.pure(condition(i)), targets)

  /**
   * Create stream branch.
   */
  def stream[I](condition: Stream[IO, I] => IO[NodeKey], targets: Set[NodeKey]): Branch[I] =
    StreamBranch(condition, targets)

  /**
   * Binary branch (if/else).
   */
  def binary[I](
    predicate: I => IO[Boolean],
    ifTrue: NodeKey,
    ifFalse: NodeKey
  ): Branch[I] =
    InvokeBranch(
      i => predicate(i).map(if _ then ifTrue else ifFalse),
      Set(ifTrue, ifFalse)
    )

  /**
   * Branch to END if condition is true.
   */
  def endIf[I](predicate: I => IO[Boolean], otherwise: NodeKey): Branch[I] =
    binary(predicate, NodeKey.END, otherwise)
```

---

### Task 2: Create Router

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/Router.scala`

**API Design**:
```scala
package org.adk4s.orchestration.branch

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.types.NodeKey
import org.adk4s.core.runnable.Runnable

/**
 * Router manages multiple branches and determines routing.
 */
case class Router[I](branches: Map[NodeKey, Branch[I]]):
  /**
   * Determine next node for given input from a source node.
   */
  def route(fromNode: NodeKey, input: I): IO[NodeKey] =
    branches.get(fromNode) match
      case Some(InvokeBranch(condition, _)) =>
        condition(input)
      case Some(StreamBranch(_, _)) =>
        IO.raiseError(new IllegalStateException("Cannot use invoke routing with stream branch"))
      case None =>
        IO.raiseError(new IllegalStateException(s"No branch defined for node ${fromNode.value}"))

  /**
   * Determine next node for streaming input.
   */
  def routeStream(fromNode: NodeKey, input: Stream[IO, I]): IO[NodeKey] =
    branches.get(fromNode) match
      case Some(StreamBranch(condition, _)) =>
        condition(input)
      case Some(InvokeBranch(condition, _)) =>
        // Fall back to invoke with last element
        input.compile.lastOrError.flatMap(condition)
      case None =>
        IO.raiseError(new IllegalStateException(s"No branch defined for node ${fromNode.value}"))

  /**
   * Add a branch.
   */
  def addBranch(fromNode: NodeKey, branch: Branch[I]): Router[I] =
    copy(branches = branches + (fromNode -> branch))

object Router:
  def empty[I]: Router[I] = Router(Map.empty)
```

---

### Task 3: Create Workflows4s Branch Integration

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/branch/WIOBranch.scala`

**API Design**:
```scala
package org.adk4s.orchestration.branch

import cats.effect.IO
import workflows4s.wio.{WIO, WorkflowContext}

/**
 * Integration with Workflows4s WIO branching.
 */
object WIOBranch:
  /**
   * Binary fork in WIO.
   */
  def fork[I, O, Ctx <: WorkflowContext](
    condition: I => Boolean,
    ifTrue: WIO[I, Nothing, O, Ctx],
    ifFalse: WIO[I, Nothing, O, Ctx]
  ): WIO[I, Nothing, O, Ctx] =
    WIO.runIO[I, Nothing, Boolean, Ctx](i => IO.pure(condition(i)))
      .flatMap { result =>
        if result then ifTrue else ifFalse
      }

  /**
   * Multi-way branch in WIO.
   */
  def branch[I, O, K, Ctx <: WorkflowContext](
    selector: I => K,
    branches: Map[K, WIO[I, Nothing, O, Ctx]],
    default: WIO[I, Nothing, O, Ctx]
  ): WIO[I, Nothing, O, Ctx] =
    WIO.runIO[I, Nothing, K, Ctx](i => IO.pure(selector(i)))
      .flatMap { key =>
        branches.getOrElse(key, default)
      }

  /**
   * Branch to end if condition met.
   */
  def endIf[I, O, Ctx <: WorkflowContext](
    condition: I => Boolean,
    continueWith: WIO[I, Nothing, O, Ctx],
    endValue: O
  ): WIO[I, Nothing, O, Ctx] =
    fork(condition, WIO.pure(_ => endValue), continueWith)
```

---

## File Structure

```
adk4s-orchestration/
└── src/main/scala/org/adk4s/orchestration/branch/
    ├── package.scala
    ├── Branch.scala
    ├── Router.scala
    └── WIOBranch.scala
```

## Testing Plan

1. Test InvokeBranch routing
2. Test StreamBranch routing
3. Test binary branching
4. Test WIO fork integration

## Completion Criteria

- [ ] Branch ADT implemented
- [ ] Router logic complete
- [ ] Workflows4s integration done
- [ ] Unit tests passing
