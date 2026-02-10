# Eino Framework Analysis - Architectural Patterns

## 1. Builder Pattern

Eino uses builders extensively for constructing complex objects.

### Chain Builder

```go
chain := NewChain[map[string]any, *Message]().
    AppendChatTemplate(prompt).
    AppendChatModel(model).
    AppendLambda(lambda).
    Compile(ctx)
```

**Characteristics**:
- Fluent API with method chaining
- Type parameters preserved through chain
- Errors accumulated, reported at Compile()
- Immutable-ish (internal state mutates, but API feels immutable)

### Handler Builder

```go
handler := NewHandlerBuilder().
    OnStartFn(startFn).
    OnEndFn(endFn).
    OnErrorFn(errorFn).
    Build()
```

### Scala 3 Translation

```scala
// Immutable builder with phantom types for compile-time validation
sealed trait ChainState
sealed trait Empty extends ChainState
sealed trait HasNodes extends ChainState

class ChainBuilder[I, O, S <: ChainState] private (nodes: List[Node[?, ?]]):
  def appendChatModel(model: ChatModel[IO])(using S =:= Empty | S =:= HasNodes): 
    ChainBuilder[I, Message, HasNodes] = ???
    
  def compile(using S =:= HasNodes): IO[Runnable[I, O]] = ???

object ChainBuilder:
  def apply[I, O]: ChainBuilder[I, O, Empty] = new ChainBuilder(Nil)
```

### Workflows4s WIO Builder Pattern

**Workflows4s uses functional composition instead of mutable builders**:

```scala
import workflows4s.wio.WIO

// WIO is immutable - each operation returns a new WIO
val step1: WIO[String, Nothing, String, Ctx.type] = 
  WIO.runIO(_ => IO.pure("step1"))

val step2: WIO[String, Nothing, String, Ctx.type] = 
  WIO.runIO(_ => IO.pure("step2"))

// Composition creates new WIO instances
val chain: WIO[String, Nothing, String, Ctx.type] = 
  step1.flatMap(_ => step2)

// Or using >>> operator for cleaner syntax
val chainAlt: WIO[String, Nothing, String, Ctx.type] = 
  step1 >>> step2
```

### LLM4S ToolBuilder Pattern

**LLM4S uses immutable builder for tools**:

```scala
import org.llm4s.tools.{ToolBuilder, ToolFunction, StringSchema}

// Immutable builder - each method returns new builder
val tool: ToolFunction = ToolBuilder("get_weather")
  .description("Get weather for a location")           // Returns new builder
  .parameter("location", StringSchema("City"), true)   // Returns new builder
  .parameter("units", StringSchema("celsius/fahrenheit"), false)
  .handler { params =>                                  // Returns ToolFunction
    val location: String = params.getString("location").getOrElse("Unknown")
    Right(s"Weather in $location: Sunny")
  }
  .build
```

**ADK4S Recommendation**: Use immutable builders. Workflows4s and LLM4S already provide this pattern - compose with them rather than building new builders.

---

## 2. Functional Options Pattern

Go's idiomatic way to handle optional configuration.

### Option Functions

```go
// Definition
type GraphAddNodeOpt func(*graphAddNodeOpts)

func WithNodeName(name string) GraphAddNodeOpt {
    return func(o *graphAddNodeOpts) {
        o.name = name
    }
}

func WithStatePreHandler[I, S any](handler StatePreHandler[I, S]) GraphAddNodeOpt {
    return func(o *graphAddNodeOpts) {
        o.preHandler = handler
    }
}

// Usage
graph.AddChatModelNode("model", chatModel, 
    WithNodeName("MyModel"),
    WithStatePreHandler(preHandler))
```

### Scala 3 Translation

```scala
// Option 1: Case class with defaults
case class NodeConfig(
  name: Option[String] = None,
  preHandler: Option[PreHandler[?, ?]] = None,
  postHandler: Option[PostHandler[?, ?]] = None
)

graph.addChatModelNode("model", chatModel, NodeConfig(name = Some("MyModel")))

// Option 2: Builder pattern
case class NodeConfigBuilder private (config: NodeConfig):
  def withName(name: String): NodeConfigBuilder = 
    copy(config = config.copy(name = Some(name)))
  def build: NodeConfig = config

// Option 3: Using Scala 3 context functions (cleaner)
def addNode(key: String, node: Node)(using config: NodeConfig = NodeConfig()): Graph
```

### LLM4S CompletionOptions Pattern

**LLM4S uses case classes with defaults for configuration**:

```scala
import org.llm4s.llmconnect.model.CompletionOptions

// Case class with sensible defaults
case class CompletionOptions(
  temperature: Option[Double] = None,
  maxTokens: Option[Int] = None,
  topP: Option[Double] = None,
  frequencyPenalty: Option[Double] = None,
  presencePenalty: Option[Double] = None,
  stop: List[String] = Nil,
  tools: List[ToolFunction] = Nil
)

// Usage with named parameters
val options: CompletionOptions = CompletionOptions(
  temperature = Some(0.7),
  maxTokens = Some(1000)
)

client.complete(conversation, options)
```

### structured-llm Prompt Configuration

**structured-llm uses immutable case classes for prompts**:

```scala
import org.adk4s.structured.core.{Prompt, Message, Role}

// Prompt is immutable - methods return new instances
val prompt: Prompt = Prompt.simple("You are helpful", "Hello")
  .addUserMessage("Follow-up question")
  .withOutputFormat[MyOutputType]  // Returns new Prompt with schema injected
```

**ADK4S Recommendation**: Use case classes with defaults. LLM4S and structured-llm already follow this pattern - extend rather than replace.

---

## 3. Type-Safe Graph Construction

Eino validates type compatibility at graph construction time.

### Edge Type Validation

```go
func (g *graph) addEdgeWithMappings(startNode, endNode string, ...) error {
    startNodeOutputType := g.getNodeOutputType(startNode)
    endNodeInputType := g.getNodeInputType(endNode)
    
    result := checkAssignable(startNodeOutputType, endNodeInputType)
    if result == assignableTypeMustNot {
        return fmt.Errorf("type mismatch: %s -> %s", 
            startNodeOutputType, endNodeInputType)
    }
    // ...
}
```

### Assignability Checking

```go
type assignableType int

const (
    assignableTypeMust    assignableType = iota  // Always compatible
    assignableTypeMay                             // May be compatible (runtime check)
    assignableTypeMustNot                         // Never compatible
)

func checkAssignable(from, to reflect.Type) assignableType {
    if from == to { return assignableTypeMust }
    if to.Kind() == reflect.Interface && from.Implements(to) { 
        return assignableTypeMust 
    }
    if from.Kind() == reflect.Interface { 
        return assignableTypeMay  // Runtime check needed
    }
    return assignableTypeMustNot
}
```

### Scala 3 Translation

```scala
// Compile-time type safety via type parameters
trait Node[I, O]:
  def inputType: TypeTag[I]
  def outputType: TypeTag[O]

class Graph[I, O]:
  // Edge addition is type-safe at compile time
  def addEdge[A, B, C](
    from: NodeRef[A, B], 
    to: NodeRef[B, C]
  ): Graph[I, O] = ???
  
  // Or using path-dependent types
  def connect(from: this.Node, to: this.Node)(
    using from.Output =:= to.Input
  ): Graph[I, O] = ???
```

### Workflows4s Type-Safe Composition

**Workflows4s WIO provides compile-time type safety through type parameters**:

```scala
import workflows4s.wio.WIO

// WIO[Input, Error, Output, Context] - all types tracked at compile time
val step1: WIO[String, Nothing, Int, Ctx.type] = 
  WIO.runIO(_ => IO.pure(42))

val step2: WIO[Int, Nothing, String, Ctx.type] = 
  WIO.runIO(n => IO.pure(s"Result: $n"))

// Composition is type-checked at compile time
// step1 outputs Int, step2 expects Int - compiles!
val composed: WIO[String, Nothing, String, Ctx.type] = 
  step1.flatMap(step2)

// This would NOT compile:
// val invalid = step1.flatMap(step1)  // step1 expects String, not Int
```

### LLM4S Type-Safe Tool Parameters

**LLM4S SafeParameterExtractor provides type-safe parameter access**:

```scala
import org.llm4s.tools.SafeParameterExtractor

val handler: Map[String, Any] => Either[String, String] = { params =>
  val extractor: SafeParameterExtractor = SafeParameterExtractor(params)
  
  // Type-safe extraction with explicit types
  for
    location <- extractor.getString("location")   // Either[String, String]
    count <- extractor.getInt("count")            // Either[String, Int]
    enabled <- extractor.getBoolean("enabled")    // Either[String, Boolean]
  yield s"Location: $location, Count: $count, Enabled: $enabled"
}
```

**ADK4S Recommendation**: Leverage Scala's type system for compile-time validation. Workflows4s and LLM4S already provide type-safe APIs - use them directly.

---

## 4. Composite Pattern (Nested Graphs)

Graphs can contain other graphs as nodes.

### Nested Graph Example

```go
// Inner graph
innerGraph := NewGraph[string, int]()
innerGraph.AddLambdaNode("parse", parseLambda)
innerGraph.AddEdge(START, "parse")
innerGraph.AddEdge("parse", END)

// Outer graph
outerGraph := NewGraph[string, string]()
outerGraph.AddGraphNode("inner", innerGraph)  // Graph as node
outerGraph.AddLambdaNode("format", formatLambda)
outerGraph.AddEdge(START, "inner")
outerGraph.AddEdge("inner", "format")
outerGraph.AddEdge("format", END)
```

### State Inheritance

```go
// Inner graph can access outer graph's state
func ProcessState[S any](ctx context.Context, handler func(context.Context, S) error) error {
    // Walks up the state chain to find matching type
    for interState != nil {
        if cState, ok := interState.state.(S); ok {
            return handler(ctx, cState)
        }
        interState = interState.parent
    }
    return fmt.Errorf("state type not found")
}
```

### Scala 3 Translation

```scala
// Graph as a node type
given [I, O]: Conversion[Graph[I, O], Node[I, O]] = graph => graph.asNode

// State with parent chain
case class GraphContext[S](
  state: Ref[IO, S],
  parent: Option[GraphContext[?]]
)

def findState[S: TypeTag](ctx: GraphContext[?]): Option[Ref[IO, S]] =
  ctx match
    case GraphContext(ref: Ref[IO, S], _) => Some(ref)
    case GraphContext(_, Some(parent)) => findState[S](parent)
    case _ => None
```

### Workflows4s Nested Workflows

**Workflows4s supports workflow composition naturally**:

```scala
import workflows4s.wio.WIO

// Define inner workflow
val innerWorkflow: WIO[String, Nothing, Int, Ctx.type] =
  WIO.runIO(_ => IO.pure(42))
    .flatMap(n => WIO.runIO(_ => IO.pure(n * 2)))

// Compose into outer workflow
val outerWorkflow: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO(_ => IO.pure("start"))
    .flatMap(_ => innerWorkflow)  // Nested workflow
    .flatMap(n => WIO.runIO(_ => IO.pure(s"Result: $n")))

// Workflows share the same context - state is accessible throughout
```

### LLM4S Agent Composition

**LLM4S agents can be composed for multi-agent patterns**:

```scala
import org.llm4s.agent.{Agent, AgentState}

// Define specialized agents
val researchAgent: Agent = Agent(client, researchTools, Some("You are a research assistant"))
val writerAgent: Agent = Agent(client, writerTools, Some("You are a technical writer"))

// Compose agents in a workflow
def multiAgentWorkflow(query: String): Either[LLMError, String] =
  for
    // First agent does research
    researchResult <- researchAgent.run(query)
    researchContent = researchResult.conversation.lastAssistantMessage
    
    // Second agent writes based on research
    writeResult <- writerAgent.run(s"Write an article based on: $researchContent")
  yield writeResult.conversation.lastAssistantMessage
```

**ADK4S Recommendation**: Use Workflows4s for nested workflow composition. Use LLM4S agent composition for multi-agent patterns.

---

## 5. Strategy Pattern (Branching)

Dynamic routing based on runtime conditions.

### Branch Definition

```go
// Condition function returns target node key
condition := func(ctx context.Context, input *Message) (string, error) {
    if len(input.ToolCalls) > 0 {
        return "tools", nil
    }
    return "end", nil
}

branch := NewGraphBranch(condition, map[string]bool{
    "tools": true,
    "end":   true,
})

graph.AddBranch("model", branch)
```

### Stream-Aware Branching

```go
// Branch that consumes stream to make decision
streamBranch := NewStreamGraphBranch(
    func(ctx context.Context, sr *StreamReader[*Message]) (string, error) {
        // Must consume/close stream
        defer sr.Close()
        msg, _ := concatStreamReader(sr)
        if len(msg.ToolCalls) > 0 {
            return "tools", nil
        }
        return "end", nil
    },
    endNodes,
)
```

### Scala 3 Translation

```scala
// Branch as a sealed trait
sealed trait Branch[I]:
  def endNodes: Set[String]

case class InvokeBranch[I](
  condition: I => IO[String],
  endNodes: Set[String]
) extends Branch[I]

case class StreamBranch[I](
  condition: fs2.Stream[IO, I] => IO[String],
  endNodes: Set[String]
) extends Branch[I]

// Usage
val branch = InvokeBranch[Message](
  msg => IO.pure(if msg.toolCalls.nonEmpty then "tools" else "end"),
  Set("tools", "end")
)
```

### Workflows4s Branching with WIO.fork

**Workflows4s provides type-safe branching**:

```scala
import workflows4s.wio.WIO

// Simple boolean branching
val branchWorkflow: WIO[Message, Nothing, String, Ctx.type] =
  WIO.fork[Message, String, Ctx.type](
    condition = msg => msg.toolCalls.nonEmpty,
    ifTrue = WIO.runIO(_ => IO.pure("Processing tools...")),
    ifFalse = WIO.runIO(_ => IO.pure("No tools needed"))
  )

// Multi-way branching using pattern matching
val multiWayBranch: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO[String, Ctx.type] { input =>
    input match
      case s if s.startsWith("A") => IO.pure("Branch A")
      case s if s.startsWith("B") => IO.pure("Branch B")
      case _ => IO.pure("Default branch")
  }
```

### LLM4S Agent Status-Based Branching

**LLM4S Agent uses status for control flow**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}

// Agent status determines next action
def processAgentStep(state: AgentState): Either[LLMError, AgentState] =
  state.status match
    case AgentStatus.Complete => 
      Right(state)  // Done - return final state
    case AgentStatus.AwaitingToolResults =>
      agent.step(state)  // Execute pending tools, then continue
    case AgentStatus.Ready =>
      agent.step(state)  // Get next LLM response
```

**ADK4S Recommendation**: Use Workflows4s `WIO.fork` for workflow branching. Use LLM4S `AgentStatus` for agent control flow.

---

## 6. Middleware Pattern (Tool Middleware)

Wrapping tool execution with cross-cutting concerns.

### Middleware Definition

```go
type InvokableToolMiddleware func(InvokableToolEndpoint) InvokableToolEndpoint
type InvokableToolEndpoint func(ctx context.Context, input *ToolInput) (*ToolOutput, error)

// Example middleware
func loggingMiddleware(next InvokableToolEndpoint) InvokableToolEndpoint {
    return func(ctx context.Context, input *ToolInput) (*ToolOutput, error) {
        log.Printf("Calling tool: %s", input.Name)
        result, err := next(ctx, input)
        log.Printf("Tool result: %v", result)
        return result, err
    }
}
```

### Middleware Composition

```go
// Applied in reverse order (last added runs first around the core)
for i := len(middlewares) - 1; i >= 0; i-- {
    next = middlewares[i](next)
}
```

### Scala 3 Translation

```scala
// Using Kleisli for middleware composition
type ToolEndpoint[F[_]] = Kleisli[F, ToolInput, ToolOutput]
type ToolMiddleware[F[_]] = ToolEndpoint[F] => ToolEndpoint[F]

def loggingMiddleware[F[_]: Console: Monad]: ToolMiddleware[F] = 
  endpoint => Kleisli { input =>
    Console[F].println(s"Calling: ${input.name}") *>
    endpoint.run(input) <*
    Console[F].println("Done")
  }

// Composition
val middlewares: List[ToolMiddleware[IO]] = List(logging, metrics, retry)
val composed: ToolEndpoint[IO] = middlewares.foldRight(coreEndpoint)((m, e) => m(e))
```

### LLM4S Tool Handler Composition

**LLM4S tool handlers can be composed with middleware-like patterns**:

```scala
import org.llm4s.tools.{ToolFunction, ToolBuilder}

// Define a wrapper for tool handlers
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

// Compose middlewares
val composedHandler: ToolHandler = 
  (loggingMiddleware compose validationMiddleware)(baseHandler)

// Use in tool definition
val tool: ToolFunction = ToolBuilder("my_tool")
  .handler(composedHandler)
  .build
```

### Workflows4s Interceptors

**Workflows4s provides interceptor patterns via WorkflowInstanceEngine**:

```scala
import workflows4s.runtime.WorkflowInstanceEngine

// Interceptor wraps workflow execution
trait WorkflowInterceptor[F[_]]:
  def beforeStep[A](stepName: String, input: A): F[Unit]
  def afterStep[A](stepName: String, output: A): F[Unit]
  def onError(stepName: String, error: Throwable): F[Unit]

// Apply interceptor to workflow engine
def withInterceptor[F[_]: Monad](
  engine: WorkflowInstanceEngine[F],
  interceptor: WorkflowInterceptor[F]
): WorkflowInstanceEngine[F] = ???
```

**ADK4S Recommendation**: Use Kleisli for middleware. LLM4S tool handlers and Workflows4s interceptors provide natural extension points.

---

## 7. Observer Pattern (Callbacks)

Decoupled notification of execution events.

### Callback Registration

```go
// Global handlers
callbacks.AppendGlobalHandlers(tracingHandler, metricsHandler)

// Per-invocation handlers
graph.Invoke(ctx, input, WithCallbacks(customHandler))

// Node-specific handlers
graph.Invoke(ctx, input, WithCallbacks(handler).DesignateNode("model"))
```

### Callback Dispatch

```go
// Handlers notified at each timing
for _, handler := range handlers {
    if checker, ok := handler.(TimingChecker); ok {
        if !checker.IsNeeded(timing) {
            continue
        }
    }
    ctx = handler.OnStart(ctx, info, input)
}
```

### Scala 3 Translation

```scala
// Callback as a trait with default implementations
trait CallbackHandler[F[_]]:
  def onStart[I](info: RunInfo, input: I): F[Unit] = Applicative[F].unit
  def onEnd[O](info: RunInfo, output: O): F[Unit] = Applicative[F].unit
  def onError(info: RunInfo, error: Throwable): F[Unit] = Applicative[F].unit

// Composition of handlers
given Monoid[CallbackHandler[IO]] with
  def empty: CallbackHandler[IO] = new CallbackHandler[IO] {}
  def combine(a: CallbackHandler[IO], b: CallbackHandler[IO]): CallbackHandler[IO] =
    new CallbackHandler[IO]:
      override def onStart[I](info: RunInfo, input: I): IO[Unit] =
        a.onStart(info, input) *> b.onStart(info, input)
      // ... etc
```

### LLM4S Agent Step-by-Step Observation

**LLM4S provides observability through step-by-step execution**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}

// Observer pattern via step-by-step execution
trait AgentObserver:
  def onStepStart(state: AgentState): IO[Unit]
  def onStepEnd(state: AgentState): IO[Unit]
  def onToolCall(toolName: String, args: String): IO[Unit]
  def onComplete(state: AgentState): IO[Unit]

def runWithObserver(agent: Agent, query: String, observer: AgentObserver): IO[AgentState] =
  def loop(state: AgentState): IO[AgentState] =
    for
      _ <- observer.onStepStart(state)
      nextState <- IO.fromEither(agent.step(state))
      _ <- observer.onStepEnd(nextState)
      result <- nextState.status match
        case AgentStatus.Complete => 
          observer.onComplete(nextState) *> IO.pure(nextState)
        case _ => loop(nextState)
    yield result
  
  val initial: AgentState = agent.initialize(query)
  loop(initial)
```

### Workflows4s Event-Based Observation

**Workflows4s events provide natural observation points**:

```scala
import workflows4s.wio.WorkflowContext

// Events are emitted for every state change
sealed trait WorkflowEvent
case class StepStarted(stepName: String, timestamp: Instant) extends WorkflowEvent
case class StepCompleted(stepName: String, timestamp: Instant) extends WorkflowEvent
case class WorkflowFailed(error: Throwable, timestamp: Instant) extends WorkflowEvent

// Observer can subscribe to event stream
def observeWorkflow[F[_]: Concurrent](
  events: fs2.Stream[F, WorkflowEvent],
  observer: WorkflowEvent => F[Unit]
): F[Unit] =
  events.evalMap(observer).compile.drain
```

**ADK4S Recommendation**: Model callbacks as a Monoid for easy composition. Use LLM4S step-by-step execution for agent observation. Use Workflows4s events for workflow observation.
