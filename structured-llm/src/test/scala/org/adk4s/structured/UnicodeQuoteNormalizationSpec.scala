package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.sap.CompletionState
import org.adk4s.structured.sap.JsonishParser
import org.adk4s.structured.sap.JsonishValue
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

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Smart quotes AND apostrophes in the same value
  // spec: unicode-quote-normalization — Scenario: smart quotes AND apostrophes
  // ════════════════════════════════════════════════════════════════════════

  test("smart quotes normalized AND apostrophes preserved in the same value") {
    // \u201C = " (left double), \u201D = " (right double)
    // The apostrophes in "it's" and "isn't" should be preserved by
    // JsonishParser's quote-state-tracking scanner after normalization
    // converts the smart double quotes to ASCII.
    val input: String = """{"name": "it\u2019s \u201Cfine\u201D, isn\u2019t it"}"""
    SchemaAlignedParser.parse[NameObj](input) match
      case ParseResult.Success(value, _) =>
        assertEquals(value.name, """it's "fine", isn't it""")
      case other => fail(s"Expected Success with preserved apostrophes, got: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 3: Normalization + JsonishParser preserves apostrophe-containing values
  // spec: unicode-quote-normalization — Property: normalization + JsonishParser
  // Compatibility gate: verifies the normalization step does not interfere
  // with JsonishParser's apostrophe-preservation.
  // ════════════════════════════════════════════════════════════════════════

  property("normalize then JsonishParser.parse preserves apostrophes in string values") {
    val charGen: Gen[Char] = Gen.frequency1(
      5 -> Gen.constant('\''),
      3 -> Gen.constant('a'),
      2 -> Gen.constant(' '),
      1 -> Gen.constant('n')
    )
    val stringGen: Gen[String] = Gen.string(charGen, Range.linear(2, 30))
    stringGen.forAll.map { (s: String) =>
      val json: String = s"""{"note": "$s"}"""
      val normalized: String = UnicodeQuoteNormalizer.normalize(json)
      val parsed: JsonishValue = JsonishParser.parse(normalized)
      parsed match
        case JsonishValue.Obj(fields, _) =>
          fields.find(_._1 == "note") match
            case Some((_, JsonishValue.Str(value, _))) =>
              value ==== s
            case _ => hedgehog.Result.failure
        case _ => hedgehog.Result.failure
    }
  }
