package org.adk4s.structured.template

import org.adk4s.structured.core.{Message, Prompt, PromptTemplate, Role, Schema}

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
    
    def toRole: Option[Role] = this match
      case System    => Some(Role.System)
      case User      => Some(Role.User)
      case Assistant => Some(Role.Assistant)
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
          val content: String = buildString(sc.parts, evaluatedArgs)
          val messages: List[Message] = parseMessages(content)
          Prompt(messages.toVector)
    
    /**
     * System message interpolator.
     */
    def system(args: Matchable*): Message =
      Message.system(buildString(sc.parts, args))
    
    /**
     * User message interpolator.
     */
    def user(args: Matchable*): Message =
      Message.user(buildString(sc.parts, args))
    
    /**
     * Assistant message interpolator.
     */
    def assistant(args: Matchable*): Message =
      Message.assistant(buildString(sc.parts, args))
  
  /**
   * Build a string from StringContext parts and arguments.
   */
  private def buildString(parts: Seq[String], args: Seq[Matchable]): String =
    val sb = StringBuilder()
    val partsIter = parts.iterator
    val argsIter = args.iterator
    
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
    if s.contains('|') then
      s.stripMargin.trim
    else
      s.trim
  
  /**
   * Parse messages from a formatted string.
   * Supports XML-like tags: <s>, <system>, <u>, <user>, <a>, <assistant>
   */
  private def parseMessages(content: String): List[Message] =
    val systemPattern = """(?s)<(?:s|system)>(.*?)</(?:s|system)>""".r
    val userPattern = """(?s)<(?:u|user)>(.*?)</(?:u|user)>""".r
    val assistantPattern = """(?s)<(?:a|assistant)>(.*?)</(?:a|assistant)>""".r
    
    case class TaggedSection(role: Role, content: String, start: Int)
    
    val sections = scala.collection.mutable.ListBuffer[TaggedSection]()
    
    // Find all tagged sections with their positions
    for m <- systemPattern.findAllMatchIn(content) do
      sections += TaggedSection(Role.System, m.group(1).trim, m.start)
    
    for m <- userPattern.findAllMatchIn(content) do
      sections += TaggedSection(Role.User, m.group(1).trim, m.start)
    
    for m <- assistantPattern.findAllMatchIn(content) do
      sections += TaggedSection(Role.Assistant, m.group(1).trim, m.start)
    
    if sections.isEmpty then
      // No tags found - treat entire content as a user message
      List(Message.user(content.trim))
    else
      // Sort by position and convert to messages
      sections.sortBy(_.start).map(s => Message(s.role, s.content)).toList
  
  /**
   * Builder for more complex prompts.
   */
  class PromptBuilder:
    private val messages = scala.collection.mutable.ListBuffer[Message]()
    
    def system(content: String): this.type =
      messages += Message.system(content)
      this
    
    def user(content: String): this.type =
      messages += Message.user(content)
      this
    
    def assistant(content: String): this.type =
      messages += Message.assistant(content)
      this
    
    def message(role: Role, content: String): this.type =
      messages += Message(role, content)
      this
    
    def withOutputFormat[A: Schema]: this.type =
      val lastIdx = messages.length - 1
      if lastIdx >= 0 then
        val last = messages(lastIdx)
        messages(lastIdx) = last.append("\n\n" + Schema[A].outputFormatBlock)
      this
    
    def build: Prompt = Prompt(messages.toVector)
  
  /**
   * Start building a prompt.
   */
  def buildPrompt: PromptBuilder = new PromptBuilder

/**
 * Alternative syntax using a more functional builder pattern.
 */
object dsl:
  import org.adk4s.structured.core.*
  
  /**
   * Create a prompt template using a builder function.
   */
  def template[I](f: I => PromptBuilder): PromptTemplate[I] =
    new PromptTemplate[I]:
      def render(input: I): Prompt = f(input).build
  
  /**
   * Builder that accumulates messages.
   */
  class PromptBuilder private[dsl] (messages: Vector[Message]):
    def system(content: String): PromptBuilder =
      PromptBuilder(messages :+ Message.system(content))
    
    def user(content: String): PromptBuilder =
      PromptBuilder(messages :+ Message.user(content))
    
    def assistant(content: String): PromptBuilder =
      PromptBuilder(messages :+ Message.assistant(content))
    
    def outputFormat[A: Schema]: PromptBuilder =
      if messages.isEmpty then this
      else
        val last = messages.last
        val updated = last.append("\n\n" + Schema[A].outputFormatBlock)
        PromptBuilder(messages.init :+ updated)
    
    def build: Prompt = Prompt(messages)
  
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
