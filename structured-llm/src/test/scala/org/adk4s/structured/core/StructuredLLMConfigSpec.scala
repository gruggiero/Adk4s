package org.adk4s.structured.core

// spec: add-iron-refined-types/structured-llm — Test oracle (Step 2)
// Hedgehog properties and scenario tests for StructuredLLM maxParseAttempts refinement.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import io.github.iltotore.iron.{:|, refineEither}
import io.github.iltotore.iron.constraint.numeric.Positive

@SuppressWarnings(Array("org.wartremover.warts.Null"))
class StructuredLLMConfigSpec extends HedgehogSuite:

  // ── Properties (Ring 3) ──────────────────────────────────────────────────

  property("maxParseAttempts refineEither round-trips for positive inputs"):
    // spec: add-iron-refined-types/structured-llm — Property: maxParseAttempts refineEither round-trips
    val gen: Gen[Int] = Gen.int(Range.linear(1, 10))
    for n <- gen.forAll
      yield
        val result: Either[String, Int :| Positive] = n.refineEither[Positive]
        result.map { (refined: Int :| Positive) => (refined: Int) } ==== Right(n)

  property("maxParseAttempts rejects zero and negatives"):
    // spec: add-iron-refined-types/structured-llm — Property: maxParseAttempts rejects zero and negatives
    val gen: Gen[Int] = Gen.int(Range.linear(-10, 0))
    for n <- gen.forAll
      yield
        val result: Either[String, Int :| Positive] = n.refineEither[Positive]
        result.isLeft ==== true

  // ── Scenario tests ───────────────────────────────────────────────────────

  test("fromClientWithMiddlewaresEither rejects zero maxParseAttempts"):
    // spec: add-iron-refined-types/structured-llm — Scenario: Zero is rejected at runtime
    val result: Either[String, StructuredLLM[cats.effect.IO]] =
      StructuredLLM.fromClientWithMiddlewaresEither[cats.effect.IO](
        client = null,
        middlewares = Nil,
        maxParseAttempts = 0
      )
    assert(result.isLeft, s"Expected Left, got $result")

  test("fromClientWithMiddlewaresEither rejects negative maxParseAttempts"):
    // spec: add-iron-refined-types/structured-llm — Scenario: Negative is rejected at runtime
    val result: Either[String, StructuredLLM[cats.effect.IO]] =
      StructuredLLM.fromClientWithMiddlewaresEither[cats.effect.IO](
        client = null,
        middlewares = Nil,
        maxParseAttempts = -1
      )
    assert(result.isLeft, s"Expected Left, got $result")

  test("fromClientWithMiddlewaresEither accepts positive maxParseAttempts"):
    // spec: add-iron-refined-types/structured-llm — Scenario: Positive literal compiles
    val result: Either[String, StructuredLLM[cats.effect.IO]] =
      StructuredLLM.fromClientWithMiddlewaresEither[cats.effect.IO](
        client = null,
        middlewares = Nil,
        maxParseAttempts = 3
      )
    assert(result.isRight, s"Expected Right, got $result")

  test("fromClientWithMiddlewaresEither accepts default maxParseAttempts = 1"):
    // spec: add-iron-refined-types/structured-llm — Scenario: Default value 1 remains valid
    val result: Either[String, StructuredLLM[cats.effect.IO]] =
      StructuredLLM.fromClientWithMiddlewaresEither[cats.effect.IO](
        client = null,
        middlewares = Nil
      )
    assert(result.isRight, s"Expected Right, got $result")
