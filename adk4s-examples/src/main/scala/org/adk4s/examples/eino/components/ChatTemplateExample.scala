package org.adk4s.examples.eino.components

import cats.effect.IO
import cats.effect.IOApp
import cats.syntax.foldable.toFoldableOps
import org.adk4s.core.component.ChatTemplate
import org.llm4s.llmconnect.model.{ Message as Llm4sMessage, SystemMessage, UserMessage }
import org.adk4s.examples.eino.common.ExampleUtils
import org.llm4s.llmconnect.model.Conversation

/**
 * Eino equivalent: components/prompt
 *
 * Demonstrates ChatTemplate with variable substitution,
 * template rendering into Conversation, and multiple templates.
 */
object ChatTemplateExample extends IOApp.Simple:

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ChatTemplate Example (Eino: components/prompt)")
      chatModel <- ExampleUtils.createChatModel

      // 1. Simple template with variable substitution
      _ <- ExampleUtils.printSubSection("1. Simple Template with Variables")
      template1: ChatTemplate[IO, Map[String, String]] = ChatTemplate.simple[IO](List(
        SystemMessage("You are a {role}. Respond in character."),
        UserMessage("{question}")
      ))
      conv1 <- template1.formatConversation(Map(
        "role" -> "pirate",
        "question" -> "What is the meaning of life?"
      ))
      _ <- IO.println(s"   Messages: ${conv1.messages.size}")
      _ <- conv1.messages.toList.traverse_ { (msg: Llm4sMessage) =>
        IO.println(s"   [${msg.role}]: ${msg.content}")
      }
      response1 <- chatModel.generate(conv1)
      _ <- IO.println(s"   Response: ${response1.content}")

      // 2. Multiple templates for different personas
      _ <- ExampleUtils.printSubSection("2. Multiple Templates (Different Personas)")
      catTemplate: ChatTemplate[IO, Map[String, String]] = ChatTemplate.simple[IO](List(
        SystemMessage("You are a cat. Always respond with cat-like behavior."),
        UserMessage("{question}")
      ))
      dogTemplate: ChatTemplate[IO, Map[String, String]] = ChatTemplate.simple[IO](List(
        SystemMessage("You are a dog. Always respond with dog-like behavior."),
        UserMessage("{question}")
      ))
      catConv <- catTemplate.formatConversation(Map("question" -> "How are you?"))
      dogConv <- dogTemplate.formatConversation(Map("question" -> "How are you?"))
      catResponse <- chatModel.generate(catConv)
      dogResponse <- chatModel.generate(dogConv)
      _ <- IO.println(s"   Cat says: ${catResponse.content}")
      _ <- IO.println(s"   Dog says: ${dogResponse.content}")

      // 3. Template with multiple variables
      _ <- ExampleUtils.printSubSection("3. Template with Multiple Variables")
      template3: ChatTemplate[IO, Map[String, String]] = ChatTemplate.simple[IO](List(
        SystemMessage("You are an expert in {topic}. Your audience is {audience}."),
        UserMessage("Explain {concept} in simple terms.")
      ))
      conv3 <- template3.formatConversation(Map(
        "topic" -> "astronomy",
        "audience" -> "children",
        "concept" -> "black holes"
      ))
      _ <- conv3.messages.toList.traverse_ { (msg: Llm4sMessage) =>
        IO.println(s"   [${msg.role}]: ${msg.content}")
      }
      response3 <- chatModel.generate(conv3)
      _ <- IO.println(s"   Response: ${response3.content}")

      _ <- IO.println("\nChatTemplate example completed.")
    yield ()
