# Eino Framework Analysis - Gotchas and Best Practices

## Gotchas

### 1. Mutable Tool Binding (Deprecated Pattern)

**Problem**: The old `ChatModel.BindTools()` method mutates state, causing concurrency issues.

```go
// BAD - Deprecated pattern
type ChatModel interface {
    BaseChatModel
    BindTools(tools []*ToolInfo) error  // Mutates internal state!
}

// Multiple goroutines binding different tools = race condition
go func() { model.BindTools(toolsA) }()
go func() { model.BindTools(toolsB) }()  // Race!
```

**Solution**: Use `ToolCallingChatModel.WithTools()` which returns a new instance.

```go
// GOOD - Immutable pattern
type ToolCallingChatModel interface {
    BaseChatModel
    WithTools(tools []*ToolInfo) (ToolCallingChatModel, error)  // Returns new instance
}

modelA, _ := model.WithTools(toolsA)  // Safe
modelB, _ := model.WithTools(toolsB)  // Safe, independent
```

**ADK4S**: Always use immutable `withX` methods. Never mutate component state.

**LLM4S Pattern**: LLM4S follows this pattern - `CompletionOptions` is immutable, tools are passed per-request:

```scala
import org.llm4s.llmconnect.model.CompletionOptions
import org.llm4s.tools.ToolFunction

// Tools are passed in options, not bound to client
val optionsWithTools: CompletionOptions = CompletionOptions(
  tools = List(weatherTool, calculatorTool)
)

// Each call is independent - no shared mutable state
val result1: Either[LLMError, Completion] = client.complete(conv1, optionsWithTools)
val result2: Either[LLMError, Completion] = client.complete(conv2, optionsWithTools)
```

---

### 2. Stream Must Be Closed

**Problem**: Forgetting to close streams causes resource leaks.

```go
// BAD - Stream not closed
stream, _ := r.Stream(ctx, input)
for {
    chunk, err := stream.Recv()
    if err == io.EOF { break }
    if err != nil { return err }  // Stream not closed on error!
    process(chunk)
}
// Stream not closed after loop!

// GOOD - Always close
stream, _ := r.Stream(ctx, input)
defer stream.Close()  // Always close!
for {
    chunk, err := stream.Recv()
    if err == io.EOF { break }
    if err != nil { return err }
    process(chunk)
}
```

**ADK4S**: fs2.Stream handles this automatically via bracket/resource semantics.

**LLM4S + fs2 Pattern**: Convert LLM4S Iterator to fs2.Stream for automatic resource management:

```scala
import fs2.Stream
import cats.effect.IO

// LLM4S returns Iterator - wrap in fs2 for safety
val streamResult: Either[LLMError, Iterator[StreamedChunk]] = 
  client.completeStreamed(conversation, options)

// fs2.Stream handles cleanup automatically
val safeStream: Stream[IO, String] = streamResult match
  case Right(iter) => 
    Stream.fromIterator[IO](iter, 1)
      .map(_.content)
      .onFinalize(IO.println("Stream closed"))  // Automatic cleanup
  case Left(error) => 
    Stream.raiseError[IO](new RuntimeException(error.formatted))

// Use bracket for explicit resource management
Stream.bracket(IO(acquireResource))(r => IO(r.close()))
  .flatMap(r => processResource(r))
```

---

### 3. Branch Condition Must Consume Stream

**Problem**: Stream branch conditions must fully consume or close the stream.

```go
// BAD - Stream not consumed
streamBranch := func(ctx context.Context, sr *StreamReader[*Message]) (string, error) {
    // Checking first chunk only, but stream not closed!
    chunk, _ := sr.Recv()
    if len(chunk.ToolCalls) > 0 {
        return "tools", nil
    }
    return "end", nil
}

// GOOD - Stream properly closed
streamBranch := func(ctx context.Context, sr *StreamReader[*Message]) (string, error) {
    defer sr.Close()  // Always close!
    // ... process stream
    return result, nil
}
```

**ADK4S**: Design branch conditions to work with fs2's resource-safe patterns.

**Workflows4s Pattern**: Workflows4s WIO.fork handles branching without stream consumption issues:

```scala
import workflows4s.wio.WIO

// WIO.fork is type-safe and doesn't require manual stream management
val branchWorkflow: WIO[Message, Nothing, String, Ctx.type] =
  WIO.fork[Message, String, Ctx.type](
    condition = msg => msg.toolCalls.nonEmpty,
    ifTrue = WIO.pure(_ => "tools"),
    ifFalse = WIO.pure(_ => "end")
  )
```

---

### 4. State Type Must Match Exactly

**Problem**: `ProcessState` requires exact type match, not interface satisfaction.

```go
type MyState struct { Counter int }
type OtherState struct { Counter int }  // Same fields, different type

// This fails even though fields are identical
err := compose.ProcessState[*OtherState](ctx, func(ctx context.Context, s *OtherState) error {
    // Error: cannot find state with type *OtherState
    return nil
})
```

**ADK4S**: Use TypeTags or ClassTags for runtime type lookup. Document type requirements clearly.

**Workflows4s Pattern**: Workflows4s uses typed WorkflowContext - state types are explicit:

```scala
import workflows4s.wio.WorkflowContext

// State type is explicit in context definition
object MyContext extends WorkflowContext:
  override type State = MyState  // Explicit type
  override type Event = MyEvent

// WIO is parameterized by context - type safety at compile time
val workflow: WIO[String, Nothing, String, MyContext.type] = ???
// Cannot accidentally use wrong state type
```

**LLM4S Pattern**: LLM4S AgentState is a concrete type - no runtime type matching needed:

```scala
import org.llm4s.agent.AgentState

// AgentState is a concrete case class - no type matching issues
val state: AgentState = agent.initialize(query)
// state.conversation, state.pendingToolCalls, etc. are all typed
```

---

### 5. Graph Compilation Is One-Time

**Problem**: Graphs cannot be modified after compilation.

```go
graph := NewGraph[string, string]()
graph.AddLambdaNode("node1", lambda1)
graph.AddEdge(START, "node1")
graph.AddEdge("node1", END)

r, _ := graph.Compile(ctx)

// This fails!
graph.AddLambdaNode("node2", lambda2)  // Error: graph already compiled
```

**ADK4S**: Make this explicit in types - compiled graph should be a different type than builder.

**Workflows4s Pattern**: WIO is immutable - composition creates new WIO instances:

```scala
import workflows4s.wio.WIO

// WIO is immutable - each operation returns a new WIO
val step1: WIO[String, Nothing, Int, Ctx.type] = WIO.runIO(_ => IO.pure(42))
val step2: WIO[Int, Nothing, String, Ctx.type] = WIO.runIO(n => IO.pure(s"Result: $n"))

// Composition creates new WIO - originals unchanged
val composed: WIO[String, Nothing, String, Ctx.type] = step1.flatMap(_ => step2)

// No "compilation" step - WIO is always ready to run
val runtime: WorkflowRuntime[IO, Ctx.type] = InMemorySyncRuntime.create[Ctx.type]
val result: IO[String] = runtime.run(composed, "input")
```

---

### 6. Parallel Output Is Map, Not Original Type

**Problem**: Parallel nodes output `map[string]any`, not the original types.

```go
parallel := NewParallel()
parallel.AddLambda("a", lambdaReturningString)
parallel.AddLambda("b", lambdaReturningInt)

// Output is map[string]any{"a": "string", "b": 123}
// NOT a struct with typed fields
```

**ADK4S**: Consider using HLists or tuples for type-safe parallel outputs.

**Workflows4s Pattern**: WIO.parallel returns typed tuples:

```scala
import workflows4s.wio.WIO

// Parallel execution returns typed tuple
val parallelWorkflow: WIO[Unit, Nothing, (String, Int), Ctx.type] =
  WIO.parallel(
    WIO.runIO[String, Ctx.type](_ => IO.pure("hello")),
    WIO.runIO[Int, Ctx.type](_ => IO.pure(42))
  )

// Result is (String, Int) - fully typed!
val result: IO[(String, Int)] = runtime.run(parallelWorkflow, ())
```

---

### 7. Field Mapping Paths Are Strings

**Problem**: Field mapping uses string paths, no compile-time validation.

```go
// Typo in field name - only fails at runtime
wf.AddInput("model", MapFields("Conten", "Input"))  // "Conten" typo!
```

**ADK4S**: Use Smithy-generated types for compile-time field validation.

**structured-llm Pattern**: Use Smithy schemas for type-safe field definitions:

```scala
import org.adk4s.structured.core.Schema
import smithy4s.Schema as Smithy4sSchema

// Define schema with Smithy IDL - compile-time validation
case class ModelOutput(content: String, role: String)
given Schema[ModelOutput] = Schema.derived[ModelOutput]

// Field access is type-safe - no string paths
val output: ModelOutput = ???
val content: String = output.content  // Compile-time checked
val role: String = output.role        // Compile-time checked
```

---

## Best Practices

### 1. Use ToolCallingChatModel Over ChatModel

```go
// Prefer this
config := &AgentConfig{
    ToolCallingModel: model,  // Immutable tool binding
}

// Over this (deprecated)
config := &AgentConfig{
    Model: model,  // Mutable tool binding
}
```

**LLM4S Best Practice**: Use LLM4S Agent with ToolRegistry - tools are immutable:

```scala
import org.llm4s.agent.Agent
import org.llm4s.tools.ToolRegistry

// Tools are passed at construction - immutable
val agent: Agent = Agent(
  client = client,
  tools = ToolRegistry(List(weatherTool, calculatorTool)),
  systemPrompt = Some("You are helpful")
)
```

### 2. Always Set MaxRunSteps

```go
// Prevent infinite loops in cyclic graphs
r, _ := graph.Compile(ctx, WithMaxRunSteps(100))

// Or at runtime
result, _ := r.Invoke(ctx, input, WithRuntimeMaxSteps(50))
```

**LLM4S Best Practice**: LLM4S Agent supports maxSteps:

```scala
val agent: Agent = Agent(
  client = client,
  tools = registry,
  maxSteps = Some(12)  // Prevent infinite loops
)

// Or check step count during execution
def runWithLimit(agent: Agent, query: String, maxSteps: Int): Either[LLMError, AgentState] =
  def loop(state: AgentState): Either[LLMError, AgentState] =
    if state.stepCount >= maxSteps then Right(state)
    else if state.status == AgentStatus.Complete then Right(state)
    else agent.step(state).flatMap(loop)
  
  loop(agent.initialize(query))
```

### 3. Use State Pre/Post Handlers for Side Effects

```go
// Accumulate messages in state, not in lambda
preHandler := func(ctx context.Context, input []*Message, state *State) ([]*Message, error) {
    state.Messages = append(state.Messages, input...)
    return state.Messages, nil
}

graph.AddChatModelNode("model", model, WithStatePreHandler(preHandler))
```

**Workflows4s Best Practice**: Use event sourcing for state changes:

```scala
import workflows4s.wio.WIO

// State changes via events - pure and auditable
val processStep: WIO[Message, Nothing, Message, Ctx.type] =
  WIO.runIO { msg =>
    IO.pure(msg)
  }.emitEvent(msg => MessageAdded(msg))  // State updated via event
```

### 4. Use Callbacks for Cross-Cutting Concerns

```go
// Don't pollute business logic with logging
handler := callbacks.NewHandlerBuilder().
    OnStartFn(logStart).
    OnEndFn(logEnd).
    OnErrorFn(logError).
    Build()

r.Invoke(ctx, input, WithCallbacks(handler))
```

**LLM4S Best Practice**: Use step-by-step execution for observability:

```scala
def runWithLogging(agent: Agent, query: String): IO[AgentState] =
  def loop(state: AgentState, step: Int): IO[AgentState] =
    IO.println(s"Step $step: ${state.status}") *>  // Cross-cutting logging
    (state.status match
      case AgentStatus.Complete => IO.pure(state)
      case _ => IO.fromEither(agent.step(state)).flatMap(loop(_, step + 1))
    )
  
  IO.pure(agent.initialize(query)).flatMap(loop(_, 1))
```

### 5. Prefer Chain for Simple Linear Flows

```go
// Simple and readable
chain := NewChain[string, string]().
    AppendLambda(step1).
    AppendLambda(step2).
    AppendLambda(step3)

// Only use Graph when you need:
// - Cycles
// - Complex branching
// - Non-linear data flow
```

**Workflows4s Best Practice**: Use WIO.flatMap for linear flows:

```scala
import workflows4s.wio.WIO

// Simple and readable
val linearWorkflow: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO(step1)
    .flatMap(_ => WIO.runIO(step2))
    .flatMap(_ => WIO.runIO(step3))

// Or using >>> operator
val linearAlt: WIO[String, Nothing, String, Ctx.type] =
  step1Wio >>> step2Wio >>> step3Wio
```

### 6. Use Workflow for Field-Level Data Mapping

```go
// When you need to map specific fields between nodes
wf := NewWorkflow[Input, Output]()
wf.AddLambdaNode("a", lambdaA).AddInput(START)
wf.AddLambdaNode("b", lambdaB).AddInput("a", MapFields("X", "Y"))
```

### 7. Handle Unknown Tools Gracefully

```go
toolsNode, _ := NewToolNode(ctx, &ToolsNodeConfig{
    Tools: tools,
    UnknownToolsHandler: func(ctx context.Context, name, input string) (string, error) {
        return fmt.Sprintf("Tool '%s' not found", name), nil
    },
})
```

**LLM4S Best Practice**: ToolRegistry handles unknown tools gracefully:

```scala
import org.llm4s.tools.ToolRegistry

val registry: ToolRegistry = ToolRegistry(List(weatherTool, calculatorTool))

// execute returns Left for unknown tools
val result: Either[String, String] = registry.execute("unknown_tool", Map.empty)
// result = Left("Tool 'unknown_tool' not found")

// Handle in agent context
result match
  case Right(output) => s"Tool result: $output"
  case Left(error) => s"Tool error: $error"  // Graceful handling
```

### 8. Use Stream Paradigm for Long-Running Operations

```go
// Better UX - user sees progress
stream, _ := agent.Stream(ctx, messages)
for {
    chunk, err := stream.Recv()
    if err == io.EOF { break }
    displayToUser(chunk)  // Incremental display
}

// Worse UX - user waits for complete response
result, _ := agent.Generate(ctx, messages)
displayToUser(result)  // All at once
```

**LLM4S Best Practice**: Use completeStreamed for real-time output:

```scala
import org.llm4s.llmconnect.model.StreamedChunk
import fs2.Stream
import cats.effect.IO

val streamResult: Either[LLMError, Iterator[StreamedChunk]] = 
  client.completeStreamed(conversation, options)

// Stream to user in real-time
streamResult match
  case Right(iter) =>
    Stream.fromIterator[IO](iter, 1)
      .map(_.content)
      .filter(_.nonEmpty)
      .evalMap(chunk => IO.print(chunk))  // Incremental display
      .compile.drain
  case Left(error) =>
    IO.println(s"Error: ${error.formatted}")
```

### 9. Register Custom Types for Serialization

```go
// Required for checkpoint/interrupt support
func init() {
    schema.RegisterName[*MyState]("my_package_my_state")
}
```

### 10. Use Node Names for Debugging

```go
// Named nodes are easier to debug
graph.AddChatModelNode("model", cm, WithNodeName("GPT4ChatModel"))
graph.AddLambdaNode("parser", lambda, WithNodeName("JSONParser"))

// Error messages will include node names
// "[NodeRunError] ... node path: [GPT4ChatModel, JSONParser]"
```

---

## ADK4S-Specific Recommendations

### 1. Leverage Scala's Type System

```scala
// Use phantom types for state machine validation
sealed trait GraphState
sealed trait Building extends GraphState
sealed trait Compiled extends GraphState

class Graph[I, O, S <: GraphState]:
  def addNode(using S =:= Building): Graph[I, O, Building]
  def compile(using S =:= Building): IO[Graph[I, O, Compiled]]
  def invoke(using S =:= Compiled): I => IO[O]
```

### 2. Use Opaque Types for Type Safety

```scala
// Per project.md - use opaque types
opaque type NodeKey = String
object NodeKey:
  def apply(s: String): NodeKey = s
  extension (k: NodeKey) def value: String = k

opaque type FieldPath = List[String]
object FieldPath:
  def apply(path: String): FieldPath = path.split('.').toList
```

### 3. Use Validated for Error Accumulation

```scala
// Accumulate all validation errors
def compile: ValidatedNec[GraphError, IO[Runnable[I, O]]] =
  (
    validateNodes,
    validateEdges,
    validateTypes
  ).mapN((_, _, _) => buildRunnable)
```

### 4. Use fs2 for Streaming

```scala
// fs2 provides all streaming primitives needed
trait Runnable[I, O]:
  def stream(input: I): fs2.Stream[IO, O]
  
// Automatic resource management
stream.evalMap(process).compile.drain
```

### 5. Use Ref for State Management

```scala
// Thread-safe state with Cats Effect
case class GraphState(messages: List[Message])

def createGraph: IO[Graph] =
  Ref.of[IO, GraphState](GraphState(Nil)).map(Graph(_))
```

### 6. Use Smithy for Schema Definitions

```scala
// Per project.md - use Smithy4s for type-safe schemas
// Define tool parameters in Smithy
// Generate Scala types automatically
// Get compile-time validation of field mappings
```

### 7. Use Kleisli for Middleware

```scala
// Clean middleware composition
type Middleware[F[_], A, B] = Kleisli[F, A, B] => Kleisli[F, A, B]

val composed = middlewares.foldRight(endpoint)((m, e) => m(e))
```

### 8. Use Resource for Lifecycle Management

```scala
// Automatic cleanup
def withCallbacks[A](handler: CallbackHandler[IO])(fa: IO[A]): IO[A] =
  Resource.make(handler.onStart)(_ => handler.onEnd).use(_ => fa)
```
