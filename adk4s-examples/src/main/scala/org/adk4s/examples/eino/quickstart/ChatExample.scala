package org.adk4s.examples.eino.quickstart

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.Message
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: quickstart/chat
 *
 * Basic multi-turn chat with ChatModel.
 * Shows conversation construction with message history,
 * both generate (invoke) and stream modes.
 */
object ChatExample extends IOApp.Simple:

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("Quickstart Chat Example (Eino: quickstart/chat)")
      chatModel <- ExampleUtils.createChatModel

      // 1. Single-turn generate
      _ <- ExampleUtils.printSubSection("1. Single-turn Generate")
      conv1 = Conversation(Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("What is 2 + 2?")
      ))
      response1 <- chatModel.generate(conv1)
      _ <- IO.println(s"   User: What is 2 + 2?")
      _ <- IO.println(s"   Assistant: ${response1.content}")

      // 2. Multi-turn conversation
      _ <- ExampleUtils.printSubSection("2. Multi-turn Conversation")
      messages1: Seq[Message] = Seq(
        SystemMessage("You are a helpful assistant."),
        UserMessage("Tell me about cats.")
      )
      turn1 <- chatModel.generate(Conversation(messages1))
      _ <- IO.println(s"   User: Tell me about cats.")
      _ <- IO.println(s"   Assistant: ${turn1.content}")

      messages2: Seq[Message] = messages1 ++ Seq(turn1.message, UserMessage("Why do they purr?"))
      turn2 <- chatModel.generate(Conversation(messages2))
      _ <- IO.println(s"   User: Why do they purr?")
      _ <- IO.println(s"   Assistant: ${turn2.content}")

      messages3: Seq[Message] = messages2 ++ Seq(turn2.message, UserMessage("Are they good pets?"))
      turn3 <- chatModel.generate(Conversation(messages3))
      _ <- IO.println(s"   User: Are they good pets?")
      _ <- IO.println(s"   Assistant: ${turn3.content}")

      // 3. Streaming mode
      _ <- ExampleUtils.printSubSection("3. Streaming Mode (token-by-token)")
      streamConv = Conversation(Seq(
        SystemMessage("You are a dog."),
        UserMessage("Why do dogs bark?")
      ))
      _ <- IO.print("   Streaming: ")
      _ <- chatModel.streamContent(streamConv)
        .evalMap((token: String) => IO.print(token))
        .compile
        .drain
      _ <- IO.println("")

      _ <- IO.println("\nQuickstart chat example completed.")
    yield ()
