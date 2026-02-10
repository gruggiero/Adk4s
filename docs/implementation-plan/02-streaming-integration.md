# Feature 02: Streaming Integration

## Overview

This document details the implementation of streaming infrastructure for ADK4S using fs2. The streaming layer provides interoperability between LLM4S Iterator-based streaming and fs2.Stream for functional composition.

## Prerequisites

- **Feature 01**: Core Types & Schema System (for type converters)
- **Existing**: structured-llm module

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| fs2-core | Functional streaming | 3.9.x |
| fs2-io | IO integration | 3.9.x |
| Cats Effect | Effect management | 3.6.3 |
| LLM4S | StreamedChunk type | Local |

## Current State Analysis

### LLM4S Streaming API

LLM4S provides streaming via Iterator:

```scala
// LLM4S streaming interface
trait LLMClient:
  def completeStreamed(
    conversation: Conversation,
    options: CompletionOptions
  ): Either[LLMError, Iterator[StreamedChunk]]

// StreamedChunk structure
case class StreamedChunk(
  content: String,
  finishReason: Option[String],
  toolCalls: List[ToolCall]
)
```

### Target: fs2.Stream Integration

ADK4S will use fs2.Stream for:
- Resource-safe streaming
- Functional composition (map, filter, flatMap)
- Automatic resource cleanup
- Backpressure handling
- Concurrent operations

## Implementation Tasks

### Task 1: Create Stream Conversion Utilities

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamConverter.scala`

**Purpose**: Convert LLM4S Iterator to fs2.Stream

**Subtasks**:
1. Create `fromIterator` method that wraps Iterator in fs2.Stream
2. Create `fromEither` method that handles Either[LLMError, Iterator]
3. Add proper resource management for cleanup
4. Handle chunk sizing for efficiency

**API Design**:
```scala
package org.adk4s.core.streaming

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.error.LLMError
import org.adk4s.core.error.{AdkError, LlmCallError}

object StreamConverter:
  /**
   * Convert an Iterator to fs2.Stream.
   * Uses default chunk size of 1 for LLM streaming (low latency).
   */
  def fromIterator[A](iter: Iterator[A], chunkSize: Int = 1): Stream[IO, A] =
    Stream.fromIterator[IO](iter, chunkSize)

  /**
   * Convert Either[LLMError, Iterator] to fs2.Stream.
   * Left values become stream errors.
   */
  def fromEither[A](result: Either[LLMError, Iterator[A]]): Stream[IO, A] =
    result match
      case Right(iter) => fromIterator(iter)
      case Left(error) => Stream.raiseError[IO](LlmCallError(error))

  /**
   * Convert LLM4S streaming result to fs2.Stream of StreamedChunk.
   */
  def fromLlm4sStream(
    result: Either[LLMError, Iterator[StreamedChunk]]
  ): Stream[IO, StreamedChunk] =
    fromEither(result)

  /**
   * Convert LLM4S streaming result to fs2.Stream of content strings.
   * Filters empty chunks and extracts content.
   */
  def contentStream(
    result: Either[LLMError, Iterator[StreamedChunk]]
  ): Stream[IO, String] =
    fromLlm4sStream(result)
      .map(_.content)
      .filter(_.nonEmpty)

  extension [A](result: Either[LLMError, Iterator[A]])
    def toFs2Stream: Stream[IO, A] = fromEither(result)
```

**Testing**:
- Test successful stream conversion
- Test error propagation
- Test empty iterator
- Test content filtering

---

### Task 2: Create StreamedChunk Accumulator

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/ChunkAccumulator.scala`

**Purpose**: Accumulate streaming chunks into complete response

**Subtasks**:
1. Create `ChunkAccumulator` for accumulating StreamedChunk
2. Handle content concatenation
3. Handle tool call accumulation (streaming tool calls)
4. Track finish reason from final chunk

**API Design**:
```scala
package org.adk4s.core.streaming

import fs2.{Stream, Pipe}
import cats.effect.IO
import org.llm4s.llmconnect.model.{StreamedChunk, ToolCall, Completion}

/**
 * Accumulated result from streaming chunks.
 */
case class AccumulatedResponse(
  content: String,
  finishReason: Option[String],
  toolCalls: List[ToolCall]
):
  def toCompletion: Completion = Completion(content, finishReason, toolCalls)

object ChunkAccumulator:
  /**
   * Empty accumulator state.
   */
  val empty: AccumulatedResponse = AccumulatedResponse("", None, Nil)

  /**
   * Accumulate a chunk into the response.
   */
  def accumulate(acc: AccumulatedResponse, chunk: StreamedChunk): AccumulatedResponse =
    AccumulatedResponse(
      content = acc.content + chunk.content,
      finishReason = chunk.finishReason.orElse(acc.finishReason),
      toolCalls = acc.toolCalls ++ chunk.toolCalls
    )

  /**
   * fs2 Pipe that accumulates all chunks into a single AccumulatedResponse.
   */
  def accumulateAll: Pipe[IO, StreamedChunk, AccumulatedResponse] =
    _.fold(empty)(accumulate).lastOrError

  /**
   * Accumulate stream to single response.
   */
  def collectAll(stream: Stream[IO, StreamedChunk]): IO[AccumulatedResponse] =
    stream.through(accumulateAll).compile.lastOrError

  /**
   * Accumulate stream to Completion.
   */
  def collectCompletion(stream: Stream[IO, StreamedChunk]): IO[Completion] =
    collectAll(stream).map(_.toCompletion)

  extension (stream: Stream[IO, StreamedChunk])
    def accumulate: IO[AccumulatedResponse] = collectAll(stream)
    def toCompletion: IO[Completion] = collectCompletion(stream)
```

**Testing**:
- Test content accumulation
- Test tool call accumulation
- Test finish reason handling
- Test empty stream handling

---

### Task 3: Create Stream Utilities for Messages

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/MessageStream.scala`

**Purpose**: Stream operations specific to Message types

**Subtasks**:
1. Create utilities for streaming message content
2. Handle message-to-stream conversion
3. Handle stream-to-message conversion (Eino's Concatenate)

**API Design**:
```scala
package org.adk4s.core.streaming

import fs2.{Stream, Pipe}
import cats.effect.IO
import org.adk4s.structured.core.{Message, Role}
import org.llm4s.llmconnect.model.{AssistantMessage, ToolCall}

object MessageStream:
  /**
   * Convert a single message to a stream (Eino's "Box" operation).
   */
  def box(message: Message): Stream[IO, Message] =
    Stream.emit(message)

  /**
   * Convert multiple messages to a stream.
   */
  def fromMessages(messages: Message*): Stream[IO, Message] =
    Stream.emits(messages)

  /**
   * Concatenate message content stream into single message (Eino's "Concatenate").
   */
  def concatenate(role: Role): Pipe[IO, String, Message] =
    _.fold("")(_ + _).map(content => Message(role, content)).lastOrError

  /**
   * Merge multiple message streams into one (Eino's "Merge").
   */
  def merge(streams: Stream[IO, Message]*): Stream[IO, Message] =
    Stream(streams*).parJoinUnbounded

  /**
   * Copy/fan-out a stream to multiple consumers (Eino's "Copy").
   * Returns a stream that when pulled, produces the same elements to all consumers.
   */
  def broadcast[A](stream: Stream[IO, A]): Stream[IO, Stream[IO, A]] =
    Stream.eval(fs2.concurrent.Topic[IO, Option[A]]).flatMap { topic =>
      val publisher = stream.map(Some(_)).through(topic.publish) ++ Stream.eval(topic.publish1(None))
      val subscriber = topic.subscribe(10).unNoneTerminate
      Stream.emit(subscriber).concurrently(publisher)
    }

  extension (message: Message)
    def toStream: Stream[IO, Message] = box(message)

  extension (messages: Seq[Message])
    def toStream: Stream[IO, Message] = Stream.emits(messages)
```

**Testing**:
- Test box operation
- Test concatenate operation
- Test merge operation
- Test broadcast operation

---

### Task 4: Create Streaming LLM Client Wrapper

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`

**Purpose**: Wrap LLM4S client with fs2.Stream interface

**Subtasks**:
1. Create `StreamingLLM[F]` trait with stream methods
2. Implement wrapper for LLM4S client
3. Add convenience methods for common patterns
4. Handle errors properly in stream context

**API Design**:
```scala
package org.adk4s.core.streaming

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, CompletionOptions, Completion, StreamedChunk}
import org.adk4s.core.error.{AdkError, LlmCallError}

/**
 * Streaming-first LLM interface using fs2.Stream.
 */
trait StreamingLLM[F[_]]:
  /**
   * Complete with streaming response.
   */
  def stream(conversation: Conversation, options: CompletionOptions): Stream[F, StreamedChunk]

  /**
   * Complete with streaming response, returning content strings.
   */
  def streamContent(conversation: Conversation, options: CompletionOptions): Stream[F, String]

  /**
   * Complete and accumulate to single response.
   */
  def complete(conversation: Conversation, options: CompletionOptions): F[Completion]

object StreamingLLM:
  /**
   * Create StreamingLLM from LLM4S client.
   */
  def fromClient(client: LLMClient): StreamingLLM[IO] = new StreamingLLM[IO]:
    def stream(conversation: Conversation, options: CompletionOptions): Stream[IO, StreamedChunk] =
      Stream.eval(IO(client.completeStreamed(conversation, options)))
        .flatMap(StreamConverter.fromEither)

    def streamContent(conversation: Conversation, options: CompletionOptions): Stream[IO, String] =
      stream(conversation, options).map(_.content).filter(_.nonEmpty)

    def complete(conversation: Conversation, options: CompletionOptions): IO[Completion] =
      stream(conversation, options).through(ChunkAccumulator.accumulateAll).compile.lastOrError.map(_.toCompletion)

  /**
   * Create StreamingLLM that uses non-streaming API (for testing or fallback).
   */
  def fromNonStreaming(client: LLMClient): StreamingLLM[IO] = new StreamingLLM[IO]:
    def stream(conversation: Conversation, options: CompletionOptions): Stream[IO, StreamedChunk] =
      Stream.eval(IO(client.complete(conversation, options)))
        .flatMap {
          case Right(completion) =>
            Stream.emit(StreamedChunk(completion.content, completion.finishReason, completion.toolCalls))
          case Left(error) =>
            Stream.raiseError[IO](LlmCallError(error))
        }

    def streamContent(conversation: Conversation, options: CompletionOptions): Stream[IO, String] =
      stream(conversation, options).map(_.content).filter(_.nonEmpty)

    def complete(conversation: Conversation, options: CompletionOptions): IO[Completion] =
      IO(client.complete(conversation, options)).flatMap {
        case Right(completion) => IO.pure(completion)
        case Left(error) => IO.raiseError(LlmCallError(error))
      }
```

**Testing**:
- Test streaming completion
- Test content extraction
- Test accumulation to completion
- Test error handling
- Test non-streaming fallback

---

### Task 5: Create Stream Processing Utilities

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamOps.scala`

**Purpose**: Common stream processing operations

**Subtasks**:
1. Create timeout handling for streams
2. Create retry logic for streams
3. Create buffering utilities
4. Create rate limiting utilities

**API Design**:
```scala
package org.adk4s.core.streaming

import fs2.{Stream, Pipe}
import cats.effect.IO
import scala.concurrent.duration.*
import org.adk4s.core.error.AdkError

object StreamOps:
  /**
   * Add timeout to each element in stream.
   */
  def withElementTimeout[A](timeout: FiniteDuration): Pipe[IO, A, A] =
    _.evalMap(a => IO.pure(a).timeout(timeout))

  /**
   * Add timeout to entire stream.
   */
  def withStreamTimeout[A](timeout: FiniteDuration): Pipe[IO, A, A] =
    stream => stream.timeout(timeout)

  /**
   * Retry stream on error with exponential backoff.
   */
  def withRetry[A](
    maxRetries: Int,
    initialDelay: FiniteDuration = 1.second,
    maxDelay: FiniteDuration = 30.seconds
  )(stream: => Stream[IO, A]): Stream[IO, A] =
    def loop(retriesLeft: Int, currentDelay: FiniteDuration): Stream[IO, A] =
      stream.handleErrorWith { error =>
        if retriesLeft > 0 then
          Stream.exec(IO.sleep(currentDelay)) ++
          loop(retriesLeft - 1, (currentDelay * 2).min(maxDelay))
        else
          Stream.raiseError[IO](error)
      }
    loop(maxRetries, initialDelay)

  /**
   * Buffer stream elements with specified capacity.
   */
  def buffered[A](capacity: Int): Pipe[IO, A, A] =
    _.buffer(capacity)

  /**
   * Rate limit stream to specified elements per second.
   */
  def rateLimit[A](elementsPerSecond: Int): Pipe[IO, A, A] =
    _.metered((1.second / elementsPerSecond.max(1)).toMillis.millis)

  /**
   * Log stream elements for debugging.
   */
  def debug[A](prefix: String): Pipe[IO, A, A] =
    _.evalTap(a => IO.println(s"[$prefix] $a"))

  /**
   * Take until a condition is met (inclusive).
   */
  def takeUntilInclusive[A](predicate: A => Boolean): Pipe[IO, A, A] =
    _.flatMap { a =>
      if predicate(a) then Stream.emit(a) ++ Stream.empty
      else Stream.emit(a)
    }

  extension [A](stream: Stream[IO, A])
    def withTimeout(timeout: FiniteDuration): Stream[IO, A] =
      stream.through(withStreamTimeout(timeout))
    def withElementTimeout(timeout: FiniteDuration): Stream[IO, A] =
      stream.through(StreamOps.withElementTimeout(timeout))
    def retryWithBackoff(maxRetries: Int): Stream[IO, A] =
      withRetry(maxRetries)(stream)
    def rateLimited(elementsPerSecond: Int): Stream[IO, A] =
      stream.through(rateLimit(elementsPerSecond))
    def debugLog(prefix: String): Stream[IO, A] =
      stream.through(debug(prefix))
```

**Testing**:
- Test timeout behavior
- Test retry logic with exponential backoff
- Test rate limiting
- Test buffering

---

### Task 6: Create Stream Types Package Object

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/streaming/package.scala`

**Purpose**: Export streaming utilities and provide syntax extensions

**Subtasks**:
1. Export all streaming utilities
2. Provide convenient imports
3. Document usage patterns

**API Design**:
```scala
package org.adk4s.core

import fs2.Stream
import cats.effect.IO

package object streaming:
  // Re-exports
  export StreamConverter.*
  export ChunkAccumulator.*
  export MessageStream.*
  export StreamOps.*

  // Type aliases
  type AdkStream[A] = Stream[IO, A]

  // Syntax imports
  object syntax:
    export StreamConverter.given
    export MessageStream.given
    export ChunkAccumulator.given
    export StreamOps.given
```

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
    │                   └── streaming/
    │                       ├── package.scala          # Exports
    │                       ├── StreamConverter.scala  # Iterator -> Stream
    │                       ├── ChunkAccumulator.scala # Chunk accumulation
    │                       ├── MessageStream.scala    # Message operations
    │                       ├── StreamingLLM.scala     # LLM wrapper
    │                       └── StreamOps.scala        # Utilities
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── core/
                        └── streaming/
                            ├── StreamConverterTest.scala
                            ├── ChunkAccumulatorTest.scala
                            ├── MessageStreamTest.scala
                            ├── StreamingLLMTest.scala
                            └── StreamOpsTest.scala
```

## Testing Plan

### Unit Tests

1. **StreamConverter Tests**
   - Convert successful Iterator to Stream
   - Convert error Either to error Stream
   - Handle empty Iterator
   - Test chunk sizing

2. **ChunkAccumulator Tests**
   - Accumulate content correctly
   - Accumulate tool calls correctly
   - Handle finish reason from last chunk
   - Handle empty stream

3. **MessageStream Tests**
   - Box single message to stream
   - Concatenate content stream to message
   - Merge multiple streams
   - Broadcast to multiple consumers

4. **StreamingLLM Tests**
   - Stream completion returns chunks
   - Content stream filters empty chunks
   - Complete accumulates to single response
   - Errors propagate correctly

5. **StreamOps Tests**
   - Timeout fires on slow elements
   - Retry respects max retries
   - Exponential backoff increases delay
   - Rate limiting works correctly

### Integration Tests

1. Test with real LLM4S client (optional, via env vars)
2. Test full streaming flow from LLM to accumulated response
3. Test concurrent stream operations

## Examples

### Basic Streaming

```scala
import org.adk4s.core.streaming.*
import org.adk4s.core.streaming.syntax.*

val client: LLMClient = LLMClient.create()
val streaming: StreamingLLM[IO] = StreamingLLM.fromClient(client)

// Stream content with real-time output
streaming.streamContent(conversation, options)
  .evalTap(chunk => IO.print(chunk))
  .compile.drain

// Accumulate to completion
val completion: IO[Completion] = streaming.complete(conversation, options)
```

### Stream Operations

```scala
import org.adk4s.core.streaming.*
import scala.concurrent.duration.*

// With timeout
streaming.stream(conversation, options)
  .withTimeout(30.seconds)
  .compile.toList

// With retry
StreamOps.withRetry(maxRetries = 3) {
  streaming.stream(conversation, options)
}.compile.toList

// With debugging
streaming.streamContent(conversation, options)
  .debugLog("LLM")
  .compile.foldMonoid
```

### Message Operations

```scala
import org.adk4s.core.streaming.MessageStream.*

// Box single message
val stream = Message(Role.User, "Hello").toStream

// Concatenate content to message
val contentStream: Stream[IO, String] = ???
val message: IO[Message] = contentStream.through(concatenate(Role.Assistant)).compile.lastOrError

// Merge multiple message streams
val merged = MessageStream.merge(stream1, stream2, stream3)
```

## Completion Criteria

- [ ] All stream conversion utilities implemented
- [ ] ChunkAccumulator working with streaming responses
- [ ] MessageStream operations matching Eino (Box, Concatenate, Merge, Copy)
- [ ] StreamingLLM wrapper complete
- [ ] Stream utilities (timeout, retry, rate limit) implemented
- [ ] Unit tests passing with >90% coverage
- [ ] Integration test with LLM4S verified
- [ ] Documentation updated
