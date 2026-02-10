# Feature 01: Core Types & Schema System

## Overview

This document details the implementation of core types and schema system for ADK4S. The foundation layer establishes type definitions that will be used throughout the framework, ensuring compatibility with LLM4S and building on the existing structured-llm module.

## Prerequisites

- None (this is the first feature to implement)
- **Existing Code**: structured-llm module already provides `Schema[A]`, `Prompt`, `PromptTemplate`, `Message`, `Role`

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| structured-llm | Schema[A], Prompt, PromptTemplate | Current |
| LLM4S | Message types, Conversation, ToolCall | Local |
| Cats Effect | IO monad, effect management | 3.6.3 |
| Smithy4s | Schema definitions | 0.18.45 |

## Current State Analysis

### Already Implemented in structured-llm

The following types already exist and should be **reused, not reimplemented**:

```scala
// org.adk4s.structured.core.Schema
opaque type Schema[A] = SchemaData[A]
// - smithyDefinition: String
// - description: Option[String]
// - smithySchema: Smithy4sSchema[A]

// org.adk4s.structured.core.Prompt
case class Prompt(messages: Vector[Message])
// - withOutputFormat[A: Schema]: Prompt
// - addUserMessage(content: String): Prompt
// - appendToLast(content: String): Prompt

// org.adk4s.structured.core.Message
case class Message(role: Role, content: String)

// org.adk4s.structured.core.Role
enum Role:
  case System, User, Assistant, Tool

// org.adk4s.structured.core.PromptTemplate
case class PromptTemplate[I](render: I => Prompt)
```

### Available from LLM4S

LLM4S provides these types that ADK4S will use:

```scala
// org.llm4s.llmconnect.model
sealed trait Message
case class UserMessage(content: String) extends Message
case class SystemMessage(content: String) extends Message
case class AssistantMessage(content: Option[String], toolCalls: List[ToolCall]) extends Message
case class ToolMessage(content: String, toolCallId: String) extends Message

case class Conversation(messages: Vector[Message])
case class Completion(content: String, finishReason: Option[String], toolCalls: List[ToolCall])
case class ToolCall(id: String, function: FunctionCall)
case class FunctionCall(name: String, arguments: String)
case class CompletionOptions(temperature: Option[Double], maxTokens: Option[Int], tools: List[ToolFunction])

// org.llm4s.error
sealed trait LLMError
```

## Implementation Tasks

### Task 1: Create Message Type Converters

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/MessageConverter.scala`

**Purpose**: Convert between structured-llm Messages and LLM4S Messages

**Subtasks**:
1. Create `MessageConverter` object with bidirectional conversion methods
2. Implement `toLlm4s: org.adk4s.structured.core.Message => org.llm4s.llmconnect.model.Message`
3. Implement `fromLlm4s: org.llm4s.llmconnect.model.Message => org.adk4s.structured.core.Message`
4. Handle tool calls in conversions (AssistantMessage with toolCalls)
5. Handle tool responses (ToolMessage with toolCallId)

**API Design**:
```scala
package org.adk4s.core.types

import org.adk4s.structured.core.{Message as AdkMessage, Role as AdkRole}
import org.llm4s.llmconnect.model.{Message as Llm4sMessage, UserMessage, SystemMessage, AssistantMessage, ToolMessage}

object MessageConverter:
  def toLlm4s(msg: AdkMessage): Llm4sMessage
  def fromLlm4s(msg: Llm4sMessage): AdkMessage

  extension (msg: AdkMessage)
    def asLlm4s: Llm4sMessage = toLlm4s(msg)

  extension (msg: Llm4sMessage)
    def asAdk: AdkMessage = fromLlm4s(msg)
```

**Testing**:
- Test conversion of each role type
- Test round-trip conversion preserves data
- Test tool call handling

---

### Task 2: Create Conversation Converters

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/ConversationConverter.scala`

**Purpose**: Convert between structured-llm Prompt and LLM4S Conversation

**Subtasks**:
1. Create `ConversationConverter` object
2. Implement `toConversation: Prompt => Conversation`
3. Implement `fromConversation: Conversation => Prompt`
4. Ensure message ordering is preserved

**API Design**:
```scala
package org.adk4s.core.types

import org.adk4s.structured.core.Prompt
import org.llm4s.llmconnect.model.Conversation

object ConversationConverter:
  def toConversation(prompt: Prompt): Conversation
  def fromConversation(conv: Conversation): Prompt

  extension (prompt: Prompt)
    def asConversation: Conversation = toConversation(prompt)

  extension (conv: Conversation)
    def asPrompt: Prompt = fromConversation(conv)
```

**Testing**:
- Test empty prompt/conversation
- Test multi-message conversion
- Test round-trip preservation

---

### Task 3: Create ADK4S Error Types

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/error/AdkError.scala`

**Purpose**: Define unified error hierarchy for ADK4S

**Subtasks**:
1. Create sealed trait `AdkError` hierarchy
2. Include error types for LLM failures, parsing, validation, workflow errors
3. Wrap LLM4S `LLMError` in ADK4S error type
4. Wrap structured-llm `StructuredLLMError` in ADK4S error type
5. Implement `Show[AdkError]` for error formatting

**API Design**:
```scala
package org.adk4s.core.error

import org.llm4s.error.LLMError
import org.adk4s.structured.core.StructuredLLMError
import cats.Show

sealed trait AdkError extends Throwable:
  def message: String
  override def getMessage: String = message

// LLM-related errors
case class LlmCallError(underlying: LLMError) extends AdkError:
  def message: String = s"LLM call failed: ${underlying.formatted}"

case class StructuredOutputError(underlying: StructuredLLMError) extends AdkError:
  def message: String = s"Structured output error: ${underlying.message}"

// Validation errors
case class TypeMismatchError(expected: String, actual: String, path: List[String]) extends AdkError:
  def message: String = s"Type mismatch at ${path.mkString(".")}: expected $expected, got $actual"

case class MissingFieldError(field: String, path: List[String]) extends AdkError:
  def message: String = s"Missing required field: ${(path :+ field).mkString(".")}"

// Graph/Workflow errors
case class NodeNotFoundError(nodeKey: String) extends AdkError:
  def message: String = s"Node '$nodeKey' not found in graph"

case class EdgeValidationError(from: String, to: String, reason: String) extends AdkError:
  def message: String = s"Invalid edge $from -> $to: $reason"

case class MaxStepsExceededError(steps: Int, max: Int) extends AdkError:
  def message: String = s"Exceeded maximum steps: $steps > $max"

case class GraphCompiledError() extends AdkError:
  def message: String = "Graph already compiled, cannot be modified"

// Tool errors
case class ToolNotFoundError(toolName: String) extends AdkError:
  def message: String = s"Tool '$toolName' not found in registry"

case class ToolExecutionError(toolName: String, cause: Throwable) extends AdkError:
  def message: String = s"Tool '$toolName' execution failed: ${cause.getMessage}"

// State errors
case class StateTypeMismatchError(expected: String, actual: String) extends AdkError:
  def message: String = s"State type mismatch: expected $expected, got $actual"

object AdkError:
  given Show[AdkError] with
    def show(e: AdkError): String = e.message
```

**Testing**:
- Test error message formatting
- Test error wrapping
- Test Show instance

---

### Task 4: Create Core Type Aliases

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/package.scala`

**Purpose**: Provide convenient type aliases for common patterns

**Subtasks**:
1. Create type aliases for common effectful operations
2. Export commonly used types from dependencies

**API Design**:
```scala
package org.adk4s.core

import cats.effect.IO
import org.adk4s.core.error.AdkError

package object types:
  // Effect aliases
  type AdkIO[A] = IO[A]
  type AdkResult[A] = Either[AdkError, A]
  type AdkIOResult[A] = IO[Either[AdkError, A]]

  // Re-export common types
  type Message = org.adk4s.structured.core.Message
  type Role = org.adk4s.structured.core.Role
  type Prompt = org.adk4s.structured.core.Prompt
  type Schema[A] = org.adk4s.structured.core.Schema[A]
  type PromptTemplate[I] = org.adk4s.structured.core.PromptTemplate[I]

  // LLM4S type aliases
  type LlmConversation = org.llm4s.llmconnect.model.Conversation
  type LlmCompletion = org.llm4s.llmconnect.model.Completion
  type LlmMessage = org.llm4s.llmconnect.model.Message
  type ToolCall = org.llm4s.llmconnect.model.ToolCall
  type FunctionCall = org.llm4s.llmconnect.model.FunctionCall
```

**Testing**:
- Compile-time verification that aliases work
- Import tests

---

### Task 5: Create NodeKey Opaque Type

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/NodeKey.scala`

**Purpose**: Type-safe node identifiers for graph construction

**Subtasks**:
1. Create opaque type `NodeKey`
2. Add validation (non-empty, no reserved names)
3. Define reserved keys (START, END)
4. Implement Eq, Show, Order instances

**API Design**:
```scala
package org.adk4s.core.types

import cats.{Eq, Show, Order}

opaque type NodeKey = String

object NodeKey:
  val START: NodeKey = "__start__"
  val END: NodeKey = "__end__"

  private val reservedKeys: Set[String] = Set("__start__", "__end__")

  def apply(key: String): Either[String, NodeKey] =
    if key.isEmpty then Left("Node key cannot be empty")
    else if reservedKeys.contains(key) then Left(s"Node key '$key' is reserved")
    else Right(key)

  def unsafeApply(key: String): NodeKey =
    apply(key).getOrElse(throw new IllegalArgumentException(s"Invalid node key: $key"))

  extension (key: NodeKey)
    def value: String = key
    def isStart: Boolean = key == START
    def isEnd: Boolean = key == END
    def isReserved: Boolean = reservedKeys.contains(key)

  given Eq[NodeKey] = Eq.fromUniversalEquals
  given Show[NodeKey] = Show.show(_.value)
  given Order[NodeKey] = Order.by(_.value)
```

**Testing**:
- Test validation rules
- Test reserved keys
- Test typeclass instances

---

### Task 6: Create FieldPath Opaque Type

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/FieldPath.scala`

**Purpose**: Type-safe field paths for workflow field mapping

**Subtasks**:
1. Create opaque type `FieldPath`
2. Implement path parsing from dotted string
3. Implement path concatenation
4. Implement Show instance

**API Design**:
```scala
package org.adk4s.core.types

import cats.Show

opaque type FieldPath = Vector[String]

object FieldPath:
  val Root: FieldPath = Vector.empty

  def apply(path: String): FieldPath =
    if path.isEmpty then Root
    else Vector.from(path.split('.'))

  def fromSegments(segments: String*): FieldPath =
    Vector.from(segments)

  extension (path: FieldPath)
    def segments: Vector[String] = path
    def isEmpty: Boolean = path.isEmpty
    def nonEmpty: Boolean = path.nonEmpty
    def head: Option[String] = path.headOption
    def tail: FieldPath = if path.isEmpty then Root else path.tail
    def :+(segment: String): FieldPath = path :+ segment
    def ++(other: FieldPath): FieldPath = path ++ other
    def render: String = path.mkString(".")

  given Show[FieldPath] = Show.show(_.render)
```

**Testing**:
- Test path parsing
- Test concatenation
- Test edge cases (empty path, single segment)

---

### Task 7: Create RunInfo Case Class

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/types/RunInfo.scala`

**Purpose**: Metadata about node execution for callbacks

**Subtasks**:
1. Create `RunInfo` case class
2. Include node key, component type, timing info
3. Implement Show instance

**API Design**:
```scala
package org.adk4s.core.types

import cats.Show
import java.time.Instant

case class RunInfo(
  nodeKey: NodeKey,
  componentType: String,
  nodeName: Option[String] = None,
  startTime: Option[Instant] = None,
  parentPath: List[NodeKey] = Nil
):
  def fullPath: List[NodeKey] = parentPath :+ nodeKey

object RunInfo:
  def forNode(key: NodeKey, componentType: String): RunInfo =
    RunInfo(key, componentType)

  def forNode(key: NodeKey, componentType: String, name: String): RunInfo =
    RunInfo(key, componentType, Some(name))

  given Show[RunInfo] = Show.show { info =>
    val name = info.nodeName.map(n => s" ($n)").getOrElse("")
    val path = if info.parentPath.isEmpty then "" else s" [${info.fullPath.map(_.value).mkString(" -> ")}]"
    s"${info.nodeKey.value}$name: ${info.componentType}$path"
  }
```

**Testing**:
- Test RunInfo creation
- Test Show formatting
- Test path handling

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
    │                   ├── types/
    │                   │   ├── package.scala           # Type aliases
    │                   │   ├── MessageConverter.scala  # Message conversion
    │                   │   ├── ConversationConverter.scala
    │                   │   ├── NodeKey.scala           # Opaque type
    │                   │   ├── FieldPath.scala         # Opaque type
    │                   │   └── RunInfo.scala           # Metadata
    │                   └── error/
    │                       └── AdkError.scala          # Error hierarchy
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── core/
                        ├── types/
                        │   ├── MessageConverterTest.scala
                        │   ├── ConversationConverterTest.scala
                        │   ├── NodeKeyTest.scala
                        │   └── FieldPathTest.scala
                        └── error/
                            └── AdkErrorTest.scala
```

## Testing Plan

### Unit Tests

1. **MessageConverter Tests**
   - Convert each role type (System, User, Assistant, Tool)
   - Handle tool calls in AssistantMessage
   - Handle toolCallId in ToolMessage
   - Round-trip conversion preserves all data

2. **ConversationConverter Tests**
   - Empty prompt/conversation
   - Single message
   - Multiple messages with different roles
   - Message ordering preserved

3. **NodeKey Tests**
   - Valid key creation
   - Empty key rejection
   - Reserved key rejection
   - Equality and ordering

4. **FieldPath Tests**
   - Parse dotted paths
   - Handle empty path
   - Concatenation operations
   - Render back to string

5. **AdkError Tests**
   - Each error type has meaningful message
   - Show instance formats correctly
   - Error wrapping works

### Integration Tests

1. Test that ADK4S types work with actual LLM4S client
2. Test structured-llm integration with new converters

## Examples

### Using Message Converters

```scala
import org.adk4s.core.types.MessageConverter.*
import org.adk4s.structured.core.{Message, Role}

// Convert ADK message to LLM4S
val adkMsg = Message(Role.User, "Hello, world!")
val llm4sMsg = adkMsg.asLlm4s  // UserMessage("Hello, world!")

// Convert LLM4S message to ADK
val llm4sResponse = AssistantMessage(Some("Hi there!"), Nil)
val adkResponse = llm4sResponse.asAdk  // Message(Role.Assistant, "Hi there!")
```

### Using NodeKey

```scala
import org.adk4s.core.types.NodeKey

// Create node keys
val modelNode = NodeKey.unsafeApply("model")
val toolsNode = NodeKey.unsafeApply("tools")

// Reserved keys
val start = NodeKey.START
val end = NodeKey.END

// Validation
NodeKey("") // Left("Node key cannot be empty")
NodeKey("__start__") // Left("Node key '__start__' is reserved")
```

### Using FieldPath

```scala
import org.adk4s.core.types.FieldPath

// Create paths
val contentPath = FieldPath("message.content")
val rolePath = FieldPath("message.role")

// Manipulate paths
val extendedPath = contentPath :+ "text"  // message.content.text
val combined = FieldPath("response") ++ contentPath  // response.message.content
```

## Completion Criteria

- [ ] All converter methods implemented and tested
- [ ] All opaque types implemented with validation
- [ ] Error hierarchy complete with Show instances
- [ ] Unit tests passing with >90% coverage
- [ ] Integration with structured-llm verified
- [ ] Documentation updated
