package org.adk4s.structured.sap

/**
 * Context threaded through `TypeCoercer.coerce` calls, tracking the current
 * path, parser configuration, and recursion depth.
 *
 * Per the type-aware-sap-coercion spec: "ParsingContext tracks depth —
 * a nested object coercion at depth 3 increments depth for the recursive
 * call."
 */
final case class ParsingContext(
  path: Vector[String] = Vector.empty,
  config: SchemaAlignedParser.ParserConfig = SchemaAlignedParser.ParserConfig(),
  depth: Int = 0
):

  /** Push a field name onto the path and increment depth for nested coercion. */
  def nest(field: String): ParsingContext =
    copy(path = path :+ field, depth = depth + 1)

  /** Increment depth without adding a path segment (for array elements). */
  def nest: ParsingContext =
    copy(depth = depth + 1)

  /** Render the current path as a dotted string (for error messages). */
  def pathString: String =
    if path.isEmpty then "<root>"
    else path.mkString(".")

object ParsingContext:
  /** Empty context — the starting point for top-level coercion. */
  val empty: ParsingContext = ParsingContext()
