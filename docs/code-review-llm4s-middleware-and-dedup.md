# Code Review: llm4s-middleware-and-dedup Changes

## Bugs

### 1. `RetryTrigger.LLMError` incorrectly maps to `ParseRetryTrigger.ParseFailed`

`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala:257`

```scala
case RetryTrigger.LLMError          => ParseRetryTrigger.ParseFailed // LLM errors handled by RetryStructuredLLM
```

When a user requests retry **only on LLM errors** via `fromClientWithRetry(..., RetryTrigger.LLMError, ...)`, the inner `StructuredLLMImpl` gets `parseRetryTrigger = Some(ParseRetryTrigger.ParseFailed)`, which causes it to **also retry on parse failures**. This is unexpected — the user asked for LLM-error-only retry but gets parse-failure retry as a side effect.

The correct mapping should be `None` (no parse retry), letting the outer `RetryStructuredLLM` handle LLM error retries exclusively.

### 2. `MessageStream.messageForRole` silently converts `ToolMessage` to `UserMessage`

`adk4s-core/src/main/scala/org/adk4s/core/streaming/MessageStream.scala:37`

```scala
case other                 => UserMessage(content)
```

The fallback case converts any unrecognized message type (including `ToolMessage`) to `UserMessage`. If `concatenate(MessageRole.Tool)` is called, the resulting message is mislabeled as a user message, silently corrupting the conversation role. The same pattern exists in `structured-llm/src/main/scala/org/adk4s/structured/template/PromptSyntax.scala:137`:

```scala
case MessageRole.Tool      => UserMessage(content)
```

### 3. `ChatTemplate.substituteMessageContent` skips `ToolMessage`

`adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala:56`

```scala
case other =>
  other
```

Variable substitution is skipped for `ToolMessage` and any other non-standard message type. If a `ToolMessage` contains `{variable}` placeholders, they will remain unresolved in the final prompt sent to the LLM.

## Code Quality Issues

### 4. Dead code: `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable`

`adk4s-core/src/main/scala/org/adk4s/core/tools/ToolsNodeConfig.scala:35-56`

After the `ToolWrapper` refactor (which now stores `ToolFunction[?, ?]` directly), the following are no longer used in production code:

- `trait SafeToolExecutable` (line 35)
- `opaque type ToolFunctionAdapter` and its companion object (lines 39-56)
- `StructuredToolFunction.toSafeExecutable` (`adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala:120`)

These should be removed to avoid confusion.

### 5. `StructuredToolFunction.toToolFunction` uses permissive schema with no parameters

`adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala:152-153`

```scala
val schemaDef: ObjectSchema[ujson.Value] =
  ObjectSchema[ujson.Value](stf.description, Seq.empty, false)
```

The synthesized `ToolFunction` exposes an empty parameter schema to the LLM. The actual parameter validation happens in the handler via `stf.inputSchema.decoder`. This means the LLM doesn't see what parameters the tool accepts — only the description. The `stf.inputSchema.jsonSchema` is available but not passed to the `ObjectSchema`, which could significantly degrade the LLM's ability to call the tool correctly.

### 6. `StructuredToolFunction.toToolFunction` loses structured error information

`adk4s-core/src/main/scala/org/adk4s/core/tools/StructuredToolFunction.scala:160,163`

```scala
case Left(err: ToolSchemaError) => Left(err.message)
```

Errors are flattened to plain strings. Compare with `toSafeExecutable` (line 134) which preserves structured `ToolCallError.InvalidArguments` / `ToolCallError.ExecutionError` variants. When using `toToolWrapper` (the preferred path), error diagnostics are less informative.

### 7. `ClientStrategy.executeFallback` — `clientNames` can be misaligned

`structured-llm/src/main/scala/org/adk4s/structured/core/ClientStrategy.scala:69-71`

```scala
val name: String = clientNames.headOption.getOrElse("unknown")
executeFallback(tail, operation, clientNames.drop(1), errors :+ record)
```

If `clientNames` is shorter than `clients`, remaining clients get name `"unknown"` in `AttemptRecord`. Error messages will be misleading for debugging. This is pre-existing but worth noting.

## Summary

The most impactful bug is **#1** (incorrect retry trigger mapping) — it changes runtime behavior for users of the deprecated `fromClientWithRetry` API. **#2** and **#3** are data integrity issues for edge cases involving `ToolMessage`. **#4-6** are code quality issues that should be addressed but won't cause runtime failures.
