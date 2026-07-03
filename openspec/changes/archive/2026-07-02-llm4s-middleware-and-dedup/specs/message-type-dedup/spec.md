# Spec: Message Type Deduplication

<!-- This is a DELTA spec. It replaces structured-llm's Message/Role with
     llm4s Message subtypes, making Prompt wrap Conversation directly. -->

## Concepts Used (from inventory)

| Concept | Kind | Package |
|---------|------|---------|
| `Message` (llm4s) | sealed trait | `org.llm4s.llmconnect.model` |
| `SystemMessage` | case class (extends Message) | `org.llm4s.llmconnect.model` |
| `UserMessage` | case class (extends Message) | `org.llm4s.llmconnect.model` |
| `AssistantMessage` | case class (extends Message) | `org.llm4s.llmconnect.model` |
| `ToolMessage` | case class (extends Message) | `org.llm4s.llmconnect.model` |
| `Conversation` | case class | `org.llm4s.llmconnect.model` |
| `Prompt` | case class | `org.adk4s.structured.core` |
| `PromptTemplate[-I]` | trait | `org.adk4s.structured.core` |
| `Schema[A]` | opaque type | `org.adk4s.structured.core` |
| `StructuredLLM[F[_]]` | trait | `org.adk4s.structured.core` |
| `Message` (adk4s) | case class | `org.adk4s.structured.core` |
| `Role` (adk4s) | enum | `org.adk4s.structured.core` |
| `MessageConverter` | object | `org.adk4s.core.types` |
| `ConversationConverter` | object | `org.adk4s.core.types` |
| `AgentToolState` | case class (derives ReadWriter) | `org.adk4s.core.component` |
| `SerializableMessage` | case class (derives ReadWriter) | `org.adk4s.core.component` |

## Concepts Introduced (new)

| Concept | Kind | Description |
|---------|------|-------------|
| `Prompt(conversation: Conversation)` | case class (refactored) | `Prompt` now wraps `Conversation` directly; the `messages: Vector[Message]` field is removed |
| `Message` (deprecated alias) | type alias | `type Message = org.llm4s.llmconnect.model.Message` — one-release shim for source compatibility |
| `Role` (deprecated alias) | type alias | `type Role = org.llm4s.llmconnect.model.MessageRole` — one-release shim |

## ADDED Requirements

### Requirement: Prompt wraps Conversation directly

The system SHALL refactor `Prompt` to wrap a llm4s `Conversation` directly as its `conversation: Conversation` field, eliminating the `messages: Vector[Message]` field and the lossy `MessageConverter`/`ConversationConverter` conversions.

**Given** a `Prompt` constructed via `Prompt.simple(systemPrompt, userMessage)`
**When** the `conversation` field is accessed
**Then** it contains a `Conversation` with exactly two messages: a `SystemMessage(systemPrompt)` and a `UserMessage(userMessage)`, in order

**Rationale**: The current `Prompt` stores adk4s `Message(role, content)` which is lossy (drops `toolCallId` and `toolCalls`). Wrapping `Conversation` directly preserves all message metadata.

#### Scenario: Prompt.simple produces correct Conversation

**Given** `Prompt.simple("You are helpful", "Parse this")`
**When** `prompt.conversation.messages` is inspected
**Then** the result is `Seq(SystemMessage("You are helpful"), UserMessage("Parse this"))`

#### Scenario: Prompt with ToolMessage preserves toolCallId

**Given** a `Prompt` containing a `ToolMessage("result", toolCallId = "call-123")`
**When** `prompt.conversation.messages` is inspected
**Then** the `ToolMessage` has `toolCallId = "call-123"` (not `"unknown"` or `"tool-call-id"`)

#### Scenario: Prompt with AssistantMessage preserves toolCalls

**Given** a `Prompt` containing an `AssistantMessage(contentOpt = Some("thinking"), toolCalls = Seq(call1, call2))`
**When** `prompt.conversation.messages` is inspected
**Then** the `AssistantMessage` has `toolCalls = Seq(call1, call2)` (not `Seq.empty`)

### Requirement: withOutputFormat appends schema to last user message

The system SHALL update `Prompt.withOutputFormat[A]` to append the schema block to the last `UserMessage` in the wrapped `Conversation`, returning a new `Prompt` with the modified `Conversation`.

**Given** a `Prompt` whose last message is a `UserMessage("Parse this")`
**When** `prompt.withOutputFormat[BankTransaction]` is called
**Then** the resulting `Prompt`'s last message is a `UserMessage("Parse this\n\n" + schemaBlock)` where `schemaBlock` is `Schema[BankTransaction].outputFormatBlock`

**Rationale**: Schema injection must continue to work after the `Prompt` payload changes from `Vector[Message]` to `Conversation`.

#### Scenario: Schema appended to user message

**Given** `Prompt.user("extract data")`
**When** `.withOutputFormat[Resume]` is called
**Then** the last message content ends with the Resume schema block

#### Scenario: No user message present

**Given** a `Prompt` containing only a `SystemMessage`
**When** `.withOutputFormat[Resume]` is called
**Then** a new `UserMessage` containing only the schema block is appended

### Requirement: Deprecated type aliases for migration

The system SHALL provide deprecated type aliases `Message` and `Role` in `org.adk4s.structured.core` pointing to the llm4s types, so existing source code continues to compile with deprecation warnings for one release.

**Given** source code importing `org.adk4s.structured.core.Message`
**When** compiled
**Then** it compiles with a deprecation warning, and `Message` resolves to `org.llm4s.llmconnect.model.Message`

**Rationale**: In-repo callers are updated in the same change, but downstream users (if any) need a migration window.

#### Scenario: Deprecated alias resolves to llm4s type

**Given** `import org.adk4s.structured.core.Message`
**When** `classOf[Message]` is inspected at compile time
**Then** it is `classOf[org.llm4s.llmconnect.model.Message]`

### Requirement: MessageConverter and ConversationConverter removed

The system SHALL delete `MessageConverter` and `ConversationConverter` from `adk4s-core` since the conversion becomes the identity (`Prompt.conversation` is already a `Conversation`).

**Given** the refactored `Prompt` wrapping `Conversation`
**When** `StructuredLLMImpl.toConversation(prompt)` is called
**Then** it returns `prompt.conversation` directly (no conversion)

**Rationale**: The converters were lossy and are now unnecessary.

#### Scenario: toConversation is identity

**Given** a `Prompt` with a `Conversation` containing 3 messages
**When** `toConversation(prompt)` is called
**Then** the result is the same `Conversation` instance (reference equality or value equality)

### Requirement: AgentToolState serialization compatibility

The system SHALL ensure `AgentToolState` and `SerializableMessage` continue to serialize/deserialize correctly after the message-type change, preserving the existing on-disk format.

**Given** an `AgentToolState` serialized with the current format (before this change)
**When** deserialized after this change
**Then** the `SerializableMessage` list is preserved with `role: String` and `content: String` fields unchanged

**Rationale**: `AgentToolState` is persisted in `CheckpointStore` for interrupt/resume. In-flight checkpoints must remain readable.

#### Scenario: Round-trip serialization preserves messages

**Given** an `AgentToolState` with 3 `SerializableMessage` entries
**When** serialized via `upickle.default.write` and deserialized via `upickle.default.read`
**Then** the deserialized state has the same 3 entries with identical `role` and `content` fields

#### Scenario: Old checkpoint format still readable

**Given** a JSON string `{"messages":[{"role":"user","content":"hello"}],"iterationCount":1}` produced by the current code
**When** deserialized as `AgentToolState` after this change
**Then** the result has `messages = List(SerializableMessage("user", "hello"))` and `iterationCount = 1`

## Properties (Ring 3)

### Property: Conversation round-trip is identity

**Invariant**: For all `Conversation` values, `Prompt(conversation).conversation == conversation`.

**Generator strategy**: `genConversation` (constructive: `Gen.list(Range.linear(0, 10), genMessage).map(msgs => Conversation(msgs.toSeq))` where `genMessage` is a weighted choice of `genSystemMessage`, `genUserMessage`, `genAssistantMessage`, `genToolMessage`). Classify by message count and message type distribution.

```
forAll { (conv: Conversation) =>
  Prompt(conv).conversation == conv
}
```

### Property: withOutputFormat preserves non-last messages

**Invariant**: For all `Prompt` values with at least one message, `withOutputFormat[A]` preserves all messages except the last, and the last message's content has the schema block appended.

**Generator strategy**: `genNonEmptyPrompt` (constructive: `Gen.nonEmpty(Range.linear(1, 10), genMessage).map(msgs => Prompt(Conversation(msgs.toSeq)))`). Classify by last message type.

```
forAll { (prompt: Prompt) =>
  val withSchema = prompt.withOutputFormat[BankTransaction]
  withSchema.conversation.messages.dropRight(1) == prompt.conversation.messages.dropRight(1)
}
```

### Property: SerializableMessage round-trip preserves role and content

**Invariant**: For all `SerializableMessage` values, `read[SerializableMessage](write(sm)) == sm`.

**Generator strategy**: `genSerializableMessage` (constructive: `genRoleString` flatMap `genContent` where `genRoleString = Gen.element1("user", "assistant", "system", "tool")` and `genContent = Gen.string(Range.linear(0, 100), Gen.alphaNum)`). Classify by role.

```
forAll { (sm: SerializableMessage) =>
  read[SerializableMessage](write(sm)) == sm
}
```

## Compile-Negative Obligations

| Forbidden Construction | Why | Test |
|------------------------|-----|------|
| `Prompt(messages = Vector(Message(Role.User, "x")))` after migration | The `messages` field is removed; callers must use `Prompt(Conversation(...))` | `assertDoesNotCompile("Prompt(Vector(Message(Role.User, \"x\")))")` |

## Proof Obligations

| Obligation | Source | Enforcement | Artifact |
|------------|--------|-------------|----------|
| Prompt.conversation is a Conversation | Requirement: Prompt wraps Conversation | Type system (case class field) | MessageTypeDedupSpec, TypeContract |
| ToolMessage preserves toolCallId | Requirement: Prompt wraps Conversation + Scenario | Scenario test (assert toolCallId) | MessageTypeDedupSpec |
| AssistantMessage preserves toolCalls | Requirement: Prompt wraps Conversation + Scenario | Scenario test (assert toolCalls) | MessageTypeDedupSpec |
| withOutputFormat appends to last user message | Requirement: withOutputFormat | Hedgehog property + scenario test | MessageTypeDedupSpec |
| Deprecated alias resolves to llm4s type | Requirement: Deprecated aliases | Compile test (classOf equality) | TypeContract |
| MessageConverter/ConversationConverter deleted | Requirement: Converters removed | Compile-negative test (import fails) | TypeContract |
| Conversation round-trip identity | Property: Round-trip | Hedgehog property | MessageTypeDedupSpec |
| AgentToolState old format readable | Requirement: Serialization compat | Ring 4 fixture test (old JSON string) | MessageTypeDedupSpec |
| SerializableMessage round-trip | Property: SerializableMessage round-trip | Hedgehog property | MessageTypeDedupSpec |
