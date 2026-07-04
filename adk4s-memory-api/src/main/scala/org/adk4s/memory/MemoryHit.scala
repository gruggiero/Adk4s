package org.adk4s.memory

import java.time.Instant

/**
 * A single recalled fact.
 *
 * `text` is the agent-facing rendering (e.g. "Alice works at Meta") suitable
 * for splicing into a prompt. `validFrom`/`validTo` expose the temporal window
 * when the backend tracks it (both `None` for non-temporal backends).
 * `provenance` points back to the episode/group that asserted the fact.
 */
final case class MemoryHit(
  text: String,
  score: Double,
  validFrom: Option[Instant] = None,
  validTo: Option[Instant] = None,
  provenance: Option[String] = None,
  payload: Map[String, String] = Map.empty
)

/**
 * Optional point-in-time scoping for recall: "what did the agent know / what
 * was true as of `asOf`". Backends without temporal support MUST ignore this
 * rather than fail.
 */
final case class TemporalScope(asOf: Instant)
