package org.adk4s.examples.eino.agent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.core.component.ChatModel
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: agent/multiagent/host
 *
 * Demonstrates a multi-agent pattern with a host/router agent
 * that delegates to specialist agents based on the query topic.
 *
 * Pattern:
 *   1. Host agent receives query
 *   2. Host classifies the query into a category
 *   3. Host routes to the appropriate specialist agent
 *   4. Specialist agent responds
 *
 * This uses ChatModel directly to show the pattern without
 * requiring the llm4s Agent class (which is synchronous).
 */
object MultiAgentHostExample extends IOApp.Simple:

  final case class SpecialistAgent(
    name: String,
    systemPrompt: String
  )

  private val specialists: Map[String, SpecialistAgent] = Map(
    "math" -> SpecialistAgent(
      "MathAgent",
      "You are a math expert. Solve mathematical problems step by step."
    ),
    "code" -> SpecialistAgent(
      "CodeAgent",
      "You are a programming expert. Help with code questions and provide examples."
    ),
    "general" -> SpecialistAgent(
      "GeneralAgent",
      "You are a helpful general assistant. Answer questions clearly and concisely."
    )
  )

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Multi-Agent Host Example (Eino: agent/multiagent/host)")
      chatModel <- ExampleUtils.createChatModel

      // Query 1: Math question → routes to MathAgent
      _ <- ExampleUtils.printSubSection("1. Math Query")
      _ <- routeAndRespond(chatModel, "What is the derivative of x^3 + 2x?")

      // Query 2: Code question → routes to CodeAgent
      _ <- ExampleUtils.printSubSection("2. Code Query")
      _ <- routeAndRespond(chatModel, "How do I write a quicksort in Scala?")

      // Query 3: General question → routes to GeneralAgent
      _ <- ExampleUtils.printSubSection("3. General Query")
      _ <- routeAndRespond(chatModel, "What is the weather like today?")

      _ <- IO.println("\nMulti-agent host example completed.")
    yield ()

  private def routeAndRespond(chatModel: ChatModel[IO], query: String): IO[Unit] =
    for
      // Step 1: Host classifies the query
      category <- classifyQuery(chatModel, query)
      _ <- IO.println(s"   Query: $query")
      _ <- IO.println(s"   Routed to: $category")

      // Step 2: Get the specialist
      specialist = specialists.getOrElse(category, specialists("general"))
      _ <- IO.println(s"   Agent: ${specialist.name}")

      // Step 3: Specialist responds
      response <- runSpecialist(chatModel, specialist, query)
      _ <- IO.println(s"   Response: ${response.take(120)}...")
    yield ()

  private def classifyQuery(chatModel: ChatModel[IO], query: String): IO[String] =
    val classifierConv: Conversation = Conversation(Seq(
      SystemMessage(
        """You are a query classifier. Classify the user's query into exactly one category.
          |Reply with ONLY the category name, nothing else.
          |Categories: math, code, general""".stripMargin
      ),
      UserMessage(query)
    ))
    chatModel.generate(classifierConv).map { (completion: Completion) =>
      val raw: String = completion.content.trim.toLowerCase
      if raw.contains("math") then "math"
      else if raw.contains("code") then "code"
      else "general"
    }

  private def runSpecialist(
    chatModel: ChatModel[IO],
    specialist: SpecialistAgent,
    query: String
  ): IO[String] =
    val conv: Conversation = Conversation(Seq(
      SystemMessage(specialist.systemPrompt),
      UserMessage(query)
    ))
    chatModel.generate(conv).map(_.content)
