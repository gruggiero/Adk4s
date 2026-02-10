# Feature 04: Lambda & Runnable

## Overview

This document details the implementation of Lambda functions and the Runnable trait, which are core abstractions for wrapping user logic as graph nodes. These enable Eino's four streaming paradigms (Invoke, Stream, Collect, Transform).

## Prerequisites

- **Feature 01**: Core Types & Schema System
- **Feature 02**: Streaming Integration
- **Feature 03**: Component Abstractions

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| fs2 | Streaming | 3.9.x |
| Cats Effect | Effects | 3.6.3 |

## Design Philosophy

ADK4S Lambda follows these principles:

1. **ADT-based design** - Lambda variants as sealed trait hierarchy
2. **Automatic derivation** - Missing paradigms derived from provided ones
3. **Type safety** - Input/output types tracked at compile time
4. **Functional** - Pure functions wrapped in IO

## Implementation Tasks

### Task 1: Create Runnable Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/runnable/Runnable.scala`

**Purpose**: Define the four streaming paradigms from Eino

**Subtasks**:
1. Create `Runnable[I, O]` trait with all four methods
2. Define default implementations that derive from each other
3. Add configuration support

**API Design**:
```scala
package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO

/**
 * Runnable represents an executable unit with four streaming paradigms.
 *
 * Based on Eino's Runnable interface:
 * - invoke: non-stream input -> non-stream output
 * - stream: non-stream input -> stream output
 * - collect: stream input -> non-stream output
 * - transform: stream input -> stream output
 *
 * @tparam I Input type
 * @tparam O Output type
 */
trait Runnable[I, O]:
  /**
   * Non-streaming execution: single input to single output.
   */
  def invoke(input: I): IO[O]

  /**
   * Streaming output: single input to stream of outputs.
   */
  def stream(input: I): Stream[IO, O]

  /**
   * Collecting: stream of inputs to single output.
   */
  def collect(input: Stream[IO, I]): IO[O]

  /**
   * Transforming: stream of inputs to stream of outputs.
   */
  def transform(input: Stream[IO, I]): Stream[IO, O]

object Runnable:
  /**
   * Create Runnable from invoke function only.
   * Other paradigms are derived automatically.
   */
  def fromInvoke[I, O](f: I => IO[O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(input)

    def stream(input: I): Stream[IO, O] =
      Stream.eval(f(input))

    def collect(input: Stream[IO, I]): IO[O] =
      input.compile.lastOrError.flatMap(f)

    def transform(input: Stream[IO, I]): Stream[IO, O] =
      input.evalMap(f)

  /**
   * Create Runnable from stream function only.
   * Other paradigms are derived automatically.
   */
  def fromStream[I, O](f: I => Stream[IO, O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] =
      f(input).compile.lastOrError

    def stream(input: I): Stream[IO, O] =
      f(input)

    def collect(input: Stream[IO, I]): IO[O] =
      input.compile.lastOrError.flatMap(i => f(i).compile.lastOrError)

    def transform(input: Stream[IO, I]): Stream[IO, O] =
      input.flatMap(f)

  /**
   * Create Runnable from collect function only.
   * Other paradigms are derived automatically.
   */
  def fromCollect[I, O](f: Stream[IO, I] => IO[O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] =
      f(Stream.emit(input))

    def stream(input: I): Stream[IO, O] =
      Stream.eval(f(Stream.emit(input)))

    def collect(input: Stream[IO, I]): IO[O] =
      f(input)

    def transform(input: Stream[IO, I]): Stream[IO, O] =
      Stream.eval(f(input))

  /**
   * Create Runnable from transform function only.
   * Other paradigms are derived automatically.
   */
  def fromTransform[I, O](f: Stream[IO, I] => Stream[IO, O]): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] =
      f(Stream.emit(input)).compile.lastOrError

    def stream(input: I): Stream[IO, O] =
      f(Stream.emit(input))

    def collect(input: Stream[IO, I]): IO[O] =
      f(input).compile.lastOrError

    def transform(input: Stream[IO, I]): Stream[IO, O] =
      f(input)

  /**
   * Create Runnable with all four paradigms explicitly provided.
   */
  def full[I, O](
    invokeFn: I => IO[O],
    streamFn: I => Stream[IO, O],
    collectFn: Stream[IO, I] => IO[O],
    transformFn: Stream[IO, I] => Stream[IO, O]
  ): Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = invokeFn(input)
    def stream(input: I): Stream[IO, O] = streamFn(input)
    def collect(input: Stream[IO, I]): IO[O] = collectFn(input)
    def transform(input: Stream[IO, I]): Stream[IO, O] = transformFn(input)
```

**Testing**:
- Test each factory creates working Runnable
- Test derivation correctness
- Test all four paradigms

---

### Task 2: Create Lambda ADT

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/runnable/Lambda.scala`

**Purpose**: Wrap user functions as graph nodes with type information

**Subtasks**:
1. Create sealed trait `Lambda[I, O]` hierarchy
2. Create variants: InvokableLambda, StreamableLambda, CollectableLambda, TransformableLambda
3. Add conversion to Runnable
4. Add metadata (name, description)

**API Design**:
```scala
package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO
import scala.reflect.TypeTest

/**
 * Lambda configuration and metadata.
 */
case class LambdaConfig(
  name: Option[String] = None,
  description: Option[String] = None
)

/**
 * Lambda wraps user functions as composable graph nodes.
 *
 * Lambda is an ADT with four variants matching the four streaming paradigms.
 * Each variant can be converted to a Runnable for execution.
 *
 * @tparam I Input type
 * @tparam O Output type
 */
sealed trait Lambda[I, O]:
  /**
   * Optional configuration/metadata.
   */
  def config: LambdaConfig

  /**
   * Convert to Runnable for execution.
   */
  def toRunnable: Runnable[I, O]

  /**
   * Create a copy with updated config.
   */
  def withConfig(newConfig: LambdaConfig): Lambda[I, O]

  /**
   * Set lambda name.
   */
  def named(name: String): Lambda[I, O] =
    withConfig(config.copy(name = Some(name)))

  /**
   * Set lambda description.
   */
  def described(description: String): Lambda[I, O] =
    withConfig(config.copy(description = Some(description)))

/**
 * Lambda that processes single input to single output.
 */
case class InvokableLambda[I, O](
  f: I => IO[O],
  config: LambdaConfig = LambdaConfig()
) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = Runnable.fromInvoke(f)
  def withConfig(newConfig: LambdaConfig): Lambda[I, O] = copy(config = newConfig)

/**
 * Lambda that processes single input to stream output.
 */
case class StreamableLambda[I, O](
  f: I => Stream[IO, O],
  config: LambdaConfig = LambdaConfig()
) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = Runnable.fromStream(f)
  def withConfig(newConfig: LambdaConfig): Lambda[I, O] = copy(config = newConfig)

/**
 * Lambda that processes stream input to single output.
 */
case class CollectableLambda[I, O](
  f: Stream[IO, I] => IO[O],
  config: LambdaConfig = LambdaConfig()
) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = Runnable.fromCollect(f)
  def withConfig(newConfig: LambdaConfig): Lambda[I, O] = copy(config = newConfig)

/**
 * Lambda that processes stream input to stream output.
 */
case class TransformableLambda[I, O](
  f: Stream[IO, I] => Stream[IO, O],
  config: LambdaConfig = LambdaConfig()
) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = Runnable.fromTransform(f)
  def withConfig(newConfig: LambdaConfig): Lambda[I, O] = copy(config = newConfig)

/**
 * Lambda that has all four paradigms explicitly provided.
 */
case class FullLambda[I, O](
  invokeFn: I => IO[O],
  streamFn: I => Stream[IO, O],
  collectFn: Stream[IO, I] => IO[O],
  transformFn: Stream[IO, I] => Stream[IO, O],
  config: LambdaConfig = LambdaConfig()
) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = Runnable.full(invokeFn, streamFn, collectFn, transformFn)
  def withConfig(newConfig: LambdaConfig): Lambda[I, O] = copy(config = newConfig)

object Lambda:
  /**
   * Create invokable lambda (most common case).
   */
  def apply[I, O](f: I => IO[O]): Lambda[I, O] =
    InvokableLambda(f)

  /**
   * Create invokable lambda from pure function.
   */
  def pure[I, O](f: I => O): Lambda[I, O] =
    InvokableLambda(i => IO.pure(f(i)))

  /**
   * Create streamable lambda.
   */
  def stream[I, O](f: I => Stream[IO, O]): Lambda[I, O] =
    StreamableLambda(f)

  /**
   * Create collectable lambda.
   */
  def collect[I, O](f: Stream[IO, I] => IO[O]): Lambda[I, O] =
    CollectableLambda(f)

  /**
   * Create transformable lambda.
   */
  def transform[I, O](f: Stream[IO, I] => Stream[IO, O]): Lambda[I, O] =
    TransformableLambda(f)

  /**
   * Create full lambda with all paradigms.
   */
  def full[I, O](
    invoke: I => IO[O],
    stream: I => Stream[IO, O],
    collect: Stream[IO, I] => IO[O],
    transform: Stream[IO, I] => Stream[IO, O]
  ): Lambda[I, O] =
    FullLambda(invoke, stream, collect, transform)

  /**
   * Implicit conversion from function to Lambda.
   */
  given [I, O]: Conversion[I => IO[O], Lambda[I, O]] = f => InvokableLambda(f)
```

**Testing**:
- Test each Lambda variant creation
- Test toRunnable produces correct behavior
- Test config methods
- Test implicit conversion

---

### Task 3: Create Runnable Combinators

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/runnable/RunnableOps.scala`

**Purpose**: Provide composition and transformation operations for Runnable

**Subtasks**:
1. Create `andThen` for sequential composition
2. Create `map` and `contramap` for transformations
3. Create parallel execution combinator
4. Create error handling combinators

**API Design**:
```scala
package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration

object RunnableOps:
  extension [I, O](self: Runnable[I, O])
    /**
     * Sequential composition: run this, then run next with output.
     */
    def andThen[O2](next: Runnable[O, O2]): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] =
        self.invoke(input).flatMap(next.invoke)

      def stream(input: I): Stream[IO, O2] =
        self.stream(input).flatMap(o => next.stream(o))

      def collect(input: Stream[IO, I]): IO[O2] =
        self.collect(input).flatMap(next.invoke)

      def transform(input: Stream[IO, I]): Stream[IO, O2] =
        next.transform(self.transform(input))

    /**
     * Transform output.
     */
    def map[O2](f: O => O2): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] =
        self.invoke(input).map(f)

      def stream(input: I): Stream[IO, O2] =
        self.stream(input).map(f)

      def collect(input: Stream[IO, I]): IO[O2] =
        self.collect(input).map(f)

      def transform(input: Stream[IO, I]): Stream[IO, O2] =
        self.transform(input).map(f)

    /**
     * Transform output with effect.
     */
    def evalMap[O2](f: O => IO[O2]): Runnable[I, O2] = new Runnable[I, O2]:
      def invoke(input: I): IO[O2] =
        self.invoke(input).flatMap(f)

      def stream(input: I): Stream[IO, O2] =
        self.stream(input).evalMap(f)

      def collect(input: Stream[IO, I]): IO[O2] =
        self.collect(input).flatMap(f)

      def transform(input: Stream[IO, I]): Stream[IO, O2] =
        self.transform(input).evalMap(f)

    /**
     * Transform input.
     */
    def contramap[I2](f: I2 => I): Runnable[I2, O] = new Runnable[I2, O]:
      def invoke(input: I2): IO[O] =
        self.invoke(f(input))

      def stream(input: I2): Stream[IO, O] =
        self.stream(f(input))

      def collect(input: Stream[IO, I2]): IO[O] =
        self.collect(input.map(f))

      def transform(input: Stream[IO, I2]): Stream[IO, O] =
        self.transform(input.map(f))

    /**
     * Add timeout to invoke.
     */
    def timeout(duration: FiniteDuration): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] =
        self.invoke(input).timeout(duration)

      def stream(input: I): Stream[IO, O] =
        self.stream(input).timeout(duration)

      def collect(input: Stream[IO, I]): IO[O] =
        self.collect(input).timeout(duration)

      def transform(input: Stream[IO, I]): Stream[IO, O] =
        self.transform(input).timeout(duration)

    /**
     * Handle errors with fallback.
     */
    def handleError(handler: Throwable => IO[O]): Runnable[I, O] = new Runnable[I, O]:
      def invoke(input: I): IO[O] =
        self.invoke(input).handleErrorWith(handler)

      def stream(input: I): Stream[IO, O] =
        self.stream(input).handleErrorWith(e => Stream.eval(handler(e)))

      def collect(input: Stream[IO, I]): IO[O] =
        self.collect(input).handleErrorWith(handler)

      def transform(input: Stream[IO, I]): Stream[IO, O] =
        self.transform(input).handleErrorWith(e => Stream.eval(handler(e)))

  /**
   * Run multiple Runnables in parallel, combine outputs as tuple.
   */
  def parallel[I, O1, O2](
    r1: Runnable[I, O1],
    r2: Runnable[I, O2]
  ): Runnable[I, (O1, O2)] = new Runnable[I, (O1, O2)]:
    def invoke(input: I): IO[(O1, O2)] =
      (r1.invoke(input), r2.invoke(input)).parTupled

    def stream(input: I): Stream[IO, (O1, O2)] =
      r1.stream(input).zip(r2.stream(input))

    def collect(input: Stream[IO, I]): IO[(O1, O2)] =
      // Note: this consumes the stream twice, which may not be desired
      // For production, consider using fs2 broadcast
      input.compile.toList.flatMap { items =>
        (r1.collect(Stream.emits(items)), r2.collect(Stream.emits(items))).parTupled
      }

    def transform(input: Stream[IO, I]): Stream[IO, (O1, O2)] =
      // Similar limitation as collect
      Stream.eval(input.compile.toList).flatMap { items =>
        r1.transform(Stream.emits(items)).zip(r2.transform(Stream.emits(items)))
      }

  /**
   * Run multiple Runnables in parallel with 3 outputs.
   */
  def parallel3[I, O1, O2, O3](
    r1: Runnable[I, O1],
    r2: Runnable[I, O2],
    r3: Runnable[I, O3]
  ): Runnable[I, (O1, O2, O3)] = new Runnable[I, (O1, O2, O3)]:
    def invoke(input: I): IO[(O1, O2, O3)] =
      (r1.invoke(input), r2.invoke(input), r3.invoke(input)).parTupled

    def stream(input: I): Stream[IO, (O1, O2, O3)] =
      r1.stream(input).zip(r2.stream(input)).zip(r3.stream(input)).map {
        case ((o1, o2), o3) => (o1, o2, o3)
      }

    def collect(input: Stream[IO, I]): IO[(O1, O2, O3)] =
      input.compile.toList.flatMap { items =>
        (
          r1.collect(Stream.emits(items)),
          r2.collect(Stream.emits(items)),
          r3.collect(Stream.emits(items))
        ).parTupled
      }

    def transform(input: Stream[IO, I]): Stream[IO, (O1, O2, O3)] =
      Stream.eval(input.compile.toList).flatMap { items =>
        r1.transform(Stream.emits(items))
          .zip(r2.transform(Stream.emits(items)))
          .zip(r3.transform(Stream.emits(items)))
          .map { case ((o1, o2), o3) => (o1, o2, o3) }
      }
```

**Testing**:
- Test andThen composition
- Test map/contramap transformations
- Test parallel execution
- Test error handling

---

### Task 4: Create ComponentRunnable Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/runnable/ComponentRunnable.scala`

**Purpose**: Bridge between component abstractions and Runnable

**Subtasks**:
1. Create typeclass for converting components to Runnable
2. Implement for ChatModel, Tool, etc.

**API Design**:
```scala
package org.adk4s.core.runnable

import fs2.Stream
import cats.effect.IO
import org.adk4s.core.component.*
import org.llm4s.llmconnect.model.{Conversation, Completion, StreamedChunk}

/**
 * Typeclass for converting components to Runnable.
 */
trait ToRunnable[C, I, O]:
  def toRunnable(component: C): Runnable[I, O]

object ToRunnable:
  /**
   * ChatModel as Runnable[Conversation, Completion].
   */
  given chatModelRunnable: ToRunnable[ChatModel[IO], Conversation, Completion] with
    def toRunnable(model: ChatModel[IO]): Runnable[Conversation, Completion] =
      new Runnable[Conversation, Completion]:
        def invoke(input: Conversation): IO[Completion] =
          model.generate(input)

        def stream(input: Conversation): Stream[IO, Completion] =
          model.stream(input).through(org.adk4s.core.streaming.ChunkAccumulator.accumulateAll)
            .map(_.toCompletion)

        def collect(input: Stream[IO, Conversation]): IO[Completion] =
          input.compile.lastOrError.flatMap(model.generate(_))

        def transform(input: Stream[IO, Conversation]): Stream[IO, Completion] =
          input.evalMap(model.generate(_))

  /**
   * InvokableTool as Runnable[String, String].
   */
  given invokableToolRunnable: ToRunnable[InvokableTool[IO], String, String] with
    def toRunnable(tool: InvokableTool[IO]): Runnable[String, String] =
      Runnable.fromInvoke(tool.run)

  /**
   * StreamableTool as Runnable[String, String].
   */
  given streamableToolRunnable: ToRunnable[StreamableTool[IO], String, String] with
    def toRunnable(tool: StreamableTool[IO]): Runnable[String, String] =
      Runnable.fromStream(tool.runStream)

  /**
   * Lambda as Runnable.
   */
  given lambdaRunnable[I, O]: ToRunnable[Lambda[I, O], I, O] with
    def toRunnable(lambda: Lambda[I, O]): Runnable[I, O] =
      lambda.toRunnable

  extension [C](component: C)
    def asRunnable[I, O](using tr: ToRunnable[C, I, O]): Runnable[I, O] =
      tr.toRunnable(component)
```

**Testing**:
- Test ChatModel to Runnable
- Test Tool to Runnable
- Test Lambda to Runnable

---

## File Structure

```
adk4s-core/
└── src/
    ├── main/
    │   └── scala/
    │       └── org/
    │           └── adk4s/
    │               └── core/
    │                   └── runnable/
    │                       ├── package.scala            # Exports
    │                       ├── Runnable.scala           # Core trait
    │                       ├── Lambda.scala             # Lambda ADT
    │                       ├── RunnableOps.scala        # Combinators
    │                       └── ComponentRunnable.scala  # Component bridge
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── core/
                        └── runnable/
                            ├── RunnableTest.scala
                            ├── LambdaTest.scala
                            ├── RunnableOpsTest.scala
                            └── ComponentRunnableTest.scala
```

## Testing Plan

### Unit Tests

1. **Runnable Tests**
   - Each factory produces working Runnable
   - Derived paradigms work correctly
   - Full Runnable with all four provided

2. **Lambda Tests**
   - Each variant creates correctly
   - toRunnable produces expected behavior
   - Config methods work
   - Named/described work

3. **RunnableOps Tests**
   - andThen composes correctly
   - map/contramap transform correctly
   - parallel runs concurrently
   - timeout and error handling work

4. **ComponentRunnable Tests**
   - ChatModel converts to Runnable
   - Tools convert to Runnable
   - Lambda converts to Runnable

## Examples

### Creating Lambdas

```scala
import org.adk4s.core.runnable.Lambda

// Simple invokable lambda
val toUpper: Lambda[String, String] = Lambda.pure(_.toUpperCase)

// Lambda with IO effect
val fetchData: Lambda[String, Data] = Lambda { url =>
  IO(httpClient.get(url)).map(parseData)
}

// Streaming lambda
val tokenize: Lambda[String, String] = Lambda.stream { text =>
  Stream.emits(text.split(" ").toList)
}

// Transform lambda
val uppercase: Lambda[String, String] = Lambda.transform { stream =>
  stream.map(_.toUpperCase)
}
```

### Composing Runnables

```scala
import org.adk4s.core.runnable.RunnableOps.*

val pipeline: Runnable[String, Result] =
  fetchLambda.toRunnable
    .andThen(processLambda.toRunnable)
    .andThen(formatLambda.toRunnable)
    .timeout(30.seconds)
    .handleError(_ => IO.pure(defaultResult))

// Parallel execution
val combined: Runnable[Input, (A, B, C)] =
  RunnableOps.parallel3(runnableA, runnableB, runnableC)
```

## Completion Criteria

- [ ] Runnable trait with all four paradigms implemented
- [ ] Lambda ADT with all variants implemented
- [ ] Automatic paradigm derivation working correctly
- [ ] Runnable combinators complete (andThen, map, parallel, etc.)
- [ ] Component to Runnable conversion working
- [ ] Unit tests passing with >90% coverage
- [ ] Documentation updated
