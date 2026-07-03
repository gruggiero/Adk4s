package org.adk4s.structured.core.typecontract

/** Typed contract for spec: message-type-dedup
  *
  * This file is a COMPILE-ONLY contract. It declares the new type signatures
  * that the implementation must honor.
  *
  * Key change: `Prompt` wraps `Conversation` directly instead of
  * `Vector[Message]`. The adk4s `Message` case class and `Role` enum are
  * replaced with deprecated type aliases pointing to the llm4s types.
  *
  * spec: message-type-dedup
  */

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Conversation,
  Message as Llm4sMessage,
  MessageRole,
  SystemMessage,
  ToolMessage,
  UserMessage
}

// ─────────────────────────────────────────────────────────────────
// CONTRACT 1: Deprecated type aliases
//
// `Message` and `Role` become deprecated type aliases pointing to the
// llm4s types. Existing code that imports them will compile with
// deprecation warnings.
// ─────────────────────────────────────────────────────────────────

@deprecated("Use org.llm4s.llmconnect.model.Message directly", "message-type-dedup")
type Message = Llm4sMessage

@deprecated("Use org.llm4s.llmconnect.model.MessageRole directly", "message-type-dedup")
type Role = MessageRole

// ─────────────────────────────────────────────────────────────────
// CONTRACT 2: Refactored Prompt wrapping Conversation
//
// Prompt now has a single `conversation: Conversation` field.
// The `messages: Vector[Message]` field is removed.
// Factory methods construct Conversations directly.
// ─────────────────────────────────────────────────────────────────

object RefactoredPromptContract:

  /** The refactored Prompt shape — single conversation field. */
  final case class PromptShape(conversation: Conversation):
    /** Add a message to the end of the conversation. */
    def addMessage(message: Llm4sMessage): PromptShape =
      PromptShape(conversation.addMessage(message))

    /** Add a system message. */
    def addSystemMessage(content: String): PromptShape =
      addMessage(SystemMessage(content))

    /** Add a user message. */
    def addUserMessage(content: String): PromptShape =
      addMessage(UserMessage(content))

    /** Add an assistant message. */
    def addAssistantMessage(content: String): PromptShape =
      addMessage(AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty))

    /** Append content to the last user message, or add a new user message. */
    def appendToLast(content: String): PromptShape =
      conversation.messages.lastOption match
        case Some(last: UserMessage) =>
          val updated: UserMessage = UserMessage(last.content + content)
          val newMessages: Seq[Llm4sMessage] = conversation.messages.dropRight(1) :+ updated
          PromptShape(Conversation(newMessages))
        case Some(_) =>
          addUserMessage(content)
        case None =>
          addUserMessage(content)

    /** Check if empty. */
    def isEmpty: Boolean = conversation.messages.isEmpty

    /** Total character count. */
    def totalLength: Int = conversation.messages.map(_.content.length).sum

    /** Combine two prompts. */
    def ++(other: PromptShape): PromptShape =
      PromptShape(Conversation(conversation.messages ++ other.conversation.messages))

  object PromptShape:
    val empty: PromptShape = PromptShape(Conversation.empty())

    def apply(messages: Llm4sMessage*): PromptShape =
      PromptShape(Conversation(messages.toSeq))

    def single(message: Llm4sMessage): PromptShape =
      PromptShape(Conversation(Seq(message)))

    def system(content: String): PromptShape =
      single(SystemMessage(content))

    def user(content: String): PromptShape =
      single(UserMessage(content))

    def simple(systemPrompt: String, userMessage: String): PromptShape =
      PromptShape(Conversation(Seq(
        SystemMessage(systemPrompt),
        UserMessage(userMessage)
      )))

// ─────────────────────────────────────────────────────────────────
// CONTRACT 3: withOutputFormat appends schema to last user message
// ─────────────────────────────────────────────────────────────────

object WithOutputFormatContract:
  import RefactoredPromptContract.*

  /** Appends the schema block to the last UserMessage in the conversation.
    * If no UserMessage exists, appends a new UserMessage with the schema block.
    */
  def withOutputFormat[A](prompt: PromptShape, schemaBlock: String): PromptShape =
    val messages: Seq[Llm4sMessage] = prompt.conversation.messages
    messages.lastOption match
      case Some(last: UserMessage) =>
        val updated: UserMessage = UserMessage(last.content + "\n\n" + schemaBlock)
        PromptShape(Conversation(messages.dropRight(1) :+ updated))
      case Some(_) =>
        // Last message is not a UserMessage — append a new one
        PromptShape(Conversation(messages :+ UserMessage(schemaBlock)))
      case None =>
        // Empty conversation — add a UserMessage with the schema block
        PromptShape(Conversation(Seq(UserMessage(schemaBlock))))

// ─────────────────────────────────────────────────────────────────
// CONTRACT 4: toConversation is identity
//
// StructuredLLMImpl.toConversation(prompt) now returns prompt.conversation
// directly — no conversion needed.
// ─────────────────────────────────────────────────────────────────

object ToConversationIdentityContract:
  import RefactoredPromptContract.*

  def toConversation(prompt: PromptShape): Conversation =
    prompt.conversation // identity — no conversion

// ─────────────────────────────────────────────────────────────────
// COMPILE-NEGATIVE OBLIGATIONS
//
// `Prompt(messages = Vector(Message(Role.User, "x")))` must not compile
// after the refactor because:
// 1. The `messages` field is removed (replaced by `conversation`)
// 2. The adk4s `Message` case class is removed (replaced by type alias)
// 3. The adk4s `Role` enum is removed (replaced by type alias)
// ─────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────
// PROPERTY OBLIGATIONS (structured comments — implemented in test oracle)
//
// Property 1: Conversation round-trip is identity
//   forAll { (conv: Conversation) =>
//     Prompt(conv).conversation == conv
//   }
//
// Property 2: withOutputFormat preserves non-last messages
//   forAll { (prompt: Prompt) =>
//     val withSchema = prompt.withOutputFormat[BankTransaction]
//     withSchema.conversation.messages.dropRight(1) == prompt.conversation.messages.dropRight(1)
//   }
//
// Property 3: SerializableMessage round-trip preserves role and content
//   forAll { (sm: SerializableMessage) =>
//     read[SerializableMessage](write(sm)) == sm
//   }
// ─────────────────────────────────────────────────────────────────
