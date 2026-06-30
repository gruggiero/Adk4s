package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.sap.*
import smithy4s.schema.Schema as Smithy4sSchema

// spec: type-aware-sap-coercion — Test oracle (Step 2)

class TypeAwareSapCoercionSpec extends HedgehogSuite:

  // ── Types for testing ──────────────────────────────────────────────────

  given s4sInt: Smithy4sSchema[Int] = smithy4s.Schema.int
  given schemaInt: Schema[Int] = Schema.instance("integer Integer")(using s4sInt)

  given s4sString: Smithy4sSchema[String] = smithy4s.Schema.string
  given schemaString: Schema[String] = Schema.instance("string String")(using s4sString)

  given s4sBool: Smithy4sSchema[Boolean] = smithy4s.Schema.boolean
  given schemaBool: Schema[Boolean] = Schema.instance("boolean Boolean")(using s4sBool)

  given s4sListString: Smithy4sSchema[List[String]] =
    smithy4s.Schema.list(smithy4s.Schema.string)
  given schemaListString: Schema[List[String]] =
    Schema.instance("list StringList { member: String }")(using s4sListString)

  final case class Person(name: String, age: Int)
  given s4sPerson: Smithy4sSchema[Person] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.string.required[Person]("name", _.name),
      Schema.int.required[Person]("age", _.age)
    )(Person.apply)
  }
  given schemaPerson: Schema[Person] = Schema.instance(
    "structure Person { @required name: String @required age: Integer }"
  )(using s4sPerson)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Coercion preserves semantic value (string→int)
  // ════════════════════════════════════════════════════════════════════════

  property("string to int coercion preserves the integer value") {
    val intGen: Gen[Int] = Gen.int(Range.linear(-10000, 10000))
    intGen.forAll.map { (n: Int) =>
      val strVal: JsonishValue = JsonishValue.Str(n.toString, CompletionState.Complete)
      val (json, flags) = TypeCoercer.coerceToJson(strVal)
      // The coerced JSON should be a quoted string — smithy4s will handle the actual coercion
      // For now we verify the value is preserved in the JSON
      json.contains(n.toString) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Score ordering — lower score is always preferred (Zero is best)
  // ════════════════════════════════════════════════════════════════════════

  property("CoercionScore.Zero is less than any non-zero score") {
    val flagsGen: Gen[Vector[CoercionFlag]] =
      Gen.list(
        Gen.element1(
          CoercionFlag.StringToInt,
          CoercionFlag.StringToBool,
          CoercionFlag.SingleToArray,
          CoercionFlag.CaseInsensitive
        ),
        Range.linear(1, 5)
      ).map(_.toVector)
    flagsGen.forAll.map { (flags: Vector[CoercionFlag]) =>
      val score: CoercionScore = CoercionScore.fromFlags(flags)
      CoercionScore.Zero < score ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Enum fuzzy matching — exact match (score 0)
  // ════════════════════════════════════════════════════════════════════════

  test("enum exact match produces no flags") {
    val result: Option[(String, Vector[CoercionFlag])] =
      EnumMatching.matchEnum("Active", List("Active", "Inactive"))
    result match
      case Some((matched, flags)) =>
        assertEquals(matched, "Active")
        assert(flags.isEmpty)
      case None => fail("Expected match")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Enum fuzzy matching — case-insensitive
  // ════════════════════════════════════════════════════════════════════════

  test("enum case-insensitive match produces CaseInsensitive flag") {
    val result: Option[(String, Vector[CoercionFlag])] =
      EnumMatching.matchEnum("active", List("Active", "Inactive"))
    result match
      case Some((matched, flags)) =>
        assertEquals(matched, "Active")
        assert(flags.contains(CoercionFlag.CaseInsensitive))
      case None => fail("Expected match")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Enum fuzzy matching — punctuation stripped
  // ════════════════════════════════════════════════════════════════════════

  test("enum punctuation-stripped match produces PunctuationStripped flag") {
    val result: Option[(String, Vector[CoercionFlag])] =
      EnumMatching.matchEnum("math-science", List("MathScience", "Humanities"))
    result match
      case Some((matched, flags)) =>
        assertEquals(matched, "MathScience")
        assert(flags.contains(CoercionFlag.PunctuationStripped))
      case None => fail("Expected match")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Enum fuzzy matching — no match
  // ════════════════════════════════════════════════════════════════════════

  test("enum no match returns None") {
    val result: Option[(String, Vector[CoercionFlag])] =
      EnumMatching.matchEnum("Pending", List("Active", "Inactive"))
    assert(result.isEmpty)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: JsonishValue.AnyOf preserves all interpretations
  // ════════════════════════════════════════════════════════════════════════

  test("AnyOf preserves all choices") {
    val anyOf: JsonishValue = JsonishValue.AnyOf(
      Vector(
        JsonishValue.Str("42", CompletionState.Complete),
        JsonishValue.Num(42.0, CompletionState.Complete)
      ),
      "42"
    )
    anyOf match
      case JsonishValue.AnyOf(choices, original) =>
        assertEquals(choices.length, 2)
        assertEquals(original, "42")
      case _ => fail("Expected AnyOf")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: CompletionState tracking
  // ════════════════════════════════════════════════════════════════════════

  test("CompletionState is tracked on JsonishValue") {
    val v: JsonishValue = JsonishValue.Str("hello", CompletionState.Incomplete)
    assertEquals(v.completionState, CompletionState.Incomplete)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Backward compatibility — existing SAP still works
  // ════════════════════════════════════════════════════════════════════════

  test("existing SAP parsing still works for valid JSON") {
    val input: String = """{"name": "John", "age": 30}"""
    SchemaAlignedParser.parse[Person](input) match
      case ParseResult.Success(value, _) =>
        assertEquals(value.name, "John")
        assertEquals(value.age, 30)
      case other => fail(s"Expected Success, got: $other")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Coercion flag penalties are positive
  // ════════════════════════════════════════════════════════════════════════

  test("all coercion flags produce positive scores") {
    val allFlags: Vector[CoercionFlag] = Vector(
      CoercionFlag.StringToInt,
      CoercionFlag.StringToBool,
      CoercionFlag.StringToFloat,
      CoercionFlag.IntToFloat,
      CoercionFlag.FloatToInt,
      CoercionFlag.SingleToArray,
      CoercionFlag.ObjectToString,
      CoercionFlag.StrippedNonAlphaNumeric,
      CoercionFlag.DefaultFromNoValue,
      CoercionFlag.CaseInsensitive,
      CoercionFlag.PunctuationStripped,
      CoercionFlag.AnyOfResolved
    )
    val score: CoercionScore = CoercionScore.fromFlags(allFlags)
    assert(score.value > 0)
  }
