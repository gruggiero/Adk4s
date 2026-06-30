package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.core.DynamicTypeBuilder.*
import smithy4s.schema.Schema as Smithy4sSchema

// spec: dynamic-type-builder — Test oracle

class DynamicTypeBuilderSpec extends HedgehogSuite:

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: buildSmithyIdl produces valid structure with all fields
  // ════════════════════════════════════════════════════════════════════════

  property("buildSmithyIdl includes all field names") {
    val fieldNameGen: Gen[String] = Gen.string(Gen.char('a', 'z'), Range.linear(1, 10))
    val fieldsGen: Gen[Vector[FieldDef]] =
      Gen.list(fieldNameGen, Range.linear(1, 5)).map(_.distinct.map(name => FieldDef(name, "String", required = true)).toVector)
    fieldsGen.forAll.map { (fields: Vector[FieldDef]) =>
      val idl: String = buildSmithyIdl("TestStruct", fields)
      fields.forall(f => idl.contains(f.name)) ==== true
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Property 2: Required fields get @required annotation
  // ════════════════════════════════════════════════════════════════════════

  property("required fields get @required annotation in IDL") {
    val boolGen: Gen[Boolean] = Gen.boolean
    boolGen.forAll.map { (required: Boolean) =>
      val fields: Vector[FieldDef] = Vector(FieldDef("testField", "String", required))
      val idl: String = buildSmithyIdl("TestStruct", fields)
      (idl.contains("@required") ==== required)
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: buildSmithyIdl produces structure keyword
  // ════════════════════════════════════════════════════════════════════════

  test("buildSmithyIdl produces structure keyword") {
    val idl: String = buildSmithyIdl("MyStruct", Vector(FieldDef("a", "Integer", required = true)))
    assert(idl.contains("structure MyStruct"))
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: DynamicValue parse and accessors
  // ════════════════════════════════════════════════════════════════════════

  test("DynamicValue.parse parses valid JSON") {
    DynamicValue.parse("""{"name": "Alice", "age": 30}""") match
      case Right(dyn) =>
        assertEquals(dyn.field("name").flatMap(_.asString), Some("Alice"))
        assertEquals(dyn.field("age").flatMap(_.asInt), Some(30))
      case Left(err) => fail(s"Parse failed: $err")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: DynamicValue parse invalid JSON returns Left
  // ════════════════════════════════════════════════════════════════════════

  test("DynamicValue.parse returns Left for invalid JSON") {
    val result: Either[String, DynamicValue] = DynamicValue.parse("{invalid json}")
    assert(result.isLeft)
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: DynamicValue array accessor
  // ════════════════════════════════════════════════════════════════════════

  test("DynamicValue.asArray extracts array elements") {
    DynamicValue.parse("""[1, 2, 3]""") match
      case Right(dyn) =>
        assertEquals(dyn.asArray.map(_.length), Some(3))
      case Left(err) => fail(s"Parse failed: $err")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: DynamicValue boolean accessor
  // ════════════════════════════════════════════════════════════════════════

  test("DynamicValue.asBoolean extracts boolean") {
    DynamicValue.parse("true") match
      case Right(dyn) => assertEquals(dyn.asBoolean, Some(true))
      case Left(err)  => fail(s"Parse failed: $err")
  }
