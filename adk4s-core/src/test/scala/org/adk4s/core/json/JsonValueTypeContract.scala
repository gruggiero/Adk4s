package org.adk4s.core.json

import smithy4s.Document

/**
 * Typed contract / permanent conformance test for the json-value-model spec.
 *
 * After Step 3 promotion, this file is a permanent conformance test:
 * eta-expanded signature pins verify the public API stays exactly as
 * approved. Any later signature drift breaks `Test/compile`.
 *
 * spec: json-value-model
 */

// ── Conformance pins: JsonValue type alias ─────────────────────────────────

/**
 * Pin: JsonValue must be a type alias for smithy4s.Document.
 * If the alias is changed or removed, this pin fails to compile.
 */
val jsonValueIsDocument: Document = ??? : JsonValue

// ── Conformance pins: JsonValueCodec signatures ────────────────────────────

/** Pin: JsonValueCodec.toUjson has signature `JsonValue => ujson.Value`. */
val toUjsonSig: JsonValue => ujson.Value = JsonValueCodec.toUjson

/** Pin: JsonValueCodec.fromUjson has signature `ujson.Value => JsonValue`. */
val fromUjsonSig: ujson.Value => JsonValue = JsonValueCodec.fromUjson

// ── Compile-negative: no in-place update on JsonValue ──────────────────────
// JsonValue is smithy4s.Document (immutable). DObject wraps immutable Map,
// DArray wraps immutable IndexedSeq. There is no `update` method.
// The following would NOT compile if uncommented (DObject has no update):
//   val jv: JsonValue = Document.DObject(Map.empty)
//   jv.update("key", Document.DString("value")) // does not exist

// ── Compile-negative: ujson.Value is NOT JsonValue ─────────────────────────
// ujson.Value and JsonValue are distinct types. The following would NOT compile:
//   val uv: ujson.Value = ujson.Null
//   val jv: JsonValue = uv // type mismatch: ujson.Value does not conform to smithy4s.Document

// ── Property obligations (structured comments) ─────────────────────────────
// Property: JsonValue-ujson round-trip is identity
//   forAll { (jv: JsonValue) => JsonValueCodec.fromUjson(JsonValueCodec.toUjson(jv)) == jv }
// Property: ujson-JsonValue round-trip is identity
//   forAll { (uv: ujson.Value) => JsonValueCodec.toUjson(JsonValueCodec.fromUjson(uv)) == uv }
// Property: Long precision preserved across boundary
//   forAll { (n: Long) =>
//     val jv: JsonValue = Document.DNumber(BigDecimal(n))
//     JsonValueCodec.fromUjson(JsonValueCodec.toUjson(jv)) == jv }
// Property: JsonValue is immutable (compile-time, enforced by compile-negative)
