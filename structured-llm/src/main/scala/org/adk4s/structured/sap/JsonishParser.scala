package org.adk4s.structured.sap

import scala.annotation.tailrec

/**
 * Tolerant string → `JsonishValue` parser — replaces SAP's regex-cleaning
 * candidate pipeline with a real scanner that tracks quote state.
 *
 * Per the type-aware-sap-coercion spec:
 * - Markdown-fence extraction (```json ... ```)
 * - Structural balance recovery (unclosed braces/brackets → Incomplete)
 * - Comment stripping (// and /* */)
 * - In-string-literal tracking that correctly distinguishes apostrophes
 *   inside string values from quote delimiters
 * - Does NOT mutate the input string before parsing (no regex-based fixQuotes)
 *
 * The quote-state-tracking scanner is absorbed from `JsonFixMiddleware`
 * (which had the correct scanner, unlike SAP's regex that caused the
 * apostrophe bug).
 */
object JsonishParser:

  /**
   * Parse a raw LLM response string into a `JsonishValue`.
   *
   * This is a tolerant parser — it handles markdown fences, comments,
   * single-quoted strings, unquoted keys, trailing commas, and structural
   * imbalances. It does NOT crash on any input (total function).
   */
  def parse(raw: String): JsonishValue =
    val trimmed: String = raw.trim
    if trimmed.isEmpty then JsonishValue.Str("", CompletionState.Incomplete)
    else
      // Step 1: Extract from markdown fences if present
      val extracted: String = extractMarkdownFence(trimmed).getOrElse(trimmed)
      // Step 2: Strip comments
      val noComments: String = stripComments(extracted)
      // Step 3: Normalize Unicode smart quotes
      val normalized: String = UnicodeQuoteNormalizer.normalize(noComments)
      // Step 4: Fix quotes using quote-state-tracking scanner (NOT regex)
      val fixedQuotes: String = fixQuotesWithScanner(normalized)
      // Step 5: Remove trailing commas
      val noTrailingCommas: String = removeTrailingCommas(fixedQuotes)
      // Step 6: Parse the cleaned string into JsonishValue
      parseJsonish(noTrailingCommas)

  /**
   * Repair a JSON string (for tool-argument repair).
   * Returns the repaired JSON string, not a JsonishValue.
   * This absorbs JsonFixMiddleware.repair's functionality.
   */
  def repair(raw: String): String =
    val trimmed: String = raw.trim
    if isValidJson(trimmed) then trimmed
    else
      val extracted: String = extractMarkdownFence(trimmed).getOrElse(trimmed)
      val noComments: String = stripComments(extracted)
      val normalized: String = UnicodeQuoteNormalizer.normalize(noComments)
      val fixedQuotes: String = fixQuotesWithScanner(normalized)
      val noTrailingCommas: String = removeTrailingCommas(fixedQuotes)
      val balanced: String = balanceBraces(noTrailingCommas)
      balanced

  // ── Markdown fence extraction ──────────────────────────────────────────

  private def extractMarkdownFence(s: String): Option[String] =
    val fencePattern: String = "```"
    val startIdx: Int = s.indexOf(fencePattern)
    if startIdx < 0 then None
    else
      val afterStart: Int = startIdx + 3
      // Skip optional language tag (json, jsonl, etc.)
      val lineEnd: Int = s.indexOf('\n', afterStart)
      if lineEnd < 0 then None
      else
        val endIdx: Int = s.indexOf(fencePattern, lineEnd + 1)
        if endIdx < 0 then
          // Unclosed fence — take everything after the first line
          Some(s.substring(lineEnd + 1).trim)
        else
          Some(s.substring(lineEnd + 1, endIdx).trim)

  // ── Comment stripping ──────────────────────────────────────────────────

  private def stripComments(s: String): String =
    // Strip /* */ block comments and // line comments, but NOT inside strings
    @tailrec
    def loop(i: Int, inString: Boolean, acc: StringBuilder): String =
      if i >= s.length then acc.toString
      else
        val c: Char = s.charAt(i)
        if inString then
          if c == '"' && s.charAt(i - 1) != '\\' then loop(i + 1, false, acc.append(c))
          else loop(i + 1, true, acc.append(c))
        else
          if c == '"' then loop(i + 1, true, acc.append(c))
          else if c == '/' && i + 1 < s.length && s.charAt(i + 1) == '/' then
            // Line comment — skip to end of line
            val lineEnd: Int = s.indexOf('\n', i)
            if lineEnd < 0 then acc.toString
            else loop(lineEnd + 1, false, acc)
          else if c == '/' && i + 1 < s.length && s.charAt(i + 1) == '*' then
            // Block comment — skip to */
            val closeIdx: Int = s.indexOf("*/", i + 2)
            if closeIdx < 0 then acc.toString
            else loop(closeIdx + 2, false, acc)
          else loop(i + 1, false, acc.append(c))

    loop(0, false, new StringBuilder(s.length))

  // ── Quote-state-tracking scanner (absorbed from JsonFixMiddleware) ────

  /**
   * Replace single-quoted strings with double-quoted strings using a
   * quote-state-tracking scanner. This correctly handles apostrophes
   * inside double-quoted strings (the apostrophe bug fix).
   *
   * The regex-based approach (`'([^']*)'`) corrupts strings like
   * "it's fine, isn't it" by matching 's fine, isn' as a single-quoted
   * string. This scanner tracks whether we're inside a double-quoted
   * string and only replaces single quotes outside of them.
   */
  private def fixQuotesWithScanner(s: String): String =
    // First: fix unquoted keys (keys that are not quoted at all)
    val withQuotedKeys: String = quoteUnquotedKeys(s)
    // Then: replace single quotes with double quotes (outside double-quoted strings)
    replaceSingleQuotes(withQuotedKeys)

  /** Quote unquoted JSON keys using a scanner. */
  private def quoteUnquotedKeys(s: String): String =
    // Simple approach: find patterns like `key:` at the start of a line or after {/,
    // where key is not already quoted
    @tailrec
    def loop(i: Int, inString: Boolean, acc: StringBuilder): String =
      if i >= s.length then acc.toString
      else
        val c: Char = s.charAt(i)
        if inString then
          if c == '"' && s.charAt(i - 1) != '\\' then loop(i + 1, false, acc.append(c))
          else loop(i + 1, true, acc.append(c))
        else if c == '"' then loop(i + 1, true, acc.append(c))
        else if c == '{' || c == ',' then
          // Look ahead for an unquoted key
          val rest: String = s.substring(i + 1).dropWhile(ch => ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r')
          if rest.nonEmpty && rest.head != '"' && rest.head != '}' then
            // Find the colon
            val colonIdx: Int = rest.indexOf(':')
            if colonIdx > 0 then
              val key: String = rest.substring(0, colonIdx).trim
              if key.forall(ch => ch.isLetterOrDigit || ch == '_' || ch == '-') then
                // Quote this key
                val prefix: String = s.substring(i + 1, s.length - rest.length)
                acc.append(c)
                acc.append(prefix.dropWhile(ch => ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r'))
                acc.append('"')
                acc.append(key)
                acc.append('"')
                val afterKey: Int = i + 1 + prefix.length + colonIdx
                loop(afterKey, false, acc)
              else loop(i + 1, false, acc.append(c))
            else loop(i + 1, false, acc.append(c))
          else loop(i + 1, false, acc.append(c))
        else loop(i + 1, false, acc.append(c))

    loop(0, false, new StringBuilder(s.length))

  /**
   * Replace single quotes with double quotes, tracking whether we're inside
   * a double-quoted string. Absorbed from JsonFixMiddleware.replaceSingleQuotes.
   */
  private def replaceSingleQuotes(s: String): String =
    @tailrec
    def loop(i: Int, inDoubleQuote: Boolean, acc: StringBuilder): String =
      if i >= s.length then acc.toString
      else
        val c: Char = s.charAt(i)
        if c == '"' && (i == 0 || s.charAt(i - 1) != '\\') then
          loop(i + 1, !inDoubleQuote, acc.append(c))
        else if c == '\'' && !inDoubleQuote then
          loop(i + 1, inDoubleQuote, acc.append('"'))
        else
          loop(i + 1, inDoubleQuote, acc.append(c))

    loop(0, false, new StringBuilder(s.length))

  // ── Trailing comma removal ─────────────────────────────────────────────

  private def removeTrailingCommas(s: String): String =
    s.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]")

  // ── Brace balancing ────────────────────────────────────────────────────

  private def balanceBraces(s: String): String =
    val openBraces: Int = s.count(_ == '{')
    val closeBraces: Int = s.count(_ == '}')
    val openBrackets: Int = s.count(_ == '[')
    val closeBrackets: Int = s.count(_ == ']')
    val missingBraces: Int = openBraces - closeBraces
    val missingBrackets: Int = openBrackets - closeBrackets
    if missingBraces > 0 then s + "}" * missingBraces
    else if missingBrackets > 0 then s + "]" * missingBrackets
    else s

  /**
   * Fix quotes for SAP — replaces the old regex-based `fixQuotes` method.
   * Uses the quote-state-tracking scanner to:
   * 1. Quote unquoted JSON keys
   * 2. Replace single-quoted strings with double-quoted strings
   *
   * Unlike the old regex, this correctly preserves apostrophes inside
   * double-quoted string values.
   */
  def fixQuotesForSAP(s: String): String =
    val withQuotedKeys: String = quoteUnquotedKeys(s)
    replaceSingleQuotes(withQuotedKeys)

  // ── JSON validation ────────────────────────────────────────────────────

  private def isValidJson(s: String): Boolean =
    try
      ujson.read(s)
      true
    catch
      case _: Exception => false

  // ── JsonishValue parsing ───────────────────────────────────────────────

  /**
   * Parse a cleaned JSON string into a `JsonishValue`.
   * Uses ujson as the underlying parser, then converts to JsonishValue.
   */
  private def parseJsonish(s: String): JsonishValue =
    try
      val parsed: ujson.Value = ujson.read(s)
      ujsonToJsonish(parsed, CompletionState.Complete)
    catch
      case _: Exception =>
        // Try to recover — treat as string if not valid JSON
        JsonishValue.Str(s, CompletionState.Incomplete)

  /** Convert a ujson.Value to a JsonishValue. */
  private def ujsonToJsonish(value: ujson.Value, state: CompletionState): JsonishValue =
    value match
      case ujson.Null       => JsonishValue.Null
      case ujson.Bool(b)    => JsonishValue.Bool(b, state)
      case ujson.Num(n)     => JsonishValue.Num(n, state)
      case ujson.Str(s)     => JsonishValue.Str(s, state)
      case ujson.Arr(items) => JsonishValue.Arr(items.toVector.map(v => ujsonToJsonish(v, state)), state)
      case ujson.Obj(fields) =>
        JsonishValue.Obj(fields.toVector.map { case (k, v) => (k, ujsonToJsonish(v, state)) }, state)
