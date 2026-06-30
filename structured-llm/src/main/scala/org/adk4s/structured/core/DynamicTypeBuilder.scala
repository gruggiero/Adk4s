package org.adk4s.structured.core

import smithy4s.schema.Schema as SmithySchema
import smithy4s.{Document, ShapeId}

/**
 * Dynamic type builder for constructing schemas at runtime.
 *
 * This enables building Schema[A] instances from runtime data (e.g., from
 * configuration or database metadata) rather than compile-time derivation.
 *
 * The builder uses smithy4s `Document` as the intermediate representation,
 * allowing dynamic JSON values to be decoded against dynamically-built schemas.
 */
object DynamicTypeBuilder:

  /**
   * Field definition for dynamic struct construction.
   *
   * @param name Field name
   * @param fieldType Field type (e.g., "String", "Integer", "Boolean")
   * @param required Whether the field is required
   */
  final case class FieldDef(
    name: String,
    fieldType: String,
    required: Boolean
  )

  /**
   * Build a Smithy IDL string from field definitions.
   *
   * @param structName Name of the structure
   * @param fields Field definitions
   * @return Smithy IDL string
   */
  def buildSmithyIdl(structName: String, fields: Vector[FieldDef]): String =
    val fieldLines: Vector[String] = fields.map { f =>
      val requiredAnnotation: String = if f.required then "@required " else ""
      s"  ${requiredAnnotation}${f.name}: ${f.fieldType}"
    }
    s"""structure $structName {
       |${fieldLines.mkString("\n")}
       |}""".stripMargin

  /**
   * Build a Schema[Document] for dynamic JSON values.
   *
   * This allows parsing arbitrary JSON into a smithy4s Document,
   * which can then be navigated dynamically without a fixed schema.
   */
  def documentSchema: Schema[Document] =
    val s4s: SmithySchema[Document] = smithy4s.Schema.document
    Schema.instance("document Document")(using s4s)

  /**
   * Build a Schema[Map[String, DynamicValue]] for fully dynamic parsing.
   *
   * @param structName Name for the structure (for prompt injection)
   * @param fields Field definitions
   * @return A Schema for dynamic JSON objects
   */
  def buildDynamicSchema(structName: String, fields: Vector[FieldDef]): Schema[Document] =
    val idl: String = buildSmithyIdl(structName, fields)
    Schema.instance(idl)(using smithy4s.Schema.document)

/**
 * Dynamic value wrapper for runtime-typed JSON values.
 *
 * Wraps smithy4s Document with convenient accessors.
 */
final case class DynamicValue(document: Document):
  def asString: Option[String] = document match
    case Document.DString(s) => Some(s)
    case _                   => None

  def asInt: Option[Int] = document match
    case Document.DNumber(n) => Some(n.toInt)
    case _                   => None

  def asDouble: Option[Double] = document match
    case Document.DNumber(n) => Some(n.toDouble)
    case _                   => None

  def asBoolean: Option[Boolean] = document match
    case Document.DBoolean(b) => Some(b)
    case _                    => None

  def asObject: Option[Map[String, DynamicValue]] = document match
    case Document.DObject(m) => Some(m.view.mapValues(DynamicValue.apply).toMap)
    case _                   => None

  def asArray: Option[Vector[DynamicValue]] = document match
    case Document.DArray(arr) => Some(arr.map(DynamicValue.apply).toVector)
    case _                    => None

  def field(name: String): Option[DynamicValue] = asObject.flatMap(_.get(name))

object DynamicValue:
  /** Parse a JSON string into a DynamicValue using ujson. */
  def parse(json: String): Either[String, DynamicValue] =
    try
      val ujsonValue: ujson.Value = ujson.read(json)
      val doc: Document = ujsonValueToDocument(ujsonValue)
      Right(DynamicValue(doc))
    catch
      case e: Exception => Left(e.getMessage)

  /**
   * Convert ujson.Value to smithy4s Document.
   */
  private def ujsonValueToDocument(value: ujson.Value): Document = value match
    case ujson.Null       => Document.DNull
    case ujson.Bool(b)    => Document.DBoolean(b)
    case ujson.Num(n)     => Document.DNumber(n)
    case ujson.Str(s)     => Document.DString(s)
    case ujson.Arr(items) => Document.DArray(items.map(ujsonValueToDocument).toIndexedSeq)
    case ujson.Obj(fields) => Document.DObject(fields.view.mapValues(ujsonValueToDocument).toMap)
