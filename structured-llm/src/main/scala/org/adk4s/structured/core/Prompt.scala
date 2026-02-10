package org.adk4s.structured.core

/**
 * Role in a conversation message.
 */
enum Role:
  case System
  case User
  case Assistant
  case Tool

  def asString: String = this match
    case System    => "system"
    case User      => "user"
    case Assistant => "assistant"
    case Tool      => "tool"

/**
 * A single message in a conversation.
 */
final case class Message(
  role: Role,
  content: String
):
  /**
   * Append additional content to this message.
   */
  def append(additional: String): Message =
    copy(content = content + additional)

  /**
   * Prepend content to this message.
   */
  def prepend(prefix: String): Message =
    copy(content = prefix + content)

object Message:
  def system(content: String): Message    = Message(Role.System, content)
  def user(content: String): Message      = Message(Role.User, content)
  def assistant(content: String): Message = Message(Role.Assistant, content)

/**
 * A complete prompt ready to be sent to an LLM.
 *
 * Prompts are immutable and composable.
 */
final case class Prompt(messages: Vector[Message]):
  /**
   * Add a message to the end of this prompt.
   */
  def addMessage(message: Message): Prompt =
    Prompt(messages :+ message)

  def addSystemMessage(content: String): Prompt =
    addMessage(Message.system(content))

  def addUserMessage(content: String): Prompt =
    addMessage(Message.user(content))

  def addAssistantMessage(content: String): Prompt =
    addMessage(Message.assistant(content))

  /**
   * Append content to the last message, or add a new user message if empty.
   */
  def appendToLast(content: String): Prompt =
    if messages.isEmpty then addUserMessage(content)
    else Prompt(messages.init :+ messages.last.append(content))

  /**
   * Inject the output format schema into the last user message.
   */
  def withOutputFormat[A: Schema]: Prompt =
    val schema = Schema[A]
    appendToLast("\n\n" + schema.outputFormatBlock)

  /**
   * Combine two prompts.
   */
  def ++(other: Prompt): Prompt =
    Prompt(messages ++ other.messages)

  /**
   * Check if this prompt is empty.
   */
  def isEmpty: Boolean = messages.isEmpty

  /**
   * Get the total character count (useful for token estimation).
   */
  def totalLength: Int = messages.map(_.content.length).sum

object Prompt:
  val empty: Prompt = Prompt(Vector.empty)

  def apply(messages: Message*): Prompt =
    Prompt(messages.toVector)

  def single(message: Message): Prompt =
    Prompt(Vector(message))

  def system(content: String): Prompt =
    single(Message.system(content))

  def user(content: String): Prompt =
    single(Message.user(content))

  /**
   * Create a simple prompt with a system message and user message.
   */
  def simple(systemPrompt: String, userMessage: String): Prompt =
    Prompt(
      Vector(
        Message.system(systemPrompt),
        Message.user(userMessage)
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
