package org.adk4s.core.tools

import munit.FunSuite
import smithy4s.schema.Schema as Smithy4sSchema

class ToolSchemaDeriveTest extends FunSuite:

  // Test case classes
  final case class SimpleRecord(name: String, count: Int)
  given s4sSimpleRecord: Smithy4sSchema[SimpleRecord] = smithy4s.Schema.struct(
    smithy4s.Schema.string.required[SimpleRecord]("name", _.name),
    smithy4s.Schema.int.required[SimpleRecord]("count", _.count)
  )(SimpleRecord.apply)

  final case class AllTypesRecord(
    stringField: String,
    intField: Int,
    longField: Long,
    floatField: Float,
    doubleField: Double,
    boolField: Boolean
  )
  given s4sAllTypesRecord: Smithy4sSchema[AllTypesRecord] = smithy4s.Schema.struct(
    smithy4s.Schema.string.required[AllTypesRecord]("stringField", _.stringField),
    smithy4s.Schema.int.required[AllTypesRecord]("intField", _.intField),
    smithy4s.Schema.long.required[AllTypesRecord]("longField", _.longField),
    smithy4s.Schema.float.required[AllTypesRecord]("floatField", _.floatField),
    smithy4s.Schema.double.required[AllTypesRecord]("doubleField", _.doubleField),
    smithy4s.Schema.boolean.required[AllTypesRecord]("boolField", _.boolField)
  )(AllTypesRecord.apply)

  final case class OptionalFieldsRecord(
    required: String,
    optionalString: Option[String],
    optionalInt: Option[Int]
  )
  given s4sOptionalFieldsRecord: Smithy4sSchema[OptionalFieldsRecord] = smithy4s.Schema.struct(
    smithy4s.Schema.string.required[OptionalFieldsRecord]("required", _.required),
    smithy4s.Schema.string.optional[OptionalFieldsRecord]("optionalString", _.optionalString),
    smithy4s.Schema.int.optional[OptionalFieldsRecord]("optionalInt", _.optionalInt)
  )(OptionalFieldsRecord.apply)

  test("derive generates correct JSON schema") {
    given ToolSchema[SimpleRecord] = ToolSchema.derive[SimpleRecord]

    val schema: ToolSchema[SimpleRecord] = ToolSchema[SimpleRecord]
    val jsonSchema: ujson.Value = schema.jsonSchema

    // Verify schema structure
    assertEquals(jsonSchema("type").str, "object")

    val properties: ujson.Obj = jsonSchema("properties").obj
    assertEquals(properties("name")("type").str, "string")
    assertEquals(properties("count")("type").str, "integer")

    val required: ujson.Arr = jsonSchema("required").arr
    assertEquals(required.value.size, 2)
    assert(required.value.exists(_.str == "name"))
    assert(required.value.exists(_.str == "count"))
  }

  test("derive encoder encodes case class to JSON") {
    given ToolSchema[SimpleRecord] = ToolSchema.derive[SimpleRecord]

    val schema: ToolSchema[SimpleRecord] = ToolSchema[SimpleRecord]
    val record: SimpleRecord = SimpleRecord("test", 42)

    val encoded: ujson.Value = schema.encoder(record)

    assertEquals(encoded("name").str, "test")
    assertEquals(encoded("count").num.toInt, 42)
  }

  test("derive decoder decodes JSON to case class") {
    given ToolSchema[SimpleRecord] = ToolSchema.derive[SimpleRecord]

    val schema: ToolSchema[SimpleRecord] = ToolSchema[SimpleRecord]
    val json: ujson.Value = ujson.Obj(
      "name" -> "test",
      "count" -> 42
    )

    val decoded: Either[ToolSchemaError, SimpleRecord] = schema.decoder(json)

    assert(decoded.isRight)
    val result: SimpleRecord = decoded.toOption.getOrElse(fail("expected Right"))
    assertEquals(result.name, "test")
    assertEquals(result.count, 42)
  }

  test("derive supports all basic types") {
    given ToolSchema[AllTypesRecord] = ToolSchema.derive[AllTypesRecord]

    val schema: ToolSchema[AllTypesRecord] = ToolSchema[AllTypesRecord]
    val record: AllTypesRecord = AllTypesRecord(
      stringField = "hello",
      intField = 10,
      longField = 100L,
      floatField = 1.5f,
      doubleField = 2.5,
      boolField = true
    )

    // Test encoding
    val encoded: ujson.Value = schema.encoder(record)
    assertEquals(encoded("stringField").str, "hello")
    assertEquals(encoded("intField").num.toInt, 10)
    assertEquals(encoded("longField").num.toLong, 100L)
    assertEquals(encoded("floatField").num.toFloat, 1.5f)
    assertEquals(encoded("doubleField").num, 2.5)
    assertEquals(encoded("boolField").bool, true)

    // Test decoding
    val decoded: Either[ToolSchemaError, AllTypesRecord] = schema.decoder(encoded)
    assert(decoded.isRight)
    val result: AllTypesRecord = decoded.toOption.getOrElse(fail("expected Right"))
    assertEquals(result.stringField, "hello")
    assertEquals(result.intField, 10)
    assertEquals(result.longField, 100L)
    assertEquals(result.floatField, 1.5f)
    assertEquals(result.doubleField, 2.5)
    assertEquals(result.boolField, true)
  }

  test("derive handles optional fields correctly") {
    given ToolSchema[OptionalFieldsRecord] = ToolSchema.derive[OptionalFieldsRecord]

    val schema: ToolSchema[OptionalFieldsRecord] = ToolSchema[OptionalFieldsRecord]

    // Test with all fields present
    val record1: OptionalFieldsRecord = OptionalFieldsRecord(
      required = "test",
      optionalString = Some("optional"),
      optionalInt = Some(42)
    )

    val encoded1: ujson.Value = schema.encoder(record1)
    assertEquals(encoded1("required").str, "test")
    assertEquals(encoded1("optionalString").str, "optional")
    assertEquals(encoded1("optionalInt").num.toInt, 42)

    // Test with optional fields as None
    val record2: OptionalFieldsRecord = OptionalFieldsRecord(
      required = "test",
      optionalString = None,
      optionalInt = None
    )

    val encoded2: ujson.Value = schema.encoder(record2)
    assertEquals(encoded2("required").str, "test")
    // smithy4s omits None fields from JSON output (rather than encoding as null)
    assert(!encoded2.obj.contains("optionalString") || encoded2("optionalString").isNull)
    assert(!encoded2.obj.contains("optionalInt") || encoded2("optionalInt").isNull)

    // Test decoding with missing optional fields
    val json: ujson.Value = ujson.Obj("required" -> "test")
    val decoded: Either[ToolSchemaError, OptionalFieldsRecord] = schema.decoder(json)

    assert(decoded.isRight)
    val result: OptionalFieldsRecord = decoded.toOption.getOrElse(fail("expected Right"))
    assertEquals(result.required, "test")
    assertEquals(result.optionalString, None)
    assertEquals(result.optionalInt, None)
  }

  test("derive round-trip encoding/decoding preserves data") {
    given ToolSchema[AllTypesRecord] = ToolSchema.derive[AllTypesRecord]

    val schema: ToolSchema[AllTypesRecord] = ToolSchema[AllTypesRecord]
    val original: AllTypesRecord = AllTypesRecord(
      stringField = "test",
      intField = 123,
      longField = 456L,
      floatField = 7.89f,
      doubleField = 10.11,
      boolField = false
    )

    // Encode then decode
    val encoded: ujson.Value = schema.encoder(original)
    val decoded: Either[ToolSchemaError, AllTypesRecord] = schema.decoder(encoded)

    assert(decoded.isRight)
    val result: AllTypesRecord = decoded.toOption.getOrElse(fail("expected Right"))
    assertEquals(result, original)
  }

  test("derive decoder returns error for missing required field") {
    given ToolSchema[SimpleRecord] = ToolSchema.derive[SimpleRecord]

    val schema: ToolSchema[SimpleRecord] = ToolSchema[SimpleRecord]

    // Missing "count" field
    val json: ujson.Value = ujson.Obj("name" -> "test")
    val decoded: Either[ToolSchemaError, SimpleRecord] = schema.decoder(json)

    assert(decoded.isLeft)
    // The new smithy4s-based decoder produces specific ToolSchemaError cases
    // (MissingRequiredField, TypeMismatch, etc.) rather than just DecodingFailed
    decoded match
      case Left(error: ToolSchemaError) =>
        assert(error.message.nonEmpty)
      case Right(_) => fail("Expected Left error for missing required field")
  }
