package org.adk4s.structured.core

import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Conversation,
  Message as Llm4sMessage,
  MessageRole,
  SystemMessage,
  UserMessage
}

/**
 * Deprecated type alias for source compatibility.
 * Use `org.llm4s.llmconnect.model.Message` directly.
 *
 * spec: message-type-dedup
 */
@deprecated("Use org.llm4s.llmconnect.model.Message directly", "message-type-dedup")
type Message = Llm4sMessage

/**
 * Deprecated type alias for source compatibility.
 * Use `org.llm4s.llmconnect.model.MessageRole` directly.
 *
 * spec: message-type-dedup
 */
@deprecated("Use org.llm4s.llmconnect.model.MessageRole directly", "message-type-dedup")
type Role = MessageRole

/**
 * A complete prompt ready to be sent to an LLM.
 *
 * Wraps a llm4s `Conversation` directly, preserving all message metadata
 * (toolCallId, toolCalls, etc.) that was lost in the previous
 * `Vector[Message]` representation.
 *
 * Prompts are immutable and composable.
 *
 * spec: message-type-dedup
 */
final case class Prompt(conversation: Conversation):
  /**
   * Add a message to the end of this prompt's conversation.
   */
  def addMessage(message: Llm4sMessage): Prompt =
    Prompt(conversation.addMessage(message))

  def addSystemMessage(content: String): Prompt =
    addMessage(SystemMessage(content))

  def addUserMessage(content: String): Prompt =
    addMessage(UserMessage(content))

  def addAssistantMessage(content: String): Prompt =
    addMessage(AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty))

  /**
   * Append content to the last user message, or add a new user message if empty
   * or last message is not a UserMessage.
   */
  def appendToLast(content: String): Prompt =
    conversation.messages.lastOption match
      case Some(last: UserMessage) =>
        val updated: UserMessage           = UserMessage(last.content + content)
        val newMessages: Seq[Llm4sMessage] = conversation.messages.dropRight(1) :+ updated
        Prompt(Conversation(newMessages))
      case Some(_) =>
        addUserMessage(content)
      case None =>
        addUserMessage(content)

  /**
   * Inject the output format schema into the last user message.
   * If no user message exists, appends a new UserMessage with the schema block.
   *
   * spec: message-type-dedup
   */
  def withOutputFormat[A: Schema]: Prompt =
    val schema: Schema[A] = Schema[A]
    appendToLast("\n\n" + schema.outputFormatBlock)

  /**
   * Combine two prompts by concatenating their conversations.
   */
  def ++(other: Prompt): Prompt =
    Prompt(Conversation(conversation.messages ++ other.conversation.messages))

  /**
   * Check if this prompt is empty.
   */
  def isEmpty: Boolean = conversation.messages.isEmpty

  /**
   * Get the total character count (useful for token estimation).
   */
  def totalLength: Int = conversation.messages.map(_.content.length).sum

object Prompt:
  val empty: Prompt = Prompt(Conversation.empty())

  def apply(messages: Llm4sMessage*): Prompt =
    Prompt(Conversation(messages.toSeq))

  def single(message: Llm4sMessage): Prompt =
    Prompt(Conversation(Seq(message)))

  def system(content: String): Prompt =
    single(SystemMessage(content))

  def user(content: String): Prompt =
    single(UserMessage(content))

  /**
   * Create a simple prompt with a system message and user message.
   */
  def simple(systemPrompt: String, userMessage: String): Prompt =
    Prompt(
      Conversation(
        Seq(
          SystemMessage(systemPrompt),
          UserMessage(userMessage)
        )
      )
    )

/**
 * A prompt template that can be rendered with input variables.
 *
 * @tparam I The input type required to render this template
 */
trait PromptTemplate[-I]:
  /**
   * Render this template with the given input.
   */
  def render(input: I): Prompt

  /**
   * Compose with another template that runs after this one.
   */
  def andThen[I2 <: I](other: PromptTemplate[I2]): PromptTemplate[I2] =
    val self = this
    new PromptTemplate[I2]:
      def render(input: I2): Prompt = self.render(input) ++ other.render(input)

  /**
   * Transform the input before rendering.
   */
  def contramap[I2](f: I2 => I): PromptTemplate[I2] =
    val self = this
    new PromptTemplate[I2]:
      def render(input: I2): Prompt = self.render(f(input))

  /**
   * Add output format injection for a specific output type.
   */
  def expecting[A: Schema]: PromptTemplate[I] =
    val self = this
    new PromptTemplate[I]:
      def render(input: I): Prompt = self.render(input).withOutputFormat[A]

object PromptTemplate:
  /**
   * Create a template from a function.
   */
  def apply[I](f: I => Prompt): PromptTemplate[I] =
    new PromptTemplate[I]:
      def render(input: I): Prompt = f(input)

  /**
   * Create a static template (no input required).
   */
  def static(prompt: Prompt): PromptTemplate[Any] =
    new PromptTemplate[Any]:
      def render(input: Any): Prompt = prompt

  /**
   * Template that just uses the input string as a user message.
   */
  val userMessage: PromptTemplate[String] =
    PromptTemplate(input => Prompt.user(input))

  /**
   * Template with a fixed system prompt.
   */
  def withSystem(systemPrompt: String): PromptTemplate[String] =
    PromptTemplate(input => Prompt.simple(systemPrompt, input))
