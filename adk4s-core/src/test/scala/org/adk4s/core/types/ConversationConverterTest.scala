package org.adk4s.core.types

import munit.CatsEffectSuite
import org.adk4s.structured.core.{ Message as AdkMessage, Role as AdkRole, Prompt }
import org.llm4s.llmconnect.model.{ Conversation as Llm4sConversation, SystemMessage, UserMessage, AssistantMessage }
import org.adk4s.core.types.ConversationConverter.asConversation
import org.adk4s.core.types.ConversationConverter.asPrompt

class ConversationConverterTest extends CatsEffectSuite:

  test("convert empty prompt to conversation") {
    val prompt = Prompt(Vector.empty)
    val conv   = prompt.asConversation
    assertEquals(conv.messages, Seq.empty)
  }

  test("convert single-message prompt to conversation") {
    val prompt = Prompt(Vector(AdkMessage(AdkRole.System, "You are a helpful assistant")))
    val conv   = prompt.asConversation
    assertEquals(conv.messages.size, 1, "Should have one message")
    assertEquals(conv.messages(0), SystemMessage("You are a helpful assistant"))
  }

  test("convert multi-message prompt to conversation with correct order") {
    val prompt = Prompt(
      Vector(
        AdkMessage(AdkRole.System, "You are a helpful assistant"),
        AdkMessage(AdkRole.User, "Hello!"),
        AdkMessage(AdkRole.Assistant, "Hi there!")
      )
    )
    val conv = prompt.asConversation
    assertEquals(conv.messages.size, 3, "Should have three messages")
    assertEquals(conv.messages(0), SystemMessage("You are a helpful assistant"))
    assertEquals(conv.messages(1), UserMessage("Hello!"))
    assertEquals(conv.messages(2), AssistantMessage(Some("Hi there!"), Seq.empty))
  }

  test("convert conversation to prompt") {
    val conv = Llm4sConversation(
      Seq(
        SystemMessage("System prompt"),
        UserMessage("User message"),
        AssistantMessage(Some("Assistant response"), Seq.empty)
      )
    )
    val prompt = conv.asPrompt
    assertEquals(prompt.messages.size, 3, "Should have three messages")
    assertEquals(prompt.messages(0), AdkMessage(AdkRole.System, "System prompt"))
    assertEquals(prompt.messages(1), AdkMessage(AdkRole.User, "User message"))
    assertEquals(prompt.messages(2), AdkMessage(AdkRole.Assistant, "Assistant response"))
  }

  test("round-trip conversation conversion preserves data") {
    val original = Prompt(
      Vector(
        AdkMessage(AdkRole.System, "System"),
        AdkMessage(AdkRole.User, "User"),
        AdkMessage(AdkRole.Assistant, "Assistant")
      )
    )
    val converted = original.asConversation.asPrompt
    assertEquals(converted.messages.size, original.messages.size)
    assert(clue(converted.messages.zip(original.messages).forall { case (a, b) =>
      a.role == b.role && a.content == b.content
    }))
  }

  test("round-trip conversion preserves message order") {
    val original = Prompt(
      Vector(
        AdkMessage(AdkRole.System, "First"),
        AdkMessage(AdkRole.User, "Second"),
        AdkMessage(AdkRole.Assistant, "Third"),
        AdkMessage(AdkRole.User, "Fourth"),
        AdkMessage(AdkRole.Assistant, "Fifth")
      )
    )
    val converted = original.asConversation.asPrompt
    assertEquals(converted.messages.map(_.content), original.messages.map(_.content))
  }
