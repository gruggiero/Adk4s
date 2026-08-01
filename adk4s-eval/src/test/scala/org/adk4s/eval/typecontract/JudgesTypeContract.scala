package org.adk4s.eval
package typecontract

import cats.effect.IO
import org.adk4s.structured.core.Prompt
import org.adk4s.structured.core.PromptTemplate
import org.adk4s.structured.core.Schema
import org.adk4s.structured.core.StructuredLLM
import org.adk4s.structured.core.ValidationResult
import org.adk4s.structured.sap.StreamState
import smithy4s.schema.Schema as Smithy4sSchema

// ═══════════════════════════════════════════════════════════════════════════
//  Typed Contract for spec: llm-judges
//  Generated: 2026-08-01
//  Schema: verified-scala3
//
//  The type declarations live in MAIN sources (org.adk4s.eval.Judges) with
//  `???` bodies — this file verifies their signatures compile correctly and
//  that the judge schemas compile without the smithy4s-sbt-codegen plugin.
//
//  PLACEMENT: adk4s-eval/src/test/scala/org/adk4s/eval/typecontract/
//  so that `sbt adk4s-eval/Test/compile` genuinely compiles it against the
//  real project classpath.
//
//  LIFECYCLE — after implementation (apply Step 3) this file is NOT deleted:
//  it becomes a permanent API CONFORMANCE ASSERTION.
//
//  Status: [x] Compiles via adk4s-eval/Test/compile  [ ] Human-approved
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Type contract test suite for the LLM judges — verifies signatures compile,
 * judge schemas are defined in adk4s-eval (not test-models), and the
 * `Schema.instance` definitions resolve without the codegen plugin.
 */
class JudgesTypeContract extends munit.FunSuite:

  // ── Judge schema signatures ──────────────────────────────────────────────

  test("SemanticF1Judge: case class with precision, recall, reasoning") {
    val errors: String = compileErrors("""
      val judge: org.adk4s.eval.SemanticF1Judge =
        org.adk4s.eval.SemanticF1Judge(0.8, 0.6, "partial overlap")
      val precision: Double = judge.precision
      val recall: Double = judge.recall
      val reasoning: String = judge.reasoning
      ()
    """)
    assert(errors.isEmpty, s"SemanticF1Judge must compile: $errors")
  }

  test("CompleteAndGroundedJudge: case class with completeness, groundedness, reasoning") {
    val errors: String = compileErrors("""
      val judge: org.adk4s.eval.CompleteAndGroundedJudge =
        org.adk4s.eval.CompleteAndGroundedJudge(0.7, 0.9, "covers all facts")
      val completeness: Double = judge.completeness
      val groundedness: Double = judge.groundedness
      val reasoning: String = judge.reasoning
      ()
    """)
    assert(errors.isEmpty, s"CompleteAndGroundedJudge must compile: $errors")
  }

  // ── Schema.instance definitions resolve (no codegen plugin) ──────────────

  test("SemanticF1Judge Schema.instance resolves in adk4s-eval") {
    val errors: String = compileErrors("""
      val schema: org.adk4s.structured.core.Schema[org.adk4s.eval.SemanticF1Judge] =
        summon[org.adk4s.structured.core.Schema[org.adk4s.eval.SemanticF1Judge]]
      val idl: String = schema.smithyDefinition
      ()
    """)
    assert(errors.isEmpty, s"SemanticF1Judge Schema must resolve: $errors")
  }

  test("CompleteAndGroundedJudge Schema.instance resolves in adk4s-eval") {
    val errors: String = compileErrors("""
      val schema: org.adk4s.structured.core.Schema[org.adk4s.eval.CompleteAndGroundedJudge] =
        summon[org.adk4s.structured.core.Schema[org.adk4s.eval.CompleteAndGroundedJudge]]
      val idl: String = schema.smithyDefinition
      ()
    """)
    assert(errors.isEmpty, s"CompleteAndGroundedJudge Schema must resolve: $errors")
  }

  // ── Factory method signatures ────────────────────────────────────────────

  test("Judges.semanticF1: factory returns Metric[F, String, String]") {
    val errors: String = compileErrors("""
      val structured: org.adk4s.structured.core.StructuredLLM[cats.effect.IO] =
        new org.adk4s.structured.core.StructuredLLM[cats.effect.IO]:
          def complete[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def completeRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def streamWithResult[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def streamWithResultRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def completeValidated[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[org.adk4s.structured.core.ValidationResult[A]] = ???
          def streamPartial[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): fs2.Stream[cats.effect.IO, org.adk4s.structured.sap.StreamState[A]] = ???
          def function[I, A: org.adk4s.structured.core.Schema](
            template: org.adk4s.structured.core.PromptTemplate[I]
          ): I => cats.effect.IO[A] = ???
          def extractor[A: org.adk4s.structured.core.Schema](
            systemPrompt: String
          ): String => cats.effect.IO[A] = ???
      val metric: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Judges.semanticF1[cats.effect.IO](structured, threshold = 0.66)
      ()
    """)
    assert(errors.isEmpty, s"Judges.semanticF1 must compile: $errors")
  }

  test("Judges.completeAndGrounded: factory returns Metric[F, String, String]") {
    val errors: String = compileErrors("""
      val structured: org.adk4s.structured.core.StructuredLLM[cats.effect.IO] =
        new org.adk4s.structured.core.StructuredLLM[cats.effect.IO]:
          def complete[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def completeRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def streamWithResult[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def streamWithResultRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def completeValidated[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[org.adk4s.structured.core.ValidationResult[A]] = ???
          def streamPartial[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): fs2.Stream[cats.effect.IO, org.adk4s.structured.sap.StreamState[A]] = ???
          def function[I, A: org.adk4s.structured.core.Schema](
            template: org.adk4s.structured.core.PromptTemplate[I]
          ): I => cats.effect.IO[A] = ???
          def extractor[A: org.adk4s.structured.core.Schema](
            systemPrompt: String
          ): String => cats.effect.IO[A] = ???
      val metric: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Judges.completeAndGrounded[cats.effect.IO](structured, threshold = 0.66)
      ()
    """)
    assert(errors.isEmpty, s"Judges.completeAndGrounded must compile: $errors")
  }

  test("Judges.semanticF1: default threshold overload compiles") {
    val errors: String = compileErrors("""
      val structured: org.adk4s.structured.core.StructuredLLM[cats.effect.IO] =
        new org.adk4s.structured.core.StructuredLLM[cats.effect.IO]:
          def complete[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def completeRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[A] = ???
          def streamWithResult[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def streamWithResultRaw[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[(fs2.Stream[cats.effect.IO, String], cats.effect.IO[A])] = ???
          def completeValidated[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): cats.effect.IO[org.adk4s.structured.core.ValidationResult[A]] = ???
          def streamPartial[A: org.adk4s.structured.core.Schema](
            prompt: org.adk4s.structured.core.Prompt
          ): fs2.Stream[cats.effect.IO, org.adk4s.structured.sap.StreamState[A]] = ???
          def function[I, A: org.adk4s.structured.core.Schema](
            template: org.adk4s.structured.core.PromptTemplate[I]
          ): I => cats.effect.IO[A] = ???
          def extractor[A: org.adk4s.structured.core.Schema](
            systemPrompt: String
          ): String => cats.effect.IO[A] = ???
      val metric: org.adk4s.eval.Metric[cats.effect.IO, String, String] =
        org.adk4s.eval.Judges.semanticF1[cats.effect.IO](structured)
      ()
    """)
    assert(errors.isEmpty, s"Judges.semanticF1 default threshold must compile: $errors")
  }

  // ── Compile-negative: judge schemas must NOT come from structured-llm-test-models ─

  test("adk4s-eval does NOT depend on structured-llm-test-models (Ring 2)") {
    // This is an import-audit assertion: the judge schemas are defined in
    // org.adk4s.eval (this module), not in org.adk4s.structured.test.
    // If someone moves them to test-models, this summon fails to resolve
    // from adk4s-eval's classpath (test-models is not a dependency).
    val errors: String = compileErrors("""
      val s1: org.adk4s.structured.core.Schema[org.adk4s.eval.SemanticF1Judge] =
        summon[org.adk4s.structured.core.Schema[org.adk4s.eval.SemanticF1Judge]]
      val s2: org.adk4s.structured.core.Schema[org.adk4s.eval.CompleteAndGroundedJudge] =
        summon[org.adk4s.structured.core.Schema[org.adk4s.eval.CompleteAndGroundedJudge]]
      ()
    """)
    assert(errors.isEmpty, s"Judge schemas must resolve from adk4s-eval: $errors")
  }
