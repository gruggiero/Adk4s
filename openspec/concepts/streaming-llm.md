# Concept: StreamingLLM

## Concept specification

```
concept StreamingLLM[F[_]]
purpose
    Bridge llm4s callback-based streaming into fs2.Stream, and accumulate
    streamed chunks into a Completion-like AccumulatedResponse.
state
    # stateless interface; fromClient captures an LLMClient
actions
    stream [ conversation: Conversation ; options: CompletionOptions ]
        => [ chunks: Stream[F, StreamedChunk] ]
    stream [ conversation ; options ]
        => [ error: Stream.raiseError(LlmCallError) ]
    streamContent [ conversation ; options ]
        => [ content: Stream[F, String] ]
    complete [ conversation ; options ]
        => [ completion: Completion ]
    fromClient [ client: LLMClient ]
        => [ StreamingLLMClient[IO] ]
    fromNonStreaming [ client: LLMClient ]
        => [ StreamingLLMClient[IO] ]   # wraps single Completion as one chunk
operational principle
    A caller obtains a StreamingLLMClient via fromClient (real streaming)
    or fromNonStreaming (simulated). stream calls client.streamComplete
    with a callback that accumulates chunks, then emits them via
    Stream.emits. ChunkAccumulator folds chunks into an
    AccumulatedResponse with concatenated content, first finishReason,
    accumulated toolCalls.
```

## Implementation map

| Element | Code |
|---|---|
| trait `StreamingLLMClient` | `trait StreamingLLMClient[F[_]]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| action `stream` | `StreamingLLMClient.stream(conversation, options): Stream[F, StreamedChunk]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| action `streamContent` | `StreamingLLMClient.streamContent(conversation, options): Stream[F, String]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| action `complete` | `StreamingLLMClient.complete(conversation, options): F[Completion]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| factory `fromClient` | `StreamingLLMClient.fromClient(client): StreamingLLMClient[IO]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| factory `fromNonStreaming` | `StreamingLLMClient.fromNonStreaming(client): StreamingLLMClient[IO]` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`) |
| converter `StreamConverter` | `object StreamConverter` with `fromEither`, `fromLlm4sStream`, `contentStream` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamConverter.scala`) |
| accumulator `ChunkAccumulator` | `object ChunkAccumulator` with `accumulate`, `accumulateAll` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/ChunkAccumulator.scala`) |
| state `AccumulatedResponse` | `case class AccumulatedResponse(content, finishReason, toolCalls, id, created, model, usage, thinking)` (`adk4s-core/src/main/scala/org/adk4s/core/streaming/ChunkAccumulator.scala`) |
| error `LlmCallError` | `LlmCallError(underlying: LLMError)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.core.streaming` |

## Deviations from the pattern

- `fromClient.stream` accumulates all chunks in a mutable `scala.collection.mutable.ListBuffer[StreamedChunk]` inside the callback before emitting via `Stream.emits` — the stream is not lazy; the entire response is buffered in memory (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`).
- `client.streamComplete` blocks until all chunks are received; the callback is synchronous, so `stream` cannot emit incrementally (`adk4s-core/src/main/scala/org/adk4s/core/streaming/StreamingLLM.scala`).
- `ChunkAccumulator.accumulate` takes `chunk.finishReason.orElse(acc.finishReason)` — the first non-empty finishReason wins, but if an early chunk has a transient finishReason it overrides a later, more meaningful one (`adk4s-core/src/main/scala/org/adk4s/core/streaming/ChunkAccumulator.scala`).
- `ChunkAccumulator.accumulate` concatenates toolCalls via `acc.toolCalls ++ chunk.toolCall.toList` — partial tool-call chunks may produce malformed accumulated tool calls (`adk4s-core/src/main/scala/org/adk4s/core/streaming/ChunkAccumulator.scala`).
