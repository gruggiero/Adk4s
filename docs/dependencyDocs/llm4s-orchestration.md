# LLM4S Multi-Agent Orchestration

## Overview

LLM4S provides multi-agent orchestration in `org.llm4s.agent.orchestration` and agent handoff in `org.llm4s.agent.Handoff`. It supports typed DAG-based execution plans, parallel node execution, retry/timeout/fallback policies, cancellation, and LLM-driven agent delegation.

**Key Features**:
- Typed DAG with compile-time type safety on edges
- Topological ordering and parallel execution
- Retry, timeout, and fallback policies
- Cooperative cancellation
- LLM-driven agent handoff with context transfer
- Structured logging with MDC context

---

## Core Components

### TypedAgent[I, O]

**Purpose**: Strongly-typed agent abstraction for orchestration.

```scala
trait TypedAgent[I, O] {
  def execute(input: I)(implicit ec: ExecutionContext): AsyncResult[O]
  def id: AgentId
  def name: String
  def description: Option[String] = None
}
```

**Factory Methods**:

```scala
object TypedAgent {
  // From a pure function returning Result
  def fromFunction[I, O](agentName: String)(f: I => Result[O]): TypedAgent[I, O]

  // From an unsafe function (auto-wrapped with Result.safely)
  def fromUnsafeFunction[I, O](agentName: String)(f: I => O): TypedAgent[I, O]

  // From a Future-returning function
  def fromFuture[I, O](agentName: String)(f: I => Future[Result[O]]): TypedAgent[I, O]

  // From an unsafe Future (auto-wrapped with error handling)
  def fromUnsafeFuture[I, O](agentName: String)(f: I => Future[O]): TypedAgent[I, O]

  // Constant value (always succeeds)
  def constant[I, O](agentName: String, value: O): TypedAgent[I, O]

  // Always fails
  def failure[I, O](agentName: String, error: OrchestrationError): TypedAgent[I, O]
  def simpleFailure[I, O](agentName: String, errorMessage: String): TypedAgent[I, O]
}
```

**Usage**:

```scala
// Simple functional agent
val summarizer = TypedAgent.fromFunction[String, String]("summarizer") { text =>
  Right(s"Summary of: ${text.take(100)}...")
}

// Async agent wrapping an LLM call
val llmAgent = TypedAgent.fromFuture[String, String]("llm-agent") { query =>
  Future {
    client.complete(Conversation(Seq(UserMessage(query)))) match {
      case Right(completion) => Right(completion.message.content)
      case Left(error) => Left(OrchestrationError.NodeExecutionError(
        AgentId.generate().value, "llm-agent", error.message, new RuntimeException(error.message)
      ))
    }
  }
}
```

---

### DAG Structure

#### Node

```scala
case class Node[I, O](
  id: String,
  agent: TypedAgent[I, O],
  description: Option[String] = None
)
```

#### Edge

```scala
case class Edge[A, B](
  id: String,
  source: Node[_, A],
  target: Node[A, B],
  description: Option[String] = None
)
```

**Type Safety**: The output type of `source` must match the input type of `target` — enforced at compile time.

#### Plan

```scala
case class Plan(
  nodes: Map[String, Node[_, _]],
  edges: List[Edge[_, _]],
  entryPoints: List[Node[_, _]],  // Nodes with no incoming edges
  exitPoints: List[Node[_, _]]    // Nodes with no outgoing edges
) {
  def validate: Either[String, Unit]                       // Check for cycles
  def topologicalOrder: Either[String, List[Node[_, _]]]   // Kahn's algorithm
}
```

---

### PlanRunner

**Purpose**: Executes DAG plans with topological ordering and parallel execution.

```scala
class PlanRunner(maxConcurrentNodes: Int = 10) {
  def execute(
    plan: Plan,
    initialInputs: Map[String, Any],
    cancellationToken: CancellationToken = CancellationToken.none
  )(implicit ec: ExecutionContext): AsyncResult[Map[String, Any]]
}
```

**Features**:
- Topological ordering ensures correct execution order
- Independent nodes execute in parallel (up to `maxConcurrentNodes`)
- MDC context preserved across async boundaries
- Cooperative cancellation via `CancellationToken`

**Usage**:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

// Define agents
val researcher = TypedAgent.fromFunction[String, String]("researcher") { query =>
  Right(s"Research results for: $query")
}

val writer = TypedAgent.fromFunction[String, String]("writer") { research =>
  Right(s"Article based on: $research")
}

val reviewer = TypedAgent.fromFunction[String, String]("reviewer") { article =>
  Right(s"Reviewed: $article")
}

// Build nodes
val researchNode = Node("research", researcher)
val writeNode = Node("write", writer)
val reviewNode = Node("review", reviewer)

// Build plan
val plan = Plan(
  nodes = Map(
    "research" -> researchNode,
    "write" -> writeNode,
    "review" -> reviewNode
  ),
  edges = List(
    Edge("e1", researchNode, writeNode),
    Edge("e2", writeNode, reviewNode)
  ),
  entryPoints = List(researchNode),
  exitPoints = List(reviewNode)
)

// Execute
val runner = PlanRunner(maxConcurrentNodes = 5)
val token = CancellationToken()

val result = runner.execute(
  plan,
  initialInputs = Map("research" -> "Scala functional programming"),
  cancellationToken = token
)

result.map {
  case Right(outputs) =>
    outputs.foreach { case (nodeId, output) =>
      println(s"$nodeId: $output")
    }
  case Left(error) =>
    println(s"Error: ${error.toString}")
}
```

---

### CancellationToken

```scala
case class CancellationToken() {
  def cancel(): Unit
  def isCancelled: Boolean
}

object CancellationToken {
  def none: CancellationToken  // Non-cancellable token
}
```

**Usage**:

```scala
val token = CancellationToken()

// Start execution
val future = runner.execute(plan, inputs, token)

// Cancel after timeout
Future {
  Thread.sleep(30000)
  token.cancel()
}
```

---

## Execution Policies

### Retry Policy

```scala
Policies.withRetry[I, O](
  agent: TypedAgent[I, O],
  maxAttempts: Int,
  backoff: FiniteDuration = 1.second
): TypedAgent[I, O]
```

- Exponential backoff: `backoff * attemptNumber`
- Only retries recoverable errors (`OrchestrationError.NodeExecutionError.recoverable`)
- Non-recoverable errors fail immediately

```scala
val resilientAgent = Policies.withRetry(llmAgent, maxAttempts = 3, backoff = 2.seconds)
```

### Timeout Policy

```scala
Policies.withTimeout[I, O](
  agent: TypedAgent[I, O],
  timeout: FiniteDuration
): TypedAgent[I, O]
```

```scala
val timedAgent = Policies.withTimeout(llmAgent, 30.seconds)
```

### Fallback Policy

```scala
Policies.withFallback[I, O](
  primary: TypedAgent[I, O],
  fallback: TypedAgent[I, O]
): TypedAgent[I, O]
```

```scala
val robustAgent = Policies.withFallback(primaryLLM, fallbackLLM)
```

### Composing Policies

```scala
// Retry with timeout and fallback
val agent = Policies.withFallback(
  Policies.withRetry(
    Policies.withTimeout(llmAgent, 30.seconds),
    maxAttempts = 3
  ),
  fallbackAgent
)
```

---

## Agent Handoff

**Purpose**: LLM-driven delegation between agents with context transfer.

```scala
case class Handoff(
  targetAgent: Agent,
  transferReason: Option[String] = None,
  preserveContext: Boolean = true,
  transferSystemMessage: Boolean = false
) {
  def handoffId: String       // Unique identifier for tool naming
  def handoffName: String     // Human-readable name
}
```

**How it works**: Handoffs are exposed to the LLM as tool calls. When the LLM decides to delegate, it calls the handoff tool, which transfers the conversation to the target agent.

**Usage**:

```scala
val generalAgent = new Agent(client)
val specialistAgent = new Agent(client)

// Define handoff
val handoff = Handoff(
  targetAgent = specialistAgent,
  transferReason = Some("Requires physics expertise"),
  preserveContext = true   // Transfer conversation history
)

// Run with handoffs available
generalAgent.run(
  query = "Explain quantum entanglement",
  tools = toolRegistry,
  handoffs = Seq(handoff)
)
```

**Multiple Specialists**:

```scala
val weatherSpecialist = new Agent(client)
val travelSpecialist = new Agent(client)
val financeSpecialist = new Agent(client)

val handoffs = Seq(
  Handoff(weatherSpecialist, Some("Weather-related queries")),
  Handoff(travelSpecialist, Some("Travel planning and booking")),
  Handoff(financeSpecialist, Some("Financial advice and calculations"))
)

// Host agent delegates to specialists as needed
hostAgent.run(
  query = "Plan a trip to Tokyo, check weather, and estimate budget",
  tools = generalTools,
  handoffs = handoffs
)
```

---

## OrchestrationError

```scala
sealed trait OrchestrationError extends LLMError

object OrchestrationError {
  case class NodeExecutionError(
    nodeId: String,
    nodeName: String,
    message: String,
    cause: Throwable,
    recoverable: Boolean = true
  ) extends OrchestrationError

  object NodeExecutionError {
    def nonRecoverable(nodeId: String, nodeName: String, message: String): NodeExecutionError
  }

  case class PlanValidationError(message: String) extends OrchestrationError
  case class CancellationError(message: String) extends OrchestrationError
  case class TimeoutError(nodeId: String, nodeName: String, timeout: FiniteDuration) extends OrchestrationError
}
```

---

## Complete Example: Research Pipeline

```scala
import org.llm4s.agent.orchestration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

// 1. Define typed agents
val queryAnalyzer = TypedAgent.fromFunction[String, List[String]]("query-analyzer") { query =>
  Right(query.split(" ").toList.take(3).map(w => s"subtopic: $w"))
}

val researcher = TypedAgent.fromFunction[List[String], Map[String, String]]("researcher") { topics =>
  Right(topics.map(t => t -> s"Research results for $t").toMap)
}

val synthesizer = TypedAgent.fromFunction[Map[String, String], String]("synthesizer") { research =>
  Right(research.values.mkString("\n\n"))
}

// 2. Build nodes
val analyzeNode = Node("analyze", queryAnalyzer, Some("Break query into subtopics"))
val researchNode = Node("research", researcher, Some("Research each subtopic"))
val synthesizeNode = Node("synthesize", synthesizer, Some("Combine research into answer"))

// 3. Build plan
val plan = Plan(
  nodes = Map(
    "analyze" -> analyzeNode,
    "research" -> researchNode,
    "synthesize" -> synthesizeNode
  ),
  edges = List(
    Edge("e1", analyzeNode, researchNode),
    Edge("e2", researchNode, synthesizeNode)
  ),
  entryPoints = List(analyzeNode),
  exitPoints = List(synthesizeNode)
)

// 4. Validate
plan.validate match {
  case Right(()) => println("Plan is valid")
  case Left(error) => println(s"Invalid plan: $error")
}

// 5. Execute with policies
val resilientResearcher = Policies.withRetry(
  Policies.withTimeout(researcher, 30.seconds),
  maxAttempts = 3
)

val runner = PlanRunner(maxConcurrentNodes = 5)
val token = CancellationToken()

runner.execute(plan, Map("analyze" -> "Scala functional programming patterns"), token).map {
  case Right(results) =>
    println(s"Final answer: ${results("synthesize")}")
  case Left(error) =>
    println(s"Pipeline failed: ${error.toString}")
}
```

---

## Next Steps

- **Agent Patterns**: See `llm4s-agent-patterns.md` for single-agent execution
- **Memory System**: See `llm4s-memory-system.md` for agent memory
- **RAG Pipeline**: See `llm4s-rag-pipeline.md` for document retrieval
- **Guardrails**: See `llm4s-guardrails.md` for input/output validation
