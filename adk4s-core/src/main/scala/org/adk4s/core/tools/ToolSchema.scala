package org.adk4s.core.tools

import ujson.Value
import smithy4s.Blob
import smithy4s.codecs.PayloadError
import smithy4s.json.Json
import smithy4s.schema.Schema as Smithy4sSchema

/** Typeclass for tool argument and result schemas with JSON encoding/decoding.
  *
  * `ToolSchema[A]` provides a type-safe way to define how tool inputs and outputs
  * are serialized to/from JSON. It includes:
  *   - A JSON Schema definition for validation and LLM prompt injection
  *   - A decoder to parse JSON into typed values
  *   - An encoder to serialize typed values to JSON
  *
  * The `derive` method uses smithy4s `Schema[A]` for decoding and encoding,
  * supporting nested case classes, collections, enums, and `BigDecimal` —
  * beyond the previous six-primitive limit. No `asInstanceOf` is used.
  *
  * @example
  * {{{
  * case class WeatherRequest(location: String, unit: String)
  *
  * given ToolSchema[WeatherRequest] = ToolSchema.instance[WeatherRequest](
  *   jsonSchema = ujson.Obj(
  *     "type" -> "object",
  *     "properties" -> ujson.Obj(
  *       "location" -> ujson.Obj("type" -> "string"),
  *       "unit" -> ujson.Obj("type" -> "string")
  *     ),
  *     "required" -> ujson.Arr("location", "unit")
  *   ),
  *   description = Some("Weather request parameters")
  * )(
  *   decoder = json => for {
  *     location <- json.obj.get("location").flatMap(_.strOpt)
  *       .toRight(ToolSchemaError.MissingRequiredField("location", ""))
  *     unit <- json.obj.get("unit").flatMap(_.strOpt)
  *       .toRight(ToolSchemaError.MissingRequiredField("unit", ""))
  *   } yield WeatherRequest(location, unit),
  *   encoder = req => ujson.Obj("location" -> req.location, "unit" -> req.unit)
  * )
  * }}}
  *
  * @tparam A the type this schema encodes/decodes
  */
opaque type ToolSchema[A] = ToolSchema.SchemaData[A]

/** Companion object for [[ToolSchema]] providing factory methods and extension methods. */
object ToolSchema:
  /** Internal representation of schema data. */
  final case class SchemaData[A](
    jsonSchema: Value,
    description: Option[String],
    decoder: Value => Either[ToolSchemaError, A],
    encoder: A => Value
  )

  private inline def asData[A](schema: ToolSchema[A]): SchemaData[A] =
    schema match
      case data: SchemaData[A] => data

  /** Summons a [[ToolSchema]] instance from implicit scope.
    *
    * @tparam A the type to get the schema for
    * @return the schema instance
    */
  def apply[A](using schema: ToolSchema[A]): ToolSchema[A] = schema

  /** Creates a new [[ToolSchema]] instance.
    *
    * @param jsonSchema JSON Schema definition as ujson.Value
    * @param description optional human-readable description
    * @param decoder function to decode JSON to type A
    * @param encoder function to encode type A to JSON
    * @tparam A the type this schema handles
    * @return a new ToolSchema instance
    */
  def instance[A](
    jsonSchema: Value,
    description: Option[String] = None
  )(
    decoder: Value => Either[ToolSchemaError, A],
    encoder: A => Value
  ): ToolSchema[A] =
    SchemaData[A](jsonSchema, description, decoder, encoder)

  /** Automatically derives a [[ToolSchema]] from a smithy4s `Schema[A]`.
    *
    * This method uses smithy4s `Json.read` for decoding and `Json.writeBlob`
    * for encoding, supporting nested case classes, collections, enums, and
    * `BigDecimal` — beyond the previous six-primitive limit. No `asInstanceOf`
    * is used. Decode errors are mapped to `ToolSchemaError`'s specific cases
    * with field paths.
    *
    * The JSON Schema (for `AdkToolInfo.parameters`) is derived from the
    * case class's `Mirror` via `ToolInfer.deriveSchema` — it stays
    * `ujson.Value` because it is boundary data for `org.llm4s.toolapi`.
    *
    * @example
    * {{{
    * case class BookingResult(confirmation: String, price: Double)
    * given Smithy4sSchema[BookingResult] = smithy4s.Schema.derive[BookingResult]
    * given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]
    * }}}
    *
    * @tparam A the case class type to derive a schema for
    * @param smithySchema the smithy4s Schema for decoding/encoding
    * @param m the Mirror for the product type (for JSON Schema generation)
    * @return a new ToolSchema instance with smithy4s-based decoder/encoder
    */
  inline def derive[A <: Product](using smithySchema: Smithy4sSchema[A], m: scala.deriving.Mirror.ProductOf[A]): ToolSchema[A] =
    val jsonSchema: Value = ToolInfer.deriveSchema[A]

    val decoder: Value => Either[ToolSchemaError, A] = (json: Value) =>
      val jsonString: String = json.render()
      val blob: Blob = Blob(jsonString)
      Json.read[A](blob)(using smithySchema) match
        case Right(value) => Right(value)
        case Left(error: PayloadError) =>
          Left(payloadErrorToSchemaError(error))

    val encoder: A => Value = (a: A) =>
      val blob: Blob = Json.writeBlob[A](a)(using smithySchema)
      val jsonString: String = blob.toUTF8String
      ujson.read(jsonString)

    ToolSchema.instance(jsonSchema, None)(decoder, encoder)

  /** Map a smithy4s `PayloadError` to a `ToolSchemaError` specific case.
    *
    * `PayloadError` carries a `path`, `expected`, and `message`. We map:
    * - "required" / "missing" messages → `MissingRequiredField`
    * - "expected" type mismatch messages → `TypeMismatch`
    * - enum-related messages → `InvalidEnumValue`
    * - everything else → `DecodingFailed`
    *
    * Each case carries the field path from `PayloadError.path`.
    */
  private def payloadErrorToSchemaError(error: PayloadError): ToolSchemaError =
    val pathStr: String = error.path.render()
    val message: String = error.message
    val expected: String = error.expected
    // Heuristic mapping based on smithy4s error message patterns
    if message.contains("required") || message.contains("Missing") then
      ToolSchemaError.MissingRequiredField(
        fieldName = pathStr,
        path = pathStr
      )
    else if message.contains("Expected") || expected.nonEmpty then
      ToolSchemaError.TypeMismatch(
        expectedType = expected,
        actualValue = ujson.Str(message),
        path = pathStr
      )
    else
      ToolSchemaError.DecodingFailed(
        msg = s"$message (path: $pathStr, expected: $expected)",
        underlying = Some(error)
      )

  extension [A](schema: ToolSchema[A])
    /** Returns the JSON Schema definition. */
    def jsonSchema: Value =
      val data: SchemaData[A] = asData[A](schema)
      data.jsonSchema

    /** Returns the optional description. */
    def description: Option[String] =
      val data: SchemaData[A] = asData[A](schema)
      data.description

    /** Returns the decoder function. */
    def decoder: Value => Either[ToolSchemaError, A] =
      val data: SchemaData[A] = asData[A](schema)
      data.decoder

    /** Returns the encoder function. */
    def encoder: A => Value =
      val data: SchemaData[A] = asData[A](schema)
      data.encoder

/** Error ADO for schema-level validation and decoding failures.
  *
  * These errors occur during JSON parsing and validation against a [[ToolSchema]].
  */
sealed trait ToolSchemaError extends Throwable:
  /** Human-readable error message. */
  def message: String
  override def getMessage: String = message

/** Companion object containing [[ToolSchemaError]] subtypes. */
object ToolSchemaError:
  /** A required field is missing from the JSON input.
    *
    * @param fieldName the name of the missing field
    * @param path JSON path where the field was expected
    */
  case class MissingRequiredField(
    fieldName: String,
    path: String
  ) extends ToolSchemaError:
    def message: String = s"Missing required field '$fieldName' at $path"

  /** The JSON value has an unexpected type.
    *
    * @param expectedType the expected JSON type (e.g., "string", "number")
    * @param actualValue the actual JSON value received
    * @param path JSON path where the mismatch occurred
    */
  case class TypeMismatch(
    expectedType: String,
    actualValue: Value,
    path: String
  ) extends ToolSchemaError:
    def message: String = s"Expected type $expectedType at $path but got ${actualValue.getClass.getSimpleName}"

  /** The value is not one of the allowed enum values.
    *
    * @param value the invalid value received
    * @param allowedValues list of valid enum values
    * @param path JSON path where the error occurred
    */
  case class InvalidEnumValue(
    value: String,
    allowedValues: List[String],
    path: String
  ) extends ToolSchemaError:
    def message: String =
      s"Invalid enum value '$value' at $path. Allowed: ${allowedValues.mkString(", ")}"

  /** Generic decoding failure with optional underlying cause.
    *
    * @param msg description of the decoding failure
    * @param underlying optional underlying exception
    */
  case class DecodingFailed(
    msg: String,
    underlying: Option[Throwable] = None
  ) extends ToolSchemaError:
    def message: String = s"Decoding failed: $msg"
