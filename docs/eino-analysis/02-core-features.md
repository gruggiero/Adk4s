# Eino Framework Analysis - Core Features

## 1. Component Abstractions

Eino encapsulates common LLM building blocks into **component abstractions**, each with:
- Defined input/output types
- Defined option types
- Streaming paradigms that make sense for the component

### Component Types

| Component | Input | Output | Purpose |
|-----------|-------|--------|---------|
| `BaseChatModel` | `[]*Message` | `*Message` | LLM chat completion |
| `ChatTemplate` | `map[string]any` | `[]*Message` | Prompt templating |
| `Retriever` | `string` | `[]*Document` | Document retrieval |
| `Embedder` | `[]string` | `[][]float64` | Text embeddings |
| `Indexer` | `[]*Document` | `[]string` | Document indexing |
| `Loader` | `LoaderSource` | `[]*Document` | Document loading |
| `Transformer` | `[]*Document` | `[]*Document` | Document transformation |
| `BaseTool` | - | `*ToolInfo` | Tool metadata |
| `InvokableTool` | `string` (JSON) | `string` | Tool execution |
| `StreamableTool` | `string` (JSON) | `*StreamReader[string]` | Streaming tool |

### Scala 3 Translation

```scala
// Go interface
type BaseChatModel interface {
    Generate(ctx context.Context, input []*Message, opts ...Option) (*Message, error)
    Stream(ctx context.Context, input []*Message, opts ...Option) (*StreamReader[*Message], error)
}

// Scala 3 trait with Cats Effect
trait ChatModel[F[_]]:
  def generate(input: List[Message], config: ChatModelConfig): F[Message]
  def stream(input: List[Message], config: ChatModelConfig): fs2.Stream[F, Message]

// Or as a typeclass
trait ChatModel[F[_], M]:
  extension (model: M)
    def generate(input: List[Message], config: ChatModelConfig): F[Message]
    def stream(input: List[Message], config: ChatModelConfig): fs2.Stream[F, Message]
```

### LLM4S Implementation

**LLM4S already provides this abstraction via `LLMClient`**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, Completion, CompletionOptions, Message, UserMessage, SystemMessage, AssistantMessage}

// LLM4S LLMClient trait (already implemented)
trait LLMClient:
  def complete(conversation: Conversation, options: CompletionOptions): Either[LLMError, Completion]
  def completeStreamed(conversation: Conversation, options: CompletionOptions): Either[LLMError, Iterator[StreamedChunk]]

// Usage with LLM4S
val client: LLMClient = LLMClient.create()  // Auto-detects provider from env
val conversation: Conversation = Conversation(Vector(
  SystemMessage("You are a helpful assistant"),
  UserMessage("What's the weather in Beijing?")
))
val result: Either[LLMError, Completion] = client.complete(conversation, CompletionOptions())

// Streaming with LLM4S
val streamResult: Either[LLMError, Iterator[StreamedChunk]] = 
  client.completeStreamed(conversation, CompletionOptions())
```

### structured-llm Integration

**For type-safe structured outputs, use `StructuredLLM`**:

```scala
import org.adk4s.structured.core.{StructuredLLM, Prompt, Schema}
import cats.effect.IO

// Define output type with Smithy schema
case class WeatherResponse(location: String, temperature: Int, conditions: String)
given Schema[WeatherResponse] = Schema.instance[WeatherResponse](
  """structure WeatherResponse {
    @required location: String
    @required temperature: Integer
    @required conditions: String
  }"""
)

// Create StructuredLLM from LLMClient
val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)

// Type-safe completion
val result: IO[WeatherResponse] = structured.complete[WeatherResponse](
  Prompt.simple("You are a weather API", "What's the weather in Beijing?")
)
```

**ADK4S Recommendation**: Use `LLMClient` from LLM4S for raw LLM interaction. Wrap with `StructuredLLM` from structured-llm when type-safe outputs are needed.

---

## 2. Orchestration APIs

Eino provides three orchestration APIs:

### 2.1 Chain

Simple linear directed graph - components execute in sequence.

```go
chain, _ := NewChain[map[string]any, *Message]().
    AppendChatTemplate(prompt).
    AppendChatModel(model).
    Compile(ctx)

chain.Invoke(ctx, map[string]any{"query": "hello"})
```

**Characteristics**:
- Builder pattern with fluent API
- Type parameters `[I, O]` for input/output
- Supports parallel nodes and branches
- Compiles to `Runnable[I, O]`

### 2.2 Graph

Cyclic or acyclic directed graph - powerful and flexible.

```go
graph := NewGraph[map[string]any, *Message]()
graph.AddChatTemplateNode("template", chatTpl)
graph.AddChatModelNode("model", chatModel)
graph.AddToolsNode("tools", toolsNode)

graph.AddEdge(START, "template")
graph.AddEdge("template", "model")
graph.AddBranch("model", branch)

compiledGraph, _ := graph.Compile(ctx)
```

**Characteristics**:
- Explicit node and edge management
- Supports cycles (Pregel mode) or DAG mode
- Branch conditions for dynamic routing
- State management across nodes

### 2.3 Workflow

Acyclic graph with field-level data mapping.

```go
wf := NewWorkflow[[]*Message, *Message]()
wf.AddChatModelNode("model", m).AddInput(START)
wf.AddLambdaNode("lambda1", lambda).
    AddInput("model", MapFields("Content", "Input"))
wf.End().AddInput("lambda3")
```

**Characteristics**:
- Fine-grained field mapping between nodes
- No cycles allowed
- Explicit dependency declaration
- Static value injection

### Scala 3 Translation

```scala
// Chain with builder pattern
class Chain[I, O]:
  def appendChatModel(model: ChatModel[IO]): Chain[I, O]
  def appendLambda[A, B](f: A => IO[B]): Chain[I, B]
  def compile: IO[Runnable[I, O]]

// Graph with type-safe edges
class Graph[I, O]:
  def addNode[A, B](key: String, node: Node[A, B]): Graph[I, O]
  def addEdge(from: String, to: String): Either[GraphError, Graph[I, O]]
  def compile: IO[Runnable[I, O]]

// Workflow with field mapping
class Workflow[I, O]:
  def addNode[A, B](key: String, node: Node[A, B]): WorkflowNode
  
class WorkflowNode:
  def addInput(from: String, mappings: FieldMapping*): WorkflowNode
```

### Workflows4s Implementation

**Workflows4s provides a more powerful orchestration model via `WIO`**:

```scala
import workflows4s.wio.WIO
import workflows4s.wio.builders.AllBuilders
import cats.effect.IO

// Define workflow context
object MyWorkflowContext extends workflows4s.wio.WorkflowContext:
  override type State = MyState
  override type Event = MyEvent
  
case class MyState(messages: List[String], result: Option[String])
sealed trait MyEvent
case class MessageAdded(msg: String) extends MyEvent
case class ResultSet(result: String) extends MyEvent

// Chain equivalent: Sequential composition with WIO
val chainWorkflow: WIO[String, Nothing, String, MyWorkflowContext.type] =
  WIO.pure[String, MyWorkflowContext.type](identity)
    .flatMap { input =>
      WIO.runIO[String, MyWorkflowContext.type] { _ =>
        IO.pure(input.toUpperCase)  // Step 1: Transform
      }
    }
    .flatMap { transformed =>
      WIO.runIO[String, MyWorkflowContext.type] { _ =>
        IO.pure(s"Processed: $transformed")  // Step 2: Format
      }
    }

// Graph equivalent: Branching with WIO.fork
val graphWorkflow: WIO[String, Nothing, String, MyWorkflowContext.type] =
  WIO.fork[String, String, MyWorkflowContext.type](
    condition = input => input.startsWith("A"),
    ifTrue = WIO.pure(_ => "Branch A"),
    ifFalse = WIO.pure(_ => "Branch B")
  )

// Parallel execution with WIO.parallel
val parallelWorkflow: WIO[String, Nothing, (String, Int), MyWorkflowContext.type] =
  WIO.parallel(
    WIO.runIO[String, MyWorkflowContext.type](_ => IO.pure("result1")),
    WIO.runIO[Int, MyWorkflowContext.type](_ => IO.pure(42))
  )

// Loop for retry/cycle patterns
val loopWorkflow: WIO[Int, Nothing, Int, MyWorkflowContext.type] =
  WIO.loop[Int, Int, MyWorkflowContext.type](
    body = WIO.runIO { state => IO.pure(state.counter + 1) },
    onRestart = identity,
    condition = result => result < 10  // Continue while < 10
  )
```

### LLM4S Agent for ReAct-style Orchestration

**For agent-based orchestration (like Eino's ReAct), use LLM4S `Agent`**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.tools.{ToolFunction, ToolRegistry}

// Define tools
val weatherTool: ToolFunction = ToolBuilder("get_weather")
  .description("Get weather for a location")
  .parameter("location", StringSchema("City name"), required = true)
  .handler { params =>
    val location: String = params.getString("location").getOrElse("Unknown")
    Right(s"Weather in $location: Sunny, 25°C")
  }
  .build

val registry: ToolRegistry = ToolRegistry(List(weatherTool))

// Create agent (handles the ReAct loop internally)
val agent: Agent = Agent(
  client = LLMClient.create(),
  tools = registry,
  systemPrompt = Some("You are a helpful weather assistant")
)

// Run agent - automatically handles tool calls and loops
val result: Either[LLMError, AgentState] = agent.run("What's the weather in Beijing?")

// Or step-by-step execution for more control
val initialState: AgentState = agent.initialize("What's the weather in Beijing?")
val afterStep: Either[LLMError, AgentState] = agent.step(initialState)
```

**ADK4S Recommendation**: 
- Use **Workflows4s `WIO`** for complex, event-sourced workflows with checkpointing, timers, and signals
- Use **LLM4S `Agent`** for ReAct-style LLM agent loops with automatic tool execution
- Use immutable builders returning new instances
- Leverage Scala 3's union types for error handling

---

## 3. Stream Processing

Eino's streaming is a first-class feature with automatic handling:

### Stream Operations

| Operation | Description |
|-----------|-------------|
| **Concatenate** | Merge stream chunks for non-stream consumers |
| **Box** | Convert non-stream to stream when needed |
| **Merge** | Combine multiple streams into one |
| **Copy** | Fan-out stream to multiple consumers |

### Four Streaming Paradigms

```go
type Runnable[I, O any] interface {
    Invoke(ctx, input I) (O, error)                           // ping → pong
    Stream(ctx, input I) (*StreamReader[O], error)            // ping → stream
    Collect(ctx, input *StreamReader[I]) (O, error)           // stream → pong
    Transform(ctx, input *StreamReader[I]) (*StreamReader[O], error) // stream → stream
}
```

### StreamReader Implementation

```go
type StreamReader[T any] struct {
    typ readerType  // stream, array, multiStream, withConvert, child
    // ... variant-specific fields
}

func (sr *StreamReader[T]) Recv() (T, error)
func (sr *StreamReader[T]) Close()
func (sr *StreamReader[T]) Copy(n int) []*StreamReader[T]
```

### Scala 3 Translation

```scala
// Using fs2.Stream
trait Runnable[F[_], I, O]:
  def invoke(input: I): F[O]
  def stream(input: I): fs2.Stream[F, O]
  def collect(input: fs2.Stream[F, I]): F[O]
  def transform(input: fs2.Stream[F, I]): fs2.Stream[F, O]

// Automatic conversions via given instances
given [F[_]: Concurrent, I, O](using r: Runnable[F, I, O]): Conversion[I, fs2.Stream[F, O]] =
  input => r.stream(input)
```

### LLM4S Streaming Support

**LLM4S provides streaming via `completeStreamed`**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, CompletionOptions, StreamedChunk}

val client: LLMClient = LLMClient.create()

// Streaming completion returns Iterator[StreamedChunk]
val streamResult: Either[LLMError, Iterator[StreamedChunk]] = 
  client.completeStreamed(conversation, CompletionOptions())

// Convert to fs2.Stream for functional composition
import fs2.Stream
import cats.effect.IO

def toFs2Stream(iter: Iterator[StreamedChunk]): Stream[IO, StreamedChunk] =
  Stream.fromIterator[IO](iter, chunkSize = 1)

// Usage with fs2 operations
val processedStream: Stream[IO, String] = streamResult match
  case Right(iter) => 
    toFs2Stream(iter)
      .map(_.content)           // Extract content
      .filter(_.nonEmpty)       // Filter empty chunks
      .evalTap(chunk => IO.println(chunk))  // Side effect: print
  case Left(error) => 
    Stream.raiseError[IO](new RuntimeException(error.toString))
```

### fs2.Stream Operations (Native Equivalents)

```scala
import fs2.Stream
import cats.effect.IO

// Concatenate: Merge stream chunks
val concatenated: IO[String] = stream.compile.foldMonoid

// Box: Convert single value to stream
val boxed: Stream[IO, String] = Stream.emit("single value")

// Merge: Combine multiple streams
val merged: Stream[IO, String] = Stream(stream1, stream2).parJoinUnbounded

// Copy/Fan-out: Broadcast to multiple consumers
val (s1, s2): (Stream[IO, String], Stream[IO, String]) = 
  stream.broadcastThrough(pipe1, pipe2)
```

**ADK4S Recommendation**: Use fs2.Stream directly - it already provides all these operations natively. LLM4S streaming integrates seamlessly with fs2.

---

## 4. Callback/Aspect System

Cross-cutting concerns handled via callbacks:

### Callback Timings

| Timing | Description |
|--------|-------------|
| `OnStart` | Before component execution |
| `OnEnd` | After successful execution |
| `OnError` | On error |
| `OnStartWithStreamInput` | Before with stream input |
| `OnEndWithStreamOutput` | After with stream output |

### Handler Builder

```go
handler := NewHandlerBuilder().
    OnStartFn(func(ctx context.Context, info *RunInfo, input CallbackInput) context.Context {
        log.Infof("onStart: %v", info)
        return ctx
    }).
    OnEndFn(func(ctx context.Context, info *RunInfo, output CallbackOutput) context.Context {
        log.Infof("onEnd: %v", info)
        return ctx
    }).
    Build()

graph.Invoke(ctx, input, WithCallbacks(handler))
```

### Aspect Injection

- Graph automatically injects callbacks to components that don't handle them
- Components can opt-in to handle callbacks themselves
- Global handlers can be registered for all nodes

### Scala 3 Translation

```scala
// Callback handler as a typeclass
trait CallbackHandler[F[_]]:
  def onStart[I](info: RunInfo, input: I): F[Unit]
  def onEnd[O](info: RunInfo, output: O): F[Unit]
  def onError(info: RunInfo, error: Throwable): F[Unit]

// Using Cats Effect Resource for lifecycle
def withCallbacks[F[_]: Async, A](
  handler: CallbackHandler[F],
  info: RunInfo
)(fa: F[A]): F[A] =
  handler.onStart(info, ()) *> fa.guaranteeCase {
    case Outcome.Succeeded(a) => handler.onEnd(info, a)
    case Outcome.Errored(e) => handler.onError(info, e)
    case Outcome.Canceled() => Async[F].unit
  }
```

### LLM4S Tracing Support

**LLM4S provides built-in tracing for agent execution**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}

// Agent execution with tracing
val agent: Agent = Agent(client, tools, systemPrompt = Some("..."))

// Step-by-step execution with full visibility
def executeWithTracing(query: String): Either[LLMError, AgentState] =
  val initialState: AgentState = agent.initialize(query)
  
  @tailrec
  def loop(state: AgentState, stepCount: Int): Either[LLMError, AgentState] =
    // Log current state (callback equivalent)
    println(s"Step $stepCount: Status=${state.status}")
    println(s"  Messages: ${state.conversation.messages.size}")
    println(s"  Pending tool calls: ${state.pendingToolCalls.size}")
    
    state.status match
      case AgentStatus.Complete => Right(state)
      case AgentStatus.AwaitingToolResults => 
        agent.step(state) match
          case Right(newState) => loop(newState, stepCount + 1)
          case Left(error) => 
            println(s"  Error: $error")  // onError callback
            Left(error)
      case AgentStatus.Ready =>
        agent.step(state) match
          case Right(newState) => loop(newState, stepCount + 1)
          case Left(error) => Left(error)
  
  loop(initialState, 1)
```

### Workflows4s Hooks and Interceptors

**Workflows4s provides `WorkflowInstanceEngine` for cross-cutting concerns**:

```scala
import workflows4s.runtime.{WorkflowInstanceEngine, WorkflowInstance}

// Engine with hooks for logging, metrics, etc.
val engineWithLogging: WorkflowInstanceEngine[IO] = new WorkflowInstanceEngine[IO]:
  def onWorkflowStarted(instance: WorkflowInstance[IO]): IO[Unit] =
    IO.println(s"Workflow started: ${instance.id}")
  
  def onStepCompleted(instance: WorkflowInstance[IO], stepName: String): IO[Unit] =
    IO.println(s"Step completed: $stepName")
  
  def onWorkflowCompleted(instance: WorkflowInstance[IO]): IO[Unit] =
    IO.println(s"Workflow completed: ${instance.id}")
  
  def onError(instance: WorkflowInstance[IO], error: Throwable): IO[Unit] =
    IO.println(s"Workflow error: ${error.getMessage}")

// KnockerUpper for timer-based callbacks
import workflows4s.runtime.KnockerUpper

val knockerUpper: KnockerUpper[IO] = new KnockerUpper[IO]:
  def schedule(wakeupTime: Instant, callback: IO[Unit]): IO[Unit] =
    // Schedule callback at wakeupTime
    IO.sleep(Duration.between(Instant.now, wakeupTime).toScala) *> callback
```

**ADK4S Recommendation**: 
- Use **LLM4S step-by-step execution** for agent tracing and debugging
- Use **Workflows4s `WorkflowInstanceEngine`** for workflow-level hooks
- Use Cats Effect's `Resource` and `Bracket` for lifecycle management
- Consider using `Trace` from natchez for distributed tracing
- Implement as middleware pattern for composability

---

## 5. State Management

Graph-level state for sharing data across nodes:

### State Definition

```go
type state struct {
    Messages []*Message
    Counter  int
}

graph := NewGraph[[]*Message, *Message](
    WithGenLocalState(func(ctx context.Context) *state {
        return &state{Messages: make([]*Message, 0)}
    }),
)
```

### State Access

```go
// Pre-handler with state
preHandler := func(ctx context.Context, input []*Message, state *state) ([]*Message, error) {
    state.Messages = append(state.Messages, input...)
    return state.Messages, nil
}

graph.AddChatModelNode("model", chatModel, WithStatePreHandler(preHandler))

// Direct state access in lambda
err := compose.ProcessState[*state](ctx, func(ctx context.Context, s *state) error {
    s.Counter++
    return nil
})
```

### Concurrency Safety

- State access is mutex-protected
- `ProcessState` acquires lock for duration of handler
- Nested graphs can access parent state

### Scala 3 Translation

```scala
// Using Ref from Cats Effect
case class GraphState(messages: List[Message], counter: Int)

def createGraph[F[_]: Concurrent]: F[Graph[F, List[Message], Message]] =
  Ref.of[F, GraphState](GraphState(Nil, 0)).map { stateRef =>
    Graph[F, List[Message], Message](stateRef)
  }

// State access via Ref
def processState[F[_]: Concurrent, S, A](
  stateRef: Ref[F, S]
)(f: S => F[(S, A)]): F[A] =
  stateRef.modify(s => ???) // atomic update
```

### LLM4S AgentState for Conversation State

**LLM4S `AgentState` manages conversation state immutably**:

```scala
import org.llm4s.agent.{AgentState, AgentStatus}
import org.llm4s.llmconnect.model.{Conversation, ToolCall}

// AgentState is immutable - each step returns a new state
case class AgentState(
  conversation: Conversation,      // Message history
  pendingToolCalls: List[ToolCall], // Tool calls awaiting execution
  status: AgentStatus,             // Ready, AwaitingToolResults, Complete
  stepCount: Int                   // Number of steps taken
)

// State flows through agent execution
val state0: AgentState = agent.initialize("What's the weather?")
val state1: Either[LLMError, AgentState] = agent.step(state0)
val state2: Either[LLMError, AgentState] = state1.flatMap(agent.step)
// Each state is independent - no mutation
```

### Workflows4s Event-Sourced State

**Workflows4s provides event-sourced state management**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}

// Define state and events
case class AgentWorkflowState(
  messages: List[Message],
  toolResults: Map[String, String],
  stepCount: Int
)

sealed trait AgentEvent
case class MessageAdded(message: Message) extends AgentEvent
case class ToolResultReceived(callId: String, result: String) extends AgentEvent
case class StepCompleted(stepNumber: Int) extends AgentEvent

object AgentWorkflowContext extends WorkflowContext:
  override type State = AgentWorkflowState
  override type Event = AgentEvent

// State updates happen through events (event sourcing)
val addMessageStep: WIO[Message, Nothing, Unit, AgentWorkflowContext.type] =
  WIO.runIO[Unit, AgentWorkflowContext.type] { state =>
    // This would emit a MessageAdded event
    IO.pure(())
  }.named("add_message")

// State is reconstructed by replaying events
// This enables:
// - Checkpointing and resume
// - Audit trail
// - Time travel debugging
```

### Combining LLM4S and Workflows4s for Stateful Agents

```scala
// Use Workflows4s for durable, event-sourced agent orchestration
// Use LLM4S AgentState for in-memory agent execution within workflow steps

val agentWorkflow: WIO[String, Nothing, String, AgentWorkflowContext.type] =
  WIO.runIO[String, AgentWorkflowContext.type] { workflowState =>
    // Run LLM4S agent within a workflow step
    val agent: Agent = Agent(client, tools)
    val result: Either[LLMError, AgentState] = agent.run(workflowState.query)
    
    result match
      case Right(finalState) => IO.pure(finalState.conversation.lastAssistantMessage)
      case Left(error) => IO.raiseError(new RuntimeException(error.toString))
  }
```

**ADK4S Recommendation**:
- Use **LLM4S `AgentState`** for in-memory, immutable agent state during execution
- Use **Workflows4s event sourcing** for durable, persistent workflow state
- Use `Ref[F, S]` for thread-safe mutable state when needed
- Consider `StateT` for pure state threading
- Leverage Cats Effect's `Resource` for state lifecycle
