# Feature 11: Callbacks & Observability

## Overview

This document details the implementation of callbacks and observability features for ADK4S, enabling logging, tracing, and monitoring of agent and graph execution.

## Prerequisites

- **Feature 01-08**: Core features

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| Cats Effect | IOLocal, effects | 3.6.3 |
| natchez (optional) | Distributed tracing | 0.3.x |

## Design Philosophy

ADK4S observability:
1. **Handler-based callbacks** - Non-invasive observation
2. **Global and local scope** - Different granularities
3. **Streaming support** - Callbacks for streaming operations
4. **Integration ready** - Hooks for external systems

## Implementation Tasks

### Task 1: Create CallbackHandler Trait

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/CallbackHandler.scala`

**API Design**:
```scala
package org.adk4s.observability

import fs2.Stream
import cats.effect.IO
import cats.Applicative
import org.adk4s.core.types.RunInfo

/**
 * Callback handler for graph/agent execution events.
 */
trait CallbackHandler[F[_]]:
  /**
   * Called when a node starts execution.
   */
  def onStart[I](info: RunInfo, input: I): F[Unit]

  /**
   * Called when a node completes successfully.
   */
  def onEnd[O](info: RunInfo, output: O): F[Unit]

  /**
   * Called when a node fails.
   */
  def onError(info: RunInfo, error: Throwable): F[Unit]

  /**
   * Called when streaming input starts.
   */
  def onStartStream[I](info: RunInfo): F[Unit]

  /**
   * Called when streaming output completes.
   */
  def onEndStream[O](info: RunInfo): F[Unit]

object CallbackHandler:
  /**
   * No-op handler.
   */
  def noop[F[_]: Applicative]: CallbackHandler[F] = new CallbackHandler[F]:
    def onStart[I](info: RunInfo, input: I): F[Unit] = Applicative[F].unit
    def onEnd[O](info: RunInfo, output: O): F[Unit] = Applicative[F].unit
    def onError(info: RunInfo, error: Throwable): F[Unit] = Applicative[F].unit
    def onStartStream[I](info: RunInfo): F[Unit] = Applicative[F].unit
    def onEndStream[O](info: RunInfo): F[Unit] = Applicative[F].unit

  /**
   * Combine multiple handlers.
   */
  def combine[F[_]: Applicative](handlers: List[CallbackHandler[F]]): CallbackHandler[F] =
    new CallbackHandler[F]:
      def onStart[I](info: RunInfo, input: I): F[Unit] =
        handlers.traverse_(_.onStart(info, input))
      def onEnd[O](info: RunInfo, output: O): F[Unit] =
        handlers.traverse_(_.onEnd(info, output))
      def onError(info: RunInfo, error: Throwable): F[Unit] =
        handlers.traverse_(_.onError(info, error))
      def onStartStream[I](info: RunInfo): F[Unit] =
        handlers.traverse_(_.onStartStream(info))
      def onEndStream[O](info: RunInfo): F[Unit] =
        handlers.traverse_(_.onEndStream(info))
```

---

### Task 2: Create CallbackHandler Builder

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/CallbackBuilder.scala`

**API Design**:
```scala
package org.adk4s.observability

import cats.effect.IO
import org.adk4s.core.types.RunInfo

/**
 * Builder for creating CallbackHandlers.
 */
case class CallbackBuilder(
  private val onStartFn: Option[(RunInfo, Any) => IO[Unit]] = None,
  private val onEndFn: Option[(RunInfo, Any) => IO[Unit]] = None,
  private val onErrorFn: Option[(RunInfo, Throwable) => IO[Unit]] = None,
  private val onStartStreamFn: Option[RunInfo => IO[Unit]] = None,
  private val onEndStreamFn: Option[RunInfo => IO[Unit]] = None
):
  def withOnStart(f: (RunInfo, Any) => IO[Unit]): CallbackBuilder =
    copy(onStartFn = Some(f))

  def withOnEnd(f: (RunInfo, Any) => IO[Unit]): CallbackBuilder =
    copy(onEndFn = Some(f))

  def withOnError(f: (RunInfo, Throwable) => IO[Unit]): CallbackBuilder =
    copy(onErrorFn = Some(f))

  def withOnStartStream(f: RunInfo => IO[Unit]): CallbackBuilder =
    copy(onStartStreamFn = Some(f))

  def withOnEndStream(f: RunInfo => IO[Unit]): CallbackBuilder =
    copy(onEndStreamFn = Some(f))

  def build: CallbackHandler[IO] = new CallbackHandler[IO]:
    def onStart[I](info: RunInfo, input: I): IO[Unit] =
      onStartFn.fold(IO.unit)(_(info, input))
    def onEnd[O](info: RunInfo, output: O): IO[Unit] =
      onEndFn.fold(IO.unit)(_(info, output))
    def onError(info: RunInfo, error: Throwable): IO[Unit] =
      onErrorFn.fold(IO.unit)(_(info, error))
    def onStartStream[I](info: RunInfo): IO[Unit] =
      onStartStreamFn.fold(IO.unit)(_(info))
    def onEndStream[O](info: RunInfo): IO[Unit] =
      onEndStreamFn.fold(IO.unit)(_(info))

object CallbackBuilder:
  def apply(): CallbackBuilder = new CallbackBuilder()
```

---

### Task 3: Create Global Handler Registry

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/GlobalHandlers.scala`

**API Design**:
```scala
package org.adk4s.observability

import cats.effect.{IO, IOLocal, Ref}

/**
 * Global handler registry using IOLocal for scoped handlers.
 */
object GlobalHandlers:
  private val globalHandlers: Ref[IO, List[CallbackHandler[IO]]] =
    Ref.unsafe[IO, List[CallbackHandler[IO]]](Nil)

  private val localHandlers: IOLocal[List[CallbackHandler[IO]]] =
    IOLocal[List[CallbackHandler[IO]]](Nil).unsafeRunSync()

  /**
   * Register a global handler (affects all executions).
   */
  def registerGlobal(handler: CallbackHandler[IO]): IO[Unit] =
    globalHandlers.update(_ :+ handler)

  /**
   * Register a local handler (affects current fiber only).
   */
  def registerLocal(handler: CallbackHandler[IO]): IO[Unit] =
    localHandlers.update(_ :+ handler)

  /**
   * Run with local handlers.
   */
  def withLocalHandlers[A](handlers: List[CallbackHandler[IO]])(io: IO[A]): IO[A] =
    localHandlers.set(handlers) *> io

  /**
   * Get all active handlers (global + local).
   */
  def getHandlers: IO[List[CallbackHandler[IO]]] =
    for
      global <- globalHandlers.get
      local <- localHandlers.get
    yield global ++ local

  /**
   * Get combined handler.
   */
  def getCombinedHandler: IO[CallbackHandler[IO]] =
    getHandlers.map(CallbackHandler.combine)
```

---

### Task 4: Create Logging Handler

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/LoggingHandler.scala`

**API Design**:
```scala
package org.adk4s.observability

import cats.effect.IO
import org.adk4s.core.types.RunInfo
import java.time.Instant

/**
 * Pre-built logging callback handler.
 */
object LoggingHandler:
  /**
   * Create handler that logs to console.
   */
  def console: CallbackHandler[IO] = CallbackBuilder()
    .withOnStart { (info, input) =>
      IO.println(s"[${Instant.now}] START ${info.nodeKey.value}: input=${truncate(input.toString)}")
    }
    .withOnEnd { (info, output) =>
      IO.println(s"[${Instant.now}] END ${info.nodeKey.value}: output=${truncate(output.toString)}")
    }
    .withOnError { (info, error) =>
      IO.println(s"[${Instant.now}] ERROR ${info.nodeKey.value}: ${error.getMessage}")
    }
    .build

  /**
   * Create handler that logs to a function.
   */
  def custom(log: String => IO[Unit]): CallbackHandler[IO] = CallbackBuilder()
    .withOnStart { (info, input) =>
      log(s"START ${info.nodeKey.value}: input=${truncate(input.toString)}")
    }
    .withOnEnd { (info, output) =>
      log(s"END ${info.nodeKey.value}: output=${truncate(output.toString)}")
    }
    .withOnError { (info, error) =>
      log(s"ERROR ${info.nodeKey.value}: ${error.getMessage}")
    }
    .build

  private def truncate(s: String, max: Int = 100): String =
    if s.length > max then s.take(max) + "..." else s
```

---

### Task 5: Create Timing Handler

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/TimingHandler.scala`

**API Design**:
```scala
package org.adk4s.observability

import cats.effect.{IO, Ref}
import org.adk4s.core.types.{RunInfo, NodeKey}
import scala.concurrent.duration.FiniteDuration

/**
 * Timing metrics for node execution.
 */
case class NodeTiming(
  nodeKey: NodeKey,
  startTime: Long,
  endTime: Option[Long] = None
):
  def duration: Option[Long] = endTime.map(_ - startTime)

/**
 * Handler that tracks execution timing.
 */
class TimingHandler private (
  timings: Ref[IO, Map[NodeKey, NodeTiming]]
) extends CallbackHandler[IO]:
  def onStart[I](info: RunInfo, input: I): IO[Unit] =
    IO.realTime.flatMap { now =>
      timings.update(_ + (info.nodeKey -> NodeTiming(info.nodeKey, now.toMillis)))
    }

  def onEnd[O](info: RunInfo, output: O): IO[Unit] =
    IO.realTime.flatMap { now =>
      timings.update { m =>
        m.get(info.nodeKey).fold(m)(t => m + (info.nodeKey -> t.copy(endTime = Some(now.toMillis))))
      }
    }

  def onError(info: RunInfo, error: Throwable): IO[Unit] =
    onEnd(info, ())

  def onStartStream[I](info: RunInfo): IO[Unit] = IO.unit
  def onEndStream[O](info: RunInfo): IO[Unit] = IO.unit

  /**
   * Get all timings.
   */
  def getTimings: IO[Map[NodeKey, NodeTiming]] =
    timings.get

  /**
   * Get timing for specific node.
   */
  def getTiming(key: NodeKey): IO[Option[NodeTiming]] =
    timings.get.map(_.get(key))

object TimingHandler:
  def create: IO[TimingHandler] =
    Ref.of[IO, Map[NodeKey, NodeTiming]](Map.empty).map(new TimingHandler(_))
```

---

### Task 6: Create Tracing Integration (Optional)

**Location**: `adk4s-observability/src/main/scala/org/adk4s/observability/TracingHandler.scala`

**API Design**:
```scala
package org.adk4s.observability

import cats.effect.IO
import org.adk4s.core.types.RunInfo

/**
 * Tracing handler for distributed tracing integration.
 * Designed to work with natchez or similar libraries.
 */
trait TracingHandler extends CallbackHandler[IO]:
  /**
   * Create a new span for node execution.
   */
  def span[A](name: String)(io: IO[A]): IO[A]

/**
 * No-op tracing handler.
 */
object NoopTracingHandler extends TracingHandler:
  def onStart[I](info: RunInfo, input: I): IO[Unit] = IO.unit
  def onEnd[O](info: RunInfo, output: O): IO[Unit] = IO.unit
  def onError(info: RunInfo, error: Throwable): IO[Unit] = IO.unit
  def onStartStream[I](info: RunInfo): IO[Unit] = IO.unit
  def onEndStream[O](info: RunInfo): IO[Unit] = IO.unit
  def span[A](name: String)(io: IO[A]): IO[A] = io
```

---

## File Structure

```
adk4s-observability/
└── src/main/scala/org/adk4s/observability/
    ├── package.scala
    ├── CallbackHandler.scala
    ├── CallbackBuilder.scala
    ├── GlobalHandlers.scala
    ├── LoggingHandler.scala
    ├── TimingHandler.scala
    └── TracingHandler.scala
```

## Testing Plan

1. Test callback handler creation
2. Test global/local handler scoping
3. Test logging handler output
4. Test timing handler metrics
5. Test handler combination

## Completion Criteria

- [ ] CallbackHandler trait complete
- [ ] CallbackBuilder working
- [ ] Global/Local handler registry
- [ ] Logging and Timing handlers
- [ ] Tracing integration ready
- [ ] Unit tests passing
