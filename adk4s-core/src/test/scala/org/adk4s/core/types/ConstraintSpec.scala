package org.adk4s.core.types

// spec: add-iron-refined-types/core-types — Test oracle (Step 2)
// Hedgehog properties for Positive and NonNegative constraint aliases.
// Tests the ALIASES (Positive, NonNegative) by type-annotating the
// refineEither result with the alias. If the alias definition were broken
// (e.g. swapped or pointing at the wrong Iron constraint), the type
// annotation would not compile.

import io.github.iltotore.iron.refineEither
import io.github.iltotore.iron.constraint.numeric
import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

class ConstraintSpec extends HedgehogSuite:

  // ═══════════════════════════════════════════════════════════════
  // Requirement: Positive and NonNegative constraint aliases are reusable
  // Scenario: Positive rejects zero and negatives
  // spec: add-iron-refined-types/core-types — Scenario: Positive rejects zero and negatives
  // ═══════════════════════════════════════════════════════════════

  property("Positive rejects zero and negatives") {
    val nonPositiveGen: Gen[Int] = Gen.choice1(
      Gen.constant(0),
      Gen.int(Range.linear(-100, -1))
    )
    nonPositiveGen.forAll.map { (n: Int) =>
      // Type-annotated with the Positive alias — compilation fails if
      // the alias does not match numeric.Positive
      val result: Either[String, Positive] = n.refineEither[numeric.Positive]
      result.isLeft ==== true
    }
  }

  property("Positive accepts positive values") {
    val posGen: Gen[Int] = Gen.int(Range.linear(1, 100))
    posGen.forAll.map { (n: Int) =>
      val result: Either[String, Positive] = n.refineEither[numeric.Positive]
      result.toOption ==== Some(n)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Scenario: NonNegative rejects negatives but accepts zero
  // spec: add-iron-refined-types/core-types — Scenario: NonNegative rejects negatives but accepts zero
  // ═══════════════════════════════════════════════════════════════

  property("NonNegative rejects negatives") {
    val negGen: Gen[Int] = Gen.int(Range.linear(-100, -1))
    negGen.forAll.map { (n: Int) =>
      // Type-annotated with the NonNegative alias — compilation fails if
      // the alias does not match numeric.Positive0
      val result: Either[String, NonNegative] = n.refineEither[numeric.Positive0]
      result.isLeft ==== true
    }
  }

  property("NonNegative accepts zero and positive values") {
    val nonNegGen: Gen[Int] = Gen.int(Range.linear(0, 100))
    nonNegGen.forAll.map { (n: Int) =>
      val result: Either[String, NonNegative] = n.refineEither[numeric.Positive0]
      result.toOption ==== Some(n)
    }
  }
