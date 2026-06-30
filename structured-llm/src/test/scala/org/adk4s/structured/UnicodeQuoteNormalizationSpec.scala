package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.sap.SchemaAlignedParser
import org.adk4s.structured.sap.UnicodeQuoteNormalizer
import smithy4s.schema.Schema as Smithy4sSchema

// spec: unicode-quote-normalization — Test oracle (Step 2)
// Tests written from the spec BEFORE implementation.
// They verify Unicode smart quote normalization in the SAP.

class UnicodeQuoteNormalizationSpec extends HedgehogSuite:

  // ── Types for testing ──────────────────────────────────────────────────

  given s4sString: Smithy4sSchema[String] = smithy4s.Schema.string
  given schemaString: Schema[String] = Schema.instance(
    """string StringType"""
  )(using s4sString)

  final case class NameObj(name: String)
  given s4sNameObj: Smithy4sSchema[NameObj] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.string.required[NameObj]("name", _.name)
    )(NameObj.apply)
  }
  given schemaNameObj: Schema[NameObj] = Schema.instance(
    """structure NameObj { @required name: String }"""
  )(using s4sNameObj)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Normalization is idempotent
  // spec: unicode-quote-normalization — Property: idempotent
  // ════════════════════════════════════════════════════════════════════════

  property("normalizing an already-normalized string produces the same string") {
    val charGen: Gen[Char] = Gen.char('a', 'z')
    val stringGen: Gen[String] =
      Gen.string(charGen, Range.linear(0, 100))
    stringGen.forAll.map { (s: String) =>
      val once: String = UnicodeQuoteNormalizer.normalize(s)
      UnicodeQuoteNormalizer.normalize(once) ==== once
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: ASCII quote count increases by the number of Unicode quotes replaced
  // spec: unicode-quote-normalization — Property: ASCII quotes preserved
  // ════════════════════════════════════════════════════════════════════════

  property("ASCII double quotes are never removed, only added from Unicode replacement") {
    val charGen: Gen[Char] = Gen.char('a', 'z')
    val stringGen: Gen[String] =
      Gen.string(charGen, Range.linear(0, 100))
    stringGen.forAll.map { (s: String) =>
      val normalized: String = UnicodeQuoteNormalizer.normalize(s)
      val originalAsciiDouble: Int = s.count(_ == '"')
      val unicodeDoubleCount: Int =
        s.count(c => c == '\u201C' || c == '\u201D' || c == '\u201E' || c == '\u201F')
      val normalizedAsciiDouble: Int = normalized.count(_ == '"')
      normalizedAsciiDouble ==== (originalAsciiDouble + unicodeDoubleCount)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Smart double quotes in JSON values
  // spec: unicode-quote-normalization — Scenario: smart double quotes
  // ════════════════════════════════════════════════════════════════════════

  test("smart double quotes in JSON values are normalized to ASCII") {
    // \u201C = left double quotation mark "
    // \u201D = right double quotation mark "
    val input: String = """{"name": "John \u201CDoc\u201D Smith"}"""
    SchemaAlignedParser.parse[NameObj](input) match
      case ParseResult.Success(value, _) =>
        assertEquals(value.name, "John \"Doc\" Smith")
      case other => fail(s"Expected Success, got: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Smart single quotes in JSON values
  // spec: unicode-quote-normalization — Scenario: smart single quotes
  // ════════════════════════════════════════════════════════════════════════

  test("smart single quotes in JSON values are normalized to ASCII") {
    // \u2018 = left single quotation mark '
    // \u2019 = right single quotation mark '
    val input: String = """{"name": "it\u2019s a test"}"""
    SchemaAlignedParser.parse[NameObj](input) match
      case ParseResult.Success(value, _) =>
        assertEquals(value.name, "it's a test")
      case other => fail(s"Expected Success, got: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: No smart quotes — no change
  // spec: unicode-quote-normalization — Scenario: no smart quotes
  // ════════════════════════════════════════════════════════════════════════

  test("JSON with only ASCII quotes parses unchanged") {
    val input: String = """{"name": "John Smith"}"""
    SchemaAlignedParser.parse[NameObj](input) match
      case ParseResult.Success(value, _) =>
        assertEquals(value.name, "John Smith")
      case other => fail(s"Expected Success, got: $other")
  }
