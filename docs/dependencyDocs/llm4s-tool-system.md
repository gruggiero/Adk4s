# LLM4S Tool System Guide

## Overview

The llm4s tool system enables type-safe, structured tool calling with LLMs. Tools are defined with JSON Schema validation, type-safe parameter extraction, and automatic serialization.

**Key Features**:
- Fluent schema builder API
- Type-safe parameter extraction
- Automatic JSON serialization with upickle
- Registry-based execution
- OpenAI-compatible tool definitions

---

## Core Components

### ToolFunction

**Purpose**: Complete tool definition with schema, handler, and serialization.

```scala
case class ToolFunction[T, R: ReadWriter](
  name: String,                              // Tool identifier
  description: String,                       // LLM-visible description
  schema: SchemaDefinition[T],               // Parameter schema
  handler: SafeParameterExtractor => Either[String, R]  // Execution logic
) {
  /** Convert to OpenAI tool format */
  def toOpenAITool(strict: Boolean = true): ujson.Value
  
  /** Execute with JSON arguments */
  def execute(args: ujson.Value): Either[ToolCallError, ujson.Value]
}
```

**Type parameters**:
- `T`: Input parameter type (structural, not enforced at runtime)
- `R`: Return type (must have upickle `ReadWriter` for JSON serialization)

**Key methods**:
- `toOpenAITool`: Generates JSON Schema for LLM
- `execute`: Runs handler and serializes result

---

### ToolBuilder

**Purpose**: Fluent API for constructing tools.

```scala
class ToolBuilder[T, R: ReadWriter](
  name: String,
  description: String,
  schema: SchemaDefinition[T],
  handler: Option[SafeParameterExtractor => Either[String, R]] = None
) {
  def withHandler(handler: SafeParameterExtractor => Either[String, R]): ToolBuilder[T, R]
  def build(): ToolFunction[T, R]
}

object ToolBuilder {
  def apply[T, R: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[T]
  ): ToolBuilder[T, R]
}
```

**Usage pattern**:
```scala
val tool = ToolBuilder[Map[String, Any], MyResult](
  name = "my_tool",
  description = "Does something useful",
  schema = mySchema
).withHandler { params =>
  // Extract and validate parameters
  for {
    param1 <- params.getString("param1")
    param2 <- params.getInt("param2")
  } yield MyResult(param1, param2)
}.build()
```

---

## Schema System

### SchemaDefinition Trait

```scala
sealed trait SchemaDefinition[T] {
  /** Convert to JSON Schema format */
  def toJsonSchema(strict: Boolean): ujson.Value
}
```

**Implementations**:
- `StringSchema`: String with validation (length, enum)
- `IntegerSchema`: Integer with validation (range, multiple)
- `NumberSchema`: Double with validation (range, multiple)
- `BooleanSchema`: Boolean
- `ArraySchema[A]`: Array with item schema
- `ObjectSchema[T]`: Object with properties
- `NullableSchema[T]`: Wraps any schema to allow null

---

### Schema Builder API

```scala
object Schema {
  // Primitive types
  def string(description: String): StringSchema
  def integer(description: String): IntegerSchema
  def number(description: String): NumberSchema
  def boolean(description: String): BooleanSchema
  
  // Complex types
  def array[A](description: String, itemSchema: SchemaDefinition[A]): ArraySchema[A]
  def `object`[T](description: String): ObjectSchema[T]
  def nullable[T](schema: SchemaDefinition[T]): NullableSchema[T]
  
  // Property definition
  def property[T](
    name: String,
    schema: SchemaDefinition[T],
    required: Boolean = true
  ): PropertyDefinition[T]
}
```

---

### StringSchema

```scala
case class StringSchema(
  description: String,
  enumValues: Option[Seq[String]] = None,
  minLength: Option[Int] = None,
  maxLength: Option[Int] = None
) extends SchemaDefinition[String] {
  def withEnum(values: Seq[String]): StringSchema
  def withLengthConstraints(min: Option[Int], max: Option[Int]): StringSchema
}
```

**Examples**:
```scala
// Simple string
Schema.string("User name")

// With enum
Schema.string("Temperature units")
  .withEnum(Seq("celsius", "fahrenheit", "kelvin"))

// With length constraints
Schema.string("Description")
  .withLengthConstraints(Some(10), Some(500))

// Combined
Schema.string("Country code")
  .withEnum(Seq("US", "UK", "FR"))
  .withLengthConstraints(Some(2), Some(2))
```

---

### NumberSchema & IntegerSchema

```scala
case class NumberSchema(
  description: String,
  isInteger: Boolean = false,
  minimum: Option[Double] = None,
  maximum: Option[Double] = None,
  exclusiveMinimum: Option[Double] = None,
  exclusiveMaximum: Option[Double] = None,
  multipleOf: Option[Double] = None
) extends SchemaDefinition[Double] {
  def withRange(min: Option[Double], max: Option[Double]): NumberSchema
  def withExclusiveRange(min: Option[Double], max: Option[Double]): NumberSchema
  def withMultipleOf(multiple: Double): NumberSchema
}

case class IntegerSchema(
  description: String,
  minimum: Option[Int] = None,
  maximum: Option[Int] = None,
  exclusiveMinimum: Option[Int] = None,
  exclusiveMaximum: Option[Int] = None,
  multipleOf: Option[Int] = None
) extends SchemaDefinition[Int] {
  def withRange(min: Option[Int], max: Option[Int]): IntegerSchema
  def withExclusiveRange(min: Option[Int], max: Option[Int]): IntegerSchema
  def withMultipleOf(multiple: Int): IntegerSchema
}
```

**Examples**:
```scala
// Number with range
Schema.number("Temperature")
  .withRange(Some(-273.15), Some(1000.0))

// Integer with validation
Schema.integer("Age")
  .withRange(Some(0), Some(120))

// Multiple of constraint
Schema.integer("Even number")
  .withMultipleOf(2)

// Exclusive range (value must be strictly between bounds)
Schema.number("Probability")
  .withExclusiveRange(Some(0.0), Some(1.0))
```

---

### ObjectSchema

```scala
case class ObjectSchema[T](
  description: String,
  properties: Seq[PropertyDefinition[_]],
  additionalProperties: Boolean = false
) extends SchemaDefinition[T] {
  def withProperty[P](property: PropertyDefinition[P]): ObjectSchema[T]
}

case class PropertyDefinition[T](
  name: String,
  schema: SchemaDefinition[T],
  required: Boolean = true
)
```

**Examples**:
```scala
// Simple object
val userSchema = Schema
  .`object`[Map[String, Any]]("User information")
  .withProperty(Schema.property("name", Schema.string("Full name")))
  .withProperty(Schema.property("age", Schema.integer("Age").withRange(Some(0), Some(120))))
  .withProperty(Schema.property("email", Schema.string("Email address")))

// With optional properties
val profileSchema = Schema
  .`object`[Map[String, Any]]("User profile")
  .withProperty(Schema.property("username", Schema.string("Username")))
  .withProperty(Schema.property("bio", Schema.string("Biography"), required = false))
  .withProperty(Schema.property("website", Schema.string("Website URL"), required = false))

// Nested objects
val addressSchema = Schema
  .`object`[Map[String, Any]]("Address")
  .withProperty(Schema.property("street", Schema.string("Street")))
  .withProperty(Schema.property("city", Schema.string("City")))
  .withProperty(Schema.property("zipCode", Schema.string("ZIP code")))

val personSchema = Schema
  .`object`[Map[String, Any]]("Person")
  .withProperty(Schema.property("name", Schema.string("Name")))
  .withProperty(Schema.property("address", addressSchema))
```

---

### ArraySchema

```scala
case class ArraySchema[A](
  description: String,
  itemSchema: SchemaDefinition[A],
  minItems: Option[Int] = None,
  maxItems: Option[Int] = None,
  uniqueItems: Boolean = false
) extends SchemaDefinition[Seq[A]] {
  def withSizeConstraints(min: Option[Int], max: Option[Int]): ArraySchema[A]
  def withUniqueItems(unique: Boolean = true): ArraySchema[A]
}
```

**Examples**:
```scala
// Array of strings
Schema.array("Tags", Schema.string("Tag"))

// Array with size constraints
Schema.array("Items", Schema.string("Item"))
  .withSizeConstraints(Some(1), Some(10))

// Array with unique items
Schema.array("Categories", Schema.string("Category"))
  .withUniqueItems(true)

// Array of objects
val todoItemSchema = Schema
  .`object`[Map[String, Any]]("Todo item")
  .withProperty(Schema.property("task", Schema.string("Task description")))
  .withProperty(Schema.property("done", Schema.boolean("Completed")))

Schema.array("Todo list", todoItemSchema)
```

---

### NullableSchema

```scala
case class NullableSchema[T](underlying: SchemaDefinition[T]) extends SchemaDefinition[Option[T]]
```

**Example**:
```scala
// Nullable string
Schema.nullable(Schema.string("Optional description"))

// Nullable number
Schema.nullable(Schema.number("Optional score"))

// Nullable object
val optionalAddressSchema = Schema.nullable(addressSchema)
```

---

## SafeParameterExtractor

**Purpose**: Type-safe extraction of tool parameters from JSON.

```scala
case class SafeParameterExtractor(params: ujson.Value) {
  def getString(path: String): Either[String, String]
  def getInt(path: String): Either[String, Int]
  def getDouble(path: String): Either[String, Double]
  def getBoolean(path: String): Either[String, Boolean]
  def getArray(path: String): Either[String, ujson.Arr]
  def getObject(path: String): Either[String, ujson.Obj]
}
```

**Features**:
- **Dot-notation paths**: Navigate nested JSON with `"user.address.city"`
- **Type validation**: Returns `Left(error)` if type mismatch
- **No exceptions**: Pure functional error handling
- **Path validation**: Checks each segment exists

**Examples**:
```scala
// Simple extraction
val extractor = SafeParameterExtractor(ujson.Obj(
  "name" -> "Alice",
  "age" -> 30
))

extractor.getString("name")  // Right("Alice")
extractor.getInt("age")      // Right(30)
extractor.getInt("name")     // Left("Value at 'name' is not of expected type 'integer'")
extractor.getString("missing")  // Left("Path 'missing' not found")

// Nested extraction
val extractor2 = SafeParameterExtractor(ujson.Obj(
  "user" -> ujson.Obj(
    "address" -> ujson.Obj(
      "city" -> "Paris"
    )
  )
))

extractor2.getString("user.address.city")  // Right("Paris")

// For-comprehension pattern
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    name <- params.getString("name")
    age <- params.getInt("age")
    city <- params.getString("address.city")
  } yield Result(name, age, city)
```

---

## ToolRegistry

**Purpose**: Manages and executes tool calls.

```scala
class ToolRegistry(initialTools: Seq[ToolFunction[_, _]]) {
  def tools: Seq[ToolFunction[_, _]]
  def getTool(name: String): Option[ToolFunction[_, _]]
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value]
  def getOpenAITools(strict: Boolean = true): ujson.Arr
  def getToolDefinitions(provider: String): ujson.Value
  def addToAzureOptions(chatOptions: ChatCompletionsOptions): ChatCompletionsOptions
}

case class ToolCallRequest(
  functionName: String,
  arguments: ujson.Value
)

sealed trait ToolCallError
object ToolCallError {
  case class UnknownFunction(name: String) extends ToolCallError
  case class InvalidArguments(errors: List[String]) extends ToolCallError
  case class ExecutionError(cause: Throwable) extends ToolCallError
}
```

**Usage**:
```scala
val toolRegistry = new ToolRegistry(Seq(
  weatherTool,
  calculatorTool,
  searchTool
))

// Execute a tool
val request = ToolCallRequest(
  functionName = "calculator",
  arguments = ujson.Obj("operation" -> "add", "a" -> 5, "b" -> 3)
)

toolRegistry.execute(request) match {
  case Right(result) => println(result.render())
  case Left(ToolCallError.UnknownFunction(name)) => println(s"Tool not found: $name")
  case Left(ToolCallError.InvalidArguments(errors)) => println(s"Invalid args: ${errors.mkString(", ")}")
  case Left(ToolCallError.ExecutionError(cause)) => println(s"Execution failed: ${cause.getMessage}")
}

// Get tool definitions for LLM
val openaiTools = toolRegistry.getOpenAITools()
```

---

## Complete Tool Example

### Step 1: Define Result Type

```scala
import upickle.default._

case class WeatherResult(
  temperature: Double,
  conditions: String,
  humidity: Int
)

// Required for JSON serialization
implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW
```

### Step 2: Define Schema

```scala
val weatherSchema = Schema
  .`object`[Map[String, Any]]("Weather query parameters")
  .withProperty(
    Schema.property(
      "location",
      Schema.string("City name and optional country")
        .withLengthConstraints(Some(2), Some(100))
    )
  )
  .withProperty(
    Schema.property(
      "units",
      Schema.string("Temperature units")
        .withEnum(Seq("celsius", "fahrenheit", "kelvin")),
      required = false  // Optional parameter
    )
  )
```

### Step 3: Define Handler

```scala
def weatherHandler(params: SafeParameterExtractor): Either[String, WeatherResult] =
  for {
    location <- params.getString("location")
    units <- params.getString("units").orElse(Right("celsius"))  // Default value
  } yield {
    // In real implementation, call weather API
    WeatherResult(
      temperature = 22.0,
      conditions = "Sunny",
      humidity = 65
    )
  }
```

### Step 4: Build Tool

```scala
val weatherTool = ToolBuilder[Map[String, Any], WeatherResult](
  name = "get_weather",
  description = "Get current weather for a location",
  schema = weatherSchema
).withHandler(weatherHandler).build()
```

### Step 5: Use with LLM

```scala
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi._

val client = LLM.client()
val toolRegistry = new ToolRegistry(Seq(weatherTool))

val conversation = Conversation(Seq(
  UserMessage("What's the weather in Paris?")
))

val options = CompletionOptions(tools = Seq(weatherTool))

client.complete(conversation, options) match {
  case Right(completion) if completion.message.toolCalls.nonEmpty =>
    // LLM requested tool calls
    val toolMessages = completion.message.toolCalls.map { tc =>
      val request = ToolCallRequest(tc.name, tc.arguments)
      val result = toolRegistry.execute(request)
      
      val content = result match {
        case Right(json) => json.render()
        case Left(error) => s"""{"error": "$error"}"""
      }
      
      ToolMessage(tc.id, content)
    }
    
    // Send tool results back
    val updatedConv = conversation
      .addMessage(completion.message)
      .addMessages(toolMessages)
    
    // Get final response
    client.complete(updatedConv) match {
      case Right(finalCompletion) =>
        println(finalCompletion.message.content)
      case Left(error) =>
        println(error.formatted)
    }
  
  case Right(completion) =>
    // Direct answer without tools
    println(completion.message.content)
  
  case Left(error) =>
    println(error.formatted)
}
```

---

## Multi-Tool Example

```scala
// Calculator result type
case class CalculationResult(result: Double, operation: String)
implicit val calcResultRW: ReadWriter[CalculationResult] = macroRW

// Calculator schema
val calculatorSchema = Schema
  .`object`[Map[String, Any]]("Calculator parameters")
  .withProperty(
    Schema.property(
      "operation",
      Schema.string("Mathematical operation").withEnum(Seq("add", "subtract", "multiply", "divide"))
    )
  )
  .withProperty(Schema.property("a", Schema.number("First operand")))
  .withProperty(Schema.property("b", Schema.number("Second operand")))

// Calculator handler
def calculatorHandler(params: SafeParameterExtractor): Either[String, CalculationResult] =
  for {
    operation <- params.getString("operation")
    a <- params.getDouble("a")
    b <- params.getDouble("b")
    result <- operation match {
      case "add" => Right(a + b)
      case "subtract" => Right(a - b)
      case "multiply" => Right(a * b)
      case "divide" if b == 0 => Left("Division by zero")
      case "divide" => Right(a / b)
      case _ => Left(s"Unknown operation: $operation")
    }
  } yield CalculationResult(result, operation)

// Build calculator tool
val calculatorTool = ToolBuilder[Map[String, Any], CalculationResult](
  "calculator",
  "Performs basic arithmetic operations",
  calculatorSchema
).withHandler(calculatorHandler).build()

// Search result type
case class SearchResult(query: String, results: Seq[String])
implicit val searchResultRW: ReadWriter[SearchResult] = macroRW

// Search schema
val searchSchema = Schema
  .`object`[Map[String, Any]]("Search parameters")
  .withProperty(Schema.property("query", Schema.string("Search query")))
  .withProperty(
    Schema.property(
      "limit",
      Schema.integer("Max results").withRange(Some(1), Some(10))
    )
  )

// Search handler
def searchHandler(params: SafeParameterExtractor): Either[String, SearchResult] =
  for {
    query <- params.getString("query")
    limit <- params.getInt("limit")
  } yield {
    val mockResults = (1 to limit).map(i => s"Result $i for: $query")
    SearchResult(query, mockResults)
  }

// Build search tool
val searchTool = ToolBuilder[Map[String, Any], SearchResult](
  "search",
  "Searches for information",
  searchSchema
).withHandler(searchHandler).build()

// Create registry with both tools
val toolRegistry = new ToolRegistry(Seq(calculatorTool, searchTool))

// Use with LLM
val options = CompletionOptions(tools = Seq(calculatorTool, searchTool))
```

---

## Recursive Tool Calling Pattern

**Purpose**: Handle multiple rounds of tool calls automatically.

```scala
import scala.annotation.tailrec

@tailrec
def processUntilComplete(
  client: LLMClient,
  conversation: Conversation,
  toolRegistry: ToolRegistry,
  maxIterations: Int = 10
): Either[LLMError, String] = {
  if (maxIterations <= 0) {
    return Left(LLMError.ValidationError("Max iterations reached", "iterations"))
  }
  
  val options = if (conversation.messages.size == 1) {
    // First call - include tools
    CompletionOptions(tools = toolRegistry.tools)
  } else {
    // Subsequent calls - no tools in options
    CompletionOptions()
  }
  
  client.complete(conversation, options) match {
    case Right(completion) if completion.message.toolCalls.nonEmpty =>
      // Execute tools
      val toolMessages = completion.message.toolCalls.map { tc =>
        val request = ToolCallRequest(tc.name, tc.arguments)
        val result = toolRegistry.execute(request)
        ToolMessage(tc.id, result.fold(e => s"""{"error": "$e"}""", _.render()))
      }
      
      // Recurse with updated conversation
      val updated = conversation
        .addMessage(completion.message)
        .addMessages(toolMessages)
      
      processUntilComplete(client, updated, toolRegistry, maxIterations - 1)
    
    case Right(completion) =>
      // Final answer
      Right(completion.message.content)
    
    case Left(error) =>
      Left(error)
  }
}

// Usage
val initialConv = Conversation(Seq(
  UserMessage("Calculate 5 * 3, then search for that many results about Scala")
))

processUntilComplete(LLM.client(), initialConv, toolRegistry) match {
  case Right(answer) => println(answer)
  case Left(error) => println(error.formatted)
}
```

---

## Best Practices

### 1. Always Provide upickle ReadWriter
```scala
// Required for tool result serialization
case class MyResult(x: Int, y: String)
implicit val myResultRW: ReadWriter[MyResult] = macroRW
```

### 2. Use For-Comprehensions in Handlers
```scala
// Good - clean error handling
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    a <- params.getString("a")
    b <- params.getInt("b")
    c <- params.getDouble("c")
  } yield Result(a, b, c)

// Avoid - nested matches
def handlerBad(params: SafeParameterExtractor): Either[String, Result] =
  params.getString("a") match {
    case Right(a) => params.getInt("b") match {
      case Right(b) => // deeply nested
      case Left(e) => Left(e)
    }
    case Left(e) => Left(e)
  }
```

### 3. Validate Business Logic in Handler
```scala
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    age <- params.getInt("age")
    _ <- if (age >= 0 && age <= 120) Right(()) else Left("Age must be between 0 and 120")
    name <- params.getString("name")
    _ <- if (name.nonEmpty) Right(()) else Left("Name cannot be empty")
  } yield Result(name, age)
```

### 4. Provide Good Descriptions
```scala
// Good - clear and specific
Schema.string("City name in format 'City, Country' (e.g., 'Paris, France')")

// Avoid - vague
Schema.string("Location")
```

### 5. Handle Tool Errors Gracefully
```scala
toolRegistry.execute(request) match {
  case Right(result) => 
    ToolMessage(tc.id, result.render())
  
  case Left(error) =>
    // Return structured error, not raw error string
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

### 6. Use Optional Parameters with Defaults
```scala
def handler(params: SafeParameterExtractor): Either[String, Result] =
  for {
    query <- params.getString("query")
    limit <- params.getInt("limit").orElse(Right(10))  // Default to 10
    sort <- params.getString("sort").orElse(Right("relevance"))  // Default sort
  } yield Result(query, limit, sort)
```

---

## Common Gotchas

### 1. Tool Results Must Be JSON Strings
```scala
// Wrong - plain text
ToolMessage(tcId, "The answer is 42")

// Correct - JSON string
ToolMessage(tcId, ujson.Obj("answer" -> 42).render())
```

### 2. Type Parameter T is Structural
```scala
// T is just a hint, not enforced at runtime
ToolBuilder[MyCustomType, Result](...)  // MyCustomType not actually used

// Always use Map[String, Any] for parameter type
ToolBuilder[Map[String, Any], Result](...)
```

### 3. Tools Need to Be in CompletionOptions
```scala
// Wrong - tools not passed to LLM
val options = CompletionOptions()
client.complete(conv, options)  // LLM won't know about tools

// Correct
val options = CompletionOptions(tools = Seq(weatherTool, calcTool))
client.complete(conv, options)
```

### 4. Second Call Should Not Include Tools
```scala
// After tool execution, don't include tools in follow-up
val followUpOptions = CompletionOptions()  // No tools
client.complete(updatedConv, followUpOptions)
```

---

## Next Steps

- **Agent Patterns**: See `llm4s-agent-patterns.md` for orchestrated workflows
- **Examples**: See `llm4s-usage-examples.md` for complete code
- **Best Practices**: See `llm4s-best-practices.md` for more tips
