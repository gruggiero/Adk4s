# Feature 03: Component Abstractions

## Overview

This document details the implementation of component abstractions for ADK4S, defining the core interfaces for LLM models, tools, retrievers, and other building blocks. These abstractions align with Eino's component model while leveraging LLM4S and structured-llm.

## Prerequisites

- **Feature 01**: Core Types & Schema System
- **Feature 02**: Streaming Integration

## Dependencies

| Dependency | Purpose | Version |
|------------|---------|---------|
| LLM4S | LLMClient, ToolFunction, ToolRegistry | Local |
| structured-llm | StructuredLLM, Schema | Current |
| fs2 | Streaming | 3.9.x |
| Cats Effect | Effects | 3.6.3 |

## Design Philosophy

ADK4S component abstractions follow these principles:

1. **Use LLM4S directly where possible** - Don't wrap when direct use works
2. **Thin wrapper pattern** - Only add ADK4S-specific functionality
3. **Composition over inheritance** - Prefer typeclass pattern
4. **Immutability** - All operations return new instances

## Implementation Tasks

### Task 1: Create ChatModel Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/ChatModel.scala`

**Purpose**: Define chat model abstraction that wraps LLM4S LLMClient

**Design Decision**: Since LLM4S `LLMClient` already provides the core functionality, ADK4S creates a thin wrapper that:
- Adds fs2.Stream support
- Integrates with ADK4S error types
- Provides convenient factory methods

**Subtasks**:
1. Create `ChatModel[F]` trait with generate and stream methods
2. Create factory from LLM4S `LLMClient`
3. Add configuration case class `ChatModelConfig`

**API Design**:
```scala
package org.adk4s.core.component

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions, StreamedChunk}
import org.adk4s.core.error.AdkError
import org.adk4s.core.streaming.StreamingLLM

/**
 * Configuration for chat model operations.
 */
case class ChatModelConfig(
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  topP: Option[Double] = None,
  stop: List[String] = Nil
):
  def toCompletionOptions: CompletionOptions = CompletionOptions(
    temperature = temperature,
    maxTokens = maxTokens,
    topP = topP,
    stop = stop
  )

object ChatModelConfig:
  val default: ChatModelConfig = ChatModelConfig()

/**
 * Chat model abstraction for ADK4S.
 *
 * Wraps LLM4S LLMClient with fs2.Stream support and ADK4S error handling.
 */
trait ChatModel[F[_]]:
  /**
   * Generate a completion from the conversation.
   */
  def generate(conversation: Conversation, config: ChatModelConfig = ChatModelConfig.default): F[Completion]

  /**
   * Stream completion chunks.
   */
  def stream(conversation: Conversation, config: ChatModelConfig = ChatModelConfig.default): Stream[F, StreamedChunk]

  /**
   * Stream completion content (convenience method).
   */
  def streamContent(conversation: Conversation, config: ChatModelConfig = ChatModelConfig.default): Stream[F, String]

object ChatModel:
  /**
   * Create ChatModel from LLM4S client.
   */
  def fromLlm4s(client: LLMClient): ChatModel[IO] = new ChatModel[IO]:
    private val streaming = StreamingLLM.fromClient(client)

    def generate(conversation: Conversation, config: ChatModelConfig): IO[Completion] =
      streaming.complete(conversation, config.toCompletionOptions)

    def stream(conversation: Conversation, config: ChatModelConfig): Stream[IO, StreamedChunk] =
      streaming.stream(conversation, config.toCompletionOptions)

    def streamContent(conversation: Conversation, config: ChatModelConfig): Stream[IO, String] =
      streaming.streamContent(conversation, config.toCompletionOptions)

  /**
   * Create ChatModel that uses environment-detected provider.
   */
  def auto: IO[ChatModel[IO]] =
    IO(LLMClient.create()).map(fromLlm4s)
```

**Testing**:
- Test generate with mock client
- Test stream produces chunks
- Test streamContent filters empty chunks
- Test error handling

---

### Task 2: Create ToolCallingChatModel Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/ToolCallingChatModel.scala`

**Purpose**: Extend ChatModel with tool calling capabilities

**Subtasks**:
1. Create `ToolCallingChatModel[F]` trait extending `ChatModel[F]`
2. Add `withTools` method (immutable, returns new instance)
3. Create factory from LLM4S client + tools

**API Design**:
```scala
package org.adk4s.core.component

import fs2.Stream
import cats.effect.IO
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions, StreamedChunk}
import org.llm4s.tools.{ToolFunction, ToolRegistry}
import org.adk4s.core.streaming.StreamingLLM

/**
 * Chat model with tool calling capabilities.
 *
 * Tools are passed immutably via withTools method.
 */
trait ToolCallingChatModel[F[_]] extends ChatModel[F]:
  /**
   * Get current tools bound to this model.
   */
  def tools: List[ToolFunction]

  /**
   * Create new model instance with specified tools.
   * This is immutable - returns a new instance.
   */
  def withTools(tools: List[ToolFunction]): ToolCallingChatModel[F]

  /**
   * Create new model with additional tools appended.
   */
  def addTools(additionalTools: List[ToolFunction]): ToolCallingChatModel[F] =
    withTools(tools ++ additionalTools)

  /**
   * Generate with tools available for the model to call.
   */
  def generateWithTools(conversation: Conversation, config: ChatModelConfig = ChatModelConfig.default): F[Completion]

  /**
   * Stream with tools available.
   */
  def streamWithTools(conversation: Conversation, config: ChatModelConfig = ChatModelConfig.default): Stream[F, StreamedChunk]

object ToolCallingChatModel:
  /**
   * Create ToolCallingChatModel from LLM4S client.
   */
  def fromLlm4s(client: LLMClient, initialTools: List[ToolFunction] = Nil): ToolCallingChatModel[IO] =
    new ToolCallingChatModelImpl(client, initialTools)

  private class ToolCallingChatModelImpl(
    client: LLMClient,
    override val tools: List[ToolFunction]
  ) extends ToolCallingChatModel[IO]:
    private val streaming = StreamingLLM.fromClient(client)

    def generate(conversation: Conversation, config: ChatModelConfig): IO[Completion] =
      streaming.complete(conversation, config.toCompletionOptions)

    def stream(conversation: Conversation, config: ChatModelConfig): Stream[IO, StreamedChunk] =
      streaming.stream(conversation, config.toCompletionOptions)

    def streamContent(conversation: Conversation, config: ChatModelConfig): Stream[IO, String] =
      streaming.streamContent(conversation, config.toCompletionOptions)

    def withTools(newTools: List[ToolFunction]): ToolCallingChatModel[IO] =
      new ToolCallingChatModelImpl(client, newTools)

    def generateWithTools(conversation: Conversation, config: ChatModelConfig): IO[Completion] =
      val options = config.toCompletionOptions.copy(tools = tools)
      streaming.complete(conversation, options)

    def streamWithTools(conversation: Conversation, config: ChatModelConfig): Stream[IO, StreamedChunk] =
      val options = config.toCompletionOptions.copy(tools = tools)
      streaming.stream(conversation, options)
```

**Testing**:
- Test withTools returns new instance
- Test generateWithTools includes tools in request
- Test tools list is immutable

---

### Task 3: Create Tool Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/Tool.scala`

**Purpose**: Define tool abstraction that wraps LLM4S ToolFunction

**Subtasks**:
1. Create `Tool[F]` trait with info and run methods
2. Create `InvokableTool[F]` for non-streaming tools
3. Create `StreamableTool[F]` for streaming tools
4. Create factories from LLM4S ToolFunction

**API Design**:
```scala
package org.adk4s.core.component

import fs2.Stream
import cats.effect.IO
import org.llm4s.tools.{ToolFunction, ToolInfo, SchemaDefinition}
import org.adk4s.core.error.{AdkError, ToolExecutionError}

/**
 * Tool information (matches LLM4S ToolInfo structure).
 */
case class AdkToolInfo(
  name: String,
  description: String,
  parameters: Option[SchemaDefinition]
)

object AdkToolInfo:
  def fromLlm4s(tool: ToolFunction): AdkToolInfo =
    AdkToolInfo(tool.name, tool.description, Some(tool.parameters))

/**
 * Base tool trait - provides tool information.
 */
trait Tool[F[_]]:
  /**
   * Get tool information for LLM.
   */
  def info: F[AdkToolInfo]

  /**
   * Get underlying LLM4S ToolFunction if available.
   */
  def asToolFunction: Option[ToolFunction]

/**
 * Tool that can be invoked with arguments returning a result.
 */
trait InvokableTool[F[_]] extends Tool[F]:
  /**
   * Execute tool with JSON arguments.
   */
  def run(arguments: String): F[String]

/**
 * Tool that can be invoked with streaming output.
 */
trait StreamableTool[F[_]] extends Tool[F]:
  /**
   * Execute tool with JSON arguments, returning stream.
   */
  def runStream(arguments: String): Stream[F, String]

object Tool:
  /**
   * Create InvokableTool from LLM4S ToolFunction.
   */
  def fromLlm4s(toolFn: ToolFunction): InvokableTool[IO] = new InvokableTool[IO]:
    def info: IO[AdkToolInfo] = IO.pure(AdkToolInfo.fromLlm4s(toolFn))

    def asToolFunction: Option[ToolFunction] = Some(toolFn)

    def run(arguments: String): IO[String] =
      IO(io.circe.parser.parse(arguments).toOption.flatMap(_.as[Map[String, Any]].toOption).getOrElse(Map.empty))
        .flatMap { params =>
          toolFn.handler(params) match
            case Right(result) => IO.pure(result)
            case Left(error) => IO.raiseError(ToolExecutionError(toolFn.name, new RuntimeException(error)))
        }

  /**
   * Create InvokableTool from function.
   */
  def invokable(
    name: String,
    description: String,
    parameters: Option[SchemaDefinition] = None
  )(handler: Map[String, Any] => Either[String, String]): InvokableTool[IO] = new InvokableTool[IO]:
    def info: IO[AdkToolInfo] = IO.pure(AdkToolInfo(name, description, parameters))

    def asToolFunction: Option[ToolFunction] = None

    def run(arguments: String): IO[String] =
      IO(io.circe.parser.parse(arguments).toOption.flatMap(_.as[Map[String, Any]].toOption).getOrElse(Map.empty))
        .flatMap { params =>
          handler(params) match
            case Right(result) => IO.pure(result)
            case Left(error) => IO.raiseError(ToolExecutionError(name, new RuntimeException(error)))
        }

  /**
   * Create StreamableTool from function.
   */
  def streamable(
    name: String,
    description: String,
    parameters: Option[SchemaDefinition] = None
  )(handler: Map[String, Any] => Stream[IO, String]): StreamableTool[IO] = new StreamableTool[IO]:
    def info: IO[AdkToolInfo] = IO.pure(AdkToolInfo(name, description, parameters))

    def asToolFunction: Option[ToolFunction] = None

    def runStream(arguments: String): Stream[IO, String] =
      Stream.eval(IO(io.circe.parser.parse(arguments).toOption.flatMap(_.as[Map[String, Any]].toOption).getOrElse(Map.empty)))
        .flatMap(handler)
```

**Testing**:
- Test tool info retrieval
- Test tool execution
- Test error handling
- Test streaming tool

---

### Task 4: Create Retriever Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/Retriever.scala`

**Purpose**: Define document retrieval abstraction for RAG

**Subtasks**:
1. Create `Document` case class
2. Create `Retriever[F]` trait
3. Create `RetrieverConfig` for configuration
4. Provide stub implementation for future RAG support

**API Design**:
```scala
package org.adk4s.core.component

import fs2.Stream
import cats.effect.IO
import io.circe.Json

/**
 * Document for RAG applications.
 */
case class Document(
  id: String,
  content: String,
  metadata: Map[String, Json] = Map.empty,
  score: Option[Double] = None
)

/**
 * Configuration for retrieval operations.
 */
case class RetrieverConfig(
  topK: Int = 10,
  minScore: Option[Double] = None,
  filter: Option[Map[String, Json]] = None
)

object RetrieverConfig:
  val default: RetrieverConfig = RetrieverConfig()

/**
 * Document retriever abstraction.
 */
trait Retriever[F[_]]:
  /**
   * Retrieve documents matching query.
   */
  def retrieve(query: String, config: RetrieverConfig = RetrieverConfig.default): F[List[Document]]

  /**
   * Retrieve documents as stream.
   */
  def retrieveStream(query: String, config: RetrieverConfig = RetrieverConfig.default): Stream[F, Document]

object Retriever:
  /**
   * Create a no-op retriever that returns empty results.
   * Placeholder for future RAG implementation.
   */
  def empty: Retriever[IO] = new Retriever[IO]:
    def retrieve(query: String, config: RetrieverConfig): IO[List[Document]] =
      IO.pure(Nil)

    def retrieveStream(query: String, config: RetrieverConfig): Stream[IO, Document] =
      Stream.empty

  /**
   * Create a retriever from a function.
   */
  def fromFunction(f: (String, RetrieverConfig) => IO[List[Document]]): Retriever[IO] = new Retriever[IO]:
    def retrieve(query: String, config: RetrieverConfig): IO[List[Document]] =
      f(query, config)

    def retrieveStream(query: String, config: RetrieverConfig): Stream[IO, Document] =
      Stream.eval(f(query, config)).flatMap(docs => Stream.emits(docs))
```

**Testing**:
- Test empty retriever returns empty list
- Test fromFunction retriever
- Test stream retrieval

---

### Task 5: Create Embedder Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/Embedder.scala`

**Purpose**: Define text embedding abstraction for RAG

**Subtasks**:
1. Create `Embedding` type alias
2. Create `Embedder[F]` trait
3. Provide stub implementation for future embedding support

**API Design**:
```scala
package org.adk4s.core.component

import cats.effect.IO

/**
 * Embedding vector type.
 */
type Embedding = Vector[Double]

/**
 * Batch embeddings result.
 */
case class EmbeddingResult(
  embeddings: List[Embedding],
  model: Option[String] = None,
  usage: Option[EmbeddingUsage] = None
)

case class EmbeddingUsage(
  promptTokens: Int,
  totalTokens: Int
)

/**
 * Text embedder abstraction.
 */
trait Embedder[F[_]]:
  /**
   * Embed a single text.
   */
  def embed(text: String): F[Embedding]

  /**
   * Embed multiple texts.
   */
  def embedBatch(texts: List[String]): F[EmbeddingResult]

  /**
   * Get embedding dimension.
   */
  def dimension: F[Int]

object Embedder:
  /**
   * Create a mock embedder for testing.
   * Returns random embeddings of specified dimension.
   */
  def mock(dim: Int): Embedder[IO] = new Embedder[IO]:
    def embed(text: String): IO[Embedding] =
      IO(Vector.fill(dim)(scala.util.Random.nextDouble()))

    def embedBatch(texts: List[String]): IO[EmbeddingResult] =
      IO(texts.map(_ => Vector.fill(dim)(scala.util.Random.nextDouble()))).map { embeddings =>
        EmbeddingResult(embeddings)
      }

    def dimension: IO[Int] = IO.pure(dim)
```

**Testing**:
- Test mock embedder returns correct dimension
- Test batch embedding

---

### Task 6: Create ChatTemplate Trait

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/ChatTemplate.scala`

**Purpose**: Align with Eino's ChatTemplate concept using structured-llm PromptTemplate

**Design Decision**: structured-llm already has `PromptTemplate[I]`, so this creates a thin wrapper for Eino compatibility.

**Subtasks**:
1. Create `ChatTemplate[F, V]` trait
2. Create factory from structured-llm `PromptTemplate`
3. Add variable substitution support

**API Design**:
```scala
package org.adk4s.core.component

import cats.effect.IO
import org.adk4s.structured.core.{Prompt, PromptTemplate, Message, Role}
import org.adk4s.core.types.ConversationConverter
import org.llm4s.llmconnect.model.{Conversation, Message as Llm4sMessage}

/**
 * Chat template that formats variables into messages.
 *
 * This is a thin wrapper around structured-llm PromptTemplate
 * for Eino compatibility.
 */
trait ChatTemplate[F[_], V]:
  /**
   * Format template with variables to produce Prompt.
   */
  def format(variables: V): F[Prompt]

  /**
   * Format template to LLM4S Conversation.
   */
  def formatConversation(variables: V): F[Conversation]

object ChatTemplate:
  /**
   * Create ChatTemplate from structured-llm PromptTemplate.
   */
  def fromPromptTemplate[V](template: PromptTemplate[V]): ChatTemplate[IO, V] = new ChatTemplate[IO, V]:
    def format(variables: V): IO[Prompt] =
      IO.pure(template.render(variables))

    def formatConversation(variables: V): IO[Conversation] =
      format(variables).map(ConversationConverter.toConversation)

  /**
   * Create ChatTemplate from message patterns with variable substitution.
   */
  def fromMessages(messages: Message*)(substitute: (String, Map[String, String]) => String): ChatTemplate[IO, Map[String, String]] =
    new ChatTemplate[IO, Map[String, String]]:
      def format(variables: Map[String, String]): IO[Prompt] =
        IO.pure(Prompt(messages.map(m => m.copy(content = substitute(m.content, variables))).toVector))

      def formatConversation(variables: Map[String, String]): IO[Conversation] =
        format(variables).map(ConversationConverter.toConversation)

  /**
   * Create ChatTemplate with simple {variable} substitution.
   */
  def simple(messages: Message*): ChatTemplate[IO, Map[String, String]] =
    fromMessages(messages*) { (content, vars) =>
      vars.foldLeft(content) { case (c, (k, v)) =>
        c.replace(s"{$k}", v)
      }
    }
```

**Testing**:
- Test format produces Prompt
- Test formatConversation produces Conversation
- Test variable substitution

---

### Task 7: Create Component Package Object

**Location**: `adk4s-core/src/main/scala/org/adk4s/core/component/package.scala`

**Purpose**: Export all component abstractions

**API Design**:
```scala
package org.adk4s.core

package object component:
  // Re-exports
  export ChatModel.*
  export ToolCallingChatModel.*
  export Tool.*
  export Retriever.*
  export Embedder.*
  export ChatTemplate.*

  // Type aliases for convenience
  type ChatModelIO = ChatModel[cats.effect.IO]
  type ToolIO = Tool[cats.effect.IO]
  type InvokableToolIO = InvokableTool[cats.effect.IO]
  type RetrieverIO = Retriever[cats.effect.IO]
  type EmbedderIO = Embedder[cats.effect.IO]
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
    │                   └── component/
    │                       ├── package.scala              # Exports
    │                       ├── ChatModel.scala            # Chat model
    │                       ├── ToolCallingChatModel.scala # With tools
    │                       ├── Tool.scala                 # Tool abstractions
    │                       ├── Retriever.scala            # Document retrieval
    │                       ├── Embedder.scala             # Text embeddings
    │                       └── ChatTemplate.scala         # Prompt templating
    └── test/
        └── scala/
            └── org/
                └── adk4s/
                    └── core/
                        └── component/
                            ├── ChatModelTest.scala
                            ├── ToolCallingChatModelTest.scala
                            ├── ToolTest.scala
                            ├── RetrieverTest.scala
                            ├── EmbedderTest.scala
                            └── ChatTemplateTest.scala
```

## Testing Plan

### Unit Tests

1. **ChatModel Tests**
   - Generate returns Completion
   - Stream returns chunks
   - Error handling works

2. **ToolCallingChatModel Tests**
   - withTools returns new instance (immutability)
   - generateWithTools includes tools
   - addTools appends correctly

3. **Tool Tests**
   - Tool info retrieval
   - Tool execution with valid JSON
   - Tool execution with invalid JSON
   - Error handling

4. **Retriever Tests**
   - Empty retriever returns empty list
   - Custom retriever works
   - Stream retrieval works

5. **ChatTemplate Tests**
   - Variable substitution works
   - Format produces valid Prompt
   - formatConversation produces valid Conversation

### Integration Tests

1. Test ChatModel with real LLM4S client
2. Test ToolCallingChatModel with actual tool execution
3. Test full flow: template -> model -> response

## Completion Criteria

- [ ] All component traits implemented
- [ ] Factories for LLM4S integration complete
- [ ] Tool execution working with JSON arguments
- [ ] Retriever and Embedder stubs for future RAG
- [ ] ChatTemplate aligns with structured-llm
- [ ] Unit tests passing with >90% coverage
- [ ] Documentation updated
