# LLM4S Core API Reference

## Overview

**LLM4S** is a functional, type-safe Scala library for building LLM applications. This document covers the core API including clients, messages, completions, and error handling.

**Version**: 0.1.1 (Scala 2.13 and 3.3+)

---

## Architecture

### Module Organization

```
org.llm4s/
├── llmconnect/          # LLM client abstraction
│   ├── model/          # Domain models (Message, Completion, etc.)
│   ├── provider/       # Provider implementations (OpenAI, Anthropic, Azure, OpenRouter)
│   ├── config/         # Configuration management
│   └── extractors/     # Text extraction utilities
├── types/              # Type aliases and newtypes (package.scala)
├── error/              # Comprehensive error hierarchy
└── config/             # Environment configuration
```

### Design Philosophy

| Principle | Implementation | Benefit |
|-----------|----------------|---------|
| Immutability | All case classes immutable | Thread-safe, pure functions |
| Explicit errors | `Either[LLMError, A]` | No hidden exceptions |
| Type safety | Sealed traits + newtypes | Compile-time guarantees |
| Provider abstraction | `LLMClient` trait | Swap providers without code changes |

---

## Core Traits and Classes

### LLMClient Trait

**Purpose**: Abstract interface for all LLM provider interactions.

```scala
trait LLMClient {
  /** Complete a conversation and get a response */
  def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Either[LLMError, Completion]

  /** Stream a completion with callback for chunks */
  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Either[LLMError, Completion]
}
```

**Implementations**:
- `OpenAIClient`: OpenAI API (via Azure SDK)
- `AnthropicClient`: Anthropic Claude API
- `AzureOpenAIClient`: Azure-hosted OpenAI
- `OpenRouterClient`: OpenRouter aggregator

**Usage**:
```scala
import org.llm4s.llmconnect.LLM

// Environment-based client (reads LLM_MODEL env var)
val client: LLMClient = LLM.client()

// Explicit provider and config
val client2: LLMClient = LLMConnect.getClient(
  provider = LLMProvider.OpenAI,
  config = OpenAIConfig.fromEnv("gpt-4o")
)
```

---

### LLMConnect Object (Factory)

**Purpose**: Factory for creating provider-specific clients.

```scala
object LLMConnect {
  /** Get client based on LLM_MODEL environment variable */
  def getClient(): LLMClient
  
  /** Get client with explicit provider and config */
  def getClient(provider: LLMProvider, config: ProviderConfig): LLMClient
  
  /** Convenience method for quick completion */
  def complete(
    messages: Seq[Message],
    options: CompletionOptions = CompletionOptions()
  ): Either[LLMError, Completion]
}
```

**Environment Variable Format**:
```bash
# Format: provider/model-name
LLM_MODEL=openai/gpt-4o              # Uses OpenAI
LLM_MODEL=anthropic/claude-3-7-sonnet-latest  # Uses Anthropic
LLM_MODEL=azure/gpt-4                # Uses Azure OpenAI
LLM_MODEL=openrouter/meta-llama/llama-3  # Uses OpenRouter

# Required API keys
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
AZURE_OPENAI_API_KEY=...
OPENROUTER_API_KEY=sk-or-...
```

**Smart routing**: If `OPENAI_BASE_URL` contains `openrouter.ai`, automatically uses `OpenRouterClient`.

---

### Message Hierarchy

**Purpose**: Type-safe representation of conversation messages.

```scala
sealed trait Message {
  def role: String
  def content: String
  override def toString: String = s"${role}: ${content}"
}

case class UserMessage(content: String) extends Message {
  val role: String = "user"
}

case class SystemMessage(content: String) extends Message {
  val role: String = "system"
}

case class AssistantMessage(
  contentOpt: Option[String] = None,
  toolCalls: Seq[ToolCall] = Seq.empty
) extends Message {
  val role: String = "assistant"
  def content: String = contentOpt.getOrElse("")
}

case class ToolMessage(
  toolCallId: String,
  content: String  // Must be JSON string
) extends Message {
  val role: String = "tool"
}
```

**Key characteristics**:
- **Sealed trait**: Exhaustive pattern matching at compile time
- **Immutable**: All fields are `val`
- **Flexible assistant**: Can have text content, tool calls, or both
- **Tool linking**: `ToolMessage.toolCallId` links to `ToolCall.id`

**Companion object helpers**:
```scala
object AssistantMessage {
  // Convenience constructor for text-only messages
  def apply(content: String): AssistantMessage = 
    AssistantMessage(Some(content), Seq.empty)
  
  // Constructor for text + tool calls
  def apply(content: String, toolCalls: Seq[ToolCall]): AssistantMessage = 
    AssistantMessage(Some(content), toolCalls)
}
```

**Usage**:
```scala
// User message
val userMsg = UserMessage("Hello!")

// System prompt
val sysMsg = SystemMessage("You are a helpful assistant.")

// Assistant reply (text only)
val assistMsg1 = AssistantMessage("Hello! How can I help?")

// Assistant with tool calls
val assistMsg2 = AssistantMessage(
  contentOpt = Some("Let me check the weather for you."),
  toolCalls = Seq(ToolCall(id = "call_123", name = "get_weather", arguments = ujson.Obj("city" -> "Paris")))
)

// Tool result
val toolMsg = ToolMessage(
  toolCallId = "call_123",
  content = """{"temperature": 22, "conditions": "sunny"}"""
)
```

---

### Conversation

**Purpose**: Immutable container for message sequences.

```scala
case class Conversation(messages: Seq[Message]) {
  /** Add a single message and return new Conversation */
  def addMessage(message: Message): Conversation =
    Conversation(messages :+ message)
    
  /** Add multiple messages and return new Conversation */
  def addMessages(newMessages: Seq[Message]): Conversation =
    Conversation(messages ++ newMessages)
}
```

**Key points**:
- **Immutable**: Methods return new instances
- **Thread-safe**: No mutable state
- **Sequential**: Messages maintain insertion order

**Usage patterns**:
```scala
// Initial conversation
var conv = Conversation(Seq(
  SystemMessage("You are a helpful assistant."),
  UserMessage("What is 2+2?")
))

// Add assistant's response
conv = conv.addMessage(AssistantMessage("The answer is 4."))

// Add follow-up
conv = conv.addMessage(UserMessage("And 3+3?"))

// Add multiple messages at once
conv = conv.addMessages(Seq(
  AssistantMessage("Let me calculate..."),
  ToolMessage("call_123", """{"result": 6}""")
))
```

---

### Completion

**Purpose**: Represents LLM response with metadata.

```scala
case class Completion(
  id: String,                        // Unique completion ID
  created: Long,                     // Unix timestamp
  message: AssistantMessage,         // The actual response
  usage: Option[TokenUsage] = None   // Token consumption stats
)

case class TokenUsage(
  promptTokens: Int,       // Input tokens
  completionTokens: Int,   // Generated tokens
  totalTokens: Int         // Sum of both
)
```

**Usage**:
```scala
client.complete(conversation) match {
  case Right(completion) =>
    println(s"ID: ${completion.id}")
    println(s"Created: ${completion.created}")
    println(s"Response: ${completion.message.content}")
    
    completion.usage.foreach { u =>
      println(s"Tokens used: ${u.totalTokens}")
      println(s"  Prompt: ${u.promptTokens}")
      println(s"  Completion: ${u.completionTokens}")
    }
  
  case Left(error) =>
    println(s"Error: ${error.formatted}")
}
```

---

### CompletionOptions

**Purpose**: Configuration for LLM generation behavior.

```scala
case class CompletionOptions(
  temperature: Double = 0.7,              // Randomness (0.0 to 2.0)
  topP: Double = 1.0,                     // Nucleus sampling (0.0 to 1.0)
  maxTokens: Option[Int] = None,          // Max tokens to generate
  presencePenalty: Double = 0.0,          // Encourage new topics (-2.0 to 2.0)
  frequencyPenalty: Double = 0.0,         // Discourage repetition (-2.0 to 2.0)
  tools: Seq[ToolFunction[_, _]] = Seq.empty  // Available tools
)
```

**Common presets**:
```scala
// Creative writing
val creative = CompletionOptions(temperature = 1.2, topP = 0.95)

// Deterministic output
val deterministic = CompletionOptions(temperature = 0.0, topP = 1.0)

// Concise responses
val concise = CompletionOptions(maxTokens = Some(100))

// With tool calling
val withTools = CompletionOptions(
  temperature = 0.7,
  tools = Seq(weatherTool, calculatorTool)
)
```

---

### ToolCall

**Purpose**: Represents an LLM's request to execute a tool.

```scala
case class ToolCall(
  id: String,              // Unique ID (generated by LLM)
  name: String,            // Tool name from registry
  arguments: ujson.Value   // JSON arguments
)
```

**Flow**:
1. LLM returns `AssistantMessage` with `toolCalls` populated
2. Your code executes each tool
3. Results sent back as `ToolMessage` with matching `toolCallId`

**Example**:
```scala
val assistantMsg = completion.message

assistantMsg.toolCalls.foreach { toolCall =>
  println(s"Tool requested: ${toolCall.name}")
  println(s"Arguments: ${toolCall.arguments}")
  
  // Execute tool and create response
  val result = executeToolSomehow(toolCall.name, toolCall.arguments)
  val toolMsg = ToolMessage(toolCall.id, result.render())
}
```

---

### StreamedChunk

**Purpose**: Represents a chunk in streaming completion.

```scala
case class StreamedChunk(
  id: String,
  content: Option[String],                // Incremental text
  toolCall: Option[ToolCall] = None,      // Incremental tool call
  finishReason: Option[String] = None     // "stop", "length", "tool_calls"
)
```

**Note**: Streaming is not fully implemented in all providers yet. Many providers fall back to non-streaming `complete()`.

---

## Error Handling

### LLMError Hierarchy

**Purpose**: Comprehensive, structured error types with recovery guidance.

```scala
sealed abstract class LLMError extends Product with Serializable {
  /** Human-readable error message */
  def message: String
  
  /** Optional error code for programmatic handling */
  def code: Option[String] = None
  
  /** Additional context information */
  def context: Map[String, String] = Map.empty
  
  /** Whether this error is recoverable with retry */
  def isRecoverable: Boolean = false
  
  /** Suggested retry delay in milliseconds */
  def retryDelay: Option[Long] = None
  
  /** Formatted error message with context */
  def formatted: String
}
```

### Error Types

#### 1. AuthenticationError
```scala
case class AuthenticationError(
  message: String,
  provider: String,
  code: Option[String] = None
) extends LLMError
```

**When**: Invalid API key, expired token, missing credentials
**Recoverable**: No
**Action**: Check API key configuration

#### 2. RateLimitError
```scala
case class RateLimitError(
  message: String,
  retryAfter: Option[Long] = None,        // Seconds to wait
  provider: String,
  requestsRemaining: Option[Int] = None,
  resetTime: Option[Long] = None          // Unix timestamp
) extends LLMError {
  override val isRecoverable: Boolean = true
  override val retryDelay: Option[Long] = retryAfter.map(_ * 1000)
}
```

**When**: Too many requests to provider
**Recoverable**: Yes
**Action**: Wait for `retryDelay` then retry

#### 3. ServiceError
```scala
case class ServiceError(
  message: String,
  httpStatus: Int,
  provider: String,
  requestId: Option[String] = None
) extends LLMError {
  override val isRecoverable: Boolean = httpStatus >= 500
  override val retryDelay: Option[Long] = Some(1000)
}
```

**When**: Provider service issues (5xx HTTP codes)
**Recoverable**: Yes (if 5xx)
**Action**: Retry with exponential backoff

#### 4. ValidationError
```scala
case class ValidationError(
  message: String,
  field: String,
  violations: List[String] = List.empty
) extends LLMError
```

**When**: Invalid request parameters
**Recoverable**: No
**Action**: Fix request parameters

#### 5. NetworkError
```scala
case class NetworkError(
  message: String,
  cause: Option[Throwable] = None,
  endpoint: String
) extends LLMError {
  override val isRecoverable: Boolean = true
  override val retryDelay: Option[Long] = Some(2000)
}
```

**When**: Connection timeout, DNS failure, network issues
**Recoverable**: Yes
**Action**: Retry after delay

#### 6. ConfigurationError
```scala
case class ConfigurationError(
  message: String,
  missingKeys: List[String] = List.empty
) extends LLMError
```

**When**: Missing environment variables, invalid config
**Recoverable**: No
**Action**: Fix configuration

#### 7. UnknownError
```scala
case class UnknownError(
  message: String,
  cause: Throwable
) extends LLMError
```

**When**: Unexpected exceptions
**Recoverable**: No
**Action**: Log and investigate

### Smart Constructors

```scala
object LLMError {
  def authenticationFailed(provider: String, details: String): LLMError
  def rateLimited(provider: String, retryAfter: Option[Long] = None): LLMError
  def invalidField(field: String, reason: String): LLMError
  def missingConfig(keys: List[String]): LLMError
  def fromThrowable(throwable: Throwable): LLMError
}
```

### Error Handling Patterns

**Pattern 1: Match on Either**
```scala
client.complete(conversation) match {
  case Right(completion) => // success
  case Left(error: LLMError.RateLimitError) =>
    println(s"Rate limited, retry after ${error.retryAfter}")
  case Left(error: LLMError.AuthenticationError) =>
    println("Check API key!")
  case Left(error) =>
    println(error.formatted)
}
```

**Pattern 2: For-comprehension**
```scala
for {
  completion1 <- client.complete(conv1)
  completion2 <- client.complete(conv2)
} yield (completion1, completion2)
```

**Pattern 3: Retry with backoff**
```scala
def retryable(attempt: Int = 0): Either[LLMError, Completion] = {
  client.complete(conversation) match {
    case Left(error) if error.isRecoverable && attempt < 3 =>
      val delay = error.retryDelay.getOrElse(1000L) * Math.pow(2, attempt).toLong
      Thread.sleep(delay)
      retryable(attempt + 1)
    case result => result
  }
}
```

**Pattern 4: Fold**
```scala
val result: String = client.complete(conversation).fold(
  error => s"Error: ${error.message}",
  completion => completion.message.content
)
```

---

## Type System (org.llm4s.types)

### Result Type Aliases

```scala
package object types {
  /** Standard synchronous result type */
  type Result[+A] = Either[error.LLMError, A]
  
  /** Async result for Future-based operations */
  type AsyncResult[+A] = Future[Result[A]]
  
  /** Validated result with accumulating errors */
  type ValidatedResult[+A] = Either[List[error.LLMError], A]
  
  /** Optional result */
  type OptionalResult[+A] = Result[Option[A]]
  
  /** Multi-value result */
  type MultiResult[+A] = Result[List[A]]
}
```

### Type-Safe IDs (AnyVal newtypes)

**Purpose**: Zero-overhead type safety using `AnyVal`.

```scala
// Model and provider
case class ModelName(value: String) extends AnyVal {
  override def toString: String = value
}

case class ProviderName(value: String) extends AnyVal {
  override def toString: String = value
  def normalized: String = value.toLowerCase.trim
}

// Security-sensitive
case class ApiKey(private val value: String) extends AnyVal {
  override def toString: String = "ApiKey(***)"  // Never logs actual key
  def reveal: String = value
  def masked: String = 
    if (value.length > 8) s"${value.take(4)}...${value.takeRight(4)}" 
    else "***"
}

// Conversation tracking
case class ConversationId(value: String) extends AnyVal
case class CompletionId(value: String) extends AnyVal
case class MessageId(value: String) extends AnyVal

// Tool system
case class ToolName(value: String) extends AnyVal {
  def isValid: Boolean = value.matches("[a-zA-Z0-9_-]+")
}
case class ToolCallId(value: String) extends AnyVal

// Workspace and sessions
case class WorkspaceId(value: String) extends AnyVal
case class SessionId(value: String) extends AnyVal

// Tracing
case class RequestId(value: String) extends AnyVal
case class TraceId(value: String) extends AnyVal
```

**Benefits**:
- **Zero runtime overhead**: Compiled away to primitive types
- **Compile-time safety**: Cannot mix up `ToolCallId` and `ConversationId`
- **Secure logging**: `ApiKey.toString` never reveals actual value

### Smart Constructors with Validation

```scala
object ModelName {
  def create(value: String): Result[ModelName] =
    if (value.trim.nonEmpty) Right(ModelName(value.trim))
    else Left(LLMError.ValidationError("Model name cannot be empty", "modelName"))
  
  // Common constants
  val GPT_4: ModelName = ModelName("gpt-4")
  val GPT_4_TURBO: ModelName = ModelName("gpt-4-turbo")
  val CLAUDE_3_OPUS: ModelName = ModelName("claude-3-opus-20240229")
}

object ApiKey {
  def create(value: String): Result[ApiKey] =
    if (value.trim.nonEmpty) Right(ApiKey(value.trim))
    else Left(LLMError.ValidationError("API key cannot be empty", "apiKey"))
  
  def fromEnvironment(envVar: String): Result[ApiKey] =
    sys.env.get(envVar) match {
      case Some(key) => create(key)
      case None => Left(LLMError.ConfigurationError(s"Environment variable $envVar not found", List(envVar)))
    }
}

object ToolName {
  def create(value: String): Result[ToolName] = {
    val trimmed = value.trim
    if (trimmed.nonEmpty && trimmed.matches("[a-zA-Z0-9_-]+"))
      Right(ToolName(trimmed))
    else
      Left(LLMError.ValidationError(
        "Tool name must be non-empty and contain only alphanumeric characters, underscores, and hyphens",
        "toolName"
      ))
  }
}
```

### Result Utilities

```scala
object Result {
  def success[A](value: A): Result[A] = Right(value)
  def failure[A](error: LLMError): Result[A] = Left(error)
  
  def fromTry[A](t: Try[A]): Result[A] = t match {
    case Success(value) => success(value)
    case Failure(throwable) => failure(LLMError.fromThrowable(throwable))
  }
  
  def fromOption[A](opt: Option[A], error: => LLMError): Result[A] =
    opt.toRight(error)
  
  /** Sequence multiple results into one */
  def sequence[A](results: List[Result[A]]): Result[List[A]]
  
  /** Traverse with transformation */
  def traverse[A, B](list: List[A])(f: A => Result[B]): Result[List[B]]
  
  /** Combine two results into tuple */
  def combine[A, B](ra: Result[A], rb: Result[B]): Result[(A, B)]
}

object AsyncResult {
  def success[A](value: A): AsyncResult[A] = Future.successful(Right(value))
  def failure[A](error: LLMError): AsyncResult[A] = Future.successful(Left(error))
  
  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): AsyncResult[A] =
    future.map(Right(_)).recover { case ex => Left(LLMError.fromThrowable(ex)) }
}
```

**Usage**:
```scala
// Sequence
val results = List(Right(1), Right(2), Right(3))
Result.sequence(results)  // Right(List(1, 2, 3))

// Traverse
val ids = List("1", "2", "3")
Result.traverse(ids)(id => fetchUser(id))  // Result[List[User]]

// Combine
for {
  (user, settings) <- Result.combine(fetchUser(id), fetchSettings(id))
} yield (user, settings)
```

---

## Quick Start Example

```scala
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

object QuickStart extends App {
  // 1. Get client from environment (LLM_MODEL=openai/gpt-4o)
  val client: LLMClient = LLM.client()
  
  // 2. Create conversation
  val conversation = Conversation(Seq(
    SystemMessage("You are a helpful assistant."),
    UserMessage("What is the capital of France?")
  ))
  
  // 3. Complete conversation
  client.complete(conversation) match {
    case Right(completion) =>
      println(s"Response: ${completion.message.content}")
      completion.usage.foreach { u =>
        println(s"Tokens: ${u.totalTokens}")
      }
    
    case Left(error) =>
      println(s"Error: ${error.formatted}")
  }
}
```

---

## Next Steps

- **Tool System**: See `llm4s-tool-system.md` for structured tool calling
- **Agent Patterns**: See `llm4s-agent-patterns.md` for multi-step reasoning
- **Examples**: See `llm4s-usage-examples.md` for real-world code
- **Best Practices**: See `llm4s-best-practices.md` for gotchas and patterns
