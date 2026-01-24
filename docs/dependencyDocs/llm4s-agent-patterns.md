# LLM4S Agent Patterns

## Overview

The llm4s agent system provides orchestration for multi-step LLM reasoning with automatic tool execution. Agents manage conversation state, execute tools, and iterate until completion.

**Key Features**:
- Immutable state management
- Step-by-step or automatic execution
- Built-in tracing and logging
- Status tracking (InProgress, WaitingForTools, Complete, Failed)
- Markdown trace generation

---

## Core Components

### Agent Class

**Purpose**: Orchestrates multi-step LLM interactions with tools.

```scala
class Agent(client: LLMClient) {
  /** Initialize new agent state with query and tools */
  def initialize(
    query: String,
    tools: ToolRegistry,
    systemPromptAddition: Option[String] = None
  ): AgentState
  
  /** Execute a single step of reasoning */
  def runStep(state: AgentState): Either[LLMError, AgentState]
  
  /** Run agent until completion or max steps */
  def run(
    initialState: AgentState,
    maxSteps: Option[Int] = None,
    traceLogPath: Option[String] = None
  ): Either[LLMError, AgentState]
  
  /** Write execution trace to markdown file */
  def writeTraceLog(state: AgentState, traceLogPath: String): Unit
  
  /** Format state as markdown for debugging */
  def formatStateAsMarkdown(state: AgentState): String
}
```

**Constructor**: Takes a single `LLMClient` for all interactions.

---

### AgentState

**Purpose**: Immutable snapshot of agent execution state.

```scala
case class AgentState(
  conversation: Conversation,           // Message history
  tools: ToolRegistry,                  // Available tools
  userQuery: String,                    // Original query
  status: AgentStatus = AgentStatus.InProgress,  // Current status
  logs: Seq[String] = Seq.empty        // Execution logs
) {
  def addMessage(message: Message): AgentState
  def addMessages(messages: Seq[Message]): AgentState
  def log(entry: String): AgentState
  def withStatus(newStatus: AgentStatus): AgentState
  def dump(): Unit  // Print debug information
}
```

**Immutability**: All methods return new `AgentState` instances.

---

### AgentStatus

**Purpose**: Track agent execution phase.

```scala
sealed trait AgentStatus

object AgentStatus {
  case object InProgress extends AgentStatus       // Requesting LLM completion
  case object WaitingForTools extends AgentStatus  // Executing tools
  case object Complete extends AgentStatus         // Successfully finished
  case class Failed(error: String) extends AgentStatus  // Error occurred
}
```

**State machine**:
```
InProgress → WaitingForTools → InProgress → Complete/Failed
     ↑______________|
```

---

## Agent Execution Model

### Initialization

```scala
val agent = new Agent(LLM.client())
val toolRegistry = new ToolRegistry(Seq(weatherTool, calculatorTool))

val initialState = agent.initialize(
  query = "What's the weather in Paris and calculate 5+3?",
  tools = toolRegistry,
  systemPromptAddition = Some("You are a helpful travel assistant.")
)
```

**Generated system prompt**:
```
You are a helpful assistant with access to tools.
Follow these steps:
1. Analyze the user's question and determine which tools you need to use
2. Use the necessary tools to find the information needed
3. When you have enough information, provide a helpful final answer
4. Think step by step and be thorough

You are a helpful travel assistant.
```

---

### Step-by-Step Execution

**Pattern**: Manual control over each step for debugging.

```scala
var state = agent.initialize(query, toolRegistry)

// Write initial trace
agent.writeTraceLog(state, "agent-trace.md")

var stepCount = 0
while (state.status == AgentStatus.InProgress && stepCount < 10) {
  println(s"Executing step ${stepCount + 1}...")
  
  agent.runStep(state) match {
    case Right(newState) =>
      state = newState
      println(s"Status: ${state.status}")
      
      // Log most recent message
      state.conversation.messages.lastOption.foreach { msg =>
        println(s"${msg.role}: ${msg.content.take(100)}...")
      }
      
      // Update trace
      agent.writeTraceLog(state, "agent-trace.md")
    
    case Left(error) =>
      println(s"Error: ${error.formatted}")
      state = state.withStatus(AgentStatus.Failed(error.toString))
      agent.writeTraceLog(state, "agent-trace.md")
  }
  
  stepCount += 1
}

// Check final status
state.status match {
  case AgentStatus.Complete =>
    println("Agent completed successfully!")
    println(state.conversation.messages.last.content)
  
  case AgentStatus.Failed(error) =>
    println(s"Agent failed: $error")
  
  case _ =>
    println(s"Agent stopped with status: ${state.status}")
}

// Dump full state for debugging
state.dump()
```

---

### Automatic Execution

**Pattern**: Run until completion with built-in iteration.

```scala
val agent = new Agent(LLM.client())
val toolRegistry = new ToolRegistry(Seq(weatherTool, searchTool))

val initialState = agent.initialize(
  query = "Research the weather in Tokyo and find hotels",
  tools = toolRegistry
)

agent.run(
  initialState = initialState,
  maxSteps = Some(15),
  traceLogPath = Some("logs/agent-execution.md")
) match {
  case Right(finalState) =>
    finalState.status match {
      case AgentStatus.Complete =>
        println("Success!")
        val finalAnswer = finalState.conversation.messages
          .reverse
          .collectFirst { case msg: AssistantMessage => msg.content }
          .getOrElse("No response")
        println(finalAnswer)
      
      case AgentStatus.Failed(error) =>
        println(s"Failed: $error")
      
      case other =>
        println(s"Unexpected status: $other")
    }
  
  case Left(error) =>
    println(s"Error: ${error.formatted}")
}
```

---

### Overloaded run Method

**Pattern**: Initialize and run in one call.

```scala
agent.run(
  query = "Calculate 10 * 5 and search for that many facts about Scala",
  tools = toolRegistry,
  maxSteps = Some(10),
  traceLogPath = Some("trace.md"),
  systemPromptAddition = Some("Be concise and factual.")
) match {
  case Right(finalState) =>
    // Handle completion
  case Left(error) =>
    // Handle error
}
```

---

## Execution Flow

### Phase 1: LLM Reasoning (InProgress)

```scala
// Agent calls LLM with tools in CompletionOptions
val options = CompletionOptions(tools = state.tools.tools)
client.complete(state.conversation, options) match {
  case Right(completion) =>
    val updatedState = state
      .log(s"[assistant] ${completion.message.content}")
      .addMessage(completion.message)
    
    if (completion.message.toolCalls.nonEmpty) {
      // Transition to WaitingForTools
      Right(updatedState.withStatus(AgentStatus.WaitingForTools))
    } else {
      // No tools needed, mark complete
      Right(updatedState.withStatus(AgentStatus.Complete))
    }
  
  case Left(error) =>
    Left(error)
}
```

### Phase 2: Tool Execution (WaitingForTools)

```scala
// Extract tool calls from last assistant message
val assistantMessage = state.conversation.messages.reverse
  .collectFirst { case msg: AssistantMessage if msg.toolCalls.nonEmpty => msg }
  .get

// Execute each tool
val toolMessages = assistantMessage.toolCalls.map { toolCall =>
  val startTime = System.currentTimeMillis()
  val request = ToolCallRequest(toolCall.name, toolCall.arguments)
  val result = state.tools.execute(request)
  val duration = System.currentTimeMillis() - startTime
  
  val content = result match {
    case Right(json) => json.render()
    case Left(error) => s"""{"error": "$error"}"""
  }
  
  state.log(s"[tool] ${toolCall.name} (${duration}ms): $content")
  ToolMessage(toolCall.id, content)
}

// Add tool results to conversation
val newState = state
  .addMessages(toolMessages)
  .withStatus(AgentStatus.InProgress)  // Back to reasoning phase

Right(newState)
```

### Phase 3: Completion

```scala
// LLM returns message with no tool calls
val finalState = state.withStatus(AgentStatus.Complete)
Right(finalState)
```

---

## Tracing and Logging

### In-Memory Logs

**Purpose**: Track execution without file I/O.

```scala
val state = agent.initialize(query, toolRegistry)

// Logs are accumulated in state.logs
state.logs.foreach(println)

// Examples of log entries:
// [assistant] text: Let me check the weather for you
// [assistant] tools: 1 tool call requested (get_weather)
// [tools] executing 1 tools (get_weather)
// [tool] get_weather (234ms): {"temperature": 22, "conditions": "sunny"}
```

---

### Markdown Trace Files

**Purpose**: Detailed execution trace for debugging.

```scala
agent.writeTraceLog(state, "execution-trace.md")
```

**Generated markdown**:
```markdown
# Agent Execution Trace

**Query:** What's the weather in Paris?
**Status:** Complete
**Tools Available:** get_weather, calculator

## Conversation Flow

### Step 1: System Message

```
You are a helpful assistant with access to tools...
```

### Step 2: User Message

What's the weather in Paris?

### Step 3: Assistant Message

Let me check the weather for you.

**Tool Calls:**

Tool: **get_weather**

Arguments:
```json
{"location": "Paris, France", "units": "celsius"}
```

### Step 4: Tool Response

Tool Call ID: `call_abc123`

Result:
```json
{"temperature": 22, "conditions": "Sunny", "humidity": 65}
```

### Step 5: Assistant Message

The weather in Paris is currently sunny with a temperature of 22°C and 65% humidity.

## Execution Logs

1. **Assistant:** text: Let me check the weather for you
2. **Assistant:** tools: 1 tool calls requested (get_weather)
3. **Tools:** executing 1 tools (get_weather)
4. **Tool Output:** get_weather (245ms): {"temperature": 22, ...}
5. **Assistant:** text: The weather in Paris is currently...
```

---

### Debug Dump

**Purpose**: Console output for quick debugging.

```scala
state.dump()
```

**Output**:
```
================================================================================
AGENT STATE DUMP - Status: Complete
================================================================================
User Query: What's the weather in Paris?
Available Tools: get_weather, calculator
================================================================================
CONVERSATION FLOW:
================================================================================
STEP 1: ⚙️ SYSTEM
Content: You are a helpful assistant with access to tools...
================================================================================
STEP 2: 👤 USER
Content: What's the weather in Paris?
================================================================================
STEP 3: 🤖 ASSISTANT
Content: Let me check the weather for you.
Tool Calls:
  - ID: call_abc123
    Tool: get_weather
    Args: {"location":"Paris, France","units":"celsius"}
================================================================================
STEP 4: 🛠️ TOOL
Tool Call ID: call_abc123
Result: {"temperature":22,"conditions":"Sunny","humidity":65}
================================================================================
STEP 5: 🤖 ASSISTANT
Content: The weather in Paris is currently sunny...
================================================================================
EXECUTION LOGS:
1. [assistant] text: Let me check the weather for you
2. [assistant] tools: 1 tool calls requested (get_weather)
3. [tools] executing 1 tools (get_weather)
4. [tool] get_weather (245ms): {"temperature":22,...}
5. [assistant] text: The weather in Paris is currently...
================================================================================
END OF AGENT STATE DUMP - Status: Complete
================================================================================
```

---

## Advanced Patterns

### Pattern 1: Custom System Prompt

```scala
val customPrompt = """
You are a financial advisor with expertise in:
- Investment strategies
- Risk management
- Portfolio optimization

Always:
1. Ask clarifying questions if needed
2. Provide conservative recommendations
3. Cite data sources when using tools
4. Explain your reasoning
""".trim

val state = agent.initialize(
  query = "Should I invest in tech stocks?",
  tools = toolRegistry,
  systemPromptAddition = Some(customPrompt)
)
```

---

### Pattern 2: Max Steps with Graceful Handling

```scala
agent.run(initialState, maxSteps = Some(5)) match {
  case Right(finalState) =>
    finalState.status match {
      case AgentStatus.Complete =>
        println("Completed successfully")
      
      case AgentStatus.Failed("Maximum step limit reached") =>
        println("Agent ran out of steps, but here's what we got:")
        val partialAnswer = finalState.conversation.messages
          .reverse
          .collectFirst { case msg: AssistantMessage => msg.content }
        partialAnswer.foreach(println)
      
      case AgentStatus.Failed(other) =>
        println(s"Failed: $other")
      
      case _ =>
        println("Unexpected status")
    }
  
  case Left(error) =>
    println(error.formatted)
}
```

---

### Pattern 3: Streaming Logs

```scala
import java.io.PrintWriter
import java.nio.file.{Files, Paths, StandardOpenOption}

val logFile = "logs/agent-stream.log"
Files.createDirectories(Paths.get("logs"))

var state = agent.initialize(query, toolRegistry)

def appendLog(entry: String): Unit = {
  val writer = new PrintWriter(Files.newOutputStream(
    Paths.get(logFile),
    StandardOpenOption.CREATE,
    StandardOpenOption.APPEND
  ))
  try {
    writer.println(s"[${java.time.Instant.now}] $entry")
  } finally {
    writer.close()
  }
}

while (state.status == AgentStatus.InProgress) {
  agent.runStep(state) match {
    case Right(newState) =>
      // Log new entries
      newState.logs.drop(state.logs.size).foreach(appendLog)
      state = newState
    
    case Left(error) =>
      appendLog(s"ERROR: ${error.formatted}")
      state = state.withStatus(AgentStatus.Failed(error.toString))
  }
}
```

---

### Pattern 4: Multi-Agent Coordination

```scala
case class AgentResult(agent: String, state: AgentState)

def runMultipleAgents(
  query: String,
  agentConfigs: Seq[(String, ToolRegistry, String)]  // (name, tools, systemPrompt)
): Seq[AgentResult] = {
  val client = LLM.client()
  
  agentConfigs.map { case (name, tools, systemPrompt) =>
    val agent = new Agent(client)
    val initialState = agent.initialize(query, tools, Some(systemPrompt))
    
    val finalState = agent.run(initialState, maxSteps = Some(10)) match {
      case Right(state) => state
      case Left(error) =>
        initialState.withStatus(AgentStatus.Failed(error.toString))
    }
    
    AgentResult(name, finalState)
  }
}

// Usage
val results = runMultipleAgents(
  query = "Research travel options to Tokyo",
  agentConfigs = Seq(
    ("WeatherAgent", weatherTools, "You specialize in weather analysis"),
    ("HotelAgent", hotelTools, "You specialize in accommodation"),
    ("FlightAgent", flightTools, "You specialize in flight booking")
  )
)

results.foreach { result =>
  println(s"${result.agent}: ${result.state.status}")
  if (result.state.status == AgentStatus.Complete) {
    println(result.state.conversation.messages.last.content)
  }
}
```

---

### Pattern 5: Retry with Different Temperature

```scala
def runWithFallback(
  query: String,
  tools: ToolRegistry,
  temperatures: Seq[Double] = Seq(0.7, 0.3, 0.0)
): Either[LLMError, AgentState] = {
  temperatures.foldLeft[Either[LLMError, AgentState]](
    Left(LLMError.ValidationError("No temperatures provided", "temperatures"))
  ) { (result, temp) =>
    result match {
      case Right(state) if state.status == AgentStatus.Complete =>
        // Already succeeded
        result
      
      case _ =>
        // Try with this temperature
        println(s"Attempting with temperature=$temp")
        val clientWithTemp = LLM.client()  // Would need to support temp override
        val agent = new Agent(clientWithTemp)
        agent.run(
          query = query,
          tools = tools,
          maxSteps = Some(10),
          traceLogPath = None,
          systemPromptAddition = None
        )
    }
  }
}
```

---

### Pattern 6: Stateful External Context

```scala
class ContextualAgent(client: LLMClient, externalContext: collection.mutable.Map[String, String]) {
  private val agent = new Agent(client)
  
  def run(query: String, tools: ToolRegistry): Either[LLMError, AgentState] = {
    // Inject external context into system prompt
    val contextPrompt = externalContext.map { case (k, v) =>
      s"$k: $v"
    }.mkString("\n")
    
    val systemAddition = s"""
      |Additional context:
      |$contextPrompt
      |
      |Use this context when relevant to the query.
    """.stripMargin
    
    val initialState = agent.initialize(query, tools, Some(systemAddition))
    
    agent.run(initialState, maxSteps = Some(15)).map { finalState =>
      // Extract and store any learned context
      finalState.conversation.messages.collect {
        case AssistantMessage(Some(content), _) =>
          // Parse for key insights and update context
          // (simplified example)
          if (content.contains("learned:")) {
            // Extract and store
          }
      }
      
      finalState
    }
  }
}

// Usage
val context = collection.mutable.Map(
  "user_preference" -> "prefers budget options",
  "previous_destination" -> "Paris"
)

val contextualAgent = new ContextualAgent(LLM.client(), context)
contextualAgent.run("Plan a trip to Rome", toolRegistry)
```

---

## Integration with Cats Effect

**Current state**: llm4s does not use Cats Effect natively.

**Manual integration pattern**:

```scala
import cats.effect.IO

def runAgentIO(
  agent: Agent,
  query: String,
  tools: ToolRegistry
): IO[AgentState] = {
  IO {
    agent.run(
      query = query,
      tools = tools,
      maxSteps = Some(10),
      traceLogPath = None,
      systemPromptAddition = None
    )
  }.flatMap {
    case Right(state) => IO.pure(state)
    case Left(error) => IO.raiseError(new RuntimeException(error.formatted))
  }
}

// Usage
val program: IO[Unit] = for {
  state <- runAgentIO(agent, "What's the weather?", toolRegistry)
  _ <- IO.println(s"Status: ${state.status}")
  _ <- state.status match {
    case AgentStatus.Complete =>
      IO.println("Success!")
    case AgentStatus.Failed(error) =>
      IO.println(s"Failed: $error")
    case other =>
      IO.println(s"Unexpected: $other")
  }
} yield ()

// Run
import cats.effect.unsafe.implicits.global
program.unsafeRunSync()
```

---

## Best Practices

### 1. Always Set Max Steps
```scala
// Good - prevents infinite loops
agent.run(initialState, maxSteps = Some(20))

// Risky - could loop forever
agent.run(initialState, maxSteps = None)
```

### 2. Enable Tracing for Debugging
```scala
// During development
agent.run(
  initialState,
  maxSteps = Some(10),
  traceLogPath = Some("debug-trace.md")  // Easy to inspect
)

// In production
agent.run(
  initialState,
  maxSteps = Some(10),
  traceLogPath = None  // Skip file I/O
)
```

### 3. Check Status After Execution
```scala
agent.run(initialState) match {
  case Right(finalState) =>
    finalState.status match {
      case AgentStatus.Complete =>
        // Extract result
      case AgentStatus.Failed(error) =>
        // Handle failure
      case other =>
        // Unexpected status
    }
  case Left(error) =>
    // LLM error
}
```

### 4. Use Step-by-Step for Complex Debugging
```scala
// When agent behaves unexpectedly, use step-by-step
var state = agent.initialize(query, toolRegistry)

while (state.status == AgentStatus.InProgress) {
  println(s"\n--- Step ${state.logs.size + 1} ---")
  state.conversation.messages.lastOption.foreach(msg => println(s"Last: ${msg.role}"))
  
  val input = scala.io.StdIn.readLine("Press Enter to continue, or 'q' to quit: ")
  if (input == "q") {
    state = state.withStatus(AgentStatus.Failed("User cancelled"))
  } else {
    agent.runStep(state) match {
      case Right(newState) => state = newState
      case Left(error) =>
        println(s"Error: ${error.formatted}")
        state = state.withStatus(AgentStatus.Failed(error.toString))
    }
  }
}
```

### 5. Provide Domain-Specific System Prompts
```scala
// Generic (less effective)
agent.initialize(query, tools)

// Domain-specific (more effective)
agent.initialize(
  query = query,
  tools = tools,
  systemPromptAddition = Some("""
    You are a travel planning specialist.
    Always consider:
    - Budget constraints
    - Travel dates and seasonality
    - User preferences (if mentioned)
    
    Format recommendations as:
    1. Option name
    2. Key details
    3. Why it's recommended
  """.trim)
)
```

---

## Common Gotchas

### 1. Status Can Be InProgress After run()
```scala
// Agent can end in InProgress if max steps reached
agent.run(initialState, maxSteps = Some(2)) match {
  case Right(state) =>
    // Don't assume Complete!
    state.status match {
      case AgentStatus.Complete => // expected
      case AgentStatus.Failed(_) => // handle
      case AgentStatus.InProgress => // max steps reached
      case AgentStatus.WaitingForTools => // shouldn't happen but check
    }
  case Left(error) => // LLM error
}
```

### 2. Agent Doesn't Retry Failed Tool Calls
```scala
// If a tool fails, agent receives error in ToolMessage
// Agent may or may not retry based on LLM's decision
// To force retry, catch ToolCallError and retry manually
```

### 3. Trace File Overwrites Each Time
```scala
// Each writeTraceLog overwrites the file
agent.writeTraceLog(state, "trace.md")  // Creates/overwrites
agent.writeTraceLog(state, "trace.md")  // Overwrites again

// Use unique filenames if you want history
agent.writeTraceLog(state, s"trace-${System.currentTimeMillis()}.md")
```

### 4. State is Immutable
```scala
// Wrong - doesn't update state
agent.runStep(state)
println(state.status)  // Still old status

// Correct
state = agent.runStep(state) match {
  case Right(newState) => newState
  case Left(error) =>
    state.withStatus(AgentStatus.Failed(error.toString))
}
```

---

## Next Steps

- **Usage Examples**: See `llm4s-usage-examples.md` for complete runnable code
- **Best Practices**: See `llm4s-best-practices.md` for more tips
- **Core API**: See `llm4s-core-api.md` for foundational concepts
