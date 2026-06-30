package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.core.StructuredTestFramework.*
import smithy4s.schema.Schema as Smithy4sSchema

// spec: structured-test-framework — Test oracle

class StructuredTestFrameworkSpec extends HedgehogSuite:

  given s4sString: Smithy4sSchema[String] = smithy4s.Schema.string
  given schemaString: Schema[String] = Schema.instance("string String")(using s4sString)

  final case class Person(name: String, age: Int)
  given s4sPerson: Smithy4sSchema[Person] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.string.required[Person]("name", _.name),
      Schema.int.required[Person]("age", _.age)
    )(Person.apply)
  }
  given schemaPerson: Schema[Person] = Schema.instance(
    """structure Person { @required name: String @required age: Integer }"""
  )(using s4sPerson)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: testParse succeeds for valid JSON
  // ════════════════════════════════════════════════════════════════════════

  property("testParse succeeds for valid JSON") {
    val nameGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 10))
    val ageGen: Gen[Int] = Gen.int(Range.linear(0, 100))
    val personGen: Gen[Person] = for n <- nameGen; a <- ageGen yield Person(n, a)
    personGen.forAll.map { (p: Person) =>
      val json: String = s"""{"name": "${p.name}", "age": ${p.age}}"""
      val result: ParseTestResult[Person] = testParse[Person](json)
      result.parsed ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: testParse fails for invalid JSON
  // ════════════════════════════════════════════════════════════════════════

  property("testParse fails for invalid JSON") {
    val garbageGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 20))
    garbageGen.forAll.map { (s: String) =>
      val result: ParseTestResult[Person] = testParse[Person](s)
      result.parsed ==== false
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: testParse returns value on success
  // ════════════════════════════════════════════════════════════════════════

  test("testParse returns the parsed value on success") {
    val json: String = """{"name": "Alice", "age": 30}"""
    val result: ParseTestResult[Person] = testParse[Person](json)
    assertEquals(result.parsed, true)
    assertEquals(result.value.map(_.name), Some("Alice"))
    assertEquals(result.value.map(_.age), Some(30))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: testParse returns errors on failure
  // ════════════════════════════════════════════════════════════════════════

  test("testParse returns errors on failure") {
    val json: String = "not json at all"
    val result: ParseTestResult[Person] = testParse[Person](json)
    assertEquals(result.parsed, false)
    assert(result.errors.nonEmpty)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: testParseBatch runs multiple tests
  // ════════════════════════════════════════════════════════════════════════

  test("testParseBatch runs multiple parse tests") {
    val inputs: Vector[(String, String)] = Vector(
      "valid" -> """{"name": "Alice", "age": 30}""",
      "invalid" -> "garbage"
    )
    val results: Map[String, ParseTestResult[Person]] = testParseBatch[Person](inputs)
    assertEquals(results("valid").parsed, true)
    assertEquals(results("invalid").parsed, false)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: reportParseResults produces human-readable output
  // ════════════════════════════════════════════════════════════════════════

  test("reportParseResults produces human-readable output") {
    val results: Map[String, ParseTestResult[Person]] = Map(
      "valid" -> ParseTestResult[Person]("""{"name":"A","age":1}""", true, Some(Person("A", 1)), List.empty, List.empty),
      "invalid" -> ParseTestResult[Person]("garbage", false, None, List.empty, List("parse error"))
    )
    val report: String = reportParseResults(results)
    assert(report.contains("[PASS]"))
    assert(report.contains("[FAIL]"))
    assert(report.contains("valid"))
    assert(report.contains("invalid"))
  }
