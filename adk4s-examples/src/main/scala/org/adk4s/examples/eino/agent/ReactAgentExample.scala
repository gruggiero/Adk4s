package org.adk4s.examples.eino.agent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.orchestration.agent.ReactAgent
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: flow/agent/react
 *
 * Demonstrates a ReAct (Reasoning + Acting) agent that loops between
 * LLM generation and tool execution. The agent recommends restaurants
 * and dishes using tool calls.
 *
 * Scenarios:
 *   1. Generate (ping/pong) — single request/response with tool calls
 *   2. Stream — token-by-token streaming after tool resolution
 */
object ReactAgentExample extends IOApp.Simple:

  // --- Tools ---

  private val restaurantTool: InvokableTool[IO] = Tool.invokable[IO](
    "get_restaurants",
    "Get restaurants in a given city. Returns a JSON array of restaurant objects.",
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "city" -> ujson.Obj("type" -> "string", "description" -> "The city to search for restaurants")
      ),
      "required" -> ujson.Arr("city")
    ),
    (args: ujson.Value) => {
      val city: String = args("city").str
      val restaurants: ujson.Value = ujson.Arr(
        ujson.Obj("name" -> "Sichuan Palace", "cuisine" -> "Sichuan", "rating" -> 4.8, "city" -> city),
        ujson.Obj("name" -> "Hunan Garden", "cuisine" -> "Hunan", "rating" -> 4.5, "city" -> city),
        ujson.Obj("name" -> "Beijing Duck House", "cuisine" -> "Beijing", "rating" -> 4.7, "city" -> city)
      )
      Right(restaurants)
    }
  )

  private val dishTool: InvokableTool[IO] = Tool.invokable[IO](
    "get_dishes",
    "Get dishes for a given restaurant. Returns a JSON array of dish objects.",
    ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "restaurant" -> ujson.Obj("type" -> "string", "description" -> "The restaurant name to get dishes for")
      ),
      "required" -> ujson.Arr("restaurant")
    ),
    (args: ujson.Value) => {
      val restaurant: String = args("restaurant").str
      val dishes: ujson.Value = restaurant match
        case "Sichuan Palace" => ujson.Arr(
          ujson.Obj("name" -> "Mapo Tofu", "spicy" -> true, "price" -> 28),
          ujson.Obj("name" -> "Kung Pao Chicken", "spicy" -> true, "price" -> 35),
          ujson.Obj("name" -> "Dan Dan Noodles", "spicy" -> true, "price" -> 22)
        )
        case "Hunan Garden" => ujson.Arr(
          ujson.Obj("name" -> "Steamed Fish Head", "spicy" -> true, "price" -> 58),
          ujson.Obj("name" -> "Stir-fried Pork", "spicy" -> false, "price" -> 32)
        )
        case _ => ujson.Arr(
          ujson.Obj("name" -> "House Special", "spicy" -> false, "price" -> 40)
        )
      Right(dishes)
    }
  )

  // --- Scenarios ---

  private def runGenerate(agent: ReactAgent): IO[Unit] =
    for
      _ <- ExampleUtils.printSubSection("Scenario 1: Generate (ping/pong with tool calls)")
      result <- agent.generate(
        List(UserMessage("I'm in Beijing, recommend some spicy dishes from at least 2 restaurants")),
        maxSteps = 5
      )
      _ <- IO.println(s"   Agent response: ${result.content}")
    yield ()

  private def runStream(agent: ReactAgent): IO[Unit] =
    for
      _ <- ExampleUtils.printSubSection("Scenario 2: Stream (token-by-token after tool resolution)")
      _ <- IO.print("   Agent: ")
      _ <- agent
        .stream(
          List(UserMessage("What dishes does Sichuan Palace serve?")),
          maxSteps = 5
        )
        .evalMap { chunk =>
          IO.print(chunk.content.getOrElse(""))
        }
        .compile
        .drain
      _ <- IO.println("")
    yield ()

  // --- Main ---

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ReactAgent Example (Eino: flow/agent/react)")
      chatModel <- ExampleUtils.createChatModel
      agent = ReactAgent.create(
        model = chatModel,
        tools = List(restaurantTool, dishTool),
        systemPrompt = Some("You are a helpful assistant that recommends restaurants and dishes. Use the available tools to look up information."),
        maxSteps = 10
      )
      _ <- runGenerate(agent)
      _ <- runStream(agent)
      _ <- IO.println("\n=== ReactAgent Example Completed ===")
    yield ()
