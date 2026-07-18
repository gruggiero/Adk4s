# Concept: StructuredLLM

## Concept specification

```
concept StructuredLLM[F[_]]
purpose
    Produce a typed value A from a Prompt by injecting a Schema[A] into the
    prompt, calling the LLM, and parsing the response with
    SchemaAlignedParser. Supports retry on parse failure, constraint
    validation, and streaming-with-final-result.
state
    client: StructuredLLM -> LLMClient
    options: StructuredLLM -> CompletionOptions
    logRawResponse: StructuredLLM -> Boolean
    parseRetryTrigger: StructuredLLM -> Option[ParseRetryTrigger]
    maxParseAttempts: StructuredLLM -> Int
    parseRetryDelay: StructuredLLM -> Duration
actions
    complete [ prompt: Prompt ]
        => [ value: A ]
    complete [ prompt ]
        => [ error: LLMCallFailed("LLM call failed: <underlying>") ]
    complete [ prompt ]
        => [ error: EmptyResponse("LLM returned empty response") ]
    complete [ prompt ]
        => [ error: ParseFailed("Failed to parse LLM response: <errors>; Raw response (truncated): <take 500>") ]
    completeRaw [ prompt ]
        => [ value: A ]   # no schema injection
    completeValidated [ prompt ]
        => [ result: ValidationResult[A] ]
    completeValidated [ prompt ]
        => [ error: ValidationFailed("Validation failed: <failedAsserts>") ]
    streamWithResult [ prompt ]
        => [ (Stream[F, String], F[A]) ]
    streamPartial [ prompt ]
        => [ Stream[F, StreamState[A]] ]   # currently single Complete emission
operational principle
    A caller supplies a Prompt and a Schema[A] in scope. complete injects
    the schema's outputFormatBlock into the last user message, calls the
    LLM, extracts content, optionally logs it, and parses via
    SchemaAlignedParser. If parseRetryTrigger is set, completeRawWithRetry
    retries up to maxParseAttempts with parseRetryDelay between attempts.
```

## Implementation map

| Element | Code |
|---|---|
| trait `StructuredLLM` | `trait StructuredLLM[F[_]]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| impl `StructuredLLMImpl` | `final class StructuredLLMImpl(client, options, logRawResponse, parseRetryTrigger, maxParseAttempts, parseRetryDelay)` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| action `complete` | `StructuredLLM.complete[A: Schema](prompt): F[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| action `completeRaw` | `StructuredLLM.completeRaw[A: Schema](prompt): F[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| action `completeValidated` | `StructuredLLM.completeValidated[A: Schema](prompt): F[ValidationResult[A]]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| action `streamWithResult` | `StructuredLLM.streamWithResult[A: Schema](prompt): F[(Stream[F, String], F[A])]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| action `streamPartial` | `StructuredLLM.streamPartial[A: Schema](prompt): Stream[F, StreamState[A]]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| helper `callLLM` | `StructuredLLMImpl.callLLM(conversation, prompt): F[Completion]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| helper `extractContent` | `StructuredLLMImpl.extractContent(completion, prompt): F[String]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| helper `parseResponse` | `StructuredLLMImpl.parseResponse[A](response): F[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| helper `evaluateConstraints` | `StructuredLLMImpl.evaluateConstraints[A](value, constraints, rawResponse): F[ValidationResult[A]]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| error `LLMCallFailed` | `StructuredLLMError.LLMCallFailed(underlying, prompt)` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| error `ParseFailed` | `StructuredLLMError.ParseFailed(errors, rawResponse)` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| error `EmptyResponse` | `StructuredLLMError.EmptyResponse(prompt)` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| error `ValidationFailed` | `StructuredLLMError.ValidationFailed(failedAsserts)` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`) |
| retry trigger | `ParseRetryTrigger` (`structured-llm/src/main/scala/org/adk4s/structured/core/ParseRetryTrigger.scala`) |
| runtime host | `org.adk4s.structured.core` |

## Synchronizations

```
sync SchemaInjection
when {
    StructuredLLM/complete: called with Prompt and Schema[A]
}
then {
    Prompt/withOutputFormat: appends Schema[A].outputFormatBlock to last user message
}
```

impl: `StructuredLLMImpl.complete[A]` calls `prompt.withOutputFormat[A]` then `completeRaw[A]` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).
Deviation: `Prompt.withOutputFormat` directly summons `Schema[A]`, tightly coupling Prompt to Schema.

```
sync ResponseToParser
when {
    StructuredLLM/completeRawSingle: has LLM response content
}
then {
    SchemaAlignedParser/parse: response -> ParseResult[A]
}
```

impl: `parseResponse[A]` calls `SchemaAlignedParser.parse[A](response)` and lifts Failure to `ParseFailed` (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).

## Deviations from the pattern

- `streamPartial` is a stub: it delegates to `complete[A]` and emits a single `StreamState.complete(value)`. A comment admits "Full incremental streaming will be added in a future iteration" (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).
- `streamWithResultRaw` uses mutable `ListBuffer[StreamedChunk]` and `StringBuilder` to accumulate chunks, violating the project's no-mutable-state rule (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).
- `completeRawWithRetry` raises `"Max parse retries exhausted"` only when `remaining <= 0`; the boundary between "retry once more" and "exhausted" is off-by-one sensitive and not covered by a dedicated error variant (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).
- `evaluateConstraints` runs `Constraint.evaluateStrictAll` then `Constraint.evaluateAll` — predicates are evaluated twice when all pass (`structured-llm/src/main/scala/org/adk4s/structured/core/StructuredLLM.scala`).
- `ParseRetryTrigger.All` includes `LLMCallFailed`, so parse-retry can retry LLM-call failures that llm4s middleware may also retry — potential double retry (`structured-llm/src/main/scala/org/adk4s/structured/core/ParseRetryTrigger.scala`).
