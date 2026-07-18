# Concept: Tool

## Concept specification

```
concept Tool[F[_]]
purpose
    Expose a named, described, JSON-schema-parameterised capability to an LLM
    and to the runtime, in three tiers: metadata-only Tool, invokable
    InvokableTool, and streamable StreamableTool.
state
    info: Tool -> AdkToolInfo
    handler: InvokableTool -> (ujson.Value => F[ujson.Value])
    streamHandler: StreamableTool -> (ujson.Value => Stream[F, String])
actions
    invokable [ name: String ; description: String ; handler: ujson.Value => Either[String, ujson.Value] ]
        => [ tool: InvokableTool[F] ]
    streamable [ name: String ; description: String ; handler: ujson.Value => Stream[F, String] ]
        => [ tool: StreamableTool[F] ]
    fromLlm4s [ toolFunction: ToolFunction[?, ?] ]
        => [ tool: InvokableTool[F] ]
    run [ arguments: ujson.Value ]
        => [ result: ujson.Value ]
    run [ arguments: ujson.Value ]
        => [ error: ToolExecutionError("Tool '<name>' execution failed: <cause.getMessage>") ]
    run [ arguments: ujson.Value ]
        => [ error: RuntimeException("Tool execution failed: <err>") ]   # fromLlm4s path
    runStream [ arguments: ujson.Value ]
        => [ chunks: Stream[F, String] ]
operational principle
    A caller builds a tool via Tool.invokable with a handler returning
    Either[String, ujson.Value]; the runtime calls run(arguments) and
    receives either the Right value or a ToolExecutionError wrapping the
    Left message. A StreamableTool returns a Stream of string chunks
    instead, propagating handler errors as stream failures.
```

## Implementation map

| Element | Code |
|---|---|
| state `info` | `AdkToolInfo(name, description, parameters)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| trait `Tool` | `trait Tool[F[_]]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| trait `InvokableTool` | `trait InvokableTool[F[_]] extends Tool[F]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| trait `StreamableTool` | `trait StreamableTool[F[_]] extends Tool[F]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| action `invokable` | `Tool.invokable[IO](name, description, handler)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| action `streamable` | `Tool.streamable[IO](name, description, handler)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| action `fromLlm4s` | `Tool.fromLlm4s[IO](toolFunction)` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| action `run` | `InvokableTool.run(arguments): F[ujson.Value]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| action `runStream` | `StreamableTool.runStream(arguments): Stream[F, String]` (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`) |
| error `ToolExecutionError` | `ToolExecutionError(toolName, cause)` (`adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`) |
| runtime host | `org.adk4s.core.component` |

## Deviations from the pattern

- `AdkToolInfo.buildObjectSchema` uses `asInstanceOf` (suppressed via `@SuppressWarnings`) for schema type conversion (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`).
- `AdkToolInfo.toToolFunction` always returns a `ToolFunction` whose handler yields `Left("AdkToolInfo wrapper: execution not supported via ToolFunction")` — a non-executable wrapper that silently fails at execution time (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`).
- `Tool.fromLlm4s` raises a generic `RuntimeException("Tool execution failed: <err>")` rather than the structured `ToolExecutionError` used by `Tool.invokable` — two error vocabularies for the same action (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`).
- `Tool.streamable` propagates handler errors as raw stream failures with no wrapping (`adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`).
