# Eino Framework Analysis - Usage Examples from Tests

## Example 1: Simple Graph with ChatModel

From `graph_test.go` - Basic graph construction with prompt template and chat model.

```go
func TestSingleGraph(t *testing.T) {
    ctx := context.Background()
    g := NewGraph[map[string]any, *schema.Message]()

    // Create prompt template
    pt := prompt.FromMessages(schema.FString,
        schema.UserMessage("what's the weather in {location}?"),
    )
    g.AddChatTemplateNode("prompt", pt)

    // Create chat model
    cm := &chatModel{msgs: []*schema.Message{{Role: schema.Assistant, Content: "the weather is good"}}}
    g.AddChatModelNode("model", cm, WithNodeName("MockChatModel"))

    // Connect nodes
    g.AddEdge(START, "prompt")
    g.AddEdge("prompt", "model")
    g.AddEdge("model", END)

    // Compile and run
    r, _ := g.Compile(context.Background(), WithMaxRunSteps(10))
    
    // Invoke
    result, _ := r.Invoke(ctx, map[string]any{"location": "beijing"})
    
    // Stream
    stream, _ := r.Stream(ctx, map[string]any{"location": "beijing"})
    for {
        chunk, err := stream.Recv()
        if err == io.EOF { break }
        // process chunk
    }
}
```

### Scala 3 Translation

```scala
def simpleGraphExample: IO[Unit] =
  for
    // Create graph
    graph <- IO.pure(Graph[Map[String, Any], Message]())
    
    // Create prompt template
    promptTemplate = PromptTemplate.fromMessages(
      Message.user("what's the weather in {location}?")
    )
    
    // Create mock chat model
    chatModel = MockChatModel(Message.assistant("the weather is good"))
    
    // Build graph
    compiled <- graph
      .addChatTemplateNode("prompt", promptTemplate)
      .flatMap(_.addChatModelNode("model", chatModel))
      .flatMap(_.addEdge(START, "prompt"))
      .flatMap(_.addEdge("prompt", "model"))
      .flatMap(_.addEdge("model", END))
      .flatMap(_.compile(GraphCompileConfig(maxRunSteps = 10)))
    
    // Invoke
    result <- compiled.invoke(Map("location" -> "beijing"))
    _ <- IO.println(s"Result: ${result.content}")
    
    // Stream
    _ <- compiled.stream(Map("location" -> "beijing"))
      .evalMap(chunk => IO.println(s"Chunk: ${chunk.content}"))
      .compile.drain
  yield ()
```

### LLM4S + structured-llm Implementation

**Using LLM4S and structured-llm for the same functionality**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{Conversation, UserMessage, SystemMessage, CompletionOptions}
import org.adk4s.structured.core.{StructuredLLM, Prompt, PromptTemplate}
import cats.effect.IO

def simpleWeatherExample: IO[Unit] =
  val client: LLMClient = LLMClient.create()
  val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)
  
  // Define output type
  case class WeatherResponse(location: String, weather: String, temperature: Int)
  given Schema[WeatherResponse] = Schema.derived[WeatherResponse]
  
  // Create prompt template
  val template: PromptTemplate[String] = PromptTemplate.withSystem(
    "You are a weather assistant. Respond with JSON."
  ).withUserTemplate("What's the weather in {location}?")
  
  for
    // Invoke with type-safe output
    result <- structured.completeTemplate[String, WeatherResponse](
      template, 
      "beijing"
    )
    _ <- IO.println(s"Weather in ${result.location}: ${result.weather}, ${result.temperature}°C")
    
    // Streaming (using LLM4S directly)
    streamResult = client.completeStreamed(
      Conversation(Vector(
        SystemMessage("You are a weather assistant"),
        UserMessage("What's the weather in beijing?")
      )),
      CompletionOptions()
    )
    _ <- streamResult match
      case Right(iter) => 
        fs2.Stream.fromIterator[IO](iter, 1)
          .map(_.content)
          .filter(_.nonEmpty)
          .evalMap(chunk => IO.print(chunk))
          .compile.drain
      case Left(error) => IO.println(s"Error: ${error.formatted}")
  yield ()
```

### Workflows4s Implementation

**Using Workflows4s for orchestrated workflow**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}
import cats.effect.IO

object WeatherContext extends WorkflowContext:
  override type State = WeatherState
  override type Event = WeatherEvent

case class WeatherState(location: Option[String], result: Option[String])
sealed trait WeatherEvent
case class LocationSet(location: String) extends WeatherEvent
case class WeatherFetched(result: String) extends WeatherEvent

def weatherWorkflow: WIO[String, Nothing, String, WeatherContext.type] =
  // Step 1: Set location
  WIO.runIO[String, WeatherContext.type] { location =>
    IO.pure(location)
  }.named("set_location")
  // Step 2: Call LLM
  .flatMap { location =>
    WIO.runIO[String, WeatherContext.type] { _ =>
      val client: LLMClient = LLMClient.create()
      val result: Either[LLMError, Completion] = client.complete(
        Conversation(Vector(UserMessage(s"What's the weather in $location?"))),
        CompletionOptions()
      )
      IO.fromEither(result.map(_.content))
    }.named("call_llm")
  }
```

---

## Example 2: Nested Graph

From `graph_test.go` - Graph containing another graph as a node.

```go
func TestNestedGraph(t *testing.T) {
    ctx := context.Background()
    
    // Create inner graph
    sg := NewGraph[map[string]any, *schema.Message]()
    sg.AddChatTemplateNode("prompt", pt)
    sg.AddChatModelNode("model", cm)
    sg.AddEdge(START, "prompt")
    sg.AddEdge("prompt", "model")
    sg.AddEdge("model", END)

    // Create outer graph
    g := NewGraph[string, *schema.Message]()
    
    // Lambda to convert string to map
    l1 := InvokableLambda(func(ctx context.Context, input string) (map[string]any, error) {
        return map[string]any{"location": input}, nil
    })
    
    // Lambda to process result
    l2 := InvokableLambda(func(ctx context.Context, input *schema.Message) (*schema.Message, error) {
        input.Content = fmt.Sprintf("after lambda 2: %s", input.Content)
        return input, nil
    })

    g.AddLambdaNode("lambda1", l1)
    g.AddGraphNode("sub_graph", sg)  // Nested graph!
    g.AddLambdaNode("lambda2", l2)
    
    g.AddEdge(START, "lambda1")
    g.AddEdge("lambda1", "sub_graph")
    g.AddEdge("sub_graph", "lambda2")
    g.AddEdge("lambda2", END)

    r, _ := g.Compile(ctx, WithMaxRunSteps(10), WithGraphName("GraphName"))
    result, _ := r.Invoke(ctx, "london")
}
```

### Scala 3 Translation

```scala
def nestedGraphExample: IO[Unit] =
  for
    // Create inner graph
    innerGraph <- Graph[Map[String, Any], Message]()
      .addChatTemplateNode("prompt", promptTemplate)
      .flatMap(_.addChatModelNode("model", chatModel))
      .flatMap(_.addEdge(START, "prompt"))
      .flatMap(_.addEdge("prompt", "model"))
      .flatMap(_.addEdge("model", END))
    
    // Create outer graph with lambdas
    toMap = Lambda[String, Map[String, Any]](input => 
      IO.pure(Map("location" -> input))
    )
    
    processResult = Lambda[Message, Message](msg =>
      IO.pure(msg.copy(content = s"after lambda 2: ${msg.content}"))
    )
    
    outerGraph <- Graph[String, Message]()
      .addLambdaNode("lambda1", toMap)
      .flatMap(_.addGraphNode("sub_graph", innerGraph))
      .flatMap(_.addLambdaNode("lambda2", processResult))
      .flatMap(_.addEdge(START, "lambda1"))
      .flatMap(_.addEdge("lambda1", "sub_graph"))
      .flatMap(_.addEdge("sub_graph", "lambda2"))
      .flatMap(_.addEdge("lambda2", END))
      .flatMap(_.compile(GraphCompileConfig(maxRunSteps = 10, graphName = Some("GraphName"))))
    
    result <- outerGraph.invoke("london")
  yield ()
```

### Workflows4s Nested Workflow Implementation

**Workflows4s naturally supports workflow composition**:

```scala
import workflows4s.wio.WIO
import cats.effect.IO

// Define inner workflow
val innerWorkflow: WIO[Map[String, Any], Nothing, String, Ctx.type] =
  WIO.runIO[Map[String, Any], Ctx.type] { input =>
    val location: String = input.getOrElse("location", "unknown").toString
    val client: LLMClient = LLMClient.create()
    val result: Either[LLMError, Completion] = client.complete(
      Conversation(Vector(UserMessage(s"What's the weather in $location?"))),
      CompletionOptions()
    )
    IO.fromEither(result.map(_.content))
  }.named("llm_call")

// Define outer workflow with composition
val outerWorkflow: WIO[String, Nothing, String, Ctx.type] =
  // Step 1: Convert string to map
  WIO.runIO[String, Ctx.type] { input =>
    IO.pure(Map("location" -> input))
  }.named("to_map")
  // Step 2: Call inner workflow
  .flatMap { inputMap =>
    innerWorkflow.contramap(_ => inputMap)
  }
  // Step 3: Post-process result
  .flatMap { result =>
    WIO.runIO[String, Ctx.type] { _ =>
      IO.pure(s"after processing: $result")
    }.named("post_process")
  }
```

---

## Example 3: Chain with Branch and Parallel

From `chain_test.go` - Complex chain with branching and parallel execution.

```go
func TestChain(t *testing.T) {
    cm := &mockIntentChatModel{}

    // Branch condition
    branchCond := func(ctx context.Context, input map[string]any) (string, error) {
        if rand.Intn(2) == 1 {
            return "b1", nil
        }
        return "b2", nil
    }

    // Branch lambdas
    b1 := InvokableLambda(func(ctx context.Context, kvs map[string]any) (map[string]any, error) {
        kvs["role"] = "cat"
        return kvs, nil
    })
    b2 := InvokableLambda(func(ctx context.Context, kvs map[string]any) (map[string]any, error) {
        kvs["role"] = "dog"
        return kvs, nil
    })

    // Parallel nodes
    parallel := NewParallel()
    parallel.
        AddLambda("role", InvokableLambda(func(ctx context.Context, kvs map[string]any) (string, error) {
            return kvs["role"].(string), nil
        })).
        AddLambda("input", InvokableLambda(func(ctx context.Context, kvs map[string]any) (string, error) {
            return "你的叫声是怎样的？", nil
        }))

    // Role play chain
    rolePlayChain := NewChain[map[string]any, *schema.Message]()
    rolePlayChain.
        AppendChatTemplate(prompt.FromMessages(schema.FString, 
            schema.SystemMessage(`You are a {role}.`), 
            schema.UserMessage(`{input}`))).
        AppendChatModel(cm)

    // Main chain
    chain := NewChain[map[string]any, string]()
    chain.
        AppendLambda(InvokableLambda(func(ctx context.Context, kvs map[string]any) (map[string]any, error) {
            return kvs, nil
        })).
        AppendBranch(NewChainBranch[map[string]any](branchCond).AddLambda("b1", b1).AddLambda("b2", b2)).
        AppendPassthrough().
        AppendParallel(parallel).
        AppendGraph(rolePlayChain).
        AppendLambda(InvokableLambda(func(ctx context.Context, m *schema.Message) (string, error) {
            return m.Content, nil
        }))

    r, _ := chain.Compile(context.Background())
    out, _ := r.Invoke(context.Background(), map[string]any{})
}
```

### Scala 3 Translation

```scala
def chainWithBranchAndParallel: IO[Unit] =
  for
    // Branch condition
    branchCondition = (input: Map[String, Any]) => IO {
      if Random.nextInt(2) == 1 then "b1" else "b2"
    }
    
    // Branch lambdas
    b1 = Lambda[Map[String, Any], Map[String, Any]](kvs => 
      IO.pure(kvs + ("role" -> "cat"))
    )
    b2 = Lambda[Map[String, Any], Map[String, Any]](kvs => 
      IO.pure(kvs + ("role" -> "dog"))
    )
    
    // Parallel nodes
    parallel = Parallel()
      .addLambda("role", Lambda(kvs => IO.pure(kvs("role").toString)))
      .addLambda("input", Lambda(_ => IO.pure("你的叫声是怎样的？")))
    
    // Role play chain
    rolePlayChain <- Chain[Map[String, Any], Message]()
      .appendChatTemplate(PromptTemplate.fromMessages(
        Message.system("You are a {role}."),
        Message.user("{input}")
      ))
      .appendChatModel(chatModel)
      .compile
    
    // Main chain
    mainChain <- Chain[Map[String, Any], String]()
      .appendLambda(Lambda(kvs => IO.pure(kvs)))
      .appendBranch(
        ChainBranch(branchCondition)
          .addLambda("b1", b1)
          .addLambda("b2", b2)
      )
      .appendPassthrough
      .appendParallel(parallel)
      .appendGraph(rolePlayChain)
      .appendLambda(Lambda(msg => IO.pure(msg.content)))
      .compile
    
    result <- mainChain.invoke(Map.empty)
  yield ()
```

### Workflows4s Branch and Parallel Implementation

**Workflows4s provides native branching and parallel execution**:

```scala
import workflows4s.wio.WIO
import cats.effect.IO
import scala.util.Random

// Branch with WIO.fork
val branchWorkflow: WIO[Map[String, Any], Nothing, Map[String, Any], Ctx.type] =
  WIO.fork[Map[String, Any], Map[String, Any], Ctx.type](
    condition = _ => Random.nextInt(2) == 1,
    ifTrue = WIO.runIO(kvs => IO.pure(kvs + ("role" -> "cat"))),
    ifFalse = WIO.runIO(kvs => IO.pure(kvs + ("role" -> "dog")))
  )

// Parallel execution with WIO.parallel
val parallelWorkflow: WIO[Map[String, Any], Nothing, (String, String), Ctx.type] =
  WIO.parallel(
    WIO.runIO[String, Ctx.type](kvs => IO.pure(kvs("role").toString)),
    WIO.runIO[String, Ctx.type](_ => IO.pure("你的叫声是怎样的？"))
  )

// Complete workflow composition
val rolePlayWorkflow: WIO[Map[String, Any], Nothing, String, Ctx.type] =
  branchWorkflow
    .flatMap { kvs =>
      parallelWorkflow.contramap(_ => kvs).flatMap { case (role, input) =>
        WIO.runIO[String, Ctx.type] { _ =>
          val client: LLMClient = LLMClient.create()
          val prompt: Conversation = Conversation(Vector(
            SystemMessage(s"You are a $role."),
            UserMessage(input)
          ))
          IO.fromEither(client.complete(prompt, CompletionOptions()).map(_.content))
        }
      }
    }
```

---

## Example 4: ReAct Agent

From `flow/agent/react/react.go` - Complete ReAct agent implementation.

```go
func NewAgent(ctx context.Context, config *AgentConfig) (*Agent, error) {
    // Get tool infos
    toolInfos, _ := genToolInfos(ctx, config.ToolsConfig)
    
    // Bind tools to model
    chatModel, _ := agent.ChatModelWithTools(config.Model, config.ToolCallingModel, toolInfos)
    
    // Create tools node
    toolsNode, _ := compose.NewToolNode(ctx, &config.ToolsConfig)

    // Create graph with state
    graph := compose.NewGraph[[]*schema.Message, *schema.Message](
        compose.WithGenLocalState(func(ctx context.Context) *state {
            return &state{Messages: make([]*schema.Message, 0, config.MaxStep+1)}
        }),
    )

    // Model pre-handler accumulates messages
    modelPreHandle := func(ctx context.Context, input []*schema.Message, state *state) ([]*schema.Message, error) {
        state.Messages = append(state.Messages, input...)
        if config.MessageModifier != nil {
            return config.MessageModifier(ctx, state.Messages), nil
        }
        return state.Messages, nil
    }

    graph.AddChatModelNode("chat", chatModel, compose.WithStatePreHandler(modelPreHandle))
    graph.AddEdge(compose.START, "chat")

    // Tools node pre-handler
    toolsNodePreHandle := func(ctx context.Context, input *schema.Message, state *state) (*schema.Message, error) {
        state.Messages = append(state.Messages, input)
        return input, nil
    }
    graph.AddToolsNode("tools", toolsNode, compose.WithStatePreHandler(toolsNodePreHandle))

    // Branch: if tool calls, go to tools; else end
    modelPostBranch := func(ctx context.Context, sr *schema.StreamReader[*schema.Message]) (string, error) {
        if isToolCall, _ := toolCallChecker(ctx, sr); isToolCall {
            return "tools", nil
        }
        return compose.END, nil
    }
    graph.AddBranch("chat", compose.NewStreamGraphBranch(modelPostBranch, 
        map[string]bool{"tools": true, compose.END: true}))

    // Tools branch: if return directly, end; else back to chat
    toolsBranch := func(ctx context.Context, msgsStream *schema.StreamReader[[]*schema.Message]) (string, error) {
        msgsStream.Close()
        var endNode string
        compose.ProcessState[*state](ctx, func(_ context.Context, state *state) error {
            if len(state.ReturnDirectlyToolCallID) > 0 {
                endNode = "direct_return"
            } else {
                endNode = "chat"
            }
            return nil
        })
        return endNode, nil
    }
    graph.AddBranch("tools", compose.NewStreamGraphBranch(toolsBranch, 
        map[string]bool{"chat": true, "direct_return": true}))

    runnable, _ := graph.Compile(ctx, 
        compose.WithMaxRunSteps(config.MaxStep), 
        compose.WithNodeTriggerMode(compose.AnyPredecessor))

    return &Agent{runnable: runnable, graph: graph}, nil
}

// Usage
agent, _ := react.NewAgent(ctx, &react.AgentConfig{
    ToolCallingModel: chatModel,
    ToolsConfig: compose.ToolsNodeConfig{
        Tools: []tool.BaseTool{weatherTool, calculatorTool},
    },
})

result, _ := agent.Generate(ctx, []*schema.Message{
    schema.UserMessage("What's the weather in Beijing?"),
})
```

### Scala 3 Translation

```scala
case class AgentState(
  messages: List[Message] = Nil,
  returnDirectlyToolCallId: Option[String] = None
)

def createReActAgent(
  chatModel: ToolCallingChatModel[IO],
  tools: List[Tool[IO]],
  maxSteps: Int = 12
): IO[Agent] =
  for
    // Get tool infos
    toolInfos <- tools.traverse(_.info)
    
    // Bind tools to model
    modelWithTools <- chatModel.withTools(toolInfos)
    
    // Create tools node
    toolsNode = ToolsNode(ToolsNodeConfig(tools))
    
    // Create graph with state
    stateRef <- Ref.of[IO, AgentState](AgentState())
    
    graph = Graph[List[Message], Message](stateRef)
    
    // Model pre-handler
    modelPreHandler: PreHandler[IO, List[Message], AgentState] = (input, state) =>
      state.modify { s =>
        val newMessages = s.messages ++ input
        (s.copy(messages = newMessages), newMessages)
      }
    
    // Build graph
    compiled <- graph
      .addChatModelNode("chat", modelWithTools, NodeConfig(preHandler = Some(modelPreHandler)))
      .flatMap(_.addEdge(START, "chat"))
      .flatMap(_.addToolsNode("tools", toolsNode))
      .flatMap(_.addBranch("chat", StreamBranch(
        condition = stream => stream.compile.last.map {
          case Some(msg) if msg.toolCalls.nonEmpty => "tools"
          case _ => END
        },
        endNodes = Set("tools", END)
      )))
      .flatMap(_.addBranch("tools", StreamBranch(
        condition = stream => stream.compile.drain *> stateRef.get.map { s =>
          if s.returnDirectlyToolCallId.isDefined then END else "chat"
        },
        endNodes = Set("chat", END)
      )))
      .flatMap(_.compile(GraphCompileConfig(
        maxRunSteps = maxSteps,
        nodeTriggerMode = NodeTriggerMode.AnyPredecessor
      )))
  yield Agent(compiled)

// Usage
val agent = createReActAgent(chatModel, List(weatherTool, calculatorTool))
val result = agent.flatMap(_.generate(List(Message.user("What's the weather in Beijing?"))))
```

### LLM4S Agent Implementation (Recommended)

**LLM4S provides a complete ReAct agent implementation out of the box**:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.tools.{ToolFunction, ToolBuilder, ToolRegistry, StringSchema}

// Define tools
val weatherTool: ToolFunction = ToolBuilder("get_weather")
  .description("Get current weather for a location")
  .parameter("location", StringSchema("City name"), required = true)
  .handler { params =>
    val location: String = params.getString("location").getOrElse("Unknown")
    Right(s"Weather in $location: Sunny, 25°C")
  }
  .build

val calculatorTool: ToolFunction = ToolBuilder("calculate")
  .description("Perform mathematical calculations")
  .parameter("expression", StringSchema("Math expression"), required = true)
  .handler { params =>
    val expr: String = params.getString("expression").getOrElse("0")
    Right(s"Result: ${eval(expr)}")
  }
  .build

// Create agent with tools
val client: LLMClient = LLMClient.create()
val registry: ToolRegistry = ToolRegistry(List(weatherTool, calculatorTool))

val agent: Agent = Agent(
  client = client,
  tools = registry,
  systemPrompt = Some("You are a helpful assistant with access to weather and calculator tools."),
  maxSteps = Some(12)
)

// Run agent - handles ReAct loop automatically
val result: Either[LLMError, AgentState] = agent.run("What's the weather in Beijing?")

// Or step-by-step for more control
def runWithLogging(query: String): IO[AgentState] =
  def loop(state: AgentState, step: Int): IO[AgentState] =
    IO.println(s"Step $step: ${state.status}") *>
    (state.status match
      case AgentStatus.Complete => IO.pure(state)
      case _ => IO.fromEither(agent.step(state)).flatMap(loop(_, step + 1))
    )
  
  IO.pure(agent.initialize(query)).flatMap(loop(_, 1))
```

> **Expert Note**: LLM4S `Agent` provides the same functionality as Eino's ReAct agent but with a simpler, more functional API. The agent handles tool calling, message accumulation, and the ReAct loop internally. Use step-by-step execution for debugging or custom control flow.

---

## Example 5: Workflow with Field Mapping

From `workflow_test.go` - Workflow with fine-grained field mapping.

```go
func TestWorkflow(t *testing.T) {
    type Input1 struct { Input string }
    type Output1 struct { Output string }
    type Input2 struct { Role schema.RoleType }
    type Output2 struct { Output string }
    type Input3 struct { Query string; MetaData string }

    lambda1 := func(ctx context.Context, in Input1) (Output1, error) {
        return Output1{Output: in.Input + "_processed"}, nil
    }
    lambda2 := func(ctx context.Context, in Input2) (Output2, error) {
        return Output2{Output: string(in.Role)}, nil
    }
    lambda3 := func(ctx context.Context, in Input3) (*schema.Message, error) {
        return schema.UserMessage(in.Query + " " + in.MetaData), nil
    }

    wf := NewWorkflow[[]*schema.Message, *schema.Message]()
    
    // Model node takes input from START
    wf.AddChatModelNode("model", m).AddInput(START)
    
    // Lambda1 maps model's Content to its Input
    wf.AddLambdaNode("lambda1", InvokableLambda(lambda1)).
        AddInput("model", MapFields("Content", "Input"))
    
    // Lambda2 maps model's Role to its Role
    wf.AddLambdaNode("lambda2", InvokableLambda(lambda2)).
        AddInput("model", MapFields("Role", "Role"))
    
    // Lambda3 combines outputs from lambda1 and lambda2
    wf.AddLambdaNode("lambda3", InvokableLambda(lambda3)).
        AddInput("lambda1", MapFields("Output", "Query")).
        AddInput("lambda2", MapFields("Output", "MetaData"))
    
    // End takes output from lambda3
    wf.End().AddInput("lambda3")

    runnable, _ := wf.Compile(ctx)
    result, _ := runnable.Invoke(ctx, []*schema.Message{
        schema.UserMessage("kick start this workflow!"),
    })
}
```

### Scala 3 Translation

```scala
case class Input1(input: String)
case class Output1(output: String)
case class Input2(role: RoleType)
case class Output2(output: String)
case class Input3(query: String, metaData: String)

def workflowWithFieldMapping: IO[Unit] =
  for
    lambda1 = Lambda[Input1, Output1](in => 
      IO.pure(Output1(in.input + "_processed"))
    )
    lambda2 = Lambda[Input2, Output2](in => 
      IO.pure(Output2(in.role.toString))
    )
    lambda3 = Lambda[Input3, Message](in =>
      IO.pure(Message.user(s"${in.query} ${in.metaData}"))
    )
    
    workflow = Workflow[List[Message], Message]()
    
    // Build workflow with field mappings
    compiled <- workflow
      .addChatModelNode("model", chatModel)
      .addInput(START)
      .addLambdaNode("lambda1", lambda1)
      .addInput("model", FieldMapping("content", "input"))
      .addLambdaNode("lambda2", lambda2)
      .addInput("model", FieldMapping("role", "role"))
      .addLambdaNode("lambda3", lambda3)
      .addInput("lambda1", FieldMapping("output", "query"))
      .addInput("lambda2", FieldMapping("output", "metaData"))
      .end
      .addInput("lambda3")
      .compile
    
    result <- compiled.invoke(List(Message.user("kick start this workflow!")))
  yield ()
```

### Workflows4s + structured-llm Implementation

**Workflows4s provides type-safe data flow without string-based field mapping**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}
import org.adk4s.structured.core.{StructuredLLM, Schema, Prompt}
import org.llm4s.llmconnect.LLMClient
import cats.effect.IO

// Define typed intermediate structures
case class ModelOutput(content: String, role: String)
case class ProcessedContent(output: String)
case class ProcessedRole(output: String)
case class CombinedInput(query: String, metaData: String)

given Schema[ModelOutput] = Schema.derived[ModelOutput]

object FieldMappingContext extends WorkflowContext:
  override type State = WorkflowState
  override type Event = WorkflowEvent

case class WorkflowState(modelOutput: Option[ModelOutput] = None)
sealed trait WorkflowEvent
case class ModelOutputReceived(output: ModelOutput) extends WorkflowEvent

def fieldMappingWorkflow: WIO[List[Message], Nothing, Message, FieldMappingContext.type] =
  val client: LLMClient = LLMClient.create()
  val structured: StructuredLLM[IO] = StructuredLLM.fromClient[IO](client)
  
  // Step 1: Call LLM and get structured output
  val callModel: WIO[List[Message], Nothing, ModelOutput, FieldMappingContext.type] =
    WIO.runIO[ModelOutput, FieldMappingContext.type] { messages =>
      val prompt: Prompt = Prompt(messages.map(m => 
        org.adk4s.structured.core.Message(Role.User, m.content)
      ).toVector)
      structured.complete[ModelOutput](prompt)
    }.named("call_model")
  
  // Step 2: Process content (lambda1 equivalent)
  val processContent: WIO[ModelOutput, Nothing, ProcessedContent, FieldMappingContext.type] =
    WIO.runIO[ProcessedContent, FieldMappingContext.type] { modelOutput =>
      IO.pure(ProcessedContent(modelOutput.content + "_processed"))
    }.named("process_content")
  
  // Step 3: Process role (lambda2 equivalent)
  val processRole: WIO[ModelOutput, Nothing, ProcessedRole, FieldMappingContext.type] =
    WIO.runIO[ProcessedRole, FieldMappingContext.type] { modelOutput =>
      IO.pure(ProcessedRole(modelOutput.role))
    }.named("process_role")
  
  // Step 4: Combine outputs (lambda3 equivalent) - using parallel
  val combineAndFinish: WIO[ModelOutput, Nothing, Message, FieldMappingContext.type] =
    WIO.parallel(processContent, processRole)
      .flatMap { case (processed, role) =>
        WIO.runIO[Message, FieldMappingContext.type] { _ =>
          IO.pure(UserMessage(s"${processed.output} ${role.output}"))
        }
      }.named("combine_outputs")
  
  // Compose the workflow
  callModel.flatMap(combineAndFinish)
```

> **Expert Note**: Workflows4s eliminates string-based field mapping by using typed case classes. The type system ensures field compatibility at compile time. Use `WIO.parallel` to process multiple fields from the same source concurrently.

---

## Example 6: State Management

From `state_test.go` - Using state across nodes.

```go
func TestStateGraph(t *testing.T) {
    type MyState struct {
        Counter int
        History []string
    }

    graph := NewGraph[string, string](
        WithGenLocalState(func(ctx context.Context) *MyState {
            return &MyState{Counter: 0, History: []string{}}
        }),
    )

    // Node that increments counter
    incrementer := InvokableLambda(func(ctx context.Context, input string) (string, error) {
        err := compose.ProcessState[*MyState](ctx, func(ctx context.Context, s *MyState) error {
            s.Counter++
            s.History = append(s.History, input)
            return nil
        })
        return fmt.Sprintf("processed: %s (count: %d)", input, counter), err
    })

    graph.AddLambdaNode("increment", incrementer)
    graph.AddEdge(START, "increment")
    graph.AddEdge("increment", END)

    r, _ := graph.Compile(ctx)
    result, _ := r.Invoke(ctx, "hello")
    // result: "processed: hello (count: 1)"
}
```

### Scala 3 Translation

```scala
case class MyState(counter: Int = 0, history: List[String] = Nil)

def stateGraphExample: IO[Unit] =
  for
    stateRef <- Ref.of[IO, MyState](MyState())
    
    incrementer = Lambda[String, String] { input =>
      stateRef.modify { s =>
        val newState = s.copy(
          counter = s.counter + 1,
          history = s.history :+ input
        )
        (newState, s"processed: $input (count: ${newState.counter})")
      }
    }
    
    compiled <- Graph[String, String](stateRef)
      .addLambdaNode("increment", incrementer)
      .flatMap(_.addEdge(START, "increment"))
      .flatMap(_.addEdge("increment", END))
      .flatMap(_.compile)
    
    result <- compiled.invoke("hello")
    _ <- IO.println(result) // "processed: hello (count: 1)"
  yield ()
```

### Workflows4s Event-Sourced State Implementation

**Workflows4s provides event-sourced state management**:

```scala
import workflows4s.wio.{WIO, WorkflowContext}
import cats.effect.IO

// Define state and events
case class CounterState(counter: Int = 0, history: List[String] = Nil)

sealed trait CounterEvent
case class Incremented(input: String, newCount: Int) extends CounterEvent

object CounterContext extends WorkflowContext:
  override type State = CounterState
  override type Event = CounterEvent
  
  // Event handler updates state
  def applyEvent(state: CounterState, event: CounterEvent): CounterState =
    event match
      case Incremented(input, newCount) =>
        state.copy(counter = newCount, history = state.history :+ input)

def stateWorkflow: WIO[String, Nothing, String, CounterContext.type] =
  WIO.runIO[String, CounterContext.type] { (input, state) =>
    val newCount: Int = state.counter + 1
    val result: String = s"processed: $input (count: $newCount)"
    IO.pure(result)
  }
  .emitEvent { (input, state) => 
    Incremented(input, state.counter + 1) 
  }
  .named("increment")

// Usage
val runtime: WorkflowRuntime[IO, CounterContext.type] = 
  InMemorySyncRuntime.create[CounterContext.type]

val result: IO[String] = runtime.run(stateWorkflow, "hello")
// result: "processed: hello (count: 1)"
// State is persisted via events - can be replayed for recovery
```

### LLM4S AgentState for Conversation State

**LLM4S Agent manages conversation state immutably**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.tools.ToolRegistry

val agent: Agent = Agent(
  client = LLMClient.create(),
  tools = ToolRegistry(List(myTool)),
  systemPrompt = Some("You are helpful")
)

// AgentState tracks conversation history automatically
val state0: AgentState = agent.initialize("hello")
// state0.conversation.messages contains the initial message
// state0.stepCount = 0

val state1: Either[LLMError, AgentState] = agent.step(state0)
// state1.conversation.messages now includes LLM response
// state1.stepCount = 1

// State is immutable - each step returns new state
// History is automatically accumulated in conversation
```

> **Expert Note**: Workflows4s uses event sourcing for durable, auditable state. LLM4S AgentState is immutable and tracks conversation history automatically. Choose Workflows4s for complex workflows needing persistence; use LLM4S AgentState for agent-based conversation flows.

---

## Example 7: Callbacks/Tracing

From `graph_test.go` - Adding callbacks for observability.

```go
func TestGraphWithCallbacks(t *testing.T) {
    graph := NewGraph[string, string]()
    // ... build graph ...

    handler := callbacks.NewHandlerBuilder().
        OnStartFn(func(ctx context.Context, info *callbacks.RunInfo, input callbacks.CallbackInput) context.Context {
            log.Printf("Starting %s (%s)", info.Name, info.Component)
            return ctx
        }).
        OnEndFn(func(ctx context.Context, info *callbacks.RunInfo, output callbacks.CallbackOutput) context.Context {
            log.Printf("Finished %s", info.Name)
            return ctx
        }).
        OnErrorFn(func(ctx context.Context, info *callbacks.RunInfo, err error) context.Context {
            log.Printf("Error in %s: %v", info.Name, err)
            return ctx
        }).
        Build()

    r, _ := graph.Compile(ctx)
    
    // Run with callbacks
    result, _ := r.Invoke(ctx, "input", WithCallbacks(handler))
    
    // Node-specific callbacks
    result, _ = r.Invoke(ctx, "input", WithCallbacks(handler).DesignateNode("model"))
}
```

### Scala 3 Translation

```scala
def callbacksExample: IO[Unit] =
  for
    // Build callback handler
    handler = CallbackHandlerBuilder[IO]()
      .withOnStart { (info, input) =>
        IO.println(s"Starting ${info.name} (${info.component})")
      }
      .withOnEnd { (info, output) =>
        IO.println(s"Finished ${info.name}")
      }
      .withOnError { (info, error) =>
        IO.println(s"Error in ${info.name}: ${error.getMessage}")
      }
      .build
    
    // Build and compile graph
    compiled <- buildGraph.compile
    
    // Run with callbacks
    result <- compiled.invoke("input", InvokeConfig(callbacks = List(handler)))
    
    // Node-specific callbacks
    result2 <- compiled.invoke("input", InvokeConfig(
      callbacks = List(handler),
      designatedNodes = Set("model")
    ))
  yield ()
```

### LLM4S Step-by-Step Tracing Implementation

**LLM4S provides observability through step-by-step agent execution**:

```scala
import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.tools.ToolRegistry
import cats.effect.IO

// Define a tracing observer
trait AgentObserver:
  def onStepStart(step: Int, state: AgentState): IO[Unit]
  def onStepEnd(step: Int, state: AgentState): IO[Unit]
  def onToolCall(toolName: String, args: String): IO[Unit]
  def onComplete(state: AgentState): IO[Unit]
  def onError(step: Int, error: LLMError): IO[Unit]

// Logging observer implementation
val loggingObserver: AgentObserver = new AgentObserver:
  def onStepStart(step: Int, state: AgentState): IO[Unit] =
    IO.println(s"Starting step $step (status: ${state.status})")
  
  def onStepEnd(step: Int, state: AgentState): IO[Unit] =
    IO.println(s"Finished step $step (messages: ${state.conversation.messages.size})")
  
  def onToolCall(toolName: String, args: String): IO[Unit] =
    IO.println(s"Tool call: $toolName($args)")
  
  def onComplete(state: AgentState): IO[Unit] =
    IO.println(s"Agent completed after ${state.stepCount} steps")
  
  def onError(step: Int, error: LLMError): IO[Unit] =
    IO.println(s"Error at step $step: ${error.formatted}")

// Run agent with observer
def runWithObserver(agent: Agent, query: String, observer: AgentObserver): IO[AgentState] =
  def loop(state: AgentState, step: Int): IO[AgentState] =
    for
      _ <- observer.onStepStart(step, state)
      // Log any pending tool calls
      _ <- state.pendingToolCalls.traverse_ { tc =>
        observer.onToolCall(tc.function.name, tc.function.arguments)
      }
      result <- IO.fromEither(agent.step(state)).attempt
      finalState <- result match
        case Right(newState) =>
          observer.onStepEnd(step, newState) *>
          (newState.status match
            case AgentStatus.Complete => 
              observer.onComplete(newState) *> IO.pure(newState)
            case _ => 
              loop(newState, step + 1)
          )
        case Left(error: LLMError) =>
          observer.onError(step, error) *> IO.raiseError(error)
        case Left(other) =>
          IO.raiseError(other)
    yield finalState
  
  loop(agent.initialize(query), 1)

// Usage
val agent: Agent = Agent(client, tools, Some("You are helpful"))
val result: IO[AgentState] = runWithObserver(agent, "What's the weather?", loggingObserver)
```

### Workflows4s Interceptor Implementation

**Workflows4s provides hooks via WorkflowInstanceEngine**:

```scala
import workflows4s.wio.WIO
import workflows4s.runtime.{WorkflowRuntime, WorkflowInstanceEngine}
import cats.effect.IO

// Define workflow with named steps for tracing
val tracedWorkflow: WIO[String, Nothing, String, Ctx.type] =
  WIO.runIO[String, Ctx.type] { input =>
    IO.println(s"Step 1: Processing $input") *>
    IO.pure(input.toUpperCase)
  }.named("step1_process")
  .flatMap { processed =>
    WIO.runIO[String, Ctx.type] { _ =>
      IO.println(s"Step 2: Formatting $processed") *>
      IO.pure(s"Result: $processed")
    }.named("step2_format")
  }

// Create runtime with logging hooks
trait TracingEngine[F[_]] extends WorkflowInstanceEngine[F]:
  def beforeStep(stepName: String): F[Unit]
  def afterStep(stepName: String): F[Unit]
  def onError(stepName: String, error: Throwable): F[Unit]

val tracingEngine: TracingEngine[IO] = new TracingEngine[IO]:
  def beforeStep(stepName: String): IO[Unit] = 
    IO.println(s"[TRACE] Starting: $stepName")
  def afterStep(stepName: String): IO[Unit] = 
    IO.println(s"[TRACE] Completed: $stepName")
  def onError(stepName: String, error: Throwable): IO[Unit] = 
    IO.println(s"[TRACE] Error in $stepName: ${error.getMessage}")

// Run with tracing
val result: IO[String] = runtime.run(tracedWorkflow, "hello")
// Output:
// [TRACE] Starting: step1_process
// Step 1: Processing hello
// [TRACE] Completed: step1_process
// [TRACE] Starting: step2_format
// Step 2: Formatting HELLO
// [TRACE] Completed: step2_format
```

> **Expert Note**: LLM4S step-by-step execution provides fine-grained control for agent tracing. Workflows4s named steps (`WIO.named()`) enable workflow-level observability. Both approaches are more type-safe than string-based callback registration.
