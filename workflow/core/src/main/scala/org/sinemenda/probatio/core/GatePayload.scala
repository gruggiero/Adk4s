package org.sinemenda.probatio.core

import upickle.default.*

/**
 * The hook JSON payload (ex-`gate-hookjson-contract.jq`).
 *
 * Byte-stable (§4.6). The `hookSpecificOutput` object carries the event
 * name and optional additional context.
 *
 * spec: probatio-core — Requirement: (GatePayload byte-stable)
 */
final case class HookSpecificOutput(
  hookEventName: String,
  additionalContext: Option[String] = None
) derives ReadWriter

final case class GatePayload(
  hookSpecificOutput: HookSpecificOutput
) derives ReadWriter
