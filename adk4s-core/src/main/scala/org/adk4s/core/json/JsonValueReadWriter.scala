package org.adk4s.core.json

import upickle.default.*
import smithy4s.Document

/** upickle `ReadWriter` for `JsonValue` (= `smithy4s.Document`).
  *
  * Bridges `JsonValue` through `ujson.Value` via `JsonValueCodec` so that
  * `derives ReadWriter` on types containing `JsonValue` fields (e.g.
  * `InterruptSignal.Stateful.state`) produces wire-format-compatible JSON.
  *
  * The wire format is identical to what upickle produces for the equivalent
  * `ujson.Value` — the conversion is transparent to serialization consumers.
  *
  * spec: agent-interrupt-resume — Requirement: InterruptSignal sealed trait hierarchy
  */
given ReadWriter[JsonValue] =
  summon[ReadWriter[ujson.Value]]
    .bimap[JsonValue](JsonValueCodec.toUjson, JsonValueCodec.fromUjson)
