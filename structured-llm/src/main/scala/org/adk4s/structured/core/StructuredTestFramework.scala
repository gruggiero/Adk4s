package org.adk4s.structured.core

import cats.effect.Async
import cats.syntax.all.*

/**
 * Structured test framework for testing LLM schema adherence.
 *
 * Provides utilities for running parse-only tests (no LLM call) and
 * full integration tests (with LLM call) against typed schemas.
 */
object StructuredTestFramework:

  /**
   * Result of a parse-only test.
   *
   * @param input The raw JSON input
   * @param parsed Whether parsing succeeded
   * @param value The parsed value (if successful)
   * @param warnings Warnings from the parser
   * @param errors Parse errors (if failed)
   */
  final case class ParseTestResult[A](
    input: String,
    parsed: Boolean,
    value: Option[A],
    warnings: List[String],
    errors: List[String]
  )

  /**
   * Run a parse-only test — no LLM call, just SAP parsing.
   *
   * @param input The raw JSON string to parse
   * @return ParseTestResult with details
   */
  def testParse[A: Schema](input: String): ParseTestResult[A] =
    org.adk4s.structured.sap.SchemaAlignedParser.parse[A](input) match
      case org.adk4s.structured.core.ParseResult.Success(value, warnings) =>
        ParseTestResult(input, parsed = true, Some(value), warnings, List.empty)
      case org.adk4s.structured.core.ParseResult.Failure(errors) =>
        ParseTestResult(input, parsed = false, None, List.empty, errors.map(_.message))

  /**
   * Run a batch of parse-only tests.
   *
   * @param inputs List of (label, json) pairs
   * @return Map of label → ParseTestResult
   */
  def testParseBatch[A: Schema](inputs: Vector[(String, String)]): Map[String, ParseTestResult[A]] =
    inputs.map { case (label, json) => label -> testParse[A](json) }.toMap

  /**
   * Result of a full integration test (with LLM call).
   *
   * @param prompt The prompt sent
   * @param rawResponse The raw LLM response
   * @param parsed Whether parsing succeeded
   * @param value The parsed value (if successful)
   * @param checks Constraint check results (if validated)
   * @param error Error message (if failed)
   */
  final case class IntegrationTestResult[A](
    prompt: String,
    rawResponse: Option[String],
    parsed: Boolean,
    value: Option[A],
    checks: Vector[org.adk4s.structured.core.ResponseCheck],
    error: Option[String]
  )

  /**
   * Run a full integration test — LLM call + SAP parsing + constraint validation.
   *
   * @param llm The StructuredLLM instance
   * @param prompt The prompt to send
   * @return IntegrationTestResult with details
   */
  def testIntegration[F[_]: Async, A: Schema](
    llm: StructuredLLM[F],
    prompt: Prompt
  ): F[IntegrationTestResult[A]] =
    llm.completeValidated[A](prompt).attempt.map {
      case Right(validationResult) =>
        IntegrationTestResult(
          prompt = prompt.conversation.messages.map(_.content).mkString("\n"),
          rawResponse = None,
          parsed = true,
          value = Some(validationResult.value),
          checks = validationResult.checks,
          error = None
        )
      case Left(err: StructuredLLMError) =>
        IntegrationTestResult(
          prompt = prompt.conversation.messages.map(_.content).mkString("\n"),
          rawResponse = None,
          parsed = false,
          value = None,
          checks = Vector.empty,
          error = Some(err.message)
        )
      case Left(other) =>
        IntegrationTestResult(
          prompt = prompt.conversation.messages.map(_.content).mkString("\n"),
          rawResponse = None,
          parsed = false,
          value = None,
          checks = Vector.empty,
          error = Some(other.getMessage)
        )
    }

  /**
   * Generate a test report from a batch of parse tests.
   *
   * @param results Map of label → ParseTestResult
   * @return Human-readable report
   */
  def reportParseResults[A](results: Map[String, ParseTestResult[A]]): String =
    val lines: Vector[String] = results.toVector.map { case (label, result) =>
      val status: String = if result.parsed then "PASS" else "FAIL"
      val details: String =
        if result.parsed then s"warnings: ${result.warnings.mkString(", ")}"
        else s"errors: ${result.errors.mkString(", ")}"
      s"[$status] $label — $details"
    }
    lines.mkString("\n")
