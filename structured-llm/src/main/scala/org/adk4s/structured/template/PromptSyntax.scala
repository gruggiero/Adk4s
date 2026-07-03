package org.adk4s.structured.template

import org.adk4s.structured.core.{ Prompt, PromptTemplate, Schema }
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Conversation,
  Message as Llm4sMessage,
  MessageRole,
  SystemMessage,
  ToolMessage,
  UserMessage
}

/**
 * Custom string interpolators for creating prompt templates.
 *
 * Provides a DSL for building structured prompts with:
 * - Multi-line support with proper whitespace handling
 * - Variable interpolation
 * - Structured sections (system, user, assistant)
 * - Output format injection
 *
 * Example usage:
 * ```scala
 * import org.adk4s.structured.template.syntax.*
 *
 * val template = prompt"""
 *   |<s>You are an expert at ${_.expertise}</s>
 *   |<u>Please help me with: ${_.task}</u>
 * """
 *
 * case class Input(expertise: String, task: String)
 * val rendered: Prompt = template.render(Input("cooking", "making pasta"))
 * ```
 */
object syntax:

  /**
   * Marker for output format injection.
   */
  case class OutputFormat[A: Schema]():
    def toBlock: String = Schema[A].outputFormatBlock

  /**
   * Helper to create output format markers.
   */
  def outputFormat[A: Schema]: OutputFormat[A] = OutputFormat[A]()

  /**
   * Section types for structured prompts.
   */
  enum SectionType:
    case System
    case User
    case Assistant
    case Raw

    def toRole: Option[MessageRole] = this match
      case System    => Some(MessageRole.System)
      case User      => Some(MessageRole.User)
      case Assistant => Some(MessageRole.Assistant)
      case Raw       => None

  /**
   * A section of a prompt with a role and content.
   */
  case class PromptSection(
    sectionType: SectionType,
    content: String
  )

  /**
   * Extension for custom string interpolation.
   */
  extension (sc: StringContext)

    /**
     * Type-safe prompt interpolator that renders using an input value.
     * Also supports output format markers.
     */
    def prompt[I](args: (I => Matchable)*): PromptTemplate[I] =
      new PromptTemplate[I]:
        def render(input: I): Prompt =
          val evaluatedArgs: Seq[Matchable] = args.map(f => f(input))
          val content: String               = buildString(sc.parts, evaluatedArgs)
          val messages: List[Llm4sMessage]  = parseMessages(content)
          Prompt(messages*)

    /**
     * System message interpolator.
     */
    def system(args: Matchable*): Llm4sMessage =
      SystemMessage(buildString(sc.parts, args))

    /**
     * User message interpolator.
     */
    def user(args: Matchable*): Llm4sMessage =
      UserMessage(buildString(sc.parts, args))

    /**
     * Assistant message interpolator.
     */
    def assistant(args: Matchable*): Llm4sMessage =
      AssistantMessage(contentOpt = Some(buildString(sc.parts, args)), toolCalls = Seq.empty)

  /**
   * Build a string from StringContext parts and arguments.
   */
  private def buildString(parts: Seq[String], args: Seq[Matchable]): String =
    val sb        = StringBuilder()
    val partsIter = parts.iterator
    val argsIter  = args.iterator

    sb.append(partsIter.next())
    while argsIter.hasNext do
      val arg = argsIter.next()
      val argStr = arg match
        case of: OutputFormat[?] => of.toBlock
        case other               => other.toString
      sb.append(argStr)
      sb.append(partsIter.next())

    // Handle stripMargin-style formatting
    processMargins(sb.toString)

  /**
   * Process margin stripping (lines starting with |).
   */
  private def processMargins(s: String): String =
    if s.contains('|') then s.stripMargin.trim
    else s.trim

  private def messageForRole(role: MessageRole, content: String): Llm4sMessage = role match
    case MessageRole.System    => SystemMessage(content)
    case MessageRole.User      => UserMessage(content)
    case MessageRole.Assistant => AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
    // Tool results keep their role label rather than being silently mislabelled as
    // a user message. No toolCallId is available here, so it is left empty.
    case MessageRole.Tool      => ToolMessage(content, toolCallId = "")

  /**
   * Parse messages from a formatted string.
   * Supports XML-like tags: <s>, <system>, <u>, <user>, <a>, <assistant>
   */
  private def parseMessages(content: String): List[Llm4sMessage] =
    val systemPattern    = """(?s)<(?:s|system)>(.*?)</(?:s|system)>""".r
    val userPattern      = """(?s)<(?:u|user)>(.*?)</(?:u|user)>""".r
    val assistantPattern = """(?s)<(?:a|assistant)>(.*?)</(?:a|assistant)>""".r

    case class TaggedSection(role: MessageRole, content: String, start: Int)

    val sections = scala.collection.mutable.ListBuffer[TaggedSection]()

    // Find all tagged sections with their positions
    for m <- systemPattern.findAllMatchIn(content) do
      sections += TaggedSection(MessageRole.System, m.group(1).trim, m.start)

    for m <- userPattern.findAllMatchIn(content) do
      sections += TaggedSection(MessageRole.User, m.group(1).trim, m.start)

    for m <- assistantPattern.findAllMatchIn(content) do
      sections += TaggedSection(MessageRole.Assistant, m.group(1).trim, m.start)

    if sections.isEmpty then
      // No tags found - treat entire content as a user message
      List(UserMessage(content.trim))
    else
      // Sort by position and convert to messages
      sections.sortBy(_.start).map(s => messageForRole(s.role, s.content)).toList

  /**
   * Builder for more complex prompts.
   */
  class PromptBuilder:
    private val messages = scala.collection.mutable.ListBuffer[Llm4sMessage]()

    def system(content: String): this.type =
      messages += SystemMessage(content)
      this

    def user(content: String): this.type =
      messages += UserMessage(content)
      this

    def assistant(content: String): this.type =
      messages += AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty)
      this

    def message(role: MessageRole, content: String): this.type =
      messages += messageForRole(role, content)
      this

    def withOutputFormat[A: Schema]: this.type =
      val lastIdx = messages.length - 1
      if lastIdx >= 0 then
        val last                = messages(lastIdx)
        val schemaBlock: String = "\n\n" + Schema[A].outputFormatBlock
        last match
          case um: UserMessage => messages(lastIdx) = UserMessage(um.content + schemaBlock)
          case _               => messages += UserMessage(schemaBlock)
      this

    def build: Prompt = Prompt(messages.toSeq*)

  /**
   * Start building a prompt.
   */
  def buildPrompt: PromptBuilder = new PromptBuilder

/**
 * Alternative syntax using a more functional builder pattern.
 */
object dsl:
  import org.adk4s.structured.core.{ Prompt, PromptTemplate, Schema }

  /**
   * Create a prompt template using a builder function.
   */
  def template[I](f: I => PromptBuilder): PromptTemplate[I] =
    new PromptTemplate[I]:
      def render(input: I): Prompt = f(input).build

  /**
   * Builder that accumulates messages.
   */
  class PromptBuilder private[dsl] (messages: Vector[Llm4sMessage]):
    def system(content: String): PromptBuilder =
      PromptBuilder(messages :+ SystemMessage(content))

    def user(content: String): PromptBuilder =
      PromptBuilder(messages :+ UserMessage(content))

    def assistant(content: String): PromptBuilder =
      PromptBuilder(messages :+ AssistantMessage(contentOpt = Some(content), toolCalls = Seq.empty))

    def outputFormat[A: Schema]: PromptBuilder =
      messages.lastOption match
        case Some(last) =>
          val schemaBlock: String = "\n\n" + Schema[A].outputFormatBlock
          last match
            case um: UserMessage =>
              val updated: UserMessage = UserMessage(um.content + schemaBlock)
              PromptBuilder(messages.dropRight(1) :+ updated)
            case _ =>
              PromptBuilder(messages :+ UserMessage(schemaBlock))
        case None => this

    def build: Prompt = Prompt(messages*)

  object PromptBuilder:
    def empty: PromptBuilder = PromptBuilder(Vector.empty)

  /**
   * Start a prompt with a system message.
   */
  def systemMessage(content: String): PromptBuilder =
    PromptBuilder.empty.system(content)

  /**
   * Start a prompt with a user message.
   */
  def userMessage(content: String): PromptBuilder =
    PromptBuilder.empty.user(content)
