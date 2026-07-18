# Concept: ChatModel

## Concept specification

```
concept ChatModel[F[_]]
purpose
    Call an LLM to produce a completion (or a stream of chunks) for a
    conversation, parameterised by an effect F and a ChatModelConfig.
state
    client: ChatModel -> LLMClient
    config: ChatModel -> ChatModelConfig
actions
    generate [ conversation: Conversation ]
        => [ completion: Completion ]
    generate [ conversation: Conversation ; options: CompletionOptions ]
        => [ completion: Completion ]
    generate [ conversation: Conversation ]
        => [ error: LlmCallError("LLM call failed: <underlying.formatted>") ]
    stream [ conversation: Conversation ]
        => [ chunks: Stream[F, StreamedChunk] ]
    stream [ conversation: Conversation ]
        => [ error: Stream.raiseError(LlmCallError) ]
    streamContent [ conversation: Conversation ]
        => [ content: Stream[F, String] ]
    withConfig [ newConfig: ChatModelConfig ]
        => [ model: ChatModel[F] ]
operational principle
    A caller builds a Conversation, hands it to generate, and either receives
    a Completion or an LlmCallError wrapping the underlying LLMError. Calling
    withConfig returns a new ChatModel with replaced options, leaving the
    original unchanged.
```

## Implementation map

| Element | Code |
|---|---|
| state `client` | `LLMClient` captured in `ChatModel.fromLlm4s` closure (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| state `config` | `ChatModelConfig` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| action `generate` | `ChatModel.generate` -> `client.complete` -> `F.pure` or `F.raiseError(LlmCallError)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| action `stream` | `ChatModel.stream` -> `client.streamComplete` -> `Stream.emits` or `Stream.raiseError(LlmCallError)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| action `streamContent` | `ChatModel.streamContent` converts chunks to `chunk.content.getOrElse("")` and filters empties (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| action `withConfig` | `ChatModel.fromLlm4s(client, newConfig)` (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`) |
| error `LlmCallError` | `LlmCallError(underlying: LLMError)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.core.component` |

## Deviations from the pattern

- `stream` accumulates all chunks in a mutable `scala.collection.mutable.ListBuffer[StreamedChunk]` before emitting via `Stream.emits` — not lazy/streaming (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`).
- `streamContent` silently drops chunks whose `content` is empty (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`).
- `ChatModelConfig.toCompletionOptions` silently defaults `temperature` to `0.7` and `topP` to `1.0` when `None` — caller cannot distinguish "unset" from "default" (`adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`).
