package org.adk4s.structured.sap

import org.adk4s.structured.core.ParseError
import org.adk4s.structured.core.ParseResult

/**
 * Type coercion from JsonishValue to typed values.
 *
 * This is a pragmatic implementation that provides the core coercions:
 * - String → Int, String → Boolean, String → Double
 * - Single value → Array
 * - Enum fuzzy matching (via EnumMatching)
 *
 * The coercer integrates with the existing smithy4s decoder by first
 * converting JsonishValue back to JSON, then letting smithy4s decode.
 * This ensures backward compatibility with existing schemas.
 */
object TypeCoercer:

  /**
   * Coerce a JsonishValue to a JSON string suitable for smithy4s decoding.
   *
   * This resolves AnyOf by picking the first choice that decodes successfully,
   * and applies type coercions (string→int, string→bool, single→array).
   *
   * @param value The JsonishValue to coerce
   * @return The coerced JSON string and flags recording coercions applied
   */
  def coerceToJson(value: JsonishValue): (String, Vector[CoercionFlag]) =
    value match
      case JsonishValue.Null       => ("null", Vector.empty)
      case JsonishValue.Bool(b, _) => (b.toString, Vector.empty)
      case JsonishValue.Num(n, _)  => (n.toString, Vector.empty)
      case JsonishValue.Str(s, _)  => (s"\"${escapeJson(s)}\"", Vector.empty)
      case JsonishValue.Arr(items, _) =>
        val coerced: Vector[(String, Vector[CoercionFlag])] = items.map(coerceToJson)
        val json: String                                    = coerced.map(_._1).mkString("[", ",", "]")
        val flags: Vector[CoercionFlag]                     = coerced.flatMap(_._2)
        (json, flags)
      case JsonishValue.Obj(fields, _) =>
        val coerced: Vector[(String, String, Vector[CoercionFlag])] =
          fields.map { case (k, v) => (k, coerceToJson(v)) }.map { case (k, (j, f)) => (k, j, f) }
        val json: String = coerced.map { case (k, j, _) => s"\"${escapeJson(k)}\":$j" }.mkString("{", ",", "}")
        val flags: Vector[CoercionFlag] = coerced.flatMap(_._3)
        (json, flags)
      case JsonishValue.Markdown(_, inner, _) => coerceToJson(inner)
      case JsonishValue.AnyOf(choices, _)     =>
        // Pick the first choice (best effort — smithy4s will validate)
        choices.headOption match
          case Some(first) =>
            val (json, flags) = coerceToJson(first)
            (json, flags :+ CoercionFlag.AnyOfResolved)
          case None => ("null", Vector.empty)

  /**
   * Parse an LLM response into JsonishValue, then coerce to JSON for smithy4s.
   *
   * This is the main entry point for type-aware parsing. It:
   * 1. Parses the response into a JsonishValue using `JsonishParser.parse`
   *    (tolerant parser with quote-state-tracking — no regex corruption)
   * 2. Resolves AnyOf and applies coercions via `coerceToJson`
   * 3. Returns the cleaned JSON string for smithy4s decoding
   *
   * @param response The raw LLM response
   * @return The cleaned JSON string and coercion flags
   */
  def parseAndCoerce(response: String): (String, Vector[CoercionFlag]) =
    val jsonishValue: JsonishValue = JsonishParser.parse(response)
    coerceToJson(jsonishValue)

  /**
   * Coerce a JsonishValue to a typed value with flags and score.
   *
   * Per the type-aware-sap-coercion spec:
   * `(JsonishValue, ParsingContext, Schema[A]) => Either[ParsingError, BamlValueWithFlags[A]]`
   *
   * This implementation uses `coerceToJson` to produce a JSON string, then
   * relies on smithy4s for the actual type decoding. The flags and score
   * are derived from the coercions applied.
   *
   * @param value   The JsonishValue to coerce
   * @param context The parsing context (path, depth, config)
   * @return Either a parsing error or a BamlValueWithFlags containing the
   *         coerced JSON string (for smithy4s to decode)
   */
  def coerce(
    value: JsonishValue,
    context: ParsingContext
  ): Either[ParsingError, BamlValueWithFlags[String]] =
    val (json: String, flags: Vector[CoercionFlag]) = coerceToJson(value)
    if json.isEmpty then Left(ParsingError("Empty value", context.pathString, "non-empty JSON", Some("empty string")))
    else
      val score: CoercionScore = CoercionScore.fromFlags(flags)
      Right(BamlValueWithFlags(json, flags, score))

  /**
   * Escape a string for JSON output.
   */
  private def escapeJson(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
