package org.adk4s.examples.eino.agent

import cats.effect.IO
import cats.effect.IOApp
import org.adk4s.examples.eino.common.ExampleUtils
import org.adk4s.examples.eino.common.MockChatModel
import org.llm4s.llmconnect.model.Completion
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.llmconnect.model.UserMessage

/**
 * Eino equivalent: agent/react/memory
 *
 * Demonstrates a ReAct-style agent loop with conversation memory.
 * Uses ChatModel directly (no llm4s Agent class) to show the pattern:
 *   1. User asks a question
 *   2. Agent reasons and responds
 *   3. Conversation history is preserved for follow-up questions
 *   4. Agent can reference earlier context
 *
 * This is a simplified version — a full ReAct agent would include
 * tool calling in the loop (see ToolCallAgentExample for that pattern).
 */
object ReactMemoryExample extends IOApp.Simple:

  final case class ConversationMemory(
    messages: List[org.llm4s.llmconnect.model.Message],
    systemPrompt: String
  ):
    def addUser(content: String): ConversationMemory =
      copy(messages = messages :+ UserMessage(content))

    def addAssistant(msg: org.llm4s.llmconnect.model.Message): ConversationMemory =
      copy(messages = messages :+ msg)

    def toConversation: Conversation =
      Conversation(SystemMessage(systemPrompt) +: messages)

  def run: IO[Unit] =
    for
      _ <- ExampleUtils.printSection("ReAct Memory Example (Eino: agent/react/memory)")
      chatModel <- ExampleUtils.createChatModel

      systemPrompt = "You are a helpful assistant. Remember everything the user tells you and use it in later responses."
      initialMemory = ConversationMemory(List.empty, systemPrompt)

      // Turn 1: User introduces themselves
      _ <- ExampleUtils.printSubSection("Turn 1: Introduction")
      memory1 = initialMemory.addUser("My name is Alice and I'm a software engineer.")
      response1 <- chatModel.generate(memory1.toConversation)
      memory2 = memory1.addAssistant(response1.message)
      _ <- IO.println(s"   User: My name is Alice and I'm a software engineer.")
      _ <- IO.println(s"   Agent: ${response1.content}")

      // Turn 2: Ask about a topic
      _ <- ExampleUtils.printSubSection("Turn 2: Topic Question")
      memory3 = memory2.addUser("What are the benefits of functional programming?")
      response2 <- chatModel.generate(memory3.toConversation)
      memory4 = memory3.addAssistant(response2.message)
      _ <- IO.println(s"   User: What are the benefits of functional programming?")
      _ <- IO.println(s"   Agent: ${response2.content}")

      // Turn 3: Follow-up referencing earlier context
      _ <- ExampleUtils.printSubSection("Turn 3: Follow-up (tests memory)")
      memory5 = memory4.addUser("How would those benefits help someone in my profession?")
      response3 <- chatModel.generate(memory5.toConversation)
      memory6 = memory5.addAssistant(response3.message)
      _ <- IO.println(s"   User: How would those benefits help someone in my profession?")
      _ <- IO.println(s"   Agent: ${response3.content}")

      // Turn 4: Test memory recall
      _ <- ExampleUtils.printSubSection("Turn 4: Memory Recall")
      memory7 = memory6.addUser("What's my name and what do I do?")
      response4 <- chatModel.generate(memory7.toConversation)
      _ <- IO.println(s"   User: What's my name and what do I do?")
      _ <- IO.println(s"   Agent: ${response4.content}")

      // Show conversation stats
      _ <- ExampleUtils.printSubSection("Conversation Stats")
      _ <- IO.println(s"   Total messages in memory: ${memory7.messages.size + 1}")
      _ <- IO.println(s"   User messages: ${memory7.messages.count(_.role.toString == "user")}")

      _ <- IO.println("\nReAct memory example completed.")
    yield ()
