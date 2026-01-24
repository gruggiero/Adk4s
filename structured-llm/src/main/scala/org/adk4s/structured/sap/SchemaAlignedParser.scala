package org.adk4s.structured.sap

import scala.annotation.tailrec
import scala.util.matching.Regex
import org.adk4s.structured.core.ParseError
import org.adk4s.structured.core.ParseResult
import org.adk4s.structured.core.Schema
import smithy4s.Blob
import smithy4s.Schema as Smithy4sSchema
import smithy4s.json.Json

/**
 * Schema-Aligned Parser (SAP)
 *
 * Implements lenient JSON parsing that recovers from common LLM output errors.
 * This adapts the recovery strategies used in BAML's jsonish parser:
 *  - Parse JSON as-is when possible.
 *  - Extract JSON from markdown code fences.
 *  - Grep for multiple JSON objects/arrays and, when found, also try combining them.
 *  - Apply common fixes (comments removal, quote fixes, trailing comma removal).
 *  - Attempt structural recovery for truncated outputs (missing brackets/quotes).
 *  - Fallback to treating the whole response as a JSON string when necessary.
 */
object SchemaAlignedParser:

  private final case class Candidate(json: String, warnings: List[String])

  /**
   * Parse an LLM response into the expected type.
   */
  def parse[A: Schema](response: String): ParseResult[A] =
    val schema: Schema[A] = Schema[A]
    val candidates: List[Candidate] = buildCandidates(response)
    attemptCandidates[A](candidates, schema.smithySchema)

  /**
   * Parse with custom configuration.
   */
  def parseWithConfig[A: Schema](
    response: String,
    config: ParserConfig
  ): ParseResult[A] =
    val schema: Schema[A] = Schema[A]
    val trimmed: String = response.trim
    if config.strictMode then
      tryParseWithSmithy[A](trimmed, schema.smithySchema, List.empty)
    else
      parse[A](response)

  /**
   * Configuration for the parser.
   */
  final case class ParserConfig(
    maxRecoveryAttempts: Int = 3,
    allowPartialResults: Boolean = false,
    strictMode: Boolean = false
  )

  private def attemptCandidates[A](
    candidates: List[Candidate],
    smithySchema: Smithy4sSchema[A]
  ): ParseResult[A] =
    candidates match
      case head :: tail =>
        tryParseWithSmithy[A](head.json, smithySchema, head.warnings) match
          case success @ ParseResult.Success(_, _) => success
          case ParseResult.Failure(_) => attemptCandidates[A](tail, smithySchema)
      case Nil =>
        ParseResult.Failure(
          List(ParseError.JsonSyntaxError("Failed to parse JSON after recovery attempts", None, recoveryAttempted = true))
        )

  /**
   * Build a list of parsing candidates, ordered by preference, mirroring the BAML
   * jsonish pipeline: markdown extraction -> multiple JSONs -> fixed JSON -> fallback string.
   */
  private def buildCandidates(response: String): List[Candidate] =
    val trimmed: String = response.trim

    val fenceCandidates: List[Candidate] =
      extractMarkdownCodeBlocks(trimmed).map { json =>
        val message: String = "Removed markdown code fences"
        Candidate(json, List(message))
      }

    val jsonSegments: List[String] = findJsonSegments(trimmed)

    val segmentCandidates: List[Candidate] =
      jsonSegments.map { json =>
        val message: String = "Extracted JSON segment from response"
        Candidate(json, List(message))
      }

    val aggregatedSegments: List[Candidate] =
      if jsonSegments.lengthCompare(1) > 0 then
        val aggregatedJson: String = s"[${jsonSegments.mkString(",")}]"
        val message: String = "Aggregated multiple JSON blocks into an array candidate"
        List(Candidate(aggregatedJson, List(message)))
      else
        List.empty

    val wholeResponseCandidate: List[Candidate] =
      if trimmed.nonEmpty then List(Candidate(trimmed, List.empty)) else List.empty

    val asStringCandidate: List[Candidate] =
      if trimmed.nonEmpty && !startsWithJsonDelimiter(trimmed) then
        val jsonString: String = encodeAsJsonString(trimmed)
        val message: String = "Treated response as JSON string value"
        List(Candidate(jsonString, List(message)))
      else
        List.empty

    val rawCandidates: List[Candidate] =
      fenceCandidates ++ segmentCandidates ++ aggregatedSegments ++ wholeResponseCandidate ++ asStringCandidate

    val cleaned: List[Candidate] =
      rawCandidates.flatMap(cleanAndRecoverCandidate)

    deduplicateCandidates(cleaned)

  private def deduplicateCandidates(candidates: List[Candidate]): List[Candidate] =
    val initial: (List[Candidate], Set[String]) = (List.empty, Set.empty)
    val deduped: (List[Candidate], Set[String]) =
      candidates.foldLeft[(List[Candidate], Set[String])](initial) { case ((acc, seen), cand) =>
        if seen.contains(cand.json) then
          (acc, seen)
        else
          (acc :+ cand, seen + cand.json)
      }
    deduped._1

  private def startsWithJsonDelimiter(text: String): Boolean =
    val trimmed: String = text.trim
    trimmed.nonEmpty && (trimmed.headOption.contains('{') || trimmed.headOption.contains('[') || trimmed.headOption.contains('"'))

  private def encodeAsJsonString(text: String): String =
    val escaped: String = text
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\b", "\\b")
      .replace("\f", "\\f")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s""""$escaped""""

  private def cleanAndRecoverCandidate(candidate: Candidate): List[Candidate] =
    val cleaned: Candidate = applyCleaning(candidate)
    val recoveredOpt: Option[Candidate] = recoverStructuralIssues(cleaned)
    recoveredOpt match
      case Some(recovered) => List(cleaned, recovered)
      case None            => List(cleaned)

  private def applyCleaning(candidate: Candidate): Candidate =
    val fencesResult: (String, Boolean) = removeMarkdownFences(candidate.json)
    val noFences: String = fencesResult._1
    val fenceRemoved: Boolean = fencesResult._2
    val warningsAfterFences: List[String] =
      candidate.warnings ++ (if fenceRemoved then List("Removed markdown code fences") else List.empty)

    val commentsResult: (String, Boolean) = removeComments(noFences)
    val noComments: String = commentsResult._1
    val commentsRemoved: Boolean = commentsResult._2
    val warningsAfterComments: List[String] =
      warningsAfterFences ++ (if commentsRemoved then List("Removed comments from JSON") else List.empty)

    val fixedQuotesResult: (String, Boolean) = fixQuotes(fixLeadingCommas(noComments))
    val fixedQuotes: String = fixedQuotesResult._1
    val quotesFixed: Boolean = fixedQuotesResult._2
    val warningsAfterQuotes: List[String] =
      warningsAfterComments ++ (if quotesFixed then List("Fixed quote or key issues") else List.empty)

    val trailingResult: (String, Boolean) = removeTrailingCommas(fixedQuotes)
    val noTrailingCommas: String = trailingResult._1
    val trailingRemoved: Boolean = trailingResult._2
    val warningsAfterTrailing: List[String] =
      warningsAfterQuotes ++ (if trailingRemoved then List("Removed trailing commas") else List.empty)

    Candidate(noTrailingCommas.trim, warningsAfterTrailing)

  private def recoverStructuralIssues(candidate: Candidate): Option[Candidate] =
    val recoveredOpt: Option[(String, List[String])] = tryRecovery(candidate.json)
    recoveredOpt.map { case (json, recoveryWarnings) =>
      Candidate(
        json,
        candidate.warnings ++ recoveryWarnings
      )
    }

  private def extractMarkdownCodeBlocks(text: String): List[String] =
    val fencePattern: Regex = """```(?:json|JSON|smithy|SMITHY)?\s*\n?([\s\S]*?)\n?\s*```""".r
    val matches: List[Regex.Match] = fencePattern.findAllMatchIn(text).toList
    val contents: List[String] = matches.map(_.group(1).trim)
    contents.filter(_.nonEmpty)

  private def findJsonSegments(text: String): List[String] =
    val indexed: List[(Char, Int)] = text.zipWithIndex.toList
    val starts: List[Int] = indexed.collect { case (c, idx) if c == '{' || c == '[' => idx }
    starts.flatMap(idx => balancedJsonAt(text, idx))

  private def balancedJsonAt(text: String, start: Int): Option[String] =
    if start < 0 || start >= text.length then None
    else
      val open: Char = text.charAt(start)
      val close: Char = if open == '{' then '}' else ']'
      val maybeBalance: Option[(Int, Int)] = scanForBalance(text, start, open, close)
      maybeBalance.map { case (endIndex, depthAtEnd) =>
        val base: String = text.substring(start, endIndex + 1)
        val suffix: String = close.toString.repeat(depthAtEnd)
        if depthAtEnd > 0 then base + suffix else base
      }

  private def scanForBalance(
    text: String,
    start: Int,
    open: Char,
    close: Char
  ): Option[(Int, Int)] =
    @tailrec
    def loop(index: Int, depth: Int, inString: Boolean, escaped: Boolean): Option[(Int, Int)] =
      if index >= text.length then Some((text.length - 1, depth))
      else
        val c: Char = text.charAt(index)
        val transition: (Boolean, Boolean, Int, Boolean) =
          if escaped then (inString, false, depth, false)
          else if c == '\\' && inString then (inString, true, depth, false)
          else if c == '"' then (!inString, false, depth, false)
          else if !inString && c == open then (inString, false, depth + 1, false)
          else if !inString && c == close then (inString, false, depth - 1, depth - 1 == 0)
          else (inString, false, depth, false)

        val nextInString: Boolean = transition._1
        val nextEscaped: Boolean = transition._2
        val nextDepth: Int = transition._3
        val done: Boolean = transition._4

        if done then Some((index, nextDepth))
        else loop(index + 1, nextDepth, nextInString, nextEscaped)

    loop(start, 0, inString = false, escaped = false)

  private def removeMarkdownFences(text: String): (String, Boolean) =
    val fencePattern: Regex = """```(?:json|JSON|smithy|SMITHY)?\s*\n?([\s\S]*?)\n?\s*```""".r
    fencePattern.findFirstMatchIn(text) match
      case Some(matched) => (matched.group(1).trim, true)
      case None          => (text, false)

  private def removeComments(text: String): (String, Boolean) =
    val singleLinePattern: Regex = """(?<!:)//[^\n]*""".r
    val strippedSingle: String = singleLinePattern.replaceAllIn(text, "")
    val singleChanged: Boolean = strippedSingle != text

    val multiLinePattern: Regex = """/\*[\s\S]*?\*/""".r
    val strippedMulti: String = multiLinePattern.replaceAllIn(strippedSingle, "")
    val multiChanged: Boolean = strippedMulti != strippedSingle

    (strippedMulti, singleChanged || multiChanged)

  private def fixLeadingCommas(text: String): String =
    val leadingCommaPattern: Regex = """^\s*,+""".r
    leadingCommaPattern.replaceAllIn(text, "")

  /**
   * Fix quote issues:
   * - Convert single quotes to double quotes (when used for strings)
   * - Add quotes to unquoted keys
   */
  private def fixQuotes(text: String): (String, Boolean) =
    val singleQuotePattern: Regex = """'([^']*)'""".r
    val replacedSingle: String = singleQuotePattern.replaceAllIn(text, "\"$1\"")
    val singleChanged: Boolean = replacedSingle != text

    val unquotedKeyPattern: Regex = """(?m)^\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*:""".r
    val quotedKeyCheck: Regex = """"[^"]+"\s*:""".r
    val shouldQuoteKeys: Boolean = unquotedKeyPattern.findFirstIn(replacedSingle).isDefined && !quotedKeyCheck.findFirstIn(replacedSingle).isDefined
    val withQuotedKeys: String =
      if shouldQuoteKeys then
        unquotedKeyPattern.replaceAllIn(replacedSingle, m => s""""${m.group(1)}":""")
      else
        replacedSingle
    val keyChanged: Boolean = withQuotedKeys != replacedSingle

    (withQuotedKeys, singleChanged || keyChanged)

  /**
   * Remove trailing commas before ] and }.
   */
  private def removeTrailingCommas(text: String): (String, Boolean) =
    val pattern: Regex = """,(\s*[}\]])""".r
    val newText: String = pattern.replaceAllIn(text, "$1")
    (newText, newText != text)

  /**
   * Additional recovery attempts for malformed JSON.
   * Returns the recovered JSON and any warnings for changes applied.
   */
  private def tryRecovery(json: String): Option[(String, List[String])] =
    val steps: List[String => (String, Boolean, String)] =
      List(
        applyCloseUnbalanced,
        applyInsertMissingCommas,
        applyFillMissingValues,
        applyCoerceNumericStrings,
        applyTrimTrailingGarbage
      )

    val aggregated: (String, Boolean, List[String]) =
      steps.foldLeft[(String, Boolean, List[String])]((json, false, List.empty)) {
        case ((currentJson, changedAcc, warningsAcc), step) =>
          val (nextJson, changed, warning) = step(currentJson)
          val updatedWarnings: List[String] =
            if changed then warningsAcc :+ warning else warningsAcc
          (nextJson, changedAcc || changed, updatedWarnings)
      }

    val finalJson: String = aggregated._1
    val finalChanged: Boolean = aggregated._2
    val finalWarnings: List[String] = aggregated._3

    if finalChanged then Some((finalJson, finalWarnings)) else None

  private def applyCloseUnbalanced(json: String): (String, Boolean, String) =
    val braceDiff: Int = json.count(_ == '{') - json.count(_ == '}')
    val bracketDiff: Int = json.count(_ == '[') - json.count(_ == ']')
    val quoteCount: Int = json.count(_ == '"')

    val closedBraces: String =
      if braceDiff > 0 then json + "}".repeat(braceDiff) else json
    val closedBrackets: String =
      if bracketDiff > 0 then closedBraces + "]".repeat(bracketDiff) else closedBraces
    val closedQuotes: String =
      if quoteCount % 2 != 0 then closedBrackets + "\"" else closedBrackets

    val changed: Boolean = closedQuotes != json
    (closedQuotes, changed, "Recovered truncated or unbalanced JSON")

  private def applyInsertMissingCommas(json: String): (String, Boolean, String) =
    val pattern: Regex = """((?:"[^"]*"|-?\d+(?:\.\d+)?|true|false|null|\}|\]))\s*(")""".r
    val replaced: String = pattern.replaceAllIn(json, m => s"${m.group(1)},${m.group(2)}")
    val changed: Boolean = replaced != json
    (replaced, changed, "Inserted missing commas between object fields")

  private def applyFillMissingValues(json: String): (String, Boolean, String) =
    val pattern: Regex = """("[^"]+"\s*:)\s*(,|\}|\])""".r
    val replaced: String = pattern.replaceAllIn(json, m => s"${m.group(1)} null${m.group(2)}")
    val changed: Boolean = replaced != json
    (replaced, changed, "Filled missing values with null")

  private def applyCoerceNumericStrings(json: String): (String, Boolean, String) =
    val pattern: Regex = """("[^"]+"\s*:\s*)"(-?\d+(?:\.\d+)?)""".r
    val replaced: String = pattern.replaceAllIn(json, m => s"${m.group(1)}${m.group(2)}")
    val changed: Boolean = replaced != json
    (replaced, changed, "Coerced numeric-looking strings to numbers")

  private def applyTrimTrailingGarbage(json: String): (String, Boolean, String) =
    val trimmed: String =
      if json.nonEmpty && (json.head == '{' || json.head == '[') then
        balancedJsonAt(json, 0).map(_.length) match
          case Some(len) if len < json.length =>
            json.substring(0, len)
          case _ => json
      else
        json
    val changed: Boolean = trimmed != json
    (trimmed, changed, "Trimmed trailing non-JSON content after balanced value")

  /**
   * Decode JSON with smithy4s, collecting warnings.
   */
  private def tryParseWithSmithy[A](
    jsonString: String,
    smithySchema: Smithy4sSchema[A],
    warnings: List[String]
  ): ParseResult[A] =
    val blob: Blob = Blob(jsonString)
    Json.read[A](blob)(using smithySchema) match
      case Right(value) =>
        ParseResult.Success(value, warnings)
      case Left(error) =>
        ParseResult.Failure(
          List(
            ParseError.SchemaViolation(
              message = error.getMessage,
              path = "",
              expectedType = smithySchema.shapeId.toString
            )
          )
        )
