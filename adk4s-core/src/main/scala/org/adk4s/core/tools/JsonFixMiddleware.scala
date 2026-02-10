package org.adk4s.core.tools

import cats.data.Kleisli
import cats.effect.IO

/**
 * A ToolMiddleware that repairs common LLM JSON errors in tool arguments
 * before passing them to the tool endpoint.
 *
 * Eino equivalent: components/tool/middlewares/jsonfix
 *
 * Repairs:
 *   - Strips leading/trailing whitespace
 *   - Isolates the first {...} JSON object region
 *   - Removes common LLM artifacts (think tags, function call markers)
 *   - Removes trailing commas before } or ]
 *   - Replaces single-quoted strings with double-quoted strings
 *   - Adds missing braces
 */
object JsonFixMiddleware:

  val middleware: ToolMiddleware = (endpoint: ToolEndpoint) =>
    Kleisli { (input: ToolInput) =>
      val fixedArgs: String = repair(input.arguments)
      endpoint.run(input.copy(arguments = fixedArgs))
    }

  def repair(input: String): String =
    val trimmed: String = input.trim

    // Fast-path: valid JSON as-is
    if isValidJson(trimmed) then trimmed
    else
      // Isolate JSON object region if present
      val isolated: String = isolateJsonObject(trimmed)
      val cleaned: String = removeLlmArtifacts(isolated)

      if isValidJson(cleaned) then cleaned
      else
        // Apply heuristic fixes
        val fixed: String = applyHeuristicFixes(cleaned)
        fixed

  private def isValidJson(s: String): Boolean =
    try
      ujson.read(s)
      true
    catch
      case _: Exception => false

  private def isolateJsonObject(s: String): String =
    val openIdx: Int = s.indexOf('{')
    val closeIdx: Int = s.lastIndexOf('}')
    if openIdx >= 0 && closeIdx >= openIdx then
      val sub: String = s.substring(openIdx, closeIdx + 1)
      if isValidJson(sub) then sub
      else sub
    else s

  private def removeLlmArtifacts(s: String): String =
    val artifacts: List[String] = List(
      "<|FunctionCallBegin|>",
      "<|FunctionCallEnd|>",
      "<think>",
      "</think>",
      "```json",
      "```"
    )
    artifacts.foldLeft(s) { (acc: String, artifact: String) =>
      acc.replace(artifact, "")
    }.trim

  private def applyHeuristicFixes(s: String): String =
    var result: String = s

    // Fix trailing commas: ,} → } and ,] → ]
    result = result.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]")

    // Fix single-quoted strings → double-quoted
    result = replaceSingleQuotes(result)

    // Add missing braces
    if !result.startsWith("{") && result.endsWith("}") then
      result = "{" + result
    else if result.startsWith("{") && !result.endsWith("}") then
      result = result + "}"

    result

  private def replaceSingleQuotes(s: String): String =
    val sb: StringBuilder = new StringBuilder(s.length)
    var inDoubleQuote: Boolean = false
    var i: Int = 0
    while i < s.length do
      val c: Char = s.charAt(i)
      if c == '"' && (i == 0 || s.charAt(i - 1) != '\\') then
        inDoubleQuote = !inDoubleQuote
        sb.append(c)
      else if c == '\'' && !inDoubleQuote then
        sb.append('"')
      else
        sb.append(c)
      i = i + 1
    sb.toString
