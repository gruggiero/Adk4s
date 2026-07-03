package org.adk4s.structured.core

import scala.util.matching.Regex
import smithy4s.schema.Schema as SmithySchema

/**
 * Schema typeclass implemented as an opaque wrapper around smithy4s.Schema.
 * It carries the Smithy IDL definition, optional description, and the decoder schema.
 */
opaque type Schema[A] = Schema.SchemaData[A]

object Schema:

  /**
   * Opaque carrier for schema metadata and smithy4s decoder.
   */
  final case class SchemaData[A](
    smithyDefinition: String,
    description: Option[String],
    smithySchema: SmithySchema[A],
    constraints: Vector[Constraint[A]] = Vector.empty[Constraint[A]]
  )

  private inline def asData[A](schema: Schema[A]): SchemaData[A] =
    schema match
      case data: SchemaData[A] => data

  def apply[A](using schema: Schema[A]): Schema[A] = schema

  /**
   * Derive a Schema from a smithy4s schema and Smithy definition.
   */
  def instance[A](
    smithy: String,
    desc: Option[String] = None
  )(using smithySchema: SmithySchema[A]): Schema[A] =
    SchemaData[A](smithy, desc, smithySchema)

  /**
   * Helper to create Schema for simple wrapper types.
   */
  def derived[A](smithy: String)(using smithySchema: SmithySchema[A]): Schema[A] =
    instance[A](smithy)

  /**
   * Extension methods providing the typeclass surface for the opaque type.
   */
  extension [A](schema: Schema[A])
    /**
     * The Smithy IDL representation of this type.
     * This is injected into prompts to guide the LLM's output format.
     */
    def smithyDefinition: String =
      val data: SchemaData[A] = asData[A](schema)
      data.smithyDefinition

    /**
     * Human-readable description of the expected output.
     * Used in the prompt alongside the Smithy definition.
     */
    def description: Option[String] =
      val data: SchemaData[A] = asData[A](schema)
      data.description

    /**
     * Smithy4s schema for JSON decoding.
     * The SAP layer will clean up the JSON before this decoder is applied.
     */
    def smithySchema: SmithySchema[A] =
      val data: SchemaData[A] = asData[A](schema)
      data.smithySchema

    /**
     * Constraints attached to this schema.
     * Evaluated after parsing to validate the result.
     */
    def constraints: Vector[Constraint[A]] =
      val data: SchemaData[A] = asData[A](schema)
      data.constraints

    /**
     * Attach a non-failing check constraint to this schema.
     * Failed checks are collected as warnings but do not fail the parse.
     */
    def withCheck(label: String)(predicate: A => Boolean): Schema[A] =
      val data: SchemaData[A]       = asData[A](schema)
      val constraint: Constraint[A] = Constraint.check(label)(predicate)
      SchemaData[A](
        data.smithyDefinition,
        data.description,
        data.smithySchema,
        data.constraints :+ constraint
      )

    /**
     * Attach a strict assert constraint to this schema.
     * Failed asserts raise ValidationFailed.
     */
    def withAssert(label: String)(predicate: A => Boolean): Schema[A] =
      val data: SchemaData[A]       = asData[A](schema)
      val constraint: Constraint[A] = Constraint.assert(label)(predicate)
      SchemaData[A](
        data.smithyDefinition,
        data.description,
        data.smithySchema,
        data.constraints :+ constraint
      )

    /**
     * Generate the full output format block to inject into prompts.
     * Includes both the Smithy definition and parsing instructions.
     *
     * The Smithy definition is sanitized before injection: `list Foo { member: Bar }`
     * definitions are replaced with `Bar[]` array notation to prevent LLMs from
     * interpreting `member` as a literal JSON field name.
     */
    def outputFormatBlock: String =
      val sanitized: String = sanitizeSmithyForPrompt(smithyDefinition)
      val descPart: String  = description.fold("")(d => s"\n// $d\n")
      s"""Respond with JSON matching this schema:
         |```smithy$descPart
         |$sanitized
         |```
         |
         |Important:
         |- Respond ONLY with valid JSON, no additional text
         |- Use the exact field names shown
         |- Include all @required fields""".stripMargin

  /**
   * Sanitize Smithy IDL for LLM prompt injection.
   *
   * Transforms `list FooList { member: Bar }` definitions into `Bar[]` array
   * notation and removes the standalone list definitions. This prevents LLMs
   * from interpreting the Smithy `member` keyword as a literal JSON field name,
   * which would produce `{"field": {"member": [...]}}` instead of `{"field": [...]}`.
   */
  private def sanitizeSmithyForPrompt(idl: String): String =
    val listPattern: Regex = """(?s)list\s+(\w+)\s*\{\s*member\s*:\s*(\w+)\s*\}""".r

    val listMappings: Map[String, String] = listPattern
      .findAllMatchIn(idl)
      .map { m =>
        val listName: String    = m.group(1)
        val elementType: String = m.group(2)
        listName -> elementType
      }
      .toMap

    if listMappings.isEmpty then idl
    else
      val withoutListDefs: String = listPattern.replaceAllIn(idl, "")

      val withArrayNotation: String = listMappings.foldLeft(withoutListDefs) { case (acc, (listName, elementType)) =>
        val fieldRefPattern: Regex = s"""(:\\s*)$listName""".r
        fieldRefPattern.replaceAllIn(acc, m => s"${m.group(1)}$elementType[]")
      }

      val cleaned: String = withArrayNotation
        .replaceAll("""(?m)^\s*$[\n\r]+""", "\n")
        .replaceAll("""\n{3,}""", "\n\n")
        .trim

      cleaned

/**
 * Result of parsing an LLM response.
 */
enum ParseResult[+A]:
  case Success(value: A, warnings: List[String] = Nil)
  case Failure(errors: List[ParseError])

  def toEither: Either[List[ParseError], A] = this match
    case Success(value, _) => Right(value)
    case Failure(errors)   => Left(errors)

  def map[B](f: A => B): ParseResult[B] = this match
    case Success(value, warnings) => Success(f(value), warnings)
    case Failure(errors)          => Failure(errors)

  def flatMap[B](f: A => ParseResult[B]): ParseResult[B] = this match
    case Success(value, warnings) =>
      f(value) match
        case Success(b, moreWarnings) => Success(b, warnings ++ moreWarnings)
        case Failure(errors)          => Failure(errors)
    case Failure(errors) => Failure(errors)

sealed trait ParseError:
  def message: String

object ParseError:
  case class JsonSyntaxError(
    message: String,
    position: Option[Int] = None,
    recoveryAttempted: Boolean = false
  ) extends ParseError

  case class SchemaViolation(
    message: String,
    path: String,
    expectedType: String,
    actualValue: Option[String] = None
  ) extends ParseError

  case class MissingRequiredField(
    fieldName: String,
    path: String
  ) extends ParseError:
    def message: String = s"Missing required field '$fieldName' at $path"

  case class UnexpectedEnumValue(
    value: String,
    allowedValues: List[String],
    path: String
  ) extends ParseError:
    def message: String =
      s"Unexpected enum value '$value' at $path. Allowed: ${allowedValues.mkString(", ")}"

  case class NoJsonFound(
    rawResponse: String
  ) extends ParseError:
    def message: String = "No JSON object found in LLM response"
