package org.adk4s.structured

import munit.FunSuite
import org.adk4s.structured.core.*
import org.adk4s.structured.sap.SchemaAlignedParser
import smithy4s.schema.Schema as Smithy4sSchema

final class SchemaAlignedParserUnitTest extends FunSuite:

  // Minimal types and smithy4s schemas for decoding.
  // We use simple recursive schemas that can actually parse JSON.

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

  given s4sString: Smithy4sSchema[String] = smithy4s.Schema.string
  given schemaString: Schema[String] = Schema.instance(
    """string StringType"""
  )(using s4sString)

  given s4sListInt: Smithy4sSchema[List[Int]] = smithy4s.Schema.list(smithy4s.Schema.int)
  given schemaListInt: Schema[List[Int]] = Schema.instance(
    """list IntList { member: Integer }"""
  )(using s4sListInt)

  given s4sListListInt: Smithy4sSchema[List[List[Int]]] = smithy4s.Schema.list(s4sListInt)
  given schemaListListInt: Schema[List[List[Int]]] = Schema.instance(
    """list ListOfIntLists { member: IntList }"""
  )(using s4sListListInt)

  private def assertHasWarning(warnings: List[String], expected: String): Unit =
    assert(
      warnings.exists(_ == expected),
      s"Expected warnings to contain: '$expected' but got: ${warnings.mkString(" | ")}"
    )

  private def assertHasAnyWarning(warnings: List[String], expectedAny: List[String]): Unit =
    assert(
      warnings.exists(w => expectedAny.contains(w)),
      s"Expected warnings to contain one of: ${expectedAny.mkString(", ")} but got: ${warnings.mkString(" | ")}"
    )

  test("parses JSON enclosed in markdown code fences with fence removal warning") {
    val input =
      """```json
        |"hello"
        |```""".stripMargin

    SchemaAlignedParser.parse[String](input) match
      case ParseResult.Success(_, warnings) =>
        assertHasWarning(warnings, "Removed markdown code fences")
      case other => fail(s"Expected Success, got: $other")
  }

  test("parses with missing closing bracket by auto-closing (may come from segment extraction)") {
    val input = "[1, 2"

    SchemaAlignedParser.parse[List[Int]](input) match
      case ParseResult.Success(_, warnings) =>
        // Depending on path, either segment extraction or recovery may apply.
        // We assert success and presence of at least one warning indicating recovery/extraction.
        assert(warnings.nonEmpty, s"Expected some warnings, got none")
      case other => fail(s"Expected Success, got: $other")
  }

  test("removes trailing commas and parses successfully with warning") {
    val input = "[1, 2,]"

    SchemaAlignedParser.parse[List[Int]](input) match
      case ParseResult.Success(_, warnings) =>
        assertHasWarning(warnings, "Removed trailing commas")
      case other => fail(s"Expected Success, got: $other")
  }

  test("treats non-JSON responses as JSON string with warning") {
    val input = "Hello, this is plain text"

    SchemaAlignedParser.parse[String](input) match
      case ParseResult.Success(_, warnings) =>
        assertHasWarning(warnings, "Treated response as JSON string value")
      case other => fail(s"Expected Success, got: $other")
  }

  test("handles multiple JSON blocks: extracts segments and may aggregate; ensure warning present") {
    // Depending on decode success of individual segments, the parser may succeed on a single
    // extracted segment or try the aggregated candidate. We accept either path but require
    // at least one of the corresponding warnings to be present.
    val input = "prefix [1] middle [2] suffix"

    SchemaAlignedParser.parse[List[List[Int]]](input) match
      case ParseResult.Success(_, warnings) =>
        assertHasAnyWarning(
          warnings,
          List(
            "Extracted JSON segment from response",
            "Aggregated multiple JSON blocks into an array candidate"
          )
        )
      case other => fail(s"Expected Success, got: $other")
  }

  test("outputFormatBlock sanitizes Smithy list definitions to array notation") {
    val schemaWithList: Schema[List[Int]] = Schema.instance(
      """structure MyResult {
        |  @required
        |  items: ItemList
        |}
        |
        |list ItemList {
        |  member: Integer
        |}""".stripMargin
    )(using s4sListInt)

    val block: String = schemaWithList.outputFormatBlock
    assert(!block.contains("member:"), s"outputFormatBlock should not contain 'member:' but got:\n$block")
    assert(!block.contains("list ItemList"), s"outputFormatBlock should not contain 'list ItemList' but got:\n$block")
    assert(block.contains("Integer[]"), s"outputFormatBlock should contain 'Integer[]' but got:\n$block")
  }

  test("outputFormatBlock preserves IDL without list definitions") {
    val schemaWithoutList: Schema[DummyObj] = Schema.instance(
      """structure DummyObj {
        |  @required
        |  a: Integer
        |}""".stripMargin
    )(using s4sDummyObj)

    val block: String = schemaWithoutList.outputFormatBlock
    assert(block.contains("a: Integer"), s"outputFormatBlock should preserve simple fields but got:\n$block")
  }

  // --- Types for member-wrapping test ---
  final case class ItemContainer(items: List[Int])
  given s4sItemContainer: Smithy4sSchema[ItemContainer] = smithy4s.Schema.recursive {
    import smithy4s.Schema
    Schema.struct(
      Schema.list(Schema.int).required[ItemContainer]("items", _.items)
    )(ItemContainer.apply)
  }
  given schemaItemContainer: Schema[ItemContainer] = Schema.instance(
    """structure ItemContainer {
      |  @required
      |  items: IntList
      |}
      |list IntList {
      |  member: Integer
      |}""".stripMargin
  )(using s4sItemContainer)

  test("SAP recovers from member-wrapped JSON arrays in struct fields") {
    // Simulates what a real LLM produces when it sees "list IntList { member: Integer }"
    val input = """{"items": {"member": [1, 2, 3]}}"""

    SchemaAlignedParser.parse[ItemContainer](input) match
      case ParseResult.Success(value, warnings) =>
        assertEquals(value.items, List(1, 2, 3))
        assertHasWarning(warnings, "Unwrapped Smithy 'member' list wrapper from JSON values")
      case other => fail(s"Expected Success, got: $other")
  }
