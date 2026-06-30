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

  /** Package-private for test access. */
  private[tools] def applyHeuristicFixes(s: String): String =
    import scala.annotation.tailrec

    def applyOnce(input: String): String =
      // Fix trailing commas: ,} → } and ,] → ]
      val step1: String = input.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]")

      // Fix single-quoted strings → double-quoted
      val step2: String = replaceSingleQuotes(step1)

      // Add missing braces
      val step3: String =
        if !step2.startsWith("{") && step2.endsWith("}") then "{" + step2
        else if step2.startsWith("{") && !step2.endsWith("}") then step2 + "}"
        else step2

      step3

    @tailrec
    def loop(current: String): String =
      val next: String = applyOnce(current)
      if next == current then next
      else loop(next)

    loop(s)

  /** Package-private for test access. */
  private[tools] def replaceSingleQuotes(s: String): String =
    import scala.annotation.tailrec

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
