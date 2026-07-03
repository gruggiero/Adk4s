package org.adk4s.core.error

import munit.CatsEffectSuite
import org.llm4s.error.NetworkError
import org.adk4s.structured.core.StructuredLLMError.LLMCallFailed
import org.adk4s.structured.core.Prompt

class AdkErrorTest extends CatsEffectSuite:

  test("LlmCallError formats message with underlying error") {
    val underlying = NetworkError("Connection timeout", None, "openai")
    val error      = LlmCallError(underlying)
    assert(error.message.contains("LLM call failed:"))
    assert(error.message.contains("NetworkError"))
  }

  test("StructuredOutputError formats message with underlying error") {
    val underlying = LLMCallFailed(NetworkError("Timeout", None, "anthropic"), Prompt.empty)
    val error      = StructuredOutputError(underlying)
    assert(error.message.contains("Structured output error:"))
    assert(error.message.contains("LLM call failed"))
  }

  test("TypeMismatchError formats message correctly") {
    val error = TypeMismatchError("String", "Number", List("user", "age"))
    assertEquals(error.message, "Type mismatch at user.age: expected String, got Number")
  }

  test("TypeMismatchError with single element path") {
    val error = TypeMismatchError("String", "Number", List("age"))
    assertEquals(error.message, "Type mismatch at age: expected String, got Number")
  }

  test("TypeMismatchError with empty path") {
    val error = TypeMismatchError("String", "Number", List.empty)
    assertEquals(error.message, "Type mismatch at : expected String, got Number")
  }

  test("MissingFieldError formats message correctly") {
    val error = MissingFieldError("email", List("user"))
    assertEquals(error.message, "Missing required field: user.email")
  }

  test("MissingFieldError with empty path") {
    val error = MissingFieldError("email", List.empty)
    assertEquals(error.message, "Missing required field: email")
  }

  test("NodeNotFoundError formats message correctly") {
    val error = NodeNotFoundError("agent_1")
    assertEquals(error.message, "Node 'agent_1' not found in graph")
  }

  test("EdgeValidationError formats message correctly") {
    val error = EdgeValidationError("node_a", "node_b", "Missing required parameter")
    assertEquals(error.message, "Invalid edge node_a -> node_b: Missing required parameter")
  }

  test("MaxStepsExceededError formats message correctly") {
    val error = MaxStepsExceededError(15, 10)
    assertEquals(error.message, "Exceeded maximum steps: 15 > 10")
  }

  test("GraphCompiledError formats message correctly") {
    val error = GraphCompiledError()
    assertEquals(error.message, "Graph already compiled, cannot be modified")
  }

  test("ToolNotFoundError formats message correctly") {
    val error = ToolNotFoundError("search")
    assertEquals(error.message, "Tool 'search' not found in registry")
  }

  test("ToolExecutionError formats message correctly") {
    val cause = new RuntimeException("API timeout")
    val error = ToolExecutionError("search", cause)
    assert(error.message.contains("Tool 'search' execution failed:"))
    assert(error.message.contains("API timeout"))
  }

  test("StateTypeMismatchError formats message correctly") {
    val error = StateTypeMismatchError("Map[String, Any]", "String")
    assertEquals(error.message, "State type mismatch: expected Map[String, Any], got String")
  }

  test("Show instance formats errors correctly") {
    val error = NodeNotFoundError("test_node")
    assertEquals(cats.Show[AdkError].show(error), error.message)
  }

  test("Show instance works for all error types") {
    val errors: List[AdkError] = List(
      TypeMismatchError("A", "B", List.empty),
      MissingFieldError("x", List.empty),
      NodeNotFoundError("n"),
      EdgeValidationError("a", "b", "c"),
      MaxStepsExceededError(5, 3),
      GraphCompiledError(),
      ToolNotFoundError("t"),
      StateTypeMismatchError("A", "B")
    )
    errors.foreach(error => assertEquals(cats.Show[AdkError].show(error), error.message))
  }

  test("getMessage returns message") {
    val error = NodeNotFoundError("test")
    assertEquals(error.getMessage, error.message)
  }
