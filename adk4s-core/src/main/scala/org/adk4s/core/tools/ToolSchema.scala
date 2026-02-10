package org.adk4s.core.tools

import ujson.Value
import scala.deriving.Mirror
import scala.compiletime.erasedValue

/** Typeclass for tool argument and result schemas with JSON encoding/decoding.
  *
  * `ToolSchema[A]` provides a type-safe way to define how tool inputs and outputs
  * are serialized to/from JSON. It includes:
  *   - A JSON Schema definition for validation and LLM prompt injection
  *   - A decoder to parse JSON into typed values
  *   - An encoder to serialize typed values to JSON
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

  /** Automatically derives a [[ToolSchema]] from a case class using compile-time reflection.
    *
    * This method reuses [[ToolInfer]]'s schema derivation logic and adds automatic
    * encoder generation, providing a one-line solution for creating ToolSchema instances.
    *
    * Supported field types: String, Int, Long, Float, Double, Boolean, Option[T]
    *
    * @example
    * {{{
    * case class BookingResult(confirmation: String, price: Double)
    * given ToolSchema[BookingResult] = ToolSchema.derive[BookingResult]
    * }}}
    *
    * @tparam A the case class type to derive a schema for
    * @param m the Mirror for the product type (provided by compiler)
    * @return a new ToolSchema instance with automatic encoder/decoder
    */
  inline def derive[A <: Product](using m: Mirror.ProductOf[A]): ToolSchema[A] =
    val jsonSchema: Value = ToolInfer.deriveSchema[A]

    val decoder: Value => Either[ToolSchemaError, A] = (json: Value) =>
      ToolInfer.decodeProduct[A](json).left.map { (err: String) =>
        ToolSchemaError.DecodingFailed(err, None)
      }

    val encoder: A => Value = (a: A) => encodeProduct[A](a)

    ToolSchema.instance(jsonSchema, None)(decoder, encoder)

  /** Encodes a product type (case class) to JSON.
    *
    * @tparam A the product type to encode
    * @param a the instance to encode
    * @param m the Mirror for the product type
    * @return ujson.Value representing the encoded object
    */
  inline def encodeProduct[A <: Product](a: A)(using m: Mirror.ProductOf[A]): Value =
    val fieldNames: List[String] = ToolInfer.getFieldNames[m.MirroredElemLabels]
    val fieldValues: List[Value] = encodeFields[m.MirroredElemTypes](a.productIterator.toList)
    val properties: Map[String, Value] = fieldNames.zip(fieldValues).toMap
    ujson.Obj.from(properties)

  /** Encodes a tuple of field values to a list of JSON values.
    *
    * @tparam T the tuple type representing field types
    * @param values the list of field values from productIterator
    * @return list of encoded JSON values
    */
  inline def encodeFields[T <: Tuple](values: List[Any]): List[Value] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        val encodedValue: Value = encodeField[head](values.head)
        encodedValue :: encodeFields[tail](values.tail)

  /** Encodes a single field value to JSON based on its type.
    *
    * @tparam T the field type
    * @param value the field value to encode
    * @return the encoded JSON value
    */
  inline def encodeField[T](value: Any): Value =
    inline erasedValue[T] match
      case _: String  => ujson.Str(value.asInstanceOf[String])
      case _: Int     => ujson.Num(value.asInstanceOf[Int].toDouble)
      case _: Long    => ujson.Num(value.asInstanceOf[Long].toDouble)
      case _: Float   => ujson.Num(value.asInstanceOf[Float].toDouble)
      case _: Double  => ujson.Num(value.asInstanceOf[Double])
      case _: Boolean => ujson.Bool(value.asInstanceOf[Boolean])
      case _: Option[?] =>
        value.asInstanceOf[Option[Any]] match
          case Some(v) =>
            // For Option types, we need to encode the inner value
            // We determine the inner type and encode accordingly
            v match
              case s: String  => ujson.Str(s)
              case i: Int     => ujson.Num(i.toDouble)
              case l: Long    => ujson.Num(l.toDouble)
              case f: Float   => ujson.Num(f.toDouble)
              case d: Double  => ujson.Num(d)
              case b: Boolean => ujson.Bool(b)
              case other      => ujson.Str(other.toString)
          case None => ujson.Null

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

/** Error ADT for schema-level validation and decoding failures.
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
