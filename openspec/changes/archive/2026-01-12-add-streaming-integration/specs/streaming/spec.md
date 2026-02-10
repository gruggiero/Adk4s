## ADDED Requirements

### Requirement: Stream Conversion Utilities
The system SHALL provide utilities to convert LLM4S Iterator-based streaming to fs2.Stream with proper resource management, error handling, and chunk sizing for efficient streaming.

#### Scenario: Convert successful Iterator to Stream
- **GIVEN** an Iterator of StreamedChunk from LLM4S
- **WHEN** fromIterator is called with the iterator
- **THEN** a fs2.Stream[IO, StreamedChunk] is created
- **AND** elements are emitted with default chunk size of 1
- **AND** stream can be consumed by fs2 operations

#### Scenario: Convert error Either to error Stream
- **GIVEN** Either[LLMError, Iterator] containing Left with error
- **WHEN** fromEither is called on the result
- **THEN** a Stream that raises LlmCallError is returned
- **AND** the underlying LLMError is wrapped in LlmCallError

#### Scenario: Convert successful Either to Stream
- **GIVEN** Either[LLMError, Iterator] containing Right with iterator
- **WHEN** fromEither is called on the result
- **THEN** a Stream that emits elements from the iterator is returned
- **AND** no error is raised

#### Scenario: Convert LLM4S streaming result to fs2.Stream
- **GIVEN** Either[LLMError, Iterator[StreamedChunk]] from LLM4S
- **WHEN** fromLlm4sStream is called on the result
- **THEN** a Stream[IO, StreamedChunk] is returned
- **AND** Left values become stream errors
- **AND** Right values become element streams

#### Scenario: Extract content from streaming result
- **GIVEN** Either[LLMError, Iterator[StreamedChunk]] with multiple chunks
- **WHEN** contentStream is called on the result
- **THEN** a Stream[IO, String] is returned
- **AND** content is extracted from each StreamedChunk
- **AND** empty content strings are filtered out

#### Scenario: Handle empty Iterator conversion
- **GIVEN** an empty Iterator of StreamedChunk
- **WHEN** fromIterator is called on the empty iterator
- **THEN** an empty Stream[IO, StreamedChunk] is created
- **AND** consuming the stream completes successfully with no elements

#### Scenario: Use extension method to convert Either to Stream
- **GIVEN** Either[LLMError, Iterator[String]] with Right value
- **WHEN** toFs2Stream extension method is called
- **THEN** the result is equivalent to calling fromEither

### Requirement: StreamedChunk Accumulator
The system SHALL provide a ChunkAccumulator that accumulates streaming StreamedChunk objects into a complete response, handling content concatenation, tool call accumulation, and finish reason tracking.

#### Scenario: Accumulate content from multiple chunks
- **GIVEN** an empty AccumulatedResponse
- **AND** two StreamedChunk objects with content "Hello" and " World"
- **WHEN** accumulate is called with each chunk
- **THEN** the resulting AccumulatedResponse has content "Hello World"
- **AND** finishReason is None
- **AND** toolCalls is empty

#### Scenario: Accumulate tool calls from chunks
- **GIVEN** an empty AccumulatedResponse
- **AND** StreamedChunk with toolCalls containing ToolCall A
- **AND** StreamedChunk with toolCalls containing ToolCall B
- **WHEN** accumulate is called with each chunk
- **THEN** the resulting AccumulatedResponse has both ToolCall A and ToolCall B
- **AND** toolCalls are accumulated in order

#### Scenario: Handle finish reason from final chunk
- **GIVEN** an AccumulatedResponse with finishReason None
- **AND** a StreamedChunk with finishReason Some("stop")
- **WHEN** accumulate is called with the chunk
- **THEN** the resulting AccumulatedResponse has finishReason Some("stop")

#### Scenario: Use accumulateAll pipe to collect chunks
- **GIVEN** a Stream[IO, StreamedChunk] with multiple chunks
- **WHEN** the stream is passed through accumulateAll pipe
- **THEN** the output Stream[IO, AccumulatedResponse] emits single element
- **AND** the element contains accumulated content and tool calls

#### Scenario: Collect all chunks via collectAll
- **GIVEN** a Stream[IO, StreamedChunk] with content "Hello" and " World"
- **WHEN** collectAll is called on the stream
- **THEN** IO[AccumulatedResponse] is returned
- **AND** the result has content "Hello World"

#### Scenario: Collect completion via collectCompletion
- **GIVEN** a Stream[IO, StreamedChunk] with content "Response"
- **AND** finishReason Some("stop")
- **WHEN** collectCompletion is called on the stream
- **THEN** IO[Completion] is returned
- **AND** the Completion has content "Response"
- **AND** the Completion has finishReason Some("stop")

#### Scenario: Handle empty stream accumulation
- **GIVEN** an empty Stream[IO, StreamedChunk]
- **WHEN** collectAll is called on the stream
- **THEN** IO[AccumulatedResponse] is returned
- **AND** the result has empty content
- **AND** the result has None finishReason
- **AND** the result has empty toolCalls

#### Scenario: Use accumulate extension on stream
- **GIVEN** a Stream[IO, StreamedChunk] with chunks
- **WHEN** accumulate extension method is called
- **THEN** IO[AccumulatedResponse] is returned
- **AND** the result equals collectAll(stream)

### Requirement: Message Stream Operations
The system SHALL provide stream operations for Message types including Box (single message to stream), Concatenate (content stream to message), Merge (multiple streams to one), and Copy (fan-out to multiple consumers).

#### Scenario: Box single message to stream
- **GIVEN** a Message with Role.User and content "Hello"
- **WHEN** box is called on the message
- **THEN** a Stream[IO, Message] is returned
- **AND** the stream emits the single message
- **AND** consuming the stream completes after emitting the message

#### Scenario: Convert multiple messages to stream
- **GIVEN** three Message objects
- **WHEN** fromMessages is called with the messages
- **THEN** a Stream[IO, Message] is returned
- **AND** the stream emits the messages in order
- **AND** consuming the stream completes after all messages

#### Scenario: Concatenate content stream into single message
- **GIVEN** a Stream[IO, String] emitting "Hello", " ", "World"
- **AND** Role.Assistant
- **WHEN** concatenate(Assistant) pipe is applied to the stream
- **THEN** the output Stream[IO, Message] emits single Message
- **AND** the Message has Role.Assistant
- **AND** the Message has content "Hello World"

#### Scenario: Merge multiple message streams into one
- **GIVEN** two Stream[IO, Message] objects
- **WHEN** merge is called with the streams
- **THEN** a single Stream[IO, Message] is returned
- **AND** messages from both streams are emitted in merged order
- **AND** both streams run concurrently

#### Scenario: Broadcast stream to multiple consumers
- **GIVEN** a Stream[IO, String] emitting values
- **WHEN** broadcast is called on the stream
- **THEN** a Stream[IO, Stream[IO, String]] is returned
- **AND** each emitted value is produced to all consumers
- **AND** consumers receive values in order

#### Scenario: Use toStream extension on single message
- **GIVEN** a Message object
- **WHEN** toStream extension method is called
- **THEN** a Stream[IO, Message] is returned
- **AND** the stream emits the single message

#### Scenario: Use toStream extension on message sequence
- **GIVEN** a Seq[Message] with multiple messages
- **WHEN** toStream extension method is called
- **THEN** a Stream[IO, Message] is returned
- **AND** the stream emits all messages in sequence

### Requirement: Streaming LLM Client Wrapper
The system SHALL provide a StreamingLLM wrapper that converts LLM4S client to use fs2.Stream interface with streaming, content extraction, and completion accumulation methods.

#### Scenario: Stream completion with chunks
- **GIVEN** a StreamingLLM[IO] wrapping an LLMClient
- **AND** a Conversation and CompletionOptions
- **WHEN** stream method is called
- **THEN** a Stream[IO, StreamedChunk] is returned
- **AND** chunks are emitted as LLM4S returns them
- **AND** Left results become stream errors

#### Scenario: Stream content with real-time output
- **GIVEN** a StreamingLLM[IO] wrapping an LLMClient
- **AND** a Conversation and CompletionOptions
- **WHEN** streamContent method is called
- **THEN** a Stream[IO, String] is returned
- **AND** content is extracted from chunks
- **AND** empty content strings are filtered

#### Scenario: Complete with accumulation to single response
- **GIVEN** a StreamingLLM[IO] wrapping an LLMClient
- **AND** a Conversation and CompletionOptions
- **WHEN** complete method is called
- **THEN** IO[Completion] is returned
- **AND** the Completion contains accumulated content from all chunks
- **AND** the Completion contains tool calls from all chunks
- **AND** the Completion contains finish reason from final chunk

#### Scenario: Handle errors in streaming context
- **GIVEN** a StreamingLLM[IO] wrapping an LLMClient
- **AND** the LLMClient returns Left with LLMError
- **WHEN** stream method is called
- **THEN** the Stream[IO, StreamedChunk] raises LlmCallError
- **AND** the underlying LLMError is wrapped

#### Scenario: Use non-streaming API for fallback
- **GIVEN** a StreamingLLM[IO] created with fromNonStreaming
- **AND** the underlying LLMClient only supports non-streaming
- **WHEN** stream method is called
- **THEN** a Stream[IO, StreamedChunk] is returned
- **AND** the stream emits single chunk from Completion
- **AND** Left results become stream errors

### Requirement: Stream Processing Utilities
The system SHALL provide stream processing utilities including timeout handling (per-element and entire stream), retry logic with exponential backoff, buffering, and rate limiting.

#### Scenario: Add timeout to each stream element
- **GIVEN** a Stream[IO, Int] with slow elements
- **AND** a timeout of 5 seconds
- **WHEN** withElementTimeout pipe is applied to the stream
- **THEN** each element completes within timeout or stream fails
- **AND** timeout error is raised if element exceeds timeout

#### Scenario: Add timeout to entire stream
- **GIVEN** a Stream[IO, Int] that takes time to complete
- **AND** a timeout of 10 seconds
- **WHEN** withStreamTimeout pipe is applied to the stream
- **THEN** the stream completes within timeout or fails
- **AND** timeout error is raised if stream exceeds timeout

#### Scenario: Retry stream on error with exponential backoff
- **GIVEN** a Stream[IO, Int] that fails on first attempt
- **AND** maxRetries of 3
- **WHEN** withRetry is applied to the stream
- **THEN** the stream is retried up to maxRetries times
- **AND** delay between retries doubles each time (exponential backoff)
- **AND** delay does not exceed maxDelay
- **AND** final failure is raised if all retries exhausted

#### Scenario: Buffer stream elements with capacity
- **GIVEN** a fast-producing Stream[IO, Int]
- **AND** a buffer capacity of 10
- **WHEN** buffered pipe is applied to the stream
- **THEN** up to 10 elements are buffered
- **AND** producer is slowed if buffer is full
- **AND** consumer can pull at its own pace

#### Scenario: Rate limit stream elements
- **GIVEN** a Stream[IO, Int]
- **AND** rate limit of 5 elements per second
- **WHEN** rateLimit pipe is applied to the stream
- **THEN** no more than 5 elements are emitted per second
- **AND** additional elements are delayed to maintain rate

#### Scenario: Take elements until condition met inclusive
- **GIVEN** a Stream[IO, Int] emitting 1, 2, 3, 4, 5
- **AND** predicate that returns true for values >= 4
- **WHEN** takeUntilInclusive pipe is applied to the stream
- **THEN** stream emits 1, 2, 3, 4
- **AND** stream terminates after first element matching predicate

#### Scenario: Log stream elements for debugging
- **GIVEN** a Stream[IO, String] with values "a", "b", "c"
- **AND** a prefix "DEBUG"
- **WHEN** debug pipe with prefix is applied to the stream
- **THEN** each element is printed with prefix "[DEBUG]"
- **AND** elements pass through unchanged

#### Scenario: Use withTimeout extension on stream
- **GIVEN** a Stream[IO, Int]
- **AND** a timeout
- **WHEN** withTimeout extension is called
- **THEN** the result equals applying withStreamTimeout pipe

#### Scenario: Use retryWithBackoff extension on stream
- **GIVEN** a Stream[IO, Int]
- **AND** maxRetries value
- **WHEN** retryWithBackoff extension is called
- **THEN** the result equals applying withRetry pipe

### Requirement: Streaming Package Exports
The system SHALL provide a streaming package object that exports all streaming utilities, provides type aliases, and offers syntax extensions for convenient imports.

#### Scenario: Export StreamConverter utilities
- **GIVEN** import of org.adk4s.core.streaming.*
- **WHEN** StreamConverter methods are accessed
- **THEN** fromIterator, fromEither, fromLlm4sStream, contentStream are available
- **AND** toFs2Stream extension is available

#### Scenario: Export ChunkAccumulator utilities
- **GIVEN** import of org.adk4s.core.streaming.*
- **WHEN** ChunkAccumulator methods are accessed
- **THEN** empty, accumulate, accumulateAll, collectAll, collectCompletion are available
- **AND** accumulate, toCompletion extensions on Stream are available

#### Scenario: Export MessageStream utilities
- **GIVEN** import of org.adk4s.core.streaming.*
- **WHEN** MessageStream methods are accessed
- **THEN** box, fromMessages, concatenate, merge, broadcast are available
- **AND** toStream extension on Message and Seq are available

#### Scenario: Export StreamOps utilities
- **GIVEN** import of org.adk4s.core.streaming.*
- **WHEN** StreamOps methods are accessed
- **THEN** withElementTimeout, withStreamTimeout, withRetry, buffered, rateLimit, debug, takeUntilInclusive are available
- **AND** all extension methods on Stream are available

#### Scenario: Use AdkStream type alias
- **GIVEN** code needing to use Stream[IO, A]
- **WHEN** AdkStream[A] type is used
- **THEN** AdkStream[A] is equivalent to Stream[IO, A]
- **AND** type alias simplifies type signatures

#### Scenario: Import syntax extensions
- **GIVEN** import of org.adk4s.core.streaming.syntax.*
- **WHEN** extension methods are used
- **THEN** all given extension instances are available without separate imports
