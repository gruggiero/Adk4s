# Feature 06: State Management

## Overview

This document details the implementation of state management for ADK4S, providing both lightweight Ref-based state and event-sourced state via Workflows4s.

## Prerequisites

- **Feature 01**: Core Types & Schema System
- **Feature 04**: Lambda & Runnable

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Cats Effect | Ref, concurrent state | 3.6.3 |
| Workflows4s | Event sourcing, WIO | Local |

## Design Philosophy

ADK4S provides two state management approaches:

1. **Ref-based State** - Lightweight, in-memory, for simple workflows
2. **Event-sourced State** - Durable, auditable, for production via Workflows4s

## Implementation Tasks

### Task 1: Create StateRef Wrapper

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateRef.scala`

**Purpose**: Type-safe wrapper around Cats Effect Ref for graph state

**API Design**:
```scala
package org.adk4s.orchestration.state

import cats.effect.{IO, Ref}
import cats.syntax.all.*

/**
 * Type-safe state reference for graph execution.
 */
trait StateRef[F[_], S]:
  def get: F[S]
  def set(s: S): F[Unit]
  def update(f: S => S): F[Unit]
  def modify[A](f: S => (S, A)): F[A]
  def getAndUpdate(f: S => S): F[S]
  def updateAndGet(f: S => S): F[S]

object StateRef:
  /**
   * Create StateRef from initial state.
   */
  def of[S](initial: S): IO[StateRef[IO, S]] =
    Ref.of[IO, S](initial).map(fromRef)

  /**
   * Create StateRef from existing Ref.
   */
  def fromRef[S](ref: Ref[IO, S]): StateRef[IO, S] = new StateRef[IO, S]:
    def get: IO[S] = ref.get
    def set(s: S): IO[Unit] = ref.set(s)
    def update(f: S => S): IO[Unit] = ref.update(f)
    def modify[A](f: S => (S, A)): IO[A] = ref.modify(f)
    def getAndUpdate(f: S => S): IO[S] = ref.getAndUpdate(f)
    def updateAndGet(f: S => S): IO[S] = ref.updateAndGet(f)

  /**
   * Create no-op StateRef (for stateless execution).
   */
  def empty[S](default: S): StateRef[IO, S] = new StateRef[IO, S]:
    def get: IO[S] = IO.pure(default)
    def set(s: S): IO[Unit] = IO.unit
    def update(f: S => S): IO[Unit] = IO.unit
    def modify[A](f: S => (S, A)): IO[A] = IO.pure(f(default)._2)
    def getAndUpdate(f: S => S): IO[S] = IO.pure(default)
    def updateAndGet(f: S => S): IO[S] = IO.pure(f(default))
```

---

### Task 2: Create Pre/Post Handlers

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StateHandlers.scala`

**Purpose**: Define state handlers that run before/after node execution

**API Design**:
```scala
package org.adk4s.orchestration.state

import fs2.Stream
import cats.effect.IO

/**
 * Pre-handler runs before node execution, can modify input based on state.
 */
type PreHandler[I, S] = (I, StateRef[IO, S]) => IO[I]

/**
 * Post-handler runs after node execution, can modify output and update state.
 */
type PostHandler[O, S] = (O, StateRef[IO, S]) => IO[O]

/**
 * Stream pre-handler for streaming nodes.
 */
type StreamPreHandler[I, S] = (Stream[IO, I], StateRef[IO, S]) => Stream[IO, I]

/**
 * Stream post-handler for streaming nodes.
 */
type StreamPostHandler[O, S] = (Stream[IO, O], StateRef[IO, S]) => Stream[IO, O]

object StateHandlers:
  /**
   * Identity pre-handler (no modification).
   */
  def identityPre[I, S]: PreHandler[I, S] = (i, _) => IO.pure(i)

  /**
   * Identity post-handler (no modification).
   */
  def identityPost[O, S]: PostHandler[O, S] = (o, _) => IO.pure(o)

  /**
   * Pre-handler that accumulates input into state.
   */
  def accumulate[I, S](lens: S => List[I], update: (S, List[I]) => S): PreHandler[I, S] =
    (input, stateRef) =>
      stateRef.modify { s =>
        val accumulated = lens(s) :+ input
        (update(s, accumulated), input)
      }

  /**
   * Pre-handler that returns accumulated state as input.
   */
  def fromState[I, S](lens: S => I): PreHandler[I, S] =
    (_, stateRef) => stateRef.get.map(lens)

  /**
   * Post-handler that stores output in state.
   */
  def storeOutput[O, S](update: (S, O) => S): PostHandler[O, S] =
    (output, stateRef) =>
      stateRef.update(s => update(s, output)).as(output)

  /**
   * Combine pre-handlers.
   */
  def combinePre[I, S](handlers: PreHandler[I, S]*): PreHandler[I, S] =
    (input, stateRef) =>
      handlers.foldLeftM(input)((i, h) => h(i, stateRef))

  /**
   * Combine post-handlers.
   */
  def combinePost[O, S](handlers: PostHandler[O, S]*): PostHandler[O, S] =
    (output, stateRef) =>
      handlers.foldLeftM(output)((o, h) => h(o, stateRef))
```

---

### Task 3: Create Workflows4s Integration

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/EventSourcedState.scala`

**Purpose**: Integrate with Workflows4s for event-sourced state

**API Design**:
```scala
package org.adk4s.orchestration.state

import cats.effect.IO
import workflows4s.wio.{WIO, WorkflowContext}

/**
 * Base trait for ADK4S workflow contexts.
 */
trait AdkWorkflowContext extends WorkflowContext:
  /**
   * Create initial state.
   */
  def initialState: State

  /**
   * Apply event to state.
   */
  def applyEvent(state: State, event: Event): State

/**
 * Agent state context for ReAct-style agents.
 */
object AgentStateContext extends AdkWorkflowContext:
  case class AgentState(
    messages: List[org.adk4s.structured.core.Message] = Nil,
    stepCount: Int = 0,
    toolCallCount: Int = 0,
    metadata: Map[String, String] = Map.empty
  )

  sealed trait AgentEvent
  case class MessageAdded(message: org.adk4s.structured.core.Message) extends AgentEvent
  case class StepCompleted(stepNumber: Int) extends AgentEvent
  case class ToolCalled(toolName: String) extends AgentEvent
  case class MetadataUpdated(key: String, value: String) extends AgentEvent

  override type State = AgentState
  override type Event = AgentEvent

  def initialState: AgentState = AgentState()

  def applyEvent(state: AgentState, event: AgentEvent): AgentState =
    event match
      case MessageAdded(msg) =>
        state.copy(messages = state.messages :+ msg)
      case StepCompleted(step) =>
        state.copy(stepCount = step)
      case ToolCalled(_) =>
        state.copy(toolCallCount = state.toolCallCount + 1)
      case MetadataUpdated(key, value) =>
        state.copy(metadata = state.metadata + (key -> value))

/**
 * Helper to create WIO steps with event emission.
 */
object EventSourcedOps:
  /**
   * Create WIO step that emits an event.
   */
  def emitEvent[E, Ctx <: WorkflowContext { type Event >: E }](
    event: E
  ): WIO[Any, Nothing, Unit, Ctx] =
    WIO.noop[Ctx].emitEvent(_ => event)

  /**
   * Run IO and emit event based on result.
   */
  def runAndEmit[I, O, E, Ctx <: WorkflowContext { type Event >: E }](
    run: I => IO[O],
    toEvent: O => E
  ): WIO[I, Nothing, O, Ctx] =
    WIO.runIO[I, Nothing, O, Ctx](run).emitEvent((_, o) => toEvent(o))
```

---

### Task 4: Create State-Aware Node Wrapper

**Location**: `adk4s-orchestration/src/main/scala/org/adk4s/orchestration/state/StatefulNode.scala`

**Purpose**: Wrap nodes with state handling capabilities

**API Design**:
```scala
package org.adk4s.orchestration.state

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.runnable.Runnable

/**
 * Configuration for stateful node.
 */
case class StatefulNodeConfig[I, O, S](
  preHandler: Option[PreHandler[I, S]] = None,
  postHandler: Option[PostHandler[O, S]] = None,
  streamPreHandler: Option[StreamPreHandler[I, S]] = None,
  streamPostHandler: Option[StreamPostHandler[O, S]] = None
)

/**
 * Wrap a Runnable with state handling.
 */
class StatefulNode[I, O, S](
  inner: Runnable[I, O],
  stateRef: StateRef[IO, S],
  config: StatefulNodeConfig[I, O, S]
) extends Runnable[I, O]:

  def invoke(input: I): IO[O] =
    for
      processedInput <- config.preHandler.fold(IO.pure(input))(_(input, stateRef))
      output <- inner.invoke(processedInput)
      processedOutput <- config.postHandler.fold(IO.pure(output))(_(output, stateRef))
    yield processedOutput

  def stream(input: I): Stream[IO, O] =
    val inputStream = Stream.eval(
      config.preHandler.fold(IO.pure(input))(_(input, stateRef))
    )
    val outputStream = inputStream.flatMap(inner.stream)
    config.streamPostHandler.fold(outputStream)(_(outputStream, stateRef))

  def collect(input: Stream[IO, I]): IO[O] =
    val processedInput = config.streamPreHandler.fold(input)(_(input, stateRef))
    for
      output <- inner.collect(processedInput)
      processedOutput <- config.postHandler.fold(IO.pure(output))(_(output, stateRef))
    yield processedOutput

  def transform(input: Stream[IO, I]): Stream[IO, O] =
    val processedInput = config.streamPreHandler.fold(input)(_(input, stateRef))
    val outputStream = inner.transform(processedInput)
    config.streamPostHandler.fold(outputStream)(_(outputStream, stateRef))

object StatefulNode:
  /**
   * Wrap runnable with state handling.
   */
  def wrap[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    config: StatefulNodeConfig[I, O, S]
  ): StatefulNode[I, O, S] =
    new StatefulNode(runnable, stateRef, config)

  /**
   * Wrap with just pre-handler.
   */
  def withPre[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    preHandler: PreHandler[I, S]
  ): StatefulNode[I, O, S] =
    wrap(runnable, stateRef, StatefulNodeConfig(preHandler = Some(preHandler)))

  /**
   * Wrap with just post-handler.
   */
  def withPost[I, O, S](
    runnable: Runnable[I, O],
    stateRef: StateRef[IO, S],
    postHandler: PostHandler[O, S]
  ): StatefulNode[I, O, S] =
    wrap(runnable, stateRef, StatefulNodeConfig(postHandler = Some(postHandler)))
```

---

## File Structure

```
adk4s-orchestration/
└── src/
    ├── main/
    │   └── scala/
    │       └── org/
    │           └── adk4s/
    │               └── orchestration/
    │                   └── state/
    │                       ├── package.scala           # Exports
    │                       ├── StateRef.scala          # Ref wrapper
    │                       ├── StateHandlers.scala     # Pre/post handlers
    │                       ├── EventSourcedState.scala # Workflows4s integration
    │                       └── StatefulNode.scala      # State-aware nodes
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── orchestration/
                        └── state/
                            └── ...tests...
```

## Testing Plan

1. **StateRef Tests** - Test all operations
2. **StateHandlers Tests** - Test accumulate, fromState, storeOutput
3. **StatefulNode Tests** - Test pre/post handler execution
4. **Workflows4s Integration Tests** - Test event emission and state reconstruction

## Completion Criteria

- [ ] StateRef wrapper implemented
- [ ] Pre/Post handlers implemented
- [ ] Workflows4s context integration complete
- [ ] StatefulNode wrapper working
- [ ] Unit tests passing
- [ ] Documentation updated
