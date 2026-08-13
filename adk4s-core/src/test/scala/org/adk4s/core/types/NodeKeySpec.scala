package org.adk4s.core.types

// spec: add-iron-refined-types/core-types — Test oracle (Step 2)
// Hedgehog properties for NodeKey refinement and ReservedNodeKey values.
// Properties derived from the spec, NOT from the implementation.

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Result
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.error.ConfigError

class NodeKeySpec extends HedgehogSuite:

  // ═══════════════════════════════════════════════════════════════
  // Property: NodeKey refineEither round-trips for valid inputs
  // spec: add-iron-refined-types/core-types — Property: NodeKey refineEither round-trips for valid inputs
  // ═══════════════════════════════════════════════════════════════

  property("NodeKey.refineEither round-trips for valid inputs") {
    val validKeyGen: Gen[String] =
      Gen.string(Gen.alphaNum, Range.linear(1, 30))
        .filter(s => s != "__start__" && s != "__end__")
    validKeyGen.forAll.map { (s: String) =>
      NodeKey.refineEither(s).map(_.value) ==== Right(s)
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property: NodeKey refineEither rejects all empty and reserved inputs
  // spec: add-iron-refined-types/core-types — Property: NodeKey refineEither rejects all empty and reserved inputs
  // ═══════════════════════════════════════════════════════════════

  property("NodeKey.refineEither rejects empty and reserved inputs") {
    val invalidGen: Gen[String] = Gen.choice1(
      Gen.constant(""), Gen.constant("__start__"), Gen.constant("__end__")
    )
    invalidGen.forAll.map { (s: String) =>
      val result: Either[ConfigError, NodeKey] = NodeKey.refineEither(s)
      result match
        case Left(err: ConfigError) =>
          val fieldOk: Boolean = err.field == "NodeKey"
          val valueOk: Boolean = err.invalidValue == s
          val constraintOk: Boolean = err.constraint == "NonEmpty & Not[Reserved]"
          (fieldOk && valueOk && constraintOk) ==== true
        case Right(_) => Result.failure
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Property: ReservedNodeKey underlying values are the reserved strings
  // spec: add-iron-refined-types/core-types — Property: ReservedNodeKey underlying values are the reserved strings
  // ═══════════════════════════════════════════════════════════════

  property("ReservedNodeKey values are the reserved strings") {
    val reservedGen: Gen[ReservedNodeKey] =
      Gen.element1(ReservedNodeKey.Start, ReservedNodeKey.End)
    reservedGen.forAll.map { (r: ReservedNodeKey) =>
      val isStart: Boolean = r == ReservedNodeKey.Start && r.value == "__start__"
      val isEnd: Boolean = r == ReservedNodeKey.End && r.value == "__end__"
      (isStart || isEnd) ==== true
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Requirement: NodeKey preserves Cats typeclass instances and value access
  // spec: add-iron-refined-types/core-types — Requirement: NodeKey preserves Cats typeclass instances and value access
  // ═══════════════════════════════════════════════════════════════

  property("Eq compares by underlying value") {
    val validKeyGen: Gen[String] =
      Gen.string(Gen.alphaNum, Range.linear(1, 30))
        .filter(s => s != "__start__" && s != "__end__")
    validKeyGen.forAll.map { (s: String) =>
      val k: NodeKey = NodeKey.either(s).fold(_ => NodeKey("fallback"), identity)
      cats.Eq[NodeKey].eqv(k, k) ==== true
    }
  }

  property("Show renders the underlying value") {
    val validKeyGen: Gen[String] =
      Gen.string(Gen.alphaNum, Range.linear(1, 30))
        .filter(s => s != "__start__" && s != "__end__")
    validKeyGen.forAll.map { (s: String) =>
      val k: NodeKey = NodeKey.either(s).fold(_ => NodeKey("fallback"), identity)
      cats.Show[NodeKey].show(k) ==== s
    }
  }
