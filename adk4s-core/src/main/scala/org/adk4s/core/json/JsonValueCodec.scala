package org.adk4s.core.json

import smithy4s.Document

/**
 * The single, explicit conversion point between `JsonValue`
 * (= `smithy4s.Document`) and `ujson.Value` at the llm4s boundary.
 *
 * `ujson.Value` is the type at the `org.llm4s.toolapi` edge
 * (`ToolFunction`, `ToolRegistry`, tool `run`/`runStream` signatures).
 * It must remain the type there. Everywhere else in ADK4S, `JsonValue`
 * is the type. This adapter is the single conversion point so an llm4s
 * upgrade that reshapes upickle usage breaks at most one file, not
 * every persisted/public type signature.
 *
 * These are the ONLY functions in the codebase that convert between
 * `JsonValue` and `ujson.Value`. The Scalafix `NoUjsonOutsideBoundary`
 * rule confines `ujson.Value` to `org.adk4s.core.json` and
 * `org.adk4s.core.tools`.
 *
 * == Number precision ==
 *
 * `ujson.Num` wraps `Double` and truncates Longs above 2^53. This is a
 * limitation of the ujson AST (`ujson.Value` is sealed; `ujson.Num` only
 * accepts `Double`). The adapter routes all numbers through
 * `ujson.Num(Double)`, so Long precision MAY be lost when crossing the
 * ujson boundary. It is the responsibility of the ujson library user to
 * not insert large Long values into `ujson.Num` if they need exact
 * precision — the `JsonValue` model (`smithy4s.Document.DNumber(BigDecimal)`)
 * preserves precision on the ADK4S side.
 *
 * spec: json-value-model — Requirement: JsonValueCodec is the single boundary adapter at the llm4s edge
 */
object JsonValueCodec:

  /**
   * Convert a `JsonValue` (smithy4s.Document) to `ujson.Value`.
   *
   * Numbers are routed through `ujson.Num(Double)`. Long precision MAY
   * be lost for values above 2^53 (ujson AST limitation — see class doc).
   */
  def toUjson(jv: JsonValue): ujson.Value = jv match
    case Document.DNull         => ujson.Null
    case Document.DBoolean(b)   => ujson.Bool(b)
    case Document.DNumber(n)    => ujson.Num(n.toDouble)
    case Document.DString(s)    => ujson.Str(s)
    case Document.DArray(items) => ujson.Arr.from(items.map(toUjson))
    case Document.DObject(fields) =>
      ujson.Obj.from(fields.view.map { case (k, v) => k -> toUjson(v) })

  /**
   * Convert a `ujson.Value` to `JsonValue` (smithy4s.Document).
   *
   * `ujson.Num`'s `Double` maps to `DNumber(BigDecimal(double))` which
   * is exact for the double value (preserves the exact double).
   */
  def fromUjson(uv: ujson.Value): JsonValue = uv match
    case ujson.Null     => Document.DNull
    case ujson.Bool(b)  => Document.DBoolean(b)
    case ujson.Num(n)   => Document.DNumber(BigDecimal(n))
    case ujson.Str(s)   => Document.DString(s)
    case ujson.Arr(arr) => Document.DArray(arr.map(fromUjson).toVector)
    case ujson.Obj(obj) =>
      Document.DObject(obj.view.map { case (k, v) => k -> fromUjson(v) }.toMap)
