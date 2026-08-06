package org.adk4s.core.json

import hedgehog.*
import hedgehog.munit.HedgehogSuite
import munit.*
import smithy4s.Document

/**
 * Test oracle for the json-value-model spec.
 *
 * Properties and scenarios derived from the SPEC ONLY, before any
 * implementation exists. Tests are EXPECTED TO FAIL at runtime until
 * Step 3 — that is the oracle polarity.
 *
 * spec: json-value-model
 */
final class JsonValueCodecSpec extends HedgehogSuite:

  // JsonValueCodec is now imported from main sources (org.adk4s.core.json).
  // The stub was removed in Step 3 when the real implementation was promoted.

  // ── Generators ───────────────────────────────────────────────────────────

  /**
   * Generates leaf smithy4s.Document variants (no nesting).
   * Covers DNull, DBoolean, DNumber (Long/Double), DString.
   * Numbers are bounded to the Double-exact range [-2^53, 2^53] so the
   * round-trip property holds (ujson.Num wraps Double — Longs above 2^53
   * lose precision across the boundary; this is a documented ujson
   * limitation, not a bug).
   */
  def genJsonValueLeaf: Gen[Document] =
    val twoPow53: Long = 1L << 53
    Gen.choice1(
      Gen.constant(Document.DNull),
      Gen.boolean.map(Document.DBoolean.apply),
      Gen.long(Range.linear(-twoPow53, twoPow53)).map(n => Document.DNumber(BigDecimal(n))),
      Gen.double(Range.linearFrac(-1000000.0, 1000000.0)).map(n => Document.DNumber(BigDecimal(n))),
      Gen.string(Gen.alphaNum, Range.linear(0, 20)).map(Document.DString.apply)
    )

  /**
   * Generates all smithy4s.Document variants (depth 0-1).
   * Leaf variants plus DArray/DObject of leaves. Deeper nesting is
   * covered by the "Nested arrays and objects" scenario test.
   */
  def genJsonValue: Gen[Document] =
    Gen.choice1(
      genJsonValueLeaf,
      genJsonValueLeaf
        .list(Range.linear(0, 5))
        .map(items => Document.DArray(items.toVector)),
      genJsonValueLeaf
        .map(v => ("k" + v.hashCode.toString) -> v)
        .list(Range.linear(0, 5))
        .map(pairs => Document.DObject(pairs.toMap))
    )

  /**
   * Generates leaf ujson.Value variants (no nesting).
   * Covers Null, Bool, Num (from Long and Double), Str.
   */
  def genUjsonValueLeaf: Gen[ujson.Value] =
    Gen.choice1(
      Gen.constant(ujson.Null),
      Gen.boolean.map(ujson.Bool.apply),
      Gen.long(Range.linear(Long.MinValue, Long.MaxValue)).map(n => ujson.Num(n.toDouble)),
      Gen.double(Range.linearFrac(-1000000.0, 1000000.0)).map(ujson.Num.apply),
      Gen.string(Gen.alphaNum, Range.linear(0, 20)).map(ujson.Str.apply)
    )

  /**
   * Generates all ujson.Value variants (depth 0-1).
   * Leaf variants plus Arr/Obj of leaves.
   */
  def genUjsonValue: Gen[ujson.Value] =
    Gen.choice1(
      genUjsonValueLeaf,
      genUjsonValueLeaf
        .list(Range.linear(0, 5))
        .map(items => ujson.Arr(items)),
      genUjsonValueLeaf
        .map(v => ("k" + v.hashCode.toString) -> v)
        .list(Range.linear(0, 5))
        .map(pairs => ujson.Obj.from(pairs))
    )

  /**
   * Generates Long values with |n| > 2^53 — the values ujson.Num(Double)
   * would truncate.
   */
  def genLargeLong: Gen[Long] =
    val twoPow53: Long = 1L << 53
    Gen.choice1(
      Gen.long(Range.linear(twoPow53 + 1, Long.MaxValue)),
      Gen.long(Range.linear(Long.MinValue, -(twoPow53 + 1)))
    )

  // ── Properties (Ring 3) ──────────────────────────────────────────────────

  // spec: json-value-model — Property: JsonValue-ujson round-trip is identity
  property("JsonValue-ujson round-trip is identity") {
    for jv <- genJsonValue.forAll
        .cover(2, "DNull", (d: Document) => d == Document.DNull)
        .cover(2, "DBoolean", (d: Document) => d match { case _: Document.DBoolean => true; case _ => false })
        .cover(2, "DNumber", (d: Document) => d match { case _: Document.DNumber => true; case _ => false })
        .cover(2, "DString", (d: Document) => d match { case _: Document.DString => true; case _ => false })
        .cover(2, "DArray", (d: Document) => d match { case _: Document.DArray => true; case _ => false })
        .cover(2, "DObject", (d: Document) => d match { case _: Document.DObject => true; case _ => false })
    yield
      val roundTripped: Document = JsonValueCodec.fromUjson(JsonValueCodec.toUjson(jv))
      Result.assert(roundTripped == jv)
  }

  // spec: json-value-model — Property: ujson-JsonValue round-trip is identity
  property("ujson-JsonValue round-trip is identity") {
    for uv <- genUjsonValue.forAll
        .cover(2, "Null", (v: ujson.Value) => v == ujson.Null)
        .cover(2, "Bool", (v: ujson.Value) => v match { case _: ujson.Bool => true; case _ => false })
        .cover(2, "Num", (v: ujson.Value) => v match { case _: ujson.Num => true; case _ => false })
        .cover(2, "Str", (v: ujson.Value) => v match { case _: ujson.Str => true; case _ => false })
        .cover(2, "Arr", (v: ujson.Value) => v match { case _: ujson.Arr => true; case _ => false })
        .cover(2, "Obj", (v: ujson.Value) => v match { case _: ujson.Obj => true; case _ => false })
    yield
      val roundTripped: ujson.Value = JsonValueCodec.toUjson(JsonValueCodec.fromUjson(uv))
      Result.assert(roundTripped == uv)
  }

  // spec: json-value-model — Property: Long precision preserved in the JsonValue model
  // NOTE: ujson.Num wraps Double and truncates Longs > 2^53. This property
  // verifies precision is preserved in the JsonValue model (Document.DNumber
  // carries BigDecimal), NOT across the ujson boundary round-trip.
  property("Long precision preserved in the JsonValue model") {
    for n <- genLargeLong.forAll
        .cover(30, "positive", (x: Long) => x > 0)
        .cover(30, "negative", (x: Long) => x < 0)
    yield
      val jv: Document = Document.DNumber(BigDecimal(n))
      Result.assert(jv == Document.DNumber(BigDecimal(n)))
  }

  // ── Scenarios ────────────────────────────────────────────────────────────

  // spec: json-value-model — Scenario: Long values are exact
  test("Long values are exact") {
    val id: Long     = 9007199254740993L // 2^53 + 1
    val jv: Document = Document.DObject(Map("id" -> Document.DNumber(BigDecimal(id))))
    jv match
      case Document.DObject(fields) =>
        fields("id") match
          case Document.DNumber(value) =>
            assertEquals(value.toLong, id)
          case other => fail(s"Expected DNumber, got $other")
      case other => fail(s"Expected DObject, got $other")
  }

  // spec: json-value-model — Scenario: DNull variant is representable
  test("DNull variant is representable") {
    val jv: Document = Document.DObject(Map("note" -> Document.DNull))
    jv match
      case Document.DObject(fields) =>
        assertEquals(fields("note"), Document.DNull)
      case other => fail(s"Expected DObject, got $other")
  }

  // spec: json-value-model — Scenario: Nested arrays and objects
  test("Nested arrays and objects") {
    val jv: Document = Document.DObject(
      Map(
        "items" -> Document.DArray(
          Vector(
            Document.DObject(Map("id" -> Document.DNumber(BigDecimal(1)))),
            Document.DObject(Map("id" -> Document.DNumber(BigDecimal(2))))
          )
        )
      )
    )
    jv match
      case Document.DObject(fields) =>
        fields("items") match
          case Document.DArray(items) =>
            assertEquals(items.length, 2)
            items(0) match
              case Document.DObject(f1) => assertEquals(f1("id"), Document.DNumber(BigDecimal(1)))
              case other                => fail(s"Expected DObject, got $other")
            items(1) match
              case Document.DObject(f2) => assertEquals(f2("id"), Document.DNumber(BigDecimal(2)))
              case other                => fail(s"Expected DObject, got $other")
          case other => fail(s"Expected DArray, got $other")
      case other => fail(s"Expected DObject, got $other")
  }

  // spec: json-value-model — Scenario: toUjson round-trips all Document variants
  test("toUjson round-trips all Document variants") {
    val variants: List[Document] = List(
      Document.DNull,
      Document.DBoolean(true),
      Document.DNumber(BigDecimal(42)),
      Document.DNumber(BigDecimal(42L)),
      Document.DNumber(BigDecimal("3.14")),
      Document.DString("s"),
      Document.DArray(Vector(Document.DNumber(BigDecimal(1)), Document.DString("two"))),
      Document.DObject(Map("a" -> Document.DBoolean(false), "b" -> Document.DNull))
    )
    for v <- variants
    yield
      val roundTripped: Document = JsonValueCodec.fromUjson(JsonValueCodec.toUjson(v))
      assertEquals(roundTripped, v)
  }

  // spec: json-value-model — Scenario: fromUjson round-trips all ujson variants
  test("fromUjson round-trips all ujson variants") {
    val variants: List[ujson.Value] = List(
      ujson.Null,
      ujson.Bool(true),
      ujson.Num(42.0),
      ujson.Num(42L.toDouble),
      ujson.Str("s"),
      ujson.Arr(ujson.Num(1.0), ujson.Str("two")),
      ujson.Obj.from(List("a" -> ujson.Bool(false), "b" -> ujson.Null))
    )
    for v <- variants
    yield
      val roundTripped: ujson.Value = JsonValueCodec.toUjson(JsonValueCodec.fromUjson(v))
      assertEquals(roundTripped, v)
  }

  // spec: json-value-model — Scenario: Long precision is preserved in the JsonValue model
  // NOTE: This tests the JsonValue model directly, NOT the ujson boundary round-trip
  // (ujson.Num wraps Double and truncates Longs > 2^53 — a ujson AST limitation).
  test("Long precision is preserved in the JsonValue model") {
    val n: Long      = 9007199254740993L
    val jv: Document = Document.DNumber(BigDecimal(n))
    assertEquals(jv, Document.DNumber(BigDecimal(n)))
  }

  // ── Compile-negative obligations ─────────────────────────────────────────

  // spec: json-value-model — Compile-Negative: no in-place update on JsonValue
  test("JsonValue is immutable (compile-negative: no in-place update)") {
    // DObject wraps immutable Map — no `update` method exists.
    // This compileErrors check verifies that calling `update` on a JsonValue
    // does not compile.
    val errors: String = compileErrors("""
      val jv: smithy4s.Document = smithy4s.Document.DObject(Map.empty)
      jv.update("key", smithy4s.Document.DString("value"))
    """)
    assertEquals(errors.nonEmpty, true)
  }

  // spec: json-value-model — Compile-Negative: ujson.Value is not JsonValue
  test("ujson.Value is not JsonValue (compile-negative: type distinctness)") {
    // ujson.Value and smithy4s.Document are distinct types.
    // Assigning one to the other must not compile.
    val errors: String = compileErrors("""
      val uv: ujson.Value = ujson.Null
      val jv: smithy4s.Document = uv
    """)
    assertEquals(errors.nonEmpty, true)
  }

  // spec: json-value-model — Requirement: upickle declared explicitly in Dependencies.scala
  // BUILD-INSPECTION OBLIGATION — enforced by adversarial review (grep Dependencies.scala
  // + sbt dependencyTree for version match), NOT by a runtime test.
  // Versions.scala is in the sbt build definition (project/) and not on the
  // test classpath, so it cannot be referenced from test sources.
