package org.adk4s.core.tools

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite

// spec: wartremover-var — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// Property 1: replaceSingleQuotes output is byte-identical to a reference oracle.
// Property 2: applyHeuristicFixes is idempotent (no hidden mutable state).

class VarSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Reference oracle: the original imperative implementation of
  // replaceSingleQuotes, captured here as a pure function to compare
  // against the refactored (recursive) version.
  // ════════════════════════════════════════════════════════════════════════

  private def referenceReplaceSingleQuotes(s: String): String =
    val sb: StringBuilder = new StringBuilder(s.length)

    def loop(i: Int, inDoubleQuote: Boolean): Unit =
      if i < s.length then
        val c: Char = s.charAt(i)
        if c == '"' && (i == 0 || s.charAt(i - 1) != '\\') then
          sb.append(c)
          loop(i + 1, !inDoubleQuote)
        else if c == '\'' && !inDoubleQuote then
          sb.append('"')
          loop(i + 1, inDoubleQuote)
        else
          sb.append(c)
          loop(i + 1, inDoubleQuote)

    loop(0, false)
    sb.toString

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: replaceSingleQuotes output is identical to reference oracle
  // spec: wartremover-var — Property: byte-identical to reference
  // ════════════════════════════════════════════════════════════════════════

  property("replaceSingleQuotes is byte-identical to reference oracle") {
    val charGen: Gen[Char] = Gen.frequency1(
      5 -> Gen.constant('"'),
      5 -> Gen.constant('\''),
      2 -> Gen.constant('\\'),
      3 -> Gen.constant('a'),
    )
    val stringGen: Gen[String] = Gen.string(charGen, Range.linear(0, 50))
    stringGen.forAll.map { (s: String) =>
      JsonFixMiddleware.replaceSingleQuotes(s) ==== referenceReplaceSingleQuotes(s)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: applyHeuristicFixes is idempotent
  // spec: wartremover-var — Property: idempotent (no hidden mutable state)
  // ════════════════════════════════════════════════════════════════════════

  property("applyHeuristicFixes is idempotent") {
    val jsonIshCharGen: Gen[Char] = Gen.choice1(
      Gen.constant('{'), Gen.constant('}'), Gen.constant(','),
      Gen.constant('"'), Gen.constant('\''), Gen.constant('a'), Gen.constant(' ')
    )
    val jsonIshStringGen: Gen[String] = Gen.string(jsonIshCharGen, Range.linear(0, 40))
    jsonIshStringGen.forAll.map { (s: String) =>
      val once: String  = JsonFixMiddleware.applyHeuristicFixes(s)
      val twice: String = JsonFixMiddleware.applyHeuristicFixes(once)
      once ==== twice
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Edge case — empty string input
  // spec: wartremover-var — Scenario: empty string returns empty
  // ════════════════════════════════════════════════════════════════════════

  property("replaceSingleQuotes and applyHeuristicFixes on empty string return empty") {
    val emptyGen: Gen[String] = Gen.constant("")
    emptyGen.forAll.map { (s: String) =>
      val r1: hedgehog.Result = JsonFixMiddleware.replaceSingleQuotes(s) ==== ""
      val r2: hedgehog.Result = JsonFixMiddleware.applyHeuristicFixes(s) ==== ""
      r1 and r2
    }
  }
