# Eino Framework Analysis - Extension Patterns

## 1. Component Extension via Interfaces

### Interface Composition

Eino uses interface composition to extend capabilities:

```go
// Base interface
type BaseChatModel interface {
    Generate(ctx context.Context, input []*Message, opts ...Option) (*Message, error)
    Stream(ctx context.Context, input []*Message, opts ...Option) (*StreamReader[*Message], error)
}

// Extended interface (deprecated pattern)
type ChatModel interface {
    BaseChatModel
    BindTools(tools []*ToolInfo) error  // Mutable - not recommended
}

// Better extension pattern
type ToolCallingChatModel interface {
    BaseChatModel
    WithTools(tools []*ToolInfo) (ToolCallingChatModel, error)  // Immutable
}
```

### Tool Interface Hierarchy

```go
// Base - just metadata
type BaseTool interface {
    Info(ctx context.Context) (*ToolInfo, error)
}

// Invokable extension
type InvokableTool interface {
    BaseTool
    InvokableRun(ctx context.Context, argumentsInJSON string, opts ...Option) (string, error)
}

// Streamable extension
type StreamableTool interface {
    BaseTool
    StreamableRun(ctx context.Context, argumentsInJSON string, opts ...Option) (*StreamReader[string], error)
}
```

### Scala 3 Translation

```scala
// Base trait
trait ChatModel[F[_]]:
  def generate(messages: List[Message], config: ChatModelConfig): F[Message]
  def stream(messages: List[Message], config: ChatModelConfig): fs2.Stream[F, Message]

// Extension via trait inheritance
trait ToolCallingChatModel[F[_]] extends ChatModel[F]:
  def withTools(tools: List[ToolInfo]): F[ToolCallingChatModel[F]]

// Tool hierarchy
trait Tool[F[_]]:
  def info: F[ToolInfo]

trait InvokableTool[F[_]] extends Tool[F]:
  def run(arguments: String): F[String]

trait StreamableTool[F[_]] extends Tool[F]:
  def runStream(arguments: String): fs2.Stream[F, String]

// Combined capability
trait FullTool[F[_]] extends InvokableTool[F] with StreamableTool[F]
```

**ADK4S Recommendation**: Use trait inheritance for capability extension. Prefer immutable `withX` methods over mutable `bindX` methods.

### LLM4S Extension Pattern

**LLM4S uses composition rather than inheritance**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.tools.{ToolFunction, ToolRegistry}
import org.llm4s.agent.Agent

// LLMClient is the base - no tool support built-in
val client: LLMClient = LLMClient.create()

// Tool support is added via CompletionOptions
val optionsWithTools: CompletionOptions = CompletionOptions(
  tools = List(weatherTool, calculatorTool)
)

// Or use Agent for automatic tool execution
val agent: Agent = Agent(
  client = client,
  tools = ToolRegistry(List(weatherTool)),
  systemPrompt = Some("You are helpful")
)

// Extend with structured output via structured-llm
import org.adk4s.structured.core.StructuredLLM

val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)
// Now has type-safe output capabilities
```

### structured-llm Extension Pattern

**structured-llm wraps LLMClient to add capabilities**:

```scala
import org.adk4s.structured.core.{StructuredLLM, Schema, Prompt}

// Base LLMClient
val client: LLMClient = LLMClient.create()

// Wrap with StructuredLLM for type-safe outputs
val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)

// Now you can use type-safe completions
case class WeatherResponse(location: String, temperature: Int)
given Schema[WeatherResponse] = Schema.derived[WeatherResponse]

val result: IO[WeatherResponse] = structured.complete[WeatherResponse](prompt)

// Create reusable functions
val weatherExtractor: String => IO[WeatherResponse] = 
  structured.extractor[WeatherResponse]("Extract weather information")
```

---

## 2. Lambda Functions as Extension Points

### Lambda Types

```go
// Four lambda paradigms
type Invoke[I, O, TOption any] func(ctx context.Context, input I, opts ...TOption) (O, error)
type Stream[I, O, TOption any] func(ctx context.Context, input I, opts ...TOption) (*StreamReader[O], error)
type Collect[I, O, TOption any] func(ctx context.Context, input *StreamReader[I], opts ...TOption) (O, error)
type Transform[I, O, TOption any] func(ctx context.Context, input *StreamReader[I], opts ...TOption) (*StreamReader[O], error)
```

### Lambda Creation

```go
// Simple lambdas (no options)
lambda := InvokableLambda(func(ctx context.Context, input string) (string, error) {
    return strings.ToUpper(input), nil
})

// With options
lambda := InvokableLambdaWithOption(func(ctx context.Context, input string, opts ...MyOption) (string, error) {
    // Use opts
    return input, nil
})

// Combined lambda
lambda, _ := AnyLambda(invokeFn, streamFn, collectFn, transformFn)
```

### Automatic Paradigm Conversion

```go
// If only Invoke is provided, others are derived:
// - Stream: invoke then wrap result in single-element stream
// - Collect: concat stream then invoke
// - Transform: concat stream, invoke, wrap in stream

func newRunnablePacker[I, O, TOption any](...) *runnablePacker[I, O, TOption] {
    if i != nil {
        r.i = i
    } else if s != nil {
        r.i = invokeByStream(s)  // Derived
    } else if c != nil {
        r.i = invokeByCollect(c)
    } else {
        r.i = invokeByTransform(t)
    }
    // Similar for other paradigms...
}
```

### Scala 3 Translation

```scala
// Lambda as sealed trait
sealed trait Lambda[I, O]:
  def toRunnable: Runnable[I, O]

case class InvokableLambda[I, O](f: I => IO[O]) extends Lambda[I, O]:
  def toRunnable: Runnable[I, O] = new Runnable[I, O]:
    def invoke(input: I): IO[O] = f(input)
    def stream(input: I): fs2.Stream[IO, O] = fs2.Stream.eval(f(input))
    def collect(input: fs2.Stream[IO, I]): IO[O] = 
      input.compile.lastOrError.flatMap(f)
    def transform(input: fs2.Stream[IO, I]): fs2.Stream[IO, O] =
      input.evalMap(f)

// Smart constructors
object Lambda:
  def apply[I, O](f: I => IO[O]): Lambda[I, O] = InvokableLambda(f)
  def stream[I, O](f: I => fs2.Stream[IO, O]): Lambda[I, O] = StreamableLambda(f)
  
  // Automatic derivation via given instances
  given [I, O]: Conversion[I => IO[O], Lambda[I, O]] = f => InvokableLambda(f)
```

**ADK4S Recommendation**: Model lambdas as ADTs. Provide automatic derivation of missing paradigms.

### Workflows4s Lambda Equivalents

**Workflows4s WIO provides similar lambda patterns**:

```scala
import workflows4s.wio.WIO

// Invoke equivalent: WIO.runIO
val invokeStep: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO { input => IO.pure(input.toUpperCase) }

// Stream equivalent: WIO with fs2.Stream
val streamStep: WIO[String, Nothing, fs2.Stream[IO, Char], Ctx.type] =
  WIO.runIO { input => IO.pure(fs2.Stream.emits(input.toList)) }

// Transform equivalent: composition
val transformStep: WIO[fs2.Stream[IO, String], Nothing, fs2.Stream[IO, String], Ctx.type] =
  WIO.runIO { stream => IO.pure(stream.map(_.toUpperCase)) }

// Automatic composition via flatMap
val composed: WIO[String, Nothing, String, Ctx.type] =
  invokeStep.flatMap { upper =>
    WIO.runIO(_ => IO.pure(s"Result: $upper"))
  }
```

---

## 3. State Handlers as Extension Points

### Pre/Post Handlers

```go
// State pre-handler - runs before node, can modify input
type StatePreHandler[I, S any] func(ctx context.Context, in I, state S) (I, error)

// State post-handler - runs after node, can modify output
type StatePostHandler[O, S any] func(ctx context.Context, out O, state S) (O, error)

// Stream variants
type StreamStatePreHandler[I, S any] func(ctx context.Context, in *StreamReader[I], state S) (*StreamReader[I], error)
type StreamStatePostHandler[O, S any] func(ctx context.Context, out *StreamReader[O], state S) (*StreamReader[O], error)
```

### Usage

```go
preHandler := func(ctx context.Context, input []*Message, state *MyState) ([]*Message, error) {
    state.Messages = append(state.Messages, input...)
    return state.Messages, nil  // Return accumulated messages
}

graph.AddChatModelNode("model", chatModel, WithStatePreHandler(preHandler))
```

### Scala 3 Translation

```scala
// Pre/post handlers as type aliases
type PreHandler[F[_], I, S] = (I, Ref[F, S]) => F[I]
type PostHandler[F[_], O, S] = (O, Ref[F, S]) => F[O]

// Stream variants
type StreamPreHandler[F[_], I, S] = (fs2.Stream[F, I], Ref[F, S]) => fs2.Stream[F, I]
type StreamPostHandler[F[_], O, S] = (fs2.Stream[F, O], Ref[F, S]) => fs2.Stream[F, O]

// Node configuration
case class NodeConfig[I, O, S](
  preHandler: Option[PreHandler[IO, I, S]] = None,
  postHandler: Option[PostHandler[IO, O, S]] = None
)

// Usage
val preHandler: PreHandler[IO, List[Message], MyState] = (input, stateRef) =>
  stateRef.modify { state =>
    val newState = state.copy(messages = state.messages ++ input)
    (newState, newState.messages)
  }

graph.addChatModelNode("model", chatModel, NodeConfig(preHandler = Some(preHandler)))
```

**ADK4S Recommendation**: Use `Ref[F, S]` for state access. Keep handlers pure functions.

### Workflows4s State Handlers

**Workflows4s uses event sourcing for state management**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}

// Define state and events
case class AgentState(messages: List[Message], stepCount: Int)
sealed trait AgentEvent
case class MessageAdded(msg: Message) extends AgentEvent
case class StepCompleted(step: Int) extends AgentEvent

object AgentContext extends WorkflowContext:
  override type State = AgentState
  override type Event = AgentEvent

// Pre-handler equivalent: transform input based on state
val preProcess: WIO[Message, Nothing, List[Message], AgentContext.type] =
  WIO.runIO { (msg, state) =>
    // Access current state, return accumulated messages
    IO.pure(state.messages :+ msg)
  }

// Post-handler equivalent: update state after processing
val postProcess: WIO[Message, Nothing, Message, AgentContext.type] =
  WIO.runIO { (result, state) =>
    // Emit event to update state
    IO.pure(result)
  }.emitEvent(result => MessageAdded(result))
```

### LLM4S Agent State

**LLM4S AgentState is immutable - state flows through execution**:

```scala
import org.llm4s.agent.{Agent, AgentState}

// AgentState is immutable - each step returns new state
val state0: AgentState = agent.initialize(query)
val state1: Either[LLMError, AgentState] = agent.step(state0)

// Pre-processing: modify state before step
def withPreProcess(state: AgentState): AgentState =
  state.copy(conversation = state.conversation.addMessage(SystemMessage("Additional context")))

// Post-processing: modify state after step
def withPostProcess(state: AgentState): AgentState =
  state.copy(stepCount = state.stepCount + 1)

// Compose
val processed: Either[LLMError, AgentState] = 
  agent.step(withPreProcess(state0)).map(withPostProcess)
```

---

## 4. Middleware Pattern

### Tool Middleware

```go
type InvokableToolMiddleware func(InvokableToolEndpoint) InvokableToolEndpoint
type InvokableToolEndpoint func(ctx context.Context, input *ToolInput) (*ToolOutput, error)

// Example: Logging middleware
func loggingMiddleware(next InvokableToolEndpoint) InvokableToolEndpoint {
    return func(ctx context.Context, input *ToolInput) (*ToolOutput, error) {
        log.Printf("Tool call: %s(%s)", input.Name, input.Arguments)
        result, err := next(ctx, input)
        if err != nil {
            log.Printf("Tool error: %v", err)
        } else {
            log.Printf("Tool result: %s", result.Result)
        }
        return result, err
    }
}

// Middleware composition (applied in reverse order)
for i := len(middlewares) - 1; i >= 0; i-- {
    endpoint = middlewares[i](endpoint)
}
```

### Scala 3 Translation

```scala
// Using Kleisli for middleware
import cats.data.Kleisli

type ToolEndpoint[F[_]] = Kleisli[F, ToolInput, ToolOutput]
type ToolMiddleware[F[_]] = ToolEndpoint[F] => ToolEndpoint[F]

// Logging middleware
def loggingMiddleware[F[_]: Console: Monad]: ToolMiddleware[F] = endpoint =>
  Kleisli { input =>
    for
      _ <- Console[F].println(s"Tool call: ${input.name}(${input.arguments})")
      result <- endpoint.run(input).attempt
      _ <- result match
        case Left(err) => Console[F].println(s"Tool error: $err")
        case Right(out) => Console[F].println(s"Tool result: ${out.result}")
      output <- result.liftTo[F]
    yield output
  }

// Composition
def composeMiddlewares[F[_]](
  middlewares: List[ToolMiddleware[F]],
  endpoint: ToolEndpoint[F]
): ToolEndpoint[F] =
  middlewares.foldRight(endpoint)((m, e) => m(e))
```

**ADK4S Recommendation**: Use Kleisli for middleware composition. It provides excellent composability with Cats.

### LLM4S Tool Handler Middleware

**LLM4S tool handlers can be wrapped with middleware**:

```scala
import org.llm4s.tools.{ToolFunction, ToolBuilder}

// Define middleware type
type ToolHandler = Map[String, Any] => Either[String, String]
type ToolMiddleware = ToolHandler => ToolHandler

// Logging middleware
val loggingMiddleware: ToolMiddleware = handler => params =>
  println(s"Tool called with: $params")
  val result: Either[String, String] = handler(params)
  println(s"Tool result: $result")
  result

// Validation middleware
val validationMiddleware: ToolMiddleware = handler => params =>
  if params.isEmpty then Left("No parameters provided")
  else handler(params)

// Compose and apply
val composedHandler: ToolHandler = 
  (loggingMiddleware compose validationMiddleware)(baseHandler)

val tool: ToolFunction = ToolBuilder("my_tool")
  .handler(composedHandler)
  .build
```

### Workflows4s Interceptor Pattern

**Workflows4s provides interceptor hooks via WorkflowInstanceEngine**:

```scala
import workflows4s.runtime.WorkflowInstanceEngine

// Define interceptor
trait WorkflowInterceptor[F[_]]:
  def beforeStep[A](stepName: String, input: A): F[Unit]
  def afterStep[A](stepName: String, output: A): F[Unit]
  def onError(stepName: String, error: Throwable): F[Unit]

// Wrap engine with interceptor
def withInterceptor[F[_]: Monad](
  engine: WorkflowInstanceEngine[F],
  interceptor: WorkflowInterceptor[F]
): WorkflowInstanceEngine[F] = ???
```

---

## 5. Callback Handler Extension

### Handler Builder

```go
type HandlerBuilder struct {
    onStart              func(ctx, *RunInfo, CallbackInput) context.Context
    onEnd                func(ctx, *RunInfo, CallbackOutput) context.Context
    onError              func(ctx, *RunInfo, error) context.Context
    onStartWithStream    func(ctx, *RunInfo, *StreamReader[CallbackInput]) context.Context
    onEndWithStream      func(ctx, *RunInfo, *StreamReader[CallbackOutput]) context.Context
}

func NewHandlerBuilder() *HandlerBuilder
func (b *HandlerBuilder) OnStartFn(fn func(...)) *HandlerBuilder
func (b *HandlerBuilder) OnEndFn(fn func(...)) *HandlerBuilder
func (b *HandlerBuilder) Build() Handler
```

### Global vs Local Handlers

```go
// Global handlers - apply to all nodes
callbacks.AppendGlobalHandlers(tracingHandler, metricsHandler)

// Per-invocation handlers
graph.Invoke(ctx, input, WithCallbacks(customHandler))

// Node-specific handlers
graph.Invoke(ctx, input, WithCallbacks(handler).DesignateNode("model"))
```

### Scala 3 Translation

```scala
// Handler builder
case class CallbackHandlerBuilder[F[_]: Applicative](
  onStart: Option[(RunInfo, Any) => F[Unit]] = None,
  onEnd: Option[(RunInfo, Any) => F[Unit]] = None,
  onError: Option[(RunInfo, Throwable) => F[Unit]] = None
):
  def withOnStart(f: (RunInfo, Any) => F[Unit]): CallbackHandlerBuilder[F] =
    copy(onStart = Some(f))
  def withOnEnd(f: (RunInfo, Any) => F[Unit]): CallbackHandlerBuilder[F] =
    copy(onEnd = Some(f))
  def withOnError(f: (RunInfo, Throwable) => F[Unit]): CallbackHandlerBuilder[F] =
    copy(onError = Some(f))
  def build: CallbackHandler[F] = new CallbackHandler[F]:
    override def onStart[I](info: RunInfo, input: I): F[Unit] =
      onStart.fold(Applicative[F].unit)(_(info, input))
    // ... etc

// Global handlers via IOLocal or implicit
object GlobalHandlers:
  private val handlers: IOLocal[List[CallbackHandler[IO]]] = ???
  
  def append(handler: CallbackHandler[IO]): IO[Unit] =
    handlers.update(_ :+ handler)
```

**ADK4S Recommendation**: Use builder pattern for handlers. Consider IOLocal for global handler registration.

---

## 6. Graph Node Extension

### Custom Node Types

```go
// All nodes ultimately become graphNode
type graphNode struct {
    cr           *composableRunnable  // The actual executor
    nodeInfo     *nodeInfo            // Metadata
    executorMeta *executorMeta        // Component info
    instance     any                  // Original component
    opts         []GraphAddNodeOpt    // Options
    g            AnyGraph             // Nested graph (if any)
}

// Adding custom components
func (g *graph) AddLambdaNode(key string, node *Lambda, opts ...GraphAddNodeOpt) error {
    gNode, options := toLambdaNode(node, opts...)
    return g.addNode(key, gNode, options)
}

// Conversion to graphNode
func toLambdaNode(node *Lambda, opts ...GraphAddNodeOpt) (*graphNode, *graphAddNodeOpts) {
    options := getGraphAddNodeOpts(opts...)
    return &graphNode{
        cr:           node.executor,
        nodeInfo:     &nodeInfo{...},
        executorMeta: node.executor.meta,
        instance:     node,
        opts:         opts,
    }, options
}
```

### Scala 3 Translation

```scala
// Node as a sealed trait
sealed trait Node[I, O]:
  def inputType: TypeTag[I]
  def outputType: TypeTag[O]
  def toRunnable: Runnable[I, O]

// Built-in node types
case class ChatModelNode[F[_]](model: ChatModel[F]) extends Node[List[Message], Message]
case class LambdaNode[I, O](lambda: Lambda[I, O]) extends Node[I, O]
case class GraphNode[I, O](graph: Graph[I, O]) extends Node[I, O]
case class ToolsNode(config: ToolsNodeConfig) extends Node[Message, List[Message]]

// Extension via new case classes
case class CustomNode[I, O](
  process: I => IO[O],
  inputType: TypeTag[I],
  outputType: TypeTag[O]
) extends Node[I, O]:
  def toRunnable: Runnable[I, O] = Lambda(process).toRunnable
```

**ADK4S Recommendation**: Model nodes as a sealed trait hierarchy. Allow extension via custom node types.

---

## 7. Field Mapping Extension

### Field Mapping in Workflow

```go
// Map fields between nodes
type FieldMapping struct {
    from        FieldPath
    to          FieldPath
    fromNodeKey string
}

// Usage
wf.AddLambdaNode("lambda1", lambda).
    AddInput("model", MapFields("Content", "Input"))

// MapFields creates a FieldMapping
func MapFields(from, to string) *FieldMapping {
    return &FieldMapping{
        from: splitFieldPath(from),
        to:   splitFieldPath(to),
    }
}
```

### Scala 3 Translation

```scala
// Field path as opaque type
opaque type FieldPath = List[String]

object FieldPath:
  def apply(path: String): FieldPath = path.split('.').toList
  extension (fp: FieldPath)
    def segments: List[String] = fp

// Field mapping
case class FieldMapping(
  from: FieldPath,
  to: FieldPath,
  fromNodeKey: Option[String] = None
)

object FieldMapping:
  def apply(from: String, to: String): FieldMapping =
    FieldMapping(FieldPath(from), FieldPath(to))

// Usage
workflow.addLambdaNode("lambda1", lambda)
  .addInput("model", FieldMapping("content", "input"))
```

**ADK4S Recommendation**: Use opaque types for FieldPath. Consider using Smithy for type-safe field mapping definitions.
