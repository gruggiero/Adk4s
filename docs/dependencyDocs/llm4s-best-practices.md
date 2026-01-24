# LLM4S Best Practices and Gotchas

## Overview

This guide covers common pitfalls, best practices, and tips for effective use of llm4s.

---

## Common Gotchas

### 1. Azure SDK Dependency for All Providers

**Issue**: Even when using OpenAI, llm4s uses Azure SDK classes.

**Why**: Provides unified API across all providers.

**Impact**: Azure dependencies in your classpath even if you only use OpenAI.

```scala
// All providers use Azure SDK internally
import com.azure.ai.openai.models.ChatCompletionsOptions
```

**Workaround**: None needed - this is by design.

---

### 2. Tool Results Must Be JSON Strings

**Issue**: `ToolMessage` expects JSON string content, not plain text.

```scala
// ❌ Wrong - plain text
ToolMessage(toolCallId, "The answer is 42")

// ✅ Correct - JSON string
ToolMessage(toolCallId, ujson.Obj("answer" -> 42).render())
```

**Best practice**:
```scala
toolRegistry.execute(request) match {
  case Right(json) => ToolMessage(tc.id, json.render())
  case Left(error) => ToolMessage(tc.id, ujson.Obj("error" -> error.toString).render())
}
```

---

### 3. AssistantMessage Can Have Empty Content

**Issue**: `contentOpt` is `Option[String]` and can be `None` when only tool calls exist.

```scala
// ❌ Unsafe
println(assistantMessage.content)  // Could be empty string ""

// ✅ Safe
assistantMessage.contentOpt match {
  case Some(text) => println(text)
  case None => println("No text, only tool calls")
}
```

---

### 4. Tool Execution is Synchronous

**Issue**: No built-in parallel tool execution.

**Workaround**: Use Scala `Future` or Cats Effect `Parallel`:

```scala
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val toolFutures = toolCalls.map { tc =>
  Future {
    val request = ToolCallRequest(tc.name, tc.arguments)
    toolRegistry.execute(request)
  }
}

val results = Await.result(Future.sequence(toolFutures), 30.seconds)
```

---

### 5. StreamComplete Not Fully Implemented

**Issue**: Some providers just call `complete()` instead of streaming.

**Current state**: Streaming support is partial.

**Check**: Test with your specific provider before relying on streaming.

---

### 6. Immutability Requires Reassignment

**Issue**: Methods return new instances but don't mutate in place.

```scala
// ❌ Wrong - doesn't update conversation
conversation.addMessage(msg)

// ✅ Correct - reassign
conversation = conversation.addMessage(msg)

// ❌ Wrong - doesn't update state
agent.runStep(state)

// ✅ Correct - reassign
state = agent.runStep(state) match {
  case Right(newState) => newState
  case Left(error) => state.withStatus(AgentStatus.Failed(error.toString))
}
```

---

### 7. ujson is Untyped

**Issue**: `ujson.Value` doesn't catch type errors at compile time.

```scala
// ❌ Compiles but fails at runtime if not a string
val name = json("name").str

// ✅ Better - use SafeParameterExtractor
val extractor = SafeParameterExtractor(json)
extractor.getString("name") match {
  case Right(name) => // use name
  case Left(error) => // handle type error
}
```

---

### 8. Tools Not Included in Follow-up Calls

**Issue**: After tool execution, tools should not be in `CompletionOptions`.

```scala
// First call - include tools
val options1 = CompletionOptions(tools = Seq(weatherTool))
client.complete(conversation, options1)

// After processing tool results - no tools
val options2 = CompletionOptions()  // Empty options
client.complete(updatedConversation, options2)
```

---

### 9. Agent Status Can Be InProgress After run()

**Issue**: Agent might not reach `Complete` if max steps reached.

```scala
agent.run(initialState, maxSteps = Some(5)) match {
  case Right(finalState) =>
    // ❌ Don't assume Complete
    finalState.status match {
      case AgentStatus.Complete => // success
      case AgentStatus.Failed(error) => // failure
      case AgentStatus.InProgress => // max steps reached
      case _ => // unexpected
    }
  case Left(error) => // LLM error
}
```

---

### 10. Trace File Overwrites Each Time

**Issue**: `writeTraceLog` overwrites the file on each call.

```scala
// ❌ Loses history
agent.writeTraceLog(state, "trace.md")  // Overwrites
agent.writeTraceLog(state, "trace.md")  // Overwrites again

// ✅ Use unique filenames
agent.writeTraceLog(state, s"trace-${System.currentTimeMillis()}.md")

// Or ✅ Call once at end
agent.run(initialState, traceLogPath = Some("trace.md"))  // Writes once
```

---

## Best Practices

### Error Handling

#### 1. Use Smart Constructors for Type-Safe IDs

```scala
// ❌ Avoid - no validation
val model = ModelName("gpt-4")

// ✅ Good - validated
ModelName.create("gpt-4") match {
  case Right(modelName) => // use modelName
  case Left(error) => // handle validation error
}

// ✅ Best - use constants
val model = ModelName.GPT_4
```

#### 2. Check isRecoverable Before Retry

```scala
// ✅ Good
def retryable(attempt: Int = 0): Either[LLMError, Completion] = {
  client.complete(conversation) match {
    case Left(error) if error.isRecoverable && attempt < 3 =>
      Thread.sleep(error.retryDelay.getOrElse(1000L) * Math.pow(2, attempt).toLong)
      retryable(attempt + 1)
    case result => result
  }
}
```

#### 3. Use formatted for Error Logging

```scala
// ✅ Good - structured output
case Left(error) => logger.error(error.formatted)

// ❌ Avoid - less informative
case Left(error) => logger.error(error.toString)
```

#### 4. Handle All Error Types

```scala
// ✅ Good - exhaustive
client.complete(conversation) match {
  case Right(completion) => // success
  case Left(error: LLMError.RateLimitError) => // specific handling
  case Left(error: LLMError.NetworkError) => // retry
  case Left(error: LLMError.AuthenticationError) => // check config
  case Left(error) => // catch-all
}
```

---

### Tool System

#### 1. Define upickle ReadWriter Once

```scala
// ✅ Good - define once at top level
case class MyResult(x: Int, y: String)
implicit val myResultRW: ReadWriter[MyResult] = macroRW

// Reuse in multiple tools
val tool1 = ToolBuilder[_, MyResult](...)
val tool2 = ToolBuilder[_, MyResult](...)
```

#### 2. Use For-Comprehensions in Handlers

```scala
// ✅ Good - clean error handling
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    a <- params.getString("a")
    b <- params.getInt("b")
    c <- params.getDouble("c")
  } yield Result(a, b, c)

// ❌ Avoid - nested matches
def handlerBad(params: SafeParameterExtractor): Either[String, Result] =
  params.getString("a") match {
    case Right(a) => params.getInt("b") match {
      case Right(b) => // deeply nested
      case Left(e) => Left(e)
    }
    case Left(e) => Left(e)
  }
```

#### 3. Validate Business Logic in Handler

```scala
// ✅ Good - validate in handler
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    age <- params.getInt("age")
    _ <- if (age >= 0 && age <= 120) Right(()) else Left("Age must be 0-120")
    name <- params.getString("name")
    _ <- if (name.nonEmpty) Right(()) else Left("Name cannot be empty")
  } yield Result(name, age)
```

#### 4. Provide Clear Descriptions

```scala
// ✅ Good - specific
Schema.string("City name in format 'City, Country' (e.g., 'Paris, France')")

// ❌ Avoid - vague
Schema.string("Location")
```

#### 5. Return Structured Errors

```scala
// ✅ Good - structured
toolRegistry.execute(request) match {
  case Right(result) => 
    ToolMessage(tc.id, result.render())
  
  case Left(error) =>
    val errorJson = ujson.Obj(
      "success" -> false,
      "error" -> error.toString,
      "type" -> (error match {
        case ToolCallError.UnknownFunction(_) => "unknown_function"
        case ToolCallError.InvalidArguments(_) => "invalid_arguments"
        case ToolCallError.ExecutionError(_) => "execution_error"
      })
    )
    ToolMessage(tc.id, errorJson.render())
}
```

#### 6. Use Optional Parameters with Defaults

```scala
// ✅ Good - defaults in handler
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    query <- params.getString("query")
    limit <- params.getInt("limit").orElse(Right(10))
    sort <- params.getString("sort").orElse(Right("relevance"))
  } yield Result(query, limit, sort)
```

---

### Agent System

#### 1. Always Set Max Steps

```scala
// ✅ Good - prevents infinite loops
agent.run(initialState, maxSteps = Some(20))

// ❌ Risky - could loop forever
agent.run(initialState, maxSteps = None)
```

#### 2. Enable Tracing for Debugging

```scala
// ✅ Development
agent.run(
  initialState,
  maxSteps = Some(10),
  traceLogPath = Some("debug-trace.md")
)

// ✅ Production
agent.run(
  initialState,
  maxSteps = Some(10),
  traceLogPath = None  // Skip file I/O
)
```

#### 3. Check Status After Execution

```scala
// ✅ Good - exhaustive status check
agent.run(initialState) match {
  case Right(finalState) =>
    finalState.status match {
      case AgentStatus.Complete => // extract result
      case AgentStatus.Failed(error) => // handle failure
      case AgentStatus.InProgress => // max steps reached
      case AgentStatus.WaitingForTools => // shouldn't happen
    }
  case Left(error) => // LLM error
}
```

#### 4. Use Step-by-Step for Debugging

```scala
// ✅ Good for troubleshooting
var state = agent.initialize(query, toolRegistry)

while (state.status == AgentStatus.InProgress) {
  println(s"\n--- Step ${state.logs.size + 1} ---")
  println("Press Enter to continue...")
  scala.io.StdIn.readLine()
  
  agent.runStep(state) match {
    case Right(newState) => state = newState
    case Left(error) => state = state.withStatus(AgentStatus.Failed(error.toString))
  }
}
```

#### 5. Domain-Specific System Prompts

```scala
// ✅ Good - domain expertise
agent.initialize(
  query = query,
  tools = tools,
  systemPromptAddition = Some("""
    You are a travel planning specialist.
    Always consider:
    - Budget constraints
    - Seasonality
    - User preferences
  """.trim)
)

// ❌ Less effective - generic
agent.initialize(query, tools)
```

---

### Conversation Management

#### 1. Build Conversations Incrementally

```scala
// ✅ Good - incremental
var conv = Conversation(Seq(SystemMessage("...")))
conv = conv.addMessage(UserMessage("query"))

client.complete(conv).foreach { completion =>
  conv = conv.addMessage(completion.message)
}
```

#### 2. Reuse System Prompts

```scala
// ✅ Good - extract common prompts
object SystemPrompts {
  val helpful = "You are a helpful assistant."
  val concise = "You are a helpful assistant. Be concise."
  val creative = "You are a creative writing assistant."
}

Conversation(Seq(
  SystemMessage(SystemPrompts.concise),
  UserMessage(query)
))
```

---

### Performance

#### 1. Reuse Clients

```scala
// ✅ Good - reuse client
val client = LLM.client()
queries.foreach { query =>
  client.complete(Conversation(Seq(UserMessage(query))))
}

// ❌ Avoid - creates new client each time
queries.foreach { query =>
  LLM.client().complete(Conversation(Seq(UserMessage(query))))
}
```

#### 2. Batch Unrelated Queries

```scala
// ✅ Good - batch with Future
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val client = LLM.client()
val futures = queries.map { query =>
  Future {
    client.complete(Conversation(Seq(UserMessage(query))))
  }
}

val results = Await.result(Future.sequence(futures), 60.seconds)
```

#### 3. Use Temperature Wisely

```scala
// ✅ Deterministic tasks
val options = CompletionOptions(temperature = 0.0)

// ✅ Creative tasks
val options = CompletionOptions(temperature = 1.2)

// ✅ Balanced
val options = CompletionOptions(temperature = 0.7)  // Default
```

---

### Testing

#### 1. Mock LLMClient for Testing

```scala
class MockLLMClient extends LLMClient {
  override def complete(conv: Conversation, opts: CompletionOptions): Either[LLMError, Completion] = {
    Right(Completion(
      id = "test",
      created = System.currentTimeMillis() / 1000,
      message = AssistantMessage("Mocked response"),
      usage = Some(TokenUsage(10, 20, 30))
    ))
  }
  
  override def streamComplete(conv: Conversation, opts: CompletionOptions, onChunk: StreamedChunk => Unit): Either[LLMError, Completion] =
    complete(conv, opts)
}

// Use in tests
val testAgent = new Agent(new MockLLMClient())
```

#### 2. Test Tool Handlers Separately

```scala
// ✅ Good - test handler independently
test("calculator handler divides correctly") {
  val params = SafeParameterExtractor(ujson.Obj(
    "operation" -> "divide",
    "a" -> 10.0,
    "b" -> 2.0
  ))
  
  calculatorHandler(params) match {
    case Right(result) => assert(result.result == 5.0)
    case Left(error) => fail(s"Unexpected error: $error")
  }
}

test("calculator handler rejects division by zero") {
  val params = SafeParameterExtractor(ujson.Obj(
    "operation" -> "divide",
    "a" -> 10.0,
    "b" -> 0.0
  ))
  
  calculatorHandler(params) match {
    case Left(error) => assert(error.contains("zero"))
    case Right(_) => fail("Should have failed")
  }
}
```

---

### Security

#### 1. Never Log API Keys

```scala
// ✅ Good - ApiKey.toString masks value
val apiKey = ApiKey("sk-actual-key")
logger.info(s"Using key: $apiKey")  // Logs "ApiKey(***)"

// ❌ Dangerous
logger.info(s"Using key: ${apiKey.reveal}")  // Logs actual key
```

#### 2. Validate Tool Parameters

```scala
// ✅ Good - validate inputs
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    url <- params.getString("url")
    _ <- if (url.startsWith("https://")) Right(()) else Left("Must use HTTPS")
    limit <- params.getInt("limit")
    _ <- if (limit > 0 && limit <= 100) Right(()) else Left("Limit must be 1-100")
  } yield Result(url, limit)
```

#### 3. Sanitize Tool Outputs

```scala
// ✅ Good - sanitize before sending to LLM
toolRegistry.execute(request) match {
  case Right(json) =>
    val sanitized = sanitize(json)  // Remove sensitive data
    ToolMessage(tc.id, sanitized.render())
  
  case Left(error) =>
    ToolMessage(tc.id, ujson.Obj("error" -> "Tool execution failed").render())
}
```

---

## Checklist for Production

### Before Deployment

- [ ] Set `maxSteps` on all agent runs
- [ ] Disable trace logging (or use conditional logging)
- [ ] Add retry logic with exponential backoff
- [ ] Validate all environment variables at startup
- [ ] Add comprehensive error logging
- [ ] Test with rate limits
- [ ] Implement circuit breakers for external tools
- [ ] Add metrics/monitoring for LLM calls
- [ ] Set appropriate timeouts
- [ ] Test with malformed LLM responses

### Configuration

- [ ] Store API keys in secrets manager (not env vars in production)
- [ ] Use appropriate temperature for use case
- [ ] Set `maxTokens` to prevent excessive costs
- [ ] Configure request timeouts
- [ ] Enable structured logging

### Monitoring

- [ ] Log all LLM errors with context
- [ ] Track token usage per request
- [ ] Monitor error rates by type
- [ ] Track agent completion rates
- [ ] Alert on authentication failures
- [ ] Monitor rate limit hits

---

## Quick Reference

### Environment Variables

```bash
# Core
LLM_MODEL=openai/gpt-4o
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
AZURE_OPENAI_API_KEY=...
OPENROUTER_API_KEY=sk-or-...

# Optional
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_ORGANIZATION=org-...

# Embeddings
EMBEDDING_PROVIDER=openai
```

### Common Imports

```scala
// Core
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.error.LLMError
import org.llm4s.types.Result

// Tools
import org.llm4s.toolapi._
import upickle.default._

// Agent
import org.llm4s.agent.{Agent, AgentState, AgentStatus}
```

### Result Helpers

```scala
// Sequence
Result.sequence(List(Right(1), Right(2)))  // Right(List(1, 2))

// Traverse
Result.traverse(List("a", "b"))(s => Right(s.length))  // Right(List(1, 1))

// Combine
Result.combine(Right(1), Right(2))  // Right((1, 2))
```

---

## Next Steps

- **Core API**: See `llm4s-core-api.md` for fundamentals
- **Tool System**: See `llm4s-tool-system.md` for tool calling
- **Agent Patterns**: See `llm4s-agent-patterns.md` for orchestration
- **Examples**: See `llm4s-usage-examples.md` for code samples
