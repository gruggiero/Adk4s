package org.adk4s.core.tools

import cats.MonadError
import cats.effect.Sync
import cats.syntax.all.*
import org.llm4s.toolapi.{ToolRegistry, ToolCallRequest}
import org.llm4s.llmconnect.model.ToolCall
import ujson.Value
import scala.deriving.Mirror

/** Type-safe wrapper for llm4s tool calling with the signature `ToolCall => F[A]`.
  *
  * `StructuredToolCall` provides typed argument parsing, typed result decoding,
  * and unified error handling for tool execution. It wraps the llm4s `ToolRegistry`
  * without replacing it.
  *
  * @example
  * {{{
  * import cats.effect.IO
  *
  * // Create from an existing ToolRegistry
  * val registry: ToolRegistry = new ToolRegistry(Seq(weatherTool))
  * val structured: StructuredToolCall[IO] = StructuredToolCall.fromRegistry[IO](registry)
  *
  * // Execute with typed input/output
  * val toolCall = ToolCall(id = "1", name = "get_weather", arguments = ujson.Obj(...))
  * val result: IO[WeatherResult] = structured.execute[WeatherRequest, WeatherResult](toolCall)
  *
  * // Create a reusable function for a specific tool
  * val getWeather: ToolCall => IO[WeatherResult] =
  *   structured.function[WeatherRequest, WeatherResult]("get_weather")
  * }}}
  *
  * @tparam F the effect type (e.g., IO, Either[Error, *])
  */
trait StructuredToolCall[F[_]]:
  /** Executes a tool call with typed input parsing and output decoding.
    *
    * @param toolCall the tool call from the LLM response
    * @param inputSchema schema for parsing arguments to type I
    * @param outputSchema schema for decoding result to type O
    * @param monadError error handling capability
    * @tparam I input argument type
    * @tparam O output result type
    * @return the typed result wrapped in F
    */
  def execute[I, O](
    toolCall: ToolCall
  )(using
    inputSchema: ToolSchema[I],
    outputSchema: ToolSchema[O],
    monadError: MonadError[F, StructuredToolCallError]
  ): F[O]

  /** Executes a tool call and returns the raw JSON result without decoding.
    *
    * @param toolCall the tool call from the LLM response
    * @param monadError error handling capability
    * @return the raw JSON result wrapped in F
    */
  def executeRaw(
    toolCall: ToolCall
  )(using
    monadError: MonadError[F, StructuredToolCallError]
  ): F[Value]

  /** Creates a typed function for a specific tool.
    *
    * The returned function validates that the tool call matches the expected
    * tool name before executing.
    *
    * @param toolName the expected tool name
    * @param inputSchema schema for parsing arguments
    * @param outputSchema schema for decoding result
    * @param monadError error handling capability
    * @tparam I input argument type
    * @tparam O output result type
    * @return a function `ToolCall => F[O]`
    */
  def function[I, O](
    toolName: String
  )(using
    inputSchema: ToolSchema[I],
    outputSchema: ToolSchema[O],
    monadError: MonadError[F, StructuredToolCallError]
  ): ToolCall => F[O] =
    (toolCall: ToolCall) =>
      if toolCall.name != toolName then
        monadError.raiseError(StructuredToolCallError.UnknownTool(toolName))
      else
        execute[I, O](toolCall)

  /** Creates an extractor that only parses tool arguments without executing.
    *
    * Useful when you want to extract typed arguments from a tool call
    * for custom processing.
    *
    * @param toolName the expected tool name
    * @param inputSchema schema for parsing arguments
    * @param monadError error handling capability
    * @tparam I input argument type
    * @return a function `ToolCall => F[I]`
    */
  def extractor[I](
    toolName: String
  )(using
    inputSchema: ToolSchema[I],
    monadError: MonadError[F, StructuredToolCallError]
  ): ToolCall => F[I] =
    (toolCall: ToolCall) =>
      if toolCall.name != toolName then
        monadError.raiseError(StructuredToolCallError.UnknownTool(toolName))
      else
        monadError.fromEither(
          inputSchema.decoder(toolCall.arguments).left.map(e =>
            StructuredToolCallError.InvalidArguments(List(e))
          )
        )

/** Represents a fully-typed tool with compile-time checked input/output types.
  *
  * `TypedTool` combines ToolInfer's schema derivation with StructuredToolCall's
  * type-safe execution, providing a streamlined API for creating tools with
  * typed arguments and results.
  *
  * @example
  * {{{
  * case class BookingArgs(destination: String, passengers: Int)
  * case class BookingResult(confirmation: String, price: Double)
  *
  * given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]
  *
  * val bookTool = StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
  *   "book_trip",
  *   "Book a trip to a destination"
  * ) { args =>
  *   IO.pure(BookingResult(s"Confirmed: ${args.destination}", args.passengers * 200.0))
  * }
  *
  * // Execute with typed arguments
  * val result: IO[BookingResult] = bookTool.execute(BookingArgs("Paris", 2))
  *
  * // Use with registry
  * val registry = new ToolRegistry(Seq(bookTool.asInvokableTool))
  * }}}
  *
  * @tparam F the effect type
  * @tparam I the input argument type (must be a Product/case class)
  * @tparam O the output result type (must be a Product/case class)
  */
trait TypedTool[F[_], I, O]:
  /** The tool name used in LLM tool calls. */
  def name: String

  /** The tool description shown to the LLM. */
  def description: String

  /** Executes the tool with typed arguments, returning typed result.
    *
    * @param args the typed input arguments
    * @return the typed result wrapped in effect F
    */
  def execute(args: I): F[O]

  /** Converts this TypedTool to an InvokableTool for registry compatibility.
    *
    * The returned InvokableTool accepts and returns ujson.Value, but internally
    * uses the typed execute method with automatic encoding/decoding.
    *
    * @return an InvokableTool that can be added to a ToolRegistry
    */
  def asInvokableTool: org.adk4s.core.component.InvokableTool[F]

/** Factory methods for creating [[StructuredToolCall]] instances. */
object StructuredToolCall:
  /** Creates a [[StructuredToolCall]] from an llm4s [[ToolRegistry]].
    *
    * This is the primary factory method for creating structured tool call handlers.
    * It wraps the registry's execution logic with typed argument parsing and
    * result decoding.
    *
    * @param registry the llm4s tool registry containing tool definitions
    * @param monadError error handling capability for effect F
    * @tparam F the effect type
    * @return a new StructuredToolCall instance
    */
  def fromRegistry[F[_]](
    registry: ToolRegistry
  )(using
    monadError: MonadError[F, StructuredToolCallError]
  ): StructuredToolCall[F] =
    new StructuredToolCall[F]:
      override def execute[I, O](
        toolCall: ToolCall
      )(using
        inputSchema: ToolSchema[I],
        outputSchema: ToolSchema[O],
        monadError: MonadError[F, StructuredToolCallError]
      ): F[O] =
        monadError.handleErrorWith(
          for
            input <- monadError.fromEither(
              inputSchema.decoder(toolCall.arguments).left.map(e => StructuredToolCallError.InvalidArguments(List(e)))
            )
            rawResult <- monadError.fromEither(
              registry.execute(ToolCallRequest(toolCall.name, toolCall.arguments)).left.map {
                case org.llm4s.toolapi.ToolCallError.UnknownFunction(toolName) =>
                  StructuredToolCallError.UnknownTool(toolName)
                case err =>
                  StructuredToolCallError.InvalidArguments(List(ToolSchemaError.DecodingFailed(err.toString)))
              }
            )
            output <- monadError.fromEither(
              outputSchema.decoder(rawResult).left.map(e =>
                StructuredToolCallError.ResultParsingFailed(e.message, rawResult)
              )
            )
          yield output
        ) { (err: StructuredToolCallError) =>
          monadError.raiseError(err)
        }

      override def executeRaw(
        toolCall: ToolCall
      )(using
        monadError: MonadError[F, StructuredToolCallError]
      ): F[Value] =
        monadError.fromEither(
          registry.execute(ToolCallRequest(toolCall.name, toolCall.arguments)).left.map {
            case org.llm4s.toolapi.ToolCallError.UnknownFunction(toolName) =>
              StructuredToolCallError.UnknownTool(toolName)
            case err =>
              StructuredToolCallError.InvalidArguments(List(ToolSchemaError.DecodingFailed(err.toString)))
          }
        )

  /** Creates a fully-typed tool with compile-time checked input/output types.
    *
    * This convenience method combines ToolInfer's schema derivation with type-safe
    * execution, eliminating the need to manually create ToolRegistry and StructuredToolCall
    * instances for single tools.
    *
    * The created TypedTool can be used directly with typed arguments or converted to
    * an InvokableTool for registry compatibility.
    *
    * @example
    * {{{
    * case class BookingArgs(destination: String, passengers: Int)
    * case class BookingResult(confirmation: String, price: Double)
    *
    * given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]
    *
    * val bookTool = StructuredToolCall.createTool[IO, BookingArgs, BookingResult](
    *   "book_trip",
    *   "Book a trip to a destination"
    * ) { args =>
    *   IO.pure(BookingResult(s"Confirmed: ${args.destination}", args.passengers * 200.0))
    * }
    *
    * // Execute with typed arguments
    * val result: IO[BookingResult] = bookTool.execute(BookingArgs("Paris", 2))
    *
    * // Or use with registry
    * val registry = new ToolRegistry(Seq(bookTool.asInvokableTool))
    * }}}
    *
    * @param toolName the name of the tool (used in LLM tool calls)
    * @param toolDescription the description shown to the LLM
    * @param impl the implementation function that takes typed input and returns typed output
    * @param m Mirror for input type (provided by compiler)
    * @param outputSchema Schema for encoding/decoding output type
    * @param F effect capability (e.g., IO, Either)
    * @tparam F the effect type
    * @tparam I the input argument type (must be a Product/case class)
    * @tparam O the output result type (must be a Product/case class)
    * @return a TypedTool that can execute with typed arguments or convert to InvokableTool
    */
  inline def createTool[F[_], I <: Product, O <: Product](
    toolName: String,
    toolDescription: String
  )(
    impl: I => F[O]
  )(using
    m: scala.deriving.Mirror.ProductOf[I],
    outputSchema: ToolSchema[O],
    F: cats.effect.Sync[F]
  ): TypedTool[F, I, O] =
    // Derive JSON schema for input type (inline expansion)
    val inputSchema: Value = ToolInfer.deriveSchema[I]

    new TypedTool[F, I, O]:
      val name: String = toolName
      val description: String = toolDescription

      def execute(args: I): F[O] = impl(args)

      def asInvokableTool: org.adk4s.core.component.InvokableTool[F] =
        val toolInfo: org.adk4s.core.component.AdkToolInfo =
          org.adk4s.core.component.AdkToolInfo(
            name = toolName,
            description = toolDescription,
            parameters = inputSchema
          )

        new org.adk4s.core.component.InvokableTool[F]:
          val info: org.adk4s.core.component.AdkToolInfo = toolInfo

          def run(arguments: Value): F[Value] =
            // Decode input arguments
            ToolInfer.decodeProduct[I](arguments) match
              case Left(err) =>
                F.raiseError(new RuntimeException(s"Failed to decode arguments: $err"))
              case Right(input) =>
                // Execute implementation and encode result
                impl(input).map { (output: O) =>
                  outputSchema.encoder(output)
                }.handleErrorWith { (err: Throwable) =>
                  F.raiseError(
                    org.adk4s.core.error.ToolExecutionError(toolName, err)
                  )
                }

          def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None
