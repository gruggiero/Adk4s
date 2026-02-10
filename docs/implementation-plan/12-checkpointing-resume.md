# Feature 12: Checkpointing & Resume

## Overview

This document details the implementation of checkpointing and resume capabilities for ADK4S, enabling durable workflow execution that can survive failures and resume from saved state.

## Prerequisites

- **Feature 06**: State Management
- **Feature 08**: Graph/Chain/Workflow
- **Feature 09**: ReAct Agent

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Workflows4s | Event sourcing | Local |
| Cats Effect | IO | 3.6.3 |
| Circe | JSON serialization | 0.14.x |

## Design Philosophy

ADK4S checkpointing leverages Workflows4s event sourcing:
1. **Event-driven state** - State reconstructed from events
2. **Automatic checkpointing** - Via Workflows4s runtime
3. **Serializable state** - JSON encoding for persistence
4. **Resume from anywhere** - Continue from last event

## Implementation Tasks

### Task 1: Create Checkpoint Types

**Location**: `adk4s-persistence/src/main/scala/org/adk4s/persistence/Checkpoint.scala`

**API Design**:
```scala
package org.adk4s.persistence

import io.circe.{Json, Encoder, Decoder}
import java.time.Instant

/**
 * Checkpoint represents a saved execution state.
 */
case class Checkpoint(
  id: String,
  workflowId: String,
  timestamp: Instant,
  state: Json,
  events: List[Json],
  metadata: Map[String, String] = Map.empty
)

object Checkpoint:
  given Encoder[Checkpoint] = io.circe.generic.semiauto.deriveEncoder
  given Decoder[Checkpoint] = io.circe.generic.semiauto.deriveDecoder

  def create[S: Encoder](
    workflowId: String,
    state: S,
    events: List[Json]
  ): Checkpoint = Checkpoint(
    id = java.util.UUID.randomUUID().toString,
    workflowId = workflowId,
    timestamp = Instant.now(),
    state = Encoder[S].apply(state),
    events = events
  )

/**
 * Result of checkpoint save operation.
 */
case class CheckpointResult(
  checkpoint: Checkpoint,
  success: Boolean,
  error: Option[String] = None
)
```

---

### Task 2: Create Checkpoint Storage

**Location**: `adk4s-persistence/src/main/scala/org/adk4s/persistence/CheckpointStorage.scala`

**API Design**:
```scala
package org.adk4s.persistence

import cats.effect.IO
import fs2.Stream

/**
 * Storage backend for checkpoints.
 */
trait CheckpointStorage:
  /**
   * Save a checkpoint.
   */
  def save(checkpoint: Checkpoint): IO[CheckpointResult]

  /**
   * Load checkpoint by ID.
   */
  def load(id: String): IO[Option[Checkpoint]]

  /**
   * Load latest checkpoint for workflow.
   */
  def loadLatest(workflowId: String): IO[Option[Checkpoint]]

  /**
   * List checkpoints for workflow.
   */
  def list(workflowId: String): IO[List[Checkpoint]]

  /**
   * Delete checkpoint.
   */
  def delete(id: String): IO[Boolean]

  /**
   * Stream all checkpoints.
   */
  def stream: Stream[IO, Checkpoint]

object CheckpointStorage:
  /**
   * In-memory storage (for testing).
   */
  def inMemory: IO[CheckpointStorage] =
    cats.effect.Ref.of[IO, Map[String, Checkpoint]](Map.empty).map { ref =>
      new CheckpointStorage:
        def save(checkpoint: Checkpoint): IO[CheckpointResult] =
          ref.update(_ + (checkpoint.id -> checkpoint))
            .as(CheckpointResult(checkpoint, true))

        def load(id: String): IO[Option[Checkpoint]] =
          ref.get.map(_.get(id))

        def loadLatest(workflowId: String): IO[Option[Checkpoint]] =
          ref.get.map(_.values.filter(_.workflowId == workflowId)
            .maxByOption(_.timestamp))

        def list(workflowId: String): IO[List[Checkpoint]] =
          ref.get.map(_.values.filter(_.workflowId == workflowId)
            .toList.sortBy(_.timestamp))

        def delete(id: String): IO[Boolean] =
          ref.modify { m =>
            if m.contains(id) then (m - id, true)
            else (m, false)
          }

        def stream: Stream[IO, Checkpoint] =
          Stream.eval(ref.get).flatMap(m => Stream.emits(m.values.toList))
    }

  /**
   * File-based storage.
   */
  def fileBased(directory: java.nio.file.Path): CheckpointStorage = ???
```

---

### Task 3: Create Workflows4s Checkpoint Integration

**Location**: `adk4s-persistence/src/main/scala/org/adk4s/persistence/WorkflowsCheckpoint.scala`

**API Design**:
```scala
package org.adk4s.persistence

import cats.effect.IO
import io.circe.{Json, Encoder, Decoder}
import workflows4s.wio.{WIO, WorkflowContext}
import workflows4s.runtime.{WorkflowInstance, ActiveWorkflow}

/**
 * Integration with Workflows4s for event-sourced checkpointing.
 */
object WorkflowsCheckpoint:
  /**
   * Save workflow instance state as checkpoint.
   */
  def saveInstance[Ctx <: WorkflowContext](
    instance: WorkflowInstance[IO],
    storage: CheckpointStorage
  )(using Encoder[Ctx#State], Encoder[Ctx#Event]): IO[CheckpointResult] =
    for
      state <- instance.getState
      events <- instance.getEvents
      checkpoint = Checkpoint.create(
        workflowId = instance.id,
        state = state,
        events = events.map(Encoder[Ctx#Event].apply)
      )
      result <- storage.save(checkpoint)
    yield result

  /**
   * Restore workflow instance from checkpoint.
   */
  def restoreInstance[Ctx <: WorkflowContext](
    checkpoint: Checkpoint,
    workflow: WIO[?, ?, ?, Ctx]
  )(using Decoder[Ctx#State], Decoder[Ctx#Event]): IO[WorkflowInstance[IO]] =
    for
      state <- IO.fromEither(checkpoint.state.as[Ctx#State])
      events <- IO.fromEither(checkpoint.events.traverse(_.as[Ctx#Event]))
      // Reconstruct instance by replaying events
      instance <- reconstructInstance(workflow, state, events)
    yield instance

  private def reconstructInstance[Ctx <: WorkflowContext](
    workflow: WIO[?, ?, ?, Ctx],
    state: Ctx#State,
    events: List[Ctx#Event]
  ): IO[WorkflowInstance[IO]] = ???
```

---

### Task 4: Create Agent Checkpoint Support

**Location**: `adk4s-persistence/src/main/scala/org/adk4s/persistence/AgentCheckpoint.scala`

**API Design**:
```scala
package org.adk4s.persistence

import cats.effect.IO
import io.circe.{Encoder, Decoder}
import io.circe.generic.semiauto.*
import org.adk4s.agent.react.{AgentState, AgentStatus, ReActAgent}
import org.adk4s.structured.core.Message

/**
 * Checkpoint support for ReAct agents.
 */
object AgentCheckpoint:
  // Encoders/Decoders for AgentState
  given Encoder[Message] = deriveEncoder
  given Decoder[Message] = deriveDecoder
  given Encoder[AgentStatus] = Encoder.encodeString.contramap(_.toString)
  given Decoder[AgentStatus] = Decoder.decodeString.map(AgentStatus.valueOf)
  given Encoder[AgentState] = deriveEncoder
  given Decoder[AgentState] = deriveDecoder

  /**
   * Save agent state as checkpoint.
   */
  def saveAgent(
    agentId: String,
    state: AgentState,
    storage: CheckpointStorage
  ): IO[CheckpointResult] =
    val checkpoint = Checkpoint.create(
      workflowId = agentId,
      state = state,
      events = Nil
    )
    storage.save(checkpoint)

  /**
   * Load agent state from checkpoint.
   */
  def loadAgent(
    agentId: String,
    storage: CheckpointStorage
  ): IO[Option[AgentState]] =
    storage.loadLatest(agentId).flatMap {
      case Some(checkpoint) =>
        IO.fromEither(checkpoint.state.as[AgentState]).map(Some(_))
      case None =>
        IO.pure(None)
    }

  /**
   * Resume agent from checkpoint.
   */
  def resumeAgent(
    agent: ReActAgent,
    storage: CheckpointStorage,
    agentId: String
  ): IO[String] =
    loadAgent(agentId, storage).flatMap {
      case Some(state) if !state.isComplete =>
        // Continue from saved state
        agent.runToState(state).map(_.lastMessage.map(_.content).getOrElse(""))
      case Some(state) =>
        // Already complete
        IO.pure(state.lastMessage.map(_.content).getOrElse(""))
      case None =>
        IO.raiseError(new RuntimeException(s"No checkpoint found for agent $agentId"))
    }

  extension (agent: ReActAgent)
    /**
     * Run with automatic checkpointing.
     */
    def runWithCheckpointing(
      query: String,
      storage: CheckpointStorage,
      checkpointInterval: Int = 1
    ): IO[String] =
      val agentId = java.util.UUID.randomUUID().toString
      agent.runStreaming(query)
        .zipWithIndex
        .evalTap { case (result, idx) =>
          if idx % checkpointInterval == 0 then
            saveAgent(agentId, result.newState, storage).void
          else IO.unit
        }
        .compile.lastOrError
        .map(_.newState.lastMessage.map(_.content).getOrElse(""))
```

---

### Task 5: Create Interrupt/Resume Pattern

**Location**: `adk4s-persistence/src/main/scala/org/adk4s/persistence/InterruptResume.scala`

**API Design**:
```scala
package org.adk4s.persistence

import cats.effect.IO
import io.circe.Json

/**
 * Interrupt signal with checkpoint data.
 */
case class InterruptSignal(
  reason: String,
  checkpoint: Checkpoint,
  extra: Option[Json] = None
)

/**
 * Resume token for continuing interrupted execution.
 */
case class ResumeToken(
  checkpointId: String,
  workflowId: String
)

object InterruptResume:
  /**
   * Interrupt execution and save checkpoint.
   */
  def interrupt[S: io.circe.Encoder](
    workflowId: String,
    reason: String,
    state: S,
    storage: CheckpointStorage
  ): IO[ResumeToken] =
    for
      checkpoint <- IO.pure(Checkpoint.create(workflowId, state, Nil))
      _ <- storage.save(checkpoint)
    yield ResumeToken(checkpoint.id, workflowId)

  /**
   * Resume from token.
   */
  def resume[S: io.circe.Decoder](
    token: ResumeToken,
    storage: CheckpointStorage
  ): IO[S] =
    storage.load(token.checkpointId).flatMap {
      case Some(checkpoint) =>
        IO.fromEither(checkpoint.state.as[S])
      case None =>
        IO.raiseError(new RuntimeException(s"Checkpoint ${token.checkpointId} not found"))
    }
```

---

## File Structure

```
adk4s-persistence/
└── src/main/scala/org/adk4s/persistence/
    ├── package.scala
    ├── Checkpoint.scala
    ├── CheckpointStorage.scala
    ├── WorkflowsCheckpoint.scala
    ├── AgentCheckpoint.scala
    └── InterruptResume.scala
```

## Testing Plan

1. Test checkpoint save/load
2. Test in-memory storage
3. Test Workflows4s integration
4. Test agent checkpointing
5. Test interrupt/resume pattern
6. Test checkpoint recovery after failure

## Examples

### Agent with Checkpointing

```scala
val storage = CheckpointStorage.inMemory.unsafeRunSync()
val agent = ReActAgent(config)

// Run with automatic checkpointing
val result = agent.runWithCheckpointing(
  "Complex multi-step task",
  storage,
  checkpointInterval = 1
)

// Resume from checkpoint after failure
val resumed = AgentCheckpoint.resumeAgent(agent, storage, agentId)
```

### Manual Interrupt/Resume

```scala
// Save checkpoint on interrupt
val token = InterruptResume.interrupt(
  workflowId = "workflow-123",
  reason = "User requested pause",
  state = currentState,
  storage = storage
).unsafeRunSync()

// Later: resume from token
val state = InterruptResume.resume[MyState](token, storage).unsafeRunSync()
```

## Completion Criteria

- [ ] Checkpoint types complete
- [ ] In-memory storage working
- [ ] Workflows4s integration
- [ ] Agent checkpointing
- [ ] Interrupt/resume pattern
- [ ] Unit tests passing
