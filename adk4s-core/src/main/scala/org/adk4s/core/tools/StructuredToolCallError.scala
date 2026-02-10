package org.adk4s.core.tools

import cats.ApplicativeError
import org.llm4s.llmconnect.model.ToolCall
import ujson.Value

/** Unified error ADT for all structured tool call failures.
  *
  * This sealed trait consolidates errors from argument parsing, tool execution,
  * and result decoding into a single hierarchy for consistent error handling.
  *
  * @example
  * {{{
  * structured.execute[Request, Result](toolCall).handleErrorWith {
  *   case StructuredToolCallError.UnknownTool(name) =>
  *     IO.raiseError(new Exception(s"Tool '$name' not registered"))
  *   case StructuredToolCallError.InvalidArguments(errors) =>
  *     IO.raiseError(new Exception(s"Bad arguments: ${errors.map(_.message).mkString(", ")}"))
  *   case StructuredToolCallError.ExecutionFailed(cause) =>
  *     IO.raiseError(cause)
  *   case StructuredToolCallError.ResultParsingFailed(msg, raw) =>
  *     IO.raiseError(new Exception(s"Cannot parse result: $msg"))
  * }
  * }}}
  */
sealed trait StructuredToolCallError extends Throwable

/** Companion object containing [[StructuredToolCallError]] subtypes. */
object StructuredToolCallError:
  /** The requested tool is not registered in the [[org.llm4s.toolapi.ToolRegistry]].
    *
    * @param toolName the name of the unknown tool
    */
  case class UnknownTool(
    toolName: String
  ) extends StructuredToolCallError:
    override def getMessage: String = s"Unknown tool: $toolName"

  /** Tool arguments failed schema validation or parsing.
    *
    * @param errors list of schema errors describing what went wrong
    */
  case class InvalidArguments(
    errors: List[ToolSchemaError]
  ) extends StructuredToolCallError:
    override def getMessage: String =
      s"Invalid arguments: ${errors.map(_.message).mkString(", ")}"

  /** Tool handler threw an exception during execution.
    *
    * @param cause the underlying exception from the tool handler
    */
  case class ExecutionFailed(
    cause: Throwable
  ) extends StructuredToolCallError:
    override def getMessage: String = s"Tool execution failed: ${Option(cause.getMessage).getOrElse(cause.toString)}"
    override def getCause: Throwable = cause

  /** Tool result could not be parsed into the expected output type.
    *
    * Includes the raw JSON for debugging purposes.
    *
    * @param message description of the parsing failure
    * @param rawJson the raw JSON that failed to parse
    */
  case class ResultParsingFailed(
    message: String,
    rawJson: Value
  ) extends StructuredToolCallError:
    override def getMessage: String = s"Result parsing failed: $message\nRaw JSON: $rawJson"
