package org.adk4s.core.tools

import org.llm4s.toolapi.{ BooleanSchema, IntegerSchema, NumberSchema, ObjectSchema, PropertyDefinition, SafeParameterExtractor, SchemaDefinition, StringSchema, ToolFunction }
import ujson.Value
import upickle.default.*

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

  /** Builds llm4s [[PropertyDefinition]]s from a derived JSON schema so the
    * synthesized ToolFunction exposes its parameters to the LLM. Only the
    * primitive JSON-schema types produced by ToolInfer (string/integer/number/
    * boolean) are mapped to typed llm4s schemas; anything else falls back to a
    * permissive string schema (argument validation still happens in the handler).
    */
  private def propertiesFromJsonSchema(jsonSchema: Value): Seq[PropertyDefinition[?]] =
    jsonSchema match
      case schemaObj: ujson.Obj =>
        val requiredNames: Set[String] =
          schemaObj.obj.get("required").map(_.arr.map(_.str).toSet).getOrElse(Set.empty[String])
        schemaObj.obj.get("properties") match
          case Some(props: ujson.Obj) =>
            props.obj.toSeq.map { (name: String, propSchema: Value) =>
              val typeStr: String = propSchema match
                case p: ujson.Obj => p.obj.get("type").map(_.str).getOrElse("string")
                case _            => "string"
              val propSchemaDef: SchemaDefinition[?] = typeStr match
                case "string"  => StringSchema("")
                case "integer" => IntegerSchema("")
                case "number"  => NumberSchema("")
                case "boolean" => BooleanSchema("")
                case _         => StringSchema("")
              PropertyDefinition(name, propSchemaDef, required = requiredNames.contains(name))
            }
          case _ => Seq.empty[PropertyDefinition[?]]
      case _ => Seq.empty[PropertyDefinition[?]]

  extension [I, O](stf: StructuredToolFunction[I, O])
    /** Synthesizes a llm4s [[ToolFunction]] from this StructuredToolFunction.
      *
      * The ToolFunction's schema is derived from `inputSchema.jsonSchema` so the
      * LLM sees the tool's parameters (names/types/required), not just name +
      * description. Argument validation is still performed by
      * `inputSchema.decoder` in the handler.
      *
      * Error-reporting note: llm4s `ToolFunction` handlers return
      * `Either[String, R]`, so structured `ToolCallError` variants cannot be
      * carried through this path. Errors surface as
      * `ToolCallError.HandlerError(name, message)` where the message preserves
      * the field/path from the underlying `ToolSchemaError`.
      *
      * @return a ToolFunction[ujson.Value, ujson.Value] that wraps this function
      */
    def toToolFunction: ToolFunction[ujson.Value, ujson.Value] =
      val schemaDef: ObjectSchema[ujson.Value] =
        ObjectSchema[ujson.Value](stf.description, propertiesFromJsonSchema(stf.inputSchema.jsonSchema), false)
      ToolFunction[ujson.Value, ujson.Value](
        name = stf.name,
        description = stf.description,
        schema = schemaDef,
        handler = (extractor: SafeParameterExtractor) =>
          stf.inputSchema.decoder(extractor.params) match
            case Left(err: ToolSchemaError) => Left(err.message)
            case Right(input: I) =>
              stf.handler(input) match
                case Left(err: ToolSchemaError) => Left(err.message)
                case Right(output: O)           => Right(stf.outputSchema.encoder(output))
      )

    /** Converts to [[ToolWrapper]] for use with [[ToolsNodeConfig]].
      *
      * This is the preferred method for integrating structured tool functions
      * with the ToolsNode orchestration system. The resulting ToolWrapper
      * contains a synthesized ToolFunction (via [[toToolFunction]]), making
      * the tool visible in [[ToolsNodeConfig.toToolRegistry]].
      *
      * @return a ToolWrapper that can be added to ToolsNodeConfig
      */
    def toToolWrapper: ToolWrapper =
      ToolWrapper(stf.toToolFunction)
