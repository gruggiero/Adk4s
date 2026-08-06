package org.adk4s.core.json

import smithy4s.Document

/**
 * ADK4S's own internal, immutable JSON value type.
 *
 * A type alias for `smithy4s.Document` — the immutable, exact-on-Long
 * and `BigDecimal` AST already on the classpath via `smithy4s-json`.
 * Replaces `ujson.Value` everywhere except the llm4s boundary
 * (`org.adk4s.core.tools`, where `JsonValueCodec` converts at the edge).
 *
 * All `Document` variants are case classes or case objects:
 * - `DNull` — null (first-class value, not absence)
 * - `DBoolean(b)` — boolean
 * - `DNumber(BigDecimal)` — exact numeric (no `Double`-only truncation)
 * - `DString(s)` — string
 * - `DArray(Vector[Document])` — immutable array
 * - `DObject(Map[String, Document])` — immutable object
 *
 * No field can be updated in place — the `Map` and `Vector` are immutable.
 *
 * spec: json-value-model — Requirement: JsonValue is an immutable type alias over smithy4s.Document
 */
type JsonValue = Document
