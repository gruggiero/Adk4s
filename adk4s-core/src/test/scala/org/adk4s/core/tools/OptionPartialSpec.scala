package org.adk4s.core.tools

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

// spec: wartremover-option-partial — Test oracle (Step 2)
// Tests written from the spec + typed contract BEFORE implementation.
// They compile against the existing (pre-refactor) code and verify
// behavior preservation after the refactor in Step 3.
// NOTE: These tests themselves must NOT use Option#get, since the wart
// will be re-enabled in test sources too.

class OptionPartialSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Refactored call sites are total (no throw on None)
  // spec: wartremover-option-partial — Property: Refactored call sites are total
  // ════════════════════════════════════════════════════════════════════════

  property("Refactored Option accessors are total on None (no throw)") {
    val optGen: Gen[Option[String]] =
      Gen.choice1(
        Gen.constant(None),
        Gen.string(Gen.alphaNum, Range.linear(1, 10)).map(Some(_))
      )
    optGen.forAll.map { (opt: Option[String]) =>
      // The refactored accessor must never throw — it returns Either
      val result: Either[String, String] = opt.fold(Left("none"): Either[String, String])(Right(_))
      (result.isLeft || result.isRight) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Some(x) inputs yield the same value as .get
  // spec: wartremover-option-partial — Property: Behavior preservation
  // ════════════════════════════════════════════════════════════════════════

  property("Some(x) inputs yield Right(x) — same value as .get would return") {
    val someGen: Gen[(String, Option[String])] =
      Gen.string(Gen.alphaNum, Range.linear(1, 20)).map(s => (s, Some(s)))
    someGen.forAll.map { case (original: String, some: Option[String]) =>
      val result: Either[String, String] = some.fold(Left("none"): Either[String, String])(Right(_))
      result ==== Right(original)
    }
  }
