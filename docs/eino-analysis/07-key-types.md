# Eino Framework Analysis - Key Types and Their Purposes

## 1. Schema Types

### Message

The core data structure for LLM conversations.

```go
type Message struct {
    Role                     RoleType              // assistant, user, system, tool
    Content                  string                // Text content
    UserInputMultiContent    []MessageInputPart    // Multimodal user input
    AssistantGenMultiContent []MessageOutputPart   // Multimodal assistant output
    Name                     string                // Optional name
    ToolCalls                []ToolCall            // Tool calls (assistant only)
    ToolCallID               string                // Tool call ID (tool only)
    ToolName                 string                // Tool name (tool only)
    ResponseMeta             *ResponseMeta         // Finish reason, usage, logprobs
    ReasoningContent         string                // Reasoning/thinking content
    Extra                    map[string]any        // Custom data
}

// Role types
const (
    Assistant RoleType = "assistant"
    User      RoleType = "user"
    System    RoleType = "system"
    Tool      RoleType = "tool"
)
```

**Purpose**: Unified message format for all LLM interactions.

**Scala 3 Translation**:
```scala
enum RoleType:
  case Assistant, User, System, Tool

case class Message(
  role: RoleType,
  content: String,
  userInputMultiContent: List[MessageInputPart] = Nil,
  assistantGenMultiContent: List[MessageOutputPart] = Nil,
  name: Option[String] = None,
  toolCalls: List[ToolCall] = Nil,
  toolCallId: Option[String] = None,
  toolName: Option[String] = None,
  responseMeta: Option[ResponseMeta] = None,
  reasoningContent: Option[String] = None,
  extra: Map[String, Json] = Map.empty
)

object Message:
  def system(content: String): Message = Message(RoleType.System, content)
  def user(content: String): Message = Message(RoleType.User, content)
  def assistant(content: String, toolCalls: List[ToolCall] = Nil): Message = 
    Message(RoleType.Assistant, content, toolCalls = toolCalls)
  def tool(content: String, toolCallId: String): Message =
    Message(RoleType.Tool, content, toolCallId = Some(toolCallId))
```

### LLM4S Message Types (Use Directly)

**LLM4S already provides these types - use them directly**:

```scala
import org.llm4s.llmconnect.model.{Message, UserMessage, SystemMessage, AssistantMessage, ToolMessage}

// LLM4S Message hierarchy (sealed trait)
sealed trait Message
case class UserMessage(content: String) extends Message
case class SystemMessage(content: String) extends Message
case class AssistantMessage(content: Option[String], toolCalls: List[ToolCall] = Nil) extends Message
case class ToolMessage(content: String, toolCallId: String) extends Message

// Conversation wraps messages
case class Conversation(messages: Vector[Message]):
  def addMessage(message: Message): Conversation = 
    Conversation(messages :+ message)
  def lastAssistantMessage: Option[String] = 
    messages.collectLast { case AssistantMessage(Some(content), _) => content }

// Usage
val conversation: Conversation = Conversation(Vector(
  SystemMessage("You are helpful"),
  UserMessage("Hello")
))
```

### structured-llm Message/Prompt Types

**structured-llm provides its own Prompt abstraction that wraps LLM4S**:

```scala
import org.adk4s.structured.core.{Message, Role, Prompt}

// structured-llm Message (internal representation)
case class Message(role: Role, content: String)

enum Role:
  case System, User, Assistant, Tool

// Prompt is a collection of messages
case class Prompt(messages: Vector[Message]):
  def addUserMessage(content: String): Prompt
  def withOutputFormat[A: Schema]: Prompt  // Injects schema into prompt

// Conversion to LLM4S Conversation happens internally
// in StructuredLLM.toConversation()
```

---

### ToolCall / ToolInfo

Tool invocation and definition structures.

```go
// Tool call from LLM
type ToolCall struct {
    Index    *int         // Position in multi-call
    ID       string       // Unique call ID
    Type     string       // Usually "function"
    Function FunctionCall // Name and arguments
    Extra    map[string]any
}

type FunctionCall struct {
    Name      string // Function name
    Arguments string // JSON arguments
}

// Tool definition
type ToolInfo struct {
    Name  string
    Desc  string
    Extra map[string]any
    *ParamsOneOf  // Parameters schema
}

type ParameterInfo struct {
    Type      DataType              // object, string, number, etc.
    ElemInfo  *ParameterInfo        // For arrays
    SubParams map[string]*ParameterInfo // For objects
    Desc      string
    Enum      []string
    Required  bool
}
```

**Purpose**: Define tools for LLM function calling.

**Scala 3 Translation**:
```scala
case class ToolCall(
  index: Option[Int],
  id: String,
  `type`: String = "function",
  function: FunctionCall,
  extra: Map[String, Json] = Map.empty
)

case class FunctionCall(name: String, arguments: String)

case class ToolInfo(
  name: String,
  description: String,
  parameters: Option[ParameterSchema],
  extra: Map[String, Json] = Map.empty
)

// Use Smithy for parameter schema (per project.md)
// ParameterSchema would be generated from Smithy definitions
```

### LLM4S Tool System (Use Directly)

**LLM4S provides a complete tool system**:

```scala
import org.llm4s.tools.{ToolFunction, ToolBuilder, ToolRegistry, SchemaDefinition}
import org.llm4s.tools.{StringSchema, NumberSchema, ObjectSchema, ArraySchema}

// ToolFunction is the core tool type
case class ToolFunction(
  name: String,
  description: String,
  parameters: SchemaDefinition,
  handler: Map[String, Any] => Either[String, String]
)

// Build tools with ToolBuilder
val weatherTool: ToolFunction = ToolBuilder("get_weather")
  .description("Get current weather for a location")
  .parameter("location", StringSchema("City name"), required = true)
  .parameter("units", StringSchema("celsius or fahrenheit"), required = false)
  .handler { params =>
    val location: String = params.getString("location").getOrElse("Unknown")
    Right(s"Weather in $location: Sunny, 25°C")
  }
  .build

// ToolRegistry manages multiple tools
val registry: ToolRegistry = ToolRegistry(List(weatherTool, calculatorTool))

// Execute tool by name
val result: Either[String, String] = registry.execute("get_weather", Map("location" -> "Beijing"))
```

### LLM4S Schema Definitions

**LLM4S provides type-safe schema definitions**:

```scala
import org.llm4s.tools.{SchemaDefinition, StringSchema, NumberSchema, ObjectSchema, ArraySchema, NullableSchema}

// Primitive schemas
val stringParam: SchemaDefinition = StringSchema("A text value")
val numberParam: SchemaDefinition = NumberSchema("A numeric value")

// Object schema with properties
val objectParam: SchemaDefinition = ObjectSchema(
  description = "User information",
  properties = Map(
    "name" -> StringSchema("User's name"),
    "age" -> NumberSchema("User's age")
  ),
  required = List("name")
)

// Array schema
val arrayParam: SchemaDefinition = ArraySchema(
  description = "List of items",
  items = StringSchema("An item")
)

// Nullable wrapper
val optionalParam: SchemaDefinition = NullableSchema(StringSchema("Optional value"))
```

---

### StreamReader / StreamWriter

Stream abstraction for chunked data.

```go
type StreamReader[T any] struct {
    typ readerType  // Implementation variant
    st  *stream[T]  // Channel-based stream
    ar  *arrayReader[T]  // Array-based
    msr *multiStreamReader[T]  // Merged streams
    srw *streamReaderWithConvert[T]  // Converted stream
    csr *childStreamReader[T]  // Copied stream
}

func (sr *StreamReader[T]) Recv() (T, error)
func (sr *StreamReader[T]) Close()
func (sr *StreamReader[T]) Copy(n int) []*StreamReader[T]

type StreamWriter[T any] struct {
    stm *stream[T]
}

func (sw *StreamWriter[T]) Send(chunk T, err error) (closed bool)
func (sw *StreamWriter[T]) Close()

// Creation
func Pipe[T any](cap int) (*StreamReader[T], *StreamWriter[T])
func StreamReaderFromArray[T any](arr []T) *StreamReader[T]
```

**Purpose**: Unified streaming interface for LLM responses.

**Scala 3 Translation**:
```scala
// Use fs2.Stream directly - no need for custom StreamReader
type StreamReader[F[_], T] = fs2.Stream[F, T]

// Pipe is just a function
type Pipe[F[_], I, O] = fs2.Stream[F, I] => fs2.Stream[F, O]

// If needed, wrapper for compatibility
class StreamWrapper[F[_], T](underlying: fs2.Stream[F, T]):
  def recv: F[Option[T]] = underlying.head.compile.last
  def close: F[Unit] = underlying.compile.drain
```

### LLM4S Streaming Types

**LLM4S provides streaming via `StreamedChunk`**:

```scala
import org.llm4s.llmconnect.model.StreamedChunk

// StreamedChunk represents a chunk of streamed response
case class StreamedChunk(
  content: String,
  finishReason: Option[String],
  toolCalls: List[ToolCall]
)

// Streaming returns Iterator[StreamedChunk]
val streamResult: Either[LLMError, Iterator[StreamedChunk]] = 
  client.completeStreamed(conversation, options)

// Convert to fs2.Stream for functional composition
import fs2.Stream
import cats.effect.IO

def toFs2Stream(iter: Iterator[StreamedChunk]): Stream[IO, StreamedChunk] =
  Stream.fromIterator[IO](iter, chunkSize = 1)

// Usage with fs2 operations
val processed: Stream[IO, String] = streamResult match
  case Right(iter) => toFs2Stream(iter).map(_.content).filter(_.nonEmpty)
  case Left(error) => Stream.raiseError[IO](new RuntimeException(error.formatted))
```

---

### Document

Document structure for RAG applications.

```go
type Document struct {
    ID       string
    Content  string
    MetaData map[string]any
}
```

**Purpose**: Represent documents for retrieval and indexing.

**Scala 3 Translation**:
```scala
case class Document(
  id: String,
  content: String,
  metadata: Map[String, Json] = Map.empty
)
```

---

## 2. Component Interfaces

### BaseChatModel

Core LLM interface.

```go
type BaseChatModel interface {
    Generate(ctx context.Context, input []*Message, opts ...Option) (*Message, error)
    Stream(ctx context.Context, input []*Message, opts ...Option) (*StreamReader[*Message], error)
}

// Extended with tool binding
type ToolCallingChatModel interface {
    BaseChatModel
    WithTools(tools []*ToolInfo) (ToolCallingChatModel, error)
}
```

**Purpose**: Abstract LLM providers (OpenAI, Anthropic, etc.).

**Scala 3 Translation**:
```scala
trait ChatModel[F[_]]:
  def generate(messages: List[Message], config: ChatModelConfig): F[Message]
  def stream(messages: List[Message], config: ChatModelConfig): fs2.Stream[F, Message]

trait ToolCallingChatModel[F[_]] extends ChatModel[F]:
  def withTools(tools: List[ToolInfo]): F[ToolCallingChatModel[F]]
```

---

### BaseTool / InvokableTool / StreamableTool

Tool interfaces.

```go
type BaseTool interface {
    Info(ctx context.Context) (*ToolInfo, error)
}

type InvokableTool interface {
    BaseTool
    InvokableRun(ctx context.Context, argumentsInJSON string, opts ...Option) (string, error)
}

type StreamableTool interface {
    BaseTool
    StreamableRun(ctx context.Context, argumentsInJSON string, opts ...Option) (*StreamReader[string], error)
}
```

**Purpose**: Define executable tools for agents.

**Scala 3 Translation**:
```scala
trait Tool[F[_]]:
  def info: F[ToolInfo]

trait InvokableTool[F[_]] extends Tool[F]:
  def run(arguments: String): F[String]

trait StreamableTool[F[_]] extends Tool[F]:
  def runStream(arguments: String): fs2.Stream[F, String]
```

---

### ChatTemplate

Prompt templating.

```go
type ChatTemplate interface {
    Format(ctx context.Context, vs map[string]any, opts ...Option) ([]*Message, error)
}
```

**Purpose**: Generate messages from templates with variable substitution.

**Scala 3 Translation**:
```scala
trait ChatTemplate[F[_]]:
  def format(variables: Map[String, Any]): F[List[Message]]

// Or using Smithy-generated types for type-safe variables
trait TypedChatTemplate[F[_], V]:
  def format(variables: V): F[List[Message]]
```

---

### Retriever / Embedder / Indexer

RAG components.

```go
type Retriever interface {
    Retrieve(ctx context.Context, query string, opts ...Option) ([]*Document, error)
}

type Embedder interface {
    EmbedStrings(ctx context.Context, texts []string, opts ...Option) ([][]float64, error)
}

type Indexer interface {
    Store(ctx context.Context, docs []*Document, opts ...Option) ([]string, error)
}
```

**Purpose**: Document retrieval, embedding, and storage.

**Scala 3 Translation**:
```scala
trait Retriever[F[_]]:
  def retrieve(query: String, config: RetrieverConfig): F[List[Document]]

trait Embedder[F[_]]:
  def embed(texts: List[String]): F[List[Vector[Double]]]

trait Indexer[F[_]]:
  def store(documents: List[Document]): F[List[String]]
```

---

## 3. Compose Types

### Graph / Chain / Workflow

Orchestration containers.

```go
type Graph[I, O any] struct {
    nodes        map[string]*graphNode
    controlEdges map[string][]string
    dataEdges    map[string][]string
    branches     map[string][]*GraphBranch
    // ... state, validation, etc.
}

type Chain[I, O any] struct {
    gg          *Graph[I, O]
    nodeIdx     int
    preNodeKeys []string
    hasEnd      bool
    err         error
}

type Workflow[I, O any] struct {
    g             *graph
    workflowNodes map[string]*WorkflowNode
    dependencies  map[string]map[string]dependencyType
}
```

**Purpose**: Different orchestration styles for composing components.

**Scala 3 Translation**:
```scala
class Graph[I, O] private (
  nodes: Map[String, GraphNode[?, ?]],
  controlEdges: Map[String, List[String]],
  dataEdges: Map[String, List[String]],
  branches: Map[String, List[GraphBranch[?]]],
  stateGen: Option[IO[Any]]
):
  def addNode[A, B](key: String, node: Node[A, B]): Either[GraphError, Graph[I, O]]
  def addEdge(from: String, to: String): Either[GraphError, Graph[I, O]]
  def compile: IO[Runnable[I, O]]

class Chain[I, O] private (graph: Graph[I, O], preNodes: List[String]):
  def appendChatModel(model: ChatModel[IO]): Chain[I, Message]
  def appendLambda[A, B](f: A => IO[B]): Chain[I, B]
  def compile: IO[Runnable[I, O]]
```

---

### Runnable

Compiled executable.

```go
type Runnable[I, O any] interface {
    Invoke(ctx context.Context, input I, opts ...Option) (output O, err error)
    Stream(ctx context.Context, input I, opts ...Option) (output *StreamReader[O], err error)
    Collect(ctx context.Context, input *StreamReader[I], opts ...Option) (output O, err error)
    Transform(ctx context.Context, input *StreamReader[I], opts ...Option) (output *StreamReader[O], err error)
}
```

**Purpose**: Unified execution interface for compiled graphs.

**Scala 3 Translation**:
```scala
trait Runnable[I, O]:
  def invoke(input: I): IO[O]
  def stream(input: I): fs2.Stream[IO, O]
  def collect(input: fs2.Stream[IO, I]): IO[O]
  def transform(input: fs2.Stream[IO, I]): fs2.Stream[IO, O]
```

---

### Lambda

Custom logic wrapper.

```go
type Lambda struct {
    executor *composableRunnable
}

// Creation functions
func InvokableLambda[I, O any](i InvokeWOOpt[I, O], opts ...LambdaOpt) *Lambda
func StreamableLambda[I, O any](s StreamWOOpt[I, O], opts ...LambdaOpt) *Lambda
func TransformableLambda[I, O any](t TransformWOOpts[I, O], opts ...LambdaOpt) *Lambda
```

**Purpose**: Wrap user functions as graph nodes.

**Scala 3 Translation**:
```scala
sealed trait Lambda[I, O]

object Lambda:
  def invokable[I, O](f: I => IO[O]): Lambda[I, O] = InvokableLambda(f)
  def streamable[I, O](f: I => fs2.Stream[IO, O]): Lambda[I, O] = StreamableLambda(f)
  def transformable[I, O](f: fs2.Stream[IO, I] => fs2.Stream[IO, O]): Lambda[I, O] = 
    TransformableLambda(f)

case class InvokableLambda[I, O](f: I => IO[O]) extends Lambda[I, O]
case class StreamableLambda[I, O](f: I => fs2.Stream[IO, O]) extends Lambda[I, O]
case class TransformableLambda[I, O](f: fs2.Stream[IO, I] => fs2.Stream[IO, O]) extends Lambda[I, O]
```

---

### ToolsNode

Tool execution node.

```go
type ToolsNode struct {
    tuple                *toolsTuple
    unknownToolHandler   func(ctx, name, input string) (string, error)
    executeSequentially  bool
    toolArgumentsHandler func(ctx, name, input string) (string, error)
    toolCallMiddlewares  []InvokableToolMiddleware
}

// Input: AssistantMessage with ToolCalls
// Output: Array of ToolMessages
func (tn *ToolsNode) Invoke(ctx, input *Message, opts ...ToolsNodeOption) ([]*Message, error)
func (tn *ToolsNode) Stream(ctx, input *Message, opts ...ToolsNodeOption) (*StreamReader[[]*Message], error)
```

**Purpose**: Execute tool calls from LLM responses.

**Scala 3 Translation**:
```scala
case class ToolsNodeConfig(
  tools: List[Tool[IO]],
  unknownToolHandler: Option[(String, String) => IO[String]] = None,
  executeSequentially: Boolean = false,
  argumentsHandler: Option[(String, String) => IO[String]] = None,
  middlewares: List[ToolMiddleware[IO]] = Nil
)

class ToolsNode(config: ToolsNodeConfig):
  def invoke(input: Message): IO[List[Message]]
  def stream(input: Message): fs2.Stream[IO, List[Message]]
```

---

### GraphBranch

Conditional routing.

```go
type GraphBranch struct {
    invoke        func(ctx, input any) ([]string, error)
    collect       func(ctx, sr streamReader) ([]string, error)
    inputType     reflect.Type
    endNodes      map[string]bool
    genericHelper *genericHelper
}

func NewGraphBranch[I any](condition func(ctx, I) (string, error), endNodes map[string]bool) *GraphBranch
func NewStreamGraphBranch[I any](condition func(ctx, *StreamReader[I]) (string, error), endNodes map[string]bool) *GraphBranch
```

**Purpose**: Dynamic routing based on runtime conditions.

**Scala 3 Translation**:
```scala
sealed trait GraphBranch[I]:
  def endNodes: Set[String]

case class InvokeBranch[I](
  condition: I => IO[String],
  endNodes: Set[String]
) extends GraphBranch[I]

case class StreamBranch[I](
  condition: fs2.Stream[IO, I] => IO[String],
  endNodes: Set[String]
) extends GraphBranch[I]

object GraphBranch:
  def apply[I](condition: I => IO[String], endNodes: Set[String]): GraphBranch[I] =
    InvokeBranch(condition, endNodes)
  
  def stream[I](condition: fs2.Stream[IO, I] => IO[String], endNodes: Set[String]): GraphBranch[I] =
    StreamBranch(condition, endNodes)
```

---

## 4. Callback Types

### Handler / RunInfo

Callback interfaces.

```go
type Handler interface {
    OnStart(ctx context.Context, info *RunInfo, input CallbackInput) context.Context
    OnEnd(ctx context.Context, info *RunInfo, output CallbackOutput) context.Context
    OnError(ctx context.Context, info *RunInfo, err error) context.Context
    OnStartWithStreamInput(ctx context.Context, info *RunInfo, input *StreamReader[CallbackInput]) context.Context
    OnEndWithStreamOutput(ctx context.Context, info *RunInfo, output *StreamReader[CallbackOutput]) context.Context
}

type RunInfo struct {
    Name      string
    Type      string
    Component string
}
```

**Purpose**: Cross-cutting concerns (logging, tracing, metrics).

**Scala 3 Translation**:
```scala
case class RunInfo(
  name: String,
  `type`: String,
  component: String
)

trait CallbackHandler[F[_]]:
  def onStart[I](info: RunInfo, input: I): F[Unit] = Applicative[F].unit
  def onEnd[O](info: RunInfo, output: O): F[Unit] = Applicative[F].unit
  def onError(info: RunInfo, error: Throwable): F[Unit] = Applicative[F].unit
  def onStartWithStream[I](info: RunInfo, input: fs2.Stream[F, I]): F[Unit] = Applicative[F].unit
  def onEndWithStream[O](info: RunInfo, output: fs2.Stream[F, O]): F[Unit] = Applicative[F].unit
```
