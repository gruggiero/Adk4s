package org.adk4s.examples.eino.agent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.DynamicToolRegistry
import org.adk4s.orchestration.agent.ReactAgent
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: flow/agent/react/dynamic_option
 *
 * Demonstrates a ReactAgent with a DynamicToolRegistry that allows
 * adding and removing tools between invocations. The agent's available
 * capabilities change at runtime.
 *
 * Scenarios:
 *   1. Agent with initial tools (restaurant lookup)
 *   2. Add a new tool (weather lookup) at runtime
 *   3. Remove a tool at runtime
 *   4. Show that the agent adapts to the new tool set
 */
object DynamicOptionExample extends IOApp.Simple:

  // --- Tools ---

  private val restaurantTool: InvokableTool[IO] = Tool.invokable[IO](
    "get_restaurants",
    "Get restaurants in a given city",
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "city" -> ujson.Obj("type" -> "string", "description" -> "The city to search for restaurants")
      ),
      "required" -> ujson.Arr("city")
    ),
    (args: ujson.Value) => {
      val city: String = args("city").str
      Right(ujson.Arr(
        ujson.Obj("name" -> "Sichuan Palace", "city" -> city),
        ujson.Obj("name" -> "Hunan Garden", "city" -> city)
      ))
    }
  )

  private val weatherTool: InvokableTool[IO] = Tool.invokable[IO](
    "get_weather",
    "Get current weather for a city",
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "city" -> ujson.Obj("type" -> "string", "description" -> "The city to get weather for")
      ),
      "required" -> ujson.Arr("city")
    ),
    (args: ujson.Value) => {
      val city: String = args("city").str
      Right(ujson.Obj("city" -> city, "temp" -> 22, "condition" -> "sunny"))
    }
  )

  private val reviewTool: InvokableTool[IO] = Tool.invokable[IO](
    "get_reviews",
    "Get reviews for a restaurant",
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "restaurant" -> ujson.Obj("type" -> "string", "description" -> "The restaurant name to get reviews for")
      ),
      "required" -> ujson.Arr("restaurant")
    ),
    (args: ujson.Value) => {
      val restaurant: String = args("restaurant").str
      Right(ujson.Arr(
        ujson.Obj("rating" -> 4.5, "text" -> s"Great food at $restaurant!"),
        ujson.Obj("rating" -> 4.0, "text" -> s"Nice atmosphere at $restaurant.")
      ))
    }
  )

  // --- Main ---

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("DynamicOption Example (Eino: flow/agent/react/dynamic_option)")

      // Create a dynamic tool registry with initial tools
      chatModel <- ExampleUtils.createChatModel
      registry <- DynamicToolRegistry.create(List(restaurantTool))
      agent = ReactAgent.createWithToolProvider(
        model = chatModel,
        toolProvider = registry.currentTools,
        systemPrompt = Some("You are a helpful assistant. Use available tools to answer questions."),
        maxSteps = 5
      )

      // Scenario 1: Initial tools
      _ <- ExampleUtils.printSubSection("Scenario 1: Initial tools (restaurant only)")
      names1 <- registry.toolNames
      _ <- IO.println(s"   Available tools: $names1")
      result1 <- agent.generate(List(UserMessage("Find restaurants in Beijing")), 5)
      _ <- IO.println(s"   Agent: ${result1.content}")

      // Scenario 2: Add weather tool at runtime
      _ <- ExampleUtils.printSubSection("Scenario 2: Add weather tool at runtime")
      _ <- registry.addTool(weatherTool)
      names2 <- registry.toolNames
      _ <- IO.println(s"   Available tools: $names2")
      result2 <- agent.generate(List(UserMessage("What's the weather in Beijing?")), 5)
      _ <- IO.println(s"   Agent: ${result2.content}")

      // Scenario 3: Add reviews tool, remove weather tool
      _ <- ExampleUtils.printSubSection("Scenario 3: Swap tools (add reviews, remove weather)")
      _ <- registry.addTool(reviewTool)
      _ <- registry.removeTool("get_weather")
      names3 <- registry.toolNames
      _ <- IO.println(s"   Available tools: $names3")
      result3 <- agent.generate(List(UserMessage("Get reviews for Sichuan Palace")), 5)
      _ <- IO.println(s"   Agent: ${result3.content}")

      // Scenario 4: Clear all tools
      _ <- ExampleUtils.printSubSection("Scenario 4: Clear all tools")
      _ <- registry.clear
      names4 <- registry.toolNames
      _ <- IO.println(s"   Available tools: $names4")
      result4 <- agent.generate(List(UserMessage("Hello")), 5)
      _ <- IO.println(s"   Agent: ${result4.content}")

      _ <- IO.println("\n=== DynamicOption Example Completed ===")
    yield ()
