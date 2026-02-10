package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: components/model
 *
 * Demonstrates ChatModel[IO] with generate (invoke) and stream modes.
 * Shows conversation construction with SystemMessage and UserMessage.
 */
object ChatModelExample extends IOApp.Simple:

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ChatModel Example (Eino: components/model)")
      chatModel <- ExampleUtils.createChatModel

      // 1. Generate (invoke) mode — full response at once
      _ <- ExampleUtils.printSubSection("1. Generate Mode (invoke)")
      conversation1 = Conversation(Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("What is the weather like today?")
      ))
      completion1 <- chatModel.generate(conversation1)
      _ <- IO.println(s"   Model: ${completion1.model}")
      _ <- IO.println(s"   Response: ${completion1.content}")

      // 2. Stream mode — token-by-token output
      _ <- ExampleUtils.printSubSection("2. Stream Mode (token-by-token)")
      conversation2 = Conversation(Seq(
        SystemMessage("You are a cat."),
        UserMessage("Why do cats purr?")
      ))
      _ <- IO.print("   Streaming: ")
      _ <- chatModel.streamContent(conversation2)
        .evalMap((token: String) => IO.print(token))
        .compile
        .drain
      _ <- IO.println("")

      // 3. Multi-turn conversation
      _ <- ExampleUtils.printSubSection("3. Multi-turn Conversation")
      turn1 = Conversation(Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Tell me about dogs.")
      ))
      response1 <- chatModel.generate(turn1)
      _ <- IO.println(s"   User: Tell me about dogs.")
      _ <- IO.println(s"   Assistant: ${response1.content}")

      turn2 = Conversation(Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Tell me about dogs."),
        response1.message,
        UserMessage("Why do they bark?")
      ))
      response2 <- chatModel.generate(turn2)
      _ <- IO.println(s"   User: Why do they bark?")
      _ <- IO.println(s"   Assistant: ${response2.content}")

      _ <- IO.println("\nChatModel example completed.")
    yield ()
