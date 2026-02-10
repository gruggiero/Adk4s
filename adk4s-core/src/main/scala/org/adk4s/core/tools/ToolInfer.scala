package org.adk4s.core.tools

import cats.effect.IO
import org.adk4s.core.component.AdkToolInfo
import org.adk4s.core.component.InvokableTool
import org.adk4s.core.component.Tool
import scala.deriving.Mirror
import scala.compiletime.constValueTuple
import scala.compiletime.erasedValue
import scala.compiletime.summonInline

/**
 * Compile-time tool schema inference from Scala 3 case classes.
 *
 * Eino equivalent: jsonschema package that derives JSON Schema from Go structs.
 * In Scala 3, we use Mirror + inline macros to derive schemas at compile time.
 *
 * Supports: String, Int, Double, Boolean, Long, Float, Option[T] field types.
 */
object ToolInfer:

  /**
   * Create an InvokableTool with JSON Schema derived from a case class.
   *
   * Usage:
   * ```
   * case class BookingArgs(destination: String, passengers: Int, premium: Boolean)
   * val tool = ToolInfer.infer[BookingArgs]("book", "Book a trip") { args =>
   *   IO.pure(Right(ujson.Str(s"Booked to ${args.destination}")))
   * }
   * ```
   */
  inline def infer[I <: Product](
    name: String,
    description: String
  )(
    fn: I => IO[Either[String, ujson.Value]]
  )(using m: Mirror.ProductOf[I]): InvokableTool[IO] =
    val schema: ujson.Value = deriveSchema[I]
    val decoder: ujson.Value => Either[String, I] = (json: ujson.Value) => decodeProduct[I](json)
    createTool(name, description, schema, decoder, fn)

  /**
   * Get the JSON Schema for a case class without creating a tool.
   */
  inline def schemaFor[I <: Product](using m: Mirror.ProductOf[I]): ujson.Value =
    deriveSchema[I]

  private def createTool[I](
    name: String,
    description: String,
    schema: ujson.Value,
    decoder: ujson.Value => Either[String, I],
    fn: I => IO[Either[String, ujson.Value]]
  ): InvokableTool[IO] =
    val toolInfo: AdkToolInfo = AdkToolInfo(
      name = name,
      description = description,
      parameters = schema
    )
    new InvokableTool[IO]:
      val info: AdkToolInfo = toolInfo

      def run(arguments: ujson.Value): IO[ujson.Value] =
        decoder(arguments) match
          case Left(err) => IO.raiseError(new RuntimeException(s"Argument decode error: $err"))
          case Right(input) =>
            fn(input).flatMap {
              case Right(result) => IO.pure(result)
              case Left(err) => IO.raiseError(new RuntimeException(s"Tool error: $err"))
            }

      def asToolFunction: Option[org.llm4s.toolapi.ToolFunction[Any, Any]] = None

  // --- Schema derivation ---

  inline def deriveSchema[I <: Product](using m: Mirror.ProductOf[I]): ujson.Value =
    val fieldNames: List[String] = getFieldNames[m.MirroredElemLabels]
    val fieldSchemas: List[ujson.Value] = getFieldSchemas[m.MirroredElemTypes]
    val requiredFields: List[String] = getRequiredFields[m.MirroredElemTypes](fieldNames)
    val properties: ujson.Obj = ujson.Obj()
    fieldNames.zip(fieldSchemas).foreach { case (name: String, schema: ujson.Value) =>
      properties(name) = schema
    }
    ujson.Obj(
      "type" -> "object",
      "properties" -> properties,
      "required" -> ujson.Arr(requiredFields.map(ujson.Str.apply)*)
    )

  inline def getFieldNames[T <: Tuple]: List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        inline erasedValue[head] match
          case _: String =>
            scala.compiletime.constValue[head].asInstanceOf[String] :: getFieldNames[tail]

  inline def getFieldSchemas[T <: Tuple]: List[ujson.Value] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (head *: tail) =>
        schemaForType[head] :: getFieldSchemas[tail]

  inline def getRequiredFields[T <: Tuple](names: List[String]): List[String] =
    inline erasedValue[T] match
      case _: EmptyTuple => Nil
      case _: (Option[?] *: tail) =>
        getRequiredFields[tail](names.drop(1))
      case _: (head *: tail) =>
        names.headOption.toList ++ getRequiredFields[tail](names.drop(1))

  inline def schemaForType[T]: ujson.Value =
    inline erasedValue[T] match
      case _: String  => ujson.Obj("type" -> "string")
      case _: Int     => ujson.Obj("type" -> "integer")
      case _: Long    => ujson.Obj("type" -> "integer")
      case _: Float   => ujson.Obj("type" -> "number")
      case _: Double  => ujson.Obj("type" -> "number")
      case _: Boolean => ujson.Obj("type" -> "boolean")
      case _: Option[String]  => ujson.Obj("type" -> "string")
      case _: Option[Int]     => ujson.Obj("type" -> "integer")
      case _: Option[Long]    => ujson.Obj("type" -> "integer")
      case _: Option[Float]   => ujson.Obj("type" -> "number")
      case _: Option[Double]  => ujson.Obj("type" -> "number")
      case _: Option[Boolean] => ujson.Obj("type" -> "boolean")

  // --- Decoding ---

  inline def decodeProduct[I <: Product](json: ujson.Value)(using m: Mirror.ProductOf[I]): Either[String, I] =
    val fieldNames: List[String] = getFieldNames[m.MirroredElemLabels]
    try
      val values: m.MirroredElemTypes = decodeFields[m.MirroredElemTypes](json, fieldNames).asInstanceOf[m.MirroredElemTypes]
      Right(m.fromTuple(values))
    catch
      case e: Exception => Left(e.getMessage)

  inline def decodeFields[T <: Tuple](json: ujson.Value, names: List[String]): Tuple =
    inline erasedValue[T] match
      case _: EmptyTuple => EmptyTuple
      case _: (head *: tail) =>
        val name: String = names.head
        val value: head = decodeField[head](json, name)
        value *: decodeFields[tail](json, names.tail)

  inline def decodeField[T](json: ujson.Value, name: String): T =
    inline erasedValue[T] match
      case _: String  => json(name).str.asInstanceOf[T]
      case _: Int     => json(name).num.toInt.asInstanceOf[T]
      case _: Long    => json(name).num.toLong.asInstanceOf[T]
      case _: Float   => json(name).num.toFloat.asInstanceOf[T]
      case _: Double  => json(name).num.asInstanceOf[T]
      case _: Boolean => json(name).bool.asInstanceOf[T]
      case _: Option[String]  => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.str)).asInstanceOf[T]
      case _: Option[Int]     => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.num.toInt)).asInstanceOf[T]
      case _: Option[Long]    => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.num.toLong)).asInstanceOf[T]
      case _: Option[Float]   => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.num.toFloat)).asInstanceOf[T]
      case _: Option[Double]  => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.num)).asInstanceOf[T]
      case _: Option[Boolean] => json.obj.get(name).flatMap(v => if v.isNull then None else Some(v.bool)).asInstanceOf[T]
