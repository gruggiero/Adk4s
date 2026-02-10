package org.adk4s.core.tools

import org.llm4s.toolapi.ToolCallError
import org.llm4s.toolapi.ToolParameterError
import ujson.Value

/** Typed wrapper for building tool functions with input and output schemas.
  *
  * `StructuredToolFunction` provides a type-safe way to define tools with
  * explicit input/output types. It can be converted to llm4s-compatible
  * formats for use with [[ToolsNode]] and [[ToolsNodeConfig]].
  *
  * @example
  * {{{
  * case class AddRequest(a: Int, b: Int)
  * case class AddResult(sum: Int)
  *
  * val addTool = StructuredToolFunction.pure[AddRequest, AddResult](
  *   name = "add",
  *   description = "Adds two numbers",
  *   inputSchema = summon[ToolSchema[AddRequest]],
  *   outputSchema = summon[ToolSchema[AddResult]],
  *   handler = req => AddResult(req.a + req.b)
  * )
  *
  * // Use with ToolsNodeConfig
  * val config = ToolsNodeConfig.builder
  *   .withStructuredTool(addTool)
  *   .build
  * }}}
  *
  * @param name tool identifier (used in LLM tool calls)
  * @param description human-readable description for LLM
  * @param inputSchema schema for parsing input arguments
  * @param outputSchema schema for encoding output results
  * @param handler function that processes typed input and returns typed output
  * @tparam I input argument type
  * @tparam O output result type
  */
case class StructuredToolFunction[I, O](
  name: String,
  description: String,
  inputSchema: ToolSchema[I],
  outputSchema: ToolSchema[O],
  handler: I => Either[ToolSchemaError, O]
)

/** Factory methods and extension methods for [[StructuredToolFunction]]. */
object StructuredToolFunction:
  /** Creates a tool function with a pure (infallible) handler.
    *
    * Use this when your handler cannot fail. The handler result is
    * automatically wrapped in `Right`.
    *
    * @param name tool identifier
    * @param description human-readable description
    * @param inputSchema schema for input arguments
    * @param outputSchema schema for output results
    * @param handler pure function from I to O
    * @tparam I input type
    * @tparam O output type
    * @return a new StructuredToolFunction
    */
  def pure[I, O](
    name: String,
    description: String,
    inputSchema: ToolSchema[I],
    outputSchema: ToolSchema[O],
    handler: I => O
  ): StructuredToolFunction[I, O] =
    StructuredToolFunction[I, O](
      name,
      description,
      inputSchema,
      outputSchema,
      handler andThen (Right(_))
    )

  /** Creates a tool function with a fallible handler.
    *
    * Use this when your handler can fail with a [[ToolSchemaError]].
    *
    * @param name tool identifier
    * @param description human-readable description
    * @param inputSchema schema for input arguments
    * @param outputSchema schema for output results
    * @param handler function that may fail with ToolSchemaError
    * @tparam I input type
    * @tparam O output type
    * @return a new StructuredToolFunction
    */
  def fromHandler[I, O](
    name: String,
    description: String,
    inputSchema: ToolSchema[I],
    outputSchema: ToolSchema[O]
  )(
    handler: I => Either[ToolSchemaError, O]
  ): StructuredToolFunction[I, O] =
    StructuredToolFunction[I, O](
      name = name,
      description = description,
      inputSchema = inputSchema,
      outputSchema = outputSchema,
      handler = handler
    )

  extension [I, O](stf: StructuredToolFunction[I, O])
    /** Converts to [[SafeToolExecutable]] for use with [[ToolsNode]].
      *
      * The resulting executable handles JSON argument parsing, handler
      * execution, and result encoding automatically.
      *
      * @return a SafeToolExecutable that wraps this function
      */
    def toSafeExecutable: SafeToolExecutable =
      new SafeToolExecutable:
        def execute(args: Value): Either[ToolCallError, Value] =
          stf.inputSchema.decoder(args) match
            case Left(schemaErr) =>
              val paramError: ToolParameterError = schemaErr match
                case ToolSchemaError.MissingRequiredField(fieldName, path) =>
                  ToolParameterError.MissingParameter(fieldName, "unknown")
                case ToolSchemaError.TypeMismatch(expectedType, actualValue, path) =>
                  ToolParameterError.TypeMismatch(path, expectedType, actualValue.getClass.getSimpleName)
                case ToolSchemaError.InvalidEnumValue(value, allowedValues, path) =>
                  ToolParameterError.TypeMismatch(path, s"one of: ${allowedValues.mkString(", ")}", value)
                case ToolSchemaError.DecodingFailed(msg, underlying) =>
                  ToolParameterError.TypeMismatch("input", "valid JSON", msg)
              Left(ToolCallError.InvalidArguments(stf.name, List(paramError)))
            case Right(input) =>
              stf.handler(input) match
                case Left(handlerErr) =>
                  Left(ToolCallError.ExecutionError(stf.name, handlerErr))
                case Right(output) =>
                  Right(stf.outputSchema.encoder(output))

    /** Converts to [[ToolWrapper]] for use with [[ToolsNodeConfig]].
      *
      * This is the preferred method for integrating structured tool functions
      * with the ToolsNode orchestration system.
      *
      * @return a ToolWrapper that can be added to ToolsNodeConfig
      */
    def toToolWrapper: ToolWrapper =
      ToolWrapper(
        originalToolFunction = None,  // StructuredToolFunction doesn't wrap a ToolFunction
        executable = stf.toSafeExecutable,
        name = stf.name,
        description = stf.description
      )
