# Eino Framework Analysis - API Design

## 1. Generic Type Parameters

Eino uses Go generics extensively for type safety.

### Graph/Chain Type Parameters

```go
// Graph with input/output types
type Graph[I, O any] struct { ... }

// Chain with input/output types
type Chain[I, O any] struct { ... }

// Runnable with input/output types
type Runnable[I, O any] interface {
    Invoke(ctx context.Context, input I, opts ...Option) (O, error)
    Stream(ctx context.Context, input I, opts ...Option) (*StreamReader[O], error)
    Collect(ctx context.Context, input *StreamReader[I], opts ...Option) (O, error)
    Transform(ctx context.Context, input *StreamReader[I], opts ...Option) (*StreamReader[O], error)
}
```

### Lambda Type Parameters

```go
// Lambda functions with three type parameters
type Invoke[I, O, TOption any] func(ctx context.Context, input I, opts ...TOption) (O, error)
type Stream[I, O, TOption any] func(ctx context.Context, input I, opts ...TOption) (*StreamReader[O], error)
type Collect[I, O, TOption any] func(ctx context.Context, input *StreamReader[I], opts ...TOption) (O, error)
type Transform[I, O, TOption any] func(ctx context.Context, input *StreamReader[I], opts ...TOption) (*StreamReader[O], error)
```

### Scala 3 Translation

```scala
// Graph with type parameters
class Graph[I, O](using TypeTag[I], TypeTag[O]):
  def addNode[A, B](key: String, node: Node[A, B]): Graph[I, O]
  def compile: IO[Runnable[I, O]]

// Runnable trait
trait Runnable[I, O]:
  def invoke(input: I): IO[O]
  def stream(input: I): fs2.Stream[IO, O]
  def collect(input: fs2.Stream[IO, I]): IO[O]
  def transform(input: fs2.Stream[IO, I]): fs2.Stream[IO, O]

// Lambda with effect type parameter
type InvokeLambda[F[_], I, O] = I => F[O]
type StreamLambda[F[_], I, O] = I => fs2.Stream[F, O]
type CollectLambda[F[_], I, O] = fs2.Stream[F, I] => F[O]
type TransformLambda[F[_], I, O] = fs2.Stream[F, I] => fs2.Stream[F, O]
```

### Workflows4s WIO Type Parameters

**Workflows4s WIO uses comprehensive type parameters**:

```scala
import workflows4s.wio.WIO

// WIO[Input, Error, Output, Context] - four type parameters
// - Input: What the workflow step receives
// - Error: Error type (use Nothing for infallible steps)
// - Output: What the workflow step produces
// - Context: WorkflowContext defining State and Event types

val step: WIO[String, MyError, Int, MyContext.type] = 
  WIO.runIO { input => IO.pure(input.length) }

// Type parameters flow through composition
val composed: WIO[String, MyError, String, MyContext.type] =
  step.flatMap { n => WIO.pure(s"Length: $n") }
```

### LLM4S Type Parameters

**LLM4S uses explicit types throughout**:

```scala
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions}
import org.llm4s.error.LLMError

// LLMClient methods have explicit return types
trait LLMClient:
  def complete(
    conversation: Conversation, 
    options: CompletionOptions
  ): Either[LLMError, Completion]
  
  def completeStreamed(
    conversation: Conversation, 
    options: CompletionOptions
  ): Either[LLMError, Iterator[StreamedChunk]]

// Agent uses explicit state types
case class AgentState(
  conversation: Conversation,
  pendingToolCalls: List[ToolCall],
  status: AgentStatus,
  stepCount: Int
)
```

### structured-llm Generic Type Parameters

**structured-llm uses Schema typeclass for type-safe outputs**:

```scala
import org.adk4s.structured.core.{StructuredLLM, Schema, Prompt}

// StructuredLLM is parameterized by effect type F[_]
trait StructuredLLM[F[_]]:
  def complete[A: Schema](prompt: Prompt): F[A]
  def function[I, A: Schema](template: PromptTemplate[I]): I => F[A]
  def extractor[A: Schema](systemPrompt: String): String => F[A]

// Usage with explicit type parameter
val result: IO[MyOutputType] = structured.complete[MyOutputType](prompt)
```

**ADK4S Recommendation**: Use type parameters consistently. LLM4S, Workflows4s, and structured-llm all follow this pattern - maintain consistency when extending.

---

## 2. Constructor Functions

Eino uses constructor functions rather than direct struct instantiation.

### Component Constructors

```go
// ChatModel constructor (in eino-ext)
func NewChatModel(ctx context.Context, config *ChatModelConfig) (*ChatModel, error)

// ToolsNode constructor
func NewToolNode(ctx context.Context, conf *ToolsNodeConfig) (*ToolsNode, error)

// Graph constructor
func NewGraph[I, O any](opts ...NewGraphOption) *Graph[I, O]

// Chain constructor
func NewChain[I, O any](opts ...NewGraphOption) *Chain[I, O]
```

### Lambda Constructors

```go
// Different lambda constructors for different paradigms
func InvokableLambda[I, O any](i InvokeWOOpt[I, O], opts ...LambdaOpt) *Lambda
func StreamableLambda[I, O any](s StreamWOOpt[I, O], opts ...LambdaOpt) *Lambda
func CollectableLambda[I, O any](c CollectWOOpt[I, O], opts ...LambdaOpt) *Lambda
func TransformableLambda[I, O any](t TransformWOOpts[I, O], opts ...LambdaOpt) *Lambda

// Combined constructor
func AnyLambda[I, O, TOption any](
    i Invoke[I, O, TOption], 
    s Stream[I, O, TOption],
    c Collect[I, O, TOption], 
    t Transform[I, O, TOption], 
    opts ...LambdaOpt,
) (*Lambda, error)
```

### Scala 3 Translation

```scala
// Using companion objects
object Graph:
  def apply[I, O](using TypeTag[I], TypeTag[O]): Graph[I, O] = 
    new Graph[I, O]
  
  def withState[I, O, S](genState: IO[S])(using TypeTag[I], TypeTag[O], TypeTag[S]): Graph[I, O] =
    new Graph[I, O](Some(genState))

// Lambda smart constructors
object Lambda:
  def invokable[I, O](f: I => IO[O]): Lambda[I, O] = ???
  def streamable[I, O](f: I => fs2.Stream[IO, O]): Lambda[I, O] = ???
  def collectable[I, O](f: fs2.Stream[IO, I] => IO[O]): Lambda[I, O] = ???
  def transformable[I, O](f: fs2.Stream[IO, I] => fs2.Stream[IO, O]): Lambda[I, O] = ???
```

### LLM4S Constructor Patterns

**LLM4S uses companion object `create` methods**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.tools.{ToolBuilder, ToolRegistry}
import org.llm4s.agent.Agent

// LLMClient.create() auto-detects provider from environment
val client: LLMClient = LLMClient.create()

// ToolBuilder uses fluent API
val tool: ToolFunction = ToolBuilder("get_weather")
  .description("Get weather")
  .parameter("location", StringSchema("City"), required = true)
  .handler(params => Right("Sunny"))
  .build

// ToolRegistry from list
val registry: ToolRegistry = ToolRegistry(List(tool1, tool2))

// Agent constructor
val agent: Agent = Agent(
  client = client,
  tools = registry,
  systemPrompt = Some("You are helpful")
)
```

### structured-llm Constructor Patterns

**structured-llm uses `fromClient` factory methods**:

```scala
import org.adk4s.structured.core.{StructuredLLM, Prompt, PromptTemplate}

// Create from LLMClient
val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)

// With logging enabled
val structuredWithLogging: StructuredLLM[IO] = 
  StructuredLLM.fromClientWithLogging[IO](client)

// With custom options
val structuredWithOptions: CompletionOptions => StructuredLLM[IO] = 
  StructuredLLM.fromClientWithOptions[IO](client)

// Prompt constructors
val prompt: Prompt = Prompt.simple("system", "user message")
val template: PromptTemplate[String] = PromptTemplate.withSystem("You are helpful")
```

### Workflows4s Constructor Patterns

**Workflows4s uses object-based context definitions**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}
import workflows4s.runtime.{WorkflowRuntime, InMemorySyncRuntime}

// WorkflowContext as object
object MyContext extends WorkflowContext:
  override type State = MyState
  override type Event = MyEvent

// WIO constructors
val pureStep: WIO[String, Nothing, String, MyContext.type] = WIO.pure(identity)
val ioStep: WIO[String, Nothing, Int, MyContext.type] = WIO.runIO(_ => IO.pure(42))

// Runtime creation
val runtime: WorkflowRuntime[IO, MyContext.type] = InMemorySyncRuntime.create[MyContext.type]
```

**ADK4S Recommendation**: Use companion objects with `apply` and `fromX` methods. LLM4S, Workflows4s, and structured-llm provide consistent patterns to follow.

---

## 3. Method Naming Conventions

### Component Methods

| Pattern | Example | Purpose |
|---------|---------|---------|
| `Generate` | `model.Generate(ctx, msgs)` | Synchronous execution |
| `Stream` | `model.Stream(ctx, msgs)` | Streaming execution |
| `Invoke` | `runnable.Invoke(ctx, input)` | Non-stream in/out |
| `Collect` | `runnable.Collect(ctx, stream)` | Stream in, non-stream out |
| `Transform` | `runnable.Transform(ctx, stream)` | Stream in/out |

### Graph Building Methods

| Pattern | Example | Purpose |
|---------|---------|---------|
| `Add*Node` | `AddChatModelNode` | Add typed node |
| `AddEdge` | `AddEdge(from, to)` | Add edge |
| `AddBranch` | `AddBranch(from, branch)` | Add conditional branch |
| `Compile` | `graph.Compile(ctx)` | Build runnable |

### Chain Building Methods

| Pattern | Example | Purpose |
|---------|---------|---------|
| `Append*` | `AppendChatModel` | Add node to chain |
| `AppendParallel` | `AppendParallel(p)` | Add parallel nodes |
| `AppendBranch` | `AppendBranch(b)` | Add conditional branch |

### Scala 3 Translation

```scala
// Component methods - use lowercase per Scala convention
trait ChatModel[F[_]]:
  def generate(messages: List[Message]): F[Message]
  def stream(messages: List[Message]): fs2.Stream[F, Message]

// Graph building - use lowercase, return new instance
class Graph[I, O]:
  def addChatModelNode(key: String, model: ChatModel[IO]): Graph[I, O]
  def addEdge(from: String, to: String): Either[GraphError, Graph[I, O]]
  def compile: IO[Runnable[I, O]]

// Chain building - fluent API
class Chain[I, O]:
  def appendChatModel(model: ChatModel[IO]): Chain[I, ?]
  def appendLambda[A, B](f: A => IO[B]): Chain[I, B]
```

**ADK4S Recommendation**: Follow Scala naming conventions (camelCase). Keep method names descriptive but concise.

---

## 4. Option/Configuration Patterns

### Functional Options (Go)

```go
// Option type
type GraphCompileOption func(*graphCompileOptions)

// Option functions
func WithMaxRunSteps(steps int) GraphCompileOption
func WithGraphName(name string) GraphCompileOption
func WithNodeTriggerMode(mode NodeTriggerMode) GraphCompileOption
func WithCallbacks(handlers ...Handler) Option

// Usage
graph.Compile(ctx, 
    WithMaxRunSteps(10),
    WithGraphName("MyGraph"),
)
```

### Scala 3 Translation Options

```scala
// Option 1: Case class with defaults (recommended)
case class GraphCompileConfig(
  maxRunSteps: Int = 100,
  graphName: Option[String] = None,
  nodeTriggerMode: NodeTriggerMode = NodeTriggerMode.AnyPredecessor,
  callbacks: List[CallbackHandler[IO]] = Nil
)

graph.compile(GraphCompileConfig(maxRunSteps = 10, graphName = Some("MyGraph")))

// Option 2: Builder pattern
case class GraphCompileConfigBuilder private (config: GraphCompileConfig):
  def maxRunSteps(n: Int): GraphCompileConfigBuilder = 
    copy(config = config.copy(maxRunSteps = n))
  def graphName(name: String): GraphCompileConfigBuilder =
    copy(config = config.copy(graphName = Some(name)))
  def build: GraphCompileConfig = config

object GraphCompileConfigBuilder:
  def apply(): GraphCompileConfigBuilder = 
    GraphCompileConfigBuilder(GraphCompileConfig())

// Option 3: Context parameters (for cross-cutting options)
def compile(using config: GraphCompileConfig = GraphCompileConfig()): IO[Runnable[I, O]]
```

**ADK4S Recommendation**: Use case classes with defaults for configuration. Named parameters provide similar ergonomics to Go's functional options.

---

## 5. Error Return Patterns

### Go Error Handling

```go
// Methods return (result, error)
func (g *Graph[I, O]) Compile(ctx context.Context, opts ...GraphCompileOption) (Runnable[I, O], error)

// Error checking
r, err := graph.Compile(ctx)
if err != nil {
    return nil, fmt.Errorf("compile failed: %w", err)
}
```

### Accumulated Errors

```go
// Chain accumulates errors, reports at Compile
type Chain[I, O any] struct {
    err error  // First error encountered
    // ...
}

func (c *Chain[I, O]) AppendLambda(node *Lambda, opts ...GraphAddNodeOpt) *Chain[I, O] {
    if c.err != nil {
        return c  // Skip if already errored
    }
    // ... add node
    if err != nil {
        c.err = err  // Store first error
    }
    return c
}

func (c *Chain[I, O]) Compile(ctx context.Context, opts ...GraphCompileOption) (Runnable[I, O], error) {
    if c.err != nil {
        return nil, c.err  // Return accumulated error
    }
    // ...
}
```

### Scala 3 Translation

```scala
// Option 1: IO with error channel
def compile: IO[Runnable[I, O]]  // Errors in IO's error channel

// Option 2: Either for validation errors
def compile: Either[GraphError, IO[Runnable[I, O]]]

// Option 3: Validated for accumulated errors
import cats.data.Validated
import cats.data.ValidatedNec

def compile: ValidatedNec[GraphError, IO[Runnable[I, O]]]

// Chain with accumulated errors using Writer or State
class ChainBuilder[I, O]:
  private val errors: List[GraphError] = Nil
  
  def appendLambda[A, B](f: A => IO[B]): ChainBuilder[I, B] = ???
  
  def compile: ValidatedNec[GraphError, IO[Runnable[I, O]]] =
    if errors.isEmpty then Valid(buildRunnable)
    else Invalid(NonEmptyChain.fromSeq(errors).get)
```

### LLM4S Error Return Pattern

**LLM4S uses `Either[LLMError, A]` consistently**:

```scala
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.Completion

// All LLM operations return Either
val result: Either[LLMError, Completion] = client.complete(conversation, options)

// LLMError is a sealed hierarchy
sealed trait LLMError:
  def message: String
  def isRecoverable: Boolean
  def formatted: String

case class APIError(message: String, statusCode: Option[Int]) extends LLMError
case class RateLimitError(message: String, retryAfter: Option[Duration]) extends LLMError
case class AuthenticationError(message: String) extends LLMError
case class NetworkError(message: String, cause: Option[Throwable]) extends LLMError

// Pattern matching for error handling
result match
  case Right(completion) => processCompletion(completion)
  case Left(RateLimitError(_, Some(retryAfter))) => 
    IO.sleep(retryAfter) *> retry
  case Left(error) if error.isRecoverable => retry
  case Left(error) => IO.raiseError(new RuntimeException(error.formatted))
```

### structured-llm Error Pattern

**structured-llm uses `StructuredLLMError` sealed hierarchy**:

```scala
import org.adk4s.structured.core.{StructuredLLMError, ParseError, ParseResult}

sealed trait StructuredLLMError extends Throwable:
  def message: String

case class LLMCallFailed(underlying: LLMError, prompt: Prompt) extends StructuredLLMError
case class ParseFailed(errors: List[ParseError], rawResponse: String) extends StructuredLLMError
case class EmptyResponse(prompt: Prompt) extends StructuredLLMError

// ParseResult for parsing outcomes
enum ParseResult[+A]:
  case Success(value: A, warnings: List[String])
  case Failure(errors: List[ParseError])
```

### Workflows4s Error Pattern

**Workflows4s uses typed errors in WIO**:

```scala
import workflows4s.wio.WIO

// Error type is explicit in WIO signature
val step: WIO[String, MyError, Int, Ctx.type] = 
  WIO.runIO { input =>
    if input.isEmpty then IO.raiseError(MyError.EmptyInput)
    else IO.pure(input.length)
  }

// Error handling with handleErrorWith
val withRecovery: WIO[String, Nothing, Int, Ctx.type] =
  step.handleErrorWith {
    case MyError.EmptyInput => WIO.pure(0)
    case other => WIO.raiseError(other)
  }
```

**ADK4S Recommendation**: 
- Use `Either[LLMError, A]` for LLM operations (LLM4S pattern)
- Use `IO` error channel for runtime errors
- Use `Either` or `Validated` for construction/validation errors
- Use `ValidatedNec` when accumulating multiple errors

---

## 6. Context Propagation

### Go Context

```go
// Context passed through all calls
func (r *Runnable[I, O]) Invoke(ctx context.Context, input I, opts ...Option) (O, error)

// Context carries:
// - Cancellation
// - Deadlines
// - Values (state, callbacks, etc.)

// Adding values to context
ctx = context.WithValue(ctx, stateKey{}, state)
ctx = callbacks.ReuseHandlers(ctx, runInfo)
```

### Scala 3 Translation

```scala
// Option 1: Explicit context parameter
def invoke(input: I)(using ctx: GraphContext): IO[O]

// Option 2: Reader monad
type GraphIO[A] = ReaderT[IO, GraphContext, A]
def invoke(input: I): GraphIO[O]

// Option 3: IOLocal for fiber-local state (recommended for Cats Effect)
def invoke(input: I)(using 
  stateLocal: IOLocal[GraphState],
  callbacksLocal: IOLocal[List[CallbackHandler[IO]]]
): IO[O]

// Context case class
case class GraphContext(
  state: Option[Ref[IO, ?]],
  callbacks: List[CallbackHandler[IO]],
  runInfo: RunInfo
)
```

**ADK4S Recommendation**: Use `IOLocal` for fiber-local context in Cats Effect. It provides similar semantics to Go's context values.

---

## 7. Streaming API Design

### StreamReader API

```go
// Creation
sr, sw := schema.Pipe[T](capacity)
sr := schema.StreamReaderFromArray(items)

// Consumption
for {
    chunk, err := sr.Recv()
    if err == io.EOF {
        break
    }
    if err != nil {
        return err
    }
    process(chunk)
}
defer sr.Close()

// Transformation
converted := schema.StreamReaderWithConvert(sr, func(t T) (U, error) { ... })

// Copying
copies := sr.Copy(n)  // Creates n independent readers

// Merging
merged := schema.MergeStreamReaders(readers)
```

### Scala 3 Translation

```scala
// Use fs2.Stream directly - it has all these operations

// Creation
val stream: fs2.Stream[IO, T] = fs2.Stream.emits(items)
val stream: fs2.Stream[IO, T] = fs2.Stream.eval(io)

// Consumption
stream.evalMap(process).compile.drain

// Transformation
stream.map(transform)
stream.evalMap(transformIO)

// Copying (broadcast)
stream.broadcastThrough(pipe1, pipe2)

// Merging
fs2.Stream(stream1, stream2).parJoinUnbounded
```

**ADK4S Recommendation**: Use fs2.Stream directly. It provides a superset of Eino's StreamReader functionality with better composability.
