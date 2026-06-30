package org.adk4s.core.error

import org.llm4s.error.LLMError
import org.adk4s.structured.core.StructuredLLMError
import org.adk4s.core.interrupt.InterruptSignal
import cats.Show

sealed trait AdkError extends Throwable:
  def message: String
  override def getMessage: String = message

case class LlmCallError(underlying: LLMError) extends AdkError:
  def message: String = s"LLM call failed: ${underlying.formatted}"

case class StructuredOutputError(underlying: StructuredLLMError) extends AdkError:
  def message: String = s"Structured output error: ${underlying.message}"

case class TypeMismatchError(expected: String, actual: String, path: List[String]) extends AdkError:
  def message: String = s"Type mismatch at ${path.mkString(".")}: expected $expected, got $actual"

case class MissingFieldError(field: String, path: List[String]) extends AdkError:
  def message: String = s"Missing required field: ${(path :+ field).mkString(".")}"

case class NodeNotFoundError(nodeKey: String) extends AdkError:
  def message: String = s"Node '$nodeKey' not found in graph"

case class EdgeValidationError(from: String, to: String, reason: String) extends AdkError:
  def message: String = s"Invalid edge $from -> $to: $reason"

case class MaxStepsExceededError(steps: Int, max: Int) extends AdkError:
  def message: String = s"Exceeded maximum steps: $steps > $max"

case class GraphCompiledError() extends AdkError:
  def message: String = "Graph already compiled, cannot be modified"

case class GraphEntryMissingError() extends AdkError:
  def message: String = "Graph entry node is not set"

case class GraphEndNodesMissingError() extends AdkError:
  def message: String = "Graph end nodes are not set"

case class ToolNotFoundError(toolName: String) extends AdkError:
  def message: String = s"Tool '$toolName' not found in registry"

case class ToolExecutionError(toolName: String, cause: Throwable) extends AdkError:
  def message: String = s"Tool '$toolName' execution failed: ${cause.getMessage}"

case class StateTypeMismatchError(expected: String, actual: String) extends AdkError:
  def message: String = s"State type mismatch: expected $expected, got $actual"

case class NodeAlreadyExistsError(nodeKey: String) extends AdkError:
  def message: String = s"Node '$nodeKey' already exists"

case class SourceNodeNotFoundError(nodeKey: String) extends AdkError:
  def message: String = s"Source node '$nodeKey' does not exist"

case class NodeDoesNotExistError(nodeKey: String) extends AdkError:
  def message: String = s"Node '$nodeKey' does not exist"

case class FanInError(nodeKey: String) extends AdkError:
  def message: String = s"Fan-in not supported for node '$nodeKey' - nodes can have at most one incoming edge"

case class BranchTargetError(nodeKey: String, targetNode: String) extends AdkError:
  def message: String = s"Branch target '$targetNode' is not a declared outgoing edge from node '$nodeKey'"

case class AgentInterruptedException(signal: InterruptSignal) extends AdkError:
  def message: String = s"Agent interrupted: ${signal.info} at ${signal.address.map(_.name).mkString(" > ")}"

case class CheckpointNotFoundError(checkpointId: String) extends AdkError:
  def message: String = s"Checkpoint '$checkpointId' not found"

case class GenericError(message: String) extends AdkError

case class NodeKeyError(invalidKey: String) extends AdkError:
  def message: String = s"Invalid node key: '$invalidKey'"

object AdkError:
  given Show[AdkError] = Show.show(_.message)

  def apply(message: String): AdkError = GenericError(message)

