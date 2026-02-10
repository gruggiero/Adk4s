package org.adk4s.core.types

import org.adk4s.structured.core.Prompt
import org.llm4s.llmconnect.model.Conversation
import org.adk4s.core.types.MessageConverter.asLlm4s
import org.adk4s.core.types.MessageConverter.asAdk

object ConversationConverter:
  def toConversation(prompt: Prompt): Conversation =
    Conversation(messages = prompt.messages.map(_.asLlm4s))

  def fromConversation(conv: Conversation): Prompt =
    Prompt(messages = conv.messages.map(_.asAdk).toVector)

  extension (prompt: Prompt) def asConversation: Conversation = toConversation(prompt)

  extension (conv: Conversation) def asPrompt: Prompt = fromConversation(conv)
