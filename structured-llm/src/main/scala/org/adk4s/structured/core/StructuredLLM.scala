package org.adk4s.structured.core

import cats.effect.Async
import cats.effect.Temporal
import cats.syntax.all.*
import cats.syntax.either.*
import fs2.Stream
import org.adk4s.structured.sap.SchemaAlignedParser
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  CompletionOptions,
  Conversation,
  Message as LLM4sMessage,
  StreamedChunk,
  SystemMessage,
  ToolMessage,
  UserMessage
}
import scala.concurrent.duration.Duration

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
   * Stream a prompt and parse the final response into the expected type.
   * The output format schema is automatically injected into the prompt.
   *
   * Returns a tuple of:
   * - Stream[F, String]: Token stream for progressive display
   * - F[A]: Final parsed result after stream completes
   *
   * Example usage:
   * ```scala
   * val (tokenStream, resultF) = structured.streamWithResult[Resume](prompt)
   *
   * // Display tokens as they arrive
   * tokenStream.evalMap(token => IO.print(token)).compile.drain.unsafeRunSync()
   *
   * // Get final parsed result
   * val resume: Resume = resultF.unsafeRunSync()
   * ```
   */
  def streamWithResult[A: Schema](prompt: Prompt): F[(fs2.Stream[F, String], F[A])]

  /**
   * Stream a prompt without schema injection.
   * Useful when you've already formatted the prompt with the schema.
   */
  def streamWithResultRaw[A: Schema](prompt: Prompt): F[(fs2.Stream[F, String], F[A])]

  /**
   * Complete a prompt and parse the response, returning the parsed value
   * along with all constraint check results.
   *
   * @check constraints are evaluated and their results are included in
   * ValidationResult.checks. @assert constraints are evaluated and
   * raise ValidationFailed if any fail.
   */
  def completeValidated[A: Schema](prompt: Prompt): F[ValidationResult[A]]

  /**
   * Stream a prompt and emit partial structured values during streaming.
   *
   * Returns a stream of StreamState values, with the final emission being
   * a Complete state containing the fully parsed value.
   *
   * The output format schema is automatically injected into the prompt.
   */
  def streamPartial[A: Schema](prompt: Prompt): fs2.Stream[F, org.adk4s.structured.sap.StreamState[A]]

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

  case class ValidationFailed(
    failedAsserts: Vector[String]
  ) extends StructuredLLMError:
    def message: String = s"Validation failed: ${failedAsserts.mkString(", ")}"

  /**
   * Enriched error — wraps an underlying error with context about
   * all attempts made (client name, error, raw response, timestamp).
   */
  case class Enriched(
    underlying: StructuredLLMError,
    attempts: Vector[AttemptRecord]
  ) extends StructuredLLMError:
    def message: String =
      val attemptDetails: String = attempts.zipWithIndex.map { case (record, idx) =>
        s"  Attempt ${idx + 1} (${record.client}): ${record.error.message}"
      }.mkString("\n")
      s"${underlying.message}\nAttempts:\n$attemptDetails"

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
   * Create a StructuredLLM that retries on parse failures (not just LLM errors).
   *
   * llm4s's ReliableClient only retries on LLMError. This factory wraps the
   * StructuredLLM to also retry when parsing fails, using the given trigger
   * to control which failure types trigger retries.
   *
   * @param client The underlying LLM client
   * @param maxAttempts Maximum number of attempts (including the first)
   * @param delay Delay between retry attempts
   * @param trigger Which failure types trigger retries
   * @param defaultOptions Completion options to use
   */
  def fromClientWithRetry[F[_]: Async](
    client: LLMClient,
    maxAttempts: Int,
    delay: Duration,
    trigger: RetryTrigger,
    defaultOptions: CompletionOptions = CompletionOptions()
  ): StructuredLLM[F] =
    val underlying: StructuredLLM[F] = new StructuredLLMImpl[F](client, defaultOptions, logRawResponse = false)
    new RetryStructuredLLM[F](underlying, maxAttempts, delay, trigger)

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

      // Log if enabled
      _ <- logRawResponseIfEnabled(responseContent)

      // Parse with SAP
      result <- parseResponse[A](responseContent)
    yield result

  override def function[I, A: Schema](template: PromptTemplate[I]): I => F[A] =
    input => complete[A](template.render(input))

  override def extractor[A: Schema](systemPrompt: String): String => F[A] =
    val template = PromptTemplate.withSystem(systemPrompt)
    function[String, A](template)

  override def streamWithResult[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])] =
    // Inject the output format into the prompt
    val promptWithSchema: Prompt = prompt.withOutputFormat[A]
    streamWithResultRaw[A](promptWithSchema)

  override def streamWithResultRaw[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])] =
    Async[F].delay {
      // Convert to llm4s Conversation
      val conversation: Conversation = toConversation(prompt)

      // Accumulator for collecting chunks
      val chunks: scala.collection.mutable.ListBuffer[StreamedChunk] =
        scala.collection.mutable.ListBuffer[StreamedChunk]()

      val accumulator: StringBuilder = new StringBuilder()

      // Call streaming LLM with callback to collect chunks
      val streamResult: Either[LLMError, Completion] =
        client.streamComplete(conversation, options, (chunk: StreamedChunk) => {
          chunks += chunk
          chunk.content.foreach(accumulator.append)
        })

      // Create token stream from collected chunks
      val tokenStream: Stream[F, String] = streamResult match
        case Right(_) =>
          Stream.emits(chunks.toList)
            .map((chunk: StreamedChunk) => chunk.content.getOrElse(""))
            .filter(_.nonEmpty)
        case Left(err) =>
          Stream.raiseError[F](StructuredLLMError.LLMCallFailed(err, prompt))

      // Create final parsed result
      val resultF: F[A] = streamResult match
        case Right(_) =>
          val fullResponse: String = accumulator.toString
          if fullResponse.trim.isEmpty then
            Async[F].raiseError(StructuredLLMError.EmptyResponse(prompt))
          else
            logRawResponseIfEnabled(fullResponse) *> parseResponse[A](fullResponse)
        case Left(err) =>
          Async[F].raiseError(StructuredLLMError.LLMCallFailed(err, prompt))

      (tokenStream, resultF)
    }

  override def completeValidated[A: Schema](prompt: Prompt): F[ValidationResult[A]] =
    val promptWithSchema: Prompt = prompt.withOutputFormat[A]
    for
      conversation <- Async[F].pure(toConversation(promptWithSchema))
      completion   <- callLLM(conversation, promptWithSchema)
      response     <- extractContent(completion, promptWithSchema)
      _            <- logRawResponseIfEnabled(response)
      value        <- parseResponse[A](response)
      result       <- evaluateConstraints[A](value, Schema[A].constraints, response)
    yield result

  override def streamPartial[A: Schema](prompt: Prompt): fs2.Stream[F, org.adk4s.structured.sap.StreamState[A]] =
    import org.adk4s.structured.sap.StreamState
    import org.adk4s.structured.sap.CompletionState
    // For now, stream the final result as a single Complete emission.
    // Full incremental streaming will be added in a future iteration.
    fs2.Stream.eval(
      complete[A](prompt).map(value => StreamState.complete(value))
    )

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
    if content.trim.isEmpty then Async[F].raiseError(StructuredLLMError.EmptyResponse(prompt))
    else Async[F].pure(content)

  /**
   * Parse response using SAP.
   */
  private def parseResponse[A: Schema](response: String): F[A] =
    SchemaAlignedParser.parse[A](response) match
      case ParseResult.Success(value, warnings) =>
        Async[F].pure(value)
      case ParseResult.Failure(errors) =>
        Async[F].raiseError(StructuredLLMError.ParseFailed(errors, response))

  /**
   * Evaluate constraints on a parsed value.
   * @check constraints are collected into ValidationResult.checks.
   * @assert constraints raise ValidationFailed if any fail.
   */
  private def evaluateConstraints[A](
    value: A,
    constraints: Vector[Constraint[A]],
    rawResponse: String
  ): F[ValidationResult[A]] =
    if constraints.isEmpty then Async[F].pure(ValidationResult(value, Vector.empty))
    else
      Constraint.evaluateStrictAll(value, constraints) match
        case Right(_) =>
          Async[F].pure(Constraint.evaluateAll(value, constraints))
        case Left(failure) =>
          Async[F].raiseError(failure)

  /**
   * Log raw response if logging is enabled.
   */
  private def logRawResponseIfEnabled(response: String): F[Unit] =
    if logRawResponse then
      Async[F].delay {
        println("\n" + "=" * 80)
        println("RAW LLM RESPONSE (before parsing):")
        println("=" * 80)
        println(response)
        println("=" * 80)
      }
    else Async[F].unit
}

/**
 * Wrapper that retries structured LLM operations on parse failures.
 */
private class RetryStructuredLLM[F[_]: Async](
  underlying: StructuredLLM[F],
  maxAttempts: Int,
  delay: Duration,
  trigger: RetryTrigger
) extends StructuredLLM[F]:

  override def complete[A: Schema](prompt: Prompt): F[A] =
    Retry.withRetry[F, A](maxAttempts, delay, trigger)(underlying.complete[A](prompt))

  override def completeRaw[A: Schema](prompt: Prompt): F[A] =
    Retry.withRetry[F, A](maxAttempts, delay, trigger)(underlying.completeRaw[A](prompt))

  override def streamWithResult[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])] =
    underlying.streamWithResult[A](prompt)

  override def streamWithResultRaw[A: Schema](prompt: Prompt): F[(Stream[F, String], F[A])] =
    underlying.streamWithResultRaw[A](prompt)

  override def completeValidated[A: Schema](prompt: Prompt): F[ValidationResult[A]] =
    Retry.withRetry[F, ValidationResult[A]](maxAttempts, delay, trigger)(
      underlying.completeValidated[A](prompt)
    )

  override def function[I, A: Schema](template: PromptTemplate[I]): I => F[A] =
    input => Retry.withRetry[F, A](maxAttempts, delay, trigger)(underlying.complete[A](template.render(input)))

  override def extractor[A: Schema](systemPrompt: String): String => F[A] =
    underlying.extractor[A](systemPrompt)

  override def streamPartial[A: Schema](prompt: Prompt): fs2.Stream[F, org.adk4s.structured.sap.StreamState[A]] =
    underlying.streamPartial[A](prompt)
