package org.adk4s.core.tools.typecontract

/** Typed contract for spec: tool-abstraction-dedup
  *
  * This file is a COMPILE-ONLY contract. It declares the new type signatures
  * and method signatures that the implementation must honor. Method bodies
  * are `???` — they will be promoted to main sources during Step 3.
  *
  * spec: tool-abstraction-dedup
  */

import org.adk4s.core.tools.StructuredToolFunction
import org.adk4s.core.tools.ToolSchema
import org.adk4s.core.tools.ToolSchemaError
import org.llm4s.toolapi.ObjectSchema
import org.llm4s.toolapi.SafeParameterExtractor
import org.llm4s.toolapi.ToolCallError
import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.ToolRegistry
import ujson.Value
import upickle.default.*

// ─────────────────────────────────────────────────────────────────
// CONTRACT 1: StructuredToolFunction.toToolFunction
// Synthesizes a ToolFunction[ujson.Value, ujson.Value] from the
// inputSchema, outputSchema, and handler.
// ─────────────────────────────────────────────────────────────────

object ToToolFunctionContract:

  /** Synthesizes a llm4s ToolFunction from a StructuredToolFunction.
    *
    * The ToolFunction's schema is built from the StructuredToolFunction's
    * inputSchema.jsonSchema. The handler decodes args via inputSchema.decoder,
    * calls the structured handler, and encodes the result via outputSchema.encoder.
    */
  def synthesize[I, O](stf: StructuredToolFunction[I, O]): ToolFunction[ujson.Value, ujson.Value] =
    // SchemaDefinition is sealed — use ObjectSchema (permissive; real validation
    // is in the handler via inputSchema.decoder)
    val schemaDef: ObjectSchema[ujson.Value] =
      ObjectSchema[ujson.Value](stf.description, Seq.empty, false)

    ToolFunction[ujson.Value, ujson.Value](
      name = stf.name,
      description = stf.description,
      schema = schemaDef,
      handler = (extractor: SafeParameterExtractor) =>
        stf.inputSchema.decoder(extractor.params) match
          case Left(err: ToolSchemaError) => Left(err.message)
          case Right(input: I) =>
            stf.handler(input) match
              case Left(err: ToolSchemaError) => Left(err.message)
              case Right(output: O)           => Right(stf.outputSchema.encoder(output))
    )

// ─────────────────────────────────────────────────────────────────
// CONTRACT 2: Refactored ToolWrapper (single toolFunction field)
//
// ToolWrapper now stores a single `toolFunction: ToolFunction[?, ?]` field.
// The `originalToolFunction` and `executable` fields are removed.
// `execute` delegates to `toolFunction.execute` directly.
// `name` and `description` delegate to `toolFunction.name/description`.
// ─────────────────────────────────────────────────────────────────

object RefactoredToolWrapperContract:

  /** Constructs a ToolWrapper from a ToolFunction. */
  def fromToolFunction[T, R](tf: ToolFunction[T, R]): ToolWrapperShape =
    ToolWrapperShape(tf)

  /** Constructs a ToolWrapper from a StructuredToolFunction
    * via the synthesized toToolFunction.
    */
  def fromStructuredToolFunction[I, O](
    stf: StructuredToolFunction[I, O]
  ): ToolWrapperShape =
    ToolWrapperShape(ToToolFunctionContract.synthesize(stf))

  /** The refactored ToolWrapper shape — single toolFunction field.
    * This mirrors the target signature of `ToolWrapper` in main sources.
    */
  final case class ToolWrapperShape(toolFunction: ToolFunction[?, ?]):
    def name: String = toolFunction.name
    def description: String = toolFunction.description
    def execute(args: Value): Either[Throwable, Value] =
      toolFunction.execute(args).left.map {
        case ToolCallError.ExecutionError(message, cause) =>
          new RuntimeException(s"Tool execution error: $message", cause)
        case err => new RuntimeException(err.toString)
      }

// ─────────────────────────────────────────────────────────────────
// CONTRACT 3: Simplified toToolRegistry
//
// toToolRegistry maps every Left(ToolWrapper) to its toolFunction,
// with no flatMap on Option. All StructuredToolFunction-derived tools
// are now visible.
// ─────────────────────────────────────────────────────────────────

object SimplifiedToToolRegistryContract:

  /** Simplified toToolRegistry — includes ALL ToolWrappers. */
  def toToolRegistry(wrappers: List[RefactoredToolWrapperContract.ToolWrapperShape]): ToolRegistry =
    val toolFunctions: Seq[ToolFunction[?, ?]] = wrappers.map(_.toolFunction)
    ToolRegistry(toolFunctions)

// ─────────────────────────────────────────────────────────────────
// COMPILE-NEGATIVE OBLIGATIONS
//
// The following constructions must NOT compile after the refactor:
// 1. ToolWrapper(originalToolFunction = None, executable = ???, name = "x", description = "y")
//    — the originalToolFunction and executable fields are removed.
//
// These will be tested in the test oracle via munit's compileError macro
// or assertDoesNotCompile.
// ─────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────
// PROPERTY OBLIGATIONS (structured comments — implemented in test oracle)
//
// Property 1: All ToolWrappers appear in ToolRegistry
//   forAll { (config: ToolsNodeConfig) =>
//     val leftCount = config.tools.collect { case Left(tw) => tw }.size
//     config.toToolRegistry.tools.size == leftCount
//   }
//   Generator: genToolsNodeConfig (constructive, weighted choice of
//     ToolFunction/StructuredToolFunction/AdkTool)
//
// Property 2: ToolWrapper execute matches toolFunction.execute
//   forAll { (wrapper: ToolWrapper, args: Value) =>
//     wrapper.execute(args).isRight == wrapper.toolFunction.execute(args).isRight
//   }
//   Generator: genToolWrapper (constructive from genToolFunction),
//     genArgs (constructive from Gen.json)
//
// Property 3: Synthesized ToolFunction name matches StructuredToolFunction name
//   forAll { (stf: StructuredToolFunction[?, ?]) =>
//     stf.toToolFunction.name == stf.name
//   }
//   Generator: genStructuredToolFunction (constructive)
// ─────────────────────────────────────────────────────────────────
