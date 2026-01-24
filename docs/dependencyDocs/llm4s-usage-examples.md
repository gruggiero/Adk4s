# LLM4S Usage Examples

Real-world examples from the llm4s codebase (`samples/src/main/scala/org/llm4s/samples/`).

---

## Example 1: Basic LLM Calling

**Source**: `samples/basic/BasicLLMCallingExample.scala`

```scala
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

val conversation = Conversation(Seq(
  SystemMessage("You are a helpful assistant. You will talk like a pirate."),
  UserMessage("Please write a scala function to add two integers")
))

val client = LLM.client()

client.complete(conversation) match {
  case Right(completion) =>
    println(completion.message.content)
    completion.usage.foreach { u =>
      println(s"Tokens: ${u.totalTokens} (${u.promptTokens} prompt, ${u.completionTokens} completion)")
    }
  case Left(error) =>
    println(s"Error: ${error.message}")
}
```

**Environment**: `LLM_MODEL=openai/gpt-4o`, `OPENAI_API_KEY=sk-...`

---

## Example 2: Multiple Tools

**Source**: `samples/toolapi/MultiToolExample.scala`

```scala
import org.llm4s.toolapi._
import upickle.default._

case class CalculationResult(result: Double)
implicit val calcResultRW: ReadWriter[CalculationResult] = macroRW

val calculatorSchema = Schema
  .`object`[Map[String, Any]]("Calculator parameters")
  .withProperty(Schema.property("operation", Schema.string("Operation").withEnum(Seq("add", "multiply"))))
  .withProperty(Schema.property("a", Schema.number("First")))
  .withProperty(Schema.property("b", Schema.number("Second")))

def calculatorHandler(params: SafeParameterExtractor): Either[String, CalculationResult] =
  for {
    op <- params.getString("operation")
    a <- params.getDouble("a")
    b <- params.getDouble("b")
    result <- op match {
      case "add" => Right(a + b)
      case "multiply" => Right(a * b)
      case _ => Left(s"Unknown: $op")
    }
  } yield CalculationResult(result)

val calculatorTool = ToolBuilder[Map[String, Any], CalculationResult](
  "calculator",
  "Arithmetic operations",
  calculatorSchema
).withHandler(calculatorHandler).build()

val toolRegistry = new ToolRegistry(Seq(calculatorTool))

val request = ToolCallRequest("calculator", ujson.Obj("operation" -> "multiply", "a" -> 5.2, "b" -> 3.0))
toolRegistry.execute(request) match {
  case Right(json) => println(json.render(indent = 2))
  case Left(error) => println(s"Error: $error")
}
```

---

## Example 3: LLM with Tool Calling

**Source**: `samples/toolapi/LLMWeatherExample.scala`

```scala
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._
import scala.annotation.tailrec

val client = LLM.client()
val toolRegistry = new ToolRegistry(Seq(weatherTool))

@tailrec
def processUntilComplete(conversation: Conversation, options: CompletionOptions): Unit =
  client.complete(conversation, options) match {
    case Right(completion) if completion.message.toolCalls.nonEmpty =>
      val toolMessages = completion.message.toolCalls.map { tc =>
        val request = ToolCallRequest(tc.name, tc.arguments)
        val result = toolRegistry.execute(request)
        ToolMessage(tc.id, result.fold(e => s"""{"error": "$e"}""", _.render()))
      }
      
      val updated = conversation.addMessage(completion.message).addMessages(toolMessages)
      processUntilComplete(updated, CompletionOptions())
    
    case Right(completion) =>
      println(completion.message.content)
    
    case Left(error) =>
      println(s"Error: $error")
  }

processUntilComplete(
  Conversation(Seq(UserMessage("What's the weather in Paris?"))),
  CompletionOptions(tools = Seq(weatherTool))
)
```

---

## Example 4: Single-Step Agent

**Source**: `samples/agent/SingleStepAgentExample.scala`

```scala
import org.llm4s.agent.{Agent, AgentStatus}
import org.llm4s.llmconnect.LLM
import org.llm4s.toolapi.ToolRegistry

val agent = new Agent(LLM.client())
val toolRegistry = new ToolRegistry(Seq(weatherTool))

var state = agent.initialize("What's the weather in Paris?", toolRegistry)
agent.writeTraceLog(state, "trace.md")

var stepCount = 0
while (state.status == AgentStatus.InProgress && stepCount < 5) {
  agent.runStep(state) match {
    case Right(newState) =>
      state = newState
      println(s"Step ${stepCount + 1}: ${state.status}")
      agent.writeTraceLog(state, "trace.md")
    
    case Left(error) =>
      println(s"Error: $error")
      state = state.withStatus(AgentStatus.Failed(error.toString))
  }
  stepCount += 1
}

println(s"Final: ${state.status}")
state.dump()
```

---

## Example 5: Automatic Agent

**Source**: `samples/agent/MultiStepAgentExample.scala`

```scala
val agent = new Agent(LLM.client())
val toolRegistry = new ToolRegistry(Seq(weatherTool))

val initialState = agent.initialize(
  "Check weather in Paris, London, Tokyo. Which has best weather?",
  toolRegistry
)

agent.run(initialState, maxSteps = Some(15), traceLogPath = Some("trace.md")) match {
  case Right(finalState) =>
    finalState.status match {
      case AgentStatus.Complete =>
        val answer = finalState.conversation.messages.reverse
          .collectFirst { case msg: AssistantMessage => msg.content }
          .getOrElse("No response")
        println(answer)
      
      case AgentStatus.Failed(error) =>
        println(s"Failed: $error")
      
      case other =>
        println(s"Unexpected: $other")
    }
  
  case Left(error) =>
    println(error.formatted)
}
```

---

## Example 6: Embeddings with Chunking

**Source**: `samples/embeddingsupport/EmbeddingExample.scala`

```scala
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.extractors.UniversalExtractor
import org.llm4s.llmconnect.utils.{ChunkingUtils, SimilarityUtils}

val text = UniversalExtractor.extract("document.pdf").getOrElse("")
val chunks = ChunkingUtils.chunkText(text, chunkSize = 500, overlap = 50)

val request = EmbeddingRequest(
  input = chunks :+ "search query",
  model = ModelSelector.selectModel()
)

val client = EmbeddingClient.fromConfig()
client.embed(request) match {
  case Right(response) =>
    val querySimilarities = response.embeddings.init.map { chunkEmbedding =>
      SimilarityUtils.cosineSimilarity(chunkEmbedding, response.embeddings.last)
    }
    
    val topChunk = querySimilarities.zipWithIndex.maxBy(_._1)
    println(s"Most relevant: chunk ${topChunk._2} (similarity: ${topChunk._1})")
  
  case Left(error) =>
    println(s"Error: ${error.message}")
}
```

**Config**: `EMBEDDING_PROVIDER=openai`, `OPENAI_API_KEY=sk-...`

---

## Example 7: Enhanced Error Handling

**Source**: `samples/migration/ErrorMigration.scala`

```scala
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMConnectV2

val client = LLMConnectV2.enhancedClient()

client.complete(conversation) match {
  case Right(completion) =>
    println(completion.message.content)
  
  case Left(error: LLMError.RateLimitError) =>
    println(s"Rate limited. Retry after ${error.retryAfter} seconds")
    if (error.isRecoverable) {
      Thread.sleep(error.retryDelay.getOrElse(1000))
      // Retry
    }
  
  case Left(error: LLMError.NetworkError) =>
    println(s"Network error: ${error.endpoint}")
    if (error.isRecoverable) {
      println(s"Retrying in ${error.retryDelay}ms...")
    }
  
  case Left(error) =>
    println(error.formatted)
}
```

---

## Example 8: Result Combinators

**Source**: `samples/types/TypeUsage.scala`

```scala
import org.llm4s.types.Result

// Sequence
val results = List(Right(1), Right(2), Right(3))
Result.sequence(results) match {
  case Right(values) => println(s"All: $values")
  case Left(error) => println(s"Failed: ${error.message}")
}

// Traverse
val queries = List("What is 1+1?", "What is 2+2?")
Result.traverse(queries) { query =>
  client.complete(Conversation(Seq(UserMessage(query)))).map(_.message.content)
} match {
  case Right(answers) => answers.foreach(println)
  case Left(error) => println(error.formatted)
}

// Combine
Result.combine(
  client.complete(conv1).map(_.message.content),
  client.complete(conv2).map(_.message.content)
) match {
  case Right((answer1, answer2)) => println(s"$answer1\n$answer2")
  case Left(error) => println(error.formatted)
}
```

---

## Common Patterns

### Pattern 1: Retry with Backoff
```scala
def retryWithBackoff(attempt: Int = 0): Either[LLMError, Completion] = {
  client.complete(conversation) match {
    case Left(error) if error.isRecoverable && attempt < 3 =>
      Thread.sleep(error.retryDelay.getOrElse(1000L) * Math.pow(2, attempt).toLong)
      retryWithBackoff(attempt + 1)
    case result => result
  }
}
```

### Pattern 2: Parallel Tool Execution
```scala
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

val toolFutures = toolCalls.map { tc =>
  Future {
    val request = ToolCallRequest(tc.name, tc.arguments)
    (tc.id, toolRegistry.execute(request))
  }
}

val results = Await.result(Future.sequence(toolFutures), 30.seconds)
val toolMessages = results.map { case (id, result) =>
  ToolMessage(id, result.fold(e => s"""{"error": "$e"}""", _.render()))
}
```

### Pattern 3: Streaming Logs
```scala
def logToFile(entry: String, logFile: String): Unit = {
  val writer = new PrintWriter(new FileWriter(logFile, true))
  try writer.println(s"[${java.time.Instant.now}] $entry")
  finally writer.close()
}

while (state.status == AgentStatus.InProgress) {
  agent.runStep(state) match {
    case Right(newState) =>
      newState.logs.drop(state.logs.size).foreach(logToFile(_, "agent.log"))
      state = newState
    case Left(error) =>
      logToFile(s"ERROR: ${error.formatted}", "agent.log")
      state = state.withStatus(AgentStatus.Failed(error.toString))
  }
}
```

---

## Next Steps

- **Core API**: See `llm4s-core-api.md`
- **Tool System**: See `llm4s-tool-system.md`
- **Agent Patterns**: See `llm4s-agent-patterns.md`
- **Best Practices**: See `llm4s-best-practices.md`
