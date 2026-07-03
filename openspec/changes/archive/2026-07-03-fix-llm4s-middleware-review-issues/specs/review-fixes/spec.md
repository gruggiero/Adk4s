# Spec: llm4s-middleware-and-dedup review fixes

<!-- Delta spec. One requirement per review finding. Driven by
     docs/code-review-llm4s-middleware-and-dedup.md and the escape analysis
     docs/escape-analysis-code-review-llm4s-middleware-and-dedup.md. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `StructuredLLM.fromClientWithRetry` | deprecated factory | `org.adk4s.structured.core` |
| `RetryTrigger` | enum | `org.adk4s.structured.core` |
| `ParseRetryTrigger` | enum | `org.adk4s.structured.core` |
| `MessageStream.concatenate` | method | `org.adk4s.core.streaming` |
| `MessageRole` (llm4s) | enum | `org.llm4s.llmconnect.model` |
| `ChatTemplate` | trait | `org.adk4s.core.component` |
| `StructuredToolFunction.toToolFunction` | method | `org.adk4s.core.tools` |
| `ToolWrapper` | case class | `org.adk4s.core.tools` |
| `ClientStrategy.executeFallback` | method | `org.adk4s.structured.core` |

## Concepts Introduced (new)

None. (Removes: `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable`.)

## ADDED Requirements

### Requirement: LLMError-only retry must not enable parse-failure retry

The deprecated `fromClientWithRetry(trigger = RetryTrigger.LLMError)` SHALL NOT
enable parse-failure retry; parse-retry is enabled only for `ParseFailure`,
`ValidationFailure`, and `All`.

**Given** a `fromClientWithRetry` call with `trigger = RetryTrigger.LLMError` and
an inner client whose first response is malformed JSON (a parse failure, not an
`LLMError`)
**When** a structured completion is requested
**Then** the inner client is called exactly once and the result is a
`StructuredLLMError.ParseFailed` (parse failures are not retried)

Rationale: previously `LLMError` was mis-mapped to `ParseRetryTrigger.ParseFailed`,
silently enabling the parse-retry loop the caller did not request.

#### Scenario: LLMError trigger does not retry on parse failures

**Given** `fromClientWithRetry(trigger = RetryTrigger.LLMError, maxAttempts = 3)`
and an inner client returning malformed JSON
**When** a structured completion is requested
**Then** `client.getCallCount == 1` and the error is `StructuredLLMError.ParseFailed`

### Requirement: Tool role is preserved by message assembly, never mislabelled

Message assembly SHALL preserve the `Tool` role (for both `MessageStream.concatenate`
and `PromptSyntax.messageForRole`) when assembling a `Tool` message, rather than
silently converting it to `UserMessage`.

**Given** a `Tool` role passed to a message-assembly helper
**When** the message is built
**Then** its `role` is `MessageRole.Tool` (not `MessageRole.User`)

Rationale: after `type Role = MessageRole`, the catch-all `case other =>
UserMessage(...)` silently mislabelled tool results as user messages.

#### Scenario: concatenate over the Tool role yields a tool message

**Given** `Stream("a", "b").through(MessageStream.concatenate(MessageRole.Tool))`
**When** the resulting message is inspected
**Then** its `role == MessageRole.Tool` and its content is `"ab"`

### Requirement: Variable substitution applies to ToolMessage content

`ChatTemplate` substitution SHALL apply to `ToolMessage.content` and SHALL preserve
`toolCallId`, instead of skipping tool messages entirely.

**Given** a `ChatTemplate` containing a `ToolMessage("{result}", toolCallId = "c1")`
**When** formatted with variables `Map("result" -> "ok")`
**Then** the resulting tool message has `content == "ok"` and `toolCallId == "c1"`

Rationale: previously `case other => other` no-op'd substitution for `ToolMessage`,
leaving `{variable}` placeholders unresolved in tool results sent to the LLM.

#### Scenario: ToolMessage placeholder substituted, id preserved

**Given** `ChatTemplate.simple(List(ToolMessage("{result}", "c1")))`
**When** formatted with `Map("result" -> "ok")`
**Then** the conversation contains one `ToolMessage` with `content == "ok"` and
`toolCallId == "c1"`

### Requirement: Synthesized ToolFunction exposes its parameters to the LLM

`StructuredToolFunction.toToolFunction` SHALL derive its LLM-facing schema from
`inputSchema.jsonSchema` so the LLM sees the tool's parameters (names/types/
required), not an empty schema.

**Given** a `StructuredToolFunction[AddRequest, AddResult]` whose input has fields
`a: Int, b: Int` (both required)
**When** `stf.toToolFunction.schema.toJsonSchema(false)` is inspected
**Then** `properties` contains `a` and `b` typed `"integer"`, and `required` lists
both `a` and `b`

Rationale: the synthesized tool was technically registry-visible but practically
uncallable — the LLM saw only the description.

#### Scenario: input parameters appear in the LLM-facing schema

**Given** `makeAddStructuredTool.toToolFunction`
**When** the schema is serialized
**Then** `properties.a.type == "integer"`, `properties.b.type == "integer"`, and
`required` contains `a` and `b`

### Requirement: Tool execution errors surface with faithful, field-level detail

Errors from a synthesized `ToolFunction` SHALL surface as
`ToolCallError.HandlerError(toolName, message)` where `message` preserves the
field/path from the underlying `ToolSchemaError` (e.g. "Missing required field
'a'"). The test oracle asserts the variant and message exactly — no loosened
`|| contains` fallback.

**Given** a `StructuredToolFunction` and a call with a missing required field `a`
**When** `stf.toToolFunction.execute(ujson.Obj())` is called
**Then** the result is `Left(HandlerError("add", msg))` and `msg` contains
"Missing required field 'a'"

Rationale (and feasibility note): llm4s `ToolFunction` handlers return
`Either[String, R]`, so a structured `InvalidArguments` variant cannot be carried
through this boundary; the faithful oracle asserts the achievable `HandlerError`
shape. (Per spec-lint check 14 / error-variant feasibility.)

#### Scenario: missing required field surfaces as HandlerError naming the field

**Given** `makeAddStructuredTool.toToolFunction.execute(ujson.Obj())`
**When** the result is matched
**Then** it is `Left(HandlerError("add", _))` whose message contains
"Missing required field 'a'"

### Requirement: Refactors remove orphaned tool-execution adapters

Production sources SHALL not contain the orphaned adapters `SafeToolExecutable`,
`ToolFunctionAdapter`, or `toSafeExecutable` after the `ToolWrapper` refactor (no
production code references them anymore).

**Given** the codebase after the `ToolWrapper` refactor
**When** searched for `SafeToolExecutable` / `ToolFunctionAdapter` / `toSafeExecutable`
**Then** no production source references them (only historical test/type-contract
records, updated to not depend on the removed symbols)

#### Scenario: removed symbols absent from production

**Given** the `adk4s-core` main sources
**When** grepped for `SafeToolExecutable`, `ToolFunctionAdapter`, `toSafeExecutable`
**Then** zero matches outside removed definitions

### Requirement: Fallback clients are named by index, not "unknown"

`ClientStrategy.executeFallback` SHALL name each client by its position
(`client-<index>`) when no caller-supplied name is available, so `AttemptRecord`
names are unambiguous when `clientNames` is shorter than `clients`.

**Given** a fallback over 3 clients with a `clientNames` vector of length 1
**When** all 3 fail
**Then** the `AttemptRecord` names are distinct (the unnamed ones are `client-1`,
`client-2`, not repeated `"unknown"`)

#### Scenario: short clientNames vector yields index-based names

**Given** `executeFallback(clients = 3, clientNames = Vector("only"))`
**When** every client fails
**Then** the recorded names include `client-1` and `client-2` for the unnamed clients
