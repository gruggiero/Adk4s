package org.adk4s.core.tools

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import org.adk4s.core.types.NodeKey
import org.adk4s.core.error.NodeKeyError

// spec: wartremover-throw — Test oracle (Step 2)
// Property 1: NodeKey.from is total and rejects empty/reserved keys.
// Property 2: Error messages preserve distinguishing substrings.

class ThrowSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: NodeKey.from is total and rejects empty/reserved keys
  // spec: wartremover-throw — Property: NodeKey.from total
  // ════════════════════════════════════════════════════════════════════════

  property("NodeKey.from is total and rejects empty/reserved keys") {
    val reservedOrEmptyGen: Gen[String] = Gen.choice1(
      Gen.constant(""), Gen.constant("__start__"), Gen.constant("__end__")
    )
    val validGen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 10))
      .filter(s => s != "__start__" && s != "__end__")

    val labeledGen: Gen[(String, String)] = Gen.choice1(
      reservedOrEmptyGen.map(s => ("invalid", s)),
      validGen.map(s => ("valid", s))
    )

    labeledGen.forAll.map { case (label: String, s: String) =>
      val result: Either[NodeKeyError, NodeKey] = NodeKey.from(s)
      label match
        case "invalid" => result.isLeft ==== true
        case "valid"   => result.isRight ==== true
        case _         => false ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Error messages preserve distinguishing substrings
  // spec: wartremover-throw — Property: error messages preserve substring
  // ════════════════════════════════════════════════════════════════════════

  property("NodeKeyError message contains the invalid key") {
    val invalidKeyGen: Gen[String] = Gen.choice1(
      Gen.constant(""), Gen.constant("__start__"), Gen.constant("__end__")
    )
    invalidKeyGen.forAll.map { (s: String) =>
      val error: NodeKeyError = NodeKeyError(s)
      error.message.contains(s) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: NodeKey.from on valid key returns Right
  // spec: wartremover-throw — Scenario: valid key returns Right
  // ════════════════════════════════════════════════════════════════════════

  property("NodeKey.from on valid key returns Right(NodeKey)") {
    val validGen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(1, 20))
      .filter(s => s != "__start__" && s != "__end__")
    validGen.forAll.map { (s: String) =>
      val result: Either[NodeKeyError, NodeKey] = NodeKey.from(s)
      result.isRight ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 4: NodeKey.unsafeApply is total (never throws)
  // spec: wartremover-throw — Property: unsafeApply total
  // ════════════════════════════════════════════════════════════════════════

  property("NodeKey.unsafeApply is total (never throws, even on invalid input)") {
    val anyStringGen: Gen[String] = Gen.string(Gen.alphaNum, Range.linear(0, 20))
    anyStringGen.forAll.map { (s: String) =>
      // unsafeApply should never throw — it's a direct assignment for trusted keys
      val key: NodeKey = NodeKey.unsafeApply(s)
      key.value ==== s
    }
  }
