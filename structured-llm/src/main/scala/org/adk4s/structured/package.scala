package org.adk4s

/**
 * Structured LLM: A BAML-inspired wrapper for llm4s
 *
 * This package provides type-safe, composable structured outputs from LLMs
 * using Smithy schemas and Schema-Aligned Parsing (SAP).
 *
 * == Quick Start ==
 *
 * {{{
 * import org.adk4s.structured.*
 * import org.adk4s.structured.template.syntax.*
 * import cats.effect.IO
 *
 * // 1. Define your output type with a Schema
 * case class Person(name: String, age: Int)
 *
 * given Schema[Person] = SmithySchema.manual(
 *   """structure Person {
 *     |  @required name: String
 *     |  @required age: Integer
 *     |}""".stripMargin
 * )
 *
 * // 2. Create a structured LLM client
 * val structured = StructuredLLM.fromClient[IO](llmClient)
 *
 * // 3. Make type-safe calls
 * val result: IO[Person] = structured.complete[Person](
 *   Prompt.simple("Extract person info", "John is 30 years old")
 * )
 * }}}
 *
 * == Key Concepts ==
 *
 * '''Schema[A]''': Typeclass providing Smithy IDL definition and JSON decoder
 *
 * '''StructuredLLM[F]''': Main wrapper providing `complete[A](prompt): F[A]`
 *
 * '''Prompt''': Immutable conversation representation
 *
 * '''PromptTemplate[I]''': Reusable template taking input I to produce a Prompt
 *
 * '''SAP (Schema-Aligned Parser)''': Lenient JSON parser that recovers from errors
 *
 * @see [[org.adk4s.structured.core.Schema]] for schema definition
 * @see [[org.adk4s.structured.core.StructuredLLM]] for the main client
 * @see [[org.adk4s.structured.template.syntax]] for prompt DSL
 */
package object structured:

  // Core types
  export core.Schema
  export core.ParseResult
  export core.ParseError

  // Prompt types
  export core.Prompt
  export core.PromptTemplate
  export core.Message
  export core.Role

  // Main client
  export core.StructuredLLM
  export core.StructuredLLMError

  // Smithy integration
  // export smithy.SmithySchemaDerivation

  // SAP
  export sap.SchemaAlignedParser

  /**
   * Type alias for the core abstraction: a function from Prompt to structured output.
   *
   * This is the fundamental building block for composition.
   */
  type StructuredCall[F[_], A] = Prompt => F[A]

  /**
   * Convenience type for IO-based structured calls.
   */
  type IOStructuredCall[A] = StructuredCall[cats.effect.IO, A]

/**
 * Template syntax extensions.
 *
 * Import this for the prompt string interpolators:
 * {{{
 * import org.adk4s.structured.template.syntax.*
 *
 * val p = prompt"""
 *   |<s>System message</s>
 *   |<u>User message with $variable</u>
 * """
 * }}}
 */
package object templates:
  export structured.template.syntax.*
  export structured.template.dsl.{ systemMessage, userMessage, template }
