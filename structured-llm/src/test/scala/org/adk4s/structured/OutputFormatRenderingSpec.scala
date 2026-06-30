package org.adk4s.structured

import hedgehog.Gen
import hedgehog.Range
import hedgehog.Syntax
import hedgehog.munit.HedgehogSuite
import munit.FunSuite
import org.adk4s.structured.core.*
import smithy4s.schema.Schema as Smithy4sSchema

// spec: output-format-rendering — Test oracle

class OutputFormatRenderingSpec extends HedgehogSuite:

  given s4sInt: Smithy4sSchema[Int] = smithy4s.Schema.int
  given schemaInt: Schema[Int] = Schema.instance("integer Integer")(using s4sInt)

  final case class DummyObj(a: Int)
  given s4sDummyObj: Smithy4sSchema[DummyObj] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.int.required[DummyObj]("a", _.a)
    )(DummyObj.apply)
  }
  given schemaDummyObj: Schema[DummyObj] = Schema.instance(
    """structure DummyObj { @required a: Integer }"""
  )(using s4sDummyObj)

  // ════════════════════════════════════════════════════════════════════════
  // Property 1: Default rendering is backward compatible with outputFormatBlock
  // ════════════════════════════════════════════════════════════════════════

  property("default OutputFormatOptions produces same output as outputFormatBlock") {
    val schemaGen: Gen[Schema[DummyObj]] = Gen.constant(schemaDummyObj)
    schemaGen.forAll.map { (schema: Schema[DummyObj]) =>
      val rendered: String = OutputFormatRenderer.render(
        schema.smithyDefinition,
        schema.description,
        OutputFormatOptions.default
      )
      val expected: String = schema.outputFormatBlock
      rendered ==== expected
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Quote class fields
  // ════════════════════════════════════════════════════════════════════════

  test("quoteClassFields option quotes field names") {
    val rendered: String = OutputFormatRenderer.render(
      """structure Test { @required a: Integer }""",
      None,
      OutputFormatOptions(quoteClassFields = true)
    )
    assert(rendered.contains("\"a\":"), s"Expected quoted field name but got:\n$rendered")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: Default rendering preserves simple fields
  // ════════════════════════════════════════════════════════════════════════

  test("default rendering preserves simple field definitions") {
    val rendered: String = OutputFormatRenderer.render(
      """structure DummyObj { @required a: Integer }""",
      None,
      OutputFormatOptions.default
    )
    assert(rendered.contains("a: Integer"), s"Expected 'a: Integer' but got:\n$rendered")
  }

  // ════════════════════════════════════════════════════════════════════════
  // Scenario: List sanitization still works
  // ════════════════════════════════════════════════════════════════════════

  test("list definitions are sanitized to array notation") {
    val rendered: String = OutputFormatRenderer.render(
      """structure MyResult { @required items: ItemList } list ItemList { member: Integer }""",
      None,
      OutputFormatOptions.default
    )
    assert(rendered.contains("Integer[]"), s"Expected 'Integer[]' but got:\n$rendered")
    assert(!rendered.contains("member:"), s"Should not contain 'member:' but got:\n$rendered")
  }
