package org.adk4s.examples.structured.toolcall

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.*
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.tools.{StructuredToolCall, ToolSchema, TypedTool}
import org.adk4s.examples.eino.common.ExampleUtils

/**
 * Demonstrates StructuredToolCall ReAct agent pattern with typed tools.
 *
 * Shows how to:
 * - Define typed tool input/output case classes
 * - Use StructuredToolCall.createTool for compile-time type safety
 * - Implement a ReAct loop (Reason + Act) with tool execution
 * - Use ToolSchema.derive for automatic encoder/decoder generation
 * - Handle tool results with ToolSchema encoder
 *
 * Supports both MockChatModel and real LLM via OPENAI_API_KEY env variable.
 */
object ReactAgentStructuredExample extends IOApp.Simple:

  // Tool input/output case classes
  case class CalculatorInput(operation: String, x: Double, y: Double)
  case class CalculatorResult(result: Double, explanation: String)

  case class SearchInput(query: String)
  case class SearchResult(topResult: String, relevance: Double)

  case class WeatherInput(city: String, units: String)
  case class WeatherResult(temperature: Double, condition: String, city: String)

  // Derive ToolSchema instances automatically
  given ToolSchema[CalculatorInput] = ToolSchema.derive[CalculatorInput]
  given ToolSchema[CalculatorResult] = ToolSchema.derive[CalculatorResult]
  given ToolSchema[SearchInput] = ToolSchema.derive[SearchInput]
  given ToolSchema[SearchResult] = ToolSchema.derive[SearchResult]
  given ToolSchema[WeatherInput] = ToolSchema.derive[WeatherInput]
  given ToolSchema[WeatherResult] = ToolSchema.derive[WeatherResult]

  // Create typed tools using StructuredToolCall.createTool
  private def createCalculatorTool: TypedTool[IO, CalculatorInput, CalculatorResult] =
    StructuredToolCall.createTool[IO, CalculatorInput, CalculatorResult](
      toolName = "calculator",
      toolDescription = "Performs arithmetic operations (add, subtract, multiply, divide)"
    ) { input =>
      val result: Double = input.operation.toLowerCase match
        case "add" => input.x + input.y
        case "subtract" => input.x - input.y
        case "multiply" => input.x * input.y
        case "divide" if input.y != 0 => input.x / input.y
        case "divide" => Double.NaN
        case _ => Double.NaN

      val explanation: String = s"${input.operation}(${input.x}, ${input.y}) = $result"
      IO.pure(CalculatorResult(result, explanation))
    }

  private def createSearchTool: TypedTool[IO, SearchInput, SearchResult] =
    StructuredToolCall.createTool[IO, SearchInput, SearchResult](
      toolName = "search",
      toolDescription = "Searches the web and returns the top result"
    ) { input =>
      // Mock search results based on query
      val (topResult: String, relevance: Double) = input.query.toLowerCase match
        case q if q.contains("scala") =>
          ("Scala Programming Language - scala-lang.org", 0.95)
        case q if q.contains("weather") =>
          ("Weather Forecast - weather.com", 0.88)
        case q if q.contains("functional") =>
          ("Functional Programming in Scala", 0.92)
        case _ =>
          ("General search result", 0.70)

      IO.pure(SearchResult(topResult, relevance))
    }

  private def createWeatherTool: TypedTool[IO, WeatherInput, WeatherResult] =
    StructuredToolCall.createTool[IO, WeatherInput, WeatherResult](
      toolName = "weather",
      toolDescription = "Gets current weather information for a city"
    ) { input =>
      // Mock weather data
      val temperature: Double = input.city.toLowerCase match
        case "san francisco" => if input.units == "celsius" then 18.0 else 64.0
        case "new york" => if input.units == "celsius" then 22.0 else 72.0
        case "london" => if input.units == "celsius" then 15.0 else 59.0
        case _ => if input.units == "celsius" then 20.0 else 68.0

      val condition: String = input.city.toLowerCase match
        case "san francisco" => "Foggy"
        case "new york" => "Partly Cloudy"
        case "london" => "Rainy"
        case _ => "Sunny"

      IO.pure(WeatherResult(temperature, condition, input.city))
    }

  private def createLLMClient: IO[org.llm4s.llmconnect.LLMClient] =
    ExampleUtils.createLLMClient.recoverWith {
      case _: UnsupportedOperationException => IO.pure(new org.adk4s.examples.structured.StructuredMockLLMClient())
    }

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ReAct Agent with Typed Tools (Structured)")
      llmClient <- createLLMClient

      // Create typed tools
      calculatorTool = createCalculatorTool
      searchTool = createSearchTool
      weatherTool = createWeatherTool

      // Convert to invokable tools for LLM tool calling
      tools: List[InvokableTool[IO]] = List(
        calculatorTool.asInvokableTool,
        searchTool.asInvokableTool,
        weatherTool.asInvokableTool
      )

      // Example 1: Calculator tool
      _ <- ExampleUtils.printSubSection("1. Calculator Tool Execution")
      _ <- IO.println("   Task: Calculate 45 * 67")
      calcInput = CalculatorInput("multiply", 45.0, 67.0)
      calcResult <- calculatorTool.execute(calcInput)
      _ <- IO.println(s"   Result: ${calcResult.result}")
      _ <- IO.println(s"   Explanation: ${calcResult.explanation}")

      // Example 2: Search tool
      _ <- ExampleUtils.printSubSection("2. Search Tool Execution")
      _ <- IO.println("   Task: Search for 'Scala functional programming'")
      searchInput = SearchInput("Scala functional programming")
      searchResult <- searchTool.execute(searchInput)
      _ <- IO.println(s"   Top Result: ${searchResult.topResult}")
      _ <- IO.println(s"   Relevance: ${searchResult.relevance}")

      // Example 3: Weather tool
      _ <- ExampleUtils.printSubSection("3. Weather Tool Execution")
      _ <- IO.println("   Task: Get weather for San Francisco")
      weatherInput = WeatherInput("San Francisco", "celsius")
      weatherResult <- weatherTool.execute(weatherInput)
      _ <- IO.println(s"   City: ${weatherResult.city}")
      _ <- IO.println(s"   Temperature: ${weatherResult.temperature}°C")
      _ <- IO.println(s"   Condition: ${weatherResult.condition}")

      // Example 4: ReAct loop simulation (simplified)
      _ <- ExampleUtils.printSubSection("4. ReAct Loop Simulation")
      _ <- IO.println("   Query: What's 144 divided by 12?")
      _ <- IO.println("   [LLM Reasoning]: Need to perform division calculation")
      _ <- IO.println("   [Action]: Calling calculator tool")
      reactInput = CalculatorInput("divide", 144.0, 12.0)
      reactResult <- calculatorTool.execute(reactInput)
      _ <- IO.println(s"   [Tool Result]: ${reactResult.explanation}")
      _ <- IO.println(s"   [Final Answer]: The result is ${reactResult.result}")

      // Example 5: Tool schema encoding (for returning results to LLM)
      _ <- ExampleUtils.printSubSection("5. Tool Result Encoding")
      _ <- IO.println("   Encoding calculator result as JSON for LLM:")
      encodedResult = summon[ToolSchema[CalculatorResult]].encoder(calcResult)
      encodedJson = ujson.write(encodedResult, indent = 2)
      _ <- IO.println(s"   $encodedJson")

      _ <- IO.println("\nReAct agent example completed.")
    yield ()
