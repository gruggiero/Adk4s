package org.adk4s.structured.core

import cats.effect.Async
import cats.syntax.all.*
import cats.syntax.either.*
import org.adk4s.structured.sap.SchemaAlignedParser
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message as LLM4sMessage,
  SystemMessage,
  ToolMessage,
  UserMessage
}

/**
 * A structured LLM client that wraps llm4s and provides type-safe completions.
 * 
 * The core abstraction is: `Prompt => F[A]`
 * 
 * Example usage:
 * ```scala
 * val structured = StructuredLLM.fromClient(llmClient)
 * 
 * val result: IO[Resume] = structured.complete[Resume](
 *   Prompt.simple(
 *     "You are an expert resume parser",
 *     "Parse: John Doe, 5 years Python experience..."
 *   )
 * )
 * ```
 */
trait StructuredLLM[F[_]]:
  /**
   * Complete a prompt and parse the response into the expected type.
   * The output format schema is automatically injected into the prompt.
   */
  def complete[A: Schema](prompt: Prompt): F[A]
  
  /**
   * Complete a prompt without schema injection.
   * Useful when you've already formatted the prompt with the schema.
   */
  def completeRaw[A: Schema](prompt: Prompt): F[A]
  
  /**
   * Complete using a template and input.
   */
  def completeTemplate[I, A: Schema](template: PromptTemplate[I], input: I): F[A] =
    complete[A](template.render(input))
  
  /**
   * Create a reusable function from a template.
   * This is the core `Prompt => F[A]` abstraction.
   */
  def function[I, A: Schema](template: PromptTemplate[I]): I => F[A]
  
  /**
   * Create a simple extraction function for a given type.
   */
  def extractor[A: Schema](systemPrompt: String): String => F[A]

/**
 * Errors that can occur during structured completion.
 */
sealed trait StructuredLLMError extends Throwable:
  def message: String
  override def getMessage: String = message

object StructuredLLMError:
  case class LLMCallFailed(
    underlying: LLMError,
    prompt: Prompt
  ) extends StructuredLLMError:
    def message: String = s"LLM call failed: ${underlying.toString}"
  
  case class ParseFailed(
    errors: List[ParseError],
    rawResponse: String
  ) extends StructuredLLMError:
    def message: String = 
      s"Failed to parse LLM response: ${errors.map(_.message).mkString("; ")}\n" +
      s"Raw response (truncated): ${rawResponse.take(500)}"
  
  case class EmptyResponse(
    prompt: Prompt  
  ) extends StructuredLLMError:
    def message: String = "LLM returned empty response"

object StructuredLLM:

  /**
   * Create a StructuredLLM from an llm4s LLMClient.
   */
  def fromClient[F[_]: Async](
    client: LLMClient,
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] = new StructuredLLMImpl[F](client, defaultOptions, logRawResponse = false)

  /**
   * Create a StructuredLLM from an llm4s LLMClient with raw response logging.
   */
  def fromClientWithLogging[F[_]: Async](
    client: LLMClient,
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] = new StructuredLLMImpl[F](client, defaultOptions, logRawResponse = true)

  /**
   * Create a StructuredLLM with custom completion options per call.
   */
  def fromClientWithOptions[F[_]: Async](
    client: LLMClient
  ): CompletionOptions => StructuredLLM[F] =
    options => new StructuredLLMImpl[F](client, options, logRawResponse = false)

/**
 * Implementation of StructuredLLM.
 */
private class StructuredLLMImpl[F[_]: Async](
  client: LLMClient,
  options: CompletionOptions,
  logRawResponse: Boolean
) extends StructuredLLM[F] {
  
  override def complete[A: Schema](prompt: Prompt): F[A] =
    // Inject the output format into the prompt
    val promptWithSchema = prompt.withOutputFormat[A]
    completeRaw[A](promptWithSchema)
  
  override def completeRaw[A: Schema](prompt: Prompt): F[A] =
    for
      // Convert to llm4s Conversation
      conversation <- Async[F].pure(toConversation(prompt))
      
      // Call the LLM
      completion <- callLLM(conversation, prompt)
      
      // Extract the response content
      responseContent <- extractContent(completion, prompt)
      
      // Parse with SAP
      result <- parseResponse[A](responseContent)
    yield result
  
  override def function[I, A: Schema](template: PromptTemplate[I]): I => F[A] =
    input => complete[A](template.render(input))
  
  override def extractor[A: Schema](systemPrompt: String): String => F[A] =
    val template = PromptTemplate.withSystem(systemPrompt)
    function[String, A](template)
  
  /**
   * Convert our Prompt to llm4s Conversation.
   */
  private def toConversation(prompt: Prompt): Conversation =
    val messages: Vector[LLM4sMessage] = prompt.messages.map { msg =>
      msg.role match
        case Role.System    => SystemMessage(msg.content)
        case Role.User      => UserMessage(msg.content)
        case Role.Assistant => AssistantMessage(Some(msg.content))
        case Role.Tool      => ToolMessage(msg.content, toolCallId = "tool-call-id")
    }
    Conversation(messages)
  
  /**
   * Call the underlying LLM.
   */
  private def callLLM(conversation: Conversation, originalPrompt: Prompt): F[Completion] =
    Async[F].fromEither(
      client.complete(conversation, options).leftMap(err => StructuredLLMError.LLMCallFailed(err, originalPrompt))
    )
  
  /**
   * Extract the text content from the completion.
   */
  private def extractContent(completion: Completion, prompt: Prompt): F[String] =
    val content = completion.content
    if content.trim.isEmpty then
      Async[F].raiseError(StructuredLLMError.EmptyResponse(prompt))
    else
      Async[F].pure(content)
  
  /**
   * Parse response using SAP.
   */
  private def parseResponse[A: Schema](response: String): F[A] =
    val parseAction = SchemaAlignedParser.parse[A](response) match
      case ParseResult.Success(value, warnings) =>
        Async[F].pure(value)
      case ParseResult.Failure(errors) =>
        Async[F].raiseError(StructuredLLMError.ParseFailed(errors, response))

    val logAction = if logRawResponse then
      Async[F].delay {
        println("\n" + "=" * 80)
        println("RAW LLM RESPONSE (before parsing):")
        println("=" * 80)
        println(response)
        println("=" * 80)
      }
    else
      Async[F].unit

    logAction *> parseAction
}

